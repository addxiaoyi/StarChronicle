"""Mutate real stopped catalog evidence to require independent asset and reload proof."""
from contextlib import closing
from pathlib import Path
import importlib.util,json,os,shutil,sqlite3,tempfile,unittest
SPEC=importlib.util.spec_from_file_location('catalog',Path(__file__).with_name('inspect-production-catalog-paper.py'))
I=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(I)

class CatalogEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value=os.environ.get('STARCHRONICLE_PRODUCTION_CATALOG_RUN')
        if not value:raise unittest.SkipTest('Requires actual stopped catalog acceptance')
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
        (self.run/'evidence/boot2.session.json').unlink();self.reject('Missing catalog boot')
    def test_abnormal_exit(self):
        self.edit('evidence/boot2.session.json',lambda v:v['processes'][0].update(exit_code=137));self.reject('Abnormal catalog process exit')
    def test_overlap(self):
        earlier=I.read(self.run/'evidence/boot1.session.json')['processes'][0]['started_at']
        self.edit('evidence/boot2.session.json',lambda v:v['processes'][0].update(started_at=earlier));self.reject('Overlapping catalog boots')
    def test_invalid_startup_not_observed(self):
        p=self.run/'evidence/boot3.server.log';p.write_text(p.read_text().replace('Nation production catalog invalid;','missing;'));self.reject('Missing invalid-startup')
    def test_unrelated_error_not_whitelisted(self):
        with (self.run/'evidence/boot3.server.log').open('a') as f:f.write('[00:00:00 ERROR]: [StarCore] database failed\n')
        self.reject('Catalog persistence/lifecycle diagnostic')
    def test_config_variant_changed(self):
        (self.run/'fixture/production-catalog-v2.json').write_text('{}');self.reject('Frozen catalog variant changed')
    def test_final_config_changed(self):
        (self.run/'plugins/StarCore/nation-production.json').write_text('{}');self.reject('Final catalog differs')
    def test_missing_reload(self):
        self.records('evidence/memory-config.jsonl',lambda v:v.pop());self.reject('Missing admin validation/reload')
    def test_validate_promoted_to_reload(self):
        self.records('evidence/memory-config.jsonl',lambda v:v[0].update(command='nationproduction reload'));self.reject('Missing admin validation/reload')
    def test_reload_outside_lifetime(self):
        self.records('evidence/memory-config.jsonl',lambda v:v[0].update(time=0));self.reject('outside live scenario')
    def test_missing_config_transition(self):
        self.records('evidence/production-catalog-config.jsonl',lambda v:v.pop());self.reject('Catalog reload/edit sequence')
    def test_config_previous_digest(self):
        self.records('evidence/production-catalog-config.jsonl',lambda v:v[1].update(before='bad'));self.reject('Catalog file transition digest')
    def test_capacity_evidence_changed(self):
        p=self.run/'evidence/epic-fixture.jsonl';data=I.records(p)
        for r in data:
            if r['kind']=='PRODUCTION_CATALOG':r['data']['capacity']['runningRuns']=99
        p.write_text(''.join(json.dumps(r)+'\n' for r in data))
        for boot in (1,2,3):
            log=self.run/f'evidence/boot{boot}.server.log';changes=iter(r['data'] for r in data if r['boot']==boot and r['kind']=='PRODUCTION_CATALOG')
            log.write_text('\n'.join(line.split('EPIC_PRODUCTION_CATALOG ',1)[0]+'EPIC_PRODUCTION_CATALOG '+json.dumps(next(changes)) if 'EPIC_PRODUCTION_CATALOG ' in line else line for line in log.read_text().splitlines())+'\n')
        self.reject('Live capacity or recipe snapshot differs')
    def test_missing_capacity_rejection(self):
        self.records('evidence/boot1.clients.log',lambda v:v.__setitem__(slice(None),[r for r in v if not(r['kind']=='COMMAND_RESULT' and r['data']['contains']=='生产已达上限')]));self.reject('Actual catalog command sequence')
    def test_missing_admin_rejection(self):
        self.records('evidence/boot1.clients.log',lambda v:v.__setitem__(slice(None),[r for r in v if not(r['kind']=='COMMAND_RESULT' and r['data']['command']=='/nationproduction reload')]));self.reject('Actual catalog command sequence')
    def test_missing_protocol(self):
        self.records('evidence/boot1.clients.log',lambda v:v.__setitem__(slice(None),[r for r in v if r['kind']!='MESSAGE']));self.reject('Catalog command lacks protocol')
    def test_wrong_response(self):
        self.records('evidence/boot1.clients.log',lambda v:[r['data'].update(response='fake') for r in v if r['kind']=='COMMAND_RESULT']);self.reject('Catalog command response/lifetime')
    def test_changed_old_terms(self):
        self.sql("update starcore_nation_production_events set run_json=json_set(run_json,'$.outputs.industrial',11) where event_sequence=1");self.reject('Frozen production terms')
    def test_changed_wait_time(self):
        self.sql("update starcore_nation_production_events set run_json=json_set(run_json,'$.readyAt','2027-01-01T00:00:00Z') where event_sequence=1");self.reject('Frozen production terms')
    def test_missing_refund(self):
        self.sql("update starcore_resource_state set property_value='1' where property_key='stockpile.0.amount'");self.reject('conservation')
    def test_changed_restart_assets(self):
        self.sql("update starcore_resource_state set property_value='1' where property_key='stockpile.0.amount'",boot=2);self.reject('conservation')
    def test_missing_receipt_tail(self):
        self.sql('delete from starcore_nation_production_events where event_sequence=8');self.reject('committed prefix incomplete')
    def test_missing_head(self):
        self.sql('delete from starcore_nation_production_head');self.reject('Missing production head')
    def test_fabricated_contribution(self):
        self.sql("update contributions set amount='999' where type='CIVILIZATION'",'chronicle');self.reject('Production contribution columns')
    def test_missing_contribution(self):
        self.sql("delete from contributions where id=(select id from contributions where type='CIVILIZATION' limit 1)",'chronicle');self.reject('ingress/history/contribution count')
    def test_changed_source_cursor(self):
        self.sql("update committed_history_sources set sequence=9 where source='starcore.nation-production.completions.v1'",'chronicle');self.reject('source cursor')
    def test_start_response_wrong_identity(self):
        self.records('evidence/boot1.clients.log',lambda rows:[r['data'].update(response='生产已开工') for r in rows if r['kind']=='COMMAND_RESULT' and r['data']['contains']=='生产已开工']);self.reject('exact command identity/time')
    def test_early_collection_response(self):
        session=I.read(self.run/'evidence/boot3.session.json');began=next(p['started_at'] for p in session['processes'] if p['name']=='clients')
        from datetime import datetime,timezone
        value=datetime.fromtimestamp(began+0.5,timezone.utc).isoformat()
        self.records('evidence/boot3.clients.log',lambda rows:[r.update(time=value) for r in rows if r['kind']=='COMMAND_RESULT' and r['data']['contains']=='生产批次已处理']);self.reject('terminal receipt outside actual command')
    def test_missing_empty_source_binding(self):
        self.sql("delete from committed_history_sources where source='starcore.nation-production.completions.v1'",'chronicle',boot=1);self.reject('source cursor')
    def test_changed_artifact(self):
        p=self.run/'plugins/StarCore.jar';p.unlink();p.write_bytes(b'bad');self.reject('Frozen catalog artifact')

if __name__=='__main__':unittest.main()
