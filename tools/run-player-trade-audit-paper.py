"""Run four isolated real-wallet interruption windows, preserving every attempted boot."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, sys

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--attempt',required=True)
p.add_argument('--port',type=int,required=True)
for name in ('starcore','runtime','artifacts-dir','java-home','node'):
    p.add_argument('--'+name,type=Path,required=True)
p.add_argument('--case',choices=('debit-before','debit-after','credit-after','refund-after'),action='append')
a=p.parse_args()
output=ROOT/'target'/('player-trade-audit-'+a.attempt)
output.mkdir(parents=True,exist_ok=False)
cases={'debit-before':(1,'before'),'debit-after':(1,'after'),'credit-after':(2,'after'),'refund-after':(3,'after')}
done=[]
def execute(label,command):
    print(label,flush=True)
    with (output/(label+'.log')).open('x') as log:
        r=subprocess.run([str(x) for x in command],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
    if r.returncode: raise RuntimeError(f'{label} failed with {r.returncode}; retained {output}')
for offset,name in enumerate(a.case or cases):
    leg,window=cases[name];attempt=f'{a.attempt}-{name}'
    command=[sys.executable,ROOT/'tools/prepare-player-trade-audit-paper.py','--attempt',attempt,
        '--port',a.port+offset,'--starcore',a.starcore,'--leg',leg,'--window',window]
    for key in ('runtime','artifacts-dir','java-home'):command+=['--'+key,getattr(a,key.replace('-','_'))]
    execute(name+'-prepare',command)
    sha=hashlib.sha256((ROOT/'target/starchronicle-0.1.0-SNAPSHOT.jar').read_bytes()).hexdigest()
    run=ROOT/'run'/f'paper-epic-{sha[:8]}-dungeon-{attempt}'
    for boot,scenario in enumerate(('audit-seed','audit-interrupt','audit-recover','audit-recover'),1):
        execute(name+f'-boot{boot}',[sys.executable,ROOT/'tools/epic-paper-session.py','--run',run,
            '--boot',boot,'--scenario',scenario,'--java-home',a.java_home,'--node',a.node])
    done.append({'case':name,'run':str(run)})
    (output/'completed.json').write_text(json.dumps(done,indent=2)+'\n')
print(json.dumps({'completed':done}),flush=True)
