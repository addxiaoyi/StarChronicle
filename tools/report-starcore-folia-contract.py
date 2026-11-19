"""Record patch applicability, focused compilation/tests, and the unmodified loader probe."""
from pathlib import Path
import hashlib,json,subprocess,sys,xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
baseline=Path(sys.argv[1]).resolve()
patch=root/'patches'/'starcore-folia-narrative-owner-contract.patch'
out=root/'docs'/'verification'
check=subprocess.run(['git','apply','--check','--whitespace=error',str(patch)],cwd=baseline,capture_output=True,text=True)
commit=subprocess.run(['git','rev-parse','HEAD'],cwd=baseline,capture_output=True,text=True,check=True).stdout.strip()
tests=[]
for path in sorted((root/'run'/'starcore-folia-contract'/'target'/'surefire-reports').glob('TEST-*.xml')):
    suite=ET.parse(path).getroot()
    tests.append({key:suite.attrib[key] for key in ['name','tests','failures','errors','skipped']})
probe=(out/'folia-upstream-loader-probe.log').read_text(encoding='utf-8')
data={
    'date':'2026-11-19',
    'scope':'necessary owner-thread API contract only; NOT a full upstream Folia port',
    'upstream_commit':commit,
    'patch_sha256':hashlib.sha256(patch.read_bytes()).hexdigest(),
    'patch_applies_cleanly':check.returncode==0,
    'compiled_sources':['StarCoreScheduler.java','NarrativeEngine.java'],
    'compilation_scope':'focused Maven harness against verified upstream StarCore JAR and Paper 1.21.11 API; not complete upstream Maven release',
    'focused_tests':tests,
    'tests_total':sum(int(t['tests']) for t in tests),
    'failures_total':sum(int(t['failures'])+int(t['errors']) for t in tests),
    'loader_probe':{
        'artifacts':json.loads((out/'folia-upstream-probe-artifacts.json').read_text(encoding='utf-8')),
        'original_starcore_rejected':"Could not load plugin 'StarCore v1.0.0' as it is not marked as supporting Folia!" in probe,
        'chronicle_require_starcore_refused_start':'StarChronicle startup failed: StarCore services unavailable' in probe,
        'descriptor_modified':False,
        'shutdown_observed':False,
        'exit_code':None,
        'cleanup_note':'The probe PTY session became unavailable during host resource pressure. Later CIM inventory found no folia.jar process. No normal-stop or exit-code assertion is made for this separate negative loader probe.'
    }
}
(out/'starcore-folia-contract-summary.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(data,ensure_ascii=False,indent=2))
if check.returncode or data['failures_total'] or data['tests_total']!=13:raise SystemExit(1)
