"""Upgrade a read-only backup of the real phase-one V2 database with the final product.

Never starts a server or opens the original database with JDBC. Use the same exclusive JVM
window as the server acceptance tools; no original world, state or receipt is mutated.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import sqlite3
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument("--source-db", type=Path, default=ROOT / "run/expanded-paper-release-smoke/plugins/StarChronicle/chronicle.db")
p.add_argument("--plugin", type=Path, default=ROOT / "target/starchronicle-0.1.0-SNAPSHOT.jar")
p.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", r"C:\Users\Administrator\Documents\luoseng\.toolchains\jdk-21.0.13.0.2+1")))
p.add_argument("--run-name", default="phase-two-upgrade-final")
p.add_argument("--expected-plugin-sha256", required=True)
a = p.parse_args()
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
if not re.fullmatch(r"phase-two-upgrade-[a-z0-9-]{1,48}", a.run_name):
    raise SystemExit("Invalid isolated upgrade run name")
run = (ROOT / "run" / a.run_name).resolve()
if not run.is_relative_to(ROOT.resolve()) or run.exists():
    raise SystemExit("Refusing unsafe or existing upgrade run")
if not a.source_db.is_file() or not a.plugin.is_file() or digest(a.plugin) != a.expected_plugin_sha256:
    raise SystemExit("Missing source/product or final product fingerprint mismatch")
java = a.java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
javac = a.java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac")
if not java.is_file() or not javac.is_file():
    raise SystemExit("Java 21 runtime/compiler not found")
with zipfile.ZipFile(a.plugin) as jar:
    if "io/github/addxiaoyi/starchronicle/core/ChoiceLedger.class" not in jar.namelist():
        raise SystemExit("The provided product predates schema 3")


def snapshot(connection):
    names = [row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    result = {}
    for name in names:
        quoted = '"' + name.replace('"', '""') + '"'
        columns = [row[1] for row in connection.execute("PRAGMA table_info(" + quoted + ")")]
        rows = list(connection.execute("SELECT * FROM " + quoted))
        result[name] = {"columns": columns, "rows": sorted(rows, key=lambda row: json.dumps(row, ensure_ascii=False))}
    return result


run.mkdir(parents=True)
clone = run / "chronicle-clone.db"
source_hash = digest(a.source_db)
with sqlite3.connect(a.source_db.resolve().as_uri() + "?mode=ro", uri=True) as source:
    version = source.execute("PRAGMA user_version").fetchone()[0]
    if version != 2:
        raise SystemExit("This proof requires an actual old V2 database")
    before = snapshot(source)
    with sqlite3.connect(clone) as destination:
        source.backup(destination)
(run / "before.json").write_text(json.dumps(before, ensure_ascii=False, indent=2), encoding="utf-8")
classes = run / "classes"
classes.mkdir()
compile_result = subprocess.run([str(javac), "-J-Xmx128m", "--release", "21", "-encoding", "UTF-8", "-cp", str(a.plugin), "-d", str(classes), str(ROOT / "tools/SchemaUpgradeProbe.java")], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
(run / "compile.log").write_text(compile_result.stdout + compile_result.stderr, encoding="utf-8")
if compile_result.returncode:
    raise SystemExit("Upgrade probe compilation failed; inspect " + str(run / "compile.log"))
probe = subprocess.run([str(java), "-Xmx128m", "-XX:MaxMetaspaceSize=128m", "-cp", os.pathsep.join((str(a.plugin), str(classes))), "SchemaUpgradeProbe", str(clone)], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
(run / "probe.log").write_text(probe.stdout + probe.stderr, encoding="utf-8")
if probe.returncode:
    raise SystemExit("Production schema upgrade failed; inspect " + str(run / "probe.log"))
data = json.loads(probe.stdout.strip().splitlines()[-1])
with sqlite3.connect(clone.resolve().as_uri() + "?mode=ro", uri=True) as connection:
    after = snapshot(connection)
expected = json.loads(json.dumps(before))
outcomes = expected.get("outcomes")
if outcomes:
    position = outcomes["columns"].index("status")
    for row in outcomes["rows"]:
        if row[position] == "RUNNING":
            row[position] = "UNKNOWN"
    outcomes["rows"].sort(key=lambda row: json.dumps(row, ensure_ascii=False))
preserved = all(json.dumps(after.get(table), ensure_ascii=False, sort_keys=True) == json.dumps(values, ensure_ascii=False, sort_keys=True) for table, values in expected.items())
first, second = data["first"], data["second"]
checks = {
    "actual_original_v2_database": version == 2,
    "original_database_bytes_unchanged": digest(a.source_db) == source_hash,
    "verified_final_product": digest(a.plugin) == a.expected_plugin_sha256,
    "production_constructor_upgraded_to_v3": first["version"] == second["version"] == 3,
    "all_original_columns_and_payloads_preserved": preserved,
    "real_surviving_choices_were_imported": len(first["choices"]) > 0,
    "legacy_time_and_committer_remain_unknown": first["invalid_legacy"] == second["invalid_legacy"] == 0,
    "second_open_preserves_every_log_sequence_and_id": first["choices"] == second["choices"],
    "no_invented_legacy_causal_ancestry": first["causes"] == second["causes"] == 0,
    "foreign_keys_and_integrity_ok": first["foreign_keys"] == second["foreign_keys"] == [] and first["integrity"] == second["integrity"] == "ok",
    "actual_compile_and_probe_exit_zero": compile_result.returncode == probe.returncode == 0,
}
summary = {"status": "PASS" if all(checks.values()) else "FAIL", "plugin_sha256": a.expected_plugin_sha256,
           "source_database_sha256": source_hash, "source_version": version, "target_version": second["version"],
           "imported_log_rows": len(first["choices"]), "original_tables": sorted(before), "checks": checks,
           "scope": "Read-only backup of actual phase-one Paper database; production SqliteStore constructor, not a server/native quest migration"}
path = ROOT / "docs/verification/phase-two-v2-upgrade-summary.json"
path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
lines = ["# Actual phase-one V2 database upgrade", "", "Result: **" + summary["status"] + "**.", "",
         "- Product SHA-256: `" + a.expected_plugin_sha256 + "`.",
         "- Original database SHA-256: `" + source_hash + "`.",
         "- Source schema 2, production target schema " + str(second["version"]) + ".",
         "- Imported surviving choice log rows: " + str(len(first["choices"])) + ".", "",
         "The source was the actual normally stopped phase-one Paper database. Python opened it read-only and created a consistent backup; JDBC only opened the fresh isolated clone. The real production constructor opened that clone twice. Old table columns and every payload were compared, with only the established RUNNING-to-UNKNOWN outcome transition allowed.", "",
         "| Check | Result |", "| --- | --- |"]
lines += ["| " + name + " | " + ("PASS" if passed else "FAIL") + " |" for name, passed in checks.items()]
lines += ["", "Compilation and Java probe process exit codes were observed directly. Full before/after data remains in the ignored isolated run, not in the public evidence directory.", "",
          "Scope: Chronicle schema/choice migration only. This does not claim an upstream native quest migration, external reward replay, full server boot, crash or power-loss proof.", ""]
(ROOT / "docs/verification/phase-two-v2-upgrade-report.md").write_text("\n".join(lines), encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False))
if not all(checks.values()):
    raise SystemExit("Upgrade verification failed; original source was never opened by JDBC")
