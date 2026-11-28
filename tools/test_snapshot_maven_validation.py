from __future__ import annotations

import importlib.util
import contextlib
import io
import json
import os
import shutil
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock


MODULE_PATH = Path(__file__).with_name('snapshot-maven-validation.py')
SPEC = importlib.util.spec_from_file_location('snapshot_maven_validation', MODULE_PATH)
validation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validation)


class ArtifactProvenanceTest(unittest.TestCase):
    def test_successful_tests_cannot_claim_existing_target_jars(self):
        log = '[INFO] --- surefire:3.5.2:test (default-test) ---\n[INFO] BUILD SUCCESS\n'
        self.assertFalse(validation.packaging_executed(log))

    def test_actual_maven_jar_goal_records_packaging(self):
        log = '[INFO] --- jar:3.4.2:jar (default-jar) @ starcore ---\n[INFO] BUILD SUCCESS\n'
        self.assertTrue(validation.packaging_executed(log))

    def test_sources_or_test_jar_alone_is_not_product_packaging(self):
        self.assertFalse(validation.packaging_executed(
            '[INFO] --- source:3.3.1:jar-no-fork ---\n[INFO] --- jar:3.4.2:test-jar ---'))


class SourceDriftTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.write('src/main/java/Example.java', 'original')
        self.write('src/main/resources/plugin.yml', 'original')
        self.write('src/test/java/ExampleTest.java', 'original')
        self.write('pom.xml', 'original')
        self.sources = {
            path.relative_to(self.repo).as_posix(): validation.digest(path)
            for path in self.repo.rglob('*')
            if path.is_file() and path.relative_to(self.repo).parts[0] == 'src'
        }
        self.inputs = {'pom.xml': validation.digest(self.repo / 'pom.xml')}

    def write(self, name, text):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def drift(self):
        return validation.find_source_drift(self.repo, self.sources, self.inputs)

    def test_unchanged_sources_and_inputs_have_no_drift(self):
        self.assertEqual([], self.drift())

    def test_added_and_deleted_sources_are_both_reported(self):
        (self.repo / 'src/test/java/ExampleTest.java').unlink()
        self.write('src/test/java/NewRecoveryTest.java', 'new')
        self.assertEqual(
            ['src/test/java/ExampleTest.java', 'src/test/java/NewRecoveryTest.java'],
            self.drift(),
        )

    def test_changed_java_and_non_java_resources_are_reported(self):
        self.write('src/main/java/Example.java', 'changed')
        self.write('src/main/resources/plugin.yml', 'changed')
        self.assertEqual(
            ['src/main/java/Example.java', 'src/main/resources/plugin.yml'], self.drift()
        )

    def test_added_optional_build_input_is_reported(self):
        self.write('docs/API.md', 'new public API')
        self.assertEqual(['docs/API.md'], self.drift())

    def test_removed_and_changed_build_inputs_are_reported(self):
        self.write('docs/API.md', 'original')
        self.inputs['docs/API.md'] = validation.digest(self.repo / 'docs/API.md')
        (self.repo / 'docs/API.md').unlink()
        self.write('pom.xml', 'changed')
        self.assertEqual(['docs/API.md', 'pom.xml'], self.drift())

    def test_target_and_unrelated_docs_are_not_build_input_drift(self):
        self.write('target/classes/Example.class', 'compiled output')
        self.write('docs/HANDOFF.md', 'progress')
        self.assertEqual([], self.drift())


class SnapshotReuseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.repo, self.prior, self.output = [self.root / name for name in ('repo', 'prior', 'new')]
        self.repo.mkdir()
        self.names = ('src/main/Example.java', 'src/test/ExampleTest.java', 'pom.xml')
        for name in self.names:
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('original-' + name)
            target = self.prior / 'source' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
        hashes = {name: validation.digest(self.repo / name) for name in self.names}
        (self.prior / 'command.json').write_text(json.dumps({
            'source_sha256': {k:v for k,v in hashes.items() if k.startswith('src/')},
            'build_input_sha256': {'pom.xml': hashes['pom.xml']}}))
        (self.prior / 'result.json').write_text('{"exit_code": 0}')

    def run_capture(self):
        argv = ['snapshot', '--repo', str(self.repo), '--output', str(self.output),
                '--java-home', str(self.root), '--reuse-snapshot', str(self.prior), '--', 'test']
        def process(*unused, **kwargs):
            kwargs['stdout'].write(b'[INFO] BUILD SUCCESS\n')
            return Mock(pid=123, wait=Mock(return_value=0))
        with patch('sys.argv', argv), patch.object(validation.subprocess, 'Popen', side_effect=process), \
             patch.object(validation.subprocess, 'check_output', return_value='a' * 40), \
             contextlib.redirect_stdout(io.StringIO()):
            return validation.main()

    def test_reuses_only_identical_frozen_files_and_checkout_edits_cannot_change_evidence(self):
        (self.repo / self.names[1]).write_text('new test')
        self.assertEqual(0, self.run_capture())
        captured = json.loads((self.output / 'command.json').read_text())
        self.assertEqual(2, captured['reused_files'])
        unchanged = self.output / 'source' / self.names[0]
        self.assertTrue(os.path.samefile(unchanged, self.prior / 'source' / self.names[0]))
        self.assertFalse(os.path.samefile(unchanged, self.repo / self.names[0]))
        self.assertFalse(os.path.samefile(self.output / 'source' / self.names[1], self.repo / self.names[1]))
        before = validation.digest(unchanged)
        (self.repo / self.names[0]).write_text('later checkout edit')
        self.assertEqual(before, validation.digest(unchanged))
        self.assertEqual('new test', (self.output / 'source' / self.names[1]).read_text())
        self.assertEqual('original-' + self.names[1], (self.prior / 'source' / self.names[1]).read_text())

    def test_corrupted_prior_snapshot_refuses_reuse_before_build(self):
        bad = self.prior / 'source' / self.names[0]
        bad.write_text('corrupted historical source')
        with self.assertRaisesRegex(RuntimeError, 'Prior snapshot changed'):
            self.run_capture()
        self.assertFalse((self.output / 'process.json').exists())
        self.assertEqual('corrupted historical source', bad.read_text())

    def test_unsupported_hard_links_copy_verified_independent_bytes(self):
        with patch.object(validation.os, 'link', side_effect=OSError('different volume')):
            self.assertEqual(0, self.run_capture())
        self.assertEqual(0, json.loads((self.output / 'command.json').read_text())['reused_files'])
        for name in self.names:
            target = self.output / 'source' / name
            self.assertFalse(os.path.samefile(target, self.prior / 'source' / name))
            self.assertEqual(validation.digest(target), validation.digest(self.repo / name))

    def test_active_snapshot_cannot_be_reused(self):
        (self.prior / 'result.json').write_text('{"status": "RUNNING"}')
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.run_capture()
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
