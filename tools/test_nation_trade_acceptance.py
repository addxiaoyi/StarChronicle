"""Corrupt real stopped trade evidence to verify independently detected acceptance failures."""
from contextlib import closing
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest
SPEC=importlib.util.spec_from_file_location('nation_trade',Path(__file__).with_name('inspect-nation-trade-paper.py'))
I=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(I)

class ActualNationTradeEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value=os.environ.get('STARCHRONICLE_NATION_TRADE_RUN')
        if not value:raise unittest.SkipTest('Requires actual stopped three-boot national trade run')
        cls.source=Path(value).resolve();cls.expected=I.inspect(cls.source)
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.run=Path(temp.name)/self.source.name;self.run.mkdir()
        shutil.copy2(self.source/'preparation.json',self.run/'preparation.json');os.link(self.source/'paper.jar',self.run/'paper.jar')
        for name in ('evidence','fixture'):shutil.copytree(self.source/name,self.run/name)
        for plugin,name in [('StarCore','starcore.db'),('StarChronicle','chronicle.db')]:
            target=self.run/'plugins'/plugin;target.mkdir(parents=True);shutil.copy2(self.source/'plugins'/plugin/name,target/name)
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
    def offer(self,action):
        def update(db):
            row=db.execute('select offer_id,offer_json from starcore_nation_trade_offers order by expires_at limit 1').fetchone();value=json.loads(row[1]);action(value);raw=I.compact(value)
            db.execute('update starcore_nation_trade_offers set offer_json=?,offer_hash=? where offer_id=?',(raw,I.hashed(raw),row[0]))
        self.sql(update)
    def test_actual_evidence_passes(self):self.assertEqual(I.inspect(self.run),self.expected)
    def test_missing_boot(self):
        (self.run/'evidence/boot3.session.json').unlink();self.reject('Missing complete sale boots')
    def test_abnormal_server_exit(self):
        self.edit('evidence/boot2.session.json',lambda x:x['processes'][0].update(exit_code=137));self.reject('Abnormal process exit')
    def test_overlapping_boots(self):
        first=I.read(self.run/'evidence/boot1.session.json')['processes'][0]
        self.edit('evidence/boot2.session.json',lambda x:x['processes'][0].update(started_at=first['started_at']));self.reject('Overlapping sale boots')
    def test_native_error(self):
        with (self.run/'evidence/boot2.server.log').open('a') as f:f.write('[00:00:00 ERROR]: [StarCore] SQL transaction failed\n')
        self.reject('Server persistence/lifecycle diagnostic')
    def test_missing_real_accept(self):
        self.client(lambda a:a.__setitem__(slice(None),[r for r in a if not(r['kind']=='COMMAND_RESULT' and r['data']['contains']=='国家贸易操作已提交')]));self.reject('Actual command sequence differs')
    def test_missing_foreign_actor_rejection(self):
        self.client(lambda a:a.__setitem__(slice(None),[r for r in a if not(r['kind']=='COMMAND_RESULT' and r['data']['who']=='C')]));self.reject('Actual command sequence differs')
    def test_changed_command_response(self):
        self.client(lambda a:[r['data'].update(response='fake') for r in a if r['kind']=='COMMAND_RESULT']);self.reject('Actual command response/lifetime differs')
    def test_missing_protocol_messages(self):
        self.client(lambda a:a.__setitem__(slice(None),[r for r in a if r['kind']!='MESSAGE']));self.reject('Command lacks real protocol message')
    def test_rehashed_price_change(self):self.offer(lambda o:o.update(unitPriceMinor=251));self.reject('Persisted consent terms differ')
    def test_rehashed_actor_change(self):self.offer(lambda o:o.update(sellerId='83451b1e-b8c7-31d9-a711-90b86f5c427f'));self.reject('Persisted consent terms differ')
    def test_offer_hash_change(self):
        self.sql(lambda db:db.execute("update starcore_nation_trade_offers set offer_hash='changed'"));self.reject('Offer hash/index differs')
    def test_cancelled_offer_opened(self):
        self.sql(lambda db:db.execute("update starcore_nation_trade_offers set status='OPEN' where status='CANCELLED'"));self.reject('Offer status/restart boundary differs')
    def test_lost_tail(self):
        self.sql(lambda db:db.execute('delete from starcore_nation_trade_settlements where sequence_id=3'));self.reject('Native receipt count differs')
    def test_lost_head(self):
        self.sql(lambda db:db.execute('delete from starcore_nation_trade_head'));self.reject('Independent trade head differs')
    def test_changed_domain(self):
        self.sql(lambda db:db.execute("update starcore_nation_trade_head set authority_domain='00000000-0000-4000-8000-000000000111'"));self.reject('Settlement identity/terms differ')
    def test_lost_migration_marker(self):
        self.sql(lambda db:db.execute("delete from starcore_resource_state_migrations where migration_key='nation-trade-journal-v1'"));self.reject('Missing migration marker')
    def test_lost_resource_revision(self):
        self.sql(lambda db:db.execute('delete from starcore_resource_revision'));self.reject('Missing resource state revision')
    def test_changed_receipt_hash(self):
        self.sql(lambda db:db.execute("update starcore_nation_trade_settlements set receipt_hash='changed' where sequence_id=2"));self.reject('Settlement payload hash differs')
    def test_changed_resource_receipt(self):
        self.sql(lambda db:db.execute("update starcore_resource_transfer_receipts set resources='agricultural=99' where sequence=1"));self.reject('Resource delivery receipt differs')
    def test_changed_treasury_receipt(self):
        self.sql(lambda db:db.execute("update starcore_treasury_state set property_value='999.00' where property_key='transfer.0.amount'"));self.reject('Treasury payment receipt differs')
    def test_changed_inventory(self):
        self.sql(lambda db:db.execute("update starcore_resource_state set property_value='999' where property_key='stockpile.0.amount'"));self.reject('National inventory conservation failed')
    def test_changed_balance(self):
        self.sql(lambda db:db.execute("update starcore_treasury_state set property_value='999.00' where property_key='treasury.0.balance'"));self.reject('National treasury conservation failed')
    def test_changed_native_founder(self):
        self.sql(lambda db:db.execute("update starcore_nation_state set property_value='someone-else' where property_key like '%.founderId'"));self.reject('Persisted founder differs from handover')
    def test_payment_time_after_acceptance(self):
        self.sql(lambda db:db.execute("update starcore_treasury_state set property_value='9999999999999' where property_key like 'transfer.%.appliedAt'"));self.reject('Settlement chronology differs|Final runtime differs')
    def test_missing_contribution(self):
        self.sql(lambda db:db.execute('delete from contributions where id=(select id from contributions limit 1)'),'chronicle');self.reject('Trade ingress/event/contribution count differs')
    def test_wrong_contribution_amount(self):
        self.sql(lambda db:db.execute("update contributions set amount='999'"),'chronicle');self.reject('Contribution columns/amount/time differ')
    def test_wrong_contribution_actor(self):
        self.sql(lambda db:db.execute("update contributions set actor_id='somebody'"),'chronicle');self.reject('Contribution columns/amount/time differ')
    def test_wrong_cursor(self):
        self.sql(lambda db:db.execute('update committed_history_sources set sequence=2 where source=?',(I.SOURCE,)),'chronicle');self.reject('Trade source cursor differs')
    def test_event_hash_change(self):
        self.sql(lambda db:db.execute("update events set hash='changed' where type='NATIONAL_TRADE_COMMITTED'"),'chronicle');self.reject('Trade canonical event hash differs')
    def test_checkpoint_bytes_changed(self):
        p=self.run/'evidence/checkpoints/boot1/starcore.db';p.write_bytes(p.read_bytes()+b'changed');self.reject('Stopped checkpoint changed')
    def test_rehashed_checkpoint_assets_changed(self):
        self.sql(lambda db:db.execute("update starcore_treasury_state set property_value='1' where property_key='treasury.0.balance'"),boot=1);self.reject('National treasury conservation failed')
    def test_rehashed_checkpoint_pending_became_settled(self):
        self.sql(lambda db:db.execute("update starcore_nation_trade_offers set status='SETTLED' where status='OPEN'"),boot=1);self.reject('Offer status/restart boundary differs')
    def test_rehashed_checkpoint_changed_contribution_time(self):
        self.sql(lambda db:db.execute("update contributions set recorded_at='2026-11-25T23:59:59Z'"),'chronicle',1);self.reject('Contribution prefix changed across restart')
    def test_unbound_checkpoint_file(self):
        (self.run/'evidence/checkpoints/boot1/extra').write_text('unknown');self.reject('Unexpected checkpoint files')
    def test_captured_controller_changed(self):
        (self.run/'evidence/boot1.controller.py').write_text('changed');self.reject('Captured runtime script changed')
    def test_fixture_source_changed(self):
        (self.run/'fixture/NationTradePaperFixture.java').write_text('changed');self.reject('Frozen fixture source changed')
    def test_jar_changed(self):
        p=self.run/'plugins/StarCore.jar';p.unlink();p.write_bytes(b'changed');self.reject('Frozen artifact changed')

if __name__=='__main__':unittest.main()
