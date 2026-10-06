// Tests for the Logs view's model (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import {
  ListedLog,
  LogNode,
  NO_EVENT,
  buildLogTree,
  dateInFilename,
  dateOf,
  formatSize,
  logMatches,
  matchOf,
} from "../explorer/logsTree";

// A listing as list_available_logs returns it, newest first (TOOLS.md's example, extended)
const q42: ListedLog = {
  friendly_name: "VADC Qualification 42",
  path: "/logs/akit_24-03-16_15-20-00_vadc_q42.wpilog",
  filename: "akit_24-03-16_15-20-00_vadc_q42.wpilog",
  event: "VADC",
  match_type: "Qualification",
  match_number: 42,
  team_number: 2363,
  size_bytes: 15234567,
  last_modified: 1710523456000,
  tba: { alliance: "red", score: 85, won: true, opponent_score: 72 },
};
const q40: ListedLog = {
  friendly_name: "VADC Qualification 40",
  path: "/logs/akit_24-03-16_13-02-00_vadc_q40.wpilog",
  filename: "akit_24-03-16_13-02-00_vadc_q40.wpilog",
  event: "VADC",
  match_type: "Qualification",
  match_number: 40,
  size_bytes: 1024,
};
const practice: ListedLog = {
  friendly_name: "VADC",
  path: "/logs/akit_24-03-15_10-02-11_vadc.wpilog",
  filename: "akit_24-03-15_10-02-11_vadc.wpilog",
  event: "vadc",
  size_bytes: 8765432,
};
const shop: ListedLog = {
  friendly_name: "FRC_20240301_190000.wpilog",
  path: "/logs/FRC_20240301_190000.wpilog",
  filename: "FRC_20240301_190000.wpilog",
  size_bytes: 500,
};
const renamed: ListedLog = {
  friendly_name: "copy of a log.wpilog",
  path: "/logs/copy of a log.wpilog",
  filename: "copy of a log.wpilog",
  last_modified: Date.UTC(2024, 1, 29, 23, 59),
};
const listing = { status: "ok", log_directories: ["/logs"], logs: [q42, q40, practice, shop, renamed], log_count: 5 };

function labels(nodes: LogNode[]): unknown {
  return nodes.map((n) => ("children" in n ? { [n.label]: labels(n.children) } : n.label));
}

test("the date is the file name's, in both frameworks' forms, else the modification time's, else undated", () => {
  assert.equal(dateInFilename("akit_24-03-16_15-20-00_vadc_q42.wpilog"), "2024-03-16");
  assert.equal(dateInFilename("FRC_20260321_162956_VACHE_Q10.wpilog"), "2026-03-21");
  assert.equal(dateInFilename("frc_25-03-15_10-30-00_vadc_qm42_sim.wpilog"), "2025-03-15");
  assert.equal(dateInFilename("copy of a log.wpilog"), undefined);
  assert.equal(dateOf(renamed), "2024-02-29", "UTC, as the roboRIO names files");
  assert.equal(dateOf({ ...renamed, last_modified: undefined }), "Undated");
  assert.equal(dateOf(q42), "2024-03-16", "the name wins over the modification time");
});

test("sizes read as bytes, KB, or MB; a match is one phrase", () => {
  assert.equal(formatSize(500), "500 B");
  assert.equal(formatSize(1024), "1.0 KB");
  assert.equal(formatSize(15234567), "14.5 MB");
  assert.equal(formatSize(undefined), "");
  assert.equal(formatSize(-1), "");
  assert.equal(matchOf(q42), "Qualification 42");
  assert.equal(matchOf(practice), "");
  assert.equal(matchOf({ ...q42, match_number: undefined }), "Qualification");
});

test("the tree groups by event, then by date newest first, keeping the listing's order within a date", () => {
  const tree = buildLogTree(listing);
  assert.deepEqual(labels(tree), [
    { VADC: [{ "2024-03-16": ["VADC Qualification 42", "VADC Qualification 40"] }, { "2024-03-15": ["VADC"] }] },
    { [NO_EVENT]: [{ "2024-03-01": ["FRC_20240301_190000.wpilog"] }, { "2024-02-29": ["copy of a log.wpilog"] }] },
  ]);
  const log = (tree[0] as { children: { children: LogNode[] }[] }).children[0].children[0];
  assert.ok(log.kind === "log");
  assert.equal(log.description, "14.5 MB · Won 85-72 on red");
  assert.ok(log.tooltip.includes(q42.path) && log.tooltip.includes("Team 2363") && log.tooltip.includes("Match: Qualification 42"));
  assert.equal(log.log, q42, "the node carries the log it was built from");
});

