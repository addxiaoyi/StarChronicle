// Real loopback Minecraft protocol clients; does not construct server identity or write SQLite.
'use strict';
const fs=require('node:fs');
const path=require('node:path');
const readline=require('node:readline');
const args=process.argv.slice(2);
const argument=(name,fallback)=>{const at=args.indexOf(name);return at<0?fallback:args[at+1];};
const runDir=path.resolve(argument('--run-dir',''));
const boot=Number(argument('--boot','0'));
if(!fs.existsSync(path.join(runDir,'acceptance-preparation.json'))||![1,2,3].includes(boot))throw new Error('Require --run-dir <prepared run> --boot 1|2|3');
const manifest=JSON.parse(fs.readFileSync(path.join(runDir,'acceptance-preparation.json'),'utf8'));
const port=Number(argument('--port',String(manifest.server_port)));
const mineflayer=require(argument('--mineflayer','C:/Users/Administrator/Documents/starworld-tools/mc-client/node_modules/mineflayer'));
const evidence=path.join(runDir,'evidence');fs.mkdirSync(evidence,{recursive:true});
const logPath=path.join(evidence,'phase-three-folia-clients.log');
const stringify=value=>JSON.stringify(value,(_,entry)=>typeof entry==='bigint'?entry.toString():entry);
const log=(event,data={})=>{const line=new Date().toISOString()+' '+event+' '+stringify({boot,...data});fs.appendFileSync(logPath,line+'\n');console.log(line);};
let failed=false,quitting=false,ended=0;
const bots={};
const delay=milliseconds=>new Promise(resolve=>setTimeout(resolve,milliseconds));
for(const [short,username] of [['A','PhaseTwoFoliaA'],['B','PhaseTwoFoliaB']]){
    const bot=mineflayer.createBot({host:'127.0.0.1',port,username,auth:'offline',version:'1.21.11'});bots[short]=bot;
    bot.on('spawn',()=>log('SPAWN',{player:short,username,uuid:bot.player.uuid}));
    bot.on('messagestr',text=>log('MESSAGE',{player:short,text}));
    bot.on('windowOpen',window=>log('WINDOW',{player:short,id:window.id,title:window.title,items:window.slots.filter(Boolean).length}));
    bot._client.on('open_book',data=>log('BOOK',{player:short,data,held:bot.heldItem?{name:bot.heldItem.name,components:bot.heldItem.components}:null}));
    bot.on('error',error=>{failed=true;log('ERROR',{player:short,message:error.message});});
    bot.on('kicked',reason=>{if(!quitting)failed=true;log('KICKED',{player:short,reason,requestedQuit:quitting});});
    bot.on('end',reason=>{
        if(!quitting)failed=true;
        log('ENDED',{player:short,reason,requestedQuit:quitting});
        if(++ended===2){input.close();process.exitCode=failed?1:0;}
    });
}
const input=readline.createInterface({input:process.stdin,output:process.stdout,terminal:false});
const quit=reason=>{
    if(quitting)return;quitting=true;log('QUIT',{reason});
    Object.entries(bots).forEach(([player,bot])=>{log('QUIT_REQUESTED',{player,reason});bot.quit(reason);});
    input.close();
};
const inspect=bot=>({window:bot.currentWindow?{id:bot.currentWindow.id,title:bot.currentWindow.title,slots:bot.currentWindow.slots.map((item,index)=>item?{index,name:item.name,count:item.count,customName:item.customName,components:item.components}:null).filter(Boolean)}:null,inventory:bot.inventory.items().map(item=>({name:item.name,count:item.count}))});
let commandChain=Promise.resolve();
input.on('line',line=>{commandChain=commandChain.then(async()=>{
    const [action,short,...rest]=line.trim().split(' ');const bot=bots[short];
    try{
        if(!action)return;
        if(action==='quit'){quit('phase-three acceptance completed normally');return;}
        if(!bot)throw new Error('Expected actual client A or B');
        if(action==='chat'){const message=rest.join(' ');log('SENT',{player:short,message});bot.chat(message);}
        else if(action==='click'){
            const slot=Number(rest[0]);await bot.clickWindow(slot,0,0);log('CLICKED',{player:short,slot});
        }else if(action==='select'||action==='choice'){
            const choice=rest.join(' ');
            const definitions={
                'b-preserve':{label:'Preserve the original testimony',story:'phase-three-archive',version:'1',choice:'preserve',title:'Phase Three Archive Review',boot:1},
                'b-annotate':{label:'Annotate the reconciled archive',story:'phase-three-archive',version:'1',choice:'annotate',title:'Phase Three Archive Review',boot:1},
                'v2-testimony':{label:'记录我在议会实际投出的主张',story:'heir-legacy',version:'2',choice:'testimony',title:'Phase Three Legacy v2',boot:2},
                'v2-reconcile':{label:'对照公开决议与我亲历的结局记录',story:'heir-legacy',version:'2',choice:'reconcile',title:'Phase Three Legacy v2',boot:2}
            };
            const selected=definitions[choice];const needle=action==='choice'?selected?.label:choice;
            if(!needle||!bot.currentWindow)throw new Error('Need an open actual GUI and a display-text substring');
            if(action==='choice'&&(boot!==selected.boot||!stringify(bot.currentWindow.title).includes(selected.title)))throw new Error('Recorded choice requires its actual versioned story window and boot');
            const matches=bot.currentWindow.slots.slice(0,54).map((item,index)=>({item,index})).filter(({item})=>item&&stringify({customName:item.customName,components:item.components}).includes(needle));
            if(matches.length!==1)throw new Error('Expected one matching GUI item, found '+matches.length+' for '+needle);
            await bot.clickWindow(matches[0].index,0,0);log('SELECTED',{player:short,slot:matches[0].index,needle});
            if(action==='choice')log('GUI_CHOICE',{player:short,story:selected.story,version:selected.version,choice:selected.choice,slot:matches[0].index});
        }else if(action==='shift'){
            const slot=Number(rest[0]),item=bot.currentWindow?.slots[slot];
            const inventory=()=>stringify(bot.inventory.items().map(value=>({name:value.name,count:value.count})).sort((a,b)=>a.name.localeCompare(b.name)));
            const before=inventory();await bot.clickWindow(slot,0,1);await delay(400);
            log('SHIFT_CHECK',{player:short,slot,unchangedInventory:before===inventory(),slotStillPresent:!!item&&bot.currentWindow?.slots[slot]?.name===item.name&&bot.currentWindow.slots[slot].count===item.count});
        }else if(action==='close'){
            if(bot.currentWindow)bot.closeWindow(bot.currentWindow);log('CLOSED',{player:short});
        }else if(action==='interact'){
            const kind=rest[0];if(!['monument','veteran'].includes(kind))throw new Error('Expected monument or veteran');
            const entity=Object.values(bot.entities).filter(value=>kind==='veteran'?value.name==='villager':value.name==='interaction').sort((a,b)=>bot.entity.position.distanceTo(a.position)-bot.entity.position.distanceTo(b.position))[0];
            if(!entity)throw new Error('No actual nearby '+kind+' entity');
            bot.activateEntity(entity);log('INTERACTED',{player:short,kind,entity:entity.id,name:entity.name,distance:bot.entity.position.distanceTo(entity.position)});
        }else if(action==='inspect')log('CLIENT_VIEW',{player:short,...inspect(bot)});
        else throw new Error('Unknown client action: '+action);
    }catch(error){failed=true;log('ACTION_FAILED',{line,message:error.message});}
});});
process.on('SIGINT',()=>quit('phase-three acceptance interrupted'));
process.on('SIGTERM',()=>quit('phase-three acceptance terminated'));
setTimeout(()=>{failed=true;log('TIMEOUT',{});quit('bounded phase-three acceptance timeout');process.exitCode=1;},15*60*1000).unref();
log('CLIENT_PROCESS',{pid:process.pid,port,scope:manifest.scope});
