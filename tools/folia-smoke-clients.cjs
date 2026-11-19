// Automated loopback protocol clients, not a claim of human/visual GUI acceptance.
const fs = require('node:fs');
const path = require('node:path');
const mineflayer = require('C:/Users/Administrator/Documents/starworld-tools/mc-client/node_modules/mineflayer');
const root = path.resolve(__dirname,'..');
const logFile = path.join(root,'docs/verification/folia-protocol-clients.log');
const log = line => { const text = new Date().toISOString()+' '+line; fs.appendFileSync(logFile,text+'\n'); console.log(text); };
const clients = ['FoliaChronicleA','FoliaChronicleB'].map(username => {
  const bot=mineflayer.createBot({host:'127.0.0.1',port:25578,username,auth:'offline',version:'1.21.11',hideErrors:false});
  bot.on('spawn',()=>log('CLIENT_SPAWNED '+username+' uuid='+bot.player.uuid));
  bot.on('kicked',reason=>log('CLIENT_KICKED '+username+' '+JSON.stringify(reason)));
  bot.on('error',error=>log('CLIENT_ERROR '+username+' '+error.message));
  bot.on('end',reason=>log('CLIENT_ENDED '+username+' '+reason));
  return bot;
});
process.on('SIGINT',()=>{clients.forEach(bot=>bot.quit('smoke test completed'));setTimeout(()=>process.exit(0),300);});
setTimeout(()=>{log('CLIENT_TEST_TIMEOUT');clients.forEach(bot=>bot.quit('bounded smoke client timeout'));process.exit(1);},10*60*1000).unref();
