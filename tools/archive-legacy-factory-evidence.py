"""Archive validated legacy migration, six frozen-pair regressions, original failures and exact builds."""
from pathlib import Path
from datetime import datetime,timezone
import argparse,hashlib,importlib.util,json,os,re,shlex,shutil,subprocess,sys,zipfile
import xml.etree.ElementTree as ET
import yaml
ROOT=Path(__file__).resolve().parents[1]
NATIVE='530e391a31280c4f7595e4d8f1d2350b67310c70'
CHRONICLE='8f270c6f178a1e01a71b7e4fa3ebbfa7660de0dc'
CASES=[
 ('legacy','inspect-legacy-factory-paper.py','test_legacy_factory_acceptance.py','STARCHRONICLE_LEGACY_FACTORY_RUN'),
 ('factory','inspect-nation-factory-paper.py','test_nation_factory_acceptance.py','STARCHRONICLE_FACTORY_RUN'),
 ('production','inspect-nation-production-paper.py','test_nation_production_acceptance.py','STARCHRONICLE_NATION_PRODUCTION_RUN'),
 ('offline','inspect-nation-production-paper.py','test_nation_production_acceptance.py','STARCHRONICLE_NATION_PRODUCTION_RUN'),
 ('trade','inspect-nation-trade-paper.py','test_nation_trade_acceptance.py','STARCHRONICLE_NATION_TRADE_RUN'),
 ('dungeon','inspect-epic-production-paper.py','test_epic_production_acceptance.py','STARCHRONICLE_EPIC_RUN'),
 ('war','inspect-battlefield-paper.py','test_battlefield_acceptance.py','STARCHRONICLE_EPIC_RUN'),
]
def require(ok,message):
    if not ok:raise ValueError(message)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text())
