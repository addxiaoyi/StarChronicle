"""Mutate copies of actual stopped runtime evidence to verify rejection, without changing originals."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC=importlib.util.spec_from_file_location('capital_inspector',Path(__file__).with_name('inspect-capital-paper.py'))
INSPECTOR=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(INSPECTOR)


class ActualCapitalEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value=os.environ.get('STARCHRONICLE_CAPITAL_RUN')
        if not value: raise unittest.SkipTest('Requires completed real capital acceptance')
        cls.source=Path(value).resolve();cls.original=INSPECTOR.inspect(cls.source)

    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        self.run=Path(temp.name)/self.source.name;self.run.mkdir()
        for name in ('evidence','fixture'):shutil.copytree(self.source/name,self.run/name)
        shutil.copy2(self.source/'preparation.json',self.run/'preparation.json');os.link(self.source/'paper.jar',self.run/'paper.jar')
        shutil.copytree(self.source/'plugins/StarChronicle',self.run/'plugins/StarChronicle')
        for path in (self.source/'plugins').glob('*.jar'):os.link(path,self.run/'plugins'/path.name)
        for path in (self.source/'plugins/StarCore').rglob('capital-designations.log*'):
            if path.is_file():
                target=self.run/'plugins/StarCore'/path.relative_to(self.source/'plugins/StarCore');target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
        self.journal=next((self.run/'plugins/StarCore').rglob('capital-designations.log'))

    def reject(self,message):
        with self.assertRaisesRegex((AssertionError,FileNotFoundError),message):INSPECTOR.inspect(self.run)

    def edit(self,path,action):
        path=self.run/path;value=json.loads(path.read_text());action(value);path.write_text(json.dumps(value))

    def sql(self,statement,*values):
        with sqlite3.connect(self.run/'plugins/StarChronicle/chronicle.db') as db:db.execute(statement,values)

    def event(self,edit):
        with sqlite3.connect(self.run/'plugins/StarChronicle/chronicle.db') as db:
            row=db.execute('select event_id,json from committed_history_ingress where source=? and sequence=1',(INSPECTOR.SOURCE,)).fetchone()
            value=json.loads(row[1]);edit(value);raw=json.dumps(value)
            db.execute('update committed_history_ingress set json=? where event_id=?',(raw,row[0]));db.execute('update events set json=? where id=?',(raw,row[0]))

    def live(self,kind,action):
        path=self.run/'evidence/epic-fixture.jsonl';rows=INSPECTOR.records(path)
        for row in rows:
            if row['kind']==kind:action(row['data'])
        path.write_text(''.join(json.dumps(row)+'\n' for row in rows))
        for path in (self.run/'evidence').glob('boot*.server.log'):
            output=[];marker='EPIC_'+kind+' '
            for line in path.read_text().splitlines():
                if marker in line:
                    prefix,payload=line.split(marker,1);value=json.loads(payload);action(value);line=prefix+marker+json.dumps(value)
                output.append(line)
            path.write_text('\n'.join(output)+'\n')

    def test_real_run_passes(self):self.assertEqual(self.original,INSPECTOR.inspect(self.run))
    def test_missing_boot_fails(self):
        (self.run/'evidence/boot2.session.json').unlink();self.reject('Missing complete capital boot')
    def test_abnormal_exit_fails(self):
        self.edit('evidence/boot2.session.json',lambda s:s['processes'][1].update(exit_code=134));self.reject('Abnormal capital process exit')
    def test_complete_native_tail_loss_fails(self):
        raw=self.journal.read_bytes();self.journal.write_bytes(raw[:raw[:-1].rfind(b'\n')+1]);self.reject('Missing or modified committed native bytes')
    def test_native_middle_corruption_fails(self):
        raw=self.journal.read_bytes();self.journal.write_bytes(raw[:80]+b'!'+raw[81:]);self.reject('Missing or modified committed native bytes')
    def test_missing_head_fails(self):
        self.journal.with_name(self.journal.name+'.head').unlink();self.reject('capital-designations.log.head')
    def test_mismatched_authority_fails(self):
        self.journal.with_name(self.journal.name+'.authority').write_text('another domain');self.reject('Native authority domain mismatch')
    def test_native_persistence_error_is_not_a_telemetry_exception(self):
        with (self.run/'evidence/boot1.server.log').open('a') as output:output.write('[00:00:00 ERROR]: [StarCore] Failed to persist capital history\n')
        self.reject('Server persistence/lifecycle diagnostic')
    def test_cursor_rollback_fails(self):
        self.sql('update committed_history_sources set sequence=sequence-1 where source=?',INSPECTOR.SOURCE);self.reject('Chronicle cursor/domain mismatch')
    def test_missing_ingress_fails(self):
        self.sql('delete from committed_history_ingress where source=? and sequence=2',INSPECTOR.SOURCE);self.reject('Chronicle inbox/event count mismatch')
    def test_unacknowledged_ingress_fails(self):
        self.sql("update committed_history_ingress set status='PENDING' where source=? and sequence=1",INSPECTOR.SOURCE);self.reject('Inbox acknowledgement or identity differs')
    def test_current_ruler_cannot_replace_historical_actor(self):
        self.event(lambda e:e.update(participants=['83451b1e-b8c7-31d9-a711-90b86f5c427f']));self.reject('Original actor/nation changed')
    def test_replay_time_cannot_replace_commit_time(self):
        self.event(lambda e:e.update(occurredAt='2000-01-01T00:00:00Z'));self.reject('Commit time changed')
    def test_initial_designation_is_not_a_move(self):
        self.event(lambda e:e.update(type='CAPITAL_MOVED'));self.reject('Initial designation confused with migration')
    def test_previous_city_must_match_native(self):
        self.event(lambda e:e['facts'].update({'capital.previous_city':'00000000-0000-0000-0000-000000000000'}));self.reject('Committed capital facts/source differ')
    def test_historical_actor_projection_cannot_disappear(self):
        self.sql('delete from event_players where event_id in (select event_id from committed_history_ingress where source=?)',INSPECTOR.SOURCE);self.reject('Player projection changed')
    def test_checkpoint_damage_fails(self):
        path=self.run/'evidence/checkpoints/boot1/chronicle.db';path.write_bytes(path.read_bytes()+b'corrupt');self.reject('Stopped checkpoint bytes changed')
    def test_live_replay_must_keep_the_original(self):
        self.live('CAPITAL_REPEAT',lambda d:d.update(head=999));self.reject('Replay changed original operation')
    def test_live_rejections_are_required(self):
        self.live('CAPITAL_REJECTIONS',lambda d:d.update(rejected=0));self.reject('Missing permission/replay/no-op rejections')
    def test_real_burst_size_is_required(self):
        self.live('CAPITAL_BURST',lambda d:d.update(count=1));self.reject('Burst count differs')
    def test_captured_fixture_code_cannot_drift(self):
        (self.run/'fixture/CapitalHistoryPaperFixture.java').write_text('changed');self.reject('Frozen fixture changed')
    def test_offline_must_finish_before_players_log_in(self):
        if self.original['mode']!='offline':self.skipTest('Offline scenario only')
        self.live('CAPITAL_CHECK',lambda d:d.update(onlinePlayers=4));self.reject('Offline backlog was not consumed before login')
    def test_offline_poll_change_must_be_exact(self):
        if self.original['mode']!='offline':self.skipTest('Offline scenario only')
        self.edit('evidence/capital-poll-resume.json',lambda d:d.update(after='other config'));self.reject('Offline configuration provenance changed')


if __name__=='__main__':unittest.main()
