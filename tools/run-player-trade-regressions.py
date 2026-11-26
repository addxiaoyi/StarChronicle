"""Revalidate the frozen player trade pair on six independent selected-stack scenarios."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,sys
ROOT=Path(__file__).resolve().parents[1]
CASES={
    'factory':('prepare-nation-factory-paper.py',['factory-first','factory-second','factory-third'],'inspect-nation-factory-paper.py','test_nation_factory_acceptance.py','STARCHRONICLE_FACTORY_RUN'),
    'production':('prepare-nation-production-paper.py',['production-first','production-second','production-third'],'inspect-nation-production-paper.py','test_nation_production_acceptance.py','STARCHRONICLE_NATION_PRODUCTION_RUN'),
    'offline':('prepare-nation-production-paper.py',['production-offline-first','production-offline-second'],'inspect-nation-production-paper.py','test_nation_production_acceptance.py','STARCHRONICLE_NATION_PRODUCTION_RUN'),
    'trade':('prepare-nation-trade-paper.py',['sale-first','sale-second','sale-third'],'inspect-nation-trade-paper.py','test_nation_trade_acceptance.py','STARCHRONICLE_NATION_TRADE_RUN'),
    'dungeon':('prepare-production-paper.py',['first','second','third'],'inspect-epic-production-paper.py','test_epic_production_acceptance.py','STARCHRONICLE_EPIC_RUN'),
    'war':('prepare-battlefield-paper.py',['first','second','third'],'inspect-battlefield-paper.py','test_battlefield_acceptance.py','STARCHRONICLE_EPIC_RUN'),
}
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--attempt',required=True);p.add_argument('--first-port',type=int,required=True)
    p.add_argument('--cases',nargs='+',choices=CASES,default=list(CASES))
    p.add_argument('--frozen-dir',type=Path,default=ROOT/'target/player-trade-frozen')
    for name in ('runtime','artifacts-dir','java-home','node'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();assert os.environ.get('EPIC_MINEFLAYER_MODULE')
    frozen=a.frozen_dir.resolve();pair=json.loads((frozen/'manifest.json').read_text())
    sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
    assert sha(ROOT/'target/starchronicle-0.1.0-SNAPSHOT.jar')==pair['StarChronicle']['sha256']
    assert sha(frozen/'StarCore.jar')==pair['StarCore']['sha256']
    output=ROOT/'target'/('player-trade-regressions-'+a.attempt);output.mkdir(exist_ok=False)
    def execute(label,command,env=None):
        print(json.dumps(dict(stage=label,log=str(output/(label+'.log')))),flush=True)
        with (output/(label+'.log')).open('x') as log:
            r=subprocess.run([sys.executable,*map(str,command)],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        if r.returncode:raise RuntimeError('Failed '+label+'; retained original attempt and logs')
    result=[]
    for index,name in enumerate(a.cases):
        preparer,scenarios,inspector,test,variable=CASES[name]
        attempt=a.attempt+'-'+name
        command=['tools/'+preparer,'--attempt',attempt,'--port',a.first_port+index,'--starcore',frozen/'StarCore.jar']
        for key in ('runtime','artifacts-dir','java-home'):command+=['--'+key,getattr(a,key.replace('-','_'))]
        if name=='offline':command+=['--offline']
        if name=='dungeon':command+=['--campaign','--route','DUNGEON']
        execute(name+'-prepare',command)
        run=ROOT/'run'/f'paper-epic-{pair["StarChronicle"]["sha256"][:8]}-{"war" if name=="war" else "dungeon"}-{attempt}'
        for boot,scenario in enumerate(scenarios,1):
            if name=='offline' and boot==2:execute(name+'-resume',['tools/prepare-nation-production-resume.py','--run',run])
            command=['tools/epic-paper-session.py','--run',run,'--boot',boot,'--scenario',scenario,'--java-home',a.java_home,'--node',a.node]
            if name in ('dungeon','war'):command+=['--contributions']
            execute(name+'-boot'+str(boot),command)
        execute(name+'-inspect',['tools/'+inspector,run])
        execute(name+'-evidence-tests',['-m','unittest','discover','-s','tools','-p',test,'-v'],dict(os.environ,**{variable:str(run)}))
        result.append(dict(kind=name,run=str(run),result=json.loads((output/(name+'-inspect.log')).read_text())))
        (output/'completed.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(status='COMPLETE',directory=str(output))),flush=True)
if __name__=='__main__':main()
