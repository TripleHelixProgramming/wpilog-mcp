// Tests for the webview's Arrow stream reader (no VS Code needed): npm test
//
// The reader is checked against the streams the server's own tests leave under
// ../build/arrow-samples (DataEndpointTest, which CI runs before these tests), each beside the
// CSV of the same request, and which the CI pyarrow job reads with the reference implementation.
// Without the samples (a checkout where the Java tests have not run) the sample tests are skipped.
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as path from "path";

// The reader is a plain script shared with the webview, so it is loaded as one
// eslint-disable-next-line @typescript-eslint/no-var-requires
const ArrowStream = require(path.join(__dirname, "..", "..", "media", "arrowStream.js")) as {
  read(bytes: Uint8Array): Stream;
  entrySeries(stream: Stream, entry: string): { count: number; timestamps: Float64Array; columns: Record<string, Float64Array | unknown[]> };
  parsedMetadata(stream: Stream): Record<string, unknown>;
  fileChanged(stream: Stream): string | null;
};

interface Field { name: string; type: string; nullable: boolean; children: Field[] }
interface Column { name: string; type: string; length: number; nullCount: number; valid: Uint8Array | null; values: Float64Array | Uint8Array | unknown[] }
interface Batch { metadata: Record<string, string>; length: number; columns: Column[] }
interface Stream { fields: Field[]; metadata: Record<string, string>; batches: Batch[]; complete: boolean }

const samples = path.join(__dirname, "..", "..", "..", "build", "arrow-samples");
const haveSamples = fs.existsSync(samples) && fs.readdirSync(samples).some((f) => f.endsWith(".arrow"));

test("an unnamed enum from the real endpoint retains its number and null label", { skip: !haveSamples && "run DataEndpointTest first" }, () => {
  const stream = ArrowStream.read(new Uint8Array(fs.readFileSync(path.join(samples, "unnamed_enum.arrow"))));
  assert.ok(stream.complete);
  const series = ArrowStream.entrySeries(stream, "/Mode");
  assert.equal(series.count, 1);
  assert.deepEqual(series.columns.value, [{ state: { value: 2, label: null } }]);
});

/** The CSV's sections as the data endpoint writes them: entry to {columns, rows}. */
function readCsv(file: string): { entry: string; columns: string[]; rows: string[][] }[] {
  const text = fs.readFileSync(file, "utf8");
  const sections: { entry: string; columns: string[]; rows: string[][] }[] = [];
  let current: { entry: string; columns: string[]; rows: string[][] } | undefined;
  for (const record of parseCsv(text)) {
    if (record.length === 0 || (record.length === 1 && record[0] === "")) continue;
    if (record[0].startsWith("# entry: ")) {
      current = { entry: record[0].slice("# entry: ".length), columns: [], rows: [] };
      sections.push(current);
    } else if (record[0].startsWith("#")) {
      continue;
    } else if (current && current.columns.length === 0) {
      current.columns = record;
    } else if (current) {
      current.rows.push(record);
    }
  }
  return sections;
}

/** RFC 4180, enough for the endpoint's CSV: quoted cells may hold commas, quotes, and newlines. */
function parseCsv(text: string): string[][] {
  const records: string[][] = [];
  let record: string[] = [];
  let cell = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') { cell += '"'; i++; } else quoted = false;
      } else cell += c;
    } else if (c === '"') {
      quoted = true;
    } else if (c === ",") {
      record.push(cell); cell = "";
    } else if (c === "\n") {
      record.push(cell); records.push(record); record = []; cell = "";
    } else if (c !== "\r") {
      cell += c;
    }
  }
  if (cell !== "" || record.length > 0) { record.push(cell); records.push(record); }
  return records;
}

function close(a: number, b: number): boolean {
  if (Number.isNaN(a) || Number.isNaN(b)) return Number.isNaN(a) && Number.isNaN(b);
  return Math.abs(a - b) <= Math.max(1e-9, 1e-9 * Math.max(Math.abs(a), Math.abs(b)));
}

/** The endpoint's samples: each stream beside the CSV of the same request. */
function endpointSamples(): string[] {
  return fs.readdirSync(samples)
    .filter((f) => f.endsWith(".arrow") && fs.existsSync(path.join(samples, f.replace(/\.arrow$/, ".csv"))))
    .sort();
}

test("every sample stream the server's tests left is read whole, with the metadata and the entry tags", { skip: !haveSamples && "no build/arrow-samples (run the Java DataEndpointTest first)" }, () => {
  for (const name of endpointSamples()) {
    const stream = ArrowStream.read(new Uint8Array(fs.readFileSync(path.join(samples, name))));
    assert.ok(stream.complete, `${name}: end marker`);
    assert.equal(stream.fields[0].name, "timestamp", name);
    assert.equal(stream.fields[0].type, "timestamp[us]", name);
    const metadata = ArrowStream.parsedMetadata(stream) as { entries: { name: string }[]; inputs: { log: string }; bucketed: boolean; server_version: string };
    assert.ok(metadata.server_version.length > 0, name);
    assert.ok(typeof metadata.inputs.log === "string", name);
    const tagged = stream.batches.filter((b) => b.metadata.entry).map((b) => b.metadata.entry);
    assert.deepEqual([...new Set(tagged)], metadata.entries.map((e) => e.name), `${name}: the batches' entries`);
    assert.equal(ArrowStream.fileChanged(stream), null, name);
  }
});

