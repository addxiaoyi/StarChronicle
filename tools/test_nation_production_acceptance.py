"""Corrupt stopped production evidence to ensure real acceptance cannot silently lose proof."""
from contextlib import closing
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest
SPEC=importlib.util.spec_from_file_location('nation_production',Path(__file__).with_name('inspect-nation-production-paper.py'))
I=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(I)

class ActualProductionEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value=os.environ.get('STARCHRONICLE_NATION_PRODUCTION_RUN')
        if not value:raise unittest.SkipTest('Requires actual stopped national production run')
        cls.source=Path(value).resolve();cls.expected=I.inspect(cls.source)
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.run=Path(temp.name)/self.source.name;self.run.mkdir()
        shutil.copy2(self.source/'preparation.json',self.run/'preparation.json');os.link(self.source/'paper.jar',self.run/'paper.jar')
        for name in ('evidence','fixture'):shutil.copytree(self.source/name,self.run/name)
        for plugin,name in [('StarCore','starcore.db'),('StarChronicle','chronicle.db')]:
            target=self.run/'plugins'/plugin;target.mkdir(parents=True);shutil.copy2(self.source/'plugins'/plugin/name,target/name)
        shutil.copy2(self.source/'plugins/StarChronicle/config.yml',self.run/'plugins/StarChronicle/config.yml')
        for path in (self.source/'plugins').glob('*.jar'):os.link(path,self.run/'plugins'/path.name)
    def reject(self,pattern):
        with self.assertRaisesRegex((AssertionError,FileNotFoundError,ValueError),pattern):I.inspect(self.run)
    def edit(self,path,action):
        path=self.run/path;value=I.read(path);action(value);path.write_text(json.dumps(value))
    def sql(self,action,store='starcore',boot=None):
        path=self.run/(f'evidence/checkpoints/boot{boot}' if boot else 'plugins/'+('StarCore' if store=='starcore' else 'StarChronicle'))/(store+'.db')
        with closing(sqlite3.connect(path)) as db:action(db);db.commit()
        if boot:self.edit(f'evidence/checkpoints/boot{boot}/checkpoint.json',lambda x:x['sha256'].update({store+'.db':I.sha(path)}))
    def client(self,action,boot=1):
        p=self.run/f'evidence/boot{boot}.clients.log';value=I.records(p);action(value);p.write_text(''.join(json.dumps(r)+'\n' for r in value))
    def test_actual_evidence_passes(self):self.assertEqual(I.inspect(self.run),self.expected)
    def test_missing_boot(self):
        (self.run/'evidence/boot2.session.json').unlink();self.reject('Missing complete production boots')
    def test_abnormal_exit(self):
        self.edit('evidence/boot2.session.json',lambda x:x['processes'][0].update(exit_code=137));self.reject('Abnormal production process exit')
    def test_overlapping_boots(self):
        first=I.read(self.run/'evidence/boot1.session.json')['processes'][0]
        self.edit('evidence/boot2.session.json',lambda x:x['processes'][0].update(started_at=first['started_at']));self.reject('Overlapping production boots')
    def test_native_error(self):
        with (self.run/'evidence/boot2.server.log').open('a') as f:f.write('[00:00:00 ERROR]: [StarCore] SQL transaction failed\n')
        self.reject('Server persistence/lifecycle diagnostic')
    def test_missing_actual_collection(self):
        self.client(lambda rows:rows.__setitem__(slice(None),[r for r in rows if not(r['kind']=='COMMAND_RESULT' and r['data']['contains']=='生产批次已处理')]));self.reject('Actual production command sequence differs')
    def test_missing_foreign_rejection(self):
        self.client(lambda rows:rows.__setitem__(slice(None),[r for r in rows if not(r['kind']=='COMMAND_RESULT' and r['data']['who']=='C')]));self.reject('Actual production command sequence differs')
    def test_missing_wait_gate(self):
        self.client(lambda rows:rows.__setitem__(slice(None),[r for r in rows if not(r['kind']=='COMMAND_RESULT' and r['data']['contains']=='尚未到领取时间')]));self.reject('Actual production command sequence differs')
    def test_changed_response(self):
        self.client(lambda rows:[r['data'].update(response='fake') for r in rows if r['kind']=='COMMAND_RESULT']);self.reject('Actual production response/lifetime differs')
    def test_missing_protocol_message(self):
        self.client(lambda rows:rows.__setitem__(slice(None),[r for r in rows if r['kind']!='MESSAGE']));self.reject('Production command lacks protocol message')
    def test_missing_tail(self):
        self.sql(lambda db:db.execute('delete from starcore_nation_production_events where event_sequence=(select max(event_sequence) from starcore_nation_production_events)'));self.reject('Production committed prefix incomplete')
    def test_missing_start(self):
        self.sql(lambda db:db.execute('delete from starcore_nation_production_events where event_sequence=1'));self.reject('Production event gap')
    def test_missing_head(self):
        self.sql(lambda db:db.execute('delete from starcore_nation_production_head'));self.reject('Missing production head')
    def test_missing_marker(self):
        self.sql(lambda db:db.execute("delete from starcore_resource_state_migrations where migration_key='nation-production-journal-v1'"));self.reject('Missing production marker')
    def test_missing_revision(self):
        self.sql(lambda db:db.execute('delete from starcore_resource_revision'));self.reject('Missing inventory revision')
    def test_changed_digest(self):
        self.sql(lambda db:db.execute("update starcore_nation_production_events set entry_hash='bad' where event_sequence=1"));self.reject('Production event digest mismatch')
    def test_changed_actor(self):
        self.sql(lambda db:db.execute("update starcore_nation_production_events set run_json=json_set(run_json,'$.actorId','83451b1e-b8c7-31d9-a711-90b86f5c427f')"));self.reject('Frozen production terms differ')
    def test_changed_outputs(self):
        self.sql(lambda db:db.execute("update starcore_nation_production_events set run_json=json_set(run_json,'$.outputs.industrial',999)"));self.reject('Frozen production terms differ')
    def test_changed_inventory(self):
        self.sql(lambda db:db.execute("update starcore_resource_state set property_value='999' where property_key='stockpile.0.amount'"));self.reject('Production input/output conservation failed')
    def test_changed_founder(self):
        self.sql(lambda db:db.execute("update starcore_nation_state set property_value='somebody' where property_key like '%.founderId'"));self.reject('Persisted production founder differs')
    def test_changed_treasury(self):
        self.sql(lambda db:db.execute("update starcore_treasury_state set property_value='1' where property_key='treasury.0.balance'"));self.reject('Production unexpectedly changed treasury')
    def test_changed_contribution_actor(self):
        self.sql(lambda db:db.execute("update contributions set actor_id='somebody' where type='CIVILIZATION'"),'chronicle');self.reject('Production contribution columns differ')
    def test_changed_contribution_amount(self):
        self.sql(lambda db:db.execute("update contributions set amount='999' where type='CIVILIZATION'"),'chronicle');self.reject('Production contribution columns differ')
    def test_missing_contribution(self):
        self.sql(lambda db:db.execute("delete from contributions where id=(select id from contributions where type='CIVILIZATION' limit 1)"),'chronicle');self.reject('Production ingress/history/contribution count differs')
    def test_wrong_cursor(self):
        self.sql(lambda db:db.execute('update committed_history_sources set sequence=99 where source=?',(I.SOURCE,)),'chronicle');self.reject('Production source cursor differs')
    def test_changed_event_hash(self):
        self.sql(lambda db:db.execute("update events set hash='bad' where type='NATIONAL_PRODUCTION_COMMITTED'"),'chronicle');self.reject('Canonical production event hash differs')
    def test_unacknowledged_ingress(self):
        self.sql(lambda db:db.execute("update committed_history_ingress set status='PENDING' where source=?",(I.SOURCE,)),'chronicle');self.reject('Production ingress changed or unacknowledged')
    def test_changed_checkpoint_bytes(self):
        p=self.run/'evidence/checkpoints/boot1/starcore.db';p.write_bytes(p.read_bytes()+b'changed');self.reject('Stopped production checkpoint changed')
    def test_rehashed_checkpoint_assets(self):
        self.sql(lambda db:db.execute("update starcore_resource_state set property_value='999' where property_key='stockpile.0.amount'"),boot=1);self.reject('Production input/output conservation failed')
    def test_changed_controller(self):
        (self.run/'evidence/boot1.controller.py').write_text('changed');self.reject('Captured runtime script changed')
    def test_changed_fixture(self):
        (self.run/'fixture/NationProductionPaperFixture.java').write_text('changed');self.reject('Frozen fixture source changed')
    def live(self,action):
        p=self.run/'evidence/epic-fixture.jsonl';rows=I.records(p);action(rows);p.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        for boot in (1,2):
            log=self.run/f'evidence/boot{boot}.server.log';lines=log.read_text().splitlines()
            for kind in ('PRODUCTION_OFFLINE_CONSUMED','PRODUCTION_OFFLINE_BUFFERED'):
                replacement=iter([r['data'] for r in rows if r['boot']==boot and r['kind']==kind])
                lines=[line.split('EPIC_'+kind+' ',1)[0]+'EPIC_'+kind+' '+json.dumps(next(replacement)) if 'EPIC_'+kind+' ' in line else line for line in lines]
            log.write_text('\n'.join(lines)+'\n')
    def test_offline_online_players_rejected(self):
        if self.expected['mode']!='offline':self.skipTest('Offline-only mutation; main production scenario has no offline handoff')
        self.live(lambda rows:[r['data'].update(onlinePlayers=1) for r in rows if r['kind']=='PRODUCTION_OFFLINE_CONSUMED']);self.reject('Backlog consumed after client launch')
    def test_offline_consumption_after_client_launch_rejected(self):
        if self.expected['mode']!='offline':self.skipTest('Offline-only mutation; main production scenario has no offline handoff')
        session=I.read(self.run/'evidence/boot2.session.json');client=next(p for p in session['processes'] if p['name']=='clients')
        self.live(lambda rows:[r.update(time=I.stamp(round((client['started_at']+1)*1000))) for r in rows if r['kind']=='PRODUCTION_OFFLINE_CONSUMED']);self.reject('Backlog consumed after client launch')
    def test_offline_premature_consumption_rejected(self):
        if self.expected['mode']!='offline':self.skipTest('Offline-only mutation; main production scenario has no offline handoff')
        cp=self.run/'evidence/checkpoints/boot1';path=cp/'chronicle.db';shutil.copy2(self.run/'plugins/StarChronicle/chronicle.db',path)
        self.edit('evidence/checkpoints/boot1/checkpoint.json',lambda x:x['sha256'].update({'chronicle.db':I.sha(path)}));self.reject('Production ingress/history/contribution count differs')
    def test_offline_actual_config_changed(self):
        if self.expected['mode']!='offline':self.skipTest('Offline-only mutation; main production scenario has no offline handoff')
        (self.run/'plugins/StarChronicle/config.yml').write_text('changed');self.reject('Offline production config differs')
    def test_unbound_checkpoint_file(self):
        (self.run/'evidence/checkpoints/boot1/extra').write_text('unknown');self.reject('Unexpected production checkpoint file')
    def test_changed_jar(self):
        p=self.run/'plugins/StarCore.jar';p.unlink();p.write_bytes(b'changed');self.reject('Frozen production artifact changed')

if __name__=='__main__':unittest.main()
