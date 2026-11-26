"""Capture exact source, Maven output and executed Surefire reports for an offline check.

Use --maven-home to launch an already cached Maven without a platform shell wrapper.
This does not start Minecraft or certify any live-server behavior.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

BUILD_INPUT_NAMES = ('pom.xml', 'docs/API.md')


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def find_source_drift(repo: Path, source_hashes: dict, inputs: dict) -> list[str]:
    current_sources = {
        path.relative_to(repo).as_posix(): path
        for tree in ('src/main', 'src/test')
        for path in (repo / tree).rglob('*') if path.is_file()
    }
    current_inputs = {name: repo / name for name in BUILD_INPUT_NAMES
                      if (repo / name).is_file()}
    drift = set(current_sources) ^ set(source_hashes)
    drift.update(set(current_inputs) ^ set(inputs))
    for previous, current in ((source_hashes, current_sources), (inputs, current_inputs)):
        drift.update(name for name, value in previous.items()
                     if name in current and digest(current[name]) != value)
    return sorted(drift)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--maven-home', type=Path)
    parser.add_argument('--heap', default='288m')
    parser.add_argument('--metaspace', default='144m')
    parser.add_argument('--java-agent', type=Path)
    parser.add_argument('goals', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    goals = args.goals[1:] if args.goals[:1] == ['--'] else args.goals
    if not goals:
        parser.error('Supply explicit Maven goals after --')
    repo, out = args.repo.resolve(), args.output.resolve()
    if not (repo / 'pom.xml').is_file():
        parser.error('--repo must contain pom.xml')
    out.mkdir(parents=True, exist_ok=False)
    options = ['-Xms16m', f'-Xmx{args.heap}', f'-XX:MaxMetaspaceSize={args.metaspace}', '-Xss512k',
               '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:TieredStopAtLevel=1',
               '-XX:ReservedCodeCacheSize=24m', '-XX:CompressedClassSpaceSize=16m',
               '-Dfile.encoding=UTF-8']
    if args.java_agent:
        if not args.java_agent.is_file():
            parser.error('--java-agent does not exist')
        options.append('-javaagent:' + str(args.java_agent.resolve()))
    env = os.environ.copy()
    env['JAVA_HOME'] = str(args.java_home.resolve())
    env['MAVEN_OPTS'] = ' '.join(options)
    env['PATH'] = str(args.java_home.resolve() / 'bin') + os.pathsep + env.get('PATH', '')
    if args.maven_home:
        home = args.maven_home.resolve()
        launchers = list((home / 'boot').glob('plexus-classworlds-*.jar'))
        if len(launchers) != 1:
            parser.error('--maven-home needs one boot/plexus-classworlds-*.jar')
        java = args.java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        command = [str(java), *options, '-classpath', str(launchers[0]),
                   '-Dclassworlds.conf=' + str(home / 'bin/m2.conf'),
                   '-Dmaven.home=' + str(home), '-Dmaven.multiModuleProjectDirectory=' + str(repo),
                   'org.codehaus.plexus.classworlds.launcher.Launcher', '-B', '-ntp', *goals]
    else:
        wrapper = repo / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
        command = ([env.get('COMSPEC', 'cmd.exe'), '/d', '/c', str(wrapper)]
                   if os.name == 'nt' else ['bash', str(wrapper)]) + ['-B', '-ntp', *goals]

    source_hashes = {}
    for tree in ('src/main', 'src/test'):
        for path in sorted((repo / tree).rglob('*')):
            if path.is_file():
                name = path.relative_to(repo)
                target = out / 'source' / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)
                source_hashes[name.as_posix()] = digest(path)
    inputs = {}
    for name in BUILD_INPUT_NAMES:
        path = repo / name
        if path.is_file():
            target = out / 'source' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
            inputs[name] = digest(path)
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    write_json(out / 'command.json', {'args': command, 'maven_opts': env['MAVEN_OPTS'],
                                     'head': head, 'source_sha256': source_hashes,
                                     'build_input_sha256': inputs, 'server_started': False})
    started = time.monotonic()
    with (out / 'maven.log').open('wb') as log:
        process = subprocess.Popen(command, cwd=repo, env=env, stdout=log, stderr=subprocess.STDOUT)
        write_json(out / 'process.json', {'pid': process.pid, 'head': head, 'args': command})
        code = process.wait()
    log = (out / 'maven.log').read_text(encoding='utf-8', errors='replace')
    totals = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    suites = []
    names = dict.fromkeys(re.findall(
        r'Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+, Time elapsed: [^\r\n]+ -- in ([\w.$]+)', log))
    for name in names:
        path = repo / 'target/surefire-reports' / ('TEST-' + name + '.xml')
        if not path.is_file():
            raise RuntimeError('Executed suite is missing its XML: ' + name)
        (out / 'surefire').mkdir(exist_ok=True)
        shutil.copy2(path, out / 'surefire' / path.name)
        text = path.with_name(name + '.txt')
        if text.is_file():
            shutil.copy2(text, out / 'surefire' / text.name)
        suite = ET.parse(path).getroot()
        counts = {key: int(suite.attrib.get(key, 0)) for key in totals}
        suites.append({'name': name, **counts})
        for key in totals:
            totals[key] += counts[key]
    drift = find_source_drift(repo, source_hashes, inputs)
    result = {'exit_code': code, 'seconds': time.monotonic() - started, 'totals': totals,
              'suites': suites, 'args': command, 'build_success': 'BUILD SUCCESS' in log,
              'source_drift': drift, 'server_started': False}
    if code == 0 and 'BUILD SUCCESS' in log:
        result['artifacts'] = {path.name: digest(path) for path in (repo / 'target').glob('*.jar')}
    write_json(out / 'result.json', result)
    print(json.dumps({key: value for key, value in result.items() if key not in ('suites', 'args')}, ensure_ascii=False))
    return code or int(bool(drift))


if __name__ == '__main__':
    raise SystemExit(main())
