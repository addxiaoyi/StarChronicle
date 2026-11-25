"""Verify live contribution queries and their exact persisted native aid sources."""
from pathlib import Path
from contextlib import closing
from decimal import Decimal
import argparse
import importlib.util
import json

SPEC = importlib.util.spec_from_file_location('epic_inspector', Path(__file__).with_name('inspect-epic-paper.py'))
EPIC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EPIC)
check = EPIC.check


def contributions(run, boots=(1, 2, 3)):
    manifest = EPIC.read(run / 'preparation.json')
    with closing(EPIC.connect(run / 'plugins/StarChronicle/chronicle.db')) as db:
        markers = list(db.execute('select * from epic_contribution_projections'))
        entries = [dict(row) for row in db.execute('select * from contributions order by id')]
        check(len(markers) == 4 and len(entries) == 8, 'Missing or duplicate contribution projections')
        aid = [json.loads(row[0]) for row in db.execute("select receipt_json from epic_operations where json_extract(receipt_json,'$.type') in ('RESOURCE_AID','TREASURY_AID')")]
        check({m['operation_id'] for m in markers} == {r['operationId'] for r in aid}, 'Contribution marker has an unrelated operation')
        expected_entries = {}
        for receipt in aid:
            marker = next(m for m in markers if m['operation_id'] == receipt['operationId'])
            event = json.loads(db.execute('select json from events where id=?', (marker['source_event_id'],)).fetchone()[0])
            check(event['participants'] == [receipt['actorId']] and set(event['nations']) == {receipt['nationId'], receipt['target']}, 'Contribution history attribution mismatch')
            check(event['occurredAt'] == receipt['appliedAt'] and event['source'] == receipt['issuer'], 'Contribution history source/time mismatch')
            check(event['facts']['time_basis'] == 'COMMITTED_SOURCE', 'Contribution must retain committed source time')
            if receipt['type'] == 'RESOURCE_AID':
                check(event['type'] == 'NATIONAL_RESOURCE_AID_COMMITTED', 'Wrong resource contribution event')
                check(event['facts']['resource.authority_domain'] == receipt['authorityDomain'] and event['facts']['resource.native_receipt'] == receipt['nativeReceiptId'], 'Contribution source receipt mismatch')
                check(json.loads(event['facts']['resource.quantities']) == {'agricultural': 10}, 'Contribution history quantities mismatch')
                unit, amount = 'resource.agricultural', Decimal(10)
            else:
                check(event['type'] == 'NATIONAL_AID_COMMITTED', 'Wrong treasury contribution event')
                check(event['facts']['aid.authority_domain'] == receipt['authorityDomain'] and event['facts']['aid.transaction'] == receipt['nativeReceiptId'], 'Contribution treasury receipt mismatch')
                unit, amount = 'treasury', Decimal(receipt['nativeReference'])
            linked = [entry for entry in entries if entry['source_event_id'] == event['id']]
            check(len(linked) == 2 and {e['subject_id'] for e in linked} == {'player:' + receipt['actorId'], 'nation:' + receipt['nationId']}, 'Contribution subjects differ from native aid')
            for entry in linked:
                request = json.loads(entry['request_json'], parse_float=Decimal)
                for column, key in [('operation_id','operationId'), ('subject_id','subjectId'), ('source_event_id','sourceEventId'),
                                    ('actor_id','actorId'), ('nation_id','nationId'), ('type','type'), ('unit','unit'),
                                    ('committed_at','committedAt'), ('evidence','evidence')]:
                    check(entry[column] == request[key], 'Contribution request/index mismatch')
                check(request['unit'] == unit and Decimal(str(request['amount'])) == Decimal(entry['amount']) == amount, 'Contribution amount/unit differs from native receipt')
                check(request['actorId'] == receipt['actorId'] and request['nationId'] == receipt['nationId'], 'Contribution actor/nation differs from native receipt')
                check(request['type'] == 'RESOURCE_AID' and request['committedAt'] == receipt['appliedAt'], 'Contribution type/time mismatch')
                expected_entries[entry['id']] = {'id': entry['id'], 'request': request, 'recordedAt': entry['recorded_at']}
        check(len(expected_entries) == 8, 'Contribution source rows are missing')
        check(db.execute("select count(*) from events where type in ('NATIONAL_RESOURCE_AID_COMMITTED','NATIONAL_AID_COMMITTED')").fetchone()[0] == 4, 'Duplicate or missing aid history')
    fixture = [json.loads(line, parse_float=Decimal) for line in (run / 'evidence/epic-fixture.jsonl').read_text().splitlines()]
    for boot in boots:
        rows = [r for r in fixture if r['boot'] == boot and r['kind'] == 'CONTRIBUTIONS_PASS']
        check(len(rows) == 1, 'Missing live contribution API check')
        actual = {entry['id']: entry for entry in rows[0]['data']['entries']}
        check(len(rows[0]['data']['entries']) == 8 and actual == expected_entries, 'Live contribution query differs from persisted evidence')
        clients = [json.loads(line) for line in (run / f'evidence/boot{boot}.clients.log').read_text().splitlines() if line.startswith('{')]
        queries = [row for row in clients if row['kind'] == 'CONTRIBUTIONS_QUERY']
        check(len(queries) == 2 and {q['data']['who'] for q in queries} == set('AB'), 'Missing real client contribution query')
        check(all(q['data']['command'] == '/chronicle contributions' and '10 国库货币' in q['data']['response'] and '10 资源 agricultural' in q['data']['response'] for q in queries), 'Unexpected contribution command result')
    return dict(contribution_entries=8, aid_source_events=4, contribution_query_boots=list(boots),
                subjects=['player:' + manifest['players'][who] for who in 'AB'])


def inspect(run, *, scenarios=('first','second','third'), quest_binding=None):
    boots=tuple(i for i,phase in enumerate(scenarios,1) if phase!='quest-first')
    return dict(EPIC.inspect(run,scenarios=scenarios,quest_binding=quest_binding), **contributions(run,boots))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    args = parser.parse_args()
    result = inspect(args.run.resolve())
    (args.run / 'evidence/contribution-check.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
