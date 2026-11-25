"""Negative checks made by corrupting copies of completed live contribution runs."""
import importlib.util
import json
from pathlib import Path
import sqlite3
import unittest
import test_epic_acceptance as epic_cases

SPEC = importlib.util.spec_from_file_location('contribution_inspector', Path(__file__).with_name('inspect-contribution-paper.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualContributionEvidenceTest(epic_cases.ActualEpicEvidenceTest):
    def test_contributions_match_native_receipts_and_live_queries(self):
        self.assertEqual(INSPECTOR.inspect(self.run)['contribution_entries'], 8)

    def test_changed_amount_in_both_columns_and_json_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarChronicle/chronicle.db') as db:
            db.execute("update contributions set amount='999',request_json=json_set(request_json,'$.amount',999) where unit='resource.agricultural'")
        with self.assertRaisesRegex(AssertionError, 'amount/unit differs'):
            INSPECTOR.inspect(self.run)

    def test_missing_projection_marker_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarChronicle/chronicle.db') as db:
            db.execute('delete from epic_contribution_projections where operation_id=(select operation_id from epic_contribution_projections limit 1)')
        with self.assertRaisesRegex(AssertionError, 'contribution projections'):
            INSPECTOR.inspect(self.run)

    def test_missing_live_api_observation_is_rejected(self):
        path = self.run / 'evidence/epic-fixture.jsonl'
        path.write_text('\n'.join(line for line in path.read_text().splitlines() if json.loads(line)['kind'] != 'CONTRIBUTIONS_PASS'))
        with self.assertRaisesRegex(AssertionError, 'live contribution API'):
            INSPECTOR.inspect(self.run)

    def test_missing_actual_command_query_is_rejected(self):
        path = self.run / 'evidence/boot3.clients.log'
        path.write_text('\n'.join(line for line in path.read_text().splitlines() if 'CONTRIBUTIONS_QUERY' not in line))
        with self.assertRaisesRegex(AssertionError, 'real client contribution query'):
            INSPECTOR.inspect(self.run)


if __name__ == '__main__':
    unittest.main()
