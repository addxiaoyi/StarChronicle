"""Reject corrupted copies of actual native technology history and three-boot evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('technology_inspector', Path(__file__).with_name('inspect-technology-paper.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualTechnologyEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied = os.environ.get('STARCHRONICLE_TECHNOLOGY_RUN')
        if not supplied:
            raise unittest.SkipTest('Requires a completed actual technology history run')
        cls.source = Path(supplied).resolve(); cls.original = INSPECTOR.inspect(cls.source)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.run = Path(temporary.name) / self.source.name; self.run.mkdir()
        for relative in ('preparation.json', 'fixture/EpicCampaignPaperFixture.java', 'plugins/StarCore/starcore.db', 'plugins/StarChronicle/chronicle.db'):
            destination = self.run / relative; destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(self.source / relative, destination)
        for relative in ('evidence', 'plugins/StarCore/technology'):
            shutil.copytree(self.source / relative, self.run / relative)
        for name in ('StarCore', 'StarChronicle', 'EpicCampaignPaperFixture'):
            os.link(self.source / f'plugins/{name}.jar', self.run / f'plugins/{name}.jar')

    def reject(self, message):
        with self.assertRaisesRegex(AssertionError, message): INSPECTOR.inspect(self.run)

    def reject_sql(self, sql, message, native=False):
        with sqlite3.connect(self.run / ('plugins/StarCore/starcore.db' if native else 'plugins/StarChronicle/chronicle.db')) as db: db.execute(sql)
        self.reject(message)

    def receipt(self, sequence):
        return next((self.run / 'plugins/StarCore/technology/history').rglob(str(sequence) + '.receipt'))

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
            out.write('\n[WARN] Committed history source starcore.technology.history.v1 retained for reconciliation: missing receipt\n')
        self.reject('persistence/lifecycle diagnostic')

    def test_missing_actual_query_is_rejected(self):
        path = self.run / 'evidence/boot3.clients.log'
        path.write_text('\n'.join(line for line in path.read_text().splitlines() if 'COMMAND_RESULT' not in line)); self.reject('actual contribution query')

    def test_missing_live_observation_is_rejected(self):
        path = self.run / 'evidence/epic-fixture.jsonl'; rows = INSPECTOR.records(path)
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows if not (r['boot'] == 3 and r['kind'] == 'TECH_CHECK')))
        self.reject('live technology API observation')

    def test_reordered_revoke_is_rejected(self):
        path = self.run / 'evidence/epic-fixture.jsonl'; rows = INSPECTOR.records(path)
        next(r for r in rows if r['kind'] == 'TECH_REVOKED')['time'] = next(r for r in rows if r['boot'] == 2 and r['kind'] == 'READY')['time']
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows)); self.reject('causal order')

    def test_deleted_transient_revoke_receipt_is_rejected(self):
        self.receipt(2).unlink(); self.reject('Missing or extra immutable technology receipt')

    def test_rewritten_transient_revoke_is_rejected(self):
        path = self.receipt(2); path.write_text(path.read_text().replace('|REVOKED|', '|UNLOCKED|'))
        self.reject('Native technology sequence/change')

    def test_reused_native_operation_is_rejected(self):
        first = self.receipt(1).read_text().split('|'); second = self.receipt(2).read_text().split('|'); second[3] = first[3]
        self.receipt(2).write_text('|'.join(second)); self.reject('operation identity reused')

    def test_wrong_native_authority_marker_is_rejected(self):
        (self.run / 'plugins/StarCore/technology/history/authority-domain').write_text('00000000-0000-0000-0000-000000000000')
        self.reject('history authority marker')

    def test_wrong_native_sql_marker_is_rejected(self):
        (self.run / 'plugins/StarCore/technology/technology-sql-authority').write_text('FILE')
        self.reject('SQL authority marker')

    def test_stale_native_sql_outbox_is_rejected(self):
        self.reject_sql("update starcore_technology_state set property_value='missing' where property_key='history.pending'", 'final state/outbox', native=True)

    def test_wrong_current_technology_state_is_rejected(self):
        self.reject_sql("update starcore_technology_state set property_value='1' where property_key='count'", 'final state/outbox', native=True)

    def test_rebound_chronicle_domain_is_rejected(self):
        self.reject_sql("update committed_history_sources set authority_domain='00000000-0000-0000-0000-000000000000' where source='starcore.technology.history.v1'", 'technology domain/cursor')

    def test_missing_acknowledgement_is_rejected(self):
        self.reject_sql("update committed_history_ingress set status='PENDING' where source='starcore.technology.history.v1' and sequence=4", 'inbox acknowledgement')

    def test_missing_inbox_is_rejected(self):
        self.reject_sql("delete from committed_history_ingress where source='starcore.technology.history.v1' and sequence=2", 'Missing immutable technology inbox')

    def test_invented_researcher_is_rejected(self):
        self.reject_sql("update committed_history_ingress set json=json_set(json,'$.participants',json('[\"00000000-0000-0000-0000-000000000000\"]')) where source='starcore.technology.history.v1' and sequence=1", 'inbox differs from native receipt')

    def test_missing_retained_event_is_rejected(self):
        self.reject_sql("delete from events where id like 'starcore:technology-history:%:2'", 'four retained technology events')

    def test_changed_event_hash_is_rejected(self):
        self.reject_sql("update events set hash='rewritten' where id like 'starcore:technology-history:%:1'", 'canonical event hash')

    def test_false_contribution_is_rejected(self):
        self.reject_sql("insert into contributions select 'false-tech','false-tech','CIVILIZATION','false-subject',id,'false-actor',NULL,'1','technology','2026-01-01T00:00:00Z','not researcher proof','{}','2026-01-01T00:00:00Z' from events where id like 'starcore:technology-history:%:1'", 'cannot prove player contributions')


if __name__ == '__main__': unittest.main()
