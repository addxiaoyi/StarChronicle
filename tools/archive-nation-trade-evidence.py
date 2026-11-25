"""Archive the frozen national trade pair, online/offline trade, dungeon, war and failures."""
from datetime import datetime,timezone
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile
ROOT=Path(__file__).resolve().parents[1]
def load(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/name);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
TRADE=load('inspect-nation-trade-paper.py');OFFLINE=load('inspect-nation-trade-offline-paper.py');STACK=load('inspect-epic-production-paper.py');BATTLE=load('inspect-battlefield-paper.py')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def git(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
def tests(root,log):
    suites=sorted((root/'target/surefire-reports').glob('TEST-*.xml'));totals=dict(tests=0,failures=0,errors=0,skipped=0)
    for p in suites:
        suite=ET.parse(p).getroot()
        for key in totals:totals[key]+=int(suite.attrib.get(key,'0'))
    assert suites and totals['failures']==totals['errors']==0
    raw=log.read_text();assert '[INFO] BUILD SUCCESS' in raw
    assert re.search(r'Tests run: '+str(totals['tests'])+r', Failures: 0, Errors: 0, Skipped: '+str(totals['skipped']),raw)
    return dict(totals=totals,suites=len(suites),sha256={p.name:sha(p) for p in suites},build_log=log.name,build_log_sha256=sha(log)),suites

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('run','offline-run','dungeon-run','war-run','native','chronicle-build-log','native-build-log'):p.add_argument('--'+key,type=Path,required=True)
    p.add_argument('--failed-run',type=Path,action='append',default=[]);p.add_argument('--regression-log',type=Path,action='append',required=True);p.add_argument('--log',type=Path,action='append',default=[])
    p.add_argument('--label',default='nation-trade-paper-20261125');a=p.parse_args();assert re.fullmatch('[a-z0-9-]+',a.label)
    native=a.native.resolve();runs=[a.run.resolve(),a.offline_run.resolve(),a.dungeon_run.resolve(),a.war_run.resolve()];accepted=[TRADE.inspect(runs[0]),OFFLINE.inspect(runs[1]),STACK.inspect(runs[2]),BATTLE.inspect(runs[3])]
    assert all(result[key]==accepted[0][key] for result in accepted for key in ('chronicle_sha256','starcore_sha256'))
    assert sha(ROOT/'target/nation-trade-frozen-starchronicle-0.1.0-SNAPSHOT.jar')==accepted[0]['chronicle_sha256']
    assert sha(native/'target/nation-trade-frozen-starcore-1.0.0.jar')==accepted[0]['starcore_sha256']
    assert not git(ROOT,'status','--porcelain') and not git(native,'status','--porcelain'),'Commit tools before archiving'
    product_head='01cbe53735fb8692b59ec552051956e80bd44b05';native_head='253added0228032fe7a687a84f5d5e0b02788ece'
    assert not git(ROOT,'diff',product_head,'HEAD','--','src','pom.xml') and not git(native,'diff',native_head,'HEAD','--','src','pom.xml'),'Product source differs from tested build'
    chronicle_tests,cs=tests(ROOT,a.chronicle_build_log);native_tests,ns=tests(native,a.native_build_log)
    regressions=[]
    for path in a.regression_log:
        raw=path.read_text();count=re.search(r'^Ran (\d+) tests in ',raw,re.M);ok=re.search(r'^OK(?: \(skipped=(\d+)\))?$',raw,re.M)
        assert count and ok and not re.search(r'^(?:FAILED|ERROR:|FAIL:)',raw,re.M)
        skip=int(ok[1] or 0);regressions.append(dict(log=path.name,tests=int(count[1]),passed=int(count[1])-skip,skipped=skip,sha256=sha(path)))
    failed=[r.resolve() for r in a.failed_run];failures=[]
    for run in failed:
        sessions=[TRADE.read(p) for p in sorted((run/'evidence').glob('boot*.session.json'))]
        assert sessions and any(s['status']=='FAILED' for s in sessions) and all(p['exit_code']==0 for s in sessions for p in s['processes'])
        failures.append(dict(directory=run.name,sessions=sessions,errors=[r for f in ('epic-fixture.jsonl','epic-clients.jsonl') for r in STACK.read_lines(run/'evidence'/f) if r['kind']=='FAILED']))
    summary=dict(status='NATIONAL_TRADE_OFFLINE_AND_EPIC_SELECTED_STACK_ACCEPTED',captured_utc=datetime.now(timezone.utc).isoformat(),chronicle_head=git(ROOT,'rev-parse','HEAD'),native_head=git(native,'rev-parse','HEAD'),build_sources=dict(chronicle=product_head,native=native_head),runs=[dict(directory=r.name,**v) for r,v in zip(runs,accepted)],chronicle_tests=chronicle_tests,native_tests=native_tests,regressions=regressions,failed_runs=failures)
    destination=ROOT/'target'/a.label;destination.mkdir(parents=True,exist_ok=False)
    for plugin in ('StarCore','StarChronicle'):shutil.copy2(runs[0]/'plugins'/f'{plugin}.jar',destination/f'{plugin}.jar')
    for root,name in ((ROOT,'starchronicle-source.tar.gz'),(native,'starcore-source.tar.gz')):
        with (destination/name).open('xb') as out:subprocess.run(['git','-C',str(root),'archive','--format=tar.gz','HEAD'],stdout=out,check=True)
    archive_path=destination/'evidence.zip';redactions=[]
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        def add(path,name):
            assert path.is_file() and not path.is_symlink() and name not in archive.namelist()
            if path.suffix in ('.yml','.yaml'):
                raw,count=re.subn(r'(?m)^(\s*(?:access-secret|password|api-key|mineskin_api_key):)\s*.*$',r'\1 "REDACTED_TEST_SECRET"',path.read_text())
                if count:redactions.append(dict(path=name,fields=count))
                archive.writestr(name,raw)
            else:archive.write(path,name)
        for run in [*runs,*failed]:
            files=[run/'preparation.json',run/'server.properties']+[p for p in (run/'evidence').rglob('*') if p.is_file()]
            files += [p for p in (run/'fixture').iterdir() if p.suffix in ('.java','.yml','.yaml','.json')]
            for plugin in ('StarCore','StarChronicle','EpicCampaignPaperFixture'):
                files += [p for p in (run/'plugins'/plugin).rglob('*') if p.is_file() and (p.suffix in ('.db','.yml','.yaml','.json','.properties') or 'quest' in p.relative_to(run/'plugins'/plugin).parts or p.name.startswith('capital-designations.log'))]
            for path in sorted(set(files)):add(path,'runs/'+run.name+'/'+path.relative_to(run).as_posix())
        for path in dict.fromkeys([*a.log,*a.regression_log,a.chronicle_build_log,a.native_build_log]):add(path,'logs/'+path.name)
        for name,root,suites,pattern in [('chronicle',ROOT,cs,'nation-trade-*'),('native',native,ns,'nation-trade-*')]:
            for path in suites:add(path,'build/'+name+'/full/'+path.name)
            for directory in sorted((root/'target').glob(pattern)):
                if directory.is_dir():
                    for path in sorted(directory.glob('TEST-*.xml')):add(path,'build/'+name+'/retained/'+directory.name+'/'+path.name)
        for path in sorted((ROOT/'tools').iterdir()):
            if path.is_file() and (path.suffix in ('.py','.java','.cjs') and any(word in path.name for word in ('quest','epic','contribution','battlefield','production','nation-trade','nation_trade','EpicCampaign','ProductionStack','QuestRecovery','Battlefield','NationTrade'))):add(path,'tools/'+path.name)
        add(ROOT/'docs/verification/production-stack-candidate-20261125.json','production-stack-lock.json')
        summary['redactions']=redactions;archive.writestr('source-provenance.json',json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
        inventory={e.filename:hashlib.sha256(archive.read(e.filename)).hexdigest() for e in archive.infolist()};archive.writestr('evidence-manifest.json',json.dumps(inventory,indent=2)+'\n')
    with zipfile.ZipFile(archive_path) as archive:assert archive.testzip() is None and all(hashlib.sha256(archive.read(n)).hexdigest()==h for n,h in inventory.items())
    summary['evidence_archive']=dict(sha256=sha(archive_path),bytes=archive_path.stat().st_size,members=len(inventory));summary['source_archives']={name:sha(destination/name) for name in ('starchronicle-source.tar.gz','starcore-source.tar.gz')}
    data=json.dumps(summary,ensure_ascii=False,indent=2)+'\n';(destination/'manifest.json').write_text(data)
    output=ROOT/'docs/verification';assert not (output/(a.label+'-evidence.zip')).exists() and not (output/(a.label+'-summary.json')).exists()
    shutil.copy2(archive_path,output/(a.label+'-evidence.zip'));(output/(a.label+'-summary.json')).write_text(data)
    print(json.dumps(dict(delivery=str(destination),archive=summary['evidence_archive'],regressions=regressions),ensure_ascii=False,indent=2))

if __name__=='__main__':main()
