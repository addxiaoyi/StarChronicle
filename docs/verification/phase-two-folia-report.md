# Phase-two real Folia standalone acceptance

Scope: the selected StarChronicle product in explicit standalone mode. This is **not StarCore full-stack Folia certification**. The fixture does not supply a fake nation, role, authority service, or replacement NarrativeEngine.

- Product SHA-256: `d765e0d14b9a9122e0ba1304c9c1b0d3ef844d4458295b1f4847ecb213bd943b`.
- Runtime: `C:\Users\Administrator\Documents\StarChronicle\run\phase-two-folia-d765e0d1`.
- Captured in Asia/Shanghai: `2026-11-20T03:52:47.673854+08:00`.
- Official Folia 1.21.11 build 14 server SHA-256: `f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39`.
- Actual Java runtime from each boot log: `21 (OpenJDK 64-Bit Server VM 21.0.13.1+2-LTS; Microsoft Microsoft-16059585) on Windows Server 2022 10.0 (amd64)`.
- Three serial server boots, two protocol clients on each boot, and two actual region tick threads are checked against retained evidence. Process results are read from `phase-two-folia-processes.json`, never inferred from a log ending.

Setup evidence retains 1 pre-JVM preparation failure(s) in `phase-two-folia-setup-failures.json`: KeyError: 'JAVA_HOME'. Each retained record states whether Java started or the database was written. Compilation used the explicitly selected installed JDK, whose actual runtime is recorded above. Pre-JVM setup attempts are not relabelled as server boots.

The scenario retains all twelve bundled story files unchanged. An explicitly test-only source goes through the real ChronicleService and its fresh player snapshots. Both players vote convene, support, and restoration in last-heir. The first stop preserves the real completion and the unconsumed derived heir-legacy inbox. Second-boot logins recover each personal follow-up without resubmitting the source. The client trace and database must agree that A chooses testimony and B chooses reconcile. Third-boot restoration and duplicate source replay must preserve all result tables, personal ballots, collective resolutions, causal paths, honor, titles, NPC relationships, and memory registry rows.

The extra `phase-two-folia-login-recovered.json` capture checks the intermediate recovery state after both real protocol logins: two separate personal archive nodes, no new ballots or outcomes, and both inbox receipts consumed. The operator observed this capture before fixture instance inspection. Its capture time is checked against the protocol SPAWN timestamps; second-resolution server log timestamps do not independently prove sub-second order against the first fixture query.

API observations separately verify global and personal supported-event counts, recorded personal and shared choices, absent national projections, and the unassigned standalone role. Protocol evidence exercises the personal story GUI, history and evidence menus, NPC dossier, fate menu, written history book, historical-choice command, and unavailable native scene rejection. Region-owned observations cover live memory entities, a disabled-site tombstone, and an unrelated bystander.

| Check | Result |
| --- | --- |
| approved_product_hash_in_every_capture_and_runtime | PASS |
| captures_belong_to_one_selected_runtime_and_stage | PASS |
| official_folia_server_hash_in_every_capture | PASS |
| same_fixture_binary_and_source_across_all_boots | PASS |
| schema3_readonly_consistent_snapshots | PASS |
| sqlite_integrity_and_foreign_keys_clean | PASS |
| exactly_twelve_unchanged_stories_and_default_npc_resource | PASS |
| twelve_definitions_validated_on_every_boot | PASS |
| real_folia_1_21_11_build14_on_three_boots | PASS |
| two_actual_region_threads_on_three_boots | PASS |
| standalone_scope_without_starcore_on_every_boot | PASS |
| ten_minute_poll_interval_preserved | PASS |
| two_stable_real_client_identities_each_boot | PASS |
| different_region_ownership_observed | PASS |
| actual_server_and_client_processes_recorded_without_launcher_or_hash_drift | PASS |
| three_servers_and_three_clients_exited_zero | PASS |
| normal_quit_stop_and_all_owned_children_exit_recorded | PASS |
| each_real_client_requested_quit_and_ended_once_per_boot | PASS |
| source_submitted_with_fresh_api_contexts_only_on_first_boot | PASS |
| third_boot_replays_the_same_source_as_duplicate | PASS |
| source_payload_and_hash_unchanged_across_restarts | PASS |
| source_has_real_actors_no_fabricated_nation_and_test_evidence | PASS |
| source_time_is_not_in_the_future | PASS |
| same_completed_two_player_heir_instance_survives_all_boots | PASS |
| derived_followup_pending_for_both_players_before_first_stop | PASS |
| login_recovery_snapshot_is_same_artifact_and_history_after_both_real_logins | PASS |
| login_alone_consumes_two_personal_inboxes_without_votes_or_rewards | PASS |
| login_recovers_two_player_followups_and_distinct_completed_endings | PASS |
| actual_gui_selected_testimony_for_a_and_reconcile_for_b | PASS |
| pending_triggers_consumed_with_one_receipt_per_player | PASS |
| heir_history_once_with_only_observed_arrival_and_npc_interaction_roots | PASS |
| honor_and_root_title_are_correct_before_followups | PASS |
| honor17_and_individual_titles_persist | PASS |
| local_outcomes_finished_without_uncertain_external_delivery | PASS |
| one_stable_npc_reaction_receipt | PASS |
| append_only_choice_ledger_contains_all_six_real_heir_ballots_and_followups | PASS |
| causal_paths_link_both_followups_to_real_completion_and_root | PASS |
| replay_and_third_boot_changed_no_persisted_game_table | PASS |
| api_recollections_returns_canonical_world_player_counts_and_choices | PASS |
| standalone_fate_has_no_invented_role_and_honor17 | PASS |
| npc_reputation_trust_growth_position_persisted | PASS |
| npc_trusted_restoration_dialogue_selected_for_both_players | PASS |
| real_gui_packets_for_history_evidence_npc_fate_and_followup | PASS |
| gui_inspection_occurs_on_actual_player_owner | PASS |
| actual_shift_click_preserves_inventory_and_menu_item | PASS |
| monument_evidence_gui_contains_the_exact_test_only_source | PASS |
| real_written_history_book_packet_received | PASS |
| past_command_reads_completed_personal_and_world_decisions | PASS |
| native_scene_unavailable_and_native_action_rejected | PASS |
| memory_registry_and_disabled_tombstone_survive_restarts | PASS |
| invalid_and_forbidden_memory_attempts_are_evidenced_and_not_persisted | PASS |
| live_memory_uses_actual_chunk_edge_coordinates | PASS |
| edge_owned_memory_entities_and_unrelated_bystander_observed_each_boot | PASS |
| real_monument_and_veteran_interactions | PASS |
| no_folia_thread_violation_or_unhandled_plugin_failure | PASS |
| no_protocol_error_or_bounded_timeout | PASS |

Observed result: **56/56 checks passed**. The machine-readable summary retains any evaluation errors, and its `all_passed` field is true only when every check succeeds.

Evidence is local to this isolated runtime: `phase-two-folia-boot1.log`, `phase-two-folia-boot2.log`, `phase-two-folia-boot3.log`, `phase-two-folia-clients.log`, `phase-two-folia-processes.json`, and five `phase-two-folia-*.json` read-only snapshots including the intermediate login recovery capture. SQLite was opened with `mode=ro` and `query_only=ON`; only polling liveness column `story_triggers.checked` is excluded from result comparison. Product, server, fixture binary, and fixture source hashes are retained in every snapshot.

Limits: protocol automation is not human visual acceptance. This report does not certify StarCore on Folia, real national mutations, external NPC providers, external reward settlement, crash or power-loss durability, or an AI service. Earlier artifacts and reports are not used as proof for this product hash.
