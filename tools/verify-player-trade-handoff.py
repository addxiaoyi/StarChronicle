"""Recheck a transferred player-trade evidence ZIP with its separate exact binary bundle.

Creates a fresh output directory, validates every archived byte, then runs the seven
inspectors and evidence tests for its declared profile. No server starts and no original run path is required.
"""
from pathlib import Path,PurePosixPath
import argparse,hashlib,json,os,re,stat,subprocess,sys,zipfile

CASES={
    'player':('inspect-player-trade-paper.py','test_player_trade_acceptance.py','STARCHRONICLE_PLAYER_TRADE_RUN'),
    'dungeon':('inspect-epic-production-paper.py','test_epic_production_acceptance.py','STARCHRONICLE_EPIC_RUN'),
    'war':('inspect-battlefield-paper.py','test_battlefield_acceptance.py','STARCHRONICLE_EPIC_RUN'),
    'trade':('inspect-nation-trade-paper.py','test_nation_trade_acceptance.py','STARCHRONICLE_NATION_TRADE_RUN'),
    'factory':('inspect-nation-factory-paper.py','test_nation_factory_acceptance.py','STARCHRONICLE_FACTORY_RUN'),
    'production':('inspect-nation-production-paper.py','test_nation_production_acceptance.py','STARCHRONICLE_NATION_PRODUCTION_RUN'),
    'offline':('inspect-nation-production-paper.py','test_nation_production_acceptance.py','STARCHRONICLE_NATION_PRODUCTION_RUN'),
}
AUDIT_CASES={name:('inspect-player-trade-audit-paper.py','test_player_trade_audit_acceptance.py','STARCHRONICLE_PLAYER_TRADE_AUDIT_RUN') for name in ('debit-before','debit-after','credit-after','refund-after')}
AUDIT_CASES['player']=CASES['player']
def require(value,message):
    if not value:raise ValueError(message)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def safe_path(name):
    p=PurePosixPath(name)
    require(name and not p.is_absolute() and '..' not in p.parts and '\\' not in name and ':' not in name and str(p)==name,'Unsafe archive path')
    return p
def members(archive):
    names=archive.namelist();require(len(names)==len(set(names)),'Duplicate archive member')
    for item in archive.infolist():
        safe_path(item.filename)
        require(not stat.S_ISLNK(item.external_attr>>16) and not item.is_dir(),'Unexpected archive link/directory')
    require(archive.testzip() is None,'Corrupt ZIP')
    return set(names)
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('evidence','binaries','output'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();out=a.output.resolve();out.mkdir(exist_ok=False)
    with zipfile.ZipFile(a.evidence) as evidence,zipfile.ZipFile(a.binaries) as binaries:
        names=members(evidence);binary_names=members(binaries)
        inventory=json.loads(evidence.read('evidence-manifest.json'));manifest=json.loads(binaries.read('binary-manifest.json'))
        require(set(inventory)|{'evidence-manifest.json'}==names,'Unmanifested evidence')
        require(manifest['format']==1 and manifest['evidence_sha256']==sha(a.evidence.read_bytes()),'Wrong evidence/binary pair')
        require({'blobs/'+key+'.jar' for key in manifest['artifacts']}|{'binary-manifest.json'}==binary_names,'Unmanifested binary')
        for name,digest in inventory.items():require(sha(evidence.read(name))==digest,'Evidence hash mismatch: '+name)
        for digest,size in manifest['artifacts'].items():
            require(re.fullmatch('[0-9a-f]{64}',digest),'Invalid binary digest')
            raw=binaries.read('blobs/'+digest+'.jar');require(len(raw)==size and sha(raw)==digest,'Binary hash mismatch')
        provenance=json.loads(evidence.read('source-provenance.json'));runs=provenance['runs']
        cases=AUDIT_CASES if provenance.get('profile')=='player-trade-audit-v1' else CASES
        require({r['kind'] for r in runs}==set(cases) and len(runs)==len(cases),'Declared accepted scenarios required')
        require(set(manifest['runs'])=={r['directory'] for r in runs},'Binary scenario set differs')
        for item in evidence.infolist():
            target=out/'evidence'/item.filename;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(evidence.read(item.filename))
        for r in runs:
            require(len(safe_path(r['directory']).parts)==1,'Unsafe scenario directory')
            run=out/'evidence/runs'/r['directory'];prepared=json.loads((run/'preparation.json').read_text())
            expected={'paper.jar':next(d for n,d in prepared['source_artifacts'].items() if n.endswith('/paper.jar')),
                'plugins/StarCore.jar':r['starcore_sha256'],'plugins/StarChronicle.jar':r['chronicle_sha256'],
                'plugins/EpicCampaignPaperFixture.jar':prepared['fixture_sha256'],
                **{'plugins/'+v['filename']:v['sha256'] for v in prepared['production_stack']['artifacts']}}
            require(manifest['runs'][r['directory']]==expected,'Binary mapping differs from accepted preparation')
            for name,digest in expected.items():
                safe_path(name);target=run/name;require(not target.exists(),'Refusing to replace extracted evidence')
                target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(binaries.read('blobs/'+digest+'.jar'))
    results={};tool=out/'evidence/tools'
    for r in runs:
        name=r['kind'];run=out/'evidence/runs'/r['directory'];inspector,test,variable=cases[name]
        for kind,command,env in [('inspect',[sys.executable,str(tool/inspector),str(run)],None),('tests',[sys.executable,'-m','unittest','discover','-s',str(tool),'-p',test,'-v'],dict(os.environ,**{variable:str(run)}))]:
            log=out/(name+'-'+kind+'.log')
            with log.open('xb') as output:code=subprocess.run(command,env=env,cwd=out,stdout=output,stderr=subprocess.STDOUT).returncode
            require(code==0,'Transferred '+name+' '+kind+' failed; retained '+str(log))
            if kind=='inspect':require(json.loads(log.read_text())=={k:v for k,v in r.items() if k not in ('kind','directory')},'Transferred result differs')
            else:
                raw=log.read_text();count=re.search(r'^Ran (\d+) tests in ',raw,re.M);ok=re.search(r'^OK(?: \(skipped=(\d+)\))?$',raw,re.M)
                require(count and ok,'Missing transferred test summary');skips=int(ok[1] or 0)
                result=dict(tests=int(count[1]),passed=int(count[1])-skips,skipped=skips)
                require(all(result[k]==provenance['regressions'][name][k] for k in result),'Transferred test counts differ');results[name]=result
    summary=dict(status='TRANSFERRED_BYTES_AND_AUDIT_SCENARIOS_VERIFIED' if cases is AUDIT_CASES else 'TRANSFERRED_BYTES_AND_SEVEN_SCENARIOS_VERIFIED',evidence_sha256=sha(a.evidence.read_bytes()),binaries_sha256=sha(a.binaries.read_bytes()),regressions=results,
        limitation='Offline inspection of transferred stopped evidence on this machine; no new game server, physical power loss or another device execution claimed.')
    (out/'verification.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
if __name__=='__main__':main()