test("an event's case does not split it in two, and a log with no event is under its own group", () => {
  const tree = buildLogTree({ logs: [{ ...q42, event: "vadc" }, { ...q40, event: "VADC" }] });
  assert.equal(tree.length, 1);
  assert.equal(tree[0].label, "VADC");
});

test("the filter narrows by name, file name, event, or match, case-insensitively, and says when nothing is left", () => {
  assert.ok(logMatches(q42, "qualification 42"));
  assert.ok(logMatches(q42, "VADC"));
  assert.ok(logMatches(q42, "q42"), "the file name");
  assert.ok(logMatches(shop, "FRC_2024"));
  assert.ok(!logMatches(shop, "vadc"));
  assert.ok(logMatches(shop, "  "), "a blank filter passes everything");
  assert.deepEqual(labels(buildLogTree(listing, "q40")), [{ VADC: [{ "2024-03-16": ["VADC Qualification 40"] }] }]);
  const none = buildLogTree(listing, "zzz");
  assert.equal(none.length, 1);
  assert.ok(none[0].kind === "note" && none[0].label === 'No log matches "zzz"');
});

test("a directory the server could not read is a note before the logs, and a failed listing is one note", () => {
  const partial = buildLogTree({
    status: "partial",
    logs: [q42],
    skipped: [{ section: "logs", directory: "/mnt/archive", reason: "not a directory" }],
  });
  assert.equal(partial[0].kind, "note");
  assert.equal(partial[0].label, "Could not read /mnt/archive");
  assert.equal((partial[0] as { tooltip?: string }).tooltip, "not a directory");
  assert.equal(partial[1].kind, "event");
  const failed = buildLogTree({ status: "error", error: "No log directory configured", hint: "set logdir" });
  assert.deepEqual(failed, [{ kind: "note", label: "No log directory configured", tooltip: "set logdir" }]);
  const empty = buildLogTree({ status: "no_match", logs: [], log_directories: ["/logs"], hint: "copy logs there" });
  assert.deepEqual(empty, [{ kind: "note", label: "No logs in /logs", tooltip: "copy logs there" }]);
});

test("a listing cut by its page says so after the logs", () => {
  const tree = buildLogTree({ logs: [q42], log_count: 88, has_more: true });
  assert.equal(tree.at(-1)?.kind, "note");
  assert.equal(tree.at(-1)?.label, "Showing 1 of 88 logs; narrow with the filter");
});

// Store rows are the same shape as the server fixture's recorded manifest listing.
import { listing as storeListing, one as storedOne, plain, plainLog, store } from "./fixtures/organizing";
import * as path from "path";

test("a store groups robots, dates and sessions, with the wpilog before REV companions and import groups last", () => {
  const tree = buildLogTree(storeListing);
  assert.deepEqual(labels(tree), [
    { Practice: [{ "2026-01-10": [{ "15:00:00 UTC · TEST · Qualification 2": ["TEST Qualification 2", "REV.revlog"] }] }] },
    { "Competition · SERIAL42": [{ "2026-01-11": [{ "16:00:00 UTC · TEST · Qualification 3": ["TEST Qualification 3"] }] }] },
    { Unassigned: ["loose.wpilog"] }, { Inbox: ["waiting.wpilog", "running.wpilog", "bad.txt"] }, { Unmanaged: ["stray.wpilog"] },
  ]);
  assert.equal(tree[0].kind, "robot");
  assert.ok(tree[0].kind === "robot" && tree[0].tooltip.includes("stated"));
  assert.ok(tree[1].kind === "robot" && tree[1].tooltip.includes("logged"));
  const flat = (nodes: LogNode[]): LogNode[] => nodes.flatMap(n => [n, ...("children" in n ? flat(n.children) : [])]);
  const all = flat(tree);
  const rev = all.find(n => n.kind === "log" && n.log.kind === "revlog");
  assert.ok(rev?.kind === "log");
  assert.equal(rev.log.wpilog, storedOne.path, "REV opens beside its wpilog in the existing viewer");
  assert.equal(rev.description, "1.0 KB");
  const inbox = tree[3];
  assert.ok(inbox.kind === "imports");
  assert.equal(inbox.store, store);
  assert.equal(inbox.group, "inbox");
  assert.deepEqual(inbox.children.map(n => n.kind === "importFile" ? n.description : ""),
    ["waiting · 40 B", "importing · Practice · 80 B", "refused · 5 B"]);
  assert.ok(inbox.children[2].kind === "importFile" && inbox.children[2].tooltip.includes("Unsupported file content"));
  const unassigned = tree[2];
  assert.ok(unassigned.kind === "imports" && unassigned.children[0].kind === "importFile");
  assert.equal(unassigned.children[0].file.path, path.join(store, "unassigned", "hash", "loose.wpilog"));
});

