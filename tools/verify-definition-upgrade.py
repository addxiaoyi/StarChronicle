"""Verify production schema-4 upgrades of real, normally stopped schema-2/3 databases.

The original is opened only with mode=ro&immutable=1 after refusing non-empty WAL/journal
sidecars. Only SQLite's consistent backup is sent to JDBC. Run this tool in the shared
exclusive JVM window after the final plugin fingerprint is frozen; it starts no server.
"""
from pathlib import Path
import argparse
import base64
import copy
import hashlib
import json
import os
import re
import shutil
import sqlite3
import struct
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[1]
TARGET_SCHEMA = 4
SIDECARS = ("", "-wal", "-shm", "-journal")


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def file_states(source):
    result = {}
    for suffix in SIDECARS:
        path = Path(str(source) + suffix)
        result[suffix or "database"] = ({"exists": True, "bytes": path.stat().st_size,
                                         "sha256": digest(path)} if path.is_file() else {"exists": False})
    return result


def encoded(value):
    if value is None:
        return {"type": "null", "value": None}
    if isinstance(value, bytes):
        return {"type": "blob", "value": base64.b64encode(value).decode("ascii")}
    if isinstance(value, int):
        return {"type": "integer", "value": str(value)}
    if isinstance(value, float):
        return {"type": "real", "value": struct.pack(">d", value).hex()}
    if isinstance(value, str):
        return {"type": "text", "value": value}
    raise ValueError("Unsupported SQLite value type: " + type(value).__name__)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def normalize_tables(tables):
    result = copy.deepcopy(tables)
    for table in result.values():
        table["rows"].sort(key=canonical)
    return result


