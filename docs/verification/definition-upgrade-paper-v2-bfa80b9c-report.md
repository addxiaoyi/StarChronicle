# Actual definition catalog upgrade

Result: **PASS**.

- Product SHA-256: `bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154`.
- Original database SHA-256: `28885c47a58c7ce218131423b88bf16fcb248d736f2b18d12d34f8b0274510c3`.
- Actual source schema: 2; target schema: 4.

The original was read only with `mode=ro&immutable=1`; non-empty WAL/rollback journals are refused. Main database and all sidecar presence, sizes and hashes were recorded before/after. JDBC received only the specially named isolated clone of SQLite's consistent backup.

Every original table, including existing choice log and sqlite_sequence when present, was compared by columns and typed values. JSON, hashes and times remain exact text values. Only the established outcomes RUNNING→UNKNOWN transition is allowed. Full data snapshots stay in the ignored run directory.

| Check | Result |
| --- | --- |
| actual_probe_compilation_exit_zero | PASS |
| actual_production_probe_exit_zero | PASS |
| actual_source_schema_2_or_3 | PASS |
| source_schema_matches_requested_assertion | PASS |
| consistent_readonly_backup_matches_source | PASS |
| source_foreign_keys_and_integrity_ok | PASS |
| compiled_and_production_schema_are_4 | PASS |
| every_original_table_column_and_typed_value_preserved_first_open | PASS |
| every_original_table_column_and_typed_value_preserved_second_open | PASS |
| every_original_schema_object_preserved | PASS |
| catalog_contains_exact_actual_canonical_definition_variants | PASS |
| catalog_conflicts_are_retained_and_reported_without_winners | PASS |
| origin_expression_index_is_used_for_real_instance_tuple | PASS |
| foreign_keys_and_integrity_ok | PASS |
| second_open_adds_or_changes_no_table_value | PASS |
| second_open_adds_or_changes_no_schema_or_catalog_metadata | PASS |
| new_legacy_choices_match_actual_surviving_snapshot_count | PASS |
| new_legacy_choice_times_and_committers_remain_unknown | PASS |
| original_database_and_all_sidecar_bytes_states_unchanged | PASS |
| final_product_fingerprint_unchanged | PASS |

## Retained definition conflicts

No conflicting canonical variants were present in this source's retained definitions.

Scope: Chronicle production schema/catalog migration only. This is not a server startup, native quest migration, external state mutation, reward delivery or crash/power-loss test.