test("a plain directory keeps its event/date tree and a mixed window adds store and directory roots", () => {
  assert.deepEqual(labels(buildLogTree({ log_directories: [plain], logs: [plainLog] })),
    [{ "No event": [{ "2026-01-09": ["Shop"] }] }]);
  const tree = buildLogTree({ ...storeListing, log_directories: [store, plain], logs: [...storeListing.logs!, plainLog] });
  assert.deepEqual(tree.map(n => [n.kind, n.label]), [["store", path.basename(store)], ["directory", path.basename(plain)]]);
  assert.ok(tree[0].kind === "store" && tree[0].folder === store);
  assert.ok(tree[1].kind === "directory");
  assert.deepEqual(labels(tree[1].children), [{ "No event": [{ "2026-01-09": ["Shop"] }] }]);
});

test("store filters reach REV companions and inbox refusals; empty stores keep their import groups", () => {
  const rev = buildLogTree(storeListing, "REV.revlog");
  assert.deepEqual(labels(rev), [
    { Practice: [{ "2026-01-10": [{ "15:00:00 UTC · TEST · Qualification 2": ["REV.revlog"] }] }] },
    { Unassigned: [] }, { Inbox: [] }, { Unmanaged: [] },
  ]);
  assert.deepEqual(labels(buildLogTree(storeListing, "unsupported")), [
    { Unassigned: [] }, { Inbox: ["bad.txt"] }, { Unmanaged: [] },
  ]);
  assert.deepEqual(labels(buildLogTree({ stores: [{ path: store }], log_directories: [store] })),
    [{ Unassigned: [] }, { Inbox: [] }, { Unmanaged: [] }]);
  const partial = buildLogTree({ ...storeListing, skipped: [{ directory: plain, reason: "not mounted" }], has_more: true });
  assert.equal(partial[0].label, `Could not read ${plain}`);
  assert.equal(partial.at(-1)?.label, "Showing 2 of 2 logs; narrow with the filter");
});

test("store sessions sort by their actual clocks, including fractional seconds, within newest dates first", () => {
  const make = (id: string, started_at: string) => ({ ...storedOne, friendly_name: id, filename: `${id}.wpilog`,
    path: path.join(store, id), revlogs: [], session: { ...storedOne.session!, id, started_at, ended_at: started_at } });
  const tree = buildLogTree({ stores: [{ path: store }], logs: [
    make("early", "2026-01-10T15:00:00Z"), make("fraction", "2026-01-10T15:00:00.123Z"), make("next", "2026-01-11T14:00:00Z"),
  ] });
  assert.deepEqual(labels(tree), [{ Practice: [
    { "2026-01-11": [{ "14:00:00 UTC · TEST · Qualification 2": ["next"] }] },
    { "2026-01-10": [{ "15:00:00.123 UTC · TEST · Qualification 2": ["fraction"] }, { "15:00:00 UTC · TEST · Qualification 2": ["early"] }] },
  ] }, { Unassigned: [] }, { Inbox: [] }, { Unmanaged: [] }]);
});

test("origin-bearing store and plain directories preserve the existing tree", () => {
  const listing = { ...storeListing, log_directories: [
    { path: store, origin: "configured" as const, team: null },
    { path: plain, origin: "leased" as const, team: 11 },
  ], logs: [...storeListing.logs!, plainLog] };
  const tree = buildLogTree(listing);
  assert.deepEqual(tree.map(node => node.label), [path.basename(store), path.basename(plain)]);
  assert.equal(tree[1].kind, "directory");
});
