"""Reject tampered copies of completed real technology history evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('technology_inspector', Path(__file__).with_name('inspect-technology-history.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualTechnologyHistoryEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        location = os.environ.get('STARCHRONICLE_TECHNOLOGY_RUN')
        if not location:
            raise unittest.SkipTest('Requires completed real technology history evidence')
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
        for name in ('StarCore', 'StarChronicle', 'TechnologyHistoryPaperFixture'):
            os.link(self.source / f'plugins/{name}.jar', self.run / f'plugins/{name}.jar')
        os.link(self.source / 'paper.jar', self.run / 'paper.jar')

    def db(self, plugin, boot=3):
        return self.run / f'evidence/boot{boot}.data/{plugin}/' / ('chronicle.db' if plugin == 'StarChronicle' else 'starcore.db')

    def test_real_three_boot_source_and_consumer_evidence_passes(self):
        self.assertEqual(INSPECTOR.inspect(self.run), self.original)

    def test_missing_source_receipt_is_rejected(self):
        next((self.run / 'evidence/boot2.data/StarCore/technology/history').rglob('2.receipt')).unlink()
        with self.assertRaisesRegex(AssertionError, 'Missing native committed receipt'):
            INSPECTOR.inspect(self.run)

    def test_native_state_and_history_mismatch_is_rejected(self):
        with sqlite3.connect(self.db('StarCore')) as db:
            db.execute("UPDATE starcore_technology_state SET property_value='138' WHERE property_key='history.sequence'")
        with self.assertRaisesRegex(AssertionError, 'Native source head changed'):
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

    def test_fake_personal_attribution_is_rejected(self):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            row = db.execute("SELECT id,json FROM events WHERE json_extract(json,'$.facts.\"history.sequence\"')='139'").fetchone()
            value = json.loads(row[1]); value['participants'] = ['00000000-0000-0000-0000-000000000001']
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
        path.write_text(path.read_text(encoding='utf-8') + '\n[WARN]: Technology history scan retained its cursor: source unavailable\n', encoding='utf-8')
        with self.assertRaisesRegex(AssertionError, 'Server error or dropped source evidence'):
            INSPECTOR.inspect(self.run)

    def test_abnormal_shutdown_is_not_a_passing_restart(self):
        path = self.run / 'evidence/boot3.session.json'
        data = json.loads(path.read_text(encoding='utf-8')); data['exit_code'] = 137
        path.write_text(json.dumps(data), encoding='utf-8')
        with self.assertRaisesRegex(AssertionError, 'Incomplete process lifecycle'):
            INSPECTOR.inspect(self.run)


if __name__ == '__main__':
    unittest.main()
