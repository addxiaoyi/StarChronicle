"""Prepare new-feature Folia acceptance, never changing a prior verification directory."""
from pathlib import Path
from datetime import datetime,timezone,timedelta
import hashlib,json,re,shutil,sys,zipfile

root=Path(__file__).resolve().parents[1]
name=sys.argv[1] if len(sys.argv)>1 else 'folia-final-smoke-abaadf4c'
if not re.fullmatch(r'[a-z0-9][a-z0-9_.-]{0,79}',name):raise SystemExit('Invalid isolated run directory name')
run=root/'run'/name
source=root/'run'/'folia-smoke'
if run.exists():raise SystemExit('Refusing to overwrite existing Folia feature run')
jar=source/'folia.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest()=='f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39'
product=root/'target'/'starchronicle-0.1.0-SNAPSHOT.jar'
expected=sys.argv[2] if len(sys.argv)>2 else 'abaadf4c4dd318d518687bafe2596f43e32c9f9a115b4074deeae9f37abc22df'
product_hash=hashlib.sha256(product.read_bytes()).hexdigest()
if product_hash!=expected:raise SystemExit('Product does not match the explicitly approved final JAR: '+product_hash)
(run/'plugins'/'StarChronicle'/'stories').mkdir(parents=True)
for name in ['folia.jar','eula.txt']:shutil.copy2(source/name,run/name)
for directory in ['cache','libraries']:
    if (source/directory).exists():shutil.copytree(source/directory,run/directory)
shutil.copy2(product,run/'plugins'/'StarChronicle.jar')
(run/'server.properties').write_text((source/'server.properties').read_text(encoding='utf-8'),encoding='utf-8')
(run/'plugins'/'StarChronicle'/'config.yml').write_text('require-starcore: false\nadmin-demo-enabled: false\nnpc-hooks: false\nworker-queue-capacity: 2048\npoll-ticks: 100\n',encoding='utf-8')
(run/'config').mkdir(exist_ok=True)
(run/'config'/'paper-global.yml').write_text('_version: 31\nchunk-system:\n  io-threads: 1\n  worker-threads: 1\nspark:\n  enabled: false\nthreaded-regions:\n  threads: 2\n',encoding='utf-8')
with zipfile.ZipFile(product) as archive:
    catalog=archive.read('npcs.yml').decode('utf-8')
(run/'plugins'/'StarChronicle'/'npcs.yml').write_text(catalog+'''
  folia-witness:
    name: "Folia Test Witness"
    background: "Isolated verification character reacting only to the actual shared test completion."
    faction: verification
    initialPosition: witness
    growth:
      - {id: completed-test, threshold: 20, position: history-witness}
    bindings: []
    dialogues:
      - id: trusted
        priority: 20
        condition: {fact: npc.trust, op: gte, value: "9"}
        text: "I remember the completed shared test, {player.name}."
      - id: initial
        priority: 0
        text: "The real shared verification has not completed yet."
    reactions:
      - {id: shared-test-observed, events: [FOLIA_FEATURE_COMPLETED], reputation: 7, trust: 9, growth: 21}
''',encoding='utf-8')
(run/'plugins'/'StarChronicle'/'stories'/'folia-feature.yml').write_text('''id: folia-feature
version: "1"
title: "Folia Feature Verification"
scope: WORLD
triggers: [FOLIA_FEATURE_EVENT]
start: vote
nodes:
  vote:
    text: "Two actual clients vote before and after a normal server restart."
    quorum: 2
    choices:
      remember: {label: "Remember the test", next: end}
  end:
    text: "The shared verification is complete."
    terminal: true
    effects:
      - {id: record, type: history, target: FOLIA_FEATURE_COMPLETED, value: "Two connected protocol clients completed the restarted shared test."}
      - {id: title, type: title, target: folia-feature-witness, value: "Folia Feature Witness"}
      - {id: honor, type: honor, target: player, value: "3"}
''',encoding='utf-8')
(run/'plugins'/'StarChronicle'/'stories'/'folia-offline.yml').write_text('''id: folia-offline
version: "1"
title: "Offline Audience Recovery Verification"
scope: WORLD
triggers: [FOLIA_OFFLINE_EVENT]
start: vote
nodes:
  vote:
    text: "This evidenced test event committed with no online audience; actual cohort members can now participate."
    quorum: 2
    choices:
      remember: {label: "Remember the offline event", next: end}
  end:
    text: "The deferred trigger survived restart and completed once."
    terminal: true
    effects:
      - {id: history, type: history, target: FOLIA_OFFLINE_COMPLETED, value: "A real previously enrolled test cohort completed the deferred offline test after restart."}
      - {id: title, type: title, target: folia-offline-witness, value: "Offline Event Witness"}
''',encoding='utf-8')
now=datetime.now(timezone.utc)
manifest={
    'status':'PREPARED_NOT_RUN',
    'prepared_utc':now.isoformat(),
    'prepared_asia_shanghai':now.astimezone(timezone(timedelta(hours=8))).isoformat(),
    'run_directory':run.relative_to(root).as_posix(),
    'plugin_sha256':hashlib.sha256((run/'plugins'/'StarChronicle.jar').read_bytes()).hexdigest(),
    'folia_sha256':hashlib.sha256((run/'folia.jar').read_bytes()).hexdigest(),
    'scope':'explicit standalone; require-starcore=false; no StarCore installed',
    'planned_heap_max_mb':768,
    'planned_region_threads':2,
    'fixture_compiled':False,
    'server_started':False,
}
(run/'acceptance-preparation.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
current_manifest=root/'docs'/'verification'/'expanded-folia-smoke-preparation.json'
if current_manifest.exists():
    previous=json.loads(current_manifest.read_text(encoding='utf-8'))
    if previous['plugin_sha256']!=manifest['plugin_sha256']:
        previous_path=current_manifest.with_name('expanded-folia-smoke-preparation-'+previous['plugin_sha256'][:8]+'.json')
        if previous_path.exists() and json.loads(previous_path.read_text(encoding='utf-8'))!=previous:raise SystemExit('Refusing to overwrite a different archived preparation manifest')
        previous_path.write_text(json.dumps(previous,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
current_manifest.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Prepared new-feature standalone Folia run:',run)
print('Product SHA-256:',product_hash)
