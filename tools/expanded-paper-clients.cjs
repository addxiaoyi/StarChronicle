// Real loopback protocol clients. No injected identities, packets granting permissions or fake GUI assertions.
const fs=require('node:fs');const path=require('node:path');const readline=require('node:readline');
const mineflayer=require('C:/Users/Administrator/Documents/starworld-tools/mc-client/node_modules/mineflayer');
const logName=process.argv[2]||process.env.EXPANDED_CLIENT_LOG||'expanded-paper-clients.log';
if(!/^[a-z0-9-]+\.log$/.test(logName))throw new Error('Invalid evidence log name');
const logFile=path.resolve(__dirname,'../docs/verification/'+logName);
const log=(event,data)=>{const line=new Date().toISOString()+' '+event+' '+JSON.stringify(data);fs.appendFileSync(logFile,line+'\n');console.log(line);};
const bots={};
for(const [short,username] of [['A','ExpandedCoreA'],['B','ExpandedCoreB']]){
 const bot=mineflayer.createBot({host:'127.0.0.1',port:25579,username,auth:'offline',version:'1.21.11'});bots[short]=bot;
 bot.on('spawn',()=>log('SPAWN',{player:short,username,uuid:bot.player.uuid}));
 bot.on('messagestr',text=>log('MESSAGE',{player:short,text}));
 bot.on('windowOpen',window=>log('WINDOW',{player:short,id:window.id,title:window.title,items:window.slots.filter(Boolean).length}));
 bot._client.on('open_book',data=>log('BOOK',{player:short,data}));
 bot.on('error',error=>log('ERROR',{player:short,message:error.message}));
 bot.on('kicked',reason=>log('KICKED',{player:short,reason}));
 bot.on('end',reason=>log('ENDED',{player:short,reason}));
}
const input=readline.createInterface({input:process.stdin,output:process.stdout,terminal:true});
input.on('line',async line=>{const [action,short,...rest]=line.trim().split(' ');const bot=bots[short];try{
 if(action==='quit'){Object.values(bots).forEach(b=>b.quit('expanded acceptance complete'));input.close();return;}
 if(!bot)throw new Error('Expected A or B');
 if(action==='chat'){const message=rest.join(' ');bot.chat(message);log('SENT',{player:short,message});}
 else if(action==='click'){await bot.clickWindow(Number(rest[0]),0,0);log('CLICKED',{player:short,slot:Number(rest[0])});}
 else if(action==='close'){if(bot.currentWindow)bot.closeWindow(bot.currentWindow);log('CLOSED',{player:short});}
 else if(action==='inspect')log('CLIENT_VIEW',{player:short,window:bot.currentWindow?{id:bot.currentWindow.id,title:bot.currentWindow.title,slots:bot.currentWindow.slots.map((item,index)=>item?{index,name:item.name,count:item.count,customName:item.customName,components:item.components}:null).filter(Boolean)}:null});
 else throw new Error('Unknown action');
}catch(error){log('ACTION_FAILED',{line,message:error.message});}});
process.on('SIGINT',()=>{Object.values(bots).forEach(b=>b.quit('verification stopped'));input.close();});
let ended=0;Object.values(bots).forEach(bot=>bot.on('end',()=>{if(++ended===2){input.close();process.exitCode=0;}}));
setTimeout(()=>{log('TIMEOUT',{});Object.values(bots).forEach(bot=>bot.quit('bounded verification timeout'));input.close();process.exitCode=1;},15*60*1000).unref();
