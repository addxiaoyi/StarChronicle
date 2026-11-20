'use strict';
// Packet diagnostics plus the reviewed library's explicit inventory synchronization
// request. Creates no clients, never repeats an inventory action, and never treats
// the local predicted inventory model as server acknowledgement.
function attachCraftWire(bot,player,log,view,decodeItem){
    let receiveSequence=0,writeSequence=0,stopped=false;
    const recent=[],events=['window_items','set_slot','set_cursor_item','set_player_inventory'];
    const capture=(kind,value)=>{const entry={player,...value};recent.push({kind,...entry});if(recent.length>32)recent.shift();log(kind,entry);};
    const listeners=new Map(events.map(packetName=>[packetName,packet=>{
        if(stopped)return;
        capture('CRAFT_SERVER_PACKET',{receiveSequence:++receiveSequence,packetName,packet,modelAfterPacket:view(bot),note:'Actual received protocol packet; model may also contain local predictions.'});
    }]));
    for(const [event,listener] of listeners)bot._client.on(event,listener);
    const originalWrite=bot._client.write;
    const observedWrite=function(packetName,packet,...rest){
        if(packetName==='window_click'&&!stopped)capture('CRAFT_CLIENT_PACKET_WRITE',{writeSequence:++writeSequence,packetName,packet,modelBeforeWrite:view(bot),note:'Actual client.write invocation observed once; not a server acknowledgement.'});
        return originalWrite.call(this,packetName,packet,...rest);
    };
    bot._client.write=observedWrite;
    return {
        async synchronize(window,predicate,description,timeoutMs=15000){
            if(typeof bot._syncWindow!=='function'||typeof decodeItem!=='function')throw new Error('Reviewed inventory synchronization API/slot decoder is unavailable');
            let timer,listener,lastSnapshot=null;
            const response=new Promise((resolve,reject)=>{
                listener=packet=>{if(packet.windowId!==window.id)return;try{
                    if(!Array.isArray(packet.items)||packet.items.length!==window.slots.length||!Object.hasOwn(packet,'carriedItem')||!Number.isInteger(packet.stateId))throw new Error('Actual full-window packet has an unsupported schema');
                    const snapshot={windowId:packet.windowId,stateId:packet.stateId,slots:packet.items.map(decodeItem),cursor:decodeItem(packet.carriedItem),receiveSequence};
                    lastSnapshot=snapshot;
                    // A prior request's full-window response can still be in flight.
                    // Observe subsequent real responses to this ONE synchronization;
                    // never resend the request or the inventory action.
                    if(!predicate(snapshot)){log('CRAFT_SYNC_PENDING_SNAPSHOT',{player,description,snapshot,note:'First/older actual full window does not yet satisfy this action. Passive observation continues; nothing is resent.'});return;}
                    resolve(snapshot);
                }catch(error){reject(error);}};
                bot._client.on('window_items',listener);
            });
            const deadline=new Promise((resolve,reject)=>{timer=setTimeout(()=>reject(new Error('No matching actual full-window synchronization response for '+description+'; lastSnapshot='+JSON.stringify(lastSnapshot))),timeoutMs);});
            try{
                // This existing library API sends exactly one end-of-absent-drag
                // synchronization request. It does not resend the inventory action.
                log('CRAFT_SYNC_REQUEST',{player,description,windowId:window.id,receiveSequence,writeSequence});
                const [snapshot]=await Promise.race([Promise.all([response,bot._syncWindow(window)]),deadline]);
                if(bot.currentWindow!==window)throw new Error('Window identity changed during actual server synchronization');
                log('CRAFT_SERVER_WINDOW_CONFIRMED',{player,description,snapshot,note:'Raw received full-window packet confirmed; no craft or inventory action was repeated.'});
                return snapshot;
            }finally{clearTimeout(timer);bot._client.removeListener('window_items',listener);}
        },
        record(stage,detail={}){log('CRAFT_GRID_OBSERVATION',{player,stage,receiveSequence,writeSequence,...detail,...view(bot)});},
        failure(error){log('CRAFT_GRID_FAILURE',{player,error:String(error?.message||error),receiveSequence,writeSequence,recent:[...recent],...view(bot),note:'No placement or result click will be retried.'});},
        stop(){if(stopped)return;stopped=true;for(const [event,listener] of listeners)bot._client.removeListener(event,listener);if(bot._client.write===observedWrite)bot._client.write=originalWrite;},
        counters(){return {receiveSequence,writeSequence};}
    };
}
function itemIs(item,type,count){return item!==null&&item?.type===type&&item.count===count;}
function mapIngredientsMatch(snapshot,paper,compass,placedPaper,compassStage){
    const paperSlots=[1,2,3,4,6,7,8,9];
    if(!paperSlots.every((slot,index)=>index<placedPaper?itemIs(snapshot.slots[slot],paper,1):snapshot.slots[slot]===null))return false;
    if(compassStage==='grid'?!itemIs(snapshot.slots[5],compass,1):snapshot.slots[5]!==null)return false;
    const expectedCursor=compassStage==='cursor'?{type:compass,count:1}:placedPaper<8?{type:paper,count:8-placedPaper}:null;
    if(expectedCursor?!itemIs(snapshot.cursor,expectedCursor.type,expectedCursor.count):snapshot.cursor!==null)return false;
    const count=type=>snapshot.slots.slice(1).reduce((total,item)=>total+(item?.type===type?item.count:0),0)+(snapshot.cursor?.type===type?snapshot.cursor.count:0);
    return count(paper)===8&&count(compass)===1;
}
function mapCraftConsumed(snapshot,paper,compass,map){
    const items=snapshot.slots.slice(1).concat([snapshot.cursor]);
    const count=type=>items.reduce((total,item)=>total+(item?.type===type?item.count:0),0);
    return snapshot.slots[0]===null&&snapshot.slots.slice(1,10).every(item=>item===null)&&count(paper)===0&&count(compass)===0&&count(map)===1;
}
module.exports={attachCraftWire,itemIs,mapIngredientsMatch,mapCraftConsumed};
