"""Evaluate retained phase-two standalone Folia evidence; never start or stop a JVM.

Run only after collecting the four read-only snapshots, three complete server logs,
protocol client log, and observed process exits in the selected runtime/evidence.
Missing inputs are fatal; no previous verification report is read or overwritten.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PREFIX = "phase-two-folia"
STAGES = ("before-restart", "after-complete", "after-replay", "restored")
TABLES = ("events", "event_players", "event_nations", "instances", "outcomes", "facts", "story_triggers",
          "story_trigger_receipts", "event_causes", "story_choice_log", "story_trigger_blocks", "memory_sites", "native_scene_journal")
SERVER_SHA = "f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39"
SOURCE_ID = "phase-two-folia:heir-discovery-1"
SUPPORTED = "HEIR_RESTORATION_SUPPORTED"
PERSONAL = "past.player.choice.last-heir/1/council"
WORLD_COUNCIL = "past.resolution.world.last-heir/1/council"
WORLD_END = "past.resolution.world.last-heir/1/preparations"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def parse_protocol(text: str):
    result = []
    for line in text.splitlines():
        match = re.match(r"^\S+ ([A-Z_]+) (\{.*\})$", line)
        if match:
            result.append((match[1], json.loads(match[2])))
    return result


def parse_server(text: str):
    result = []
    for line in text.splitlines():
        match = re.search(r"PHASE_TWO_([A-Z_]+) (\{.*\})\s*$", line)
        if match:
            result.append((match[1], json.loads(match[2])))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    expected = args.expected_sha.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("--expected-sha must be a complete approved product SHA-256")
    run = args.run_dir.resolve()
    evidence = run / "evidence"
    output = (args.output_dir or evidence).resolve()
    snapshots = {stage: read_json(evidence / f"{PREFIX}-{stage}.json") for stage in STAGES}
    logs = [(evidence / f"{PREFIX}-boot{boot}.log").read_text(encoding="utf-8") for boot in (1, 2, 3)]
    client_text = (evidence / f"{PREFIX}-clients.log").read_text(encoding="utf-8")
    processes = read_json(evidence / f"{PREFIX}-processes.json")
    protocol = parse_protocol(client_text)
    markers = [parse_server(log) for log in logs]
    before, after, replay, restored = (snapshots[stage] for stage in STAGES)
    checks, details = {}, {}

    def check(name, operation):
        try:
            passed = bool(operation())
        except (KeyError, ValueError, TypeError, StopIteration, IndexError, AssertionError) as error:
            passed = False
            details[name] = f"{type(error).__name__}: {error}"
        checks[name] = passed

    def records(snapshot, story):
        return [json.loads(row["json"]) for row in snapshot["instances"] if row["story"] == story]

    def heir(snapshot):
        rows = records(snapshot, "last-heir")
        if len(rows) != 1:
            raise ValueError(f"Expected one last-heir instance, found {len(rows)}")
        return rows[0]

    def event(snapshot, event_id):
        rows = [row for row in snapshot["events"] if row["id"] == event_id]
        if len(rows) != 1:
            raise ValueError(f"Expected one event {event_id}")
        return rows[0]

    def typed_event(snapshot, event_type):
        rows = [row for row in snapshot["events"] if row["type"] == event_type]
        if len(rows) != 1:
            raise ValueError(f"Expected one event type {event_type}, found {len(rows)}")
        return rows[0]

    def facts(snapshot):
        return {(row["owner"], row["key"]): row["value"] for row in snapshot["facts"]}

    def site_map(snapshot):
        return {row["id"]: json.loads(row["json"]) for row in snapshot["memory_sites"]}

    def server_rows(name, boots=(1, 2, 3)):
        return [(boot, data) for boot in boots for marker, data in markers[boot - 1] if marker == name]

    def client_rows(name, boot=None, player=None):
        return [data for marker, data in protocol if marker == name and (boot is None or data.get("boot") == boot) and (player is None or data.get("player") == player)]

    # The UUID map comes from real protocol logins, never from a made-up PlayerContext.
    spawn_ids = {label: {row.get("uuid") for row in client_rows("SPAWN", player=label)} for label in ("A", "B")}
    identities = {label: next(iter(ids)) for label, ids in spawn_ids.items() if len(ids) == 1 and None not in ids}
    players = set(identities.values())
    combined = "\n".join(logs)

    check("approved_product_hash_in_every_capture_and_runtime", lambda: all(snapshot["plugin_sha256"] == expected for snapshot in snapshots.values()) and sha256(run / "plugins" / "StarChronicle.jar") == expected)
    check("captures_belong_to_one_selected_runtime_and_stage", lambda: all(Path(snapshot["run_path"]).resolve() == run and snapshot["stage"] == stage for stage, snapshot in snapshots.items()))
    check("official_folia_server_hash_in_every_capture", lambda: all(snapshot["server_sha256"] == SERVER_SHA for snapshot in snapshots.values()) and sha256(run / "folia.jar") == SERVER_SHA)
    check("same_fixture_binary_and_source_across_all_boots", lambda: all(len({snapshot[key] for snapshot in snapshots.values()}) == 1 for key in ("fixture_sha256", "fixture_source_sha256")) and after["fixture_sha256"] == sha256(run / "plugins" / "PhaseTwoFoliaFixture.jar") and after["fixture_source_sha256"] == sha256(run / "fixture" / "PhaseTwoFoliaFixture.java"))
    check("schema3_readonly_consistent_snapshots", lambda: all(snapshot["schema_version"] == 3 and snapshot["database_open_mode"] == "ro; query_only=ON; consistent read transaction" and set(TABLES).issubset(snapshot) and snapshot["excluded_columns"] == {"story_triggers": ["checked"]} for snapshot in snapshots.values()))
    check("sqlite_integrity_and_foreign_keys_clean", lambda: all(snapshot["integrity"] == ["ok"] and snapshot["foreign_key_violations"] == [] for snapshot in snapshots.values()))

    def bundled_stories_match():
        with zipfile.ZipFile(run / "plugins" / "StarChronicle.jar") as archive:
            names = {name[len("stories/"):]: archive.read(name) for name in archive.namelist() if name.startswith("stories/") and name.endswith((".yml", ".yaml", ".json"))}
            npc_resource = archive.read("npcs.yml")
        actual = {path.name: path.read_bytes() for path in (run / "plugins" / "StarChronicle" / "stories").iterdir() if path.is_file() and path.suffix in (".yml", ".yaml", ".json")}
        return len(names) == 12 and names == actual and "last-heir.yml" in names and "heir-legacy.yml" in names and npc_resource == (run / "plugins" / "StarChronicle" / "npcs.yml").read_bytes()

    check("exactly_twelve_unchanged_stories_and_default_npc_resource", bundled_stories_match)
    check("twelve_definitions_validated_on_every_boot", lambda: all("Loaded 12 validated stories" in log for log in logs))
    check("real_folia_1_21_11_build14_on_three_boots", lambda: all("Loading Folia 1.21.11-14-" in log for log in logs))
    check("two_actual_region_threads_on_three_boots", lambda: all("initial 2 target thread(s)" in log for log in logs))
    check("standalone_scope_without_starcore_on_every_boot", lambda: all("STANDALONE MODE" in log for log in logs) and not (run / "plugins" / "StarCore.jar").exists())
    check("ten_minute_poll_interval_preserved", lambda: re.search(r"(?m)^poll-ticks:\s*12000\s*$", (run / "plugins" / "StarChronicle" / "config.yml").read_text(encoding="utf-8")) is not None)
    check("two_stable_real_client_identities_each_boot", lambda: len(players) == 2 and all(len(client_rows("SPAWN", boot, label)) == 1 for boot in (1, 2, 3) for label in ("A", "B")) and sum(name == "SPAWN" for name, _ in protocol) == 6)
    check("different_region_ownership_observed", lambda: any(data.get("playerOwned") is True and data.get("otherOwned") is False for _, data in server_rows("SEPARATION")))
    def actual_processes_recorded():
        boots = processes["boots"]
        if processes["plugin_sha256"] != expected or processes["fixture_compile_exit_code"] != 0 or [row["boot"] for row in boots] != [1, 2, 3]:
            return False
        pids = [row[key] for row in boots for key in ("server_pid", "client_pid")]
        return len(set(pids)) == 6 and all(isinstance(pid, int) and pid > 0 for pid in pids) and all(
            not row.get("launcher_problem") and row["server_exit_code"] == row["client_exit_code"] == 0
            and row["plugin_sha256_after"] == expected and row["all_owned_children_exited"] is True for row in boots)

    check("actual_server_and_client_processes_recorded_without_launcher_or_hash_drift", actual_processes_recorded)
    check("three_servers_and_three_clients_exited_zero", lambda: processes["server_exit_codes"] == [0, 0, 0] and processes["client_exit_codes"] == [0, 0, 0])
    check("normal_quit_stop_and_all_owned_children_exit_recorded", lambda: processes["normal_console_stop_each_server"] is True and processes["clients_quit_normally_each_boot"] is True and processes["all_owned_children_exited"] is True and all(row["normal_console_stop_requested"] is True and row["clients_quit_requested"] is True for row in processes["boots"]) and all("Saved all player data" in log and "All RegionFile I/O tasks to complete" in log for log in logs))
    check("each_real_client_requested_quit_and_ended_once_per_boot", lambda: len(client_rows("QUIT")) == 3 and len(client_rows("QUIT_REQUESTED")) == len(client_rows("ENDED")) == 6 and all(len(client_rows("QUIT", boot)) == 1 and len(client_rows("QUIT_REQUESTED", boot)) == 2 and all(len(client_rows("QUIT_REQUESTED", boot, player)) == len(client_rows("ENDED", boot, player)) == 1 and client_rows("ENDED", boot, player)[0].get("requestedQuit") is True for player in ("A", "B")) for boot in (1, 2, 3)))
    check("source_submitted_with_fresh_api_contexts_only_on_first_boot", lambda: any(data.get("id") == SOURCE_ID and data.get("duplicate") is False and data.get("contextsFresh") is True and set(data.get("players", [])) == players and data.get("online") == 2 for _, data in server_rows("SOURCE", (1,))) and not server_rows("SOURCE", (2,)))
    check("third_boot_replays_the_same_source_as_duplicate", lambda: any(data.get("id") == SOURCE_ID and data.get("duplicate") is True for _, data in server_rows("SOURCE", (3,))))
    check("source_payload_and_hash_unchanged_across_restarts", lambda: all(event(snapshot, SOURCE_ID) == event(before, SOURCE_ID) for snapshot in snapshots.values()))
    check("source_has_real_actors_no_fabricated_nation_and_test_evidence", lambda: (lambda source: set(source["participants"]) == players and source["nations"] == [] and "test-only" in source["evidence"].lower())(json.loads(event(before, SOURCE_ID)["json"])))
    check("source_time_is_not_in_the_future", lambda: datetime.fromisoformat(json.loads(event(before, SOURCE_ID)["json"])["occurredAt"].replace("Z", "+00:00")) <= datetime.fromisoformat(before["captured_utc"]))
    check("same_completed_two_player_heir_instance_survives_all_boots", lambda: len(players) == 2 and all(heir(snapshot)["id"] == heir(before)["id"] and heir(snapshot)["completed"] is True and heir(snapshot)["node"] == "restored" and set(heir(snapshot)["members"]) == players and set(heir(snapshot)["members"].values()) == {""} for snapshot in snapshots.values()))

    def pending_followups():
        supported = typed_event(before, SUPPORTED)["id"]
        return [row for row in before["story_triggers"] if row["event_id"] == supported and json.loads(row["story_json"])["id"] == "heir-legacy"]

    check("derived_followup_pending_for_both_players_before_first_stop", lambda: not records(before, "heir-legacy") and len(pending_followups()) == 2 and {row["scope"] for row in pending_followups()} == {"player:" + player for player in players})

    def followups_are_complete(snapshot):
        rows = records(snapshot, "heir-legacy")
        wanted = {identities["A"]: "personal", identities["B"]: "reconciled"}
        return len(rows) == 2 and all(row["completed"] is True and len(row["members"]) == 1 and row["node"] == wanted[next(iter(row["members"]))] and row["sourceEventId"] == typed_event(snapshot, SUPPORTED)["id"] for row in rows)

    check("login_recovers_two_player_followups_and_distinct_completed_endings", lambda: all(followups_are_complete(snapshot) for snapshot in (after, replay, restored)))
    check("actual_gui_selected_testimony_for_a_and_reconcile_for_b", lambda: all(any(row.get("story") == "heir-legacy" and row.get("choice") == choice and isinstance(row.get("slot"), int) for row in client_rows("GUI_CHOICE", 2, label)) for label, choice in (("A", "testimony"), ("B", "reconcile"))))
    check("pending_triggers_consumed_with_one_receipt_per_player", lambda: all(any(receipt["trigger_key"] == row["trigger_key"] and receipt["scope"] == row["scope"] for receipt in restored["story_trigger_receipts"]) for row in pending_followups()) and not any(json.loads(row["story_json"])["id"] == "heir-legacy" for row in restored["story_triggers"]))
    def real_events_valid():
        events = restored["events"]
        roots = {"PLAYER_JOIN", "NPC_INTERACT"}
        if Counter(row["type"] for row in events if row["type"] not in roots) != Counter({"ANCIENT_HEIR_DISCOVERED": 1, "HEIR_COUNCIL_OPENED": 1, SUPPORTED: 1, "HEIR_PERSONAL_TESTIMONY": 1, "HEIR_RECORDS_RECONCILED": 1}):
            return False
        arrivals = [json.loads(row["json"]) for row in events if row["type"] == "PLAYER_JOIN"]
        interactions = [json.loads(row["json"]) for row in events if row["type"] == "NPC_INTERACT"]
        return len(arrivals) == 2 and {next(iter(row["participants"])) for row in arrivals} == players and all(row["nations"] == [] for row in arrivals) and len(interactions) == 1 and all(
            row["source"] == "NPC_BRIDGE" and "observed uncancelled interaction" in row["evidence"].lower()
            and len(row["participants"]) == 1 and set(row["participants"]).issubset(players) and row["nations"] == [] for row in interactions)

    check("heir_history_once_with_only_observed_arrival_and_npc_interaction_roots", real_events_valid)
    check("honor_and_root_title_are_correct_before_followups", lambda: all(facts(before).get((player, "player.honor")) == "15" and facts(before).get((player, "title.old-capital-guardian")) == "旧都的守望者" for player in players))
    check("honor17_and_individual_titles_persist", lambda: len(players) == 2 and all(all(facts(snapshot).get((player, "player.honor")) == "17" and facts(snapshot).get((player, "title.old-capital-guardian")) == "旧都的守望者" for player in players) and facts(snapshot).get((identities["A"], "title.imperial-testimony-keeper")) == "议会证言保存者" and facts(snapshot).get((identities["B"], "title.imperial-archive-reader")) == "旧都档案校读者" for snapshot in (after, replay, restored)))
    check("local_outcomes_finished_without_uncertain_external_delivery", lambda: bool(after["outcomes"]) and all(row["status"] == "DONE" for row in after["outcomes"]) and len({row["id"] for row in after["outcomes"]}) == len(after["outcomes"]))
    check("one_stable_npc_reaction_receipt", lambda: len([row for row in after["outcomes"] if row["id"].startswith("npc-reaction:")]) == 1)

    def choice_ledger_valid():
        rows = after["story_choice_log"]
        if len(rows) != 13 or any(row["provenance"] != "LIVE" or row["chosen_at"] is None or row["committed_by"] not in players or row["nation_id"] != "" or row["revision"] != 1 or row["story_version"] != "1" for row in rows):
            return False
        instances = {row["id"]: row for row in after["instances"]}
        for row in rows:
            instance = json.loads(instances[row["instance_id"]]["json"])
            if row["source_event_id"] != instance["sourceEventId"] or row["scope_key"] != instance["scopeKey"]:
                return False
            if row["actor_key"] != ("player:" + row["player_id"] if row["kind"] == "BALLOT" else row["scope_key"]):
                return False
        for node, choice in (("summons", "convene"), ("council", "support"), ("preparations", "restoration")):
            selected = [row for row in rows if row["story_id"] == "last-heir" and row["node_id"] == node]
            if len(selected) != 3 or any(row["choice_id"] != choice for row in selected):
                return False
            if {row["player_id"] for row in selected if row["kind"] == "BALLOT"} != players:
                return False
            if len([row for row in selected if row["kind"] == "RESOLUTION" and row["scope_key"] == "world" and row["player_id"] is None]) != 1:
                return False
        for label, choice in (("A", "testimony"), ("B", "reconcile")):
            selected = [row for row in rows if row["story_id"] == "heir-legacy" and row["scope_key"] == "player:" + identities[label]]
            if len(selected) != 2 or {row["kind"] for row in selected} != {"BALLOT", "RESOLUTION"} or any(row["node_id"] != "archive" or row["choice_id"] != choice for row in selected):
                return False
        return len({row["seq"] for row in rows}) == len({row["id"] for row in rows}) == 13

    check("append_only_choice_ledger_contains_all_six_real_heir_ballots_and_followups", choice_ledger_valid)

    def causes_valid():
        rows = {row["event_id"]: row for row in after["event_causes"]}
        if set(rows) != {row["id"] for row in after["events"]}:
            return False
        root = rows[SOURCE_ID]
        if root["parent_id"] is not None or root["root_id"] != SOURCE_ID or root["depth"] != 0 or json.loads(root["story_path"]) != []:
            return False
        supported_id = typed_event(after, SUPPORTED)["id"]
        for event_type in ("HEIR_COUNCIL_OPENED", SUPPORTED, "HEIR_PERSONAL_TESTIMONY", "HEIR_RECORDS_RECONCILED"):
            row = rows[typed_event(after, event_type)["id"]]
            followup = event_type in ("HEIR_PERSONAL_TESTIMONY", "HEIR_RECORDS_RECONCILED")
            if row["root_id"] != SOURCE_ID or row["parent_id"] != (supported_id if followup else SOURCE_ID) or row["depth"] != (2 if followup else 1) or json.loads(row["story_path"]) != (["last-heir", "heir-legacy"] if followup else ["last-heir"]):
                return False
        inherited = {event_id for event_id, row in rows.items() if row["root_id"] == SOURCE_ID}
        observed_roots = [row for row in after["events"] if row["type"] in {"PLAYER_JOIN", "NPC_INTERACT"}]
        return len(inherited) == 5 and all(rows[row["id"]]["root_id"] == row["id"] and rows[row["id"]]["parent_id"] is None and rows[row["id"]]["depth"] == 0 and json.loads(rows[row["id"]]["story_path"]) == [] for row in observed_roots) and not after["story_trigger_blocks"]

    check("causal_paths_link_both_followups_to_real_completion_and_root", causes_valid)
    check("replay_and_third_boot_changed_no_persisted_game_table", lambda: all(after[table] == replay[table] == restored[table] for table in TABLES))
    expected_recollections = {"history." + SUPPORTED: "1", "chronicle.history.world." + SUPPORTED: "1", "chronicle.history.player." + SUPPORTED: "1", PERSONAL: "support", WORLD_COUNCIL: "support", WORLD_END: "restoration"}
    check("api_recollections_returns_canonical_world_player_counts_and_choices", lambda: all(any(data.get("player") == label and all(data.get("facts", {}).get(key) == value for key, value in expected_recollections.items()) and data.get("nationKeysAbsent") is True and not any(key.startswith("chronicle.history.nation.") or key.startswith("past.resolution.nation.") for key in data.get("facts", {})) for _, data in server_rows("RECOLLECTIONS", (2, 3))) for label in ("A", "B")))
    check("standalone_fate_has_no_invented_role_and_honor17", lambda: all(any(data.get("player") == label and data.get("role") == "unassigned" and str(data.get("honor")) == "17" for _, data in server_rows("FATE", (2, 3))) for label in ("A", "B")))
    check("npc_reputation_trust_growth_position_persisted", lambda: all(all(facts(snapshot).get((player, "npc.ancient-heir.reputation")) == "25" and facts(snapshot).get((player, "npc.ancient-heir.trust")) == "12" for player in players) and facts(snapshot).get(("WORLD", "npc.ancient-heir.growth")) == "20" and facts(snapshot).get(("WORLD", "npc.ancient-heir.position")) == "council-speaker" for snapshot in (after, restored)))
    check("npc_trusted_restoration_dialogue_selected_for_both_players", lambda: all(any(data.get("player") == label and data.get("npcId") == "ancient-heir" and str(data.get("rep")) == "25" and str(data.get("trust")) == "12" and str(data.get("growth")) == "20" and data.get("position") == "council-speaker" and data.get("dialogueId") == "trusted-restoration" for _, data in server_rows("NPC", (2, 3))) for label in ("A", "B")))
    titles = [json.dumps(row.get("title", ""), ensure_ascii=False) for row in client_rows("WINDOW")]
    check("real_gui_packets_for_history_evidence_npc_fate_and_followup", lambda: all(any(title in observed for observed in titles) for title in ("世界编年史", "历史证据", "人物档案", "我的命运与历史荣誉", "旧都档案")))
    check("gui_inspection_occurs_on_actual_player_owner", lambda: bool(server_rows("VIEW")) and all(data.get("owned") is True for _, data in server_rows("VIEW")))
    check("actual_shift_click_preserves_inventory_and_menu_item", lambda: bool(client_rows("SHIFT_CHECK")) and all(row.get("unchangedInventory") is True and row.get("slotStillPresent") is True for row in client_rows("SHIFT_CHECK")))
    check("monument_evidence_gui_contains_the_exact_test_only_source", lambda: any("历史证据" in json.dumps((row.get("window") or {}).get("title", ""), ensure_ascii=False) and SOURCE_ID in json.dumps((row.get("window") or {}).get("slots", []), ensure_ascii=False) and "test-only" in json.dumps((row.get("window") or {}).get("slots", []), ensure_ascii=False).lower() and any(interaction.get("kind") == "monument" and interaction.get("boot") == row.get("boot") and interaction.get("player") == row.get("player") for interaction in client_rows("INTERACTED")) for row in client_rows("CLIENT_VIEW")))
    check("real_written_history_book_packet_received", lambda: bool(client_rows("BOOK")))
    check("past_command_reads_completed_personal_and_world_decisions", lambda: any(row.get("message") == "/chronicle past last-heir/1/council" for row in client_rows("SENT")) and any(PERSONAL + " = support" in row.get("text", "") for row in client_rows("MESSAGE")) and any(WORLD_COUNCIL + " = support" in row.get("text", "") for row in client_rows("MESSAGE")))
    check("native_scene_unavailable_and_native_action_rejected", lambda: any(data.get("present") is False for _, data in server_rows("SCENE")) and any(data.get("rejected") is True for _, data in server_rows("SCENE_ACTION")) and any(row.get("message") == "/chronicle scene" for row in client_rows("SENT")) and any("暂无自有StarCore场景" in row.get("text", "") for row in client_rows("MESSAGE")))
    check("memory_registry_and_disabled_tombstone_survive_restarts", lambda: all(set(site_map(snapshot)) == {"phase-two-live", "phase-two-removed"} and site_map(snapshot)["phase-two-live"]["enabled"] is True and site_map(snapshot)["phase-two-removed"]["enabled"] is False for snapshot in snapshots.values()))
    def denied_memory_attempts_proven():
        attempts = []
        for row in client_rows("SENT"):
            parts = row.get("message", "").split()
            if len(parts) >= 5 and parts[:3] == ["/chronicle", "memory", "create"]:
                attempts.append((row, parts[3]))
        return all(any(row.get("player") == player and marker in site_id for row, site_id in attempts) for player, marker in (("A", "invalid"), ("B", "forbidden"))) and any("管理权限" in row.get("text", "") for row in client_rows("MESSAGE", player="B")) and all(not any("invalid" in site_id or "forbidden" in site_id for site_id in site_map(snapshot)) for snapshot in snapshots.values())

    check("invalid_and_forbidden_memory_attempts_are_evidenced_and_not_persisted", denied_memory_attempts_proven)
    check("live_memory_uses_actual_chunk_edge_coordinates", lambda: all(site_map(snapshot)["phase-two-live"]["x"] % 16 == 15 and site_map(snapshot)["phase-two-live"]["z"] % 16 == 15 for snapshot in snapshots.values()))
    expected_entities = ["monument:INTERACTION", "text:TEXT_DISPLAY", "veteran:VILLAGER"]
    check("edge_owned_memory_entities_and_unrelated_bystander_observed_each_boot", lambda: all(any(data.get("owned") is True and data.get("bystanders") == 1 and data.get("sites", {}).get("phase-two-live") == expected_entities and not data.get("sites", {}).get("phase-two-removed") for _, data in server_rows("SITES", (boot,))) for boot in (1, 2, 3)))
    check("real_monument_and_veteran_interactions", lambda: all(any(row.get("kind") == kind for row in client_rows("INTERACTED")) for kind in ("monument", "veteran")))
    error_pattern = r"(?i)thread check failed|not on an entity|not on a region|Asynchronous .+!|UnsupportedOperationException|Error occurred while|Exception in thread|PHASE_TWO_\w+_FAILED|PHASE_TWO_FIXTURE_FAILED|PHASE_TWO_FAILED"
    check("no_folia_thread_violation_or_unhandled_plugin_failure", lambda: not re.search(error_pattern, combined))
    check("no_protocol_error_or_bounded_timeout", lambda: bool(protocol) and not any(name in {"ERROR", "TIMEOUT", "ACTION_FAILED", "UNCAUGHT"} for name, _ in protocol))

    output.mkdir(parents=True, exist_ok=True)
    summary = {"scope": "StarChronicle standalone on real Folia; NOT StarCore full-stack Folia certification", "plugin_sha256": expected,
               "run_directory": str(run), "server": "Folia 1.21.11 build 14", "region_threads": 2,
               "captured_asia_shanghai": restored["captured_asia_shanghai"], "server_exit_codes": processes.get("server_exit_codes"),
               "client_exit_codes": processes.get("client_exit_codes"), "checks": checks, "details": details,
               "passed": sum(checks.values()), "total": len(checks), "all_passed": all(checks.values())}
    (output / f"{PREFIX}-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rows = "\n".join(f"| {name} | {'PASS' if passed else 'FAIL'} |" for name, passed in checks.items())
    report = f"""# Phase-two real Folia standalone acceptance

