import { test } from "node:test";
import * as assert from "node:assert/strict";
import { VisualizationBudgets } from "../visualization/byteBudget";
import { RecordedBuffers, recordedPlan } from "../visualization/recordedBuffers";
import { displayCopies, displayMultiplier } from "../visualization/recordedModel";
const tick = () => new Promise<void>(r => setImmediate(r));
function fixture(timeout = 5000) {
  const budgets = new VisualizationBudgets(16), messages: any[] = [];
  const owner = new RecordedBuffers(budgets, m => { messages.push(m); return true; },timeout);
  const load = (slot = "value:x", result: unknown = { value: "exact" }, signal = new AbortController().signal, current = () => true) => owner.load(slot,"g",signal,() => recordedPlan(budgets),async () => ({ type: "value",result }),current);
  const ack = () => owner.acknowledge(messages.at(-1).delivery_id);
  return { budgets,messages,owner,load,ack };
}
test("recorded display and its replacement stay charged until their own acknowledgements",async () => {
  const f=fixture(); f.owner.acknowledge(undefined as any);
  const one=f.load(); await tick(); const first=f.budgets.decoded.reserved; assert.ok(first>0); assert.ok(f.budgets.encoded.reserved>0);
  f.owner.acknowledge("foreign"); assert.equal(f.budgets.encoded.reserved,2*65536); f.ack(); await one;
  assert.equal(f.budgets.encoded.reserved,0); assert.equal(f.budgets.decoded.reserved,first);
  const two=f.load();await tick();assert.equal(f.budgets.decoded.reserved,2*first);f.ack();await two;assert.equal(f.budgets.decoded.reserved,first);
  const clear=f.owner.clear("next");await tick();assert.equal(f.budgets.decoded.reserved,first);f.ack();await clear;assert.equal(f.budgets.decoded.reserved,0);f.owner.dispose();
});
test("shared recorded and live reservations reduce admission before either caller downloads",async () => {
  const f=fixture();const peer=f.budgets.decoded.reserve(16*1048576-65536);
  let downloads=0;await assert.rejects(f.owner.load("x","g",new AbortController().signal,() => recordedPlan(f.budgets),async () => {downloads++;return {};},() => true),/budget/);
  assert.equal(downloads,0);assert.equal(f.budgets.decoded.reserved,16*1048576-65536);peer();f.owner.dispose();
});
test("queued recorded requests admit one response at a time and close releases every owned slot",async () => {
  const f=fixture();const a=f.load("a"),b=f.load("b");await tick();assert.equal(f.messages.length,1);f.ack();await a;await tick();assert.equal(f.messages.length,2);f.ack();await b;
  assert.ok(f.budgets.decoded.reserved>0);f.owner.dispose();assert.equal(f.budgets.decoded.reserved,0);assert.equal(f.budgets.encoded.reserved,0);
});
test("a stalled recorded renderer stops admission but keeps its unacknowledged bytes charged",async () => {
  const f=fixture(10);await assert.rejects(f.load(),/acknowledging/);const held=f.budgets.decoded.reserved;assert.ok(held>0);
  await assert.rejects(f.load(),/stopped/);assert.equal(f.budgets.decoded.reserved,held);f.owner.dispose();assert.equal(f.budgets.decoded.reserved,0);assert.equal(f.budgets.encoded.reserved,0);
});
test("failed or obsolete recorded reads release bytes without publishing",async () => {
  for(const mode of ["failure","stale","oversized"]){
    const f=fixture();let resolve!:(value:any)=>void;
    const op=f.owner.load("x","g",new AbortController().signal,()=>recordedPlan(f.budgets),()=>new Promise(r=>resolve=r),()=>true);
    const outcome=op.catch(e=>e);await tick();if(mode==="stale")f.owner.dispose();
    resolve(mode==="oversized"?{bytes:new Uint8Array(100000)}:mode==="failure"?{result:{ value: Array(70).fill(0).reduce(x=>[x],null as any) }}:{result:null});
    await tick(); if(f.messages.length)f.ack();
    const result=await outcome;if(mode!=="stale")assert.ok(result instanceof Error);
    assert.equal(f.messages.length,0);assert.equal(f.budgets.decoded.reserved,0);assert.equal(f.budgets.encoded.reserved,0);f.owner.dispose();
  }
});
test("recorded reduction sizing accounts all plot copies and cannot oversubscribe encoded peers",()=>{
  const b=new VisualizationBudgets(64);const one=recordedPlan(b),four=recordedPlan(b,1,4),many=recordedPlan(b,4);
  assert.equal(one.responseBytes,262144);assert.equal(one.maxPoints,4064);assert.equal(four.maxPoints,992);assert.equal(many.maxPoints,992);
  const peer=b.encoded.reserve(64*1048576-65536);assert.equal(recordedPlan(b).responseBytes,32768);peer();
  assert.equal(new VisualizationBudgets(1024).encoded.maximum,b.encoded.maximum);
});
test("copy counts include joined plot columns and repeated table rows across and within panels",()=>{
  const panel=(type:string,ids:string[])=>({type,series:ids.map(requirement_id=>({requirement_id}))});
  const config:any={requirements:[{id:"r"},{id:"s"}],panels:[panel("time_series",["r","s","r"]),panel("time_series",["r","s"]),panel("time_series",["s"]),panel("latest",["r","r","s"]),panel("latest",["r"])]};
  assert.equal(displayCopies(config,"r","time_series"),5);assert.equal(displayCopies(config,"s","time_series"),6);
  assert.equal(displayCopies(config,"r","latest"),3);assert.equal(displayCopies(config,"s","latest"),1);assert.equal(displayMultiplier(config),6);
});
test("recorded admission bounds queued work without retaining pending decoded payloads", async () => {
  const f=fixture(); const pending=Array.from({length:64},(_,n)=>f.load(String(n)).catch(e=>e));
  await tick(); assert.equal(f.messages.length,1);
  let refused:Error|undefined; const overflow=f.load("overflow").catch(e=>{refused=e;}); await tick();
  try { assert.match(refused?.message??"",/pending/); }
  finally { f.owner.dispose(); await Promise.all([...pending,overflow]); }
  assert.equal(f.budgets.decoded.reserved,0); assert.equal(f.budgets.encoded.reserved,0);
});
test("abort and query replacement discard an in-flight recorded response before delivery", async () => {
  for(const aborting of [true,false]) {
    const f=fixture(), abort=new AbortController(); let current=true, resolve!:(value:any)=>void;
    const op=f.owner.load("x","g",abort.signal,()=>recordedPlan(f.budgets),()=>new Promise(r=>resolve=r),()=>current);
    await tick(); if(aborting)abort.abort(); else current=false;
    resolve({result:{value:1}}); await tick(); if(f.messages.length)f.ack(); await op;
    assert.equal(f.messages.length,0); assert.equal(f.budgets.decoded.reserved,0); assert.equal(f.budgets.encoded.reserved,0); f.owner.dispose();
  }
});
test("a refused renderer send retains the outstanding reservation until disposal", async () => {
  for(const send of [()=>false,()=>{throw new Error("renderer closed");},()=>Promise.reject(new Error("renderer closed"))]) {
    const budgets=new VisualizationBudgets(16), owner=new RecordedBuffers(budgets,send);
    await assert.rejects(owner.load("x","g",new AbortController().signal,()=>recordedPlan(budgets),async()=>({result:1}),()=>true));
    assert.ok(budgets.decoded.reserved>0); assert.ok(budgets.encoded.reserved>0);
    await assert.rejects(owner.clear("next"),/stopped/); owner.dispose();
    assert.equal(budgets.decoded.reserved,0); assert.equal(budgets.encoded.reserved,0);
  }
});
test("decoded JSON expansion beyond its reserved allowance refuses before renderer delivery", async () => {
  const f=fixture(); const limit={...recordedPlan(f.budgets),decodedBytes:17000};
  const op=f.owner.load("x","g",new AbortController().signal,()=>limit,async()=>({result:Array(32).fill(0)}),()=>true).catch(e=>e);
  await tick(); if(f.messages.length)f.ack(); assert.match(String(await op),/decoded byte allowance/);
  assert.equal(f.messages.length,0); assert.equal(f.budgets.decoded.reserved,0); assert.equal(f.budgets.encoded.reserved,0); f.owner.dispose();
});
