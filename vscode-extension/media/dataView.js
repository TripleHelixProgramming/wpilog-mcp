// Shared by the webview and Node: convert the server's Arrow batches to Perspective columns.
// No resampling: a row is one recorded sample, with nested struct fields flattened by path.
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.DataViewAdapter = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";
  function columns(field, path, value, schema, row) {
    if (field.type === "struct") {
      for (const child of field.children) columns(child, path + "." + child.name, value?.[child.name], schema, row);
    } else {
      schema[path] = field.type === "bool" ? "boolean" : /^(float|int|uint)/.test(field.type) ? "float" : "string";
      row[path] = value == null ? null : field.type === "list" || field.type === "binary" ? JSON.stringify(Array.from(value)) : value;
    }
  }
  function rowsOf(stream) {
    if (stream.metadata.bucketed === "true") throw new Error("The data view requires exact samples; narrow the window.");
    const rows = [], schema = { entry: "string", timestamp_sec: "float" };
    const scale = { "timestamp[s]": 1, "timestamp[ms]": 1e-3, "timestamp[us]": 1e-6, "timestamp[ns]": 1e-9 }[stream.fields[0].type] ?? 1e-6;
    for (const batch of stream.batches) {
      if (batch.metadata.file_changed) throw new Error("The file changed while it was read; reload the data view.");
      for (let i = 0; i < batch.length; i++) {
        const row = { entry: batch.metadata.entry, timestamp_sec: batch.columns[0].values[i] * scale };
        for (let c = 1; c < batch.columns.length; c++) {
          const col = batch.columns[c];
          let value = col.valid && !col.valid[i] ? null : col.values[i];
          if (col.type === "bool" && value !== null) value = Boolean(value);
          columns(stream.fields[c], row.entry + (col.name === "value" ? "" : "." + col.name), value, schema, row);
        }
        rows.push(row);
      }
    }
    return { rows, schema };
  }
  class Adapter {
    constructor(worker, viewer) { this.worker = worker; this.viewer = viewer; this.table = null; this.schema = {}; this.ends = new Map(); }
    async accept(stream, append) {
      const batch = rowsOf(stream);
      // Inclusive windows repeat their first timestamp. Keep distinct records at that timestamp.
      const skipped = new Map();
      const rows = append ? batch.rows.filter(row => {
        const end = this.ends.get(row.entry); if (!end) return true;
        if (row.timestamp_sec < end.time) return false;
        if (row.timestamp_sec > end.time) return true;
        const n = (skipped.get(row.entry) ?? 0) + 1; skipped.set(row.entry, n); return n > end.count;
      }) : batch.rows;
      const changed = Object.keys(batch.schema).some(key => !(key in this.schema));
      if (!this.table || changed) {
        let held = [], config;
        if (this.table) {
          const view = await this.table.view(); try { held = await view.to_json(); } finally { await view.delete(); }
          config = await this.viewer.save();
          // A new selected entry adds visible columns without dropping the user's filters/sort.
          if (Array.isArray(config.columns)) config.columns.push(...Object.keys(batch.schema).filter(key => !(key in this.schema)));
          await this.viewer.delete(); await this.table.delete();
        }
        this.schema = { ...this.schema, ...batch.schema };
        this.table = await this.worker.table(this.schema);
        if (held.length) await this.table.update(held);
        await this.viewer.load(this.table);
        if (config) await this.viewer.restore(config);
      }
      if (rows.length) await this.table.update(rows);
      for (const row of rows) {
        const last = this.ends.get(row.entry);
        this.ends.set(row.entry, { time: row.timestamp_sec, count: last?.time === row.timestamp_sec ? last.count + 1 : 1 });
      }
      return this.table.size();
    }
    async dispose() { if (this.table) { await this.viewer.delete(); await this.table.delete(); } }
  }
  return { rowsOf, Adapter };
});
