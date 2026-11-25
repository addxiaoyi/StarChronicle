"""Archive paid factory acceptance and the exact pair's five selected-stack regressions.

Keep original failed attempts, verify extracted inspectors and mutation tests, and scan
private credential bytes before publishing. Worlds and third-party JARs remain local.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse, hashlib, importlib.util, json, os, re, shlex, shutil, subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PRODUCT = dict(chronicle='fc08038d5e9016ad04280552a3a641193c0971c4', native='5c7eb07d9aab43b80eaf83aaee3d5c5b69615b3e')
BASE = dict(chronicle='4980a19ba52cc7708d60cc601111ade6ecd9f08f', native='3391555e99e8a673a2adfd23fa9f3081be031583')
CASES = [
    ('factory', 'inspect-nation-factory-paper.py', 'test_nation_factory_acceptance.py', 'STARCHRONICLE_FACTORY_RUN'),
    ('production', 'inspect-nation-production-paper.py', 'test_nation_production_acceptance.py', 'STARCHRONICLE_NATION_PRODUCTION_RUN'),
    ('offline', 'inspect-nation-production-paper.py', 'test_nation_production_acceptance.py', 'STARCHRONICLE_NATION_PRODUCTION_RUN'),
    ('trade', 'inspect-nation-trade-paper.py', 'test_nation_trade_acceptance.py', 'STARCHRONICLE_NATION_TRADE_RUN'),
    ('dungeon', 'inspect-epic-production-paper.py', 'test_epic_production_acceptance.py', 'STARCHRONICLE_EPIC_RUN'),
    ('war', 'inspect-battlefield-paper.py', 'test_battlefield_acceptance.py', 'STARCHRONICLE_EPIC_RUN'),
]

def require(value, message):
    if not value: raise ValueError(message)

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads(path.read_text())
def git(root, *args): return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()

def inspector(name):
    spec = importlib.util.spec_from_file_location(name, ROOT/'tools'/name)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module

def build(frozen, name):
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    suites = sorted((frozen/(name+'-surefire')).glob('TEST-*.xml'))
    for path in suites:
        suite = ET.parse(path).getroot()
        for key in counts: counts[key] += int(suite.get(key, 0))
    expected = dict(tests=7465 if name=='native' else 988, failures=0, errors=0, skipped=47 if name=='native' else 0)
    log = frozen/(name+'-verify.log'); raw = log.read_text()
    require(suites and counts==expected and 'BUILD SUCCESS' in raw and 'BUILD FAILURE' not in raw, 'Incomplete full build: '+name)
    require(re.search(r'Tests run: '+str(counts['tests'])+r', Failures: 0, Errors: 0, Skipped: '+str(counts['skipped']), raw), 'Build log/XML counts differ')
    return dict(counts=counts, suites=len(suites), log_sha256=sha(log))

def evidence_test(path):
    raw = path.read_text(); count = re.search(r'^Ran (\d+) tests in ', raw, re.M)
    ok = re.search(r'^OK(?: \(skipped=(\d+)\))?$', raw, re.M)
    require(count and ok and not re.search(r'^(?:FAILED|ERROR:|FAIL:)', raw, re.M), 'Evidence tests failed: '+path.name)
    skipped = int(ok[1] or 0)
    return dict(log=path.name, tests=int(count[1]), passed=int(count[1])-skipped, skipped=skipped, sha256=sha(path))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name, *_ in CASES: parser.add_argument('--'+name+'-run', type=Path, required=True)
    for name in ('native', 'mysql-evidence', 'regression-directory', 'factory-test-log', 'private-env'):
        parser.add_argument('--'+name, type=Path, required=True)
    for name in ('failed-run', 'retained-preparation', 'retained-directory', 'log'):
        parser.add_argument('--'+name, type=Path, action='append', default=[])
    parser.add_argument('--label', default='nation-factories-20261125-final')
    args = parser.parse_args(); native = args.native.resolve(); frozen = ROOT/'target/nation-factories-frozen'
    require(re.fullmatch('[a-z0-9-]+', args.label), 'Invalid archive label')
    for name, root in [('chronicle', ROOT), ('native', native)]:
        require(not git(root, 'status', '--porcelain'), 'Commit sources/tools before archiving')
        require(not git(root, 'diff', PRODUCT[name], 'HEAD', '--', 'src', 'pom.xml'), 'Source changed after frozen build')
    runs = [getattr(args, name+'_run').resolve() for name, *_ in CASES]
    results = [inspector(tool).inspect(run) for (_, tool, *_), run in zip(CASES, runs)]
    for result in results:
        for key in ('chronicle_sha256', 'starcore_sha256'):
            require(result[key]==results[0][key], 'Changed live pair')
    manifest = read(frozen/'manifest.json')
    for plugin, name, key in [('StarCore', 'native', 'starcore_sha256'), ('StarChronicle', 'chronicle', 'chronicle_sha256')]:
        require(sha(frozen/(plugin+'.jar'))==manifest[plugin]['sha256']==results[0][key] and manifest[plugin]['source_head']==PRODUCT[name], 'Frozen artifact provenance differs')
    mysql = args.mysql_evidence.resolve(); sql = read(mysql/'manifest.json')
    require(sql['status']=='PASSED' and sql['exit_code']==0 and sql['tests']==dict(tests=56, failures=0, errors=0, skipped=0), 'Incomplete MySQL tests')
    for name, digest in sql['files'].items(): require(sha(mysql/name)==digest, 'Changed MySQL evidence')
    test_source = native/'src/test/java/dev/starcore/starcore/module/resource/MySqlNationSettlementIntegrationTest.java'
    require(sha(test_source)==sha(mysql/test_source.name), 'Changed MySQL source')
    stopped = read(mysql/'mysql-stopped.json'); state = stopped['state']
    require(state['Status']=='exited' and not state['Running'] and not state['OOMKilled'] and state['ExitCode']==0, 'MySQL did not stop normally')
    runtime = (mysql/'mysql-final-runtime.txt').read_text()
    require('8.4.13\tInnoDB\tREPEATABLE-READ\t1\t1' in runtime and runtime.count('\tInnoDB\t0')==13, 'MySQL final runtime audit differs')
    regressions = [evidence_test(path) for path in [args.factory_test_log, *sorted(args.regression_directory.glob('*-evidence-tests.log'))]]
    require(len(regressions)==6, 'Missing evidence regression suite')
    failures=[]
    for run in args.failed_run:
        sessions=[read(path) for path in sorted((run/'evidence').glob('boot*.session.json'))]
        require(sessions and any(session['status']=='FAILED' for session in sessions), 'Failed attempt has no original failed session')
        require(all(p['exit_code']==0 for s in sessions for p in s['processes'] if p['name']=='server'), 'Failed server did not stop normally')
        failures.append(dict(directory=run.name, sessions=sessions))
    summary=dict(status='NATION_FACTORIES_AND_FROZEN_PAIR_SELECTED_STACK_ACCEPTED', captured_utc=datetime.now(timezone.utc).isoformat(),
        source_heads=dict(chronicle=git(ROOT,'rev-parse','HEAD'),native=git(native,'rev-parse','HEAD')), product_sources=PRODUCT,
        runs=[dict(kind=case[0],directory=run.name,**result) for case,run,result in zip(CASES,runs,results)],
        builds={name:build(frozen,name) for name in ('chronicle','native')},mysql=sql,mysql_shutdown=stopped,
        mysql_runtime_note='TABLE_ROWS is approximate metadata; test teardown independently requires empty tables. The precommit HEAD in the original MySQL manifest is retained; the copied test source hash matches the final product commit.',
        regressions=regressions,failed_runs=failures,retained_preparations=[run.name for run in args.retained_preparation])
    dest=ROOT/'target'/args.label;dest.mkdir(exist_ok=False)
    for plugin in ('StarCore','StarChronicle'):shutil.copy2(frozen/(plugin+'.jar'),dest/(plugin+'.jar'))
    for root,name in [(ROOT,'starchronicle-source.tar.gz'),(native,'starcore-source.tar.gz')]:
        with (dest/name).open('xb') as out:subprocess.run(['git','-C',str(root),'archive','--format=tar.gz','HEAD'],stdout=out,check=True)
    # Read actual generated credentials only to reject accidental inclusion; never log them.
    secrets=set()
    for line in args.private_env.read_text().splitlines():
        if '=' not in line or line.lstrip().startswith('#'):continue
        key,value=line.split('=',1)
        if re.search('password|secret',key,re.I):
            parts=shlex.split(value)
            if parts and len(parts[0])>=8:secrets.add(parts[0].encode())
    require(secrets,'Private credential scan has no inputs')
    redactions=[];archive_path=dest/'evidence.zip'
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        def add(path,name):
            require(path.is_file() and not path.is_symlink() and name not in archive.namelist(), 'Unsafe/duplicate evidence: '+name)
            raw=path.read_bytes()
            if path.suffix in ('.yml','.yaml'):
                pattern=r'(?m)^(\s*(?:access-secret|password|api-key|mineskin_api_key):)\s*([^\n]*)$'
                for match in re.finditer(pattern,raw.decode()):
                    value=match[2].strip().strip('\"\'')
                    if len(value)>=16 and value!='REDACTED_TEST_SECRET':secrets.add(value.encode())
                text,count=re.subn(pattern,r'\1 "REDACTED_TEST_SECRET"',raw.decode());raw=text.encode()
                if count:redactions.append(dict(path=name,fields=count))
            archive.writestr(name,raw)
        for run in [*runs,*args.failed_run,*args.retained_preparation]:
            files=[p for p in (run/'preparation.json',run/'server.properties') if p.is_file()]
            files += [p for p in (run/'evidence').rglob('*') if p.is_file()]
            files += [p for p in (run/'fixture').glob('*') if p.suffix in ('.java','.json','.yml','.yaml')]
            for plugin in ('StarCore','StarChronicle','EpicCampaignPaperFixture','Essentials'):
                folder=run/'plugins'/plugin
                files += [p for p in folder.rglob('*') if p.is_file() and (p.suffix in ('.db','.yml','.yaml','.json','.properties') or 'quest' in p.relative_to(folder).parts or p.name=='authority-domain.txt' or p.name.startswith('capital-designations.log'))]
            for path in sorted(set(files)):add(path,'runs/'+run.name+'/'+path.relative_to(run).as_posix())
        for name in ('native','chronicle'):
            add(frozen/(name+'-verify.log'),'build/'+name+'/full-verify.log')
            for path in sorted((frozen/(name+'-surefire')).iterdir()):
                if path.is_file():add(path,'build/'+name+'/surefire/'+path.name)
        add(frozen/'manifest.json','frozen-pair.json')
        for folder,prefix in [(mysql,'mysql'),(args.regression_directory,'regressions'),*[(p,'retained/'+p.name) for p in args.retained_directory]]:
            for path in sorted(folder.rglob('*')):
                if path.is_file():add(path,prefix+'/'+path.relative_to(folder).as_posix())
        for path in dict.fromkeys([args.factory_test_log,*args.log]):add(path,'logs/'+path.name)
        for path in sorted((ROOT/'tools').iterdir()):
            if path.is_file() and path.suffix in ('.py','.java','.cjs'):add(path,'tools/'+path.name)
        for path in sorted((native/'tools').glob('*nation*mysql*.py')):add(path,'native-tools/'+path.name)
        for name,root in [('chronicle',ROOT),('native',native)]:
            for relative in git(root,'diff','--name-only',BASE[name],PRODUCT[name],'--','src','pom.xml').splitlines():
                add(root/relative,name+'-source/'+relative)
        add(ROOT/'docs/verification/production-stack-candidate-20261125.json','production-stack-lock.json')
        summary['redactions']=redactions
        archive.writestr('source-provenance.json',json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
        inventory={entry.filename:hashlib.sha256(archive.read(entry.filename)).hexdigest() for entry in archive.infolist()}
        archive.writestr('evidence-manifest.json',json.dumps(inventory,indent=2)+'\n')
    with zipfile.ZipFile(archive_path) as archive:
        require(archive.testzip() is None,'Corrupt archive')
        for name,digest in inventory.items():
            raw=archive.read(name);require(hashlib.sha256(raw).hexdigest()==digest,'Archive member changed')
            require(not any(secret in raw for secret in secrets),'Private credential appears in member: '+name)
    extracted=dest/'extracted-check';extracted.mkdir(exist_ok=False)
    with zipfile.ZipFile(archive_path) as archive:archive.extractall(extracted)
    extracted_tests=[]
    for (name,tool,test,variable),run in zip(CASES,runs):
        copied=extracted/'runs'/run.name
        binaries=['paper.jar','plugins/StarCore.jar','plugins/StarChronicle.jar','plugins/EpicCampaignPaperFixture.jar']
        binaries += ['plugins/'+a['filename'] for a in read(run/'preparation.json')['production_stack']['artifacts']]
        for binary in binaries:
            target=copied/binary;target.parent.mkdir(parents=True,exist_ok=True);target.symlink_to(run/binary)
        checks=[('inspect',['python3',str(extracted/'tools'/tool),str(copied)],None),
                ('tests',['python3','-m','unittest','discover','-s',str(extracted/'tools'),'-p',test,'-v'],dict(os.environ,**{variable:str(copied)}))]
        for kind,command,env in checks:
            path=dest/(name+'-extracted-'+kind+'.log')
            with path.open('xb') as log:code=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT).returncode
            require(code==0,'Extracted '+name+' '+kind+' failed; keep unpublished attempt')
            if kind=='tests':extracted_tests.append(evidence_test(path))
    summary['extracted_inspectors']='ALL_SIX_PASSED';summary['extracted_regressions']=extracted_tests
    summary['credential_scan']='No actual private environment or captured configuration credential bytes found'
    summary['evidence_archive']=dict(sha256=sha(archive_path),bytes=archive_path.stat().st_size,members=len(inventory))
    summary['source_archives']={name:sha(dest/name) for name in ('starchronicle-source.tar.gz','starcore-source.tar.gz')}
    data=json.dumps(summary,ensure_ascii=False,indent=2)+'\n';(dest/'manifest.json').write_text(data)
    public=ROOT/'docs/verification'
    require(not (public/(args.label+'-evidence.zip')).exists() and not (public/(args.label+'-summary.json')).exists(),'Public evidence already exists')
    shutil.copy2(archive_path,public/(args.label+'-evidence.zip'));(public/(args.label+'-summary.json')).write_text(data)
    print(json.dumps(dict(delivery=str(dest),archive=summary['evidence_archive'],regressions=regressions,extracted_regressions=extracted_tests),indent=2))

if __name__=='__main__':main()
