"""Old/new export compatibility and malformed copy rejection, serial isolated JVMs; no server."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('old-jar','new-jar','java-home','test-xml','output'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
    old,new=a.old_jar.resolve(),a.new_jar.resolve();props=ET.parse(a.test_xml).getroot().find('properties')
    cp=next(p.get('value') for p in props if p.get('name')=='surefire.test.class.path');deps=[Path(p) for p in cp.split(os.pathsep) if p.endswith('.jar')]
    assert all(p.is_file() for p in deps);agent=next(p for p in deps if p.name.startswith('mockito-core-'))
    source=ROOT/'tools/SocialExportFilesProbe.java';result={'status':'RUNNING','server_started':False,'old_jar_sha256':digest(old),'new_jar_sha256':digest(new),'probe_sha256':digest(source),'runs':[]}
    def argsfile(label,values):
        path=out/(label+'.args');path.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8');return path
    def classpath(jar):return os.pathsep.join(str(p) for p in [jar,out,*deps])
    def run(phase,folder,jar=new):
        label=str(len(result['runs'])+1)+'-'+phase+'-'+folder.name
        values=['-Xms8m','-Xmx96m','-XX:MaxMetaspaceSize=128m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-Dfile.encoding=UTF-8','-javaagent:'+str(agent),'-cp',classpath(jar),'SocialExportFilesProbe',phase,folder]
        r=subprocess.run([str(a.java_home/'bin/java.exe'),'@'+str(argsfile(label,values))],capture_output=True,timeout=90)
        (out/(label+'.log')).write_bytes(r.stdout+r.stderr);result['runs'].append({'phase':phase,'case':folder.name,'jar':str(jar),'exit_code':r.returncode})
        assert r.returncode==0,label
        lines=[x for x in r.stdout.decode('utf-8').splitlines() if x.startswith('{')];assert len(lines)==1 and json.loads(lines[0])['verified']
    try:
        r=subprocess.run([str(a.java_home/'bin/javac.exe'),'-J-Xmx96m','-J-XX:ActiveProcessorCount=1','@'+str(argsfile('compile',['-encoding','UTF-8','-proc:none','--release','21','-cp',classpath(new),'-d',out,source]))],capture_output=True,timeout=90)
        (out/'compile.log').write_bytes(r.stdout+r.stderr);assert r.returncode==0,'compile'
        folder=out/'roundtrip';run('old-write',folder,old);shutil.copytree(folder,out/'legacy-source');run('upgrade',folder);run('reopen',folder)
        for case,value in [('duplicate',b'{"friends":[],"friends":[]}'),('future',b'{"version":"99.0","friends":[]}'),('deep',b'{"value":'+b'['*66+b'0'+b']'*66+b'}'),('utf8',b'{"value":"\xc3\x28"}')]:
            folder=out/case;(folder/'social_exports').mkdir(parents=True);path=folder/'social_exports/reject.json';path.write_bytes(value);before=digest(path);run('reject',folder);assert digest(path)==before
        result['status']='VERIFIED'
    except Exception as failure:result.update(status='FAILED',error=repr(failure));raise
    finally:(out/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'server_started':False}))
if __name__=='__main__':main()
