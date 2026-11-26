"""Verify the merged pair against tested source snapshots and both pre-merge JARs.

Only fixture compilation starts a JVM; class descriptors are read directly in Python.
No Paper/Folia process, client, world or production database is started or changed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT.parent / 'StarChronicle-audit' / 'starcore-all-stages'
PUBLIC, PRIVATE, PROTECTED, STATIC, FINAL, INTERFACE, ABSTRACT = 1, 2, 4, 8, 16, 512, 1024


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class ClassFile:
    """Minimal bounded classfile reader; no reflection, loading or bytecode execution."""
    def __init__(self, raw):
        self.raw, self.pos = raw, 0
        require(self.take(4) == b'\xca\xfe\xba\xbe', 'Invalid classfile magic')
        self.minor, self.major = self.u2(), self.u2()
        self.cp = [None] * self.u2()
        index = 1
        while index < len(self.cp):
            tag = self.u1()
            if tag == 1:
                self.cp[index] = self.take(self.u2()).decode('utf-8', errors='replace')
            elif tag in (7, 8, 16, 19, 20):
                self.cp[index] = (tag, self.u2())
            elif tag in (3, 4):
                self.take(4)
            elif tag in (5, 6):
                self.take(8)
                index += 1
            elif tag in (9, 10, 11, 12, 17, 18):
                self.take(4)
            elif tag == 15:
                self.take(3)
            else:
                raise AssertionError(f'Unsupported constant-pool tag: {tag}')
            index += 1
        self.access = self.u2()
        self.name, self.super = self.class_name(self.u2()), self.class_name(self.u2())
        self.interfaces = [self.class_name(self.u2()) for _ in range(self.u2())]
        self.members = []
        for kind in ('field', 'method'):
            for _ in range(self.u2()):
                access, name, descriptor = self.u2(), self.utf(self.u2()), self.utf(self.u2())
                self.members.append((kind, name, descriptor, access))
                self.attributes()
        attrs = self.attributes()
        self.source_file = self.utf(struct.unpack('>H', attrs['SourceFile'])[0]) if 'SourceFile' in attrs else None
        require(self.pos == len(raw), f'Trailing classfile data: {self.name}')

    def take(self, length):
        require(length >= 0 and self.pos + length <= len(self.raw), 'Truncated classfile')
        value = self.raw[self.pos:self.pos + length]
        self.pos += length
        return value

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack('>H', self.take(2))[0]

    def u4(self):
        return struct.unpack('>I', self.take(4))[0]

    def utf(self, index):
        value = self.cp[index]
        require(isinstance(value, str), f'Expected UTF8 constant: {index}')
        return value

    def class_name(self, index):
        return self.utf(self.cp[index][1]) if index else None

    def attributes(self):
        result = {}
        for _ in range(self.u2()):
            name, length = self.utf(self.u2()), self.u4()
            result[name] = self.take(length)
        return result

    def api(self):
        return {(kind, name, descriptor): access for kind, name, descriptor, access in self.members
                if access & (PUBLIC | PROTECTED)}


def project_entries(archive, prefix):
    return {name for name in archive.namelist() if name.startswith(prefix) and name.endswith('.class')
            and not name.startswith((prefix + 'lib/', prefix + 'libs/'))}


def verify_api(old_path, new_path, prefix):
    result = {}
    with zipfile.ZipFile(old_path) as old, zipfile.ZipFile(new_path) as current:
        current_names = set(current.namelist())
        for name in sorted(project_entries(old, prefix)):
            before = ClassFile(old.read(name))
            if not before.access & PUBLIC:
                continue
            require(name in current_names, f'Old public type disappeared: {name}')
            after = ClassFile(current.read(name))
            require(after.access & PUBLIC, f'Public type lost visibility: {name}')
            require(bool(before.access & INTERFACE) == bool(after.access & INTERFACE), f'Type changed class/interface: {name}')
            require(not (after.access & FINAL and not before.access & FINAL), f'Old extensible type became final: {name}')
            require(not (after.access & ABSTRACT and not before.access & ABSTRACT), f'Old concrete type became abstract: {name}')
            require(before.super == after.super, f'Old superclass changed: {name}')
            require(set(before.interfaces) <= set(after.interfaces), f'Old interface disappeared: {name}')
            previous, latest = before.api(), after.api()
            for member, flags in previous.items():
                require(member in latest, f'Old public/protected descriptor disappeared: {name} {member}')
                new_flags = latest[member]
                if flags & PUBLIC:
                    require(bool(new_flags & PUBLIC), f'Public member lost visibility: {name} {member}')
                else:
                    require(bool(new_flags & (PUBLIC | PROTECTED)), f'Protected member lost visibility: {name} {member}')
                require(bool(flags & STATIC) == bool(new_flags & STATIC), f'Member changed static/instance: {name} {member}')
                if member[0] == 'method' and not flags & STATIC:
                    require(not (new_flags & FINAL and not flags & FINAL), f'Old overridable method became final: {name} {member}')
                    require(not (new_flags & ABSTRACT and not flags & ABSTRACT), f'Old implemented method became abstract: {name} {member}')
            result[name] = {'preserved': len(previous), 'added': len(set(latest) - set(previous)),
                            'descriptors': [list(member) for member in sorted(previous)]}
    require(result, f'No baseline public types inspected: {old_path}')
    return result


def source_snapshot(repo, snapshot, *, require_tests, artifact=None):
    snapshot = snapshot.resolve()
    frozen = snapshot / 'source' if (snapshot / 'source' / 'src').is_dir() else snapshot
    result_path, command_path = snapshot / 'result.json', snapshot / 'command.json'
    result, command = json.loads(result_path.read_text(encoding='utf-8')), json.loads(command_path.read_text(encoding='utf-8'))
    require(result.get('exit_code') == 0 and result.get('build_success', True), f'Unsuccessful snapshot: {snapshot}')
    require(not result.get('source_drift'), f'Source changed during validation: {snapshot}')
    totals = result.get('totals', {})
    if require_tests:
        require(totals.get('tests', 0) > 0, f'Snapshot did not run tests: {snapshot}')
        require(all(totals.get(key, 0) == 0 for key in ('failures', 'errors', 'skipped')), f'Snapshot contains unsuccessful tests: {snapshot}')
    source_hashes = {}
    for tree in ('src/main', 'src/test'):
        current_files = {p.relative_to(repo).as_posix(): p for p in (repo / tree).rglob('*') if p.is_file()}
        frozen_files = {p.relative_to(frozen).as_posix(): p for p in (frozen / tree).rglob('*') if p.is_file()}
        require(set(current_files) == set(frozen_files), f'Source file set drifted from {snapshot}: {tree}')
        for name, path in current_files.items():
            require(path.read_bytes() == frozen_files[name].read_bytes(), f'Source changed after snapshot: {snapshot} {name}')
            source_hashes[name] = sha(path.read_bytes())
    if 'source_sha256' in command:
        require(source_hashes == command['source_sha256'], f'Frozen source disagrees with command manifest: {snapshot}')
    for name, expected in command.get('build_input_sha256', {}).items():
        require(sha((repo / name).read_bytes()) == expected, f'Build input changed after snapshot: {snapshot} {name}')
    if artifact:
        require(result.get('artifacts', {}).get(artifact.name) == sha(artifact.read_bytes()), f'Artifact changed after successful build: {snapshot} {artifact}')
    return {'path': str(snapshot), 'result_sha256': sha(result_path.read_bytes()), 'command_sha256': sha(command_path.read_bytes()),
            'totals': totals, 'source_files': len(source_hashes), 'source_hashes': source_hashes}


def surface(parsed, replacements=()):
    def rename(value):
        if value is None:
            return None
        for before, after in replacements:
            value = value.replace(before, after)
        return value
    return {'name': rename(parsed.name), 'super': rename(parsed.super), 'access': parsed.access,
            'interfaces': sorted(rename(value) for value in parsed.interfaces),
            'members': sorted((kind, name, rename(descriptor), access) for kind, name, descriptor, access in parsed.members)}


def verify_packaging(repo, jar_path, original_path, prefix, replacements):
    classes = repo / 'target' / 'classes'
    compiled = {p.relative_to(classes).as_posix(): p for p in (classes / prefix).rglob('*.class')
                if not p.relative_to(classes).as_posix().startswith((prefix + 'lib/', prefix + 'libs/'))}
    require(compiled, f'No compiled project classes: {classes}')
    source_index = {}
    for source_root in (repo / 'src/main/java', repo / 'target/generated-sources'):
        if not source_root.is_dir():
            continue
        for source in source_root.rglob('*.java'):
            text = source.read_text(encoding='utf-8', errors='replace')
            package = re.search(r'(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;', text)
            if package:
                key = (package.group(1).replace('.', '/'), source.name)
                source_index.setdefault(key, source)
    hashes = {}
    with zipfile.ZipFile(jar_path) as final, zipfile.ZipFile(original_path) as original:
        require(set(compiled) == project_entries(original, prefix), f'Compiled/original JAR class set differs: {repo}')
        require(set(compiled) == project_entries(final, prefix), f'Compiled/final JAR class set differs: {repo}')
        for name, path in sorted(compiled.items()):
            raw, packaged = path.read_bytes(), final.read(name)
            require(original.read(name) == raw, f'Original JAR does not contain compiled class bytes: {name}')
            before, after = ClassFile(raw), ClassFile(packaged)
            require(before.major == after.major == 65, f'Non-Java-21 project class: {name}')
            require(surface(before, replacements) == surface(after), f'Shading changed class declarations beyond configured relocation: {name}')
            require(before.source_file is not None, f'Class has no source identity: {name}')
            key = (Path(before.name).parent.as_posix(), before.source_file)
            source = source_index.get(key)
            require(source is not None, f'Possibly stale class has no current or generated source file: {name} {key}')
            hashes[name] = {'compiled_sha256': sha(raw), 'packaged_sha256': sha(packaged),
                            'source': source.relative_to(repo).as_posix()}
        resources = {}
        for path in sorted((repo / 'src/main/resources').rglob('*')):
            if not path.is_file():
                continue
            name = path.relative_to(repo / 'src/main/resources').as_posix()
            built = classes / name
            require(built.is_file(), f'Resource missing from compiled output: {name}')
            if name.startswith('META-INF/services/'):
                service = name.removeprefix('META-INF/services/')
                service_file = relocate_binary_name(service, replacements)
                candidate_names = ('META-INF/services/' + service_file, name)
                packaged_service = next((candidate for candidate in candidate_names if candidate in final.namelist()), None)
                require(packaged_service is not None, f'Service descriptor missing after shading: {name}')
                source_providers = {relocate_binary_name(line.split('#', 1)[0].strip(), replacements)
                                    for line in built.read_text(encoding='utf-8').splitlines()} - {''}
                built_providers = {line.split('#', 1)[0].strip() for line in final.read(packaged_service).decode('utf-8').splitlines()} - {''}
                require(source_providers <= built_providers, f'Shading dropped or failed to relocate a service provider: {name}')
                resources[name] = {'source_sha256': sha(path.read_bytes()), 'built_sha256': sha(built.read_bytes()),
                                   'packaged_service': packaged_service, 'source_providers': sorted(source_providers),
                                   'packaged_providers': sorted(built_providers)}
                continue
            require(final.read(name) == built.read_bytes(), f'Packaged resource differs from built resource: {name}')
            if name not in ('plugin.yml', 'config.yml') or repo == ROOT:
                require(path.read_bytes() == built.read_bytes(), f'Unfiltered source resource changed in build: {name}')
            resources[name] = {'source_sha256': sha(path.read_bytes()), 'built_sha256': sha(built.read_bytes())}
    return {'classes': hashes, 'resources': resources, 'original_sha256': sha(original_path.read_bytes())}


def relocate_binary_name(name, replacements):
    for before, after in replacements:
        name = name.replace(before.rstrip('/').replace('/', '.'), after.rstrip('/').replace('/', '.'))
    return name


def verify_sources_jar(repo, path):
    checked = {}
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        expected = {p.relative_to(repo / 'src/main/java').as_posix(): p for p in (repo / 'src/main/java').rglob('*.java')}
        actual = {name for name in names if name.endswith('.java') and not name.startswith('META-INF/')}
        require(actual == set(expected), 'Sources JAR Java source file set differs')
        for name, source in expected.items():
            require(archive.read(name) == source.read_bytes(), f'Sources JAR has stale source: {name}')
            checked[name] = sha(source.read_bytes())
    return {'sha256': sha(path.read_bytes()), 'source_hashes': checked}


def compile_fixtures(args, chronicle_jar, native_jar, out):
    fixtures = ['EpicCampaignPaperFixture.java', 'TechnologyHistoryPaperFixture.java', 'TradeHistoryPaperFixture.java',
                'TradeContributionPaperFixture.java', 'CapitalHistoryPaperFixture.java', 'ResourceHistoryPaperFixture.java',
                'ProcessingHistoryPaperFixture.java', 'BattlefieldPaperFixture.java']
    paths = [ROOT / 'tools' / name for name in fixtures]
    require(all(path.is_file() for path in paths), 'Missing acceptance fixture source')
    libraries = sorted(args.runtime_libraries.rglob('*.jar'))
    require(len(libraries) > 3, f'Missing existing runtime libraries: {args.runtime_libraries}')
    fixture_out = out / 'fixtures'
    fixture_out.mkdir()
    classpath = os.pathsep.join(str(path.resolve()) for path in [chronicle_jar, native_jar] + libraries)
    quote = lambda value: '"' + str(value).replace(chr(92), '/') + '"'
    argfile = out / 'fixtures.args'
    argfile.write_text('\n'.join(['-encoding', 'UTF-8', '-proc:none', '--release', '21', '-classpath', quote(classpath),
                                   '-d', quote(fixture_out.resolve())] + [quote(path.resolve()) for path in paths]) + '\n', encoding='utf-8')
    command = [str(args.java_home / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')), '-J-Xmx128m', '-J-Xss512k', '-J-XX:+UseSerialGC',
               '-J-XX:ActiveProcessorCount=1', '-J-XX:TieredStopAtLevel=1', '@' + str(argfile.resolve())]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=180)
    (out / 'fixtures.log').write_bytes(result.stdout + result.stderr)
    require(result.returncode == 0, f'Fixture compilation failed; inspect {out / "fixtures.log"}')
    compiled = {path.relative_to(fixture_out).as_posix(): sha(path.read_bytes()) for path in fixture_out.rglob('*.class')}
    require(compiled, 'Fixture compiler produced no classes')
    return {'count': len(paths), 'source_sha256': {path.name: sha(path.read_bytes()) for path in paths},
            'compiled_classes': compiled, 'command': command, 'server_started': False}


def main():
    global NATIVE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chronicle-snapshot', type=Path, required=True)
    parser.add_argument('--chronicle-build-snapshot', type=Path)
    parser.add_argument('--native-root', type=Path, default=NATIVE)
    parser.add_argument('--native-snapshot', type=Path, required=True)
    parser.add_argument('--native-build-snapshot', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--runtime-libraries', type=Path, default=ROOT / 'run/paper-epic-1faf2ba2-dungeon-phases-2/libraries')
    parser.add_argument('--chronicle-baseline', type=Path, default=ROOT / 'run/processing-history-baseline-20261126/chronicle.jar')
    parser.add_argument('--native-baseline', type=Path, default=ROOT / 'run/processing-history-baseline-20261126/native.jar')
    args = parser.parse_args()
    NATIVE = args.native_root.resolve()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    chronicle_jar, native_jar = ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar', NATIVE / 'target/starcore-1.0.0.jar'
    chronicle_prefix, native_prefix = 'io/github/addxiaoyi/starchronicle/', 'dev/starcore/starcore/'
    result = {'status': 'VERIFYING', 'scope': 'All baseline public project classes and their public/protected JVM descriptors; all current project class files; exact tested source snapshots; eight fixtures compiled only.'}
    try:
        result['chronicle_test_snapshot'] = source_snapshot(ROOT, args.chronicle_snapshot, require_tests=True, artifact=None if args.chronicle_build_snapshot else chronicle_jar)
        if args.chronicle_build_snapshot:
            result['chronicle_build_snapshot'] = source_snapshot(ROOT, args.chronicle_build_snapshot, require_tests=False, artifact=chronicle_jar)
        result['native_test_snapshot'] = source_snapshot(NATIVE, args.native_snapshot, require_tests=True)
        result['native_build_snapshot'] = source_snapshot(NATIVE, args.native_build_snapshot, require_tests=False, artifact=native_jar)
        result['chronicle_api'] = verify_api(args.chronicle_baseline, chronicle_jar, chronicle_prefix)
        result['native_api'] = verify_api(args.native_baseline, native_jar, native_prefix)
        result['chronicle_packaging'] = verify_packaging(ROOT, chronicle_jar, ROOT / 'target/original-starchronicle-0.1.0-SNAPSHOT.jar', chronicle_prefix,
                [('com/fasterxml/jackson/', chronicle_prefix + 'lib/jackson/'), ('org/yaml/snakeyaml/', chronicle_prefix + 'lib/yaml/')])
        result['native_packaging'] = verify_packaging(NATIVE, native_jar, NATIVE / 'target/original-starcore-1.0.0.jar', native_prefix,
                [('com/fasterxml/jackson/', native_prefix + 'libs/jackson/'), ('org/apache/commons/lang3/', native_prefix + 'libs/apache/commons/lang3/')])
        result['chronicle_sources_jar'] = verify_sources_jar(ROOT, ROOT / 'target/starchronicle-0.1.0-SNAPSHOT-sources.jar')
        api = ROOT / 'docs/API.md'
        with zipfile.ZipFile(chronicle_jar) as archive:
            require(archive.read('META-INF/starchronicle/docs/API.md') == api.read_bytes(), 'Packaged docs/API.md changed after build')
        result['api_document_sha256'] = sha(api.read_bytes())
        result['fixtures'] = compile_fixtures(args, chronicle_jar, native_jar, out)
        result['jars'] = {str(path): sha(path.read_bytes()) for path in (args.chronicle_baseline, args.native_baseline, chronicle_jar, native_jar)}
        result['status'] = 'VERIFIED'
        result['limits'] = 'No live Minecraft or client verification. Pre-shade classes match target/classes byte-for-byte; final shaded classes retain the complete declaration surface after configured relocation, not unrelocated class bytes. Native snapshot test counts do not imply full native-suite coverage.'
    except Exception as error:
        result['status'], result['error'] = 'FAILED', repr(error)
        raise
    finally:
        (out / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'status': result['status'], 'chronicle_api_types': len(result['chronicle_api']),
                      'chronicle_preserved_descriptors': sum(value['preserved'] for value in result['chronicle_api'].values()),
                      'native_api_types': len(result['native_api']),
                      'native_preserved_descriptors': sum(value['preserved'] for value in result['native_api'].values()),
                      'chronicle_classes': len(result['chronicle_packaging']['classes']),
                      'native_classes': len(result['native_packaging']['classes']),
                      'fixtures_compiled': result['fixtures']['count'], 'output': str(out)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
