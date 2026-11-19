"""Publish a Folia smoke log with credential-related lines fully redacted."""
from pathlib import Path
import re, sys

root = Path(__file__).resolve().parents[1]
source = root / 'run' / (sys.argv[2] if len(sys.argv) > 2 else 'folia-smoke') / 'logs' / 'latest.log'
out = []
redacted = 0
for line in source.read_text(encoding='utf-8', errors='replace').splitlines():
    if re.search(r'(?i)secret|password|bearer|api[-_ ]?key|token\s*[:=]', line):
        prefix = line.split(']:', 1)[0] + ']:' if ']:' in line else ''
        out.append(prefix + ' [REDACTED: credential-related log line]')
        redacted += 1
    else:
        out.append(line)
target = root / 'docs' / 'verification' / sys.argv[1]
target.write_text('\n'.join(out) + '\n', encoding='utf-8')
print('Archived', target.name, 'redacted credential-related lines:', redacted)
