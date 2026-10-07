import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "path";
import { Choices, ORGANIZE_CHOICES, MOVE_CHOICES, containsPath, folderKey, importPlan, offerFor, organizeFolders, rememberChoice, robotItems, robotNameError } from "../explorer/organize";
import { organizeSources, DirectoryEntry } from "../explorer/organizeSources";
import { listing, plain, plainLog, store } from "./fixtures/organizing";
const empty: Choices = { never: [], deferred: [], offered: {} };
const simple = { logs: [plainLog], log_directories: [plain] };

test("a plain folder offers its exact log count once; cancelling defers until activation", () => {
  const offer = offerFor(simple, plain, empty)!;
  assert.equal(offer.message, "Organize these 1 logs by robot and session?");
  assert.equal(offer.count, 1);
  assert.equal(offer.store, false);
  assert.deepEqual(offer.paths, [plainLog.path]);
  assert.deepEqual(ORGANIZE_CHOICES, ["Organize", "Not now", "Never for this folder"]);
  assert.deepEqual(MOVE_CHOICES.map(c => c.move), [true, false]);
  for (const choice of ["Organize", "Not now", undefined] as const) {
    const remembered = rememberChoice(empty, offer, choice);
    assert.equal(offerFor(simple, plain, remembered), undefined);
    assert.ok(offerFor(simple, plain, { ...empty, never: remembered.never }), "a new activation may offer again");
    assert.ok(offerFor(simple, plain, remembered, true), "the command can ask again now");
  }
});

test("Never is per folder and survives activation; an explicit command can revisit it", () => {
  const offer = offerFor(simple, plain, empty)!;
  const remembered = rememberChoice(empty, offer, "Never for this folder");
  assert.deepEqual(remembered.never, [folderKey(plain)]);
  assert.equal(offerFor(simple, plain, { ...empty, never: remembered.never }), undefined);
  assert.ok(offerFor(listing, store, remembered));
  assert.ok(offerFor(simple, plain, remembered, true));
  assert.deepEqual(rememberChoice(remembered, offer, "Never for this folder").never, remembered.never);
});

test("store offers show unmanaged names only, first three, and a new batch can offer again", () => {
  const unmanaged = ["b.wpilog", "a.wpilog", "d.wpilog", "c.revlog"].map(name => ({ store, path: path.join(store, name) }));
  const current = { ...listing, unmanaged };
  const offer = offerFor(current, store, empty)!;
  assert.equal(offer.count, 4);
  assert.equal(offer.store, true);
  assert.equal(offer.message, "Organize these 4 unmanaged files by robot and session? a.wpilog, b.wpilog, c.revlog, …");
  const accepted = rememberChoice(empty, offer, "Organize");
  assert.equal(offerFor(current, store, accepted), undefined);
  assert.ok(offerFor({ ...current, unmanaged: [...unmanaged, { store, path: path.join(store, "e.wpilog") }] }, store, accepted));
  const deferred = rememberChoice(empty, offer, "Not now");
  assert.equal(offerFor({ ...current, unmanaged: [] }, store, deferred), undefined);
  assert.equal(offerFor({ ...current, unmanaged: [...unmanaged, { store, path: path.join(store, "new.wpilog") }] }, store, deferred), undefined);
  assert.deepEqual(organizeFolders({ ...listing, log_directories: [store, plain] }), [store, plain]);
});

test("empty, failed, skipped, partial-page, other-folder, and already managed listings never offer", () => {
  for (const current of [{}, { ...simple, status: "error" }, { ...simple, has_more: true },
    { ...simple, skipped: [{ directory: plain, reason: "unavailable" }] }, { logs: listing.logs },
    { ...listing, unmanaged: [] }]) {
    assert.equal(offerFor(current, current === simple ? plain : ("stores" in current ? store : plain), empty), undefined);
  }
  assert.equal(offerFor(simple, `${plain}-other`, empty), undefined);
  assert.equal(offerFor({ logs: [plainLog] }, plain, empty), undefined, "a folder must be configured");
  assert.ok(offerFor({ ...simple, status: "partial", skipped: [{ directory: store }] }, plain, empty));
  assert.equal(containsPath("/logs", "/logs-old/a", path.posix), false);
  assert.equal(containsPath("C:\\Logs", "c:/logs/nested/a.wpilog", path.win32), true);
  assert.equal(containsPath("C:\\Logs", "C:\\Logs-other\\a.wpilog", path.win32), false);
  assert.equal(containsPath("C:\\Logs", "D:\\Logs\\a.wpilog", path.win32), false);
});

