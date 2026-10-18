/** Drive activation and settings events through the real glue, with a shared configuration
 * guard like the local server's. No installed server, files or editor profile are touched. */
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as path from "node:path";
import * as os from "node:os";

const tick = () => new Promise<void>(resolve => setImmediate(resolve));
async function until(check: () => boolean) {
  const end = Date.now() + 2000;
  while (!check()) { if (Date.now() > end) assert.fail("Timed out waiting for configuration"); await tick(); }
}
function deferred() { let resolve!: () => void; const promise = new Promise<void>(done => resolve = done); return { promise, resolve }; }
type Kind = "live" | "mirror";
async function fixture(t: any, holdActivation?: Kind) {
  const settings: Record<string, unknown> = { pitServerUrl: "http://pit-a.invalid:2363", "live.enabled": true, "mirror.enabled": true };
  const listeners: ((event: { affectsConfiguration(key: string): boolean }) => void)[] = [];
  const calls: { kind: Kind; origin?: string }[] = [], refused: string[] = [], output: string[] = [];
  const configured: Partial<Record<Kind, string>> = {};
  let busy = false, registration = Promise.resolve(), hold: Kind | undefined = holdActivation, fail: Kind | undefined;
  const held = deferred();
  const disposable = () => ({ dispose() {} });
  const service = async (kind: Kind, origin?: string) => {
    calls.push({ kind, origin });
    if (busy) { refused.push(kind); throw new Error("HTTP 409: another configuration operation is in progress"); }
    busy = true;
    try {
      if (hold === kind) { hold = undefined; await held.promise; }
      if (fail === kind) { fail = undefined; throw new Error(`injected ${kind} refusal`); }
      configured[kind] = origin;
      return { state: "configured" };
    } finally { busy = false; }
  };
  const host = {
    EventEmitter: class { event = disposable; fire() {} dispose() {} },
    StatusBarAlignment: { Left: 1 }, ExtensionMode: { Test: 3 },
    window: {
      createOutputChannel: () => ({ appendLine: (s: string) => output.push(s), dispose() {} }),
      createStatusBarItem: () => ({ show() {}, hide() {}, dispose() {} }),
      showInformationMessage: async () => undefined, showErrorMessage: async (s: string) => { output.push(s); },
    },
    lm: { registerMcpServerDefinitionProvider: disposable },
    commands: { registerCommand: disposable },
    workspace: {
      workspaceFolders: [], getConfiguration: () => ({
        get: (key: string, fallback: unknown) => settings[key] ?? fallback,
        inspect: () => undefined,
      }),
      onDidChangeConfiguration: (listener: typeof listeners[number]) => { listeners.push(listener); return disposable(); },
      onDidChangeWorkspaceFolders: disposable,
    },
  };
  class Explorer {
    logs = { getChildren() {}, refresh() {} }; editor = { inspectData() {} };
    serversChanged() {} offerOrganizing() {} dispose() {} async endSessions() {}
    async clientFor() { return { endpoint: "http://127.0.0.1:2363/mcp", refreshRegistration: () => {
      registration = registration.then(tick); return registration;
    } }; }
  }
  class StoreClient {
    configure(config: any) { return service("mirror", config.origin); }
    disable() { return service("mirror"); }
    configureLive(config: any) { return service("live", config.robot.host); }
    disableLive() { return service("live"); }
    async status() { return { state: configured.mirror ? "idle" : "disabled" }; }
  }
  const loader = require("node:module") as { _load: (...args: any[]) => any }, load = loader._load;
  const extensionPath = require.resolve("../extension"); delete require.cache[extensionPath];
  let extension: typeof import("../extension");
  try {
    loader._load = (name, parent, ...args) => {
      if (name === "vscode") return host;
      if (parent?.filename === extensionPath) {
        if (name === "./explorer") return { Explorer };
        if (name === "./serverManager") return { ServerManager: class { dispose() {} } };
        if (name === "./explorer/storeClient") return { StoreClient };
        if (name === "./standaloneServer") return { STANDALONE_SERVER: "http", findStandaloneInstall: () => ({ launcher: "fixture-launcher" }) };
        if (name === "./standaloneInstall") return { installAction: () => "none", launcherVersion: () => "0.10.0-dev5" };
        if (name === "fs") return { ...load(name, parent, ...args), existsSync: () => false,
          promises: { readFile: async () => "# wpilog-mcp 0.10.0-dev5 launcher" } };
      }
      return load(name, parent, ...args);
    };
    extension = require("../extension");
  } finally { loader._load = load; }
  const context = { subscriptions: [] as { dispose(): void }[], extensionMode: 3,
    globalStorageUri: { fsPath: path.join(os.tmpdir(), "configuration-fixture-unused") },
    globalState: { get: (key: string) => key === "wpilog-mcp.retiredOwnedDaemon", update: async () => {} },
    secrets: { get: async () => undefined, onDidChange: disposable }, extension: { packageJSON: { version: "0.10.0-dev5" } },
  };
  const api = extension!.activate(context as any);
  t.after(async () => {
    held.resolve(); await api.ready;
    for (let i = 0; i < 30; i++) await tick();
    await extension!.deactivate(); for (const subscription of context.subscriptions) subscription.dispose();
    delete require.cache[extensionPath];
  });
  const change = (key: string, value: unknown) => {
    settings[key] = value;
    for (const listener of listeners) listener({ affectsConfiguration: name => `wpilog-mcp.${key}` === name || `wpilog-mcp.${key}`.startsWith(name + ".") });
  };
  return { api, calls, refused, configured, output, change, release: held.resolve,
    hold: (kind: Kind) => { hold = kind; }, fail: (kind: Kind) => { fail = kind; } };
}

