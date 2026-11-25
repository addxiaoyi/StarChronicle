"""Archive catalog acceptance, the frozen pair's five regressions, SQL tests and retained failures.

Requires both source worktrees committed and all six stopped-run inspectors passing.
Credentials, worlds and third-party binaries are never put in the public evidence ZIP.
"""
from pathlib import Path
from datetime import datetime,timezone
import argparse,hashlib,importlib.util,json,re,shutil,subprocess,xml.etree.ElementTree as ET,zipfile
ROOT=Path(__file__).resolve().parents[1]
NATIVE_SOURCE='c9992387d27808f69a4c78411d5407c24bb717f8'
CHRONICLE_SOURCE='1aacb5ad874e153fc41dbda1502d9dc8729f91fc'
def load(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/name);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def git(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
def read(p):return json.loads(p.read_text())
def require(value,message):
    if not value:raise ValueError(message)
def build(root):
    directory=root/'target/catalog-build-evidence';counts={k:0 for k in ('tests','failures','errors','skipped')}
    suites=sorted((directory/'surefire-reports').glob('TEST-*.xml'))
    for p in suites:
        suite=ET.parse(p).getroot()
        for k in counts:counts[k]+=int(suite.get(k,0))
    raw=(directory/'full-verify.log').read_text()
    require(suites and counts==read(directory/'counts.json') and counts['failures']==counts['errors']==0 and 'BUILD SUCCESS' in raw and 'BUILD FAILURE' not in raw,'Invalid full-build evidence')
    require(re.search(r'Tests run: '+str(counts['tests'])+r', Failures: 0, Errors: 0, Skipped: '+str(counts['skipped']),raw),'Build log/XML count differs')
    return dict(counts=counts,suites=len(suites),log_sha256=sha(directory/'full-verify.log'))
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('catalog-run','production-run','offline-run','trade-run','dungeon-run','war-run','native','mysql-evidence','regression-directory','catalog-test-log'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--failed-run',type=Path,action='append',default=[]);p.add_argument('--log',type=Path,action='append',default=[]);p.add_argument('--label',default='production-catalog-20261125');a=p.parse_args()
    require(re.fullmatch('[a-z0-9-]+',a.label),'Invalid label');native=a.native.resolve()
    require(not git(ROOT,'status','--porcelain') and not git(native,'status','--porcelain'),'Commit sources and tools before archiving')
    require(not git(ROOT,'diff',CHRONICLE_SOURCE,'HEAD','--','src','pom.xml') and not git(native,'diff',NATIVE_SOURCE,'HEAD','--','src','pom.xml'),'Product source changed after build')
    names=('catalog','production','offline','trade','dungeon','war')
    tools=('inspect-production-catalog-paper.py','inspect-nation-production-paper.py','inspect-nation-production-paper.py','inspect-nation-trade-paper.py','inspect-epic-production-paper.py','inspect-battlefield-paper.py')
    runs=[getattr(a,name+'_run').resolve() for name in names];results=[load(tool).inspect(run) for tool,run in zip(tools,runs)]
    require(all(r[key]==results[0][key] for r in results for key in ('chronicle_sha256','starcore_sha256')),'Acceptance changed frozen pair')
    for path,key in [(ROOT/'target/starchronicle-0.1.0-SNAPSHOT.jar','chronicle_sha256'),(native/'target/starcore-1.0.0.jar','starcore_sha256')]:require(sha(path)==results[0][key],'Frozen build artifact changed')
    mysql=a.mysql_evidence.resolve();sql=read(mysql/'settlement/manifest.json')
    require(sql['status']=='PASSED' and sql['exit_code']==0 and sql['tests']==dict(tests=34,failures=0,errors=0,skipped=0),'MySQL suite incomplete')
    for name,digest in sql['files'].items():require(sha(mysql/'settlement'/name)==digest,'MySQL result changed')
    source=native/'src/test/java/dev/starcore/starcore/module/resource/MySqlNationSettlementIntegrationTest.java'
    require(sha(source)==sha(mysql/'settlement'/source.name),'MySQL test source changed')
    shutdown=read(mysql/'shutdown.json');require(shutdown['Running'] is False and shutdown['ExitCode']==0 and shutdown['OOMKilled'] is False,'MySQL did not stop normally')
    snapshot=read(mysql/'after-tests.json');require(snapshot['version']=='8.4.13' and snapshot['isolation']=='REPEATABLE-READ' and snapshot['ssl_cipher'] and len(snapshot['tables'])==11 and not any(t['rows'] for t in snapshot['tables'].values()),'MySQL final audit incomplete')
    regressions=[]
    for log in [a.catalog_test_log,*sorted(a.regression_directory.glob('*-evidence-tests.log'))]:
        raw=log.read_text();count=re.search(r'^Ran (\d+) tests in ',raw,re.M);ok=re.search(r'^OK(?: \(skipped=(\d+)\))?$',raw,re.M)
        require(count and ok and not re.search(r'^(?:FAILED|ERROR:|FAIL:)',raw,re.M),'Evidence regression failed: '+log.name)
        skipped=int(ok[1] or 0);regressions.append(dict(log=log.name,tests=int(count[1]),passed=int(count[1])-skipped,skipped=skipped,sha256=sha(log)))
    require(len(regressions)==6,'Missing evidence regression suites')
    failures=[]
    for run in a.failed_run:
        sessions=[read(s) for s in sorted((run/'evidence').glob('boot*.session.json'))]
        require(sessions and any(s['status']=='FAILED' for s in sessions),'Failed run lacks original failed session')
        require(all(p['exit_code']==0 for s in sessions for p in s['processes'] if p['name']=='server'),'Failed-run server did not stop normally')
        failures.append(dict(directory=run.name,sessions=sessions))
    summary=dict(status='PRODUCTION_CATALOG_AND_FROZEN_PAIR_SELECTED_STACK_ACCEPTED',captured_utc=datetime.now(timezone.utc).isoformat(),source_heads=dict(chronicle=git(ROOT,'rev-parse','HEAD'),native=git(native,'rev-parse','HEAD')),product_sources=dict(chronicle=CHRONICLE_SOURCE,native=NATIVE_SOURCE),runs=[dict(kind=name,directory=run.name,**result) for name,run,result in zip(names,runs,results)],builds=dict(chronicle=build(ROOT),native=build(native)),mysql=sql,mysql_shutdown=shutdown,regressions=regressions,failed_runs=failures)
    dest=ROOT/'target'/a.label;dest.mkdir(exist_ok=False)
    for plugin in ('StarCore','StarChronicle'):shutil.copy2(runs[0]/'plugins'/f'{plugin}.jar',dest/f'{plugin}.jar')
    for root,name in [(ROOT,'starchronicle-source.tar.gz'),(native,'starcore-source.tar.gz')]:
        with (dest/name).open('xb') as out:subprocess.run(['git','-C',str(root),'archive','--format=tar.gz','HEAD'],stdout=out,check=True)
    redactions=[];archive_path=dest/'evidence.zip'
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        def add(path,name):
            require(path.is_file() and not path.is_symlink() and name not in archive.namelist(),'Unsafe/duplicate evidence member: '+name)
            raw=path.read_bytes()
            if path.suffix in ('.yml','.yaml'):
                value,count=re.subn(r'(?m)^(\s*(?:access-secret|password|api-key|mineskin_api_key):)\s*.*$',r'\1 "REDACTED_TEST_SECRET"',raw.decode())
                raw=value.encode()
                if count:redactions.append(dict(path=name,fields=count))
            archive.writestr(name,raw)
        for run in [*runs,*a.failed_run]:
            files=[run/'preparation.json',run/'server.properties']+[f for f in (run/'evidence').rglob('*') if f.is_file()]
            files += [f for f in (run/'fixture').iterdir() if f.suffix in ('.java','.json','.yml','.yaml')]
            for plugin in ('StarCore','StarChronicle','EpicCampaignPaperFixture','Essentials'):
                files += [f for f in (run/'plugins'/plugin).rglob('*') if f.is_file() and (f.suffix in ('.db','.yml','.yaml','.json','.properties') or 'quest' in f.relative_to(run/'plugins'/plugin).parts or f.name.startswith('capital-designations.log'))]
            for path in sorted(set(files)):add(path,'runs/'+run.name+'/'+path.relative_to(run).as_posix())
        for name,root in [('native',native),('chronicle',ROOT)]:
            for path in sorted((root/'target/catalog-build-evidence').rglob('*')):
                if path.is_file():add(path,'build/'+name+'/'+path.relative_to(root/'target/catalog-build-evidence').as_posix())
        for path in sorted(mysql.rglob('*')):
            if path.is_file():add(path,'mysql/'+path.relative_to(mysql).as_posix())
        for path in sorted(a.regression_directory.rglob('*')):
            if path.is_file():add(path,'regressions/'+path.relative_to(a.regression_directory).as_posix())
        for path in dict.fromkeys([a.catalog_test_log,*a.log]):add(path,'logs/'+path.name)
        for path in sorted((ROOT/'tools').iterdir()):
            if path.is_file() and path.suffix in ('.py','.java','.cjs'):add(path,'tools/'+path.name)
        for path in sorted((native/'tools').glob('*nation*mysql*.py')):add(path,'native-tools/'+path.name)
        changed=git(native,'diff','--name-only','ebdfacaea2132f86bc3cbaedd6cd6f438d2de0b5',NATIVE_SOURCE).splitlines()
        for name in changed:add(native/name,'native-source/'+name)
        add(ROOT/'docs/verification/production-stack-candidate-20261125.json','production-stack-lock.json')
        summary['redactions']=redactions;archive.writestr('source-provenance.json',json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
        inventory={i.filename:hashlib.sha256(archive.read(i.filename)).hexdigest() for i in archive.infolist()};archive.writestr('evidence-manifest.json',json.dumps(inventory,indent=2)+'\n')
    with zipfile.ZipFile(archive_path) as z:require(z.testzip() is None and all(hashlib.sha256(z.read(k)).hexdigest()==v for k,v in inventory.items()),'Archive hash verification failed')
    summary['evidence_archive']=dict(sha256=sha(archive_path),bytes=archive_path.stat().st_size,members=len(inventory));summary['source_archives']={n:sha(dest/n) for n in ('starchronicle-source.tar.gz','starcore-source.tar.gz')}
    data=json.dumps(summary,ensure_ascii=False,indent=2)+'\n';(dest/'manifest.json').write_text(data)
    public=ROOT/'docs/verification';require(not (public/(a.label+'-evidence.zip')).exists() and not (public/(a.label+'-summary.json')).exists(),'Public evidence exists')
    shutil.copy2(archive_path,public/(a.label+'-evidence.zip'));(public/(a.label+'-summary.json')).write_text(data)
    print(json.dumps(dict(delivery=str(dest),archive=summary['evidence_archive'],regressions=regressions),indent=2))
if __name__=='__main__':main()
