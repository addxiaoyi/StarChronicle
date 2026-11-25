"""Clone a stopped, accepted pre-contribution save; retain its independently checkable baseline."""
from pathlib import Path
from contextlib import closing
import argparse
import importlib.util
import json
import re
import shutil


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


PREPARE = module('epic_prepare', 'prepare-epic-paper.py')
EPIC = module('epic_inspector', 'inspect-epic-paper.py')
ROOT = Path(__file__).resolve().parents[1]


def prepare(args):
    source = args.source.resolve()
    original = EPIC.inspect(source)
    with closing(EPIC.connect(source / 'plugins/StarChronicle/chronicle.db')) as db:
        EPIC.check(db.execute('select count(*) from contributions').fetchone()[0] == 0,
                   'Upgrade requires an actual old save without contributions')
    for folder in ('plugins/StarCore', 'plugins/StarChronicle', 'plugins/EpicCampaignPaperFixture',
                   'world', 'world_nether', 'world_the_end', 'evidence'):
        EPIC.check(not any(p.is_symlink() for p in (source / folder).rglob('*')), 'Refusing source symlinks')
    EPIC.check(not any(p.stat().st_size for p in (source / 'plugins').rglob('*.db-wal')), 'Source has uncheckpointed WAL data')
    manifest = EPIC.read(source / 'preparation.json')
    EPIC.check(PREPARE.sha(args.starcore) == manifest['starcore_sha256'], 'Upgrade must keep the accepted native artifact')
    config = (source / 'plugins/EpicCampaignPaperFixture/config.yml').read_text()
    EPIC.check(re.search(r'(?m)^boot: 3$', config) and re.search(r'(?m)^seeded: true$', config), 'Expected completed, seeded baseline')
    args.route = manifest['route']
    PREPARE.prepare(args)
    run = ROOT / 'run' / f'paper-epic-{PREPARE.sha(args.product)[:8]}-{args.route.lower()}-{args.attempt}'
    baseline = run / 'evidence/upgrade-baseline'
    baseline.mkdir()
    for relative in ('preparation.json', 'server.properties', 'fixture/EpicCampaignPaperFixture.java',
                     'plugins/StarCore.jar', 'plugins/StarChronicle.jar', 'plugins/EpicCampaignPaperFixture.jar'):
        destination = baseline / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / relative, destination)
    for relative in ('evidence', 'plugins/StarCore', 'plugins/StarChronicle', 'plugins/EpicCampaignPaperFixture'):
        shutil.copytree(source / relative, baseline / relative)
    config_copy = baseline / 'plugins/StarCore/config.yml'
    config_copy.write_text(re.sub(r'(?m)^(\s*access-secret:)\s*.*$', r'\1 "REDACTED_TEST_SECRET"', config_copy.read_text()))
    # Only the new isolated run receives old save data. Original evidence is never edited.
    for relative in ('plugins/StarCore', 'plugins/StarChronicle', 'plugins/EpicCampaignPaperFixture',
                     'world', 'world_nether', 'world_the_end'):
        shutil.copytree(source / relative, run / relative, dirs_exist_ok=True)
    for relative in ('usercache.json', 'whitelist.json', 'banned-players.json', 'banned-ips.json'):
        if (source / relative).exists():
            shutil.copy2(source / relative, run / relative)
    new_sha = PREPARE.sha(args.product)
    config, count = re.subn(r'(?m)^expected-sha:.*$', 'expected-sha: ' + new_sha, config)
    EPIC.check(count == 1, 'Missing expected product SHA')
    (run / 'plugins/EpicCampaignPaperFixture/config.yml').write_text(config)
    before = {str(p.relative_to(baseline)): PREPARE.sha(p) for p in baseline.rglob('*') if p.is_file()}
    PREPARE.write(run / 'evidence/upgrade-source.json', json.dumps(dict(
        source=str(source), original=original, baseline_sha256=before,
        upgrade_boots=[4, 5], native_unchanged=True), indent=2) + '\n')
    args.run = run
    PREPARE.compile_fixture(args)
    print(json.dumps(dict(run=str(run), baseline=original, upgrade_boots=[4, 5])))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--attempt', default='contribution-upgrade-1', type=lambda value: value if re.fullmatch(r'[a-z0-9-]{1,32}', value) else parser.error('Invalid attempt'))
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--product', type=Path, default=ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--starcore', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    prepare(parser.parse_args())
