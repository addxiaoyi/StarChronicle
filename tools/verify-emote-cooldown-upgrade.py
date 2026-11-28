"""Actual old serialized cooldowns and two independent new-service JVM reopens; no server."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]

def sha(path):
    with path.open('rb') as source: return hashlib.file_digest(source, 'sha256').hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('old-jar', 'new-jar', 'test-xml', 'java-home', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    jars = [args.old_jar.resolve(), args.new_jar.resolve()]
    properties = ET.parse(args.test_xml).getroot().find('properties')
    captured = next(p.get('value') for p in properties if p.get('name') == 'surefire.test.class.path')
    deps = [Path(p) for p in captured.split(os.pathsep) if p.endswith('.jar')]
    assert all(p.is_file() for p in deps)
    sources = [ROOT/'tools/EmoteCooldownLegacyProbe.java', ROOT/'tools/EmoteCooldownOfflineProbe.java']
    result = {'status':'RUNNING', 'server_started':False, 'jars':{str(p):sha(p) for p in jars},
              'sources':{p.name:sha(p) for p in sources}, 'runs':[]}
    def invoke(name, executable, values):
        argfile = out/(name+'.args')
        argfile.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8')
        jvm = ['-J-Xmx96m','-J-XX:ActiveProcessorCount=1'] if executable.name.startswith('javac') else []
        response = subprocess.run([str(executable), *jvm, '@'+str(argfile)], capture_output=True, timeout=120)
        (out/(name+'.log')).write_bytes(response.stdout+response.stderr)
        assert response.returncode == 0, (name, response.returncode)
        return response
    def cp(jar): return os.pathsep.join(str(p) for p in [jar,out,*deps])
    def run(name, jar, entry, *paths):
        response = invoke(name, args.java_home/'bin/java.exe', ['-Xms8m','-Xmx64m','-XX:MaxMetaspaceSize=96m',
            '-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-Dfile.encoding=UTF-8','-cp',cp(jar),entry,*paths])
        verified = json.loads(response.stdout.decode('utf-8').strip()); assert verified['verified']
        result['runs'].append({'name':name,'jar':str(jar),'exit_code':response.returncode, **verified})
    try:
        invoke('compile', args.java_home/'bin/javac.exe', ['-encoding','UTF-8',
               '-proc:none','--release','21','-cp',cp(jars[1]),'-d',out,*sources])
        original = out/'legacy.json'; upgraded = out/'upgraded.json'
        run('legacy', jars[0], 'EmoteCooldownLegacyProbe', original)
        original_hash = sha(original)
        run('upgrade', jars[1], 'dev.starcore.starcore.social.emote.EmoteCooldownOfflineProbe', original, upgraded)
        run('reopen', jars[1], 'dev.starcore.starcore.social.emote.EmoteCooldownOfflineProbe', upgraded)
        assert original_hash == sha(original)
        def normalize(path):
            data = json.loads(path.read_text())
            for player in data['cooldowns']['playerCooldowns']: player['cooldowns'].sort()
            data['cooldowns']['playerCooldowns'].sort(key=lambda player:player['playerId'])
            return data
        assert normalize(original) == normalize(upgraded)
        assert json.loads(original.read_text())['oldAttached'] == {'playerCooldowns':[]}
        result.update(status='VERIFIED', original_sha256=original_hash, upgraded_sha256=sha(upgraded),
            limits='In-memory cooldown API serialization only. No persistent cooldown store, server, animation or region scheduling exercised; old attached-mode omitted evidence cannot be reconstructed.')
    except Exception as failure:
        result.update(status='FAILED', error=repr(failure)); raise
    finally:
        (out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':result['status'],'jvms':len(result['runs']),'server_started':False}))

if __name__ == '__main__': main()
