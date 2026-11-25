"""Prepare real exploration -> memory -> library acceptance on the frozen Paper stack."""
from pathlib import Path
import argparse
import copy
import hashlib
import json
import shutil
import subprocess
import uuid
import yaml

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--attempt', required=True)
parser.add_argument('--port', type=int, required=True)
parser.add_argument('--starcore', type=Path, required=True)
parser.add_argument('--runtime', type=Path, required=True)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--artifacts-dir', type=Path)
parser.add_argument('--offline', action='store_true', help='Leave a real first visit in durable ingress until the next empty-server boot')
args = parser.parse_args()
command = ['python3', str(ROOT/'tools/prepare-production-paper.py'), '--prepare-only', '--attempt', args.attempt,
           '--port', str(args.port), '--starcore', str(args.starcore), '--runtime', str(args.runtime), '--java-home', str(args.java_home)]
if args.artifacts_dir: command += ['--artifacts-dir', str(args.artifacts_dir)]
subprocess.run(command, check=True, cwd=ROOT)
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
product_sha = digest(ROOT/'target/starchronicle-0.1.0-SNAPSHOT.jar')
run = ROOT/'run'/f'paper-epic-{product_sha[:8]}-dungeon-{args.attempt}'
manifest = json.loads((run/'preparation.json').read_text())
assert manifest['fixture_compiled'] is False
extra = ROOT/'tools/MemoryRecipePaperFixture.java'
shutil.copy2(extra, run/'fixture'/extra.name)
manifest['fixture_extra_sources_sha256'][extra.name] = digest(extra)
manifest['fixture_main_class'] = 'MemoryRecipePaperFixture'
world_id = uuid.uuid4()
(run/'world').mkdir(); (run/'world/uid.dat').write_bytes(world_id.bytes)
config_path = run/'plugins/StarChronicle/config.yml'
config = yaml.safe_load(config_path.read_text())
config.update({'install-native-example-quests': False, 'install-epic-strategy-example': False,
               'poll-ticks': 72000 if args.offline else 20,
               'exploration': {'enabled': True, 'regions': [dict(id='old-caravan-road', world=str(world_id),
                    **{'min-x':1004,'max-x':1006,'min-y':-60,'max-y':-58,'min-z':0,'max-z':3})]}})
config_path.write_text(yaml.safe_dump(config, allow_unicode=True, sort_keys=False))
story_source = ROOT/'examples/memory-recipes/caravan-library.yml'
stories = run/'plugins/StarChronicle/stories'; stories.mkdir()
shutil.copy2(story_source, stories/story_source.name)
base = yaml.safe_load((ROOT/'examples/memory-recipes/memory-recipes.yml').read_text())
base['recipes'][0]['enabled'] = True
variants = {'enabled': base}
variants['validate-v2'] = copy.deepcopy(base); variants['validate-v2']['recipes'][0]['version'] = '2'
variants['invalid-v1'] = copy.deepcopy(base); variants['invalid-v1']['recipes'][0]['title'] += '（未升级版本的非法修改）'
variants['disabled'] = copy.deepcopy(base); variants['disabled']['recipes'][0]['enabled'] = False
variant_hashes = {}
for name, value in variants.items():
    path = run/'fixture'/f'memory-{name}.yml'
    path.write_text(yaml.safe_dump(value, allow_unicode=True, sort_keys=False)); variant_hashes[name] = digest(path)
shutil.copy2(run/'fixture/memory-enabled.yml', run/'plugins/StarChronicle/memory-recipes.yml')
fixture_config = run/'plugins/EpicCampaignPaperFixture/config.yml'
fixture_config.write_text(fixture_config.read_text()+f'memory-world: "{world_id}"\nmemory-offline: {str(args.offline).lower()}\n')
manifest['memory_acceptance'] = dict(mode='offline' if args.offline else 'main', world=str(world_id),
    recipe_variants_sha256=variant_hashes, story_sha256=digest(story_source), story=yaml.safe_load(story_source.read_text()),
    example_catalog_sha256=digest(ROOT/'examples/memory-recipes/memory-recipes.yml'))
manifest['limitations'] = ['Selected isolated Paper stack; no Folia or production deployment claim.',
    'Only real configured first visits generate sources; replay reuses the actual stored canonical source.',
    'The fixture creates an isolated walking station and explicit native nations; recipes create no blocks or external NPCs.',
    'The delivered library story is used unchanged; only its first-visit recipe is explicitly enabled.']
(run/'preparation.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
subprocess.run(['python3',str(ROOT/'tools/prepare-epic-paper.py'),'compile','--run',str(run),'--java-home',str(args.java_home)],check=True,cwd=ROOT)
print(json.dumps(dict(run=str(run), mode=manifest['memory_acceptance']['mode'], status='PREPARED_NOT_ACCEPTED')))
