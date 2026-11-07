import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "node:path";
import { legacyCleanupAction, retireLegacyEntry } from "../legacyMigration";

const own = JSON.stringify({ mcpServers: {
  "wpilog-analyzer": { command: "/jdk/java", args: ["-Xmx4g", "-jar", "/storage/server.jar", "connect", "vscode-default", "--config", "/storage/servers/vscode-default.json"] },
  another: { command: "other-server", args: ["keep"] },
}, extra: { keep: true } });

for (const git of ["untracked", "ignored"] as const) {
  test(`a recorded private bridge entry is retired from an ${git} file, preserving its other contents`, () => {
    const edit = retireLegacyEntry(own, git, "/storage", "/project", path.posix);
    assert.equal(edit.changed, true);
    assert.deepEqual(JSON.parse(edit.text!), { mcpServers: { another: { command: "other-server", args: ["keep"] } }, extra: { keep: true } });
    assert.match(edit.note!, /user-scope/);
    assert.equal(retireLegacyEntry(edit.text, git, "/storage", "/project", path.posix).changed, false);
  });
}

for (const platform of [path.posix, path.win32]) {
  test(`http entries require a configuration inside extension storage (${platform.sep})`, () => {
    const root = platform.resolve(platform === path.win32 ? "C:\\VS Code\\Storage" : "/VS Code/Storage");
    const project = platform.resolve(platform === path.win32 ? "C:\\Project" : "/Project");
    const config = platform.join(root, "servers", "http.json");
    const recorded = (file: string) => JSON.stringify({ mcpServers: { "wpilog-analyzer": {
      command: platform === path.win32 ? "cmd" : "wpilog-mcp",
      args: [...(platform === path.win32 ? ["/c", "C:\\Tools\\wpilog-mcp.bat"] : []), "connect", "http", "--config", file],
    } } });
    for (const file of [config, platform.relative(project, config)]) {
      assert.equal(retireLegacyEntry(recorded(file), "ignored", root, project, platform).changed, true);
    }
    if (platform === path.win32) {
      assert.equal(retireLegacyEntry(recorded(config.toUpperCase().replace(/\\/g, "/")), "ignored", root, project, platform).changed, true);
    }
    for (const file of [root, platform.join(root + "-other", "http.json"), platform.join(root, "..", "outside.json"),
      ...(platform === path.win32 ? ["D:\\user.json"] : [])]) {
      const edit = retireLegacyEntry(recorded(file), "ignored", root, project, platform);
      assert.equal(edit.changed, false, file);
      assert.equal(edit.text, recorded(file));
      assert.match(edit.note!, /custom/);
    }
    const outside = platform.join(project, "user.json");
    assert.equal(retireLegacyEntry(recorded(config), "ignored", root, project, platform,
      file => file === config ? outside : file).changed, false, "a symbolic link's target is authoritative");
    assert.match(retireLegacyEntry(recorded(config), "ignored", root, project, platform,
      () => { throw new Error("unreadable"); }).note!, /could not be resolved/);
  });
}

test("tracked and unknown-git files are byte-for-byte unchanged, with a note", () => {
  for (const git of ["tracked", "none"] as const) {
    const edit = retireLegacyEntry(own, git, "/storage", "/project", path.posix);
    assert.equal(edit.changed, false);
    assert.equal(edit.text, own);
    assert.match(edit.note!, git === "tracked" ? /tracked/ : /git did not identify/);
  }
});

test("custom or malformed entries are kept without quoting their contents", () => {
  for (const entry of [{ type: "http", url: "http://localhost:2363/mcp" },
    { args: ["connect", "http"] }, { args: ["start", "default"] }, { args: ["connect", "another"] },
    { args: ["connect", "http", "--config"] }, { args: ["connect", "vscode-default", 3] }, null]) {
    const text = JSON.stringify({ mcpServers: { "wpilog-analyzer": entry, other: { key: "synthetic-private" } } });
    const edit = retireLegacyEntry(text, "untracked", "/storage", "/project", path.posix);
    assert.equal(edit.changed, false);
    assert.equal(edit.text, text);
    assert.match(edit.note!, /custom/);
    assert.ok(!edit.note!.includes("synthetic-private"));
  }
  for (const text of ["{synthetic-private", "[]", '{"mcpServers":1}']) {
    const edit = retireLegacyEntry(text, "untracked", "/storage", "/project", path.posix);
    assert.equal(edit.changed, false);
    assert.equal(edit.text, text);
    assert.ok(!edit.note!.includes("synthetic-private"));
  }
  assert.deepEqual(retireLegacyEntry(undefined, "untracked", "/storage", "/project"), { changed: false, text: undefined });
  const other = '{"mcpServers":{"wpilog":{"args":["connect","vscode-default"]}}}';
  assert.equal(retireLegacyEntry(other, "untracked", "/storage", "/project").text, other);
});

test("cleanup stops the old daemon once and removes private settings only after a successful stop", () => {
  assert.equal(legacyCleanupAction(true, true), "none");
  assert.equal(legacyCleanupAction(true, false), "none");
  assert.equal(legacyCleanupAction(false, false), "remove");
  assert.equal(legacyCleanupAction(false, true), "stop");
  assert.equal(legacyCleanupAction(false, true, false), "retry");
  assert.equal(legacyCleanupAction(false, true, true), "remove");
});
