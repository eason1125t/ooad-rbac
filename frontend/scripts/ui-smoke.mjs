/** 功能：在浏览器审阅三应用构建、原型/状态与文档；API使用明确合成401，仅验证UI，不代替后端集成。 */
import { createRequire } from "node:module";
import { createServer } from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
const root = fileURLToPath(new URL("../..", import.meta.url));
const out = path.join(root, "reports/ui");
fs.mkdirSync(out, { recursive: true });
const require = createRequire(import.meta.resolve("@mermaid-js/mermaid-cli"));
const puppeteer = require("puppeteer");
const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript",
  ".css": "text/css",
  ".svg": "image/svg+xml",
};
const server = createServer((request, response) => {
  const url = decodeURIComponent(
    new URL(request.url, "http://localhost").pathname,
  );
  if (url.startsWith("/api/")) {
    response.writeHead(401, { "Content-Type": "application/json" });
    response.end(
      JSON.stringify({
        code: "UI_SYNTHETIC_401",
        message: "UI测试的未登录状态",
        traceId: "synthetic-ui",
      }),
    );
    return;
  }
  const match = url.match(/^\/(admin|oa|pmis)\/(.*)$/);
  let file;
  if (match) {
    file = path.join(root, `frontend/apps/${match[1]}/dist`, match[2]);
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory())
      file = path.join(root, `frontend/apps/${match[1]}/dist/index.html`);
  } else file = path.join(root, url);
  if (
    !file.startsWith(root) ||
    !fs.existsSync(file) ||
    fs.statSync(file).isDirectory()
  ) {
    response.writeHead(404);
    response.end();
    return;
  }
  response.writeHead(200, {
    "Content-Type": types[path.extname(file)] ?? "application/octet-stream",
  });
  fs.createReadStream(file).pipe(response);
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const base = `http://127.0.0.1:${server.address().port}`;
let browser;
try {
  browser = await puppeteer.launch({
    headless: true,
    executablePath:
      process.env.CHROME_PATH ??
      (process.platform === "win32"
        ? "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
        : undefined),
    args: ["--no-sandbox"],
  });
  const page = await browser.newPage();
  const failures = [];
  page.on("pageerror", (error) => failures.push(error.message));
  for (const app of ["admin", "oa", "pmis"]) {
    await page.setViewport({ width: 1366, height: 900 });
    await page.goto(`${base}/${app}/`, { waitUntil: "networkidle0" });
    assert.equal(
      await page
        .locator("h1")
        .wait()
        .then(() => true),
      true,
    );
    if (app === "admin") {
      assert.ok(
        page.url().includes("/admin/login"),
        "admin requires real login",
      );
      assert.equal(
        await page.$$eval(".site-nav nav a", (links) => links.length),
        5,
      );
      assert.equal(
        await page.$$eval(".login-panel input", (inputs) => inputs.length),
        2,
      );
    } else {
      assert.ok(
        page.url().includes(`/${app}/login`),
        `${app} requires real login`,
      );
      assert.equal(
        await page.$$eval(".login-panel input", (inputs) => inputs.length),
        2,
      );
    }
    await page.screenshot({
      path: path.join(out, `${app}-desktop.png`),
      fullPage: true,
    });
    await page.setViewport({ width: 390, height: 844 });
    await page.screenshot({
      path: path.join(out, `${app}-mobile.png`),
      fullPage: true,
    });
    assert.ok(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
      "page overflow",
    );
    await page.goto(`${base}/${app}/login`, { waitUntil: "networkidle0" });
    assert.equal(await page.$$eval("input", (inputs) => inputs.length), 2);
  }
  await page.setViewport({ width: 1366, height: 900 });
  for (const file of [
    "docs/prototypes/index.html",
    "reports/docs/00-文档导航.html",
    "reports/docs/02-架构与模块实现.html",
    "reports/docs/03-功能与权限规则详解.html",
    "reports/docs/04-新环境安装与运行.html",
    "reports/docs/05-功能操作与测试指南.html",
    "reports/docs/06-API设计与接口说明.html",
    "reports/docs/07-数据库设计说明.html",
    "reports/docs/08-数据库表与字段字典.html",
    "reports/docs/09-测试结果与验收记录.html",
    "reports/docs/10-后续开发路线与RBAC演进.html",
    "reports/docs/11-开发协作与维护约定.html",
    "reports/docs/12-课程交付与资料边界.html",
    "reports/docs/14-API接口与模型清单.html",
    "reports/docs/15-第一阶段开发规格与验收.html",
    "reports/docs/历史资料/00-历史资料索引.html",
  ]) {
    await page.goto(`${base}/${file}`, { waitUntil: "networkidle0" });
    assert.ok(
      (await page.$eval("body", (element) => element.textContent)).length > 100,
    );
    assert.equal(
      await page.$$eval(
        "img",
        (images) =>
          images.filter((image) => !image.complete || image.naturalWidth === 0)
            .length,
      ),
      0,
      "broken document image",
    );
    const missingAnchors = await page.$$eval('a[href^="#"]', (links) =>
      links
        .filter(
          (link) =>
            link.hash.length > 1 &&
            !document.getElementById(decodeURIComponent(link.hash.slice(1))),
        )
        .map((link) => link.hash),
    );
    assert.deepEqual(missingAnchors, [], `broken section link: ${file}`);
    await page.screenshot({
      path: path.join(
        out,
        file.includes("prototype")
          ? "prototype.png"
          : `说明-${path.basename(file, ".html")}.png`,
      ),
      fullPage: false,
    });
  }
  assert.deepEqual(failures, []);
  console.log(
    "UI smoke PASS: 3 apps desktop/mobile, login, five admin menus and mandatory login on all applications, historical prototype and Chinese numbered documents/images/anchors; synthetic API only.",
  );
} finally {
  if (browser) await browser.close();
  await new Promise((resolve) => server.close(resolve));
}
