// Tests for the Entries view's model (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import {
  EntryNode,
  ListedEntry,
  MAX_ELEMENTS_SHOWN,
  arrayLength,
  buildEntryTree,
  buildFieldNodes,
  elementNodes,
  elementPaths,
  formatCount,
  formatSeconds,
  hasFieldPaths,
} from "../explorer/entriesTree";

const entries: ListedEntry[] = [
  { name: "/Drive", type: "string", sample_count: 1 },
  { name: "/Drive/Odometry/Pose", type: "struct:Pose2d", sample_count: 11735 },
  { name: "/Drive/ModuleStates", type: "struct:SwerveModuleState[]", sample_count: 11000 },
  { name: "/SystemStats/BatteryVoltage", type: "double", sample_count: 11149 },
  { name: "/SystemStats/BatteryCurrent", type: "double", sample_count: 11149 },
  { name: "NT:/FMSInfo/EventName", type: "string", sample_count: 3 },
  { name: "/Vision/Camera0/PoseObservations", type: "struct:PoseObservation[]", sample_count: 5000 },
  { name: "/PDH/Currents", type: "double[]", sample_count: 400 },
];

function shape(nodes: EntryNode[]): unknown {
  return nodes.map((n) => (n.kind === "group" ? { [n.label]: shape(n.children) } : n.label));
}

test("entries are a tree by the segments of their names, groups before entries, in name order", () => {
  const tree = buildEntryTree({ status: "ok", entries });
  assert.deepEqual(shape(tree), [
    { Drive: [{ Odometry: ["Pose"] }, "ModuleStates"] },
    { "NT:": [{ FMSInfo: ["EventName"] }] },
    { PDH: ["Currents"] },
    { SystemStats: ["BatteryCurrent", "BatteryVoltage"] },
    { Vision: [{ Camera0: ["PoseObservations"] }] },
    "Drive",
  ]);
});

test("an entry's line carries its type and count, and says whether it has fields to expand", () => {
  const tree = buildEntryTree({ entries });
  const drive = tree[0] as { children: EntryNode[]; path: string };
  assert.equal(drive.path, "/Drive");
  const states = drive.children[1];
  assert.ok(states.kind === "entry");
  assert.equal(states.description, "struct:SwerveModuleState[] · 11,000");
  assert.equal(states.tooltip, "/Drive/ModuleStates\nstruct:SwerveModuleState[], 11,000 samples");
  assert.ok(states.expandable);
  assert.equal(states.entry.name, "/Drive/ModuleStates");
  const scalar = (tree[3] as { children: EntryNode[] }).children[1];
  assert.ok(scalar.kind === "entry" && !scalar.expandable, "a double has no fields");
  assert.ok(hasFieldPaths("struct:Pose2d") && hasFieldPaths("double[]") && hasFieldPaths("struct:X[]"));
  assert.ok(!hasFieldPaths("double") && !hasFieldPaths("string") && !hasFieldPaths("boolean") && !hasFieldPaths("raw"));
});

test("a failed listing or an empty one is a note with the server's words", () => {
  assert.deepEqual(buildEntryTree({ status: "error", error: "File not found: /x", hint: "list_available_logs" }), [
    { kind: "note", label: "File not found: /x", tooltip: "list_available_logs" },
  ]);
  assert.deepEqual(buildEntryTree({ status: "no_match", entries: [], reason: 'No entry name contains "zzz"' }), [
    { kind: "note", label: 'No entry name contains "zzz"', tooltip: undefined },
  ]);
  assert.equal(buildEntryTree({})[0].label, "No entries");
});

