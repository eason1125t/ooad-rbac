"""功能：校验中文说明编号、源链接、生成明细与历史归档；与docs生成/CI配合，不访问秘密或数据库。"""
from pathlib import Path
import csv
import json
import re
import sys
from html.parser import HTMLParser
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]


class RenderedPage(HTMLParser):
    """读取真实HTML目标和锚点，验证改名后的导航而不请求外部网站。"""
    def __init__(self, path: Path) -> None:
        super().__init__()
        self.ids: set[str] = set()
        self.links: list[str] = []
        self.feed(path.read_text(encoding="utf-8"))

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.add(values["id"])
        target = values.get("href") if tag == "a" else values.get("src") if tag == "img" else None
        if target:
            self.links.append(target)


def check_rendered() -> None:
    manifest = json.loads((ROOT / "reports/docs/文档清单.json").read_text(encoding="utf-8"))
    pages = {(ROOT / item).resolve(): RenderedPage(ROOT / item) for item in manifest}
    errors = []
    count = 0
    for path, page in pages.items():
        for link in page.links:
            uri = urlsplit(link)
            if uri.scheme or uri.netloc:
                continue
            destination = (path.parent / unquote(uri.path)).resolve() if uri.path else path
            if not destination.exists():
                errors.append(f"{path.name}: missing rendered target {link}")
            elif uri.fragment and destination in pages and unquote(uri.fragment) not in pages[destination].ids:
                errors.append(f"{path.name}: missing rendered anchor {link}")
            count += 1
    if errors:
        raise RuntimeError("Rendered documentation errors:\n" + "\n".join(errors))
    print(f"Rendered documentation PASS: {len(pages)} pages / {count} local file, image and section links")


def check() -> None:
    docs = ROOT / "docs"
    failures: list[str] = []
    sources = [ROOT / "README.md", ROOT / "AGENTS.md", ROOT / "references/source/README.md", *docs.rglob("*.adoc")]
    # 只解析真实链接指令；历史正文中的旧文件名和代码示例不是当前入口。
    for path in sources:
        text = path.read_text(encoding="utf-8")
        targets = re.findall(r"(?:link:|include::|image::)([^\s\[]+)\[", text)
        if path.suffix == ".md":
            targets += [next(item for item in pair if item) for pair in re.findall(r"\[[^\]]+\]\((?:<([^>]+)>|([^\s)]+))\)", text)]
        for target in targets:
            if target.startswith("{doc-root}/"):
                target = str(docs / target.removeprefix("{doc-root}/"))
            if (re.match(r"^[a-zA-Z][\w+.-]*:", target) and not Path(target).is_absolute()) or target.startswith("#") or "{" in target:
                continue
            filename = target.split("#", 1)[0]
            if filename and not (path.parent / filename).resolve().exists():
                failures.append(f"{path.relative_to(ROOT)}: missing {filename}")
        definitions = set(re.findall(r"\[\[([^,\]]+)(?:,[^\]]+)?\]\]", text))
        for anchor in re.findall(r"<<((?:api|model)-[^,>]+)(?:,[^>]+)?>>", text):
            if anchor not in definitions:
                failures.append(f"{path.name}: missing anchor {anchor}")
    numbered = [p.name for p in docs.iterdir() if p.is_file() and p.suffix in (".adoc", ".csv")]
    for name in numbered:
        if not re.match(r"^\d{2}-.+\.(adoc|csv)$", name) or not re.search(r"[\u4e00-\u9fff]", name):
            failures.append(f"Unexpected current document name: {name}")
    if sorted(name[:2] for name in numbered) != [f"{i:02}" for i in range(16)]:
        failures.append("Current documentation must contain unique 00–15 numbering")
    api_text = (docs / "14-API接口与模型清单.adoc").read_text(encoding="utf-8")
    api_count = len(re.findall(r"^\[\[api-", api_text, re.M))
    model_count = len(re.findall(r"^\[\[model-", api_text, re.M))
    if api_count != 96 or model_count != 95:
        failures.append(f"API inventory mismatch: {api_count} operations / {model_count} models; update baseline explicitly")
    dictionary = (docs / "08-数据库表与字段字典.adoc").read_text(encoding="utf-8")
    import schema_docs
    tables = schema_docs.current_tables()
    listed = re.findall(r"^== (\w+)$", dictionary, re.M)
    if listed != list(tables) or len(listed) != 30:
        failures.append("Dictionary tables differ from current Flyway model")
    for name, entries in tables.items():
        section = dictionary.split(f"== {name}\n", 1)[1].split("\n== ", 1)[0]
        fields = [m.group(1) for line in entries if (m := re.match(rf"(\w+) {schema_docs.COLUMN_TYPE}", line))]
        shown = re.findall(r"^\|`(\w+)` \|", section, re.M)
        if shown != fields:
            failures.append(f"Dictionary field mismatch: {name}")
    with (docs / "13-需求与用例追踪.csv").open(encoding="utf-8", newline="") as stream:
        traced = [row["requirement"] for row in csv.DictReader(stream)]
    expected = [f"FR-{i:02}" for i in range(1, 11)] + [f"NFR-{i:02}" for i in range(1, 5)]
    if traced != expected:
        failures.append("Requirement IDs changed or missing")
    if failures:
        raise RuntimeError("Documentation errors:\n" + "\n".join(failures))
    report = {"status": "PASS", "currentDocuments": len(numbered), "checkedSources": len(sources), "operations": api_count, "models": model_count, "tables": len(listed), "requirementIds": len(traced)}
    (ROOT / "reports").mkdir(exist_ok=True)
    (ROOT / "reports/documentation-integrity.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Documentation PASS: {len(numbered)} numbered files; links/anchors; {api_count} operations/{model_count} models; {len(listed)} tables and all fields; {len(traced)} requirements")


if __name__ == "__main__":
    check()
    if "--rendered" in sys.argv:
        check_rendered()
