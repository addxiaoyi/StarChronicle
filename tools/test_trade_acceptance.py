"""Reject damaged copies of actual three-boot trade-history storage evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('trade_inspector', Path(__file__).with_name('inspect-trade-paper.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualTradeEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied = os.environ.get('STARCHRONICLE_TRADE_RUN')
        if not supplied:
            raise unittest.SkipTest('Requires a completed actual trade-history storage run')
        cls.source = Path(supplied).resolve()
        cls.original = INSPECTOR.inspect(cls.source)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.run = Path(temporary.name) / self.source.name
        self.run.mkdir()
        for relative in ('preparation.json', 'fixture/EpicCampaignPaperFixture.java',
                         'plugins/StarCore/starcore.db', 'plugins/StarChronicle/chronicle.db'):
            destination = self.run / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(self.source / relative, destination)
        shutil.copytree(self.source / 'evidence', self.run / 'evidence')
        for name in ('StarCore', 'StarChronicle', 'EpicCampaignPaperFixture'):
            os.link(self.source / f'plugins/{name}.jar', self.run / f'plugins/{name}.jar')

    def reject(self, message):
        with self.assertRaisesRegex(AssertionError, message):
            INSPECTOR.inspect(self.run)

    def reject_sql(self, sql, message, native=False):
        with sqlite3.connect(self.run / ('plugins/StarCore/starcore.db' if native else 'plugins/StarChronicle/chronicle.db')) as db:
            db.execute(sql)
        self.reject(message)

    def test_original_live_run_passes(self):
        self.assertEqual(INSPECTOR.inspect(self.run), self.original)

    def test_missing_boot_is_rejected(self):
        (self.run / 'evidence/boot3.session.json').unlink()
        self.reject('three completed')

    def test_abnormal_exit_is_rejected(self):
        path = self.run / 'evidence/boot2.session.json'
        data = INSPECTOR.EPIC.read(path)
        data['processes'][0]['exit_code'] = 137
        path.write_text(json.dumps(data))
        self.reject('Abnormal process exit')

    def test_real_retained_shutdown_failure_is_rejected(self):
        supplied = os.environ.get('STARCHRONICLE_EPIC_FAILURE_LOG')
        if not supplied or not Path(supplied).is_file():
            self.skipTest('Requires retained real policy conflict log')
        shutil.copy2(supplied, self.run / 'evidence/boot1.server.log')
        self.reject('persistence/lifecycle diagnostic')

    def test_trade_source_read_failure_is_rejected(self):
        with (self.run / 'evidence/boot3.server.log').open('a') as out:
            out.write('\n[WARN] Trade history source retained for reconciliation: missing committed row\n')
        self.reject('persistence/lifecycle diagnostic')

    def test_missing_actual_query_is_rejected(self):
        path = self.run / 'evidence/boot3.clients.log'
        path.write_text('\n'.join(line for line in path.read_text().splitlines() if 'COMMAND_RESULT' not in line))
        self.reject('actual contribution query')

    def test_false_response_is_rejected(self):
        path = self.run / 'evidence/boot3.clients.log'
        rows = INSPECTOR.records(path)
        next(r for r in rows if r['kind'] == 'COMMAND_RESULT')['data']['response'] = 'unrelated'
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        self.reject('actual contribution response')

    def test_missing_api_observation_is_rejected(self):
        path = self.run / 'evidence/epic-fixture.jsonl'
        rows = INSPECTOR.records(path)
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows if not (r['boot'] == 3 and r['kind'] == 'TRADE_CHECK')))
        self.reject('live trade API observation')

    def test_reordered_late_write_is_rejected(self):
        path = self.run / 'evidence/epic-fixture.jsonl'
        rows = INSPECTOR.records(path)
        next(r for r in rows if r['kind'] == 'TRADE_WRITE' and r['data']['name'] == 'late')['time'] = next(r for r in rows if r['kind'] == 'READY')['time']
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        self.reject('causal order')

    def test_deleted_late_native_change_is_rejected(self):
        self.reject_sql('delete from trade_history_changes where sequence_id=2', 'Native committed sequence gap', native=True)

    def test_rewritten_original_native_status_is_rejected(self):
        self.reject_sql("update trade_history_changes set record_json=json_set(record_json,'$.status','DISPUTED') where sequence_id=1", 'Native immutable snapshot', native=True)

    def test_rewritten_original_native_nation_is_rejected(self):
        self.reject_sql("update trade_history_changes set record_json=json_set(record_json,'$.buyerNationId','00000000-0000-0000-0000-000000000000') where sequence_id=2", 'Native immutable snapshot', native=True)

    def test_rewritten_late_execution_time_is_rejected(self):
        self.reject_sql("update trade_history_changes set record_json=json_set(record_json,'$.executedAt','2026-01-01T00:00:00Z') where sequence_id=2", 'Native immutable snapshot', native=True)

    def test_rebound_source_domain_is_rejected(self):
        self.reject_sql("update committed_history_sources set authority_domain='00000000-0000-0000-0000-000000000000'", 'Chronicle source domain/cursor')

    def test_missing_acknowledgement_is_rejected(self):
        self.reject_sql("update committed_history_ingress set status='PENDING' where sequence=5", 'Inbox acknowledgement')

    def test_missing_inbox_is_rejected(self):
        self.reject_sql('delete from committed_history_ingress where sequence=2', 'Missing immutable inbox')

    def test_rewritten_inbox_and_event_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarChronicle/chronicle.db') as db:
            db.execute("update committed_history_ingress set json=json_set(json,'$.facts.\"trade.amount\"','999') where sequence=2")
            db.execute("update events set json=json_set(json,'$.facts.\"trade.amount\"','999') where id like 'starcore:trade-history:%:2'")
        self.reject('Inbox differs from native journal')

    def test_missing_retained_event_is_rejected(self):
        self.reject_sql("delete from events where id like 'starcore:trade-history:%:1'", 'five retained trade events')

    def test_changed_event_hash_is_rejected(self):
        self.reject_sql("update events set hash='rewritten' where id like 'starcore:trade-history:%:1'", 'canonical event hash')

    def test_false_contribution_is_rejected(self):
        self.reject_sql("insert into contributions select 'false-trade','false-trade','TRADE','false-subject',id,'false-actor',NULL,'1','trade','2026-01-01T00:00:00Z','not asset proof','{}','2026-01-01T00:00:00Z' from events where id like 'starcore:trade-history:%:1'", 'History storage cannot prove contributions')


if __name__ == '__main__':
    unittest.main()
