"""Corrupt copies of completed real combination evidence and require rejection."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest
import yaml

SPEC = importlib.util.spec_from_file_location('combination_inspector', Path(__file__).with_name('inspect-production-combination.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualCombinationEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = os.environ.get('STARCHRONICLE_COMBINATION_RUN')
        if not source:
            raise unittest.SkipTest('Requires completed real combination evidence')
        cls.source = Path(source).resolve()
        cls.original = INSPECTOR.inspect(cls.source)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.run = Path(temporary.name) / self.source.name
        self.run.mkdir()
        for relative in ('preparation.json', 'combination.json', 'fixture/EpicCampaignPaperFixture.java',
                         'fixture/ProductionCombinationFixture.java', 'plugins/StarCore/starcore.db', 'plugins/StarChronicle/chronicle.db'):
            destination = self.run / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(self.source / relative, destination)
        for relative in ('evidence', 'plugins/StarCore/quest', 'plugins/StarCore/dungeon', 'plugins/FancyNpcs', 'plugins/WorldGuard'):
            shutil.copytree(self.source / relative, self.run / relative)
        for name in ('StarCore', 'StarChronicle', 'EpicCampaignPaperFixture', 'ProductionCombinationFixture', 'FancyNpcs', 'WorldGuard', 'WorldEdit'):
            os.link(self.source / f'plugins/{name}.jar', self.run / f'plugins/{name}.jar')

    def remove_kind(self, kind):
        path = self.run / 'evidence/combination-fixture.jsonl'
        rows = INSPECTOR.rows(path)
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows if r['kind'] != kind))

    def test_actual_three_boot_evidence_passes(self):
        self.assertEqual(INSPECTOR.inspect(self.run), self.original)

    def test_missing_real_cancelled_packet_is_rejected(self):
        self.remove_kind('NPC_PACKET')
        with self.assertRaisesRegex(AssertionError, 'real NPC packet'):
            INSPECTOR.inspect(self.run)

    def test_missing_provider_cleanup_is_rejected(self):
        self.remove_kind('NPC_DISABLED')
        with self.assertRaisesRegex(AssertionError, 'leaked bridge'):
            INSPECTOR.inspect(self.run)

    def test_missing_native_readiness_is_rejected(self):
        self.remove_kind('NPC_LOADED')
        with self.assertRaisesRegex(AssertionError, 'boot lifecycle: NPC_LOADED'):
            INSPECTOR.inspect(self.run)

    def test_missing_offline_maintenance_is_rejected(self):
        self.remove_kind('MAINTENANCE_EMPTY')
        with self.assertRaisesRegex(AssertionError, 'offline maintenance boundary'):
            INSPECTOR.inspect(self.run)

    def test_missing_disabled_protection_rejection_is_rejected(self):
        path = self.run / 'evidence/boot3.clients.log'
        path.write_text('\n'.join(line for line in path.read_text().splitlines() if '领地保护服务暂不可用' not in line))
        with self.assertRaisesRegex(AssertionError, 'fail closed'):
            INSPECTOR.inspect(self.run)

    def test_denied_placement_in_database_is_rejected(self):
        with sqlite3.connect(self.run / 'plugins/StarChronicle/chronicle.db') as db:
            db.execute("INSERT INTO memory_sites SELECT 'combo-denied',event_id,json FROM memory_sites LIMIT 1")
        with self.assertRaisesRegex(AssertionError, 'Denied placement persisted'):
            INSPECTOR.inspect(self.run)

    def test_recreated_owned_entities_on_restart_is_rejected(self):
        path = self.run / 'evidence/combination-fixture.jsonl'
        rows = INSPECTOR.rows(path)
        next(r for r in rows if r['boot'] == 2 and r['kind'] == 'MEMORY')['data']['owned'][0] = 'changed-entity'
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        with self.assertRaisesRegex(AssertionError, 'entity changed across restart'):
            INSPECTOR.inspect(self.run)

    def test_native_region_owner_drift_is_rejected(self):
        path = self.run / 'plugins/WorldGuard/worlds/world/regions.yml'
        content = yaml.safe_load(path.read_text())
        content['regions']['chronicle-owned']['owners'] = {'unique-ids': []}
        path.write_text(yaml.safe_dump(content))
        with self.assertRaisesRegex(AssertionError, 'region authority mismatch'):
            INSPECTOR.inspect(self.run)

    def test_changed_plugin_pin_is_rejected(self):
        path = self.run / 'combination.json'
        content = json.loads(path.read_text())
        content['artifacts']['WorldGuard']['sha256'] = '0' * 64
        path.write_text(json.dumps(content))
        with self.assertRaisesRegex(AssertionError, 'artifact mismatch'):
            INSPECTOR.inspect(self.run)


if __name__ == '__main__':
    unittest.main()
