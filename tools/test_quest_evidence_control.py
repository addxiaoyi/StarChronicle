"""No-JVM regressions for owned control transport and one-shot runtime coordination."""
from pathlib import Path
import importlib.util
import json
import os
import queue
import sys
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch

TOOLS=Path(__file__).resolve().parent
sys.path.insert(0,str(TOOLS))
import quest_evidence_control as control
SPEC=importlib.util.spec_from_file_location('quest_evidence_owner',TOOLS/'run-quest-evidence-acceptance.py')
OWNER=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(OWNER)


class ControlProtocolTest(unittest.TestCase):
    def test_sequence_accepts_only_next_integer(self):
        self.assertEqual(control.decode_command('{"sequence":1,"command":"server stop"}',1),'server stop')
        for sequence in (0,2,True,'1',None):
            with self.subTest(sequence=sequence),self.assertRaises(ValueError):
                control.decode_command(json.dumps({'sequence':sequence,'command':'status'}),1)

    def test_duplicate_json_keys_and_extra_keys_rejected(self):
        for row in ('{"sequence":1,"sequence":1,"command":"status"}',
                    '{"sequence":1,"command":"status","other":0}','[]','null'):
            with self.subTest(row=row),self.assertRaises(ValueError):control.decode_command(row,1)

    def test_invalid_command_and_line_limits(self):
        for command in ('','a\nb','a\rb','a\0b','a'*4097,42):
            with self.subTest(command=str(command)[:20]),self.assertRaises(ValueError):
                control.decode_command(json.dumps({'sequence':1,'command':command}),1)
        with self.assertRaises(ValueError):control.decode_command(' '*8193,1)

    def test_process_creation_identity_is_live_and_absent_is_none(self):
        identity=control.process_identity(os.getpid())
        self.assertIsInstance(identity,str);self.assertEqual(identity,control.process_identity(os.getpid()))
        self.assertIsNone(control.process_identity(2**30))

    def watcher(self,path,identity=None):
        events=queue.Queue();stopped=threading.Event()
        thread=threading.Thread(target=control.watch_commands,args=(path,events,stopped,os.getpid(),identity or control.process_identity(os.getpid())))
        thread.start();self.addCleanup(lambda:(stopped.set(),thread.join(timeout=2)))
        return events,stopped,thread

    def test_partial_utf8_command_waits_until_complete_newline(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'control.jsonl';path.touch();events,stopped,thread=self.watcher(path)
            line=json.dumps({'sequence':1,'command':'client inspect 历史'},ensure_ascii=False).encode('utf-8')
            cut=line.index('历'.encode('utf-8'))+1
            with path.open('ab') as stream:stream.write(line[:cut]);stream.flush()
            with self.assertRaises(queue.Empty):events.get(timeout=.15)
            with path.open('ab') as stream:stream.write(line[cut:]+b'\n');stream.flush()
            self.assertEqual(events.get(timeout=2),('input','client inspect 历史'))
            stopped.set();thread.join(timeout=2)

    def test_duplicate_sequence_stops_reader_before_dependent_command(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'control.jsonl'
            path.write_text('{"sequence":1,"command":"status"}\n{"sequence":1,"command":"server stop"}\n{"sequence":2,"command":"client craft A paper"}\n',encoding='utf-8')
            events,stopped,thread=self.watcher(path)
            self.assertEqual(events.get(timeout=2),('input','status'))
            kind,error=events.get(timeout=2);self.assertEqual(kind,'control-error');self.assertIn('sequence',error)
            thread.join(timeout=2);self.assertTrue(events.empty())

    def test_retired_owner_emits_failure_without_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'control.jsonl';path.write_text('{"sequence":1,"command":"status"}\n')
            events,stopped,thread=self.watcher(path,identity='retired-owner-identity')
            kind,error=events.get(timeout=2);self.assertEqual(kind,'control-error');self.assertIn('retired',error)
            thread.join(timeout=2);self.assertTrue(events.empty())

    def test_truncation_is_a_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'control.jsonl';path.write_text('{"sequence":1,"command":"status"}\n')
            events,stopped,thread=self.watcher(path);self.assertEqual(events.get(timeout=2),('input','status'))
            path.write_bytes(b'')
            kind,error=events.get(timeout=2);self.assertEqual(kind,'control-error');self.assertIn('truncated',error)
            stopped.set();thread.join(timeout=2)


class CoordinatorOneShotTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup)
        self.run=Path(self.temporary.name);(self.run/'evidence').mkdir()
        args=SimpleNamespace(run_dir=self.run)
        self.boot=OWNER.Boot(args,2,{'boots':[]})
        self.boot.process=SimpleNamespace(poll=lambda:None)
        self.boot.control.touch();self.boot.command_log.touch()

    def fixture(self,boot,kind,**fields):
        path=self.run/'evidence/quest-fixture.jsonl'
        with path.open('a',encoding='utf-8') as stream:stream.write(json.dumps({'boot':boot,'kind':kind,'value':fields})+'\n')

    def client(self,boot,kind,**fields):
        path=self.run/'evidence/quest-evidence-clients.log'
        with path.open('a',encoding='utf-8') as stream:stream.write('2026-11-20T00:00:00Z '+kind+' '+json.dumps({'boot':boot,**fields})+'\n')

    def test_complete_log_ignores_partial_multibyte_trailing_record(self):
        path=self.run/'partial.log';encoded='历史'.encode('utf-8');path.write_bytes(b'first\n'+encoded[:1])
        self.assertEqual(OWNER.complete_lines(path),['first'])
        path.write_bytes(b'first\n'+encoded+b'\n');self.assertEqual(OWNER.complete_lines(path),['first','历史'])

    def test_old_same_boot_and_old_boot_markers_cannot_acknowledge_new_action(self):
        self.fixture(1,'ACK',player='A');self.fixture(2,'ACK',player='A')
        def only_old_markers(predicate,*args,**kwargs):
            self.boot.refresh();self.assertFalse(predicate());raise TimeoutError('No fresh actual marker')
        with patch.object(self.boot,'wait',side_effect=only_old_markers),self.assertRaises(TimeoutError):
            self.boot.action('server qeverify probe','ACK',player='A')
        self.assertEqual(len(self.boot.control.read_text().splitlines()),1)
        command=json.loads(self.boot.command_log.read_text());self.assertEqual(command['after_lines']['fixture'],2)
        with patch.object(self.boot,'wait'),self.assertRaisesRegex(RuntimeError,'resend'):
            self.boot.action('server qeverify probe','ACK',player='A')
        self.assertEqual(len(self.boot.control.read_text().splitlines()),1)

    def test_only_post_send_current_boot_marker_acknowledges(self):
        self.fixture(1,'ACK',player='A')
        def actual_new_marker(predicate,*args,**kwargs):
            self.fixture(2,'ACK',player='A');self.boot.refresh();self.assertTrue(predicate())
        with patch.object(self.boot,'wait',side_effect=actual_new_marker):
            cursor=self.boot.action('server qeverify probe','ACK',player='A')
        self.assertEqual(cursor['fixture'],1)
        self.assertEqual(json.loads(self.boot.control.read_text())['sequence'],1)

    def test_duplicate_acknowledgements_fail(self):
        def duplicate_markers(predicate,*args,**kwargs):
            self.fixture(2,'ACK',player='A');self.fixture(2,'ACK',player='A');self.boot.refresh();predicate()
        with patch.object(self.boot,'wait',side_effect=duplicate_markers),self.assertRaisesRegex(RuntimeError,'Duplicate acknowledgement'):
            self.boot.action('server qeverify probe','ACK',player='A')

    def test_fixture_failure_blocks_send(self):
        self.fixture(2,'FAILED',operation='real action')
        with self.assertRaisesRegex(RuntimeError,'fixture FAILED'):self.boot.send('client craft A paper')
        self.assertEqual(self.boot.control.read_bytes(),b'');self.assertEqual(self.boot.command_log.read_bytes(),b'')

    def test_client_failure_blocks_send(self):
        self.client(2,'ACTION_FAILED',line='craft A paper')
        with self.assertRaisesRegex(RuntimeError,'client failure'):self.boot.send('client craft A paper')
        self.assertEqual(self.boot.control.read_bytes(),b'')

    def test_failed_delivery_intent_is_never_resent(self):
        real_open=Path.open
        def failed_control(path,*args,**kwargs):
            if path==self.boot.control and args and args[0]=='a':raise OSError('simulated ambiguous delivery')
            return real_open(path,*args,**kwargs)
        with patch.object(Path,'open',failed_control),self.assertRaises(OSError):self.boot.send('client craft A paper')
        self.assertEqual(len(self.boot.command_log.read_text().splitlines()),1)
        with self.assertRaisesRegex(RuntimeError,'resend'):self.boot.send('client craft A paper')
        self.assertEqual(self.boot.control.read_bytes(),b'')

    def test_durable_message_wait_failure_stops_receipt_and_other_completions(self):
        (self.run/'preparation.json').write_text(json.dumps({'native_quests':{'heir':{'name':'继承证据的抄录'}}}),encoding='utf-8')
        attempted=[]
        def action(command,*args,**kwargs):attempted.append(command);return {'fixture':0,'clients':0,'output':0}
        with patch.object(self.boot,'action',side_effect=action),patch.object(self.boot,'wait',side_effect=TimeoutError('durable confirmation missing')),self.assertRaises(TimeoutError):self.boot.boot_two()
        self.assertEqual(attempted[-1],'client native-complete A')
        self.assertFalse(any('check-receipt' in row or 'native-complete B' in row for row in attempted))

    def test_read_only_poll_has_distinct_sequences_and_fresh_cursors(self):
        count=0
        def state_response(predicate,*args,**kwargs):
            nonlocal count
            count+=1
            self.fixture(2,'STATE',player='A',story=OWNER.STORIES['nation'],gates={'ready':count==2},visibleChoices=['yes'] if count==2 else [])
            self.boot.refresh();self.assertTrue(predicate())
        with patch.object(self.boot,'wait',side_effect=state_response),patch.object(OWNER.time,'sleep'):
            result=self.boot.poll_read_only_state('A','nation',lambda state:state['gates']['ready'])
        self.assertTrue(result['gates']['ready'])
        commands=[json.loads(line) for line in self.boot.command_log.read_text().splitlines()]
        self.assertEqual([row['sequence'] for row in commands],[1,2])
        self.assertEqual([row['after_lines']['fixture'] for row in commands],[0,1])
        self.assertTrue(all(row['policy']=='READ_ONLY_OBSERVATION' for row in commands))

    def test_read_only_poll_rejects_every_mutation_route(self):
        for command in ('client craft A paper','server qeverify begin player','server qeverify replay player','server stop'):
            with self.subTest(command=command),self.assertRaises(ValueError):
                self.boot.send(command,read_only_observation=True)
        self.assertEqual(self.boot.control.read_bytes(),b'')

    def test_native_admission_hint_requires_exact_identity_and_active(self):
        path=self.run/'native.properties';binding='a'*64
        def write(player='p',quest='q',status='ACTIVE'):
            row={'snapshot':{'bindingId':binding,'playerId':player,'questId':quest,'status':status}}
            path.write_text(binding+'='+json.dumps(row).replace(':',r'\:')+'\n',encoding='utf-8')
        write();self.assertEqual(len(OWNER.native_admissions(path,'p','q')),1)
        self.assertEqual(OWNER.native_admissions(path,'other','q'),[])
        write(status='COMMITTED');self.assertEqual(OWNER.native_admissions(path,'p','q'),[])
        path.write_bytes((binding+'={"snap').encode());self.assertEqual(OWNER.native_admissions(path,'p','q'),[])


if __name__=='__main__':unittest.main()
