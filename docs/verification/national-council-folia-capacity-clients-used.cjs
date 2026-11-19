'use strict';
// Four actual original clients; no invented contexts, SQLite writes or command impersonation.
const fs=require('node:fs'),path=require('node:path'),readline=require('node:readline'),crypto=require('node:crypto');
const args=process.argv.slice(2),arg=(name,fallback)=>{const index=args.indexOf(name);return index<0?fallback:args[index+1];};
const run=path.resolve(arg('--run-dir','')),boot=Number(arg('--boot','0')),expected=arg('--expected-sha','');
if(!/^folia-national-[a-z0-9][a-z0-9-]{0,63}$/.test(path.basename(run))||![1,2,3].includes(boot)||!fs.existsSync(path.join(run,'preparation.json')))throw new Error('Require a prepared isolated run and boot 1|2|3');
const manifest=JSON.parse(fs.readFileSync(path.join(run,'preparation.json'),'utf8'));
const sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
if(manifest.plugin_sha256!==expected||sha(path.join(run,'plugins/StarChronicle.jar'))!==expected)throw new Error('Refusing product fingerprint drift');
const mineflayer=require(arg('--mineflayer','C:/Users/Administrator/Documents/starworld-tools/mc-client/node_modules/mineflayer'));
const logFile=path.join(run,'evidence/national-council-folia-clients.log');
const log=(event,value={})=>{const line=new Date().toISOString()+' '+event+' '+JSON.stringify({boot,...value},(_,v)=>typeof v==='bigint'?v.toString():v);fs.appendFileSync(logFile,line+'\n');console.log(line);};
const bots=new Map(),messages=new Map();let quitting=false;
const input=readline.createInterface({input:process.stdin,output:process.stdout,terminal:false});
function text(value){if(value==null)return '';if(typeof value==='string')return value;if(Array.isArray(value))return value.map(text).join('');if(value.type==='string')return value.value;if(value.type==='compound')return text(value.value);if(value.text!=null)return text(value.text)+text(value.extra);if(value.value!=null)return text(value.value);return '';}
const label=item=>text(item.customName||item.components?.find(c=>c.type==='custom_name')?.data);
const lore=item=>(item.components?.find(c=>c.type==='lore')?.data||[]).map(text);
const top=bot=>{if(!bot.currentWindow)throw new Error('No actual inventory window');return bot.currentWindow.slots.slice(0,bot.currentWindow.inventoryStart||54);};
function windowAfter(bot,contains){return new Promise((resolve,reject)=>{const done=window=>{if(!contains||text(window.title).includes(contains)){cleanup();resolve(window);}};const timer=setTimeout(()=>{cleanup();reject(new Error('Timed out waiting for actual window '+contains));},15000);const cleanup=()=>{clearTimeout(timer);bot.removeListener('windowOpen',done);};bot.on('windowOpen',done);});}
async function click(bot,who,slot,detail={},wait=false){if(!Number.isInteger(slot)||slot<0||slot>=top(bot).length)throw new Error('Invalid top-inventory slot');const fromTitle=bot.currentWindow.title,next=wait?windowAfter(bot,''):null;await bot.clickWindow(slot,0,0);log('CLICKED',{player:who,slot,windowTitle:fromTitle,...detail});if(next)await next;}
function named(bot,wanted){const matches=top(bot).flatMap((item,slot)=>item&&label(item).includes(wanted)?[{slot,name:label(item)}]:[]);if(matches.length!==1)throw new Error('Expected one actual named item '+wanted+'; found '+JSON.stringify(matches));return matches[0];}
for(const who of ['A','B','C','D']){
    const username='PhaseTwo'+who,bot=mineflayer.createBot({host:'127.0.0.1',port:manifest.server_port,username,auth:'offline',version:'1.21.11'});bots.set(who,bot);messages.set(who,[]);log('CONNECTING',{player:who,username});
    bot.on('spawn',()=>{if(bot.player.uuid!==manifest.players[who]){log('IDENTITY_FAILED',{player:who,actual:bot.player.uuid});process.exitCode=1;}log('SPAWN',{player:who,username,uuid:bot.player.uuid});});
    bot.on('messagestr',message=>{messages.get(who).push(message);log('MESSAGE',{player:who,message});});
    bot.on('windowOpen',window=>log('WINDOW',{player:who,id:window.id,title:window.title,items:window.slots.filter(Boolean).length}));
    bot._client.on('open_book',data=>log('BOOK',{player:who,data}));
    bot.on('error',error=>{log('ERROR',{player:who,message:error.message});process.exitCode=1;});
    bot.on('kicked',reason=>{log('KICKED',{player:who,reason});process.exitCode=1;});
    bot.on('end',reason=>{log('ENDED',{player:who,reason});if(quitting&&[...bots.values()].every(item=>item._client.ended))input.close();});
}
let commands=Promise.resolve();
input.on('line',line=>{commands=commands.then(async()=>{
    const [action,who,...rest]=line.trim().split(' ');
    if(action==='quit'){quitting=true;for(const bot of bots.values())bot.quit('national council acceptance complete');return;}
    const bot=bots.get(who);if(!bot)throw new Error('Unknown original client');
    if(action==='chat'){const message=rest.join(' ');bot.chat(message);log('SENT',{player:who,message});}
    else if(action==='wait-window'){if(!bot.currentWindow||!text(bot.currentWindow.title).includes(rest.join(' ')))await windowAfter(bot,rest.join(' '));log('WINDOW_VERIFIED',{player:who,title:bot.currentWindow.title});}
    else if(action==='open-story'){
        const next=windowAfter(bot,'共同书写历史');bot.chat('/chronicle stories');log('SENT',{player:who,message:'/chronicle stories'});await next;
        const match=named(bot,rest.join(' '));await click(bot,who,match.slot,{matchedName:match.name},true);
    }
    else if(action==='shift'){await bot.clickWindow(Number(rest[0]),0,1);log('SHIFTED',{player:who,slot:Number(rest[0])});}
    else if(action==='click-nowait')await click(bot,who,Number(rest[0]),{},false);
    else if(action==='click')await click(bot,who,Number(rest[0]),{},true);
    else if(action==='click-name'){const match=named(bot,rest.join(' '));await click(bot,who,match.slot,{matchedName:match.name},true);}
    else if(action==='choice'){
        const choice=rest[0],wanted='选项: '+choice,matches=top(bot).flatMap((item,slot)=>item&&lore(item).some(value=>value===wanted||value==='国家'+wanted)?[{slot,name:label(item)}]:[]);
        if(matches.length!==1)throw new Error('Expected one currently visible choice '+choice);
        await click(bot,who,matches[0].slot,{choice,matchedName:matches[0].name},true);
    }
    else if(action==='assert-choices'){
        const actual=top(bot).flatMap(item=>item?lore(item).filter(value=>value.startsWith('选项: ')||value.startsWith('国家选项: ')).map(value=>value.substring(value.indexOf(': ')+2)):[]).sort();
        const wanted=rest[0].split(',').filter(Boolean).sort();if(JSON.stringify(actual)!==JSON.stringify(wanted))throw new Error('Visible choices differ: '+JSON.stringify({actual,wanted}));log('CHOICES_VERIFIED',{player:who,title:bot.currentWindow.title,choices:actual});
    }
    else if(action==='assert-message'){const wanted=rest.join(' ');if(!messages.get(who).some(value=>value.includes(wanted)))throw new Error('Actual message not observed: '+wanted);log('MESSAGE_VERIFIED',{player:who,contains:wanted});}
    else if(action==='inspect')log('CLIENT_VIEW',{player:who,window:bot.currentWindow?{id:bot.currentWindow.id,title:bot.currentWindow.title,slots:top(bot).map((item,slot)=>item?{slot,name:item.name,label:label(item),lore:lore(item),components:item.components}:null).filter(Boolean)}:null});
    else if(action==='close'){if(bot.currentWindow)bot.closeWindow(bot.currentWindow);log('CLOSED',{player:who});}
    else throw new Error('Unknown controller action');
}).catch(error=>{log('ACTION_FAILED',{line,error:error.message});process.exitCode=1;});});
process.on('SIGINT',()=>{quitting=true;for(const bot of bots.values())bot.quit('operator interrupted verification');});
setTimeout(()=>{log('TIMEOUT');process.exitCode=1;quitting=true;for(const bot of bots.values())bot.quit('bounded acceptance timeout');input.close();},30*60*1000).unref();
