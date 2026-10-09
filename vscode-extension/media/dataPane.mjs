import perspective from "./vendor/perspective/perspective.js";
import viewer from "./vendor/perspective/perspective-viewer.js";
import * as wasm from "./vendor/perspective/wasm/perspective-viewer.js";
import "./vendor/perspective/perspective-viewer-datagrid.js";
import "./vendor/perspective/perspective-viewer-charts.js";

// VS Code requires a blob worker. Its only source is this bundled file; it has no network API.
let engine;
function worker() {
  return engine ??= (async () => {
    await viewer.init_client(fetch(new URL("./vendor/perspective/perspective-viewer.wasm", import.meta.url)), wasm);
    perspective.init_server(fetch(new URL("./vendor/perspective/perspective-server.wasm", import.meta.url)));
    const source = await fetch(new URL("./vendor/perspective/perspective-server.worker.js", import.meta.url));
    if (!source.ok) throw new Error("Could not load the bundled data-view worker");
    const url = URL.createObjectURL(new Blob([await source.text()], { type: "application/javascript" }));
    const thread = new Worker(url, { type: "module", name: "wpilog-data" });
    try { return await perspective.worker(Promise.resolve(thread)); } finally { URL.revokeObjectURL(url); }
  })();
}
export class DataPane {
  constructor(host, section) {
    this.host = host; this.section = section; this.viewer = section.querySelector("perspective-viewer");
    this.note = section.querySelector(".data-status"); this.names = []; this.next = 1e9; this.requests = new Map();
    this.chain = Promise.resolve(); this.adapter = null; this.inputs = {}; this.generation = 0;
  }
  async show(names, start, end, append = false) {
    const generation = ++this.generation;
    this.section.classList.remove("hidden"); this.section.open = true;
    const selected = [...new Set(names)];
    this.names = selected; this.start = start; this.end = end;
    this.note.textContent = "Loading exact samples…";
    this.chain = this.chain.catch(() => {}).then(async () => {
      if (!append || !this.adapter) {
        if (this.adapter) await this.adapter.dispose();
        this.adapter = new DataViewAdapter.Adapter(await worker(), this.viewer); this.inputs = {};
      }
      for (const name of selected) {
        const requestId = this.next++;
        const stream = await new Promise((resolve, reject) => {
          this.requests.set(requestId, { resolve, reject });
          this.host.post({ type: "fetch", requestId, name, startTime: append ? this.adapter.ends.get(name)?.time ?? start : start, endTime: end });
        });
        this.inputs[name] = ArrowStream.parsedMetadata(stream).inputs;
        await this.adapter.accept(stream, append);
      }
      await this.viewer.flush();
      const rows = await this.adapter.table.size();
      this.note.textContent = `${rows.toLocaleString("en-US")} recorded samples`;
      this.host.post({ type: "dataViewState", rows, inputs: this.inputs, generation });
      return rows;
    });
    return this.chain.catch(error => {
      this.note.textContent = error.message;
      this.host.post({ type: "dataViewState", error: error.message, generation });
      throw error;
    });
  }
  receive(message) {
    const request = this.requests.get(message.requestId); if (!request) return false;
    this.requests.delete(message.requestId);
    if (message.type === "dataError") request.reject(new Error(message.message));
    else { try { request.resolve(ArrowStream.read(message.bytes)); } catch (error) { request.reject(error); } }
    return true;
  }
}
// The page's ordinary script awaits this module, so readiness never races a data-view request.
window.DataPane = DataPane;
window.dispatchEvent(new Event("data-pane-ready"));
