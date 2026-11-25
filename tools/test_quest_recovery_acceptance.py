"""Reject corrupt copies of the actual four-boot task recovery evidence."""
from contextlib import closing
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest
SPEC=importlib.util.spec_from_file_location('task_recovery',Path(__file__).with_name('inspect-quest-recovery-paper.py'))
INSPECTOR=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(INSPECTOR)

class ActualQuestRecoveryEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source=os.environ.get('STARCHRONICLE_QUEST_RECOVERY_RUN')
        if not source:raise unittest.SkipTest('Requires real stopped four-boot task recovery run')
        cls.source=Path(source).resolve();cls.original=INSPECTOR.inspect(cls.source)
    def setUp(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup);self.run=Path(temporary.name)/self.source.name;self.run.mkdir()
        for relative in ('preparation.json','paper.jar'):
            (shutil.copy2 if relative.endswith('.json') else os.link)(self.source/relative,self.run/relative)
        for relative in ('evidence','fixture','plugins/StarCore/quest','plugins/StarCore/dungeon'):
            shutil.copytree(self.source/relative,self.run/relative)
        for relative in ('plugins/StarCore/starcore.db','plugins/StarChronicle/chronicle.db'):
            target=self.run/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(self.source/relative,target)
        for path in (self.source/'plugins').glob('*.jar'):os.link(path,self.run/'plugins'/path.name)
    def edit(self,relative,action):
        path=self.run/relative;data=json.loads(path.read_text());action(data);path.write_text(json.dumps(data))
    def reject(self,pattern):
        with self.assertRaisesRegex((AssertionError,FileNotFoundError,ValueError),pattern):INSPECTOR.inspect(self.run)
    def fixture(self,action):
        path=self.run/'evidence/epic-fixture.jsonl';rows=INSPECTOR.STACK.read_lines(path);action(rows);path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    def client(self,action,boot=1):
        path=self.run/f'evidence/boot{boot}.clients.log';rows=INSPECTOR.STACK.read_lines(path);action(rows);path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    def checkpoint(self,action,boot=1):
        path=self.run/f'evidence/checkpoints/boot{boot}/chronicle.db'
        with closing(sqlite3.connect(path)) as db:action(db);db.commit()
        self.edit(f'evidence/checkpoints/boot{boot}/checkpoint.json',lambda x:x['sha256'].update({'chronicle.db':INSPECTOR.sha(path)}))
    def retirement(self,action):
        def change(db):
            row=db.execute('select gate_id,proof_json from quest_gate_round_history').fetchone();proof=json.loads(row[1]);action(proof);raw=json.dumps(proof)
            db.execute('update quest_gate_round_history set proof_json=?,proof_hash=? where gate_id=?',(raw,INSPECTOR.Q.stable('quest-round-proof',raw),row[0]))
        self.checkpoint(change)
    def test_actual_four_boots_pass(self):self.assertEqual(INSPECTOR.inspect(self.run),self.original)
    def test_missing_fourth_boot(self):
        (self.run/'evidence/boot4.session.json').unlink();self.reject('4 completed controller sessions')
    def test_abnormal_exit(self):
        self.edit('evidence/boot4.session.json',lambda x:x['processes'][0].update(exit_code=137));self.reject('Abnormal process exit')
    def test_native_storage_error(self):
        with (self.run/'evidence/boot2.server.log').open('a') as out:out.write('[00:00:00 ERROR]: [StarCore] terminal-state write failed\n')
        self.reject('Server persistence/lifecycle diagnostic')
    def test_missing_actual_craft(self):
        self.client(lambda rows:rows.__setitem__(slice(None),[r for r in rows if r['kind']!='CRAFTED']),2);self.reject('Exactly one real craft')
    def test_missing_real_gui_abandon(self):
        self.client(lambda rows:rows.__setitem__(slice(None),[r for r in rows if r['kind']!='QUEST_GUI_ABANDON']));self.reject('Real native GUI abandonment missing')
    def test_synthetic_gui_retry(self):
        self.client(lambda rows:[r['data'].update(syntheticEvents=True) for r in rows if r['kind']=='QUEST_GUI_RECOVERY']);self.reject('Task recovery GUI provenance')
    def test_reconcile_changed_run(self):
        self.client(lambda rows:[r['data']['after'].update(run='replacement') for r in rows if r['kind']=='QUEST_GUI_RECOVERY' and r['data']['action']=='reconcile']);self.reject('UNKNOWN reconcile changed')
    def test_retry_before_durable_abandonment(self):
        self.client(lambda rows:[r['data']['before'].update(status='状态待核对') for r in rows if r['kind']=='QUEST_GUI_RECOVERY' and r['data']['action']=='retry']);self.reject('Retry did not follow')
    def test_unknown_did_not_authorize_new_round(self):
        self.fixture(lambda rows:[r['data']['gate'].update(round=1) for r in rows if r['kind']=='QUEST_UNKNOWN_RETRY_HELD']);self.reject('UNKNOWN allocated')
    def test_unknown_requires_old_durable_active_shard(self):
        self.fixture(lambda rows:[r['data']['disk']['snapshot'].update(status='ABANDONED') for r in rows if r['kind']=='QUEST_RECOVERY_STATE' and r['data']['status']=='UNKNOWN']);self.reject('UNKNOWN must retain')
    def test_missing_owner_rejection(self):
        self.fixture(lambda rows:rows.__setitem__(slice(None),[r for r in rows if not(r['kind']=='QUEST_RETRY_REJECTED' and r['data']['kind']=='foreign')]));self.reject('Owner/stale/completed retry')
    def test_missing_round_archive(self):
        self.checkpoint(lambda db:db.execute('delete from quest_gate_round_history'));self.reject('Duplicate/missing task retirement')
    def test_missing_round_head(self):
        self.checkpoint(lambda db:db.execute('delete from quest_gate_round_heads'));self.reject('Duplicate/missing task retirement')
    def test_bad_archive_hash(self):
        self.checkpoint(lambda db:db.execute("update quest_gate_round_history set proof_hash='changed'"));self.reject('Retired task proof hash')
    def test_rehashed_archive_actor_change(self):
        self.retirement(lambda p:p.update(requestedBy='someone-else'));self.reject('Retirement lacks owner')
    def test_rehashed_archive_provider_change(self):
        self.retirement(lambda p:p['gate'].update(providerId='other-provider'));self.reject('Retired task identity/provider')
    def test_rehashed_archive_unknown_is_not_terminal(self):
        self.retirement(lambda p:p['terminalProof'].update(status='UNKNOWN'));self.reject('Retirement lacks owner and exact terminal proof')
    def test_rehashed_archive_next_binding_change(self):
        self.retirement(lambda p:p.update(nextBindingId='different'));self.reject('Retired/replacement binding')
    def test_rehashed_archive_retirement_time_change(self):
        self.retirement(lambda p:p.update(retiredAt='2000-01-01T00:00:00Z'));self.reject('Retired task chronology')
    def test_settlement_before_task_completion(self):
        self.checkpoint(lambda db:db.execute("update epic_campaigns set status='COMPLETE'"));self.reject('Settlement crossed the wrong restart boundary')
    def test_lost_active_native_run_at_first_restart(self):
        path=next((self.run/'evidence/checkpoints/boot1/StarCore/quest/tracked-evidence').glob('*.properties'));path.unlink();self.reject('No such file|Stopped campaign database checkpoint changed')
    def test_wrong_task_admission_time(self):
        self.checkpoint(lambda db:db.execute("update quest_gates set provider_pinned_at=9999999999999,updated_at=9999999999999 where player_id=(select player_id from quest_gates where id not in (select gate_id from quest_gate_round_heads) limit 1)"));self.reject('Task admission predates provider pin')
    def test_native_completion_count_is_checked(self):
        cp=self.run/'evidence/checkpoints/boot2';path=cp/'StarCore/quest/quests.properties';raw=path.read_text();self.assertIn('chronicle_heir_records\\:1',raw);path.write_text(raw.replace('chronicle_heir_records\\:1','chronicle_heir_records\\:2'))
        self.edit('evidence/checkpoints/boot2/checkpoint.json',lambda x:x['sha256'].update({'StarCore/quest/quests.properties':INSPECTOR.sha(path)}));self.reject('Native task completed more than once')
    def test_stopped_native_checkpoint_cannot_have_unbound_files(self):
        (self.run/'evidence/checkpoints/boot1/StarCore/quest/unbound.json').write_text('{}');self.reject('Missing/unbound stopped task checkpoint files')
    def test_captured_controller_cannot_change(self):
        (self.run/'evidence/boot1.controller.py').write_text('altered');self.reject('Captured runtime script changed')

if __name__=='__main__':unittest.main()
