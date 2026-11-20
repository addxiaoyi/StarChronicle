"""Non-JVM regressions using unchanged real protocol observations, never synthetic PASS evidence.

Full snapshot/protocol tests run only when STARCHRONICLE_EXPLORATION_RUN points to a completed
two-boot run; live databases are never opened by these tests.
"""
import importlib.util
import io
import json
import os
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('exploration_acceptance',ROOT/'tools/exploration-acceptance.py')
INSPECTOR=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class RetainedProtocolRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evidence_run=ROOT/'run/exploration-9612b1a4-paper-retry2'
        if not (cls.evidence_run/'evidence/fixture.jsonl').is_file():raise unittest.SkipTest('Requires preserved real retry2 observations')
        cls.manifest=INSPECTOR.read(cls.evidence_run/'preparation.json')
        cls.fixture=[json.loads(line) for line in (cls.evidence_run/'evidence/fixture.jsonl').read_text(encoding='utf-8').splitlines()]

    def test_actual_recorded_scope_matches_world_uuid_and_all_configured_bounds(self):
        observation=INSPECTOR.observed(self.fixture,1,'CHECK_PASS',phase='walk')['value']['discoveries'][0]
        region=next(row for row in INSPECTOR.expected_regions(self.manifest) if row['id']=='fixture-walk')
        self.assertEqual(INSPECTOR.region_scope(region),observation['facts']['region.scope'])
        self.assertEqual(observation['id'],'exploration:first-visit:'+self.manifest['player_uuid']+':'+INSPECTOR.region_scope(region))
        moved=deepcopy(region);moved['min-x']+=1
        self.assertNotEqual(INSPECTOR.region_scope(moved),observation['facts']['region.scope'])

    def test_observed_failed_attempt_is_still_rejected_as_incomplete(self):
        rows=(self.evidence_run/'evidence/clients.jsonl').read_text(encoding='utf-8').splitlines()
        self.assertTrue(any(' ACTION_FAILED ' in line for line in rows))
        args=SimpleNamespace(sha=self.manifest['product_sha256'])
        with self.assertRaisesRegex(ValueError,'Exactly two completed boots required'):
            INSPECTOR.inspect(args,self.evidence_run)

    def test_preserved_folia_eventless_teleport_failure_cannot_be_reclassified(self):
        evidence_run=ROOT/'run/exploration-9612b1a4-folia-retry1'
        if not (evidence_run/'evidence/fixture.jsonl').is_file():self.skipTest('Requires preserved real failed Folia observations')
        manifest=INSPECTOR.read(evidence_run/'preparation.json')
        fixture=[json.loads(line) for line in (evidence_run/'evidence/fixture.jsonl').read_text(encoding='utf-8').splitlines()]
        self.assertTrue(any(row['kind']=='FAILED' and row['value']['operation']=='check-both' for row in fixture))
        actual=INSPECTOR.observed(fixture,1,'TELEPORT_RESULT',kind='cancel')['value']
        self.assertIs(actual['accepted'],True)
        self.assertFalse(any(row['kind']=='TELEPORT_CANCELLED' for row in fixture))
        with self.assertRaisesRegex(ValueError,'Exactly two completed boots required'):
            INSPECTOR.inspect(SimpleNamespace(sha=manifest['product_sha256']),evidence_run)
        with self.assertRaisesRegex(ValueError,'CANCELLATION_UNSUPPORTED'):
            INSPECTOR.cancellation_coverage(manifest,fixture)

    def test_current_lore_parser_preserves_real_wrapped_ids_and_stops_at_permission_lines(self):
        native=ROOT/'run/paper-quest-evidence-8f6f83ab-retry21/evidence/quest-evidence-clients.log'
        if not native.is_file():self.skipTest('Requires preserved native GUI protocol rows')
        records=[]
        for line in native.read_text(encoding='utf-8').splitlines():
            stamp,kind,value=line.split(' ',2)
            if kind=='STORY_VERIFIED':records.append(json.loads(value))
        record=next(row for row in records if row['storyId']=='qe-player-records')
        header=next(item for item in record['window']['slots'] if item['slot']==4)
        choice=next(item for item in record['window']['slots'] if item['slot']==9)
        source=(ROOT/'tools/exploration-clients.cjs').read_text(encoding='utf-8')
        selected='\n'.join(line for line in source.splitlines() if line.startswith(('const boundaries=','function field(')))
        self.assertIn('function field(',selected)
        payload={'source':selected,'cases':[[choice,'选项: '],[header,'实例: '],[header,'当前阶段: ']]}
        script="const fs=require('node:fs'),vm=require('node:vm');const p=JSON.parse(fs.readFileSync(0,'utf8'));const c={lore:item=>item.lore};vm.createContext(c);vm.runInContext(p.source,c);process.stdout.write(JSON.stringify(p.cases.map(([item,prefix])=>c.field(item,prefix))));"
        result=subprocess.run([str(INSPECTOR.NODE),'--max-old-space-size=32','-e',script],input=json.dumps(payload,ensure_ascii=False),text=True,encoding='utf-8',capture_output=True,timeout=10)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(json.loads(result.stdout),['yes',record['instanceId'],'await'])


class CompletedRunContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied=os.environ.get('STARCHRONICLE_EXPLORATION_RUN')
        if not supplied:raise unittest.SkipTest('Set STARCHRONICLE_EXPLORATION_RUN to an unchanged completed two-boot run')
        cls.evidence_run=Path(supplied).resolve();cls.manifest=INSPECTOR.read(cls.evidence_run/'preparation.json')
        cls.process=INSPECTOR.read(cls.evidence_run/'evidence/processes.json')
        if len(cls.process['boots'])!=2 or any(row.get('server_exit')!=0 or row.get('client_exit')!=0 or not row.get('checkpoint') for row in cls.process['boots']):
            raise unittest.SkipTest('Specified run is live or incomplete; no database inspection performed')
        cls.fixture=[json.loads(line) for line in (cls.evidence_run/'evidence/fixture.jsonl').read_text(encoding='utf-8').splitlines()]
        cls.clients=[]
        for line in (cls.evidence_run/'evidence/clients.jsonl').read_text(encoding='utf-8').splitlines():
            stamp,kind,value=line.split(' ',2);cls.clients.append({'time':stamp,'kind':kind,**json.loads(value)})

    def test_actual_protocol_requires_physical_repeat_actions_between_final_checks(self):
        INSPECTOR.verify_protocol(self.manifest,self.fixture,self.clients)
        missing=[row for row in self.clients if row['kind']!='WALK_ARRIVED']
        with self.assertRaisesRegex(ValueError,'WALK_ARRIVED'):INSPECTOR.verify_protocol(self.manifest,self.fixture,missing)
        changed=deepcopy(self.fixture)
        if self.manifest['platform']=='paper':
            cancelled=next(row for row in changed if row['kind']=='TELEPORT_RESULT' and row['value']['kind']=='cancel');cancelled['value']['accepted']=True
            with self.assertRaisesRegex(ValueError,'Cancelled real teleport'):INSPECTOR.verify_protocol(self.manifest,changed,self.clients)
        else:
            unsupported=next(row for row in changed if row['kind']=='CANCELLATION_UNSUPPORTED');unsupported['kind']='TELEPORT_CANCELLED'
            changed.append(deepcopy(next(row for row in self.fixture if row['kind']=='CANCELLATION_UNSUPPORTED')))
            with self.assertRaisesRegex(ValueError,'cancellation-unavailable scope'):INSPECTOR.verify_protocol(self.manifest,changed,self.clients)
            missing=[row for row in self.fixture if row['kind']!='CANCELLATION_UNSUPPORTED']
            with self.assertRaisesRegex(ValueError,'CANCELLATION_UNSUPPORTED'):INSPECTOR.verify_protocol(self.manifest,missing,self.clients)

    def test_completed_stopped_snapshots_independently_match_honor_choices_and_first_visits(self):
        checked=[]
        for record in self.process['boots']:
            descriptor=INSPECTOR.read(self.evidence_run/record['checkpoint']['path'])
            db=(self.evidence_run/record['checkpoint']['path']).parent/'chronicle.db'
            self.assertEqual(INSPECTOR.digest(db),descriptor['db_sha256'])
            checked.append(INSPECTOR.inspect_snapshot(db,self.manifest))
        for key in ('discoveries','claims','honor','instances','outcomes'):
            self.assertEqual(checked[0][key],checked[1][key])

    def test_current_complete_inspector_accepts_original_evidence_without_writing_it(self):
        originals=[self.evidence_run/'plugins/StarChronicle/chronicle.db',self.evidence_run/'evidence/inspection.json']
        for record in self.process['boots']:
            descriptor=self.evidence_run/record['checkpoint']['path']
            originals.extend((descriptor,descriptor.parent/'chronicle.db'))
        before={path:INSPECTOR.digest(path) for path in originals}
        with patch.object(INSPECTOR,'write') as output,redirect_stdout(io.StringIO()):
            INSPECTOR.inspect(SimpleNamespace(sha=self.manifest['product_sha256']),self.evidence_run)
        output.assert_called_once()
        self.assertEqual(output.call_args.args[0],self.evidence_run/'evidence/inspection.json')
        self.assertEqual(output.call_args.args[1]['status'],'PASS')
        cancellation=output.call_args.args[1]['cases']['cancelled_teleport_no_discovery']
        if self.manifest['platform']=='paper':self.assertIs(cancellation,True)
        else:
            self.assertIsNone(cancellation)
            detail=output.call_args.args[1]['case_details']['cancelled_teleport_no_discovery']
            self.assertEqual(detail['status'],'UNSUPPORTED');self.assertIn('PlayerTeleportEvent',detail['reason'])
            self.assertIn('coverage is unavailable',output.call_args.args[1]['scope'])
        self.assertEqual(output.call_args.args[1],INSPECTOR.read(self.evidence_run/'evidence/inspection.json'))
        self.assertEqual(before,{path:INSPECTOR.digest(path) for path in originals})

    def test_original_database_copies_reject_reward_ingress_scope_choice_and_outcome_tampering(self):
        descriptor=self.evidence_run/self.process['boots'][-1]['checkpoint']['path']
        original=descriptor.parent/'chronicle.db';before=INSPECTOR.digest(original)
        mutations=[
            ("UPDATE facts SET value='6' WHERE key='player.honor'",'exactly five honor'),
            ("UPDATE exploration_discoveries SET json='{}'",'Immutable ingress JSON'),
            ("UPDATE exploration_region_scopes SET scope='altered'",'logical region bindings'),
            ("UPDATE story_choice_log SET provenance='LEGACY_INSTANCE',chosen_at=NULL,committed_by=NULL",'ballot/resolution records'),
            ("DELETE FROM outcomes WHERE id=(SELECT id FROM outcomes LIMIT 1)",'Stable reward/history receipts'),
            ("UPDATE events SET hash='altered' WHERE type='EXPLORATION_DISCOVERY'",'Canonical history payload hash'),
        ]
        with tempfile.TemporaryDirectory(prefix='starchronicle-inspector-negative-') as directory:
            for index,(sql,error) in enumerate(mutations):
                with self.subTest(sql=sql):
                    copied=Path(directory)/f'negative-{index}.db';shutil.copyfile(original,copied)
                    conn=sqlite3.connect(copied)
                    try:conn.execute(sql);conn.commit()
                    finally:conn.close()
                    with self.assertRaisesRegex(ValueError,error):INSPECTOR.inspect_snapshot(copied,self.manifest)
        self.assertEqual(before,INSPECTOR.digest(original))

    def test_complete_inspector_rejects_changed_process_and_checkpoint_provenance(self):
        process_path=self.evidence_run/'evidence/processes.json';original_read=INSPECTOR.read
        cases=[
            ('child exit',lambda value:value['boots'][0].update(client_exit=1),'Normal owned child exits'),
            ('runtime identity',lambda value:value['boots'][0].update(client_sha256='0'*64),'Copied runtime source'),
            ('checkpoint path',lambda value:value['boots'][0]['checkpoint'].update(path='../chronicle.db'),'expected owned boot path'),
            ('checkpoint fingerprint',lambda value:value['boots'][0]['checkpoint'].update(sha256='0'*64),'Checkpoint descriptor changed'),
            ('checkpoint database fingerprint',lambda value:value['boots'][0]['checkpoint'].update(db_sha256='0'*64),'Checkpoint bytes changed'),
            ('serial restart',lambda value:value['boots'][1].update(started_utc=value['boots'][0]['started_utc']),'Boots were not serial'),
        ]
        for name,mutate,error in cases:
            with self.subTest(name=name):
                changed=deepcopy(self.process);mutate(changed)
                def read_negative(path):return changed if path==process_path else original_read(path)
                with patch.object(INSPECTOR,'read',side_effect=read_negative),patch.object(INSPECTOR,'write') as output,redirect_stdout(io.StringIO()):
                    with self.assertRaisesRegex(ValueError,error):INSPECTOR.inspect(SimpleNamespace(sha=self.manifest['product_sha256']),self.evidence_run)
                    output.assert_not_called()


if __name__=='__main__':unittest.main()
