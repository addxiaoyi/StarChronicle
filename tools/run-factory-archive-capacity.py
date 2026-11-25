"""Compare full runtime history with SQL archival using exact frozen services and independent evidence."""
import argparse,hashlib,importlib.util,json,os,shutil,sqlite3,struct,subprocess,time
from pathlib import Path


def module(name,filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename));result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


def framed(values):
    result=hashlib.sha256()
    for value in values:
        raw=str(value).encode();result.update(struct.pack('>I',len(raw)));result.update(raw)
    return result.hexdigest()


def logical_database(path):
    stable=module('stable','run-factory-stable-capacity.py')
    state,receipts=stable.read_database(path)
    if state[stable.PREFIX+'version']!='3':return stable.logical(state),receipts
    prefix=stable.PREFIX
    assert framed([x for key in sorted(k for k in state if (k.startswith(prefix) or k.startswith('factory.')) and k!=prefix+'head') for x in (key,state[key])])==state[prefix+'head']
    runs={}
    with sqlite3.connect(path) as db:
        db.row_factory=sqlite3.Row;chain='';count=0
        for row in db.execute('SELECT * FROM starcore_resource_factory_archive ORDER BY archive_sequence'):
            count+=1;assert row['archive_sequence']==count
            run=dict(zip(('id','domain','factory','nation','actor','recipe','batches','inputs','outputs','started','ready','completed','sequence'),
                         (row['run_id'],row['authority_domain_id'],row['factory_id'],row['nation_id'],row['actor_id'] or '',row['recipe_id'],str(row['batches']),row['inputs'],row['outputs'],str(row['started_at']),str(row['ready_at']),str(row['completed_at']),str(row['completion_sequence']))))
            assert hashlib.sha256(run['id'].encode()).hexdigest()==row['run_key']
            assert framed(run.values())==row['row_hash']
            chain=framed((chain,count,row['row_hash']));assert chain==row['chain_hash']
            assert run['id'] not in runs;runs[run['id']]=run
        assert str(count)==state[prefix+'archive-count'] and chain==state[prefix+'archive-head']
    # Validate active row framing using the V2 verifier; recompute only the synthetic comparison header.
    active={k:v for k,v in state.items() if k not in (prefix+'archive-count',prefix+'archive-head')};active[prefix+'version']='2'
    active[prefix+'head']=framed([x for k in sorted(k for k in active if (k.startswith(prefix) or k.startswith('factory.')) and k!=prefix+'head') for x in (k,active[k])])
    result=stable.logical(active)
    assert not set(result['runs'])&set(runs);runs.update(result['runs']);result['runs']=runs;result['state'][prefix+'count']=str(len(runs))
    return result,receipts


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('previous','starcore','chronicle','runtime','java-home','output'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--runs',type=int,default=5000);args=parser.parse_args()
    if args.runs<1000:parser.error('Use at least 1000 historical completions')
    cap=module('capacity','run-capacity-contract.py');out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    frozen=out/'artifacts';frozen.mkdir();classes=out/'classes';classes.mkdir()
    sources=[Path(__file__).with_name(name) for name in ('FactoryRecoveryProbe.java','FactoryArchiveProbe.java')]
    for path,name in [(args.previous,'previous.jar'),(args.starcore,'current.jar'),(args.chronicle,'starchronicle.jar'),*((p,p.name) for p in sources)]:shutil.copy2(path,frozen/name)
    libs=sorted((args.runtime/'libraries').rglob('*.jar'));assert libs
    common=[frozen/'starchronicle.jar',*libs];suffix='.exe' if os.name=='nt' else ''
    def cp(jar):return os.pathsep.join(str(p.resolve()) for p in (classes,frozen/(jar+'.jar'),*common))
    manifest={'status':'RUNNING','scope':'Serial actual ResourceModule, SQLite, frozen V2 writer; atomic archive migration, 20 grants, exact old-ID replay, fresh-process restart; no Minecraft or long concurrent load',
              'completed_runs':args.runs,'artifacts':{p.name:cap.sha(p) for p in frozen.iterdir()},'phases':{}}
    def save():(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    def run(label,program,arguments,launcher=()):
        af=out/(label+'.args');af.write_text('\n'.join(json.dumps(str(a).replace('\\','/')) for a in arguments)+'\n')
        start=time.monotonic()
        with (out/(label+'.log')).open('wb') as log:r=subprocess.run([str(args.java_home/'bin'/(program+suffix)),*launcher,'@'+str(af)],stdout=log,stderr=subprocess.STDOUT,timeout=900)
        manifest['phases'][label]={'exit_code':r.returncode,'elapsed_seconds':round(time.monotonic()-start,3),'log_sha256':cap.sha(out/(label+'.log'))};save()
        if r.returncode:raise RuntimeError(label+' failed; raw log retained')
        print(label,'PASS',flush=True)
    vm=['-Xmx128m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=32m','-Dfile.encoding=UTF-8'];package='dev.starcore.starcore.module.resource.'
    try:
        run('compile','javac',['-proc:none','-encoding','UTF-8','--release','21','-cp',cp('previous'),'-d',classes,*(frozen/p.name for p in sources)],['-J-Xmx96m','-J-XX:+UseSerialGC'])
        original=out/'original';original.mkdir();run('seed','java',['-Xmx256m',*vm[1:],'-cp',cp('previous'),package+'FactoryRecoveryProbe','seed',original,args.runs])
        before,receipts=logical_database(original/'factory.db');tables=cap.original_tables(original/'factory.db');(out/'original-tables.json').write_text(json.dumps(tables,indent=2)+'\n')
        expected=json.loads(json.dumps(before));expected['state']['stockpile.0.amount']=str(args.runs*2+21)
        for build in ('previous','current'):
            target=out/build;target.mkdir();shutil.copy2(original/'factory.db',target/'factory.db')
            for phase in ('run','restart'):
                label=build+'-'+phase
                run(label,'java',[*vm,'-cp',cp(build),package+'FactoryArchiveProbe',phase,target,args.runs,out/(label+'.json')])
                actual,after_receipts=logical_database(target/'factory.db');assert actual==expected;assert after_receipts==receipts
                after=cap.original_tables(target/'factory.db')
                for table,proof in tables.items():
                    if table!='starcore_resource_state':assert after[table]==proof
                if phase=='restart':assert after==first_tables
                first_tables=after
                report=json.loads((out/(label+'.json')).read_text());assert report['format']==('3' if build=='current' else '2')
                assert report['retained_run_objects']==(1 if build=='current' else args.runs+1)
                if build=='current':assert report['state_properties']<100
                manifest['phases'][label]['all_original_runs_and_receipts_preserved']=True
            (out/(build+'-tables.json')).write_text(json.dumps(after,indent=2)+'\n')
        manifest['status']='PASS'
    except BaseException:manifest['status']='FAILED';raise
    finally:save()


if __name__=='__main__':main()