test("robot picks use names and serials, deduplicate listing rows, and include New and Decide later", () => {
  assert.deepEqual(robotItems(listing, store), [
    { action: "robot", label: "Competition", robot: "Competition", description: "Serial SERIAL42" },
    { action: "robot", label: "Practice", robot: "Practice", description: "Stated name; serial not logged" },
    { action: "new", label: "New robot…" }, { action: "later", label: "Decide later" },
  ]);
  assert.deepEqual(robotItems(listing, plain).map(i => i.action), ["new", "later"]);
  assert.equal(robotItems({ stores: [{ path: store, robots: [{ id: "SERIAL", serial_number: "SERIAL", basis: "logged" }] }] }, store)[0].robot, "SERIAL");
});

test("robot name errors match the server's ASCII and Windows portability words", () => {
  for (const name of ["practice", "Robot_2-A.b", "123"]) assert.equal(robotNameError(name), undefined);
  for (const name of ["", "two words", "robot/child", "café"]) {
    assert.equal(robotNameError(name), "Robot names use only letters, digits, dots, hyphens, and underscores");
  }
  for (const name of [".", "..", "tail.", "CON", "lpt9.txt"]) assert.equal(robotNameError(name), `Not a portable robot or file name: ${name}`);
});

test("plain offers include REV files but skip nested stores and symlinks; store offers keep exact paths", async () => {
  const reads: string[] = [];
  const nested = path.join(plain, "nested-store");
  const tree = new Map<string, DirectoryEntry[]>([
    [plain, [
      { name: "one.REVLOG", directory: false, file: true, symlink: false },
      { name: "notes.txt", directory: false, file: true, symlink: false },
      { name: "alias", directory: true, file: false, symlink: true },
      { name: "sub", directory: true, file: false, symlink: false },
      { name: "nested-store", directory: true, file: false, symlink: false },
    ]],
    [path.join(plain, "sub"), [{ name: "two.revlog", directory: false, file: true, symlink: false }]],
  ]);
  const read = async (folder: string) => { reads.push(folder); return tree.get(folder) ?? []; };
  const files = await organizeSources(offerFor(simple, plain, empty)!, [nested], read);
  assert.deepEqual(files, [plainLog.path, path.join(plain, "one.REVLOG"), path.join(plain, "sub", "two.revlog")]);
  assert.deepEqual(reads, [plain, path.join(plain, "sub")]);
  const offer = offerFor(listing, store, empty)!;
  assert.deepEqual(await organizeSources(offer, [store], async () => { throw new Error("must not scan"); }), offer.paths);
});


test("only Organize plus completed move and robot choices authorizes a request; Decide later sends null", () => {
  const offer = offerFor(simple, plain, empty)!;
  for (const choice of ["Not now", "Never for this folder", undefined] as const) {
    assert.equal(importPlan(offer, choice, true, "Practice"), undefined);
  }
  assert.equal(importPlan(offer, "Organize", undefined, "Practice"), undefined);
  assert.equal(importPlan(offer, "Organize", true, undefined), undefined);
  assert.deepEqual(importPlan(offer, "Organize", true, "Practice"),
    { store: plain, paths: [plainLog.path], move: true, stated_robot: "Practice" });
  assert.deepEqual(importPlan(offer, "Organize", false, null),
    { store: plain, paths: [plainLog.path], move: false, stated_robot: null });
});

test("a copy that creates a store does not offer its retained originals again; new files do", () => {
  const accepted = rememberChoice(empty, offerFor(simple, plain, empty)!, "Organize");
  const copied = { log_directories: [plain], stores: [{ path: plain }], unmanaged: [{ store: plain, path: plainLog.path }] };
  assert.equal(offerFor(copied, plain, accepted), undefined);
  assert.equal(offerFor({ ...simple, logs: [plainLog, { ...plainLog, path: path.join(plain, "more.wpilog") }] }, plain, accepted), undefined);
  assert.equal(offerFor({ ...copied, unmanaged: [...copied.unmanaged, { store: plain, path: path.join(plain, "new.wpilog") }] }, plain, accepted)?.count, 2);
});

test("origin-bearing directories and the compatibility path list still offer organizing", () => {
  const origins = { ...simple, log_directories: [{ path: plain, origin: "leased" as const, team: 11 }] };
  assert.deepEqual(organizeFolders(origins), [plain]);
  assert.equal(offerFor(origins, plain, empty)?.count, 1);
  assert.deepEqual(organizeFolders({ log_directory_paths: [plain] }), [plain]);
});

test("a mirror and its unmanaged files are never offered for organizing", () => {
  const folder = path.resolve("mirror");
  const listing = { log_directories: [folder], stores: [{ path: folder, mirror: true }], unmanaged: [{ store: folder, path: path.join(folder, "stray.wpilog") }] };
  assert.deepEqual(organizeFolders(listing), []);
  assert.equal(offerFor(listing, folder, { never: [], deferred: [], offered: {} }, true), undefined);
});
