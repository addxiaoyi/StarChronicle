"""Upgrade only an isolated clone of a normally stopped, actual schema-5 database.

Uses the frozen production JAR twice. Original files and sidecars are read-only and hashed
before/after; all released tables, payload bytes, DDL, national seats and receipt rows must
survive unchanged (the existing RUNNING -> UNKNOWN recovery rule is explicit).
Starts javac/Java only when the coordinator invokes this verification in its JVM window.
"""
from pathlib import Path
import argparse
import importlib.util
import json
import shutil
import sqlite3
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("released_upgrade_helpers", ROOT / "tools/verify-definition-upgrade.py")
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-db", required=True, type=Path)
    parser.add_argument("--expected-plugin-sha256", required=True)
    parser.add_argument("--java-home", required=True, type=Path)
    args = parser.parse_args()
    plugin = ROOT / "target/starchronicle-0.1.0-SNAPSHOT.jar"
    expected = args.expected_plugin_sha256
    if len(expected) != 64 or helpers.digest(plugin) != expected:
        raise SystemExit("Frozen plugin fingerprint does not match")
    source = args.source_db.resolve(strict=True)
    run = ROOT / "run/definition-upgrade-quest-schema5"
    if run.exists():
        raise SystemExit("Refusing to overwrite an existing migration run")
    original_files = helpers.file_states(source)
    if any(original_files[suffix].get("bytes", 0) for suffix in ("-wal", "-journal")):
        raise SystemExit("Source must be normally stopped with no nonempty WAL/journal")
    run.mkdir()
    clone = run / "chronicle-upgrade-clone.db"
    result = {"product_sha256": expected, "source": str(source), "source_files_before": original_files,
              "source_schema": 5, "target_schema": 6, "checks": {}}
    failure = None
    try:
        with sqlite3.connect(source.as_uri() + "?mode=ro&immutable=1", uri=True) as connection:
            if connection.execute("PRAGMA user_version").fetchone()[0] != 5:
                raise ValueError("This probe requires an actual released schema-5 source")
            before = helpers.snapshot(connection)
            before_schema = helpers.schema_objects(connection)
            if connection.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
                raise ValueError("Original database integrity failed")
            if connection.execute("PRAGMA foreign_key_check").fetchall():
                raise ValueError("Original database foreign keys failed")
            if "quest_gates" in before or "quest_gate_completions" in before:
                raise ValueError("Source already has future quest tables")
            with sqlite3.connect(clone) as backup:
                connection.backup(backup)
                backup.execute("PRAGMA journal_mode=DELETE")
        write(run / "before.json", {"tables": before, "schema_objects": before_schema})
        classes = run / "classes"
        classes.mkdir()
        java, javac = helpers.jdk(args.java_home)
        compile_command = [str(javac), "-J-Xmx256m", "-encoding", "UTF-8", "-cp", str(plugin),
                           "-d", str(classes), str(ROOT / "tools/DefinitionUpgradeProbe.java")]
        with (run / "compile.log").open("w", encoding="utf-8") as log:
            subprocess.run(compile_command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
        import os
        command = [str(java), "-Xmx256m", "-Dfile.encoding=UTF-8", "-cp",
                   str(classes) + os.pathsep + str(plugin), "DefinitionUpgradeProbe", str(clone)]
        with (run / "probe.log").open("w", encoding="utf-8") as log:
            subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
        first = json.loads((run / "first-open.json").read_text(encoding="utf-8"))
        second = json.loads((run / "second-open.json").read_text(encoding="utf-8"))
        tables = helpers.normalize_tables(first["tables"])
        expected_tables = helpers.expected_original_tables(before)
        checks = result["checks"]
        checks["production_schema6"] = first["version"] == second["version"] == 6
        checks["every_released_table_and_typed_payload_preserved"] = helpers.preserve_check(expected_tables, tables)
        checks["released_ddl_preserved"] = all(item in first["schema_objects"] for item in before_schema)
        checks["only_two_additive_tables"] = set(tables) - set(before) == {"quest_gates", "quest_gate_completions"}
        checks["no_invented_native_receipts"] = not tables["quest_gates"]["rows"] and not tables["quest_gate_completions"]["rows"]
        checks["reopen_exactly_idempotent"] = tables == helpers.normalize_tables(second["tables"]) and first["schema_objects"] == second["schema_objects"]
        checks["catalog_identity_preserved_across_reopen"] = first["catalog"] == second["catalog"]
        checks["foreign_keys_clean_both_opens"] = not first["foreign_keys"] and not second["foreign_keys"]
        checks["integrity_clean_both_opens"] = first["integrity"] == second["integrity"] == [["ok"]]
        result["original_table_rows"] = {name: len(table["rows"]) for name, table in before.items()}
        result["probe_source_sha256"] = helpers.digest(ROOT / "tools/DefinitionUpgradeProbe.java")
        result["jdbc_driver"] = first["jdbc_driver"]
    except Exception as error:
        failure = repr(error)
    finally:
        result["source_files_after"] = helpers.file_states(source)
        result["checks"]["original_files_untouched"] = result["source_files_after"] == original_files
        result["product_still_frozen"] = helpers.digest(plugin) == expected
        result["failure"] = failure
        result["passed"] = failure is None and result["product_still_frozen"] and all(result["checks"].values())
        write(run / "summary.json", result)
        print(json.dumps(result, ensure_ascii=False))
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
