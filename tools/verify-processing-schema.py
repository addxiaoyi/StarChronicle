"""Upgrade a consistent database copy with the tested JAR and reject older writers."""
from pathlib import Path
import argparse
import hashlib
import json
import os
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
        return version,{name:dict(columns=[r[1] for r in c.execute('PRAGMA table_info("'+name+'")')],
            rows=sorted(c.execute('SELECT * FROM "'+name+'"').fetchall(),key=repr)) for name in tables}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('source','new-jar','java-home','output'): p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--old-jar',type=Path,action='append',required=True)
    p.add_argument('--expected-schema',type=int,default=11)
    a=p.parse_args(); a.output.mkdir(exist_ok=False,parents=True)
    before_version,before=snapshot(a.source); assert before_version<a.expected_schema
    copy=a.output/'chronicle.db'
    with sqlite3.connect(a.source.resolve().as_uri()+'?mode=ro',uri=True) as src,sqlite3.connect(copy) as dest: src.backup(dest)
    assert snapshot(copy)==(before_version,before)
    source=Path(__file__).with_name('ProcessingSchemaProbe.java')
    def execute(cmd,log):
        with (a.output/log).open('x') as stream: subprocess.run([str(s) for s in cmd],stdout=stream,stderr=subprocess.STDOUT,check=True)
    java=a.java_home/'bin/java';javac=a.java_home/'bin/javac'
    opts=['-Xmx96m','-Xss512k','-XX:+UseSerialGC','-XX:ActiveProcessorCount=1','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=24m','-XX:CompressedClassSpaceSize=24m']
    execute([javac,*['-J'+o for o in opts],'--release','21','-cp',a.new_jar,'-d',a.output,source],'compile.log')
    execute([java,*opts,'-cp',os.pathsep.join((str(a.output),str(a.new_jar))),'ProcessingSchemaProbe','upgrade',copy,a.expected_schema],'upgrade.log')
    version,after=snapshot(copy); assert version==a.expected_schema
    with sqlite3.connect(copy.resolve().as_uri()+'?mode=ro',uri=True) as c:
        for name,old in before.items():
            assert name in after and set(old['columns'])<=set(after[name]['columns']),name
            columns=','.join('"'+x.replace('"','""')+'"' for x in old['columns'])
            assert sorted(c.execute('SELECT '+columns+' FROM "'+name+'"').fetchall(),key=repr)==old['rows'],name
    upgraded_sha=sha(copy)
    for i,old in enumerate(a.old_jar):
        logfile=f'old-jar-rejection-{i}.log'
        execute([java,*opts,'-cp',os.pathsep.join((str(a.output),str(old))),'ProcessingSchemaProbe','reject',copy],logfile)
        assert sha(copy)==upgraded_sha
        assert 'REJECTED Database schema newer than this plugin' in (a.output/logfile).read_text()
    assert f'UPGRADED schema={a.expected_schema}' in (a.output/'upgrade.log').read_text()
    result=dict(status='PASS',from_schema=before_version,to_schema=version,unchanged_tables=len(before),unchanged_rows=sum(len(x['rows']) for x in before.values()),
                old_plugin_rejected=True,source=str(a.source),source_sha256=sha(a.source),upgraded_sha256=upgraded_sha,
                old_jar_sha256=[sha(old) for old in a.old_jar],new_jar_sha256=sha(a.new_jar),new_tables=sorted(set(after)-set(before)))
    (a.output/'inspection.json').write_text(json.dumps(result,indent=2)+'\n'); print(json.dumps(result))


if __name__=='__main__': main()
