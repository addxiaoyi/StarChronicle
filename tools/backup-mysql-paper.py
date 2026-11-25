"""Back up a stopped isolated Paper run and native MySQL as one private verified bundle.

The full archive includes plugin configuration and must be treated as private.
Copy its printed SHA-256 independently before moving/restoring the archive.
"""
from pathlib import Path,PurePosixPath
import argparse
import hashlib
import json
import os
import stat
import tempfile
import zipfile
import mysql_recovery as R


def verify(path,expected):
    R.check(R.M.sha(path)==expected,'Backup bundle SHA-256 differs')
    with zipfile.ZipFile(path) as z:
        names=z.namelist();R.check(len(names)==len(set(names)),'Duplicate backup member')
        for item in z.infolist():
            p=PurePosixPath(item.filename)
            R.check(p.parts and str(p)==item.filename and not p.is_absolute() and '..' not in p.parts and '\\' not in item.filename and ':' not in item.filename and not stat.S_ISLNK(item.external_attr>>16),'Unsafe backup member')
        manifest=json.loads(z.read('backup-manifest.json'))
        R.check(manifest['format']==1 and manifest['consistency']=='STOPPED_GAME_AND_QUIESCENT_NATIVE_DATABASE','Invalid backup boundary')
        R.check({'preparation.json','server.properties','paper.jar','plugins/StarCore.jar','plugins/StarChronicle.jar',
                 'plugins/StarCore/config.yml','plugins/StarChronicle/chronicle.db','plugins/EpicCampaignPaperFixture/config.yml'}<=set(manifest['files']), 'Missing paired server/plugin data')
        R.check(set(manifest['database_files'])=={'native.sql','native.json'},'Missing native dump or snapshot')
        R.check(manifest['database_files']['native.sql']['bytes']>0,'Empty native SQL dump')
        for name in manifest['files']:
            p=PurePosixPath(name)
            R.check(p.parts and str(p)==name and not p.is_absolute() and '..' not in p.parts,'Unsafe manifest path')
        wanted={'backup-manifest.json','native.sql','native.json'}|{'server/'+n for n in manifest['files']}
        R.check(set(names)==wanted,'Backup member inventory differs')
        for name,proof in {**{'server/'+k:v for k,v in manifest['files'].items()},**manifest['database_files']}.items():
            raw=z.read(name);R.check(len(raw)==proof['bytes'] and hashlib.sha256(raw).hexdigest()==proof['sha256'],'Backup member differs: '+name)
        R.check(z.testzip() is None,'Corrupt backup member')
        native=json.loads(z.read('native.json'))
        R.check(native['storage']=='mysql' and native['database']==manifest['source_target']['database'] and native['tables'] and native['ddl'],'Native backup identity differs')
        R.check(manifest['source_preparation_sha256']==manifest['files']['preparation.json']['sha256'],'Source preparation hash differs')
    return manifest


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run',type=Path,required=True);p.add_argument('--instance',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();run=a.run.resolve();output=a.output.resolve()
    R.check(run.name.startswith('paper-epic-'),'Require isolated fixture run')
    R.private_location(output);R.check(not output.exists(),'Use a new private backup path outside Git')
    manifest,session=R.stopped(run);target=R.M.config(run,manifest);instance=R.read(a.instance)
    R.check(target['container_id']==instance['id'] and target['database'] in instance['databases'],'Wrong source instance')
    R.check(R.owned(instance)['Running'],'Source MySQL must be ready with Paper stopped')
    before=R.snapshot(target);before_files=R.files(run)
    output.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    R.check(output.parent.stat().st_mode & 0o077 == 0,'Backup directory must be private (0700)')
    temporary=output.with_name(output.name+'.incomplete')
    R.check(not temporary.exists(),'Prior incomplete backup retained')
    with tempfile.TemporaryDirectory(prefix='mysql-backup-',dir=output.parent) as tmp:
        tmp=Path(tmp);dump=tmp/'native.sql'
        command=['exec',instance['container'],'sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --user="$MYSQL_USER" --protocol=socket --single-transaction --routines --events --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF --skip-dump-date "$1"','backup',target['database']]
        with dump.open('xb') as stream:R.docker(*command,stdout=stream)
        R.check(dump.stat().st_size>0,'Empty native dump')
        after=R.snapshot(target);R.equal_data(before,after)
        R.check(R.files(run)==before_files,'Local server files changed during backup')
        R.stopped(run)
        R.write(tmp/'native.json',before)
        proof=dict(format=1,consistency='STOPPED_GAME_AND_QUIESCENT_NATIVE_DATABASE',source_run=str(run),source_preparation_sha256=R.M.sha(run/'preparation.json'),
                   stopped_session=session,source_target=target,source_snapshot_started_at=before['started_at'],source_snapshot_finished_at=after['finished_at'],
                   files=before_files,database_files={name:dict(bytes=(tmp/name).stat().st_size,sha256=R.M.sha(tmp/name)) for name in ('native.sql','native.json')},
                   private=True,limitations=['Offline coordinated backup; no live-server or multi-writer backup support.','External systems such as remote wallets require their own coordinated backup; only files and this native database are included.'])
        with open(temporary,'xb',opener=lambda path,flags:os.open(path,flags,0o600)) as f:
            with zipfile.ZipFile(f,'w',zipfile.ZIP_DEFLATED,compresslevel=1) as z:
                for name in before_files:z.write(run/name,'server/'+name)
                z.write(dump,'native.sql');z.write(tmp/'native.json','native.json');z.writestr('backup-manifest.json',json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
            f.flush();os.fsync(f.fileno())
        digest=R.M.sha(temporary);verify(temporary,digest)
        os.link(temporary,output);temporary.unlink()
        public=dict(status='VERIFIED_OFFLINE_BACKUP',archive=str(output),sha256=digest,bytes=output.stat().st_size,files=len(before_files),database=target['database'],
                    table_count=len(before['tables']),view_count=len(before['views']),source_server_uuid=before['server_uuid'],source_last_boot=session['boot'],manifest=proof)
        R.write(output.with_suffix('.manifest.json'),public)
        print(json.dumps({k:v for k,v in public.items() if k!='manifest'},ensure_ascii=False))


if __name__=='__main__':main()
