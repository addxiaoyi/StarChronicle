"""Check two real upgrade boots against an accepted, untouched pre-upgrade save."""
from pathlib import Path
from contextlib import ExitStack
import argparse
import importlib.util
import json

SPEC = importlib.util.spec_from_file_location('contributions', Path(__file__).with_name('inspect-contribution-paper.py'))
CONTRIBUTIONS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTRIBUTIONS)
EPIC = CONTRIBUTIONS.EPIC
check = EPIC.check


def rows(db, table):
    return sorted(tuple(row) for row in db.execute('SELECT * FROM "' + table + '"'))


def inspect(run):
    run = run.resolve()
    baseline = run / 'evidence/upgrade-baseline'
    source = EPIC.read(run / 'evidence/upgrade-source.json')
    for relative, sha in source['baseline_sha256'].items():
        check(EPIC.digest(baseline / relative) == sha, 'Baseline file changed: ' + relative)
    original = EPIC.inspect(baseline)
    check(original['chronicle_sha256'] == source['original']['chronicle_sha256'], 'Wrong upgrade baseline')
    current = EPIC.inspect(run, scenarios=('third', 'third'), first_boot=4)
    check(current['starcore_sha256'] == original['starcore_sha256'], 'Native artifact changed during upgrade')
    check(current['chronicle_sha256'] != original['chronicle_sha256'], 'Upgrade requires a new Chronicle artifact')
    with ExitStack() as stack:
        before = EPIC.connect(baseline / 'plugins/StarChronicle/chronicle.db'); stack.callback(before.close)
        after = EPIC.connect(run / 'plugins/StarChronicle/chronicle.db'); stack.callback(after.close)
        check(not rows(before, 'contributions'), 'Baseline already had contributions')
        tables = ('epic_operations', 'epic_operation_providers', 'epic_campaigns', 'epic_route_votes',
                  'epic_signups', 'epic_signup_seats', 'quest_gates', 'quest_gate_completions',
                  'instances', 'outcomes', 'story_nation_resolutions', 'aid_attempts')
        for table in tables:
            check(rows(before, table) == rows(after, table), 'Upgrade changed prior Chronicle state: ' + table)
        prior_events = {row['id']: tuple(row) for row in before.execute('select * from events')}
        new_events = {row['id']: tuple(row) for row in after.execute('select * from events')}
        check(all(new_events.get(key) == value for key, value in prior_events.items()), 'Upgrade changed prior history')
        added = set(new_events) - set(prior_events)
        check(len(added) == 4 and added == {r[0] for r in after.execute('select source_event_id from epic_contribution_projections')},
              'Upgrade must add exactly four canonical aid events')
        old_native = EPIC.connect(baseline / 'plugins/StarCore/starcore.db'); stack.callback(old_native.close)
        new_native = EPIC.connect(run / 'plugins/StarCore/starcore.db'); stack.callback(new_native.close)
        native_tables = ('starcore_resource_transfer_receipts', 'starcore_diplomacy_invite_receipts',
                         'starcore_diplomacy_acceptance_receipts', 'starcore_nation_founding_receipts',
                         'starcore_wars', 'starcore_war_state', 'starcore_resource_state')
        for table in native_tables:
            check(rows(old_native, table) == rows(new_native, table), 'Upgrade changed native receipts/balances: ' + table)
        old_treasury = dict(rows(old_native, 'starcore_treasury_state'))
        new_treasury = dict(rows(new_native, 'starcore_treasury_state'))
        # SqlTreasuryStateStorage.writeSnapshot increments its optimistic-lock
        # revision even for the identical snapshot flushed at each normal shutdown.
        revisions = [int(old_treasury.pop('__starcore_revision__')), int(new_treasury.pop('__starcore_revision__'))]
        check(revisions[1] == revisions[0] + 2, 'Unexpected treasury save revision across two upgrade boots')
        check(old_treasury == new_treasury, 'Upgrade changed native receipts/balances: starcore_treasury_state')
    for relative in ('plugins/StarCore/dungeon/completions.json', 'plugins/StarCore/dungeon/authority-domain.txt'):
        check(EPIC.digest(baseline / relative) == EPIC.digest(run / relative), 'Native dungeon evidence changed')
    contributions = CONTRIBUTIONS.contributions(run, boots=(4, 5))
    return dict(current, **contributions, baseline_chronicle_sha256=original['chronicle_sha256'],
                baseline_contributions=0, prior_chronicle_tables_unchanged=list(tables),
                native_tables_unchanged=list(native_tables), treasury_receipts_balances_unchanged=True,
                treasury_shutdown_revisions=revisions, added_history_events=4)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    args = parser.parse_args()
    result = inspect(args.run)
    (args.run / 'evidence/contribution-upgrade-check.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
