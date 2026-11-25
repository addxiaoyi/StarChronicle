"""Archive native score victory, paired dungeon regression, retained failures and exact native build."""
from datetime import datetime,timezone
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('battlefield_inspector',Path(__file__).with_name('inspect-battlefield-paper.py'))
INSPECTOR=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(INSPECTOR)
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def git(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--dungeon-run',type=Path,required=True);p.add_argument('--native',type=Path,required=True)
    p.add_argument('--failed-run',type=Path,action='append',required=True);p.add_argument('--native-build-log',type=Path,required=True)
    p.add_argument('--regression-log',type=Path,action='append',required=True);p.add_argument('--log',type=Path,action='append',default=[])
    p.add_argument('--label',default='battlefield-paper-20261125');a=p.parse_args();assert re.fullmatch('[a-z0-9-]+',a.label)
    runs=[a.run.resolve(),a.dungeon_run.resolve()];accepted=[INSPECTOR.inspect(runs[0]),INSPECTOR.STACK.inspect(runs[1])];assert [v['route'] for v in accepted]==['WAR','DUNGEON']
    assert all(r[key]==accepted[0][key] for r in accepted for key in ('chronicle_sha256','starcore_sha256'))
    failed=[path.resolve() for path in a.failed_run];failures=[]
    for run in failed:
        session=json.loads((run/'evidence/boot1.session.json').read_text());assert session['status']=='FAILED'
        assert all(p['exit_code']==0 for p in session['processes'])
        failures.append(dict(directory=run.name,session=session,errors=[r for name in ('epic-fixture.jsonl','epic-clients.jsonl') for r in INSPECTOR.read_lines(run/'evidence'/name) if r['kind']=='FAILED']))
    native=a.native.resolve();assert not git(ROOT,'status','--porcelain') and not git(native,'status','--porcelain'),'Commit tools before archiving'
    inherited=ROOT/'docs/verification/capital-history-paper-20261125-summary.json';build=json.loads(inherited.read_text())
    assert not git(ROOT,'diff',build['chronicle_head'],'HEAD','--','src','pom.xml'),'Chronicle product source drift'
    assert accepted[0]['chronicle_sha256']==build['runs'][0]['chronicle_sha256']
    assert sha(native/'target/battlefield-frozen-starcore-1.0.0.jar')==accepted[0]['starcore_sha256']
    suites=sorted((native/'target/surefire-reports').glob('TEST-*.xml'));totals=dict(tests=0,failures=0,errors=0,skipped=0)
    for path in suites:
        suite=ET.parse(path).getroot()
        for key in totals:totals[key]+=int(suite.attrib.get(key,'0'))
    assert suites and totals['failures']==totals['errors']==0
    native_log=a.native_build_log.read_text();assert '[INFO] BUILD SUCCESS' in native_log
    assert re.search(r'Tests run: '+str(totals['tests'])+r', Failures: 0, Errors: 0, Skipped: '+str(totals['skipped']),native_log)
    regressions=[]
    for path in a.regression_log:
        raw=path.read_text();count=re.search(r'^Ran (\d+) tests in ',raw,re.M);ok=re.search(r'^OK(?: \(skipped=(\d+)\))?$',raw,re.M)
        assert count and ok and not re.search(r'^(?:FAILED|ERROR:|FAIL:)',raw,re.M)
        skipped=int(ok[1] or 0);regressions.append(dict(log=path.name,tests=int(count[1]),passed=int(count[1])-skipped,skipped=skipped,sha256=sha(path)))
    summary=dict(status='NATIVE_STRONGHOLD_VICTORY_AND_DUNGEON_ACCEPTED',captured_utc=datetime.now(timezone.utc).isoformat(),chronicle_head=git(ROOT,'rev-parse','HEAD'),native_head=git(native,'rev-parse','HEAD'),
        runs=[dict(directory=run.name,**result) for run,result in zip(runs,accepted)],regressions=regressions,failed_runs=failures,
        native_tests=dict(totals=totals,suites=len(suites),sha256={p.name:sha(p) for p in suites},build_log=a.native_build_log.name,build_log_sha256=sha(a.native_build_log)),
        inherited_builds=dict(reference=inherited.name,sha256=sha(inherited),chronicle=build['chronicle_tests']['totals'],
            note='Chronicle is not rebuilt; its src/ and pom.xml match the capital increment and its frozen runtime JAR matches that acceptance. Native is rebuilt and revalidated separately.'))
    destination=ROOT/'target'/a.label;destination.mkdir(parents=True,exist_ok=False)
    for plugin in ('StarCore','StarChronicle'):shutil.copy2(runs[0]/'plugins'/f'{plugin}.jar',destination/f'{plugin}.jar')
    for root,name in ((ROOT,'starchronicle-source.tar.gz'),(native,'starcore-source.tar.gz')):
        with (destination/name).open('xb') as output:subprocess.run(['git','-C',str(root),'archive','--format=tar.gz','HEAD'],stdout=output,check=True)
    archive_path=destination/'evidence.zip';redactions=[]
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        def add(path,name):
            assert not path.is_symlink() and name not in archive.namelist()
            if path.suffix in ('.yml','.yaml'):
                raw,count=re.subn(r'(?m)^(\s*(?:access-secret|password|api-key|mineskin_api_key):)\s*.*$',r'\1 "REDACTED_TEST_SECRET"',path.read_text())
                if count:redactions.append(dict(path=name,fields=count))
                archive.writestr(name,raw)
            else:archive.write(path,name)
        for run in [*runs,*failed]:
            files=[run/'preparation.json',run/'server.properties']+[path for path in (run/'evidence').rglob('*') if path.is_file()]
            files += [path for path in (run/'fixture').iterdir() if path.suffix in ('.java','.yml','.yaml','.json')]
            for plugin in ('StarCore','StarChronicle','EpicCampaignPaperFixture'):
                files += [path for path in (run/'plugins'/plugin).rglob('*') if path.is_file() and (path.suffix in ('.db','.yml','.yaml','.json','.properties') or path.name.startswith('capital-designations.log'))]
            for path in sorted(set(files)):add(path,'runs/'+run.name+'/'+path.relative_to(run).as_posix())
        for path in [*a.log,*a.regression_log,a.native_build_log]:add(path,'logs/'+path.name)
        add(inherited,'inherited-builds/'+inherited.name)
        for path in suites:add(path,'build/native/'+path.name)
        for directory in sorted((native/'target').glob('nation-contention-*')):
            if directory.is_dir():
                for path in sorted(directory.glob('TEST-*.xml')):add(path,'build/retained/'+directory.name+'/'+path.name)
        for name in ('BattlefieldPaperFixture.java','prepare-battlefield-paper.py','inspect-battlefield-paper.py','test_battlefield_acceptance.py','EpicCampaignPaperFixture.java','ProductionStackPaperFixture.java','prepare-epic-paper.py','prepare-production-paper.py','epic-paper-session.py','epic-paper-clients.cjs',
            'inspect-epic-production-paper.py','inspect-contribution-paper.py','inspect-epic-paper.py','inspect-quest-evidence-paper.py',
            'test_epic_production_acceptance.py','test_contribution_acceptance.py','test_epic_acceptance.py','archive-battlefield-evidence.py'):
            add(ROOT/'tools'/name,'tools/'+name)
        add(ROOT/'docs/verification/production-stack-candidate-20261125.json','production-stack-lock.json')
        summary['redactions']=redactions;archive.writestr('source-provenance.json',json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
        inventory={entry.filename:hashlib.sha256(archive.read(entry.filename)).hexdigest() for entry in archive.infolist()}
        archive.writestr('evidence-manifest.json',json.dumps(inventory,indent=2)+'\n')
    with zipfile.ZipFile(archive_path) as archive:assert archive.testzip() is None and all(hashlib.sha256(archive.read(name)).hexdigest()==digest for name,digest in inventory.items())
    summary['evidence_archive']=dict(sha256=sha(archive_path),bytes=archive_path.stat().st_size,members=len(inventory))
    summary['source_archives']={name:sha(destination/name) for name in ('starchronicle-source.tar.gz','starcore-source.tar.gz')}
    text=json.dumps(summary,ensure_ascii=False,indent=2)+'\n';(destination/'manifest.json').write_text(text)
    output=ROOT/'docs/verification';assert not (output/(a.label+'-evidence.zip')).exists() and not (output/(a.label+'-summary.json')).exists()
    shutil.copy2(archive_path,output/(a.label+'-evidence.zip'));(output/(a.label+'-summary.json')).write_text(text)
    print(json.dumps(dict(delivery=str(destination),archive=summary['evidence_archive'],regressions=regressions),ensure_ascii=False,indent=2))

if __name__=='__main__':main()
