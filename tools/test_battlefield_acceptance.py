"""Reject tampered copies of actual native stronghold victory evidence."""
from contextlib import closing
import importlib.util
import json
from pathlib import Path
import sqlite3
import os
import shutil
import unittest
import test_current_combination as stack_cases
SPEC=importlib.util.spec_from_file_location('battle_inspector',Path(__file__).with_name('inspect-battlefield-paper.py'))
BATTLE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(BATTLE)

class ActualBattlefieldEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        location=os.environ.get('STARCHRONICLE_COMBINATION_RUN')
        if not location: raise unittest.SkipTest('Requires completed current battlefield combination evidence')
        cls.source=Path(location).resolve(); cls.original=BATTLE.inspect(cls.source)

    def setUp(self):
        stack_cases.CurrentCombinationSourceEvidenceTest.setUp(self)
        shutil.copy2(self.source/'fixture/BattlefieldPaperFixture.java',self.run/'fixture/BattlefieldPaperFixture.java')

    def reject(self, message):
        with self.assertRaisesRegex(AssertionError,message): BATTLE.inspect(self.run)

    def edit(self, name, action):
        path=self.run/name; value=json.loads(path.read_text()); action(value)
        path.write_text(json.dumps(value))

    def test_full_selected_stack_passes(self):
        result=BATTLE.inspect(self.run)
        self.assertEqual(result['status'],'NATIVE_STRONGHOLD_VICTORY_ACCEPTED')
        self.assertEqual(result['native_end_reason'],'VICTORY_DETERMINED')

    def fixture(self,action):
        path=self.run/'evidence/epic-fixture.jsonl';rows=BATTLE.read_lines(path);action(rows)
        path.write_text(''.join(json.dumps(r)+'\n' for r in rows))

    def change_kind(self,kind,action):
        self.fixture(lambda rows:[action(r['data']) for r in rows if r['kind']==kind])

    def mutate_native_checkpoint(self,table,column,action,boot=2):
        path=self.run/f'evidence/checkpoints/boot{boot}/starcore.db'
        with closing(sqlite3.connect(path)) as db:
            row=db.execute(f'select war_id,{column} from {table}').fetchone();value=json.loads(row[1]);action(value)
            db.execute(f'update {table} set {column}=? where war_id=?',(json.dumps(value),row[0]))
            db.commit()
        self.edit(f'evidence/checkpoints/boot{boot}/checkpoint.json',lambda m:m['sha256'].update({'starcore.db':BATTLE.sha(path)}))

    def test_stalemate_is_not_victory(self):
        self.change_kind('BATTLE_END_EVENT',lambda d:d.update(reason='STALEMATE'));self.reject('actual score-victory event')

    def test_synthetic_end_event_is_rejected(self):
        self.change_kind('BATTLE_END_EVENT',lambda d:d.update(syntheticEvent=True));self.reject('actual score-victory event')

    def test_missing_server_capture_is_rejected(self):
        self.fixture(lambda rows:rows.__setitem__(slice(None),[r for r in rows if r['kind']!='BATTLE_GUI_CLICK']))
        self.reject('Exactly two native capture clicks required')

    def test_capture_actor_cannot_be_replaced(self):
        self.change_kind('BATTLE_GUI_CLICK',lambda d:d.update(actor='unrelated'));self.reject('capture actor, timing or event provenance')

    def test_client_click_evidence_is_required(self):
        path=self.run/'evidence/boot1.clients.log';path.write_text('\n'.join(s for s in path.read_text().splitlines() if 'BATTLE_GUI_ATTEMPT' not in s));self.reject('actual battlefield attempt sequence|Actual battlefield attempt sequence')

    def test_native_end_command_is_required(self):
        path=self.run/'evidence/boot1.clients.log';path.write_text('\n'.join(s for s in path.read_text().splitlines() if 'BATTLE_END_COMMAND' not in s));self.reject('principal ending command missing')

    def test_war_admin_bypass_is_rejected(self):
        self.change_kind('BATTLE_BOUND',lambda d:d.update(warAdmin=True));self.reject('administrator bypass')

    def test_rehashed_committed_score_change_is_rejected(self):
        self.mutate_native_checkpoint('starcore_wars','war_json',lambda d:d.update(aggressorWarScore=101));self.reject('Committed war score')

    def test_rehashed_score_marker_loss_is_rejected(self):
        self.mutate_native_checkpoint('starcore_wars','war_json',lambda d:d.update(appliedScoreEvents=[]));self.reject('score events duplicated or missing')

    def test_rehashed_stronghold_owner_change_is_rejected(self):
        self.mutate_native_checkpoint('starcore_battlefield_state','state_json',lambda d:d['strongholds'][0].update(owner='unrelated'));self.reject('Committed capture owner')

    def test_pending_capture_score_cannot_look_settled(self):
        self.mutate_native_checkpoint('starcore_battlefield_state','state_json',lambda d:d['strongholds'][0].update(pendingScoreEventId='pending'));self.reject('Committed capture owner')

    def test_pending_capture_points_cannot_look_settled(self):
        self.mutate_native_checkpoint('starcore_battlefield_state','state_json',lambda d:d['strongholds'][0].update(pendingScorePoints=50));self.reject('Committed capture owner')

    def test_changed_battlefield_fixture_is_rejected(self):
        path=self.run/'fixture/BattlefieldPaperFixture.java'
        path.write_text(path.read_text()+'\n// changed\n');self.reject('Battlefield source drift')

    def test_checkpoint_hash_cannot_hide_unrecorded_writes(self):
        self.edit('evidence/checkpoints/boot2/checkpoint.json',lambda m:m['sha256'].update({'starcore.db':'0'*64}))
        self.reject('Stopped database checkpoint changed')

    def test_rehashed_chronicle_receipt_change_on_restart_is_rejected(self):
        path=self.run/'evidence/checkpoints/boot3/chronicle.db'
        with closing(sqlite3.connect(path)) as db:
            db.execute('DELETE FROM epic_operation_providers');db.commit()
        self.edit('evidence/checkpoints/boot3/checkpoint.json',lambda m:m['sha256'].update({'chronicle.db':BATTLE.sha(path)}))
        self.reject('Settled campaign, receipts or contributions changed')

if __name__=='__main__':unittest.main()
