// Real PID files and HTTP health, fake launches: no user's daemon is touched.
import { test, TestContext } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as http from "node:http";
import * as os from "node:os";
import * as path from "node:path";
import type { AddressInfo } from "node:net";
import type * as vscode from "vscode";
import type { StandaloneDaemonSpec, ServerManager as Manager } from "../serverManager";

const loader = require("node:module") as { _load: (name: string, ...args: unknown[]) => unknown };
const originalLoad = loader._load;
let ServerManager: typeof Manager;
try {
  loader._load = (name, ...args) => name === "vscode" ? {
    window: { showErrorMessage: async () => undefined }, commands: { executeCommand: async () => undefined },
  } : originalLoad(name, ...args);
  ServerManager = (require("../serverManager") as typeof import("../serverManager")).ServerManager;
} finally { loader._load = originalLoad; }

async function fixture(t: TestContext) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "wpilog-manager-"));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const daemon = http.createServer((_req, res) => res.end(JSON.stringify({ status: "ok", sessions: 0, version: "0.9.1" })));
  await new Promise<void>(resolve => daemon.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise<void>(resolve => daemon.close(() => resolve())));
  const port = (daemon.address() as AddressInfo).port;
  const spec: StandaloneDaemonSpec = { kind: "standalone", name: "http", installDir: dir,
    launcher: path.join(dir, "wpilog-mcp"), configPath: path.join(dir, "servers.yaml"), pidFile: path.join(dir, "http.pid") };
  await fs.writeFile(spec.pidFile, `1234\n${port}\n`);
  await fs.writeFile(spec.configPath, "# the user's settings\nteam: 2363\n");
  const fake = { commands: [] as string[], prepared: 0, declined: false, stopCode: 0, startCode: 0, changes: 0,
    prepare: async () => {} };
  const manager = new ServerManager({ extension: { packageJSON: { version: "0.9.1" } } } as unknown as vscode.ExtensionContext,
    { appendLine() {} } as unknown as vscode.OutputChannel, () => fake.changes++, async () => {
      fake.prepared++;
      await fake.prepare();
      return fake.declined ? undefined : spec;
    });
  t.after(() => manager.dispose());
  (manager as unknown as { run(command: string, args: string[]): Promise<{ code: number }> }).run = async (_command, args) => {
    const operation = args.join(" ").includes("stop") ? "stop" : "start";
    fake.commands.push(operation);
    assert.ok(args.join(" ").includes(spec.configPath), "start and stop use the home config explicitly");
    return { code: operation === "stop" ? fake.stopCode : fake.startCode };
  };
  return { manager, spec, fake, port };
}

test("concurrent consumers share preparation and one standalone start, preserving its config", async t => {
  const { manager, spec, fake, port } = await fixture(t);
  const urls = await Promise.all([manager.ensure(spec), manager.ensure(spec), manager.ensure(spec)]);
  assert.deepEqual(urls, Array(3).fill(`http://127.0.0.1:${port}/mcp`));
  assert.equal(fake.prepared, 1);
  assert.deepEqual(fake.commands, ["start"]);
  assert.equal(fake.changes, 1);
  assert.equal(await fs.readFile(spec.configPath, "utf8"), "# the user's settings\nteam: 2363\n");
});

test("declining the install starts nothing; a failed start or absent PID cannot publish a URL", async t => {
  const { manager, spec, fake } = await fixture(t);
  fake.declined = true;
  assert.equal(await manager.ensure(spec), undefined);
  assert.deepEqual(fake.commands, []);
  fake.declined = false;
  fake.startCode = 1;
  assert.equal(await manager.ensure(spec), undefined);
  assert.equal(fake.changes, 0);
  fake.startCode = 0;
  await fs.rm(spec.pidFile);
  assert.equal(await manager.ensure(spec), undefined);
});

test("manual restart waits for an in-flight start and stops before starting again", async t => {
  const { manager, spec, fake } = await fixture(t);
  let release!: () => void;
  fake.prepare = () => new Promise<void>(resolve => { release = resolve; });
  const starting = manager.ensure(spec);
  const restarting = manager.restart(spec);
  assert.deepEqual(fake.commands, []);
  fake.prepare = async () => {};
  release();
  await Promise.all([starting, restarting]);
  assert.deepEqual(fake.commands, ["start", "stop", "start"]);
  fake.stopCode = 1;
  assert.equal(await manager.restart(spec), undefined);
  assert.deepEqual(fake.commands, ["start", "stop", "start", "stop"]);
});
