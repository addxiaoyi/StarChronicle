// Actual loopback protocol clients. Commands enter the real player command/GUI/entity paths.
const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const mineflayer = require('C:/Users/Administrator/Documents/starworld-tools/mc-client/node_modules/mineflayer');
const logPath=path.resolve(__dirname,'../docs/verification/expanded-folia-smoke-clients.log');
const log=(event,data)=>{const line=new Date().toISOString()+' '+event+' '+JSON.stringify(data);fs.appendFileSync(logPath,line+'\n');console.log(line);};
const bots={};
for(const [short,username] of [['A','FoliaFeatureA'],['B','FoliaFeatureB']]){
 const bot=mineflayer.createBot({host:'127.0.0.1',port:25578,username,auth:'offline',version:'1.21.11'});bots[short]=bot;
 bot.on('spawn',()=>log('SPAWN',{player:short,username,uuid:bot.player.uuid}));
 bot.on('messagestr',text=>log('MESSAGE',{player:short,text}));
 bot.on('windowOpen',window=>log('WINDOW',{player:short,id:window.id,title:window.title,items:window.slots.filter(Boolean).length}));
 bot._client.on('open_book',data=>log('BOOK',{player:short,data,held:bot.heldItem?{name:bot.heldItem.name,components:bot.heldItem.components}:null}));
 bot.on('error',error=>log('ERROR',{player:short,message:error.message}));
 bot.on('kicked',reason=>log('KICKED',{player:short,reason}));
 bot.on('end',reason=>log('ENDED',{player:short,reason}));
}
const input=readline.createInterface({input:process.stdin,output:process.stdout,terminal:true});
input.on('line',async line=>{
 const [action,short,...rest]=line.trim().split(' ');const bot=bots[short];
 try {
  if(action==='quit'){Object.values(bots).forEach(b=>b.quit('verification complete'));input.close();return;}
  if(!bot)throw new Error('Expected client A or B');
  if(action==='chat'){const message=rest.join(' ');log('SENT',{player:short,message});bot.chat(message);}
  else if(action==='click'){await bot.clickWindow(Number(rest[0]),0,0);log('CLICKED',{player:short,slot:Number(rest[0])});}
  else if(action==='shift'){
   const slot=Number(rest[0]);const item=bot.currentWindow?.slots[slot];
   const inventory=()=>JSON.stringify(bot.inventory.items().map(value=>({name:value.name,count:value.count})).sort((a,b)=>a.name.localeCompare(b.name)));
   const before=inventory();await bot.clickWindow(slot,0,1);await new Promise(resolve=>setTimeout(resolve,300));
   log('SHIFT_CHECK',{player:short,slot,unchangedInventory:before===inventory(),slotStillPresent:!!item&&bot.currentWindow?.slots[slot]?.name===item.name&&bot.currentWindow.slots[slot].count===item.count});
  }
  else if(action==='close'){if(bot.currentWindow)bot.closeWindow(bot.currentWindow);log('CLOSED',{player:short});}
  else if(action==='interact'){
   const kind=rest[0];const entity=Object.values(bot.entities).filter(e=>kind==='veteran'?e.name==='villager':e.name==='interaction').sort((a,b)=>bot.entity.position.distanceTo(a.position)-bot.entity.position.distanceTo(b.position))[0];
   if(!entity)throw new Error('No actual nearby '+kind+' entity');
   bot.activateEntity(entity);log('INTERACTED',{player:short,kind,entity:entity.id,name:entity.name,distance:bot.entity.position.distanceTo(entity.position)});
  }
  else if(action==='inspect')log('CLIENT_VIEW',{player:short,window:bot.currentWindow?{id:bot.currentWindow.id,title:bot.currentWindow.title,slots:bot.currentWindow.slots.map((item,index)=>item?{index,name:item.name,count:item.count,customName:item.customName,components:item.components}:null).filter(Boolean)}:null,inventory:bot.inventory.items().map(item=>({name:item.name,count:item.count}))});
 }catch(error){log('ACTION_FAILED',{line,message:error.message});}
});
process.on('SIGINT',()=>{Object.values(bots).forEach(b=>b.quit('verification stopped'));input.close();});
let ended=0;Object.values(bots).forEach(bot=>bot.on('end',()=>{if(++ended===2){input.close();process.exitCode=0;}}));
setTimeout(()=>{log('TIMEOUT',{});Object.values(bots).forEach(bot=>bot.quit('bounded verification timeout'));input.close();process.exitCode=1;},15*60*1000).unref();
