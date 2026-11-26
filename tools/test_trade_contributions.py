"""Mutate copied real acceptance evidence to ensure the checker rejects partial or false success."""
from pathlib import Path
import importlib.util
import json
import os
import shutil
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('trade_contribution_inspector', Path(__file__).with_name('inspect-trade-contributions.py'))
inspector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspector)


@unittest.skipUnless(os.environ.get('STARCHRONICLE_TRADE_CONTRIBUTION_RUN'), 'Set the real accepted run directory')
class TradeContributionEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.original = Path(os.environ['STARCHRONICLE_TRADE_CONTRIBUTION_RUN']).resolve()
        self.temporary = tempfile.TemporaryDirectory(prefix='trade-evidence-test-')
        self.addCleanup(self.temporary.cleanup)
        self.run = Path(self.temporary.name) / 'run'
        self.run.mkdir()
        for name in ('fixture', 'evidence'):
            shutil.copytree(self.original / name, self.run / name)
        shutil.copy2(self.original / 'preparation.json', self.run / 'preparation.json')
        # Large frozen artifacts are only read. Every mutated file/SQL database is a private copy.
        (self.run / 'plugins').mkdir()
        for jar in (self.original / 'plugins').glob('*.jar'):
            (self.run / 'plugins' / jar.name).symlink_to(jar)
        (self.run / 'paper.jar').symlink_to(self.original / 'paper.jar')

    def sql(self, statement, boot=3, native=False):
        name, filename = ('StarCore', 'starcore.db') if native else ('StarChronicle', 'chronicle.db')
        with sqlite3.connect(self.run / f'evidence/boot{boot}.data' / name / filename) as c:
            c.execute(statement)

    def mutate_json(self, relative, callback):
        path = self.run / relative
        value = json.loads(path.read_text()); callback(value)
        path.write_text(json.dumps(value) + '\n')

    def mutate_fixture(self, callback):
        path = self.run / 'evidence/fixture.jsonl'
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        callback(rows)
        path.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows))

    def reject(self, message):
        with self.assertRaisesRegex(AssertionError, message):
            inspector.inspect(self.run)

    def test_unmodified_real_three_boot_evidence(self):
        result = inspector.inspect(self.run)
        self.assertEqual((139, 534, 554), (result['native_changes'], result['original_contributions'], result['status_revisions']))

    def test_artifact_mismatch(self):
        self.mutate_json('preparation.json', lambda value: value.update(product_sha256='0' * 64))
        self.reject('fingerprint drift')

    def test_source_sequence_gap(self):
        self.sql('DELETE FROM trade_history_commits WHERE sequence_id=64', native=True)
        self.reject('Missing native sequence')

    def test_changed_historical_nation(self):
        self.sql("UPDATE contributions SET request_json=json_set(request_json,'$.nationId','rewritten-current-nation') WHERE id=(SELECT id FROM contributions LIMIT 1)")
        self.reject('Original contribution rewritten')

    def test_refund_status_overwritten(self):
        self.sql("UPDATE contribution_revisions SET status='ACTIVE' WHERE status='REFUNDED'")
        self.reject('Revision audit overwritten')

    def test_missing_consumer_receipt(self):
        self.sql('DELETE FROM trade_history_receipts WHERE sequence=139')
        self.reject('Partial event or receipt')

    def test_archive_reactivated(self):
        self.sql("UPDATE committed_history_ingress SET activate=1 WHERE namespace='starcore.trade' AND sequence=1")
        self.reject('Archive/live policy')

    def test_failed_process_marked_success(self):
        self.mutate_json('evidence/boot3.session.json', lambda value: value.update(exit_code=1))
        self.reject('Incomplete process lifecycle')

    def test_injected_fault_hidden(self):
        self.mutate_json('evidence/boot1.session.json', lambda value: value.update(expected_faults=[]))
        self.reject('Unaccounted source or consumer fault')

    def test_restart_asset_replay(self):
        def mutate(rows):
            row = next(r for r in rows if r['boot'] == 3 and r['kind'] == 'READY')
            row['data']['balances']['buyerResource'] += 1
        self.mutate_fixture(mutate)
        self.reject('Restart changed assets')

    def test_public_total_still_counts_refund(self):
        def mutate(rows):
            row = next(r for r in rows if r['boot'] == 2 and r['kind'] == 'CHECK')
            subject = next(iter(row['data']['totals']))
            row['data']['totals'][subject] = '999999'
        self.mutate_fixture(mutate)
        self.reject('Public net totals')

    def test_history_timestamp_replaced_by_poll_time(self):
        self.sql("UPDATE events SET json=json_set(json,'$.occurredAt','2020-01-01T00:00:00Z') WHERE id=(SELECT event_id FROM trade_history_receipts WHERE sequence=1)")
        self.reject('Consumer receipt or event changed')

    def test_pending_refund_prematurely_acknowledged(self):
        self.sql("UPDATE committed_history_ingress SET status='ACKED' WHERE namespace='starcore.trade' AND sequence=136", boot=1)
        self.reject('Wrong receipt acknowledgement')


if __name__ == '__main__':
    unittest.main()
