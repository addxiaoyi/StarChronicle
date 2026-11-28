"""Real legacy serializer, strict file upgrade and independent JVM restarts; no server."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
def sha(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('old-jar','new-jar','test-xml','java-home','output'):p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
    old,new=a.old_jar.resolve(),a.new_jar.resolve()
    props=ET.parse(a.test_xml).getroot().find('properties')
    captured=next(v.get('value') for v in props if v.get('name')=='surefire.test.class.path')
    deps=[Path(v) for v in captured.split(os.pathsep) if v.endswith('.jar')];assert all(v.is_file() for v in deps)
    sources=[ROOT/'tools/CustomEmoteLegacyProbe.java',ROOT/'tools/CustomEmoteRecoveryProbe.java']
    result={'status':'RUNNING','server_started':False,'jars':{str(v):sha(v) for v in (old,new)},'sources':{v.name:sha(v) for v in sources},'runs':[]}
    def invoke(label,exe,values):
        file=out/(label+'.args');file.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8')
        vm=['-J-Xmx96m','-J-XX:ActiveProcessorCount=1'] if exe.name.startswith('javac') else []
        response=subprocess.run([str(exe),*vm,'@'+str(file)],capture_output=True,timeout=120)
        (out/(label+'.log')).write_bytes(response.stdout+response.stderr)
        assert response.returncode==0,(label,response.returncode)
        return response
    def cp(jar):return os.pathsep.join(str(v) for v in [jar,out,*deps])
    def run(phase,folder,jar=new):
        label=str(len(result['runs'])+1)+'-'+phase+'-'+folder.name
        entry='CustomEmoteLegacyProbe' if phase=='legacy' else 'CustomEmoteRecoveryProbe'
        values=[folder] if phase=='legacy' else [phase,folder]
        response=invoke(label,a.java_home/'bin/java.exe',['-Xms8m','-Xmx64m','-XX:MaxMetaspaceSize=96m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-Dfile.encoding=UTF-8','-cp',cp(jar),entry,*values])
        verified=json.loads(response.stdout.decode('utf-8').strip());assert verified['verified']
        result['runs'].append({'phase':phase,'case':folder.name,'jar':str(jar),'exit_code':response.returncode})
    try:
        invoke('compile',a.java_home/'bin/javac.exe',['-encoding','UTF-8','-proc:none','--release','21','-cp',cp(new),'-d',out,*sources])
        folder=out/'roundtrip';run('legacy',folder,old);shutil.copytree(folder,out/'legacy-source')
        original=sha(folder/'custom-emotes.yml');run('upgrade',folder);run('reopen',folder)
        assert sha(folder/('custom-emotes.yml.legacy-'+original+'.bak'))==original
        for case,raw in [('broken',b'customEmotes: ['),('duplicate',b'customEmotes: []\ncustomEmotes: []\n'),('future',b'schema: 99\ncustomEmotes: []\n'),('utf8',b'customEmotes: "\xc3\x28"')]:
            copy=out/case;copy.mkdir();(copy/'custom-emotes.yml').write_bytes(raw);before=sha(copy/'custom-emotes.yml');run('reject',copy);assert sha(copy/'custom-emotes.yml')==before
        for case in ('incomplete','missing'):
            copy=out/case;shutil.copytree(folder,copy)
            if case=='missing':(copy/'custom-emotes.yml').unlink()
            else:(copy/'custom-emotes.yml.tmp').write_text('incomplete',encoding='utf-8')
            run('reject',copy)
        result.update(status='VERIFIED',original_sha256=original)
    except Exception as failure:result.update(status='FAILED',error=repr(failure));raise
    finally:(out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'server_started':False}))
if __name__=='__main__':main()
