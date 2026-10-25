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
    await page.click("#prototype-preview");
    assert.match(
      await page.$eval("main", (element) => element.textContent),
      /合成原型数据/,
    );
    await page.screenshot({
      path: path.join(out, `${app}-desktop.png`),
      fullPage: true,
    });
    if (app === "admin") {
      assert.equal(
        await page.$$eval(".site-nav nav a", (links) => links.length),
        5,
      );
      for (const route of ["organizations", "roles", "applications", "audit"]) {
        await page.click(`a[href="/admin/${route}"]`);
        await page.waitForSelector(".detail-panel");
        const count = await page.$$eval('[role="tab"]', (tabs) => tabs.length);
        for (let index = 0; index < count; index++) {
          await page.click(`[role="tab"]:nth-child(${index + 1})`);
          await page.waitForFunction(
            (number) =>
              document
                .querySelectorAll('[role="tab"]')
                [number]?.getAttribute("aria-selected") === "true",
            {},
            index,
          );
        }
      }
      for (const state of [
        "loading",
        "empty",
        "failed",
        "401",
        "403",
        "syncing",
      ]) {
        await page.select('select[aria-label="状态预览"]', state);
        assert.ok(await page.$(".empty-state"));
      }
      await page.select('select[aria-label="状态预览"]', "normal");
    }
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
    "reports/docs/design.html",
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
    await page.screenshot({
      path: path.join(
        out,
        file.includes("prototype") ? "prototype.png" : "design-document.png",
      ),
      fullPage: false,
    });
  }
  assert.deepEqual(failures, []);
  console.log(
    "UI smoke PASS: 3 apps desktop/mobile, login, five admin menus and detail tabs/states, prototype and rendered design; synthetic API only.",
  );
} finally {
  if (browser) await browser.close();
  await new Promise((resolve) => server.close(resolve));
}
