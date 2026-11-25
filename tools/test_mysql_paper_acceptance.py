"""Reject corrupted copies of real MySQL/Paper evidence, including rehashed forgeries."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('mysql_auditor', Path(__file__).with_name('inspect-mysql-paper.py'))
I = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(I)


@unittest.skipUnless(os.environ.get('STARCHRONICLE_MYSQL_RUN'), 'Requires a complete real MySQL Paper run')
class ActualMySqlPaperEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = Path(os.environ['STARCHRONICLE_MYSQL_RUN']).resolve()
        cls.expected = I.inspect_storage(cls.source)

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.run = Path(temp.name) / self.source.name
        self.run.mkdir()
        shutil.copy2(self.source / 'preparation.json', self.run / 'preparation.json')
        shutil.copytree(self.source / 'evidence', self.run / 'evidence')
        native = self.run / 'plugins/StarCore'
        native.mkdir(parents=True)
        for name in ('config.yml', 'starcore.db'):
            shutil.copy2(self.source / 'plugins/StarCore' / name, native / name)

    def edit(self, path, action):
        path = self.run / path
        value = I.read(path)
        action(value)
        path.write_text(json.dumps(value, ensure_ascii=False))

    def reject(self, pattern):
        with self.assertRaisesRegex((AssertionError, FileNotFoundError, KeyError), pattern):
            I.inspect_storage(self.run)

    def raw(self, action, rehash=True):
        path = self.run / 'evidence/mysql/boot1.json'
        self.edit(path, action)
        if rehash:
            digest = I.M.sha(path)
            self.edit('evidence/checkpoints/boot1/checkpoint.json', lambda d: d['mysql'].update(raw_sha256=digest))
            self.edit('evidence/mysql/restart-after-boot1.json', lambda d: d.update(previous_snapshot_sha256=digest))

    def restart(self, action):
        self.edit('evidence/mysql/restart-after-boot1.json', action)

    def test_actual_storage_evidence_passes(self):
        self.assertEqual(I.inspect_storage(self.run), self.expected)

    def test_raw_digest_changed(self):
        self.raw(lambda d: d.update(storage='sqlite'), rehash=False)
        self.reject('Raw MySQL snapshot hash/path differs')

    def test_forged_storage(self):
        self.raw(lambda d: d.update(storage='sqlite'))
        self.reject('MySQL provenance differs')

    def test_changed_target(self):
        self.raw(lambda d: d['target'].update(database='other'))
        self.reject('MySQL snapshot target differs')

    def test_changed_server_uuid(self):
        self.raw(lambda d: d['server'].update(server_uuid='other'))
        self.reject('MySQL authority changed')

    def test_no_tls(self):
        self.raw(lambda d: d['server'].update(ssl_cipher=''))
        self.reject('MySQL engine/isolation/encryption differs')

    def test_capture_while_game_running(self):
        session = I.read(self.run / 'evidence/boot1.session.json')
        self.raw(lambda d: d.update(started_at=session['processes'][0]['started_at']))
        self.reject('MySQL snapshot was not taken after game shutdown')

    def test_missing_raw_table(self):
        self.raw(lambda d: d['tables'].pop())
        self.reject('MySQL table inventory invalid')

    def test_rehashed_table_omission(self):
        self.raw(lambda d: d['tables'].pop())
        self.edit('evidence/checkpoints/boot1/checkpoint.json', lambda d: d['mysql'].update(table_count=d['mysql']['table_count'] - 1))
        self.reject('MySQL projection table inventory differs')

    def test_nontransactional_table(self):
        self.raw(lambda d: d['tables'][0].update(engine='MyISAM'))
        self.reject('Nontransactional MySQL table')

    def test_missing_view(self):
        self.raw(lambda d: d['views'].pop())
        self.reject('MySQL view provenance invalid')

    def test_altered_view(self):
        self.raw(lambda d: d['views'][0].__setitem__(1, 'SELECT 1'))
        self.reject('Database restart changed committed data or views')

    def test_rehashed_raw_asset_change(self):
        def alter(d):
            table = next(t for t in d['tables'] if t['name'] == 'starcore_resource_state')
            row = next(r for r in table['rows'] if r[0].endswith('.amount'))
            row[1] = '999999'
            table['rows'].sort(key=I.M.compact)
        self.raw(alter)
        self.reject('MySQL raw rows differ from projection')

    def test_rehashed_projection_asset_change(self):
        path = self.run / 'evidence/checkpoints/boot1/starcore.db'
        with sqlite3.connect(path) as db:
            db.execute("UPDATE starcore_resource_state SET property_value='999999' WHERE property_key LIKE '%.amount'")
            db.commit()
        digest = I.M.sha(path)
        self.edit('evidence/checkpoints/boot1/checkpoint.json', lambda d: (d['mysql'].update(projection_sha256=digest), d['sha256'].update({'starcore.db': digest})))
        self.reject('MySQL raw rows differ from projection')

    def test_projection_schema_change(self):
        self.raw(lambda d: d['tables'][0]['columns'][0].__setitem__(0, 'changed'))
        self.reject('MySQL projection columns differ')

    def test_frozen_capture_script_changed(self):
        (self.run / 'evidence/boot1.mysql-snapshot.py').write_text('changed')
        self.reject('Frozen MySQL capture script changed')

    def test_missing_native_mysql_lifecycle(self):
        p = self.run / 'evidence/boot1.server.log'
        p.write_text(p.read_text().replace('STARCORE database ready: MySQL', 'STARCORE database ready: SQLite'))
        self.reject('Native runtime MySQL binding missing or fell back')

    def test_missing_restart(self):
        (self.run / 'evidence/mysql/restart-after-boot1.json').unlink()
        self.reject('MySQL snapshot/restart inventory differs')

    def test_killed_database(self):
        self.restart(lambda d: d['stopped'].update(ExitCode=137))
        self.reject('Abnormal or missing database restart')

    def test_restart_did_not_occur(self):
        self.restart(lambda d: d['after'].update(StartedAt=d['before']['StartedAt']))
        self.reject('Abnormal or missing database restart')

    def test_restart_wrong_instance(self):
        self.restart(lambda d: d.update(container_id='another'))
        self.reject('Database restart identity differs')

    def test_restart_lost_table(self):
        self.restart(lambda d: d['after_tables'].pop())
        self.reject('Database restart changed committed data or views')

    def test_restart_modified_view(self):
        self.restart(lambda d: d['after_views'][0].__setitem__(1, 'SELECT 1'))
        self.reject('Database restart changed committed data or views')

    def test_restart_precedes_checkpoint(self):
        self.restart(lambda d: d.update(started_at=0))
        self.reject('Database restart preceded stopped checkpoint')

    def test_game_started_before_restart_finished(self):
        next_session = I.read(self.run / 'evidence/boot2.session.json')
        self.restart(lambda d: d.update(finished_at=next_session['processes'][0]['started_at'] + 1))
        self.reject('Game started before database restart verification')

    def test_restart_script_changed(self):
        (self.run / 'evidence/mysql/restart-after-boot1.py').write_text('changed')
        self.reject('Database restart script changed')

    def test_failed_game_session(self):
        self.edit('evidence/boot1.session.json', lambda d: d.update(status='FAILED'))
        self.reject('Incomplete MySQL game session')

    def test_final_projection_changed(self):
        p = self.run / 'plugins/StarCore/starcore.db'
        p.write_bytes(p.read_bytes() + b'changed')
        self.reject('Final MySQL audit projection differs')

    def test_extra_snapshot(self):
        (self.run / 'evidence/mysql/boot9.json').write_text('{}')
        self.reject('MySQL snapshot/restart inventory differs')

    def test_native_backend_drift(self):
        p = self.run / 'plugins/StarCore/config.yml'
        p.write_text(p.read_text().replace('type: mysql', 'type: sqlite'))
        self.reject('')


class TypedProjectionTest(unittest.TestCase):
    def test_exact_decimal_binary_and_nullable_values(self):
        raw = dict(tables=[dict(name='sample', columns=[['money', 'decimal', 'decimal(65,30)', 'NO', None], ['payload', 'blob', 'blob', 'YES', None], ['optional', 'varchar', 'varchar(20)', 'YES', 'utf8mb4_unicode_ci']], rows=[[{'decimal': '12345678901234567890.123456789012345678901234567890'}, {'binary': 'AAEC/w=='}, None]])])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'projection.db'
            I.M.project(raw, path)
            I.compare_projection(raw, path)
            changed = copy.deepcopy(raw)
            changed['tables'][0]['rows'][0][0]['decimal'] = '12345678901234567890.123456789012345678901234567891'
            with self.assertRaisesRegex(AssertionError, 'MySQL raw rows differ'):
                I.compare_projection(changed, path)


if __name__ == '__main__':
    unittest.main()
