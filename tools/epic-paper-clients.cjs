'use strict';
// Real protocol clients; crafting uses the server's recipe and result slot.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),readline=require('node:readline');
const run=path.resolve(process.argv[2]),manifest=JSON.parse(fs.readFileSync(path.join(run,'preparation.json'),'utf8'));
if(!path.basename(run).startsWith('paper-epic-'))throw Error('Only isolated epic runs');
const mineflayer=require(process.env.EPIC_MINEFLAYER_MODULE || 'mineflayer');
const log=(kind,data={})=>{const line=JSON.stringify({time:new Date().toISOString(),kind,data});fs.appendFileSync(path.join(run,'evidence/epic-clients.jsonl'),line+'\n');console.log(line);};
const bots=new Map();let quitting=false;
for(const who of ['A','B','C','D']) {
  const bot=mineflayer.createBot({host:'127.0.0.1',port:manifest.server_port,username:'Epic'+who,auth:'offline',version:'1.21.11'});bots.set(who,bot);
  bot.on('spawn',()=>{if(bot.player.uuid!==manifest.players[who])throw Error('Client UUID mismatch');log('SPAWN',{who,uuid:bot.player.uuid});});
  bot.on('messagestr',message=>log('MESSAGE',{who,message}));
  bot.on('error',error=>log('FAILED',{who,error:String(error)}));
  bot.on('kicked',reason=>log('FAILED',{who,reason}));
  bot.on('end',reason=>{log('ENDED',{who,reason});if(quitting&&[...bots.values()].every(b=>b._client.ended))process.exit(0);});
}
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
async function contributions(who) {
  const bot=bots.get(who);
  await new Promise((resolve,reject)=>{
    let received='';
    const timer=setTimeout(()=>{bot.removeListener('messagestr',onMessage);reject(Error('No verified contribution query response'));},10000);
    function onMessage(message) {
      received+='\n'+message;
      if(received.includes('已核验贡献')&&received.includes('10 国库货币')&&received.includes('10 资源 agricultural')) {
        clearTimeout(timer);bot.removeListener('messagestr',onMessage);
        log('CONTRIBUTIONS_QUERY',{who,command:'/chronicle contributions',response:received});resolve();
      }
    }
    bot.on('messagestr',onMessage);bot.chat('/chronicle contributions');
  });
}
async function npc(who,rawId) {
  const bot=bots.get(who),entityId=Number(rawId);
  if(!Number.isInteger(entityId)||entityId<0)throw Error('Invalid NPC entity ID');
  let entity;
  for(let i=0;i<80;i++) {entity=bot.entities[entityId];if(entity)break;await new Promise(resolve=>setTimeout(resolve,100));}
  if(!entity)throw Error('Real NPC entity was not received by client');
  const distance=bot.entity.position.distanceTo(entity.position);
  if(distance>4)throw Error('NPC outside interaction reach: '+distance);
  if(bot.currentWindow)throw Error('Close menu before NPC interaction');
  await bot.lookAt(entity.position.offset(0,1,0),true);
  await bot.activateEntity(entity);
  log('NPC_CLICK',{who,entityId,distance,type:entity.name,syntheticEvents:false});
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
function itemField(item,prefix){
  const lines=itemLore(item),at=lines.findIndex(line=>line.startsWith(prefix));if(at<0)return null;
  const boundaries=['实例: ','范围: ','参与人数: ','当前阶段: ','投票: ','选项: ','权限和条件','同一阶段'];
  let value=lines[at].slice(prefix.length);for(let n=at+1;n<lines.length&&!boundaries.some(b=>lines[n].startsWith(b));n++)value+=lines[n];return value.trim();
}
function waitWindow(bot,predicate,action){return new Promise((resolve,reject)=>{
  const timer=setTimeout(()=>{clean();reject(Error('Actual memory GUI transition timed out'));},15000);
  function clean(){clearTimeout(timer);bot.off('windowOpen',opened);}
  function opened(window){if(predicate(componentText(window.title))){clean();resolve(window);}}
  bot.on('windowOpen',opened);Promise.resolve().then(action).catch(error=>{clean();reject(error);});
});}
function topSlots(bot){const w=bot.currentWindow;if(!w||!Number.isInteger(w.inventoryStart))throw Error('Actual GUI is absent');return w.slots.slice(0,w.inventoryStart);}
function uniqueSlot(bot,predicate){const rows=topSlots(bot).flatMap((item,slot)=>item&&predicate(item)?[{item,slot}]:[]);if(rows.length!==1)throw Error('Expected one actual memory story/choice row; got '+rows.length);return rows[0];}
async function memoryWalk(who){
  if(!manifest.memory_acceptance)throw Error('Prepared memory acceptance is required');const bot=bots.get(who);
  if(bot.currentWindow)await bot.closeWindow(bot.currentWindow);
  for(let n=0;n<80&&(bot.entity.position.x<999||bot.entity.position.x>1002);n++)await bot.waitForTicks(1);
  if(bot.entity.position.x<999||bot.entity.position.x>1002||Math.abs(bot.entity.position.z-1.5)>1)throw Error('Walk must start at actual outside station');
  const before={...bot.entity.position};await bot.lookAt(bot.entity.position.offset(10,1.62,0),true);bot.setControlState('forward',true);
  const deadline=Date.now()+12000;try{while(bot.entity.position.x<1005&&Date.now()<deadline)await bot.waitForTicks(1);}finally{bot.clearControlStates();}
  await bot.waitForTicks(3);const after={...bot.entity.position};
  if(after.x<1004||after.x>=1007||after.y< -60.2||after.y> -58||after.z<0||after.z>=4)throw Error('Actual arrival is outside prepared cuboid: '+JSON.stringify(after));
  log('MEMORY_WALK',{who,before,after,syntheticEvents:false,implementation:'real control-state movement packets'});
}
async function memoryChoice(who,choice,expectedInstance){
  const story=manifest.memory_acceptance?.story;if(!story||!['testify','read'].includes(choice))throw Error('Require prepared library story and exact branch');
  if(!/^[0-9a-f]{64}$/.test(expectedInstance||''))throw Error('Require the observed active instance ID');
  const bot=bots.get(who);if(bot.currentWindow)await bot.closeWindow(bot.currentWindow);
  await waitWindow(bot,title=>/^共同书写历史 · \d+$/.test(title),()=>bot.chat('/chronicle stories'));
  const row=uniqueSlot(bot,item=>['book','enchanted_book'].includes(item.name)&&itemLabel(item)===story.title&&itemField(item,'实例: ')===expectedInstance),instance=itemField(row.item,'实例: ');
  if(!/^[0-9a-f]{64}$/.test(instance||''))throw Error('Actual story instance ID is missing');
  await waitWindow(bot,title=>title===story.title,()=>bot.clickWindow(row.slot,0,0));
  const header=topSlots(bot)[4];
  if(header?.name!=='written_book'||itemLabel(header)!==story.title||itemField(header,'实例: ')!==instance||itemField(header,'当前阶段: ')!=='archive')throw Error('Library GUI source/node changed');
  log('MEMORY_GUI_OPEN',{who,story:story.id,instance,node:'archive',title:componentText(bot.currentWindow.title),header:itemLore(header)});
  const option=uniqueSlot(bot,item=>item.name==='lime_dye'&&itemField(item,'选项: ')===choice);
  await waitWindow(bot,title=>/^共同书写历史 · \d+$/.test(title),()=>bot.clickWindow(option.slot,0,0));
  log('MEMORY_GUI_CHOICE',{who,story:story.id,instance,node:'archive',choice,slot:option.slot,realClick:true});
}
let chain=Promise.resolve();
async function expectCommand(who,encoded) {
  const request=JSON.parse(Buffer.from(encoded,'base64url').toString('utf8')),bot=bots.get(who);
  if(!request.command.startsWith('/chronicle ')||typeof request.contains!=='string'||!request.contains)throw Error('Require a concrete Chronicle command and response');
  await new Promise((resolve,reject)=>{
    let received='';
    const timer=setTimeout(()=>{bot.removeListener('messagestr',onMessage);reject(Error('Command response missing: '+request.command+' expected '+request.contains+' received '+received));},10000);
    function onMessage(message) {
      received+='\n'+message;
      if(received.includes(request.contains)) {
        clearTimeout(timer);bot.removeListener('messagestr',onMessage);
        log('COMMAND_RESULT',{who,...request,response:received});resolve();
      }
    }
    bot.on('messagestr',onMessage);bot.chat(request.command);
  });
}
readline.createInterface({input:process.stdin}).on('line',line=>{chain=chain.then(async()=>{
  const [action,who,...rest]=line.trim().split(' ');
  if(action==='quit'){quitting=true;for(const bot of bots.values())bot.quit('Epic acceptance complete');return;}
  if(action==='craft')await craft(who);
  else if(action==='contributions')await contributions(who);
  else if(action==='npc')await npc(who,rest[0]);
  else if(action==='memory-walk')await memoryWalk(who);
  else if(action==='memory-choice')await memoryChoice(who,rest[0],rest[1]);
  else if(action==='expect')await expectCommand(who,rest.join(' '));
  else if(action==='chat')bots.get(who).chat(rest.join(' '));
  else if(action==='close'&&bots.get(who).currentWindow)bots.get(who).closeWindow(bots.get(who).currentWindow);
  else if(action==='inventory')log('INVENTORY',{who,items:bots.get(who).inventory.items().map(i=>({name:i.name,count:i.count}))});
  else throw Error('Unknown client action');
  log('ACTION_DONE',{action,who});
}).catch(error=>log('FAILED',{command:line,error:String(error)}));});
