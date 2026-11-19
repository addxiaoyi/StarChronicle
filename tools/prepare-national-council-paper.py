"""Prepare a fresh SHA-bound Paper national-council acceptance run, or explicitly compile
its fixture during the coordinator's JVM window. No server is ever started by this tool.
Default/prepare mode invokes no JVM and never copies an old world or plugin database.
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


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def players():
    return {who: str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:PhaseTwo' + who).encode()).digest(), version=3)) for who in 'ABCD'}


def run_path(expected, supplied=None):
    run = (ROOT / 'run' / ('paper-national-' + expected[:8])).resolve()
    if supplied is not None and Path(supplied).resolve() != run:
        raise SystemExit('Run directory must be exactly this worktree/run/paper-national-<sha8>')
    if run.parent != (ROOT / 'run').resolve():
        raise SystemExit('Isolated run path escaped this worktree')
    return run


def content():
    def story(name, event, quorum, rewards):
        return {'id': name, 'version': '1', 'title': 'National smoke primary' if quorum == 2 else 'National smoke invalidation',
                'scope': 'WORLD', 'voteUnit': 'NATION', 'triggers': [event], 'start': 'council', 'cooldownSeconds': 0,
                'nodes': {'council': {'text': 'A formal country seat requires the current native holder. Ordinary witnesses have no national vote.',
                                      'quorum': quorum, 'choices': {'yes': {'label': 'Support as a country', 'next': 'done'}, 'no': {'label': 'Oppose as a country', 'next': 'done'}}},
                          'done': {'text': 'The actual country decision and its stable receipts are retained.', 'terminal': True, 'effects': rewards}}}
    return {
        'national-smoke-primary.json': story('national-smoke-primary', 'NATIONAL_PRIMARY_TEST', 2, [
            {'id': 'one-honor-receipt', 'type': 'honor', 'target': 'player', 'value': '7'},
            {'id': 'actual-national-ending', 'type': 'history', 'target': 'NATIONAL_COUNCIL_SMOKE_FINISHED', 'value': 'The country council made its actual recorded decision.'}]),
        'national-smoke-invalidation.json': story('national-smoke-invalidation', 'NATIONAL_INVALIDATION_TEST', 3, [])}


RUNBOOK = r'''# SHA-bound real Paper national-council verification

This file is a procedure, not a test report. Preparation does not compile Java or start a server.
Only the coordinator may use compile/session commands during its assigned JVM window.

Use the frozen full SHA throughout. The run is this isolated worktree/run/paper-national-<sha8>.
The retained source runtime contributes only Paper/StarCore JARs and runtime cache/libraries;
no source world, plugin data, SQLite database, player/op list or running server is modified.

1. Prepare: `python tools/prepare-national-council-paper.py prepare --expected-sha SHA`
2. Compile the fixture: `python tools/prepare-national-council-paper.py compile --expected-sha SHA --java-home JAVA21`
3. Start serial boots only with root's `tools/national-council-session.py --run-dir RUN --expected-sha SHA --boot 1|2|3 --java-home JAVA21`.
4. Wait for the actual Paper Done and NATIONAL_READY log before `clients start`. All four
   real offline-mode clients are PhaseTwoA/B/C/D with manifest-pinned UUIDs, and remain non-OP.
5. Commands below are input to that session controller. Wait for each actual fixture/client
   response before the next dependent command. NATIONAL_FAILED or ACTION_FAILED is a failure.

## Boot one: real holders, real GUI, strict CAS, native succession, withdrawal checkpoint

    clients start
    server ncverify seed
    server ncverify begin primary
    server ncverify state A primary
    client open-story A National smoke primary
    client inspect A
    client choice A yes
    server ncverify state A primary
    server ncverify reject-vote A primary no 0
    server ncverify reject-vote D primary yes 1
    server ncverify vote B primary no 0
    server ncverify transfer A D
    server ncverify reject-vote A primary yes 1
    server ncverify withdraw B primary 1
    server ncverify vote D primary no 1
    server ncverify withdraw D primary 2
    server ncverify vote D primary yes 3
    server ncverify check-pending
    server ncverify state B primary
    client quit
    server stop

`seed` calls real NationService.createNation for A/B/C and addMember for D in A's nation.
`transfer A D` calls actual transferOwnership, verifies new/old native ownership and persists
through StarCore. D must retain native authority and revision 4. B must retain a WITHDRAWN
tombstone at revision 2. The unresolved count is yes=1, despite two citizens A/D of one country.
Capture GUI rules/identity/counts and the actual rejected stale/ordinary/former-holder requests.
The client `choice` parser must recognize `国家选项: ` lore in the actual national GUI.

## Boot two: recover pending/tombstone, real GUI freeze, actual native disappearance

    clients start
    server ncverify seed
    server ncverify check-pending
    server ncverify state D primary
    server ncverify vote B primary no 2
    client open-story C National smoke primary
    client inspect C
    client choice C yes
    server ncverify check-frozen
    server ncverify facts D
    server ncverify replay primary
    server ncverify check-frozen
    server ncverify begin invalidation
    server ncverify vote D invalidation yes 0
    server ncverify vote B invalidation yes 0
    server ncverify vote C invalidation no 0
    server ncverify disband B
    server ncverify vote D invalidation yes 1
    server ncverify check-invalidated
    client quit
    server stop

The primary must freeze yes with exactly two supporting countries and the opposing country no.
The resolved WORLD fact and each country's stance are separate from personal BALLOT facts.
The exact local honor is seven once; canonical replay must be duplicate and preserve seven.
The second campaign uses quorum three. Native disbandNation(B) followed by a real valid
submission must invalidate B at revision 2 with choice=null and leave yes=1/no=1 unresolved.
No fixture code edits Chronicle data, directly advances a story, sets rewards or supplies a
made-up PlayerContext/NationalVoteAuthority. `source` signals are explicitly test-only and
observe actual connected players/countries; they do not claim a natural production discovery.

## Boot three: frozen history and invalidation checkpoint survive a second restart

    clients start
    server ncverify seed
    server ncverify check-frozen
    server ncverify check-invalidated
    server ncverify replay primary
    server ncverify check-frozen
    server ncverify reject-vote D primary yes 4
    server ncverify move A C
    server ncverify transfer C A
    server ncverify reject-vote A invalidation yes 1
    server ncverify state A invalidation
    server ncverify check-invalidated
    server ncverify facts D
    server ncverify state D primary
    client chat D /chronicle national ACTUAL_PRIMARY_INSTANCE_ID_FROM_STATE
    client wait-window D 国家议会
    client inspect D
    client click-name D 各国已记录票
    client inspect D
    client quit
    server stop

Use the actual ID observed in STATE, never a fabricated one. `seed` on later boots grants only
the same basic non-admin permission attachment and reads identities; it never recreates the
disbanded country or resets votes. The primary historical no ballot remains recorded after
its country later disappears; frozen history must not be dynamically invalidated. The second
campaign's INVALIDATED tombstone must persist without a new source submission.
The move/transfer sequence uses actual native leaveNation/addMember/transferOwnership to
make A the current holder of C's country. A must still be rejected in the existing campaign
because its original participant nationality was A's country; no ballot moves across borders.

The coordinator should inspect the stopped owned database read-only to verify schema=5,
integrity/FKs, one national resolution, no personal BALLOT for these national nodes, stable
receipts/ending ID, actor/revision history and original source evidence. Correlate all findings
with preparation.json, compile attempts, normal process exit records, client GUI logs and
evidence/national-fixture.jsonl. Do not label this planned route PASS until actually observed.
'''


def source_unchanged(manifest):
    return all(Path(path).is_file() and digest(Path(path)) == fingerprint for path, fingerprint in manifest['source_preserved_sha256'].items())


def prepare(args, run):
    if run.exists():
        raise SystemExit('Refusing to overwrite an existing run or acceptance evidence')
    source = args.source_runtime.resolve()
    if run == source or run in source.parents or source in run.parents:
        raise SystemExit('Source runtime and target must be distinct nonnested directories')
    product = args.product.resolve()
    paper = (args.paper or source / 'paper.jar').resolve()
    core = (args.starcore or source / 'plugins/StarCore.jar').resolve()
    fixture_source = ROOT / 'tools/NationalCouncilPaperFixture.java'
    for artifact in [product, paper, core, fixture_source]:
        if not artifact.is_file():
            raise SystemExit('Missing retained artifact: ' + str(artifact))
    if digest(product) != args.expected_sha:
        raise SystemExit('The product JAR differs from the complete expected frozen SHA-256')
    if not 1024 <= args.port <= 65535 or args.port in {25565, 25579, 25589, 25590, 25591}:
        raise SystemExit('Use a distinct isolated loopback port; default is 25603')
    with zipfile.ZipFile(product) as archive:
        for name in ['io/github/addxiaoyi/starchronicle/core/NationalVoteLedger.class', 'io/github/addxiaoyi/starchronicle/api/NationalVoteAuthority.class', 'io/github/addxiaoyi/starchronicle/api/NationalVoteState.class']:
            if name not in archive.namelist():
                raise SystemExit('The JAR predates the actual national-ballot implementation')
    with zipfile.ZipFile(core) as archive:
        config = archive.read('config.yml').decode('utf-8').replace('\r\n', '\n')
    config, costs = re.subn(r'(?m)^(    nation-create-cost:) [^\n]+', r'\1 0.0', config)
    config, web = re.subn(r'(?m)^(map:\n  web:\n(?:    #[^\n]*\n)*)    enabled: true', r'\g<1>    enabled: false', config)
    if costs != 1 or web != 1:
        raise SystemExit('Reviewed native create-cost/web config shape changed; inspect before running')
    retained = [paper, core]
    for folder in ['cache', 'libraries']:
        if any(path.is_symlink() for path in (source / folder).rglob('*')):
            raise SystemExit('Refusing runtime asset symlinks into another source tree')
        retained.extend(sorted(path for path in (source / folder).rglob('*') if path.is_file()))
    preserved = {str(path.resolve()): digest(path) for path in retained}
    for folder in ['plugins/StarCore', 'plugins/StarChronicle/stories', 'plugins/NationalCouncilPaperFixture', 'fixture', 'evidence']:
        (run / folder).mkdir(parents=True, exist_ok=True)
    shutil.copy2(paper, run / 'paper.jar')
    shutil.copy2(core, run / 'plugins/StarCore.jar')
    shutil.copy2(product, run / 'plugins/StarChronicle.jar')
    shutil.copy2(fixture_source, run / 'fixture/NationalCouncilPaperFixture.java')
    for folder in ['cache', 'libraries']:
        if (source / folder).is_dir():
            shutil.copytree(source / folder, run / folder)
    (run / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    (run / 'ops.json').write_text('[]\n', encoding='utf-8')
    (run / 'whitelist.json').write_text('[]\n', encoding='utf-8')
    properties = f'''server-ip=127.0.0.1
server-port={args.port}
online-mode=false
enable-query=false
enable-rcon=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=4
level-type=minecraft\\:flat
generator-settings={{"biome":"minecraft:plains","layers":[{{"height":1,"block":"minecraft:bedrock"}},{{"height":2,"block":"minecraft:dirt"}},{{"height":1,"block":"minecraft:grass_block"}}]}}
generate-structures=false
spawn-monsters=false
motd=Isolated national council verification
'''
    (run / 'server.properties').write_text(properties, encoding='utf-8')
    (run / 'plugins/StarCore/config.yml').write_text(config, encoding='utf-8')
    (run / 'plugins/StarChronicle/config.yml').write_text('require-starcore: true\ninstall-default-stories: false\nadmin-demo-enabled: false\nnpc-hooks: false\nworker-queue-capacity: 2048\npoll-ticks: 40\n', encoding='utf-8')
    (run / 'plugins/NationalCouncilPaperFixture/config.yml').write_text('expected-sha: "' + args.expected_sha + '"\n', encoding='utf-8')
    story_hashes = {}
    for name, story in content().items():
        path = run / 'plugins/StarChronicle/stories' / name
        write_json(path, story)
        story_hashes[name] = digest(path)
    (run / 'RUNBOOK.md').write_text(RUNBOOK, encoding='utf-8')
    manifest = {'status': 'PREPARED_NOT_COMPILED_NOT_RUN', 'prepared_utc': now(), 'run_directory': str(run), 'source_runtime': str(source),
                'plugin_sha256': args.expected_sha, 'paper_sha256': digest(paper), 'starcore_sha256': digest(core),
                'fixture_source_sha256': digest(fixture_source), 'fixture_sha256': None, 'fixture_compiled': False,
                'server_port': args.port, 'server_ip': '127.0.0.1', 'players': players(),
                'story_sha256': story_hashes, 'source_preserved_sha256': preserved,
                'source_database_copied': False, 'source_world_copied': False, 'server_started': False,
                'scope': 'Fresh real StarCore states, three actual non-OP founders and one same-country member/successor; explicit national API plus real GUI and two normal restarts'}
    if not source_unchanged(manifest):
        raise SystemExit('Retained runtime source changed during read-only preparation')
    write_json(run / 'preparation.json', manifest)
    print(json.dumps({'run': str(run), 'plugin_sha256': args.expected_sha, 'port': args.port, 'players': players(), 'jvm_started': False, 'next': 'compile during coordinator JVM window, then follow RUNBOOK.md'}, ensure_ascii=False, indent=2))


def load(args, run):
    manifest = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
    if manifest['plugin_sha256'] != args.expected_sha or digest(run / 'plugins/StarChronicle.jar') != args.expected_sha:
        raise SystemExit('Frozen product SHA drift')
    if not source_unchanged(manifest):
        raise SystemExit('Retained source runtime changed; inspect before continuing')
    return manifest


def compile_fixture(args, run):
    manifest = load(args, run)
    if manifest['fixture_compiled'] or (run / 'plugins/NationalCouncilPaperFixture.jar').exists():
        raise SystemExit('Refusing replacement of a compiled acceptance fixture')
    if args.java_home is None or not (args.java_home / 'bin/javac.exe').is_file():
        raise SystemExit('Compile requires an explicit Java 21 --java-home and coordinator JVM window')
    source = run / 'fixture/NationalCouncilPaperFixture.java'
    current = ROOT / 'tools/NationalCouncilPaperFixture.java'
    if digest(source) != manifest['fixture_source_sha256'] or digest(current) != manifest['fixture_source_sha256']:
        raise SystemExit('Fixture source changed after preparation; retain old evidence and explicitly reprepare with the reviewed source')
    api = args.paper_api or Path.home() / '.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar'
    if not api.is_file():
        raise SystemExit('Retained Paper API compile JAR missing; provide --paper-api')
    attempts = manifest.setdefault('fixture_compile_attempts', [])
    number = len(attempts) + 1
    classes = run / f'fixture/classes-attempt{number}'
    classes.mkdir()
    jars = sorted((run / 'libraries').rglob('*.jar')) + [run / 'plugins/StarChronicle.jar', api]
    argfile = run / f'fixture/javac-attempt{number}.args'
    argfile.write_text('--release 21\n-proc:none\n-encoding UTF-8\n-cp\n"' + os.pathsep.join(str(path) for path in jars).replace('\\', '/') + '"\n-d\n"' + str(classes).replace('\\', '/') + '"\n"' + str(source).replace('\\', '/') + '"\n', encoding='utf-8')
    command = [str(args.java_home / 'bin/javac.exe'), '-J-Xmx128m', '@' + str(argfile)]
    result = subprocess.run(command, capture_output=True, timeout=120, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    log = run / f'evidence/fixture-compile-attempt{number}.log'
    log.write_bytes(result.stdout + result.stderr)
    attempts.append({'attempt': number, 'command': command, 'exit_code': result.returncode, 'log': str(log.relative_to(run)), 'source_sha256': digest(source)})
    if result.returncode:
        manifest['status'] = 'FIXTURE_COMPILE_FAILED'
        write_json(run / 'preparation.json', manifest)
        raise SystemExit('Fixture compilation failed; inspect ' + str(log))
    target = run / 'plugins/NationalCouncilPaperFixture.jar'
    with zipfile.ZipFile(target, 'x', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('plugin.yml', 'name: NationalCouncilPaperFixture\nversion: 1\nmain: smoke.NationalCouncilPaperFixture\napi-version: "1.21"\ndepend: [StarCore, StarChronicle]\ncommands:\n  ncverify:\n    description: Isolated real native national-council verification\n')
        for path in sorted(classes.rglob('*.class')):
            archive.write(path, path.relative_to(classes).as_posix())
    manifest.update(fixture_compiled=True, fixture_sha256=digest(target), status='COMPILED_NOT_RUN', java_home=str(args.java_home), compiled_utc=now())
    if not source_unchanged(manifest):
        raise SystemExit('Retained source files changed during fixture compilation')
    write_json(run / 'preparation.json', manifest)
    print(json.dumps({'fixture_sha256': manifest['fixture_sha256'], 'plugin_sha256': args.expected_sha, 'server_started': False}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', nargs='?', choices=['prepare', 'compile', 'check'], default='prepare')
    parser.add_argument('--expected-sha', '--expected-plugin-sha256', dest='expected_sha', required=True)
    parser.add_argument('--run-dir', type=Path)
    parser.add_argument('--source-runtime', type=Path, default=DEFAULT_SOURCE)
    parser.add_argument('--product', type=Path, default=ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--paper', type=Path)
    parser.add_argument('--starcore', type=Path)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--paper-api', type=Path)
    parser.add_argument('--port', type=int, default=25603)
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{64}', args.expected_sha):
        raise SystemExit('Require the exact complete coordinator-frozen SHA-256')
    run = run_path(args.expected_sha, args.run_dir)
    if args.action == 'prepare':
        prepare(args, run)
    elif args.action == 'compile':
        compile_fixture(args, run)
    else:
        manifest = load(args, run)
        if manifest.get('fixture_compiled') and digest(run / 'plugins/NationalCouncilPaperFixture.jar') != manifest['fixture_sha256']:
            raise SystemExit('Compiled fixture fingerprint drift')
        print(json.dumps({'source_unchanged': True, 'plugin_sha256': args.expected_sha, 'fixture_compiled': manifest['fixture_compiled'], 'server_started_by_this_tool': False}, indent=2))


if __name__ == '__main__':
    main()
