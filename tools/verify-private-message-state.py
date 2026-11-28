"""Verify legacy/new private-message checkpoints in small independent JVMs; never start Minecraft."""
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
    agent=next(p for p in deps if p.name.startswith('mockito-core-'));source=ROOT/'tools/PrivateMessageRecoveryProbe.java'
    result={'status':'RUNNING','server_started':False,'old_jar_sha256':digest(old),'new_jar_sha256':digest(new),'probe_sha256':digest(source),'runs':[],'checks':[]}
    def argfile(name,values):
        path=out/(name+'.args');path.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8');return path
    def cp(product):return os.pathsep.join(str(p) for p in [product,out,*deps])
    def invocation(case,phase,product):
        folder=out/case;folder.mkdir(exist_ok=True);label=f'{len(result["runs"])+1}-{case}-{phase}'
        values=['-Xms8m','-Xmx96m','-XX:MaxMetaspaceSize=128m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-Dfile.encoding=UTF-8','-javaagent:'+str(agent),'-cp',cp(product),'PrivateMessageRecoveryProbe',phase,folder]
        return label,[str(java),'@'+str(argfile(label,values))]
    def completed(case,phase,product,label,process):
        (out/(label+'.log')).write_bytes(process.stdout+process.stderr)
        result['runs'].append({'case':case,'phase':phase,'jar':str(product),'exit_code':process.returncode})
        assert process.returncode==0,(label,'See preserved log')
        lines=[line for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')]
        assert len(lines)==1 and json.loads(lines[0])['verified'],label
    def run(case,phase,product=new):
        label,command=invocation(case,phase,product);process=subprocess.run(command,capture_output=True,timeout=90);completed(case,phase,product,label,process)
    def files(folder):return {p.relative_to(folder).as_posix():digest(p) for p in folder.rglob('*') if p.is_file()}
    try:
        process=subprocess.run([str(javac),'-J-Xmx96m','-J-XX:ActiveProcessorCount=1','@'+str(argfile('compile',['-encoding','UTF-8','-proc:none','--release','21','-cp',cp(new),'-d',out,source]))],capture_output=True,timeout=90)
        (out/'compile.log').write_bytes(process.stdout+process.stderr);assert process.returncode==0,'Probe compile failed'
        run('migration','old-write',old);legacy=files(out/'migration/social');assert len(legacy)==3
        run('migration','upgrade');run('migration','reopen');run('migration','reopen')
        for name,expected in legacy.items():assert digest(out/'migration/social'/name)==expected
        backups=list((out/'migration/social').glob('*.legacy-*.bak'));assert len(backups)==3
        for backup in backups:assert digest(backup)==legacy[backup.name.split('.legacy-')[0]]
        result['checks'].append('Actual old JAR three-file state imported without changing source bytes; exact backups; accepted save survives halt and two independent reopen processes')
        for case,change in [
            ('future',lambda folder:(folder/'private-message-state.yml').write_text('schema-version: 999\n')),
            ('duplicate',lambda folder:(folder/'private-message-state.yml').write_text('schema-version: 1\nschema-version: 1\n')),
            ('missing',lambda folder:(folder/'private-message-state.yml').unlink()),
            ('temporary',lambda folder:(folder/'private-message-state.yml.tmp').write_text('unfinished\n')),
            ('old-writer',lambda folder:(folder/'chat-partners.yml').write_text('00000000-0000-0000-0000-000000000001: 00000000-0000-0000-0000-000000000003\n'))]:
            folder=out/case;shutil.copytree(out/'migration',folder);change(folder/'social');before=files(folder);run(case,'reject');assert files(folder)==before
        result['checks'].append('Future/duplicate schema, missing canonical checkpoint, unfinished temporary file and changed old writer source rejected byte-for-byte without rewrite')
        run('migration','clear');run('migration','reopen');result['checks'].append('Cleared conversation/history remain removed after a separate process reopens')
        # Serialized JVM tests keep resource usage bounded; concurrent multi-process locking is a separate live-load boundary.
        result['status']='VERIFIED'
    except Exception as failure:result.update(status='FAILED',error=repr(failure));raise
    finally:(out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'checks':len(result['checks']),'server_started':False}))
if __name__=='__main__':main()
