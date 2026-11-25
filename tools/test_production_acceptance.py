"""Reject tampered copies of real production-stack acceptance evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest
import yaml

SPEC=importlib.util.spec_from_file_location('production_inspector',Path(__file__).with_name('inspect-production-paper.py'))
INSPECTOR=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(INSPECTOR)


class ActualProductionEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied=os.environ.get('STARCHRONICLE_PRODUCTION_RUN')
        if not supplied: raise unittest.SkipTest('Requires a completed actual production-stack run')
        cls.source=Path(supplied).resolve();cls.original=INSPECTOR.inspect(cls.source)

    def setUp(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        self.run=Path(temporary.name)/self.source.name;self.run.mkdir()
        for name in ('evidence','fixture'):
            shutil.copytree(self.source/name,self.run/name)
        shutil.copy2(self.source/'preparation.json',self.run/'preparation.json')
        os.link(self.source/'paper.jar',self.run/'paper.jar')
        for name in ('StarCore','StarChronicle','Essentials','WorldGuard','FancyNpcs','EpicCampaignPaperFixture'):
            source=self.source/'plugins'/name;target=self.run/'plugins'/name
            if name=='StarCore':
                target.mkdir(parents=True)
                for file in ('starcore.db','config.yml'):shutil.copy2(source/file,target/file)
            else:shutil.copytree(source,target)
        for path in (self.source/'plugins').glob('*.jar'):
            os.link(path,self.run/'plugins'/path.name)

    def reject(self,message):
        with self.assertRaisesRegex(AssertionError,message):INSPECTOR.inspect(self.run)

    def json_edit(self,path,edit):
        target=self.run/path;data=json.loads(target.read_text());edit(data);target.write_text(json.dumps(data))

    def yaml_edit(self,path,edit):
        target=self.run/path;data=yaml.safe_load(target.read_text());edit(data);target.write_text(yaml.safe_dump(data))

    def live_edit(self,boot,kind,edit):
        target=self.run/'evidence/epic-fixture.jsonl';rows=INSPECTOR.records(target)
        for row in rows:
            if row['boot']==boot and row['kind']==kind:edit(row['data'])
        target.write_text(''.join(json.dumps(row)+'\n' for row in rows))
        target=self.run/f'evidence/boot{boot}.server.log';lines=target.read_text().splitlines();marker='EPIC_'+kind+' '
        for index,line in enumerate(lines):
            if marker in line:
                prefix,payload=line.split(marker,1);data=json.loads(payload);edit(data)
                lines[index]=prefix+marker+json.dumps(data)
        target.write_text('\n'.join(lines)+'\n')

    def test_original_live_run_passes(self):self.assertEqual(INSPECTOR.inspect(self.run),self.original)

    def test_missing_boot_is_rejected(self):
        (self.run/'evidence/boot3.session.json').unlink();self.reject('three completed')

    def test_abnormal_process_exit_is_rejected(self):
        self.json_edit('evidence/boot2.session.json',lambda d:d['processes'][0].update(exit_code=137));self.reject('Abnormal process exit')

    def test_actual_retained_worldguard_failure_is_rejected(self):
        source=Path(os.environ['STARCHRONICLE_PRODUCTION_BASELINE'])
        rows=INSPECTOR.records(source/'evidence/epic-fixture.jsonl')
        failure=next(row for row in rows if row['kind']=='FAILED')
        with (self.run/'evidence/epic-fixture.jsonl').open('a') as output:output.write(json.dumps(failure)+'\n')
        self.reject('Fixture failure')

    def test_client_click_removed_is_rejected(self):
        path=self.run/'evidence/boot2.clients.log';path.write_text('\n'.join(line for line in path.read_text().splitlines() if 'NPC_CLICK' not in line));self.reject('real NPC client clicks')

    def test_real_client_replaced_by_synthetic_flag_is_rejected(self):
        path=self.run/'evidence/boot1.clients.log';path.write_text(path.read_text().replace('"syntheticEvents":false','"syntheticEvents":true'));self.reject('NPC click provenance')

    def test_missing_server_observation_is_rejected(self):
        path=self.run/'evidence/boot2.server.log';path.write_text('\n'.join(line for line in path.read_text().splitlines() if 'EPIC_STACK_NPC_EVENT ' not in line));self.reject('absent from server stream')

    def test_protection_config_disabled_is_rejected(self):
        self.yaml_edit('plugins/StarCore/config.yml',lambda d:d['integrations']['worldguard'].update(enabled=False));self.reject('integration config')

    def test_economy_config_rebound_is_rejected(self):
        self.yaml_edit('plugins/StarCore/config.yml',lambda d:d['economy'].update(provider='internal'));self.reject('integration config')

    def test_external_balance_changed_is_rejected(self):
        manifest=INSPECTOR.EPIC.read(self.run/'preparation.json')
        self.yaml_edit('plugins/Essentials/userdata/'+manifest['players']['A']+'.yml',lambda d:d.update(money=999));self.reject('Essentials balance')

    def test_exact_claim_payment_mismatch_is_rejected(self):
        self.live_edit(1,'STACK_CLAIM_PAYMENT',lambda d:d.update(essentials='9903.179999999998'))
        self.reject('exact Essentials/native claim payment')

    def test_npc_binding_changed_is_rejected(self):
        self.yaml_edit('plugins/StarChronicle/npcs.yml',lambda d:d['profiles']['ancient-heir'].update(bindings=[]));self.reject('NPC binding')

    def test_saved_external_npc_deleted_is_rejected(self):
        self.yaml_edit('plugins/FancyNpcs/npcs.yml',lambda d:d['npcs'].pop(INSPECTOR.BOUND));self.reject('FancyNpcs identities/count')

    def test_saved_external_npc_type_changed_is_rejected(self):
        self.yaml_edit('plugins/FancyNpcs/npcs.yml',lambda d:d['npcs'][INSPECTOR.BOUND].update(type='PLAYER'));self.reject('FancyNpcs entity provenance')

    def test_saved_external_npc_creator_changed_is_rejected(self):
        self.yaml_edit('plugins/FancyNpcs/npcs.yml',lambda d:d['npcs'][INSPECTOR.BOUND].update(creator='foreign'));self.reject('FancyNpcs entity provenance')

    def test_region_deleted_is_rejected(self):
        self.yaml_edit('plugins/WorldGuard/worlds/world/regions.yml',lambda d:d.update(regions={}));self.reject('WorldGuard region')

    def test_region_height_changed_is_rejected(self):
        self.yaml_edit('plugins/WorldGuard/worlds/world/regions.yml',lambda d:d['regions']['isolated_starcore_protected']['min'].update(y=64));self.reject('WorldGuard region')

    def test_wrong_claim_owner_is_rejected(self):
        with sqlite3.connect(self.run/'plugins/StarCore/starcore.db') as db:db.execute("update starcore_territory_claims set owner_id='foreign'")
        self.reject('claim ownership')

    def test_protected_claim_inserted_is_rejected(self):
        with sqlite3.connect(self.run/'plugins/StarCore/starcore.db') as db:db.execute("insert into starcore_territory_claims values ('world',16,16,'foreign')")
        self.reject('claim ownership')

    def test_missing_npc_history_is_rejected(self):
        with sqlite3.connect(self.run/'plugins/StarChronicle/chronicle.db') as db:db.execute("delete from events where id=(select id from events where type='NPC_INTERACT' limit 1)")
        self.reject('exactly three persisted')

    def test_npc_actor_changed_is_rejected(self):
        with sqlite3.connect(self.run/'plugins/StarChronicle/chronicle.db') as db:db.execute("update events set json=json_set(json,'$.participants',json('[]')) where type='NPC_INTERACT'")
        self.reject('Persisted NPC event')

    def test_npc_event_hash_changed_is_rejected(self):
        with sqlite3.connect(self.run/'plugins/StarChronicle/chronicle.db') as db:db.execute("update events set hash='invalid' where type='NPC_INTERACT'")
        self.reject('canonical event hash')

    def test_npc_actor_index_missing_is_rejected(self):
        with sqlite3.connect(self.run/'plugins/StarChronicle/chronicle.db') as db:db.execute("delete from event_players where event_id in (select id from events where type='NPC_INTERACT')")
        self.reject('participant index')

    def test_unrelated_server_error_is_rejected(self):
        with (self.run/'evidence/boot1.server.log').open('a') as out:out.write('\n[ERROR]: Failed to persist claim ownership\n')
        self.reject('persistence/lifecycle diagnostic')

    def test_production_fixture_source_drift_is_rejected(self):
        path=self.run/'fixture/ProductionStackPaperFixture.java';path.write_text(path.read_text()+'\n// changed\n');self.reject('production fixture source')

    def test_controller_source_drift_is_rejected(self):
        path=self.run/'evidence/boot2.controller.py';path.write_text(path.read_text()+'\n# changed\n');self.reject('controller/client source')

    def test_client_source_drift_is_rejected(self):
        path=self.run/'evidence/boot1.clients.cjs';path.write_text(path.read_text()+'\n// changed\n');self.reject('controller/client source')


if __name__=='__main__':unittest.main()
