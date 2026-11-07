// Tests for the TBA API key field: what happens to a key found in the settings (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as path from "path";
import { TBA_KEY_SETTING, planTbaKeyMove } from "../tbaKey";

const KEY = "a".repeat(64);
const OTHER = "b".repeat(64);
const FILE = ".vscode/settings.json";

test("nothing in the settings: nothing to do", () => {
  assert.deepEqual(planTbaKeyMove({ storedKey: KEY, workspaceSettingsName: FILE }), {
    clearUser: false,
    clearWorkspace: false,
    offerRevoke: false,
  });
});

test("a key entered in the user's settings is stored and cleared, with nothing stored before", () => {
  const move = planTbaKeyMove({ userValue: KEY, workspaceSettingsName: FILE });
  assert.equal(move.store, KEY);
  assert.equal(move.clearUser, true);
  assert.equal(move.clearWorkspace, false);
  assert.equal(move.offerRevoke, false);
  assert.match(move.message ?? "", /saved in VS Code's secret storage and cleared from your settings/);
});

test("a key entered in the user's settings replaces a different stored key", () => {
  const move = planTbaKeyMove({ userValue: OTHER, storedKey: KEY, workspaceSettingsName: FILE });
  assert.equal(move.store, OTHER);
  assert.equal(move.clearUser, true);
});

test("the stored key entered again is cleared without storing it twice", () => {
  const move = planTbaKeyMove({ userValue: KEY, storedKey: KEY, workspaceSettingsName: FILE });
  assert.equal(move.store, undefined);
  assert.equal(move.clearUser, true);
  assert.match(move.message ?? "", /already saved/);
});

test("spaces around a pasted key are not part of it", () => {
  const move = planTbaKeyMove({ userValue: `  ${KEY}\n`, workspaceSettingsName: FILE });
  assert.equal(move.store, KEY);
});

test("an empty value, or one that is not text, is cleared without storing anything or a message", () => {
  for (const userValue of ["", "   ", 42, null, ["x"], { key: KEY }]) {
    const move = planTbaKeyMove({ userValue, storedKey: KEY, workspaceSettingsName: FILE });
    assert.equal(move.store, undefined, `value ${JSON.stringify(userValue)}`);
    assert.equal(move.clearUser, true, `value ${JSON.stringify(userValue)}`);
    assert.equal(move.message, undefined, `value ${JSON.stringify(userValue)}`);
  }
});

test("a key in a workspace's settings is stored only when no key is, and revoking it is offered", () => {
  const move = planTbaKeyMove({ workspaceValue: KEY, workspaceSettingsName: FILE });
  assert.equal(move.store, KEY);
  assert.equal(move.clearWorkspace, true);
  assert.equal(move.clearUser, false);
  assert.equal(move.offerRevoke, true);
  assert.match(move.message ?? "", /\.vscode\/settings\.json/);
  assert.match(move.message ?? "", /now kept in VS Code's secret storage/);
  assert.match(move.message ?? "", /revoke the key/);
});

test("a key in a workspace's settings never replaces the stored key: it may be a teammate's", () => {
  const move = planTbaKeyMove({ workspaceValue: OTHER, storedKey: KEY, workspaceSettingsName: FILE });
  assert.equal(move.store, undefined);
  assert.equal(move.clearWorkspace, true);
  assert.equal(move.offerRevoke, true);
  assert.match(move.message ?? "", /not saved, and the key already in VS Code's secret storage is still used/);
});

test("the stored key found in a workspace's settings is removed from them, and revoking is offered", () => {
  const move = planTbaKeyMove({ workspaceValue: KEY, storedKey: KEY, workspaceSettingsName: FILE });
  assert.equal(move.store, undefined);
  assert.equal(move.clearWorkspace, true);
  assert.equal(move.offerRevoke, true);
  assert.match(move.message ?? "", /the same key is in VS Code's secret storage/);
});

test("with keys in both settings, the user's key is stored and both are cleared", () => {
  const move = planTbaKeyMove({ userValue: KEY, workspaceValue: OTHER, workspaceSettingsName: FILE });
  assert.equal(move.store, KEY);
  assert.equal(move.clearUser, true);
  assert.equal(move.clearWorkspace, true);
  assert.equal(move.offerRevoke, true);
  assert.match(move.message ?? "", /saved in VS Code's secret storage/);
  assert.match(move.message ?? "", /not saved, and the key from your user settings is used/);
});

test("the same key in both settings is stored once, and the workspace copy is reported as the same", () => {
  const move = planTbaKeyMove({ userValue: KEY, workspaceValue: KEY, workspaceSettingsName: FILE });
  assert.equal(move.store, KEY);
  assert.match(move.message ?? "", /the same key is in VS Code's secret storage/);
});

test("the workspace's settings file is named as the caller gives it, such as a .code-workspace file", () => {
  const move = planTbaKeyMove({ workspaceValue: KEY, workspaceSettingsName: "robots.code-workspace" });
  assert.match(move.message ?? "", /\(robots\.code-workspace\)/);
});

test("no message ever contains the key", () => {
  const cases = [
    { userValue: KEY },
    { userValue: KEY, storedKey: KEY },
    { workspaceValue: KEY },
    { workspaceValue: KEY, storedKey: OTHER },
    { userValue: OTHER, workspaceValue: KEY },
  ];
  for (const found of cases) {
    const message = planTbaKeyMove({ ...found, workspaceSettingsName: FILE }).message ?? "";
    assert.ok(!message.includes(KEY) && !message.includes(OTHER), JSON.stringify(found));
  }
});

const manifest = JSON.parse(
  fs.readFileSync(path.join(__dirname, "..", "..", "package.json"), "utf8")
);

test("a key pasted after the stored key, in a field still showing it, is the new key", () => {
  // The Settings editor refreshes a focused field only once the user leaves it
  const move = planTbaKeyMove({ userValue: KEY + OTHER, storedKey: KEY, workspaceSettingsName: FILE });
  assert.equal(move.store, OTHER);
  assert.equal(move.clearUser, true);
});

test("the stored key with only spaces after it is the stored key", () => {
  const move = planTbaKeyMove({ userValue: `${KEY}   `, storedKey: KEY, workspaceSettingsName: FILE });
  assert.equal(move.store, undefined);
  assert.match(move.message ?? "", /already saved/);
});

test("a key that merely begins like the stored one, without containing all of it, is kept whole", () => {
  const partial = KEY.slice(0, 10) + OTHER;
  const move = planTbaKeyMove({ userValue: partial, storedKey: KEY, workspaceSettingsName: FILE });
  assert.equal(move.store, partial);
});
