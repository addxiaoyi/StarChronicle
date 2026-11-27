"""Verify actual frozen old/new relationships implementations using independent SQLite replica JVMs."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('old-jar','new-jar','java-home','test-xml','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args()
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    old,new=args.old_jar.resolve(),args.new_jar.resolve()
    java=args.java_home/'bin'/('java.exe' if os.name=='nt' else 'java')
    javac=args.java_home/'bin'/('javac.exe' if os.name=='nt' else 'javac')
    properties=ET.parse(args.test_xml).getroot().find('properties')
    captured=next(p.get('value') for p in properties if p.get('name')=='surefire.test.class.path')
    deps=[Path(p) for p in captured.split(os.pathsep) if p.endswith('.jar')]
    assert all(p.is_file() for p in deps),'Missing test dependency'
    agent=next(p for p in deps if p.name.startswith('mockito-core-'))
    source=ROOT/'tools/RelationshipRecoveryProbe.java'
    result={'status':'RUNNING','server_started':False,'old_jar_sha256':digest(old),
            'new_jar_sha256':digest(new),'probe_sha256':digest(source),'runs':[],'checks':[]}
    def argfile(name,values):
        path=out/(name+'.args')
        path.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8')
        return path
    def cp(product):return os.pathsep.join(str(p) for p in [product,out,*deps])
    def run(case,phase,product=new):
        folder=out/case;folder.mkdir(exist_ok=True)
        label=f'{len(result["runs"])+1}-{case}-{phase}'
        values=['-Xms8m','-Xmx96m','-XX:MaxMetaspaceSize=128m','-Xss512k','-XX:+UseSerialGC',
                '-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-Dfile.encoding=UTF-8',
                '-javaagent:'+str(agent),'-cp',cp(product),'RelationshipRecoveryProbe',phase,folder]
        process=subprocess.run([str(java),'@'+str(argfile(label,values))],capture_output=True,timeout=120)
        (out/(label+'.log')).write_bytes(process.stdout+process.stderr)
        result['runs'].append({'case':case,'phase':phase,'jar':str(product),'exit_code':process.returncode})
        assert process.returncode==0,(label,'See preserved log')
        lines=[line for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')]
        assert len(lines)==1 and json.loads(lines[0])['verified'],label
    def read(path,table):
        with sqlite3.connect(f'file:{path.as_posix()}?mode=ro',uri=True) as connection:
            return connection.execute('SELECT * FROM '+table+' ORDER BY 1').fetchall()
    try:
        compile_result=subprocess.run([str(javac),'-J-Xmx128m','-J-XX:ActiveProcessorCount=1',
            '@'+str(argfile('compile',['-encoding','UTF-8','-proc:none','--release','21','-cp',cp(new),'-d',out,source]))],capture_output=True,timeout=120)
        (out/'compile.log').write_bytes(compile_result.stdout+compile_result.stderr)
        assert compile_result.returncode==0,'Probe compile failed'
        run('migration','old-write',old)
        db=out/'migration/relationships.db';legacy=read(db,'relationship_history');shutil.copy2(db,out/'legacy-original.db')
        assert len(legacy)==3, 'Legacy history not produced by old product'
        legacy_profile=read(db,'relationships')[0]
        run('migration','upgrade');run('migration','reopen');run('migration','reopen')
        current=read(db,'relationship_history');assert current[:3]==legacy and len(current)==5
        profile=read(db,'relationships')[0]
        assert (profile[0],profile[1],profile[2],profile[6])==(legacy_profile[0],legacy_profile[1],legacy_profile[2],legacy_profile[6])
        result['checks'].append('Frozen old product writes and new reverse updates/decay; original row ID, orientation, creation time and all old history columns retained through two reopens')
        for case in ('stale','missing','history-fail'):
            folder=out/case;folder.mkdir();target=folder/'relationships.db';shutil.copy2(db,target)
            run(case,case)
            assert read(target,'relationship_history')==current,'Rejected mutation appended history'
            if case=='stale':
                assert read(target,'relationships')[0][4]==77;run(case,'reopen-stale')
            elif case=='missing':assert read(target,'relationships')==[]
            else:
                assert read(target,'relationships')==read(db,'relationships');run(case,'reopen')
            result['checks'].append(case+': rejected without stale overwrite or partial relation/history commit')
        for case,sql in [('bad-strength',"UPDATE relationships SET strength='1.5'"),
                         ('future-history',"UPDATE relationship_history SET action='FUTURE'"),
                         ('bad-time',"UPDATE relationship_history SET timestamp=-1"),
                         ('reverse-duplicate',"INSERT INTO relationships(player1,player2,type,strength,last_interaction,created_at) SELECT player2,player1,type,40,last_interaction,created_at FROM relationships")]:
            folder=out/case;folder.mkdir();target=folder/'relationships.db';shutil.copy2(db,target)
            with sqlite3.connect(target) as connection:connection.execute(sql)
            before=digest(target);run(case,'reject-load');assert digest(target)==before,'Rejected load rewrote file'
            result['checks'].append(case+': failed recovery preserves exact database bytes')
        result['status']='VERIFIED'
    except Exception as failure:
        result.update(status='FAILED',error=repr(failure));raise
    finally:
        (out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'checks':len(result['checks']),'server_started':False}))

if __name__=='__main__':main()
