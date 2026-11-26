"""Reject tampered copies of completed real resource history evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('resource_inspector', Path(__file__).with_name('inspect-resource-history.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualResourceHistoryEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        location = os.environ.get('STARCHRONICLE_RESOURCE_RUN')
        if not location:
            raise unittest.SkipTest('Requires completed real resource history evidence')
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
        for name in ('StarCore', 'StarChronicle', 'ResourceHistoryPaperFixture'):
            os.link(self.source / f'plugins/{name}.jar', self.run / f'plugins/{name}.jar')
        os.link(self.source / 'paper.jar', self.run / 'paper.jar')

    def db(self, plugin, boot=3):
        return self.run / f'evidence/boot{boot}.data/{plugin}/' / ('chronicle.db' if plugin == 'StarChronicle' else 'starcore.db')

    def reject(self):
        with self.assertRaises(AssertionError):
            INSPECTOR.inspect(self.run)

    def test_real_three_boot_source_and_consumer_evidence_passes(self):
        self.assertEqual(INSPECTOR.inspect(self.run), self.original)

    def test_missing_source_receipt_is_rejected(self):
        next((self.run / 'evidence/boot2.data/StarCore/resource/history').rglob('2.receipt')).unlink()
        self.reject()

    def test_stockpile_changed_without_history_is_rejected(self):
        with sqlite3.connect(self.db('StarCore')) as db:
            db.execute("UPDATE starcore_resource_state SET property_value='1' WHERE property_key='stockpile.0.amount'")
        self.reject()

    def test_full_resource_receipt_cannot_be_omitted(self):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            db.execute('DELETE FROM resource_history_receipts WHERE sequence=139')
        self.reject()

    def test_full_resource_receipt_cannot_change_balances(self):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            row = db.execute('SELECT event_id,json FROM resource_history_receipts WHERE sequence=139').fetchone()
            value = json.loads(row[1]); value['balances'][0]['before'] += 1
            db.execute('UPDATE resource_history_receipts SET json=? WHERE event_id=?', (json.dumps(value), row[0]))
        self.reject()

    def test_first_archive_cannot_activate_story_inbox(self):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            db.execute("UPDATE committed_history_ingress SET activate=1 WHERE namespace='starcore.resources' AND sequence=1")
        self.reject()

    def mutate_event(self, key, value):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            row = db.execute("SELECT id,json FROM events WHERE json_extract(json,'$.facts.\"history.namespace\"')='starcore.resources' LIMIT 1").fetchone()
            event = json.loads(row[1]); event[key] = value
            db.execute('UPDATE events SET json=? WHERE id=?', (json.dumps(event), row[0]))

    def test_native_time_precision_loss_is_rejected(self):
        self.mutate_event('occurredAt', '2026-11-26T00:00:00Z')
        self.reject()

    def test_unattributed_resource_change_cannot_invent_an_actor(self):
        self.mutate_event('participants', ['00000000-0000-0000-0000-000000000001'])
        self.reject()

    def test_cross_nation_transfer_requires_both_story_inboxes(self):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            db.execute('DELETE FROM story_triggers WHERE id=(SELECT id FROM story_triggers LIMIT 1)')
        self.reject()

    def test_dropped_source_warning_is_not_a_passing_run(self):
        path = self.run / 'evidence/boot2.server.log'
        path.write_text(path.read_text() + '\n[WARN]: Resource history scan retained its cursor: source unavailable\n')
        self.reject()

    def test_abnormal_shutdown_is_not_a_passing_restart(self):
        path = self.run / 'evidence/boot3.session.json'
        data = json.loads(path.read_text()); data['exit_code'] = 137
        path.write_text(json.dumps(data))
        self.reject()

    def test_transfer_ledger_cannot_disagree_with_inventory_receipts(self):
        with sqlite3.connect(self.db('StarCore')) as db:
            db.execute("UPDATE starcore_resource_transfer_receipts SET resources='energy=2;mineral=1' WHERE sequence=1")
        self.reject()

    def test_sql_authority_pin_is_required(self):
        path = self.run / 'evidence/boot3.data/StarCore/resource/resource-sql-authority'
        path.write_text('MIGRATING')
        self.reject()

    def test_schema_cannot_allow_old_consumers_to_skip_full_receipts(self):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            db.execute('PRAGMA user_version=8')
        self.reject()

    def test_cursor_cannot_silently_roll_back(self):
        with sqlite3.connect(self.db('StarChronicle')) as db:
            db.execute("UPDATE committed_history_sources SET sequence=138 WHERE namespace='starcore.resources'")
        self.reject()

    def test_public_api_cannot_change_a_full_receipt(self):
        path = self.run / 'evidence/fixture.jsonl'
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        for row in rows:
            if row['kind'] == 'CHECK' and row['boot'] == 2:
                row['data']['receipts'][0]['description'] = 'tampered'
                break
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        self.reject()


if __name__ == '__main__':
    unittest.main()

