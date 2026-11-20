"""Non-JVM regression tests for the independent exploration owner and response cursors."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import sys
from types import SimpleNamespace
from unittest.mock import Mock,patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
SPEC=importlib.util.spec_from_file_location('exploration_owner',ROOT/'tools/run-exploration-acceptance.py')
OWNER=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(OWNER)


def row(boot,kind,value=None):return {'boot':boot,'kind':kind,'time':'2026-11-20T00:00:00Z','value':value or {}}


class ExplorationOwnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.run=Path(self.temporary.name);(self.run/'evidence').mkdir()
        self.args=SimpleNamespace(run_dir=self.run,platform='paper',attempt='retry-test',sha='a'*64,java=Path('unused-java'),node=Path('unused-node'))
        self.owner={'source_sha256':{},'boots':[]};self.boot=OWNER.Boot(self.args,1,self.owner)
    def tearDown(self):self.temporary.cleanup()
    def test_partial_utf8_and_unfinished_line_wait_for_a_real_complete_record(self):
        path=self.run/'evidence/fixture.jsonl';tail=OWNER.JsonLines(path)
        encoded=(json.dumps(row(1,'READY',{'text':'真实到访'}),ensure_ascii=False)+'\n').encode('utf-8');split=encoded.index('真'.encode('utf-8'))+1
        path.write_bytes(encoded[:split]);tail.refresh();self.assertEqual(tail.rows,[])
        with path.open('ab') as output:output.write(encoded[split:-1])
        tail.refresh();self.assertEqual(tail.rows,[])
        with path.open('ab') as output:output.write(b'\n')
        tail.refresh();self.assertEqual(len(tail.rows),1);self.assertEqual(tail.rows[0]['value']['text'],'真实到访')
    def test_truncated_or_replaced_evidence_is_rejected(self):
        path=self.run/'evidence/fixture.jsonl';path.write_text(json.dumps(row(1,'READY'))+'\n',encoding='utf-8');tail=OWNER.JsonLines(path);tail.refresh()
        path.write_bytes(b'');self.assertRaisesRegex(RuntimeError,'replaced or truncated',tail.refresh)
        replacement=self.run/'other';replacement.write_text(json.dumps(row(1,'READY'))+'\n',encoding='utf-8');replacement.replace(path)
        self.assertRaisesRegex(RuntimeError,'replaced or truncated',tail.refresh)
    def test_action_ignores_old_boots_and_precommand_acknowledgements(self):
        self.boot.fixture.rows=[{**row(1,'CHECK_PASS',{'phase':'final'}),'cursor':0},{**row(2,'CHECK_PASS',{'phase':'final'}),'cursor':1}]
        self.boot.send=Mock(return_value={'fixture':2,'client':0})
        def wait(predicate,description):
            self.assertFalse(predicate());self.boot.fixture.rows.append({**row(1,'CHECK_PASS',{'phase':'final'}),'cursor':2});self.assertTrue(predicate())
        self.boot.wait=wait;self.boot.action('server exploreverify check final','CHECK_PASS',phase='final')
        self.boot.send.assert_called_once_with('server exploreverify check final')
    def test_duplicate_aftercommand_acknowledgements_reject_instead_of_retrying(self):
        self.boot.send=Mock(return_value={'fixture':0,'client':0})
        self.boot.fixture.rows=[{**row(1,'CHECK_PASS',{'phase':'final'}),'cursor':index} for index in (0,1)]
        self.boot.wait=lambda predicate,description:predicate()
        self.assertRaisesRegex(RuntimeError,'Duplicate actual acknowledgement',self.boot.action,'server exploreverify check final','CHECK_PASS',phase='final')
        self.boot.send.assert_called_once()
    def test_failed_fixture_blocks_normal_commands_but_abort_still_signals_owned_session(self):
        self.boot.process=Mock(pid=123);self.boot.process.poll.return_value=None;self.boot.identity='created-123';self.boot.control.write_text('',encoding='utf-8')
        self.boot.fixture.rows=[{**row(1,'FAILED',{'error':'real failure'}),'cursor':0}]
        with patch.object(OWNER,'process_identity',return_value='created-123'):
            self.assertRaisesRegex(RuntimeError,'fixture FAILED',self.boot.send,'server exploreverify check final')
            self.assertEqual(self.boot.control.read_text(encoding='utf-8'),'');self.boot.abort()
        commands=[json.loads(line) for line in self.boot.control.read_text(encoding='utf-8').splitlines()]
        self.assertEqual(len(commands),1);self.assertEqual(commands[0]['sequence'],1);self.assertTrue(commands[0]['command'].startswith('abort '))
        self.boot.process.wait.assert_called_once_with(timeout=115)
    def test_reused_pid_cannot_receive_commands(self):
        self.boot.process=Mock(pid=123);self.boot.process.poll.return_value=None;self.boot.identity='original';self.boot.control.write_text('',encoding='utf-8');self.boot.refresh=Mock()
        with patch.object(OWNER,'process_identity',return_value='replacement'):
            self.assertRaisesRegex(RuntimeError,'retired or replaced',self.boot.send,'client walk')
        self.assertEqual(self.boot.control.read_text(encoding='utf-8'),'')
    def test_fixed_protocol_preserves_paper_cancel_and_folia_unsupported_boundary(self):
        for platform in ('paper','folia'):
            self.args.platform=platform;boot=OWNER.Boot(self.args,1,self.owner);commands=[]
            def action(command,event_kind,client=False,**fields):commands.append(command);return {'fixture':0,'client':0}
            boot.action=action;boot.settle=Mock();boot.matches=Mock(return_value=[row(1,'TELEPORT_CANCELLED')]);boot.first()
            self.assertEqual('server exploreverify teleport cancel' in commands,platform=='paper')
            self.assertEqual('server exploreverify check cancelled' in commands,platform=='paper')
            self.assertEqual(commands.count('client walk'),1);self.assertEqual(commands.count('client choose'),2)
            self.assertEqual(commands.count('server exploreverify check final'),1)
        boot=OWNER.Boot(self.args,2,self.owner);commands=[];boot.action=lambda command,event_kind,client=False,**fields:commands.append(command);boot.settle=Mock();boot.second()
        self.assertEqual(commands.count('server exploreverify check final'),2);self.assertEqual(commands.count('client walk'),1)


if __name__=='__main__':unittest.main()
