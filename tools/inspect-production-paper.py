"""Independently cross-check a stopped three-boot production plugin acceptance run."""
from contextlib import closing
from decimal import Decimal
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import socket
import zipfile
import yaml

SPEC = importlib.util.spec_from_file_location('trade_inspector', Path(__file__).with_name('inspect-trade-paper.py'))
TRADE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(TRADE)
EPIC, check, records, instant, normalized, stable = TRADE.EPIC, TRADE.check, TRADE.records, TRADE.instant, TRADE.normalized, TRADE.stable
BOUND = '00000000-0000-4000-8000-000000000701'
UNBOUND = '00000000-0000-4000-8000-000000000702'


def one(rows, kind):
    found = [row for row in rows if row['kind'] == kind]
    check(len(found) == 1, 'Missing or duplicated live observation: ' + kind)
    return found[0]['data']


def config(run, name):
    return yaml.safe_load((run / name).read_text(encoding='utf-8'))


def inspect(run):
    run = run.resolve(); manifest = EPIC.read(run / 'preparation.json')
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); probe.bind(('127.0.0.1', manifest['server_port']))
    for name, key in [('StarCore', 'starcore_sha256'), ('StarChronicle', 'plugin_sha256'), ('EpicCampaignPaperFixture', 'fixture_sha256')]:
        check(EPIC.digest(run / f'plugins/{name}.jar') == manifest[key], 'Frozen JAR changed')
    check(EPIC.digest(run / 'fixture/EpicCampaignPaperFixture.java') == manifest['fixture_source_sha256'], 'Frozen fixture source changed')
    for name, expected in manifest['fixture_extra_sources_sha256'].items():
        check(EPIC.digest(run / 'fixture' / name) == expected, 'Frozen production fixture source changed')
    for artifact in manifest['production_stack']['artifacts']:
        check(EPIC.digest(run / 'plugins' / artifact['filename']) == artifact['sha256'], 'Frozen third-party JAR changed')
    check(len(manifest['production_stack']['artifacts']) == 5, 'Incomplete frozen third-party stack')
    paper_hashes = {value for name, value in manifest['source_artifacts'].items() if Path(name).name == 'paper.jar'}
    check(len(paper_hashes) == 1 and EPIC.digest(run / 'paper.jar') in paper_hashes, 'Frozen Paper runtime changed')
    expected_versions = {}
    for path in (run / 'plugins').glob('*.jar'):
        if path.name == 'EpicCampaignPaperFixture.jar': continue
        with zipfile.ZipFile(path) as archive:
            descriptor = yaml.safe_load(archive.read('paper-plugin.yml' if 'paper-plugin.yml' in archive.namelist() else 'plugin.yml'))
        expected_versions[descriptor['name']] = str(descriptor['version'])
    core_config = config(run, 'plugins/StarCore/config.yml')
    check(core_config['economy']['provider'] == 'vault' and core_config['integrations']['worldguard']['enabled'] is True, 'Required production integration config changed')
    check(config(run, 'plugins/StarChronicle/config.yml')['npc-hooks'] is True, 'NPC bridge disabled')
    bindings = config(run, 'plugins/StarChronicle/npcs.yml')['profiles']['ancient-heir']['bindings']
    check(bindings == [dict(provider='fancynpcs', externalId=BOUND)], 'Logical NPC binding changed')
    evidence = run / 'evidence'; fixture = records(evidence / 'epic-fixture.jsonl')
    check(not any(r['kind'] == 'FAILED' for r in fixture), 'Fixture failure retained in run')
    check({r['boot'] for r in fixture} == {1, 2, 3}, 'Exactly three fixture boots required')
    check(len(list(evidence.glob('boot*.session.json'))) == 3, 'Exactly three completed controller sessions required')
    seeded = one([r for r in fixture if r['boot'] == 1], 'SEEDED')
    plugin_versions = None; runtime_sources = None; claims = None; final_history = None; lifetimes = []; telemetry = []
    for boot, scenario in enumerate(('stack-first', 'stack-second', 'stack-third'), 1):
        session = EPIC.read(evidence / f'boot{boot}.session.json')
        check(session['boot'] == boot and session['scenario'] == scenario and session['status'] == 'COMPLETE', 'Incomplete controller scenario')
        check(session.get('source_sha256') == {
            'controller': EPIC.digest(evidence / f'boot{boot}.controller.py'),
            'clients': EPIC.digest(evidence / f'boot{boot}.clients.cjs'),
        }, 'Frozen controller/client source changed')
        if runtime_sources is None: runtime_sources = session['source_sha256']
        check(session['source_sha256'] == runtime_sources, 'Controller/client source changed between boots')
        processes = session['processes']
        check(len(processes) == 2 and {p['name'] for p in processes} == {'server', 'clients'}, 'Missing actual server/client processes')
        check(all(p.get('exit_code') == 0 and p['exited_at'] >= p['started_at'] for p in processes), 'Abnormal process exit')
        server_process = next(p for p in processes if p['name'] == 'server'); lifetimes.append(server_process)
        rows = [r for r in fixture if r['boot'] == boot]
        check(one(rows, 'READY') == dict(product=manifest['plugin_sha256'], native=manifest['starcore_sha256'], route=manifest['route']), 'Running artifact mismatch')
        check(one(rows, 'SEEDED') == seeded and seeded['nonOp'] is True, 'Native identity/permissions changed')
        check(one(rows, 'CLOSED') == dict(normalDisable=True), 'Abnormal fixture shutdown')
        check(all(server_process['started_at'] <= instant(r['time']) <= server_process['exited_at'] for r in rows), 'Fixture observation outside actual process lifetime')
        server = (evidence / f'boot{boot}.server.log').read_text(encoding='utf-8')
        diagnostics = re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event|Committed history source .* retained|History scan retained pending evidence).*$', server, re.M | re.I)
        # Report the optional vendor telemetry endpoint separately; never suppress plugin/API or persistence failures.
        vendor_telemetry = [line for line in diagnostics if re.search(r'\[FancyAnalytics\] Failed to create record \(status 5\d\d\):', line)]
        telemetry.extend(dict(boot=boot, message=line) for line in vendor_telemetry)
        check(not [line for line in diagnostics if line not in vendor_telemetry], 'Server persistence/lifecycle diagnostic: ' + '\n'.join(diagnostics))
        check(all(word in server for word in ('Done (', 'Stopping server', 'All dimensions are saved')), 'Incomplete startup/shutdown')
        for kind in {r['kind'] for r in rows}:
            logged = [json.loads(line.split('EPIC_' + kind + ' ', 1)[1]) for line in server.splitlines() if 'EPIC_' + kind + ' ' in line]
            check(logged == [r['data'] for r in rows if r['kind'] == kind], 'Live fixture evidence absent from server stream: ' + kind)
        loaded = one(rows, 'STACK_LOADED')
        if plugin_versions is None: plugin_versions = loaded['plugins']
        check(loaded['plugins'] == expected_versions, 'Runtime versions differ from frozen plugin descriptors')
        check(loaded['plugins'] == plugin_versions and set(plugin_versions) == {'StarCore','StarChronicle','Essentials','Vault','FancyNpcs','WorldEdit','WorldGuard'}, 'Actual loaded plugin versions changed')
        check(loaded['businessEconomy'] == 'dev.starcore.starcore.foundation.economy.PlayerVaultEconomyService', 'Wrong selected business economy')
        check(any(s['bridgeName'] == 'WorldGuard' and s['active'] is True for s in loaded['protection']), 'WorldGuard bridge not active')
        phase = 'first' if boot == 1 else 'restore'
        check(one(rows, 'STACK_ECONOMY') == dict(balances={'A':'87.50','B':'32.50'}, phase=phase, provider='Essentials', testFunds=True), 'External balance or restart observation changed')
        protection = one(rows, 'STACK_PROTECTION_OBSERVED')
        check(protection['allowed'] is True and protection['denied'] is True and protection['region'] == 'isolated_starcore_protected', 'Actual WorldGuard intersection missing')
        claim = one(rows, 'STACK_CLAIMS'); check(claim['phase'] == phase, 'Claim restart phase mismatch')
        stable_claim = {k:v for k,v in claim.items() if k != 'phase'}
        if claims is None: claims = stable_claim
        check(stable_claim == claims and claim['denied'] and claim['protectedOwnerAbsent'], 'Claim owner or external funds changed after restart')
        check(Decimal(claim['price']) > 0 and Decimal(claim['balance']) == Decimal('10000') - Decimal(claim['price']), 'Claim fee was not charged exactly')
        payments = [row for row in rows if row['kind'] == 'STACK_CLAIM_PAYMENT']
        check(len(payments) == (1 if boot == 1 else 0), 'Missing or repeated claim payment observation')
        if boot == 1:
            payment = {key: Decimal(value) for key, value in payments[0]['data'].items()}
            check(payment == dict(before=Decimal('10000'), fee=Decimal(claim['price']),
                                  after=Decimal(claim['balance']), essentials=Decimal(claim['balance'])),
                  'Live exact Essentials/native claim payment mismatch')
        check(claim['owner'] == seeded['C'] and (claim['claimedX'],claim['claimedZ']) == (17,16), 'Native claim actor/location mismatch')
        check(one(rows,'STACK_GEOMETRY') == dict(tinyHighRegion=True, polygonNotBoundingBox=True, negativeBoundary=True, buildAllowReserved=True, globalReserved=True), 'Missing actual geometry/flag observations')
        check(one(rows,'STACK_NPC_READY') == dict(loaded=True), 'External NPC data was not ready')
        npcs = one(rows,'STACK_NPCS')
        check(npcs['phase'] == phase and npcs['bound'] == BOUND and npcs['unbound'] == UNBOUND and npcs['count'] == 2, 'External NPC identities or count changed')
        clients = records(evidence / f'boot{boot}.clients.log')
        check(not any(r['kind'] == 'FAILED' for r in clients), 'Protocol client failure')
        spawns = [r['data'] for r in clients if r['kind'] == 'SPAWN']
        check(len(spawns) == 4 and {s['who'] for s in spawns} == set('ABCD') and all(s['uuid'] == manifest['players'][s['who']] for s in spawns), 'Missing actual client identities')
        clicks = [r for r in clients if r['kind'] == 'NPC_CLICK']
        check(len(clicks) == 3 and [r['data']['entityId'] for r in clicks] == [npcs['boundEntity'],npcs['unboundEntity'],npcs['boundEntity']], 'Missing real NPC client clicks')
        check(all(r['data']['who'] == 'A' and r['data']['syntheticEvents'] is False and r['data']['distance'] <= 4 for r in clicks), 'Invalid actual NPC click provenance')
        events = [r for r in rows if r['kind'] == 'STACK_NPC_EVENT']
        check(len(events) == 3 and [r['data']['id'] for r in events] == [BOUND,UNBOUND,BOUND] and [r['data']['cancelled'] for r in events] == [False,False,True], 'Missing bound/unbound/cancelled native NPC events')
        check(all(r['data']['player'] == manifest['players']['A'] for r in events), 'Wrong actual NPC actor')
        history = [r for r in rows if r['kind'] == 'STACK_NPC_HISTORY']
        check([(r['data']['expected'], r['data']['observedEvents']) for r in history] == [(boot-1,0),(boot,1),(boot,2),(boot,3)], 'Missing live NPC history checkpoints')
        expected_previous = [] if final_history is None else final_history
        check(sorted((normalized(e) for e in history[0]['data']['history']),key=lambda e:e['id']) == sorted(expected_previous,key=lambda e:e['id']), 'NPC history changed across restart')
        first = [normalized(e) for e in history[1]['data']['history']]
        check(len(first) == boot and all(e['source'] == 'NPC_BRIDGE' and e['participants'] == [manifest['players']['A']] and e['nations'] == [seeded['A']] and e['facts'] == {'npc.id':'ancient-heir','npc.binding':'fancynpcs:'+BOUND} for e in first), 'NPC event source/actor/binding mismatch')
        for record in history[2:]:
            check(sorted((normalized(e) for e in record['data']['history']),key=lambda e:e['id']) == sorted(first,key=lambda e:e['id']), 'Unbound or cancelled interaction entered history')
        for index in range(3):
            check(instant(history[index]['time']) < instant(clicks[index]['time']) < instant(history[index+1]['time']), 'NPC client/history causal order changed')
            check(instant(history[index]['time']) < instant(events[index]['time']) < instant(history[index+1]['time']), 'Native NPC event/history causal order changed')
        additions = [e for e in first if e['id'] not in {old['id'] for old in expected_previous}]
        check(len(additions)==1 and instant(history[0]['time']) < instant(additions[0]['occurredAt']) < instant(history[1]['time']), 'NPC history commit was not tied to the actual interaction')
        final_history = first
    check(all(a['exited_at'] < b['started_at'] for a,b in zip(lifetimes,lifetimes[1:])), 'Server boots overlap')
    faults = one(fixture,'STACK_FAULTS')
    check(faults['actualWorldGuardManagerUnload'] and faults['protectionRecovered'] and faults['removedExternalProviders'] > 0 and faults['transferRejected'], 'Missing real provider failure checks')
    check({k:Decimal(v) for k,v in faults['externalBalances'].items()} == {'A':Decimal('87.50'),'B':Decimal('32.50')}, 'Provider removal changed external funds')
    for who, expected in {'A':Decimal('87.50'),'B':Decimal('32.50'),'C':Decimal(claims['balance'])}.items():
        data = config(run, f"plugins/Essentials/userdata/{manifest['players'][who]}.yml")
        check(Decimal(str(data['money'])) == expected, 'Persisted Essentials balance mismatch: '+who)
    regions = config(run,'plugins/WorldGuard/worlds/world/regions.yml')['regions']
    region = regions.get('isolated_starcore_protected')
    check(region is not None and region['min'] == dict(x=257,y=180,z=257) and region['max'] == dict(x=258,y=181,z=258) and region['flags']['build'] == 'deny', 'Persisted WorldGuard region mismatch')
    check(set(regions) == {'isolated_starcore_protected'}, 'Temporary geometry probes were not removed')
    stored_npcs = config(run, 'plugins/FancyNpcs/npcs.yml')['npcs']
    check(set(stored_npcs) == {BOUND, UNBOUND}, 'Persisted FancyNpcs identities/count mismatch')
    for npc_id, name, x, z in ((BOUND, 'isolated_bound', 6.5, 4.5), (UNBOUND, 'isolated_unbound', 4.5, 6.5)):
        npc = stored_npcs[npc_id]
        check(npc['name'] == name and npc['creator'] == manifest['players']['A'] and npc['type'] == 'VILLAGER'
              and npc['spawnEntity'] is True and npc['location'] == dict(world=claims['world'], x=x, y=-60.0, z=z, yaw=0.0, pitch=0.0),
              'Persisted FancyNpcs entity provenance/location mismatch')
    with closing(EPIC.connect(run / 'plugins/StarCore/starcore.db')) as db:
        claims_rows = [dict(r) for r in db.execute('select * from starcore_territory_claims')]
        check(claims_rows == [dict(world_name=claims['world'], chunk_x=17, chunk_z=16, owner_id=seeded['C'])], 'Persisted native claim ownership mismatch')
    with closing(EPIC.connect(run / 'plugins/StarChronicle/chronicle.db')) as db:
        persisted = [dict(r) for r in db.execute("select * from events where type='NPC_INTERACT' order by id")]
        check(len(persisted) == 3, 'Expected exactly three persisted NPC interactions')
        for row, expected in zip(persisted,sorted(final_history,key=lambda e:e['id'])):
            check(normalized(json.loads(row['json'])) == expected and row['id'] == expected['id'] and instant(row['time']) == instant(expected['occurredAt']), 'Persisted NPC event differs from live history')
            canonical = dict(id=expected['id'],type='NPC_INTERACT',time=expected['occurredAt'],players=expected['participants'],nations=expected['nations'],facts=expected['facts'],source=expected['source'],evidence=expected['evidence'])
            check(row['hash'] == stable(json.dumps(canonical,ensure_ascii=False,separators=(',',':'),sort_keys=True)), 'NPC canonical event hash mismatch')
            check([r[0] for r in db.execute('select player from event_players where event_id=?',(row['id'],))] == [manifest['players']['A']], 'NPC participant index mismatch')
            check([r[0] for r in db.execute('select nation from event_nations where event_id=?',(row['id'],))] == [seeded['A']], 'NPC nation index mismatch')
        check(db.execute('select count(*) from contributions').fetchone()[0] == 0, 'NPC interaction must not fabricate contribution credit')
    return dict(status='PASS', chronicle_sha256=manifest['plugin_sha256'], starcore_sha256=manifest['starcore_sha256'], plugin_versions=plugin_versions,
                verified_boots=3,normal_process_exits=6,actual_npc_clicks=9,npc_history_events=3,persisted_external_npcs=2,contributions=0,claims=claims,optional_vendor_telemetry_failures=telemetry,
                limitations=['Isolated Paper acceptance, not a production deployment or Folia result.', 'NPCs were explicitly created by the test fixture; Chronicle only handles interactions.', 'Claim billing and direct transfers use the selected external economy; legacy internal-economy business paths need separate review.', 'External economy/claim persistence is not a shared atomic transaction.'])


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('run',type=Path);args=parser.parse_args()
    result=inspect(args.run);(args.run/'evidence/production-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False))
