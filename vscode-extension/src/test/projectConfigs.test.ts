// Tests for the configuration files kept for Claude Code's server (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import { SERVER_NAME, buildServerConfig, buildServerEntry, mergeServerEntry } from "../mcpJson";
import {
  EntryWriteIo,
  removeTbaKeyFromConfig,
  removeTbaKeyFromConfigs,
  writeConfigFile,
  writeEntry,
} from "../projectConfigs";

const entry = buildServerEntry("/jdk/bin/java", "/storage/server/wpilog-mcp-all.jar", "4g",
  "vscode-default", "/storage/servers/vscode-default.json");

const withKey = buildServerConfig({
  logDirs: ["/logs"],
  teamNumber: 9999,
  tbaKey: "A-SECRET-KEY",
  cacheDir: "/storage/cache",
});

function tempDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), "wpilog-configs-"));
}

/** An io that records what was done, and can fail the .mcp.json write. */
function recordingIo(failMcpJson = false): { io: EntryWriteIo; calls: string[] } {
  const calls: string[] = [];
  return {
    calls,
    io: {
      writeConfig: async () => {
        calls.push("config");
      },
      removeConfig: async () => {
        calls.push("removeConfig");
      },
      writeMcpJson: async () => {
        calls.push("mcp");
        if (failMcpJson) throw new Error("EACCES: permission denied");
      },
    },
  };
}

// ---- the key in a configuration file ----

test("the key is removed from a configuration file, and nothing else", () => {
  const edit = removeTbaKeyFromConfig(withKey);
  assert.ok(edit.ok);
  assert.ok(edit.changed);
  assert.ok(!edit.text.includes("A-SECRET-KEY"));
  assert.deepEqual(JSON.parse(edit.text), {
    servers: {
      default: { transport: "stdio", logdir: ["/logs"], team: 9999, diskcachedir: "/storage/cache" },
    },
  });
});

test("a configuration file without a key is left as it is; one that cannot be read is not ok", () => {
  const noKey = buildServerConfig({ logDirs: ["/logs"], teamNumber: 9999 });
  assert.deepEqual(removeTbaKeyFromConfig(noKey), { ok: true, text: noKey, changed: false });
  assert.equal(removeTbaKeyFromConfig("{ \"servers\": { \"default\": { \"tba_key\": \"A-SEC").ok, false);
  assert.equal(removeTbaKeyFromConfig("[1, 2]").ok, false);
});

test("clearing the key reaches every configuration file, not only the remembered projects'", async () => {
  // A project is remembered only once its .mcp.json has the entry. A configuration file written
  // for a project whose .mcp.json then could not be updated was never remembered, and kept the
  // key after "Clear The Blue Alliance API Key".
  const dir = tempDir();
  const remembered = path.join(dir, "1111111111111111.json");
  const orphan = path.join(dir, "2222222222222222.json");
  const orphanNoKey = path.join(dir, "3333333333333333.json");
  const broken = path.join(dir, "4444444444444444.json");
  const noKey = buildServerConfig({ logDirs: ["/logs"], teamNumber: 9999 });
  fs.writeFileSync(remembered, withKey);
  fs.writeFileSync(orphan, withKey);
  fs.writeFileSync(orphanNoKey, noKey);
  fs.writeFileSync(broken, "{ \"servers\": { \"default\": { \"tba_key\": \"A-SECRET-KEY\"");
  fs.writeFileSync(path.join(dir, "notes.txt"), "not a configuration file");

  const result = await removeTbaKeyFromConfigs(dir, new Set([remembered]));

  assert.deepEqual(result, { scrubbed: [orphan], removed: [broken] });
  assert.equal(fs.readFileSync(remembered, "utf8"), withKey, "a remembered project's is rewritten by its own refresh");
  const scrubbed = fs.readFileSync(orphan, "utf8");
  assert.ok(!scrubbed.includes("A-SECRET-KEY"));
  assert.equal(JSON.parse(scrubbed).servers.default.team, 9999, "its other settings are kept");
  assert.equal(fs.readFileSync(orphanNoKey, "utf8"), noKey);
  assert.ok(!fs.existsSync(broken), "a file that cannot be read cannot be cleaned: it is removed");
  assert.ok(fs.existsSync(path.join(dir, "notes.txt")));
  if (process.platform !== "win32") {
    assert.equal(fs.statSync(orphan).mode & 0o777, 0o600);
  }
  // No file anywhere under the directory holds the key any more, the remembered one aside
  for (const name of fs.readdirSync(dir)) {
    const file = path.join(dir, name);
    if (file !== remembered) assert.ok(!fs.readFileSync(file, "utf8").includes("A-SECRET-KEY"), name);
  }
});

