"""Run actual old/new party SQL JARs against isolated SQLite copies; never start Minecraft."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,sqlite3,subprocess,xml.etree.ElementTree as ET
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
    agent=next(p for p in deps if p.name.startswith('mockito-core-'));source=ROOT/'tools/PartySqlRecoveryProbe.java'
    result={'status':'RUNNING','server_started':False,'old_jar_sha256':digest(old),'new_jar_sha256':digest(new),'probe_sha256':digest(source),'runs':[],'checks':[]}
    def argfile(name,values):
        path=out/(name+'.args');path.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8');return path
    def cp(product):return os.pathsep.join(str(p) for p in [product,out,*deps])
    def run(case,phase,product=new):
        folder=out/case;folder.mkdir(exist_ok=True);label=f'{len(result["runs"])+1}-{case}-{phase}'
        entry=['LegacyPartyWriter',folder] if phase=='old-write' else ['PartySqlRecoveryProbe',phase,folder]
        values=['-Xms8m','-Xmx96m','-XX:MaxMetaspaceSize=128m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-Dfile.encoding=UTF-8','-javaagent:'+str(agent),'-cp',cp(product),*entry]
        process=subprocess.run([str(java),'@'+str(argfile(label,values))],capture_output=True,timeout=90)
        (out/(label+'.log')).write_bytes(process.stdout+process.stderr)
        result['runs'].append({'case':case,'phase':phase,'jar':str(product),'exit_code':process.returncode})
        assert process.returncode==0,(label,'See preserved log')
        lines=[line for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')]
        assert len(lines)==1 and json.loads(lines[0])['verified'],label
    def files(folder):return {p.relative_to(folder).as_posix():digest(p) for p in folder.rglob('*') if p.is_file()}
    try:
        process=subprocess.run([str(javac),'-J-Xmx96m','-J-XX:ActiveProcessorCount=1','@'+str(argfile('compile',['-encoding','UTF-8','-proc:none','--release','21','-cp',cp(new),'-d',out,source]))],capture_output=True,timeout=90)
        (out/'compile.log').write_bytes(process.stdout+process.stderr);assert process.returncode==0,'Probe compile failed'
        run('migration','old-write',old)
        shutil.copytree(out/'migration',out/'legacy-source')
        run('migration','upgrade');run('migration','reopen');run('migration','reopen')
        result['checks'].append('Actual old JAR original SQL rows adopted without changing IDs/times/metadata; settings and joined member/invite transaction survive halt and two independent reopens')
        for case,statement in [
            ('future',"UPDATE starcore_party_write_gate SET schema_version=99"),
            ('invalid-leader',"UPDATE starcore_parties SET leader_uuid='00000000-0000-0000-0000-000000000003' WHERE id='00000000-0000-0001-0000-000000000001'"),
            ('missing-binding',"DELETE FROM starcore_party_invite_bindings"),
            ('fractional-time',"UPDATE starcore_party_members SET joined_at=1.5"),
            ('invalid-flag',"UPDATE starcore_parties SET friendly_fire=2"),
            ('orphan-member',"UPDATE starcore_party_members SET party_id='00000000-0000-0001-0000-000000000099' WHERE player_uuid='00000000-0000-0000-0000-000000000002'"),
            ('changed-invite',"UPDATE starcore_party_invites SET created_at=9999"),
            ('invalid-capacity',"UPDATE starcore_parties SET max_members=0")]:
            folder=out/case;shutil.copytree(out/'migration',folder)
            with sqlite3.connect(folder/'party.db') as connection:connection.execute(statement)
            before=files(folder);run(case,'reject');assert files(folder)==before,case
        result['checks'].append('Eight malformed/unsupported copies reject restore and writes byte-for-byte without changing original evidence')
        run('migration','disband');run('migration','reopen');result['checks'].append('Disband is durable on separate reopen and retains unrelated party rows')
        result['status']='VERIFIED'
    except Exception as failure:result.update(status='FAILED',error=repr(failure));raise
    finally:(out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'checks':len(result['checks']),'server_started':False}))
if __name__=='__main__':main()
