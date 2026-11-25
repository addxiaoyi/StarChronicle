"""Package stopped production-stack evidence with per-member hashes and explicit build provenance."""
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
SPEC=importlib.util.spec_from_file_location('production_inspector',Path(__file__).with_name('inspect-production-paper.py'))
INSPECTOR=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(INSPECTOR)


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def git(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',type=Path,required=True)
    parser.add_argument('--failed-run',type=Path,action='append',default=[])
    parser.add_argument('--native',type=Path,required=True)
    parser.add_argument('--native-jar',type=Path,required=True)
    parser.add_argument('--native-reports',type=Path,required=True)
    parser.add_argument('--native-build-log',type=Path,required=True)
    parser.add_argument('--log',type=Path,action='append',default=[])
    parser.add_argument('--label',default='production-stack-20261125')
    args=parser.parse_args()
    if not re.fullmatch('[a-z0-9-]+',args.label):raise SystemExit('Invalid archive label')
    result=INSPECTOR.inspect(args.run)
    native=args.native.resolve();run=args.run.resolve()
    if sha(args.native_jar)!=result['starcore_sha256']:raise SystemExit('Native artifact mismatch')
    if git(native,'status','--porcelain') or git(ROOT,'status','--porcelain'):
        raise SystemExit('Commit source/tool changes before recording source provenance')
    dest=ROOT/'target'/args.label;dest.mkdir(exist_ok=False,parents=True)
    suites=[]
    for path in sorted(args.native_reports.glob('TEST-*.xml')):
        r=ET.parse(path).getroot();suites.append(dict(name=r.get('name'),**{k:int(r.get(k,'0')) for k in ('tests','failures','errors','skipped')},sha256=sha(path)))
    totals={k:sum(row[k] for row in suites) for k in ('tests','failures','errors','skipped')}
    log=args.native_build_log.read_text()
    if f"Tests run: {totals['tests']}, Failures: {totals['failures']}, Errors: {totals['errors']}, Skipped: {totals['skipped']}" not in log or 'BUILD SUCCESS' not in log or totals['failures'] or totals['errors']:
        raise SystemExit('Build log/report totals are inconsistent or failing')
    summary=dict(status='ISOLATED_PAPER_ACCEPTED',chronicle_head=git(ROOT,'rev-parse','HEAD'),native_head=git(native,'rev-parse','HEAD'),acceptance=result,native_tests=dict(totals=totals,suites=suites),failed_runs=[])
    for old in args.failed_run:
        data=INSPECTOR.records(old/'evidence/epic-fixture.jsonl');failures=[r for r in data if r['kind']=='FAILED']
        if not failures:raise SystemExit('Failed run must retain its actual failure')
        summary['failed_runs'].append(dict(path=str(old.resolve()),failures=failures))
    shutil.copy2(args.native_jar,dest/'StarCore.jar');shutil.copy2(run/'plugins/StarChronicle.jar',dest/'StarChronicle.jar')
    for root,name in ((native,'starcore-source.tar.gz'),(ROOT,'starchronicle-source.tar.gz')):
        with (dest/name).open('xb') as out:subprocess.run(['git','-C',str(root),'archive','--format=tar.gz','HEAD'],stdout=out,check=True)
    archive_path=dest/'evidence.zip'
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        def add(path,name):
            if path.is_symlink():raise SystemExit('Refusing symlink evidence: '+str(path))
            if path.name=='config.yml':
                raw=path.read_text()
                raw=re.sub(r'(?m)^(\s*(?:access-secret|password|api-key|mineskin_api_key):)\s*.*$',r'\1 "REDACTED_TEST_SECRET"',raw)
                archive.writestr(name,raw)
            else:archive.write(path,name)
        for current in [run,*args.failed_run]:
            prefix='runs/'+current.name+'/'
            paths=[current/'preparation.json']+list((current/'evidence').rglob('*'))+list((current/'fixture').glob('*.java'))
            for relative in ('plugins/StarCore/config.yml','plugins/StarCore/starcore.db','plugins/StarChronicle/config.yml','plugins/StarChronicle/npcs.yml','plugins/StarChronicle/chronicle.db','plugins/EpicCampaignPaperFixture/config.yml'):
                if (current/relative).exists():paths.append(current/relative)
            for relative in ('plugins/Essentials/userdata','plugins/WorldGuard/worlds','plugins/FancyNpcs'):
                paths.extend(path for path in (current/relative).rglob('*') if path.suffix in ('.yml','.yaml','.json') and path.is_file())
            for path in sorted(set(paths)):
                if path.is_file():add(path,prefix+path.relative_to(current).as_posix())
        for path in sorted(args.native_reports.glob('*')):
            if path.is_file():add(path,'native-tests/'+path.name)
        for path in [args.native_build_log,*args.log]:add(path,'logs/'+path.name)
        for name in ('bom.json','bom.xml'):
            if (native/'target'/name).is_file():add(native/'target'/name,'native-sbom/'+name)
        for name in ('EpicCampaignPaperFixture.java','ProductionStackPaperFixture.java','prepare-epic-paper.py','prepare-production-paper.py','epic-paper-session.py','epic-paper-clients.cjs','inspect-production-paper.py','test_production_acceptance.py','inspect-trade-paper.py','inspect-epic-paper.py','inspect-quest-evidence-paper.py','archive-production-evidence.py'):
            add(ROOT/'tools'/name,'tools/'+name)
        add(ROOT/'docs/verification/production-stack-candidate-20261125.json','production-stack-lock.json')
        archive.writestr('source-provenance.json',json.dumps({k:summary[k] for k in ('chronicle_head','native_head')},indent=2)+'\n')
        inventory={i.filename:hashlib.sha256(archive.read(i.filename)).hexdigest() for i in archive.infolist()}
        archive.writestr('evidence-manifest.json',json.dumps(inventory,indent=2)+'\n')
    summary['evidence_archive']=dict(sha256=sha(archive_path),bytes=archive_path.stat().st_size,members=len(inventory))
    summary['source_archives']={name:sha(dest/name) for name in ('starcore-source.tar.gz','starchronicle-source.tar.gz')}
    text=json.dumps(summary,ensure_ascii=False,indent=2)+'\n';(dest/'manifest.json').write_text(text)
    output=ROOT/'docs/verification';shutil.copy2(archive_path,output/(args.label+'-evidence.zip'))
    with (output/(args.label+'-summary.json')).open('x') as out:out.write(text)
    print(json.dumps(dict(delivery=str(dest),archive=summary['evidence_archive'],native=totals,acceptance=result),ensure_ascii=False))


if __name__=='__main__':main()
