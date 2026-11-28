"""Old/new friend SQL migration, expected-state transactions and retained rows in serial JVMs."""
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
    agent=next(p for p in deps if p.name.startswith('mockito-core-'));sources=[ROOT/'tools/FriendStateRecoveryProbe.java',ROOT/'tools/FriendCompleteSqlProbe.java']
    result={'status':'RUNNING','server_started':False,'old_jar_sha256':digest(old),'new_jar_sha256':digest(new),'probes':{p.name:digest(p) for p in sources},'runs':[],'checks':[]}
    def argfile(name,values):
        path=out/(name+'.args');path.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8');return path
    def cp(product):return os.pathsep.join(str(p) for p in [product,out,*deps])
    def run(case,phase,product=new,entry='FriendCompleteSqlProbe'):
        folder=out/case;folder.mkdir(exist_ok=True);label=f'{len(result["runs"])+1}-{case}-{phase}'
        values=['-Xms8m','-Xmx96m','-XX:MaxMetaspaceSize=128m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-Dfile.encoding=UTF-8','-javaagent:'+str(agent),'-cp',cp(product),entry,phase,folder]
        process=subprocess.run([str(java),'@'+str(argfile(label,values))],capture_output=True,timeout=120)
        (out/(label+'.log')).write_bytes(process.stdout+process.stderr);result['runs'].append({'case':case,'phase':phase,'jar':str(product),'exit_code':process.returncode})
        assert process.returncode==0,(label,'See preserved log')
        lines=[line for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')];assert len(lines)==1 and json.loads(lines[0])['verified'],label
    def rows(path,table):
        with sqlite3.connect(f'file:{path.as_posix()}?mode=ro',uri=True) as c:return c.execute('SELECT * FROM '+table+' ORDER BY 1').fetchall()
    def copy(case,source):
        folder=out/case;folder.mkdir();shutil.copy2(source,folder/'social.db');return folder/'social.db'
    try:
        process=subprocess.run([str(javac),'-J-Xmx96m','-J-XX:ActiveProcessorCount=1','@'+str(argfile('compile',['-encoding','UTF-8','-proc:none','--release','21','-cp',cp(new),'-d',out,*sources]))],capture_output=True,timeout=120)
        (out/'compile.log').write_bytes(process.stdout+process.stderr);assert process.returncode==0,'Probe compile failed'
        run('migration','old-write',old,'FriendStateRecoveryProbe');original=out/'old-social.db';shutil.copy2(out/'migration/social.db',original)
        original_friends=rows(original,'starcore_friend_relations');original_requests=rows(original,'starcore_friend_requests')
        run('migration','upgrade');run('migration','reopen');run('migration','reopen');db=out/'migration/social.db'
        assert rows(db,'starcore_friend_relations')[:2]==original_friends and len(rows(db,'starcore_friend_relations'))==4
        assert rows(db,'starcore_friend_requests')==[] and len(rows(db,'starcore_friend_legacy_contacts'))==1
        result['checks'].append('Actual legacy SQL rows retain original friend identities/times; acceptance and unknown-contact state survive two halt/reopen JVMs')
        failed=copy('failure',original);run('failure','failure');run('failure','pending')
        assert rows(failed,'starcore_friend_relations')==original_friends and rows(failed,'starcore_friend_requests')==original_requests
        result['checks'].append('Failed friend insert rolls back the full candidate; original pending request survives another JVM unchanged')
        stale=copy('stale',original);run('stale','stale');run('stale','pending')
        assert rows(stale,'starcore_friend_relations')==original_friends and rows(stale,'starcore_friend_requests')==original_requests
        result['checks'].append('Cooperating old API status write prevents stale expected-state commit without erasing the other writer')
        for case,sql in [
            ('fractional-time','UPDATE starcore_friend_relations SET created_at=0.5'),
            ('fractional-status',"INSERT INTO starcore_player_status (player_uuid,is_online,last_seen) VALUES ('00000000-0000-0000-0000-000000000009',0.5,1)"),
            ('negative-row','UPDATE starcore_friend_relations SET id=-1 WHERE id=1'),
            ('bad-uuid',"UPDATE starcore_friend_relations SET friend_uuid='0-0-0-0-2' WHERE id=1"),
            ('future-marker','UPDATE starcore_friend_write_gate SET schema_version=99'),
            ('missing-marker','DELETE FROM starcore_friend_write_gate'),
            ('bad-legacy',"UPDATE starcore_friend_legacy_contacts SET contact_uuid=player_uuid"),
        ]:
            path=copy(case,db)
            with sqlite3.connect(path) as c:c.execute(sql)
            before=digest(path);run(case,'reject');assert digest(path)==before,case
        result['checks'].append('Seven malformed SQL copies reject byte-for-byte, including numeric coercion, noncanonical identity and missing/future marker')
        run('migration','legacy-api');assert rows(db,'starcore_friend_legacy_contacts')==[]
        result['checks'].append('Legacy public request API participates in the gate and removes matching unknown evidence only when new directed consent is explicitly issued')
        result['status']='VERIFIED'
    except Exception as failure:result.update(status='FAILED',error=repr(failure));raise
    finally:(out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'checks':len(result['checks']),'server_started':False}))
if __name__=='__main__':main()
