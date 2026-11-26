"""Verify current source-journal checks against damaged copies of actual combination evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import unittest


def module(name,filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
    value=importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


BASE=module('base_combination_tests','test_production_combination.py')
INSPECTOR=module('current_combination','inspect-current-combination.py')


class CurrentCombinationSourceEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        location=os.environ.get('STARCHRONICLE_COMBINATION_RUN')
        if not location: raise unittest.SkipTest('Requires completed real five-plugin evidence')
        cls.source=Path(location).resolve(); cls.original=INSPECTOR.inspect(cls.source)

    def setUp(self):
        BASE.ActualCombinationEvidenceTest.setUp(self)
        shutil.copytree(self.source/'plugins/StarCore/resource',self.run/'plugins/StarCore/resource')

    def reject(self):
        with self.assertRaises(AssertionError): INSPECTOR.inspect(self.run)

    def test_actual_current_source_and_epic_combination_pass(self):
        self.assertEqual(INSPECTOR.inspect(self.run),self.original)

    def test_missing_native_resource_receipt_rejected(self):
        next((self.run/'plugins/StarCore/resource/history').rglob('4.receipt')).unlink(); self.reject()

    def test_changed_complete_transfer_payload_rejected(self):
        with sqlite3.connect(self.run/'plugins/StarChronicle/chronicle.db') as c:
            row=c.execute('SELECT json FROM resource_history_receipts WHERE sequence=4').fetchone()
            data=json.loads(row[0]); data['balances'][0]['after']+=1
            c.execute('UPDATE resource_history_receipts SET json=? WHERE sequence=4',(json.dumps(data),))
        self.reject()

    def test_cursor_cannot_claim_more_than_source(self):
        with sqlite3.connect(self.run/'plugins/StarChronicle/chronicle.db') as c:
            c.execute("UPDATE committed_history_sources SET sequence=6 WHERE namespace='starcore.resources'")
        self.reject()

    def test_aid_cannot_invent_factory_processing(self):
        with sqlite3.connect(self.run/'plugins/StarCore/starcore.db') as c:
            c.execute("UPDATE starcore_resource_state SET property_value='1' WHERE property_key='processing.history.sequence'")
        self.reject()

    def test_source_failure_warning_is_not_passing(self):
        path=self.run/'evidence/boot2.server.log'
        path.write_text(path.read_text()+'\nResource history scan retained its cursor: unavailable\n'); self.reject()


if __name__=='__main__': unittest.main()
