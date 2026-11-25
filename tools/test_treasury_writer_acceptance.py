"""Require the stopped treasury evidence to detect missing waits, duplicate money and replay drift."""
from contextlib import closing
from pathlib import Path
import importlib.util
import json
import os
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('treasury', Path(__file__).with_name('inspect-treasury-writer-paper.py'))
I = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(I)


class TreasuryEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = os.environ.get('STARCHRONICLE_TREASURY_WRITER_RUN')
        if not source: raise unittest.SkipTest('Requires stopped real treasury writer acceptance')
        cls.source = Path(source).resolve()
        cls.expected = I.inspect(cls.source)

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.run = Path(temp.name) / self.source.name
        self.run.mkdir()
        shutil.copy2(self.source / 'preparation.json', self.run / 'preparation.json')
        for directory in ('evidence', 'fixture'):
            shutil.copytree(self.source / directory, self.run / directory)
        for plugin, name in [('StarCore', 'starcore.db'), ('StarChronicle', 'chronicle.db')]:
            target = self.run / 'plugins' / plugin
            target.mkdir(parents=True)
            shutil.copy2(self.source / 'plugins' / plugin / name, target / name)
        for path in [self.source / 'paper.jar', *(self.source / 'plugins').glob('*.jar')]:
            os.link(path, self.run / path.relative_to(self.source))

    def reject(self, message):
        with self.assertRaisesRegex((AssertionError, FileNotFoundError), message): I.inspect(self.run)

    def edit(self, name, mutate):
        path = self.run / name
        data = I.read(path)
        mutate(data)
        path.write_text(json.dumps(data))

    def observation(self, kind, mutate):
        path = self.run / 'evidence/epic-fixture.jsonl'
        rows = I.records(path)
        for row in rows:
            if row['kind'] == kind: mutate(row['data'])
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        for boot in (1, 2, 3):
            path = self.run / f'evidence/boot{boot}.server.log'
            lines = path.read_text().splitlines()
            for i, line in enumerate(lines):
                marker = 'EPIC_' + kind + ' '
                if marker in line:
                    prefix, raw = line.split(marker, 1)
                    value = json.loads(raw); mutate(value)
                    lines[i] = prefix + marker + json.dumps(value)
            path.write_text('\n'.join(lines) + '\n')

    def checkpoint(self, query, boot=2):
        cp = self.run / f'evidence/checkpoints/boot{boot}'
        with closing(sqlite3.connect(cp / 'starcore.db')) as db:
            db.execute(query); db.commit()
        self.edit(f'evidence/checkpoints/boot{boot}/checkpoint.json',
                  lambda v: v['sha256'].update({'starcore.db': I.sha(cp / 'starcore.db')}))

    def test_actual_evidence(self): self.assertEqual(I.inspect(self.run), self.expected)

    def test_missing_restart(self):
        (self.run / 'evidence/boot2.session.json').unlink(); self.reject('boot2.session.json')

    def test_abnormal_exit(self):
        self.edit('evidence/boot3.session.json', lambda v: v['processes'][0].update(exit_code=137))
        self.reject('Abnormal treasury process exit')

    def test_overlap(self):
        self.edit('evidence/boot2.session.json', lambda v: v['processes'][0].update(started_at=1))
        self.reject('Overlapping treasury lifecycle')

    def test_unrelated_error(self):
        with (self.run / 'evidence/boot3.server.log').open('a') as out: out.write('[00:00:00 ERROR]: [StarCore] save failed\n')
        self.reject('Treasury persistence/lifecycle diagnostic')

    def test_immediate_return(self):
        self.observation('TREASURY_WAIT', lambda v: v.update(elapsedMillis=10))
        self.reject('Treasury did not wait for writer release')

    def test_did_not_hold_writer(self):
        self.observation('TREASURY_WAIT', lambda v: v.update(writerReleasedAfterMillis=0))
        self.reject('Treasury did not wait for writer release')

    def test_wrong_thread(self):
        self.observation('TREASURY_WAIT', lambda v: v.update(ownerThread=False))
        self.reject('Treasury before snapshot/owner differs')

    def test_duplicate_money(self):
        self.observation('TREASURY_WAIT', lambda v: v['after'].update({'treasury.0.balance': '2000000.00'}))
        self.reject('Treasury atomic balance/receipt delta differs')

    def test_extra_revision(self):
        self.observation('TREASURY_WAIT', lambda v: v['after'].update({'__starcore_revision__': '999'}))
        self.reject('Treasury atomic balance/receipt delta differs')

    def test_replay_committed_again(self):
        self.observation('TREASURY_REPLAY', lambda v: v.update(status='APPLIED'))
        self.reject('Replay changed treasury state')

    def test_changed_restored_balance(self):
        self.checkpoint("UPDATE starcore_treasury_state SET property_value='1.00' WHERE property_key='treasury.0.balance'")
        self.reject('Stopped treasury SQL snapshot differs')

    def test_missing_restored_receipt(self):
        self.checkpoint("DELETE FROM starcore_treasury_state WHERE property_key='transfer.0.transactionId'")
        self.reject('Stopped treasury SQL snapshot differs')

    def test_uncommitted_holder(self):
        self.checkpoint("DELETE FROM isolated_treasury_writer_fixture WHERE operation='deposit-restored'")
        self.reject('Missing committed unrelated writer')

    def test_final_database_drift(self):
        with closing(sqlite3.connect(self.run / 'plugins/StarCore/starcore.db')) as db:
            db.execute("DELETE FROM starcore_treasury_state WHERE property_key='transfer.0.transactionId'"); db.commit()
        self.reject('Final treasury database differs')

    def test_changed_jar(self):
        path = self.run / 'plugins/StarCore.jar'; path.unlink(); path.write_bytes(b'changed')
        self.reject('Frozen treasury artifact changed')

    def test_missing_client(self):
        path = self.run / 'evidence/boot2.clients.log'
        path.write_text(''.join(json.dumps(row) + '\n' for row in I.records(path) if row['kind'] != 'SPAWN'))
        self.reject('Missing actual client identity')


if __name__ == '__main__': unittest.main()
