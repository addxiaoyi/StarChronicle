'use strict';
// Real protocol clients only: real crafting and inventory clicks, never fake Bukkit events,
// SQL writes, accept/complete commands, rewards or caller-supplied evidence.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),readline=require('node:readline');
const {attachCraftWire,itemIs,mapIngredientsMatch,mapCraftConsumed}=require('./quest-evidence-wire.cjs');
const args=process.argv.slice(2),arg=(key,fallback)=>{const at=args.indexOf(key);return at<0?fallback:args[at+1];};
const root=path.resolve(__dirname,'..'),run=path.resolve(arg('--run-dir','')),expected=arg('--expected-sha',''),attempt=arg('--attempt','1'),boot=Number(arg('--boot','0'));
const defaults=boot===3?'A,C':'A,B,C',cohort=arg('--players',defaults).split(',').filter(Boolean);
if(!/^[0-9a-f]{64}$/.test(expected)||!/^[a-z0-9-]{1,24}$/.test(attempt)||run!==path.join(root,'run','paper-quest-evidence-'+expected.slice(0,8)+'-'+attempt)||![1,2,3].includes(boot))throw new Error('Require this exact SHA-bound quest-evidence attempt and boot');
if(!cohort.length||new Set(cohort).size!==cohort.length||cohort.some(who=>!['A','B','C'].includes(who))||(boot===3&&cohort.includes('B')))throw new Error('Invalid original cohort; B must remain offline on boot3');
const manifest=JSON.parse(fs.readFileSync(path.join(run,'preparation.json'),'utf8'));
const sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
if(manifest.plugin_sha256!==expected||sha(path.join(run,'plugins/StarChronicle.jar'))!==expected||manifest.server_ip!=='127.0.0.1'||!Number.isInteger(manifest.server_port))throw new Error('Product or loopback endpoint differs from preparation');
if(JSON.stringify(Object.keys(manifest.players).sort())!==JSON.stringify(['A','B','C']))throw new Error('Manifest must bind the original three players');
function offlineId(name){const bytes=crypto.createHash('md5').update('OfflinePlayer:'+name).digest();bytes[6]=(bytes[6]&15)|48;bytes[8]=(bytes[8]&63)|128;const h=bytes.toString('hex');return h.slice(0,8)+'-'+h.slice(8,12)+'-'+h.slice(12,16)+'-'+h.slice(16,20)+'-'+h.slice(20);}
for(const who of ['A','B','C'])if(manifest.players[who]!==offlineId('PhaseTwo'+who))throw new Error('Prepared player UUID does not match actual offline identity');
const quests=manifest.native_quests,stories=manifest.stories;
if(!quests||!stories||!['heir','siege','atlas'].every(key=>quests[key]?.id&&quests[key]?.name)||!['player','nation','atlas'].every(key=>stories[key]?.id&&stories[key]?.title))throw new Error('Require explicit prepared native quest and story identities');
const mineflayerEntry=require.resolve(arg('--mineflayer','C:/Users/Administrator/Documents/starworld-tools/mc-client/node_modules/mineflayer'));
const mineflayer=require(mineflayerEntry),nativeRequire=require('node:module').createRequire(mineflayerEntry);
const logFile=path.join(run,'evidence/quest-evidence-clients.log');
const log=(kind,value={})=>{const line=new Date().toISOString()+' '+kind+' '+JSON.stringify({boot,...value},(_,v)=>typeof v==='bigint'?v.toString():v);fs.appendFileSync(logFile,line+'\n');console.log(line);};
const runtimeDirectory=path.join(run,'evidence','quest-evidence-client-runtime-used-boot'+boot);fs.mkdirSync(runtimeDirectory);
const runtimeSources={mineflayer_entry:mineflayerEntry,mineflayer_inventory:path.join(path.dirname(mineflayerEntry),'lib/plugins/inventory.js'),
    prismarine_windows_entry:nativeRequire.resolve('prismarine-windows'),prismarine_windows_model:path.join(path.dirname(nativeRequire.resolve('prismarine-windows')),'lib/Window.js'),
    prismarine_item_entry:nativeRequire.resolve('prismarine-item'),craft_wire:require.resolve('./quest-evidence-wire.cjs')};
const runtimeManifest={versions:{mineflayer:JSON.parse(fs.readFileSync(path.join(path.dirname(mineflayerEntry),'package.json'),'utf8')).version,
    prismarine_windows:JSON.parse(fs.readFileSync(path.join(path.dirname(runtimeSources.prismarine_windows_entry),'package.json'),'utf8')).version,
    prismarine_item:JSON.parse(fs.readFileSync(path.join(path.dirname(runtimeSources.prismarine_item_entry),'package.json'),'utf8')).version},sources:{},attribution:{}};
