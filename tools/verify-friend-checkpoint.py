"""File recovery on actual pre-directional, schema2 and current JARs in serial JVMs."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ('legacy-jar','schema2-jar','new-jar','java-home','test-xml','output'):parser.add_argument('--'+key,type=Path,required=True)
    parser.add_argument('--complete-service',action='store_true')
    args=parser.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    old,prior,new=[p.resolve() for p in (args.legacy_jar,args.schema2_jar,args.new_jar)]
    java=args.java_home/'bin/java.exe';javac=args.java_home/'bin/javac.exe'
    props=ET.parse(args.test_xml).getroot().find('properties');captured=next(p.get('value') for p in props if p.get('name')=='surefire.test.class.path')
    deps=[Path(p) for p in captured.split(os.pathsep) if p.endswith('.jar')];assert all(p.is_file() for p in deps)
    agent=next(p for p in deps if p.name.startswith('mockito-core-'))
    sources=[ROOT/'tools/FriendStateRecoveryProbe.java',ROOT/'tools/FriendCheckpointRecoveryProbe.java']
    result={'status':'RUNNING','server_started':False,'jars':{str(p):digest(p) for p in (old,prior,new)},'probes':{p.name:digest(p) for p in sources},'runs':[],'checks':[]}
    def argfile(name,values):
        path=out/(name+'.args');path.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8');return path
    def cp(product):return os.pathsep.join(str(p) for p in [product,out,*deps])
    def run(case,phase,product=new,main='FriendCheckpointRecoveryProbe'):
        folder=out/case;folder.mkdir(exist_ok=True);label=f'{len(result["runs"])+1}-{case}-{phase}'
        values=['-Xms8m','-Xmx96m','-XX:MaxMetaspaceSize=128m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-Dfile.encoding=UTF-8','-javaagent:'+str(agent),'-cp',cp(product),main,phase,folder]
        process=subprocess.run([str(java),'@'+str(argfile(label,values))],capture_output=True,timeout=120)
        (out/(label+'.log')).write_bytes(process.stdout+process.stderr);result['runs'].append({'case':case,'phase':phase,'jar':str(product),'exit_code':process.returncode})
        assert process.returncode==0,(label,'See preserved log')
        lines=[line for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')]
        assert len(lines)==1 and json.loads(lines[0])['verified'],label
    def files(folder):return {p.relative_to(folder).as_posix():digest(p) for p in folder.rglob('*') if p.is_file()}
    try:
        process=subprocess.run([str(javac),'-J-Xmx96m','-J-XX:ActiveProcessorCount=1','@'+str(argfile('compile',['-encoding','UTF-8','-proc:none','--release','21','-cp',cp(new),'-d',out,*sources]))],capture_output=True,timeout=120)
        (out/'compile.log').write_bytes(process.stdout+process.stderr);assert process.returncode==0,'Probe compile failed'
        run('legacy','old-write',old,'FriendStateRecoveryProbe');shutil.copytree(out/'legacy',out/'legacy-source')
        run('legacy','legacy-upgrade');run('legacy','accept');run('legacy','reopen');run('legacy','reopen')
        backups=list((out/'legacy').glob('friends.yml.legacy-*.bak'));assert len(backups)==1 and digest(backups[0])==digest(out/'legacy-source/friends.yml')
        result['checks'].append('Actual legacy JAR file preserves unknown request direction and exact original bytes; fresh consent/accept and close isolation survive halt/reopen')
        run('schema2','schema2-write',prior);shutil.copytree(out/'schema2',out/'schema2-source')
        run('schema2','schema2-upgrade');assert digest(out/'schema2/friends.yml.bak')==digest(out/'schema2-source/friends.yml')
        run('schema2','accept');run('schema2','reopen')
        result['checks'].append('Actual prior schema2 file keeps directed/legacy requests, blacklist, status and friendship; previous checkpoint is exact')
        run('stale','stale');run('stale','stale-reopen')
        result['checks'].append('Two loaded writers refuse stale memory; explicit load recovers authority and both committed requests survive another JVM')
        sql=out/'sql';sql.mkdir();shutil.copy2(out/'legacy-source/social.db',sql/'social.db')
        run('sql','sql-unknown');run('sql','sql-reopen')
        result['checks'].append('Actual SQLite commit with lost acknowledgement isolates both reads and writes; separate JVM recovers the committed result')
        if args.complete_service:
            run('sql','sql-legacy');run('sql','sql-legacy-reopen')
            result['checks'].append('Actual service imports unknown contacts durably into SQL; a fresh directed request, never legacy ambiguity, authorizes acceptance after reopen')
        for case,change in [
            ('duplicate',lambda p:(p/'friends.yml').write_text('schema: 2\nschema: 2\n')),
            ('future',lambda p:(p/'friends.yml').write_text('schema: 99\n')),
            ('null',lambda p:(p/'friends.yml').write_text('schema: null\n')),
            ('missing',lambda p:(p/'friends.yml').unlink()),
            ('temporary',lambda p:(p/'friends.yml.tmp').write_text('unfinished\n')),
            ('backup-temporary',lambda p:(p/'friends.yml.bak.tmp').write_text('unfinished\n')),
            ('utf8',lambda p:(p/'friends.yml').write_bytes(bytes([0xc3,0x28]))),
            ('empty',lambda p:(p/'friends.yml').write_text('')),
            ('marker',lambda p:(p/'friends.yml.created').write_text('invalid marker\n')),
        ]:
            folder=out/case;shutil.copytree(out/'schema2',folder);change(folder);before=files(folder);run(case,'reject');assert files(folder)==before,case
        result['checks'].append('Nine malformed/missing/pending authority copies refuse read/write byte-for-byte; no implicit fallback or temporary cleanup')
        result['status']='VERIFIED'
    except Exception as failure:result.update(status='FAILED',error=repr(failure));raise
    finally:(out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'checks':len(result['checks']),'server_started':False}))
if __name__=='__main__':main()