test("a struct's field nodes are its numeric leaf paths, each with the field's type where the schema says it", () => {
  const entry = entries[6];
  const info = {
    numeric_leaf_paths: ["[*].timestamp", "[*].pose.translation.x", "[*].tagCount", "[*].type"],
    struct: {
      fields: [
        { name: "timestamp", type: "double" },
        { name: "pose", type: "Pose3d" },
        { name: "tagCount", type: "int32" },
        { name: "type", type: "int32", enum: { MEGATAG_1: 0 } },
      ],
      is_array: true,
    },
  };
  const nodes = buildFieldNodes(entry, info);
  assert.deepEqual(
    nodes.map((n) => (n.kind === "field" ? [n.label, n.description, n.fieldPath] : n.kind)),
    [
      ["[*].timestamp", "double", "[*].timestamp"],
      ["[*].pose.translation.x", "Pose3d", "[*].pose.translation.x"],
      ["[*].tagCount", "int32", "[*].tagCount"],
      ["[*].type", "int32", "[*].type"],
    ]
  );
  assert.ok(nodes.every((n) => n.kind === "field" && n.entry === entry));
  // A scalar struct's paths start with a dot; a numeric array's with an index
  const scalar = buildFieldNodes(entries[1], { numeric_leaf_paths: [".translation.x"], struct: { fields: [{ name: "translation", type: "Translation2d" }] } })[0];
  assert.ok(scalar.kind === "field" && scalar.description === "Translation2d");
  assert.equal(buildFieldNodes(entries[7], { numeric_leaf_paths: ["[0]", "[1]"] })[1].label, "[1]");
  assert.deepEqual(buildFieldNodes(entry, {}), [{ kind: "note", label: "No numeric fields" }]);
});

test("counts and seconds are shown as the views show them", () => {
  assert.equal(formatCount(11735), "11,735");
  assert.equal(formatCount(NaN), "");
  assert.equal(formatSeconds(12.658122), "12.658 s");
  assert.equal(formatSeconds(undefined), "");
});

test("an array's length comes from the longest representative sample, cut or not", () => {
  assert.equal(arrayLength({ sample_values: [{ value: [1, 2, 3] }, { value: [] }, { value: [1] }] }), 3);
  assert.equal(arrayLength({ sample_values: [{ value: [1, 2], value_length: 24, value_truncated: true }] }), 24, "a cut array reports its full length");
  assert.equal(arrayLength({ sample_values: [{ value: 1.5 }] }), 0, "a scalar has no length");
  assert.equal(arrayLength({}), 0);
});

test("a [*] field path expands to its elements, each a path the tools take by index, with a note past the limit", () => {
  const entry = entries[6]; // PoseObservations, struct array
  const info = {
    numeric_leaf_paths: ["[*].timestamp", "[*].tagCount"],
    struct: { fields: [{ name: "timestamp", type: "double" }, { name: "tagCount", type: "int32" }], is_array: true },
    sample_values: [{ value: [{}, {}, {}] }],
  };
  const fields = buildFieldNodes(entry, info);
  assert.ok(fields[0].kind === "field" && fields[0].elements === 3 && fields[0].description === "double · 3 elements");
  const children = elementNodes(fields[0] as { fieldPath: string; entry: ListedEntry; elements?: number });
  assert.deepEqual(children.map((c) => c.label), ["[0].timestamp", "[1].timestamp", "[2].timestamp"]);
  assert.ok(children.every((c) => c.kind === "field" && c.entry === entry));
  // A plain path has no elements
  assert.deepEqual(elementNodes({ fieldPath: ".translation.x", entry: entries[1] }), []);
  // A long array is cut with a note that says how to address the rest
  const long = elementNodes({ fieldPath: "[*]", entry: entries[7], elements: MAX_ELEMENTS_SHOWN + 5 });
  assert.equal(long.length, MAX_ELEMENTS_SHOWN + 1);
  assert.equal(long[MAX_ELEMENTS_SHOWN].kind, "note");
  assert.ok(long[MAX_ELEMENTS_SHOWN].label.includes(`[${MAX_ELEMENTS_SHOWN}]`));
  // A numeric array's leaf path is [*] itself
  const currents = buildFieldNodes(entries[7], { numeric_leaf_paths: ["[*]"], sample_values: [{ value: [1, 2, 3, 4] }] });
  assert.ok(currents[0].kind === "field" && currents[0].elements === 4);
  assert.deepEqual(elementPaths("/PDH/Currents", "[*]", 4, 16), ["/PDH/Currents[0]", "/PDH/Currents[1]", "/PDH/Currents[2]", "/PDH/Currents[3]"]);
  assert.deepEqual(elementPaths("/V/Obs", "[*].tagCount", 30, 2), ["/V/Obs[0].tagCount", "/V/Obs[1].tagCount"], "a plot takes the first few");
});
