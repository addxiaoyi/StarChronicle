"""Bounded normal Bukkit shutdown of this launcher's abandoned pre-client JVM only.

Never enumerates/kills JVMs. The only admissible PID is the incomplete recorded launch.
Retains the original failed table/log and moves it to explicit preflight_attempts after
an actual Win32 process-handle exit observation. Does not reset any database/world.
"""
from pathlib import Path
import argparse,ctypes,importlib.util,json,shutil,subprocess,sys,zipfile
from ctypes import wintypes

sys.stdout.reconfigure(encoding='utf-8',errors='backslashreplace')
sys.stderr.reconfigure(encoding='utf-8',errors='backslashreplace')
spec=importlib.util.spec_from_file_location('phase_three_paper_common',Path(__file__).with_name('phase-three-paper-common.py'))
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run-dir',type=Path,required=True);p.add_argument('--expected-sha',required=True)
    p.add_argument('--pid',type=int,required=True);p.add_argument('--java-home',type=Path,required=True);a=p.parse_args()
    run=c.run_path(a.run_dir);manifest=c.manifest(run,a.expected_sha);evidence=run/'evidence';process_path=evidence/'phase-three-paper-processes.json'
    data=json.loads(process_path.read_text(encoding='utf8'))
    if len(data['boots'])!=1 or data['boots'][0].get('server_pid')!=a.pid or data['boots'][0].get('client_pid') is not None or data['boots'][0].get('all_owned_children_exited'):
        raise SystemExit('Only the actual incomplete pre-client launch PID is admissible')
    if a.pid!=4188:raise SystemExit('This reviewed one-shot recovery is restricted to actual abandoned PID 4188')
    saved=evidence/'preflight-original-process-table.json'
    if saved.exists():raise SystemExit('Recovery was already attempted; inspect evidence before another action')
    saved.write_bytes(process_path.read_bytes())
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD];kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes=[wintypes.HANDLE,wintypes.DWORD];kernel.WaitForSingleObject.restype=wintypes.DWORD
    kernel.GetExitCodeProcess.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.DWORD)];kernel.GetExitCodeProcess.restype=wintypes.BOOL
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handle=kernel.OpenProcess(0x00100000|0x1000,False,a.pid)
    if not handle:raise ctypes.WinError(ctypes.get_last_error())
    record={'pid':a.pid,'started_utc':c.now(),'purpose':'Recover normal shutdown after Python GBK stdout failure; no state reset','product_sha256':a.expected_sha}
    try:
        classes=run/'fixture/recovery-classes';classes.mkdir()
        source=c.ROOT/'tools/PhaseThreePaperStopAgent.java';copied=run/'fixture/PhaseThreePaperStopAgent.java';shutil.copy2(source,copied)
        compile_command=[str(a.java_home/'bin/javac.exe'),'-J-Xmx64m','--release','21','--add-modules','jdk.attach','-encoding','UTF-8','-d',str(classes),str(copied)]
        compiled=subprocess.run(compile_command,capture_output=True,timeout=60);(evidence/'preflight-recovery-compile.log').write_bytes(compiled.stdout+compiled.stderr)
        record.update(compile_command=compile_command,compile_exit_code=compiled.returncode,agent_source_sha256=c.digest(copied))
        if compiled.returncode:raise RuntimeError('Recovery agent compilation failed; no attach performed')
        agent=run/'fixture/phase-three-paper-stop-agent.jar'
        with zipfile.ZipFile(agent,'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nAgent-Class: smoke.PhaseThreePaperStopAgent\n\n')
            for path in classes.rglob('*.class'):jar.write(path,path.relative_to(classes).as_posix())
        marker=evidence/'preflight-normal-shutdown.json'
        command=[str(a.java_home/'bin/java.exe'),'-Xmx64m','--add-modules','jdk.attach','-cp',str(classes),'smoke.PhaseThreePaperStopAgent',str(a.pid),str(agent),str(run),a.expected_sha,str(marker)]
        attached=subprocess.run(command,capture_output=True,timeout=60);(evidence/'preflight-recovery-attach.log').write_bytes(attached.stdout+attached.stderr)
        record.update(attach_command=command,attach_exit_code=attached.returncode,agent_jar_sha256=c.digest(agent))
        if attached.returncode:raise RuntimeError('Normal-shutdown attach failed; do not kill unrelated processes')
        waited=kernel.WaitForSingleObject(handle,60000)
        if waited!=0:raise RuntimeError('The bounded server did not exit within sixty seconds')
        exit_code=wintypes.DWORD()
        if not kernel.GetExitCodeProcess(handle,ctypes.byref(exit_code)):raise ctypes.WinError(ctypes.get_last_error())
        if not marker.is_file():raise RuntimeError('No actual scheduled Bukkit shutdown marker')
        record.update(server_exit_code=exit_code.value,exited_utc=c.now(),normal_shutdown=json.loads(marker.read_text(encoding='utf8')),
                      source_files_unchanged=c.source_unchanged(manifest))
        if exit_code.value!=0:raise RuntimeError('Normal server recovery exit was nonzero')
        old=data['boots'][0];old.update(server_exit_code=exit_code.value,server_ended_utc=record['exited_utc'],
                                      launcher_problem='UnicodeEncodeError: GBK stdout could not encode upstream Unicode log; no client controller started',
                                      recovered_normal_bukkit_shutdown=True,all_owned_children_exited=True)
        data['preflight_attempts']=[old];data['boots']=[];data['server_exit_codes']=[];data['client_exit_codes']=[]
        data['retained_preflight_recovery']='preflight-recovery.json'
        source_log=evidence/'phase-three-paper-boot1.log'
        if source_log.exists():source_log.rename(evidence/'preflight-launcher-stdout.log')
        shutil.copy2(run/'logs/latest.log',evidence/'preflight-complete-server.log')
        c.write_json(process_path,data);manifest['status']='PREFLIGHT_RECOVERED_NO_STATE_RESET';c.write_json(run/'preparation.json',manifest)
    except Exception as error:
        record['recovery_failure']=repr(error);raise
    finally:
        kernel.CloseHandle(handle);c.write_json(evidence/'preflight-recovery.json',record)
    print(json.dumps(record,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
