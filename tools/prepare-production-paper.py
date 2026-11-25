"""Prepare a new isolated Paper run using a frozen, downloaded production candidate stack."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--attempt', required=True)
parser.add_argument('--route', choices=['DUNGEON','WAR'], default='DUNGEON')
parser.add_argument('--campaign', action='store_true', help='Run the full epic campaign with this third-party stack')
parser.add_argument('--port', type=int, required=True)
parser.add_argument('--starcore', type=Path, required=True)
parser.add_argument('--runtime', type=Path, required=True)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--artifacts-dir', type=Path, help='Downloaded JAR directory on this device; files are still verified against the lock')
parser.add_argument('--lock', type=Path, default=ROOT / 'docs/verification/production-stack-candidate-20261125.json')
parser.add_argument('--prepare-only', action='store_true', help='Leave the new, unstarted fixture uncompiled for an additional acceptance scenario')
args = parser.parse_args()
subprocess.run(['python3', str(ROOT / 'tools/prepare-epic-paper.py'), 'prepare', '--route', args.route, '--attempt', args.attempt, '--port', str(args.port), '--starcore', str(args.starcore), '--runtime', str(args.runtime)], cwd=ROOT, check=True)
sha = hashlib.sha256((ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar').read_bytes()).hexdigest()
run = ROOT / 'run' / f'paper-epic-{sha[:8]}-{args.route.lower()}-{args.attempt}'
manifest = json.loads((run / 'preparation.json').read_text())
lock = json.loads(args.lock.read_text())
for artifact in lock['artifacts']:
    original = args.artifacts_dir / artifact['filename'] if args.artifacts_dir else Path(artifact['local_path'])
    assert original.is_file() and not original.is_symlink()
    assert hashlib.sha256(original.read_bytes()).hexdigest() == artifact['sha256']
    assert artifact['main_class_major'] <= 65
    shutil.copy2(original, run / 'plugins' / artifact['filename'])
extra = ROOT / 'tools/ProductionStackPaperFixture.java'
shutil.copy2(extra, run / 'fixture' / extra.name)
manifest['fixture_extra_sources_sha256'] = {extra.name: hashlib.sha256(extra.read_bytes()).hexdigest()}
manifest['fixture_main_class'] = 'ProductionStackPaperFixture'
manifest['limitations'] = ['Isolated Paper integration acceptance with explicit fixture funds, nations and two FancyNpcs villagers.', 'This stack scenario does not execute a dungeon or war campaign.', 'Nation creation cost is disabled in this fixture; it does not validate legacy paid nation creation.', 'External economy and native claim storage are not one atomic transaction.']
if args.campaign:
    manifest['production_campaign'] = dict(route=args.route, war_ending='native principal peace', dungeon='three-second native survival room')
    manifest['limitations'] = ['Selected isolated Paper stack; no production deployment or Folia claim.',
        'The fixture seeds nations, treasury/resources and real crafting ingredients; nation creation is free only in the fixture.',
        'War ending uses native principal peace, not battlefield victory; dungeon uses a three-second native survival room.',
        'This scenario revalidates the complete epic chain and contribution projection on the frozen selected stack.']
manifest['production_stack'] = lock
manifest['production_stack_lock_sha256'] = hashlib.sha256(args.lock.read_bytes()).hexdigest()
config = run / 'plugins/StarCore/config.yml'
text = config.read_text(); text, count = re.subn(r'(?m)^  provider: internal$', '  provider: vault', text); assert count == 1
if re.search(r'(?m)^  worldguard:$', text):
    text, count = re.subn(r'(?ms)(^  worldguard:\n(?:(?!^  [a-z]).)*?    enabled:) false$', r'\1 true', text)
    assert count == 1
else:
    text = text.replace('integrations:\n', 'integrations:\n  worldguard:\n    enabled: true\n', 1)
config.write_text(text)
config = run / 'plugins/StarChronicle/config.yml'; config.write_text(config.read_text().replace('npc-hooks: false', 'npc-hooks: true'))
with zipfile.ZipFile(run / 'plugins/StarChronicle.jar') as archive:
    npc_config = archive.read('npcs.yml').decode('utf-8')
npc_config = npc_config.replace('bindings: []', 'bindings: [{provider: fancynpcs, externalId: "00000000-0000-4000-8000-000000000701"}]', 1)
(run / 'plugins/StarChronicle/npcs.yml').write_text(npc_config)
(run / 'preparation.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
if not args.prepare_only:
    subprocess.run(['python3', str(ROOT / 'tools/prepare-epic-paper.py'), 'compile', '--run', str(run), '--java-home', str(args.java_home)], cwd=ROOT, check=True)
print(json.dumps(dict(run=str(run), stage='PREPARED_NOT_ACCEPTED', plugins=[row['filename'] for row in lock['artifacts']]), ensure_ascii=False))
