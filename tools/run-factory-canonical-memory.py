"""Compare exact V1/V2 recovery allocations using frozen writers and retained SQLite/file evidence."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import time


def module(name,filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('legacy','previous','starcore','chronicle','runtime','java-home','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--runs',type=int,default=5000)
    parser.add_argument('--heap-mib',type=int,default=96)
    args=parser.parse_args()
    if args.runs<1000 or not 64<=args.heap_mib<=256:parser.error('Use >=1000 runs and a 64..256 MiB heap')
    capacity=module('capacity','run-capacity-contract.py');stable=module('stable','run-factory-stable-capacity.py')
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    frozen=output/'artifacts';frozen.mkdir();classes=output/'classes';classes.mkdir()
    sources=[Path(__file__).with_name(name) for name in ('FactoryRecoveryProbe.java','FactoryCanonicalMemoryProbe.java')]
    for source,name in [(args.legacy,'legacy.jar'),(args.previous,'previous.jar'),(args.starcore,'current.jar'),(args.chronicle,'starchronicle.jar'),*((p,p.name) for p in sources)]:shutil.copy2(source,frozen/name)
    libraries=sorted((args.runtime/'libraries').rglob('*.jar'))
    if not libraries:raise RuntimeError('Runtime libraries missing')
    common=[frozen/'starchronicle.jar',*libraries];suffix='.exe' if os.name=='nt' else ''
    manifest={'status':'RUNNING','scope':'Serial packaged factory recovery, actual file/SQLite, exact V1/V2 frozen writers, HotSpot thread allocation counter; no Minecraft or whole-module capacity claim',
              'completed_runs':args.runs,'heap_mib':args.heap_mib,'artifacts':{p.name:capacity.sha(p) for p in frozen.iterdir()},'phases':{}}
    def save():(output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    def cp(jar):return os.pathsep.join(str(p.resolve()) for p in [classes,frozen/(jar+'.jar'),*common])
    def run(label,program,arguments,launcher=()):
        argument_file=output/(label+'.args');argument_file.write_text('\n'.join(json.dumps(str(a).replace('\\','/')) for a in arguments)+'\n',encoding='utf-8')
        start=time.monotonic()
        with (output/(label+'.log')).open('wb') as log:
            result=subprocess.run([str(args.java_home/'bin'/(program+suffix)),*launcher,'@'+str(argument_file)],stdout=log,stderr=subprocess.STDOUT,timeout=900)
        manifest['phases'][label]={'exit_code':result.returncode,'elapsed_seconds':round(time.monotonic()-start,3),'log_sha256':capacity.sha(output/(label+'.log'))};save()
        if result.returncode:raise RuntimeError(label+' failed; see retained log')
        print(label,'PASS',flush=True)
    vm=['-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=32m','-Dfile.encoding=UTF-8']
    package='dev.starcore.starcore.module.resource.'
    try:
        run('compile','javac',['-proc:none','-encoding','UTF-8','--release','21','-cp',cp('previous'),'-d',classes,*(frozen/p.name for p in sources)],['-J-Xmx96m','-J-XX:+UseSerialGC'])
        measurements={};logical_snapshots=[]
        for version,writer in [('v1','legacy'),('v2','previous')]:
            original=output/(version+'-original');original.mkdir()
            run(version+'-seed','java',['-Xmx256m',*vm,'-cp',cp(writer),package+'FactoryRecoveryProbe','seed',original,args.runs])
            before=capacity.original_tables(original/'factory.db');file_hash=capacity.sha(original/'factory.properties')
            state,receipts=stable.read_database(original/'factory.db');assert state[stable.PREFIX+'version']==version[-1]
            logical_snapshots.append((stable.logical(state),receipts))
            (output/(version+'-original-tables.json')).write_text(json.dumps(before,indent=2)+'\n',encoding='utf-8')
            for build in ('previous','current'):
                target=output/(version+'-'+build);target.mkdir()
                for name in ('factory.db','factory.properties'):shutil.copy2(original/name,target/name)
                for mode in ('file','sql'):
                    for phase in ('first','restart'):
                        label='-'.join((version,build,mode,phase))
                        run(label,'java',[f'-Xmx{args.heap_mib}m',*vm,'-cp',cp(build),package+'FactoryCanonicalMemoryProbe',mode,target,args.runs,output/(label+'.json')])
                        assert capacity.sha(target/'factory.properties')==file_hash
                        assert capacity.original_tables(target/'factory.db')==before
                        report=json.loads((output/(label+'.json')).read_text(encoding='utf-8'))
                        assert report['completed']==args.runs and report['format']==version[-1]
                        assert report['max_heap_bytes']<=args.heap_mib*1024*1024
                        assert report['recovery_thread_allocated_bytes']>0
                        if mode=='file':assert report['file_loads']==1
                        measurements[label]=report
                        manifest['phases'][label]['all_tables_and_file_unchanged']=True
            for mode in ('file','sql'):
                old=sum(measurements[f'{version}-previous-{mode}-{phase}']['recovery_thread_allocated_bytes'] for phase in ('first','restart'))
                new=sum(measurements[f'{version}-current-{mode}-{phase}']['recovery_thread_allocated_bytes'] for phase in ('first','restart'))
                assert new<old,('Expected less recovery allocation',version,mode,old,new)
        assert logical_snapshots[0]==logical_snapshots[1],'Frozen V1 and V2 represent identical logical evidence'
        manifest['same_logical_evidence_across_formats']=True;manifest['status']='PASS'
    except BaseException:manifest['status']='FAILED';raise
    finally:save()


if __name__=='__main__':main()
