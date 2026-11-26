"""Archive actual build results, candidate sources and isolated runtime evidence."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import re
import shutil
import socket
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NATIVE_TESTS = {'QuestEvidenceServiceTest', 'DungeonRepositoryEpicReceiptTest', 'DungeonRepositoryIdempotencyTest',
                'NationFoundingOperationTest', 'WarDeclarationOperationRestartIntegrationTest',
                'WarCompletionReceiptTest', 'WarMenuReinforcementOwnerTest'}
NATIVE_POLICY_TESTS = {'SqlPolicyStateStorageTest', 'PolicyModuleDatabaseConflictIntegrationTest',
                       'PolicyModuleLifecycleTest', 'PolicyModuleStateTest'}


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], encoding='utf-8').strip()


def results(root, selected=None, reports=None):
    suites = []
    for path in sorted((reports or root / 'target/surefire-reports').glob('TEST-*.xml')):
        node = ET.parse(path).getroot()
        if selected and node.attrib['name'].rsplit('.', 1)[-1] not in selected:
            continue
        suites.append(dict(name=node.attrib['name'], **{key: int(node.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')},
                           report=str(path), sha256=sha(path), written_utc=datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat()))
    if selected and len(suites) != len(selected):
        raise SystemExit('Missing selected native test reports')
    if not suites:
        raise SystemExit('No test reports found')
    return dict(totals={key: sum(s[key] for s in suites) for key in ('tests', 'failures', 'errors', 'skipped')}, suites=suites)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--starcore', type=Path, required=True)
    parser.add_argument('--run', type=Path, action='append', default=[])
    parser.add_argument('--label', default='checkpoint')
    parser.add_argument('--native-reports', type=Path)
    parser.add_argument('--starcore-jar', type=Path, help='Frozen accepted artifact, if verify has since repackaged target')
    parser.add_argument('--native-artifact-commit', help='Commit used to build the frozen accepted native JAR')
    parser.add_argument('--include-policy-tests', action='store_true')
    parser.add_argument('--full-regression-log', type=Path)
    parser.add_argument('--build-log', type=Path, action='append', default=[], help='Additional actual build logs for this candidate')
    parser.add_argument('--additional-evidence', type=Path, action='append', default=[], help='Extra evidence file or directory; names must be distinct')
    parser.add_argument('--prior-native-regression', type=Path, action='append', default=[],
                        help='Preserved directory containing test.log and surefire-reports/')
    args = parser.parse_args()
    native = args.starcore.resolve()
    if not re.fullmatch(r'[a-z0-9-]+', args.label):
        raise SystemExit('Invalid archive label')
    destination = ROOT / ('target/national-epic-' + args.label)
    destination.mkdir(parents=True, exist_ok=False)
    selected = NATIVE_TESTS | (NATIVE_POLICY_TESTS if args.include_policy_tests else set())
    summary = dict(captured_utc=datetime.now(timezone.utc).isoformat(),
                   chronicle=dict(head=git(ROOT, 'rev-parse', 'HEAD'), tests=results(ROOT)),
                   starcore=dict(head=git(native, 'rev-parse', 'HEAD'), tests=results(native, selected, args.native_reports)),
                   coverage='Consult the archived build command/log for fork and JaCoCo settings; targeted native tests are not full-project coverage.',
                   source_note='Source archive captures the current candidate worktree; Git status and source fingerprints are recorded separately. No production deployment is claimed.',
                   runs=[])
    for key, root in [('chronicle', ROOT), ('starcore', native)]:
        summary[key]['worktree_status'] = git(root, 'status', '--porcelain')
        summary[key]['source_files'] = {p: sha(root / p) for p in git(root, 'ls-files', '-c', '-o', '--exclude-standard', '--', 'src', 'pom.xml').splitlines() if (root / p).is_file()}
    if args.full_regression_log:
        summary['starcore']['full_regression'] = dict(tests=results(native), log_sha256=sha(args.full_regression_log))
    summary['starcore']['prior_regressions'] = [dict(label=path.name, tests=results(native, reports=path / 'surefire-reports'),
                                                   log_sha256=sha(path / 'test.log')) for path in args.prior_native_regression]
    for key, root, name in [('chronicle', ROOT, 'starchronicle-0.1.0-SNAPSHOT.jar'), ('starcore', native, 'starcore-1.0.0.jar')]:
        original = args.starcore_jar if key == 'starcore' and args.starcore_jar else root / 'target' / name
        copy = destination / name
        shutil.copy2(original, copy)
        summary[key]['jar'] = dict(path=str(copy), sha256=sha(copy), bytes=copy.stat().st_size)
    if args.native_artifact_commit:
        commit = git(native, 'rev-parse', args.native_artifact_commit)
        if git(native, 'diff', commit, '--', 'src/main', 'pom.xml'):
            raise SystemExit('Native production sources differ from the accepted artifact commit')
        summary['starcore']['jar']['source_commit'] = commit
    native_zip = destination / 'starcore-candidate-sources.zip'
    paths = set(git(native, 'ls-files', '-c', '-o', '--exclude-standard', '--', 'src', 'pom.xml', '.mvn', 'mvnw', 'mvnw.cmd', 'docs/api').splitlines())
    with zipfile.ZipFile(native_zip, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('SOURCE-NOTE.txt', summary['source_note'] + '\nBase HEAD: ' + summary['starcore']['head'] + '\n')
        for relative in sorted(paths):
            path = native / relative
            if path.is_file() and not path.is_symlink():
                archive.write(path, relative)
    summary['starcore']['source_archive'] = dict(path=str(native_zip), sha256=sha(native_zip))
    evidence_zip = destination / 'verification-evidence.zip'
    with zipfile.ZipFile(evidence_zip, 'w', zipfile.ZIP_DEFLATED) as archive:
        extra_names = set()
        summary['additional_evidence'] = {}
        for extra in args.additional_evidence:
            if not extra.exists() or extra.is_symlink() or extra.name in extra_names:
                raise SystemExit('Extra evidence must exist, be non-symlink and have a unique name')
            extra_names.add(extra.name)
            paths = sorted(extra.rglob('*')) if extra.is_dir() else [extra]
            for path in paths:
                if path.is_symlink():
                    raise SystemExit('Symlinks are not allowed in extra evidence')
                if not path.is_file():
                    continue
                name = 'additional-evidence/' + extra.name
                if extra.is_dir():
                    name += '/' + path.relative_to(extra).as_posix()
                archive.write(path, name)
                summary['additional_evidence'][name] = sha(path)
        for index, log in enumerate(args.build_log):
            archive.write(log, 'chronicle/additional-build-logs/' + str(index) + '-' + log.name)
        for prior, prior_summary in zip(args.prior_native_regression, summary['starcore']['prior_regressions']):
            archive.write(prior / 'test.log', 'starcore/prior-regressions/' + prior.name + '/test.log')
            for suite in prior_summary['tests']['suites']:
                path = Path(suite['report'])
                archive.write(path, 'starcore/prior-regressions/' + prior.name + '/surefire-reports/' + path.name)
        if args.full_regression_log:
            archive.write(args.full_regression_log, 'starcore/full-regression.log')
            for suite in summary['starcore']['full_regression']['tests']['suites']:
                path = Path(suite['report']);archive.write(path, 'starcore/full-surefire/' + path.name)
        for key, root, logs in [('chronicle', ROOT, ['epic-final-verify.log', 'epic-targeted-test.log']),
                                ('starcore', native, ['epic-native-test.log', 'epic-native-recovery-test.log', 'epic-native-package.log', 'epic-native-package-retry.log', 'epic-native-final-package.log', 'epic-native-final-package-retry.log', 'epic-policy-regression-before.log', 'epic-native-sbom.log', 'bom.xml', 'bom.json'])]:
            for suite in summary[key]['tests']['suites']:
                path = Path(suite['report']);archive.write(path, key + '/surefire/' + path.name)
            for name in logs:
                path = root / 'target' / name
                if path.is_file():archive.write(path, key + '/' + name)
        for run_arg in args.run:
            run = run_arg.resolve()
            if run.parent != (ROOT / 'run').resolve() or not run.name.startswith('paper-epic-'):
                raise SystemExit('Refusing non-epic run')
            preparation = json.loads((run / 'preparation.json').read_text(encoding='utf-8'))
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(('127.0.0.1', preparation['server_port']))
            events = [json.loads(line) for line in (run / 'evidence/epic-fixture.jsonl').read_text(encoding='utf-8').splitlines()] if (run / 'evidence/epic-fixture.jsonl').exists() else []
            summary['runs'].append(dict(path=str(run), preparation=preparation,
                                       independent_check=json.loads((run / 'evidence/independent-check.json').read_text()) if (run / 'evidence/independent-check.json').is_file() else None,
                                       passing_checks=[e for e in events if e['kind']=='ACCEPTANCE_PASS'],
                                       failures=[e for e in events if e['kind']=='FAILED'],
                                       fatal_jvm_logs=[path.name for path in run.glob('hs_err_*.log')]))
            files = [run / 'preparation.json', run / 'server.properties']
            files += list((run / 'evidence').rglob('*')) + list(run.glob('hs_err_*.log'))
            files += list((run / 'fixture').glob('*.java'))
            files += list((run / 'plugins').glob('*/config.yml')) + list((run / 'plugins/StarCore').glob('dungeons.yml'))
            files += list((run / 'plugins/StarChronicle/stories').glob('*')) + list((run / 'plugins/StarChronicle/epic-campaigns').glob('*'))
            # Only stopped, local fixture data. Exclude generated plugin config secrets.
            files += [run / 'plugins/StarChronicle/chronicle.db', run / 'plugins/StarCore/starcore.db']
            for folder in ('dungeon', 'quest', 'wars', 'war'):
                files += list((run / 'plugins/StarCore' / folder).rglob('*'))
            for path in sorted(set(files)):
                if not path.is_file() or path.is_symlink(): continue
                name = run.name + '/' + path.relative_to(run).as_posix()
                if path == run / 'plugins/StarCore/config.yml':
                    redacted = re.sub(r'(?m)^(\s*access-secret:)\s*.*$', r'\1 "REDACTED_TEST_SECRET"', path.read_text(encoding='utf-8'))
                    archive.writestr(name, redacted)
                else: archive.write(path, name)
        for name in ('EpicCampaignPaperFixture.java','ContributionUpgradeProbe.java','prepare-epic-paper.py','epic-paper-clients.cjs','epic-paper-session.py','inspect-epic-paper.py','inspect-quest-evidence-paper.py','test_epic_acceptance.py','archive-epic-evidence.py'):
            archive.write(ROOT / 'tools' / name, 'tools/' + name)
        inventory = {entry.filename: hashlib.sha256(archive.read(entry.filename)).hexdigest() for entry in archive.infolist()}
        archive.writestr('evidence-manifest.json', json.dumps(inventory, indent=2) + '\n')
    summary['evidence_archive'] = dict(path=str(evidence_zip), sha256=sha(evidence_zip))
    text = json.dumps(summary, ensure_ascii=False, indent=2) + '\n'
    (destination / 'manifest.json').write_text(text, encoding='utf-8')
    summary_path = ROOT / ('docs/verification/national-epic-' + args.label + '-summary.json')
    with summary_path.open('x', encoding='utf-8') as output:
        output.write(text)
    shutil.copy2(evidence_zip, ROOT / ('docs/verification/national-epic-' + args.label + '-evidence.zip'))
    print(json.dumps(dict(delivery=str(destination), chronicle=summary['chronicle']['tests']['totals'],
                          starcore=summary['starcore']['tests']['totals'], runs=len(summary['runs'])), ensure_ascii=False))


if __name__ == '__main__':
    main()
