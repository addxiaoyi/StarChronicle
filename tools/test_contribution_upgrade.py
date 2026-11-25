"""Reject corrupted copies of the actual old-save upgrade and its live observations."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('upgrade_inspector', Path(__file__).with_name('inspect-contribution-upgrade.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualContributionUpgradeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied = os.environ.get('STARCHRONICLE_CONTRIBUTION_UPGRADE_RUN')
        if not supplied:
            raise unittest.SkipTest('Requires a stopped, completed real contribution upgrade')
        cls.source = Path(supplied).resolve()
        cls.original = INSPECTOR.inspect(cls.source)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.run = Path(temporary.name) / self.source.name
        self.run.mkdir()
        for relative in ('preparation.json', 'fixture/EpicCampaignPaperFixture.java',
                         'plugins/StarCore/starcore.db', 'plugins/StarChronicle/chronicle.db'):
            target = self.run / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(self.source / relative, target)
        for relative in ('evidence', 'plugins/StarCore/quest', 'plugins/StarCore/dungeon'):
            shutil.copytree(self.source / relative, self.run / relative)
        for name in ('StarCore', 'StarChronicle', 'EpicCampaignPaperFixture'):
            os.link(self.source / f'plugins/{name}.jar', self.run / f'plugins/{name}.jar')

    def test_actual_upgrade_backfill_and_reboot_pass(self):
        self.assertEqual(INSPECTOR.inspect(self.run), self.original)

    def test_changed_baseline_is_rejected(self):
        path = self.run / 'evidence/upgrade-baseline/plugins/StarChronicle/chronicle.db'
        with sqlite3.connect(path) as db:
            db.execute("update epic_operations set detail='changed'")
        with self.assertRaisesRegex(AssertionError, 'Baseline file changed'):
            INSPECTOR.inspect(self.run)

    def test_second_upgrade_boot_required(self):
        (self.run / 'evidence/boot5.session.json').unlink()
        with self.assertRaisesRegex(AssertionError, '2 completed controller'):
            INSPECTOR.inspect(self.run)

    def test_changed_native_balance_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarCore/starcore.db') as db:
            db.execute("insert into starcore_treasury_state(property_key,property_value) values('unexpected-upgrade-balance','999')")
        with self.assertRaisesRegex(AssertionError, 'changed native receipts/balances'):
            INSPECTOR.inspect(self.run)

    def test_changed_operation_revision_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarChronicle/chronicle.db') as db:
            db.execute('update epic_operations set revision=revision+1')
        with self.assertRaisesRegex(AssertionError, 'changed prior Chronicle state'):
            INSPECTOR.inspect(self.run)

    def test_unexpected_treasury_save_revision_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarCore/starcore.db') as db:
            db.execute("update starcore_treasury_state set property_value='999' where property_key='__starcore_revision__'")
        with self.assertRaisesRegex(AssertionError, 'treasury save revision'):
            INSPECTOR.inspect(self.run)

    def test_changed_prior_history_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarChronicle/chronicle.db') as db:
            db.execute("update events set json=json_set(json,'$.facts.upgrade','changed') where type='EPIC_SIGNUP_COMMITTED'")
        with self.assertRaisesRegex(AssertionError, 'changed prior history'):
            INSPECTOR.inspect(self.run)

    def test_missing_upgrade_client_query_is_rejected(self):
        path = self.run / 'evidence/boot5.clients.log'
        path.write_text('\n'.join(line for line in path.read_text().splitlines() if 'CONTRIBUTIONS_QUERY' not in line))
        with self.assertRaisesRegex(AssertionError, 'real client contribution query'):
            INSPECTOR.inspect(self.run)


if __name__ == '__main__':
    unittest.main()
