// Tests for the Entries view's model (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import {
  EntryNode,
  ListedEntry,
  buildEntryTree,
  buildFieldNodes,
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
