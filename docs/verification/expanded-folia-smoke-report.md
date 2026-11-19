# Expanded real Folia standalone acceptance — 2026-11-20

**Scope: current StarChronicle JAR in explicit standalone mode (`require-starcore: false`), not StarCore full-stack Folia certification.** No StarCore descriptor or authority service was fabricated. Upstream NarrativeEngine execution stayed unavailable on Folia.

- Product SHA-256: `abaadf4c4dd318d518687bafe2596f43e32c9f9a115b4074deeae9f37abc22df`.
- Official Folia 1.21.11 build 14, commit `529aabc13852021f6081f8a6069959a449c33785`, Java 21, Windows Server 2022.
- Official server SHA-256: `f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39`.
- Isolated loopback port 25578, two actual Minecraft 1.21.11 protocol clients, two region tick threads, `-Xmx768m`. All three server starts were serial; all three server and client exits were observed as 0.
- The eleven bundled stories plus two clearly labelled verification stories were validated. A test-only logical NPC profile reacts solely to actual completion of the shared verification, never to a fabricated nation, role or historical war.

First boot: the two real clients were separated into distant regions; the fixture submitted an evidenced test event through `ChronicleService`, and the service collected its own fresh trusted contexts. A voted once. Actual player commands created two memory sites at the edge of chunk 0,0, then disabled one. The production code spawned the monument hitbox, text display and veteran. A region-owned inspector read their real persistent markers and verified entity ownership. An unrelated tagged test armor stand survived reconciliation. Invalid-source and non-admin placement attempts left no registry row. Both clients then disconnected normally. With the online count verified as zero, the fixture submitted an additional event for the same previously observed, persisted real cohort. The source event committed while no corresponding story instance existed. No trusted PlayerContext was constructed by the fixture.

Second boot: the original instance, both members and A's single vote were restored; the enabled site's three entities were unique, the disabled site's entities were absent, and the unrelated armor stand remained. Actual cohort members joining also restored the separate deferred WORLD trigger from the event committed while everyone was offline. The two participants completed that story with one local title/history result. B's vote then completed the original shared instance. One durable NPC reaction changed each participant's reputation/trust and the character's growth/position; each participant received one local honor/title result. Replays changed no recorded table. Third boot: both completed instances, receipts, relationships, titles, growth, enabled site and disabled tombstone survived another normal restart without repetition.

Actual client commands and protocol clicks exercised history list/evidence, the shared story, NPC dossier, fate menu and written history book. The monument opened its exact stored source evidence; the veteran opened NPC dialogue. Shift-click rollback preserved the client inventory and menu item. A server-side player-owner inspector also verified the open GUI. Standalone fate remained `unassigned`; no role was invented to force an identity story. Native scene viewing returned empty and an attempted scene action was refused.

Expected negative checks remain visible in the retained logs: the deliberately nonexistent memory source produced one `Player operation failed: Chronicle transaction failed` warning; the non-admin placement was denied; completed-choice repeats were refused; and the unavailable native scene action returned a security failure. These checks created no unauthorized memory row or repeated reward and are not counted as unhandled Folia thread failures.

| Check | Result |
| --- | --- |
| approved_final_product_hash_all_boots | PASS |
| fresh_final_run_directory_all_boots | PASS |
| real_folia_1_21_11_build_14_all_boots | PASS |
| two_actual_region_tick_threads | PASS |
| players_proven_in_different_owning_regions | PASS |
| explicit_standalone_scope_all_boots | PASS |
| thirteen_validated_stories_all_boots | PASS |
| two_real_clients_three_boots | PASS |
| shared_two_member_instance | PASS |
| first_vote_persisted_before_stop | PASS |
| first_vote_restored_before_second_vote | PASS |
| same_instance_completed_and_restored | PASS |
| source_payload_hash_time_survived_restart | PASS |
| source_time_not_future | PASS |
| offline_event_committed_without_online_audience | PASS |
| offline_trigger_durably_pending_before_restart | PASS |
| offline_world_trigger_restored_for_real_players | PASS |
| offline_completion_and_titles_once | PASS |
| offline_trigger_has_one_stable_consumption_receipt | PASS |
| completed_replay_changed_no_table | PASS |
| completed_state_and_receipts_survived_second_restart | PASS |
| one_completed_history_event | PASS |
| npc_reaction_receipt_once | PASS |
| exactly_nine_local_outcomes_done_once | PASS |
| npc_relationship_and_honor_persisted | PASS |
| npc_global_growth_position_persisted | PASS |
| npc_dynamic_dialogue_selected_after_restore | PASS |
| memory_enabled_and_disabled_tombstones_persisted | PASS |
| memory_real_owned_entities_at_chunk_edge | PASS |
| memory_removed_entities_absent_after_both_restarts | PASS |
| untagged_bystander_survived_removal_and_restarts | PASS |
| invalid_and_unauthorized_memory_sites_not_persisted | PASS |
| gui_history_evidence_npc_fate_and_story_packets | PASS |
| gui_presented_on_actual_player_owner | PASS |
| monument_gui_contains_exact_source_evidence | PASS |
| real_history_book_open_packet | PASS |
| shift_click_preserved_inventory_and_menu_item | PASS |
| real_monument_and_veteran_interactions | PASS |
| native_scene_unavailable_and_action_rejected | PASS |
| standalone_role_remained_unassigned | PASS |
| sqlite_integrity_ok_all_captures | PASS |
| no_folia_thread_violation_or_unhandled_plugin_exception | PASS |
| all_server_and_client_processes_exit_zero | PASS |
| all_normal_shutdowns_observed | PASS |
| no_client_error_or_bounded_timeout | PASS |

Result: **45/45 checks passed**.

Evidence in this directory uses `expanded-folia-smoke-*`: three complete sanitized server logs, protocol-client log, four read-only SQLite snapshots, actual process exits, and this machine-readable summary. Snapshots include the durable trigger inbox and its immutable consumption receipts; only the polling `checked` counter is excluded from result comparison because it records liveness rather than a game result. Setup/fixture/client/report tools live under `tools/`; runtime files stay in ignored `run/folia-final-smoke-abaadf4c`.

Limits: protocol automation is not human visual acceptance. This standalone run does not certify upstream StarCore modules, real national mutations, provider-specific Citizens/FancyNpcs/Adyeshach behavior, external reward settlement, crash/power-loss durability, or network AI. See `../FOLIA-COMPATIBILITY.md` for the independently observed upstream loader blocker and the bounded owner-thread prerequisite patch. The older 2026-11-19 evidence is retained separately and is not relabelled as current-JAR acceptance.
