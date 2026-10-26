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
const sourceRoot = path.join(root, "docs");
function documentsIn(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((item) => {
    const target = path.join(directory, item.name);
    return item.isDirectory()
      ? documentsIn(target)
      : item.name.endsWith(".adoc")
        ? [path.relative(sourceRoot, target)]
        : [];
  });
}
const documents = documentsIn(sourceRoot).sort();
const manifest = [];
const diagnostics = [];
for (const name of documents) {
  const source = path.join(sourceRoot, name);
  const target = path.join(out, name.replace(/\.adoc$/, ".html"));
  const logger = asciidoctor.MemoryLogger.create();
  asciidoctor.LoggerManager.setLogger(logger);
  const html = asciidoctor.convert(fs.readFileSync(source, "utf8"), {
    safe: "safe",
    backend: "html5",
    standalone: true,
    base_dir: sourceRoot,
    attributes: {
      "source-highlighter": "highlightjs",
      lang: "zh-CN",
      "toc-title": "目录",
      "doc-root": sourceRoot,
      imagesdir: path
        .relative(path.dirname(target), path.dirname(source))
        .replaceAll(path.sep, "/"),
    },
  });
  for (const message of logger.getMessages())
    diagnostics.push({
      document: name,
      severity: message.severity,
      message: message.message?.text ?? String(message.message),
    });
  fs.mkdirSync(path.dirname(target), { recursive: true });
  // 文档留在HTML目录，代码/契约指回真实源；历史页面使用自己的相对层次。
  const linked = html.replace(/href="([^"]+)"/g, (whole, href) => {
    if (/^(?:[a-z][a-z\d+.-]*:|#|\/)/i.test(href)) return whole;
    const [file, anchor] = href.split("#", 2);
    const resolved = path.resolve(
      path.dirname(source),
      decodeURIComponent(file),
    );
    let destination = resolved;
    if (
      resolved.endsWith(".adoc") &&
      resolved.startsWith(sourceRoot + path.sep)
    )
      destination = path
        .join(out, path.relative(sourceRoot, resolved))
        .replace(/\.adoc$/, ".html");
    if (
      resolved.endsWith(".csv") &&
      resolved.startsWith(sourceRoot + path.sep)
    ) {
      destination = path.join(out, path.relative(sourceRoot, resolved));
      fs.mkdirSync(path.dirname(destination), { recursive: true });
      fs.copyFileSync(resolved, destination);
    }
    const relative = path
      .relative(path.dirname(target), destination)
      .replaceAll(path.sep, "/");
    return `href="${relative}${anchor ? `#${anchor}` : ""}"`;
  });
  // 长API路径/表名可换行，避免技术索引把表格和侧栏撑出阅读区域。
  const readable = linked.replace(
    "</head>",
    "<style>.tableblock p,.tableblock code,#toc a{overflow-wrap:anywhere}#toc a{word-break:normal}pre{overflow:auto}</style></head>",
  );
  fs.writeFileSync(target, readable);
  manifest.push(path.relative(root, target).replaceAll(path.sep, "/"));
}
fs.writeFileSync(
  path.join(out, "文档清单.json"),
  JSON.stringify(manifest, null, 2),
);
fs.writeFileSync(
  path.join(out, "渲染诊断.json"),
  JSON.stringify(diagnostics, null, 2),
);
if (diagnostics.length)
  throw Error(
    `Document rendering reported ${diagnostics.length} diagnostics; inspect reports/docs/渲染诊断.json`,
  );
console.log(`Documentation rendered: ${out}`);