Scope: the selected StarChronicle product in explicit standalone mode. This is **not StarCore full-stack Folia certification**. The fixture does not supply a fake nation, role, authority service, or replacement NarrativeEngine.

- Product SHA-256: `{expected}`.
- Runtime: `{run}`.
- Captured in Asia/Shanghai: `{restored['captured_asia_shanghai']}`.
- Official Folia 1.21.11 build 14 server SHA-256: `{SERVER_SHA}`.
- Three serial server boots, two protocol clients on each boot, and two actual region tick threads are checked against retained evidence. Process results are read from `{PREFIX}-processes.json`, never inferred from a log ending.

The scenario retains all twelve bundled story files unchanged. An explicitly test-only source goes through the real ChronicleService and its fresh player snapshots. Both players vote convene, support, and restoration in last-heir. The first stop preserves the real completion and the unconsumed derived heir-legacy inbox. Second-boot logins recover each personal follow-up without resubmitting the source. The client trace and database must agree that A chooses testimony and B chooses reconcile. Third-boot restoration and duplicate source replay must preserve all result tables, personal ballots, collective resolutions, causal paths, honor, titles, NPC relationships, and memory registry rows.

API observations separately verify global and personal supported-event counts, recorded personal and shared choices, absent national projections, and the unassigned standalone role. Protocol evidence exercises the personal story GUI, history and evidence menus, NPC dossier, fate menu, written history book, historical-choice command, and unavailable native scene rejection. Region-owned observations cover live memory entities, a disabled-site tombstone, and an unrelated bystander.

| Check | Result |
| --- | --- |
{rows}

Observed result: **{summary['passed']}/{summary['total']} checks passed**. The machine-readable summary retains any evaluation errors, and its `all_passed` field is true only when every check succeeds.

Evidence is local to this isolated runtime: `{PREFIX}-boot1.log`, `{PREFIX}-boot2.log`, `{PREFIX}-boot3.log`, `{PREFIX}-clients.log`, `{PREFIX}-processes.json`, and four `{PREFIX}-*.json` read-only snapshots. SQLite was opened with `mode=ro` and `query_only=ON`; only polling liveness column `story_triggers.checked` is excluded from result comparison. Product, server, fixture binary, and fixture source hashes are retained in every snapshot.

Limits: protocol automation is not human visual acceptance. This report does not certify StarCore on Folia, real national mutations, external NPC providers, external reward settlement, crash or power-loss durability, or an AI service. Earlier artifacts and reports are not used as proof for this product hash.
"""
    (output / f"{PREFIX}-report.md").write_text(report, encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if not summary["all_passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        raise SystemExit(f"Folia report refused incomplete or invalid evidence: {error}")
