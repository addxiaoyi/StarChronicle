"""Actual legacy/new PartyService migration and commit verification in serial JVMs; no Minecraft."""
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
    props=ET.parse(args.test_xml).getroot().find('properties');captured=next(p.get('value') for p in props if p.get('name')=='surefire.test.class.path')
    deps=[Path(p) for p in captured.split(os.pathsep) if p.endswith('.jar')];assert all(p.is_file() for p in deps)
    agent=next(p for p in deps if p.name.startswith('mockito-core-'));source=ROOT/'tools/PartyStateRecoveryProbe.java'
    result={'status':'RUNNING','server_started':False,'old_jar_sha256':digest(old),'new_jar_sha256':digest(new),'probe_sha256':digest(source),'runs':[],'checks':[]}
    def argfile(name,values):
        path=out/(name+'.args');path.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8');return path
    def cp(product):return os.pathsep.join(str(p) for p in [product,out,*deps])
    def run(case,phase,mode,product=new):
        folder=out/case;folder.mkdir(exist_ok=True);label=f'{len(result["runs"])+1}-{case}-{phase}'
        entry=['LegacyPartyStateWriter',mode,folder] if phase=='old-write' else ['PartyStateRecoveryProbe',phase,mode,folder]
        values=['-Xms8m','-Xmx96m','-XX:MaxMetaspaceSize=128m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-Dfile.encoding=UTF-8','-javaagent:'+str(agent),'-cp',cp(product),*entry]
        process=subprocess.run([str(java),'@'+str(argfile(label,values))],capture_output=True,timeout=90)
        (out/(label+'.log')).write_bytes(process.stdout+process.stderr);result['runs'].append({'case':case,'phase':phase,'jar':str(product),'exit_code':process.returncode})
        assert process.returncode==0,(label,'See preserved log')
        lines=[line for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')]
        assert len(lines)==1 and json.loads(lines[0])['verified'],label
    def files(folder):return {p.relative_to(folder).as_posix():digest(p) for p in folder.rglob('*') if p.is_file()}
    try:
        process=subprocess.run([str(javac),'-J-Xmx96m','-J-XX:ActiveProcessorCount=1','@'+str(argfile('compile',['-encoding','UTF-8','-proc:none','--release','21','-cp',cp(new),'-d',out,source]))],capture_output=True,timeout=90)
        (out/'compile.log').write_bytes(process.stdout+process.stderr);assert process.returncode==0,'Probe compile failed'
        for mode in ('file','sql'):
            run(mode,'old-write',mode,old);shutil.copytree(out/mode,out/(mode+'-legacy-source'))
            run(mode,'upgrade',mode);run(mode,'reopen',mode);run(mode,'accept',mode);run(mode,'reopen',mode)
        for name in ('parties.yml','party-invites.yml'):
            legacy=out/'file-legacy-source/social'/name;assert digest(legacy)==digest(out/'file/social'/name)
            backups=list((out/'file/social').glob(name+'.legacy-*.bak'));assert len(backups)==1 and digest(backups[0])==digest(legacy)
        result['checks'].append('Actual old YAML/SQL identities, times and members retained; legacy invitations keep unknown party bindings and original timestamps; settings and fresh invitation acceptance survive halt/reopen')
        for case,change in [
            ('future',lambda folder:(folder/'party-state.yml').write_text('schema-version: 99\n')),
            ('duplicate',lambda folder:(folder/'party-state.yml').write_text('schema-version: 1\nschema-version: 1\n')),
            ('missing',lambda folder:(folder/'party-state.yml').unlink()),
            ('temporary',lambda folder:(folder/'party-state.yml.tmp').write_text('unfinished\n')),
            ('old-writer',lambda folder:(folder/'parties.yml').write_text((folder/'parties.yml').read_text(encoding='utf-8')+'\n# changed old source\n',encoding='utf-8'))]:
            folder=out/case;shutil.copytree(out/'file',folder);change(folder/'social');before=files(folder);run(case,'reject','file');assert files(folder)==before,case
        result['checks'].append('Five malformed file copies reject reads/save/admission byte-for-byte; both exact old YAML backups retained')
        for mode in ('file','sql'):run(mode,'disband',mode);run(mode,'reopen',mode)
        result['checks'].append('Disband persists across independent reopen in both file and SQLite service modes')
        result['status']='VERIFIED'
    except Exception as failure:result.update(status='FAILED',error=repr(failure));raise
    finally:(out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'checks':len(result['checks']),'server_started':False}))
if __name__=='__main__':main()
