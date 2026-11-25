"""Require stopped raw legacy evidence, exact new payments, truthful origins and client commands."""
from contextlib import closing
from pathlib import Path
import importlib.util,json,os,shutil,sqlite3,tempfile,unittest
SPEC=importlib.util.spec_from_file_location('legacy_inspector',Path(__file__).with_name('inspect-legacy-factory-paper.py'))
I=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(I)

class LegacyFactoryEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value=os.environ.get('STARCHRONICLE_LEGACY_FACTORY_RUN')
        if not value:raise unittest.SkipTest('Requires actual stopped legacy factory acceptance')
        cls.source=Path(value).resolve();cls.expected=I.inspect(cls.source)
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.run=Path(temp.name)/self.source.name;self.run.mkdir()
        shutil.copy2(self.source/'preparation.json',self.run/'preparation.json');os.link(self.source/'paper.jar',self.run/'paper.jar')
        for name in ('evidence','fixture'):shutil.copytree(self.source/name,self.run/name)
        for plugin,name in [('StarCore','starcore.db'),('StarChronicle','chronicle.db')]:
            dest=self.run/'plugins'/plugin;dest.mkdir(parents=True);shutil.copy2(self.source/'plugins'/plugin/name,dest/name)
        shutil.copy2(self.source/'plugins/StarCore/nation-production.json',self.run/'plugins/StarCore/nation-production.json')
        for p in (self.source/'plugins').glob('*.jar'):os.link(p,self.run/'plugins'/p.name)
    def reject(self,pattern):
        with self.assertRaisesRegex((AssertionError,FileNotFoundError,ValueError),pattern):I.inspect(self.run)
    def edit(self,path,fn):
        p=self.run/path;v=I.read(p);fn(v);p.write_text(json.dumps(v))
    def sql(self,query,store='starcore',boot=None):
        path=self.run/(f'evidence/checkpoints/boot{boot}' if boot else 'plugins/'+('StarCore' if store=='starcore' else 'StarChronicle'))/(store+'.db')
        with closing(sqlite3.connect(path)) as db:db.execute(query);db.commit()
        if boot:self.edit(f'evidence/checkpoints/boot{boot}/checkpoint.json',lambda v:v['sha256'].update({store+'.db':I.sha(path)}))
    def live(self,boot,kind,fn):
        p=self.run/'evidence/epic-fixture.jsonl';rows=I.records(p)
        for r in rows:
            if r['boot']==boot and r['kind']==kind:fn(r['data'])
        p.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        p=self.run/f'evidence/boot{boot}.server.log';lines=p.read_text().splitlines()
        for i,line in enumerate(lines):
            if 'EPIC_'+kind+' ' in line:
                prefix,raw=line.split('EPIC_'+kind+' ',1);d=json.loads(raw);fn(d);lines[i]=prefix+'EPIC_'+kind+' '+json.dumps(d)
        p.write_text('\n'.join(lines)+'\n')
    def test_actual_evidence(self):self.assertEqual(I.inspect(self.run),self.expected)
    def test_missing_boot(self):
        (self.run/'evidence/boot3.session.json').unlink();self.reject('Missing legacy boot')
    def test_abnormal_exit(self):
        self.edit('evidence/boot2.session.json',lambda v:v['processes'][0].update(exit_code=137));self.reject('Abnormal legacy process exit')
    def test_overlap(self):
        began=I.read(self.run/'evidence/boot1.session.json')['processes'][0]['started_at']
        self.edit('evidence/boot2.session.json',lambda v:v['processes'][0].update(started_at=began));self.reject('Overlapping legacy boots')
    def test_unrelated_error(self):
        with (self.run/'evidence/boot4.server.log').open('a') as out:out.write('[00:00:00 ERROR]: [StarCore] database failed\n')
        self.reject('Legacy persistence/lifecycle diagnostic')
    def test_catalog_changed(self):
        (self.run/'plugins/StarCore/nation-production.json').write_text('{}');self.reject('Legacy production catalog changed')
    def test_missing_factory_tail(self):
        self.sql('delete from starcore_nation_factory_events where event_sequence=4');self.reject('Legacy committed prefix incomplete')
    def test_missing_factory_head(self):
        self.sql('delete from starcore_nation_factory_head');self.reject('Missing legacy factory head')
    def test_missing_factory_marker(self):
        self.sql("delete from starcore_resource_state_migrations where migration_key='nation-factory-journal-v1'");self.reject('Missing legacy factory marker')
    def test_factory_price_changed(self):
        self.sql("update starcore_nation_factory_events set change_json=json_set(change_json,'$.cost.mineral',1) where event_sequence=1");self.reject('Legacy identity/payment/provenance differs')
    def test_factory_origin_changed(self):
        self.sql("update starcore_nation_factory_events set change_json=json_set(change_json,'$.building.origin','BUILT') where event_sequence=1");self.reject('Legacy identity/payment/provenance differs')
    def test_factory_registrant_changed(self):
        self.sql("update starcore_nation_factory_events set change_json=json_set(change_json,'$.building.builderId','00000000-0000-4000-8000-000000000001') where event_sequence=1");self.reject('Legacy identity/payment/provenance differs')
    def test_factory_level_changed(self):
        self.sql("update starcore_nation_factory_events set change_json=json_set(change_json,'$.building.level',10) where event_sequence=4");self.reject('Legacy identity/payment/provenance differs')
    def test_source_receipt_changed(self):
        self.sql("update starcore_nation_factory_events set change_json=json_set(change_json,'$.legacy.fields.efficiency','2.0') where event_sequence=1");self.reject('Legacy identity/payment/provenance differs')
    def test_factory_digest_changed(self):
        self.sql("update starcore_nation_factory_events set entry_hash='bad' where event_sequence=3");self.reject('Legacy factory event digest mismatch')
    def test_factory_head_digest_changed(self):
        self.sql("update starcore_nation_factory_head set prefix_hash='bad'");self.reject('Legacy factory head digest mismatch')
    def test_restart_factory_tail(self):
        self.sql('delete from starcore_nation_factory_events where event_sequence=2',boot=2);self.reject('Legacy committed prefix incomplete')
    def test_unpaid_registration(self):
        self.sql("update starcore_resource_state set property_value='1000' where property_key='stockpile.0.amount'");self.reject('conservation')
    def test_restart_materials(self):
        self.sql("update starcore_resource_state set property_value='1' where property_key='stockpile.0.amount'",boot=3);self.reject('conservation')
    def test_legacy_unknown_field_lost(self):
        self.sql("delete from starcore_resource_state where property_key='factory.5.unrecognized'");self.reject('Legacy original properties changed')
    def test_legacy_processing_cleared(self):
        self.sql("delete from starcore_resource_state where property_key='factory.1.recipe'");self.reject('Legacy original properties changed')
    def test_legacy_snapshot_hash_changed(self):
        self.edit('evidence/legacy-staging.json',lambda v:v.update(fingerprint='0'*64));self.reject('Legacy raw snapshot digest differs')
    def test_source_claims_old_payment(self):
        self.edit('evidence/legacy-staging.json',lambda v:v.update(payment_evidence='PAID'));self.reject('Legacy staging must be explicit and unproven')
    def test_source_before_snapshot_changed(self):
        self.edit('evidence/legacy-staging.json',lambda v:v.update(before_sha256='0'*64));self.reject('Legacy source snapshot changed')
    def test_audit_ignores_inflight_work(self):
        self.live(2,'LEGACY_AUDIT',lambda v:v['report']['entries'][1].update(issues=[]));self.reject('Legacy malformed/inflight/foreign records')
    def test_quote_grants_credit_without_payment(self):
        self.live(2,'LEGACY_AUDIT',lambda v:v['quotes'][0].update(cost={}));self.reject('Legacy cumulative quote differs')
    def test_live_stock_mints_unknown_resource(self):
        self.live(4,'LEGACY_CHECK',lambda v:v['stockpile'].update(invented=0));self.reject('Legacy live materials differ')
    def test_missing_production_tail(self):
        self.sql('delete from starcore_nation_production_events where event_sequence=6');self.reject('Production committed prefix incomplete')
    def test_production_origin_rewritten(self):
        self.sql("update starcore_nation_production_events set run_json=json_set(run_json,'$.factory.origin','BUILT') where event_sequence=1");self.reject('Frozen production terms differ')
    def test_production_binding_rewritten(self):
        self.sql("update starcore_nation_production_events set run_json=json_set(run_json,'$.factory.version',4) where event_sequence=1");self.reject('Frozen production terms differ')
    def test_original_actor_rewritten(self):
        self.sql("update starcore_nation_production_events set run_json=json_set(run_json,'$.actorId','00000000-0000-4000-8000-000000000001') where event_sequence=1");self.reject('Frozen production terms differ')
    def test_missing_contribution(self):
        self.sql("delete from contributions where id=(select id from contributions where type='CIVILIZATION' limit 1)",'chronicle');self.reject('ingress/history/contribution count')
    def test_missing_actual_command(self):
        p=self.run/'evidence/boot2.clients.log';rows=I.records(p);index=next(i for i,r in enumerate(rows) if r['kind']=='COMMAND_RESULT');rows.pop(index)
        p.write_text(''.join(json.dumps(r)+'\n' for r in rows));self.reject('Actual legacy commands differ')

if __name__=='__main__':unittest.main()
