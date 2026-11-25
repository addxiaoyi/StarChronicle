"""Prepare exact capital-history acceptance on the selected isolated Paper/plugin stack."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import yaml

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--attempt', required=True)
p.add_argument('--port', type=int, required=True)
p.add_argument('--starcore', type=Path, required=True)
p.add_argument('--runtime', type=Path, required=True)
p.add_argument('--java-home', type=Path, required=True)
p.add_argument('--artifacts-dir', type=Path)
p.add_argument('--offline', action='store_true')
a = p.parse_args()
command = ['python3', str(ROOT/'tools/prepare-production-paper.py'), '--prepare-only', '--attempt', a.attempt,
           '--port', str(a.port), '--starcore', str(a.starcore), '--runtime', str(a.runtime), '--java-home', str(a.java_home)]
if a.artifacts_dir: command += ['--artifacts-dir', str(a.artifacts_dir)]
subprocess.run(command, check=True, cwd=ROOT)
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
run = ROOT/'run'/f'paper-epic-{digest(ROOT/"target/starchronicle-0.1.0-SNAPSHOT.jar")[:8]}-dungeon-{a.attempt}'
manifest = json.loads((run/'preparation.json').read_text())
assert manifest['fixture_compiled'] is False
extra = ROOT/'tools/CapitalHistoryPaperFixture.java'
shutil.copy2(extra, run/'fixture'/extra.name)
manifest['fixture_extra_sources_sha256'][extra.name] = digest(extra)
manifest['fixture_main_class'] = 'CapitalHistoryPaperFixture'
path = run/'plugins/StarChronicle/config.yml'
config = yaml.safe_load(path.read_text())
config.update({'install-native-example-quests': False, 'install-epic-strategy-example': False, 'poll-ticks':72000 if a.offline else 20})
path.write_text(yaml.safe_dump(config, allow_unicode=True, sort_keys=False))
manifest['capital_acceptance'] = {'mode':'offline' if a.offline else 'main', 'burst_count':205}
manifest['limitations'] = ['Selected isolated Paper stack; no Folia or production deployment claim.',
    'Cities, founder handover, designation and deletion use actual native service methods with real connected player IDs.',
    'No capital state or history is inserted through model setters or SQL; the fixture seeds nations and funds explicitly.',
    'File integrity checks scan the committed prefix; this is not a long-run throughput acceptance.']
(run/'preparation.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
subprocess.run(['python3',str(ROOT/'tools/prepare-epic-paper.py'),'compile','--run',str(run),'--java-home',str(a.java_home)],check=True,cwd=ROOT)
print(json.dumps(dict(run=str(run), mode=manifest['capital_acceptance']['mode'], status='PREPARED_NOT_ACCEPTED')))
