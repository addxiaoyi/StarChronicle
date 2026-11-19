"""Shared bounded data helpers for the isolated phase-three Paper acceptance tools.

Importing this file never starts a process or writes a database.
"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
OLD_PLUGIN_SHA = 'd765e0d14b9a9122e0ba1304c9c1b0d3ef844d4458295b1f4847ecb213bd943b'
DISCOVERY = 'phase-two:heir-discovery:1'
ENDING = 'chronicle:975377c7394a74dbaafc65237cf43cdccda38990fe1b2ef3f46b428f6464ebe7'
ARCHIVE = 'heir-legacy'
READER = 'phase-three-catalog-reader'
PLAYERS = {
    'A': '08ffd450-5001-3023-a480-057fef707037',
    'B': '28a86e57-337f-3ee8-8593-6eed0ce4d3d6',
    'C': '140669be-0a1a-3206-a8b1-20d23a596b3a',
    'D': '63e2bb34-acdd-360c-a4b5-57c748f2ef53',
}
NATIONS = {
    'A': 'd0ad16cb-6c42-41fd-9930-1e1b648c48da',
    'B': '25f77bd3-9110-44b5-a6c3-aee49ed19558',
    'C': 'b0bbe965-8d52-4d4b-9b71-9a01a7e58d54',
    'D': 'fd61755c-7d80-40cf-aa0b-5737c710c8f4',
}
OLD_CHOICES = {'A': 'testimony', 'B': None, 'C': 'witness', 'D': 'reconcile'}
BASE_HONOR = {'A': 17, 'B': 15, 'C': 17, 'D': 17}
RETIRED_REF = 'past.player.choice.heir-legacy/1/archive'


def now():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def stable(*parts):
    value = hashlib.sha256()
    for part in parts:
        raw = part.encode('utf-8')
        value.update(str(len(raw)).encode('ascii') + b':' + raw)
    return value.hexdigest()


def run_path(value):
    value = Path(value).resolve()
    if value.parent != (ROOT / 'run').resolve() or not re.fullmatch(r'phase-three-paper-[a-z0-9][a-z0-9-]{0,63}', value.name):
        raise SystemExit('Require one isolated run/phase-three-paper-* directory')
    return value


def manifest(run, expected):
    run = run_path(run)
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise SystemExit('Require an explicit complete expected product SHA-256')
    value = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
    if value['plugin_sha256'] != expected or digest(run / 'plugins/StarChronicle.jar') != expected:
        raise SystemExit('Refusing final product fingerprint drift')
    return value


def database(path):
    connection = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute('PRAGMA query_only=ON')
    return connection


def db_snapshot(path):
    with database(path) as connection:
        result = {'schema_version': connection.execute('PRAGMA user_version').fetchone()[0]}
        names = [row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
        for name in names:
            if not re.fullmatch(r'[A-Za-z0-9_]+', name):
                raise SystemExit('Unexpected SQLite table identifier')
            rows = [dict(row) for row in connection.execute('SELECT * FROM ' + name)]
            result[name] = sorted(rows, key=lambda row: json.dumps(row, ensure_ascii=False, sort_keys=True))
        result['integrity'] = connection.execute('PRAGMA integrity_check').fetchone()[0]
        result['foreign_key_violations'] = [list(row) for row in connection.execute('PRAGMA foreign_key_check')]
        return result


def source_unchanged(value):
    source = ROOT / value['source_run']
    return all((source / name).is_file() and digest(source / name) == fingerprint
               for name, fingerprint in value['source_preserved_sha256'].items())


def content():
    condition = {'fact': 'chronicle.history.player.HEIR_RESTORATION_SUPPORTED', 'op': 'gte', 'value': '1'}
    a2 = {
        'id': ARCHIVE, 'version': '2', 'title': '旧都档案·第二卷', 'scope': 'PLAYER',
        'triggers': ['HEIR_RESTORATION_SUPPORTED'], 'condition': condition,
        'start': 'new-record', 'cooldownSeconds': 0,
        'nodes': {
            'new-record': {'text': '这是现在编写的新卷。第一卷的真实选择仍按原版本保存；本次登记不冒充当年的资格。',
                           'choices': {'annotate': {'label': '为第二卷留下现在的批注', 'next': 'annotated'}}},
            'annotated': {'text': '第二卷批注已保存，第一卷未被覆盖。', 'terminal': True,
                          'effects': [
                              {'id': 'new-volume-honor', 'type': 'honor', 'target': 'player', 'value': '2'},
                              {'id': 'new-volume-history', 'type': 'history', 'target': 'PHASE_THREE_ARCHIVE_ANNOTATED',
                               'value': '一位真实参与者现在完成了旧都档案第二卷的批注。'},
                          ]},
        },
    }
    choices = {}
    nodes = {}
    labels = {'testimony': '按第一卷的真实证言记录继续', 'witness': '按第一卷的真实见证记录继续', 'reconcile': '按第一卷的真实核对记录继续'}
    for choice, label in labels.items():
        choices[choice] = {'label': label, 'next': 'remembered-' + choice,
                           'condition': {'fact': RETIRED_REF, 'op': 'eq', 'value': choice}}
        nodes['remembered-' + choice] = {
            'text': '旧版本的个人选择是 ' + choice + '。新故事引用了已保存的旧图与真实选择。', 'terminal': True,
            'effects': [
                {'id': 'reader-honor', 'type': 'honor', 'target': 'player', 'value': '3'},
                {'id': 'reader-history', 'type': 'history', 'target': 'PHASE_THREE_REMEMBERED_' + choice.upper(),
                 'value': '一位参与者依据第一卷真实记录，以 ' + choice + ' 身份完成了新的版本索引。'},
            ],
        }
    nodes['recall'] = {'text': '第一卷已经退役，但准确的旧版本仍可引用。只显示与你实际第一卷选择相符的入口。', 'choices': choices}
    b = {
        'id': READER, 'version': '1', 'title': '旧档中的版本坐标', 'scope': 'PLAYER',
        'triggers': ['HEIR_RESTORATION_SUPPORTED'], 'start': 'recall', 'cooldownSeconds': 0,
        'condition': {'all': [condition, {'fact': RETIRED_REF, 'op': 'exists'}]}, 'nodes': nodes,
    }
    return {'heir-legacy-v2.json': a2, 'phase-three-catalog-reader.json': b}


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
