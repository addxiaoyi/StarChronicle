"""Independently reconcile real treasury wait, restart, replay and stopped SQL evidence."""
from contextlib import closing
from pathlib import Path
from decimal import Decimal
import argparse
import importlib.util
import json
import re
import socket
import uuid

SPEC = importlib.util.spec_from_file_location('epic', Path(__file__).with_name('inspect-epic-paper.py'))
E = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(E)
check, read, sha = E.check, E.read, E.digest
SCENARIOS = ['treasury-first', 'treasury-second', 'treasury-third']


def records(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.startswith('{')]


def inspect_negative(run, accepted):
    """Require the same fixture sources to reproduce rejection on the previous frozen native JAR."""
    m = read(run / 'preparation.json')
    fixed = read(accepted / 'preparation.json')
    check(m['starcore_sha256'] == 'b06b317ffe1af77162da42e301851abc9de6ccdc4aa88e2e7da546ab8f8ced3b', 'Wrong unfixed native artifact')
    check(m['plugin_sha256'] == fixed['plugin_sha256'] and m['fixture_source_sha256'] == fixed['fixture_source_sha256']
          and m['fixture_extra_sources_sha256'] == fixed['fixture_extra_sources_sha256'], 'Red/green source or Chronicle drift')
    for plugin, key in [('StarCore', 'starcore_sha256'), ('StarChronicle', 'plugin_sha256'), ('EpicCampaignPaperFixture', 'fixture_sha256')]:
        check(sha(run / f'plugins/{plugin}.jar') == m[key], 'Unfixed runtime artifact changed')
    for name, digest in m['fixture_extra_sources_sha256'].items():
        check(sha(run / 'fixture' / name) == digest, 'Unfixed fixture source changed')
    session = read(run / 'evidence/boot1.session.json')
    check(session['status'] == 'FAILED' and session['scenario'] == 'treasury-first'
          and len(session['processes']) == 2 and all(p['exit_code'] == 0 for p in session['processes']), 'Missing retained normal-stop red failure')
    rows = records(run / 'evidence/epic-fixture.jsonl')
    seeded = [r['data'] for r in rows if r['kind'] == 'SEEDED']
    errors = [r['data'] for r in rows if r['kind'] == 'FAILED']
    check(len(seeded) == len(errors) == 1 and errors[0]['action'] == 'treasury-mutate'
          and 'Treasury deposit was rejected' in errors[0]['error'], 'Wrong unfixed failure point')
    check(not any(r['kind'].startswith('TREASURY_') for r in rows), 'Unfixed deposit unexpectedly passed')
    log = (run / 'evidence/boot1.server.log').read_text()
    check('[SQLITE_BUSY]' in log and '[treasury] 同步保存失败:' in log and 'All dimensions are saved' in log,
          'Missing original SQLite contention failure')
    seed = {who: seeded[0][who] for who in 'ABC'}
    with socket.socket() as probe:
        check(probe.connect_ex(('127.0.0.1', m['server_port'])) != 0, 'Unfixed server still running')
    with closing(E.connect(run / 'plugins/StarCore/starcore.db')) as db:
        check(db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Unfixed SQL integrity failed')
        actual = dict(db.execute('SELECT property_key,property_value FROM starcore_treasury_state'))
        check(actual == expected(seed, {who: Decimal('1000000.00') for who in 'ABC'}, actual['authorityDomainId'], 5, {}),
              'Rejected unfixed deposit changed balance or receipt')
        check([r[0] for r in db.execute('SELECT operation FROM isolated_treasury_writer_fixture')] == ['deposit-first'],
              'Unfixed independent writer missing')
    return dict(status='EXPECTED_UNFIXED_SQLITE_BUSY_REPRODUCED', native_sha256=m['starcore_sha256'],
                fixture_sources_equal=True, normal_process_exits=2, rejected_deposit='12.50',
                balances_unchanged=True, transfer_receipts=0)


def expected(seed, balances, domain, revision, receipt):
    value = {'__starcore_revision__': str(revision), 'authorityDomainId': domain,
             'count': '3', 'credit.count': '0', 'spending.count': '0', 'transfer.count': str(bool(receipt) * 1)}
    for i, nation in enumerate(sorted(seed.values())):
        who = next(who for who, key in seed.items() if key == nation)
        value[f'treasury.{i}.nationId'] = nation
        value[f'treasury.{i}.balance'] = format(balances[who], '.2f')
    value.update(receipt)
    return value


def inspect(run):
    run = run.resolve()
    m = read(run / 'preparation.json')
    check(m['fixture_main_class'] == 'TreasuryWriterPaperFixture', 'Wrong treasury fixture')
    contract = m['treasury_writer_acceptance']
    check(contract == dict(scenarios=SCENARIOS, hold_millis=750,
                          table='isolated_treasury_writer_fixture', transfer_id='isolated-treasury-writer-transfer'),
          'Changed treasury scenario contract')
    with socket.socket() as probe:
        check(probe.connect_ex(('127.0.0.1', m['server_port'])) != 0, 'Treasury server still running')
    for plugin, key in [('StarCore', 'starcore_sha256'), ('StarChronicle', 'plugin_sha256'),
                        ('EpicCampaignPaperFixture', 'fixture_sha256')]:
        check(sha(run / f'plugins/{plugin}.jar') == m[key], 'Frozen treasury artifact changed')
    check(sha(run / 'fixture/EpicCampaignPaperFixture.java') == m['fixture_source_sha256'], 'Frozen base fixture changed')
    for name, digest in m['fixture_extra_sources_sha256'].items():
        check(sha(run / 'fixture' / name) == digest, 'Frozen treasury source changed')
    paper = [value for name, value in m['source_artifacts'].items() if Path(name).name == 'paper.jar']
    check(len(paper) == 1 and sha(run / 'paper.jar') == paper[0], 'Frozen Paper changed')
    for artifact in m['production_stack']['artifacts']:
        check(sha(run / 'plugins' / artifact['filename']) == artifact['sha256'], 'Frozen third-party plugin changed')
    rows = records(run / 'evidence/epic-fixture.jsonl')
    check(not any(r['kind'] == 'FAILED' for r in rows), 'Failed treasury fixture observation')
    seeds = [r['data'] for r in rows if r['kind'] == 'SEEDED']
    check(len(seeds) == 3 and seeds[0] == seeds[1] == seeds[2] and seeds[0]['testFunds'] and seeds[0]['nonOp'], 'Seed identity drift')
    seed = {who: seeds[0][who] for who in 'ABC'}
    check(len(set(seed.values())) == 3, 'Treasury needs three distinct nations')
    waits = [r['data'] for r in rows if r['kind'] == 'TREASURY_WAIT']
    check([v['operation'] for v in waits] == ['deposit-first', 'transfer', 'deposit-restored'], 'Missing treasury contention operation')
    domain = waits[0]['before']['authorityDomainId']
    check(str(uuid.UUID(domain)) == domain, 'Noncanonical treasury authority')
    revision = int(waits[0]['before']['__starcore_revision__'])
    check(revision >= 3, 'Initial seed revision missing')
    balances = {who: Decimal('1000000.00') for who in 'ABC'}
    receipt = {}
    state = expected(seed, balances, domain, revision, receipt)
    check(waits[0]['before'] == state, 'Initial treasury seed snapshot differs')
    previous_exit = 0
    plugins = None
    applied_ops = []
    elapsed = []
    replay_count = 0
    for boot, scenario in enumerate(SCENARIOS, 1):
        prefix = run / f'evidence/boot{boot}'
        session = read(Path(str(prefix) + '.session.json'))
        check(session['boot'] == boot and session['scenario'] == scenario and session['status'] == 'COMPLETE', 'Incomplete treasury boot')
        check(len(session['processes']) == 2 and {p['name'] for p in session['processes']} == {'server', 'clients'}, 'Missing treasury processes')
        check(all(p['exit_code'] == 0 and p['started_at'] < p['exited_at'] for p in session['processes']), 'Abnormal treasury process exit')
        server = next(p for p in session['processes'] if p['name'] == 'server')
        client = next(p for p in session['processes'] if p['name'] == 'clients')
        check(previous_exit < server['started_at'] < client['started_at'] < client['exited_at'] < server['exited_at'], 'Overlapping treasury lifecycle')
        previous_exit = server['exited_at']
        for key, suffix in [('controller', 'py'), ('clients', 'cjs')]:
            check(sha(Path(str(prefix) + f'.{key}.{suffix}')) == session['source_sha256'][key], 'Captured treasury controller changed')
        log = Path(str(prefix) + '.server.log').read_text()
        diagnostics = re.findall(r'^.*(?:ERROR\]|ERROR\]:|Failed to (?:enable|disable) STARCORE module|同步保存失败|异步保存失败|SQLITE_BUSY|startup failed|Could not pass event).*$', log, re.M | re.I)
        check(not [line for line in diagnostics if not E.vendor_telemetry(line)], 'Treasury persistence/lifecycle diagnostic')
        check(all(text in log for text in ('Done (', 'Stopping server', 'All dimensions are saved')), 'Missing treasury normal shutdown')
        clients = records(Path(str(prefix) + '.clients.log'))
        check(not any(r['kind'] == 'FAILED' for r in clients), 'Client failure')
        spawned = [r['data'] for r in clients if r['kind'] == 'SPAWN']
        check(len(spawned) == 4 and {v['who']: v['uuid'] for v in spawned} == m['players'], 'Missing actual client identity')
        observed = [r for r in rows if r['boot'] == boot]
        for kind in {r['kind'] for r in observed}:
            emitted = [json.loads(line.split('EPIC_' + kind + ' ', 1)[1]) for line in log.splitlines() if 'EPIC_' + kind + ' ' in line]
            check(emitted == [r['data'] for r in observed if r['kind'] == kind], 'Treasury server observation mismatch')
        stacks = [r['data'] for r in observed if r['kind'] == 'STACK_LOADED']
        check(len(stacks) == 1, 'Missing loaded treasury stack')
        stack = stacks[0]
        check(set(stack['plugins']) == {'StarCore', 'StarChronicle', 'Essentials', 'Vault', 'FancyNpcs', 'WorldEdit', 'WorldGuard'}
              and stack['businessEconomy'] == 'dev.starcore.starcore.foundation.economy.PlayerVaultEconomyService', 'Wrong selected stack')
        if plugins is not None: check(stack == plugins, 'Stack changed across treasury boots')
        plugins = stack
        sequence = [r['kind'].removeprefix('TREASURY_') for r in observed if r['kind'].startswith('TREASURY_')]
        check(sequence == {1: ['WAIT', 'WAIT', 'CHECK', 'REPLAY', 'CHECK'],
                           2: ['CHECK', 'REPLAY', 'WAIT', 'CHECK', 'REPLAY', 'CHECK'],
                           3: ['CHECK', 'REPLAY', 'CHECK']}[boot], 'Missing treasury restart/replay boundary')
        for row in observed:
            v = row['data']
            if row['kind'] == 'TREASURY_WAIT':
                check(v['before'] == state and v['ownerThread'] is True and v['holdMillis'] == 750, 'Treasury before snapshot/owner differs')
                check(750 <= v['writerReleasedAfterMillis'] <= v['elapsedMillis'] < 10000 and v['returnedAfterRelease'] is True,
                      'Treasury did not wait for writer release')
                operation = v['operation']
                if operation == 'deposit-first': balances['A'] += Decimal('12.50')
                elif operation == 'deposit-restored': balances['C'] += Decimal('2.75')
                else:
                    balances['A'] -= Decimal('7.25'); balances['B'] += Decimal('7.25')
                    applied = v['after']['transfer.0.appliedAt']
                    check(server['started_at'] * 1000 <= int(applied) <= server['exited_at'] * 1000, 'Transfer time outside live process')
                    receipt = {'transfer.0.' + k: value for k, value in dict(transactionId=contract['transfer_id'],
                        payerId=seed['A'], receiverId=seed['B'], amount='7.25', category='OTHER',
                        description='Explicit isolated writer contention acceptance', appliedAt=applied).items()}
                check(v['result'] == ('APPLIED' if operation == 'transfer' else 'DEPOSITED'), 'Treasury mutation was rejected')
                revision += 1
                state = expected(seed, balances, domain, revision, receipt)
                check(v['after'] == state, 'Treasury atomic balance/receipt delta differs')
                applied_ops.append(operation); elapsed.append(v['elapsedMillis'])
            elif row['kind'] == 'TREASURY_CHECK':
                check(v['snapshot'] == state and v['authority'] == domain and v['balances'] == {k: format(n, '.2f') for k, n in balances.items()}, 'Live treasury balance differs')
                r = v['receipt']
                check(r['transactionId'] == contract['transfer_id'] and r['payerId'] == {'value': seed['A']}
                      and r['receiverId'] == {'value': seed['B']} and Decimal(str(r['amount'])) == Decimal('7.25')
                      and r['category'] == 'OTHER' and r['description'] == receipt['transfer.0.description']
                      and E.QUEST.millis(r['appliedAt']) == int(receipt['transfer.0.appliedAt']), 'Original transfer receipt changed')
            elif row['kind'] == 'TREASURY_REPLAY':
                check(v['status'] == 'ALREADY_APPLIED' and v['snapshot'] == state, 'Replay changed treasury state')
                replay_count += 1
        # The native module performs one final state flush during normal disable.
        revision += 1
        state = expected(seed, balances, domain, revision, receipt)
        cp = run / f'evidence/checkpoints/boot{boot}'
        inventory = read(cp / 'checkpoint.json')
        check(inventory['boot'] == boot and set(inventory['sha256']) == {'starcore.db', 'chronicle.db'}
              and inventory['time'] >= server['exited_at'], 'Missing stopped treasury checkpoint boundary')
        for filename, digest in inventory['sha256'].items():
            check(sha(cp / filename) == digest, 'Treasury checkpoint hash differs')
            with closing(E.connect(cp / filename)) as db:
                check(db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'Treasury checkpoint integrity failure')
                if filename == 'starcore.db':
                    check(dict(db.execute('SELECT property_key,property_value FROM starcore_treasury_state')) == state,
                          'Stopped treasury SQL snapshot differs')
                    check(sorted(r[0] for r in db.execute('SELECT operation FROM isolated_treasury_writer_fixture')) == sorted(applied_ops),
                          'Missing committed unrelated writer')
                else:
                    check(db.execute('SELECT COUNT(*) FROM contributions').fetchone()[0] == 0, 'Fixture deposit invented contributions')
            if boot == 3:
                check(sha(run / 'plugins' / ('StarCore' if filename == 'starcore.db' else 'StarChronicle') / filename) == digest,
                      'Final treasury database differs')
    return dict(status='TREASURY_SQLITE_WRITER_ACCEPTED', chronicle_sha256=m['plugin_sha256'], starcore_sha256=m['starcore_sha256'],
                boots=3, normal_process_exits=6, waits=3, wait_millis=elapsed, replay_checks=replay_count,
                balance_by_actor={k: format(n, '.2f') for k, n in balances.items()}, transfer_receipts=1,
                plugins=plugins['plugins'], limitation='Explicit fixture funds; public services on Paper owner thread. No crash, Folia, external wallet atomicity or throughput claim.')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('run', type=Path)
    print(json.dumps(inspect(p.parse_args().run), ensure_ascii=False, indent=2))
