/** 功能：用独占MySQL/Redis和打包后端验证管理UI真实写入；与OpenAPI/浏览器构建配合，不修改开发库、不保存凭据。 */
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
  const log = fs.openSync(path.join(out, "admin-e2e-backend.log"), "w");
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
  assert.equal(
    await page.$$eval(".site-nav nav a", (links) => links.length),
    5,
  );
  pass("真实登录与五个工作区");
  await navigate("organizations");
  await click("组织设置");
  await click("新建组织");
  await fill("组织编码", "ui-company");
  await fill("组织名称", "联调总公司");
  await click("保存组织", "dialog[open]");
  await closed();
  let organizations = (await read("/admin/organizations")).items;
  const company = organizations.find((o) => o.code === "ui-company");
  assert.ok(company);
  await click("联调总公司", ".directory-tree");
  await click("新建组织");
  await fill("组织编码", "ui-dept");
  await fill("组织名称", "联调部门");
  await click("保存组织", "dialog[open]");
  await closed();
  organizations = (await read("/admin/organizations")).items;
  const dept = organizations.find((o) => o.code === "ui-dept");
  assert.equal(dept.parentId, company.id);
  await click("联调部门", ".directory-tree");
  await click("编辑组织");
  await fill("组织名称", "联调部门改");
  await click("保存组织", "dialog[open]");
  await closed();
  assert.equal(
    (await read("/admin/organizations")).items.find((o) => o.id === dept.id)
      .name,
    "联调部门改",
  );
  pass("组织新增、查询、修改与层级联动");
  await click("人员档案");
  await page.waitForNetworkIdle();
  await click("新建人员");
  await fill("人员编码", "ui-employee-1");
  await fill("姓名", "联调员工");
  await fill("岗位", "测试岗");
  await click("保存人员", "dialog[open]");
  await closed();
  const employee = (await read("/admin/employees?size=20&page=1")).items.find(
    (e) => e.employeeCode === "ui-employee-1",
  );
  assert.equal(employee.organizationId, dept.id);
  await click("编辑资料");
  await fill("姓名", "联调员工改");
  await click("保存人员", "dialog[open]");
  await closed();
  assert.equal(
    (await read(`/admin/employees/${employee.id}`)).displayName,
    "联调员工改",
  );
  await click("账号与授权");
  await fill("登录账号", "ui-user-1");
  await click("创建账号", "dialog[open]");
  await page.waitForSelector('input[aria-label="临时密码"]');
  let temporary = await page.$eval(
    'input[aria-label="临时密码"]',
    (e) => e.value,
  );
  assert.ok(temporary.length >= 4);
  await closeDialog();
  const account = await read(`/admin/employees/${employee.id}/account`);
  assert.equal(account.username, "ui-user-1");
  pass("人员新增/修改与关联账号真实建号");
  await navigate("roles");
  await click("新建角色");
  await fill("角色编码", "ui-oa-reader");
  await fill("角色名称", "联调OA读者");
  await click("保存角色", "dialog[open]");
  await closed();
  let role = (
    await read("/admin/applications/2/roles?page=1&size=20")
  ).items.find((r) => r.code === "ui-oa-reader");
  assert.ok(role);
  await click("编辑角色");
  await fill("角色名称", "联调OA读者改");
  await click("保存角色", "dialog[open]");
  await closed();
  await click("权限配置");
  const permission = (
    await read("/admin/applications/2/permissions")
  ).items.find((p) => p.code === "oa:document:view");
  assert.ok(permission);
  await page.evaluate(
    (code) =>
      [...document.querySelectorAll(".permission-item")]
        .find((e) => e.textContent.includes(code))
        .querySelector('input[type="checkbox"]')
        .click(),
    permission.code,
  );
  await page.select('select[aria-label="范围类型"]', "ALL");
  await click("预览权限变更");
  await page.waitForSelector(".preview-box");
  await click("确认保存", ".preview-box");
  await page.waitForNetworkIdle();
  assert.equal(
    (await read(`/admin/applications/2/roles/${role.id}/permissions`))
      .permissions[0].scope.kind,
    "ALL",
  );
  pass("角色新增/编辑与权限范围预览提交");
  await click("角色成员");
  await click("添加成员");
  await selectLabel("选择成员账号", "ui-user-1");
  await click("确认添加成员", "dialog[open]");
  await closed();
  assert.equal(
    (
      await read(
        `/admin/applications/2/users/${account.id}/effective-permissions`,
      )
    ).permissions[0].code,
    "oa:document:view",
  );
  await shot("admin-role-members-desktop");
  pass("成员真实授予与有效权限来源");
  const second = await browser.createBrowserContext();
  const userPage = await second.newPage();
  const original = page;
  page = userPage;
  await login("ui-user-1", temporary);
  const userPermission = await api("/oa/demo");
  assert.equal(userPermission.status, 200);
  await second.close();
  page = original;
  await click("移除成员");
  await page.waitForNetworkIdle();
  assert.equal(
    (await read(`/admin/applications/2/users/${account.id}/roles`)).memberships
      .length,
    0,
  );
  // 实际重新登录员工并访问受保护端点，避免只凭管理页关系列表推断撤权生效。
  const revokedContext = await browser.createBrowserContext();
  page = await revokedContext.newPage();
  page.on("pageerror", (error) => failures.push(error.message));
  await login("ui-user-1", temporary);
  temporary = undefined;
  assert.equal((await api("/oa/demo")).status, 403);
  await revokedContext.close();
  page = original;
  pass("撤回角色立即影响有效权限");
  await click("基本信息");
  await click("停用角色");
  await page.waitForNetworkIdle();
  assert.equal(
    (await read(`/admin/applications/2/roles/${role.id}`)).enabled,
    false,
  );
  await click("启用角色");
  await page.waitForNetworkIdle();
  assert.equal(
    (await read(`/admin/applications/2/roles/${role.id}`)).enabled,
    true,
  );
  pass("角色停用与恢复");
  // 并发版本冲突由真实API触发，页面必须保留错误及重新加载入口。
  await click("编辑角色");
  role = await read(`/admin/applications/2/roles/${role.id}`);
  const conflictWrite = await api(
    `/admin/applications/2/roles/${role.id}`,
    "PUT",
    {
      name: "并发修改后的角色",
      personnelScope: role.personnelScope,
      expectedVersion: role.version,
    },
  );
  assert.equal(conflictWrite.status, 200);
  await fill("角色名称", "陈旧修改");
  await click("保存角色", "dialog[open]");
  await page.waitForSelector('dialog[open] [role="alert"]');
  assert.match(
    await page.$eval('dialog[open] [role="alert"]', (e) => e.textContent),
    /重新加载/,
  );
  await closeDialog();
  await click("重新加载详情");
  await page.waitForNetworkIdle();
  pass("409冲突可见并可重新加载");
  await navigate("organizations");
  await click("登录账号");
  await page.waitForNetworkIdle();
  await page.evaluate(() =>
    [...document.querySelectorAll("tbody tr")]
      .find((r) => r.textContent.includes("ui-user-1"))
      .querySelector("button")
      .click(),
  );
  await page.waitForSelector("dialog[open] .choice-grid");
  await page.waitForNetworkIdle();
  await page.evaluate(() =>
    [...document.querySelectorAll("dialog[open] .choice-grid label")]
      .find((e) => e.textContent.includes("并发修改后的角色"))
      .querySelector("input")
      .click(),
  );
  await click("预览授权变更", "dialog[open]");
  await click("确认保存", "dialog[open] .preview-box");
  await page.waitForNetworkIdle();
  await click("有效权限与来源", "dialog[open]");
  await page.waitForNetworkIdle();
  assert.match(
    await page.$eval("dialog[open]", (e) => e.textContent),
    /oa:document:view/,
  );
  await closeDialog();
  pass("人员完整角色集合与有效权限详情联动");
  await navigate("applications");
  await click("管理员与范围");
  await click("任命应用管理员");
  await selectLabel("管理员账号", "oa-admin");
  await page.evaluate(
    (name) =>
      [...document.querySelectorAll("dialog[open] .choice-grid label")]
        .find((e) => e.textContent.includes(name))
        .querySelector("input")
        .click(),
    "并发修改后的角色",
  );
  await page.evaluate(
    (code) =>
      [...document.querySelectorAll("dialog[open] .permission-item")]
        .find((e) => e.textContent.includes(code))
        .querySelector('input[type="checkbox"]')
        .click(),
    permission.code,
  );
  await page.select('dialog[open] select[aria-label="范围类型"]', "ALL");
  await click("保存管理员委派", "dialog[open]");
  await closed();
  const delegation = (
    await read("/admin/applications/2/delegations?page=1&size=20")
  ).items[0];
  assert.ok(delegation);
  pass("平台配置业务管理员范围/白名单/权限上限");
  await navigate("organizations");
  await click("登录账号");
  await page.waitForNetworkIdle();
  await page.evaluate(() =>
    [...document.querySelectorAll("tbody tr")]
      .find((r) => r.textContent.includes("ui-user-1"))
      .querySelector("button")
      .click(),
  );
  await click("维护责任", "dialog[open]");
  await selectLabel("责任管理员", "oa-admin");
  await click("保存维护责任", "dialog[open]");
  await page.waitForNetworkIdle();
  await closeDialog();
  const businessContext = await browser.createBrowserContext();
  const business = await businessContext.newPage();
  await business.setViewport({ width: 1440, height: 1000 });
  business.on("pageerror", (e) => failures.push(e.message));
  business.on("dialog", (d) => d.accept());
  page = business;
  await login("oa-admin", "1234");
  assert.equal(
    await page.$$eval('select[aria-label="管理应用"] option', (o) => o.length),
    1,
  );
  await navigate("organizations");
  await page.waitForNetworkIdle();
  assert.match(await page.$eval("main", (e) => e.textContent), /ui-user-1/);
  assert.equal((await api("/admin/employees")).status, 403);
  assert.equal((await api("/admin/applications/3/roles")).status, 403);
  await click("角色与权限");
  await page.waitForNetworkIdle();
  await shot("admin-business-grant-desktop");
  await closeDialog();
  await navigate("roles");
  await click("新建角色");
  await fill("角色编码", "ui-oa-team");
  await fill("角色名称", "业务管理员专用角色");
  await click("保存角色", "dialog[open]");
  await closed();
  const scoped = (
    await read("/admin/applications/2/roles?page=1&size=20")
  ).items.find((r) => r.code === "ui-oa-team");
  assert.equal(scoped.kind, "SCOPED");
  await click("权限配置");
  await page.evaluate(
    (code) =>
      [...document.querySelectorAll(".permission-item")]
        .find((e) => e.textContent.includes(code))
        .querySelector('input[type="checkbox"]')
        .click(),
    permission.code,
  );
  await click("预览权限变更");
  await click("确认保存", ".preview-box");
  await page.waitForNetworkIdle();
  await shot("admin-business-role-desktop");
  await click("角色成员");
  await click("添加成员");
  await selectLabel("选择成员账号", "ui-user-1");
  await click("确认添加成员", "dialog[open]");
  await closed();
  assert.equal(
    (await read(`/admin/applications/2/users/${account.id}/roles`)).memberships
      .length,
    2,
  );
  pass("业务管理员新建专用角色、配置权限并给负责员工授权");
  await page.setViewport({ width: 390, height: 844 });
  await shot("admin-business-mobile");
  assert.ok(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  );
  page = original;
  pass("业务管理员仅见对应应用/责任人员且跨域403");
  await navigate("organizations");
  await click("登录账号");
  await page.waitForNetworkIdle();
  await page.evaluate(() =>
    [...document.querySelectorAll("tbody tr")]
      .find(
        (r) => r.querySelector("td strong")?.textContent.trim() === "oa-admin",
      )
      .querySelector("button")
      .click(),
  );
  await page.waitForNetworkIdle();
  const beforeLoss = await read(
    `/admin/applications/2/users/${account.id}/roles`,
  );
  await click("移除角色", "dialog[open]");
  await click("预览授权变更", "dialog[open]");
  await click("确认保存", "dialog[open] .preview-box");
  await page.waitForNetworkIdle();
  assert.deepEqual(
    (await read(`/admin/applications/2/users/${account.id}/roles`)).memberships
      .map((m) => m.roleId)
      .sort(),
    beforeLoss.memberships.map((m) => m.roleId).sort(),
  );
  assert.ok(
    (
      await read(
        `/admin/applications/2/users/${account.id}/effective-permissions`,
      )
    ).permissions.some((p) => p.code === permission.code && p.capabilities.all),
  );
  await closeDialog();
  await businessContext.close();
  pass("界面撤回管理员本人权限后员工既有角色/访问不变（v2）");
  await click("人员档案");
  await page.waitForNetworkIdle();
  await click("调岗换角");
  await fill("新岗位", "调整后岗位");
  await page.waitForNetworkIdle();
  await click("预览人员变动", "dialog[open]");
  await click("确认保存", "dialog[open] .preview-box");
  await closed();
  assert.equal(
    (await read(`/admin/employees/${employee.id}`)).positionName,
    "调整后岗位",
  );
  assert.equal(
    (await read(`/admin/applications/2/users/${account.id}/roles`)).memberships
      .length,
    2,
  );
  pass("调岗换角预览与原子提交保留明确的新角色集合");
  await click("登录账号");
  await page.waitForNetworkIdle();
  for (const action of ["停用账号", "启用账号"]) {
    await page.evaluate(
      (action) =>
        [...document.querySelectorAll("tbody tr")]
          .find(
            (r) =>
              r.querySelector("td strong")?.textContent.trim() === "ui-user-1",
          )
          .querySelectorAll("button")
          .forEach((b) => {
            if (b.textContent.trim() === action) b.click();
          }),
      action,
    );
    await page.waitForNetworkIdle();
    assert.equal(
      (await read(`/admin/accounts/${account.id}`)).enabled,
      action === "启用账号",
    );
  }
  pass("账号停用/恢复联动真实状态");
  await navigate("audit");
  await page.waitForNetworkIdle();
  assert.ok((await read("/admin/audit?page=1&size=20")).total > 0);
  await shot("admin-audit-desktop");
  await page.setViewport({ width: 390, height: 844 });
  await shot("admin-audit-mobile");
  assert.ok(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  );
  assert.deepEqual(failures, []);
  pass("真实审计、桌面/手机布局与浏览器零异常");
  await click("退出登录");
  await page.waitForSelector(".login-panel");
  await page.goto(`${base}/admin/roles`, { waitUntil: "networkidle0" });
  await page.waitForSelector(".login-panel");
  assert.equal((await api("/admin/managed-applications")).status, 401);
  pass("退出后旧会话401与管理路由登录保护");
  fs.writeFileSync(
    path.join(out, "admin-e2e.json"),
    JSON.stringify(
      {
        status: "PASS",
        checks,
        browserErrors: failures,
        storage:
          "dedicated ephemeral MySQL/Redis; development database untouched",
      },
      null,
      2,
    ),
  );
  console.log(`Admin real API E2E PASS: ${checks.length} scenarios`);
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
  if (
    page &&
    !page.isClosed() &&
    !(await page.$('input[aria-label="临时密码"]'))
  )
    await shot("admin-e2e-failure").catch(() => {});
  fs.writeFileSync(
    path.join(out, "admin-e2e.json"),
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
