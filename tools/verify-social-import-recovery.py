"""Actual legacy exports and exact-JAR social import halts/recovery in serial offline JVMs."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]

def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ('old-jar','new-jar','java-home','test-xml','output'):parser.add_argument('--'+key,type=Path,required=True)
    args=parser.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    old,new=args.old_jar.resolve(),args.new_jar.resolve();java=args.java_home/'bin/java.exe';javac=args.java_home/'bin/javac.exe'
    properties=ET.parse(args.test_xml).getroot().find('properties')
    captured=next(p.get('value') for p in properties if p.get('name')=='surefire.test.class.path')
    deps=[Path(p) for p in captured.split(os.pathsep) if p.endswith('.jar')];assert all(p.is_file() for p in deps)
    agent=next(p for p in deps if p.name.startswith('mockito-core-'))
    sources=[ROOT/'tools/SocialImportLegacyProbe.java',ROOT/'tools/SocialImportOfflineProbe.java']
    result={'status':'RUNNING','server_started':False,'jars':{str(p):digest(p) for p in (old,new)},'probes':{p.name:digest(p) for p in sources},'runs':[],'checks':[]}
    def argfile(name,values):
        path=out/(name+'.args');path.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8');return path
    def cp(product):return os.pathsep.join(str(p) for p in [product,out,*deps])
    def run(case,phase,mode='file',legacy=False):
        folder=out/case;folder.mkdir(exist_ok=True);label=f'{len(result["runs"])+1}-{case}-{phase}'
        entry='SocialImportLegacyProbe' if legacy else 'dev.starcore.starcore.social.SocialImportOfflineProbe'
        product=old if legacy else new
        values=['-Xms8m','-Xmx96m','-XX:MaxMetaspaceSize=128m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-Dfile.encoding=UTF-8','-javaagent:'+str(agent),'-cp',cp(product),entry]
        values+= [folder] if legacy else [phase,folder,mode]
        process=subprocess.run([str(java),'@'+str(argfile(label,values))],capture_output=True,timeout=120)
        (out/(label+'.log')).write_bytes(process.stdout+process.stderr)
        result['runs'].append({'case':case,'phase':phase,'mode':mode,'jar':str(product),'exit_code':process.returncode})
        assert process.returncode==0,(label,'See preserved log')
        lines=[line for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')]
        assert len(lines)==1 and json.loads(lines[0])['verified'],label
    def files(folder):return {p.relative_to(folder).as_posix():digest(p) for p in folder.rglob('*') if p.is_file()}
    def receipts(folder):
        journal=folder/'social_imports';plans=list(journal.glob('prepare-*.json'));completed=list(journal.glob('complete-*.json'))
        assert len(plans)==len(completed)==1
        plan=json.loads(plans[0].read_text());receipt=json.loads(completed[0].read_text())
        assert receipt['id']==plan['id'] and receipt['schema']==1
        compact=json.dumps(plan,ensure_ascii=False,separators=(',',':')).encode()
        assert hashlib.sha256(compact).hexdigest()==receipt['sha256']
        anchor=json.loads((folder/'social_import_anchors'/('anchor-'+plan['id']+'.json')).read_text())
        assert anchor==receipt
    try:
        process=subprocess.run([str(javac),'-J-Xmx96m','-J-XX:ActiveProcessorCount=1','@'+str(argfile('compile',['-encoding','UTF-8','-proc:none','--release','21','-cp',cp(new),'-d',out,*sources]))],capture_output=True,timeout=120)
        (out/'compile.log').write_bytes(process.stdout+process.stderr);assert process.returncode==0,'Probe compile failed'
        run('legacy','legacy-export',legacy=True);original=digest(out/'legacy/social_exports/legacy.json')
        run('legacy','legacy-import');run('legacy','legacy-reopen');run('legacy','legacy-reopen');receipts(out/'legacy')
        assert digest(out/'legacy/social_exports/legacy.json')==original
        result['checks'].append('Frozen version-1 exporter input retains exact original bytes, group identities/creation times and roles; missing requests/blacklist/invites are not invented')
        for mode in ('file','sql','mixed'):
            for boundary in ('prepared','friends','guilds','parties','complete'):
                case=mode+'-'+boundary
                run(case,'halt-'+boundary,mode)
                run(case,'recover',mode);receipts(out/case)
                plan=next((out/case/'social_imports').glob('prepare-*.json'));original=digest(plan)
                run(case,'recover',mode);assert digest(plan)==original;receipts(out/case)
            result['checks'].append(mode+': prepared/each participant/completion halt resumes in new JVM; repeated recovery preserves plans and complete logical state')
        run('third-state','halt-prepared','mixed');run('third-state','external-party','mixed');before=files(out/'third-state');run('third-state','reject','mixed');assert files(out/'third-state')==before
        result['checks'].append('Conflicting final participant refuses before writing earlier participants; every retained file remains byte-identical')
        for case,kind in [('corrupt-plan','malformed'),('missing-plan','missing'),('unfinished','temporary')]:
            folder=out/case;shutil.copytree(out/'file-complete',folder);plan=next((folder/'social_imports').glob('prepare-*.json'))
            if kind=='malformed':plan.write_text('{}')
            elif kind=='missing':plan.rename(out/(case+'-original-plan.json'))
            else:(folder/'social_imports/unfinished.tmp').write_text('partial')
            before=files(folder);run(case,'reject');assert files(folder)==before
        result['checks'].append('Malformed/missing completed plan and orphan temporary journal refuse without changing participant or journal bytes')
        result['status']='VERIFIED'
    except Exception as failure:result.update(status='FAILED',error=repr(failure));raise
    finally:(out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'checks':len(result['checks']),'server_started':False}))
if __name__=='__main__':main()
