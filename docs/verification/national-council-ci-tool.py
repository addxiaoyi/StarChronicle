"""Read exact-head GitHub Actions evidence without exposing credentials or redirects."""
from pathlib import Path
from datetime import datetime, timezone
from urllib.parse import quote as urlquote, urlsplit
import argparse, hashlib, json, os, re, shutil, subprocess

ROOT=Path(__file__).resolve().parents[1]
REPO='addxiaoyi/StarChronicle'
PREFIX='national-council-ci-'

def sha(data):return hashlib.sha256(data).hexdigest()
def curl_quote(value):return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
def now():return datetime.now(timezone.utc).isoformat()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-head',required=True)
    parser.add_argument('--expected-branch',required=True)
    parser.add_argument('--expected-jar-sha',required=True)
    args=parser.parse_args()
    if not re.fullmatch('[0-9a-f]{40}',args.expected_head) or not re.fullmatch('[0-9a-f]{64}',args.expected_jar_sha):parser.error('Require complete expected hashes')
    local=subprocess.run(['git','rev-parse','HEAD'],cwd=ROOT,capture_output=True,timeout=15)
    branch=subprocess.run(['git','branch','--show-current'],cwd=ROOT,capture_output=True,timeout=15)
    if local.returncode or local.stdout.decode().strip()!=args.expected_head or branch.returncode or branch.stdout.decode().strip()!=args.expected_branch:raise SystemExit('Current worktree does not match the requested source; nothing queried')
    jar=ROOT/'target/starchronicle-0.1.0-SNAPSHOT.jar'
    if sha(jar.read_bytes())!=args.expected_jar_sha:raise SystemExit('Current candidate JAR does not match supplied fingerprint')
    credential=subprocess.run(['git','-c','credential.interactive=never','-c','core.askPass=','credential','fill'],cwd=ROOT,input=('url=https://github.com/'+REPO+'.git\n\n').encode(),capture_output=True,env=dict(os.environ,GIT_TERMINAL_PROMPT='0'),timeout=30)
    if credential.returncode:raise SystemExit('Credential helper unavailable; no credential output retained')
    token=dict(line.split('=',1) for line in credential.stdout.decode().splitlines() if '=' in line).get('password')
    if not token or '\r' in token or '\n' in token:raise SystemExit('No usable credential; no credential output retained')
    work=ROOT/'run';delivery=ROOT/'docs/verification';delivery.mkdir(parents=True,exist_ok=True)
    outputs={}
    def public_bytes(data):
        # Only response bodies are retained. Never retain API headers or signed locations.
        if token.encode() in data:raise RuntimeError('Sensitive credential appeared in a response; response not retained')
        text=data.decode('utf-8',errors='replace')
        signed=r'https?://[^\s"<>]+[?&](?:sig|token|X-Amz-Signature|X-Goog-Signature)=[^\s"<>]+'
        if re.search(signed,text,re.I):raise RuntimeError('Signed URL appeared in response content; response not retained')
        return data
    def retain(suffix,data):
        data=public_bytes(data);path=work/(PREFIX+suffix)
        if path.exists() and path.read_bytes()!=data:raise RuntimeError('Refusing to overwrite prior CI evidence: '+path.name)
        path.write_bytes(data);outputs[path.name]={'bytes':len(data),'sha256':sha(data)};return path
    def request(url,authenticated=True,headers=False):
        parts=urlsplit(url)
        if parts.scheme!='https' or not parts.hostname:raise RuntimeError('HTTPS destination required')
        if authenticated and parts.hostname!='api.github.com':raise RuntimeError('Refusing authentication outside GitHub API')
        config='url = '+curl_quote(url)+'\n'
        if authenticated:config+='header = '+curl_quote('Authorization: Bearer '+token)+'\nheader = "Accept: application/vnd.github+json"\nheader = "X-GitHub-Api-Version: 2022-11-28"\n'
        command=['curl','-q','--config','-','--silent','--show-error','--connect-timeout','10','--max-time','60','--user-agent','StarChronicle-exact-head-ci-verification','--write-out','\n%{http_code}']
        if headers:command+=['--include']
        response=subprocess.run(command,input=config.encode(),capture_output=True,timeout=65)
        if response.returncode:raise RuntimeError('HTTPS transport failed; private diagnostics not printed')
        body,separator,status=response.stdout.rpartition(b'\n')
        if not separator or not re.fullmatch(b'[0-9]{3}',status):raise RuntimeError('Missing HTTP status')
        return int(status),body
    def api(path,suffix):
        status,body=request('https://api.github.com/repos/'+REPO+'/'+path)
        if status!=200:raise RuntimeError('GitHub API HTTP '+str(status)+'; response not printed')
        value=json.loads(body);retain(suffix,body);return value
    remote=api('branches/'+urlquote(args.expected_branch,safe=''),'branch.json')
    if remote['name']!=args.expected_branch or remote['commit']['sha']!=args.expected_head:raise RuntimeError('Remote branch does not currently match the requested HEAD')
    listed=api('actions/runs?branch='+urlquote(args.expected_branch,safe='')+'&head_sha='+args.expected_head+'&per_page=100','runs.json')
    matches=[run for run in listed['workflow_runs'] if run['head_sha']==args.expected_head and run['head_branch']==args.expected_branch and run.get('name')=='Maven verification']
    if not matches:raise RuntimeError('No exact-head Maven verification run was found')
    chosen=max(matches,key=lambda run:run['id']);run=api('actions/runs/'+str(chosen['id']),'run.json')
    if run['head_sha']!=args.expected_head or run['head_branch']!=args.expected_branch:raise RuntimeError('Selected run identity changed')
    jobs=api('actions/runs/'+str(run['id'])+'/jobs?per_page=100','jobs.json')
    verified=[job for job in jobs['jobs'] if job['name']=='verify' and job['run_id']==run['id']]
    if len(verified)!=1:raise RuntimeError('Expected one exact-run verify job')
    job=api('actions/jobs/'+str(verified[0]['id']),'job.json')
    if job['run_id']!=run['id'] or job.get('head_sha')!=args.expected_head:raise RuntimeError('Job does not match the requested source HEAD')
    log_path=None;totals=[];build_success=False
    if run['status']=='completed' and job['status']=='completed':
        status,response=request('https://api.github.com/repos/'+REPO+'/actions/jobs/'+str(job['id'])+'/logs',headers=True)
        # curl may include an intermediary HTTP CONNECT header. The final response is last.
        header_blocks=re.split(br'\r?\n\r?\n',response)
        locations=re.findall(br'(?im)^location:\s*(.+?)\s*$',response)
        if status in (301,302,303,307,308):
            if len(locations)!=1:raise RuntimeError('Unexpected private log redirect; nothing printed')
            location=locations[0].decode();parts=urlsplit(location)
            if parts.scheme!='https' or not parts.hostname or not any(parts.hostname.endswith(suffix) for suffix in ('.blob.core.windows.net','.githubusercontent.com','.s3.amazonaws.com')):raise RuntimeError('Unapproved log download destination')
            download_status,log=request(location,authenticated=False)
            if download_status!=200:raise RuntimeError('Signed log download HTTP '+str(download_status)+'; location not retained')
        elif status==200:
            if len(header_blocks)<2:raise RuntimeError('Missing direct log response body')
            # A direct 200 log is uncommon; remove HTTP response headers, not log lines.
            offset=0
            while response[offset:].startswith(b'HTTP/'):
                match=re.search(br'\r?\n\r?\n',response[offset:])
                if not match:raise RuntimeError('Malformed direct log headers')
                offset+=match.end()
            log=response[offset:]
        else:raise RuntimeError('GitHub log HTTP '+str(status)+'; response not retained')
        log_path=retain('verify.log',log);decoded=log.decode('utf-8',errors='replace')
        totals=[{'tests':int(a),'failures':int(b),'errors':int(c),'skipped':int(d)} for a,b,c,d in re.findall(r'Tests run:\s*(\d+), Failures:\s*(\d+), Errors:\s*(\d+), Skipped:\s*(\d+)',decoded)]
        build_success='BUILD SUCCESS' in decoded
    summary={'recorded_utc':now(),'repository':REPO,'expected_branch':args.expected_branch,'expected_head':args.expected_head,'local_head_matches':True,'remote_branch_head_matches':True,'candidate_jar_sha256':args.expected_jar_sha,'candidate_jar_scope':'Local supplied candidate fingerprint verified. CI status certifies this exact source HEAD; no claim of byte identity with a separately built remote JAR.','workflow_name':run['name'],'workflow_path':run['path'],'run_id':run['id'],'run_number':run['run_number'],'run_url':run['html_url'],'run_status':run['status'],'run_conclusion':run['conclusion'],'run_created_at':run['created_at'],'run_updated_at':run['updated_at'],'job_id':job['id'],'job_status':job['status'],'job_conclusion':job['conclusion'],'job_started_at':job['started_at'],'job_completed_at':job['completed_at'],'job_steps':[{'name':step['name'],'status':step['status'],'conclusion':step['conclusion']} for step in job['steps']],'log_retained':log_path is not None,'maven_final_totals':totals[-1] if totals else None,'maven_build_success_in_final_log':build_success,'successful_exact_head_verification':run['status']=='completed' and run['conclusion']=='success' and job['status']=='completed' and job['conclusion']=='success' and build_success,'security':'Credential obtained only through noninteractive git helper; authorization and signed redirect supplied only via curl stdin; no credential/redirect headers or signed URL retained. No JVM/Maven/server commands invoked.','evidence':outputs.copy(),'tool_source_sha256':sha(Path(__file__).read_bytes())}
    summary_path=retain('summary.json',(json.dumps(summary,ensure_ascii=False,indent=2)+'\n').encode())
    markdown=f'''# National council exact-head CI evidence

Observed UTC: `{summary['recorded_utc']}`.

- Repository: `{REPO}`.
- Branch: `{args.expected_branch}`.
- Exact source HEAD: `{args.expected_head}` (local, remote branch, workflow run and verify job matched).
- Workflow: `{run['name']}`, run `{run['id']}` / number `{run['run_number']}`.
- Actual workflow status/conclusion: `{run['status']}` / `{run['conclusion']}`.
- Actual verify job status/conclusion: `{job['status']}` / `{job['conclusion']}`.
- Final log Maven totals: `{json.dumps(summary['maven_final_totals'])}`.
- BUILD SUCCESS present: `{build_success}`.
- Local supplied candidate JAR: `{args.expected_jar_sha}`. This is not a claim that GitHub's separately built artifact has identical bytes.

Official API JSON and the completed job log accompany this summary. The credential and temporary signed log download location remained in process memory and curl stdin; neither was printed or retained. This check invoked no Java, Maven or game server.

CI verifies this source commit. It does not constitute real Paper/Folia acceptance or certify upstream StarCore compatibility. Existing server acceptance reports were not changed.
'''
    retain('report.md',markdown.encode())
    for name in outputs:
        source=work/name;destination=delivery/name
        if destination.exists() and destination.read_bytes()!=source.read_bytes():raise RuntimeError('Refusing to replace a different delivered CI record: '+name)
        shutil.copy2(source,destination)
    print(json.dumps({'head':args.expected_head,'branch':args.expected_branch,'run_id':run['id'],'run_status':run['status'],'run_conclusion':run['conclusion'],'job_status':job['status'],'job_conclusion':job['conclusion'],'maven_final_totals':summary['maven_final_totals'],'maven_build_success':build_success,'exact_head_success':summary['successful_exact_head_verification'],'summary':str(summary_path),'delivered_files':list(outputs)},ensure_ascii=True,indent=2))

if __name__=='__main__':main()
