"""Verify a delivery ZIP without extracting or executing its contents.

Usage: python tools/verify-delivery.py [path/to/delivery.zip]
The manifest detects corruption; compare the ZIP SHA-256 with the separately supplied
release fingerprint when verifying the origin of a transferred bundle.
"""
from pathlib import Path, PurePosixPath
import hashlib
import json
import stat
import sys
import xml.etree.ElementTree as ET
import zipfile


root = Path(__file__).resolve().parents[1]
path = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "target/starchronicle-0.1.0-SNAPSHOT-delivery.zip"
with zipfile.ZipFile(path) as bundle:
    names = bundle.namelist()
    if len(names) != len(set(names)):
        raise SystemExit("Duplicate ZIP entry")
    for info in bundle.infolist():
        name = PurePosixPath(info.filename)
        if name.is_absolute() or ".." in name.parts or "\\" in info.filename or ":" in info.filename:
            raise SystemExit("Unsafe ZIP entry: " + info.filename)
        if stat.S_ISLNK(info.external_attr >> 16):
            raise SystemExit("Symlinks are not part of the delivery: " + info.filename)
    manifest = json.loads(bundle.read("MANIFEST-SHA256.json"))
    expected = {"MANIFEST-SHA256.json"}

    def verify(name, digest):
        expected.add(name)
        actual = hashlib.sha256(bundle.read(name)).hexdigest()
        if actual != digest:
            raise SystemExit("SHA-256 mismatch: " + name)

    for name, digest in manifest["project_files"].items():
        verify("StarChronicle/" + name, digest)
    for name, digest in manifest["build_reports"].items():
        verify("build-reports/" + name, digest)
    verify("artifacts/starchronicle-0.1.0-SNAPSHOT.jar", manifest["plugin_sha256"])
    verify("artifacts/starchronicle-0.1.0-SNAPSHOT-sources.jar", manifest["sources_jar_sha256"])
    if set(names) != expected:
        raise SystemExit("Unmanifested or missing ZIP entries")
    wrapper = bundle.getinfo("StarChronicle/mvnw")
    if wrapper.create_system != 3 or (wrapper.external_attr >> 16) != 0o100755:
        raise SystemExit("The Unix Maven Wrapper must retain its executable mode")
    suites = [name for name in names if name.startswith("build-reports/surefire/TEST-") and name.endswith(".xml")]
    tests = 0
    if not suites:
        raise SystemExit("Actual Surefire XML results are missing")
    for name in suites:
        suite = ET.fromstring(bundle.read(name))
        tests += int(suite.attrib["tests"])
        if any(int(suite.attrib.get(key, 0)) for key in ("failures", "errors", "skipped")):
            raise SystemExit("Non-successful test suite: " + name)
    if tests != manifest["test_count"]:
        raise SystemExit("Test count does not match the manifest")

print(json.dumps({"path": str(path.resolve()), "entries": len(names), "tests": tests,
                  "plugin_sha256": manifest["plugin_sha256"],
                  "zip_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                  "status": "VERIFIED"}, ensure_ascii=False))
