"""Archive exact paired builds, direct player trade, six regressions and retained failed attempts."""
from pathlib import Path
from datetime import datetime,timezone
import argparse,hashlib,importlib.util,json,os,re,shlex,shutil,subprocess,sys,zipfile
import xml.etree.ElementTree as ET
import yaml

ROOT=Path(__file__).resolve().parents[1]
def require(value,message):
    if not value:raise ValueError(message)
def read(path):return json.loads(path.read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def git(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/name);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def totals(folder):
    suites=[ET.parse(p).getroot() for p in folder.glob('TEST-*.xml')];require(suites,'Missing test XML')
    return {key:sum(int(s.get(key,0)) for s in suites) for key in ('tests','failures','errors','skipped')}
def evidence_tests(path):
    text=path.read_text();n=re.search(r'^Ran (\d+) tests in ',text,re.M);ok=re.search(r'^OK(?: \(skipped=(\d+)\))?$',text,re.M)
    require(n and ok and not re.search(r'^(FAILED|ERROR:|FAIL:)',text,re.M),'Evidence regression failed: '+path.name)
    skips=int(ok[1] or 0);return dict(tests=int(n[1]),passed=int(n[1])-skips,skipped=skips,sha256=sha(path))
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('run','regressions','frozen-dir','native','mysql','private-env','player-test-log'):p.add_argument('--'+name,type=Path,required=True)
    for name in ('failed-run','rejected-run','superseded-run','prepare-failed-run','retained-directory','log'):p.add_argument('--'+name,type=Path,action='append',default=[])
    p.add_argument('--label',default='player-trade-20261126');a=p.parse_args();require(re.fullmatch('[a-z0-9-]+',a.label),'Invalid label')
    pair=read(a.frozen_dir/'manifest.json');native=a.native.resolve()
    builds={}
    for name,root in [('StarCore',native),('StarChronicle',ROOT)]:
        require(not git(root,'status','--porcelain'),'Commit source/tools before archive')
        require(not git(root,'diff',pair[name]['source'],'HEAD','--','src','pom.xml'),'Product source drift')
        require(sha(a.frozen_dir/(name+'.jar'))==pair[name]['sha256'],'Frozen JAR drift')
        label='native' if name=='StarCore' else 'chronicle';value=totals(a.frozen_dir/(label+'-surefire'));log=(a.frozen_dir/(label+'-verify.log')).read_text()
        require(value==pair[name]['tests'] and value['tests']>0 and value['failures']==value['errors']==0 and 'BUILD SUCCESS' in log and 'BUILD FAILURE' not in log,'Incomplete frozen build')
        builds[label]=value
    cases=module('run-player-trade-regressions.py').CASES
    completed=read(a.regressions/'completed.json');require({r['kind'] for r in completed}==set(cases) and len(completed)==len(cases),'Six paired regressions required')
    accepted=[dict(kind='player',directory=a.run.name,**module('inspect-player-trade-paper.py').inspect(a.run))]
    runs=[a.run.resolve()];regressions={'player':evidence_tests(a.player_test_log)}
    for r in completed:
        name=r['kind'];run=Path(r['run']);result=module(cases[name][2]).inspect(run)
        require(result==r['result'],'Regression evidence changed after inspection');runs.append(run)
        accepted.append(dict(kind=name,directory=run.name,**result));regressions[name]=evidence_tests(a.regressions/(name+'-evidence-tests.log'))
    require(all(r['chronicle_sha256']==pair['StarChronicle']['sha256'] and r['starcore_sha256']==pair['StarCore']['sha256'] for r in accepted),'Live pair differs')
    sql=read(a.mysql/'manifest.json');require(sql['source_head']==pair['StarCore']['source'] and sql['exit_code']==0 and sql['tests']==totals(a.mysql) and sql['tests']['failures']==sql['tests']['errors']==sql['tests']['skipped']==0,'Incomplete MySQL evidence')
    require(sql['tests']['tests']==sql['sqlite_cases']+sql['mysql_cases'],'SQLite/MySQL case counts differ')
    for name,digest in sql['files'].items():require(sha(a.mysql/name)==digest,'MySQL evidence drift')
    for name in ('PlayerTradeRecoveryTest.java','MySqlPlayerTradeRecoveryTest.java','MySqlNationSettlementIntegrationTest.java'):
        require(sha(native/'src/test/java/dev/starcore/starcore/module/resource'/name)==sha(a.mysql/name),'MySQL test source drift')
    stopped=read(a.mysql/'mysql-stopped.json');require(stopped['status']=='exited' and stopped['exit_code']==0 and not stopped['oom_killed'],'Task MySQL still running or exited abnormally')
    failures=[]
    for run in a.failed_run:
        sessions=[read(p) for p in sorted((run/'evidence').glob('boot*.session.json'))]
        require(any(s['status']=='FAILED' for s in sessions) and all(p.get('exit_code')==0 for s in sessions for p in s['processes']),'Failed run not preserved/stopped')
        failures.append(dict(directory=run.name,sessions=sessions))
    for run in a.prepare_failed_run:
        require(not read(run/'preparation.json')['fixture_compiled'] and not list((run/'evidence').glob('boot*.session.json')),'Not a preparation-only failure')
        failures.append(dict(directory=run.name,status='FIXTURE_COMPILE_FAILED_BEFORE_SERVER_START'))
    for run in a.rejected_run:
        sessions=[read(p) for p in sorted((run/'evidence').glob('boot*.session.json'))]
        require(len(sessions)==3 and all(s['status']=='COMPLETE' and len(s['processes'])==2 and all(p.get('exit_code')==0 for p in s['processes']) for s in sessions),'Rejected run not normally stopped')
        diagnosis='Committed history source starcore.trade.history.v1 retained for reconciliation: Invalid persisted trade amounts'
        require(any(diagnosis in p.read_text() for p in (run/'evidence').glob('boot*.server.log')),'Missing original history compatibility diagnostic')
        failures.append(dict(directory=run.name,status='COMPLETED_SESSIONS_REJECTED_BY_INDEPENDENT_INSPECTION',reason=diagnosis,sessions=sessions))
    for run in a.superseded_run:
        sessions=[read(p) for p in sorted((run/'evidence').glob('boot*.session.json'))];reason=read(run/'evidence/candidate-superseded.json')
        require(sessions and all(p.get('exit_code')==0 for s in sessions for p in s['processes']) and reason['status']=='CANDIDATE_SUPERSEDED','Superseded run not preserved/stopped')
        failures.append(dict(directory=run.name,status='CANDIDATE_SUPERSEDED',reason=reason,sessions=sessions))
    summary=dict(status='PLAYER_TRADE_AND_PAIRED_SELECTED_STACK_ACCEPTED',captured_utc=datetime.now(timezone.utc).isoformat(),frozen_pair=pair,builds=builds,mysql=sql,mysql_shutdown=stopped,
        source_heads=dict(chronicle=git(ROOT,'rev-parse','HEAD'),native=git(native,'rev-parse','HEAD')),runs=accepted,regressions=regressions,failed_runs=failures,
        limitations=['External Vault responses are provider acknowledgements, not durable external payment receipts or a shared wallet/native transaction.',
            'Direct player-wallet trades deliver national stockpile resources; order book, quick trade, player backpacks and permanent UNKNOWN disposition remain separate.',
            'Exact pair uses isolated Paper with SQLite; actual MySQL product tests are separate. No full Folia, physical power-loss or production deployment claim.'])
    destination=ROOT/'target'/a.label;destination.mkdir(exist_ok=False)
    for name in ('StarCore','StarChronicle'):shutil.copy2(a.frozen_dir/(name+'.jar'),destination/(name+'.jar'))
    for root,name in [(ROOT,'starchronicle-source.tar.gz'),(native,'starcore-source.tar.gz')]:
        with (destination/name).open('xb') as output:subprocess.run(['git','-C',str(root),'archive','--format=tar.gz','HEAD'],stdout=output,check=True)
    secrets=[]
    for line in a.private_env.read_text().splitlines():
        if '=' in line:
            key,value=line.split('=',1)
            if re.search('password|secret',key,re.I):
                value=shlex.split(value)
                if value and len(value[0])>=8:secrets.append(value[0].encode())
    require(secrets,'Actual private values required for credential scan');redactions=[]
    pattern=r'(?m)^([ \t]*(?:access-secret|password|api-key|mineskin_api_key):)[ \t]*([^\n]*)$'
    public=set()
    for root in (ROOT,native):
        for match in re.finditer(pattern,(root/'src/main/resources/config.yml').read_text()):
            value=yaml.safe_load(match[2])
            if isinstance(value,str) and value:public.add(value)
    archive_path=destination/'evidence.zip'
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        def add(path,name):
            require(path.is_file() and not path.is_symlink() and name not in archive.namelist(),'Unsafe/duplicate archive member')
            raw=path.read_bytes()
            if path.suffix in ('.yml','.yaml'):
                for match in re.finditer(pattern,raw.decode()):
                    value=yaml.safe_load(match[2])
                    if isinstance(value,str) and len(value)>=16 and value not in public and value!='REDACTED_TEST_SECRET':secrets.append(value.encode())
                raw,n=re.subn(pattern,r'\1 "REDACTED_TEST_SECRET"',raw.decode());raw=raw.encode()
                if n:redactions.append(dict(path=name,fields=n))
            require(not any(secret in raw for secret in secrets),'Private credential detected; archive rejected')
            archive.writestr(name,raw)
        for run in [*runs,*a.failed_run,*a.rejected_run,*a.superseded_run,*a.prepare_failed_run]:
            files=[run/'preparation.json',run/'server.properties']+[p for p in (run/'evidence').rglob('*') if p.is_file()]
            files+=[p for p in (run/'fixture').glob('*') if p.suffix in ('.java','.json','.yml','.yaml')]
            for plugin in ('StarCore','StarChronicle','EpicCampaignPaperFixture','Essentials'):
                folder=run/'plugins'/plugin
                files+=[p for p in folder.rglob('*') if p.is_file() and (p.suffix in ('.db','.yml','.yaml','.json','.properties') or 'quest' in p.relative_to(folder).parts or p.name=='authority-domain.txt' or p.name.startswith('capital-designations.log'))]
            for path in sorted(set(files)):add(path,'runs/'+run.name+'/'+path.relative_to(run).as_posix())
        for label in ('native','chronicle'):
            add(a.frozen_dir/(label+'-verify.log'),'build/'+label+'/verify.log')
            for path in sorted((a.frozen_dir/(label+'-surefire')).iterdir()):
                if path.is_file():add(path,'build/'+label+'/surefire/'+path.name)
        for folder,prefix in [(a.mysql,'mysql'),(a.regressions,'regressions'),*[(p,'retained/'+p.name) for p in a.retained_directory]]:
            for path in sorted(folder.rglob('*')):
                if path.is_file():add(path,prefix+'/'+path.relative_to(folder).as_posix())
        for path in dict.fromkeys([a.player_test_log,*a.log]):add(path,'logs/'+path.name)
        for path in sorted((ROOT/'tools').iterdir()):
            if path.is_file() and path.suffix in ('.py','.java','.cjs'):add(path,'tools/'+path.name)
        add(ROOT/'docs/verification/production-stack-candidate-20261125.json','production-stack-lock.json')
        summary['redactions']=redactions;archive.writestr('source-provenance.json',json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
        inventory={p.filename:hashlib.sha256(archive.read(p.filename)).hexdigest() for p in archive.infolist()};archive.writestr('evidence-manifest.json',json.dumps(inventory,indent=2)+'\n')
    with zipfile.ZipFile(archive_path) as archive:
        require(archive.testzip() is None and all(hashlib.sha256(archive.read(k)).hexdigest()==v for k,v in inventory.items()),'Archive integrity failure')
        require(all(not any(secret in archive.read(item.filename) for secret in secrets) for item in archive.infolist()),'Private credential detected during final archive scan')
        extracted=destination/'extracted-check';extracted.mkdir();archive.extractall(extracted)
    checks={}
    for item,run in zip(accepted,runs):
        name=item['kind'];copy=extracted/'runs'/run.name
        binaries=['paper.jar','plugins/StarCore.jar','plugins/StarChronicle.jar','plugins/EpicCampaignPaperFixture.jar']+['plugins/'+v['filename'] for v in read(run/'preparation.json')['production_stack']['artifacts']]
        for relative in binaries:
            target=copy/relative;target.parent.mkdir(parents=True,exist_ok=True);target.symlink_to(run/relative)
        inspector,test,variable=('inspect-player-trade-paper.py','test_player_trade_acceptance.py','STARCHRONICLE_PLAYER_TRADE_RUN') if name=='player' else cases[name][2:]
        for kind,command,env in [('inspect',[sys.executable,str(extracted/'tools'/inspector),str(copy)],None),('tests',[sys.executable,'-m','unittest','discover','-s',str(extracted/'tools'),'-p',test,'-v'],dict(os.environ,**{variable:str(copy)}))]:
            path=destination/(name+'-extracted-'+kind+'.log')
            with path.open('xb') as output:code=subprocess.run(command,env=env,stdout=output,stderr=subprocess.STDOUT).returncode
            require(code==0,'Extracted '+name+' '+kind+' failed')
            if kind=='inspect':require(read(path)=={k:v for k,v in item.items() if k not in ('kind','directory')},'Extracted inspector result changed')
            else:
                checks[name]=evidence_tests(path)
                require(all(checks[name][key]==regressions[name][key] for key in ('tests','passed','skipped')),'Extracted test counts changed')
    summary['extracted_inspectors']='ALL_SEVEN_PASSED';summary['extracted_regressions']=checks
    summary['credential_scan']='Actual private environment and non-template configured secret bytes absent; secret fields redacted.'
    summary['archive']=dict(sha256=sha(archive_path),bytes=archive_path.stat().st_size,members=len(inventory));summary['source_archives']={name:sha(destination/name) for name in ('starchronicle-source.tar.gz','starcore-source.tar.gz')}
    data=json.dumps(summary,ensure_ascii=False,indent=2)+'\n';(destination/'manifest.json').write_text(data)
    public=ROOT/'docs/verification';require(not (public/(a.label+'-evidence.zip')).exists() and not (public/(a.label+'-summary.json')).exists(),'Public evidence already exists')
    shutil.copy2(archive_path,public/(a.label+'-evidence.zip'));(public/(a.label+'-summary.json')).write_text(data)
    print(json.dumps(dict(destination=str(destination),archive=summary['archive'],regressions=regressions),ensure_ascii=False,indent=2))

if __name__=='__main__':main()
