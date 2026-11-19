// Real protocol clients. E is never spawned until the operator explicitly enters `spawn E`.
const fs=require('node:fs');const path=require('node:path');const readline=require('node:readline');
const mineflayer=require('C:/Users/Administrator/Documents/starworld-tools/mc-client/node_modules/mineflayer');
const logName=process.argv[2]||'phase-two-paper-clients.log',boot=process.argv[3]||'boot1';
if(!/^[a-z0-9-]+\.log$/.test(logName)||!/^boot[123]$/.test(boot))throw new Error('Expected bounded evidence log name and boot1/boot2/boot3');
const logFile=path.resolve(__dirname,'../docs/verification/'+logName);
const log=(event,data)=>{const record={boot,...data};const line=new Date().toISOString()+' '+event+' '+JSON.stringify(record);fs.appendFileSync(logFile,line+'\n');console.log(line);};
const bots=new Map();let quitting=false;
const input=readline.createInterface({input:process.stdin,output:process.stdout,terminal:true});
function text(value){if(value==null)return '';if(typeof value==='string')return value;if(Array.isArray(value))return value.map(text).join('');if(value.type==='string')return value.value;if(value.type==='compound')return text(value.value);if(value.text!=null)return text(value.text)+text(value.extra);if(value.value!=null)return text(value.value);return '';}
function label(item){return text(item.customName||item.components?.find(c=>c.type==='custom_name')?.data);}
function lore(item){return (item.components?.find(c=>c.type==='lore')?.data||[]).map(text);}
function top(bot){if(!bot.currentWindow)throw new Error('No actual inventory window');return bot.currentWindow.slots.slice(0,bot.currentWindow.inventoryStart||54);}
async function click(bot,short,slot,reason){if(!Number.isInteger(slot)||slot<0||slot>=top(bot).length)throw new Error('Invalid top-inventory slot');await bot.clickWindow(slot,0,0);log('CLICKED',{player:short,slot,...reason});}
function spawn(short){
 if(!/^[ABCDE]$/.test(short)||bots.has(short))throw new Error('Unknown or already spawned client');
 const username='PhaseTwo'+short,bot=mineflayer.createBot({host:'127.0.0.1',port:25589,username,auth:'offline',version:'1.21.11'});bots.set(short,bot);log('CONNECTING',{player:short,username,late:short==='E'});
 bot.on('spawn',()=>log('SPAWN',{player:short,username,uuid:bot.player.uuid}));bot.on('messagestr',message=>log('MESSAGE',{player:short,message}));
 bot.on('windowOpen',window=>log('WINDOW',{player:short,id:window.id,title:window.title,items:window.slots.filter(Boolean).length}));
 bot._client.on('open_book',data=>log('BOOK',{player:short,data}));
 bot.on('error',error=>{log('ERROR',{player:short,message:error.message});process.exitCode=1;});
 bot.on('kicked',reason=>{log('KICKED',{player:short,reason});process.exitCode=1;});
 bot.on('end',reason=>{log('ENDED',{player:short,reason});if(quitting&&[...bots.values()].every(b=>b._client.ended)){input.close();}});
}
for(const short of ['A','B','C','D'])spawn(short);
let commands=Promise.resolve();
input.on('line',line=>{commands=commands.then(async()=>{
 const [action,short,...rest]=line.trim().split(' ');
 if(action==='spawn'){spawn(short);return;}
 if(action==='quit'){quitting=true;for(const bot of bots.values())bot.quit('phase two acceptance complete');input.close();return;}
 const bot=bots.get(short);if(!bot)throw new Error('Client has not been explicitly spawned');
 if(action==='chat'){const message=rest.join(' ');bot.chat(message);log('SENT',{player:short,message});}
 else if(action==='click')await click(bot,short,Number(rest[0]),{});
 else if(action==='click-name'){const wanted=rest.join(' ');const slots=top(bot);const matches=slots.flatMap((item,slot)=>item&&label(item).includes(wanted)?[{slot,name:label(item)}]:[]);if(matches.length!==1)throw new Error('Expected one actual named GUI item: '+wanted+' got '+JSON.stringify(matches));await click(bot,short,matches[0].slot,{matchedName:matches[0].name});}
 else if(action==='choice'){const wanted='选项: '+rest[0];const matches=top(bot).flatMap((item,slot)=>item&&lore(item).some(line=>line===wanted)?[{slot,name:label(item)}]:[]);if(matches.length!==1)throw new Error('Expected one currently visible choice '+rest[0]);await click(bot,short,matches[0].slot,{choice:rest[0],matchedName:matches[0].name});}
 else if(action==='assert-choices'){const actual=top(bot).flatMap(item=>item?lore(item).filter(line=>line.startsWith('选项: ')).map(line=>line.substring(4)):[]).sort();const expected=rest[0].split(',').sort();if(JSON.stringify(actual)!==JSON.stringify(expected))throw new Error('Visible choices differ: '+JSON.stringify({actual,expected}));log('CHOICES_VERIFIED',{player:short,choices:actual});}
 else if(action==='inspect')log('CLIENT_VIEW',{player:short,window:bot.currentWindow?{id:bot.currentWindow.id,title:bot.currentWindow.title,slots:top(bot).map((item,slot)=>item?{slot,name:item.name,label:label(item),lore:lore(item),components:item.components}:null).filter(Boolean)}:null});
 else if(action==='close'){if(bot.currentWindow)bot.closeWindow(bot.currentWindow);log('CLOSED',{player:short});}
 else throw new Error('Unknown controller command');
 }).catch(error=>{log('ACTION_FAILED',{line,error:error.message});process.exitCode=1;});});
process.on('SIGINT',()=>{quitting=true;for(const bot of bots.values())bot.quit('operator stopped verification');input.close();});
setTimeout(()=>{log('TIMEOUT',{});process.exitCode=1;quitting=true;for(const bot of bots.values())bot.quit('bounded acceptance timeout');input.close();},20*60*1000).unref();
