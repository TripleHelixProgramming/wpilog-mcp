// Tests for Claude Code's .mcp.json entry and configuration file (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import {
  SERVER_NAME,
  TBA_KEY_REFERENCE,
  addToGitignore,
  buildServerConfig,
  buildServerEntry,
  gitAction,
  hasServerEntry,
  mergeServerEntry,
  otherWpilogServer,
  scrubTbaKey,
  shouldWriteEntry,
} from "../mcpJson";

const entry = buildServerEntry("/jdk/bin/java", "/storage/server/wpilog-mcp-all.jar", "4g",
  "/storage/servers.json");

test("the entry only starts the server with the configuration file: no settings, no key", () => {
  assert.equal(entry.command, "/jdk/bin/java");
  assert.deepEqual(entry.args, ["-Xmx4g", "-jar", "/storage/server/wpilog-mcp-all.jar", "start",
    "default", "--config", "/storage/servers.json"]);
  assert.equal(entry.env, undefined);
  assert.ok(!JSON.stringify(entry).includes("tba"));
});

test("the configuration file holds the settings in the standalone's format", () => {
  const config = JSON.parse(buildServerConfig(["/logs", "/archive"], 2363, "the-key"));
  assert.deepEqual(config, {
    servers: {
      default: { transport: "stdio", logdir: ["/logs", "/archive"], team: 2363, tba_key: "the-key" },
    },
  });
});

test("the configuration file leaves out what is not set", () => {
  const config = JSON.parse(buildServerConfig([], 0));
  assert.deepEqual(config, { servers: { default: { transport: "stdio" } } });
  assert.equal(JSON.parse(buildServerConfig(["/logs"], 0, "")).servers.default.tba_key, undefined);
});

test("the configuration file is valid JSON ending in a newline, with paths kept exactly", () => {
  const text = buildServerConfig(["C:\\Users\\me\\riologs", "/odd \"quoted\" dir"], 2363);
  assert.ok(text.endsWith("\n"));
  assert.deepEqual(JSON.parse(text).servers.default.logdir,
    ["C:\\Users\\me\\riologs", "/odd \"quoted\" dir"]);
});

test("a missing or blank file gets just this entry", () => {
  for (const existing of [undefined, "", "  \n"]) {
    const edit = mergeServerEntry(existing, entry);
    assert.ok(edit.ok);
    assert.deepEqual(JSON.parse(edit.text), { mcpServers: { [SERVER_NAME]: entry } });
    assert.ok(edit.changed);
  }
});

test("other servers and top-level keys are kept; only this entry is replaced", () => {
  const existing = JSON.stringify({
    mcpServers: {
      github: { command: "gh-mcp", args: [] },
      [SERVER_NAME]: { command: "old-java", args: ["-tba-key", "SECRET"], env: {} },
    },
    somethingElse: { keep: true },
  });
  const edit = mergeServerEntry(existing, entry);
  assert.ok(edit.ok);
  const doc = JSON.parse(edit.text);
  assert.deepEqual(doc.mcpServers.github, { command: "gh-mcp", args: [] });
  assert.deepEqual(doc.mcpServers[SERVER_NAME], entry);
  assert.deepEqual(doc.somethingElse, { keep: true });
  assert.ok(!edit.text.includes("SECRET"));
});

test("an unchanged entry is not rewritten", () => {
  const first = mergeServerEntry(undefined, entry);
  assert.ok(first.ok);
  const second = mergeServerEntry(first.text, entry);
  assert.ok(second.ok);
  assert.equal(second.changed, false);
});

test("a file that is not a JSON object is refused, not overwritten", () => {
  for (const existing of ["{ not json", "[1, 2]", "\"text\"", "{\"mcpServers\": [1]}"]) {
    const edit = mergeServerEntry(existing, entry);
    assert.equal(edit.ok, false, existing);
  }
});

