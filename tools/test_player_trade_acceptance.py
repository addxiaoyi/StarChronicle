"""Mutate copies of actual stopped evidence; each corruption must be independently rejected."""
from contextlib import closing
from pathlib import Path
import importlib.util,json,os,shutil,sqlite3,tempfile,unittest

SPEC=importlib.util.spec_from_file_location('player_trade_inspector',Path(__file__).with_name('inspect-player-trade-paper.py'))
I=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(I)

class PlayerTradeEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value=os.environ.get('STARCHRONICLE_PLAYER_TRADE_RUN')
        if not value:raise unittest.SkipTest('Requires an actual stopped player trade run')
        cls.source=Path(value).resolve();cls.expected=I.inspect(cls.source)
    def setUp(self):
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup);self.run=Path(directory.name)/self.source.name;self.run.mkdir()
        shutil.copy2(self.source/'preparation.json',self.run/'preparation.json')
        for name in ('evidence','fixture'):shutil.copytree(self.source/name,self.run/name)
        for plugin,file in [('StarCore','starcore.db'),('StarChronicle','chronicle.db')]:
            target=self.run/'plugins'/plugin;target.mkdir(parents=True);shutil.copy2(self.source/'plugins'/plugin/file,target/file)
        for p in (self.source/'plugins').glob('*.jar'):os.link(p,self.run/'plugins'/p.name)
    def reject(self,pattern):
        with self.assertRaisesRegex((AssertionError,ValueError,FileNotFoundError),pattern):I.inspect(self.run)
    def edit(self,path,action):
        p=self.run/path;value=I.read(p);action(value);p.write_text(json.dumps(value))
    def sql(self,statement,store='starcore',boot=1):
        path=self.run/f'evidence/checkpoints/boot{boot}'/(store+'.db')
        with closing(sqlite3.connect(path)) as db:db.execute(statement);db.commit()
        self.edit(f'evidence/checkpoints/boot{boot}/checkpoint.json',lambda x:x['sha256'].update({store+'.db':I.sha(path)}))
    def test_actual_stopped_evidence_passes(self):self.assertEqual(self.expected,I.inspect(self.run))
    def test_unordered_event_participants_keep_same_meaning(self):
        path=self.run/'evidence/checkpoints/boot1/chronicle.db'
        with closing(sqlite3.connect(path)) as db:
            identity,encoded=db.execute("select event_id,json from committed_history_ingress where source='starcore.player-trade.settlements.v1'").fetchone()
            value=json.loads(encoded);value['participants'].reverse();value['nations'].reverse()
            db.execute('update committed_history_ingress set json=? where event_id=?',(json.dumps(value),identity));db.commit()
        self.edit('evidence/checkpoints/boot1/checkpoint.json',lambda x:x['sha256'].update({'chronicle.db':I.sha(path)}))
        self.assertEqual(self.expected,I.inspect(self.run))
    def test_missing_tail(self):
        self.sql('delete from starcore_player_trade_events where event_sequence=(select max(event_sequence) from starcore_player_trade_events)');self.reject('journal tail missing')
    def test_changed_terms(self):
        self.sql("update starcore_player_trade_events set snapshot_json=replace(snapshot_json,'2.5','2.6') where offer_version=1");self.reject('journal hash changed')
    def test_changed_head(self):
        self.sql("update starcore_player_trade_head set prefix_hash='changed'");self.reject('committed head differs')
    def test_missing_tax_receipt(self):
        self.sql("delete from starcore_treasury_state where property_key='credit.0.amount'");self.reject('tax receipt changed or missing')
    def test_changed_tax_receipt(self):
        self.sql("update starcore_treasury_state set property_value='123.45' where property_key='credit.0.amount'");self.reject('tax receipt changed or missing')
    def test_changed_resources(self):
        self.sql("update starcore_resource_state set property_value='999' where property_key='stockpile.0.amount'");self.reject('inventory conservation failed')
    def test_changed_treasury(self):
        self.sql("update starcore_treasury_state set property_value='999.00' where property_key='treasury.0.balance'");self.reject('treasury conservation failed')
    def test_history_loses_buyer_funded_semantics(self):
        self.sql('update trade_history set buyer_funded=0');self.reject('history projection differs')
    def test_missing_business_projection(self):
        self.sql('delete from business_transactions');self.reject('projection count differs')
    def test_cursor_rewound(self):
        self.sql("update committed_history_sources set sequence=0 where source='starcore.player-trade.settlements.v1'",'chronicle');self.reject('source cursor differs')
    def test_generic_history_stalled(self):
        self.sql("update committed_history_sources set sequence=0 where source='starcore.trade.history.v1'",'chronicle');self.reject('Generic history cursor differs')
    def test_generic_history_buyer_tax_marker_removed(self):
        self.sql("update committed_history_ingress set json=replace(json,'BUYER','SELLER') where source='starcore.trade.history.v1'",'chronicle');self.reject('Generic history ingress differs')
    def test_generic_history_cannot_add_contributions(self):
        self.sql("update contributions set source_event_id='starcore:trade-history:forged' where source_event_id like 'starcore:player-trade:%'",'chronicle');self.reject('Generic history generated extra contributions')
    def test_missing_contribution(self):
        self.sql("delete from contributions where source_event_id like 'starcore:player-trade:%'",'chronicle');self.reject('contribution count differs')
    def test_wrong_contribution_amount(self):
        self.sql("update contributions set amount='999' where source_event_id like 'starcore:player-trade:%'",'chronicle');self.reject('contribution amount differs')
    def test_wrong_contribution_actor(self):
        self.sql("update contributions set actor_id='wrong' where source_event_id like 'starcore:player-trade:%'",'chronicle');self.reject('contribution subject/source changed')
    def test_provider_durability_overstated(self):
        self.sql("update events set json=replace(json,'PROVIDER_ACKNOWLEDGED','EXTERNAL_PAYMENT_DURABLE') where type='PLAYER_TRADE_SETTLED'",'chronicle');self.reject('ingress changed')
    def test_wallet_file_changed_even_with_new_checkpoint_hash(self):
        manifest=I.read(self.run/'preparation.json');name='Essentials/'+manifest['players']['A']+'.yml';path=self.run/'evidence/checkpoints/boot1'/name
        value=I.yaml.safe_load(path.read_text());value['money']='999.00';path.write_text(I.yaml.safe_dump(value))
        self.edit('evidence/checkpoints/boot1/checkpoint.json',lambda x:x['sha256'].update({name:I.sha(path)}));self.reject('Persisted Essentials balance differs')
    def test_abnormal_server_exit(self):
        self.edit('evidence/boot2.session.json',lambda x:x['processes'][0].update(exit_code=137));self.reject('Abnormal player trade process exit')
    def test_replay_evidence_removed(self):
        path=self.run/'evidence/boot3.clients.log';records=I.T.records(path)
        path.write_text(''.join(json.dumps(r)+'\n' for r in records if not(r['kind']=='COMMAND_RESULT' and r['data']['command'].startswith('/trade accept '))))
        self.reject('Actual player command sequence differs')
    def test_controller_changed(self):
        (self.run/'evidence/boot1.controller.py').write_text('changed');self.reject('Captured runtime script changed')
    def test_frozen_jar_changed(self):
        path=self.run/'plugins/StarCore.jar';path.unlink();path.write_bytes(b'changed');self.reject('Frozen artifact changed')

if __name__=='__main__':unittest.main()
