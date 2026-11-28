import importlib.util
import json
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    'processing_database_checks', Path(__file__).with_name('verify-processing-merge-databases.py'))
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class StockpileChecksTest(unittest.TestCase):
    def test_explicit_zero_resource_is_allowed(self):
        checks.assert_stockpile({'mineral': 991, 'energy': 997, 'industrial': 6, 'luxury': 0},
                               {'mineral': 991, 'energy': 997, 'industrial': 6})

    def test_changed_or_missing_balance_is_rejected(self):
        for actual in ({'mineral': 990}, {}, {'mineral': 991, 'luxury': 1},
                       {'mineral': 991, 'luxury': -1}):
            with self.subTest(actual=actual), self.assertRaises(AssertionError):
                checks.assert_stockpile(actual, {'mineral': 991})


class RetainedRowsChecksTest(unittest.TestCase):
    def state(self, rows):
        return {'starcore_resource_state': {
            'columns': ['property_key', 'property_value'],
            'rows': [json.dumps(row) for row in rows]}}

    def test_only_explicit_metadata_replacement_is_allowed(self):
        before = self.state([['balance', '100'], ['head', 'old']])
        after = self.state([['balance', '100'], ['head', 'new'], ['writer', '1']])
        checks.retained(before, after, mutable_property_keys={'head'})
        with self.assertRaises(AssertionError):
            checks.retained(before, after)

    def test_deleted_metadata_or_changed_balance_is_rejected(self):
        before = self.state([['balance', '100'], ['head', 'old']])
        for rows in ([['balance', '100']], [['balance', '99'], ['head', 'new']]):
            with self.subTest(rows=rows), self.assertRaises(AssertionError):
                checks.retained(before, self.state(rows), mutable_property_keys={'head'})

    def test_exact_comparison_never_allows_metadata_change(self):
        with self.assertRaises(AssertionError):
            checks.retained(self.state([['head', 'old']]), self.state([['head', 'new']]),
                            exact=True, mutable_property_keys={'head'})


if __name__ == '__main__':
    unittest.main()
