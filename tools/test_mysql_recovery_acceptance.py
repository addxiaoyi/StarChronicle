"""Reject corrupted/rehashed restore and crash evidence copied from a real Paper run."""
from pathlib import Path
from contextlib import closing
import importlib.util
import json
import os
import shutil
import sqlite3
import tempfile
import unittest

S=importlib.util.spec_from_file_location('recovery_inspector',Path(__file__).with_name('inspect-mysql-recovery.py'));I=importlib.util.module_from_spec(S);S.loader.exec_module(I)


@unittest.skipUnless(os.environ.get('STARCHRONICLE_RECOVERY_RUN'),'Requires actual completed restore/process-recovery evidence')
class ActualRecoveryEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source=Path(os.environ['STARCHRONICLE_RECOVERY_RUN']).resolve();cls.expected=I.inspect(cls.source)

    def setUp(self):
        tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup);self.run=Path(tmp.name)/self.source.name;self.run.mkdir()
        shutil.copytree(self.source/'evidence',self.run/'evidence')
        shutil.copy2(self.source/'preparation.json',self.run/'preparation.json')
        for name in ('StarCore/config.yml','StarCore/starcore.db','StarChronicle/chronicle.db','Essentials/config.yml'):
            dst=self.run/'plugins'/name;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(self.source/'plugins'/name,dst)
        for name in ('quest','dungeon'):
            shutil.copytree(self.source/'plugins/StarCore'/name,self.run/'plugins/StarCore'/name)
        self.first=self.expected['accepted_boots'][0];self.crash=self.first+1;self.last=self.first+2
        self.e=self.run/'evidence/recovery';self.d=self.e/f'interruption-boot{self.crash}'

    def edit(self,path,action):
        path=Path(path);data=I.read(path);action(data);path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')

    def reject(self,pattern):
        with self.assertRaisesRegex((AssertionError,KeyError,FileNotFoundError),pattern):I.inspect(self.run)

    def interrupt(self,action):
        path=self.d/'interruption.json';self.edit(path,action)
        proof=I.read(path);self.edit(self.run/f'evidence/boot{self.crash}.session.json',lambda d:d.update(interruption=proof))

    def checkpoint(self,db_name,sql):
        cp=self.run/f'evidence/checkpoints/boot{self.last}';path=cp/db_name
        with closing(sqlite3.connect(path)) as db:db.execute(sql);db.commit()
        self.edit(cp/'checkpoint.json',lambda d:d['sha256'].update({db_name:I.sha(path)}))

    def test_real_evidence_passes(self):self.assertEqual(I.inspect(self.run),self.expected)

    def test_source_archive_identity_changed(self):
        self.edit(self.e/'restore.json',lambda d:d.update(backup_sha256='0'*64));self.reject('completion/bundle')

    def test_same_database_instance(self):
        self.edit(self.e/'restore.json',lambda d:d.update(target_server_uuid=d['source_server_uuid']));self.reject('new MySQL')

    def test_restore_wrong_container(self):
        self.edit(self.e/'restore.json',lambda d:d['target'].update(container_id='wrong'));self.reject('restore target')

    def test_missing_paired_database(self):
        self.edit(self.e/'backup-manifest.json',lambda d:d['files'].pop('plugins/StarChronicle/chronicle.db'));self.reject('inventory')

    def test_missing_sql_dump(self):
        self.edit(self.e/'backup-manifest.json',lambda d:d['database_files'].pop('native.sql'));self.reject('dump/snapshot')

    def test_missing_restored_world_file(self):
        self.edit(self.e/'restored-files.json',lambda d:d.pop(next(n for n in d if n.startswith('world/'))));self.reject('inventory')

    def test_unexpected_file_change(self):
        self.edit(self.e/'restored-files.json',lambda d:d['plugins/StarChronicle/chronicle.db'].update(sha256='0'*64));self.reject('Unexpected restored file change')

    def test_forged_extra_permitted_change(self):
        self.edit(self.e/'restore.json',lambda d:d['permitted_changes'].update({'world/level.dat':{}}));self.reject('allowlist')

    def test_ddl_index_change(self):
        def alter(d):
            name=next(n for n,s in d['ddl'].items() if 'PRIMARY KEY' in s);d['ddl'][name]=d['ddl'][name].replace('PRIMARY KEY','KEY `forged`',1)
        self.edit(self.e/'restored-native.json',alter);self.reject('differs: ddl')

    def test_ddl_auto_increment_change(self):
        def alter(d):
            name=next(n for n,s in d['ddl'].items() if 'AUTO_INCREMENT' in s);d['ddl'][name]=d['ddl'][name].replace('AUTO_INCREMENT','',1)
        self.edit(self.e/'restored-native.json',alter);self.reject('differs: ddl')

    def test_restore_column_collation_change(self):
        def alter(d):
            t=next(t for t in d['tables'] if any(c[4] for c in t['columns']));next(c for c in t['columns'] if c[4])[4]='utf8mb4_bin'
        self.edit(self.e/'restored-native.json',alter);self.reject('differs: tables')

    def test_restore_view_changed(self):
        self.edit(self.e/'restored-native.json',lambda d:d['views'][0].__setitem__(1,'SELECT 1'));self.reject('differs: views')

    def test_rehashed_source_snapshot_tamper(self):
        self.edit(self.e/'source-native.json',lambda d:d['tables'].pop())
        self.edit(self.e/'backup-manifest.json',lambda d:d['database_files']['native.json'].update(sha256=I.sha(self.e/'source-native.json')));self.reject('differs: tables')

    def test_essentials_gameplay_change(self):
        p=self.e/'essentials-after.yml';p.write_text(p.read_text()+'\nspawnmob-limit: 0\n');self.reject('gameplay configuration')

    def test_rehashed_native_binding_changed(self):
        p=self.run/'plugins/StarCore/config.yml';d=I.yaml.safe_load(p.read_text());d['nation']['enabled']=False if isinstance(d.get('nation'),dict) else False
        p.write_text(I.yaml.safe_dump(d,allow_unicode=True,sort_keys=False))
        self.edit(self.e/'restored-files.json',lambda r:r['plugins/StarCore/config.yml'].update(sha256=I.sha(p)))
        self.edit(self.e/'restore.json',lambda r:r['permitted_changes']['plugins/StarCore/config.yml'].update(after_sha256=I.sha(p)))
        self.reject('non-endpoint')

    def test_missing_recovered_boot(self):
        (self.run/f'evidence/boot{self.last}.session.json').unlink();self.reject('session inventory')

    def test_missing_interruption(self):
        (self.d/'interruption.json').unlink();self.reject('interruption.json')

    def test_normal_exit_presented_as_crash(self):
        self.interrupt(lambda d:d['database_killed'].update(ExitCode=0));self.reject('SIGKILL/restart')

    def test_oom_presented_as_intentional(self):
        self.interrupt(lambda d:d['database_killed'].update(OOMKilled=True));self.reject('SIGKILL/restart')

    def test_no_actual_database_restart(self):
        self.interrupt(lambda d:d['database_after'].update(StartedAt=d['database_before']['StartedAt']));self.reject('SIGKILL/restart')

    def test_wrong_killed_jvm(self):
        self.interrupt(lambda d:d.update(server_pid=d['server_pid']+1));self.reject('Wrong interrupted')

    def test_wrong_interruption_order(self):
        self.interrupt(lambda d:d.update(database_killed_at=d['started_at']-1));self.reject('interruption boundary')

    def test_interruption_snapshot_rehashed_row_loss(self):
        p=self.d/'after-database-restart-native.json';self.edit(p,lambda d:next(t for t in d['tables'] if t['rows'])['rows'].pop())
        self.interrupt(lambda d:d['files'].update({p.name:I.sha(p)}));self.reject('differs: tables')

    def test_missing_database_recovery_log(self):
        (self.d/'database-recovery.log').unlink();self.reject('digest/inventory')

    def test_rehashed_database_log_without_crash_recovery(self):
        p=self.d/'database-recovery.log';p.write_text(p.read_text().replace('XA crash recovery finished.','normal startup'))
        self.interrupt(lambda d:d['files'].update({p.name:I.sha(p)}));self.reject('crash recovery log missing')

    def test_rehashed_crash_chronicle_loss(self):
        p=self.d/'after-game-kill-chronicle.db'
        with closing(sqlite3.connect(p)) as db:db.execute('DELETE FROM contributions');db.commit()
        self.interrupt(lambda d:d['files'].update({p.name:I.sha(p)}));self.reject('Chronicle data lost')

    def test_rehashed_final_contribution_actor_change(self):
        self.checkpoint('chronicle.db',"UPDATE contributions SET actor_id='forged'");self.reject('Chronicle rows/schema')

    def test_recovered_lifecycle_error(self):
        p=self.run/f'evidence/boot{self.last}.server.log';p.write_text(p.read_text()+'\n[ERROR]: SQL persistence failed\n');self.reject('persistence/lifecycle')

    def test_captured_tool_changed(self):
        (self.run/f'evidence/boot{self.first}.controller.py').write_text('changed');self.reject('Captured recovery tool')

    def test_client_identity_changed(self):
        p=self.run/f'evidence/boot{self.first}.clients.log';rows=I.T.records(p);next(r for r in rows if r['kind']=='SPAWN')['data']['uuid']='forged';p.write_text('\n'.join(json.dumps(r) for r in rows));self.reject('client identities')

    def test_missing_actual_replay_response(self):
        p=self.run/f'evidence/boot{self.first}.clients.log';rows=I.T.records(p)
        kind='CONTRIBUTIONS_QUERY' if self.expected['kind'] in ('DUNGEON','WAR') else 'COMMAND_RESULT'
        rows.remove(next(r for r in rows if r['kind']==kind));p.write_text('\n'.join(json.dumps(r) for r in rows));self.reject('command sequence|actual aid queries')

    def test_final_runtime_data_changed(self):
        p=self.run/'plugins/StarChronicle/chronicle.db'
        with closing(sqlite3.connect(p)) as db:db.execute('DELETE FROM contributions');db.commit()
        self.reject('Final runtime/checkpoint')

    def test_uncheckpointed_runtime_wal(self):
        p=self.run/'plugins/StarChronicle/chronicle.db'
        db=sqlite3.connect(p);self.addCleanup(db.close)
        db.execute('PRAGMA journal_mode=WAL');db.execute('DELETE FROM contributions');db.commit()
        self.reject('pending journal')


if __name__=='__main__':unittest.main()
