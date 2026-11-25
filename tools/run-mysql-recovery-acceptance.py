"""Run three real restored Paper sessions, retaining a deliberate process interruption separately."""
from pathlib import Path
import argparse
import json
import subprocess
import sys
import socket
import time
import mysql_recovery as R


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('run','java-home','node'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();run=a.run.resolve();manifest=R.read(run/'preparation.json');plan=R.read(run/'evidence/recovery/acceptance-plan.json')
    R.check(run.parent==R.ROOT/'run' and manifest.get('recovery_acceptance'),'Require restored run')
    output=R.ROOT/'target'/('recovery-'+run.name);output.mkdir(parents=True,exist_ok=False)
    for offset,scenario in enumerate(plan['scenarios']):
        boot=plan['first_boot']+offset
        if offset==2:
            # The listener can be gone while a recently killed TCP socket still
            # prevents an exclusive bind. Do not disguise this as a game failure.
            began=time.time();deadline=time.monotonic()+75;attempts=0
            while True:
                attempts+=1
                try:
                    with socket.socket() as probe:probe.bind(('127.0.0.1',manifest['server_port']))
                    break
                except OSError:
                    R.check(time.monotonic()<deadline,'Recovery listener is still unavailable; retain interruption evidence')
                    time.sleep(.5)
            R.write(output/'restart-port-ready.json',dict(port=manifest['server_port'],attempts=attempts,started_at=began,finished_at=time.time()))
        command=[sys.executable,str(R.ROOT/'tools/epic-paper-session.py'),'--run',str(run),'--java-home',str(a.java_home),'--node',str(a.node),'--boot',str(boot),'--scenario',scenario]
        if scenario=='third':command.append('--contributions')
        if offset==1:command.append('--interrupt-after-replay')
        with (output/f'boot{boot}.log').open('x') as log:result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
        print(json.dumps(dict(run=run.name,boot=boot,exit_code=result.returncode)),flush=True)
        R.check(result.returncode==0,'Recovery session failed; retain output '+str(output))
    R.write(output/'runtime-result.json',dict(status='RUNTIME_FINISHED_REQUIRES_INDEPENDENT_AUDIT',run=str(run),first_boot=plan['first_boot']))


if __name__=='__main__':main()
