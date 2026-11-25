"""Reject corrupted copies of actual native resource history and three-boot evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('resource_inspector', Path(__file__).with_name('inspect-resource-paper.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualResourceEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied = os.environ.get('STARCHRONICLE_RESOURCE_RUN')
        if not supplied:
            raise unittest.SkipTest('Requires a completed actual resource history run')
        cls.source = Path(supplied).resolve(); cls.original = INSPECTOR.inspect(cls.source)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.run = Path(temporary.name) / self.source.name; self.run.mkdir()
        for relative in ('preparation.json', 'fixture/EpicCampaignPaperFixture.java', 'plugins/StarCore/starcore.db', 'plugins/StarChronicle/chronicle.db'):
            destination = self.run / relative; destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(self.source / relative, destination)
        for relative in ('evidence', 'plugins/StarCore/resource'):
            shutil.copytree(self.source / relative, self.run / relative)
        for name in ('StarCore', 'StarChronicle', 'EpicCampaignPaperFixture'):
            os.link(self.source / f'plugins/{name}.jar', self.run / f'plugins/{name}.jar')

    def reject(self, message):
        with self.assertRaisesRegex(AssertionError, message): INSPECTOR.inspect(self.run)

    def reject_sql(self, sql, message, native=False):
        with sqlite3.connect(self.run / ('plugins/StarCore/starcore.db' if native else 'plugins/StarChronicle/chronicle.db')) as db: db.execute(sql)
        self.reject(message)

    def test_original_live_run_passes(self):
        self.assertEqual(INSPECTOR.inspect(self.run), self.original)

    def test_missing_boot_is_rejected(self):
        (self.run / 'evidence/boot3.session.json').unlink(); self.reject('three completed')

    def test_abnormal_exit_is_rejected(self):
        path = self.run / 'evidence/boot2.session.json'; data = INSPECTOR.EPIC.read(path)
        data['processes'][0]['exit_code'] = 137; path.write_text(json.dumps(data)); self.reject('Abnormal process exit')

    def test_real_retained_shutdown_failure_is_rejected(self):
        supplied = os.environ.get('STARCHRONICLE_EPIC_FAILURE_LOG')
        if not supplied or not Path(supplied).is_file(): self.skipTest('Requires retained real policy conflict log')
        shutil.copy2(supplied, self.run / 'evidence/boot1.server.log'); self.reject('persistence/lifecycle diagnostic')

    def test_real_weather_shutdown_failure_is_rejected(self):
        supplied = os.environ.get('STARCHRONICLE_WEATHER_FAILURE_LOG')
        if not supplied or not Path(supplied).is_file(): self.skipTest('Requires retained real weather lock failure')
        shutil.copy2(supplied, self.run / 'evidence/boot1.server.log'); self.reject('persistence/lifecycle diagnostic')

    def test_source_reconciliation_failure_is_rejected(self):
        with (self.run / 'evidence/boot3.server.log').open('a') as out:
            out.write('\n[WARN] Committed history source starcore.resource.transfers.v1 retained for reconciliation: missing receipt\n')
        self.reject('persistence/lifecycle diagnostic')

    def test_missing_actual_query_is_rejected(self):
        path = self.run / 'evidence/boot3.clients.log'
        path.write_text('\n'.join(line for line in path.read_text().splitlines() if 'COMMAND_RESULT' not in line)); self.reject('actual contribution query')

    def test_missing_live_observation_is_rejected(self):
        path = self.run / 'evidence/epic-fixture.jsonl'; rows = INSPECTOR.records(path)
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows if not (r['boot'] == 3 and r['kind'] == 'RESOURCE_CHECK')))
        self.reject('live resource API observation')

    def test_reordered_restart_is_rejected(self):
        path = self.run / 'evidence/epic-fixture.jsonl'; rows = INSPECTOR.records(path)
        next(r for r in rows if r['kind'] == 'RESOURCE_RESTART')['time'] = next(r for r in rows if r['boot'] == 2 and r['kind'] == 'READY')['time']
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows)); self.reject('causal order')

    def test_deleted_middle_receipt_is_rejected(self):
        self.reject_sql('delete from starcore_resource_transfer_receipts where sequence=2', 'native transfer receipt', native=True)

    def test_deleted_tail_receipt_is_rejected(self):
        self.reject_sql('delete from starcore_resource_transfer_receipts where sequence=3', 'native transfer receipt', native=True)

    def test_rewritten_head_is_rejected(self):
        self.reject_sql('update starcore_resource_transfer_history set committed_sequence=2', 'independent journal head', native=True)

    def test_missing_head_is_rejected(self):
        self.reject_sql('delete from starcore_resource_transfer_history', 'independent journal head', native=True)

    def test_missing_migration_marker_is_rejected(self):
        self.reject_sql("delete from starcore_resource_state_migrations where migration_key='resource-transfer-history-v1'", 'migration marker', native=True)

    def test_foreign_receipt_domain_is_rejected(self):
        self.reject_sql("update starcore_resource_transfer_receipts set authority_domain_id='00000000-0000-0000-0000-000000000000' where sequence=2", 'receipt differs from requested', native=True)

    def test_changed_transfer_direction_is_rejected(self):
        self.reject_sql('update starcore_resource_transfer_receipts set payer_nation_id=receiver_nation_id where sequence=1', 'receipt differs from requested', native=True)

    def test_changed_quantity_is_rejected(self):
        self.reject_sql("update starcore_resource_transfer_receipts set resources='agricultural=999' where sequence=1", 'receipt differs from requested', native=True)

    def test_changed_stockpile_is_rejected(self):
        self.reject_sql("update starcore_resource_state set property_value='999' where property_key='stockpile.0.amount'", 'final stockpiles', native=True)

    def test_rebound_file_authority_is_rejected(self):
        (self.run / 'plugins/StarCore/resource/resource-sql-authority').write_text('FILE'); self.reject('SQL authority marker')

    def test_rebound_chronicle_domain_is_rejected(self):
        self.reject_sql("update committed_history_sources set authority_domain='00000000-0000-0000-0000-000000000000' where source='starcore.resource.transfers.v1'", 'resource domain/cursor')

    def test_missing_acknowledgement_is_rejected(self):
        self.reject_sql("update committed_history_ingress set status='PENDING' where source='starcore.resource.transfers.v1' and sequence=3", 'inbox acknowledgement')

    def test_missing_inbox_is_rejected(self):
        self.reject_sql("delete from committed_history_ingress where source='starcore.resource.transfers.v1' and sequence=2", 'Missing immutable resource inbox')

    def test_invented_actor_is_rejected(self):
        self.reject_sql("update committed_history_ingress set json=json_set(json,'$.participants',json('[\"00000000-0000-0000-0000-000000000000\"]')) where source='starcore.resource.transfers.v1' and sequence=1", 'inbox differs from native receipt')

    def test_missing_retained_event_is_rejected(self):
        self.reject_sql("delete from events where id like 'starcore:resource-transfer-history:%:2'", 'three retained resource events')

    def test_changed_event_hash_is_rejected(self):
        self.reject_sql("update events set hash='rewritten' where id like 'starcore:resource-transfer-history:%:1'", 'canonical event hash')

    def test_false_contribution_is_rejected(self):
        self.reject_sql("insert into contributions select 'false-resource','false-resource','CIVILIZATION','false-subject',id,'false-actor',NULL,'1','resource','2026-01-01T00:00:00Z','not actor proof','{}','2026-01-01T00:00:00Z' from events where id like 'starcore:resource-transfer-history:%:1'", 'cannot prove player contributions')


if __name__ == '__main__': unittest.main()