test("the numbers read equal the CSV of the same request, bucketed and exact alike", { skip: !haveSamples && "no build/arrow-samples" }, () => {
  for (const name of endpointSamples()) {
    const stream = ArrowStream.read(new Uint8Array(fs.readFileSync(path.join(samples, name))));
    const metadata = ArrowStream.parsedMetadata(stream) as { bucketed: boolean };
    const valueType = stream.fields[1].type;
    if (!metadata.bucketed && valueType !== "float64" && valueType !== "int64" && valueType !== "bool" && valueType !== "utf8") {
      continue; // structs and lists are compared by the Java test and by pyarrow
    }
    for (const section of readCsv(path.join(samples, name.replace(/\.arrow$/, ".csv")))) {
      const series = ArrowStream.entrySeries(stream, section.entry);
      assert.equal(series.count, section.rows.length, `${name} ${section.entry}: rows`);
      const t = section.columns.indexOf("timestamp_sec");
      for (let i = 0; i < section.rows.length; i++) {
        assert.ok(close(series.timestamps[i], Number(section.rows[i][t])), `${name} ${section.entry} row ${i}: time ${series.timestamps[i]} vs ${section.rows[i][t]}`);
        for (const column of section.columns) {
          if (column === "timestamp_sec") continue;
          const csv = section.rows[i][section.columns.indexOf(column)];
          const got = series.columns[column][i];
          if (got instanceof Object || typeof got === "string") {
            assert.equal(String(got), csv, `${name} ${section.entry} row ${i} ${column}`);
          } else {
            const expected = csv === "" ? NaN : Number(csv);
            assert.ok(close(got as number, expected), `${name} ${section.entry} row ${i} ${column}: ${got} vs ${csv}`);
          }
        }
      }
    }
  }
});

test("the writer's round-trip sample reads back with every null of every type (the Java test's data)", { skip: !fs.existsSync(path.join(samples, "writer_roundtrip.arrow")) && "no writer_roundtrip.arrow (run the Java ArrowStreamWriterTest first)" }, () => {
  const stream = ArrowStream.read(new Uint8Array(fs.readFileSync(path.join(samples, "writer_roundtrip.arrow"))));
  assert.ok(stream.complete);
  assert.deepEqual(stream.fields.map((f) => f.type), ["timestamp[us]", "float64", "int64", "bool", "utf8", "binary", "struct", "list", "list"]);
  assert.deepEqual(stream.metadata, { version: "0.9.1", inputs: '{"log":"x"}' });
  assert.equal(stream.batches.length, 2);
  const first = stream.batches[0];
  assert.equal(first.metadata.entry, "/A");
  assert.equal(first.length, 11);
  const [ts, d, i, b, s, raw, pose, values, poses] = first.columns;
  assert.deepEqual([...(ts.values as Float64Array)].slice(0, 3), [0, 1_000_000, 2_000_000]);
  // d: row 3 null (NaN here, with valid saying so), row 5 NaN as logged (valid)
  const dv = d.values as Float64Array;
  assert.ok(Number.isNaN(dv[3]) && d.valid && d.valid[3] === 0, "a null float is NaN and marked invalid");
  assert.ok(Number.isNaN(dv[5]) && d.valid![5] === 1, "a NaN as logged is NaN and valid");
  assert.equal(dv[4], 6);
  assert.equal(d.nullCount, 1);
  // i: row 0 null; b: row 9 null; s: row 1 null, row 2 empty; raw: row 4 null
  assert.ok(Number.isNaN((i.values as Float64Array)[0]) && (i.values as Float64Array)[1] === -1);
  assert.equal(b.valid![9], 0);
  assert.deepEqual([...(b.values as Uint8Array)].slice(0, 4), [1, 0, 0, 1]);
  assert.equal((s.values as unknown[])[1], null);
  assert.equal((s.values as unknown[])[2], "");
  assert.equal((s.values as unknown[])[3], "row 3 é");
  assert.equal((raw.values as unknown[])[4], null);
  assert.deepEqual([...((raw.values as Uint8Array[])[3])], [3, 255]);
  // pose: row 6 null; row 7's name null inside a valid struct
  assert.equal((pose.values as unknown[])[6], null);
  assert.deepEqual((pose.values as unknown[])[7], { x: 1.75, name: null });
  assert.deepEqual((pose.values as unknown[])[2], { x: 0.5, name: "p2" });
  // values: row 8 null, row 2 empty, row 3 [3, 6]; poses: even rows two structs, odd rows none
  assert.equal((values.values as unknown[])[8], null);
  assert.deepEqual((values.values as unknown[])[2], []);
  assert.deepEqual((values.values as unknown[])[3], [3, 6]);
  assert.deepEqual((poses.values as unknown[])[4], [{ x: 104, name: "q" }, { x: 104, name: "q" }]);
  assert.deepEqual((poses.values as unknown[])[1], []);
  const second = stream.batches[1];
  assert.equal(second.metadata.entry, "/B");
  assert.equal(second.length, 1);
  assert.deepEqual((second.columns[7].values as unknown[])[0], [9]);
  // entrySeries concatenates the numeric columns and scales the timestamps to seconds
  const series = ArrowStream.entrySeries(stream, "/A");
  assert.equal(series.count, 11);
  assert.equal(series.timestamps[2], 2);
  assert.ok(Number.isNaN((series.columns.d as Float64Array)[3]));
});

test("a stream that is not Arrow, or stops short, is refused with a reason", () => {
  assert.throws(() => ArrowStream.read(new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9])), /continuation marker/);
  assert.throws(() => ArrowStream.read(new Uint8Array(0)), /no schema/);
  if (haveSamples) {
    const file = fs.readdirSync(samples).find((f) => f.endsWith(".arrow"))!;
    const bytes = new Uint8Array(fs.readFileSync(path.join(samples, file)));
    const cut = bytes.subarray(0, bytes.length - 8);
    const stream = ArrowStream.read(cut);
    assert.ok(!stream.complete, "without the end marker the stream is incomplete, not wrong");
  }
});
