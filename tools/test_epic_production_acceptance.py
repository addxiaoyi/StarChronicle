"""Exercise selected-stack inspection using copies of completed real epic acceptance runs."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import unittest
import test_contribution_acceptance as contribution_cases

SPEC=importlib.util.spec_from_file_location('epic_production_inspector',Path(__file__).with_name('inspect-epic-production-paper.py'))
INSPECTOR=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(INSPECTOR)


class ActualProductionEpicEvidenceTest(contribution_cases.ActualContributionEvidenceTest):
    def setUp(self):
        super().setUp()
        os.link(self.source/'paper.jar',self.run/'paper.jar')
        for path in (self.source/'plugins').glob('*.jar'):
            target=self.run/'plugins'/path.name
            if not target.exists():os.link(path,target)
        for path in (self.source/'fixture').glob('*.java'):
            target=self.run/'fixture'/path.name
            if not target.exists():shutil.copy2(path,target)

    def edit(self,relative,action):
        path=self.run/relative;value=json.loads(path.read_text());action(value);path.write_text(json.dumps(value))

    def reject(self,message):
        with self.assertRaisesRegex((AssertionError,FileNotFoundError),message):INSPECTOR.inspect(self.run)

    def test_full_selected_stack_passes(self):
        result=INSPECTOR.inspect(self.run)
        self.assertEqual(result['status'],'SELECTED_PAPER_STACK_EPIC_ACCEPTED');self.assertEqual(result['database_checkpoint_boots'],[1,2,3])

    def test_dependency_fingerprint_change_is_rejected(self):
        self.edit('preparation.json',lambda m:m['production_stack']['artifacts'][0].update(sha256='0'*64));self.reject('Frozen production plugin changed')

    def test_production_fixture_change_is_rejected(self):
        (self.run/'fixture/ProductionStackPaperFixture.java').write_text('changed');self.reject('Production fixture source changed')

    def test_campaign_route_metadata_must_match(self):
        self.edit('preparation.json',lambda m:m['production_campaign'].update(route='OTHER'));self.reject('Production campaign route differs')

    def test_peace_is_not_a_battlefield_victory(self):
        self.edit('preparation.json',lambda m:m['production_campaign'].update(war_ending='battlefield victory'));self.reject('Unverified battle-victory claim')

    def test_stack_observation_is_required_each_boot(self):
        path=self.run/'evidence/epic-fixture.jsonl';rows=INSPECTOR.read_lines(path)
        path.write_text(''.join(json.dumps(r)+'\n' for r in rows if not(r['kind']=='STACK_LOADED' and r['boot']==2)))
        self.reject('Missing actual loaded stack observation')

    def test_external_economy_cannot_be_replaced_by_internal(self):
        path=self.run/'evidence/epic-fixture.jsonl';rows=INSPECTOR.read_lines(path)
        for row in rows:
            if row['kind']=='STACK_LOADED':row['data']['businessEconomy']='InternalEconomyService'
        path.write_text(''.join(json.dumps(r)+'\n' for r in rows));self.reject('Epic did not use the selected external economy')

    def test_checkpoint_bytes_are_required(self):
        path=self.run/'evidence/checkpoints/boot1/chronicle.db';path.write_bytes(path.read_bytes()+b'changed');self.reject('Stopped campaign database checkpoint changed')

    def test_settlement_before_restart_cannot_be_hidden_by_rehashing(self):
        path=self.run/'evidence/checkpoints/boot1/chronicle.db'
        with sqlite3.connect(path) as db:db.execute("update epic_campaigns set status='COMPLETE'")
        self.edit('evidence/checkpoints/boot1/checkpoint.json',lambda m:m['sha256'].update({'chronicle.db':INSPECTOR.sha(path)}))
        self.reject('Settlement crossed the wrong restart boundary')

    def test_replay_cannot_change_contributions_in_a_checkpoint(self):
        path=self.run/'evidence/checkpoints/boot2/chronicle.db'
        with sqlite3.connect(path) as db:db.execute("update contributions set amount='999'")
        self.edit('evidence/checkpoints/boot2/checkpoint.json',lambda m:m['sha256'].update({'chronicle.db':INSPECTOR.sha(path)}))
        self.reject('Replay changed completed campaign or contributions')

    def test_controller_source_cannot_drift(self):
        (self.run/'evidence/boot2.controller.py').write_text('changed');self.reject('Captured runtime script changed')

    def test_a_native_error_cannot_be_treated_as_vendor_telemetry(self):
        with (self.run/'evidence/boot2.server.log').open('a') as out:out.write('[00:00:00 ERROR]: [StarCore] Failed to persist war receipt\n')
        self.reject('Server persistence/lifecycle diagnostic')


if __name__=='__main__':unittest.main()
