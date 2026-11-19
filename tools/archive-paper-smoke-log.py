"""Publish a smoke log after redacting complete lines mentioning credentials.
The raw server log stays inside ignored run/smoke. Prints only redaction counts.
"""
from pathlib import Path
import re,sys
root=Path(__file__).resolve().parents[1];source=root/'run'/'smoke'/'logs'/'latest.log'
text=source.read_text(encoding='utf-8',errors='replace');out=[];count=0
for line in text.splitlines():
    if re.search(r'(?i)secret|password|bearer|api[-_ ]?key|token\s*[:=]',line):
        prefix=line.split(']:',1)[0]+']:' if ']:' in line else ''
        out.append(prefix+' [REDACTED: credential-related log line]');count+=1
    else: out.append(line)
target=root/'docs'/'verification'/sys.argv[1];target.write_text('\n'.join(out)+'\n',encoding='utf-8')
print('Archived sanitized log:',target.name,'redacted lines:',count)
for prior in target.parent.glob('paper-smoke*.log'):
    if prior==target: continue
    lines=prior.read_text(encoding='utf-8',errors='replace').splitlines();redacted=0
    for i,line in enumerate(lines):
        if re.search(r'(?i)secret|password|bearer|api[-_ ]?key|token\s*[:=]',line):
            prefix=line.split(']:',1)[0]+']:' if ']:' in line else ''
            lines[i]=prefix+' [REDACTED: credential-related log line]';redacted+=1
    if redacted:
        prior.write_text('\n'.join(lines)+'\n',encoding='utf-8');print('Sanitized prior evidence:',prior.name,'redacted lines:',redacted)