test("clearing the key with no remembered projects, and with no directory at all", async () => {
  const dir = tempDir();
  const orphan = path.join(dir, "2222222222222222.json");
  fs.writeFileSync(orphan, withKey);
  assert.deepEqual(await removeTbaKeyFromConfigs(dir, new Set()), { scrubbed: [orphan], removed: [] });
  assert.deepEqual(await removeTbaKeyFromConfigs(path.join(dir, "missing"), new Set()),
    { scrubbed: [], removed: [] });
});

// ---- writing a project's configuration file and its entry ----

test("a .mcp.json that cannot be updated gets no configuration file", async () => {
  // The file used to be written first, key and all, and left behind
  for (const existing of ["{ not json", "[1, 2]", "{\"mcpServers\": [1]}"]) {
    const { io, calls } = recordingIo();
    const result = await writeEntry(existing, entry, io);
    assert.equal(result.ok, false, existing);
    assert.ok(!result.ok && result.refused, existing);
    assert.deepEqual(calls, [], existing);
  }
});

test("the configuration file is written before the entry that starts the server with it", async () => {
  const { io, calls } = recordingIo();
  const result = await writeEntry(undefined, entry, io);
  assert.deepEqual(result, { ok: true, changed: true });
  assert.deepEqual(calls, ["config", "mcp"]);
});

test("an entry that is already right is not rewritten; its configuration file still is", async () => {
  const existing = mergeServerEntry(undefined, entry);
  assert.ok(existing.ok);
  const { io, calls } = recordingIo();
  assert.deepEqual(await writeEntry(existing.text, entry, io), { ok: true, changed: false });
  assert.deepEqual(calls, ["config"]);
});

test("a new entry that could not be written takes its configuration file with it", async () => {
  const { io, calls } = recordingIo(true);
  const result = await writeEntry(JSON.stringify({ mcpServers: { other: {} } }), entry, io);
  assert.equal(result.ok, false);
  assert.ok(!result.ok && result.error.includes("EACCES"));
  assert.ok(!result.ok && !result.refused && !result.entryExists);
  assert.deepEqual(calls, ["config", "mcp", "removeConfig"]);
});

test("an existing entry that could not be updated keeps the configuration file it starts with", async () => {
  const existing = JSON.stringify({
    mcpServers: { [SERVER_NAME]: { command: "/old/java", args: ["-jar", "/old.jar"] } },
  });
  const { io, calls } = recordingIo(true);
  const result = await writeEntry(existing, entry, io);
  assert.equal(result.ok, false);
  assert.ok(!result.ok && result.entryExists, "the project still has an entry: it stays remembered");
  assert.deepEqual(calls, ["config", "mcp"]);
});

// ---- the file itself ----

test("a configuration file is written whole, readable by this user only, and only when it changes", async () => {
  const dir = tempDir();
  const file = path.join(dir, "projects", "abc.json");
  assert.equal(await writeConfigFile(file, withKey), true);
  assert.equal(fs.readFileSync(file, "utf8"), withKey);
  if (process.platform !== "win32") {
    assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  }
  assert.deepEqual(fs.readdirSync(path.dirname(file)), ["abc.json"], "no temporary file is left");
  assert.equal(await writeConfigFile(file, withKey), false, "unchanged: not rewritten");
});
