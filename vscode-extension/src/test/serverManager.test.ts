// Real files and HTTP health checks; fake launches cannot touch a user's daemon.
import { test, TestContext } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as http from "node:http";
import * as os from "node:os";
import * as path from "node:path";
import type { AddressInfo } from "node:net";
import type * as vscode from "vscode";
import type { DaemonInputs, OwnDaemonSpec, ServerManager as Manager } from "../serverManager";
import { buildDaemonConfig } from "../projectServers";

const loader = require("node:module") as { _load: (name: string, ...args: unknown[]) => unknown };
const originalLoad = loader._load;
let ServerManager: typeof Manager;
try {
  loader._load = (name, ...args) => name === "vscode" ? {} : originalLoad(name, ...args);
  ServerManager = (require("../serverManager") as typeof import("../serverManager")).ServerManager;
} finally {
  loader._load = originalLoad;
}

async function fixture(t: TestContext) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "wpilog-manager-"));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const daemon = http.createServer((_req, res) => {
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ status: "ok", sessions: 0, version: "0.9.1", pid: 1234 }));
  });
  await new Promise<void>(resolve => daemon.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise<void>((resolve, reject) => daemon.close(err => err ? reject(err) : resolve())));
  const port = (daemon.address() as AddressInfo).port;
  const spec: OwnDaemonSpec = {
    kind: "own", name: "vscode-test", configPath: path.join(dir, "server.json"), folderPaths: [],
  };
  const fake = {
    inputs: {
      javaPath: "java", jarPath: "server.jar", maxHeap: "1g", logDirs: [path.join(dir, "logs")],
      teamNumber: 1111, tbaKey: "synthetic-secret", cacheDir: path.join(dir, "cache"),
      idleExitMinutes: 30, version: "0.9.1",
    } as DaemonInputs,
    commands: [] as { command: string; args: string[] }[],
    startCode: 0, stopCode: 0,
    beforeStart: async () => {},
  };
  const state = new Map<string, unknown>([["wpilog-mcp.serverPorts", { [spec.name]: port }]]);
  const context = { globalState: {
    get: (key: string) => state.get(key),
    update: async (key: string, value: unknown) => { state.set(key, value); },
  } } as unknown as vscode.ExtensionContext;
  const manager = new ServerManager(context, { appendLine() {} } as unknown as vscode.OutputChannel,
    async () => ({ ...fake.inputs }), () => {}, async () => undefined);
  t.after(() => manager.dispose());
  const processRunner = manager as unknown as {
    run(command: string, args: string[]): Promise<{ code: number; output: string }>;
  };
  processRunner.run = async (command, args) => {
    fake.commands.push({ command, args });
    if (args[3] === "start") await fake.beforeStart();
    return { code: args[3] === "start" ? fake.startCode : fake.stopCode, output: "" };
  };
  await fs.writeFile(spec.configPath, buildDaemonConfig({ ...fake.inputs, name: spec.name, port }));
  assert.equal(await manager.ensure(spec), `http://127.0.0.1:${port}/mcp`);
  assert.deepEqual(fake.commands, [], "an unchanged running daemon is adopted");
  return { manager, spec, fake };
}

for (const change of ["written configuration", "heap", "Java path", "cleared TBA key"] as const) {
  test(`${change} restarts the daemon with the current inputs`, async t => {
    const { manager, spec, fake } = await fixture(t);
    if (change === "written configuration") {
      fake.inputs.teamNumber = 2222;
      fake.inputs.logDirs = [path.join(path.dirname(spec.configPath), "other-logs")];
    } else if (change === "heap") {
      fake.inputs.maxHeap = "2g";
    } else if (change === "Java path") {
      fake.inputs.javaPath = path.join(path.dirname(spec.configPath), "other-java");
    } else {
      fake.inputs.tbaKey = undefined;
    }
    if (change === "written configuration" || change === "cleared TBA key") {
      assert.equal(await manager.writeConfig(spec), true);
      assert.equal(await manager.writeConfig(spec), false, "a second writer must not consume the restart");
    }
    await manager.ensure(spec);
    assert.deepEqual(fake.commands.map(c => c.args[3]), ["stop", "start"]);
    assert.equal(fake.commands[1].command, fake.inputs.javaPath);
    assert.equal(fake.commands[1].args[0], `-Xmx${fake.inputs.maxHeap}`);
    const config = JSON.parse(await fs.readFile(spec.configPath, "utf8")).servers[spec.name];
    assert.equal(config.team, fake.inputs.teamNumber);
    assert.deepEqual(config.logdir, fake.inputs.logDirs);
    assert.equal(config.tba_key, fake.inputs.tbaKey);
    await manager.ensure(spec);
    assert.equal(fake.commands.length, 2, "successful restart clears the pending change");
  });
}

test("unchanged configuration and launch inputs do not restart", async t => {
  const { manager, spec, fake } = await fixture(t);
  assert.equal(await manager.writeConfig(spec), false);
  await manager.ensure(spec);
  assert.deepEqual(fake.commands, []);
});

test("a failed start retains the pending restart even when the old daemon still answers", async t => {
  const { manager, spec, fake } = await fixture(t);
  fake.inputs.teamNumber = 2222;
  await manager.writeConfig(spec);
  fake.startCode = 1;
  assert.equal(await manager.ensure(spec), undefined);
  fake.startCode = 0;
  await manager.ensure(spec);
  assert.deepEqual(fake.commands.map(c => c.args[3]), ["stop", "start", "stop", "start"]);
});

test("a failed stop cannot be mistaken for a successful restart", async t => {
  const { manager, spec, fake } = await fixture(t);
  fake.inputs.maxHeap = "2g";
  fake.stopCode = 1;
  assert.equal(await manager.ensure(spec), undefined);
  assert.deepEqual(fake.commands.map(c => c.args[3]), ["stop"]);
  fake.stopCode = 0;
  await manager.ensure(spec);
  assert.deepEqual(fake.commands.map(c => c.args[3]), ["stop", "stop", "start"]);
});

test("a configuration written during a start remains pending until a later restart succeeds", async t => {
  const { manager, spec, fake } = await fixture(t);
  fake.inputs.teamNumber = 2222;
  // The fake child read its config before this callback, and has not exited yet.
  fake.beforeStart = async () => {
    fake.inputs.teamNumber = 3333;
    await manager.writeConfig(spec);
  };
  await manager.ensure(spec);
  fake.beforeStart = async () => {};
  await manager.ensure(spec);
  assert.deepEqual(fake.commands.map(c => c.args[3]), ["stop", "start", "stop", "start"]);
  await manager.ensure(spec);
  assert.equal(fake.commands.length, 4);
});
