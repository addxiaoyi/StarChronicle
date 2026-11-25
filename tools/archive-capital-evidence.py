"""Package exact capital-history runtime, failed attempts, negative startup and build evidence."""
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
SPEC=importlib.util.spec_from_file_location('capital_inspector',Path(__file__).with_name('inspect-capital-paper.py'))
INSPECTOR=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(INSPECTOR)
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def git(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
def suites(root):
    files=sorted((root/'target/surefire-reports').glob('TEST-*.xml'));totals=dict(tests=0,failures=0,errors=0,skipped=0)
    for path in files:
        suite=ET.parse(path).getroot()
        for name in totals:totals[name]+=int(suite.attrib.get(name,'0'))
    assert files and totals['failures']==totals['errors']==0
    return dict(totals=totals,suites=len(files),sha256={p.name:sha(p) for p in files})

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('run','offline-run','failed-run','corrupt-run','native'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--regression-log',type=Path,action='append',required=True);p.add_argument('--log',type=Path,action='append',default=[])
    p.add_argument('--label',default='capital-history-paper-20261125');a=p.parse_args();assert re.fullmatch('[a-z0-9-]+',a.label)
    native=a.native.resolve();runs=[a.run.resolve(),a.offline_run.resolve()];accepted=[INSPECTOR.inspect(r) for r in runs]
    assert [row['mode'] for row in accepted]==['main','offline']
    assert all(row[key]==accepted[0][key] for row in accepted for key in ('chronicle_sha256','starcore_sha256'))
    assert not git(ROOT,'status','--porcelain') and not git(native,'status','--porcelain'),'Commit implementation and tools before archiving'
    failed=a.failed_run.resolve();failed_session=json.loads((failed/'evidence/boot1.session.json').read_text());assert failed_session['status']=='FAILED'
    failure=[r for r in INSPECTOR.records(failed/'evidence/epic-fixture.jsonl') if r['kind']=='FAILED'];assert failure
    negative=a.corrupt_run.resolve();corruption=json.loads((negative/'evidence/corruption-summary.json').read_text())
    assert corruption['status']=='EXPECTED_CAPITAL_CORRUPTION_REJECTED' and corruption['exit_code']==0
    for relative,digest in corruption['preserved_authority_sha256'].items():assert sha(negative/relative)==digest
    regressions=[]
    for path in a.regression_log:
        raw=path.read_text();count=re.search(r'^Ran (\d+) tests in ',raw,re.M);ok=re.search(r'^OK(?: \(skipped=(\d+)\))?$',raw,re.M)
        assert count and ok and not re.search(r'^(?:FAILED|ERROR:|FAIL:)',raw,re.M)
        skipped=int(ok[1] or 0);regressions.append(dict(log=path.name,tests=int(count[1]),passed=int(count[1])-skipped,skipped=skipped,sha256=sha(path)))
    summary=dict(status='ISOLATED_PAPER_CAPITAL_ACCEPTED',captured_utc=datetime.now(timezone.utc).isoformat(),
        chronicle_head=git(ROOT,'rev-parse','HEAD'),native_head=git(native,'rev-parse','HEAD'),
        chronicle_tests=suites(ROOT),native_tests=suites(native),runs=[dict(directory=r.name,**v) for r,v in zip(runs,accepted)],
        regressions=regressions,failed_run=dict(directory=failed.name,failures=failure,session=failed_session),corruption=corruption)
    assert sha(ROOT/'target/starchronicle-0.1.0-SNAPSHOT.jar')==accepted[0]['chronicle_sha256']
    assert sha(native/'target/capital-history-frozen-starcore-1.0.0.jar')==accepted[0]['starcore_sha256']
    destination=ROOT/'target'/a.label;destination.mkdir(parents=True,exist_ok=False)
    for plugin in ('StarChronicle','StarCore'):shutil.copy2(runs[0]/'plugins'/f'{plugin}.jar',destination/f'{plugin}.jar')
    for root,name in ((ROOT,'starchronicle-source.tar.gz'),(native,'starcore-source.tar.gz')):
        with (destination/name).open('xb') as output:subprocess.run(['git','-C',str(root),'archive','--format=tar.gz','HEAD'],stdout=output,check=True)
    redactions=[];archive_path=destination/'evidence.zip'
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        def add(path,name):
            assert not path.is_symlink()
            if path.suffix in ('.yml','.yaml'):
                original=path.read_text();value,count=re.subn(r'(?m)^(\s*(?:access-secret|password|api-key|mineskin_api_key):)\s*.*$',r'\1 "REDACTED_TEST_SECRET"',original)
                if count:redactions.append(dict(path=name,fields=count))
                archive.writestr(name,value)
            else:archive.write(path,name)
        for run in [*runs,failed,negative]:
            files=[run/'preparation.json',run/'server.properties']
            files += [p for p in (run/'evidence').rglob('*') if p.is_file()]
            files += [p for p in (run/'fixture').iterdir() if p.suffix in ('.java','.yml','.yaml','.json')]
            for plugin in ('StarCore','StarChronicle','EpicCampaignPaperFixture'):
                files += [p for p in (run/'plugins'/plugin).rglob('*') if p.is_file() and (p.suffix in ('.db','.yml','.yaml','.json','.properties') or p.name.startswith('capital-designations.log'))]
            for path in sorted(set(files)):add(path,'runs/'+run.name+'/'+path.relative_to(run).as_posix())
        for path in [*a.regression_log,*a.log]:add(path,'logs/'+path.name)
        for root,label in ((ROOT,'chronicle'),(native,'native')):
            for path in sorted((root/'target/surefire-reports').glob('TEST-*.xml')):add(path,'build/'+label+'/'+path.name)
        for name in ('CapitalHistoryPaperFixture.java','EpicCampaignPaperFixture.java','ProductionStackPaperFixture.java',
            'prepare-epic-paper.py','prepare-production-paper.py','prepare-capital-paper.py','prepare-capital-resume.py',
            'epic-paper-session.py','epic-paper-clients.cjs','inspect-capital-paper.py','test_capital_acceptance.py','run-capital-corruption.py','archive-capital-evidence.py'):
            add(ROOT/'tools'/name,'tools/'+name)
        add(ROOT/'docs/verification/production-stack-candidate-20261125.json','production-stack-lock.json')
        summary['redactions']=redactions
        archive.writestr('source-provenance.json',json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
        inventory={entry.filename:hashlib.sha256(archive.read(entry.filename)).hexdigest() for entry in archive.infolist()}
        archive.writestr('evidence-manifest.json',json.dumps(inventory,indent=2)+'\n')
    with zipfile.ZipFile(archive_path) as archive:
        assert archive.testzip() is None and all(hashlib.sha256(archive.read(name)).hexdigest()==digest for name,digest in inventory.items())
    summary['evidence_archive']=dict(sha256=sha(archive_path),bytes=archive_path.stat().st_size,members=len(inventory))
    summary['source_archives']={name:sha(destination/name) for name in ('starchronicle-source.tar.gz','starcore-source.tar.gz')}
    text=json.dumps(summary,ensure_ascii=False,indent=2)+'\n';(destination/'manifest.json').write_text(text)
    output=ROOT/'docs/verification';assert not (output/(a.label+'-evidence.zip')).exists() and not (output/(a.label+'-summary.json')).exists()
    shutil.copy2(archive_path,output/(a.label+'-evidence.zip'));(output/(a.label+'-summary.json')).write_text(text)
    print(json.dumps(dict(delivery=str(destination),archive=summary['evidence_archive'],regressions=regressions,tests={name:summary[name+'_tests']['totals'] for name in ('chronicle','native')}),ensure_ascii=False,indent=2))

if __name__=='__main__':main()