def snapshot(connection):
    names = [row[0] for row in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    result = {}
    for name in names:
        quoted = '"' + name.replace('"', '""') + '"'
        cursor = connection.execute("SELECT * FROM " + quoted)
        result[name] = {"columns": [column[0] for column in cursor.description],
                        "rows": [[encoded(value) for value in row] for row in cursor]}
    return normalize_tables(result)


def schema_objects(connection):
    return [list(row) for row in connection.execute(
        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name")]


def original_rows(table):
    return [dict(zip(table["columns"], (item["value"] for item in row))) for row in table["rows"]]


def expected_catalog(before):
    result = {}
    sources = [("instances", "json", "LEGACY_INSTANCE "),
               ("story_triggers", "story_json", "PENDING_TRIGGER ")]
    for table_name, payload_column, prefix in sources:
        for row in original_rows(before.get(table_name, {"columns": [], "rows": []})):
            payload = json.loads(row[payload_column])
            definition = payload["definition"] if table_name == "instances" else payload
            key = (definition["id"], definition["version"])
            signature = canonical(definition)
            result.setdefault(key, {}).setdefault(signature, set()).add(prefix + row["id"])
    return result


def inspect_catalog(after, metadata, expected):
    actual = {}
    for row in original_rows(after.get("story_definitions", {"columns": [], "rows": []})):
        definition = json.loads(row["json"])
        key = (row["story"], row["version"])
        if key != (definition["id"], definition["version"]):
            raise ValueError("Catalog identity does not match its actual definition")
        signature = canonical(definition)
        if not re.fullmatch(r"[0-9a-f]{64}", row["fingerprint"]) or not row["first_seen"]:
            raise ValueError("Missing catalog fingerprint/archive observation time")
        if row["provenance"] not in expected.get(key, {}).get(signature, set()):
            raise ValueError("Catalog variant/provenance lacks a real original snapshot: " + "/".join(key))
        if signature in actual.setdefault(key, {}):
            raise ValueError("Duplicate equivalent catalog variant")
        actual[key][signature] = row
    exact = {key: set(values) for key, values in actual.items()} == {key: set(values) for key, values in expected.items()}
    expected_conflicts = {key for key, variants in expected.items() if len(variants) > 1}
    reported = {(row["story"], row["version"]): row for row in metadata}
    reported_conflicts = {key for key, row in reported.items() if row["status"] == "CONFLICT_RETAINED"}
    metadata_exact = set(reported) == set(actual) and all(
        len(reported[key]["variants"]) == len(variants)
        and {item["fingerprint"] for item in reported[key]["variants"]} == {row["fingerprint"] for row in variants.values()}
        and (reported[key]["status"] == "UNIQUE") == (len(variants) == 1)
        for key, variants in actual.items())
    conflicts = [reported[key] for key in sorted(reported_conflicts)]
    return exact, metadata_exact and reported_conflicts == expected_conflicts, conflicts, len(actual), sum(map(len, actual.values()))


def expected_original_tables(before):
    expected = copy.deepcopy(before)
    if "outcomes" in expected:
        table = expected["outcomes"]
        position = table["columns"].index("status")
        for row in table["rows"]:
            if row[position] == {"type": "text", "value": "RUNNING"}:
                row[position] = encoded("UNKNOWN")
    return normalize_tables(expected)


def preserve_check(expected, after):
    return all(after.get(name) == table for name, table in expected.items())


def jdk(java_home):
    suffix = ".exe" if os.name == "nt" else ""
    if java_home:
        java_bin = java_home.resolve() / "bin"
    else:
        compiler = shutil.which("javac" + suffix)
        if not compiler:
            raise ValueError("Supply --java-home or JAVA_HOME for the Java 21 compiler/runtime")
        java_bin = Path(compiler).resolve().parent
    java, javac = java_bin / ("java" + suffix), java_bin / ("javac" + suffix)
    if not java.is_file() or not javac.is_file():
        raise ValueError("Java compiler/runtime not found together in " + str(java_bin))
    return java, javac


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", "--source-db", dest="source", required=True, type=Path)
    parser.add_argument("--source-schema", type=int, choices=(2, 3), help="Optional assertion; otherwise detect actual source 2/3")
    parser.add_argument("--plugin", type=Path, default=ROOT / "target/starchronicle-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--java-home", type=Path, default=Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    parser.add_argument("--expected-plugin-sha256", required=True)
    parser.add_argument("--run-name", default="definition-upgrade-final")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if not re.fullmatch(r"definition-upgrade-[a-z0-9][a-z0-9-]{0,47}", args.run_name):
        raise SystemExit("Run name must use the isolated definition-upgrade- prefix")
    expected_hash = args.expected_plugin_sha256.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_hash) or args.timeout < 1:
        raise SystemExit("Invalid expected plugin SHA-256 or timeout")
    source, plugin = args.source.resolve(strict=True), args.plugin.resolve(strict=True)
    run = (ROOT / "run" / args.run_name).resolve()
    summary_path = ROOT / "docs/verification" / (args.run_name + "-summary.json")
    report_path = ROOT / "docs/verification" / (args.run_name + "-report.md")
    if not run.is_relative_to(ROOT.resolve()) or run.exists() or summary_path.exists() or report_path.exists():
        raise SystemExit("Refusing unsafe/existing isolated run or evidence destination")
    if not source.is_file() or not plugin.is_file() or digest(plugin) != expected_hash:
        raise SystemExit("Source/product missing or product fingerprint mismatch")
    with source.open("rb") as stream:
        if stream.read(16) != b"SQLite format 3\x00":
            raise SystemExit("Source is not an actual SQLite database")
    with zipfile.ZipFile(plugin) as jar:
        for name in ("io/github/addxiaoyi/starchronicle/core/DefinitionCatalog.class", "io/github/addxiaoyi/starchronicle/persistence/SqliteStore.class"):
            if name not in jar.namelist() or int.from_bytes(jar.read(name)[6:8], "big") != 65:
                raise SystemExit("The product must contain the Java 21 schema-4 implementation")
    java, javac = jdk(args.java_home)
    source_before = file_states(source)
    for suffix in ("-wal", "-journal"):
        if source_before[suffix].get("bytes", 0):
            raise SystemExit("Refusing non-empty source " + suffix + "; supply a genuinely consistent checkpointed backup. This tool never checkpoints/writes the source")
    run.mkdir(parents=True)
    checks, extra, failure = {}, {}, None
    version = None
    try:
        baseline = run / "chronicle-readonly-backup.db"
        clone = run / "chronicle-upgrade-clone.db"
        # No WAL is ignored: non-empty WAL was refused. Immutable read-only mode also avoids
        # creating/updating SHM/WAL in the normally stopped original server directory.
        with sqlite3.connect(source.as_uri() + "?mode=ro&immutable=1", uri=True) as original:
            version = original.execute("PRAGMA user_version").fetchone()[0]
            if version not in (2, 3) or args.source_schema is not None and version != args.source_schema:
                raise ValueError("Expected an actual source schema 2/3 matching --source-schema; found " + str(version))
            before = snapshot(original)
            before_schema = schema_objects(original)
            source_integrity = [list(row) for row in original.execute("PRAGMA integrity_check")]
            source_foreign_keys = [list(row) for row in original.execute("PRAGMA foreign_key_check")]
            if source_integrity != [["ok"]]:
                raise ValueError("Source integrity check failed: " + repr(source_integrity))
            if source_foreign_keys:
                raise ValueError("Source foreign-key check failed: " + repr(source_foreign_keys))
            with sqlite3.connect(baseline) as backup:
                original.backup(backup)
                # Normalize only the owned backup to a standalone main file before copying it;
                # no pending destination WAL may be silently omitted from the JDBC clone.
                if backup.execute("PRAGMA journal_mode=DELETE").fetchone()[0].lower() != "delete":
                    raise ValueError("Could not make the owned backup a standalone SQLite image")
        if file_states(source) != source_before:
            raise ValueError("Original database/sidecars changed during read-only backup; source must remain normally stopped")
        with sqlite3.connect(baseline.as_uri() + "?mode=ro&immutable=1", uri=True) as backup:
            if snapshot(backup) != before or schema_objects(backup) != before_schema:
                raise ValueError("SQLite backup differs from the original source snapshot")
        shutil.copyfile(baseline, clone)
        (run / "before.json").write_text(json.dumps({"version": version, "tables": before, "schema_objects": before_schema, "foreign_keys": source_foreign_keys, "integrity": source_integrity}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        classes = run / "classes"
        classes.mkdir()
        compiled = subprocess.run([str(javac), "-J-Xmx128m", "-J-Dfile.encoding=UTF-8", "--release", "21", "-encoding", "UTF-8", "-cp", str(plugin), "-d", str(classes), str(ROOT / "tools/DefinitionUpgradeProbe.java")], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=args.timeout)
        (run / "compile.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
        checks["actual_probe_compilation_exit_zero"] = compiled.returncode == 0
        if compiled.returncode:
            raise ValueError("Probe compilation failed; inspect compile.log")
        probed = subprocess.run([str(java), "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join((str(plugin), str(classes))), "DefinitionUpgradeProbe", str(clone)], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=args.timeout)
        (run / "probe.log").write_text(probed.stdout + probed.stderr, encoding="utf-8")
        checks["actual_production_probe_exit_zero"] = probed.returncode == 0
        if probed.returncode:
            raise ValueError("Production upgrade probe failed; inspect probe.log")
        metadata = json.loads(probed.stdout.strip().splitlines()[-1])
        first = json.loads((run / "first-open.json").read_text(encoding="utf-8"))
        second = json.loads((run / "second-open.json").read_text(encoding="utf-8"))
        first_tables, second_tables = normalize_tables(first["tables"]), normalize_tables(second["tables"])
        expected = expected_original_tables(before)
        sources = expected_catalog(before)
        exact, conflicts_reported, conflicts, key_count, variant_count = inspect_catalog(first_tables, first["catalog"], sources)
        second_exact, second_conflicts_reported, _, _, _ = inspect_catalog(second_tables, second["catalog"], sources)
        plan = first["origin_query"]
        original_schema_preserved = all(row in first["schema_objects"] and row in second["schema_objects"] for row in before_schema)
        checks.update({
            "actual_source_schema_2_or_3": version in (2, 3),
            "source_schema_matches_requested_assertion": args.source_schema is None or version == args.source_schema,
            "consistent_readonly_backup_matches_source": True,
            "source_foreign_keys_and_integrity_ok": source_foreign_keys == [] and source_integrity == [["ok"]],
            "compiled_and_production_schema_are_4": metadata["compiled_schema_version"] == first["version"] == second["version"] == TARGET_SCHEMA,
            "every_original_table_column_and_typed_value_preserved_first_open": preserve_check(expected, first_tables),
            "every_original_table_column_and_typed_value_preserved_second_open": preserve_check(expected, second_tables),
            "every_original_schema_object_preserved": original_schema_preserved,
            "catalog_contains_exact_actual_canonical_definition_variants": exact and second_exact,
            "catalog_conflicts_are_retained_and_reported_without_winners": conflicts_reported and second_conflicts_reported,
            "origin_expression_index_is_used_for_real_instance_tuple": plan["sample_instance_id"] is not None and plan["sample_instance_id"] in plan["matched_instance_ids"] and any("instance_origin" in line for line in plan["plan"]),
            "foreign_keys_and_integrity_ok": first["foreign_keys"] == second["foreign_keys"] == [] and first["integrity"] == second["integrity"] == [["ok"]],
            "second_open_adds_or_changes_no_table_value": first_tables == second_tables,
            "second_open_adds_or_changes_no_schema_or_catalog_metadata": first["schema_objects"] == second["schema_objects"] and first["catalog"] == second["catalog"],
        })
        if "story_choice_log" in before:
            checks["existing_choice_log_rows_ids_and_sequences_preserved"] = first_tables["story_choice_log"] == before["story_choice_log"]
        else:
            expected_choices = sum(len(json.loads(row["json"])["choices"]) for row in original_rows(before.get("instances", {"columns": [], "rows": []})))
            choices = original_rows(first_tables["story_choice_log"])
            checks["new_legacy_choices_match_actual_surviving_snapshot_count"] = len(choices) == expected_choices
            checks["new_legacy_choice_times_and_committers_remain_unknown"] = all(row["provenance"] == "LEGACY_INSTANCE" and row["chosen_at"] is None and row["committed_by"] is None for row in choices)
        extra.update({"baseline_database_sha256": digest(baseline), "upgraded_clone_sha256": digest(clone), "java_version": metadata["java_version"], "jdbc_driver": first["jdbc_driver"], "original_tables": sorted(before), "catalog_key_count": key_count, "catalog_variant_count": variant_count, "catalog_conflicts": conflicts, "origin_query": plan})
    except Exception as error:
        failure = type(error).__name__ + ": " + str(error)
        checks["completed_all_upgrade_observations"] = False
    finally:
        try:
            source_after = file_states(source)
        except Exception as error:
            source_after = {"inspection_error": type(error).__name__ + ": " + str(error)}
            failure = failure or "Could not recheck original database/sidecars"
        checks["original_database_and_all_sidecar_bytes_states_unchanged"] = source_after == source_before
        try:
            checks["final_product_fingerprint_unchanged"] = digest(plugin) == expected_hash
        except Exception as error:
            checks["final_product_fingerprint_unchanged"] = False
            failure = failure or "Could not recheck final product: " + str(error)
        summary = {"status": "PASS" if failure is None and checks and all(checks.values()) else "FAIL", "plugin_sha256": expected_hash,
                   "source_database": str(source), "source_database_sha256": source_before["database"]["sha256"],
                   "source_schema_version": version, "target_schema_version": TARGET_SCHEMA,
                   "source_files_before": source_before, "source_files_after": source_after,
                   "checks": checks, "error": failure, **extra,
                   "scope": "Read-only immutable backup of the explicitly supplied, normally stopped real schema-2/3 database. Production SqliteStore opens only the isolated clone twice; no server, upstream task migration, reward replay or power-loss certification."}
        summary_path.parent.mkdir(parents=True, exist_ok=True)
        summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (run / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        lines = ["# Actual definition catalog upgrade", "", "Result: **" + summary["status"] + "**.", "",
                 "- Product SHA-256: `" + expected_hash + "`.", "- Original database SHA-256: `" + summary["source_database_sha256"] + "`.",
                 "- Actual source schema: " + str(version) + "; target schema: 4.", "",
                 "The original was read only with `mode=ro&immutable=1`; non-empty WAL/rollback journals are refused. Main database and all sidecar presence, sizes and hashes were recorded before/after. JDBC received only the specially named isolated clone of SQLite's consistent backup.", "",
                 "Every original table, including existing choice log and sqlite_sequence when present, was compared by columns and typed values. JSON, hashes and times remain exact text values. Only the established outcomes RUNNING→UNKNOWN transition is allowed. Full data snapshots stay in the ignored run directory.", "",
                 "| Check | Result |", "| --- | --- |"]
        lines += ["| " + name + " | " + ("PASS" if passed else "FAIL") + " |" for name, passed in checks.items()]
        lines += ["", "## Retained definition conflicts", ""]
        if extra.get("catalog_conflicts"):
            lines += ["Conflicts were **retained**, not resolved by current-file or latest-version precedence. A preservation PASS does not certify that conflicting current configuration can load.", "", "```json", json.dumps(extra["catalog_conflicts"], ensure_ascii=False, indent=2), "```"]
        elif failure is None:
            lines += ["No conflicting canonical variants were present in this source's retained definitions."]
        else:
            lines += ["The complete catalog observation did not finish; no conflict-free claim is made."]
        if failure:
            lines += ["", "Error: `" + failure.replace("`", "'") + "`."]
        lines += ["", "Scope: Chronicle production schema/catalog migration only. This is not a server startup, native quest migration, external state mutation, reward delivery or crash/power-loss test.", ""]
        report_path.write_text("\n".join(lines), encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=False))
    return 0 if summary["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
