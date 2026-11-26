'use strict';
const fs=require('node:fs'),path=require('node:path'),readline=require('node:readline');
const run=path.resolve(process.argv[2]),manifest=JSON.parse(fs.readFileSync(path.join(run,'preparation.json'),'utf8'));
if(!path.basename(run).startsWith('paper-processing-'))throw Error('Isolated processing run required');
const targets=JSON.parse(fs.readFileSync(path.join(run,'evidence/targets.json'),'utf8'));
const mineflayer=require(process.env.PROCESSING_MINEFLAYER_MODULE||'mineflayer');
const bots=new Map();let quitting=false;
function log(kind,data={}){const line=JSON.stringify({time:new Date().toISOString(),kind,data});fs.appendFileSync(path.join(run,'evidence/clients.jsonl'),line+'\n');console.log(line);}
for(const who of ['A','B']){
  const bot=mineflayer.createBot({host:'127.0.0.1',port:manifest.port,username:'Produce'+who,auth:'offline',version:'1.21.11'});bots.set(who,bot);
  bot.on('spawn',()=>{if(bot.player.uuid!==targets[who.toLowerCase()])throw Error('Actual player identity mismatch');log('SPAWN',{who,uuid:bot.player.uuid});});
  bot.on('messagestr',message=>log('MESSAGE',{who,message}));
  bot.on('error',error=>log('FAILED',{who,error:String(error)}));bot.on('kicked',reason=>log('FAILED',{who,reason}));
  bot.on('end',reason=>{log('ENDED',{who,reason});if(quitting&&[...bots.values()].every(b=>b._client.ended))process.exit(0);});
}
readline.createInterface({input:process.stdin}).on('line',line=>{
  try {
    const [action,who,...rest]=line.trim().split(' ');
    if(action==='quit'){quitting=true;for(const bot of bots.values())bot.quit('Processing acceptance complete');return;}
    if(action!=='chat'||!bots.has(who))throw Error('Unknown client action');
    bots.get(who).chat(rest.join(' '));log('COMMAND',{who,command:rest.join(' ')});
  }catch(error){log('FAILED',{error:String(error)});}
});
