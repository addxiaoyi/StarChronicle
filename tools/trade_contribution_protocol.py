"""Read the frozen product's schema and match only the two intentional acceptance faults."""
import re
import struct
import zipfile


def artifact_schema(path):
    with zipfile.ZipFile(path) as jar:
        raw = jar.read('io/github/addxiaoyi/starchronicle/persistence/SqliteStore.class')
    position = 0

    def take(size):
        nonlocal position
        value = raw[position:position + size]
        if len(value) != size:
            raise ValueError('Truncated SQLite store class')
        position += size
        return value

    def number(size):
        return int.from_bytes(take(size), 'big')

    if take(4) != b'\xca\xfe\xba\xbe':
        raise ValueError('Invalid SQLite store class')
    take(4)  # class versions
    pool = [None] * number(2)
    i = 1
    while i < len(pool):
        tag = number(1)
        if tag == 1:
            pool[i] = take(number(2)).decode('utf-8')
        elif tag == 3:
            pool[i] = struct.unpack('>i', take(4))[0]
        elif tag in (5, 6):
            take(8); i += 1
        elif tag in (7, 8, 16, 19, 20):
            take(2)
        elif tag in (4, 9, 10, 11, 12, 17, 18):
            take(4)
        elif tag == 15:
            take(3)
        else:
            raise ValueError('Unknown class constant tag')
        i += 1
    take(6)
    take(2 * number(2))  # interfaces
    for _ in range(number(2)):
        access, name, descriptor, attributes = number(2), pool[number(2)], pool[number(2)], number(2)
        for _ in range(attributes):
            attribute, payload = pool[number(2)], take(number(4))
            if name == 'SCHEMA_VERSION' and descriptor == 'I' and access & 0x18 == 0x18 and attribute == 'ConstantValue':
                if len(payload) != 2:
                    raise ValueError('Invalid schema constant')
                schema = pool[int.from_bytes(payload, 'big')]
                if schema not in (8, 9):
                    raise ValueError('Unsupported trade contribution schema')
                return schema
    raise ValueError('Missing product schema constant')


def history_warning(line):
    return any(marker in line for marker in ('history scan retained its cursor:',
        'History scan retained pending evidence:', 'history retained its original ledger:'))


def injected_fault(line, schema):
    if schema not in (8, 9):
        raise ValueError('Unsupported trade contribution schema')
    for kind, detail in (('ingress', 'trade ingress'), ('revision', 'contribution revision')):
        prefix = 'Trade history retained its original ledger' if schema == 9 else (
            'Trade history scan retained its cursor' if kind == 'ingress' else 'History scan retained pending evidence')
        pattern = (r'\[[0-9:]+ WARN\]: \[StarChronicle\] ' + re.escape(prefix)
            + r': SQLiteException: \[SQLITE_CONSTRAINT_TRIGGER\] A RAISE function within a trigger fired, causing the SQL statement to abort '
            + r'\(acceptance injected ' + detail + r' failure\)\n?')
        if re.fullmatch(pattern, line):
            return kind
    return None
