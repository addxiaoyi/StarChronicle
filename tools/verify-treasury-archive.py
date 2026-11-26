"""Read-only verification of complete native treasury receipt archive and hash chain."""
import base64
import hashlib


def read_archive(database, state):
    if state.get('receipt-archive.version') != '1':
        raise ValueError('Expected archived treasury state')
    domain = state['authorityDomainId']
    chain = ''
    latest, originals = {}, {}
    rows = database.execute('SELECT archive_sequence,receipt_key,receipt_kind,transaction_id,refund_event,row_data,row_hash,chain_hash,authority_domain '
                            'FROM starcore_treasury_receipt_archive ORDER BY archive_sequence')
    count = 0
    for sequence, key, kind, identity, refund, text, row_hash, chain_hash, row_domain in rows:
        count += 1
        assert sequence == count and row_domain == domain
        assert kind in ('spending', 'credit', 'transfer') and refund in (0, 1)
        assert hashlib.sha256(f'{kind}:{identity}'.encode()).hexdigest() == key
        assert hashlib.sha256((domain + '\n' + text).encode()).hexdigest() == row_hash
        chain = hashlib.sha256(f'{chain}:{sequence}:{row_hash}'.encode()).hexdigest()
        assert chain == chain_hash
        fields = dict(line.split('=', 1) for line in text.splitlines())
        fields = {k: base64.b64decode(v, validate=True).decode() for k, v in fields.items()}
        assert text == ''.join(k + '=' + base64.b64encode(v.encode()).decode() + '\n' for k, v in sorted(fields.items()))
        prefix = kind + '.0.'
        assert fields[kind + '.count'] == '1' and fields['count'] == '0'
        for other in ('spending', 'credit', 'transfer'):
            if other != kind:
                assert fields[other + '.count'] == '0'
        receipt = {k[len(prefix):]: v for k, v in fields.items() if k.startswith(prefix)}
        assert receipt['transactionId'] == identity
        receipt_id = (kind, identity)
        if refund == 0:
            assert receipt_id not in originals
            originals[receipt_id] = receipt
        else:
            assert kind == 'spending' and receipt_id in originals
            assert originals[receipt_id]['refunded'] == 'false' and receipt['refunded'] == 'true'
            assert {k: v for k, v in receipt.items() if k != 'refunded'} == {k: v for k, v in originals[receipt_id].items() if k != 'refunded'}
            assert latest[receipt_id]['refunded'] == 'false'
        latest[receipt_id] = receipt
    assert count == int(state['receipt-archive.count']) and chain == state['receipt-archive.head']
    for kind in ('spending', 'credit', 'transfer'):
        assert state[kind + '.count'] == '0'
        assert not any(key.startswith(kind + '.') and key != kind + '.count' for key in state)
    return latest
