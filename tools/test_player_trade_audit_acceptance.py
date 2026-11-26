"""Reject altered copies of actual interruption evidence, including recomputed file hashes."""
from contextlib import closing
from pathlib import Path
import importlib.util,json,os,shutil,sqlite3,tempfile,unittest

SPEC=importlib.util.spec_from_file_location('audit_inspector',Path(__file__).with_name('inspect-player-trade-audit-paper.py'))
I=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(I)

class AuditEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value=os.environ.get('STARCHRONICLE_PLAYER_TRADE_AUDIT_RUN')
        if not value:raise unittest.SkipTest('Requires stopped real-wallet audit evidence')
        cls.source=Path(value).resolve();cls.expected=I.inspect(cls.source)
    def setUp(self):
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup)
        self.run=Path(directory.name)/self.source.name;self.run.mkdir()
        shutil.copy2(self.source/'preparation.json',self.run/'preparation.json')
        for name in ('evidence','fixture'):shutil.copytree(self.source/name,self.run/name)
        for plugin,file in [('StarCore','starcore.db'),('StarChronicle','chronicle.db')]:
            target=self.run/'plugins'/plugin;target.mkdir(parents=True);shutil.copy2(self.source/'plugins'/plugin/file,target/file)
        for p in (self.source/'plugins').glob('*.jar'):os.link(p,self.run/'plugins'/p.name)
    def edit(self,path,action):
        p=self.run/path;value=I.read(p);action(value);p.write_text(json.dumps(value))
    def reject(self,pattern):
        with self.assertRaisesRegex((AssertionError,ValueError,FileNotFoundError),pattern):I.inspect(self.run)
    def sql(self,statement,boot=3):
        p=self.run/f'evidence/checkpoints/boot{boot}/starcore.db'
        with closing(sqlite3.connect(p)) as db:db.execute(statement);db.commit()
        self.edit(f'evidence/checkpoints/boot{boot}/checkpoint.json',lambda v:v['sha256'].update({'starcore.db':I.sha(p)}))
    def test_actual_evidence_passes(self):self.assertEqual(self.expected,I.inspect(self.run))
    def test_deleted_intent(self):
        self.sql('delete from starcore_player_trade_events where event_sequence=(select max(event_sequence) from starcore_player_trade_events)');self.reject('journal length differs')
    def test_frozen_price_changed(self):
        self.sql("update starcore_player_trade_events set snapshot_json=replace(snapshot_json,'2.5','2.6')");self.reject('journal hash differs')
    def test_head_changed(self):
        self.sql("update starcore_player_trade_head set prefix_hash='changed'");self.reject('committed head differs')
    def test_escrow_released_without_evidence(self):
        self.sql("update starcore_resource_state set property_value='999' where property_key='stockpile.0.amount'");self.reject('escrow conservation differs')
    def test_tax_added_without_settlement(self):
        self.sql("update starcore_treasury_state set property_value='999' where property_key='treasury.0.balance'");self.reject('treasury changed')
    def test_wallet_file_changed_even_with_new_hash(self):
        manifest=I.read(self.run/'preparation.json');name='Essentials/'+manifest['players']['A']+'.yml';p=self.run/'evidence/checkpoints/boot3'/name
        value=I.yaml.safe_load(p.read_text());value['money']='999';p.write_text(I.yaml.safe_dump(value))
        self.edit('evidence/checkpoints/boot3/checkpoint.json',lambda v:v['sha256'].update({name:I.sha(p)}));self.reject('Persisted audit wallet differs')
    def test_unexpected_crash_cannot_count_as_recovery(self):
        self.edit('evidence/boot3.session.json',lambda v:v['processes'][0].update(exit_code=-9));self.reject('process exit differs')
    def test_normal_exit_cannot_count_as_interruption(self):
        self.edit('evidence/boot2.session.json',lambda v:v['processes'][0].update(exit_code=0));self.reject('process exit differs')
    def test_wrong_interrupted_process(self):
        self.edit('evidence/boot2.session.json',lambda v:v['wallet_interruption'].update(server_pid=1));self.reject('interruption process differs')
    def test_missing_replay_command(self):
        p=self.run/'evidence/boot3.clients.log';lines=I.records(p)
        p.write_text(''.join(json.dumps(r)+'\n' for r in lines if not(r['kind']=='COMMAND_RESULT' and r['data']['command'].startswith('/trade resume'))));self.reject('actual commands differ')
    def test_live_unknown_changed_to_success(self):
        for path in ('evidence/epic-fixture.jsonl','evidence/boot3.server.log','evidence/boot4.server.log'):
            p=self.run/path;p.write_text(p.read_text().replace('INTENT_WITHOUT_RESULT','PROVIDER_ACKNOWLEDGED'))
        self.reject('evidence classification differs')
    def test_missing_raw_database(self):
        p=self.run/'evidence/checkpoints/boot2/interrupted-raw/starcore.db';p.unlink();self.reject('starcore.db')
    def test_script_drift(self):
        (self.run/'evidence/boot2.controller.py').write_text('changed');self.reject('Captured audit script changed')
    def test_product_drift(self):
        p=self.run/'plugins/StarCore.jar';p.unlink();p.write_bytes(b'changed');self.reject('Frozen audit artifact changed')

if __name__=='__main__':unittest.main()
