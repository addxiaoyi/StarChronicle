"""Package public restore/crash proofs; exclude full backups, SQL dumps and private credentials."""
from datetime import datetime,timezone
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
import yaml

ROOT=Path(__file__).resolve().parents[1]
S=importlib.util.spec_from_file_location('recovery_inspector',ROOT/'tools/inspect-mysql-recovery.py');I=importlib.util.module_from_spec(S);S.loader.exec_module(I)


def git(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
def digest(data):return hashlib.sha256(data).hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('run','failed-run','instance','log','regression-log'):p.add_argument('--'+name,type=Path,action='append',default=[])
    p.add_argument('--native',type=Path,required=True);p.add_argument('--private-dir',type=Path,required=True);p.add_argument('--source-instance',type=Path,required=True);p.add_argument('--label',default='mysql-recovery-20261125');a=p.parse_args()
    I.check(re.fullmatch('[a-z0-9-]+',a.label) and not git(ROOT,'status','--porcelain'),'Commit tooling/report before archiving')
    runs=[r.resolve() for r in a.run];I.check(len(runs)==len(set(runs))==5,'Require exactly five accepted runs')
    accepted=[I.inspect(r,artifacts=True) for r in runs];I.check({r['kind'] for r in accepted}=={'production','production-offline','trade','DUNGEON','WAR'},'Missing recovery scenario')
    product=dict(chronicle='2f800988fbdd99447974e891a0623b39d7ed028a',native='59ed52abbed5521eff6383df004985b1e13e1bbe')
    I.check(not git(ROOT,'diff',product['chronicle'],'HEAD','--','src','pom.xml'),'Chronicle product source changed')
    I.check(not git(a.native,'diff',product['native'],'HEAD','--','src/main','pom.xml'),'Native product source changed')
    regressions=[]
    for log in a.regression_log:
        content=log.read_text();m=re.search(r'Ran (\d+) tests? in ',content)
        I.check(m and re.search(r'\nOK\s*$',content) and 'FAILED (' not in content,'Regression not fully passed: '+str(log))
        regressions.append(dict(log=log.name,tests=int(m[1]),sha256=I.sha(log)))
    I.check(sum(r['tests'] for r in regressions)>=192,'Missing backup/recovery regression results')
    files={};redacted=[];secret_values=[]
    def add(path,name,redaction_manifest=None,relative=None):
        I.check(path.is_file() and not path.is_symlink() and name not in files,'Unsafe/duplicate archive source: '+str(path))
        data=path.read_bytes();original_digest=digest(data)
        if path.suffix in ('.yml','.yaml'):
            def replace(match):
                if match[2].strip() in ('',"''",'""','~','null'):return match[0]
                value=match[2].strip().strip('"\'')
                if value!='REDACTED_TEST_SECRET':secret_values.append(value.encode())
                return match[1]+' "REDACTED_TEST_SECRET"'
            data=re.sub(r'(?m)^(\s*(?:access-secret|password|api-key|mineskin_api_key):)([^\n]*)$',replace,data.decode()).encode()
        if digest(data)!=original_digest:
            redacted.append(name)
            if redaction_manifest is not None:
                I.check(relative in ('plugins/StarCore/config.yml','evidence/recovery/source-config.yml'),'Unexpected accepted-run private configuration')
                redaction_manifest[relative]=dict(original_sha256=original_digest,redacted_sha256=digest(data),fields=['map.web.access-secret'])
        files[name]=data
    for run in [*runs,*[r.resolve() for r in a.failed_run]]:
        paths=[run/'preparation.json',run/'server.properties']
        paths += [x for x in (run/'evidence').rglob('*') if x.is_file() and '__pycache__' not in x.parts]
        paths += [x for x in (run/'fixture').glob('*') if x.is_file() and x.suffix in ('.java','.yml','.json')]
        for plugin in ('StarCore','StarChronicle','EpicCampaignPaperFixture','Essentials'):
            parent=run/'plugins'/plugin
            paths += [x for x in parent.rglob('*') if x.is_file() and (x.suffix in ('.db','.yml','.json','.properties','.bak') or x.name in ('authority-domain.txt','chronicle.db-wal','chronicle.db-shm') or 'quest' in x.relative_to(parent).parts)]
        paths += [x for x in (run/'crash-reports').glob('*.txt')]
        if (run/'restore-incomplete.json').exists():paths.append(run/'restore-incomplete.json')
        redactions={}
        for path in sorted(set(paths)):
            relative=path.relative_to(run).as_posix();add(path,'runs/'+run.name+'/'+relative,redactions if run in runs else None,relative)
        if run in runs:files['runs/'+run.name+'/public-redactions.json']=(json.dumps(redactions,indent=2)+'\n').encode()
        if run in runs:
            output=ROOT/'target'/('recovery-'+run.name)
            for path in sorted(output.glob('*')):
                if path.is_file():add(path,'runtime/'+run.name+'/'+path.name)
    instances=[]
    for line in (a.source_instance.parent/'mysql.env').read_text().splitlines():
        if line.startswith(('MYSQL_PASSWORD=','MYSQL_ROOT_PASSWORD=')):secret_values.append(line.split('=',1)[1].encode())
    for path in a.instance:
        metadata=I.read(path);shutdown=I.read(path.parent/'recovery-final-shutdown.json')
        I.check(shutdown['container_id']==metadata['id'] and not shutdown['state']['Running'] and shutdown['state']['ExitCode']==0,'Recovery container not normally stopped')
        instances.append(dict(metadata=metadata,shutdown=shutdown))
        for f in path.parent.iterdir():
            if f.is_file() and f.suffix in ('.json','.log'):add(f,'instances/'+path.parent.name+'/'+f.name)
        env=path.parent/'mysql.env'
        for line in env.read_text().splitlines():
            if line.startswith(('MYSQL_PASSWORD=','MYSQL_ROOT_PASSWORD=')):secret_values.append(line.split('=',1)[1].encode())
    for path in a.private_dir.glob('*.manifest.json'):add(path,'private-backup-proofs/'+path.name)
    add(a.private_dir/'source-maintenance.json','source-maintenance.json')
    for path in (ROOT/'target/mysql-recovery-preflight').iterdir():
        if path.is_file():add(path,'preflight/'+path.name)
    for path in dict.fromkeys([*a.log,*a.regression_log]):add(path,'logs/'+path.name)
    for path in sorted((ROOT/'tools').iterdir()):
        if path.is_file() and path.suffix in ('.py','.java','.cjs'):add(path,'tools/'+path.name)
    # The independent inspector pins its baseline to this archive. Include it so
    # verification after extraction does not depend on the author's worktree.
    add(ROOT/'docs/verification/mysql-paper-20261125-evidence.zip','docs/verification/mysql-paper-20261125-evidence.zip')
    summary=dict(status='FIVE_PAPER_MYSQL_OFFLINE_RESTORES_AND_POST_REPLAY_PROCESS_RECOVERIES_ACCEPTED',captured_utc=datetime.now(timezone.utc).isoformat(),chronicle_head=git(ROOT,'rev-parse','HEAD'),native_head=git(a.native,'rev-parse','HEAD'),product_sources=product,product_rebuilt=False,
        artifacts={k:I.read(runs[0]/'preparation.json')[k] for k in ('plugin_sha256','starcore_sha256')},runs=[dict(directory=r.name,**result) for r,result in zip(runs,accepted)],regressions=regressions,
        instances=instances,failed_runs=[r.name for r in a.failed_run],redacted_files=redacted,prior_accepted_archive_sha256=I.PRIOR_SHA,
        totals=dict(restored_files=sum(r['restored_files'] for r in accepted),accepted_game_boots=15,normal_process_exits=25,killed_game_processes=5,killed_database_processes=5,database_restarts=5,actual_commands=sum(r['actual_commands'] for r in accepted),regression_checks=sum(r['tests'] for r in regressions),preflight_refusals=3),
        limitations=['Full backups include secrets/worlds/configuration and stay private; this public archive cannot replace them.','One normal restored replay, post-replay JVM then MySQL SIGKILL, and normal recovered replay for each scenario; no in-flight transaction, physical power loss or full filesystem durability claim.','Source/raw restored DDL are retained; only verified implicit/explicit utf8mb4 column declarations are canonicalized.','Ordinary native save revision counters and Chronicle pending-trigger scan counters may advance across gameplay; assets, actors, receipt/settlement/history content are exact.','Selected frozen Paper stack only; no Folia, remote certificate identity, old factory/wallet migration or long-term capacity acceptance.'])
    files['source-provenance.json']=(json.dumps(summary,ensure_ascii=False,indent=2)+'\n').encode()
    I.check(all(secret not in data for secret in set(secret_values) if secret for data in files.values()),'Private credential found in public evidence')
    output=ROOT/'target'/a.label;output.mkdir(parents=True,exist_ok=False)
    archive=output/'evidence.zip';inventory={n:digest(v) for n,v in files.items()}
    with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED) as z:
        for name,data in files.items():z.writestr(name,data)
        z.writestr('evidence-manifest.json',json.dumps(inventory,indent=2)+'\n')
    with zipfile.ZipFile(archive) as z:
        I.check(z.testzip() is None and all(digest(z.read(n))==h for n,h in inventory.items()),'Public archive hash mismatch')
        with tempfile.TemporaryDirectory(prefix='recovery-public-replay-') as tmp:
            z.extractall(tmp)
            public=[]
            for run in runs:
                result=subprocess.run([sys.executable,str(Path(tmp)/'tools/inspect-mysql-recovery.py'),str(Path(tmp)/'runs'/run.name),'--public-redacted'],capture_output=True,text=True)
                I.check(result.returncode==0,'Extracted independent inspector failed: '+result.stderr)
                public.append(json.loads(result.stdout))
            I.check(all(r['status']==summary['runs'][0]['status'] for r in public),'Public recovery replay failed')
    for plugin in ('StarCore','StarChronicle'):shutil.copy2(runs[0]/'plugins'/(plugin+'.jar'),output/(plugin+'.jar'))
    for repo,name in [(ROOT,'starchronicle-source.tar.gz'),(a.native,'starcore-source.tar.gz')]:
        with (output/name).open('xb') as f:subprocess.run(['git','-C',str(repo),'archive','--format=tar.gz','HEAD'],stdout=f,check=True)
    summary['archive']=dict(bytes=archive.stat().st_size,sha256=I.sha(archive),members=len(inventory),public_inspectors_replayed=5)
    summary['source_archives']={n:I.sha(output/n) for n in ('starchronicle-source.tar.gz','starcore-source.tar.gz')}
    text=json.dumps(summary,ensure_ascii=False,indent=2)+'\n';(output/'manifest.json').write_text(text)
    destination=ROOT/'docs/verification';path=destination/(a.label+'-evidence.zip');I.check(not path.exists(),'Archive already exists')
    shutil.copy2(archive,path);(destination/(a.label+'-summary.json')).write_text(text)
    print(json.dumps(dict(archive=summary['archive'],totals=summary['totals'],delivery=str(output)),indent=2))


if __name__=='__main__':main()
