"""Capture a new named evidence stage with read-only SQLite handles and exact artifact checks."""
from pathlib import Path
import argparse
import importlib.util
import json
import re

spec = importlib.util.spec_from_file_location('phase_three_paper_common', Path(__file__).with_name('phase-three-paper-common.py'))
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage')
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--archive-log', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9-]{1,64}', args.stage):
        raise SystemExit('Invalid evidence stage')
    run = c.run_path(args.run_dir); value = c.manifest(run, args.expected_sha)
    target = run / 'evidence' / (args.stage + '.json')
    if target.exists():
        raise SystemExit('Refusing to overwrite an already observed evidence stage')
    data = {'stage': args.stage, 'captured_at': c.now(), 'run_name': run.name,
            'plugin_sha256': value['plugin_sha256'], 'fixture_sha256': c.digest(run / 'plugins/PhaseThreePaperFixture.jar'),
            'source_files_unchanged': c.source_unchanged(value), **c.db_snapshot(run / 'plugins/StarChronicle/chronicle.db')}
    with c.database(run / 'plugins/StarCore/starcore.db') as core:
        data['core_nation_state'] = [dict(row) for row in core.execute('SELECT property_key,property_value FROM starcore_nation_state ORDER BY property_key')]
        data['core_integrity'] = core.execute('PRAGMA integrity_check').fetchone()[0]
    base = run / 'plugins/StarChronicle'
    data['config_sha256'] = {path.relative_to(base).as_posix(): c.digest(path) for path in sorted(base.rglob('*'))
                             if path.is_file() and path.suffix in ['.yml', '.yaml', '.json'] and path.name != 'chronicle.db'}
    data['fixture_content_sha256'] = {path.name: c.digest(path) for path in sorted((run / 'fixture-content').iterdir()) if path.is_file()}
    data['retired_a1_file_sha256'] = c.digest(base / 'stories/heir-legacy.yml.phase-three-retired') if (base / 'stories/heir-legacy.yml.phase-three-retired').is_file() else None
    if not data['source_files_unchanged']:
        raise SystemExit('Original source files changed; refusing evidence that hides source drift')
    c.write_json(target, data)
    if args.archive_log:
        log_target = run / 'evidence' / (args.stage + '.log')
        if log_target.exists():
            raise SystemExit('Refusing log overwrite')
        lines=[];redactions=0
        for line in (run / 'logs/latest.log').read_text(encoding='utf-8', errors='replace').splitlines():
            if re.search(r'(?i)secret|password|bearer|api[-_ ]?key|token\s*[:=]', line):
                lines.append('[REDACTED: credential-related server log line]');redactions+=1
            else:
                lines.append(line)
        log_target.write_text('\n'.join(lines)+'\n',encoding='utf-8');print('Retained sanitized actual server log; redactions:',redactions)
    instances = [json.loads(row['json']) for row in data['instances'] if row['story'] in [c.ARCHIVE,c.READER]]
    print(json.dumps({'stage':args.stage,'schema':data['schema_version'],'plugin_sha256':data['plugin_sha256'],
                      'instances':[{'story':item['definition']['id'],'version':item['definition']['version'],'node':item['node'],
                                    'completed':item['completed'],'scope':item['scopeKey']} for item in instances],
                      'catalog_rows':len(data.get('story_definitions',[])),'integrity':data['integrity'],'core_integrity':data['core_integrity']},ensure_ascii=False,indent=2))


if __name__ == '__main__':
    main()
