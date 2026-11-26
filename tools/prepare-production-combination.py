"""Add the selected exact optional plugins and live fixture to a fresh epic run."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run', type=Path, required=True)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--fancynpcs', type=Path, required=True)
parser.add_argument('--worldguard', type=Path, required=True)
parser.add_argument('--worldedit', type=Path, required=True)
args = parser.parse_args()
run = args.run.resolve()
if run.parent != ROOT / 'run' or not run.name.startswith('paper-epic-'):
    raise SystemExit('Isolated epic run required')
if (run / 'combination.json').exists() or list((run / 'evidence').glob('boot*.server.log')):
    raise SystemExit('Refusing to modify a previous combination or started run')
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
selection = json.loads((ROOT / 'docs/verification/production-combination-selection-20261126.json').read_text())
manifest = json.loads((run / 'preparation.json').read_text())
artifacts = {}
for name, source, selected in zip(('FancyNpcs', 'WorldGuard', 'WorldEdit'), (args.fancynpcs, args.worldguard, args.worldedit), selection['artifacts']):
    if source.is_symlink() or sha(source) != selected['sha256']:
        raise SystemExit('Selected third-party artifact mismatch: ' + name)
    target = run / 'plugins' / (name + '.jar')
    shutil.copy2(source, target)
    artifacts[name] = dict(sha256=sha(target), selected_file=selected['file'])
config = run / 'plugins/StarChronicle/config.yml'
config.write_text(config.read_text().replace('npc-hooks: false', 'npc-hooks: true'))
catalog = (ROOT / 'src/main/resources/npcs.yml').read_text()
(run / 'plugins/StarChronicle/npcs.yml').write_text(catalog.replace('bindings: []', 'bindings: [{provider: fancynpcs, externalId: chronicle-combination-witness}]', 1))
(run / 'plugins/bStats').mkdir(exist_ok=True)
(run / 'plugins/bStats/config.yml').write_text('enabled: false\n')
(run / 'plugins/FancyAnalytics').mkdir(exist_ok=True)
(run / 'plugins/FancyAnalytics/config.json').write_text(json.dumps(dict(sender_id='chronicle-isolated-acceptance',send_metrics=False,send_errors=False,disable_logging=False))+'\n')
fixture = 'ProductionCombinationFixture'
source = run / 'fixture' / (fixture + '.java')
shutil.copy2(ROOT / 'tools' / source.name, source)
classes = run / 'fixture/combination-classes'
classes.mkdir()
api = Path.home() / '.m2/repository/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar'
jars = [api] + sorted((run / 'plugins').glob('*.jar')) + sorted((run / 'libraries').rglob('*.jar'))
argfile = run / 'fixture/combination-javac.args'
argfile.write_text('--release 21\n-proc:none\n-encoding UTF-8\n-cp\n"' + os.pathsep.join(str(p) for p in jars).replace('\\', '/') + '"\n-d\n"' + str(classes).replace('\\', '/') + '"\n"' + str(source).replace('\\', '/') + '"\n')
with (run / 'evidence/combination-compile.log').open('x') as log:
    subprocess.run([str(args.java_home / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')), '-J-Xmx256m', '@' + str(argfile)], stdout=log, stderr=subprocess.STDOUT, check=True)
target = run / 'plugins' / (fixture + '.jar')
with zipfile.ZipFile(target, 'x', zipfile.ZIP_DEFLATED) as archive:
    for path in classes.rglob('*.class'):
        archive.write(path, path.relative_to(classes).as_posix())
    archive.writestr('plugin.yml', f'name: {fixture}\nversion: 1\nmain: smoke.{fixture}\napi-version: "1.21"\ndepend: [StarCore, StarChronicle, FancyNpcs, WorldGuard, WorldEdit]\ncommands:\n  comboverify:\n    description: Isolated optional plugin acceptance\n')
artifacts[fixture] = dict(sha256=sha(target), source_sha256=sha(source))
(run / 'combination.json').write_text(json.dumps(dict(artifacts=artifacts, scope='Selected Paper combination; no Folia or production deployment'), indent=2) + '\n')
manifest['combination_acceptance'] = True
manifest['combination_maintenance_offline'] = True
(run / 'preparation.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(dict(run=str(run), artifacts=artifacts)))
