"""Mutate real stopped factory evidence to require independent asset and reload proof."""
from contextlib import closing
from pathlib import Path
import importlib.util,json,os,shutil,sqlite3,tempfile,unittest
SPEC=importlib.util.spec_from_file_location('factory',Path(__file__).with_name('inspect-nation-factory-paper.py'))
I=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(I)

class FactoryEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value=os.environ.get('STARCHRONICLE_FACTORY_RUN')
        if not value:raise unittest.SkipTest('Requires actual stopped factory acceptance')
        cls.source=Path(value).resolve();cls.expected=I.inspect(cls.source)
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.run=Path(temp.name)/self.source.name;self.run.mkdir()
        shutil.copy2(self.source/'preparation.json',self.run/'preparation.json');os.link(self.source/'paper.jar',self.run/'paper.jar')
        for name in ('evidence','fixture'):shutil.copytree(self.source/name,self.run/name)
        for plugin,name in [('StarCore','starcore.db'),('StarChronicle','chronicle.db')]:
            dest=self.run/'plugins'/plugin;dest.mkdir(parents=True);shutil.copy2(self.source/'plugins'/plugin/name,dest/name)
        (self.run/'plugins/Essentials').mkdir();shutil.copy2(self.source/'plugins/Essentials/config.yml',self.run/'plugins/Essentials/config.yml')
        shutil.copy2(self.source/'plugins/StarCore/nation-production.json',self.run/'plugins/StarCore/nation-production.json')
        for p in (self.source/'plugins').glob('*.jar'):os.link(p,self.run/'plugins'/p.name)
    def reject(self,pattern):
        with self.assertRaisesRegex((AssertionError,FileNotFoundError,ValueError),pattern):I.inspect(self.run)
    def edit(self,path,fn):
        p=self.run/path;v=I.read(p);fn(v);p.write_text(json.dumps(v))
    def records(self,path,fn):
        p=self.run/path;v=I.records(p);fn(v);p.write_text(''.join(json.dumps(r)+'\n' for r in v))
    def sql(self,query,store='starcore',boot=None):
        path=self.run/(f'evidence/checkpoints/boot{boot}' if boot else 'plugins/'+('StarCore' if store=='starcore' else 'StarChronicle'))/(store+'.db')
        with closing(sqlite3.connect(path)) as db:db.execute(query);db.commit()
        if boot:self.edit(f'evidence/checkpoints/boot{boot}/checkpoint.json',lambda v:v['sha256'].update({store+'.db':I.sha(path)}))
    def test_actual_evidence(self):self.assertEqual(I.inspect(self.run),self.expected)
    def test_missing_boot(self):
        (self.run/'evidence/boot2.session.json').unlink();self.reject('Missing factory boot')
    def test_abnormal_exit(self):
        self.edit('evidence/boot2.session.json',lambda v:v['processes'][0].update(exit_code=137));self.reject('Abnormal factory process exit')
    def test_overlap(self):
        began=I.read(self.run/'evidence/boot1.session.json')['processes'][0]['started_at']
        self.edit('evidence/boot2.session.json',lambda v:v['processes'][0].update(started_at=began));self.reject('Overlapping factory boots')
    def test_unrelated_error(self):
        with (self.run/'evidence/boot3.server.log').open('a') as out:out.write('[00:00:00 ERROR]: [StarCore] database failed\n')
        self.reject('Factory persistence/lifecycle diagnostic')
    def test_catalog_changed(self):
        (self.run/'plugins/StarCore/nation-production.json').write_text('{}');self.reject('Factory production catalog changed')
    def test_missing_factory_tail(self):
        self.sql('delete from starcore_nation_factory_events where event_sequence=6');self.reject('Factory committed prefix incomplete')
    def test_missing_factory_head(self):
        self.sql('delete from starcore_nation_factory_head');self.reject('Missing factory head')
    def test_missing_factory_marker(self):
        self.sql("delete from starcore_resource_state_migrations where migration_key='nation-factory-journal-v1'");self.reject('Missing factory marker')
    def test_factory_price_changed(self):
        self.sql("update starcore_nation_factory_events set change_json=json_set(change_json,'$.cost.mineral',1) where event_sequence=1");self.reject('Frozen factory identity/cost/transition differs')
    def test_factory_builder_changed(self):
        self.sql("update starcore_nation_factory_events set change_json=json_set(change_json,'$.building.builderId','00000000-0000-4000-8000-000000000001') where event_sequence=1");self.reject('Frozen factory identity/cost/transition differs')
    def test_factory_level_changed(self):
        self.sql("update starcore_nation_factory_events set change_json=json_set(change_json,'$.building.level',10) where event_sequence=6");self.reject('Frozen factory identity/cost/transition differs')
    def test_factory_digest_changed(self):
        self.sql("update starcore_nation_factory_events set entry_hash='bad' where event_sequence=3");self.reject('Factory event digest mismatch')
    def test_factory_head_digest_changed(self):
        self.sql("update starcore_nation_factory_head set prefix_hash='bad'");self.reject('Factory head digest mismatch')
    def test_restart_factory_tail(self):
        self.sql('delete from starcore_nation_factory_events where event_sequence=3',boot=1);self.reject('Factory committed prefix incomplete')
    def test_unpaid_building(self):
        self.sql("update starcore_resource_state set property_value='1000' where property_key='stockpile.0.amount'");self.reject('conservation')
    def test_restart_materials(self):
        self.sql("update starcore_resource_state set property_value='1' where property_key='stockpile.0.amount'",boot=2);self.reject('conservation')
    def alter_live_stockpile(self,key,value):
        path=self.run/'evidence/epic-fixture.jsonl';rows=I.records(path)
        for row in rows:
            if row['boot']==1 and row['kind']=='FACTORY_CHECK':row['data']['stockpile'][key]=value
        path.write_text(''.join(json.dumps(row)+'\n' for row in rows))
        path=self.run/'evidence/boot1.server.log';lines=path.read_text().splitlines()
        for i,line in enumerate(lines):
            if 'EPIC_FACTORY_CHECK ' in line:
                prefix,raw=line.split('EPIC_FACTORY_CHECK ',1);data=json.loads(raw);data['stockpile'][key]=value
                lines[i]=prefix+'EPIC_FACTORY_CHECK '+json.dumps(data)
        path.write_text('\n'.join(lines)+'\n')
    def test_unfunded_live_zero_resource(self):
        self.alter_live_stockpile('chemical',1);self.reject('Live factory stockpile differs')
    def test_unknown_live_zero_resource(self):
        self.alter_live_stockpile('invented',0);self.reject('Live factory stockpile differs')
    def test_missing_production_tail(self):
        self.sql('delete from starcore_nation_production_events where event_sequence=8');self.reject('Production committed prefix incomplete')
    def test_production_binding_rewritten(self):
        self.sql("update starcore_nation_production_events set run_json=json_set(run_json,'$.factory.version',6) where event_sequence=1");self.reject('Frozen production terms differ')
    def test_original_actor_rewritten(self):
        self.sql("update starcore_nation_production_events set run_json=json_set(run_json,'$.actorId','00000000-0000-4000-8000-000000000001') where event_sequence=1");self.reject('Frozen production terms differ')
    def test_missing_contribution(self):
        self.sql("delete from contributions where id=(select id from contributions where type='CIVILIZATION' limit 1)",'chronicle');self.reject('ingress/history/contribution count')
    def test_fabricated_contribution(self):
        self.sql("update contributions set amount='999' where type='CIVILIZATION'",'chronicle');self.reject('Production contribution columns')
    def test_missing_factory_event_provenance(self):
        self.sql("update events set json=json_remove(json,'$.facts.\"production.factory.id\"') where type='NATIONAL_PRODUCTION_COMMITTED'",'chronicle');self.reject('Production event differs')
    def test_changed_cursor(self):
        self.sql("update committed_history_sources set sequence=9 where source='starcore.nation-production.completions.v1'",'chronicle');self.reject('source cursor')
    def test_missing_empty_binding(self):
        self.sql("delete from committed_history_sources where source='starcore.nation-production.completions.v1'",'chronicle',boot=1);self.reject('source cursor')
    def test_missing_real_command(self):
        self.records('evidence/boot1.clients.log',lambda rows:rows.__setitem__(slice(None),[r for r in rows if not(r['kind']=='COMMAND_RESULT' and r['data']['contains']=='工厂请求已提交')]));self.reject('Actual factory command sequence')
    def test_missing_protocol_messages(self):
        self.records('evidence/boot1.clients.log',lambda rows:rows.__setitem__(slice(None),[r for r in rows if r['kind']!='MESSAGE']));self.reject('Factory command lacks protocol response')
    def test_wrong_response(self):
        self.records('evidence/boot1.clients.log',lambda rows:[r['data'].update(response='fake') for r in rows if r['kind']=='COMMAND_RESULT']);self.reject('Factory command response/lifetime')
    def test_missing_request_id(self):
        self.records('evidence/boot1.clients.log',lambda rows:[r['data'].update(response='工厂请求已提交') for r in rows if r['kind']=='COMMAND_RESULT' and r['data']['contains']=='工厂请求已提交']);self.reject('Factory receipt lacks actual request identity/time')
    def test_changed_artifact(self):
        p=self.run/'plugins/StarCore.jar';p.unlink();p.write_bytes(b'bad');self.reject('Frozen factory artifact changed')

if __name__=='__main__':unittest.main()
