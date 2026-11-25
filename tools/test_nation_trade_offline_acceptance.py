"""Require unconsumed source at shutdown and actual empty-server consumption on reboot."""
from contextlib import closing
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest
SPEC=importlib.util.spec_from_file_location('sale_offline',Path(__file__).with_name('inspect-nation-trade-offline-paper.py'))
I=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(I)
class ActualOfflineTradeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value=os.environ.get('STARCHRONICLE_NATION_TRADE_OFFLINE_RUN')
        if not value:raise unittest.SkipTest('Requires stopped real offline trade run')
        cls.source=Path(value).resolve();cls.expected=I.inspect(cls.source)
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.run=Path(temp.name)/self.source.name;self.run.mkdir()
        shutil.copy2(self.source/'preparation.json',self.run/'preparation.json');os.link(self.source/'paper.jar',self.run/'paper.jar')
        for name in ('evidence','fixture'):shutil.copytree(self.source/name,self.run/name)
        for plugin,name in [('StarCore','starcore.db'),('StarChronicle','chronicle.db')]:
            p=self.run/'plugins'/plugin;p.mkdir(parents=True);shutil.copy2(self.source/'plugins'/plugin/name,p/name)
        shutil.copy2(self.source/'plugins/StarChronicle/config.yml',self.run/'plugins/StarChronicle/config.yml')
        for p in (self.source/'plugins').glob('*.jar'):os.link(p,self.run/'plugins'/p.name)
    def reject(self,pattern):
        with self.assertRaisesRegex((AssertionError,FileNotFoundError),pattern):I.inspect(self.run)
    def edit(self,relative,action):
        p=self.run/relative;x=I.read(p);action(x);p.write_text(json.dumps(x))
    def live(self,action):
        p=self.run/'evidence/epic-fixture.jsonl';rows=I.records(p);action(rows);p.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        for boot in (1,2):
            log=self.run/f'evidence/boot{boot}.server.log';lines=log.read_text().splitlines()
            for kind in ('SALE_OFFLINE_CONSUMED','SALE_OFFLINE_BUFFERED'):
                replacement=iter([r['data'] for r in rows if r['boot']==boot and r['kind']==kind]);lines=[s.split('EPIC_'+kind+' ',1)[0]+'EPIC_'+kind+' '+json.dumps(next(replacement)) if 'EPIC_'+kind+' ' in s else s for s in lines]
            log.write_text('\n'.join(lines)+'\n')
    def test_real_empty_server_consumes_once(self):self.assertEqual(I.inspect(self.run),self.expected)
    def test_online_players_cannot_claim_offline(self):
        self.live(lambda rows:[r['data'].update(onlinePlayers=1) for r in rows if r['kind']=='SALE_OFFLINE_CONSUMED']);self.reject('Backlog not verified before actual login')
    def test_consumption_after_login_rejected(self):
        session=I.read(self.run/'evidence/boot2.session.json');client=next(p for p in session['processes'] if p['name']=='clients')
        self.live(lambda rows:[r.update(time=I.I.stamp(round((client['started_at']+1)*1000))) for r in rows if r['kind']=='SALE_OFFLINE_CONSUMED']);self.reject('Backlog not verified before actual login')
    def test_consumer_before_shutdown_rejected(self):
        cp=self.run/'evidence/checkpoints/boot1';path=cp/'chronicle.db';shutil.copy2(self.run/'plugins/StarChronicle/chronicle.db',path)
        self.edit('evidence/checkpoints/boot1/checkpoint.json',lambda x:x['sha256'].update({'chronicle.db':I.sha(path)}));self.reject('Sale consumed before offline handoff')
    def test_native_pending_offer_lost_rejected(self):
        cp=self.run/'evidence/checkpoints/boot1';path=cp/'starcore.db'
        with closing(sqlite3.connect(path)) as db:db.execute("delete from starcore_nation_trade_offers where status='OPEN'");db.commit()
        self.edit('evidence/checkpoints/boot1/checkpoint.json',lambda x:x['sha256'].update({'starcore.db':I.sha(path)}));self.reject('Unexpected/missing persisted offers')
    def test_changed_poll_config_rejected(self):
        self.edit('evidence/sale-poll-resume.json',lambda x:x.update(after=x['after']+'other'));self.reject('Offline configuration provenance changed')
    def test_actual_config_must_match_resume(self):
        (self.run/'plugins/StarChronicle/config.yml').write_text('changed');self.reject('Offline poll interval change differs')
    def test_missing_original_acceptance_rejected(self):
        p=self.run/'evidence/boot1.clients.log';p.write_text('\n'.join(s for s in p.read_text().splitlines() if not('COMMAND_RESULT' in s and '/nationtrade accept ' in s)));self.reject('Offline actual command sequence differs')
    def test_abnormal_restart_rejected(self):
        self.edit('evidence/boot2.session.json',lambda x:x['processes'][0].update(exit_code=1));self.reject('Abnormal offline process exit')
    def test_replayed_amount_cannot_change(self):
        p=self.run/'plugins/StarChronicle/chronicle.db'
        with closing(sqlite3.connect(p)) as db:db.execute("update contributions set amount='999'");db.commit()
        self.reject('Contribution columns/amount/time differ')
    def test_captured_client_changed_rejected(self):
        (self.run/'evidence/boot2.clients.cjs').write_text('changed');self.reject('Captured offline script changed')
    def test_unbound_checkpoint_file_rejected(self):
        (self.run/'evidence/checkpoints/boot1/extra').write_text('changed');self.reject('Unexpected offline checkpoint file')
if __name__=='__main__':unittest.main()
