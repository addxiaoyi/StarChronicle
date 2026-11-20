'use strict';
// Offline protocol contracts only. These stubs never become acceptance evidence.
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{EventEmitter}=require('node:events');
const {attachCraftWire,mapIngredientsMatch,mapCraftConsumed}=require('./quest-evidence-wire.cjs');
function fixture(){
    const client=new EventEmitter(),writes=[],logs=[],window={id:7,slots:Array(46).fill(null)};
    client.write=function(name,packet){writes.push({name,packet});return 'original-return';};
    const original=client.write,bot={_client:client,currentWindow:window};
    const wire=attachCraftWire(bot,'C',(kind,value)=>logs.push({kind,value}),()=>({window:{cursorState:'NULL',slots:[]},inventory:{}}),value=>value);
    return {bot,client,writes,logs,window,wire,original};
}
function syncPacket(window,extra={}){return {windowId:window.id,stateId:3,items:window.slots.slice(),carriedItem:null,...extra};}
function respond(f,packet){f.bot._syncWindow=async window=>{f.client.write('window_click',{windowId:window.id,stateId:-1,slot:-999,mouseButton:2,mode:5,changedSlots:[],cursorItem:null});queueMicrotask(()=>f.client.emit('window_items',packet));};}
test('wire observes actual write once and restores only its own listener/wrapper',()=>{
    const f=fixture();assert.equal(f.client.write('window_click',{slot:5}), 'original-return');
    assert.equal(f.writes.length,1);assert.equal(f.logs.filter(row=>row.kind==='CRAFT_CLIENT_PACKET_WRITE').length,1);
    f.client.emit('set_cursor_item',{contents:{itemCount:1,itemId:2}});f.wire.failure(new Error('observed failure'));
    const failed=f.logs.find(row=>row.kind==='CRAFT_GRID_FAILURE');assert.equal(failed.value.recent.at(-1).packetName,'set_cursor_item');
    f.wire.stop();assert.equal(f.client.write,f.original);assert.equal(f.client.listenerCount('set_cursor_item'),0);
});
test('missing reviewed sync API stops before any write',async()=>{
    const f=fixture();try{await assert.rejects(f.wire.synchronize(f.window,()=>true,'test',20),/unavailable/);assert.equal(f.writes.length,0);}finally{f.wire.stop();}
});
test('one synchronization request confirms actual full packet and never clicks a result',async()=>{
    const f=fixture(),packet=syncPacket(f.window);respond(f,packet);
    try{const snapshot=await f.wire.synchronize(f.window,value=>value.stateId===3,'test',100);
        assert.equal(snapshot.windowId,7);assert.equal(f.writes.length,1);
        assert.deepEqual(f.writes[0].packet,{windowId:7,stateId:-1,slot:-999,mouseButton:2,mode:5,changedSlots:[],cursorItem:null});
        assert.equal(f.writes.filter(row=>row.packet.slot===0).length,0);
        assert.equal(f.logs.filter(row=>row.kind==='CRAFT_SERVER_WINDOW_CONFIRMED').length,1);
    }finally{f.wire.stop();}
});
test('local predicted model cannot substitute for raw server slot data',async()=>{
    const f=fixture(),packet=syncPacket(f.window);f.window.slots[5]={type:123,count:1};respond(f,packet);
    try{await assert.rejects(f.wire.synchronize(f.window,value=>value.slots[5]?.type===123,'compass',10),/No matching actual full-window/);assert.equal(f.writes.length,1);assert.equal(f.logs.filter(row=>row.kind==='CRAFT_SYNC_PENDING_SNAPSHOT').length,1);}finally{f.wire.stop();}
});
test('missing packet times out with one sync and no original click retry',async()=>{
    const f=fixture();f.bot._syncWindow=async()=>{f.client.write('window_click',{slot:-999,mode:5,stateId:-1,mouseButton:2});};
    try{await assert.rejects(f.wire.synchronize(f.window,()=>true,'missing',10),/No matching actual full-window/);assert.equal(f.writes.length,1);assert.equal(f.client.listenerCount('window_items'),1);}finally{f.wire.stop();}
});
test('unrelated window response cannot acknowledge requested window',async()=>{
    const f=fixture();respond(f,syncPacket(f.window,{windowId:8}));
    try{await assert.rejects(f.wire.synchronize(f.window,()=>true,'unrelated',10),/No matching actual full-window/);}finally{f.wire.stop();}
});
test('an older full packet followed by the matching actual packet uses one request',async()=>{
    const f=fixture(),old=syncPacket(f.window,{stateId:26}),actual=syncPacket(f.window,{stateId:28,carriedItem:{type:2,count:1}});
    f.bot._syncWindow=async window=>{f.client.write('window_click',{windowId:window.id,stateId:-1,slot:-999,mouseButton:2,mode:5});queueMicrotask(()=>{f.client.emit('window_items',old);f.client.emit('window_items',actual);});};
    try{const snapshot=await f.wire.synchronize(f.window,value=>value.cursor?.type===2,'pickup',100);
        assert.equal(snapshot.stateId,28);assert.equal(f.writes.length,1);assert.equal(f.logs.filter(row=>row.kind==='CRAFT_SYNC_PENDING_SNAPSHOT').length,1);
        assert.equal(f.logs.filter(row=>row.kind==='CRAFT_SERVER_WINDOW_CONFIRMED').length,1);
    }finally{f.wire.stop();}
});
test('window zero state noise cannot confirm or reject the one crafting-window action',async()=>{
    const f=fixture(),actual=syncPacket(f.window,{stateId:8,carriedItem:{type:2,count:1}});
    f.bot._syncWindow=async window=>{f.client.write('window_click',{windowId:window.id,stateId:-1,slot:-999,mouseButton:2,mode:5});queueMicrotask(()=>{
        f.client.emit('set_slot',{windowId:0,stateId:1000,slot:45,item:null});f.client.emit('window_items',syncPacket(f.window,{windowId:0,stateId:1001}));f.client.emit('window_items',actual);
    });};
    try{const snapshot=await f.wire.synchronize(f.window,value=>value.cursor?.type===2,'noise',100);assert.equal(snapshot.stateId,8);assert.equal(f.writes.length,1);}finally{f.wire.stop();}
});
test('multiple nonmatching real packets time out without resending request or action',async()=>{
    const f=fixture();f.bot._syncWindow=async window=>{f.client.write('window_click',{windowId:window.id,stateId:-1,slot:-999,mouseButton:2,mode:5});queueMicrotask(()=>{
        f.client.emit('window_items',syncPacket(f.window,{stateId:27}));f.client.emit('window_items',syncPacket(f.window,{stateId:28}));
    });};
    try{await assert.rejects(f.wire.synchronize(f.window,value=>value.cursor?.type===2,'no match',10),/lastSnapshot=.*stateId.*28/);assert.equal(f.writes.length,1);assert.equal(f.logs.filter(row=>row.kind==='CRAFT_SYNC_PENDING_SNAPSHOT').length,2);assert.equal(f.logs.filter(row=>row.kind==='CRAFT_SERVER_WINDOW_CONFIRMED').length,0);}finally{f.wire.stop();}
});
test('unsupported raw full packet cursor schema is rejected',async()=>{
    const f=fixture(),packet=syncPacket(f.window);delete packet.carriedItem;respond(f,packet);
    try{await assert.rejects(f.wire.synchronize(f.window,()=>true,'bad schema',100),/unsupported schema/);}finally{f.wire.stop();}
});
test('window replacement during synchronization fails closed',async()=>{
    const f=fixture();respond(f,syncPacket(f.window));f.bot.currentWindow={...f.window};
    try{await assert.rejects(f.wire.synchronize(f.window,()=>true,'replacement',100),/identity changed/);}finally{f.wire.stop();}
});
function ingredients(placed,stage){const slots=Array(46).fill(null),paperSlots=[1,2,3,4,6,7,8,9];for(let i=0;i<placed;i++)slots[paperSlots[i]]={type:1,count:1};if(stage==='inventory')slots[39]={type:2,count:1};if(stage==='grid')slots[5]={type:2,count:1};return {slots,cursor:stage==='cursor'?{type:2,count:1}:placed<8?{type:1,count:8-placed}:null};}
test('each layout requires all eight real paper and the one compass',()=>{
    for(let placed=0;placed<=8;placed++)assert.equal(mapIngredientsMatch(ingredients(placed,'inventory'),1,2,placed,'inventory'),true);
    assert.equal(mapIngredientsMatch(ingredients(8,'cursor'),1,2,8,'cursor'),true);
    assert.equal(mapIngredientsMatch(ingredients(8,'grid'),1,2,8,'grid'),true);
    const changed=ingredients(8,'grid');changed.slots[1].count=2;assert.equal(mapIngredientsMatch(changed,1,2,8,'grid'),false);
    const unknown=ingredients(8,'grid');unknown.cursor=undefined;assert.equal(mapIngredientsMatch(unknown,1,2,8,'grid'),false);
});
test('result confirmation requires consumed ingredients and exactly one created map',()=>{
    const snapshot={slots:Array(46).fill(null),cursor:{type:3,count:1}};assert.equal(mapCraftConsumed(snapshot,1,2,3),true);
    snapshot.cursor.count=2;assert.equal(mapCraftConsumed(snapshot,1,2,3),false);snapshot.cursor.count=1;
    snapshot.slots[1]={type:1,count:1};assert.equal(mapCraftConsumed(snapshot,1,2,3),false);snapshot.slots[1]=null;
    snapshot.slots[0]={type:3,count:1};assert.equal(mapCraftConsumed(snapshot,1,2,3),false);
});
test('map client source contains one result click guarded by raw server confirmation',()=>{
    const source=fs.readFileSync(path.join(__dirname,'quest-evidence-clients.cjs'),'utf8');
    const start=source.indexOf('async function craftMapByGridOnce('),end=source.indexOf('\nfunction quest(',start),body=source.slice(start,end);
    assert.equal((body.match(/await bot\.clickWindow\(0,0,0\)/g)||[]).length,1);
    assert.ok(body.indexOf("'compass placement and actual map result'")<body.indexOf('await bot.clickWindow(0,0,0)'));
    assert.ok(body.indexOf("'only result click'")>body.indexOf('await bot.clickWindow(0,0,0)'));
    assert.equal((body.match(/await bot\.clickWindow\(5,1,0\)/g)||[]).length,1);
    assert.ok(!body.includes('retry'));
});
