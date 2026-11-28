"""Measure descending factory IDs and V1/V2 recovery with actual packaged services and SQLite."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import struct
import subprocess
import time


PREFIX = 'resource.factory-runs.'
FIELDS = {'id','domain','factory','nation','actor','recipe','batches','inputs','outputs','started','ready','completed','sequence'}


def logical(state):
    version = state[PREFIX+'version']
    assert version in ('1','2')
    digest = hashlib.sha256()
    for key in sorted(k for k in state if (k.startswith(PREFIX) or k.startswith('factory.')) and k != PREFIX+'head'):
        for value in (key, state[key]):
            encoded=value.encode('utf-8');digest.update(struct.pack('>I',len(encoded)));digest.update(encoded)
    assert digest.hexdigest()==state[PREFIX+'head'], 'Snapshot fingerprint'
    rows={}
    for key,value in state.items():
        if key.startswith(PREFIX+'row.'):
            row,field=key[len(PREFIX+'row.'):].split('.',1)
            rows.setdefault(row,{})[field]=value
    assert len(rows)==int(state[PREFIX+'count'])
    runs={}
    ordered=sorted(row['id'] for row in rows.values())
    for key,row in rows.items():
        assert set(row)==FIELDS
        assert key==(str(ordered.index(row['id'])) if version=='1' else hashlib.sha256(row['id'].encode('utf-8')).hexdigest())
        assert row['id'] not in runs
        runs[row['id']]=row
    rest={k:v for k,v in state.items() if not k.startswith(PREFIX+'row.') and k not in {PREFIX+'version',PREFIX+'head'}}
    return {'runs':runs,'state':rest}


def read_database(path):
    with sqlite3.connect(path) as db:
        assert db.execute('PRAGMA integrity_check').fetchone()==('ok',)
        assert not db.execute('PRAGMA foreign_key_check').fetchall()
        state=dict(db.execute('SELECT property_key,property_value FROM starcore_resource_state'))
        receipts=db.execute('SELECT * FROM starcore_resource_production_receipts ORDER BY sequence').fetchall()
    return state,receipts


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('previous','starcore','chronicle','runtime','java-home','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--runs',type=int,default=1000)
    parser.add_argument('--batches',type=int,default=20)
    args=parser.parse_args()
    if args.runs<257 or args.batches<10:parser.error('Use at least 257 historical runs and 10 new batches')
    spec=importlib.util.spec_from_file_location('capacity',Path(__file__).with_name('run-capacity-contract.py'))
    capacity=importlib.util.module_from_spec(spec);spec.loader.exec_module(capacity)
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    frozen=output/'artifacts';frozen.mkdir();classes=output/'classes';classes.mkdir()
    sources=[Path(__file__).with_name(name) for name in ('FactoryRecoveryProbe.java','ResourceSnapshotWriteProbe.java','FactoryStableWriteProbe.java')]
    for source,name in [(args.previous,'previous.jar'),(args.starcore,'current.jar'),(args.chronicle,'starchronicle.jar'),*((p,p.name) for p in sources)]:shutil.copy2(source,frozen/name)
    libraries=sorted((args.runtime/'libraries').rglob('*.jar'))
    if not libraries:raise RuntimeError('Runtime libraries missing')
    common=[frozen/'starchronicle.jar',*libraries];suffix='.exe' if os.name=='nt' else ''
    manifest={'status':'RUNNING','scope':'Serial 64 MiB JVMs; actual packaged timed-factory starts/completions, SQLite and JDBC row counts; synthetic historical batches; no Minecraft or concurrent throughput claim',
              'completed_runs':args.runs,'new_batches':args.batches,'artifacts':{p.name:capacity.sha(p) for p in frozen.iterdir()},'phases':{}}
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
        before=capacity.original_tables(original/'factory.db');state_before,receipts_before=read_database(original/'factory.db');old=logical(state_before)
        assert state_before[PREFIX+'version']=='1'
        (output/'original-tables.json').write_text(json.dumps(before,indent=2)+'\n',encoding='utf-8')
        final={}
        for version in ('previous','current'):
            target=output/version;target.mkdir();shutil.copy2(original/'factory.db',target/'factory.db')
            run(version,'java',[*vm,'-cp',cp(version),package+'FactoryStableWriteProbe','run',target,args.runs,args.batches,output/(version+'.json')])
            state_after,receipts_after=read_database(target/'factory.db');current=logical(state_after)
            assert receipts_after[:args.runs]==receipts_before
            assert len(receipts_after)==len(current['runs'])==args.runs+1+args.batches
            for i in range(args.runs):assert current['runs']['batch-'+str(i)]==old['runs']['batch-'+str(i)]
            active_id='batch-'+str(args.runs)
            active=dict(current['runs'][active_id]);active['completed']='';active['sequence']='0'
            assert active==old['runs'][active_id]
            for i in range(args.batches,0,-1):
                row=current['runs'][f'000-{i:05d}'];assert row['sequence']==str(args.runs+args.batches-i+2)
                assert row['inputs']=='energy=1;mineral=3' and row['outputs']=='industrial=2'
            report=json.loads((output/(version+'.json')).read_text(encoding='utf-8'))
            assert report['format']==('1' if version=='previous' else '2')
            assert report['max_heap_bytes']<=64*1024*1024
            if version=='current':
                assert report['state_rows_inserted']+report['state_rows_deleted']<=args.batches*80
                assert all(row['inserted']+row['deleted']<=50 for row in report['writes'])
            final[version]=(current,receipts_after)
            after=capacity.original_tables(target/'factory.db')
            for table,expected in before.items():
                if table not in ('starcore_resource_state','starcore_resource_production_receipts'):assert after[table]==expected
            (output/(version+'-tables.json')).write_text(json.dumps(after,indent=2)+'\n',encoding='utf-8')
            manifest['phases'][version]['independent_validation']='PASS'
            run(version+'-restart','java',[*vm,'-cp',cp(version),package+'FactoryStableWriteProbe','restart',target,args.runs,args.batches,output/(version+'-restart.json')])
            assert capacity.original_tables(target/'factory.db')==after
            manifest['phases'][version+'-restart']['all_tables_unchanged']=True
        assert final['previous']==final['current'], 'V1 and V2 must represent identical final evidence'
        manifest['same_final_logical_state_and_production_rows']=True
        manifest['status']='PASS'
    except BaseException:manifest['status']='FAILED';raise
    finally:save()


if __name__=='__main__':main()
