/** 功能：生成可打开的adoc HTML及Mermaid SVG；输入来自docs，输出reports/docs，保留所有可编辑源。 */
import Asciidoctor from "@asciidoctor/core";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../..", import.meta.url));
const out = path.join(root, "reports/docs");
fs.mkdirSync(out, { recursive: true });
const config = path.join(out, "puppeteer.json");
const chrome =
  process.env.CHROME_PATH ??
  (process.platform === "win32"
    ? "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
    : undefined);
fs.writeFileSync(
  config,
  JSON.stringify(
    chrome
      ? { executablePath: chrome, args: ["--no-sandbox"] }
      : { args: ["--no-sandbox"] },
  ),
);
const cli = path.join(
  root,
  "frontend/node_modules/@mermaid-js/mermaid-cli/src/cli.js",
);
for (const name of ["er", "flow", "modules"]) {
  execFileSync(
    process.execPath,
    [
      cli,
      "-i",
      path.join(root, `docs/diagrams/${name}.mmd`),
      "-o",
      path.join(root, `docs/diagrams/${name}.svg`),
      "-p",
      config,
      "-b",
      "white",
    ],
    { stdio: "inherit" },
  );
}
const asciidoctor = Asciidoctor();
const documents = fs
  .readdirSync(path.join(root, "docs"))
  .filter((name) => name.endsWith(".adoc") && !name.includes(".generated."));
documents.push(
  ...fs
    .readdirSync(path.join(root, "docs/adr"))
    .filter((name) => name.endsWith(".adoc"))
    .map((name) => `adr/${name}`),
);
for (const name of documents) {
  const html = asciidoctor.convert(
    fs.readFileSync(path.join(root, "docs", name), "utf8"),
    {
      safe: "safe",
      backend: "html5",
      standalone: true,
      base_dir: path.join(root, "docs"),
      attributes: {
        "source-highlighter": "highlightjs",
        imagesdir: "../../docs",
      },
    },
  );
  const target = path.join(out, name.replace(".adoc", ".html"));
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(
    target,
    html.replace(/href="([^"]+)\.adoc"/g, 'href="$1.html"'),
  );
}
console.log(`Documentation rendered: ${out}`);
