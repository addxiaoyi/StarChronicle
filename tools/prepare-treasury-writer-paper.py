"""Prepare a new frozen selected-stack run for real SQLite treasury writer contention."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--attempt', required=True)
p.add_argument('--port', type=int, required=True)
for name in ('starcore', 'runtime', 'java-home', 'artifacts-dir'):
    p.add_argument('--' + name, type=Path, required=True)
a = p.parse_args()
subprocess.run(['python3', str(ROOT / 'tools/prepare-production-paper.py'), '--prepare-only',
    '--attempt', a.attempt, '--port', str(a.port), '--starcore', str(a.starcore),
    '--runtime', str(a.runtime), '--java-home', str(a.java_home), '--artifacts-dir', str(a.artifacts_dir)],
    check=True, cwd=ROOT)
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
run = ROOT / 'run' / f'paper-epic-{sha(ROOT / "target/starchronicle-0.1.0-SNAPSHOT.jar")[:8]}-dungeon-{a.attempt}'
m = json.loads((run / 'preparation.json').read_text())
source = ROOT / 'tools/TreasuryWriterPaperFixture.java'
shutil.copy2(source, run / 'fixture' / source.name)
m['fixture_extra_sources_sha256'][source.name] = sha(source)
m['fixture_main_class'] = 'TreasuryWriterPaperFixture'
m['treasury_writer_acceptance'] = dict(scenarios=['treasury-first', 'treasury-second', 'treasury-third'],
    hold_millis=750, table='isolated_treasury_writer_fixture', transfer_id='isolated-treasury-writer-transfer')
m['limitations'] = [
    'Selected isolated Paper stack; SQLite WAL; explicit public-service seed funds and treasury deposits.',
    'An independent connection writes only an isolated fixture table to hold the real shared database writer lock.',
    'Public treasury deposits and receipt-bearing transfer execute on the Paper owner thread without replacing storage or services.',
    'Three normal boots prove this scenario only; no crash, Folia, production deployment, external wallet or throughput claim.'
]
(run / 'preparation.json').write_text(json.dumps(m, ensure_ascii=False, indent=2) + '\n')
subprocess.run(['python3', str(ROOT / 'tools/prepare-epic-paper.py'), 'compile', '--run', str(run),
                '--java-home', str(a.java_home)], check=True, cwd=ROOT)
print(json.dumps(dict(run=str(run), status='PREPARED_NOT_ACCEPTED')))
