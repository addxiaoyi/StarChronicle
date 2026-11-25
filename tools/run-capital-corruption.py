"""Boot a stopped isolated copy after removing one complete committed capital record."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import socket
import subprocess
import threading
import time

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);p.add_argument('--attempt',required=True);p.add_argument('--port',type=int,required=True);p.add_argument('--java-home',type=Path,required=True)
a=p.parse_args();source=a.source.resolve();manifest=json.loads((source/'preparation.json').read_text());sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
assert source.parent==ROOT/'run' and manifest['capital_acceptance']['mode']=='main'
assert json.loads((source/'evidence/boot3.session.json').read_text())['status']=='COMPLETE'
assert all(sha(source/'plugins'/f'{name}.jar')==manifest[key] for name,key in [('StarChronicle','plugin_sha256'),('StarCore','starcore_sha256')])
for port in (manifest['server_port'],a.port):
    with socket.socket() as probe:probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',port))
assert all(c.isalnum() or c=='-' for c in a.attempt)
run=ROOT/'run'/f'paper-epic-{manifest["plugin_sha256"][:8]}-dungeon-{a.attempt}'
shutil.copytree(source,run,ignore=shutil.ignore_patterns('evidence','logs','crash-reports'))
(run/'evidence').mkdir()
properties=run/'server.properties';properties.write_text(properties.read_text().replace('server-port='+str(manifest['server_port']),'server-port='+str(a.port)))
manifest['server_port']=a.port;manifest['corruption_source']=source.name
(run/'preparation.json').write_text(json.dumps(manifest,indent=2)+'\n')
journal=next((run/'plugins/StarCore').rglob('capital-designations.log'));original=journal.read_bytes();before=sha(journal)
assert original.endswith(b'\n');shortened=original[:original[:-1].rfind(b'\n')+1]
(run/'evidence/capital-before.log').write_bytes(original);journal.write_bytes(shortened)
head=journal.with_name(journal.name+'.head');authority=journal.with_name(journal.name+'.authority')
protected={str(path.relative_to(run)):sha(path) for path in (journal,head,authority)}
record=dict(status='INCOMPLETE',source=source.name,run=run.name,before_sha256=before,truncated_sha256=sha(journal),removed_complete_row=original[len(shortened):].decode(),
    preserved_authority_sha256=protected,port=a.port,chronicle_sha256=manifest['plugin_sha256'],starcore_sha256=manifest['starcore_sha256'])
command=[str(a.java_home/'bin/java'),'-Xms64m','-Xmx384m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=64m','-XX:CompressedClassSpaceSize=64m','-Dfile.encoding=UTF-8','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','paper.jar','nogui']
process=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1)
record.update(pid=process.pid,command=command,started_at=time.time());observed=[];ready=threading.Event()
def reader():
    with (run/'evidence/corruption.server.log').open('x') as output:
        for line in process.stdout:
            output.write(line);output.flush();observed.append(line)
            if 'Done (' in line:ready.set()
        ready.set()
thread=threading.Thread(target=reader,daemon=True);thread.start()
try:
    deadline=time.monotonic()+180
    while not ready.wait(1):
        if time.monotonic()>deadline:raise TimeoutError('Corrupted-copy startup timed out')
    if process.poll() is None:
        process.stdin.write('stop\n');process.stdin.flush()
    process.wait(timeout=60);thread.join(timeout=5)
    text=''.join(observed)
    assert 'Committed capital bytes missing or modified' in text,'Native capital corruption was not rejected'
    assert 'Failed to enable STARCORE module: nation' in text,'Damaged capital authority did not reject nation startup'
    assert 'Enabled STARCORE module: 国家 (nation)' not in text,'Nation authority incorrectly enabled after history loss'
    assert all(sha(run/relative)==digest for relative,digest in protected.items()),'Failed boot rewrote the damaged authority'
    assert process.returncode==0 and 'Stopping server' in text,'Negative-test server did not stop normally'
    record['status']='EXPECTED_CAPITAL_CORRUPTION_REJECTED'
finally:
    if process.poll() is None:
        process.stdin.write('stop\n');process.stdin.flush()
        try:process.wait(timeout=60)
        except subprocess.TimeoutExpired:print('Investigate retained negative-test PID',process.pid,flush=True)
    thread.join(timeout=5);record.update(exit_code=process.poll(),exited_at=time.time())
    (run/'evidence/corruption-summary.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps(record,indent=2))
