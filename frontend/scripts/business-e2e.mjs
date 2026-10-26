/** 功能：独占MySQL/Redis验证目录→角色→员工→OA/PMIS真实页面闭环；不读取开发库或保存凭据。 */
import { createRequire } from "node:module";
import { createServer, request as httpRequest } from "node:http";
import { spawn, execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
const root = fileURLToPath(new URL("../..", import.meta.url));
const out = path.join(root, "reports/ui");
fs.mkdirSync(out, { recursive: true });
const require = createRequire(import.meta.resolve("@mermaid-js/mermaid-cli"));
const puppeteer = require("puppeteer");
const suffix = `${process.pid}-${Date.now()}`;
const mysql = `ooad-rbac-ui-mysql-${suffix}`,
  redis = `ooad-rbac-ui-redis-${suffix}`;
const password = randomBytes(24).toString("hex");
const owned = [],
  checks = [],
  failures = [];
let backend, browser, server, page, base;
const docker = (...args) =>
  execFileSync("docker", args, {
    encoding: "utf8",
    windowsHide: true,
    env: {
      ...process.env,
      MYSQL_ROOT_PASSWORD: password,
      MYSQL_PWD: password,
      MYSQL_DATABASE: "rbac_ui",
    },
    stdio: ["ignore", "pipe", "pipe"],
  }).trim();
async function until(fn, label, timeout = 120000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    try {
      const result = await fn();
      if (result) return result;
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw Error(`Timeout: ${label}`);
}
async function freePort() {
  const socket = createServer();
  await new Promise((resolve) => socket.listen(0, "127.0.0.1", resolve));
  const port = socket.address().port;
  await new Promise((resolve) => socket.close(resolve));
  return port;
}
async function click(text, scope = "body", tag = "button") {
  await page.bringToFront();
  await page.waitForFunction(
    (text, scope, tag) =>
      [...document.querySelectorAll(`${scope} ${tag}`)].some(
        (b) =>
          b.textContent.trim() === text &&
          !b.disabled &&
          b.getBoundingClientRect().width,
      ),
    {},
    text,
    scope,
    tag,
  );
  await page.evaluate(
    (text, scope, tag) =>
      [...document.querySelectorAll(`${scope} ${tag}`)]
        .find(
          (b) =>
            b.textContent.trim() === text &&
            !b.disabled &&
            b.getBoundingClientRect().width,
        )
        .click(),
    text,
    scope,
    tag,
  );
}
async function fill(label, value, selector = "dialog[open]") {
  await page.waitForFunction(
    (label, selector) =>
      [...document.querySelectorAll(`${selector} label`)].some(
        (l) =>
          l.childNodes[0]?.textContent.trim() === label &&
          l.querySelector("input,select,textarea"),
      ),
    {},
    label,
    selector,
  );
  const handle = await page.evaluateHandle(
    (label, selector) =>
      [...document.querySelectorAll(`${selector} label`)]
        .find((l) => l.childNodes[0]?.textContent.trim() === label)
        ?.querySelector("input,select,textarea"),
    label,
    selector,
  );
  assert.ok(await handle.asElement(), `field ${label}`);
  await handle.asElement().evaluate((e, value) => {
    e.value = value;
    e.dispatchEvent(
      new Event(e.tagName === "SELECT" ? "change" : "input", { bubbles: true }),
    );
  }, value);
  await handle.dispose();
}
async function selectLabel(aria, text) {
  await page.bringToFront();
  await page.waitForFunction(
    (aria, text) => {
      const select = document.querySelector(`select[aria-label="${aria}"]`);
      return (
        select && [...select.options].some((o) => o.textContent.includes(text))
      );
    },
    { polling: 100 },
    aria,
    text,
  );
  const value = await page.$eval(
    `select[aria-label="${aria}"]`,
    (s, text) => [...s.options].find((o) => o.textContent.includes(text)).value,
    text,
  );
  await page.select(`select[aria-label="${aria}"]`, value);
  return value;
}
async function api(url, method = "GET", body) {
  return page.evaluate(
    async ({ url, method, body }) => {
      const headers = {};
      if (method !== "GET") {
        const csrf = await fetch("/api/v1/auth/csrf").then((r) => r.json());
        headers[csrf.data.headerName] = csrf.data.token;
        headers["Content-Type"] = "application/json";
      }
      const r = await fetch(`/api/v1${url}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      return { status: r.status, body: await r.json() };
    },
    { url, method, body },
  );
}
async function read(url) {
  const r = await api(url);
  assert.equal(r.status, 200, `GET ${url}`);
  return r.body.data;
}
async function navigate(route) {
  await page.goto(`${base}/admin/${route}`, { waitUntil: "networkidle0" });
  await page.waitForSelector('.live-admin select[aria-label="管理应用"]');
  await page.waitForNetworkIdle();
}
async function login(username, password) {
  await page.goto(`${base}/admin/login`, { waitUntil: "networkidle0" });
  await page.type("#username", username);
  await page.type("#password", password);
  await click("登录", ".login-panel");
  await page.waitForSelector(".live-admin");
  await page.waitForNetworkIdle();
}
async function closed() {
  await page.waitForFunction(() => !document.querySelector("dialog[open]"));
  await page.waitForNetworkIdle();
}
async function closeDialog() {
  // 保存结果会改变模态高度；等待按钮可用且位置稳定后再点击，避免CI误点旧坐标。
  await page.locator('dialog[open] button[aria-label="关闭表单"]').click();
  await closed();
}
async function shot(name) {
  await page.screenshot({
    path: path.join(out, `${name}.png`),
    fullPage: true,
  });
}
function pass(name) {
  checks.push(name);
  console.log(`PASS ${name}`);
}
try {
  docker(
    "run",
    "--detach",
    "--rm",
    "--name",
    mysql,
    "--publish",
    "127.0.0.1::3306",
    "--env",
    "MYSQL_ROOT_PASSWORD",
    "--env",
    "MYSQL_DATABASE",
    "mysql:8.4.7",
  );
  owned.push(mysql);
  docker(
    "run",
    "--detach",
    "--rm",
    "--name",
    redis,
    "--publish",
    "127.0.0.1::6379",
    "redis:7.4.6-alpine",
  );
  owned.push(redis);
  const dbPort = docker("port", mysql, "3306/tcp").split(":").at(-1),
    redisPort = docker("port", redis, "6379/tcp").split(":").at(-1);
  await until(
    () =>
      docker(
        "exec",
        "--env",
        "MYSQL_PWD",
        mysql,
        "mysqladmin",
        "-h127.0.0.1",
        "ping",
        "--silent",
      ).includes("alive"),
    "MySQL startup",
  );
  const port = await freePort();
  const log = fs.openSync(path.join(out, "business-e2e-backend.log"), "w");
  backend = spawn(
    "java",
    [
      "-jar",
      path.join(root, "backend/target/rbac-workbench-0.1.0-SNAPSHOT.jar"),
    ],
    {
      cwd: root,
      windowsHide: true,
      stdio: ["ignore", log, log],
      env: {
        ...process.env,
        DB_URL: `jdbc:mysql://127.0.0.1:${dbPort}/rbac_ui?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`,
        DB_USERNAME: "root",
        DB_PASSWORD: password,
        REDIS_HOST: "127.0.0.1",
        REDIS_PORT: redisPort,
        REDIS_PASSWORD: "",
        SERVER_PORT: String(port),
        SPRING_PROFILES_ACTIVE: "demo",
        BOOTSTRAP_ADMIN_USERNAME: "",
        BOOTSTRAP_ADMIN_PASSWORD: "",
        DEMO_RETIRED_USERNAME: "",
      },
    },
  );
  fs.closeSync(log);
  await until(
    async () => (await fetch(`http://127.0.0.1:${port}/api/v1/health`)).ok,
    "backend startup",
  );
  // 健康端点可早于ApplicationRunner事务提交；六个合成账号就绪后才登录。
  await until(
    () =>
      Number(
        docker(
          "exec",
          "--env",
          "MYSQL_PWD",
          mysql,
          "mysql",
          "-h127.0.0.1",
          "-uroot",
          "-Drbac_ui",
          "-N",
          "-e",
          "SELECT COUNT(*) FROM id_user WHERE username IN ('super-admin','oa-admin','pmis-admin','oa-user-1','pmis-user-1','employee-1')",
        ),
      ) === 6,
    "demo initialization committed",
  );
  server = createServer((req, res) => {
    if (req.url.startsWith("/api/")) {
      const proxy = httpRequest(
        {
          hostname: "127.0.0.1",
          port,
          path: req.url,
          method: req.method,
          headers: { ...req.headers, host: `127.0.0.1:${port}` },
        },
        (upstream) => {
          res.writeHead(upstream.statusCode, upstream.headers);
          upstream.pipe(res);
        },
      );
      proxy.on("error", () => {
        res.writeHead(502);
        res.end();
      });
      req.pipe(proxy);
      return;
    }
    const pathname = decodeURIComponent(
        new URL(req.url, "http://localhost").pathname,
      ),
      match = pathname.match(/^\/(admin|oa|pmis)\/(.*)$/);
    if (!match) {
      res.writeHead(404);
      res.end();
      return;
    }
    const directory = path.join(root, `frontend/apps/${match[1]}/dist`);
    let file = path.resolve(directory, match[2]);
    if (!file.startsWith(directory + path.sep) && file !== directory) {
      res.writeHead(404);
      res.end();
      return;
    }
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory())
      file = path.join(directory, "index.html");
    res.setHeader(
      "Content-Type",
      {
        ".html": "text/html; charset=utf-8",
        ".js": "text/javascript",
        ".css": "text/css",
      }[path.extname(file)] ?? "application/octet-stream",
    );
    fs.createReadStream(file).pipe(res);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
  browser = await puppeteer.launch({
    headless: true,
    executablePath:
      process.env.CHROME_PATH ??
      (process.platform === "win32"
        ? "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
        : undefined),
    args: ["--no-sandbox"],
  });
  page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 1000 });
  page.on("pageerror", (error) => failures.push(error.message));
  page.on("dialog", (dialog) => dialog.accept());
  await login("super-admin", "1234");
  const platformPage = page;
  async function write(url, body, expected = 201, method = "POST") {
    const result = await api(url, method, body);
    assert.equal(
      result.status,
      expected,
      `${method} ${url}: ${JSON.stringify(result.body)}`,
    );
    return result.body.data;
  }
  const company = await write("/admin/organizations", {
    code: "e2e-company",
    name: "闭环演示总公司",
    nodeType: "HEADQUARTERS",
    parentId: null,
  });
  const department = await write("/admin/organizations", {
    code: "e2e-department",
    name: "闭环业务部",
    nodeType: "DEPARTMENT",
    parentId: company.id,
  });
  const actors = {};
  for (const app of ["oa", "pmis"])
    for (const kind of ["employee", "approver", "other"]) {
      const username = `${app}-${kind}`;
      const person = await write("/admin/employees", {
        employeeCode: username,
        displayName: `合成${username}`,
        organizationId: department.id,
        positionName: "测试岗位",
        phone: null,
      });
      const credential = await write("/admin/accounts", {
        username,
        employeeId: person.id,
      });
      assert.equal(credential.temporaryPassword, "1234");
      actors[username] = credential.account;
    }
  async function application(app, route = "applications") {
    page = platformPage;
    await navigate(route);
    await selectLabel("管理应用", app === "oa" ? "OA公文" : "工程项目");
    await page.waitForNetworkIdle();
  }
  async function businessLogin(app, username) {
    const context = await browser.createBrowserContext();
    const businessPage = await context.newPage();
    await businessPage.setViewport({ width: 1440, height: 1000 });
    businessPage.on("pageerror", (e) => failures.push(e.message));
    businessPage.on("dialog", (dialog) => dialog.accept());
    page = businessPage;
    await page.goto(`${base}/${app}/login`, { waitUntil: "networkidle0" });
    await page.type("#username", username);
    await page.type("#password", "1234");
    await click("登录", ".login-panel");
    await page.waitForSelector(".business-workspace");
    await page.waitForNetworkIdle();
    return { context, page: businessPage };
  }
  async function permissionRow(app, code, button) {
    await page.waitForFunction(
      (code, button) =>
        [...document.querySelectorAll(".catalog-detail tbody tr")].some(
          (r) =>
            r.textContent.includes(code) &&
            [...r.querySelectorAll("button")].some(
              (b) => b.textContent.trim() === button && !b.disabled,
            ),
        ),
      {},
      code,
      button,
    );
    await page.evaluate(
      (code, button) =>
        [...document.querySelectorAll(".catalog-detail tbody tr")]
          .find((r) => r.textContent.includes(code))
          ?.querySelectorAll("button")
          .forEach((b) => {
            if (b.textContent.trim() === button) b.click();
          }),
      code,
      button,
    );
  }
  const roles = {};
  for (const app of ["oa", "pmis"]) {
    const appId = app === "oa" ? "2" : "3",
      resource = app === "oa" ? "document" : "project",
      noun = app === "oa" ? "公文" : "项目";
    await application(app);
    await click("资源权限目录");
    await page.waitForSelector(".resource-catalog");
    await page.waitForNetworkIdle();
    await click("新增资源");
    await fill("资源编码", `e2e-${app}-directory`);
    await fill("资源名称", `${noun}功能分组`);
    await fill("资源类型", "DIRECTORY");
    await click("保存目录配置", "dialog[open]");
    await closed();
    let catalog = await read(`/admin/applications/${appId}/catalog`);
    const folder = catalog.resources.find(
        (r) => r.code === `e2e-${app}-directory`,
      ),
      functionNode = catalog.resources.find((r) => r.code === resource);
    await page.evaluate(
      (code) =>
        [...document.querySelectorAll(".catalog-tree > button")]
          .find((b) => b.textContent.includes(code))
          .click(),
      resource,
    );
    await click("编辑资源");
    await fill("上级目录", folder.id);
    await fill("资源名称", `${noun}办理`);
    await click("保存目录配置", "dialog[open]");
    await closed();
    const actions =
      app === "oa"
        ? [
            "create",
            "update",
            "submit",
            "withdraw",
            "approve",
            "reject",
            "void",
          ]
        : [
            "create",
            "update",
            "submit",
            "withdraw",
            "approve",
            "reject",
            "cancel",
          ];
    for (const action of actions) {
      await click("新增权限点");
      await fill("功能资源", functionNode.id);
      await fill("权限动作", action);
      await fill("权限名称", `${noun}-${action}`);
      await fill("权限说明", `闭环测试 ${action}`);
      await click("保存目录配置", "dialog[open]");
      await closed();
    }
    await permissionRow(app, `${app}:${resource}:view`, "编辑权限点");
    await fill("权限名称", `查看${noun}`);
    await click("保存目录配置", "dialog[open]");
    await closed();
    catalog = await read(`/admin/applications/${appId}/catalog`);
    assert.equal(
      catalog.permissions.filter(
        (p) =>
          p.code.startsWith(`${app}:${resource}:`) &&
          p.implementationStatus === "IMPLEMENTED",
      ).length,
      8,
    );
    pass(`${app}页面资源分组/移动及已实现权限点新增编辑`);
    for (const kind of ["employee", "approver"]) {
      await application(app, "roles");
      await click("新建角色");
      const code = `e2e-${app}-${kind}`;
      await fill("角色编码", code);
      await fill("角色名称", `${app}-${kind}测试角色`);
      await click("保存角色", "dialog[open]");
      await closed();
      const role = (
        await read(`/admin/applications/${appId}/roles?page=1&size=20`)
      ).items.find((r) => r.code === code);
      roles[`${app}-${kind}`] = role;
      await click("权限配置");
      const selected =
        kind === "employee"
          ? [
              "view",
              "create",
              "update",
              "submit",
              "withdraw",
              app === "oa" ? "void" : "cancel",
            ]
          : ["view", "approve", "reject"];
      for (const action of selected) {
        const permissionCode = `${app}:${resource}:${action}`;
        await page.evaluate(
          (code) =>
            [...document.querySelectorAll(".permission-item")]
              .find((r) => r.textContent.includes(code))
              .querySelector('input[type="checkbox"]')
              .click(),
          permissionCode,
        );
        await page.waitForFunction(
          (code) =>
            [...document.querySelectorAll(".permission-item")]
              .find((r) => r.textContent.includes(code))
              .querySelector('select[aria-label="范围类型"]'),
          {},
          permissionCode,
        );
        await page.evaluate(
          (code, kind, org) => {
            const row = [...document.querySelectorAll(".permission-item")].find(
                (r) => r.textContent.includes(code),
              ),
              select = row.querySelector('select[aria-label="范围类型"]');
            select.value = kind;
            select.dispatchEvent(new Event("change", { bubbles: true }));
          },
          permissionCode,
          kind === "employee" ? "SELF" : "ORG_SUBTREE",
          department.id,
        );
        if (kind === "approver") {
          await page.waitForFunction(
            (code) =>
              [...document.querySelectorAll(".permission-item")]
                .find((r) => r.textContent.includes(code))
                .querySelector(".scope-editor .check-list"),
            {},
            permissionCode,
          );
          await page.evaluate(
            (code) =>
              [...document.querySelectorAll(".permission-item")]
                .find((r) => r.textContent.includes(code))
                .querySelectorAll(".scope-editor .check-list label")
                .forEach((l) => {
                  if (l.textContent.includes("闭环业务部"))
                    l.querySelector("input").click();
                }),
            permissionCode,
          );
        }
      }
      await click("预览权限变更");
      await page.waitForSelector(".preview-box");
      await click("确认保存", ".preview-box");
      await page.waitForNetworkIdle();
      assert.equal(
        (
          await read(
            `/admin/applications/${appId}/roles/${role.id}/permissions`,
          )
        ).permissions.length,
        selected.length,
      );
      pass(
        `${app}-${kind}页面角色权限及${kind === "employee" ? "SELF" : "ORG_SUBTREE"}范围预览确认`,
      );
      await click("角色成员");
      for (const username of kind === "employee"
        ? [`${app}-employee`, `${app}-other`]
        : [`${app}-approver`]) {
        await click("添加成员");
        await selectLabel("选择成员账号", username);
        await click("确认添加成员", "dialog[open]");
        await closed();
        assert.ok(
          (
            await read(
              `/admin/applications/${appId}/users/${actors[username].id}/effective-permissions`,
            )
          ).permissions.length > 0,
        );
      }
      pass(`${app}-${kind}页面给合成业务人员显式授予角色`);
    }
    const author = await businessLogin(app, `${app}-employee`);
    const makeDraft = async (title) => {
      await click(`新建${noun}`);
      await fill(app === "oa" ? "公文标题" : "项目名称", title);
      if (app === "oa")
        await fill("公文正文", "合成公文正文，用于权限闭环验证。");
      else {
        await fill("立项说明", "合成工程项目立项说明");
        await fill("计划开始", "2026-10-26");
        await fill("计划结束", "2026-11-26");
        await fill("项目预算（元）", "100000.50");
      }
      await click(`保存${noun}草稿`, "dialog[open]");
      await closed();
      return (await read(`/${app}/${app === "oa" ? "documents" : "projects"}`))
        .items[0];
    };
    const collection = `/${app}/${app === "oa" ? "documents" : "projects"}`;
    let draft = await makeDraft(`${noun}闭环测试`);
    const id = draft.id;
    await click(`编辑${noun}`);
    await fill(app === "oa" ? "公文标题" : "项目名称", `${noun}已修改`);
    await click(`保存${noun}草稿`, "dialog[open]");
    await closed();
    assert.equal(
      (await read(`${collection}/${id}`))[app === "oa" ? "title" : "name"],
      `${noun}已修改`,
    );
    pass(`${app}真实页面新建/编辑草稿及持久化回读`);
    async function submit() {
      await click("提交审批");
      await fill("指定审批人", actors[`${app}-approver`].id);
      await click("确认提交审批", "dialog[open]");
      await closed();
    }
    await submit();
    assert.equal((await read(`${collection}/${id}`)).status, "SUBMITTED");
    const reviewer = await businessLogin(app, `${app}-approver`);
    await click(`查看${noun}`);
    await page.waitForNetworkIdle();
    await click(`驳回${noun}`);
    await fill("审批意见", "请补充说明后重新提交");
    await click("确认驳回", "dialog[open]");
    await closed();
    assert.equal((await read(`${collection}/${id}`)).status, "REJECTED");
    page = author.page;
    await click("刷新业务数据");
    await page.waitForNetworkIdle();
    await submit();
    page = reviewer.page;
    await click("刷新业务数据");
    await page.waitForNetworkIdle();
    await click("审批通过");
    await fill("审批意见", "同意办理");
    await click("确认通过", "dialog[open]");
    await closed();
    assert.equal((await read(`${collection}/${id}`)).status, "APPROVED");
    assert.ok(
      (await read(`${collection}/${id}/history`)).items.some(
        (e) => e.action === "REJECT",
      ),
    );
    pass(`${app}指定审批人页面驳回/重提/通过及真实办理历史`);
    page = author.page;
    await click("刷新业务数据");
    await page.waitForNetworkIdle();
    const secondDraft = await makeDraft(`${noun}撤回测试`);
    await submit();
    await click(`撤回${noun}`);
    await page.waitForNetworkIdle();
    await click(`${app === "oa" ? "作废" : "取消"}${noun}`);
    await page.waitForNetworkIdle();
    assert.equal(
      (await read(`${collection}/${secondDraft.id}`)).status,
      app === "oa" ? "VOIDED" : "CANCELLED",
    );
    pass(`${app}页面撤回与草稿终止，保留历史`);
    const other = await businessLogin(app, `${app}-other`);
    const otherDraft = await makeDraft(`${noun}其他人员记录`);
    page = author.page;
    assert.equal((await api(`${collection}/${otherDraft.id}`)).status, 404);
    assert.equal((await read(collection)).total, 2);
    assert.equal(
      (await api(`/${app === "oa" ? "pmis/projects" : "oa/documents"}`)).status,
      403,
    );
    pass(`${app}同应用SELF隔离、详情404及跨应用403`);
    await page.setViewport({ width: 390, height: 844 });
    await shot(`business-${app}-mobile`);
    assert.ok(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    );
    await page.setViewport({ width: 1440, height: 1000 });
    await shot(`business-${app}-desktop`);
    pass(`${app}真实业务桌面/手机布局`);
    await application(app);
    await click("资源权限目录");
    await page.waitForNetworkIdle();
    const viewCode = `${app}:${resource}:view`;
    await permissionRow(app, viewCode, "停用权限点");
    await page.waitForSelector(".catalog-impact");
    await click("确认目录启停", "dialog[open]");
    await closed();
    page = author.page;
    assert.equal((await api(collection)).status, 403);
    page = platformPage;
    await permissionRow(app, viewCode, "启用权限点");
    await page.waitForSelector(".catalog-impact");
    await click("确认目录启停", "dialog[open]");
    await closed();
    page = author.page;
    assert.equal((await api(collection)).status, 200);
    pass(`${app}目录页面启停影响预览后同一会话访问即时变化`);
    page = platformPage;
    const userPath = `/admin/applications/${appId}/users/${actors[`${app}-employee`].id}/roles`;
    const current = await read(userPath);
    const preview = await write(
      userPath + "/preview",
      { expectedVersion: current.version, memberships: [] },
      200,
    );
    await write(userPath, { previewToken: preview.token }, 200, "PUT");
    page = author.page;
    assert.equal((await api(collection)).status, 403);
    await click("刷新业务数据");
    await page.waitForNetworkIdle();
    assert.match(
      await page.$eval("main", (e) => e.textContent),
      /当前没有查看本业务的有效权限/,
    );
    pass(`${app}员工撤角后同一会话业务拒绝且页面刷新联动`);
    await author.context.close();
    await reviewer.context.close();
    await other.context.close();
    page = platformPage;
  }
  // 只修改本脚本独占容器中的合成账号，验证真实强改密界面而不改变演示默认策略。
  const forceUser = actors["oa-other"].id;
  assert.match(forceUser, /^[1-9][0-9]*$/);
  docker(
    "exec",
    "--env",
    "MYSQL_PWD",
    mysql,
    "mysql",
    "-h127.0.0.1",
    "-uroot",
    "-Drbac_ui",
    "-e",
    `UPDATE id_user SET force_password_change=TRUE,auth_version=auth_version+1 WHERE id=${forceUser}`,
  );
  const forceContext = await browser.createBrowserContext();
  page = await forceContext.newPage();
  page.on("pageerror", (e) => failures.push(e.message));
  await page.goto(`${base}/oa/login`, { waitUntil: "networkidle0" });
  await page.type("#username", "oa-other");
  await page.type("#password", "1234");
  await click("登录", ".login-panel");
  await page.waitForFunction(() =>
    document.querySelector("main")?.textContent.includes("首次登录请修改密码"),
  );
  const replacement = "case-" + randomBytes(16).toString("hex");
  await fill("当前密码", "1234", "main");
  await fill("新密码", replacement, "main");
  await click("修改密码并重新登录", ".record-form");
  await page.waitForSelector(".login-panel");
  assert.equal((await api("/oa/context")).status, 401);
  await page.type("#username", "oa-other");
  await page.type("#password", replacement);
  await click("登录", ".login-panel");
  await page.waitForSelector(".business-workspace");
  await page.waitForNetworkIdle();
  await forceContext.close();
  page = platformPage;
  pass("业务首次强改密页面、旧身份失效与新口令重新登录");
  assert.deepEqual(failures, []);
  fs.writeFileSync(
    path.join(out, "business-e2e.json"),
    JSON.stringify(
      {
        status: "PASS",
        checks,
        browserErrors: failures,
        storage:
          "dedicated ephemeral MySQL/Redis; no development database writes",
      },
      null,
      2,
    ),
  );
  console.log(`Resource/business real UI E2E PASS: ${checks.length} scenarios`);
} catch (error) {
  console.error(error.stack);
  if (page && !page.isClosed())
    console.error(
      await page.$$eval('select[aria-label], [role="alert"]', (nodes) =>
        nodes.map((n) => ({
          label: n.getAttribute("aria-label") ?? "alert",
          text: n.textContent.trim(),
        })),
      ),
    );
  // 失败截图避开凭据弹窗；不输出页面/请求体中的密码、Cookie或预览令牌。
  if (page && !page.isClosed() && !(await page.$('input[type="password"]')))
    await shot("business-e2e-failure").catch(() => {});
  fs.writeFileSync(
    path.join(out, "business-e2e.json"),
    JSON.stringify(
      {
        status: "FAIL",
        checks,
        message: error.message,
        browserErrors: failures,
      },
      null,
      2,
    ),
  );
  process.exitCode = 1;
} finally {
  if (browser) await browser.close();
  if (server) await new Promise((resolve) => server.close(resolve));
  if (backend && backend.exitCode === null) {
    backend.kill();
    await new Promise((resolve) => {
      backend.once("exit", resolve);
      setTimeout(resolve, 5000);
    });
  }
  for (const name of owned.reverse()) {
    try {
      docker("stop", name);
    } catch {
      console.error(`Cleanup required: docker stop ${name}`);
      process.exitCode = 1;
    }
  }
}
