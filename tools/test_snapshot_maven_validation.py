from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name('snapshot-maven-validation.py')
SPEC = importlib.util.spec_from_file_location('snapshot_maven_validation', MODULE_PATH)
validation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validation)


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


if __name__ == '__main__':
    unittest.main()