def git(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
def module(path):
    spec=importlib.util.spec_from_file_location(path.stem,path);result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
def counts(path):
    suites=[ET.parse(p).getroot() for p in path.glob('TEST-*.xml')];require(suites,'Missing actual Surefire reports')
    return {key:sum(int(s.get(key,0)) for s in suites) for key in ('tests','failures','errors','skipped')}
def test_log(path):
    raw=path.read_text();n=re.search(r'^Ran (\d+) tests in ',raw,re.M);ok=re.search(r'^OK(?: \(skipped=(\d+)\))?$',raw,re.M)
    require(n and ok and not re.search(r'^(FAILED|ERROR:|FAIL:)',raw,re.M),'Evidence tests failed: '+path.name)
    skips=int(ok[1] or 0);return dict(tests=int(n[1]),passed=int(n[1])-skips,skipped=skips,sha256=sha(path))
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name,*_ in CASES:p.add_argument('--'+name+'-run',type=Path,required=True)
    for name in ('native','mysql','private-env','legacy-test-log','regressions'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--failed-run',type=Path,action='append',default=[])
    p.add_argument('--retained-directory',type=Path,action='append',default=[])
    p.add_argument('--log',type=Path,action='append',default=[])
    p.add_argument('--label',default='legacy-factory-20261125')
    a=p.parse_args();require(re.fullmatch('[a-z0-9-]+',a.label),'Invalid label')
    native=a.native.resolve();frozen=ROOT/'target/legacy-factory-frozen'
    for root,product in [(native,NATIVE),(ROOT,CHRONICLE)]:
        require(not git(root,'status','--porcelain'),'Commit sources/tools before archive')
        require(not git(root,'diff',product,'HEAD','--','src','pom.xml'),'Frozen source drift')
    runs=[getattr(a,name+'_run').resolve() for name,*_ in CASES]
    results=[module(ROOT/'tools'/tool).inspect(run) for (_,tool,*_),run in zip(CASES,runs)]
    require(all(all(r[k]==results[0][k] for k in ('chronicle_sha256','starcore_sha256')) for r in results),'Changed live pair')
    pair=read(frozen/'manifest.json')
    for plugin,key,product in [('StarCore','starcore_sha256',NATIVE),('StarChronicle','chronicle_sha256',CHRONICLE)]:
        require(sha(frozen/(plugin+'.jar'))==pair[plugin]['sha256']==results[0][key] and pair[plugin]['source_head']==product,'Frozen provenance differs')
    builds={}
    for name,total,skips in [('native',7526,51),('chronicle',991,0)]:
        value=counts(frozen/(name+'-surefire'));log=(frozen/(name+'-verify.log')).read_text()
        require(value==dict(tests=total,failures=0,errors=0,skipped=skips) and 'BUILD SUCCESS' in log and 'BUILD FAILURE' not in log,'Incomplete full build')
        require(re.search(r'Tests run: '+str(total)+r', Failures: 0, Errors: 0, Skipped: '+str(skips),log),'Build log/XML counts differ')
        builds[name]=value
    sql=read(a.mysql/'manifest.json');require(sql['exit_code']==0 and sql['source_head']==NATIVE and sql['tests']==counts(a.mysql)==dict(tests=64,failures=0,errors=0,skipped=0),'Missing actual MySQL settlement tests')
    require(sha(native/'src/test/java/dev/starcore/starcore/module/resource/MySqlNationSettlementIntegrationTest.java')==sha(a.mysql/'MySqlNationSettlementIntegrationTest.java'),'MySQL test source changed')
    stopped=read(a.mysql/'mysql-stopped.json');state=stopped['state'];require(state['Status']=='exited' and state['ExitCode']==0 and not state['Running'] and not state['OOMKilled'],'MySQL not stopped normally')
    regressions={name:test_log(a.legacy_test_log if name=='legacy' else a.regressions/(name+'-evidence-tests.log')) for name,*_ in CASES}
    failures=[]
    for run in a.failed_run:
        sessions=[read(p) for p in sorted((run/'evidence').glob('boot*.session.json'))]
        require(any(s['status']=='FAILED' for s in sessions) and all(p.get('exit_code')==0 for s in sessions for p in s['processes']),'Failed attempt not retained/stopped')
        failures.append(dict(directory=run.name,sessions=sessions))
    summary=dict(status='LEGACY_FACTORY_AND_FROZEN_PAIR_SELECTED_STACK_ACCEPTED',captured_utc=datetime.now(timezone.utc).isoformat(),source_heads=dict(chronicle=git(ROOT,'rev-parse','HEAD'),native=git(native,'rev-parse','HEAD')),product_sources=dict(chronicle=CHRONICLE,native=NATIVE),frozen_pair=pair,builds=builds,mysql=sql,mysql_shutdown=stopped,
        runs=[dict(kind=case[0],directory=run.name,**result) for case,run,result in zip(CASES,runs,results)],regressions=regressions,failed_runs=failures,
        limitations=['Only idle structurally valid legacy metadata supports explicit new cumulative material payment; six malformed/inflight/foreign fixture records remain quarantined.',
            'The exact pair has 21 normal Paper boots. Real MySQL tests are separate from Paper/SQLite; earlier MySQL Paper/crash evidence is not transferred to this pair.',
            'No historical payment credit, automatic old-work compensation, original-builder attribution, world construction, Folia or production deployment claim.'])
    dest=ROOT/'target'/a.label;dest.mkdir(exist_ok=False)
    for plugin in ('StarCore','StarChronicle'):shutil.copy2(frozen/(plugin+'.jar'),dest/(plugin+'.jar'))
    for root,name in [(ROOT,'starchronicle-source.tar.gz'),(native,'starcore-source.tar.gz')]:
        with (dest/name).open('xb') as out:subprocess.run(['git','-C',str(root),'archive','--format=tar.gz','HEAD'],stdout=out,check=True)
    pattern=r'(?m)^([ \t]*(?:access-secret|password|api-key|mineskin_api_key):)[ \t]*([^\n]*)$';public=set();secrets=set();redactions=[]
    for root in (ROOT,native):
        for match in re.finditer(pattern,(root/'src/main/resources/config.yml').read_text()):
            value=yaml.safe_load(match[2])
            if isinstance(value,str) and value:public.add(value)
    for line in a.private_env.read_text().splitlines():
        if '=' not in line:continue
        key,value=line.split('=',1)
        if re.search('password|secret',key,re.I):
            values=shlex.split(value)
            if values and len(values[0])>=8:secrets.add(values[0].encode())
    require(secrets,'Private credential scan needs actual private values')
    archive_path=dest/'evidence.zip'
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        def add(path,name):
            require(path.is_file() and not path.is_symlink() and name not in archive.namelist(),'Unsafe/duplicate member '+name)
            raw=path.read_bytes()
            if path.suffix in ('.yml','.yaml'):
                for match in re.finditer(pattern,raw.decode()):
                    value=yaml.safe_load(match[2])
                    if isinstance(value,str) and len(value)>=16 and value not in public and value!='REDACTED_TEST_SECRET':secrets.add(value.encode())
                text,n=re.subn(pattern,r'\1 "REDACTED_TEST_SECRET"',raw.decode());raw=text.encode()
                if n:redactions.append(dict(path=name,fields=n))
            archive.writestr(name,raw)
        for run in [*runs,*a.failed_run]:
            files=[run/'preparation.json',run/'server.properties']+[p for p in (run/'evidence').rglob('*') if p.is_file()]
            files += [p for p in (run/'fixture').glob('*') if p.suffix in ('.java','.json','.yml','.yaml')]
            for plugin in ('StarCore','StarChronicle','EpicCampaignPaperFixture','Essentials'):
                folder=run/'plugins'/plugin
                files += [p for p in folder.rglob('*') if p.is_file() and (p.suffix in ('.db','.yml','.yaml','.json','.properties') or 'quest' in p.relative_to(folder).parts or p.name=='authority-domain.txt' or p.name.startswith('capital-designations.log'))]
            for path in sorted(set(files)):add(path,'runs/'+run.name+'/'+path.relative_to(run).as_posix())
        for name in ('native','chronicle'):
            add(frozen/(name+'-verify.log'),'build/'+name+'/full-verify.log')
            for path in sorted((frozen/(name+'-surefire')).iterdir()):
                if path.is_file():add(path,'build/'+name+'/surefire/'+path.name)
        for folder,prefix in [(a.mysql,'mysql'),(a.regressions,'regressions'),*[(p,'retained/'+p.name) for p in a.retained_directory]]:
            for path in sorted(folder.rglob('*')):
                if path.is_file():add(path,prefix+'/'+path.relative_to(folder).as_posix())
        for path in dict.fromkeys([a.legacy_test_log,*a.log]):add(path,'logs/'+path.name)
        for path in sorted((ROOT/'tools').iterdir()):
            if path.is_file() and path.suffix in ('.py','.java','.cjs'):add(path,'tools/'+path.name)
        for root,product,prefix in [(native,NATIVE,'native-source'),(ROOT,CHRONICLE,'chronicle-source')]:
            for rel in git(root,'diff','--name-only',product+'^',product,'--','src').splitlines():add(root/rel,prefix+'/'+rel)
        add(ROOT/'docs/verification/production-stack-candidate-20261125.json','production-stack-lock.json')
        summary['redactions']=redactions;archive.writestr('source-provenance.json',json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
        inventory={i.filename:hashlib.sha256(archive.read(i.filename)).hexdigest() for i in archive.infolist()};archive.writestr('evidence-manifest.json',json.dumps(inventory,indent=2)+'\n')
    with zipfile.ZipFile(archive_path) as archive:
        require(archive.testzip() is None,'Corrupt archive')
        for name,digest in inventory.items():
            raw=archive.read(name);require(hashlib.sha256(raw).hexdigest()==digest,'Changed archive member');require(not any(secret in raw for secret in secrets),'Private credential in '+name)
        extracted=dest/'extracted-check';extracted.mkdir();archive.extractall(extracted)
    for run in runs:
        copy=extracted/'runs'/run.name
        binaries=['paper.jar','plugins/StarCore.jar','plugins/StarChronicle.jar','plugins/EpicCampaignPaperFixture.jar']+['plugins/'+v['filename'] for v in read(run/'preparation.json')['production_stack']['artifacts']]
        for name in binaries:
            target=copy/name;target.parent.mkdir(parents=True,exist_ok=True);target.symlink_to(run/name)
    checks={}
    for (name,tool,test,variable),run in zip(CASES,runs):
        copy=extracted/'runs'/run.name
        for kind,command,env in [('inspect',[sys.executable,str(extracted/'tools'/tool),str(copy)],None),('tests',[sys.executable,'-m','unittest','discover','-s',str(extracted/'tools'),'-p',test,'-v'],dict(os.environ,**{variable:str(copy)}))]:
            path=dest/(name+'-extracted-'+kind+'.log')
            with path.open('xb') as log:code=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT).returncode
            require(code==0,'Extracted '+name+' '+kind+' failed')
            if kind=='tests':checks[name]=test_log(path)
    summary['extracted_inspectors']='ALL_SEVEN_PASSED';summary['extracted_regressions']=checks
    summary['credential_scan']='Actual private environment and non-template configured secret bytes absent; secret fields redacted.'
    summary['evidence_archive']=dict(sha256=sha(archive_path),bytes=archive_path.stat().st_size,members=len(inventory))
    summary['source_archives']={name:sha(dest/name) for name in ('starchronicle-source.tar.gz','starcore-source.tar.gz')}
    text=json.dumps(summary,ensure_ascii=False,indent=2)+'\n';(dest/'manifest.json').write_text(text)
    public=ROOT/'docs/verification';require(not (public/(a.label+'-evidence.zip')).exists(),'Published archive already exists')
    shutil.copy2(archive_path,public/(a.label+'-evidence.zip'));(public/(a.label+'-summary.json')).write_text(text)
    print(json.dumps(dict(archive=summary['evidence_archive'],regressions=regressions,extracted_regressions=checks),indent=2))
if __name__=='__main__':main()
