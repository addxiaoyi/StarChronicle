"""Independently verify stopped real-exploration memory runs and their database checkpoints."""
from contextlib import closing
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import socket
import zipfile
import yaml

SPEC=importlib.util.spec_from_file_location('production_inspector',Path(__file__).with_name('inspect-production-paper.py'))
STACK=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(STACK)
EPIC,check,records,instant,normalized,one=STACK.EPIC,STACK.check,STACK.records,STACK.instant,STACK.normalized,STACK.one
REGION='old-caravan-road';RECIPE='caravan-archive';STORY='caravan-library'

def canonical(value):return json.dumps(value,ensure_ascii=False,separators=(',',':'),sort_keys=True)
def stable(*parts):
    digest=hashlib.sha256()
    for part in parts:
        data=part.encode();digest.update(str(len(data)).encode()+b':'+data)
    return digest.hexdigest()
def config(path):return yaml.safe_load(path.read_text())
def rows(db,query,*values):return [dict(r) for r in db.execute(query,values)]

def database(path,manifest,expected_sources,expected_memories,choices, pending_ingress=False):
    players=manifest['players']; memory=manifest['memory_acceptance']
    with closing(EPIC.connect(path)) as db:
        check(db.execute('pragma integrity_check').fetchone()[0]=='ok' and not rows(db,'pragma foreign_key_check'),'Memory database integrity failure')
        check(db.execute('pragma user_version').fetchone()[0]==7,'Wrong memory schema')
        definitions=rows(db,'select * from memory_recipe_definitions')
        check({(d['recipe_id'],d['version']) for d in definitions}=={(RECIPE,'1'),('siege-archive','1')},'Validation archived a new recipe version')
        recipe_row=next(d for d in definitions if d['recipe_id']==RECIPE);recipe=json.loads(recipe_row['json'])
        check(recipe_row['fingerprint']==stable('memory-recipe-v1',canonical(recipe)),'Recipe definition hash mismatch')
        check(recipe['title']=='旧商路的真实到访档案' and recipe['version']=='1','Rejected recipe replaced active content')
        event_rows=rows(db,"select * from events where type in ('EXPLORATION_DISCOVERY','WORLD_MEMORY_RECORDED','CARAVAN_ARCHIVE_TESTIMONY','CARAVAN_ARCHIVE_READ')")
        events={r['id']:normalized(json.loads(r['json'])) for r in event_rows}
        check(all(len(e['participants'])==1 and e['participants'][0] in players.values() for e in events.values()),'Memory history attributed an invalid actor')
        sources={next(who for who,player in players.items() if e['participants']==[player]):e for e in events.values() if e['type']=='EXPLORATION_DISCOVERY'}
        check(set(sources)==set(expected_sources),'Persisted first-visit count/actors mismatch')
        check(sum(e['type']=='EXPLORATION_DISCOVERY' for e in events.values())==len(sources),'Duplicated first-visit actor')
        for row in event_rows:
            e=events[row['id']];value=dict(id=e['id'],type=e['type'],time='OBSERVED' if e['facts'].get('time_basis')=='OBSERVED' else e['occurredAt'],players=e['participants'],nations=e['nations'],facts=e['facts'],source=e['source'],evidence=e['evidence'])
            check(row['hash']==stable(canonical(value)),'Memory history canonical hash mismatch')
            check(row['type']==e['type'] and instant(row['time'])==instant(e['occurredAt']),'Memory history columns mismatch')
            check(sorted(r['player'] for r in rows(db,'select player from event_players where event_id=?',e['id']))==e['participants'],'Memory event player index mismatch')
            check(sorted(r['nation'] for r in rows(db,'select nation from event_nations where event_id=?',e['id']))==e['nations'],'Memory event nation index mismatch')
        ingress=rows(db,'select * from exploration_discoveries')
        if pending_ingress:
            check(len(ingress)==1 and ingress[0]['status']=='PENDING' and ingress[0]['player_id']==players['D'],'Offline source was not retained in durable ingress')
        else:
            check(len(ingress)==len(sources) and all(r['status']=='ACKED' and normalized(json.loads(r['json']))==events[r['event_id']] for r in ingress),'First-visit ingress differs from accepted history')
        entries=rows(db,'select * from memory_entries');by_actor={}
        for row in entries:
            entry=json.loads(row['json']);who=next((key for key,e in sources.items() if e['id']==entry['sourceEventId']),None)
            check(who is not None and who not in by_actor,'Memory has no unique actual source');by_actor[who]=entry;source=sources[who]
            expected_id=stable('memory-entry-v1',source['id'],RECIPE,'1')
            check(entry['id']==row['id']==expected_id and entry['recipeId']==row['recipe_id']==RECIPE and entry['recipeVersion']==row['version']=='1','Memory identity/version mismatch')
            check(entry['sourceEventId']==row['source_event_id']==source['id'] and entry['signalEventId']==row['signal_event_id']=='memory:'+expected_id,'Memory source/signal identity mismatch')
            check(entry['definitionHash']==row['definition_hash']==recipe_row['fingerprint'] and entry['regionId']==row['region_id']==REGION and entry['title']==recipe['title'] and entry['text']==recipe['text'],'Persisted memory recipe content mismatch')
            check(entry['sourceOccurredAt']==source['occurredAt'] and instant(entry['recordedAt'])>=instant(source['occurredAt']) and instant(row['recorded_at'])==instant(entry['recordedAt']),'Memory source time was rewritten')
            signal=events[entry['signalEventId']]
            facts={'region.id':REGION,'memory.entry':entry['id'],'memory.recipe':RECIPE,'memory.version':'1','memory.definition_hash':entry['definitionHash'],'memory.title':entry['title'],'memory.text':entry['text'],'source.id':source['id'],'source.type':source['type'],'source.source':source['source'],'source.occurred_at':source['occurredAt'],'time_basis':'RECORDED'}
            check(signal['type']=='WORLD_MEMORY_RECORDED' and signal['source']=='StarChronicle.MemoryRecipes' and signal['facts']==facts and signal['participants']==source['participants'] and signal['nations']==source['nations'] and signal['occurredAt']==entry['recordedAt'],'Memory signal changed original participation or evidence')
            trace=rows(db,'select * from event_causes where event_id=?',signal['id'])[0]
            check(trace['parent_id']==trace['root_id']==source['id'] and trace['depth']==1 and json.loads(trace['recipe_path'])==[RECIPE] and json.loads(trace['story_path'])==[],'Memory signal causal path mismatch')
        check(set(by_actor)==set(expected_memories),'Persisted memory count/actors mismatch')
        check(sum(e['type']=='WORLD_MEMORY_RECORDED' for e in events.values())==len(entries),'Orphan or duplicated memory signal')
        for who,source in sources.items():
            facts=source['facts'];check(source['source']=='StarChronicle.ConfiguredRegionFirstVisit' and facts['time_basis']=='OBSERVED' and facts['discovery.kind']=='FIRST_VISIT' and facts['region.id']==REGION and facts['region.world']==memory['world'],'First-visit world/provenance mismatch')
            check(1004<=int(facts['region.x'])<=1006 and -60<=int(facts['region.y'])<=-58 and 0<=int(facts['region.z'])<=3,'First visit outside actual configured bounds')
            check(source['id']=='exploration:first-visit:'+players[who]+':'+facts['region.scope'],'First-visit identity mismatch')
            trace=rows(db,'select * from event_causes where event_id=?',source['id'])[0]
            check(trace['parent_id'] is None and trace['root_id']==source['id'] and trace['depth']==0 and json.loads(trace['recipe_path'])==[] and json.loads(trace['story_path'])==[],'Actual first visit is not a causal root')
        instances=[json.loads(r['json']) for r in rows(db,"select * from instances where story='caravan-library'")]
        expected_pairs={(who,origin) for origin in expected_memories for who in ('A','D') if origin in ('A','D')}|{('B','B') for origin in expected_memories if origin=='B'}
        # Before an offline source is accepted there are no story instances; once accepted on
        # an empty server only the durable seeds exist. Final offline DB is checked after login.
        if pending_ingress:expected_pairs=set()
        check(len(instances)==len(expected_pairs),'Persisted library audience count mismatch')
        instance_map={};expected_outcomes={};expected_audit={};expected_triggers=set()
        for instance in instances:
            who=next(key for key,value in players.items() if instance['scopeKey']=='player:'+value)
            origin=next(key for key,value in by_actor.items() if value['signalEventId']==instance['sourceEventId'])
            pair=(who,origin);check(pair in expected_pairs and pair not in instance_map,'Library audience/source mismatch');instance_map[pair]=instance
            check(set(instance['members'])=={players[who]} and instance['definition']['id']==STORY and instance['definition']['version']=='1','Library reader or definition changed')
            check(instance['id']==stable(STORY,'1',instance['scopeKey'],instance['sourceEventId']),'Library stable instance identity changed')
            expected_triggers.add((stable(instance['sourceEventId'],STORY,'1'),instance['scopeKey']))
            choice=choices.get(pair)
            expected_choices={} if choice is None else {'archive':choice,'archive/'+players[who]:choice}
            check(instance['completed']==(choice is not None) and instance['choices']==expected_choices and instance['node']==('archive' if choice is None else 'testimony' if choice=='testify' else 'reader'),'Persisted library choice/completion mismatch')
            endings=[e for e in events.values() if e['type'].startswith('CARAVAN_ARCHIVE_') and 'instance='+instance['id']+';' in e['evidence']]
            check(len(endings)==(0 if choice is None else 1),'Library ending not exactly once')
            if endings:
                ending=endings[0];check(ending['participants']==[players[who]] and ending['type']==('CARAVAN_ARCHIVE_TESTIMONY' if choice=='testify' else 'CARAVAN_ARCHIVE_READ'),'Library reader was attributed as another actor')
                outcome=stable(instance['id'],'node/'+instance['node'],'testimony' if choice=='testify' else 'reading','WORLD')
                check(ending['id']=='chronicle:'+outcome,'Library ending receipt identity changed')
                expected_outcomes[outcome]={'id':outcome,'status':'DONE','json':'{}'}
                if choice=='testify':
                    honor=stable(instance['id'],'node/testimony','local-honor',players[who]);expected_outcomes[honor]={'id':honor,'status':'DONE','json':'{}'}
                for kind in ('BALLOT','RESOLUTION'):
                    identity=stable('choice-log',instance['id'],'archive',kind,instance['scopeKey'],'1')
                    expected_audit[identity]=dict(id=identity,instance_id=instance['id'],source_event_id=instance['sourceEventId'],story_id=STORY,story_version='1',node_id='archive',kind=kind,actor_key=instance['scopeKey'],player_id=players[who] if kind=='BALLOT' else None,nation_id=instance['members'][players[who]],scope_key=instance['scopeKey'],revision=1,choice_id=choice,committed_by=players[who],provenance='LIVE')
                check(ending['facts']['region.id']==REGION and ending['facts']['region.source_event']==instance['sourceEventId'],'Library ending lost its real location source')
                trace=rows(db,'select * from event_causes where event_id=?',ending['id'])[0]
                check(trace['parent_id']==instance['sourceEventId'] and trace['root_id']==sources[origin]['id'] and trace['depth']==2 and json.loads(trace['recipe_path'])==[RECIPE] and json.loads(trace['story_path'])==[STORY],'Library ending causal path mismatch')
        check(sum(e['type'].startswith('CARAVAN_ARCHIVE_') for e in events.values())==len(choices),'Unexpected library ending history')
        check({r['id']:r for r in rows(db,'select * from outcomes')}==expected_outcomes,'Missing or altered exactly-once outcome receipt')
        audit=rows(db,'select * from story_choice_log')
        check(len(audit)==len(expected_audit),'Missing or duplicated library choice audit')
        for row in audit:
            check({k:v for k,v in row.items() if k not in ('seq','chosen_at')}==expected_audit.get(row['id']),'Library choice audit actor/source mismatch')
            instance=next(i for i in instances if i['id']==row['instance_id'])
            check(instant(instance['startedAt'])<=instant(row['chosen_at'])<=instant(instance['updatedAt']),'Library choice audit time mismatch')
        check({(r['trigger_key'],r['scope']) for r in rows(db,'select * from story_trigger_receipts')}==expected_triggers,'Library trigger consumption mismatch')
        for who,player in players.items():
            honor=rows(db,"select value from facts where owner=? and key='player.honor'",player)
            check((int(honor[0]['value']) if honor else 0)==3*sum(actor==who and choice=='testify' for (actor,_),choice in choices.items()),'Persisted honor duplicated or assigned to reader')
        world_facts={r['key']:r['value'] for r in rows(db,"select * from facts where owner='WORLD' and key like 'region.old-caravan-road.memory.caravan-archive.%'")}
        if entries:
            last=max(by_actor.values(),key=lambda e:instant(e['recordedAt']));prefix='region.'+REGION+'.memory.'+RECIPE+'.'
            check(world_facts=={prefix+k:v for k,v in dict(entry=last['id'],version='1',source=last['sourceEventId'],source_occurred_at=last['sourceOccurredAt'],recorded_at=last['recordedAt'],title=last['title'],text=last['text']).items()},'Latest local memory projection mismatch')
        else:check(not world_facts,'Local memory exists before source acceptance')
        for table in ('memory_recipe_blocks','memory_sites','contributions'):check(db.execute('select count(*) from '+table).fetchone()[0]==0,'Unexpected memory side effect: '+table)
        return dict(sources=sources,entries=by_actor,instances={a+':'+b:value for (a,b),value in instance_map.items()},events=events,ingress=ingress)

