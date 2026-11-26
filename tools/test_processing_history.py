"""Reject damaged copies of actual three-boot factory processing evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC=importlib.util.spec_from_file_location('processing_inspector',Path(__file__).with_name('inspect-processing-history.py'))
INSPECTOR=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(INSPECTOR)


class ActualProcessingEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        location=os.environ.get('STARCHRONICLE_PROCESSING_RUN')
        if not location: raise unittest.SkipTest('Requires completed real processing evidence')
        cls.source=Path(location).resolve(); cls.original=INSPECTOR.inspect(cls.source)

    def setUp(self):
        temporary=tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.run=Path(temporary.name)/self.source.name; self.run.mkdir()
        for directory in ('fixture','evidence'): shutil.copytree(self.source/directory,self.run/directory)
        shutil.copy2(self.source/'preparation.json',self.run/'preparation.json')
        (self.run/'plugins').mkdir()
        for name in ('StarCore','StarChronicle','ProcessingHistoryPaperFixture'):
            os.link(self.source/f'plugins/{name}.jar',self.run/f'plugins/{name}.jar')
        os.link(self.source/'paper.jar',self.run/'paper.jar')

    def database(self,plugin,boot=3):
        return self.run/f'evidence/boot{boot}.data/{plugin}'/('chronicle.db' if plugin=='StarChronicle' else 'starcore.db')

    def reject(self):
        with self.assertRaises(AssertionError): INSPECTOR.inspect(self.run)

    def sql(self,statement,values=(),plugin='StarChronicle',boot=3):
        with sqlite3.connect(self.database(plugin,boot)) as c: c.execute(statement,values)

    def alter_json_file(self,path,mutation):
        path=self.run/path; data=json.loads(path.read_text()); mutation(data); path.write_text(json.dumps(data))

    def alter_fixture(self,mutation):
        path=self.run/'evidence/fixture.jsonl'; data=[json.loads(line) for line in path.read_text().splitlines()]
        mutation(data); path.write_text(''.join(json.dumps(r)+'\n' for r in data))

    def alter_receipt(self,mutation):
        with sqlite3.connect(self.database('StarChronicle')) as c:
            row=c.execute('SELECT event_id,json FROM processing_history_receipts WHERE sequence=135').fetchone()
            data=json.loads(row[1]); mutation(data)
            c.execute('UPDATE processing_history_receipts SET json=? WHERE event_id=?',(json.dumps(data),row[0]))

    def test_real_three_boot_evidence_passes(self):
        self.assertEqual(INSPECTOR.inspect(self.run),self.original)

    def test_missing_started_source_receipt_rejected(self):
        next((self.run/'evidence/boot2.data/StarCore/resource/processing-history').rglob('4.receipt')).unlink(); self.reject()

    def test_current_leader_cannot_replace_original_actor(self):
        a=INSPECTOR.offline_id('ProduceA'); self.alter_receipt(lambda r:r['run'].update(actorId=a)); self.reject()

    def test_reregistered_recipe_cannot_replace_frozen_output(self):
        self.alter_receipt(lambda r:r['run']['outputs'].update(industrial=999)); self.reject()

    def test_frozen_ready_time_cannot_change(self):
        self.alter_receipt(lambda r:r['run'].update(readyAt=r['run']['startedAt'])); self.reject()

    def test_stockpile_must_equal_replayed_inputs_and_outputs(self):
        self.sql("UPDATE starcore_resource_state SET property_value='999' WHERE property_key='stockpile.0.amount'",plugin='StarCore'); self.reject()

    def test_active_factory_cannot_disappear(self):
        self.sql("UPDATE starcore_resource_state SET property_value='0' WHERE property_key='factory.count'",plugin='StarCore',boot=1); self.reject()

    def test_frozen_run_cannot_change(self):
        self.sql("UPDATE starcore_resource_state SET property_value=replace(property_value,'industrial:2','industrial:999') WHERE property_key='factory.0.run'",plugin='StarCore',boot=1); self.reject()

    def test_run_index_cannot_lose_a_started_entry(self):
        path=self.run/'evidence/boot2.data/StarCore/resource/processing-history/runs.sqlite'
        with sqlite3.connect(path) as c: c.execute('DELETE FROM entries WHERE sequence=4')
        self.reject()

    def test_native_pending_receipt_must_equal_archived_receipt(self):
        self.sql("UPDATE starcore_resource_state SET property_value=replace(property_value,'industrial:2','industrial:999') WHERE property_key='processing.history.pending'",plugin='StarCore'); self.reject()

    def test_consumer_cannot_drop_full_completion_receipt(self):
        self.sql('DELETE FROM processing_history_receipts WHERE sequence=135'); self.reject()

    def test_consumer_cannot_skip_start_evidence(self):
        self.sql('DELETE FROM processing_history_receipts WHERE sequence=4'); self.reject()

    def test_ingress_cannot_drop_complete_payload(self):
        self.sql("DELETE FROM committed_processing_ingress WHERE event_id LIKE '%:COMPLETED'"); self.reject()

    def test_pending_ingress_cannot_be_called_completed(self):
        self.sql("UPDATE committed_history_ingress SET status='PENDING' WHERE namespace='starcore.processing' AND sequence=135"); self.reject()

    def test_initial_archive_cannot_be_live(self):
        self.sql("UPDATE committed_history_ingress SET activate=1 WHERE namespace='starcore.processing' AND sequence=1"); self.reject()

    def test_schema9_cannot_masquerade_as_processing_schema(self):
        self.sql('PRAGMA user_version=9'); self.reject()

    def test_cursor_cannot_roll_back(self):
        self.sql("UPDATE committed_history_sources SET sequence=136 WHERE namespace='starcore.processing'"); self.reject()

    def test_contribution_cannot_be_duplicated(self):
        self.sql("INSERT INTO contributions SELECT id||':duplicate',operation_id||':duplicate',type,subject_id,source_event_id,actor_id,nation_id,amount,unit,committed_at,evidence,request_json,recorded_at FROM contributions LIMIT 1"); self.reject()

    def test_indexed_contribution_cannot_disagree_with_request(self):
        self.sql("UPDATE contributions SET amount='999' WHERE rowid=(SELECT MIN(rowid) FROM contributions)"); self.reject()

    def test_story_completion_trigger_cannot_be_lost(self):
        self.sql('DELETE FROM story_trigger_receipts WHERE rowid=(SELECT MIN(rowid) FROM story_trigger_receipts)'); self.reject()

    def test_public_full_receipt_cannot_change_actor(self):
        def mutate(rows):
            next(r for r in rows if r['kind']=='CHECK' and r['boot']==2)['data']['receipts'][3]['run']['actorId']=INSPECTOR.offline_id('ProduceA')
        self.alter_fixture(mutate); self.reject()

    def test_real_player_command_cannot_be_replaced_by_operator(self):
        def mutate(rows): next(r for r in rows if r['kind']=='PLAYER_COMMAND')['data']['op']=True
        self.alter_fixture(mutate); self.reject()

    def test_actual_leadership_handover_is_required(self):
        self.alter_fixture(lambda rows:rows.__setitem__(slice(None),[r for r in rows if r['kind']!='HANDOVER'])); self.reject()

    def test_client_denial_response_is_required(self):
        path=self.run/'evidence/boot1.clients.log'; path.write_text(path.read_text().replace('当前国家国主','UnauthorizedReplacement')); self.reject()

    def test_failed_client_shutdown_is_not_passing(self):
        self.alter_json_file('evidence/boot3.session.json',lambda r:r['processes'][1].update(exit_code=137)); self.reject()

    def test_reused_pid_is_not_a_restart(self):
        pid=json.loads((self.run/'evidence/boot1.session.json').read_text())['processes'][0]['pid']
        self.alter_json_file('evidence/boot3.session.json',lambda r:r['processes'][0].update(pid=pid)); self.reject()

    def test_source_failure_log_is_not_passing(self):
        path=self.run/'evidence/boot2.server.log'; path.write_text(path.read_text()+'\nProcessing history scan retained its cursor: unavailable\n'); self.reject()

    def test_artifact_fingerprint_drift_is_rejected(self):
        self.alter_json_file('preparation.json',lambda r:r.update(native_sha256='0'*64)); self.reject()


if __name__=='__main__': unittest.main()
