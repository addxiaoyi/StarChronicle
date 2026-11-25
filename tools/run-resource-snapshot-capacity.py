"""Compare actual native resource grants while retaining completed factory evidence."""
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
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('previous','starcore','chronicle','runtime','java-home','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--runs',type=int,default=1000)
    parser.add_argument('--grants',type=int,default=50)
    args=parser.parse_args()
    if args.runs<257 or args.grants<10:parser.error('Use at least 257 runs and 10 grants')
    spec=importlib.util.spec_from_file_location('capacity',Path(__file__).with_name('run-capacity-contract.py'))
    capacity=importlib.util.module_from_spec(spec);spec.loader.exec_module(capacity)
    stable_spec=importlib.util.spec_from_file_location('stable',Path(__file__).with_name('run-factory-stable-capacity.py'))
    stable=importlib.util.module_from_spec(stable_spec);stable_spec.loader.exec_module(stable)
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    frozen=output/'artifacts';frozen.mkdir();classes=output/'classes';classes.mkdir()
    sources=[Path(__file__).with_name(name) for name in ('FactoryRecoveryProbe.java','ResourceSnapshotWriteProbe.java')]
    for source,name in [(args.previous,'previous.jar'),(args.starcore,'current.jar'),(args.chronicle,'starchronicle.jar'),*((p,p.name) for p in sources)]:shutil.copy2(source,frozen/name)
    libraries=sorted((args.runtime/'libraries').rglob('*.jar'))
    if not libraries:raise RuntimeError('Runtime libraries missing')
    common=[frozen/'starchronicle.jar',*libraries];suffix='.exe' if os.name=='nt' else ''
    manifest={'status':'RUNNING','scope':'Serial 64 MiB JVMs; actual packaged ResourceModule grants, SQLite and JDBC row counts; synthetic historical batches; no Minecraft or concurrent throughput claim',
              'completed_runs':args.runs,'grants':args.grants,'artifacts':{p.name:capacity.sha(p) for p in frozen.iterdir()},'phases':{}}
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
    vm=['-Xmx64m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=32m','-Dfile.encoding=UTF-8']
    package='dev.starcore.starcore.module.resource.'
    try:
        run('compile','javac',['-proc:none','-encoding','UTF-8','--release','21','-cp',cp('previous'),'-d',classes,*(frozen/p.name for p in sources)],['-J-Xmx96m','-J-XX:+UseSerialGC'])
        original=output/'original';original.mkdir()
        run('seed','java',[*vm,'-cp',cp('previous'),package+'FactoryRecoveryProbe','seed',original,args.runs])
        before=capacity.original_tables(original/'factory.db')
        with sqlite3.connect(original/'factory.db') as db:state_before=dict(db.execute('SELECT property_key,property_value FROM starcore_resource_state'))
        (output/'original-tables.json').write_text(json.dumps(before,indent=2)+'\n',encoding='utf-8')
        for version in ('previous','current'):
            target=output/version;target.mkdir();shutil.copy2(original/'factory.db',target/'factory.db')
            run(version,'java',[*vm,'-cp',cp(version),package+'ResourceSnapshotWriteProbe',target,args.runs,args.grants,output/(version+'.json')])
            after=capacity.original_tables(target/'factory.db')
            for table,expected in before.items():
                if table!='starcore_resource_state':assert after[table]==expected,table
            with sqlite3.connect(target/'factory.db') as db:
                assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
                state_after=dict(db.execute('SELECT property_key,property_value FROM starcore_resource_state'))
            expected=dict(state_before);expected['stockpile.0.amount']=str(args.runs*2+args.grants)
            assert stable.logical(state_after)==stable.logical(expected),('Unexpected logical state change',version)
            report=json.loads((output/(version+'.json')).read_text(encoding='utf-8'))
            if version=='current':
                # The first grant may also convert every V1 key to V2, atomically. Subsequent
                # grants must each replace exactly the one changed inventory property.
                inserted=sum(state_before.get(key)!=value for key,value in state_after.items())
                deleted=sum(state_after.get(key)!=value for key,value in state_before.items())
                assert report['state_rows_inserted']==inserted+args.grants-1
                assert report['state_rows_deleted']==deleted+args.grants-1
                assert report['state_rows_inserted']-report['first_grant_inserted']==args.grants-1
                assert report['state_rows_deleted']-report['first_grant_deleted']==args.grants-1
            manifest['phases'][version]['independent_validation']='PASS'
            manifest['phases'][version]['only_expected_inventory_changed']=True
        manifest['status']='PASS'
    except BaseException:manifest['status']='FAILED';raise
    finally:save()


if __name__=='__main__':main()