def inspect(run):
    run=run.resolve();manifest=EPIC.read(run/'preparation.json');meta=manifest['memory_acceptance'];offline=meta['mode']=='offline';boots=2 if offline else 3
    with socket.socket() as probe:probe.bind(('127.0.0.1',manifest['server_port']))
    for name,key in [('StarCore','starcore_sha256'),('StarChronicle','plugin_sha256'),('EpicCampaignPaperFixture','fixture_sha256')]:check(EPIC.digest(run/f'plugins/{name}.jar')==manifest[key],'Frozen memory JAR changed')
    paper={h for p,h in manifest['source_artifacts'].items() if Path(p).name=='paper.jar'};check(EPIC.digest(run/'paper.jar') in paper,'Frozen memory Paper changed')
    check(EPIC.digest(run/'fixture/EpicCampaignPaperFixture.java')==manifest['fixture_source_sha256'],'Frozen base fixture changed')
    for name,value in manifest['fixture_extra_sources_sha256'].items():check(EPIC.digest(run/'fixture'/name)==value,'Frozen memory fixture changed')
    for artifact in manifest['production_stack']['artifacts']:check(EPIC.digest(run/'plugins'/artifact['filename'])==artifact['sha256'],'Frozen third-party memory JAR changed')
    versions={}
    for path in (run/'plugins').glob('*.jar'):
        if path.name=='EpicCampaignPaperFixture.jar':continue
        with zipfile.ZipFile(path) as jar:d=yaml.safe_load(jar.read('paper-plugin.yml' if 'paper-plugin.yml' in jar.namelist() else 'plugin.yml'))
        versions[d['name']]=str(d['version'])
    check(len(versions)==7,'Incomplete selected memory plugin stack')
    for variant,expected in meta['recipe_variants_sha256'].items():check(EPIC.digest(run/'fixture'/f'memory-{variant}.yml')==expected,'Frozen memory variant changed')
    check(EPIC.digest(run/'plugins/StarChronicle/memory-recipes.yml')==meta['recipe_variants_sha256']['enabled'],'Final memory recipe was not restored')
    check(EPIC.digest(run/'plugins/StarChronicle/stories/caravan-library.yml')==meta['story_sha256'],'Delivered library story changed')
    settings=config(run/'plugins/StarChronicle/config.yml');check(settings['exploration']['enabled'] and settings['poll-ticks']==(72000 if offline else 20),'Actual exploration/poll configuration changed')
    check(settings['exploration']['regions']==[{'id':REGION,'world':meta['world'],'min-x':1004,'max-x':1006,'min-y':-60,'max-y':-58,'min-z':0,'max-z':3}],'Configured physical region changed')
    evidence=run/'evidence';fixture=records(evidence/'epic-fixture.jsonl');check(not any(r['kind']=='FAILED' for r in fixture),'Retained memory fixture failure')
    check({r['boot'] for r in fixture}==set(range(1,boots+1)) and len(list(evidence.glob('boot*.session.json')))==boots,'Missing complete memory boot')
    expected_phases=[['offline-buffered'],['offline-recorded','offline-pending','offline-testimony','offline-complete','offline-complete']] if offline else [['zero','first','testimony','witness','witness','second','read','disabled','disabled','pending','pending'],['pending','d-complete','complete','complete'],['complete','complete']]
    all_clicks=[];walks=[];checks=[];sessions=[];telemetry=[];source_hashes=None
    seeded=one([r for r in fixture if r['boot']==1],'SEEDED')
    for boot in range(1,boots+1):
        session=EPIC.read(evidence/f'boot{boot}.session.json');sessions.append(session)
        expected_scenario=('memory-offline-' if offline else 'memory-')+('first','second','third')[boot-1]
        check(session['status']=='COMPLETE' and session['scenario']==expected_scenario and session['boot']==boot,'Incomplete memory scenario')
        ps=session['processes'];check(len(ps)==2 and {p['name'] for p in ps}=={'server','clients'} and all(p['exit_code']==0 for p in ps),'Abnormal memory process exit')
        hashes={'controller':EPIC.digest(evidence/f'boot{boot}.controller.py'),'clients':EPIC.digest(evidence/f'boot{boot}.clients.cjs')}
        check(session['source_sha256']==hashes,'Frozen memory controller/client changed')
        if source_hashes is None:source_hashes=hashes
        check(source_hashes==hashes,'Memory runtime source changed between boots')
        observations=[r for r in fixture if r['boot']==boot];server=(evidence/f'boot{boot}.server.log').read_text()
        check(one(observations,'SEEDED')==seeded and seeded['nonOp'] and one(observations,'CLOSED')=={'normalDisable':True},'Memory identity or shutdown changed')
        check(one(observations,'READY')==dict(product=manifest['plugin_sha256'],native=manifest['starcore_sha256'],route='DUNGEON'),'Running memory artifact mismatch')
        check(one(observations,'STACK_LOADED')['plugins']==versions,'Actual memory stack versions mismatch')
        check(all(word in server for word in ('Done (','Stopping server','All dimensions are saved')),'Incomplete memory server lifecycle')
        diagnostics=re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|Could not pass event|History scan retained pending evidence).*$',server,re.M|re.I)
        for line in diagnostics:
            check(re.search(r'\[FancyAnalytics\] Failed to create record \(status 5\d\d\):',line) is not None,'Memory server lifecycle/persistence error: '+line);telemetry.append(dict(boot=boot,message=line))
        process=next(p for p in ps if p['name']=='server')
        check(all(process['started_at']<=instant(r['time'])<=process['exited_at'] for r in observations),'Memory evidence outside server lifetime')
        for kind in {r['kind'] for r in observations}:
            live=[json.loads(line.split('EPIC_'+kind+' ',1)[1]) for line in server.splitlines() if 'EPIC_'+kind+' ' in line]
            check(live==[r['data'] for r in observations if r['kind']==kind],'Memory observation missing from server stream: '+kind)
        current=[r for r in observations if r['kind']=='MEMORY_CHECK'];checks+=current
        check([r['data']['phase'] for r in current]==expected_phases[boot-1],'Missing memory API checkpoints')
        clients=records(evidence/f'boot{boot}.clients.log');check(not any(r['kind']=='FAILED' for r in clients),'Real memory client failed')
        spawned=[r['data'] for r in clients if r['kind']=='SPAWN'];check(len(spawned)==4 and {r['who'] for r in spawned}==set('ABCD') and all(r['uuid']==manifest['players'][r['who']] for r in spawned),'Actual memory client identity mismatch')
        walks += [dict(r,boot=boot) for r in clients if r['kind']=='MEMORY_WALK'];all_clicks += [dict(r,boot=boot) for r in clients if r['kind']=='MEMORY_GUI_CHOICE']
        snapshot=evidence/f'checkpoints/boot{boot}';metadata=EPIC.read(snapshot/'checkpoint.json')
        check(metadata['boot']==boot and set(metadata['sha256'])=={'chronicle.db','starcore.db'} and all(EPIC.digest(snapshot/name)==value for name,value in metadata['sha256'].items()),'Stopped memory database checkpoint changed')
    lifetimes=[next(p for p in s['processes'] if p['name']=='server') for s in sessions]
    check(all(a['exited_at']<b['started_at'] for a,b in zip(lifetimes,lifetimes[1:])),'Memory server boots overlap')
    choices={('D','D'):'testify',('A','D'):'read'} if offline else {('A','A'):'testify',('D','A'):'read',('B','B'):'read',('D','D'):'testify',('A','D'):'read'}
    result=database(run/'plugins/StarChronicle/chronicle.db',manifest,'D' if offline else 'ABCD','D' if offline else 'ABD',choices)
    final=checks[-1]['data']['state']
    check({e['id']:e for e in final['entries']}=={e['id']:e for e in result['entries'].values()},'Persisted memories differ from live API')
    check({e['id']:normalized(e) for e in final['history']}==result['events'],'Persisted history differs from live memory API')
    check({i['id']:i for p in final['players'].values() for i in p['instances']}=={i['id']:i for i in result['instances'].values()},'Persisted library instances differ from live API')
    check(len(walks)==(1 if offline else 5) and all(r['data']['syntheticEvents'] is False for r in walks),'Missing actual memory movement')
    check(len(all_clicks)==len(choices) and all(r['data']['realClick'] is True and r['data']['node']=='archive' for r in all_clicks),'Missing real library GUI choices')
    for pair,choice in choices.items():
        instance=result['instances'][':'.join(pair)];click=[r for r in all_clicks if r['data']['instance']==instance['id']]
        check(len(click)==1 and click[0]['data']['who']==pair[0] and click[0]['data']['choice']==choice,'GUI choice/source binding mismatch')
    for who,source in result['sources'].items():
        walk=next(r for r in walks if r['data']['who']==who);before=walk['data']['before'];after=walk['data']['after']
        check(999<=before['x']<=1002 and 1004<=after['x']<1007 and -60.2<=after['y']<=-58 and 0<=after['z']<4,'Real walk did not cross the region boundary')
        outside=[r for r in fixture if r['boot']==walk['boot'] and r['kind']=='MEMORY_OUTSIDE' and r['data']['who']==who][0]
        check(instant(outside['time'])<=instant(source['occurredAt'])<=instant(walk['time']),'First visit is not tied to the real walk')
        check(source['nations']==[seeded['A' if who=='D' else who]],'First visit nation differs from native identity')
    if offline:
        pending=database(evidence/'checkpoints/boot1/chronicle.db',manifest,'','',{},True)
        check(normalized(json.loads(pending['ingress'][0]['json']))==result['sources']['D'],'Offline first visit changed across restart')
        observation=next(r for r in checks if r['data']['phase']=='offline-recorded');state=observation['data']['state']
        client=next(p for p in sessions[1]['processes'] if p['name']=='clients');entry=result['entries']['D']
        check(state['online']==[] and state['active']==[] and len(state['entries'])==1 and instant(observation['time'])<client['started_at'],'Memory was not generated before all clients connected')
        check(lifetimes[1]['started_at']<instant(entry['recordedAt'])<instant(observation['time']) and instant(entry['sourceOccurredAt'])<lifetimes[0]['exited_at'],'Offline recording rewrote the original observation time')
    else:
        first=database(evidence/'checkpoints/boot1/chronicle.db',manifest,'ABCD','ABD',{('A','A'):'testify',('D','A'):'read',('B','B'):'read'})
        check(first['sources']==result['sources'] and first['entries']==result['entries'],'Canonical memory changed across restarts')
        database(evidence/'checkpoints/boot2/chronicle.db',manifest,'ABCD','ABD',choices)
        transitions=records(evidence/'memory-config.jsonl')
        check([r['variant'] for r in transitions if 'variant' in r]==['validate-v2','invalid-v1','disabled','enabled'],'Missing real recipe configuration transitions')
        commands=[r for r in transitions if 'command' in r];check(len(commands)==5 and all(r['matched'] for r in commands),'Missing actual recipe validate/reload results')
        server=(evidence/'boot1.server.log').read_text();check(all(r['expected'] in server for r in commands),'Recipe reload result missing from server')
        client=records(evidence/'boot1.clients.log');check(any(r['kind']=='COMMAND_RESULT' and r['data']['command']=='/chronicle reload' and '没有管理权限。' in r['data']['response'] for r in client),'Non-admin reload was not rejected')
    for boot in range(1,boots+1):
        observations=[r for r in fixture if r['boot']==boot]
        replays=[r for r in observations if r['kind']=='MEMORY_REPLAY']
        expected_replays=([0,1] if offline else [7,4,4])[boot-1]
        check(len(replays)==expected_replays,'Missing canonical memory replay evidence')
        for row in replays:check(row['data']['duplicate'] and normalized(row['data']['source']) in result['sources'].values(),'Replay fabricated or changed canonical source')
        expected_ids=([result['sources']['D']['id']] if boot==2 else []) if offline else [v['id'] for k,v in result['sources'].items() if boot!=1 or k!='D']+([v['id'] for v in result['sources'].values()] if boot==1 else [])
        check(sorted(r['data']['source']['id'] for r in replays)==sorted(expected_ids),'Canonical replay source coverage mismatch')
        rejected=[r for r in observations if r['kind']=='MEMORY_CHOICE_REJECTED']
        expected_rejected=[] if offline or boot==1 else [value['id'] for pair,value in result['instances'].items() if boot==2 or pair.startswith('A:')]
        check(sorted(r['data']['instance'] for r in rejected)==sorted(expected_rejected) and all('IllegalStateException' in r['data']['error'] for r in rejected),'Missing completed library choice rejection')
        for row in rejected:
            instance=next(i for i in result['instances'].values() if i['id']==row['data']['instance'])
            check(instance['scopeKey']=='player:'+manifest['players'][row['data']['who']],'Choice replay actor mismatch')
        if rejected:
            complete=[r for r in observations if r['kind']=='MEMORY_CHECK' and r['data']['phase']=='complete']
            check(len(complete)==2 and all(instant(complete[0]['time'])<=instant(r['time'])<=instant(complete[1]['time']) for r in rejected),'Choice replay was not bracketed by completed state')
    check(EPIC.digest(run/'plugins/StarChronicle/chronicle.db')==EPIC.digest(evidence/f'checkpoints/boot{boots}/chronicle.db'),'Final memory database differs from stopped checkpoint')
    return dict(status='PASS',mode=meta['mode'],chronicle_sha256=manifest['plugin_sha256'],starcore_sha256=manifest['starcore_sha256'],boots=boots,normal_exits=boots*2,actual_walks=len(walks),gui_choices=len(all_clicks),memory_entries=len(result['entries']),library_instances=len(result['instances']),api_checkpoints=len(checks),optional_vendor_telemetry_failures=telemetry)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('run',type=Path);args=parser.parse_args()
    result=inspect(args.run);(args.run/'evidence/memory-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False))
