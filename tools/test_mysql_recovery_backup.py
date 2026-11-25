"""Archive boundary tests: refuse partial pairs, traversal, aliases, corrupt and forged members."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest
import warnings
import zipfile

S=importlib.util.spec_from_file_location('mysql_backup',Path(__file__).with_name('backup-mysql-paper.py'));B=importlib.util.module_from_spec(S);S.loader.exec_module(B)


class BackupBoundaryTest(unittest.TestCase):
    def setUp(self):
        tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup);self.path=Path(tmp.name)/'backup.zip'
        self.files={n:b'test-content' for n in ('preparation.json','server.properties','paper.jar','plugins/StarCore.jar','plugins/StarChronicle.jar','plugins/StarCore/config.yml','plugins/StarChronicle/chronicle.db','plugins/EpicCampaignPaperFixture/config.yml')}
        self.db={'native.sql':b'CREATE TABLE t (id INT);','native.json':json.dumps(dict(storage='mysql',database='starcore_paper_test',tables=[dict(name='t')],ddl={'t':'CREATE TABLE t (id INT)'})).encode()}

    def build(self,alter=lambda p:None,extras=None,omit=()):
        describe=lambda d:{n:dict(bytes=len(v),sha256=hashlib.sha256(v).hexdigest()) for n,v in d.items()}
        proof=dict(format=1,consistency='STOPPED_GAME_AND_QUIESCENT_NATIVE_DATABASE',files=describe(self.files),database_files=describe(self.db),source_target=dict(database='starcore_paper_test'),source_preparation_sha256=hashlib.sha256(self.files['preparation.json']).hexdigest())
        alter(proof)
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',UserWarning)
            with zipfile.ZipFile(self.path,'w') as z:
                for n,v in {**{'server/'+n:v for n,v in self.files.items()},**self.db,'backup-manifest.json':json.dumps(proof).encode()}.items():
                    if n not in omit:z.writestr(n,v)
                for n,v in extras or []:z.writestr(n,v)
        return B.R.M.sha(self.path)

    def reject(self,pattern,**kwargs):
        digest=self.build(**kwargs)
        with self.assertRaisesRegex((AssertionError,KeyError),pattern):B.verify(self.path,digest)

    def test_valid_complete_pair(self):self.assertEqual(B.verify(self.path,self.build())['format'],1)
    def test_wrong_transport_digest(self):
        self.build()
        with self.assertRaisesRegex(AssertionError,'SHA-256'):B.verify(self.path,'0'*64)
    def test_payload_hash_mismatch(self):self.reject('member differs',alter=lambda p:p['files']['paper.jar'].update(sha256='0'*64))
    def test_payload_size_mismatch(self):self.reject('member differs',alter=lambda p:p['files']['paper.jar'].update(bytes=999))
    def test_missing_chronicle(self):self.reject('paired',alter=lambda p:p['files'].pop('plugins/StarChronicle/chronicle.db'),omit=['server/plugins/StarChronicle/chronicle.db'])
    def test_missing_native_dump(self):self.reject('dump or snapshot',alter=lambda p:p['database_files'].pop('native.sql'),omit=['native.sql'])
    def test_empty_native_dump(self):
        self.db['native.sql']=b'';self.reject('Empty native SQL')
    def test_unlisted_member(self):self.reject('inventory',extras=[('unexpected',b'x')])
    def test_missing_listed_world(self):self.reject('inventory',alter=lambda p:p['files'].update({'world/level.dat':dict(bytes=1,sha256='0'*64)}))
    def test_duplicate_member(self):self.reject('Duplicate',extras=[('native.sql',b'evil')])
    def test_traversal(self):self.reject('Unsafe',extras=[('server/../../evil',b'x')])
    def test_absolute_path(self):self.reject('Unsafe',extras=[('/tmp/evil',b'x')])
    def test_windows_separator(self):self.reject('Unsafe',extras=[('server\\..\\evil',b'x')])
    def test_windows_drive(self):self.reject('Unsafe',extras=[('C:/evil',b'x')])
    def test_dot_alias(self):self.reject('Unsafe',extras=[('server/./paper.jar',b'x')])
    def test_repeated_separator(self):self.reject('Unsafe',extras=[('server//paper.jar',b'x')])
    def test_manifest_absolute_path(self):self.reject('Unsafe manifest',alter=lambda p:p['files'].update({'/evil':dict(bytes=1,sha256='0'*64)}))
    def test_manifest_parent_path(self):self.reject('Unsafe manifest',alter=lambda p:p['files'].update({'../evil':dict(bytes=1,sha256='0'*64)}))
    def test_symlink(self):
        i=zipfile.ZipInfo('server/link');i.create_system=3;i.external_attr=(stat.S_IFLNK|0o777)<<16
        self.reject('Unsafe',extras=[(i,b'/etc/passwd')])
    def test_wrong_catalog(self):self.reject('identity',alter=lambda p:p['source_target'].update(database='other'))
    def test_wrong_consistency(self):self.reject('boundary',alter=lambda p:p.update(consistency='LIVE_WITHOUT_COORDINATION'))
    def test_unpaired_preparation(self):self.reject('preparation hash',alter=lambda p:p.update(source_preparation_sha256='0'*64))


if __name__=='__main__':unittest.main()
