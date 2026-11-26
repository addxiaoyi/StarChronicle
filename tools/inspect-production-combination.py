"""Independently inspect stopped five-plugin Paper evidence and persisted provider state."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import sqlite3
import yaml

SPEC = importlib.util.spec_from_file_location('epic_inspector', Path(__file__).with_name('inspect-epic-paper.py'))
EPIC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EPIC)
NPC = 'chronicle-combination-witness'


def check(value, message):
    if not value:
        raise AssertionError(message)


def rows(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.startswith('{')]


def inspect(run):
    epic = EPIC.inspect(run)
    manifest = EPIC.read(run / 'preparation.json')
    check(manifest.get('combination_acceptance') and manifest.get('combination_maintenance_offline'), 'Combination requires controlled offline maintenance')
    artifacts = EPIC.read(run / 'combination.json')['artifacts']
    check(set(artifacts) == {'FancyNpcs', 'WorldGuard', 'WorldEdit', 'ProductionCombinationFixture'}, 'Incomplete combination artifacts')
    for name, entry in artifacts.items():
        check(EPIC.digest(run / f'plugins/{name}.jar') == entry['sha256'], 'Combination artifact mismatch: ' + name)
    check(EPIC.digest(run / 'fixture/ProductionCombinationFixture.java') == artifacts['ProductionCombinationFixture']['source_sha256'], 'Combination fixture drift')
    evidence = rows(run / 'evidence/combination-fixture.jsonl')
    check(not any(r['kind'] == 'FAILED' for r in evidence), 'Retained combination failure')
    check({r['boot'] for r in evidence} == {1, 2, 3}, 'Unexpected combination boot sequence')
    actor = manifest['players']['A']
    original_entities = None
    source = None
    foreign = None
    for boot in (1, 2, 3):
        selected = [r for r in evidence if r['boot'] == boot]
        for kind in ('READY', 'CLOSED', 'NPC_LOADED', 'SETUP'):
            check(sum(r['kind'] == kind for r in selected) == 1, 'Missing combination boot lifecycle: ' + kind)
        setup = next(r['data'] for r in selected if r['kind'] == 'SETUP')
        loaded = next(r['data'] for r in selected if r['kind'] == 'NPC_LOADED')
        check(loaded['count'] == (0 if boot == 1 else 1), 'Native NPC load was not observed before setup')
        check(setup['bridgeListeners'] == 1 and setup['nativeNpcCount'] == 1 and setup['bypass'] is False and setup['npc'] == NPC, 'Missing actual non-bypass provider setup')
        if source is None:
            source = setup['source']
        check(source == setup['source'], 'Memory source changed across restart')
        memories = [r['data'] for r in selected if r['kind'] == 'MEMORY']
        check(len(memories) == (2 if boot == 3 else 1), 'Missing memory restart/removal checks')
        current = memories[0]
        check(current['present'] and len(set(current['owned'])) == 2, 'Memory duplicates or missing entities')
        if original_entities is None:
            original_entities, foreign = current['owned'], current['foreign']
        check(current['owned'] == original_entities and current['foreign'] == foreign, 'Memory or foreign entity changed across restart')
        if boot == 3:
            check(memories[1] == dict(present=False, owned=[], foreign=foreign, npc=NPC), 'Removal touched foreign entity or left owned entities')
        packets = [r for r in selected if r['kind'] == 'NPC_PACKET']
        check([r['data']['cancelled'] for r in packets] == ([True, False] if boot == 1 else [False]), 'Missing cancelled/allowed real NPC packet sequence')
        check(all(r['data']['player'] == actor for r in packets), 'Wrong player interacted with NPC')
        counts = [r['data']['count'] for r in selected if r['kind'] == 'NPC_HISTORY']
        check(counts == [boot - 1, boot], 'NPC cancellation or restart history count mismatch')
        check(any(r['kind'] == 'MENU_OPEN' and r['time'] > packets[-1]['time'] for r in selected), 'Real NPC click did not open a menu')
        clients = rows(run / f'evidence/boot{boot}.clients.log')
        clicks = [r for r in clients if r['kind'] == 'NPC_CLICK']
        check(len(clicks) == len(packets) and all(r['data']['syntheticEvents'] is False and r['data']['who'] == 'A' for r in clicks), 'Missing real protocol NPC click')
        messages = [r['data']['message'] for r in clients if r['kind'] == 'MESSAGE' and r['data']['who'] == 'A']
        server = (run / f'evidence/boot{boot}.server.log').read_text()
        if boot == 1:
            check(sum('没有管理权限' in m for m in messages) == 1, 'Missing command permission rejection')
            check(sum('WorldGuard 不允许' in m for m in messages) == 2, 'Missing WorldGuard or boundary rejection')
            check(sum('纪念点已保存' in m for m in messages) == 1, 'Missing authorized placement')
            for site in ('combo-no-permission', 'combo-denied', 'combo-edge', 'combo-allowed'):
                check('/chronicle memory create ' + site + ' ' + source in server, 'Missing actual player create command')
        if boot == 3:
            maintenance = [r for r in selected if r['kind'] == 'MAINTENANCE_EMPTY']
            check(len(maintenance) == 1 and maintenance[0]['data']['online'] == 0, 'Missing offline maintenance boundary')
            check(sum(r['kind'] == 'SPAWN' and r['data']['who'] == 'A' for r in clients) == 2, 'Missing real reconnect after provider disable')
            check(any('纪念点已停用' in m for m in messages), 'Missing player removal')
            check(any('领地保护服务暂不可用' in m for m in messages), 'Disabled WorldGuard did not fail closed')
            check(any(r['kind'] == 'NPC_DISABLED' and r['data']['bridgeListeners'] == 0 for r in selected), 'Provider disable leaked bridge listener')
            check(any(r['kind'] == 'PROTECTION_DISABLED' for r in selected), 'Missing actual protection disable')
    with sqlite3.connect((run / 'plugins/StarChronicle/chronicle.db').as_uri() + '?mode=ro', uri=True) as db:
        sites = db.execute('SELECT id,event_id,json FROM memory_sites').fetchall()
        check(len(sites) == 1 and sites[0][0] == 'combo-allowed' and sites[0][1] == source, 'Denied placement persisted or allowed source changed')
        site = json.loads(sites[0][2])
        check(site['enabled'] is False and site['x'] == 30 and site['z'] == 0, 'Memory tombstone/position mismatch')
        committed = db.execute('SELECT json FROM events WHERE id=?', (source,)).fetchone()
        check(committed and json.loads(committed[0])['type'] == 'NATIONAL_RESOURCE_AID_COMMITTED', 'Memory has no actual committed aid source')
        events = [json.loads(r[0]) for r in db.execute("SELECT json FROM events WHERE type='NPC_INTERACT'")]
        check(len(events) == 3 and all(e['facts']['npc.binding'] == 'fancynpcs:' + NPC and e['facts']['npc.id'] == 'ancient-heir' and e['participants'] == [actor] for e in events), 'Persisted NPC history mismatch')
    npcs = yaml.safe_load((run / 'plugins/FancyNpcs/npcs.yml').read_text())['npcs']
    check(set(npcs) == {NPC} and npcs[NPC]['creator'] == actor and npcs[NPC]['location']['x'] == 30.5, 'Native NPC persistence changed')
    regions = yaml.safe_load((run / 'plugins/WorldGuard/worlds/world/regions.yml').read_text())['regions']
    check(regions['chronicle-owned']['owners']['unique-ids'] == [actor] and regions['chronicle-denied']['owners'] == {}, 'Native region authority mismatch')
    check(regions['chronicle-denied']['min']['x'] == 18 and regions['chronicle-owned']['min']['x'] == 28, 'Protection test boundary changed')
    result = dict(status='PASS', route=epic['route'], chronicle_sha256=epic['chronicle_sha256'], starcore_sha256=epic['starcore_sha256'],
                  artifacts=artifacts, verified_boots=3, real_npc_clicks=4, cancelled_npc_clicks=1, npc_history=3,
                  allowed_memory_sites=1, denied_memory_sites=4, persistent_owned_entities=2, native_npc_retained=True,
                  scope='Paper ' + epic['route'] + ' epic plus selected NPC interaction and memory creation protection; no Folia or production deployment')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    run = parser.parse_args().run.resolve()
    result = inspect(run)
    (run / 'evidence/combination-independent-check.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
