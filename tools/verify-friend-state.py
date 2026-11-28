"""Verify frozen old/new friend state with serialized file/SQLite replica JVMs; never start a server."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, sqlite3, subprocess, xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ('old-jar','new-jar','java-home','test-xml','output'):parser.add_argument('--'+key,type=Path,required=True)
    args=parser.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    old,new=args.old_jar.resolve(),args.new_jar.resolve()
    java=args.java_home/'bin'/('java.exe' if os.name=='nt' else 'java')
    javac=args.java_home/'bin'/('javac.exe' if os.name=='nt' else 'javac')
    props=ET.parse(args.test_xml).getroot().find('properties')
    captured=next(p.get('value') for p in props if p.get('name')=='surefire.test.class.path')
    deps=[Path(p) for p in captured.split(os.pathsep) if p.endswith('.jar')]
    assert all(p.is_file() for p in deps)
    agent=next(p for p in deps if p.name.startswith('mockito-core-'))
    source=ROOT/'tools/FriendStateRecoveryProbe.java'
    result={'status':'RUNNING','server_started':False,'old_jar_sha256':digest(old),'new_jar_sha256':digest(new),'probe_sha256':digest(source),'runs':[],'checks':[]}
    def argfile(name,values):
        path=out/(name+'.args');path.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8');return path
    def cp(product):return os.pathsep.join(str(p) for p in [product,out,*deps])
    def run(case,phase,product=new):
        folder=out/case;folder.mkdir(exist_ok=True);label=f'{len(result["runs"])+1}-{case}-{phase}'
        values=['-Xms8m','-Xmx96m','-XX:MaxMetaspaceSize=128m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-Dfile.encoding=UTF-8','-javaagent:'+str(agent),'-cp',cp(product),'FriendStateRecoveryProbe',phase,folder]
        process=subprocess.run([str(java),'@'+str(argfile(label,values))],capture_output=True,timeout=150)
        (out/(label+'.log')).write_bytes(process.stdout+process.stderr)
        result['runs'].append({'case':case,'phase':phase,'jar':str(product),'exit_code':process.returncode})
        assert process.returncode==0,(label,'See preserved log')
        lines=[line for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')]
        assert len(lines)==1 and json.loads(lines[0])['verified'],label
    def rows(path,table):
        with sqlite3.connect(f'file:{path.as_posix()}?mode=ro',uri=True) as connection:return connection.execute('SELECT * FROM '+table+' ORDER BY 1').fetchall()
    def copy_db(case):
        folder=out/case;folder.mkdir();target=folder/'social.db';shutil.copy2(out/'old-social.db',target);return target
    try:
        compiled=subprocess.run([str(javac),'-J-Xmx96m','-J-XX:ActiveProcessorCount=1','@'+str(argfile('compile',['-encoding','UTF-8','-proc:none','--release','21','-cp',cp(new),'-d',out,source]))],capture_output=True,timeout=150)
        (out/'compile.log').write_bytes(compiled.stdout+compiled.stderr);assert compiled.returncode==0,'Probe compile failed'
        run('migration','old-write',old)
        db=out/'migration/social.db';file=out/'migration/friends.yml'
        shutil.copy2(db,out/'old-social.db');shutil.copy2(file,out/'old-friends.yml')
        original_relations=rows(db,'starcore_friend_relations');assert len(original_relations)==2
        original_requests=rows(db,'starcore_friend_requests');assert len(original_requests)==1
        run('migration','file-upgrade');run('migration','file-accept');run('migration','file-reopen');run('migration','file-reopen')
        backups=list(file.parent.glob('friends.yml.legacy-*.bak'));assert len(backups)==1 and backups[0].read_bytes()==(out/'old-friends.yml').read_bytes()
        result['checks'].append('Old JAR wrote actual YAML via SocialModule; migration retained exact backup and withheld ambiguous consent; fresh directed request/accept survives two JVM reopens')
        run('migration','sql-upgrade');run('migration','sql-reopen');run('migration','sql-reopen')
        assert rows(db,'starcore_friend_relations')[:2]==original_relations and len(rows(db,'starcore_friend_relations'))==4
        assert rows(db,'starcore_friend_requests')==[]
        result['checks'].append('Old SQL explicit recipient/sender preserved, original friend row IDs and times unchanged after atomic acceptance and two JVM reopens')
        failed=copy_db('failure');run('failure','sql-failure');run('failure','sql-pending')
        assert rows(failed,'starcore_friend_relations')==original_relations and rows(failed,'starcore_friend_requests')==original_requests
        result['checks'].append('Second-row SQL failure rolled back first row and retained exact pending request through independent reopen')
        unknown=copy_db('unknown');run('unknown','sql-unknown');run('unknown','sql-reopen')
        assert len(rows(unknown,'starcore_friend_relations'))==4 and rows(unknown,'starcore_friend_requests')==[]
        result['checks'].append('Real commit followed by lost acknowledgement blocks stale-memory saves; independent JVM restores committed result')
        malformed=copy_db('malformed-sql')
        with sqlite3.connect(malformed) as connection:connection.execute("INSERT INTO starcore_blacklist(player_uuid,blocked_uuid,created_at) VALUES ('broken','00000000-0000-0000-0000-000000000002',1)")
        before=digest(malformed);run('malformed-sql','sql-reject');assert digest(malformed)==before
        folder=out/'future-file';folder.mkdir();future=folder/'friends.yml';future.write_text('schema: 999\n',encoding='utf-8')
        before=digest(future);run('future-file','file-reject');assert digest(future)==before
        result['checks'].append('Malformed SQL and future YAML schema rejected without rewriting source bytes')
        result['status']='VERIFIED'
    except Exception as failure:
        result.update(status='FAILED',error=repr(failure));raise
    finally:(out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'checks':len(result['checks']),'server_started':False}))

if __name__=='__main__':main()
