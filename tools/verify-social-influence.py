"""Run frozen old/new social influence SQL codecs in serial JVMs without a Minecraft server."""
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
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('old-jar', 'new-jar', 'java-home', 'test-xml', 'output'):
        parser.add_argument('--' + key, type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    old, new = args.old_jar.resolve(), args.new_jar.resolve()
    java = args.java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    javac = args.java_home / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
    properties = ET.parse(args.test_xml).getroot().find('properties')
    captured = next(p.get('value') for p in properties if p.get('name') == 'surefire.test.class.path')
    deps = [Path(p) for p in captured.split(os.pathsep) if p.endswith('.jar')]
    assert all(p.is_file() for p in deps), 'Missing captured test dependency'
    agent = next(p for p in deps if p.name.startswith('mockito-core-'))
    source = ROOT / 'tools/SocialInfluenceRecoveryProbe.java'
    result = {'status':'RUNNING', 'server_started':False, 'old_jar_sha256':digest(old),
              'new_jar_sha256':digest(new), 'probe_sha256':digest(source), 'runs':[], 'checks':[]}
    def argfile(name, values):
        path = out / (name + '.args')
        path.write_text('\n'.join('"' + str(v).replace('\\','/') + '"' for v in values) + '\n',encoding='utf-8')
        return path
    def cp(product): return os.pathsep.join(str(p) for p in [product, out, *deps])
    def run(case, phase, product=new):
        folder = out / case; folder.mkdir(exist_ok=True)
        label = f'{len(result["runs"])+1}-{case}-{phase}'
        values = ['-Xms8m','-Xmx96m','-XX:MaxMetaspaceSize=128m','-Xss512k','-XX:+UseSerialGC',
                  '-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-Dfile.encoding=UTF-8',
                  '-javaagent:'+str(agent),'-cp',cp(product),
                  'SocialInfluenceRecoveryProbe',phase,folder]
        process = subprocess.run([str(java),'@'+str(argfile(label,values))],capture_output=True,timeout=120)
        (out/(label+'.log')).write_bytes(process.stdout+process.stderr)
        result['runs'].append({'case':case,'phase':phase,'jar':str(product),'exit_code':process.returncode})
        assert process.returncode == 0, (label, 'See preserved log')
        lines = [line for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')]
        assert len(lines) == 1 and json.loads(lines[0])['verified'], label
    def rows(path):
        with sqlite3.connect(f'file:{path.as_posix()}?mode=ro',uri=True) as connection:
            return connection.execute('SELECT * FROM influence_sources ORDER BY id').fetchall()
    try:
        compile_result = subprocess.run([str(javac),'-J-Xmx128m','-J-XX:ActiveProcessorCount=1',
            '@'+str(argfile('compile',['-encoding','UTF-8','-proc:none','--release','21','-cp',cp(new),'-d',out,source]))],
            capture_output=True,timeout=120)
        (out/'compile.log').write_bytes(compile_result.stdout+compile_result.stderr)
        assert compile_result.returncode == 0, 'Probe compile failed'
        run('migration','old-write',old)
        database = out/'migration/influence.db'
        legacy = rows(database); shutil.copy2(database,out/'legacy-original.db')
        run('migration','upgrade'); run('migration','reopen'); run('migration','reopen')
        assert legacy[0] in rows(database) and len(rows(database)) == 2, 'Original source row changed or new source missing'
        result['checks'].append('Old product balance/source write, new durable decay and two independent reopens; original source row retained')
        for case in ('stale','missing'):
            target = out/case; target.mkdir(); shutil.copy2(database,target/'influence.db')
            run(case,case)
            assert rows(target/'influence.db') == rows(database), 'Decay changed source history'
            result['checks'].append(case+' row quarantined without stale overwrite or invented history')
            if case == 'stale': run(case,'reopen-stale')
        result['status']='VERIFIED'
    except Exception as failure:
        result.update(status='FAILED',error=repr(failure)); raise
    finally:
        (out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'checks':len(result['checks']),'server_started':False}))

if __name__ == '__main__': main()
