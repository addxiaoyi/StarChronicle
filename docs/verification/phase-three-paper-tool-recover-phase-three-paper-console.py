"""One-shot normal recovery of observed non-TTY console attempt 6636/launcher 9736.

No generic process selection, taskkill, server restart or database/world reset.
"""
from pathlib import Path
import argparse,ctypes,importlib.util,json,shutil,subprocess,sys
from ctypes import wintypes
sys.stdout.reconfigure(encoding='utf-8',errors='backslashreplace')
spec=importlib.util.spec_from_file_location('common',Path(__file__).with_name('phase-three-paper-common.py'))
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)


def main():
    p=argparse.ArgumentParser();p.add_argument('--run-dir',type=Path,required=True);p.add_argument('--expected-sha',required=True);p.add_argument('--java-home',type=Path,required=True);a=p.parse_args()
    run=c.run_path(a.run_dir);manifest=c.manifest(run,a.expected_sha);evidence=run/'evidence';table=evidence/'phase-three-paper-processes.json'
    saved=evidence/'preflight2-before-process-table.json'
    if saved.exists():raise SystemExit('One-shot recovery already attempted; inspect actual evidence')
    saved.write_bytes(table.read_bytes())
    data=json.loads(saved.read_text(encoding='utf8'))
    if data.get('boots') or len(data.get('preflight_attempts',[]))!=1:raise SystemExit('Unexpected prior bounded process state')
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD];kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes=[wintypes.HANDLE,wintypes.DWORD];kernel.WaitForSingleObject.restype=wintypes.DWORD
    kernel.GetExitCodeProcess.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.DWORD)];kernel.GetExitCodeProcess.restype=wintypes.BOOL
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handles={pid:kernel.OpenProcess(0x00100000|0x1000,False,pid) for pid in [6636,9736]}
    if not all(handles.values()):raise ctypes.WinError(ctypes.get_last_error())
    record={'server_pid':6636,'launcher_pid':9736,'started_utc':c.now(),'reason':'Tool stdin closed because exec omitted tty=true; no clients or commands were started','product_sha256':a.expected_sha}
    try:
        command=[str(a.java_home/'bin/java.exe'),'-Xmx64m','--add-modules','jdk.attach','-cp',str(run/'fixture/recovery-classes'),
                 'smoke.PhaseThreePaperStopAgent','6636',str(run/'fixture/phase-three-paper-stop-agent.jar'),str(run),a.expected_sha,str(evidence/'preflight2-normal-shutdown.json')]
        result=subprocess.run(command,capture_output=True,timeout=60);(evidence/'preflight2-recovery-attach.log').write_bytes(result.stdout+result.stderr)
        record.update(attach_command=command,attach_exit_code=result.returncode)
        if result.returncode:raise RuntimeError('Bounded attach failed; do not kill another JVM')
        for pid,handle in handles.items():
            if kernel.WaitForSingleObject(handle,60000)!=0:raise RuntimeError('Observed process did not finish: '+str(pid))
            code=wintypes.DWORD()
            if not kernel.GetExitCodeProcess(handle,ctypes.byref(code)):raise ctypes.WinError(ctypes.get_last_error())
            record['server_exit_code' if pid==6636 else 'launcher_exit_code']=code.value
        if record['server_exit_code']!=0 or record['launcher_exit_code']!=1:raise RuntimeError('Unexpected actual exit result')
        if not (evidence/'preflight2-normal-shutdown.json').is_file():raise RuntimeError('No actual Bukkit shutdown marker')
        data=json.loads(table.read_text(encoding='utf8'))
        if len(data['boots'])!=1 or data['boots'][0].get('server_pid')!=6636 or data['boots'][0].get('client_pid') is not None:raise RuntimeError('Launcher final table does not identify this exact pre-client attempt')
        shutil.copy2(table,evidence/'preflight2-final-process-table.json')
        failed=data['boots'][0];failed.update(launcher_problem=record['reason'],recovered_normal_bukkit_shutdown=True,all_owned_children_exited=True,launcher_exit_code=1)
        data['preflight_attempts'].append(failed);data['boots']=[];data['server_exit_codes']=[];data['client_exit_codes']=[]
        (evidence/'phase-three-paper-boot1.log').rename(evidence/'preflight2-launcher-stdout.log')
        shutil.copy2(run/'logs/latest.log',evidence/'preflight2-complete-server.log')
        record.update(exited_utc=c.now(),source_files_unchanged=c.source_unchanged(manifest))
        c.write_json(table,data);manifest['status']='TWO_PREFLIGHTS_RECOVERED_NO_STATE_RESET';c.write_json(run/'preparation.json',manifest)
    except Exception as error:record['recovery_failure']=repr(error);raise
    finally:
        for handle in handles.values():kernel.CloseHandle(handle)
        c.write_json(evidence/'preflight2-recovery.json',record)
    print(json.dumps(record,indent=2))


if __name__=='__main__':main()
