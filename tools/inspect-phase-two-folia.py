"""Capture one consistent, read-only schema-3 Folia acceptance snapshot.

This tool never opens SQLite in write mode and never starts a JVM. Its default
outputs remain in the explicitly selected isolated runtime, not docs/verification.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import sqlite3


ROOT = Path(__file__).resolve().parents[1]
PREFIX = "phase-two-folia"
TABLES = {
    "events": ("id", "type", "time", "hash", "json"),
    "event_players": ("event_id", "player"),
    "event_nations": ("event_id", "nation"),
    "instances": ("id", "story", "scope", "completed", "updated", "json"),
    "outcomes": ("id", "status", "json"),
    "facts": ("owner", "key", "value"),
    # Polling checked is liveness metadata rather than a game result.
    "story_triggers": ("id", "trigger_key", "event_id", "scope", "story_json"),
    "story_trigger_receipts": ("trigger_key", "scope"),
    "event_causes": ("event_id", "parent_id", "root_id", "depth", "story_path"),
    "story_choice_log": ("seq", "id", "instance_id", "source_event_id", "story_id", "story_version", "node_id", "kind", "actor_key", "player_id", "nation_id", "scope_key", "revision", "choice_id", "chosen_at", "committed_by", "provenance"),
    "story_trigger_blocks": ("id", "event_id", "story", "version", "reason"),
    "memory_sites": ("id", "event_id", "json"),
    "native_scene_journal": ("id", "player", "status", "revision", "json"),
}


def sha256(path: Path) -> str:
    if not path.is_file():
        raise ValueError(f"Missing evidence input: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True, type=Path)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--stage", required=True, choices=("before-restart", "after-complete", "after-replay", "restored"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--fixture-jar", type=Path)
    parser.add_argument("--fixture-source", type=Path)
    args = parser.parse_args()
    expected = args.expected_sha.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("--expected-sha must contain one complete SHA-256")
    run = args.run_dir.resolve()
    if not run.is_dir():
        raise ValueError(f"Missing isolated runtime: {run}")
    product = run / "plugins" / "StarChronicle.jar"
    server = run / "folia.jar"
    fixture = (args.fixture_jar or run / "plugins" / "PhaseTwoFoliaFixture.jar").resolve()
    fixture_source = (args.fixture_source or run / "fixture" / "PhaseTwoFoliaFixture.java").resolve()
    hashed = {"plugin_sha256": (product, sha256(product)), "server_sha256": (server, sha256(server)),
              "fixture_sha256": (fixture, sha256(fixture)), "fixture_source_sha256": (fixture_source, sha256(fixture_source))}
    if hashed["plugin_sha256"][1] != expected:
        raise ValueError("Runtime product does not match the explicitly approved JAR hash")
    database = run / "plugins" / "StarChronicle" / "chronicle.db"
    if not database.is_file():
        raise ValueError(f"Missing database; refusing to create it: {database}")
    now = datetime.now(timezone.utc)
    try:
        label = run.relative_to(ROOT).as_posix()
    except ValueError:
        label = str(run)
    data = {
        "format_version": 1, "stage": args.stage, "captured_utc": now.isoformat(),
        "captured_asia_shanghai": now.astimezone(timezone(timedelta(hours=8))).isoformat(),
        "scope": "StarChronicle standalone on real Folia; NOT StarCore full-stack certification",
        "run_directory": label, "run_path": str(run), "database_open_mode": "ro; query_only=ON; consistent read transaction",
        "excluded_columns": {"story_triggers": ["checked"]},
        "tool_source_sha256": sha256(Path(__file__).resolve()),
        **{key: value[1] for key, value in hashed.items()},
    }
    with sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True, timeout=5) as db:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA query_only=ON")
        db.execute("BEGIN")
        data["schema_version"] = db.execute("PRAGMA user_version").fetchone()[0]
        if data["schema_version"] != 3:
            raise ValueError(f"Expected schema 3, found {data['schema_version']}")
        present = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        missing = set(TABLES) - present
        if missing:
            raise ValueError("Incomplete schema-3 evidence: " + ", ".join(sorted(missing)))
        data["table_columns"] = {}
        for table, columns in TABLES.items():
            actual = {row[1] for row in db.execute(f'PRAGMA table_info("{table}")')}
            if not set(columns).issubset(actual):
                raise ValueError(f"Missing expected columns in {table}: {set(columns) - actual}")
            select = ",".join(f'"{column}"' for column in columns)
            data[table] = [dict(row) for row in db.execute(f'SELECT {select} FROM "{table}" ORDER BY {select}')]
            data["table_columns"][table] = list(columns)
        data["integrity"] = [row[0] for row in db.execute("PRAGMA integrity_check")]
        data["foreign_key_violations"] = [list(row) for row in db.execute("PRAGMA foreign_key_check")]
        db.rollback()
    for key, (path, before) in hashed.items():
        if sha256(path) != before:
            raise ValueError(f"Evidence file changed during capture: {key}")
    data["row_counts"] = {table: len(data[table]) for table in TABLES}
    output = (args.output or run / "evidence" / f"{PREFIX}-{args.stage}.json").resolve()
    if output == database.resolve():
        raise ValueError("Output cannot replace the database")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "stage": args.stage, "plugin_sha256": expected,
                      "schema_version": data["schema_version"], "integrity": data["integrity"], "row_counts": data["row_counts"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, sqlite3.Error) as error:
        raise SystemExit(f"Read-only Folia capture refused: {error}")
