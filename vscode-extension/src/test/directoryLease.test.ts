import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "node:path";
import { directoryRegistration, leaseChanged, LEASE_SETTINGS, SessionRegistration } from "../directoryLease";

test("leases combine User roots and open projects with a team per path", () => {
  const user = { logDirectory: "/team", additionalLogDirectories: ["/archive", "sim"], teamNumber: 1111 };
  const projects = [
    { folderPath: "/p", own: { logDirectory: "logs", teamNumber: 2222 } },
    { folderPath: "/q", own: { logDirectory: "recordings", additionalLogDirectories: [], teamNumber: 3333 } },
  ];
  assert.deepEqual(directoryRegistration(user, projects, path.posix), { team: 1111, paths: [
    { path: "/team", team: 1111 }, { path: "/archive", team: 1111 },
    { path: "/p/logs", team: 2222 }, { path: "/p/sim", team: 2222 }, { path: "/q/recordings", team: 3333 },
  ] });
  assert.deepEqual(directoryRegistration(user, [], path.posix).paths, [{ path: "/team", team: 1111 }, { path: "/archive", team: 1111 }]);
});

test("Windows aliases count once, explicit project teams win, and no team is null", () => {
  assert.deepEqual(directoryRegistration({ logDirectory: "C:\\Logs" }, [
    { folderPath: "D:\\Robot", own: { additionalLogDirectories: ["c:/logs/", "sim"], teamNumber: 2363 } },
  ], path.win32), { team: null, paths: [{ path: "C:\\Logs", team: 2363 }, { path: "D:\\Robot\\sim", team: 2363 }] });
  assert.deepEqual(directoryRegistration({ teamNumber: NaN }, []), { team: null, paths: [] });
  assert.deepEqual(directoryRegistration({ logDirectory: "logs" }, [{ folderPath: "/p", own: {} }], path.posix),
    { team: null, paths: [{ path: "/p/logs", team: null }] });
});

test("a lease refresh follows directories, team, key, removal, and session replacement, not unrelated settings", () => {
  const before: SessionRegistration = { directories: { paths: [{ path: "/logs", team: 2363 }], team: null }, key: "synthetic" };
  assert.equal(leaseChanged(undefined, before), true);
  assert.equal(leaseChanged(before, structuredClone(before)), false);
  assert.equal(leaseChanged(before, before, true), true);
  assert.equal(leaseChanged(before, { ...before, key: null }), true);
  assert.equal(leaseChanged(before, { ...before, key: "replacement" }), true);
  assert.equal(leaseChanged(before, { ...before, directories: { paths: [], team: null } }), true);
  assert.equal(leaseChanged(before, { ...before, directories: { paths: [{ path: "/logs", team: 9999 }], team: null } }), true);
  assert.deepEqual(LEASE_SETTINGS, ["wpilog-mcp.logDirectory", "wpilog-mcp.additionalLogDirectories", "wpilog-mcp.teamNumber"]);
});