for(const [name,source] of Object.entries(runtimeSources)){const bytes=fs.readFileSync(source),target=path.join(runtimeDirectory,name+'.cjs');fs.writeFileSync(target,bytes,{flag:'wx'});runtimeManifest.sources[name]={path:fs.realpathSync(source),copy:path.relative(run,target),sha256:crypto.createHash('sha256').update(bytes).digest('hex')};}
for(const [name,directory] of Object.entries({mineflayer:path.dirname(mineflayerEntry),prismarine_windows:path.dirname(runtimeSources.prismarine_windows_entry),prismarine_item:path.dirname(runtimeSources.prismarine_item_entry)})){
    const license=['LICENSE','LICENSE.txt','LICENSE.md'].map(file=>path.join(directory,file)).find(file=>fs.existsSync(file));
    runtimeManifest.attribution[name]={};
    for(const [kind,source] of Object.entries({package:path.join(directory,'package.json'),...(license?{license}:{})})){const bytes=fs.readFileSync(source),target=path.join(runtimeDirectory,name+'-'+(kind==='package'?'package.json':'LICENSE'));fs.writeFileSync(target,bytes,{flag:'wx'});runtimeManifest.attribution[name][kind]={copy:path.relative(run,target),sha256:crypto.createHash('sha256').update(bytes).digest('hex')};}
    if(!license)runtimeManifest.attribution[name].license={status:'MISSING_FROM_INSTALLED_PACKAGE',copy:null,sha256:null,declaredLicense:JSON.parse(fs.readFileSync(path.join(directory,'package.json'),'utf8')).license,note:'Keep these vendor source snapshots in the local run; distribute version/SHA/source references unless the original license is obtained.'};
}
fs.writeFileSync(path.join(runtimeDirectory,'runtime.json'),JSON.stringify(runtimeManifest,null,2)+'\n',{flag:'wx'});
log('CLIENT_RUNTIME_SOURCES',runtimeManifest);
const bots=new Map(),messages=new Map(),nativeDetails=new Map(),storyContexts=new Map(),quits=new Set(),ended=new Set();
let allQuit=false,commands=Promise.resolve(),failed=false;
const input=readline.createInterface({input:process.stdin,output:process.stdout,terminal:false});
function fail(kind,value){failed=true;process.exitCode=1;log(kind,value);}
function text(value){
    if(value==null)return '';if(typeof value==='string')return value.replace(/\u00a7[0-9A-FK-OR]/gi,'');if(Array.isArray(value))return value.map(text).join('');
    if(value.type==='string')return text(value.value);if(value.type==='compound')return text(value.value);
    if(value.text!=null)return text(value.text)+text(value.extra);if(value.value!=null)return text(value.value);return '';
}
const label=item=>text(item?.customName||item?.components?.find(c=>c.type==='custom_name')?.data);
const lore=item=>(item?.components?.find(c=>c.type==='lore')?.data||[]).map(text);
const title=bot=>text(bot.currentWindow?.title);
function top(bot){const window=bot.currentWindow;if(!window)throw new Error('No actual current inventory');if(!Number.isInteger(window.inventoryStart)||window.inventoryStart<1)throw new Error('Unknown top-inventory boundary');return window.slots.slice(0,window.inventoryStart);}
function at(bot,slot,material,name){const item=top(bot)[slot];if(!item||(material&&item.name!==material)||(name&&label(item)!==name))throw new Error('Actual slot identity differs at '+slot);return item;}
const boundaries=['实例: ','范围: ','参与人数: ','当前阶段: ','国家票节点: ','一国一席','投票: ','节点: ','修订号: ','状态: ','效果: ','参与者: ','权限和条件','同一阶段','提交时','ID: ','名称: ','描述: ','分类: ','限时: '];
// Chronicle wraps each logical lore field into 38-character rows. Continuations are
// joined until the next known field, preserving a 64-character ID rather than truncating it.
function field(item,prefix){const lines=lore(item),start=lines.findIndex(line=>line.startsWith(prefix));if(start<0)return null;let value=lines[start].slice(prefix.length);for(let i=start+1;i<lines.length;i++){if(boundaries.some(next=>lines[i].startsWith(next)))break;value+=lines[i];}return value.trim();}
function unique(bot,predicate,description){const matches=top(bot).flatMap((item,slot)=>item&&predicate(item,slot)?[{item,slot}]:[]);if(matches.length!==1)throw new Error('Expected one '+description+'; found '+matches.length);return matches[0];}
function inventory(bot){const counts={};for(const item of bot.inventory.items())counts[item.name]=(counts[item.name]||0)+item.count;return counts;}
function inventoryTypeCount(bot,type){return bot.inventory.items().filter(item=>item.type===type).reduce((total,item)=>total+item.count,0);}
function inventoryDelta(before,after){const delta={};for(const name of new Set([...Object.keys(before),...Object.keys(after)])){const change=(after[name]||0)-(before[name]||0);if(change)delta[name]=change;}return delta;}
function exactCraftDelta(delta,expectedIngredients,outputName,amount){const expected={...Object.fromEntries(Object.entries(expectedIngredients).map(([name,count])=>[name,-count])),[outputName]:amount};return JSON.stringify(Object.entries(delta).sort())===JSON.stringify(Object.entries(expected).sort());}
async function observeUntil(predicate,description,timeoutMs=5000){const deadline=Date.now()+timeoutMs;while(Date.now()<deadline){if(predicate())return;await new Promise(resolve=>setTimeout(resolve,50));}if(!predicate())throw new Error('Timed out observing '+description);}
function view(bot){return {window:bot.currentWindow?{id:bot.currentWindow.id,type:bot.currentWindow.type,title:title(bot),cursorState:bot.currentWindow.selectedItem===null?'NULL':typeof bot.currentWindow.selectedItem==='undefined'?'UNDEFINED':'ITEM',cursor:bot.currentWindow.selectedItem?{name:bot.currentWindow.selectedItem.name,count:bot.currentWindow.selectedItem.count,type:bot.currentWindow.selectedItem.type}:null,slots:top(bot).flatMap((item,slot)=>item?[{slot,name:item.name,count:item.count,label:label(item),lore:lore(item),components:item.components}]:[])}:null,inventory:inventory(bot)};}
function waitEvent(bot,event,predicate,description,ms=15000){let timer,handler;const promise=new Promise((resolve,reject)=>{handler=(...values)=>{try{if(!predicate||predicate(...values)){cleanup();resolve(values[0]);}}catch(error){cleanup();reject(error);}};timer=setTimeout(()=>{cleanup();reject(new Error('Timeout waiting for '+description));},ms);bot.on(event,handler);});function cleanup(){clearTimeout(timer);if(handler)bot.removeListener(event,handler);}return {promise,cancel:cleanup};}
async function windowAction(bot,action,expectedTitle,description){const waiting=waitEvent(bot,'windowOpen',window=>expectedTitle(text(window.title)),description);try{await action();await waiting.promise;}finally{waiting.cancel();}if(!expectedTitle(title(bot)))throw new Error('Current window changed after '+description);}
async function checkedClick(bot,who,slot,guard,nextTitle,detail){guard();const windowId=bot.currentWindow.id,from=title(bot);if(nextTitle)await windowAction(bot,()=>{guard();if(bot.currentWindow.id!==windowId)throw new Error('Window token changed before click');return bot.clickWindow(slot,0,0);},nextTitle,'verified inventory transition');else{guard();await bot.clickWindow(slot,0,0);}log('CLICKED',{player:who,slot,windowId,fromTitle:from,...detail});}
async function craftMapByGrid(bot,who,table,before,outputId,outputName,expectedIngredients,beforeOutput,recipe){
    if(typeof bot._syncWindow!=='function')throw new Error('Reviewed native inventory synchronization API unavailable; no map click was sent');
    const NativeItem=nativeRequire('prismarine-item')(bot.registry);
    const wire=attachCraftWire(bot,who,log,view,raw=>{const item=NativeItem.fromNotch(raw);return item?{type:item.type,name:item.name,count:item.count}:null;});
    try{await craftMapByGridOnce(bot,who,table,before,outputId,outputName,expectedIngredients,beforeOutput,recipe,wire);}
    catch(error){wire.failure(error);throw error;}
    finally{wire.stop();}
}
async function craftMapByGridOnce(bot,who,table,before,outputId,outputName,expectedIngredients,beforeOutput,recipe,wire){
    const opened=waitEvent(bot,'windowOpen',window=>window.type.startsWith('minecraft:crafting'),'actual map crafting table');
    bot.activateBlock(table);const window=await opened.promise;opened.cancel();if(bot.currentWindow!==window)throw new Error('Crafting window changed before map interaction');
    const paper=bot.registry.itemsByName.paper,compass=bot.registry.itemsByName.compass;if(!paper||!compass)throw new Error('Map ingredients missing from actual registry');
    const paperStack=window.findInventoryItem(paper.id),compassStack=window.findInventoryItem(compass.id);
    if(!paperStack||paperStack.count!==8||!compassStack||compassStack.count!==1)throw new Error('Map grid requires exactly eight paper and one compass in the actual inventory');
    const paperSlots=[1,2,3,4,6,7,8,9];
    log('CRAFT_REQUESTED',{player:who,item:'map',attempt:1,output:outputName,outputId,amount:1,table:{x:table.position.x,y:table.position.y,z:table.position.z},before,recipe:{result:recipe.result,delta:recipe.delta},implementation:'real crafting-table grid clicks and actual server result-slot click'});
    log('CRAFT_GRID_REQUESTED',{player:who,item:'map',windowId:window.id,paperSlot:paperStack.slot,compassSlot:compassStack.slot,paperSlots,compassSlotInGrid:5,resultSlot:0,implementation:'real crafting-table inventory clicks'});
    wire.record('BEFORE_PAPER_PICKUP',{slot:paperStack.slot});
    await bot.clickWindow(paperStack.slot,0,0);
    await wire.synchronize(window,snapshot=>mapIngredientsMatch(snapshot,paper.id,compass.id,0,'inventory'),'paper pickup');
    wire.record('AFTER_PAPER_PICKUP',{slot:paperStack.slot});
    for(let index=0;index<paperSlots.length;index++){
        const slot=paperSlots[index],remaining=paperSlots.length-index-1;
        if(bot.currentWindow!==window||window.selectedItem?.type!==paper.id)throw new Error('Map paper cursor changed before grid placement');
        wire.record('BEFORE_PAPER_PLACEMENT',{slot,remainingAfter:remaining});
        await bot.clickWindow(slot,1,0);
        await wire.synchronize(window,snapshot=>mapIngredientsMatch(snapshot,paper.id,compass.id,index+1,'inventory'),'paper placement slot '+slot);
        await observeUntil(()=>window.slots[slot]?.type===paper.id&&window.slots[slot]?.count===1&&(!remaining?window.selectedItem===null:window.selectedItem?.type===paper.id&&window.selectedItem.count===remaining),'map paper placement slot '+slot);
        wire.record('AFTER_PAPER_PLACEMENT_LOCAL_MODEL',{slot,remainingAfter:remaining,note:'This model check alone is not server acknowledgement.'});
    }
    if(window.selectedItem!==null)throw new Error('Map paper stack was not placed exactly');
    wire.record('BEFORE_COMPASS_PICKUP',{slot:compassStack.slot});
    await bot.clickWindow(compassStack.slot,0,0);
    await wire.synchronize(window,snapshot=>mapIngredientsMatch(snapshot,paper.id,compass.id,8,'cursor'),'compass pickup');
    await observeUntil(()=>window.selectedItem?.type===compass.id&&window.selectedItem.count===1,'map compass pickup');
    wire.record('AFTER_COMPASS_PICKUP_LOCAL_MODEL',{slot:compassStack.slot});
    wire.record('BEFORE_COMPASS_PLACEMENT',{slot:5});
    await bot.clickWindow(5,1,0);
    const ready=await wire.synchronize(window,snapshot=>mapIngredientsMatch(snapshot,paper.id,compass.id,8,'grid')&&itemIs(snapshot.slots[0],outputId,1),'compass placement and actual map result');
    await observeUntil(()=>window.slots[5]?.type===compass.id&&window.slots[5]?.count===1&&window.selectedItem===null,'map compass placement');
    wire.record('AFTER_COMPASS_PLACEMENT_LOCAL_MODEL',{slot:5});
    let result=null;
    for(let sample=0;sample<60;sample++){
        if(bot.currentWindow!==window)throw new Error('Map crafting window closed before server result confirmation');
        const actual=window.slots[0];if(actual?.type===outputId&&actual.count===1){result=actual;break;}
        await new Promise(resolve=>setTimeout(resolve,250));
    }
    if(!result)throw new Error('Server did not expose the actual map result in crafting output slot');
    log('CRAFT_RESULT_VERIFIED',{player:who,item:'map',windowId:window.id,slot:0,name:outputName,count:result.count,serverSnapshot:ready,implementation:'actual received full-window crafting result'});
    wire.record('BEFORE_ONLY_RESULT_CLICK',{slot:0});
    await bot.clickWindow(0,0,0);
    await wire.synchronize(window,snapshot=>mapCraftConsumed(snapshot,paper.id,compass.id,outputId),'only result click');
    if(window.selectedItem?.type===outputId&&window.selectedItem.count===1){
        const outputSlot=window.firstEmptySlotRange(window.inventoryStart,window.inventoryEnd);
        if(outputSlot==null)throw new Error('Crafted map is on the cursor and player inventory has no empty destination');
        log('CRAFT_RESULT_ON_CURSOR',{player:who,item:'map',windowId:window.id,cursor:{name:outputName,count:window.selectedItem.count},destinationSlot:outputSlot,note:'Move the already-created result only; do not click the recipe result again'});
        await bot.clickWindow(outputSlot,0,0);
        await wire.synchronize(window,snapshot=>mapCraftConsumed(snapshot,paper.id,compass.id,outputId)&&snapshot.cursor===null&&itemIs(snapshot.slots[outputSlot],outputId,1),'already crafted map moved to inventory');
        await observeUntil(()=>window.selectedItem===null&&window.slots[outputSlot]?.type===outputId&&window.slots[outputSlot]?.count===1,'map cursor placed into player inventory');
    }
    if(bot.currentWindow===window)await bot.closeWindow(window);
    let after=inventory(bot),delta=inventoryDelta(before,after);
    for(let sample=0;sample<60&&!exactCraftDelta(delta,expectedIngredients,outputName,1);sample++){await new Promise(resolve=>setTimeout(resolve,250));after=inventory(bot);delta=inventoryDelta(before,after);}
    if(!exactCraftDelta(delta,expectedIngredients,outputName,1)||inventoryTypeCount(bot,outputId)-beforeOutput!==1){
        log('CRAFT_UNCONFIRMED',{player:who,item:'map',output:outputName,delta,after,...view(bot),reason:'Map result was clicked once; output inventory delta remains uncertain and cannot be retried'});
        throw new Error('Actual map craft result is uncertain; refusing another output click '+JSON.stringify({delta,...view(bot)}));
    }
    log('CRAFTED',{player:who,item:'map',attempt:1,output:outputName,outputId,amount:1,before,after,delta,implementation:'real crafting grid and actual server result-slot click; no synthetic events'});
}
function quest(wanted){const matches=Object.entries(quests).filter(([key,value])=>key===wanted||value.id===wanted||value.name===wanted);if(matches.length!==1)throw new Error('Unknown or ambiguous prepared native quest identity');return matches[0][1];}
function story(wanted){const matches=Object.entries(stories).filter(([key,value])=>key===wanted||value.id===wanted||value.title===wanted);if(matches.length!==1)throw new Error('Unknown or ambiguous prepared story identity');return matches[0][1];}
function detailGuard(bot,expectedQuest){
    if(title(bot)!=='任务详情 - '+expectedQuest.name)throw new Error('Native detail title is not the requested quest');
    at(bot,4,null,expectedQuest.name);const info=at(bot,11,'paper','任务信息');
    if(field(info,'ID: ')!==expectedQuest.id||field(info,'名称: ')!==expectedQuest.name)throw new Error('Native detail ID/name differ from requested quest');
}
async function nativeOpen(bot,who,wanted){
    const expectedQuest=quest(wanted);nativeDetails.delete(who);
    await windowAction(bot,()=>{bot.chat('/questgui progress');log('SENT',{player:who,message:'/questgui progress'});},value=>/^任务进度 \[\d+\]$/.test(value),'native progress window');
    const selected=unique(bot,(item,slot)=>slot>=9&&slot<=44&&item.name==='writable_book'&&label(item)===expectedQuest.name&&lore(item).some(line=>/^进度: \d+(?:\.\d+)?%$/.test(line)),'active native quest progress icon');
    await checkedClick(bot,who,selected.slot,()=>{if(!/^任务进度 \[\d+\]$/.test(title(bot)))throw new Error('Not the native progress window');at(bot,selected.slot,'writable_book',expectedQuest.name);},value=>value==='任务详情 - '+expectedQuest.name,{path:'native-progress',questId:expectedQuest.id});
    detailGuard(bot,expectedQuest);nativeDetails.set(who,{...expectedQuest,windowId:bot.currentWindow.id});log('NATIVE_DETAIL_VERIFIED',{player:who,questId:expectedQuest.id,questName:expectedQuest.name,windowId:bot.currentWindow.id,...view(bot)});
}
async function nativeComplete(bot,who){
    const expectedQuest=nativeDetails.get(who);if(!expectedQuest||bot.currentWindow?.id!==expectedQuest.windowId)throw new Error('Native completion requires a freshly verified detail window');
    const guard=()=>{detailGuard(bot,expectedQuest);if(bot.currentWindow.id!==expectedQuest.windowId)throw new Error('Native detail window token changed');const button=at(bot,32,'nether_star','完成任务');if(!lore(button).includes('所有目标已完成！')||!lore(button).includes('点击领取奖励'))throw new Error('Actual native completion button is unavailable');at(bot,22,'lime_stained_glass_pane','状态: 可完成！');};
    // StarCore emits the hub title as either the legacy short form or the
    // namespaced form used by current builds. Both identify the same verified
    // task-center transition; keep the predicate exact to avoid accepting an
    // unrelated inventory window.
    await checkedClick(bot,who,32,guard,value=>value==='任务中心'||value==='任务中心 - 任务中心',{path:'native-complete',questId:expectedQuest.id});nativeDetails.delete(who);
    log('NATIVE_COMPLETION_CLICK_ONLY',{player:who,questId:expectedQuest.id,note:'A GUI click is not a receipt. The native pending completion message is retained; fixture must verify canonical post-ack evidence.'});
}
async function craft(bot,who,wanted){
    if(!['paper','book','map'].includes(wanted))throw new Error('Only prepared paper/book/map recipes are permitted');if(bot.currentWindow)throw new Error('Close the actual menu before crafting');
    const item=bot.registry.itemsByName[wanted],tableType=bot.registry.blocksByName.crafting_table;if(!item||!tableType)throw new Error('Recipe registry is unavailable');
    const table=bot.findBlock({matching:tableType.id,maxDistance:6});if(!table||table.name!=='crafting_table'||bot.entity.position.distanceTo(table.position)>5)throw new Error('No actual nearby crafting table in reach');
    const emptyHotbar=bot.inventory.slots.slice(36,45).findIndex(slot=>slot==null);if(emptyHotbar<0)throw new Error('Need an empty hotbar slot to activate the crafting table without a held item');
    bot.setQuickBarSlot(emptyHotbar);await bot.waitForTicks(2);if(bot.heldItem)throw new Error('Selected crafting slot is not empty');
    log('CRAFT_HAND_CLEARED',{player:who,quickBarSlot:emptyHotbar,heldItem:null});
    const expectedIngredients={paper:{sugar_cane:3},book:{paper:3,leather:1},map:{paper:8,compass:1}}[wanted],amount=wanted==='paper'?3:1;
    const matching=bot.recipesFor(item.id,null,amount,table).filter(recipe=>{if(recipe.result.id!==item.id||recipe.result.count!==amount)return false;const negative={};for(const part of recipe.delta)if(part.count<0){const name=bot.registry.items[part.id]?.name;if(!name)return false;negative[name]=(negative[name]||0)-part.count;}return JSON.stringify(Object.entries(negative).sort())===JSON.stringify(Object.entries(expectedIngredients).sort());});
    if(!matching.length)throw new Error('No actual craftable expected recipe; fixture must supply ingredients first');const outputId=matching[0].result.id,outputName=bot.registry.items[outputId]?.name||wanted,before=inventory(bot),beforeOutput=inventoryTypeCount(bot,outputId);
    if(wanted==='map'){await craftMapByGrid(bot,who,table,before,outputId,outputName,expectedIngredients,beforeOutput,matching[0]);return;}
    const attemptNo=1,now=inventory(bot),preDelta=inventoryDelta(before,now);if(Object.keys(preDelta).length)throw new Error('Inventory changed before craft; refusing an ambiguous retry '+JSON.stringify({preDelta,...view(bot)}));
    log('CRAFT_REQUESTED',{player:who,item:wanted,attempt:attemptNo,output:outputName,outputId,amount,table:{x:table.position.x,y:table.position.y,z:table.position.z},before,recipe:{result:matching[0].result,delta:matching[0].delta},implementation:'mineflayer bot.craft over the real crafting table with an empty main hand'});
    let craftError=null;try{await bot.craft(matching[0],1,table);}catch(error){craftError=error;}
    let after=inventory(bot),delta=inventoryDelta(before,after);
    // Paper may deliver the inventory transaction and the CraftItemEvent on different
    // ticks. Observe long enough for the output before classifying the result.
    for(let sample=0;sample<60&&!exactCraftDelta(delta,expectedIngredients,outputName,amount);sample++){await new Promise(resolve=>setTimeout(resolve,250));after=inventory(bot);delta=inventoryDelta(before,after);}
    if(exactCraftDelta(delta,expectedIngredients,outputName,amount)&&inventoryTypeCount(bot,outputId)-beforeOutput===amount){
        log('CRAFTED',{player:who,item:wanted,attempt:attemptNo,output:outputName,outputId,amount,before,after,delta,craftError:craftError?.message||null,note:'Real protocol craft; no synthetic CraftItemEvent or objective update was emitted.'});return;
    }
    log('CRAFT_UNCONFIRMED',{player:who,item:wanted,attempt:attemptNo,output:outputName,expectedIngredients,delta,after,window:bot.currentWindow?{id:bot.currentWindow.id,type:bot.currentWindow.type,title:title(bot)}:null,craftError:craftError?.message||null,reason:'A missing inventory result cannot prove the server did not commit; no retry is safe without native progress reconciliation'});
    throw new Error('Actual craft was not confirmed exactly; refusing retry because server-side commit is uncertain '+JSON.stringify({outputName,delta}));
}
function storyGuard(bot,context){const actual=title(bot);if(actual!==context.title&&actual!=='国家议会 · '+context.title)throw new Error('Not the verified story window');const header=at(bot,4,'written_book',context.title);if(field(header,'实例: ')!==context.instanceId)throw new Error('Story instance changed');return header;}
async function openStory(bot,who,wanted){
    const expectedStory=story(wanted);await windowAction(bot,()=>{bot.chat('/chronicle stories');log('SENT',{player:who,message:'/chronicle stories'});},value=>/^共同书写历史 · \d+$/.test(value),'Chronicle story list');
    const selected=unique(bot,(item,slot)=>slot<45&&['book','enchanted_book'].includes(item.name)&&label(item)===expectedStory.title,'prepared story item');const id=field(selected.item,'实例: ');if(!/^[0-9a-f]{64}$/.test(id||''))throw new Error('Actual wrapped story ID is invalid');
    await checkedClick(bot,who,selected.slot,()=>{if(!/^共同书写历史 · \d+$/.test(title(bot)))throw new Error('Not the story list');const current=at(bot,selected.slot,selected.item.name,expectedStory.title);if(field(current,'实例: ')!==id)throw new Error('Selected story identity changed');},value=>value===expectedStory.title||value==='国家议会 · '+expectedStory.title,{path:'story-open',storyId:expectedStory.id,instanceId:id});
    const context={...expectedStory,instanceId:id};storyGuard(bot,context);storyContexts.set(who,context);log('STORY_VERIFIED',{player:who,storyId:context.id,instanceId:id,...view(bot)});
}
async function choose(bot,who,choice){
    const context=storyContexts.get(who);if(!context)throw new Error('Open and verify the actual prepared story first');const header=storyGuard(bot,context),national=title(bot).startsWith('国家议会 · '),prefix=national?'国家选项: ':'选项: ';
    const selected=unique(bot,(item,slot)=>slot>=9&&slot<45&&item.name==='lime_dye'&&field(item,prefix)===choice,'currently visible exact story choice');const node=field(header,national?'国家票节点: ':'当前阶段: ');if(!node)throw new Error('No actual node token');
    const revision=national?field(selected.item,'修订号: '):null;if(national&&(!/^\d+$/.test(revision||'')||field(selected.item,'节点: ')!==node))throw new Error('National choice has no coherent node/revision token');
    await checkedClick(bot,who,selected.slot,()=>{const currentHeader=storyGuard(bot,context);if(field(currentHeader,national?'国家票节点: ':'当前阶段: ')!==node)throw new Error('Displayed node changed');const current=at(bot,selected.slot,'lime_dye',label(selected.item));if(field(current,prefix)!==choice||(national&&(field(current,'修订号: ')!==revision||field(current,'节点: ')!==node)))throw new Error('Choice token changed before click');},value=>/^共同书写历史 · \d+$/.test(value)||value==='国家议会 · '+context.title,{path:'story-choice',storyId:context.id,instanceId:context.instanceId,choice,voteUnit:national?'NATION':'PLAYER',node,revision});
    log('GUI_CHOICE',{player:who,storyId:context.id,instanceId:context.instanceId,choice,voteUnit:national?'NATION':'PLAYER',node,revision});
}
async function gateList(bot,who,wanted){
    if(wanted)await openStory(bot,who,wanted);const context=storyContexts.get(who);if(!context)throw new Error('Gate list requires a verified story');
    await checkedClick(bot,who,50,()=>{storyGuard(bot,context);at(bot,50,'iron_door','任务门槛状态');},value=>/^任务门槛 · \d+$/.test(value),{path:'gate-list',storyId:context.id,instanceId:context.instanceId});
    at(bot,4,'iron_door',context.title);log('GATE_LIST_VERIFIED',{player:who,storyId:context.id,instanceId:context.instanceId,...view(bot)});
}
async function gateDetail(bot,who,wanted,owner){
    const expectedQuest=quest(wanted),context=storyContexts.get(who);if(!context||!/^任务门槛 · \d+$/.test(title(bot)))throw new Error('Open the actual gate list first');at(bot,4,'iron_door',context.title);
    const expectedOwner=owner?(manifest.players[owner]||owner):null;
    const selected=unique(bot,(item,slot)=>slot>=9&&slot<45&&['book','lime_dye'].includes(item.name)&&label(item)===expectedQuest.id&&(!expectedOwner||field(item,'参与者: ')===expectedOwner),'visible exact quest gate');const actualOwner=field(selected.item,'参与者: '),effect=field(selected.item,'效果: ');
    if(!/^[0-9a-f-]{36}$/.test(actualOwner||'')||!effect)throw new Error('Gate row has no actual participant/effect identity');
    await checkedClick(bot,who,selected.slot,()=>{if(!/^任务门槛 · \d+$/.test(title(bot)))throw new Error('Gate list changed');const item=at(bot,selected.slot,selected.item.name,expectedQuest.id);if(field(item,'参与者: ')!==actualOwner||field(item,'效果: ')!==effect)throw new Error('Gate row changed');},value=>/^任务门槛详情 · \d+$/.test(value),{path:'gate-detail',questId:expectedQuest.id,owner:actualOwner,effect});
    const detail=(labelPrefix)=>unique(bot,item=>item.name==='paper'&&new RegExp('^'+labelPrefix+' · \\d+$').test(label(item)),labelPrefix+' field').item;
    if(lore(detail('任务 ID')).join('')!==expectedQuest.id||lore(detail('参与者 UUID')).join('')!==actualOwner||lore(detail('效果 ID')).join('')!==effect)throw new Error('Gate detail identity differs from its selected list row');
    log('GATE_DETAIL_VERIFIED',{player:who,questId:expectedQuest.id,owner:actualOwner,effect,...view(bot)});
}
function quitOne(who,reason){const bot=bots.get(who);if(!bot)throw new Error('Client was not started: '+who);if(quits.has(who))return;quits.add(who);log('QUIT_REQUESTED',{player:who,reason});if(!ended.has(who))bot.quit(reason);}
function quitAll(reason){allQuit=true;log('QUIT_ALL_REQUESTED',{reason});for(const who of cohort)quitOne(who,reason);if(ended.size===cohort.length)input.close();}
for(const who of cohort){
    const username='PhaseTwo'+who,bot=mineflayer.createBot({host:manifest.server_ip,port:manifest.server_port,username,auth:'offline',version:'1.21.11'});bots.set(who,bot);messages.set(who,[]);log('CONNECTING',{player:who,username});
    bot.on('spawn',()=>{if(bot.player.uuid!==manifest.players[who]){fail('IDENTITY_FAILED',{player:who,uuid:bot.player.uuid});quitAll('identity mismatch');return;}log('SPAWN',{player:who,username,uuid:bot.player.uuid});});
    bot.on('messagestr',message=>{messages.get(who).push(message);log('MESSAGE',{player:who,message});});
    bot.on('windowOpen',window=>{nativeDetails.delete(who);log('WINDOW',{player:who,id:window.id,type:window.type,title:text(window.title),items:window.slots.filter(Boolean).length});});
    bot.on('windowClose',window=>{nativeDetails.delete(who);log('WINDOW_CLOSED',{player:who,id:window?.id});});
    bot._client.on('open_book',data=>log('BOOK',{player:who,data}));
    bot.on('error',error=>fail('ERROR',{player:who,message:error.message}));bot.on('kicked',reason=>fail('KICKED',{player:who,reason}));
    bot.on('end',reason=>{ended.add(who);log('ENDED',{player:who,reason,requestedQuit:quits.has(who)});if(!quits.has(who))fail('UNEXPECTED_END',{player:who,reason});if(ended.size===cohort.length){if(allQuit&&quits.size===cohort.length)log('ALL_SELECTED_CLIENTS_ENDED',{requested:true,players:[...cohort].sort()});else fail('ALL_SELECTED_CLIENTS_ENDED_MISSING_REQUEST',{requested:quits.size===cohort.length,players:[...cohort].sort()});input.close();}});
}
input.on('line',line=>{commands=commands.then(async()=>{
    const [action,who,...rest]=line.trim().split(/\s+/);if(!action)return;
    if(action==='quit'){if(!who||who==='all')quitAll('quest evidence acceptance normal quit');else quitOne(who,'quest evidence acceptance selected normal quit');return;}
    const bot=bots.get(who);if(!bot||ended.has(who))throw new Error('No connected selected client '+who);if(!bot.entity)throw new Error('Wait for actual SPAWN before client operations');
    if(action==='craft')await craft(bot,who,rest[0]);else if(action==='native-open')await nativeOpen(bot,who,rest.join(' '));else if(action==='native-complete')await nativeComplete(bot,who);
    else if(action==='open-story')await openStory(bot,who,rest.join(' '));else if(action==='choice')await choose(bot,who,rest[0]);else if(action==='gate-list')await gateList(bot,who,rest.join(' ')||null);else if(action==='gate-detail')await gateDetail(bot,who,rest[0],rest[1]);
    else if(action==='inspect')log('CLIENT_VIEW',{player:who,...view(bot)});
    else if(action==='close'){if(bot.currentWindow)await bot.closeWindow(bot.currentWindow);log('CLOSED',{player:who});}
    else if(action==='assert-message'){const wanted=rest.join(' ');if(!messages.get(who).some(message=>message.includes(wanted)))throw new Error('Actual message was not observed');log('MESSAGE_VERIFIED',{player:who,contains:wanted});}
    else if(action==='chat'){const message=rest.join(' ');if(!/^\/chronicle (?:stories|history|past|fate|book)(?:\s|$)/.test(message)&&message!=='/questgui progress')throw new Error('Only read-only prepared client commands are allowed; native completion must use its actual GUI');bot.chat(message);log('SENT',{player:who,message});}
    else if(action==='shift'){const context=storyContexts.get(who);if(!context)throw new Error('Shift probe requires a verified Chronicle story');storyGuard(bot,context);const slot=Number(rest[0]),item=at(bot,slot,null,null),before=inventory(bot),identity={name:item.name,label:label(item),lore:lore(item)};await bot.clickWindow(slot,0,1);await bot.waitForTicks(5);const after=inventory(bot),remaining=top(bot)[slot],unchangedInventory=JSON.stringify(before)===JSON.stringify(after),slotStillPresent=remaining&&remaining.name===identity.name&&label(remaining)===identity.label;if(!unchangedInventory||!slotStillPresent)throw new Error('Shift click changed inventory or moved the GUI item');log('SHIFT_CHECK',{player:who,slot,unchangedInventory,slotStillPresent});}
    else throw new Error('Unknown semantic client action');
}).catch(error=>fail('ACTION_FAILED',{line,error:error.message}));});
input.on('close',()=>{commands=commands.then(()=>{if(!allQuit&&ended.size<cohort.length){fail('INPUT_CLOSED',{message:'Controller input ended without normal quit all'});quitAll('controller input closed');}});});
process.on('SIGINT',()=>{fail('INTERRUPTED',{signal:'SIGINT'});quitAll('operator interrupted quest evidence verification');});
process.on('uncaughtException',error=>{fail('UNCAUGHT',{message:error.message});quitAll('client uncaught failure');});
process.on('unhandledRejection',error=>{fail('UNCAUGHT',{message:String(error)});quitAll('client unhandled rejection');});
setTimeout(()=>{fail('TIMEOUT',{message:'Bounded quest evidence boot exceeded thirty minutes'});quitAll('bounded acceptance timeout');input.close();},30*60*1000).unref();
log('CLIENT_PROCESS',{pid:process.pid,cohort,plugin_sha256:expected,script_sha256:sha(__filename),note:'Only original offline players; no fabricated context, native completion command or synthetic objective events.'});
