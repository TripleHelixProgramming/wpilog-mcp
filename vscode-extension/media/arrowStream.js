// A reader of the Apache Arrow IPC streaming format for the streams the wpilog-mcp server
// writes (EXPLORER_PLAN.md §6): the subset the data endpoint produces, read into typed arrays
// the plot draws from without a copy. It parses the Flatbuffers metadata by hand from the format's
// definitions (Message, Schema, Field, RecordBatch), so the webview bundles no Arrow library.
// Anything outside the subset (dictionaries, compression, nested unions) is refused with a clear
// error rather than misread.
//
// The same file runs in the webview and under Node's test runner (see arrowStream.test.ts),
// where it is checked against the streams the server's own tests leave behind and pyarrow has
// read. No DOM, no imports.
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.ArrowStream = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const CONTINUATION = 0xffffffff;
  const utf8 = new TextDecoder("utf-8");

  // ---- Flatbuffers, as the format lays them out ----

  /** A table at `pos` in a buffer: reads its fields by vtable index. */
  class Table {
    constructor(view, pos) {
      this.view = view;
      this.pos = pos;
      this.vtable = pos - view.getInt32(pos, true);
      this.vtableSize = view.getUint16(this.vtable, true);
    }

    /** The field's offset from the table, or 0 when the table does not have it. */
    fieldOffset(index) {
      const slot = 4 + 2 * index;
      return slot < this.vtableSize ? this.view.getUint16(this.vtable + slot, true) : 0;
    }

    int8(index, fallback) {
      const o = this.fieldOffset(index);
      return o ? this.view.getInt8(this.pos + o) : fallback;
    }

    int16(index, fallback) {
      const o = this.fieldOffset(index);
      return o ? this.view.getInt16(this.pos + o, true) : fallback;
    }

    int32(index, fallback) {
      const o = this.fieldOffset(index);
      return o ? this.view.getInt32(this.pos + o, true) : fallback;
    }

    int64(index, fallback) {
      const o = this.fieldOffset(index);
      return o ? Number(this.view.getBigInt64(this.pos + o, true)) : fallback;
    }

    bool(index, fallback) {
      const o = this.fieldOffset(index);
      return o ? this.view.getUint8(this.pos + o) !== 0 : fallback;
    }

    /** The position a field's offset points at (a string, a vector, a table), or 0. */
    indirect(index) {
      const o = this.fieldOffset(index);
      if (!o) return 0;
      const at = this.pos + o;
      return at + this.view.getUint32(at, true);
    }

    string(index) {
      const at = this.indirect(index);
      if (!at) return null;
      const length = this.view.getUint32(at, true);
      return utf8.decode(new Uint8Array(this.view.buffer, this.view.byteOffset + at + 4, length));
    }

    table(index) {
      const at = this.indirect(index);
      return at ? new Table(this.view, at) : null;
    }

    /** A vector of tables: each element an offset to its table. */
    tables(index) {
      const at = this.indirect(index);
      if (!at) return [];
      const length = this.view.getUint32(at, true);
      const out = [];
      for (let i = 0; i < length; i++) {
        const element = at + 4 + 4 * i;
        out.push(new Table(this.view, element + this.view.getUint32(element, true)));
      }
      return out;
    }

    /** A vector of structs of `size` bytes: the positions of its elements. */
    structs(index, size) {
      const at = this.indirect(index);
      if (!at) return [];
      const length = this.view.getUint32(at, true);
      const out = [];
      for (let i = 0; i < length; i++) out.push(at + 4 + size * i);
      return out;
    }
  }

  function keyValues(tables) {
    const out = {};
    for (const kv of tables) out[kv.string(0)] = kv.string(1);
    return out;
  }

  // Type union tags, from Schema.fbs
  const TYPE = { Int: 2, FloatingPoint: 3, Binary: 4, Utf8: 5, Bool: 6, Timestamp: 10, List: 12, Struct: 13 };
  const HEADER = { Schema: 1, DictionaryBatch: 2, RecordBatch: 3 };

  function readField(f) {
    const typeType = f.int8(2, 0);
    const typeTable = f.table(3);
    let type;
    switch (typeType) {
      case TYPE.Int: {
        const bits = typeTable ? typeTable.int32(0, 0) : 0;
        const signed = typeTable ? typeTable.bool(1, false) : false;
        type = (signed ? "int" : "uint") + bits;
        break;
      }
      case TYPE.FloatingPoint: {
        const precision = typeTable ? typeTable.int16(0, 0) : 0;
        type = precision === 2 ? "float64" : precision === 1 ? "float32" : "float16";
        break;
      }
      case TYPE.Binary: type = "binary"; break;
      case TYPE.Utf8: type = "utf8"; break;
      case TYPE.Bool: type = "bool"; break;
      case TYPE.Timestamp: {
        const unit = typeTable ? typeTable.int16(0, 0) : 0;
        type = "timestamp[" + ["s", "ms", "us", "ns"][unit] + "]";
        break;
      }
      case TYPE.List: type = "list"; break;
      case TYPE.Struct: type = "struct"; break;
      default: type = "type" + typeType;
    }
    if (f.indirect(4)) throw new Error("dictionary-encoded field " + f.string(0) + " is not supported");
    return {
      name: f.string(0),
      nullable: f.bool(1, false),
      type,
      children: f.tables(5).map(readField),
      metadata: keyValues(f.tables(6)),
    };
  }

  // ---- the arrays ----

  function bitmap(bytes, length) {
    if (bytes.length === 0) return null;
    const valid = new Uint8Array(length);
    for (let i = 0; i < length; i++) valid[i] = (bytes[i >>> 3] >>> (i & 7)) & 1;
    return valid;
  }

  /** Decodes one field's array from the body, consuming its nodes and buffers in order. */
  function decode(field, nodes, buffers, body, cursor) {
    const node = nodes[cursor.node++];
    const n = node.length;
    const take = () => {
      const b = buffers[cursor.buffer++];
      if (b.offset % 8 !== 0) throw new Error("buffer not aligned at " + b.offset);
      return new Uint8Array(body.buffer, body.byteOffset + b.offset, b.length);
    };
    const validityBytes = take();
    const valid = node.nullCount > 0 ? bitmap(validityBytes, n) : null;
    if (node.nullCount > 0 && !valid) throw new Error("null count " + node.nullCount + " with no validity bitmap");
    const column = { name: field.name, type: field.type, length: n, nullCount: node.nullCount, valid };
    switch (field.type) {
      case "float64": {
        const data = take();
        if (data.length < 8 * n) throw new Error("float64 data too short");
        // A copy into an aligned array: the body's offset within the message need not be 8-aligned
        const values = new Float64Array(n);
        const dv = new DataView(data.buffer, data.byteOffset, data.length);
        for (let i = 0; i < n; i++) values[i] = dv.getFloat64(8 * i, true);
        if (valid) for (let i = 0; i < n; i++) if (!valid[i]) values[i] = NaN;
        column.values = values;
        break;
      }
      case "int64":
      case "timestamp[us]":
      case "timestamp[ms]":
      case "timestamp[s]":
      case "timestamp[ns]": {
        const data = take();
        if (data.length < 8 * n) throw new Error("int64 data too short");
        const values = new Float64Array(n);
        const dv = new DataView(data.buffer, data.byteOffset, data.length);
        for (let i = 0; i < n; i++) values[i] = Number(dv.getBigInt64(8 * i, true));
        if (valid) for (let i = 0; i < n; i++) if (!valid[i]) values[i] = NaN;
        column.values = values;
        break;
      }
      case "bool": {
        const data = take();
        if (data.length < (n + 7) >>> 3) throw new Error("bool data too short");
        const values = new Uint8Array(n);
        for (let i = 0; i < n; i++) values[i] = (data[i >>> 3] >>> (i & 7)) & 1;
        column.values = values;
        break;
      }
      case "utf8":
      case "binary": {
        const offsets = take();
        const data = take();
        const ov = new DataView(offsets.buffer, offsets.byteOffset, offsets.length);
        if (offsets.length < 4 * (n + 1)) throw new Error("offsets too short");
        const values = new Array(n);
        for (let i = 0; i < n; i++) {
          if (valid && !valid[i]) { values[i] = null; continue; }
          const start = ov.getInt32(4 * i, true);
          const end = ov.getInt32(4 * (i + 1), true);
          const slice = data.subarray(start, end);
          values[i] = field.type === "utf8" ? utf8.decode(slice) : slice.slice();
        }
        column.values = values;
        break;
      }
      case "struct": {
        const children = field.children.map((c) => decode(c, nodes, buffers, body, cursor));
        const values = new Array(n);
        for (let i = 0; i < n; i++) {
          if (valid && !valid[i]) { values[i] = null; continue; }
          const o = {};
          for (const child of children) o[child.name] = at(child, i);
          values[i] = o;
        }
        column.values = values;
        column.children = children;
        break;
      }
      case "list": {
        const offsets = take();
        const ov = new DataView(offsets.buffer, offsets.byteOffset, offsets.length);
        if (offsets.length < 4 * (n + 1)) throw new Error("list offsets too short");
        const child = decode(field.children[0], nodes, buffers, body, cursor);
        const values = new Array(n);
        for (let i = 0; i < n; i++) {
          if (valid && !valid[i]) { values[i] = null; continue; }
          const start = ov.getInt32(4 * i, true);
          const end = ov.getInt32(4 * (i + 1), true);
          const items = new Array(end - start);
          for (let k = start; k < end; k++) items[k - start] = at(child, k);
          values[i] = items;
        }
        column.values = values;
        column.children = [child];
        break;
      }
      default:
        throw new Error("column " + field.name + " has type " + field.type + ", which this reader does not take");
    }
    return column;
  }

  /** One value of a column, null where the column is null there. */
  function at(column, i) {
    if (column.valid && !column.valid[i]) return null;
    const v = column.values[i];
    return column.type === "bool" ? v === 1 : v;
  }

  // ---- the stream ----

  /**
   * Reads a whole stream. Returns {fields, metadata, batches, complete}: `batches` each with
   * `metadata` (the message's custom metadata: the entry, or file_changed), `length`, and
   * `columns` by field, where a float64, int64, or timestamp column has `values` as a
   * Float64Array (nulls as NaN, with `valid` saying which), a bool column a Uint8Array, and the
   * rest plain arrays. `complete` is whether the end-of-stream marker was seen.
   */
  function read(input) {
    const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    let pos = 0;
    let fields = null;
    let metadata = {};
    const batches = [];
    let complete = false;
    while (pos + 8 <= bytes.byteLength) {
      const marker = view.getUint32(pos, true);
      if (marker !== CONTINUATION) throw new Error("no continuation marker at " + pos);
      const metadataLength = view.getInt32(pos + 4, true);
      pos += 8;
      if (metadataLength === 0) { complete = true; break; }
      const message = new Table(new DataView(bytes.buffer, bytes.byteOffset + pos, metadataLength),
        view.getUint32(pos, true));
      const headerType = message.int8(1, 0);
      const bodyLength = message.int64(3, 0);
      const header = message.table(2);
      const bodyStart = pos + metadataLength;
      if (bodyStart + bodyLength > bytes.byteLength) throw new Error("the stream ends inside a message body");
      const body = bytes.subarray(bodyStart, bodyStart + bodyLength);
      if (headerType === HEADER.Schema) {
        fields = header.tables(1).map(readField);
        metadata = keyValues(header.tables(2));
      } else if (headerType === HEADER.RecordBatch) {
        if (!fields) throw new Error("a record batch before the schema");
        if (header.indirect(3)) throw new Error("compressed batches are not supported");
        const nodes = header.structs(1, 16).map((p) => ({
          length: Number(header.view.getBigInt64(p, true)),
          nullCount: Number(header.view.getBigInt64(p + 8, true)),
        }));
        const buffers = header.structs(2, 16).map((p) => ({
          offset: Number(header.view.getBigInt64(p, true)),
          length: Number(header.view.getBigInt64(p + 8, true)),
        }));
        const cursor = { node: 0, buffer: 0 };
        const columns = fields.map((f) => decode(f, nodes, buffers, body, cursor));
        batches.push({ metadata: keyValues(message.tables(4)), length: header.int64(0, 0), columns });
      } else if (headerType === HEADER.DictionaryBatch) {
        throw new Error("dictionary batches are not supported");
      } else {
        throw new Error("unexpected message type " + headerType);
      }
      pos = bodyStart + bodyLength;
    }
    if (!fields) throw new Error("no schema in the stream");
    return { fields, metadata, batches, complete };
  }

  /**
   * The batches of one entry, concatenated: {timestamps (seconds, Float64Array), columns by
   * name (Float64Array for numbers, with nulls as NaN), count}. The timestamp column is in the
   * log's microseconds; here it becomes seconds, the unit every tool reports.
   */
  function entrySeries(stream, entry) {
    const parts = stream.batches.filter((b) => b.metadata.entry === entry && b.length > 0);
    const total = parts.reduce((n, b) => n + b.length, 0);
    const out = { count: total, timestamps: new Float64Array(total), columns: {} };
    const numeric = stream.fields.filter((f) => f.name !== "timestamp"
      && (f.type === "float64" || f.type.startsWith("int") || f.type === "bool"));
    for (const f of numeric) out.columns[f.name] = new Float64Array(total);
    const others = stream.fields.filter((f) => f.name !== "timestamp" && !numeric.includes(f));
    for (const f of others) out.columns[f.name] = new Array(total);
    const scale = stream.fields[0].type === "timestamp[ms]" ? 1e-3
      : stream.fields[0].type === "timestamp[ns]" ? 1e-9 : stream.fields[0].type === "timestamp[s]" ? 1 : 1e-6;
    let offset = 0;
    for (const b of parts) {
      const ts = b.columns[0].values;
      for (let i = 0; i < b.length; i++) out.timestamps[offset + i] = ts[i] * scale;
      for (let c = 1; c < b.columns.length; c++) {
        const column = b.columns[c];
        const target = out.columns[column.name];
        if (column.type === "bool") {
          for (let i = 0; i < b.length; i++) target[offset + i] = column.valid && !column.valid[i] ? NaN : column.values[i];
        } else if (target instanceof Float64Array) {
          target.set(column.values, offset);
        } else {
          for (let i = 0; i < b.length; i++) target[offset + i] = at(column, i);
        }
      }
      offset += b.length;
    }
    return out;
  }

  /** The metadata JSON the server puts in the schema, parsed; a field that is not JSON is kept as text. */
  function parsedMetadata(stream) {
    const out = {};
    for (const [k, v] of Object.entries(stream.metadata)) {
      try { out[k] = JSON.parse(v); } catch { out[k] = v; }
    }
    return out;
  }

  /** The entry a stream's final empty batch says changed, or null. */
  function fileChanged(stream) {
    for (const b of stream.batches) if (b.metadata.file_changed) return b.metadata.file_changed;
    return null;
  }

  return { read, entrySeries, parsedMetadata, fileChanged };
});
