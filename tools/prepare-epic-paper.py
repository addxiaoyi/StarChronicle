"""Prepare/compile a fresh fingerprint-bound two-plugin epic acceptance run."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = 'EpicCampaignPaperFixture'


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')


def prepare(args):
    if args.battlefield and args.route != 'WAR':
        raise SystemExit('Battlefield acceptance requires the WAR route')
    product, native = args.product.resolve(), args.starcore.resolve()
    product_sha, native_sha = sha(product), sha(native)
    run = ROOT / 'run' / f'paper-epic-{product_sha[:8]}-{args.route.lower()}-{args.attempt}'
    if run.exists():
        raise SystemExit('Refusing to replace a prior run or its evidence: ' + str(run))
    if not 1024 <= args.port <= 65535 or args.port == 25565:
        raise SystemExit('Use a distinct local test port')
    with zipfile.ZipFile(native) as archive:
        for resource in ('module/war/WarCompletionReceipt', 'module/dungeon/DungeonService'):
            if f'dev/starcore/starcore/{resource}.class' not in archive.namelist():
                raise SystemExit('Native candidate lacks ' + resource)
        config = archive.read('config.yml').decode('utf-8').replace('\r\n', '\n')
        dungeon = archive.read('dungeons.yml').decode('utf-8').replace('\r\n', '\n')
    config, count = re.subn(r'(?m)^(    nation-create-cost:) [^\n]+', r'\1 0.0', config)
    if count != 1:
        raise SystemExit('Native nation-cost config changed')
    config, count = re.subn(r'(?m)^(  new-nation-protection-days:) [^\n]+', r'\1 0', config)
    if count != 1:
        raise SystemExit('Native protection config changed')
    config, count = re.subn(r'(?m)^(map:\n  web:\n(?:    #[^\n]*\n)*)    enabled: true', r'\g<1>    enabled: false', config)
    if count != 1:
        raise SystemExit('Native web config changed')
    dungeon = dungeon.split('dungeons:\n', 1)[0] + '''dungeons:
  COOPERATIVE_RELIC:
    name: "Epic acceptance relic"
    description: ["Isolated native survival-timer fixture"]
    difficulty: easy
    icon: PAPER
    min-players: 1
    max-players: 2
    recommended-level: 1
    entry-fee: 0
    rewards: {experience: 0, gold: 0, items: []}
    rooms:
      archive:
        type: TRAP
        traps: []
        clear-condition: survive
        survive-duration-seconds: 3
    entrance: {world: world, x: 0, y: -59, z: 0, yaw: 0, pitch: 0}
'''
    for folder in ('evidence', 'fixture', 'plugins/StarCore', 'plugins/StarChronicle', f'plugins/{FIXTURE}'):
        (run / folder).mkdir(parents=True)
    runtime = args.runtime.resolve()
    retained = {}
    for original, destination in ((runtime / 'paper.jar', run / 'paper.jar'), (product, run / 'plugins/StarChronicle.jar'), (native, run / 'plugins/StarCore.jar')):
        if original.is_symlink():
            raise SystemExit('Refusing a symlink input')
        retained[str(original)] = sha(original)
        shutil.copy2(original, destination)
    for folder in ('libraries', 'cache'):
        if any(path.is_symlink() for path in (runtime / folder).rglob('*')):
            raise SystemExit('Refusing runtime symlinks')
        # Dependency/cache files are immutable inputs; worlds and plugin data remain isolated.
        shutil.copytree(runtime / folder, run / folder, copy_function=os.link)
    shutil.copy2(ROOT / 'tools' / f'{FIXTURE}.java', run / 'fixture' / f'{FIXTURE}.java')
    write(run / 'plugins/StarCore/config.yml', config)
    write(run / 'plugins/StarCore/dungeons.yml', dungeon)
    write(run / 'plugins/StarChronicle/config.yml', '''require-starcore: true
install-default-stories: false
install-native-example-quests: true
install-epic-strategy-example: true
admin-demo-enabled: false
npc-hooks: false
worker-queue-capacity: 2048
poll-ticks: 40
''')
    write(run / f'plugins/{FIXTURE}/config.yml', f'expected-sha: "{product_sha}"\nexpected-starcore-sha: "{native_sha}"\nroute: {args.route}\n')
    write(run / 'eula.txt', 'eula=true\n')
    write(run / 'ops.json', '[]\n')
    write(run / 'server.properties', f'''server-ip=127.0.0.1
server-port={args.port}
online-mode=false
enable-query=false
enable-rcon=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=4
difficulty=peaceful
level-type=minecraft\\:flat
generator-settings={{"biome":"minecraft:plains","layers":[{{"height":1,"block":"minecraft:bedrock"}},{{"height":2,"block":"minecraft:dirt"}},{{"height":1,"block":"minecraft:grass_block"}}]}}
generate-structures=false
spawn-monsters=false
motd=Isolated epic receipt acceptance
''')
    manifest = dict(plugin_sha256=product_sha, starcore_sha256=native_sha, route=args.route, ledger_acceptance=args.ledgers,
                    server_port=args.port, server_ip='127.0.0.1', fixture_source_sha256=sha(run / 'fixture' / f'{FIXTURE}.java'),
                    players={who: str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:Epic' + who).encode()).digest(), version=3)) for who in 'ABCD'},
                    source_artifacts=retained, fixture_compiled=False,
                    limitations=['Fixture seeds native treasury/resources and crafting ingredients.',
                                 'Nation creation cost and new-nation protection disabled only in this test configuration.',
                                 'Dungeon uses a three-second native survival room.',
                                 'War ending uses native principal peace, not battlefield victory.'])
    if args.battlefield:
        extra = ROOT / 'tools/BattlefieldPaperFixture.java'
        shutil.copy2(extra, run / 'fixture' / extra.name)
        manifest.update(fixture_main_class='BattlefieldPaperFixture',
                        fixture_extra_sources_sha256={extra.name: sha(extra)},
                        battlefield_acceptance=dict(mode='native stronghold score victory', strongholds=2,
                                                    points_each=50, expected_winner='AGGRESSOR',
                                                    end_reason='VICTORY_DETERMINED'))
        manifest['limitations'][-1:] = [
            'Fixture seeds a native battlefield and two defender-owned citadels; the native aggressor-authorized start service bypasses the preparation timer.',
            'Real non-OP clients capture through the native GUI and use the principal end command; no score setters or synthetic completion events.',
            'Menu-based stronghold score victory; no PvP, siege movement, army simulation, occupation duration or world-territory transfer claim.']
    write(run / 'preparation.json', json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(run=str(run), **manifest), ensure_ascii=False))


def compile_fixture(args):
    run = args.run.resolve()
    manifest = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
    if run.parent != (ROOT / 'run').resolve() or not run.name.startswith('paper-epic-'):
        raise SystemExit('Not an isolated epic run')
    for name, expected in [('StarChronicle', manifest['plugin_sha256']), ('StarCore', manifest['starcore_sha256'])]:
        if sha(run / f'plugins/{name}.jar') != expected:
            raise SystemExit('Frozen plugin SHA changed')
    source = run / 'fixture' / f'{FIXTURE}.java'
    if sha(source) != manifest['fixture_source_sha256']:
        raise SystemExit('Frozen fixture source changed; prepare a new run')
    sources = [source]
    for name, expected in manifest.get('fixture_extra_sources_sha256', {}).items():
        if Path(name).name != name or not name.endswith('.java'):
            raise SystemExit('Invalid fixture source name')
        extra = run / 'fixture' / name
        if sha(extra) != expected:
            raise SystemExit('Frozen additional fixture source changed: ' + name)
        sources.append(extra)
    target = run / f'plugins/{FIXTURE}.jar'
    if target.exists():
        raise SystemExit('Fixture already compiled')
    classes = run / 'fixture/classes'
    classes.mkdir(exist_ok=True)
    paper_api = Path.home() / '.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar'
    jars = [paper_api, run / 'plugins/StarChronicle.jar', run / 'plugins/StarCore.jar'] + sorted((run / 'libraries').rglob('*.jar'))
    argfile = run / 'fixture/javac.args'
    arguments = ['--release', '21', '-proc:none', '-encoding', 'UTF-8', '-cp',
                 os.pathsep.join(str(path) for path in jars), '-d', str(classes),
                 *(str(path) for path in sources)]
    write(argfile, '\n'.join('"' + value.replace('\\', '/') + '"' for value in arguments) + '\n')
    with (run / 'evidence/fixture-compile.log').open('w', encoding='utf-8') as log:
        javac = args.java_home / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
        result = subprocess.run([str(javac), '-J-Xmx128m', '-J-XX:+UseSerialGC', '@' + str(argfile)], stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        print((run / 'evidence/fixture-compile.log').read_text(encoding='utf-8', errors='replace'))
        raise SystemExit(result.returncode)
    with zipfile.ZipFile(target, 'x', zipfile.ZIP_DEFLATED) as archive:
        for path in classes.rglob('*.class'):
            archive.write(path, path.relative_to(classes).as_posix())
        archive.writestr('plugin.yml', f'name: {FIXTURE}\nversion: 1\nmain: smoke.{manifest.get("fixture_main_class", FIXTURE)}\napi-version: "1.21"\ndepend: [StarCore, StarChronicle]\ncommands:\n  epicverify:\n    description: Isolated epic acceptance\n')
    manifest.update(fixture_compiled=True, fixture_sha256=sha(target))
    write(run / 'preparation.json', json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(run=str(run), fixture_sha256=manifest['fixture_sha256'])))


def revise_fixture(args):
    run = args.run.resolve()
    if run.parent != (ROOT / 'run').resolve() or not run.name.startswith('paper-epic-'):
        raise SystemExit('Not an isolated epic run')
    manifest = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
    probe = socket.socket()
    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    probe.bind(('127.0.0.1', manifest['server_port']))
    probe.close()
    if not args.reason:
        raise SystemExit('A fixture revision requires a recorded reason')
    revisions = manifest.setdefault('fixture_revisions', [])
    old = run / 'evidence' / ('fixture-revision-' + str(len(revisions) + 1))
    old.mkdir()
    target = run / f'plugins/{FIXTURE}.jar'
    if sha(target) != manifest['fixture_sha256']:
        raise SystemExit('Unexpected previous fixture SHA')
    shutil.copy2(run / 'preparation.json', old / 'preparation.json')
    shutil.copy2(run / 'fixture' / f'{FIXTURE}.java', old / f'{FIXTURE}.java')
    target.rename(old / target.name)
    revisions.append(dict(reason=args.reason, previous_jar_sha256=manifest['fixture_sha256'],
                          previous_source_sha256=manifest['fixture_source_sha256'], archived=str(old)))
    source = run / 'fixture' / f'{FIXTURE}.java'
    shutil.copy2(ROOT / 'tools' / f'{FIXTURE}.java', source)
    manifest.update(fixture_compiled=False, fixture_source_sha256=sha(source))
    write(run / 'preparation.json', json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    compile_fixture(args)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'compile', 'revise-fixture'])
    parser.add_argument('--route', choices=['WAR', 'DUNGEON'])
    parser.add_argument('--attempt', default='1', type=lambda value: value if re.fullmatch(r'[a-z0-9-]{1,32}', value) else parser.error('Invalid attempt'))
    parser.add_argument('--port', type=int, default=25631)
    parser.add_argument('--product', type=Path, default=ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--starcore', type=Path)
    parser.add_argument('--runtime', type=Path, default=ROOT / 'run/smoke')
    parser.add_argument('--run', type=Path)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--reason')
    parser.add_argument('--ledgers', action='store_true', help='Exercise contribution and member review APIs across restart')
    parser.add_argument('--battlefield', action='store_true', help='Real non-OP stronghold captures and native score victory')
    args = parser.parse_args()
    if args.action == 'prepare': prepare(args)
    elif args.action == 'compile': compile_fixture(args)
    else: revise_fixture(args)
