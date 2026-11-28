"""Exercise frozen old/new territory upgrade JARs in serial file-only JVMs."""
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
    source = ROOT / 'tools/TerritoryUpgradeProbe.java'
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
            'TerritoryUpgradeProbe', phase, folder]
        invocation = [str(java), '@' + str(argfile(label, arguments))]
        process = subprocess.run(invocation, capture_output=True, timeout=120)
        (out / (label + '.log')).write_bytes(process.stdout + process.stderr)
        result['runs'].append({'case':case, 'phase':phase, 'jar':str(product), 'exit_code':process.returncode})
        assert process.returncode == 0, (label, 'See preserved log')
        lines = [line for line in process.stdout.decode('utf-8').splitlines() if line.startswith('{')]
        assert len(lines) == 1 and json.loads(lines[0])['verified'], label
    def originals(folder):
        return {str(p.relative_to(folder)): digest(p) for p in [folder/'territory_upgrades.dat', folder/'upgrade/states.props']}
    def authority(folder):
        return folder/'territory_upgrade/states-v2.props'
    try:
        build = subprocess.run([str(javac), '-J-Xmx128m', '-J-XX:ActiveProcessorCount=1',
            '@' + str(argfile('compile',
            ['-encoding','UTF-8','-proc:none','--release','21',
             '-classpath',cp(new),'-d',out,source]))], capture_output=True, timeout=120)
        (out/'compile.log').write_bytes(build.stdout + build.stderr)
        assert build.returncode == 0, 'Probe compile failed'
        run('migration','old-write',old)
        baseline = originals(out/'migration')
        run('migration','upgrade')
        run('migration','reopen')
        run('migration','stale')
        run('migration','reopen')
        assert originals(out/'migration') == baseline, 'Legacy evidence changed'
        result['checks'].append('Frozen old writer, migration, two independent reopens and stale-writer rejection; legacy bytes unchanged')
        shutil.copytree(out/'migration', out/'changed-legacy')
        before = digest(authority(out/'changed-legacy'))
        run('changed-legacy','old-change',old)
        run('changed-legacy','reject')
        assert digest(authority(out/'changed-legacy')) == before
        result['checks'].append('Old writer changes retained source: new reader isolates without replacing canonical data')
        run('conflict','old-write',old)
        run('conflict','old-change',old)
        before = originals(out/'conflict')
        run('conflict','reject')
        assert originals(out/'conflict') == before and not authority(out/'conflict').exists()
        result['checks'].append('Conflicting old copies preserved without selecting a winner')
        shutil.copytree(out/'migration',out/'corrupt')
        file = authority(out/'corrupt')
        raw = file.read_bytes()
        assert b'.exp=1050' in raw
        file.write_bytes(raw.replace(b'.exp=1050', b'.exp=1051'))
        before = digest(file)
        run('corrupt','reject')
        assert digest(file) == before
        result['checks'].append('Checksum mismatch retained and rejected')
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
