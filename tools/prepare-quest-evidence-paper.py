"""Prepare a fresh SHA-bound native quest evidence Paper run without starting Java.

Only the explicit compile action invokes javac. This script never starts a server/client,
copies a world/database, edits an earlier run, or manufactures native completion evidence.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = Path(r'C:\Users\Administrator\Documents\StarChronicle\run\smoke')
FIXTURE = 'QuestEvidencePaperFixture'
PREFIX = 'paper-quest-evidence-'
NATIVE_QUESTS = {
    'heir': {'id': 'chronicle_heir_records', 'name': '继承证据的抄录', 'material': 'PAPER', 'amount': 3},
    'siege': {'id': 'chronicle_siege_testimonies', 'name': '幸存者的记录册', 'material': 'BOOK', 'amount': 1},
    'atlas': {'id': 'chronicle_caravan_atlas', 'name': '古商路的地图册', 'material': 'MAP', 'amount': 1},
}
STORIES = {
    'player': {'id': 'qe-player-records', 'title': 'Quest evidence — personal records', 'quest': NATIVE_QUESTS['heir']['id'], 'mode': 'PLAYER', 'honor': 3, 'ending': 'QE_PLAYER_FINISHED', 'choice': 'yes'},
    'nation': {'id': 'qe-nation-testimonies', 'title': 'Quest evidence — country testimonies', 'quest': NATIVE_QUESTS['siege']['id'], 'mode': 'NATION', 'honor': 7, 'ending': 'QE_NATION_FINISHED', 'choice': 'yes'},
    'atlas': {'id': 'qe-player-atlas', 'title': 'Quest evidence — caravan atlas', 'quest': NATIVE_QUESTS['atlas']['id'], 'mode': 'PLAYER', 'honor': 5, 'ending': 'QE_ATLAS_FINISHED', 'choice': 'yes'},
}


def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def players():
    return {who: str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:PhaseTwo' + who).encode()).digest(), version=3)) for who in 'ABC'}


def run_path(expected, supplied=None, attempt='retry1'):
    if not re.fullmatch(r'[a-z0-9-]{1,24}', attempt):
        raise SystemExit('Attempt suffix must contain 1-24 lowercase ASCII letters, digits or hyphens')
    run = (ROOT / 'run' / (PREFIX + expected[:8] + '-' + attempt)).resolve()
    if supplied is not None and Path(supplied).resolve() != run:
        raise SystemExit('Require exact worktree/run/paper-quest-evidence-<sha8>-<attempt> directory')
    if run.parent != (ROOT / 'run').resolve():
        raise SystemExit('Isolated target escaped this worktree')
    return run


def content():
    result = {}
    for key, meta in STORIES.items():
        result[meta['id'] + '.json'] = {
            'id': meta['id'], 'version': '1', 'title': meta['title'], 'scope': 'WORLD',
            'voteUnit': 'NATION' if key == 'nation' else 'PLAYER',
            'triggers': ['QE_' + key.upper() + '_FIXTURE'], 'start': 'await', 'cooldownSeconds': 0,
            'nodes': {
                'await': {
                    'text': 'Fixture-only acceptance: real CRAFT_ITEM progress and the actual native GUI must commit a durable completion before this choice is available.',
                    'quorum': 1,
                    'effects': [{'id': 'native-craft-proof', 'type': 'quest.await', 'target': meta['quest'], 'value': meta['mode']}],
                    'choices': {'yes': {'label': 'Record the country decision' if key == 'nation' else 'Record the actual expedition', 'next': 'done'}},
                },
                'done': {
                    'text': 'The true native receipt and actual player choice are preserved once.', 'terminal': True,
                    'effects': [
                        {'id': 'stable-honor', 'type': 'honor', 'target': 'player', 'value': str(meta['honor'])},
                        {'id': 'stable-ending', 'type': 'history', 'target': meta['ending'], 'value': 'Fixture-only completed campaign, following real native craft and GUI submission.'},
                    ],
                },
            },
        }
    return result


RUNBOOK = '''# Native quest evidence Paper procedure (not a PASS report)

Default preparation starts no JVM, server or client. Compile is a separate explicitly chosen
action in the coordinator's JVM window. Each run is ROOT/run/paper-quest-evidence-<sha8>-<attempt>.
Both Chronicle and the patched StarCore JAR are SHA-bound. No old world, player data, plugin
data, SQLite database, native quest data, ops list or whitelist is copied.

Prepare:
    python tools/prepare-quest-evidence-paper.py prepare --expected-sha SHA --starcore PATCHED_JAR --expected-starcore-sha CORE_SHA
Compile (coordinator only):
    python tools/prepare-quest-evidence-paper.py compile --expected-sha SHA --expected-starcore-sha CORE_SHA --java-home JAVA21
Run serial boots using the coordinator's quest-evidence-session.py. Wait for Paper Done and
QUEST_EVIDENCE_READY before starting clients. After each client cohort starts, wait for a real
SPAWN record for every selected original player before issuing any qeverify command. Wait for
each SOURCE/SUBMITTED response before giving out its crafting kit; wait for each CRAFTED result
before capturing its native progress. Every dependent step requires its real response.
QUEST_EVIDENCE_FAILED or client ACTION_FAILED is a failure; do not silently relabel it PASS.

The three clients are PhaseTwoA/B/C, pinned offline UUIDs in preparation.json, always non-OP.
seed invokes actual NationService.createNation for A/C and addMember for ordinary B in A's
country. Later seed calls only grant basic permissions, read actual memberships and rebuild
the explicitly fixture-only crafting station. No nationality is supplied to Chronicle.

## Boot one — real acceptance and craft readiness without completion

    clients start A,B,C  # wait for SPAWN A, B, C
    server qeverify seed
    server qeverify begin player  # wait for SUBMITTED source player
    server qeverify begin nation  # wait for SUBMITTED source nation
    server qeverify begin atlas   # wait for SUBMITTED source atlas
    server qeverify craft-kit A paper
    server qeverify craft-kit B book
    server qeverify craft-kit C map
    client craft A paper  # wait for CRAFTED A paper
    client craft B book   # wait for CRAFTED B book
    client craft C map    # wait for CRAFTED C map
    server qeverify check-native A player 3 capture
    server qeverify check-native B nation 1 capture
    server qeverify check-native C atlas 1 capture
    server qeverify state A player
    server qeverify state A nation
    server qeverify state B nation
    server qeverify state C nation
    server qeverify state C atlas
    server qeverify flush
    client quit all
    server stop

Wait for actual driver admission before crafting: check-native WHO STORY 0 read can inspect
the active native identity first. PAPER is crafted from three sugar cane (one actual recipe
produces three paper), BOOK from three paper plus leather, MAP from eight paper plus compass.
The fixture only supplies those real ingredients and a physical crafting table. Clients use
the crafting inventory; the real CraftItemEvent listener updates native progress. No fixture
calls an objective setter. Progress 3/1/1 must be actual ACTIVE runs without completion IDs;
all corresponding Chronicle choices remain blocked. Other independent country runs for A/C
may remain at zero. Flush confirms real native persistence before a normal server stop.

## Boot two — late template restoration and genuine native GUI completion

    clients start A,B,C  # wait for SPAWN A, B, C
    server qeverify seed
    server qeverify check-native A player 3 restore
    server qeverify check-native B nation 1 restore
    server qeverify check-native C atlas 1 restore
    server qeverify unregister-provider
    client native-open A heir
    client native-complete A
    client native-open B siege
    client native-complete B
    client native-open C atlas
    client native-complete C
    server qeverify check-receipt A player
    server qeverify check-receipt B nation
    server qeverify check-receipt C atlas
    server qeverify check-paused
    client quit B
    server qeverify check-receipt B nation
    server qeverify flush
    client quit all
    server stop

The native templates register after StarCore initializes stored quests. Restore compares
the real run ID, start millisecond, definition fingerprint, progress and immutable accepted
evidence against boot one's observed hash. No native service is reinitialized by the fixture.
unregister-provider legally unregisters only Chronicle's real builtin QuestEvidenceProvider
from Bukkit ServicesManager and records its exact domain-bound ID. No substitute is installed.
The native QuestEvidenceService remains available and actual quest completion still works.

Native GUI path is /questgui progress -> the exact task name -> detail. Verify actual ID,
name and title; complete only via slot 32 NETHER_STAR / 完成任务 with both expected lore lines.
The native detail inventory must be the audited fixed 54 slots (old 36 + slot49 was invalid).
Fixture native-gui WHO can request opening this native command, but never clicks or completes.
check-receipt reads canonical native lookup after the actual native state and immutable journal
acknowledge, and verifies the independent native PlayerQuest.RunCompletion identity. It never
creates, supplies or submits a completion. B must really disconnect before the normal save.

## Boot three — offline canonical receipt, national GUI, exact rewards and replay

    clients start A,C  # wait for SPAWN A, C before seed or checks
    server qeverify seed
    server qeverify check-receipt A player
    server qeverify check-receipt B nation
    server qeverify check-receipt C atlas
    server qeverify check-offline-country
    client open-story A Quest evidence — personal records
    client choice A yes
    client open-story A Quest evidence — country testimonies
    client choice A yes
    client open-story C Quest evidence — caravan atlas
    client choice C yes
    server qeverify check-final-before-replay  # wait for PRE_REPLAY_CHECK_PASS
    server qeverify replay player
    server qeverify replay nation
    server qeverify replay atlas
    server qeverify check-final  # wait for exactly one FINAL_CHECK_PASS
    client quit all
    server stop

Only A/C connect. B's already pinned offline gate is discovered by canonical lookup, making
A's original country ready while C's country is blocked. A is the actual native holder and
uses the true national GUI; B remains an ordinary member and is not given a country seat.
The country quorum is one for this evidence test; existing national tests cover ordinary
member rejection. Country-story honor intentionally rewards all three recorded witnesses
through existing engine semantics: seven each, plus A's personal three and C's personal five.
Thus final online honor is A=10/C=12 once. Exactly three quest.completed histories and one
ending event of each configured type must remain. Canonical receipt hashes must be unchanged
after restart. replay submits only each previously observed original fixture source, never
a native completion DTO; the driver alone consumes canonical native evidence.

The coordinator should correlate actual clients/GUI, fixture JSONL, native service snapshots,
normal process exits, native persistent files and read-only stopped Chronicle DB inspection.
Preparation and source code are not observed validation evidence or a full upstream/Folia claim.
'''


def source_unchanged(manifest):
    return all(Path(path).is_file() and digest(path) == sha for path, sha in manifest['source_preserved_sha256'].items())


def prepare(args, run):
    if run.exists():
        raise SystemExit('Refusing to overwrite an existing run or its evidence')
    if args.starcore is None:
        raise SystemExit('Prepare requires explicit --starcore patched JAR; no fallback to an old runtime plugin')
    source = args.source_runtime.resolve()
    if source == run or source in run.parents or run in source.parents:
        raise SystemExit('Source and fresh target must be distinct nonnested directories')
    paper = (args.paper or source / 'paper.jar').resolve()
    core, product = args.starcore.resolve(), args.product.resolve()
    fixture_source = ROOT / 'tools' / (FIXTURE + '.java')
    for artifact in (paper, core, product, fixture_source):
        if not artifact.is_file() or artifact.is_symlink():
            raise SystemExit('Require regular retained artifact: ' + str(artifact))
    if digest(product) != args.expected_sha or digest(core) != args.expected_starcore_sha:
        raise SystemExit('Explicit frozen product/native JAR SHA mismatch')
    if not 1024 <= args.port <= 65535 or args.port in {25565, 25579, 25589, 25590, 25591, 25603, 25604, 25605, 25606}:
        raise SystemExit('Require a distinct isolated loopback port (default 25617)')
    with zipfile.ZipFile(product) as jar:
        for name in ('core/QuestGateLedger', 'core/QuestGateDriver', 'api/quest/QuestEvidenceProvider', 'integration/StarCoreQuestEvidenceProvider'):
            if 'io/github/addxiaoyi/starchronicle/' + name + '.class' not in jar.namelist():
                raise SystemExit('Product lacks the actual native-evidence implementation: ' + name)
    with zipfile.ZipFile(core) as jar:
        for name in ('quest/evidence/QuestEvidenceService', 'quest/evidence/QuestCompletionJournal', 'quest/gui/QuestMenu'):
            if 'dev/starcore/starcore/' + name + '.class' not in jar.namelist():
                raise SystemExit('Native JAR lacks reviewed patched protocol: ' + name)
        config = jar.read('config.yml').decode('utf-8').replace('\r\n', '\n')
    config, costs = re.subn(r'(?m)^(    nation-create-cost:) [^\n]+', r'\1 0.0', config)
    config, web = re.subn(r'(?m)^(map:\n  web:\n(?:    #[^\n]*\n)*)    enabled: true', r'\g<1>    enabled: false', config)
    if costs != 1 or web != 1:
        raise SystemExit('Reviewed native create cost/web config shape changed; inspect before running')
    retained = [paper, core]
    for folder in ('cache', 'libraries'):
        if any(path.is_symlink() for path in (source / folder).rglob('*')):
            raise SystemExit('Refusing source runtime symlinks')
        retained.extend(sorted(path for path in (source / folder).rglob('*') if path.is_file()))
    preserved = {str(path): digest(path) for path in retained}
    for folder in ('plugins/StarCore', 'plugins/StarChronicle/stories', 'plugins/' + FIXTURE, 'fixture', 'evidence'):
        (run / folder).mkdir(parents=True, exist_ok=True)
    for original, target in ((paper, 'paper.jar'), (core, 'plugins/StarCore.jar'), (product, 'plugins/StarChronicle.jar'), (fixture_source, 'fixture/' + FIXTURE + '.java')):
        shutil.copy2(original, run / target)
    for folder in ('cache', 'libraries'):
        if (source / folder).is_dir():
            shutil.copytree(source / folder, run / folder)
    (run / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    (run / 'ops.json').write_text('[]\n', encoding='utf-8')
    (run / 'whitelist.json').write_text('[]\n', encoding='utf-8')
    (run / 'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={args.port}
online-mode=false
difficulty=peaceful
enable-query=false
enable-rcon=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=3
level-type=minecraft\\:flat
generator-settings={{"biome":"minecraft:plains","layers":[{{"height":1,"block":"minecraft:bedrock"}},{{"height":2,"block":"minecraft:dirt"}},{{"height":1,"block":"minecraft:grass_block"}}]}}
generate-structures=false
spawn-monsters=false
motd=Isolated quest evidence verification
''', encoding='utf-8')
    (run / 'plugins/StarCore/config.yml').write_text(config, encoding='utf-8')
    (run / 'plugins/StarChronicle/config.yml').write_text('require-starcore: true\ninstall-default-stories: false\ninstall-native-example-quests: true\nadmin-demo-enabled: false\nnpc-hooks: false\nworker-queue-capacity: 2048\npoll-ticks: 40\n', encoding='utf-8')
    (run / ('plugins/' + FIXTURE + '/config.yml')).write_text('expected-sha: "' + args.expected_sha + '"\nexpected-starcore-sha: "' + args.expected_starcore_sha + '"\n', encoding='utf-8')
    story_hashes = {}
    for name, story in content().items():
        path = run / 'plugins/StarChronicle/stories' / name
        write_json(path, story)
        story_hashes[name] = digest(path)
    (run / 'RUNBOOK.md').write_text(RUNBOOK, encoding='utf-8')
    manifest = {
        'status': 'PREPARED_NOT_COMPILED_NOT_RUN', 'prepared_utc': now(), 'run_directory': str(run),
        'source_runtime': str(source), 'plugin_sha256': args.expected_sha, 'starcore_sha256': args.expected_starcore_sha,
        'paper_sha256': digest(paper), 'fixture_source_sha256': digest(fixture_source), 'fixture_sha256': None,
        'fixture_compiled': False, 'server_port': args.port, 'server_ip': '127.0.0.1', 'players': players(),
        'native_quests': NATIVE_QUESTS, 'stories': STORIES, 'story_sha256': story_hashes,
        'source_preserved_sha256': preserved, 'source_database_copied': False, 'source_world_copied': False,
        'server_started': False, 'jvm_started_by_prepare': False,
        'fixture_evidence': 'evidence/quest-fixture.jsonl', 'fixture_ready_marker': 'QUEST_EVIDENCE_READY',
        'native_gui': {'progress_command': '/questgui progress', 'progress_title': '任务进度', 'detail_title': '任务详情', 'complete_slot': 32, 'complete_material': 'NETHER_STAR', 'complete_label': '完成任务'},
        'scope': 'Actual native CRAFT_ITEM, native GUI completion and post-ack canonical evidence across three normal Paper boots; three non-OP clients, offline donor and actual country choice.',
    }
    if not source_unchanged(manifest):
        raise SystemExit('Retained source assets changed during read-only preparation')
    write_json(run / 'preparation.json', manifest)
    print(json.dumps({'run': str(run), 'plugin_sha256': args.expected_sha, 'starcore_sha256': args.expected_starcore_sha, 'players': players(), 'port': args.port, 'jvm_started': False, 'server_started': False, 'next': 'Coordinator compile and three serial sessions; RUNBOOK is a procedure, not a report'}, ensure_ascii=False, indent=2))


def load(args, run):
    manifest = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
    if manifest['plugin_sha256'] != args.expected_sha or manifest['starcore_sha256'] != args.expected_starcore_sha:
        raise SystemExit('Frozen manifest SHA differs from explicit coordinator values')
    for name, sha in (('StarChronicle', args.expected_sha), ('StarCore', args.expected_starcore_sha)):
        if digest(run / ('plugins/' + name + '.jar')) != sha:
            raise SystemExit('Frozen deployed JAR SHA drift: ' + name)
    if digest(run / 'paper.jar') != manifest['paper_sha256'] or not source_unchanged(manifest):
        raise SystemExit('Retained Paper/source assets changed')
    for name, sha in manifest['story_sha256'].items():
        if digest(run / 'plugins/StarChronicle/stories' / name) != sha:
            raise SystemExit('Prepared story definition changed: ' + name)
    return manifest


def compile_fixture(args, run):
    manifest = load(args, run)
    target = run / ('plugins/' + FIXTURE + '.jar')
    if manifest.get('fixture_compiled') or target.exists():
        raise SystemExit('Refusing replacement of a compiled acceptance fixture')
    compiler = None if args.java_home is None else args.java_home / 'bin/javac.exe'
    if compiler is None or not compiler.is_file():
        raise SystemExit('Explicit compile requires --java-home Java21 and the coordinator JVM window')
    source = run / ('fixture/' + FIXTURE + '.java')
    if digest(source) != manifest['fixture_source_sha256'] or digest(ROOT / 'tools' / (FIXTURE + '.java')) != manifest['fixture_source_sha256']:
        raise SystemExit('Fixture source changed after preparation; retain old attempt and reprepare with reviewed source')
    paper_api = args.paper_api or Path.home() / '.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar'
    if not paper_api.is_file():
        raise SystemExit('Retained Paper API compile JAR missing; provide --paper-api')
    attempts = manifest.setdefault('fixture_compile_attempts', [])
    attempt = len(attempts) + 1
    classes = run / ('fixture/classes-attempt' + str(attempt))
    classes.mkdir()
    jars = sorted((run / 'libraries').rglob('*.jar')) + [run / 'plugins/StarChronicle.jar', run / 'plugins/StarCore.jar', paper_api]
    argfile = run / ('fixture/javac-attempt' + str(attempt) + '.args')
    argfile.write_text('--release 21\n-proc:none\n-encoding UTF-8\n-cp\n"' + os.pathsep.join(str(path) for path in jars).replace('\\', '/') + '"\n-d\n"' + str(classes).replace('\\', '/') + '"\n"' + str(source).replace('\\', '/') + '"\n', encoding='utf-8')
    command = [str(compiler), '-J-Xmx128m', '-J-XX:MaxMetaspaceSize=96m',
               '-J-XX:CompressedClassSpaceSize=64m', '-J-XX:ActiveProcessorCount=1',
               '-J-XX:+UseSerialGC', '@' + str(argfile)]
    log = run / ('evidence/fixture-compile-attempt' + str(attempt) + '.log')
    try:
        result = subprocess.run(command, capture_output=True, timeout=120, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        log.write_bytes(result.stdout + result.stderr)
        code = result.returncode
    except subprocess.TimeoutExpired as error:
        log.write_bytes((error.stdout or b'') + (error.stderr or b'') + b'\nEXPLICIT JAVAC TIMEOUT\n')
        code = 'TIMEOUT'
    attempts.append({'attempt': attempt, 'command': command, 'exit_code': code, 'log': str(log.relative_to(run)), 'source_sha256': digest(source), 'at_utc': now()})
    if code != 0:
        manifest['status'] = 'FIXTURE_COMPILE_FAILED'
        write_json(run / 'preparation.json', manifest)
        raise SystemExit('Fixture compilation failed; original attempt retained in ' + str(log))
    with zipfile.ZipFile(target, 'x', zipfile.ZIP_DEFLATED) as jar:
        jar.writestr('plugin.yml', 'name: ' + FIXTURE + '\nversion: 1\nmain: smoke.' + FIXTURE + '\napi-version: "1.21"\ndepend: [StarCore, StarChronicle]\ncommands:\n  qeverify:\n    description: Isolated real native quest evidence verification\n')
        for path in sorted(classes.rglob('*.class')):
            jar.write(path, path.relative_to(classes).as_posix())
    manifest.update(fixture_compiled=True, fixture_sha256=digest(target), java_home=str(args.java_home), compiled_utc=now(), status='COMPILED_NOT_RUN')
    if not source_unchanged(manifest):
        raise SystemExit('Retained source files changed during fixture compilation')
    write_json(run / 'preparation.json', manifest)
    print(json.dumps({'fixture_sha256': manifest['fixture_sha256'], 'plugin_sha256': args.expected_sha, 'starcore_sha256': args.expected_starcore_sha, 'server_started': False}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', nargs='?', choices=['prepare', 'compile', 'check'], default='prepare')
    parser.add_argument('--expected-sha', '--expected-plugin-sha256', dest='expected_sha', required=True)
    parser.add_argument('--expected-starcore-sha', required=True)
    parser.add_argument('--run-dir', type=Path)
    parser.add_argument('--attempt', default='retry1')
    parser.add_argument('--source-runtime', type=Path, default=DEFAULT_SOURCE)
    parser.add_argument('--product', type=Path, default=ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--starcore', type=Path)
    parser.add_argument('--paper', type=Path)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--paper-api', type=Path)
    parser.add_argument('--port', type=int, default=25617)
    args = parser.parse_args()
    if not all(re.fullmatch(r'[0-9a-f]{64}', sha) for sha in (args.expected_sha, args.expected_starcore_sha)):
        raise SystemExit('Require complete exact coordinator-frozen Chronicle and patched StarCore SHA-256 values')
    run = run_path(args.expected_sha, args.run_dir, args.attempt)
    if args.action == 'prepare':
        prepare(args, run)
    elif args.action == 'compile':
        compile_fixture(args, run)
    else:
        manifest = load(args, run)
        if manifest.get('fixture_compiled') and digest(run / ('plugins/' + FIXTURE + '.jar')) != manifest['fixture_sha256']:
            raise SystemExit('Compiled fixture SHA drift')
        print(json.dumps({'source_unchanged': True, 'fixture_compiled': manifest.get('fixture_compiled', False), 'plugin_sha256': args.expected_sha, 'starcore_sha256': args.expected_starcore_sha, 'jvm_started': False, 'server_started': False}, indent=2))


if __name__ == '__main__':
    main()
