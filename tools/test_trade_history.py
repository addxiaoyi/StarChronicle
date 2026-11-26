"""Mutation checks against completed native Paper trade evidence; no fabricated acceptance."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('trade_inspector', Path(__file__).with_name('inspect-trade-history.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class NativeTradeEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        location = os.environ.get('STARCHRONICLE_TRADE_RUN')
        if not location:
            raise unittest.SkipTest('Requires completed real trade history evidence')
        cls.source = Path(location).resolve()
        cls.original = INSPECTOR.inspect(cls.source)

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.run = Path(temp.name) / self.source.name
        self.run.mkdir()
        for directory in ('fixture', 'evidence'):
            shutil.copytree(self.source / directory, self.run / directory)
        shutil.copy2(self.source / 'preparation.json', self.run / 'preparation.json')
        (self.run / 'plugins').mkdir()
        for name in ('StarCore', 'StarChronicle', 'TradeHistoryPaperFixture'):
            os.link(self.source / f'plugins/{name}.jar', self.run / f'plugins/{name}.jar')
        os.link(self.source / 'paper.jar', self.run / 'paper.jar')

    def db(self, boot=3):
        return self.run / f'evidence/boot{boot}.data/StarCore/starcore.db'

    def test_real_evidence_passes(self):
        self.assertEqual(INSPECTOR.inspect(self.run), self.original)

    def test_missing_sequence_fails(self):
        with sqlite3.connect(self.db()) as db:
            db.execute('DELETE FROM trade_history_commits WHERE sequence_id=129')
        with self.assertRaisesRegex(AssertionError, 'Missing or repeated sequence'):
            INSPECTOR.inspect(self.run)

    def test_changed_identity_fails(self):
        with sqlite3.connect(self.db()) as db:
            db.execute("UPDATE trade_history_identity SET payload=json_set(payload,'$.amount',100) WHERE record_id=(SELECT record_id FROM trade_history_identity LIMIT 1)")
        with self.assertRaisesRegex(AssertionError, 'preserve prepared identity'):
            INSPECTOR.inspect(self.run)

    def test_missing_recovery_ack_fails(self):
        with sqlite3.connect(self.db(2)) as db:
            db.execute('UPDATE trade_settlement_intents SET applied_events=0 WHERE operation_id=(SELECT operation_id FROM trade_settlement_intents LIMIT 1)')
        with self.assertRaisesRegex(AssertionError, 'Unacknowledged settlement'):
            INSPECTOR.inspect(self.run)

    def test_recovery_asset_duplication_fails(self):
        with sqlite3.connect(self.db()) as db:
            db.execute('UPDATE starcore_player_balances SET balance=balance+10')
        with self.assertRaisesRegex(AssertionError, 'Public assets differ'):
            INSPECTOR.inspect(self.run)

    def test_receipt_timestamp_precision_loss_fails(self):
        with sqlite3.connect(self.db()) as db:
            db.execute("UPDATE trade_history SET executed_at_exact='2026-11-26T00:00:00Z'")
        with self.assertRaisesRegex(AssertionError, 'Query view differs'):
            INSPECTOR.inspect(self.run)

    def test_rewritten_transaction_ack_fails(self):
        with sqlite3.connect(self.db()) as db:
            db.execute("UPDATE trade_settlement_intents SET applied_fingerprint='bad'")
        with self.assertRaisesRegex(AssertionError, 'fingerprint changed'):
            INSPECTOR.inspect(self.run)

    def test_uncommitted_transaction_cannot_prove_settlement(self):
        path = self.run / 'evidence/boot2.data/StarCore/economy/transaction-fabric.properties'
        path.write_text(path.read_text().replace('.state=COMMITTED', '.state=RESERVED'))
        with self.assertRaisesRegex(AssertionError, 'No committed transaction proof'):
            INSPECTOR.inspect(self.run)

    def test_unexpected_error_cannot_be_whitelisted(self):
        path = self.run / 'evidence/boot3.server.log'
        path.write_text(path.read_text() + '\n[00:00:00 ERROR]: unexpected storage failure\n')
        with self.assertRaisesRegex(AssertionError, 'Unexpected server error'):
            INSPECTOR.inspect(self.run)

    def test_injected_failure_requires_persisted_trigger(self):
        with sqlite3.connect(self.db(1)) as db:
            db.execute('DROP TRIGGER acceptance_reject_trade_commit')
        with self.assertRaisesRegex(AssertionError, 'No injected SQL trigger'):
            INSPECTOR.inspect(self.run)

    def test_restart_must_exit_normally(self):
        path = self.run / 'evidence/boot3.session.json'
        value = json.loads(path.read_text()); value['exit_code'] = 137
        path.write_text(json.dumps(value))
        with self.assertRaisesRegex(AssertionError, 'Incomplete process lifecycle'):
            INSPECTOR.inspect(self.run)


if __name__ == '__main__':
    unittest.main()
