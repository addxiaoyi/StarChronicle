'use strict';
// Real protocol clients; crafting uses the server's recipe and result slot.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),readline=require('node:readline');
const run=path.resolve(process.argv[2]),manifest=JSON.parse(fs.readFileSync(path.join(run,'preparation.json'),'utf8'));
if(!path.basename(run).startsWith('paper-epic-'))throw Error('Only isolated epic runs');
const mineflayer=require(process.env.EPIC_MINEFLAYER_MODULE || 'mineflayer');
const log=(kind,data={})=>{const line=JSON.stringify({time:new Date().toISOString(),kind,data});fs.appendFileSync(path.join(run,'evidence/epic-clients.jsonl'),line+'\n');console.log(line);};
const bots=new Map();let quitting=false;
function connect(who) {
  const bot=mineflayer.createBot({host:'127.0.0.1',port:manifest.server_port,username:'Epic'+who,auth:'offline',version:'1.21.11'});bots.set(who,bot);
  bot.on('spawn',()=>{if(bot.player.uuid!==manifest.players[who])throw Error('Client UUID mismatch');log('SPAWN',{who,uuid:bot.player.uuid});});
  bot.on('messagestr',message=>log('MESSAGE',{who,message}));
  bot.on('error',error=>log('FAILED',{who,error:String(error)}));
  bot.on('kicked',reason=>log('FAILED',{who,reason}));
  bot.on('end',reason=>{log('ENDED',{who,reason});if(quitting&&[...bots.values()].every(b=>b._client.ended))process.exit(0);});
}
for(const who of ['A','B','C','D'])connect(who);
const count=(bot,name)=>bot.inventory.items().filter(i=>i.name===name).reduce((n,i)=>n+i.count,0);
async function craft(who) {
  const bot=bots.get(who);if(bot.currentWindow)throw Error('Close menu before craft');
  const table=bot.findBlock({matching:bot.registry.blocksByName.crafting_table.id,maxDistance:6});if(!table)throw Error('No nearby actual crafting table');
  const empty=bot.inventory.slots.slice(36,45).findIndex(i=>i==null);if(empty<0)throw Error('No empty hand slot');bot.setQuickBarSlot(empty);await bot.waitForTicks(2);
  const paper=bot.registry.itemsByName.paper.id;
  const recipe=bot.recipesFor(paper,null,3,table).find(r=>r.result.id===paper&&r.result.count===3);
  if(!recipe||count(bot,'sugar_cane')<3)throw Error('Actual recipe/ingredients unavailable');
  const before={paper:count(bot,'paper'),sugar_cane:count(bot,'sugar_cane')};let craftError;
  try {await bot.craft(recipe,1,table);}catch(error){craftError=String(error);}
  for(let i=0;i<60;i++) {
    const after={paper:count(bot,'paper'),sugar_cane:count(bot,'sugar_cane')};
    if(after.paper-before.paper===3&&before.sugar_cane-after.sugar_cane===3) {log('CRAFTED',{who,before,after,craftError,syntheticEvents:false});return;}
    await new Promise(resolve=>setTimeout(resolve,250));
  }
  throw Error('Unconfirmed craft; no automatic retry: '+craftError);
}
let chain=Promise.resolve();
readline.createInterface({input:process.stdin}).on('line',line=>{chain=chain.then(async()=>{
  const [action,who,...rest]=line.trim().split(' ');
  if(action==='quit'){quitting=true;for(const bot of bots.values())bot.quit('Epic acceptance complete');return;}
  if(action==='craft')await craft(who);
  else if(action==='membership') {
    const records=fs.readFileSync(path.join(run,'evidence/epic-fixture.jsonl'),'utf8').trim().split('\n').map(line=>JSON.parse(line));
    const id=records.filter(r=>r.kind==='MEMBER_INSTANCE').at(-1)?.data.id;if(!id)throw Error('No evidenced membership instance');
    const bot=bots.get(who);if(bot.currentWindow)bot.closeWindow(bot.currentWindow);
    const window=await new Promise((resolve,reject)=>{
      const timeout=setTimeout(()=>{bot.removeListener('windowOpen',open);reject(Error('Membership menu timeout'));},12000);
      function open(value){clearTimeout(timeout);resolve(value);}bot.once('windowOpen',open);bot.chat('/chronicle membership '+id);
    });
    if(window.slots[20]?.name!=='emerald'||window.slots[24]?.name!=='barrier')throw Error('Missing membership review/exit controls');
    log('MEMBERSHIP_GUI',{who,instance:id,title:window.title,review:window.slots[20].name,exit:window.slots[24].name});bot.closeWindow(window);
  }
  else if(action==='disconnect') {for(const bot of bots.values())bot.quit('Controlled provider maintenance');}
  else if(action==='connect') {if(!bots.has(who)||!bots.get(who)._client.ended)throw Error('Existing client must be disconnected');connect(who);}
  else if(action==='npc') {
    const bot=bots.get(who),target=JSON.parse(fs.readFileSync(path.join(run,'evidence/combination-target.json'),'utf8')).npc;
    for(let i=0;i<80&&!bot.entities[target];i++)await bot.waitForTicks(1);
    const entity=bot.entities[target];if(!entity)throw Error('Provider NPC not visible to real client');
    if(bot.entity.position.distanceTo(entity.position)>4)throw Error('NPC beyond actual interaction reach');
    await bot.lookAt(entity.position.offset(0,1,0),true);bot.activateEntity(entity);
    log('NPC_CLICK',{who,entityId:target,syntheticEvents:false});
  }
  else if(action==='chat')bots.get(who).chat(rest.join(' '));
  else if(action==='close'&&bots.get(who).currentWindow)bots.get(who).closeWindow(bots.get(who).currentWindow);
  else if(action==='inventory')log('INVENTORY',{who,items:bots.get(who).inventory.items().map(i=>({name:i.name,count:i.count}))});
  else throw Error('Unknown client action');
  log('ACTION_DONE',{action,who});
}).catch(error=>log('FAILED',{command:line,error:String(error)}));});
