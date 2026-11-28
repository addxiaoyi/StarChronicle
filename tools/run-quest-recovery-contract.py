"""Check packaged quest recovery across three JVMs without starting Minecraft."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['starcore', 'chronicle', 'runtime', 'java-home', 'maven-repository', 'output']:
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    artifacts = output/'artifacts'
    artifacts.mkdir()
    source = Path(__file__).with_name('QuestRecoveryContractProbe.java')
    for path, name in [(args.starcore, 'starcore.jar'), (args.chronicle, 'starchronicle.jar'), (source, source.name)]:
        shutil.copy2(path, artifacts/name)
    dependencies = [args.maven_repository/p for p in [
        'org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar',
        'net/bytebuddy/byte-buddy/1.17.7/byte-buddy-1.17.7.jar',
        'net/bytebuddy/byte-buddy-agent/1.17.7/byte-buddy-agent-1.17.7.jar',
        'org/objenesis/objenesis/3.3/objenesis-3.3.jar']]
    libraries = sorted((args.runtime/'libraries').rglob('*.jar'))
    if not libraries or not all(p.is_file() for p in dependencies):
        raise RuntimeError('Required runtime libraries are missing')
    cp = os.pathsep.join(p.resolve().as_posix() for p in [artifacts/'starchronicle.jar', artifacts/'starcore.jar', *dependencies, *libraries])
    classes = output/'classes'
    classes.mkdir()
    manifest = {'status': 'RUNNING', 'scope': 'Three fresh JVMs; real packaged quest services, file persistence, Chronicle SQLite and reflection adapter; mocked Bukkit player/scheduler; no Minecraft server, real player actions or rewards',
                'artifacts': {p.name: sha(p) for p in artifacts.iterdir()}, 'phases': {}}

    def run(label, executable, arguments, launcher=()):
        argfile = output/(label+'.args')
        argfile.write_text('\n'.join(json.dumps(str(arg).replace('\\', '/')) for arg in arguments)+'\n', encoding='utf-8')
        with (output/(label+'.log')).open('wb') as log:
            result = subprocess.run([str(executable), *launcher, '@'+str(argfile)], stdout=log, stderr=subprocess.STDOUT, timeout=120)
        manifest['phases'][label] = {'exit_code': result.returncode, 'log_sha256': sha(output/(label+'.log'))}
        if result.returncode:
            raise RuntimeError(f'{label} failed; see {output/(label+".log")}')

    suffix = '.exe' if os.name == 'nt' else ''
    try:
        run('compile', args.java_home/'bin'/('javac'+suffix), ['-proc:none', '-encoding', 'UTF-8', '--release', '21', '-cp', cp, '-d', classes, artifacts/source.name], ['-J-Xmx128m', '-J-XX:+UseSerialGC'])
        for phase in ['first', 'second', 'third']:
            run(phase, args.java_home/'bin'/('java'+suffix), ['-Xmx160m', '-Xss512k', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:ReservedCodeCacheSize=32m', '-XX:TieredStopAtLevel=1', '-Dfile.encoding=UTF-8', '-javaagent:'+str(dependencies[0]), '-cp', str(classes)+os.pathsep+cp, 'io.github.addxiaoyi.starchronicle.integration.QuestRecoveryContractProbe', output, phase])
        with sqlite3.connect(output/'chronicle.db') as db:
            gate = db.execute('SELECT id,attempt,status,accepted_json,provider_id FROM quest_gates').fetchone()
            assert gate[1:3] == (2, 'ACTIVE')
            accepted = json.loads(gate[3])
            archive = db.execute('SELECT binding_id,gate_json,proof_json FROM quest_gate_attempts').fetchall()
            assert len(archive) == 1
            first_binding, old_json, proof_json = archive[0]
            old, proof = json.loads(old_json), json.loads(proof_json)
            assert first_binding == gate[0] == old['id']
            assert old['acceptedRun'] == proof['acceptedRun']
            assert proof['status'] == 'ABANDONED'
            native = json.loads((output/'native/quest/termination-journal'/(proof['acceptedRun']['runId']+'.json')).read_text())
            assert native['terminationId'] == proof['terminationId']
            assert native['snapshot']['bindingId'] == first_binding
            assert native['snapshot']['acceptedEvidence'] == proof['acceptedRun']['evidence']
            assert native['snapshot']['runId'] != accepted['runId']
            assert db.execute('SELECT COUNT(*) FROM quest_gate_completions').fetchone()[0] == 0
            assert db.execute('SELECT COUNT(*) FROM events').fetchone()[0] == 1
        manifest['status'] = 'PASS'
    except BaseException:
        manifest['status'] = 'FAILED'
        raise
    finally:
        manifest['files'] = {str(p.relative_to(output)): sha(p) for p in output.rglob('*') if p.is_file() and p.name != 'manifest.json'}
        (output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'status': manifest['status'], 'evidence': str(output), 'artifacts': manifest['artifacts']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
