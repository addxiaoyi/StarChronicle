"""Cross-check native GUI captures, score victory, exact receipts and restart checkpoints."""
from contextlib import closing
from pathlib import Path
import argparse
import importlib.util
import json
SPEC=importlib.util.spec_from_file_location('production_epic',Path(__file__).with_name('inspect-current-combination.py'))
STACK=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(STACK)
EPIC=STACK.COMBINATION.EPIC
check,read,sha=EPIC.check,EPIC.read,EPIC.digest
read_lines=STACK.COMBINATION.rows

def inspect(run):
    run=run.resolve();manifest=read(run/'preparation.json')
    check(manifest['route']=='WAR' and manifest['battlefield_acceptance']==dict(mode='native stronghold score victory',strongholds=2,points_each=50,expected_winner='AGGRESSOR',end_reason='VICTORY_DETERMINED'),'Unexpected battlefield acceptance contract')
    result=STACK.inspect(run)
    check(manifest.get('fixture_main_class')=='BattlefieldPaperFixture', 'Missing battlefield fixture binding')
    expected=manifest.get('fixture_extra_sources_sha256', {}).get('BattlefieldPaperFixture.java')
    check(expected and sha(run/'fixture/BattlefieldPaperFixture.java')==expected, 'Battlefield source drift')
    rows=read_lines(run/'evidence/epic-fixture.jsonl')
    def only(kind,boot=1):
        matches=[r for r in rows if r['boot']==boot and r['kind']==kind]
        check(len(matches)==1,'Missing or duplicate actual battlefield evidence: '+kind);return matches[0]
    prepared=only('BATTLE_PREPARED');seed=prepared['data'];war_id=seed['warId'];holds=seed['holds'];hold_ids={h['id'] for h in holds}
    check(seed['fixtureObjects'] is True and len(holds)==len(hold_ids)==2 and all(h['type']=='CITADEL' and h['points']==50 and h['owner']==seed['defender'] for h in holds),'Unexpected initial strongholds')
    started=only('BATTLE_STARTED');check(started['data']['warId']==war_id and started['data']['actorNation']==seed['aggressor'] and started['data']['nativeService'] is True,'Native combat start missing')
    ended=only('BATTLE_END_EVENT');event=ended['data']
    check(event==dict(warId=war_id,reason='VICTORY_DETERMINED',aggressorScore=100,defenderScore=0,winner='AGGRESSOR',endedAt=event['endedAt'],syntheticEvent=False),'Missing actual score-victory event')
    native=only('NATIVE_WAR_ENDED')['data'];check(native['warId']==war_id and native['reason']=='VICTORY_DETERMINED' and native['ending']=='native stronghold score victory' and EPIC.QUEST.millis(native['endedAt'])==EPIC.QUEST.millis(event['endedAt']),'Native victory completion differs')
    captures=[r for r in rows if r['kind']=='BATTLE_GUI_CLICK' and any(v.startswith('capture|') for v in r['data']['actions'].values())]
    check(len(captures)==2,'Exactly two native capture clicks required')
    check({r['data']['actions'].get('starcore:war_action','').split('|')[-1] for r in captures}==hold_ids,'Capture clicks target different strongholds')
    check(all(r['boot']==1 and r['data']['actor']==manifest['players']['A'] and r['data']['item']=='REDSTONE_BLOCK' and r['data']['click']=='LEFT' and r['data']['syntheticEvent'] is False and started['time']<r['time']<ended['time'] for r in captures),'Native capture actor, timing or event provenance differs')
    clients=read_lines(run/'evidence/boot1.clients.log');attempts=[r for r in clients if r['kind']=='BATTLE_GUI_ATTEMPT']
    expected=[('A',1,'blocked'),('A',1,'capture'),('A',1,'blocked'),('A',2,'capture'),('C',1,'blocked'),('A',2,'blocked')]
    check([(r['data']['who'],r['data']['index'],r['data']['mode']) for r in attempts]==expected,'Actual battlefield attempt sequence differs')
    check(all(r['data']['realClick'] is True and r['data']['syntheticEvents'] is False for r in attempts),'Synthetic battlefield client evidence')
    check(attempts[0]['time']<started['time']<attempts[1]['time']<ended['time']<attempts[-1]['time'],'Preparation, capture and end boundaries differ')
    for row in attempts:
        d=row['data']
        if d['mode']=='capture':check(d['before']['name']=='redstone_block' and d['after']['name']=='emerald_block','Actual GUI capture did not change ownership')
        else:check(d['before']['name'] in ('iron_block','emerald_block') and d['after']['name']==d['before']['name'],'Blocked GUI still offered capture')
    check(sum(r['kind']=='BATTLE_GUI_NAVIGATION' and r['data']['who']=='A' and r['data']['realClick'] is True for r in clients)==6,'Real player battlefield navigation missing')
    commands=[r for r in clients if r['kind']=='BATTLE_END_COMMAND'];check(len(commands)==1 and commands[0]['data']==dict(who='A',command='/war end 复兴帝国 EpicTestB',response='War ended between 复兴帝国 and EpicTestB',realCommand=True),'Actual non-admin principal ending command missing')
    state=[]
    for boot in (1,2,3):
        bind=only('BATTLE_BOUND',boot)['data'];check(bind==dict(eventObserver=True,nonOp=True,warAdmin=False),'Battlefield administrator bypass')
        observed=[r['data'] for r in rows if r['boot']==boot and r['kind']=='BATTLE_STATE']
        expected_scores=[0,50,50,100,100,100] if boot==1 else [100,100]
        check([r['aggressorScore'] for r in observed]==expected_scores and all(r['defenderScore']==0 for r in observed),'Observed score or replay sequence differs')
        check(all(r['expected']=='ended' and r['status']=='ENDED' and r['winner']=='AGGRESSOR' for r in observed if boot>1 or r==observed[-1]),'Restarted victory state missing')
        if boot>1:
            later=read_lines(run/f'evidence/boot{boot}.clients.log');late=[r['data'] for r in later if r['kind']=='BATTLE_GUI_ATTEMPT']
            check(len(late)==1 and late[0]['mode']=='blocked' and late[0]['who']=='A' and late[0]['realClick'] is True,'Post-restart capture was not rejected')
        checkpoint=run/f'evidence/checkpoints/boot{boot}'
        pinned=read(checkpoint/'checkpoint.json')
        check(pinned['boot']==boot and set(pinned['sha256'])=={'starcore.db','chronicle.db'}, 'Incomplete stopped database checkpoint')
        check(all(sha(checkpoint/name)==digest for name,digest in pinned['sha256'].items()), 'Stopped database checkpoint changed')
        with closing(EPIC.connect(run/f'evidence/checkpoints/boot{boot}/starcore.db')) as db:
            war=json.loads(db.execute('select war_json from starcore_wars where war_id=?',(war_id,)).fetchone()[0])
            battlefield=json.loads(db.execute('select state_json from starcore_battlefield_state where war_id=?',(war_id,)).fetchone()[0])
        check(war['status']=='ENDED' and war['aggressor']==seed['aggressor'] and war['defender']==seed['defender'] and war['aggressorWarScore']==100 and war['defenderWarScore']==0,'Committed war score or parties differ')
        check(war['endedAt']==EPIC.QUEST.millis(event['endedAt']) and war['startedAt'] is not None,'Committed war time differs')
        check(len(war.get('appliedScoreEvents',[]))==2 and len(set(war['appliedScoreEvents']))==2,'Committed capture score events duplicated or missing')
        check(battlefield['warId']==war_id and len(battlefield['battlefields'])==1 and battlefield['battlefields'][0]['id']==seed['field'],'Committed battlefield differs')
        actual=battlefield['strongholds'];check(len(actual)==2 and {h['id'] for h in actual}==hold_ids,'Committed strongholds missing or substituted')
        check(all(h['owner']==seed['aggressor'] and h.get('lastCapturedAt') is not None and h.get('pendingScoreEventId') is None and h.get('pendingScorePoints',0)==0 for h in actual),'Committed capture owner, time or pending score differs')
        observed_holds={h['id']:h for h in observed[-1]['holds']}
        check(all(h['owner']==observed_holds[h['id']]['owner'] and h['lastCapturedAt']==EPIC.QUEST.millis(observed_holds[h['id']]['capturedAt']) for h in actual),'Live and committed capture identity or time differs')
        server_log=(run/f'evidence/boot{boot}.server.log').read_text()
        for kind in ('BATTLE_PREPARED','BATTLE_STARTED','BATTLE_END_EVENT','BATTLE_GUI_CLICK','BATTLE_STATE','BATTLE_BOUND'):
            emitted=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in server_log.splitlines() if 'EPIC_'+kind+' ' in line]
            check(emitted==[r['data'] for r in rows if r['boot']==boot and r['kind']==kind],'Battlefield server observation differs: '+kind)
        state.append(dict(war=war,battlefield=battlefield))
    client_attempts=[r['data'] for boot in (1,2,3) for r in read_lines(run/f'evidence/boot{boot}.clients.log') if r['kind']=='BATTLE_GUI_ATTEMPT']
    server_attempts=[r['data'] for r in rows if r['kind']=='BATTLE_GUI_CLICK' and r['data']['item'] in ('REDSTONE_BLOCK','IRON_BLOCK','EMERALD_BLOCK')]
    check(len(server_attempts)==len(client_attempts)==8,'Native capture/rejection click observations missing')
    check(all(server['actor']==manifest['players'][client['who']] and server['slot']==client['slot'] and server['item']==client['before']['name'].upper() and server['syntheticEvent'] is False for server,client in zip(server_attempts,client_attempts)),'Server and client stronghold clicks differ')
    check(state[0]==state[1]==state[2],'Native score or battlefield changed across restart/replay')
    with closing(EPIC.connect(run/'plugins/StarCore/starcore.db')) as db:
        final=dict(war=json.loads(db.execute('select war_json from starcore_wars where war_id=?',(war_id,)).fetchone()[0]),battlefield=json.loads(db.execute('select state_json from starcore_battlefield_state where war_id=?',(war_id,)).fetchone()[0]))
    check(final==state[2],'Final native battlefield differs from stopped checkpoint')
    snapshots=[]
    for path in [run/f'evidence/checkpoints/boot{boot}/chronicle.db' for boot in (2,3)]+[run/'plugins/StarChronicle/chronicle.db']:
        with closing(EPIC.connect(path)) as db:
            tables=[row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND (name LIKE 'epic_%' OR name='contributions') ORDER BY name")]
            snapshots.append({table:sorted(tuple(row) for row in db.execute('SELECT * FROM '+table)) for table in tables})
    check(snapshots[0]==snapshots[1]==snapshots[2], 'Settled campaign, receipts or contributions changed on restart/replay')
    return dict(result,status='NATIVE_STRONGHOLD_VICTORY_ACCEPTED',war_id=war_id,strongholds=2,aggressor_score=100,defender_score=0,native_end_reason='VICTORY_DETERMINED',native_winner='AGGRESSOR',capture_clicks=2,blocked_attempts=6,
        limitation='Native GUI stronghold capture with explicit fixture objects and native start service; no PvP, siege movement, army simulation, territory transfer, occupation-duration or production deployment claim.')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path)
    run=p.parse_args().run.resolve(); result=inspect(run)
    (run/'evidence/battlefield-independent-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False,indent=2))
