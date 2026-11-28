"""Exercise frozen old/new social verification JARs in serial file-only JVMs."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--old-jar', type=Path, required=True)
    parser.add_argument('--new-jar', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--test-xml', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    old, new = args.old_jar.resolve(), args.new_jar.resolve()
    java = args.java_home / 'bin/java.exe'
    javac = args.java_home / 'bin/javac.exe'
    properties = ET.parse(args.test_xml).getroot().find('properties')
    dependency_text = next(p.get('value') for p in properties if p.get('name') == 'surefire.test.class.path')
    deps = [Path(p) for p in dependency_text.split(os.pathsep) if p.endswith('.jar')]
    assert all(p.is_file() for p in deps), 'Missing captured test dependencies'
    agent = next(p for p in deps if p.name.startswith('mockito-core-'))
    source = ROOT / 'tools/SocialVerificationProbe.java'
    result = {'status': 'RUNNING', 'server_started': False,
              'old_jar_sha256': digest(old), 'new_jar_sha256': digest(new),
              'probe_sha256': digest(source), 'runs': [], 'checks': []}
    def argfile(name, values):
        target = out / (name + '.args')
        target.write_text('\n'.join('"' + str(v).replace('\\', '/') + '"' for v in values) + '\n', encoding='utf-8')
        return target
    def cp(product):
        return os.pathsep.join(str(p) for p in [product, out, *deps])
    def run(case, phase, product=new):
        folder = out / case
        folder.mkdir(exist_ok=True)
        label = str(len(result['runs']) + 1) + '-' + case + '-' + phase
        arguments = ['-Xms8m', '-Xmx96m', '-XX:MaxMetaspaceSize=128m', '-Xss512k',
            '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1',
            '-Dfile.encoding=UTF-8', '-javaagent:' + str(agent), '-cp', cp(product),
            'SocialVerificationProbe', phase, folder]
        invocation = [str(java), '@' + str(argfile(label, arguments))]
        process = subprocess.run(invocation, capture_output=True, timeout=120)
        (out / (label + '.log')).write_bytes(process.stdout + process.stderr)
        result['runs'].append({'case':case, 'phase':phase, 'jar':str(product), 'exit_code':process.returncode})
        assert process.returncode == 0, (label, 'See preserved log')
        lines = [line for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')]
        assert len(lines) == 1 and json.loads(lines[0])['verified'], label
    try:
        build = subprocess.run([str(javac), '-J-Xmx128m', '-J-XX:ActiveProcessorCount=1',
            '@' + str(argfile('compile',
            ['-encoding','UTF-8','-proc:none','--release','21',
             '-classpath',cp(new),'-d',out,source]))], capture_output=True, timeout=120)
        (out/'compile.log').write_bytes(build.stdout + build.stderr)
        assert build.returncode == 0, 'Probe compile failed'
        run('migration','old-write',old)
        old_bytes = (out/'migration/gossip_data.yml').read_bytes()
        assert b'schema-version: 1' in old_bytes
        (out/'legacy-gossip-original.yml').write_bytes(old_bytes)
        run('migration','upgrade')
        current = out/'migration/gossip_data.yml'
        assert b'schema-version: 2' in current.read_bytes()
        run('migration','reopen')
        run('migration','reopen')
        run('migration','process')
        run('migration','processed')
        before = digest(current)
        run('migration','reject',old)
        assert digest(current) == before
        result['checks'].append('Frozen schema1 writer, schema2 migration, independent reopens and report processing persisted; older writer rejects schema2 without modification')
        shutil.copytree(out/'migration',out/'stale')
        run('stale','stale')
        result['checks'].append('Stale verification writer cannot overwrite another committed vote')
        baseline=current.read_bytes()
        for case, data in [('future', b'schema-version: 99\n'), ('corrupt', b'gossips: [invalid]\n'),
                ('bad-verification', baseline.replace(b'status: VERIFIED', b'status: UNKNOWN')),
                ('bad-threshold', baseline.replace(b'threshold: 3', b'threshold: 0'))]:
            assert data != baseline
            shutil.copytree(out/'migration',out/case)
            file = out/case/'gossip_data.yml';file.write_bytes(data);before=digest(file)
            run(case,'reject');assert digest(file)==before
            result['checks'].append(case + ' rejected with evidence preserved')
        shutil.copytree(out/'migration',out/'missing')
        primary=out/'missing/gossip_data.yml';primary.rename(out/'missing/removed-primary.yml')
        run('missing','reject');assert not primary.exists()
        result['checks'].append('Missing primary and retained backup rejected without fallback')
        result['status'] = 'VERIFIED'
    except Exception as error:
        result['status'] = 'FAILED'
        result['error'] = repr(error)
        raise
    finally:
        (out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'runs':len(result['runs']),'checks':len(result['checks']),'server_started':False}))

if __name__ == '__main__':
    main()