test("a plaintext key written by an earlier version is removed from this entry only", () => {
  const existing = JSON.stringify({
    mcpServers: {
      [SERVER_NAME]: {
        command: "/java",
        args: ["-Xmx4g", "-jar", "/jar", "-tba-key", "SECRET", "-team", "2363"],
        env: { WPILOG_TEAM: "2363", TBA_API_KEY: "SECRET" },
      },
      other: { command: "x", env: { TBA_API_KEY: "OTHER-SECRET" } },
    },
  });
  const edit = scrubTbaKey(existing);
  assert.ok(edit.ok);
  assert.ok(edit.changed);
  const doc = JSON.parse(edit.text);
  assert.deepEqual(doc.mcpServers[SERVER_NAME].args, ["-Xmx4g", "-jar", "/jar", "-team", "2363"]);
  assert.equal(doc.mcpServers[SERVER_NAME].env.TBA_API_KEY, TBA_KEY_REFERENCE);
  assert.equal(doc.mcpServers[SERVER_NAME].env.WPILOG_TEAM, "2363");
  // Another server's configuration is not ours to change
  assert.equal(doc.mcpServers.other.env.TBA_API_KEY, "OTHER-SECRET");
  assert.ok(!JSON.stringify(doc.mcpServers[SERVER_NAME]).includes("SECRET"));
});

test("nothing to remove: no file, no entry, a reference, or unparseable text", () => {
  assert.deepEqual(scrubTbaKey(undefined), { ok: true, text: "", changed: false });
  const noEntry = JSON.stringify({ mcpServers: { other: {} } });
  assert.deepEqual(scrubTbaKey(noEntry), { ok: true, text: noEntry, changed: false });
  const clean = mergeServerEntry(undefined, entry);
  assert.ok(clean.ok);
  const again = scrubTbaKey(clean.text);
  assert.ok(again.ok);
  assert.equal(again.changed, false);
  assert.equal(scrubTbaKey("{ broken").ok, false);
});

test("enabled for Claude Code: written in robot projects and kept wherever one exists", () => {
  assert.equal(shouldWriteEntry(true, true, false), true);
  assert.equal(shouldWriteEntry(true, false, true), true, "an earlier version's entry");
  assert.equal(shouldWriteEntry(true, false, false), false, "another folder, unasked");
});

test("disabled for Claude Code: nothing is written unless the user asks for a folder", () => {
  assert.equal(shouldWriteEntry(false, true, true), false);
  assert.equal(shouldWriteEntry(false, false, false, true), true, "the Add command");
  assert.equal(shouldWriteEntry(true, false, false, true), true, "the Add command");
});

test("hasServerEntry finds this server's entry and nothing else", () => {
  assert.equal(hasServerEntry(undefined), false);
  assert.equal(hasServerEntry(""), false);
  assert.equal(hasServerEntry("{ broken"), false);
  assert.equal(hasServerEntry(JSON.stringify({ mcpServers: { wpilog: { command: "x" } } })), false);
  assert.equal(hasServerEntry(JSON.stringify({ mcpServers: [] })), false);
  assert.equal(hasServerEntry(JSON.stringify({ mcpServers: { [SERVER_NAME]: entry } })), true);
});

test("a .mcp.json the repository shares is never written; one git would pick up is offered to ignore", () => {
  assert.equal(gitAction("tracked"), "skipShared");
  assert.equal(gitAction("untracked"), "writeAndOfferIgnore");
  assert.equal(gitAction("ignored"), "write");
  assert.equal(gitAction("none"), "write");
});

test("another entry that runs wpilog-mcp (the standalone install) is found; this server's is not", () => {
  const standalone = { mcpServers: { wpilog: { command: "/Users/x/.wpilog-mcp/bin/wpilog-mcp" } } };
  assert.equal(otherWpilogServer(JSON.stringify(standalone)), "wpilog");
  const jar = { mcpServers: { logs: { command: "java", args: ["-jar", "/opt/wpilog-mcp-0.9.0-all.jar"] } } };
  assert.equal(otherWpilogServer(JSON.stringify(jar)), "logs");
  assert.equal(otherWpilogServer(JSON.stringify({ mcpServers: { [SERVER_NAME]: entry } })), undefined);
  assert.equal(otherWpilogServer(JSON.stringify({ mcpServers: { github: { command: "gh" } } })), undefined);
  assert.equal(otherWpilogServer(undefined), undefined);
  assert.equal(otherWpilogServer("{ broken"), undefined);
});

test("addToGitignore appends .mcp.json with a comment, keeping what is there", () => {
  const added = "# Claude Code's MCP servers (paths for this computer only)\n.mcp.json\n";
  assert.equal(addToGitignore(undefined), added);
  assert.equal(addToGitignore(""), added);
  assert.equal(addToGitignore("build/\n"), "build/\n\n" + added);
  assert.equal(addToGitignore("build/"), "build/\n\n" + added);
});
