'use strict';
// Execute the actual client bootstrap up to (and excluding) bot creation. Read the
// prepared manifest/JAR/vendor files, but redirect every evidence write to a temp
// directory. This check starts no server, protocol connection or acceptance run.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),vm=require('node:vm'),crypto=require('node:crypto');
const client=path.join(__dirname,'quest-evidence-clients.cjs'),source=fs.readFileSync(client,'utf8'),boundary=source.indexOf('\nconst bots=');
if(boundary<0)throw new Error('Actual client bootstrap boundary changed; inspect before preflight');
const supplied=process.argv.slice(2),at=supplied.indexOf('--run-dir');if(at<0)throw new Error('Require explicit prepared --run-dir');
const run=path.resolve(supplied[at+1]),evidence=path.join(run,'evidence'),temporary=fs.mkdtempSync(path.join(os.tmpdir(),'starchronicle-runtime-preflight-'));
const redirected=file=>{const resolved=path.resolve(file),relative=path.relative(evidence,resolved);if(relative.startsWith('..')||path.isAbsolute(relative))throw new Error('Bootstrap attempted a write outside redirected evidence: '+resolved);return path.join(temporary,relative);};
const redirectedFs=Object.create(fs);
for(const method of ['mkdirSync','writeFileSync','appendFileSync'])redirectedFs[method]=(file,...args)=>fs[method](redirected(file),...args);
const actualRequire=require('node:module').createRequire(client),sandboxRequire=name=>name==='node:fs'?redirectedFs:actualRequire(name);sandboxRequire.resolve=actualRequire.resolve.bind(actualRequire);
try{
    const context={require:sandboxRequire,__dirname,__filename:client,process:{argv:[process.execPath,client,...supplied]},console:{log(){}},globalThis:{}};
    vm.runInNewContext(source.slice(0,boundary)+'\nglobalThis.preflightManifest=runtimeManifest;',context,{filename:client,timeout:30000});
    const manifest=context.globalThis.preflightManifest,sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
    for(const row of Object.values(manifest.sources))if(sha(fs.readFileSync(redirected(path.join(run,row.copy))))!==row.sha256||sha(fs.readFileSync(row.path))!==row.sha256)throw new Error('Actual vendor/source copy did not preserve bytes');
    for(const entries of Object.values(manifest.attribution))for(const row of Object.values(entries))if(row.copy&&sha(fs.readFileSync(redirected(path.join(run,row.copy))))!==row.sha256)throw new Error('Actual attribution copy did not preserve bytes');
    console.log(JSON.stringify({status:'RUNTIME_PREFLIGHT_PASS',acceptance_proof:false,server_started:false,client_started:false,versions:manifest.versions,sources:manifest.sources,attribution:manifest.attribution},null,2));
}finally{
    const resolved=fs.realpathSync(temporary);
    if(path.dirname(resolved).toLowerCase()!==fs.realpathSync(os.tmpdir()).toLowerCase()||!path.basename(resolved).startsWith('starchronicle-runtime-preflight-'))throw new Error('Unexpected preflight temp target; left intact');
    fs.rmSync(resolved,{recursive:true,force:true});
}