for (const first of ["live", "mirror"] as const) test(`a slow ${first} configuration cannot overlap a later pit change`, async t => {
  const f = await fixture(t); await f.api.ready; f.calls.length = 0; f.hold(first);
  if (first === "live") f.change("pitServerUrl", "http://pit-b.invalid:2363");
  else f.change("mirror.days", 5);
  await until(() => f.calls.some(c => c.kind === first));
  f.change("pitServerUrl", "http://pit-c.invalid:2363");
  // Let every registration continuation run while the first configuration is still held.
  for (let i = 0; i < 30; i++) await tick();
  assert.deepEqual(f.refused, [], "complete operations must serialize, not only registration");
  assert.equal(f.calls.length, 1, "the next service cannot enter until the held change has finished");
  f.release();
  await until(() => f.configured.live === "pit-c.invalid" && f.configured.mirror === "http://pit-c.invalid:2363");
  assert.deepEqual(f.refused, []);
});

test("activation shares the configuration queue with settings changes", async t => {
  const f = await fixture(t, "live"); await until(() => f.calls.some(c => c.kind === "live"));
  const before = f.calls.length;
  f.change("pitServerUrl", "http://pit-b.invalid:2363");
  for (let i = 0; i < 30; i++) await tick();
  assert.deepEqual(f.refused, []); assert.equal(f.calls.length, before);
  f.release(); await f.api.ready;
  await until(() => f.configured.live === "pit-b.invalid" && f.configured.mirror === "http://pit-b.invalid:2363");
});

test("a refused operation does not poison later configuration or disable requests", async t => {
  const f = await fixture(t); await f.api.ready; f.fail("live");
  f.change("pitServerUrl", "http://pit-b.invalid:2363");
  await until(() => f.output.some(s => s.includes("injected live refusal")) && f.configured.mirror === "http://pit-b.invalid:2363");
  f.change("live.enabled", false); f.change("mirror.enabled", false);
  await until(() => f.configured.live === undefined && f.configured.mirror === undefined);
  assert.deepEqual(f.refused, []);
});
