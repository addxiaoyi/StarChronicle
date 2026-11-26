"""Archive inspected audit interruptions and happy-path regression, with portable byte checks."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,os,re,shutil,subprocess,sys,zipfile
import xml.etree.ElementTree as ET
import yaml

ROOT=Path(__file__).resolve().parents[1]
def require(ok,message):
    if not ok:raise ValueError(message)
def read(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def git(root,*args):return subprocess.check_output(['git','-C',str(root),*args],text=True).strip()
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/name);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def test_result(path):
    text=path.read_text();n=re.search(r'^Ran (\d+) tests in ',text,re.M);ok=re.search(r'^OK(?: \(skipped=(\d+)\))?$',text,re.M)
    require(n and ok,'Evidence tests did not pass: '+str(path));skip=int(ok[1] or 0)
    return dict(tests=int(n[1]),passed=int(n[1])-skip,skipped=skip,sha256=sha(path))
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('completed','player-run','test-logs','frozen-dir','mysql','native','private-env'):p.add_argument('--'+name,type=Path,required=True)
    for name in ('failed-run','retained-directory','log'):p.add_argument('--'+name,type=Path,action='append',default=[])
    p.add_argument('--label',default='player-trade-audit-20261126');a=p.parse_args();require(re.fullmatch('[a-z0-9-]+',a.label),'Invalid label')
    pair=read(a.frozen_dir/'manifest.json')
    for product,root in [('StarCore',a.native),('StarChronicle',ROOT)]:
        require(not git(root,'status','--porcelain'),'Commit the reviewable source before archiving')
        require(not git(root,'diff',pair[product]['source'],'HEAD','--','src','pom.xml'),'Product source drift')
        require(sha(a.frozen_dir/(product+'.jar'))==pair[product]['sha256'],'Frozen artifact drift')
        label='native' if product=='StarCore' else 'chronicle'
        suites=[ET.parse(f).getroot() for f in (a.frozen_dir/(label+'-surefire')).glob('TEST-*.xml')]
        counts={key:sum(int(s.get(key,0)) for s in suites) for key in ('tests','failures','errors','skipped')}
        log=(a.frozen_dir/(label+'-verify.log')).read_text()
        require(counts==pair[product]['tests'] and counts['tests']>0 and counts['failures']==counts['errors']==0 and 'BUILD SUCCESS' in log and 'BUILD FAILURE' not in log,'Frozen build report differs')
    cases=read(a.completed);require({r['case'] for r in cases}=={'debit-before','debit-after','credit-after','refund-after'} and len(cases)==4,'Four audit windows required')
    runs=[(r['case'],Path(r['run'])) for r in cases]+[('player',a.player_run)]
    accepted=[];regressions={}
    for kind,run in runs:
        result=module('inspect-player-trade-paper.py' if kind=='player' else 'inspect-player-trade-audit-paper.py').inspect(run)
        require(result['chronicle_sha256']==pair['StarChronicle']['sha256'] and result['starcore_sha256']==pair['StarCore']['sha256'],'Runtime pair differs')
        accepted.append(dict(kind=kind,directory=run.name,**result));regressions[kind]=test_result(a.test_logs/(kind+'-tests.log'))
    sql=read(a.mysql/'manifest.json');require(sql['exit_code']==0 and sql['source_head']==pair['StarCore']['source'] and sql['tests']['failures']==sql['tests']['errors']==sql['tests']['skipped']==0,'Final MySQL result differs')
    for name,digest in sql['files'].items():require(sha(a.mysql/name)==digest,'MySQL evidence drift')
    stopped=read(a.mysql/'mysql-stopped.json');require(stopped['Status']=='exited' and stopped['ExitCode']==0 and not stopped['OOMKilled'],'MySQL not stopped normally')
    failures=[]
    for run in a.failed_run:
        sessions=[read(p) for p in sorted((run/'evidence').glob('boot*.session.json'))]
        require(sessions and any(s['status']=='FAILED' for s in sessions) and all(p.get('exit_code')==0 for s in sessions for p in s['processes']),'Original failure not stopped/preserved')
        failures.append(dict(directory=run.name,sessions=sessions))
    summary=dict(profile='player-trade-audit-v1',status='READ_ONLY_AUDIT_AND_REAL_WALLET_INTERRUPTION_ACCEPTED',frozen_pair=pair,runs=accepted,regressions=regressions,mysql=sql,failed_runs=failures,
        source_heads={'chronicle':git(ROOT,'rev-parse','HEAD'),'native':git(a.native,'rev-parse','HEAD')},
        limitations=['Vault lacks queryable idempotent payment receipts; UNKNOWN remains unresolved.',
            'Four real-call JVM interruptions, with observed Essentials file balances, are not physical power-loss or general external durability proof.',
            'Chronicle product/build is reused byte-for-byte; this increment has no Chronicle product changes.',
            'Selected Paper/SQLite runtime and separate actual MySQL tests; no new Folia, production deployment or complete ten-stage claim.'])
    destination=ROOT/'target'/a.label;destination.mkdir(exist_ok=False)
    secrets=[]
    for line in a.private_env.read_text().splitlines():
        if '=' in line:
            key,value=line.split('=',1)
            if re.search('password|secret',key,re.I) and len(value)>=8:secrets.append(value.encode())
    require(secrets,'Actual private credential scan required')
    # Configurations are not included. Collect their actual secret values for a final byte scan.
    for _,run in runs+[(r.name,r) for r in a.failed_run]:
        configs=[run/'server.properties',run/'plugins/StarCore/config.yml',run/'plugins/StarChronicle/config.yml']
        for f in configs:
            if not f.exists():continue
            for match in re.finditer(r'(?mi)^\s*(?:access-secret|password|api-key|management-server-secret|rcon\.password)\s*[:=]\s*([^\n]+)',f.read_text()):
                value=match[1].strip().strip('\"\'')
                if len(value)>=16:secrets.append(value.encode())
    evidence=destination/'evidence.zip'
    with zipfile.ZipFile(evidence,'x',zipfile.ZIP_DEFLATED) as z:
        def add(f,name):
            require(f.is_file() and not f.is_symlink() and name not in z.namelist(),'Unsafe archive member')
            raw=f.read_bytes();require(not any(s in raw for s in secrets),'Private credential detected')
            z.writestr(name,raw)
        for _,run in runs+[(r.name,r) for r in a.failed_run]:
            files=[run/'preparation.json']+[p for p in (run/'evidence').rglob('*') if p.is_file()]
            files += list((run/'fixture').glob('*.java'))
            files += [run/'plugins'/plugin/name for plugin,name in [('StarCore','starcore.db'),('StarChronicle','chronicle.db')]]
            for f in sorted(set(files)):add(f,'runs/'+run.name+'/'+f.relative_to(run).as_posix())
        for label in ('native','chronicle'):
            add(a.frozen_dir/(label+'-verify.log'),'build/'+label+'/verify.log')
            for f in sorted((a.frozen_dir/(label+'-surefire')).iterdir()):
                if f.is_file():add(f,'build/'+label+'/surefire/'+f.name)
        for folder,prefix in [(a.mysql,'mysql'),(a.test_logs,'test-logs'),*[(d,'retained/'+d.name) for d in a.retained_directory]]:
            for f in sorted(folder.rglob('*')):
                if f.is_file():add(f,prefix+'/'+f.relative_to(folder).as_posix())
        for f in a.log:add(f,'logs/'+f.name)
        for f in sorted((ROOT/'tools').iterdir()):
            if f.is_file() and f.suffix in ('.py','.java','.cjs'):add(f,'tools/'+f.name)
        z.writestr('source-provenance.json',json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
        inventory={x.filename:hashlib.sha256(z.read(x.filename)).hexdigest() for x in z.infolist()}
        z.writestr('evidence-manifest.json',json.dumps(inventory,indent=2)+'\n')
    with zipfile.ZipFile(evidence) as z:
        require(z.testzip() is None and all(hashlib.sha256(z.read(k)).hexdigest()==v for k,v in inventory.items()),'Evidence bytes changed')
        require(all(not any(s in z.read(item.filename) for s in secrets) for item in z.infolist()),'Final private credential scan failed')
    binaries=destination/'exact-binaries.zip';bm=dict(format=1,evidence_sha256=sha(evidence),artifacts={},runs={})
    with zipfile.ZipFile(binaries,'x',zipfile.ZIP_STORED) as z:
        for _,run in runs:
            files=['paper.jar','plugins/StarCore.jar','plugins/StarChronicle.jar','plugins/EpicCampaignPaperFixture.jar']+['plugins/'+v['filename'] for v in read(run/'preparation.json')['production_stack']['artifacts']]
            mapping={}
            for name in files:
                raw=(run/name).read_bytes();digest=hashlib.sha256(raw).hexdigest();mapping[name]=digest
                if digest not in bm['artifacts']:z.writestr('blobs/'+digest+'.jar',raw);bm['artifacts'][digest]=len(raw)
            bm['runs'][run.name]=mapping
        z.writestr('binary-manifest.json',json.dumps(bm,indent=2)+'\n')
    with (destination/'transferred-check.log').open('x') as log:
        result=subprocess.run([sys.executable,str(ROOT/'tools/verify-player-trade-handoff.py'),'--evidence',str(evidence),'--binaries',str(binaries),'--output',str(destination/'transferred-check')],stdout=log,stderr=subprocess.STDOUT)
    require(result.returncode==0,'Portable verification failed; original package retained')
    summary.update(archive=dict(sha256=sha(evidence),bytes=evidence.stat().st_size,members=len(inventory)),
        binary_bundle=dict(sha256=sha(binaries),bytes=binaries.stat().st_size,public=False),
        portable_verification=read(destination/'transferred-check/verification.json'),credential_scan='Actual private environment/config secret bytes absent; configurations omitted.')
    public=ROOT/'docs/verification';dest=public/(a.label+'-evidence.zip');require(not dest.exists(),'Refusing to replace published evidence')
    shutil.copy2(evidence,dest)
    data=json.dumps(summary,ensure_ascii=False,indent=2)+'\n';(public/(a.label+'-summary.json')).write_text(data);(destination/'manifest.json').write_text(data)
    print(json.dumps({'destination':str(destination),'archive':summary['archive'],'binaries':summary['binary_bundle']},indent=2))
if __name__=='__main__':main()
