"""Upgrade a real schema9 database copy with the tested JAR, then reject it with the old JAR."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import sqlite3
import subprocess


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()


def snapshot(path):
    with sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True) as c:
        version=c.execute('PRAGMA user_version').fetchone()[0]
        assert c.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        assert not c.execute('PRAGMA foreign_key_check').fetchall()
        tables=[r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
        return version,{name:sorted(c.execute('SELECT * FROM "'+name+'"').fetchall(),key=repr) for name in tables}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('source','old-jar','new-jar','java-home','output'): p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args(); a.output.mkdir(exist_ok=False,parents=True)
    before_version,before=snapshot(a.source); assert before_version==9
    copy=a.output/'chronicle.db'; shutil.copy2(a.source,copy)
    source=Path(__file__).with_name('ProcessingSchemaProbe.java')
    def execute(cmd,log):
        with (a.output/log).open('x') as stream: subprocess.run([str(s) for s in cmd],stdout=stream,stderr=subprocess.STDOUT,check=True)
    execute([a.java_home/'bin/javac','--release','21','-cp',a.new_jar,'-d',a.output,source],'compile.log')
    execute([a.java_home/'bin/java','-cp',str(a.output)+':'+str(a.new_jar),'ProcessingSchemaProbe','upgrade',copy],'upgrade.log')
    version,after=snapshot(copy); assert version==10 and all(after.get(k)==v for k,v in before.items())
    upgraded_sha=sha(copy)
    execute([a.java_home/'bin/java','-cp',str(a.output)+':'+str(a.old_jar),'ProcessingSchemaProbe','reject',copy],'old-jar-rejection.log')
    assert sha(copy)==upgraded_sha
    assert 'UPGRADED schema=10' in (a.output/'upgrade.log').read_text()
    assert 'REJECTED Database schema newer than this plugin' in (a.output/'old-jar-rejection.log').read_text()
    result=dict(status='PASS',from_schema=9,to_schema=10,unchanged_tables=len(before),unchanged_rows=sum(map(len,before.values())),
                old_plugin_rejected=True,source=str(a.source),source_sha256=sha(a.source),upgraded_sha256=upgraded_sha,
                old_jar_sha256=sha(a.old_jar),new_jar_sha256=sha(a.new_jar),new_tables=sorted(set(after)-set(before)))
    (a.output/'inspection.json').write_text(json.dumps(result,indent=2)+'\n'); print(json.dumps(result))


if __name__=='__main__': main()
