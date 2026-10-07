import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "node:path";
import { ageText, mirrorRequest, mirroredCopy, mirrorStatusText, peerUrl, pitEndpoint, pitLogTree, readRemoteLogUri, remoteLogUri, serverDefinitions, sessionPicks } from "../explorer/pitServer";
import { ListedLog } from "../explorer/logsTree";

test("a pit URL adds a second definition even while the local daemon is unavailable", () => {
  assert.deepEqual(serverDefinitions("http://127.0.0.1:2363/mcp", "http://pit:2363"), [
    { label: "WPILog Analyzer", url: "http://127.0.0.1:2363/mcp" }, { label: "WPILog Pit Server", url: "http://pit:2363/mcp" },
  ]);
  assert.deepEqual(serverDefinitions(undefined, "http://pit:2363"), [{ label: "WPILog Pit Server", url: "http://pit:2363/mcp" }]);
  assert.equal(pitEndpoint("  "), undefined);
  for (const bad of ["ssh://pit", "http://user:secret@pit", "http://pit/#fragment", "http://pit/?key=x"]) assert.throws(() => pitEndpoint(bad));
});
test("mirror defaults activate only with a pit and the person's explicit off wins", () => {
  assert.equal(mirrorRequest(undefined, {}, "/storage", path.posix), undefined);
  assert.equal(mirrorRequest("http://pit:2363", { enabled: false }, "/storage", path.posix), undefined);
  assert.deepEqual(mirrorRequest("http://pit:2363/mcp", {}, "/storage", path.posix), {
    origin: "http://pit:2363", folder: "/storage/mirror", days: 14, max_size_gb: 20, robots: [], events: [], interval_sec: 30,
  });
  const settings = { folder: "D:\\Logs\\Mirror", days: 4, maxSizeGb: 2.5, robots: ["RIO"], events: ["District"], intervalSec: 12 };
  assert.deepEqual(mirrorRequest("https://pit/mcp", settings, "C:\\Storage", path.win32), {
    origin: "https://pit", folder: "D:\\Logs\\Mirror", days: 4, max_size_gb: 2.5, robots: ["RIO"], events: ["District"], interval_sec: 12,
  });
  for (const value of [{ folder: "relative" }, { days: -1 }, { days: 2.2 }, { maxSizeGb: 0 }, { intervalSec: 0 }]) assert.throws(() => mirrorRequest("http://pit", value, "/storage", path.posix));
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
test("remote links preserve the server and exact path across Windows, Unix and special characters", () => {
  for (const file of ["/logs/München #1/capture.wpilog", "D:\\Logs\\Match 7\\capture.wpilog"]) {
    assert.deepEqual(readRemoteLogUri(remoteLogUri("http://pit:2363", file)), { url: "http://pit:2363/mcp", path: file });
  }
  assert.equal(readRemoteLogUri("file:///logs/capture.wpilog"), undefined);
  assert.throws(() => readRemoteLogUri("wpilog-pit:/missing.wpilog"));
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
  const parsed = readRemoteLogUri(remoteLogUri("http://pit", recent.path, recent.session))!;
  assert.equal(parsed.session!.id, "new"); assert.equal(parsed.session!.path, "/origin/new");
  assert.equal(mirroredCopy({ ...recent, session: parsed.session }, { logs: [copy] }, "http://pit"), copy);
});


test("remote documents retain open-session state and pit sessions include their REV companions", () => {
  const capture = log("boot", "2026-01-01"); capture.session!.open = true;
  capture.revlogs = [{ path: "/origin/boot/motors.revlog", filename: "motors.revlog" }];
  assert.equal(readRemoteLogUri(remoteLogUri("http://pit", capture.path, capture.session))!.session!.open, true);
  capture.session!.open = false;
  assert.equal(readRemoteLogUri(remoteLogUri("http://pit", capture.path, capture.session))!.session!.open, false);
  const tree = pitLogTree({ logs: [capture] }, {}, "http://pit");
  assert.equal("children" in tree[0] && tree[0].children.length, 2);
  const companion = "children" in tree[0] && tree[0].children[1];
  assert.ok(companion && companion.kind === "log");
  assert.equal(companion.log.wpilog, capture.path); assert.equal(companion.log.session!.id, "boot");
});
