"""Reject corrupted copies of actual completed three-boot member-review evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('member_inspector', Path(__file__).with_name('inspect-member-paper.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualMemberEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied = os.environ.get('STARCHRONICLE_MEMBER_RUN')
        if not supplied:
            raise unittest.SkipTest('Requires a completed actual member run')
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

    def reject_sql(self, sql, message, native=False):
        with sqlite3.connect(self.run / ('plugins/StarCore/starcore.db' if native else 'plugins/StarChronicle/chronicle.db')) as db:
            db.execute(sql)
        with self.assertRaisesRegex(AssertionError, message):
            INSPECTOR.inspect(self.run)

    def test_original_live_run_passes(self):
        self.assertEqual(INSPECTOR.inspect(self.run), self.original)

    def test_missing_boot_is_rejected(self):
        (self.run / 'evidence/boot3.session.json').unlink()
        with self.assertRaisesRegex(AssertionError, 'three completed'):
            INSPECTOR.inspect(self.run)

    def test_abnormal_exit_is_rejected(self):
        path = self.run / 'evidence/boot2.session.json'
        data = INSPECTOR.EPIC.read(path)
        data['processes'][0]['exit_code'] = 137
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(AssertionError, 'Abnormal process exit'):
            INSPECTOR.inspect(self.run)

    def test_real_retained_shutdown_failure_is_rejected(self):
        supplied = os.environ.get('STARCHRONICLE_EPIC_FAILURE_LOG')
        if not supplied or not Path(supplied).is_file():
            self.skipTest('Requires retained real policy conflict log')
        shutil.copy2(supplied, self.run / 'evidence/boot1.server.log')
        with self.assertRaisesRegex(AssertionError, 'persistence/lifecycle diagnostic'):
            INSPECTOR.inspect(self.run)

    def test_missing_actual_query_is_rejected(self):
        path = self.run / 'evidence/boot3.clients.log'
        path.write_text('\n'.join(line for line in path.read_text().splitlines() if 'COMMAND_RESULT' not in line))
        with self.assertRaisesRegex(AssertionError, 'actual member command query'):
            INSPECTOR.inspect(self.run)

    def test_false_response_is_rejected(self):
        path = self.run / 'evidence/boot3.clients.log'
        rows = INSPECTOR.records(path)
        next(r for r in rows if r['kind'] == 'COMMAND_RESULT')['data']['response'] = 'unrelated'
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        with self.assertRaisesRegex(AssertionError, 'actual member command response'):
            INSPECTOR.inspect(self.run)

    def test_missing_api_observation_is_rejected(self):
        path = self.run / 'evidence/epic-fixture.jsonl'
        rows = INSPECTOR.records(path)
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows if not (r['boot'] == 3 and r['kind'] == 'MEMBER_CHECK')))
        with self.assertRaisesRegex(AssertionError, 'live member API observation'):
            INSPECTOR.inspect(self.run)

    def test_reordered_native_move_is_rejected(self):
        path = self.run / 'evidence/epic-fixture.jsonl'
        rows = INSPECTOR.records(path)
        next(r for r in rows if r['kind'] == 'MEMBER_NATIVE_MOVE')['time'] = next(r for r in rows if r['kind'] == 'READY')['time']
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        with self.assertRaisesRegex(AssertionError, 'causal order'):
            INSPECTOR.inspect(self.run)

    def test_deleted_original_ballot_is_rejected(self):
        self.reject_sql('delete from story_choice_log where seq=(select min(seq) from story_choice_log)', 'Historical ballot/revision')

    def test_rewritten_ballot_nation_is_rejected(self):
        self.reject_sql("update story_choice_log set nation_id='another-nation' where seq=1", 'Historical ballot attribution')

    def test_changed_audit_status_is_rejected(self):
        self.reject_sql("update story_member_review_log set status='APPROVED' where status='PENDING'", 'Review audit sequence')

    def test_rewritten_original_nation_is_rejected(self):
        self.reject_sql("update story_member_review_log set original_nation_id='another-nation'", 'Review audit differs')

    def test_changed_current_review_is_rejected(self):
        self.reject_sql("update story_member_reviews set status='EXITED'", 'Current review differs')

    def test_missing_settlement_is_rejected(self):
        self.reject_sql("delete from events where type='MEMBER_REVIEW_SETTLED'", 'member settlement')

    def test_native_member_identity_mismatch_is_rejected(self):
        player = INSPECTOR.EPIC.read(self.run / 'preparation.json')['players']['D']
        self.reject_sql("update starcore_nation_state set property_value='missing-member' where property_key like 'nation.%.member.%.id' and property_value='" + player + "'", 'Persisted native membership', native=True)


if __name__ == '__main__':
    unittest.main()
