# Actual phase-one V2 database upgrade

Result: **PASS**.

- Product SHA-256: `d765e0d14b9a9122e0ba1304c9c1b0d3ef844d4458295b1f4847ecb213bd943b`.
- Original database SHA-256: `28885c47a58c7ce218131423b88bf16fcb248d736f2b18d12d34f8b0274510c3`.
- Source schema 2, production target schema 3.
- Imported surviving choice log rows: 14.

The source was the actual normally stopped phase-one Paper database. Python opened it read-only and created a consistent backup; JDBC only opened the fresh isolated clone. The real production constructor opened that clone twice. Old table columns and every payload were compared, with only the established RUNNING-to-UNKNOWN outcome transition allowed.

| Check | Result |
| --- | --- |
| actual_original_v2_database | PASS |
| original_database_bytes_unchanged | PASS |
| verified_final_product | PASS |
| production_constructor_upgraded_to_v3 | PASS |
| all_original_columns_and_payloads_preserved | PASS |
| real_surviving_choices_were_imported | PASS |
| legacy_time_and_committer_remain_unknown | PASS |
| second_open_preserves_every_log_sequence_and_id | PASS |
| no_invented_legacy_causal_ancestry | PASS |
| foreign_keys_and_integrity_ok | PASS |
| actual_compile_and_probe_exit_zero | PASS |

Compilation and Java probe process exit codes were observed directly. Full before/after data remains in the ignored isolated run, not in the public evidence directory.

Scope: Chronicle schema/choice migration only. This does not claim an upstream native quest migration, external reward replay, full server boot, crash or power-loss proof.
