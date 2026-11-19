"""Prepare an isolated phase-two Folia run; never compiles or starts a JVM."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import argparse, hashlib, json, re, shutil, zipfile

ROOT = Path(__file__).resolve().parents[1]
SERVER_SHA = 'f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39'
DEFAULT_STORIES = {'last-heir.yml', 'heir-legacy.yml', 'siege-embers.yml', 'silk-road-scholar.yml'} | {f'fate-{role}.yml' for role in ['soldier', 'general', 'merchant', 'scholar', 'explorer', 'diplomat', 'mayor', 'monarch']}

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--run-name')
    parser.add_argument('--source-run', type=Path, default=ROOT / 'run' / 'folia-smoke')
    parser.add_argument('--product', type=Path, default=ROOT / 'target' / 'starchronicle-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--port', type=int, default=25578)
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{64}', args.expected_sha):
        parser.error('--expected-sha must be the explicitly approved full SHA-256')
    name = args.run_name or 'phase-two-folia-' + args.expected_sha[:8]
    if not re.fullmatch(r'phase-two-folia-[a-z0-9][a-z0-9_.-]{0,79}', name):
        parser.error('run name must remain a new phase-two-folia-* directory')
    if not 1024 <= args.port <= 65535:
        parser.error('invalid loopback port')
    run = ROOT / 'run' / name
    source = args.source_run.resolve()
    if run.exists():
        raise SystemExit('Refusing to overwrite any existing acceptance directory')
    if digest(args.product) != args.expected_sha:
        raise SystemExit('Product JAR does not match the explicitly approved hash')
    if digest(source / 'folia.jar') != SERVER_SHA:
        raise SystemExit('Official retained Folia artifact checksum mismatch')
    with zipfile.ZipFile(args.product) as archive:
        stories = {Path(item).name: archive.read(item) for item in archive.namelist() if item.startswith('stories/') and item.endswith(('.yml', '.yaml', '.json'))}
        if set(stories) != DEFAULT_STORIES:
            raise SystemExit('Expected exactly the twelve unmodified bundled story files including heir-legacy')
        npcs = archive.read('npcs.yml')
    (run / 'plugins' / 'StarChronicle' / 'stories').mkdir(parents=True)
    (run / 'evidence').mkdir()
    (run / 'fixture').mkdir()
    shutil.copy2(source / 'folia.jar', run / 'folia.jar')
    shutil.copy2(args.product, run / 'plugins' / 'StarChronicle.jar')
    for directory in ['cache', 'libraries']:
        if (source / directory).is_dir():
            shutil.copytree(source / directory, run / directory)
    for name, content in stories.items():
        (run / 'plugins' / 'StarChronicle' / 'stories' / name).write_bytes(content)
    (run / 'plugins' / 'StarChronicle' / 'npcs.yml').write_bytes(npcs)
    shutil.copy2(ROOT / 'tools' / 'PhaseTwoFoliaFixture.java', run / 'fixture' / 'PhaseTwoFoliaFixture.java')
    (run / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    (run / 'server.properties').write_text(f'''server-ip=127.0.0.1
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
gamemode=creative
difficulty=peaceful
motd=Isolated StarChronicle phase-two Folia acceptance
''', encoding='utf-8')
    (run / 'plugins' / 'StarChronicle' / 'config.yml').write_text('require-starcore: false\ninstall-default-stories: true\nadmin-demo-enabled: false\nnpc-hooks: false\nworker-queue-capacity: 2048\npoll-ticks: 12000\n', encoding='utf-8')
    (run / 'config').mkdir()
    (run / 'config' / 'paper-global.yml').write_text('_version: 31\nchunk-system:\n  io-threads: 1\n  worker-threads: 1\nspark:\n  enabled: false\nthreaded-regions:\n  threads: 2\n', encoding='utf-8')
    now = datetime.now(timezone.utc)
    manifest = {
        'status': 'PREPARED_NOT_COMPILED_NOT_RUN',
        'prepared_utc': now.isoformat(),
        'prepared_asia_shanghai': now.astimezone(timezone(timedelta(hours=8))).isoformat(),
        'run_directory': run.relative_to(ROOT).as_posix(),
        'plugin_sha256': digest(run / 'plugins' / 'StarChronicle.jar'),
        'server_sha256': digest(run / 'folia.jar'),
        'fixture_source_sha256': digest(run / 'fixture' / 'PhaseTwoFoliaFixture.java'),
        'bundled_story_sha256': {name: hashlib.sha256(value).hexdigest() for name, value in sorted(stories.items())},
        'story_count': len(stories),
        'install_default_stories': True,
        'npcs_sha256': hashlib.sha256(npcs).hexdigest(),
        'scope': 'StarChronicle standalone on real Folia; NOT StarCore full-stack Folia certification',
        'server_port': args.port,
        'poll_ticks': 12000,
        'planned_heap_max_mb': 768,
        'planned_region_threads': 2,
        'fixture_compiled': False,
        'server_started': False,
    }
    (run / 'acceptance-preparation.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(manifest, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
