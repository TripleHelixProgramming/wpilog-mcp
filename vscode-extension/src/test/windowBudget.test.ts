import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import { VisualizationBudgets, decodedBytes } from "../visualization/byteBudget";
const settings = JSON.parse(fs.readFileSync(path.resolve(__dirname, "../../package.json"), "utf8")).contributes.configuration[0].properties;
test("one configured window allowance changes decoded admission while encoded stays independent", () => {
  const B = VisualizationBudgets as any, defaults = new B(), small = new B(16), large = new B(128);
  assert.equal(defaults.decoded.maximum, 64 * 1048576); assert.equal(small.decoded.maximum, 16 * 1048576);
  assert.equal(large.decoded.maximum, 128 * 1048576); assert.equal(large.encoded.maximum, 64 * 1048576);
  const held = small.decoded.reserve(15 * 1048576); assert.throws(() => small.decoded.reserve(2 * 1048576)); held();
  for (const bad of [null, 0, 15, 1025, 16.5, NaN, Infinity, "64"]) assert.throws(() => new B(bad), /budget/i);
});
test("the decoded-window setting declares its integer range, default, scope and reload behavior", () => {
  const setting = settings["wpilog-mcp.visualization.decodedBufferMiB"];
  assert.ok(setting, "window memory must be configurable");
  assert.equal(setting.type, "integer"); assert.equal(setting.default, 64); assert.equal(setting.minimum, 16); assert.equal(setting.maximum, 1024);
  assert.equal(setting.scope, "window"); assert.match(setting.markdownDescription, /reload/i);
});
const loader = require("node:module"), original = loader._load;
let Preview: any;
try { loader._load = (name: string, ...args: any[]) => name === "vscode" ? {} : original(name, ...args); Preview = require("../visualization/recordedPreview").RecordedPreview; }
finally { loader._load = original; }
import { ViewGeneration } from "../visualization/recordedModel";
function recordedHost(mib: number) {
  const preview = Object.create(Preview.prototype), messages: any[] = [], fetches: any[] = [];
  const config = { requirements: [{ id: "r", entry: "/v" }], panels: [{ type: "time_series", series: [{ requirement_id: "r" }] }], time: { recorded: { mode: "full" } } };
  const snapshot = { view_id: "v", revision: 1, source_ref: { kind: "unmanaged" }, configuration: config };
  const page: any = { document: { controller: { snapshot } }, generation: new ViewGeneration(), panel: { visible: true, webview: { postMessage: async (m: any) => {
    messages.push(m); queueMicrotask(() => page.recorded?.acknowledge(m.delivery_id)); return true;
  } } }, cursorGeneration: 0 };
  const start = page.generation.begin("v",1); page.key = start.key;
  preview.budgets = new (VisualizationBudgets as any)(mib);
  preview.host = { client: async () => ({ endpoint: "http://localhost/mcp" }) };
  preview.targetEntries = () => {}; preview.warning = () => {};
  preview.backend = { describe: async () => ({ entries: [], label: "fixture" }), call: async (route: string) => route === "/source/resolve" ? { path: "/fixture" } : { bindings: [{ requirement_id: "r", state: "resolved", entry: "/v" }] } };
  preview.data = { fetch: async (_url: string, request: any) => { fetches.push({ request, encoded: preview.budgets.encoded.reserved, decoded: preview.budgets.decoded.reserved }); return { bytes: new Uint8Array(64) }; } };
  return { preview, page, snapshot, start, messages, fetches };
}
test("recorded downloads reserve shared bytes before I/O and ask the server for a budget-sized reduction", async () => {
  const small = recordedHost(16), large = recordedHost(64);
  for (const f of [small,large]) {
    await f.preview.loadData(f.page,f.snapshot,f.start.signal);
    assert.equal(f.fetches.length,1); assert.ok(f.fetches[0].encoded > 0, "reserve before starting a response");
    assert.ok(f.fetches[0].decoded > 0, "reserve decode and plot copies before starting a response");
    assert.ok(f.preview.budgets.decoded.reserved > 0, "visible data stays charged after ACK");
    assert.equal(f.preview.budgets.encoded.reserved,0);
    assert.ok(f.messages.some(m => m.type === "series" && m.delivery_id));
    f.page.recorded?.dispose(); assert.equal(f.preview.budgets.decoded.reserved,0);
  }
  assert.ok(large.fetches[0].request.maxPoints > small.fetches[0].request.maxPoints, "a larger allowance permits more exact samples before reduction");
});
test("recorded hidden expiry clears through its acknowledgement owner and reveal cancels the timer", async t => {
  t.mock.timers.enable({apis:["setTimeout"]});
  const preview=Object.create(Preview.prototype);let changed=()=>{};let clears=0;
  const document={controller:{snapshot:{view_id:"hidden",configuration:{panels:[]}}}};
  const panel:any={visible:true,webview:{postMessage:async()=>true,onDidReceiveMessage(){}},onDidChangeViewState:(f:()=>void)=>changed=f,onDidDispose(){}};
  preview.controls=async()=>{};preview.pages=new Map();preview.rendered=new Map();preview.targetEntries=()=>{};preview.pageHtml=()=>"";preview.paint=()=>{};preview.warning=()=>{};
  await preview.render(document,panel);const page=preview.pages.get("hidden");page.recorded={clear:async()=>{clears++;}};
  panel.visible=false;changed();t.mock.timers.tick(29999);assert.equal(clears,0);
  panel.visible=true;changed();t.mock.timers.tick(1);assert.equal(clears,0);
  panel.visible=false;changed();t.mock.timers.tick(30000);assert.equal(clears,1);
});
test("recorded raw, statistics and cursor reads reserve bytes and pass their response limit to transport", async () => {
  const f=recordedHost(16); await f.preview.loadData(f.page,f.snapshot,f.start.signal);
  const calls:any[]=[];
  f.preview.backend.call=async(route:string, request:any, _signal:any, options:any)=>{
    assert.ok(f.preview.budgets.encoded.reserved>0); assert.ok(f.preview.budgets.decoded.reserved>0);
    calls.push({route,request,options}); return {value:1};
  };
  await f.preview.loadAnalysis(f.page,"r","raw",0);
  await f.preview.loadAnalysis(f.page,"r","statistics",0);
  f.snapshot.configuration.panels.push({type:"latest",series:[{requirement_id:"r"}]});
  await f.preview.loadValues(f.page);
  assert.deepEqual(calls.map(c=>c.route),["/source/raw","/source/statistics","/source/value"]);
  assert.equal(calls[0].request.limit,64);
  for(const c of calls){assert.equal(c.options.maxResponseBytes,65536);assert.equal(c.options.timeoutMs,30000);}
  assert.equal(f.preview.budgets.encoded.reserved,0); f.page.recorded.dispose(); assert.equal(f.preview.budgets.decoded.reserved,0);
});
for(const type of ["raw","statistics","latest"] as const) test(`repeated ${type} panels reduce admission for every displayed table copy`, async () => {
  const f=recordedHost(16); await f.preview.loadData(f.page,f.snapshot,f.start.signal);
  f.snapshot.configuration.panels=Array.from({length:4},()=>({type,series:[{requirement_id:"r"}]}));
  const calls:any[]=[];
  f.preview.backend.call=async(route:string,request:any,_signal:any,options:any)=>{calls.push({route,request,options});return {value:1};};
  try {
    if(type==="latest")await f.preview.loadValues(f.page);else await f.preview.loadAnalysis(f.page,"r",type,0);
    assert.equal(calls.length,1);assert.equal(calls[0].options.maxResponseBytes,16384,"four DOM copies consume four times the per-sample allowance");
    if(type==="raw")assert.equal(calls[0].request.limit,16);
  } finally {f.page.recorded.dispose();}
});
test("the live host charges repeated renderer copies in addition to its frame transfer", () => {
  const f=recordedHost(16);f.snapshot.configuration.panels=Array.from({length:4},()=>({type:"raw",series:[{requirement_id:"r"}]}));
  f.page.recordingInventory={show:async()=>{},dispose:async()=>{}};f.preview.liveBroker=()=>({});
  const owner=f.preview.liveFor(f.page), frame={prefix:"one",series:{r:[1,2]}};
  try {owner.presentation.offer(frame);assert.equal(f.preview.budgets.decoded.reserved,6*decodedBytes(frame));}
  finally {owner.dispose();}
});
