// Tests for the log directories passed to the server (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as os from "os";
import * as path from "path";
import {
  addLogDirectories,
  combineLogDirectories,
  expandTilde,
  overlaySettings,
  projectConfigName,
} from "../logDirectories";

test("the main directory comes first, then the additional ones, each once", () => {
  assert.deepEqual(combineLogDirectories("/logs", ["/archive", "/logs", "/usb", "/archive"]),
    ["/logs", "/archive", "/usb"]);
});

test("no main directory, or no additional ones", () => {
  assert.deepEqual(combineLogDirectories(undefined, ["/archive"]), ["/archive"]);
  assert.deepEqual(combineLogDirectories("/logs", undefined), ["/logs"]);
  assert.deepEqual(combineLogDirectories("/logs", []), ["/logs"]);
  assert.deepEqual(combineLogDirectories(undefined, undefined), []);
});

test("entries that are not non-blank strings are ignored; a non-array setting is ignored", () => {
  assert.deepEqual(combineLogDirectories("", ["", "  ", 7, null, {}, " /usb "]), ["/usb"]);
  assert.deepEqual(combineLogDirectories("/logs", "/not-an-array"), ["/logs"]);
});

test("a leading tilde is the home directory", () => {
  assert.equal(expandTilde("~/riologs"), path.join(os.homedir(), "riologs"));
  assert.equal(expandTilde("~"), os.homedir());
  assert.equal(expandTilde("/a/~/b"), "/a/~/b");
  assert.deepEqual(combineLogDirectories("~/riologs", ["~/riologs"]),
    [path.join(os.homedir(), "riologs")]);
});

test("each directory is a -logdir, and WPILOG_DIR joins them with the delimiter", () => {
  const args: string[] = ["-jar", "x.jar"];
  const env: Record<string, string> = {};
  addLogDirectories(args, env, ["/a", "/b"], ":");
  assert.deepEqual(args, ["-jar", "x.jar", "-logdir", "/a", "-logdir", "/b"]);
  assert.deepEqual(env, { WPILOG_DIR: "/a:/b" });

  const winEnv: Record<string, string> = {};
  addLogDirectories([], winEnv, ["C:\\logs", "D:\\usb"], ";");
  assert.equal(winEnv["WPILOG_DIR"], "C:\\logs;D:\\usb");
});

test("no directories adds nothing", () => {
  const args: string[] = [];
  const env: Record<string, string> = {};
  addLogDirectories(args, env, []);
  assert.deepEqual(args, []);
  assert.deepEqual(env, {});
});

test("a relative path is a folder inside the project", () => {
  assert.deepEqual(combineLogDirectories("logs", ["/archive"], ["/robot"]),
    [path.join("/robot", "logs"), "/archive"]);
  assert.deepEqual(combineLogDirectories(undefined, ["./sim/logs"], ["/robot"]),
    [path.join("/robot", "sim", "logs")]);
});

test("a relative path names a folder in each project, and nothing without one", () => {
  assert.deepEqual(combineLogDirectories(undefined, ["logs"], ["/a", "/b"]),
    [path.join("/a", "logs"), path.join("/b", "logs")]);
  assert.deepEqual(combineLogDirectories("logs", ["/archive"]), ["/archive"]);
  assert.deepEqual(combineLogDirectories("logs", ["/robot/logs"], ["/robot"]),
    [path.join("/robot", "logs")], "the same folder twice is listed once");
});

test("a project's own settings override the user's; the rest come from the user", () => {
  const user = { logDirectory: "/riologs", additionalLogDirectories: ["/archive"], teamNumber: 2363 };
  assert.deepEqual(overlaySettings(user, {}), user);
  assert.deepEqual(overlaySettings(user, { logDirectory: "logs" }),
    { logDirectory: "logs", additionalLogDirectories: ["/archive"], teamNumber: 2363 });
  assert.deepEqual(overlaySettings(user, { additionalLogDirectories: ["logs"], teamNumber: 1 }),
    { logDirectory: "/riologs", additionalLogDirectories: ["logs"], teamNumber: 1 },
    "a project's list replaces the user's, as VS Code does");
  assert.deepEqual(overlaySettings({}, {}),
    { logDirectory: undefined, additionalLogDirectories: undefined, teamNumber: undefined });
});

test("each project has its own configuration file, named the same every time", () => {
  const a = projectConfigName("/th/Rebuilt");
  assert.match(a, /^[0-9a-f]{16}\.json$/);
  assert.equal(projectConfigName("/th/Rebuilt"), a);
  assert.equal(projectConfigName("/th/Rebuilt/"), a, "a trailing separator is the same folder");
  assert.notEqual(projectConfigName("/th/Rebuilt2025"), a);
});
