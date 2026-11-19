# Real Folia standalone verification — 2026-11-19

**Scope: StarChronicle standalone (`require-starcore: false`), not StarCore full-stack Folia certification.** The upstream StarCore artifact was deliberately absent. Its unsupported loader flag was not modified. Upstream NarrativeEngine remains disabled on Folia pending the separate compatibility work.

- Official Folia 1.21.11 build 14, commit `529aabc13852021f6081f8a6069959a449c33785`, Java 21.0.13.0.2+1, Windows Server 2022.
- Official Folia JAR SHA-256: `f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39`.
- Product JAR SHA-256: `5e227d20c46eeb91dd98d9d33e6f55cd66fbfc4fd7ecd7391f57eb9643f0d86e`.
- Isolated loopback server `127.0.0.1:25578`, offline test clients, RCON/query disabled; only StarChronicle and a test fixture installed.
- Both server invocations and both automated client invocations exited normally with code 0. No test server from this verification remains running.
- Three bundled stories plus one isolated test story were loaded. The test story uses WORLD scope, quorum 2, one local history effect, and one local title effect for each participant.

Two actual Minecraft 1.21.11 protocol clients connected. The test-only fixture submitted a fixed-ID `WorldEvent` through the published Bukkit `ChronicleService`; production code captured fresh player contexts on the owning entity scheduler. The fixture did not construct trusted contexts or nation membership. A voted once, the server stopped normally, the same players reconnected, and the shared instance restored with its original ID, members and single vote. B's vote completed it. Replaying the source event four times and repeating the completed choice did not add any event, title fact or outcome receipt.

The completed-choice repeat was intentionally refused with `IllegalStateException` and logged by the fixture as `FOLIA_VOTE_FAILED`. This is an expected negative check, not a Folia thread violation. The first shutdown also retained two host warnings, `Failed to flush log messages before plugin unload`; shutdown still completed and the state/SQLite integrity checks passed. The second shutdown did not emit those warnings.

| Check | Result |
| --- | --- |
| real_folia_1_21_11_build_14 | PASS |
| real_region_scheduler | PASS |
| plugin_enabled_both_boots | PASS |
| standalone_explicitly_reported | PASS |
| two_real_protocol_clients_each_boot | PASS |
| two_member_shared_instance | PASS |
| one_vote_persisted_before_restart | PASS |
| one_vote_restored_after_restart | PASS |
| same_instance_completed_after_second_vote | PASS |
| duplicate_event_replay_rejected | PASS |
| stable_source_event_hash | PASS |
| one_history_and_two_title_receipts | PASS |
| completed_replay_did_not_add_receipts | PASS |
| sqlite_integrity_ok | PASS |
| status_validate_reload_commands | PASS |
| no_folia_thread_violation | PASS |
| normal_server_shutdowns | PASS |
| clients_closed_normally | PASS |

Result: **18/18 checks passed**.

Evidence: `folia-smoke-first-start.log`, `folia-smoke-restart.log`, `folia-protocol-clients.log`, `folia-smoke-before-restart.json`, `folia-smoke-after-restart.json`, `folia-smoke-after-replay.json`, `folia-official-build.json`, and `folia-smoke-summary.json` in this directory. The fixture sources and reproducible setup scripts are under `tools/`.

Limits: these are automated protocol clients, not visual or human GUI acceptance. This run proves the recorded standalone API/rule/state/history paths on real Folia. It does not prove NPC-provider behavior, the newly added journal/world-memory/fate features, all StarCore modules, cross-region boss rendering, real nation authority mutations, crash/power-loss durability, or network AI behavior.
