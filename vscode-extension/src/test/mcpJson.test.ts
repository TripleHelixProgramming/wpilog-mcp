// Tests for the .mcp.json entry (no VS Code needed): npm test
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "path";
import {
  SERVER_NAME,
  TBA_KEY_REFERENCE,
  addToGitignore,
  buildServerEntry,
  gitAction,
  hasServerEntry,
  mergeServerEntry,
  otherWpilogServer,
  scrubTbaKey,
  shouldWriteEntry,
  writeMode,
} from "../mcpJson";

const entry = buildServerEntry("/jdk/bin/java", "/ext/server/wpilog-mcp.jar", "4g", ["/logs"], 2363);

test("the entry references the TBA key and never contains one", () => {
  assert.equal(entry.env["TBA_API_KEY"], TBA_KEY_REFERENCE);
  assert.ok(!entry.args.includes("-tba-key"));
  assert.deepEqual(entry.args, ["-Xmx4g", "-jar", "/ext/server/wpilog-mcp.jar", "-logdir",
    "/logs", "-team", "2363"]);
  const bare = buildServerEntry("/java", "/jar", "2g", [], 0);
  assert.deepEqual(bare.args, ["-Xmx2g", "-jar", "/jar"]);
  assert.deepEqual(bare.env, { TBA_API_KEY: TBA_KEY_REFERENCE });
});

test("several log directories are each a -logdir and joined in WPILOG_DIR", () => {
  const several = buildServerEntry("/java", "/jar", "2g", ["/logs", "/archive"], 0);
  assert.deepEqual(several.args, ["-Xmx2g", "-jar", "/jar", "-logdir", "/logs", "-logdir",
    "/archive"]);
  assert.equal(several.env["WPILOG_DIR"], ["/logs", "/archive"].join(path.delimiter));
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

test("the TBA key reaches the server by file: its path is an argument, the key is nowhere", () => {
  const withKey = buildServerEntry("/java", "/jar", "2g", [], 0, "/storage/tba-api-key");
  assert.deepEqual(withKey.args.slice(-2), ["-tba-key-file", "/storage/tba-api-key"]);
  assert.equal(withKey.env["TBA_API_KEY"], TBA_KEY_REFERENCE);
  assert.ok(!buildServerEntry("/java", "/jar", "2g", [], 0).args.includes("-tba-key-file"));
});

test("writeMcpJson: robot projects by default; true and false from development builds", () => {
  assert.equal(writeMode(undefined), "robotProjects");
  assert.equal(writeMode("robotProjects"), "robotProjects");
  assert.equal(writeMode("always"), "always");
  assert.equal(writeMode("never"), "never");
  assert.equal(writeMode(true), "always");
  assert.equal(writeMode(false), "never");
  assert.equal(writeMode("sometimes"), "robotProjects");
});

test("by default the entry is written in robot projects and kept wherever one exists", () => {
  assert.equal(shouldWriteEntry("robotProjects", true, false), true);
  assert.equal(shouldWriteEntry("robotProjects", false, true), true, "an earlier version's entry");
  assert.equal(shouldWriteEntry("robotProjects", false, false), false);
  assert.equal(shouldWriteEntry("always", false, false), true);
  assert.equal(shouldWriteEntry("never", true, true), false);
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
