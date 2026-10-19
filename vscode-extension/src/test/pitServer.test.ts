import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "node:path";
import { ageText, liveRequest, mirrorRequest, mirroredCopy, mirrorStatusText, peerUrl, pitEndpoint, pitLogTree, pitOverview, readRemoteLogUri, remoteLogUri, serverDefinitions, sessionPicks } from "../explorer/pitServer";
import { ListedLog } from "../explorer/logsTree";

/** A link as VS Code hands it back, after the native editor showed it: Uri.parse decodes each run of
 * %XX in the query once, gracefully as VS Code does, and Uri.toString encodes the query's = and &,
 * so a reader of the printed link finds neither field. */
function editorUri(link: string): { scheme: string; path: string; query: string; toString(): string } {
  const graceful = (run: string): string => { try { return decodeURIComponent(run); } catch { return run.length > 3 ? run.slice(0, 3) + graceful(run.slice(3)) : run; } };
  const decode = (text: string) => text.replace(/(%[0-9A-Za-z][0-9A-Za-z])+/g, graceful);
  const match = /^([^:/?#]+):([^?#]*)(?:\?([^#]*))?/.exec(link)!;
  const scheme = match[1], uriPath = decode(match[2]), query = decode(match[3] ?? "");
  return { scheme, path: uriPath, query, toString: () => `${scheme}:${encodeURI(uriPath)}${query ? "?" + encodeURIComponent(query) : ""}` };
}

test("a pit URL adds a second definition even while the local daemon is unavailable", () => {
  assert.deepEqual(serverDefinitions("http://127.0.0.1:2363/mcp", "http://pit:2363"), [
    { label: "WPILog Analyzer", url: "http://127.0.0.1:2363/mcp" }, { label: "WPILog Pit Server", url: "http://pit:2363/mcp" },
  ]);
  assert.deepEqual(serverDefinitions(undefined, "http://pit:2363"), [{ label: "WPILog Pit Server", url: "http://pit:2363/mcp" }]);
  assert.equal(pitEndpoint("  "), undefined);
  for (const bad of ["ssh://pit", "http://user:secret@pit", "http://pit/#fragment", "http://pit/?key=x"]) assert.throws(() => pitEndpoint(bad));
});
test("mirror and live capture require explicit opt-in even with a configured pit", () => {
  assert.equal(mirrorRequest("http://pit:2363", {}, "/storage", path.posix), undefined);
  assert.equal(mirrorRequest(undefined, {}, "/storage", path.posix), undefined);
  assert.equal(mirrorRequest("http://pit:2363", { enabled: false }, "/storage", path.posix), undefined);
  assert.deepEqual(mirrorRequest("http://pit:2363/mcp", { enabled: true }, "/storage", path.posix), {
    origin: "http://pit:2363", folder: "/storage/mirror", days: 14, max_size_gb: 20, robots: [], events: [], interval_sec: 30,
  });
  const settings = { enabled: true, folder: "D:\\Logs\\Mirror", days: 4, maxSizeGb: 2.5, robots: ["RIO"], events: ["District"], intervalSec: 12 };
  assert.deepEqual(mirrorRequest("https://pit/mcp", settings, "C:\\Storage", path.win32), {
    origin: "https://pit", folder: "D:\\Logs\\Mirror", days: 4, max_size_gb: 2.5, robots: ["RIO"], events: ["District"], interval_sec: 12,
  });
  for (const value of [{ folder: "relative" }, { days: -1 }, { days: 2.2 }, { maxSizeGb: 0 }, { intervalSec: 0 }]) assert.throws(() => mirrorRequest("http://pit", { ...value, enabled: true }, "/storage", path.posix));
});
test("the live source records the pit's gateway on the pit's own host, into a laptop folder", () => {
  assert.equal(liveRequest("http://pit:2363", {}, "/storage", path.posix), undefined);
  assert.equal(liveRequest(undefined, {}, "/storage", path.posix), undefined);
  assert.equal(liveRequest("http://pit:2363", { enabled: false }, "/storage", path.posix), undefined);
  assert.deepEqual(liveRequest("http://pit:2363/mcp", { enabled: true }, "/storage", path.posix), { robot: { host: "pit", port: 5810 }, store: "/storage/live" });
  // A proxy prefix and port belong to HTTP; the gateway listens on the pit's host on its own port.
  assert.deepEqual(liveRequest("https://pit.example:8443/pit/mcp", { enabled: true, gatewayPort: 5811 }, "/storage", path.posix),
    { robot: { host: "pit.example", port: 5811 }, store: "/storage/live" });
  assert.deepEqual(liveRequest("http://[fd00::2]:2363/mcp", { enabled: true }, "/storage", path.posix), { robot: { host: "fd00::2", port: 5810 }, store: "/storage/live" });
  assert.deepEqual(liveRequest("http://10.23.63.5:2363", { enabled: true, folder: "D:\\Logs\\Live" }, "C:\\Storage", path.win32),
    { robot: { host: "10.23.63.5", port: 5810 }, store: "D:\\Logs\\Live" });
  for (const value of [{ folder: "relative" }, { gatewayPort: 0 }, { gatewayPort: 65536 }, { gatewayPort: 5810.5 }]) assert.throws(() => liveRequest("http://pit", { ...value, enabled: true }, "/storage", path.posix));
});
test("status distinguishes synchronization, remaining bytes, offline age and incomplete work", () => {
  assert.equal(mirrorStatusText({ state: "synchronized" }), "$(check) Mirror synchronized");
  assert.equal(mirrorStatusText({ state: "synchronizing", remaining_files: 3, remaining_bytes: 2048 }), "$(sync~spin) Mirror: 3 files, 2.0 KB remaining");
  assert.equal(mirrorStatusText({ state: "offline", age_sec: 7200 }), "$(cloud-offline) Mirror offline · 2 h ago");
  assert.equal(mirrorStatusText({ state: "offline", age_sec: null }), "$(cloud-offline) Mirror offline · never synchronized");
  assert.equal(mirrorStatusText({ state: "partial" }), "$(warning) Mirror needs attention");
  assert.equal(ageText(59), "59 s ago"); assert.equal(ageText(60), "1 min ago"); assert.equal(ageText(86400), "1 d ago");
});
function log(id: string, started: string, file = "capture.wpilog"): ListedLog {
  return { path: `/origin/${id}/${file}`, filename: file.split("/").pop()!, friendly_name: id, robot: { id: "RIO", basis: "device", name: "Practice" },
    session: { id, path: `/origin/${id}`, started_at: started, ended_at: started, start_basis: "logged" } };
}
test("pin picks deduplicate session IDs, order recent first and offer only pinned unpin choices", () => {
  const old = log("old", "2026-01-01"), recent = log("new", "2026-01-02");
  const listing = { logs: [old, recent, { ...recent, path: "/origin/new/second.wpilog" }] };
  assert.deepEqual(sessionPicks(listing, ["old"]).map(p => [p.id, p.pinned]), [["new", false], ["old", true]]);
  assert.deepEqual(sessionPicks(listing, ["old"], true).map(p => p.id), ["old"]);
  assert.match(sessionPicks(listing, ["old"], true)[0].description, /pinned/);
});
test("remote links keep the server, exact path and session through VS Code's own parsing", () => {
  const session = { id: "s&1=2", path: "/store/R&D bot/s&1=2", started_at: "", ended_at: "", start_basis: "", open: false };
  for (const file of ["/logs/München #1/capture.wpilog", "D:\\Logs\\Match 7\\capture.wpilog", "/store/R&D bot/a=b+c 100% ?x/capture.wpilog", "/store/%41%zz/capture.wpilog"]) {
    assert.deepEqual(readRemoteLogUri(editorUri(remoteLogUri("http://pit:2363", file))), { url: "http://pit:2363/mcp", path: file });
    assert.deepEqual(readRemoteLogUri(editorUri(remoteLogUri("http://pit:2363", file, session))), { url: "http://pit:2363/mcp", path: file, session });
  }
  // A link VS Code saved for an editor before this release still opens when its path is ordinary.
  const saved = "wpilog-pit:/capture.wpilog?url=http%3A%2F%2Fpit%3A2363%2Fmcp&path=%2Fstore%2FMatch+7%2Fcapture.wpilog";
  assert.deepEqual(readRemoteLogUri(editorUri(saved)), { url: "http://pit:2363/mcp", path: "/store/Match 7/capture.wpilog" });
  assert.equal(readRemoteLogUri(editorUri("file:///logs/capture.wpilog")), undefined);
  assert.throws(() => readRemoteLogUri(editorUri("wpilog-pit:/missing.wpilog")));
});
test("offline fallback needs origin, session ID and relative file path, never just a basename", () => {
  const remote = log("boot", "2026-01-01", "peer/hash/capture.wpilog");
  const local: ListedLog = { ...remote, path: "C:\\mirror\\boot\\peer\\hash\\capture.wpilog", session: { ...remote.session!, path: "C:\\mirror\\boot", origin: "http://pit:2363" } };
  const wrong: ListedLog = { ...local, path: "C:\\mirror\\boot\\capture.wpilog" };
  assert.equal(mirroredCopy(remote, { logs: [wrong, local] }, "http://pit:2363"), local);
  assert.equal(mirroredCopy(remote, { logs: [local] }, "http://other:2363"), undefined);
  assert.equal(mirroredCopy({ ...remote, session: { ...remote.session!, id: "different" } }, { logs: [local] }, "http://pit:2363"), undefined);
});
test("a laptop address is normalized once for remembered-peer quick picks", () => {
  assert.equal(peerUrl("laptop:2363"), "http://laptop:2363");
  assert.equal(peerUrl("http://laptop:2363/store?store=abc"), "http://laptop:2363?store=abc");
  assert.equal(peerUrl("[::1]:2363"), "http://[::1]:2363");
  for (const bad of ["http://user:secret@laptop", "file:///store", "http://host?x=1", "http://host?store=a&store=b"]) assert.throws(() => peerUrl(bad));
});


test("pit sessions are newest first, show open and mirrored state, and filter without local path interpretation", () => {
  const old = log("old", "2026-01-01"), recent = log("new", "2026-01-02"); recent.session!.open = true;
  const copy = { ...recent, path: "/mirror/new/capture.wpilog", session: { ...recent.session!, path: "/mirror/new", origin: "http://pit" } };
  const nodes = pitLogTree({ logs: [old, recent] }, { logs: [copy] }, "http://pit");
  assert.match(nodes[0].label, /2026-01-02.*open.*mirrored/); assert.match(nodes[1].label, /2026-01-01.*closed/);
  assert.equal(pitLogTree({ logs: [old, recent] }, {}, "http://pit", "new").length, 1);
  assert.match(pitLogTree({ status: "error", error: "connection refused" }, {}, "http://pit")[0].label, /offline/);
  const parsed = readRemoteLogUri(editorUri(remoteLogUri("http://pit", recent.path, recent.session)))!;
  assert.equal(parsed.session!.id, "new"); assert.equal(parsed.session!.path, "/origin/new");
  assert.equal(mirroredCopy({ ...recent, session: parsed.session }, { logs: [copy] }, "http://pit"), copy);
});


test("remote documents retain open-session state and pit sessions include their REV companions", () => {
  const capture = log("boot", "2026-01-01"); capture.session!.open = true;
  capture.revlogs = [{ path: "/origin/boot/motors.revlog", filename: "motors.revlog" }];
  assert.equal(readRemoteLogUri(editorUri(remoteLogUri("http://pit", capture.path, capture.session)))!.session!.open, true);
  capture.session!.open = false;
  assert.equal(readRemoteLogUri(editorUri(remoteLogUri("http://pit", capture.path, capture.session)))!.session!.open, false);
  const tree = pitLogTree({ logs: [capture] }, {}, "http://pit");
  assert.equal("children" in tree[0] && tree[0].children.length, 2);
  const companion = "children" in tree[0] && tree[0].children[1];
  assert.ok(companion && companion.kind === "log");
  assert.equal(companion.log.wpilog, capture.path); assert.equal(companion.log.session!.id, "boot");
});

/** A pit session from 10:00 to 10:05 on robot SER-1: its capture, and the robot's own log the pit pulled. */
function pitFiles(): { capture: ListedLog; pulled: ListedLog } {
  const session = { id: "boot", path: "/pit/robots/SER-1/sessions/2026-10-17/100000Z", started_at: "2026-10-17T10:00:00Z", ended_at: "2026-10-17T10:05:00Z", start_basis: "pit_clock" };
  const robot = { id: "SER-1", basis: "device", serial_number: "SER-1" };
  return {
    capture: { path: session.path + "/capture.wpilog", filename: "capture.wpilog", friendly_name: "capture", robot, session: { ...session, open: false } },
    pulled: { path: session.path + "/robot/FRC_20261017.wpilog", filename: "FRC_20261017.wpilog", friendly_name: "robot", robot, session },
  };
}
const ORIGIN = "http://pit:2363", NOW = Date.parse("2026-10-17T12:00:00Z");
const MIRROR = { folder: "/laptop/mirror" };
/** The mirror's copy of a pit file: the same session ID and path within the session, under the mirror. */
function mirrored(remote: ListedLog): ListedLog {
  const copy = "/laptop/mirror/robots/SER-1/sessions/2026-10-17/100000Z";
  return { ...remote, store: "/laptop/mirror", path: copy + remote.path.slice(remote.session!.path.length), session: { ...remote.session!, path: copy, origin: ORIGIN } };
}
/** This laptop's own recording of the pit's gateway: a captured log in its live store. */
function laptopCapture(started: string, ended: string, serial = "SER-1", id = "laptop-" + started): ListedLog {
  return { path: `/laptop/live/robots/${serial}/sessions/${id}/capture.wpilog`, filename: "capture.wpilog", friendly_name: id, store: "/laptop/live",
    robot: { id: serial, basis: "device", serial_number: serial },
    session: { id, path: `/laptop/live/robots/${serial}/sessions/${id}`, started_at: started, ended_at: ended, start_basis: "pit_clock", open: false } };
}

test("the overview of a file the pit pulled opens the mirror's copy, or says why there is none and how far the mirror is", () => {
  const { pulled } = pitFiles(); const copy = mirrored(pulled);
  assert.deepEqual(pitOverview(pulled, { logs: [copy] }, ORIGIN, { state: "synchronized", config: MIRROR }, NOW), { path: copy.path });
  const missing = (state: Parameters<typeof pitOverview>[3]) => pitOverview(pulled, { logs: [] }, ORIGIN, state, NOW);
  assert.deepEqual(missing({ state: "synchronizing", remaining_files: 3, remaining_bytes: 2048 }), {
    message: "The mirror has not copied FRC_20261017.wpilog yet: 3 files, 2.0 KB remaining.", actions: [{ title: "Mirror Status", command: "wpilog-mcp.mirrorActions" }] });
  const done = missing({ state: "synchronized", age_sec: 120 }) as { message: string; actions: { command: string }[] };
  assert.match(done.message, /arrived after the last pass \(2 min ago\)/); assert.deepEqual(done.actions.map(a => a.command), ["wpilog-mcp.pinSession", "wpilog-mcp.mirrorActions"]);
  assert.match((missing({ state: "offline", age_sec: 7200 }) as { message: string }).message, /cannot reach the pit \(last synchronized 2 h ago\)/);
  assert.match((missing({ state: "partial", result: { reason: "disk full" } }) as { message: string }).message, /needs attention, so FRC_20261017.wpilog may not be copied: disk full\./);
  assert.deepEqual(missing({ state: "disabled" }), { message: "FRC_20261017.wpilog is not on this laptop: the mirror is off. Turn on wpilog-mcp.mirror.enabled to copy the pit's logs.", actions: [] });
  // A copy from another pit, or of another session, is not this file's.
  assert.equal("path" in pitOverview(pulled, { logs: [{ ...copy, session: { ...copy.session!, origin: "http://other:2363" } }] }, ORIGIN, { state: "synchronized" }, NOW), false);
});

test("the overview of the pit's capture opens this laptop's own recording of that session, never a guess", () => {
  const { capture } = pitFiles();
  const longer = laptopCapture("2026-10-17T09:58:00Z", "2026-10-17T10:04:00Z"), shorter = laptopCapture("2026-10-17T10:04:00Z", "2026-10-17T10:30:00Z");
  const opened = pitOverview(capture, { logs: [shorter, longer] }, ORIGIN, { state: "synchronized", config: MIRROR }, NOW) as { path: string; note: string };
  assert.equal(opened.path, longer.path, "four minutes of overlap outrank one");
  assert.match(opened.note, /stays on the pit\. This is this laptop's own recording of the same robot at that time/);
  const none = { message: "capture.wpilog is the pit's capture, which stays on the pit: the mirror copies the logs the pit pulled, not its captures. This laptop has no recording of that session from the pit's gateway.", actions: [] };
  // Another robot, a session that does not overlap, an unknown serial, a REV log, or a file in the mirror's folder is not it.
  for (const other of [laptopCapture("2026-10-17T10:00:00Z", "2026-10-17T10:05:00Z", "SER-2"),
    laptopCapture("2026-10-17T10:05:00Z", "2026-10-17T10:30:00Z"),
    { ...longer, robot: { id: "unknown", basis: "address" } },
    { ...longer, kind: "revlog" as const },
    { ...longer, store: "/laptop/mirror/" }]) {
    assert.deepEqual(pitOverview(capture, { logs: [other] }, ORIGIN, { state: "synchronized", config: MIRROR }, NOW), none, JSON.stringify(other));
  }
  // A capture still recording runs until now, so a laptop recording that is still going overlaps it.
  const open = { ...capture, session: { ...capture.session!, ended_at: "2026-10-17T10:00:01Z", open: true } };
  const live = laptopCapture("2026-10-17T11:00:00Z", "2026-10-17T11:00:01Z"); live.session!.open = true; live.session!.open_capture = live.path;
  assert.equal((pitOverview(open, { logs: [live] }, ORIGIN, { state: "synchronized", config: MIRROR }, NOW) as { path: string }).path, live.path);
  // The copy an earlier version of the mirror made of a capture stays readable, and says what it is.
  const earlier = mirrored(capture);
  const copied = pitOverview(capture, { logs: [earlier, longer] }, ORIGIN, { state: "synchronized", config: MIRROR }, NOW) as { path: string; note: string };
  assert.equal(copied.path, earlier.path); assert.match(copied.note, /earlier version of the mirror made of the pit's capture capture\.wpilog; it is no longer refreshed/);
});

test("a rolled-over recording selects its manifest's open member in every listing order", () => {
  const { capture } = pitFiles();
  const member = laptopCapture("2026-10-17T09:58:00Z", "2026-10-17T10:04:00Z");
  const current = member.session!.path + "/capture-3.wpilog";
  const members = ["capture.wpilog", "capture-2.wpilog", "capture-3.wpilog"].map(filename => ({ ...member, filename,
    path: member.session!.path + "/" + filename, session: { ...member.session!, open: true, open_capture: current } }));
  for (const logs of [members, [...members].reverse(), [members[1], members[0], members[2]]]) {
    const result = pitOverview(capture, { logs }, ORIGIN, { state: "synchronized", config: MIRROR }, NOW);
    assert.equal((result as { path: string }).path, current, "session.open is shared by closed rollover members");
  }
  for (const logs of [members.slice(0, 2), members.map(log => ({ ...log, session: { ...member.session!, open: true } }))]) {
    const result = pitOverview(capture, { logs }, ORIGIN, { state: "synchronized", config: MIRROR }, NOW);
    assert.ok("message" in result, "a missing current member cannot fall back to a closed one");
    assert.match(result.message, /current recording.*Refresh/i);
  }
});
test("the current member uses Windows path equality and stays within its selected store and session", () => {
  const { capture } = pitFiles(), member = laptopCapture("2026-10-17T09:58:00Z", "2026-10-17T10:04:00Z");
  const root = path.win32.join("C:\\", "live"), folder = path.win32.join(root, "session");
  const current = path.win32.join(folder, "capture-3.wpilog");
  const session = { ...member.session!, path: folder, open: true, open_capture: current.toUpperCase().replace(/\\/g, "/") };
  const first = { ...member, store: root, path: path.win32.join(folder, "capture-2.wpilog"), session };
  const last = { ...first, path: current };
  const resolve = (logs: ListedLog[]) => pitOverview(capture, { logs }, ORIGIN, { state: "disabled" }, NOW, path.win32);
  assert.equal((resolve([first, last]) as { path: string }).path, current);
  for (const other of [{ ...last, store: path.win32.join("D:\\", "other") }, { ...last, session: { ...session, id: "other" } }])
    assert.ok("message" in resolve([first, other]), "another store or session cannot supply the open member");
});
