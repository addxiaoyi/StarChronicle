"""Compare packaged file/SQLite factory recovery with retained retry keys, in serial small JVMs."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('previous','starcore','chronicle','runtime','java-home','output'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--runs', type=int, default=1000)
    args=parser.parse_args()
    if args.runs<257: parser.error('Use at least 257 completed runs')
    spec=importlib.util.spec_from_file_location('capacity',Path(__file__).with_name('run-capacity-contract.py'))
    capacity=importlib.util.module_from_spec(spec);spec.loader.exec_module(capacity)
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    frozen=output/'artifacts';frozen.mkdir();classes=output/'classes';classes.mkdir()
    for source,name in [(args.previous,'previous.jar'),(args.starcore,'current.jar'),(args.chronicle,'starchronicle.jar'),(Path(__file__).with_name('FactoryRecoveryProbe.java'),'FactoryRecoveryProbe.java')]:
        shutil.copy2(source,frozen/name)
    libraries=sorted((args.runtime/'libraries').rglob('*.jar'))
    if not libraries: raise RuntimeError('Runtime libraries missing')
    common=[frozen/'starchronicle.jar',*libraries]
    manifest={'status':'RUNNING','scope':'Serial 64 MiB JVMs, packaged native recovery codec/storage, synthetic batches, actual file reads and SQLite; no Minecraft or ongoing production capacity certification',
              'completed_runs':args.runs,'active_runs':1,'artifacts':{p.name:capacity.sha(p) for p in frozen.iterdir()},'phases':{}}
    suffix='.exe' if os.name=='nt' else ''
    def save(): (output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    def cp(jar): return os.pathsep.join(str(p.resolve()) for p in [classes,frozen/(jar+'.jar'),*common])
    def run(label,program,arguments,launcher=()):
        argument_file=output/(label+'.args');argument_file.write_text('\n'.join(json.dumps(str(a).replace('\\','/')) for a in arguments)+'\n',encoding='utf-8')
        start=time.monotonic()
        with (output/(label+'.log')).open('wb') as log:
            result=subprocess.run([str(args.java_home/'bin'/(program+suffix)),*launcher,'@'+str(argument_file)],stdout=log,stderr=subprocess.STDOUT,timeout=900)
        manifest['phases'][label]={'exit_code':result.returncode,'elapsed_seconds':round(time.monotonic()-start,3),'log_sha256':capacity.sha(output/(label+'.log'))};save()
        if result.returncode: raise RuntimeError(label+' failed; see retained log')
        print(label,'PASS',flush=True)
    vm=['-Xmx64m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=32m','-Dfile.encoding=UTF-8']
    main_class='dev.starcore.starcore.module.resource.FactoryRecoveryProbe'
    try:
        run('compile','javac',['-proc:none','-encoding','UTF-8','--release','21','-cp',cp('previous'),'-d',classes,frozen/'FactoryRecoveryProbe.java'],['-J-Xmx96m','-J-XX:+UseSerialGC'])
        original=output/'original';original.mkdir()
        run('seed','java',[*vm,'-cp',cp('previous'),main_class,'seed',original,args.runs])
        before=capacity.original_tables(original/'factory.db');file_hash=capacity.sha(original/'factory.properties')
        (output/'original-tables.json').write_text(json.dumps(before,indent=2)+'\n',encoding='utf-8')
        for version in ('previous','current'):
            target=output/version;target.mkdir()
            for name in ('factory.db','factory.properties'): shutil.copy2(original/name,target/name)
            for mode in ('file','sql'):
                for phase in (('first',) if version=='previous' else ('first','restart')):
                    label=version+'-'+mode+'-'+phase
                    run(label,'java',[*vm,'-cp',cp(version),main_class,mode,target,args.runs,output/(label+'.json')])
                    assert capacity.sha(target/'factory.properties')==file_hash
                    assert capacity.original_tables(target/'factory.db')==before
                    with sqlite3.connect(target/'factory.db') as db:
                        assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
                        assert db.execute('SELECT COUNT(*) FROM starcore_resource_production_receipts').fetchone()[0]==args.runs
                    report=json.loads((output/(label+'.json')).read_text(encoding='utf-8'))
                    if version=='current' and mode=='file': assert report['file_loads']==1
                    manifest['phases'][label]['original_tables_preserved']=len(before)
                    manifest['phases'][label]['file_bytes_preserved']=True
                    manifest['phases'][label]['independent_validation']='PASS'
        manifest['status']='PASS'
    except BaseException:
        manifest['status']='FAILED';raise
    finally: save()


if __name__=='__main__': main()
