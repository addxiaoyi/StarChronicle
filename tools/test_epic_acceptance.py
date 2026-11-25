"""Reject corrupted copies of actual three-boot evidence. No synthetic passing records.

Set STARCHRONICLE_EPIC_RUN to a completed, stopped run for either route.
"""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('epic_inspector', Path(__file__).with_name('inspect-epic-paper.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualEpicEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied = os.environ.get('STARCHRONICLE_EPIC_RUN')
        if not supplied:
            raise unittest.SkipTest('Requires a completed real epic run')
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
        for relative in ('evidence', 'plugins/StarCore/quest', 'plugins/StarCore/dungeon'):
            shutil.copytree(self.source / relative, self.run / relative)
        for name in ('StarCore', 'StarChronicle', 'EpicCampaignPaperFixture'):
            os.link(self.source / f'plugins/{name}.jar', self.run / f'plugins/{name}.jar')

    def test_original_receipts_and_three_boots_pass(self):
        self.assertEqual(INSPECTOR.inspect(self.run), self.original)

    def test_missing_third_boot_is_not_completion(self):
        (self.run / 'evidence/boot3.session.json').unlink()
        with self.assertRaisesRegex(AssertionError, 'three completed'):
            INSPECTOR.inspect(self.run)

    def test_abnormal_process_exit_is_not_completion(self):
        path = self.run / 'evidence/boot3.session.json'
        data = INSPECTOR.read(path)
        data['processes'][0]['exit_code'] = 137
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(AssertionError, 'Abnormal process exit'):
            INSPECTOR.inspect(self.run)

    def test_real_retained_shutdown_failure_is_rejected(self):
        failed = Path(os.environ.get('STARCHRONICLE_EPIC_FAILURE_LOG',
                      self.source.parent / 'paper-epic-57345b09-war-linux-2/evidence/boot1.server.log'))
        if not failed.exists():
            self.skipTest('Requires retained policy conflict run')
        shutil.copy2(failed, self.run / 'evidence/boot1.server.log')
        with self.assertRaisesRegex(AssertionError, 'persistence/lifecycle diagnostic'):
            INSPECTOR.inspect(self.run)

    def test_missing_replay_is_rejected(self):
        path = self.run / 'evidence/epic-fixture.jsonl'
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows if r['kind'] != 'REPLAY_REJECTED'))
        with self.assertRaisesRegex(AssertionError, 'restart/replay'):
            INSPECTOR.inspect(self.run)

    def test_missing_actual_craft_is_rejected(self):
        path = self.run / 'evidence/boot1.clients.log'
        path.write_text('\n'.join(line for line in path.read_text().splitlines() if '"kind":"CRAFTED"' not in line))
        with self.assertRaisesRegex(AssertionError, 'real craft'):
            INSPECTOR.inspect(self.run)

    def test_wrong_native_invited_nation_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarCore/starcore.db') as database:
            database.execute("update starcore_diplomacy_invite_receipts set invited_id=inviter_id")
        with self.assertRaisesRegex(AssertionError, 'Invitation parties mismatch'):
            INSPECTOR.inspect(self.run)

    def test_mismatched_native_quest_shard_is_rejected(self):
        for path in (self.run / 'plugins/StarCore/quest/tracked-evidence').glob('*.properties'):
            if 'COMPLETED' in path.read_text():
                path.write_text(path.read_text().replace('COMPLETED', 'ACTIVE'))
        with self.assertRaisesRegex(AssertionError, 'quest completion is not committed'):
            INSPECTOR.inspect(self.run)

    def test_rebound_provider_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarChronicle/chronicle.db') as database:
            database.execute("update epic_operation_providers set authority_domain='00000000-0000-0000-0000-000000000000'")
        with self.assertRaisesRegex(AssertionError, 'Provider/domain binding'):
            INSPECTOR.inspect(self.run)

    def test_duplicate_aftermath_per_player_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarChronicle/chronicle.db') as database:
            database.execute("insert into instances select 'duplicate',story,scope,completed,updated,json from instances where story='last-heir-strategy-aftermath' limit 1")
        with self.assertRaisesRegex(AssertionError, 'duplicate aftermath'):
            INSPECTOR.inspect(self.run)

    def test_missing_eligible_player_aftermath_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarChronicle/chronicle.db') as database:
            player = INSPECTOR.read(self.run / 'preparation.json')['players']['A']
            database.execute("delete from instances where story='last-heir-strategy-aftermath' and scope=?", ('player:' + player,))
        with self.assertRaisesRegex(AssertionError, 'Missing or duplicate aftermath'):
            INSPECTOR.inspect(self.run)


if __name__ == '__main__':
    unittest.main()
