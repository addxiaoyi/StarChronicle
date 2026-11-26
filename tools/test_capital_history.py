"""Reject tampered copies of completed real capital history evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('capital_inspector', Path(__file__).with_name('inspect-capital-history.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualCapitalHistoryEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        location = os.environ.get('STARCHRONICLE_CAPITAL_RUN')
        if not location:
            raise unittest.SkipTest('Requires completed real capital history evidence')
        cls.source = Path(location).resolve()
        cls.original = INSPECTOR.inspect(cls.source)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.run = Path(temporary.name) / self.source.name
        self.run.mkdir()
        for directory in ('fixture', 'evidence'):
            shutil.copytree(self.source / directory, self.run / directory)
        shutil.copy2(self.source / 'preparation.json', self.run / 'preparation.json')
        (self.run / 'plugins').mkdir()
        for name in ('StarCore', 'StarChronicle', 'CapitalHistoryPaperFixture'):
            os.link(self.source / f'plugins/{name}.jar', self.run / f'plugins/{name}.jar')
        os.link(self.source / 'paper.jar', self.run / 'paper.jar')

    def db(self, plugin, boot=3):
        return self.run / f'evidence/boot{boot}.data/{plugin}/' / ('chronicle.db' if plugin == 'StarChronicle' else 'nation/capital-designations.log.sqlite')

    def test_real_three_boot_source_and_consumer_evidence_passes(self):
        self.assertEqual(INSPECTOR.inspect(self.run), self.original)

    def test_missing_source_receipt_is_rejected(self):
        with sqlite3.connect(self.db('StarCore', 2)) as db:
            db.execute('DELETE FROM designations WHERE sequence=2')
        with self.assertRaisesRegex(AssertionError, 'Missing native committed receipt'):
            INSPECTOR.inspect(self.run)

    def test_native_state_and_history_mismatch_is_rejected(self):
        with sqlite3.connect(self.db('StarCore')) as db:
            db.execute("UPDATE authority SET sequence=138")
        with self.assertRaisesRegex(AssertionError, 'Native source head or domain changed'):
            INSPECTOR.inspect(self.run)

    def test_missing_restart_source_recovery_is_rejected(self):
        with sqlite3.connect(self.db('StarChronicle', 2)) as db:
            db.execute("DELETE FROM committed_history_ingress WHERE sequence=136")
        with self.assertRaisesRegex(AssertionError, 'Missing or repeated ingress sequence'):
            INSPECTOR.inspect(self.run)

    def test_first_archive_cannot_activate_story_inbox(self):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            db.execute('UPDATE committed_history_ingress SET activate=1 WHERE sequence=1')
        with self.assertRaisesRegex(AssertionError, 'initial archive mode changed'):
            INSPECTOR.inspect(self.run)

    def test_native_time_precision_loss_is_rejected(self):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            row = db.execute("SELECT id,json FROM events WHERE json_extract(json,'$.facts.\"history.sequence\"')='139'").fetchone()
            value = json.loads(row[1]); value['occurredAt'] = '2026-11-26T00:00:00Z'
            db.execute('UPDATE events SET json=? WHERE id=?', (json.dumps(value), row[0]))
        with self.assertRaisesRegex(AssertionError, 'Native timestamp precision changed'):
            INSPECTOR.inspect(self.run)

    def test_original_actor_cannot_be_replaced_by_current_founder(self):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            row = db.execute("SELECT id,json FROM events WHERE json_extract(json,'$.facts.\"history.sequence\"')='1'").fetchone()
            latest = json.loads(db.execute("SELECT json FROM events WHERE json_extract(json,'$.facts.\"history.sequence\"')='139'").fetchone()[0])
            value = json.loads(row[1]); value['participants'] = latest['participants']
            db.execute('UPDATE events SET json=? WHERE id=?', (json.dumps(value), row[0]))
        with self.assertRaisesRegex(AssertionError, 'False historical player/nation attribution'):
            INSPECTOR.inspect(self.run)

    def test_live_event_without_durable_story_inbox_is_rejected(self):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            db.execute('DELETE FROM story_triggers WHERE id=(SELECT id FROM story_triggers LIMIT 1)')
        with self.assertRaisesRegex(AssertionError, 'live commit lost its inbox'):
            INSPECTOR.inspect(self.run)

    def test_dropped_source_warning_is_not_a_passing_run(self):
        path = self.run / 'evidence/boot2.server.log'
        path.write_text(path.read_text() + '\n[WARN]: Capital history scan retained its cursor: source unavailable\n')
        with self.assertRaisesRegex(AssertionError, 'Server error or dropped source evidence'):
            INSPECTOR.inspect(self.run)

    def test_abnormal_shutdown_is_not_a_passing_restart(self):
        path = self.run / 'evidence/boot3.session.json'
        data = json.loads(path.read_text()); data['exit_code'] = 137
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(AssertionError, 'Incomplete process lifecycle'):
            INSPECTOR.inspect(self.run)

    def test_capital_transition_cannot_be_rewritten(self):
        with sqlite3.connect(self.db('StarCore')) as db:
            db.execute("UPDATE designations SET previous_city=NULL WHERE sequence=2")
        with self.assertRaisesRegex(AssertionError, 'Native capital transition changed'):
            INSPECTOR.inspect(self.run)

    def test_migration_fence_identity_is_required(self):
        path = self.run / 'evidence/boot3.data/StarCore/nation/capital-designations.log'
        path.write_text(path.read_text().replace('|READY|', '|MIGRATING|'))
        with self.assertRaisesRegex(AssertionError, 'Native authority marker mismatch'):
            INSPECTOR.inspect(self.run)

    def test_public_api_cannot_drop_authorization_proof(self):
        path = self.run / 'evidence/fixture.jsonl'
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        rows = [r for r in rows if r['kind'] != 'AUTHORITY' or r['boot'] != 2]
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        with self.assertRaisesRegex(AssertionError, 'Missing native authorization and replay proof'):
            INSPECTOR.inspect(self.run)


if __name__ == '__main__':
    unittest.main()
