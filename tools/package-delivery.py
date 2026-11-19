"""Package the verified plugin, source JAR, project sources, configs and evidence together.

Run only after verification_report.py and the intended server evidence are complete.
No .git data, runtime worlds/databases, caches, credentials or third-party binaries are included.
"""
from pathlib import Path
import hashlib
import json
import subprocess
import zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
plugin = root / "target/starchronicle-0.1.0-SNAPSHOT.jar"
sources = root / "target/starchronicle-0.1.0-SNAPSHOT-sources.jar"
report = root / "docs/TEST-REPORT.md"
if not all(p.is_file() for p in (plugin, sources, report)):
    raise SystemExit("Run a successful clean verify and verification_report.py first")
digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
if digest(plugin) not in report.read_text(encoding="utf-8"):
    raise SystemExit("Build report does not identify the current plugin JAR")

# The source JAR is proof that the delivered main source matches this compiled snapshot.
with zipfile.ZipFile(sources) as jar:
    for source in (root / "src/main/java").rglob("*.java"):
        name = source.relative_to(root / "src/main/java").as_posix()
        if name not in jar.namelist() or jar.read(name) != source.read_bytes():
            raise SystemExit("Main source drift since verify: " + name)
with zipfile.ZipFile(plugin) as jar:
    for resource in (root / "src/main/resources").rglob("*"):
        if resource.is_file():
            name = resource.relative_to(root / "src/main/resources").as_posix()
            if name not in jar.namelist() or jar.read(name) != resource.read_bytes():
                raise SystemExit("Resource drift since verify: " + name)

listed = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
                        cwd=root, capture_output=True, check=True).stdout.decode("utf-8").split("\0")
top = {"README.md", "LICENSE", "pom.xml", "mvnw", "mvnw.cmd", ".gitignore", ".gitattributes"}
directories = {"src", "docs", "tools", "patches", ".mvn", ".github"}
files = []
for name in sorted(set(listed)):
    if not name:
        continue
    relative = Path(name)
    if name not in top and relative.parts[0] not in directories:
        continue
    if "__pycache__" in relative.parts or relative.suffix in {".pyc", ".class", ".db", ".jar"}:
        continue
    path = root / relative
    if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root):
        raise SystemExit("Unsafe or missing project file: " + name)
    files.append((name, path))

destination = root / "target/starchronicle-0.1.0-SNAPSHOT-delivery.zip"
build_reports = []
suite_files = sorted((root / "target/surefire-reports").glob("TEST-*.xml"))
if not suite_files:
    raise SystemExit("Missing actual Surefire XML results")
test_count = 0
for path in suite_files:
    suite = ET.parse(path).getroot()
    test_count += int(suite.attrib.get("tests", 0))
    if any(int(suite.attrib.get(key, 0)) for key in ("failures", "errors", "skipped")):
        raise SystemExit("Only a fully successful, unskipped test run can be packaged: " + path.name)
    build_reports.append(("surefire/" + path.name, path))
for path in sorted((root / "target/surefire-reports").glob("*.txt")):
    build_reports.append(("surefire/" + path.name, path))
for name in ("jacoco.xml", "jacoco.csv"):
    path = root / "target/site/jacoco" / name
    if path.is_file():
        build_reports.append(("jacoco/" + name, path))
manifest = {"plugin_sha256": digest(plugin), "sources_jar_sha256": digest(sources),
            "test_count": test_count,
            "project_files": {name: digest(path) for name, path in files},
            "build_reports": {name: digest(path) for name, path in build_reports}}
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
    for name, path in files:
        info = zipfile.ZipInfo("StarChronicle/" + name)
        info.create_system = 3
        info.external_attr = (0o100755 if name == "mvnw" else 0o100644) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        bundle.writestr(info, path.read_bytes())
    for path in (plugin, sources):
        bundle.write(path, "artifacts/" + path.name)
    for name, path in build_reports:
        bundle.write(path, "build-reports/" + name)
    bundle.writestr("MANIFEST-SHA256.json", json.dumps(manifest, ensure_ascii=False, indent=2))
print(json.dumps({"path": str(destination), "bytes": destination.stat().st_size,
                  "sha256": digest(destination), "project_files": len(files)}, ensure_ascii=False))
