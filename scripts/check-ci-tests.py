"""功能：检查真实Surefire和JaCoCo报告，阻止容器跳过或核心覆盖率不足的CI假绿。

与verify workflow配合；只允许独立原生MySQL环境用例条件跳过，不读取密码或测试明细。
"""
from pathlib import Path
import xml.etree.ElementTree as ET


def check() -> None:
    root = Path(__file__).resolve().parents[1]
    files = list((root / "backend/target/surefire-reports").glob("TEST-*.xml"))
    if not files:
        raise RuntimeError("Missing Surefire XML; tests were not verified")
    required = {
        "com.municipal.rbac.bootstrap.MySqlRedisIntegrationTest": 6,
        "com.municipal.rbac.bootstrap.IdentityAdministrationIntegrationTest": 16,
        "com.municipal.rbac.bootstrap.ApplicationAuthorizationIntegrationTest": 28,
    }
    optional = "com.municipal.testing.MySqlMigrationTest"
    total = passed = skipped = 0
    for path in files:
        suite = ET.parse(path).getroot()
        name = suite.attrib["name"]
        count, failures, errors, skip = (
            int(suite.attrib.get(key, "0"))
            for key in ("tests", "failures", "errors", "skipped")
        )
        if failures or errors or (skip and name != optional):
            raise RuntimeError(f"Failed or unexpectedly skipped suite: {name}")
        if name in required:
            if count < required[name] or skip:
                raise RuntimeError(f"Required container suite did not fully execute: {name}")
            del required[name]
        total += count
        passed += count - skip
        skipped += skip
    if required:
        raise RuntimeError("Missing required container suites: " + ", ".join(required))
    print(f"Surefire verified: {total} tests, {passed} passed, {skipped} optional skipped; required container suites executed")
    coverage = root / "backend/target/site/jacoco/jacoco.xml"
    if not coverage.is_file():
        raise RuntimeError("Missing JaCoCo XML; run Maven verify first")
    packages = ET.parse(coverage).getroot().findall("package")
    if not packages:
        raise RuntimeError("Missing executable package coverage")
    # 全部生产包均检查；零分支包只检查行覆盖，不排除授权/安全/初始化代码。
    for package in packages:
        for counter in package.findall("counter"):
            kind = counter.attrib["type"]
            threshold = {"LINE": 80, "BRANCH": 70}.get(kind)
            covered, missed = int(counter.attrib["covered"]), int(counter.attrib["missed"])
            if threshold is not None and covered + missed and covered * 100 < threshold * (covered + missed):
                raise RuntimeError(f"Coverage below {threshold}%: {package.attrib['name']} {kind}")
    print("JaCoCo verified: every executable production package meets line80% / branch70%; no core excluded")


if __name__ == "__main__":
    check()
