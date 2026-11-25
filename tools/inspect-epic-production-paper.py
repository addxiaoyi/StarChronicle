"""Verify full epic and contributions against real selected-stack boots and stopped checkpoints."""
from contextlib import closing
from pathlib import Path
import argparse
import importlib.util
import json
import re

SPEC=importlib.util.spec_from_file_location('contribution_inspector',Path(__file__).with_name('inspect-contribution-paper.py'))
CONTRIBUTIONS=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(CONTRIBUTIONS)
EPIC=CONTRIBUTIONS.EPIC
check,read,sha=EPIC.check,EPIC.read,EPIC.digest


def table(db,name):
    return sorted((dict(row) for row in db.execute('SELECT * FROM '+name)),key=lambda row:json.dumps(row,sort_keys=True))


def inspect(run):
    run=run.resolve();manifest=read(run/'preparation.json')
    check(manifest['production_campaign']['route']==manifest['route'],'Production campaign route differs')
    check(manifest['production_campaign']['war_ending']=='native principal peace','Unverified battle-victory claim')
    result=CONTRIBUTIONS.inspect(run)
    check(manifest['fixture_main_class']=='ProductionStackPaperFixture','Missing production-aware fixture')
    for name,digest in manifest['fixture_extra_sources_sha256'].items():check(sha(run/'fixture'/name)==digest,'Production fixture source changed')
    paper={value for name,value in manifest['source_artifacts'].items() if Path(name).name=='paper.jar'}
    check(len(paper)==1 and sha(run/'paper.jar') in paper,'Frozen Paper artifact changed')
    for artifact in manifest['production_stack']['artifacts']:
        check(sha(run/'plugins'/artifact['filename'])==artifact['sha256'],'Frozen production plugin changed')
    fixture=read_lines(run/'evidence/epic-fixture.jsonl');stacks=[];telemetry=[];snapshots={};lifetimes=[]
    expected_plugins={'StarCore','StarChronicle','FancyNpcs','WorldGuard','WorldEdit','Essentials','Vault'}
    for boot in (1,2,3):
        session=read(run/f'evidence/boot{boot}.session.json');check(session['contributions'] is True,'Contribution verification disabled')
        for kind,suffix in [('controller','py'),('clients','cjs')]:check(sha(run/f'evidence/boot{boot}.{kind}.{suffix}')==session['source_sha256'][kind],'Captured runtime script changed')
        server=next(p for p in session['processes'] if p['name']=='server')
        if lifetimes:check(lifetimes[-1]['exited_at']<server['started_at'],'Campaign server boots overlap')
        lifetimes.append(server)
        observed=[r for r in fixture if r['boot']==boot and r['kind']=='STACK_LOADED'];check(len(observed)==1,'Missing actual loaded stack observation')
        stack=observed[0]['data'];check(set(stack['plugins'])==expected_plugins and all(stack['plugins'].values()),'Missing enabled production plugin')
        check(stack['businessEconomy']=='dev.starcore.starcore.foundation.economy.PlayerVaultEconomyService','Epic did not use the selected external economy')
        if stacks:check(stack==stacks[0],'Production providers or versions changed after restart')
        stacks.append(stack)
        log=(run/f'evidence/boot{boot}.server.log').read_text()
        emitted=[json.loads(line.split('EPIC_STACK_LOADED ',1)[1]) for line in log.splitlines() if 'EPIC_STACK_LOADED ' in line]
        check(emitted==[stack],'Stack observation missing from server log')
        telemetry.extend(dict(boot=boot,message=line) for line in log.splitlines() if EPIC.vendor_telemetry(line))
        checkpoint=run/f'evidence/checkpoints/boot{boot}';inventory=read(checkpoint/'checkpoint.json')
        check(inventory['boot']==boot and set(inventory['sha256'])=={'chronicle.db','starcore.db'},'Missing independent stopped database pair')
        for name,digest in inventory['sha256'].items():check(sha(checkpoint/name)==digest,'Stopped campaign database checkpoint changed')
        with closing(EPIC.connect(checkpoint/'chronicle.db')) as db:
            campaign=table(db,'epic_campaigns');check(len(campaign)==1 and campaign[0]['route']==manifest['route'],'Checkpoint campaign identity differs')
            expected='ROUTE_APPROVED' if boot==1 else 'COMPLETE';check(campaign[0]['status']==expected,'Settlement crossed the wrong restart boundary')
            settled=[json.loads(row[0]) for row in db.execute("select json from events where type='HEIR_STRATEGY_SETTLED'")]
            check(len(settled)==(0 if boot==1 else 1),'Settlement missing or duplicated across restart')
            snapshots[boot]={name:table(db,name) for name in ('epic_campaigns','epic_operations','epic_route_votes','contributions','epic_contribution_projections','story_nation_resolutions')}
            check(len(snapshots[boot]['contributions'])==8,'Contributions changed at stopped boundary')
        with closing(EPIC.connect(checkpoint/'starcore.db')) as db:check(db.execute('pragma integrity_check').fetchone()[0]=='ok','Native checkpoint integrity failed')
    check(snapshots[2]==snapshots[3],'Replay changed completed campaign or contributions')
    with closing(EPIC.connect(run/'plugins/StarChronicle/chronicle.db')) as db:
        current={name:table(db,name) for name in snapshots[3]}
    check(current==snapshots[3],'Final database differs from last stopped checkpoint')
    return dict(result,status='SELECTED_PAPER_STACK_EPIC_ACCEPTED',plugins=stacks[0]['plugins'],business_economy=stacks[0]['businessEconomy'],
        database_checkpoint_boots=[1,2,3],vendor_telemetry=telemetry,
        limitation='War completion is native principal peace; this does not demonstrate battlefield victory, paid founding, Folia or production deployment.')


def read_lines(path):return [json.loads(line) for line in path.read_text().splitlines() if line.startswith('{')]


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path)
    print(json.dumps(inspect(p.parse_args().run),ensure_ascii=False,indent=2))
