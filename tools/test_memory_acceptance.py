"""Exercise the independent inspector against copies of actual memory runtime evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('memory_inspector', Path(__file__).with_name('inspect-memory-paper.py'))
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


class ActualMemoryEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied = os.environ.get('STARCHRONICLE_MEMORY_RUN')
        if not supplied:
            raise unittest.SkipTest('Requires a completed actual memory run')
        cls.source = Path(supplied).resolve()
        cls.original = INSPECTOR.inspect(cls.source)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.run = Path(temporary.name) / self.source.name
        self.run.mkdir()
        for name in ('evidence', 'fixture'):
            shutil.copytree(self.source / name, self.run / name)
        shutil.copy2(self.source / 'preparation.json', self.run / 'preparation.json')
        os.link(self.source / 'paper.jar', self.run / 'paper.jar')
        shutil.copytree(self.source / 'plugins/StarChronicle', self.run / 'plugins/StarChronicle')
        for path in (self.source / 'plugins').glob('*.jar'):
            os.link(path, self.run / 'plugins' / path.name)

    def reject(self, message):
        with self.assertRaisesRegex(AssertionError, message):
            INSPECTOR.inspect(self.run)

    def json_edit(self, relative, edit):
        path = self.run / relative
        value = json.loads(path.read_text())
        edit(value)
        path.write_text(json.dumps(value))

    def sql(self, statement, *values):
        with sqlite3.connect(self.run / 'plugins/StarChronicle/chronicle.db') as db:
            db.execute(statement, values)

    def remove_client_kind(self, kind):
        for path in (self.run / 'evidence').glob('boot*.clients.log'):
            path.write_text(''.join(line for line in path.read_text().splitlines(keepends=True)
                                    if not line.startswith('{') or json.loads(line)['kind'] != kind))

    def live_edit(self, boot, kind, edit, remove=False):
        path = self.run / 'evidence/epic-fixture.jsonl'
        rows = INSPECTOR.records(path)
        for row in rows:
            if row['boot'] == boot and row['kind'] == kind:
                edit(row['data'])
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows
                                if not (remove and row['boot'] == boot and row['kind'] == kind)))
        path = self.run / f'evidence/boot{boot}.server.log'
        marker = 'EPIC_' + kind + ' '
        output = []
        for line in path.read_text().splitlines():
            if marker in line:
                if remove:
                    continue
                prefix, payload = line.split(marker, 1)
                value = json.loads(payload)
                edit(value)
                line = prefix + marker + json.dumps(value)
            output.append(line)
        path.write_text('\n'.join(output) + '\n')

    def test_original_actual_run_passes(self):
        self.assertEqual(INSPECTOR.inspect(self.run), self.original)

    def test_missing_boot_is_rejected(self):
        (self.run / 'evidence/boot2.session.json').unlink()
        self.reject('Missing complete memory boot')

    def test_abnormal_client_exit_is_rejected(self):
        self.json_edit('evidence/boot2.session.json', lambda d: d['processes'][1].update(exit_code=134))
        self.reject('Abnormal memory process exit')

    def test_retained_actual_first_attempt_failure_is_rejected(self):
        supplied = os.environ.get('STARCHRONICLE_MEMORY_FAILED_RUN')
        if not supplied:
            self.skipTest('Original failed run is not supplied')
        records = INSPECTOR.records(Path(supplied) / 'evidence/epic-fixture.jsonl')
        failure = next(row for row in records if row['kind'] == 'FAILED')
        with (self.run / 'evidence/epic-fixture.jsonl').open('a') as output:
            output.write(json.dumps(failure) + '\n')
        self.reject('Retained memory fixture failure')

    def test_controller_drift_is_rejected(self):
        with (self.run / 'evidence/boot2.controller.py').open('a') as output:
            output.write('\n# changed\n')
        self.reject('Frozen memory controller/client changed')

    def test_missing_stopped_snapshot_hashes_is_rejected(self):
        self.json_edit('evidence/checkpoints/boot1/checkpoint.json', lambda d: d.update(sha256={}))
        self.reject('Stopped memory database checkpoint changed')

    def test_missing_real_walk_is_rejected(self):
        self.remove_client_kind('MEMORY_WALK')
        self.reject('Missing actual memory movement')

    def test_synthetic_movement_is_rejected(self):
        path = self.run / 'evidence/boot1.clients.log'
        path.write_text(path.read_text().replace('"syntheticEvents":false', '"syntheticEvents":true'))
        self.reject('Missing actual memory movement')

    def test_missing_gui_choice_is_rejected(self):
        self.remove_client_kind('MEMORY_GUI_CHOICE')
        self.reject('Missing real library GUI choices')

    def test_source_participants_expanded_to_readers_is_rejected(self):
        self.sql("update events set json=json_set(json,'$.participants',json('[]')) where type='EXPLORATION_DISCOVERY'")
        self.reject('Memory history attributed an invalid actor')

    def test_original_history_hash_changed_is_rejected(self):
        self.sql("update events set hash='wrong' where type='EXPLORATION_DISCOVERY'")
        self.reject('Memory history canonical hash mismatch')

    def test_history_actor_index_missing_is_rejected(self):
        self.sql("delete from event_players where event_id in (select id from events where type='EXPLORATION_DISCOVERY')")
        self.reject('Memory event player index mismatch')

    def test_memory_source_time_rewritten_is_rejected(self):
        self.sql("update memory_entries set json=json_set(json,'$.sourceOccurredAt','2026-01-01T00:00:00Z')")
        self.reject('Memory source time was rewritten')

    def test_memory_title_rewritten_is_rejected(self):
        self.sql("update memory_entries set json=json_set(json,'$.title','different')")
        self.reject('Persisted memory recipe content mismatch')

    def test_memory_causal_path_deleted_is_rejected(self):
        self.sql("update event_causes set recipe_path='[]' where event_id in (select signal_event_id from memory_entries)")
        self.reject('Memory signal causal path mismatch')

    def test_library_causal_root_changed_is_rejected(self):
        self.sql("update event_causes set root_id=parent_id where event_id in (select id from events where type like 'CARAVAN_ARCHIVE_%')")
        self.reject('Library ending causal path mismatch')

    def test_player_choice_projection_deleted_is_rejected(self):
        self.sql("update instances set json=json_remove(json,'$.choices.' || 'archive/' || substr(scope,8)) where story='caravan-library'")
        self.reject('Persisted library choice/completion mismatch')

    def test_outcome_receipt_deleted_is_rejected(self):
        self.sql('delete from outcomes where id=(select id from outcomes limit 1)')
        self.reject('exactly-once outcome receipt')

    def test_choice_audit_actor_changed_is_rejected(self):
        self.sql("update story_choice_log set committed_by='foreign'")
        self.reject('Library choice audit actor/source mismatch')

    def test_trigger_consumption_deleted_is_rejected(self):
        self.sql('delete from story_trigger_receipts')
        self.reject('Library trigger consumption mismatch')

    def test_reward_replay_is_rejected(self):
        self.sql("update facts set value='6' where key='player.honor'")
        self.reject('Persisted honor duplicated or assigned to reader')

    def test_local_projection_deleted_is_rejected(self):
        self.sql("delete from facts where owner='WORLD' and key like 'region.old-caravan-road.memory.%'")
        self.reject('Latest local memory projection mismatch')

    def test_missing_replay_evidence_is_rejected(self):
        self.live_edit(2, 'MEMORY_REPLAY', lambda d: None, remove=True)
        self.reject('Missing canonical memory replay evidence')

    def test_missing_choice_rejection_is_rejected(self):
        if self.original['mode'] == 'offline':
            self.skipTest('Completed-choice rejection is exercised by the main scenario')
        self.live_edit(2, 'MEMORY_CHOICE_REJECTED', lambda d: None, remove=True)
        self.reject('Missing completed library choice rejection')

    def test_offline_observation_with_players_is_rejected(self):
        if self.original['mode'] != 'offline':
            self.skipTest('Requires the separate offline scenario')
        def edit(data):
            if data['phase'] == 'offline-recorded':
                data['state']['online'] = ['foreign']
        self.live_edit(2, 'MEMORY_CHECK', edit)
        self.reject('Memory was not generated before all clients connected')

    def test_missing_non_admin_denial_is_rejected(self):
        if self.original['mode'] == 'offline':
            self.skipTest('Reload authorization is exercised by the main scenario')
        self.remove_client_kind('COMMAND_RESULT')
        self.reject('Non-admin reload was not rejected')

    def test_unrelated_persistence_error_is_rejected(self):
        with (self.run / 'evidence/boot2.server.log').open('a') as output:
            output.write('\n[ERROR]: SQLITE_BUSY while saving memory\n')
        self.reject('Memory server lifecycle/persistence error')


if __name__ == '__main__':
    unittest.main()
