"""Fast replay-free check of the Paper acceptance parser against retained real client logs."""
import importlib.util
import json
import os
import hashlib
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
INSPECTOR_PATH = ROOT / 'tools' / 'inspect-quest-evidence-paper.py'
SPEC = importlib.util.spec_from_file_location('quest_evidence_inspector', INSPECTOR_PATH)
INSPECTOR = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(INSPECTOR)


class ActualPaperClientContractTest(unittest.TestCase):
    """The evidence rows are protocol output from the independently retained retry21 run."""

    @classmethod
    def setUpClass(cls):
        cls.retained_run = Path(os.environ.get('STARCHRONICLE_QUEST_EVIDENCE_RUN', str(ROOT / 'run' / 'paper-quest-evidence-8f6f83ab-retry21'))).resolve()
        if not (cls.retained_run / 'evidence/quest-fixture.jsonl').is_file():
            raise unittest.SkipTest('Requires retained real retry21 protocol/checkpoint evidence; set STARCHRONICLE_QUEST_EVIDENCE_RUN to that unchanged directory')
        cls.manifest = INSPECTOR.read_json(cls.retained_run / 'preparation.json')
        cls.process = INSPECTOR.read_json(cls.retained_run / 'evidence/quest-evidence-processes.json')
        cls.fixture = INSPECTOR.fixture_log(cls.retained_run / 'evidence/quest-fixture.jsonl')
        cls.clients = INSPECTOR.client_log(cls.retained_run / 'evidence/quest-evidence-clients.log')
        cls.countries = INSPECTOR.native_country_index(cls.fixture, cls.manifest)
        cls.checkpoints = [INSPECTOR.checkpoint(cls.retained_run, n, cls.process['boots'][n - 1], cls.manifest) for n in (1, 2, 3)]

    def test_real_spawn_shutdown_and_native_gui_records_match_exact_cohorts(self):
        run = self.retained_run
        manifest = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
        rows = INSPECTOR.client_log(run / 'evidence' / 'quest-evidence-clients.log')
        fixture = INSPECTOR.fixture_log(run / 'evidence' / 'quest-fixture.jsonl')
        process = json.loads((run / 'evidence' / 'quest-evidence-processes.json').read_text(encoding='utf-8'))
        checked = INSPECTOR.check_client_protocol(rows, manifest, {})
        self.assertEqual(checked['spawned'], {1: ['A', 'B', 'C'], 2: ['A', 'B', 'C'], 3: ['A', 'C']})
        for boot in process['boots']:
            client_source = run / 'evidence' / f"quest-evidence-client-used-boot{boot['boot']}.cjs"
            session_source = run / 'evidence' / f"quest-evidence-session-used-boot{boot['boot']}.py"
            self.assertEqual(INSPECTOR.digest(client_source), boot['client_script_sha256'])
            self.assertEqual(INSPECTOR.digest(session_source), boot['controller_sha256'])
        countries = INSPECTOR.native_country_index(fixture, manifest)
        self.assertEqual(countries['A'], countries['B'])
        self.assertNotEqual(countries['A'], countries['C'])
        grid = INSPECTOR.observed(rows, 1, 'CRAFT_GRID_REQUESTED', player='C', item='map')['value']
        crafted = INSPECTOR.observed(rows, 1, 'CRAFTED', player='C', item='map')['value']
        self.assertEqual((grid['resultSlot'], grid['compassSlotInGrid']), (0, 5))
        self.assertEqual(crafted['delta'], {'paper': -8, 'compass': -1, 'map': 1})
        for boot, players in INSPECTOR.COHORTS.items():
            selected = [row for row in rows if row['boot'] == boot]
            self.assertEqual(len([row for row in selected if row['kind'] == 'QUIT_ALL_REQUESTED']), 1)
            ended = [row for row in selected if row['kind'] == 'ENDED']
            self.assertEqual({row['value'].get('player') for row in ended}, players)
            self.assertTrue(all(row['value'].get('requestedQuit') is True for row in ended))
        self.assertEqual(len([row for row in rows if row['boot'] == 2 and row['kind'] == 'CLICKED' and row['value'].get('path') == 'native-complete']), 3)

    def test_actual_checkpoint_returns_verified_schema_and_native_file_namespace(self):
        for checkpoint in self.checkpoints:
            self.assertEqual(checkpoint['db']['schema_version'], 6)
        first = self.checkpoints[0]
        player = self.manifest['players']['A']
        self.assertIn(player + '.active', first['quests'])
        self.assertNotIn('pq.' + player + '.active', first['quests'])
        native = INSPECTOR.quest_record(first['quests'], player, 'active', self.manifest['native_quests']['heir']['id'])
        self.assertEqual(native['objectives'][0]['progress'], 3)

    def test_checkpoint_hash_count_and_process_identity_are_required(self):
        original = self.process['boots'][0]
        bad_hash = deepcopy(original)
        bad_hash['checkpoint']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'descriptor differs from session provenance'):
            INSPECTOR.checkpoint(self.retained_run, 1, bad_hash, self.manifest)
        bad_count = deepcopy(original)
        bad_count['checkpoint']['file_count'] += 1
        with self.assertRaisesRegex(ValueError, 'file count differs'):
            INSPECTOR.checkpoint(self.retained_run, 1, bad_count, self.manifest)
        bad_process = deepcopy(original)
        bad_process['server_pid'] += 1
        with self.assertRaisesRegex(ValueError, 'provenance differs.*server_pid'):
            INSPECTOR.checkpoint(self.retained_run, 1, bad_process, self.manifest)

    def test_all_three_native_proofs_match_actual_disk_gui_and_chronicle_rows(self):
        # The helper still requires real CRAFTED output before progress capture, exact GUI
        # completion, independent native markers/journals and all SQLite proof/history keys.
        for key, (_, _, _, progress, _) in INSPECTOR.CASES.items():
            with self.subTest(case=key):
                result = INSPECTOR.check_native_case(key, self.manifest, self.fixture, self.clients, self.checkpoints, self.countries)
                self.assertEqual(result['progress'], progress)
        # The older retry21 map protocol omitted CRAFT_REQUESTED; that missing observation
        # must remain a failure for the full case, even though its native proof is valid.
        for key in ('player', 'nation'):
            INSPECTOR.check_case(key, self.manifest, self.fixture, self.clients, self.checkpoints, self.countries)
        with self.assertRaisesRegex(ValueError, 'CRAFT_REQUESTED'):
            INSPECTOR.check_case('atlas', self.manifest, self.fixture, self.clients, self.checkpoints, self.countries)

    def test_real_craft_result_cannot_hide_unrelated_inventory_changes(self):
        changed = deepcopy(self.clients)
        crafted = next(r for r in changed if r['kind'] == 'CRAFTED' and r['value'].get('player') == 'A')
        crafted['value']['after']['diamond'] = crafted['value']['before'].get('diamond', 0) + 1
        with self.assertRaisesRegex(ValueError, 'exact inventory delta differs'):
            INSPECTOR.check_case('player', self.manifest, self.fixture, changed, self.checkpoints, self.countries)

    def test_mismatched_native_run_is_rejected_even_with_valid_runtime_fixture(self):
        checkpoints = deepcopy(self.checkpoints)
        player = self.manifest['players']['A']
        native = json.loads(checkpoints[0]['quests'][player + '.active'])
        selected = next(item for item in native if item['id'] == 'chronicle_heir_records')
        selected['runId'] = '00000000-0000-0000-0000-000000000001'
        checkpoints[0]['quests'][player + '.active'] = json.dumps(native)
        with self.assertRaisesRegex(ValueError, 'persisted active identity/craft progress differs'):
            INSPECTOR.check_native_case('player', self.manifest, self.fixture, self.clients, checkpoints, self.countries)

    def test_preparation_checks_actual_deployed_artifacts_source_and_story_bytes(self):
        INSPECTOR.preparation_checks(self.retained_run, self.manifest, self.manifest['plugin_sha256'])
        for key in ('starcore_sha256', 'paper_sha256', 'fixture_source_sha256'):
            with self.subTest(field=key):
                changed = deepcopy(self.manifest); changed[key] = '0' * 64
                with self.assertRaisesRegex(ValueError, 'SHA drift|source drift'):
                    INSPECTOR.preparation_checks(self.retained_run, changed, self.manifest['plugin_sha256'])
        changed = deepcopy(self.manifest); changed['story_sha256']['qe-player-records.json'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'story bytes changed'):
            INSPECTOR.preparation_checks(self.retained_run, changed, self.manifest['plugin_sha256'])

    def test_actual_config_and_isolated_endpoint_cannot_be_substituted(self):
        original_read = Path.read_text; config = self.retained_run / 'plugins/StarChronicle/config.yml'
        def wrong_config(path, *args, **kwargs):
            text = original_read(path, *args, **kwargs)
            return text.replace('require-starcore: true', 'require-starcore: false') if path == config else text
        with patch.object(Path, 'read_text', wrong_config):
            with self.assertRaisesRegex(ValueError, 'acceptance config differs'):
                INSPECTOR.preparation_checks(self.retained_run, self.manifest, self.manifest['plugin_sha256'])
        original_properties = INSPECTOR.properties
        def wrong_endpoint(path):
            value = original_properties(path)
            return value | {'server-ip': '0.0.0.0'} if path.name == 'server.properties' else value
        with patch.object(INSPECTOR, 'properties', wrong_endpoint):
            with self.assertRaisesRegex(ValueError, 'isolated endpoint differs'):
                INSPECTOR.preparation_checks(self.retained_run, self.manifest, self.manifest['plugin_sha256'])

    def test_process_errors_artifact_drift_and_overlapping_boots_are_rejected(self):
        for key, value in (('cleanup_errors', ['pipe failed']), ('launcher_error', 'lost controller'), ('checkpoint_error', 'copy failed')):
            with self.subTest(field=key):
                changed = deepcopy(self.process); changed['boots'][0][key] = value
                with self.assertRaisesRegex(ValueError, 'launcher, cleanup or checkpoint error'):
                    INSPECTOR.process_checks(self.retained_run, self.manifest, changed, {})
        changed = deepcopy(self.process); changed['boots'][0]['artifacts_after']['starcore_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'post-stop artifact identities differ'):
            INSPECTOR.process_checks(self.retained_run, self.manifest, changed, {})
        changed = deepcopy(self.process); changed['boots'][1]['requested_utc'] = changed['boots'][0]['server_started_utc']
        with self.assertRaisesRegex(ValueError, 'not serial'):
            INSPECTOR.process_checks(self.retained_run, self.manifest, changed, {})

    def test_actual_fixture_and_client_process_provenance_is_required(self):
        # First two preserved boots were normal. Their positive helper check never grants
        # the old run a final PASS; boot3's actual FAILED remains independently fatal.
        fixture = [r for r in self.fixture if r['boot'] < 3]
        clients = [r for r in self.clients if r['boot'] < 3]
        processes = deepcopy(self.process); processes['boots'] = processes['boots'][:2]
        INSPECTOR.check_fixture_protocol(fixture, clients, self.manifest, processes)
        changed = deepcopy(clients); process = next(r for r in changed if r['kind'] == 'CLIENT_PROCESS'); process['value']['pid'] += 1
        with self.assertRaisesRegex(ValueError, 'actual client process identity differs'):
            INSPECTOR.check_fixture_protocol(fixture, changed, self.manifest, processes)
        changed = deepcopy(clients); changed[0]['time'] = '2000-01-01T00:00:00Z'
        with self.assertRaisesRegex(ValueError, 'outside owned client lifetime'):
            INSPECTOR.check_fixture_protocol(fixture, changed, self.manifest, processes)
        with self.assertRaisesRegex(ValueError, 'fixture FAILED record retained'):
            INSPECTOR.check_fixture_protocol(self.fixture, self.clients, self.manifest, self.process)

    def projection(self, checkpoints=None, clients=None, final=None):
        return INSPECTOR.check_chronicle_projection(self.manifest, self.fixture, clients or self.clients, checkpoints or self.checkpoints, self.countries,
            final or INSPECTOR.matched(self.fixture, 3, 'FINAL_CHECK_PASS')[-1]['value'],
            {key: INSPECTOR.check_native_case(key, self.manifest, self.fixture, self.clients, self.checkpoints, self.countries) for key in INSPECTOR.CASES})

    def test_actual_gui_choices_match_independently_stopped_projection(self):
        result = self.projection()
        self.assertEqual(result['actual_gui_choices'], 3)
        self.assertEqual(result['done_outcomes'], 8)
        self.assertEqual({who: result['honor'][player] for who, player in self.manifest['players'].items()}, {'A': 10, 'B': 7, 'C': 12})
        changed = deepcopy(self.clients); click = next(r for r in changed if r['kind'] == 'CLICKED' and r['value'].get('path') == 'story-choice'); click['value']['windowId'] += 1
        with self.assertRaisesRegex(ValueError, 'Actual Chronicle GUI'):
            self.projection(clients=changed)
        changed = deepcopy(self.checkpoints); changed[-1]['db']['story_choice_log'][0]['player_id'] = self.manifest['players']['C']
        with self.assertRaisesRegex(ValueError, 'personal ballot differs'):
            self.projection(checkpoints=changed)
        changed = deepcopy(self.checkpoints); changed[-1]['db']['story_nation_vote_log'][0]['actor_id'] = self.manifest['players']['B']
        with self.assertRaisesRegex(ValueError, 'national vote differs'):
            self.projection(checkpoints=changed)

    def test_disk_reward_ending_hash_and_full_api_history_must_agree(self):
        changed = deepcopy(self.checkpoints); changed[-1]['db']['facts'][0]['value'] = '999'
        with self.assertRaisesRegex(ValueError, 'Stopped honor ledger differs'):
            self.projection(checkpoints=changed)
        changed = deepcopy(self.checkpoints); changed[-1]['db']['outcomes'][0]['status'] = 'PENDING'
        with self.assertRaisesRegex(ValueError, 'stable local reward/ending receipts differ'):
            self.projection(checkpoints=changed)
        changed = deepcopy(self.checkpoints); ending = next(r for r in changed[-1]['db']['events'] if r['type'] == 'QE_PLAYER_FINISHED'); ending['hash'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'payload hash differs'):
            self.projection(checkpoints=changed)
        final = deepcopy(INSPECTOR.matched(self.fixture, 3, 'FINAL_CHECK_PASS')[-1]['value']); final['history'][0]['evidence'] += '; invented'
        with self.assertRaisesRegex(ValueError, 'Final API history differs'):
            self.projection(final=final)

    def test_retained_failed_attempt_still_cannot_be_reported_as_pass(self):
        # A narrow parser regression must not silently suppress retry21's real premature
        # check-offline-country diagnostic or invent the newer PRE_REPLAY marker.
        self.assertEqual(len(INSPECTOR.matched(self.fixture, 3, 'PRE_REPLAY_CHECK_PASS')), 0)
        finals = INSPECTOR.matched(self.fixture, 3, 'FINAL_CHECK_PASS')
        self.assertEqual(len(finals), 2)
        self.assertEqual(INSPECTOR.final_projection(finals[0]['value']), INSPECTOR.final_projection(finals[1]['value']))
        args = SimpleNamespace(expected_sha=self.manifest['plugin_sha256'], run_dir=self.retained_run, attempt='retry21')
        with self.assertRaisesRegex(ValueError, 'fixture FAILED record retained'):
            INSPECTOR.inspect(args)


class FreshWireContractTest(unittest.TestCase):
    """Offline packet models test rejection paths; they never become runtime PASS evidence."""
    @staticmethod
    def wire_rows(move_cursor=True):
        rows = []; sequence = 0; receive = 0; paper = 1; compass = 2; map_id = 3
        def emit(kind, **value):
            row = {'boot': 1, 'kind': kind, 'time': '2026-11-20T00:00:00Z', 'line': len(rows) + 1, 'value': {'boot': 1, 'player': 'C', **value}}; rows.append(row); return row
        emit('CRAFT_REQUESTED', item='map', output='map', outputId=map_id, before={'paper': 8, 'compass': 1})
        emit('CRAFT_GRID_REQUESTED', item='map', windowId=7, paperSlot=10, compassSlot=11, paperSlots=[1, 2, 3, 4, 6, 7, 8, 9], compassSlotInGrid=5, resultSlot=0)
        def item(kind, count):
            return {'type': kind, 'name': {paper: 'paper', compass: 'compass', map_id: 'map'}[kind], 'count': count}
        def snapshot(index):
            slots = [None] * 46; cursor = None
            if index <= 8:
                for slot in [1, 2, 3, 4, 6, 7, 8, 9][:index]: slots[slot] = item(paper, 1)
                slots[11] = item(compass, 1); cursor = item(paper, 8 - index) if index < 8 else None
            elif index <= 10:
                for slot in [1, 2, 3, 4, 6, 7, 8, 9]: slots[slot] = item(paper, 1)
                if index == 9: cursor = item(compass, 1)
                else: slots[5] = item(compass, 1); slots[0] = item(map_id, 1)
            elif index == 11 and move_cursor: cursor = item(map_id, 1)
            else: slots[12] = item(map_id, 1)
            return {'windowId': 7, 'stateId': 100 + index, 'slots': slots, 'cursor': cursor, 'receiveSequence': index + 1}
        descriptions = ['paper pickup'] + ['paper placement slot ' + str(slot) for slot in [1, 2, 3, 4, 6, 7, 8, 9]] + ['compass pickup', 'compass placement and actual map result', 'only result click']
        clicks = [(10, 0)] + [(slot, 1) for slot in [1, 2, 3, 4, 6, 7, 8, 9]] + [(11, 0), (5, 1), (0, 0)]
        if move_cursor: descriptions.append('already crafted map moved to inventory'); clicks.append((12, 0))
        for index, (description, (slot, button)) in enumerate(zip(descriptions, clicks)):
            sequence += 1; emit('CRAFT_CLIENT_PACKET_WRITE', writeSequence=sequence, packetName='window_click', packet={'windowId': 7, 'stateId': 90 + index, 'slot': slot, 'mouseButton': button, 'mode': 0})
            emit('CRAFT_SYNC_REQUEST', description=description, windowId=7, receiveSequence=receive, writeSequence=sequence)
            sequence += 1; emit('CRAFT_CLIENT_PACKET_WRITE', writeSequence=sequence, packetName='window_click', packet={'windowId': 7, 'stateId': -1, 'slot': -999, 'mouseButton': 2, 'mode': 5, 'changedSlots': []})
            value = snapshot(index); receive += 1
            def raw(value):
                return {'itemCount': 0} if value is None else {'itemCount': value['count'], 'itemId': value['type'], 'components': [], 'removeComponents': []}
            packet = {'windowId': 7, 'stateId': value['stateId'], 'items': list(map(raw, value['slots'])), 'carriedItem': raw(value['cursor'])}
            emit('CRAFT_SERVER_PACKET', receiveSequence=receive, packetName='window_items', packet=packet)
            emit('CRAFT_SERVER_WINDOW_CONFIRMED', description=description, snapshot=value)
            if index == 10: emit('CRAFT_RESULT_VERIFIED', item='map', slot=0, count=1, serverSnapshot=deepcopy(value))
            if index == 11 and move_cursor: emit('CRAFT_RESULT_ON_CURSOR', item='map', destinationSlot=12)
        emit('CRAFTED', item='map')
        return rows

    def test_each_real_action_has_one_separate_sync_and_raw_conservation_proof(self):
        for cursor in (False, True):
            result = INSPECTOR.check_map_wire_protocol(self.wire_rows(cursor))
            self.assertEqual(result['result_clicks'], 1)
            self.assertEqual(result['original_clicks'], 13 if cursor else 12)
            self.assertEqual(result['synchronization_requests'], result['raw_confirmations'])

    def test_local_prediction_cannot_replace_actual_raw_slot_or_cursor(self):
        rows = self.wire_rows(); confirmed = next(r for r in rows if r['kind'] == 'CRAFT_SERVER_WINDOW_CONFIRMED'); confirmed['value']['snapshot']['cursor']['count'] = 7
        with self.assertRaisesRegex(ValueError, 'Decoded slot type/count differs'):
            INSPECTOR.check_map_wire_protocol(rows)
        rows = self.wire_rows(); packet = next(r for r in rows if r['kind'] == 'CRAFT_SERVER_PACKET'); del packet['value']['packet']['carriedItem']
        with self.assertRaisesRegex(ValueError, 'Raw full-window schema'):
            INSPECTOR.check_map_wire_protocol(rows)

    def test_extra_recipe_result_click_or_missing_raw_response_is_rejected(self):
        rows = self.wire_rows(); duplicate = deepcopy(next(r for r in rows if r['kind'] == 'CRAFT_CLIENT_PACKET_WRITE' and r['value']['packet']['slot'] == 0)); rows.insert(-1, duplicate)
        with self.assertRaisesRegex(ValueError, 'write counts differ'):
            INSPECTOR.check_map_wire_protocol(rows)
        rows = self.wire_rows(); rows = [r for r in rows if not (r['kind'] == 'CRAFT_SERVER_PACKET' and r['value']['receiveSequence'] == 2)]
        with self.assertRaisesRegex(ValueError, 'packet sequence'):
            INSPECTOR.check_map_wire_protocol(rows)

    def test_consumed_claim_requires_no_material_and_exactly_one_map(self):
        for extra in ('paper', 'map'):
            rows = self.wire_rows()
            confirmed = next(r for r in rows if r['kind'] == 'CRAFT_SERVER_WINDOW_CONFIRMED' and r['value']['description'] == 'only result click')
            snapshot = confirmed['value']['snapshot']; packet = next(r for r in rows if r['kind'] == 'CRAFT_SERVER_PACKET' and r['value']['receiveSequence'] == snapshot['receiveSequence'])['value']['packet']
            if extra == 'paper':
                snapshot['slots'][13] = {'type': 1, 'name': 'paper', 'count': 1}; packet['items'][13] = {'itemId': 1, 'itemCount': 1}
            else:
                snapshot['cursor']['count'] = 2; packet['carriedItem']['itemCount'] = 2
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, 'conservation differs'):
                INSPECTOR.check_map_wire_protocol(rows)

    def test_original_click_cannot_masquerade_as_reviewed_no_action_sync(self):
        rows = self.wire_rows(); sync = next(r for r in rows if r['kind'] == 'CRAFT_CLIENT_PACKET_WRITE' and r['value']['packet']['slot'] == -999); sync['value']['packet']['slot'] = 0
        with self.assertRaisesRegex(ValueError, 'not the reviewed non-action'):
            INSPECTOR.check_map_wire_protocol(rows)

    def test_fresh_source_set_is_hash_bound_to_actual_saved_bytes_and_three_boots(self):
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory); rows = []; processes = {'boots': []}
            names = ('mineflayer_entry', 'mineflayer_inventory', 'prismarine_windows_entry', 'prismarine_windows_model', 'prismarine_item_entry', 'craft_wire')
            for boot in (1, 2, 3):
                base = run / 'evidence' / f'quest-evidence-client-runtime-used-boot{boot}'; base.mkdir(parents=True); sources = {}
                for name in names:
                    path = base / (name + '.cjs'); path.write_bytes(('offline-test-source:' + name).encode()); digest = hashlib.sha256(path.read_bytes()).hexdigest()
                    sources[name] = {'path': str(path), 'copy': str(path.relative_to(run)), 'sha256': digest}
                value = {'versions': {'mineflayer': 'offline-test', 'prismarine_windows': 'offline-test', 'prismarine_item': 'offline-test'}, 'sources': sources}; (base / 'runtime.json').write_text(json.dumps(value), encoding='utf-8')
                rows.append({'boot': boot, 'kind': 'CLIENT_RUNTIME_SOURCES', 'value': {'boot': boot, **value}})
                wire = run / 'evidence' / f'quest-evidence-wire-used-boot{boot}.cjs'; wire.write_bytes((base / 'craft_wire.cjs').read_bytes()); control = run / 'evidence' / f'quest-evidence-control-used-boot{boot}.py'; control.write_bytes(b'offline-control')
                processes['boots'].append({'boot': boot, 'client_wire_sha256': hashlib.sha256(wire.read_bytes()).hexdigest(), 'control_transport_sha256': hashlib.sha256(control.read_bytes()).hexdigest()})
            result = INSPECTOR.check_client_runtime_sources(run, rows, processes); self.assertEqual(set(result['fingerprints']), set(names))
            (run / 'evidence/quest-evidence-client-runtime-used-boot2/mineflayer_inventory.cjs').write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'runtime source bytes drifted'):
                INSPECTOR.check_client_runtime_sources(run, rows, processes)


if __name__ == '__main__':
    unittest.main()
