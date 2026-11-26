"""Prepare an isolated real-wallet interruption and read-only audit scenario."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import yaml

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
for name in ('attempt', 'port', 'starcore', 'runtime', 'java-home', 'artifacts-dir'):
    p.add_argument('--' + name, required=True, type=int if name == 'port' else str if name == 'attempt' else Path)
p.add_argument('--leg', type=int, choices=(1, 2, 3), required=True)
p.add_argument('--window', choices=('before', 'after'), required=True)
a = p.parse_args()
if a.window == 'before' and a.leg != 1:
    p.error('This fixture covers before-debit and after each of the three wallet calls')
subprocess.run(['python3', str(ROOT/'tools/prepare-production-paper.py'), '--prepare-only',
    '--attempt', a.attempt, '--port', str(a.port), '--starcore', str(a.starcore), '--runtime', str(a.runtime),
    '--java-home', str(a.java_home), '--artifacts-dir', str(a.artifacts_dir)], check=True, cwd=ROOT)
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
run = ROOT/'run'/f'paper-epic-{sha(ROOT/"target/starchronicle-0.1.0-SNAPSHOT.jar")[:8]}-dungeon-{a.attempt}'
manifest = json.loads((run/'preparation.json').read_text())
extra = ROOT/'tools/PlayerTradeAuditPaperFixture.java'
shutil.copy2(extra, run/'fixture'/extra.name)
manifest['fixture_extra_sources_sha256'][extra.name] = sha(extra)
manifest['fixture_main_class'] = 'PlayerTradeAuditPaperFixture'
config_path = run/'plugins/StarChronicle/config.yml'
config = yaml.safe_load(config_path.read_text())
config.update({'install-native-example-quests': False, 'install-epic-strategy-example': False, 'poll-ticks': 20})
config_path.write_text(yaml.safe_dump(config, allow_unicode=True, sort_keys=False))
config_path = run/'plugins/EpicCampaignPaperFixture/config.yml'
config = yaml.safe_load(config_path.read_text())
config['audit'] = {'leg': a.leg, 'window': a.window}
config_path.write_text(yaml.safe_dump(config, allow_unicode=True, sort_keys=False))
manifest['player_trade_audit_acceptance'] = {'leg': a.leg, 'window': a.window, 'scenarios': ['audit-seed', 'audit-interrupt', 'audit-recover', 'audit-recover']}
manifest['limitations'] = [
    'Selected Paper stack, real non-OP commands, explicit test nations and funds.',
    'Test fixture pauses around the real selected wallet call; controller SIGKILLs only its owned JVM.',
    'Refund scenario uses the real Essentials maximum balance rejection for seller credit.',
    'Interrupted stage has a durable local intent and no result; recovery must not infer from current balance.',
    'Controller waits for the observed Essentials file balance before kill; this is not a physical power-loss or general provider durability proof.',
    'Vault has no queryable idempotent payment receipt; no UNKNOWN resolution or forced refund is asserted.']
(run/'preparation.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
subprocess.run(['python3', str(ROOT/'tools/prepare-epic-paper.py'), 'compile', '--run', str(run), '--java-home', str(a.java_home)], check=True, cwd=ROOT)
print(json.dumps({'run': str(run), 'status': 'PREPARED_NOT_ACCEPTED'}))
