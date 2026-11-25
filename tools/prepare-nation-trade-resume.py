"""Resume a stopped offline acceptance backlog with the normal polling interval."""
from pathlib import Path
import argparse
import hashlib
import json
import socket

p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);a=p.parse_args();run=a.run.resolve()
manifest=json.loads((run/'preparation.json').read_text())
assert manifest['nation_trade_acceptance']['mode']=='offline'
with socket.socket() as probe:
    probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',manifest['server_port']))
session=json.loads((run/'evidence/boot1.session.json').read_text());assert session['status']=='COMPLETE'
assert all(row['exit_code']==0 for row in session['processes'])
assert not (run/'evidence/boot2.server.log').exists()
path=run/'plugins/StarChronicle/config.yml';before=path.read_bytes()
assert before.count(b'poll-ticks: 72000\n')==1
after=before.replace(b'poll-ticks: 72000\n',b'poll-ticks: 20\n')
evidence=run/'evidence/sale-poll-resume.json'
with evidence.open('x') as output:
    json.dump({'before_sha256':hashlib.sha256(before).hexdigest(),'after_sha256':hashlib.sha256(after).hexdigest(),
               'before':before.decode(),'after':after.decode(),'reason':'Stopped offline backlog: enable regular polling before the second boot, before any player connects.'},output,indent=2)
path.write_bytes(after)
print(json.dumps({'status':'OFFLINE_RESUME_PREPARED','run':str(run),'poll_ticks':20}))
