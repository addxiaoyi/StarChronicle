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
const componentText=value=>{
  if(value==null)return '';if(typeof value==='string')return value.replace(/\u00a7[0-9a-fk-or]/gi,'');
  if(Array.isArray(value))return value.map(componentText).join('');
  if(value.type==='string'||value.type==='compound')return componentText(value.value);
  if(value.text!=null)return componentText(value.text)+componentText(value.extra);
  return value.value!=null?componentText(value.value):'';
};
const itemLabel=item=>componentText(item?.customName||item?.components?.find(c=>c.type==='custom_name')?.data);
const itemLore=item=>(item?.components?.find(c=>c.type==='lore')?.data||[]).map(componentText);
function waitWindow(bot,predicate,action){return new Promise((resolve,reject)=>{
  const timer=setTimeout(()=>{clean();reject(Error('Actual GUI transition timed out'));},15000);
  function clean(){clearTimeout(timer);bot.off('windowOpen',opened);}
  function opened(window){log('GUI_WINDOW_OPEN',{username:bot.username,title:componentText(window.title),id:window.id});if(predicate(componentText(window.title))){clean();resolve(window);}}
  bot.on('windowOpen',opened);Promise.resolve().then(action).catch(error=>{clean();reject(error);});
});}
function topSlots(bot){const w=bot.currentWindow;if(!w||!Number.isInteger(w.inventoryStart))throw Error('Actual GUI is absent');return w.slots.slice(0,w.inventoryStart);}
function uniqueSlot(bot,predicate){const rows=topSlots(bot).flatMap((item,slot)=>item&&predicate(item)?[{item,slot}]:[]);if(rows.length!==1)throw Error('Expected one actual GUI row; got '+rows.length);return rows[0];}
async function battleOpen(who){
  if(!manifest.battlefield_acceptance)throw Error('Prepared battlefield scenario required');
  const bot=bots.get(who);if(bot.currentWindow)await bot.closeWindow(bot.currentWindow);
  await waitWindow(bot,title=>title.includes('战争中心'),()=>bot.chat('/war gui'));
  for(const [label,title] of [['战场与要塞','战争战场总览'],['Epic acceptance field','战争战场总览'],['要塞管理','战争要塞管理']]){
    await bot.waitForTicks(3);const row=uniqueSlot(bot,item=>itemLabel(item).includes(label));
    log('BATTLE_GUI_NAVIGATION',{who,label,slot:row.slot,title:componentText(bot.currentWindow.title),realClick:true});
    await waitWindow(bot,value=>value.includes(title),()=>bot.clickWindow(row.slot,0,0));
    await bot.waitForTicks(3);
    if(label==='Epic acceptance field'&&!itemLabel(topSlots(bot)[4]).endsWith('Epic acceptance field'))throw Error('Actual battlefield detail header differs');
  }
  await bot.waitForTicks(3);
}
async function battleTry(who,index,mode){
  if(!manifest.battlefield_acceptance||!['1','2'].includes(index)||!['capture','blocked'].includes(mode))throw Error('Invalid actual battlefield attempt');
  const bot=bots.get(who);await bot.waitForTicks(5);
  if(!componentText(bot.currentWindow?.title).includes('战争要塞管理'))throw Error('Actual native stronghold menu missing');
  const row=uniqueSlot(bot,item=>itemLabel(item).endsWith('Epic citadel '+index)),before={name:row.item.name,lore:itemLore(row.item)};
  if(mode==='capture'&&row.item.name!=='redstone_block')throw Error('Native GUI did not authorize this capture');
  if(mode==='blocked'&&!['iron_block','emerald_block'].includes(row.item.name))throw Error('Native GUI unexpectedly offers capture');
  if(mode==='capture')await waitWindow(bot,title=>title.includes('战争要塞管理'),()=>bot.clickWindow(row.slot,0,0));
  else await bot.clickWindow(row.slot,0,0);
  await bot.waitForTicks(8);const after=uniqueSlot(bot,item=>itemLabel(item).endsWith('Epic citadel '+index));
  if(mode==='capture'&&after.item.name!=='emerald_block')throw Error('Actual capture did not update native ownership');
  log('BATTLE_GUI_ATTEMPT',{who,index:Number(index),mode,slot:row.slot,before,after:{name:after.item.name,lore:itemLore(after.item)},realClick:true,syntheticEvents:false});
}
async function battleEnd(who){
  if(!manifest.battlefield_acceptance||who!=='A')throw Error('Actual attacking founder required');
  const bot=bots.get(who);if(bot.currentWindow)await bot.closeWindow(bot.currentWindow);
  const command='/war end 复兴帝国 EpicTestB';
  await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{bot.off('messagestr',receive);reject(Error('Native principal war-end response missing'));},15000);
    function receive(message){if(message.includes('War ended between 复兴帝国 and EpicTestB')){
      clearTimeout(timer);bot.off('messagestr',receive);log('BATTLE_END_COMMAND',{who,command,response:message,realCommand:true});resolve();
    }}
    bot.on('messagestr',receive);bot.chat(command);
  });
}
let chain=Promise.resolve();
readline.createInterface({input:process.stdin}).on('line',line=>{chain=chain.then(async()=>{
  const [action,who,...rest]=line.trim().split(' ');
  if(action==='quit'){quitting=true;for(const bot of bots.values())bot.quit('Epic acceptance complete');return;}
  if(action==='craft')await craft(who);
  else if(action==='battle-open')await battleOpen(who);
  else if(action==='battle-try')await battleTry(who,rest[0],rest[1]);
  else if(action==='battle-end')await battleEnd(who);
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
