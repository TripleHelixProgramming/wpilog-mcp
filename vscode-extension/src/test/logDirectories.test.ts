// Tests for the log directories passed to the server (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as os from "os";
import * as path from "path";
import {
  combineLogDirectories,
  expandTilde,
  overlaySettings,
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

test("on Windows, the same folder written with / or \\, or in another case, is listed once", () => {
  // Found by CI on Windows: a relative folder resolved inside the project has backslashes, while
  // the same folder given as an absolute path kept its forward slashes, so it was listed twice
  const w = path.win32;
  assert.deepEqual(combineLogDirectories("logs", ["C:/robot/logs"], ["C:\\robot"], w), ["C:\\robot\\logs"]);
  assert.deepEqual(combineLogDirectories("C:\\Robot\\Logs", ["c:/robot/logs/", "D:/archive"], [], w),
    ["C:\\Robot\\Logs", "D:/archive"], "each is passed as written, the first spelling of a folder");
  assert.deepEqual(combineLogDirectories("C:/robot/sim/../logs", ["C:\\robot\\logs"], [], w),
    ["C:/robot/sim/../logs"]);
});

test("on macOS and Linux, a trailing slash or .. is the same folder, but case is not", () => {
  const p = path.posix;
  assert.deepEqual(combineLogDirectories("/robot/logs/", ["/robot/logs", "/robot/x/../logs"], [], p),
    ["/robot/logs/"]);
  assert.deepEqual(combineLogDirectories("/Logs", ["/logs"], [], p), ["/Logs", "/logs"]);
  assert.deepEqual(combineLogDirectories("/", ["//"], [], p), ["/"], "a root keeps its slash");
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
