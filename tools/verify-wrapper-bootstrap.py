"""Exercise the Unix wrapper in fresh caches; never modifies the user's Maven cache.

Run with Python 3.9+, a POSIX bash (Git Bash on Windows), Java 21, and the official
Maven ZIP plus its separately downloaded .sha512 file. The positive case downloads
from the configured HTTPS URL. Negative/fallback cases use verified local bytes.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import uuid

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--java-home", required=True)
parser.add_argument("--archive", required=True, type=Path)
parser.add_argument("--bash", default=shutil.which("bash") or "C:/Program Files/Git/bin/bash.exe")
args = parser.parse_args()
archive = args.archive.resolve()
raw = archive.read_bytes()
sha256 = hashlib.sha256(raw).hexdigest()
official_sha512 = Path(str(archive) + ".sha512").read_text(encoding="ascii").split()[0]
if hashlib.sha512(raw).hexdigest() != official_sha512:
    raise SystemExit("Official SHA-512 mismatch; no bootstrap executed")
properties = (root / ".mvn/wrapper/maven-wrapper.properties").read_text(encoding="utf-8")
if "distributionSha256Sum=" + sha256 not in properties.splitlines():
    raise SystemExit("Configured SHA-256 does not match the verified official distribution")
work = root / "run" / ("wrapper-bootstrap-" + uuid.uuid4().hex[:12])
work.mkdir(parents=True)
checks = {"official_sha512_and_configured_sha256": True}
results = {}


def execute(name, wrapper, prefix=""):
    cache = work / (name + "-cache")
    if cache.exists():
        raise SystemExit("Refusing an existing test cache")
    command = prefix + "JAVA_HOME=" + shlex.quote(Path(args.java_home).as_posix())
    command += " MAVEN_USER_HOME=" + shlex.quote(cache.as_posix())
    command += " MVNW_VERBOSE=true exec bash " + shlex.quote(wrapper.as_posix()) + " --version"
    result = subprocess.run([args.bash, "-c", command], cwd=root, capture_output=True,
                            timeout=240, encoding="utf-8", errors="replace")
    output = result.stdout + result.stderr
    (work / (name + ".log")).write_text(output, encoding="utf-8", newline="\n")
    results[name] = {"exit_code": result.returncode, "cache": cache.relative_to(root).as_posix()}
    return result.returncode, output, cache


syntax = subprocess.run([args.bash, "-n", (root / "mvnw").as_posix()], capture_output=True)
checks["bash_syntax"] = syntax.returncode == 0
code, output, _ = execute("https-fresh", root / "mvnw")
checks["https_fresh_bootstrap"] = code == 0 and "Apache Maven 3.9.11" in output
checks["https_used_curl"] = "using curl with bounded retries" in output


def fixture(name, checksum):
    project = work / name
    (project / ".mvn/wrapper").mkdir(parents=True)
    shutil.copyfile(root / "mvnw", project / "mvnw")
    text = "wrapperVersion=3.3.4\ndistributionType=only-script\n"
    text += "distributionUrl=" + archive.as_uri() + "\n"
    text += "distributionSha256Sum=" + checksum + "\n"
    (project / ".mvn/wrapper/maven-wrapper.properties").write_text(text, encoding="utf-8")
    return project


bad = fixture("checksum-rejection", "0" * 64)
code, output, cache = execute("checksum-rejection", bad / "mvnw")
checks["wrong_checksum_rejected_before_install"] = (
    code != 0 and "Failed to validate Maven distribution SHA-256" in output
    and not list(cache.rglob("mvnw.url")))

fallback = fixture("curl-fallback", sha256)
shim = work / "download-shims"
shim.mkdir()
(shim / "curl").write_text("#!/bin/sh\necho 'fixture curl transport failure' >&2\nexit 22\n", encoding="utf-8", newline="\n")
# Simulate only the failed transport and wget's -O contract; still install and run
# the real checksum-verified Maven ZIP. This is not an actual wget network test.
script = "#!/bin/sh\nwhile [ $# -gt 0 ]; do\n  if [ \"$1\" = -O ]; then\n"
script += "    cp " + shlex.quote(archive.as_posix()) + " \"$2\"\n    exit $?\n  fi\n  shift\ndone\nexit 2\n"
(shim / "wget").write_text(script, encoding="utf-8", newline="\n")
for file in shim.iterdir():
    file.chmod(0o755)
shim_path = shim.as_posix()
if os.name == "nt":
    # PATH is colon-delimited inside Git Bash; C:/... would split at the drive.
    shim_path = "/" + shim.drive[0].lower() + shim_path[2:]
prefix = "PATH=" + shlex.quote(shim_path) + ':"$PATH" '
code, output, _ = execute("curl-fallback", fallback / "mvnw", prefix)
checks["failed_curl_falls_back_to_wget_contract"] = (
    code == 0 and "fixture curl transport failure" in output
    and "using wget with bounded retries" in output and "Apache Maven 3.9.11" in output)
java = fixture("java-fallback", sha256)
# Leave a partial ZIP, as a real transport failure may do. Java must replace it.
script = "#!/bin/sh\nwhile [ $# -gt 0 ]; do\n  if [ \"$1\" = -O ]; then\n"
script += "    printf partial >\"$2\"\n    exit 1\n  fi\n  shift\ndone\nexit 1\n"
(shim / "wget").write_text(script, encoding="utf-8", newline="\n")
code, output, _ = execute("java-fallback", java / "mvnw", prefix)
checks["failed_transports_java_replaces_partial_download"] = (
    code == 0 and "Falling back to use Java to download" in output
    and "Apache Maven 3.9.11" in output)
summary = {"status": "PASS" if all(checks.values()) else "FAIL",
           "generated_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
           "wrapper_sha256": hashlib.sha256((root / "mvnw").read_bytes()).hexdigest(),
           "distribution_sha256": sha256, "official_distribution_sha512": official_sha512,
           "checks": checks, "processes": results,
           "limits": ["Git Bash on Windows, not a Linux runner",
                      "Fallback transport failures/wget success use shims; Java download is real",
                      "Only Maven --version is run; not a replacement for clean verify"]}
(work / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"work": str(work), **summary}, indent=2))
raise SystemExit(0 if all(checks.values()) else 1)
