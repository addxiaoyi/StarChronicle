"""Prepare a loader-only Folia probe using unchanged product and upstream artifacts.

No descriptor is rewritten. This is expected to demonstrate the external full-stack blocker.
"""
from pathlib import Path
import hashlib, json, shutil

root=Path(__file__).resolve().parents[1]
source=root/'run'/'folia-smoke'
run=root/'run'/'folia-upstream-probe'
if run.exists():raise SystemExit('Refusing to overwrite existing upstream probe')
(run/'plugins'/'StarChronicle').mkdir(parents=True)
for directory in ['cache','libraries']:
    if (source/directory).exists():shutil.copytree(source/directory,run/directory)
for name in ['folia.jar','eula.txt']:
    shutil.copy2(source/name,run/name)
for src,name in [(source/'plugins'/'StarChronicle.jar','StarChronicle.jar'),(root/'run'/'smoke'/'plugins'/'StarCore.jar','StarCore.jar')]:
    shutil.copy2(src,run/'plugins'/name)
properties=(source/'server.properties').read_text(encoding='utf-8').replace('server-port=25578','server-port=25579')
(run/'server.properties').write_text(properties,encoding='utf-8')
(run/'plugins'/'StarChronicle'/'config.yml').write_text('require-starcore: true\nadmin-demo-enabled: false\nnpc-hooks: false\n',encoding='utf-8')
evidence={name:hashlib.sha256((run/'plugins'/name).read_bytes()).hexdigest() for name in ['StarCore.jar','StarChronicle.jar']}
(root/'docs'/'verification'/'folia-upstream-probe-artifacts.json').write_text(json.dumps(evidence,indent=2)+'\n',encoding='utf-8')
print('Prepared unchanged-artifact upstream loader probe:',run)
