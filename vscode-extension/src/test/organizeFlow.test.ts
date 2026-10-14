/** Drive the explorer commands with supplied picker and registration adapters.
 * The command, HTTP import request and job polling are real. */
import { test } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as http from "node:http";
import * as os from "node:os";
import * as path from "node:path";
import type * as vscode from "vscode";
import type { McpClient } from "../mcpClient";
import type { ServerManager, DaemonSpec } from "../serverManager";
import type { ImportRequest } from "../explorer/importJobs";
import { LocalControlError } from "../mcpClient";
import type { IngestionPolicy } from "../explorer/localIngestion";
import type { LogListing } from "../explorer/logsTree";

type State = {
  picks: (string | undefined)[]; destination?: { scheme: string; fsPath: string };
  additional: string[]; updates: string[][]; errors: string[]; output: string[];
  registered: boolean; registrationFailure: boolean; requests: ImportRequest[];
  commands: Map<string, (...args: any[]) => any>; dialogs: number; contexts: Map<string, unknown>;
  featureEnabled: boolean; neverOffer: boolean;
  selectedSources: string[]; confirmSetup: boolean; confirmFinished: boolean; policy: IngestionPolicy | null;
  toolArgs: Record<string, unknown>[]; controls: { route: string; method: string; body?: IngestionPolicy }[]; temporaryRoots: string[][]; conflict: boolean; infos: string[];
};
let ui: State;
const disposable = () => ({ dispose() {} });
const host = {
  TreeItem: class {},
  EventEmitter: class { event = () => disposable(); fire() {} dispose() {} },
  Disposable: class { dispose() {} },
  ConfigurationTarget: { Global: 1 }, ProgressLocation: { Notification: 15 },
  workspace: {
    registerFileSystemProvider: disposable,
    getConfiguration: (section: string) => {
      assert.equal(section, "wpilog-mcp");
      return { inspect: () => ({ globalValue: ui.additional }),
        update: async (key: string, value: string[], target: number) => {
          assert.equal(key, "additionalLogDirectories"); assert.equal(target, 1);
          ui.additional = [...value]; ui.updates.push([...value]);
        } };
    },
  },
  commands: { registerCommand: (name: string, action: (...args: any[]) => any) => {
    ui.commands.set(name, action); return disposable();
  }, executeCommand: async (command: string, key: string, value: unknown) => {
    assert.equal(command, "setContext"); ui.contexts.set(key, value);
  } },
  window: {
    tabGroups: { activeTabGroup: {}, onDidChangeTabs: disposable, onDidChangeTabGroups: disposable },
    registerTreeDataProvider: disposable, registerWebviewViewProvider: disposable, registerCustomEditorProvider: disposable,
    registerUriHandler: disposable,
    showInformationMessage: async (message: string, ...choices: unknown[]) => {
      ui.infos.push(message);
      return choices.includes("Organize") ? "Organize" : choices.includes("Save and Start Syncing") && ui.confirmSetup ? "Save and Start Syncing" : undefined;
    },
    showWarningMessage: async () => ui.confirmFinished ? "This source contains only finished files" : undefined,
    showErrorMessage: async (message: string) => { ui.errors.push(message); },
    showQuickPick: async (choices: { label: string }[], options?: vscode.QuickPickOptions) => {
      if (options?.canPickMany) return choices.filter(choice => ui.selectedSources.includes(choice.label));
      assert.ok(ui.picks.length, "an unexpected picker must not silently cancel the test");
      const selected = ui.picks.shift();
      if (selected === undefined) return undefined;
      const result = choices.find(choice => choice.label === selected);
      assert.ok(result, `picker does not offer ${selected}`); return result;
    },
    showOpenDialog: async (options: vscode.OpenDialogOptions) => {
      ui.dialogs++;
      assert.equal(options.canSelectFolders, true); assert.equal(options.canSelectFiles, false);
      assert.equal(options.canSelectMany, false);
      return ui.destination ? [ui.destination] : undefined;
    },
    withProgress: async (options: vscode.ProgressOptions, action: (progress: { report(): void }) => Promise<unknown>) => {
      if (options.title === "Importing copies") assert.ok(ui.registered, "Import must await the destination lease");
      return action({ report() {} });
    },
  },
};

// Node 20 has no mock.module. Substitute only vscode while loading the real command module,
// then restore Node's loader immediately; each test file has its own Node worker.
const loader = require("node:module") as { _load: (...args: any[]) => any };
const originalLoad = loader._load;
let Explorer: typeof import("../explorer").Explorer;
try {
  loader._load = (name, ...args) => name === "vscode" ? host : originalLoad(name, ...args);
  Explorer = require("../explorer").Explorer;
} finally { loader._load = originalLoad; }

async function fixture(action: (explorer: import("../explorer").Explorer, listing: LogListing,
  source: string, target: string) => Promise<void>): Promise<void> {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "wpilog-import-picker-"));
  const source = path.join(root, "source"), target = path.join(root, "chosen-store");
  fs.mkdirSync(source); fs.mkdirSync(target);
  const file = path.join(source, "fixture.wpilog"), rev = path.join(source, "fixture.revlog");
  fs.writeFileSync(file, "synthetic fixture"); fs.writeFileSync(rev, "synthetic companion");
  ui = { picks: ["Import into another folder…", "Decide later"], destination: { scheme: "file", fsPath: target },
    additional: [path.join(root, "previous")], updates: [], errors: [], output: [], registered: false,
    registrationFailure: false, requests: [], commands: new Map(), dialogs: 0, contexts: new Map(),
    featureEnabled: true, neverOffer: false,
    selectedSources: [fs.realpathSync(source)], confirmSetup: true, confirmFinished: false,
    policy: { revision: 0, enabled: false, store: null, store_id: null, sources: [] },
    toolArgs: [], controls: [], temporaryRoots: [], conflict: false, infos: [] };
  const server = http.createServer(async (request, response) => {
    response.setHeader("Content-Type", "application/json");
    if (request.method === "POST") {
      let text = ""; for await (const chunk of request) text += chunk;
      const submitted = JSON.parse(text) as ImportRequest;
      ui.requests.push(submitted);
      if (submitted.store === fs.realpathSync(target) && !ui.registered) {
        response.writeHead(403); response.end(JSON.stringify({ error: "Destination was not leased before import" })); return;
      }
      response.writeHead(202); response.end(JSON.stringify({ job_id: "fixture", url: "/store/import/fixture" }));
    } else {
      response.end(JSON.stringify({ state: "done", result: { files: [], same_robots: [] } }));
    }
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const endpoint = `http://127.0.0.1:${(server.address() as { port: number }).port}/mcp`;
  const spec = { kind: "standalone", name: "http" } as DaemonSpec;
  const context = { globalStorageUri: { fsPath: path.join(root, "state") }, globalState: { get: (key: string) => key === "wpilog-mcp.neverOfferDataStore" ? ui.neverOffer : undefined, update: async () => {} } } as unknown as vscode.ExtensionContext;
  const explorer = new Explorer(context, { appendLine: (line: string) => { ui.output.push(line); } } as vscode.OutputChannel,
    {} as ServerManager, () => spec);
  const listing: LogListing = { status: "ok", log_directories: [source],
    logs: [{ path: file, filename: "fixture.wpilog", friendly_name: "Synthetic fixture" }] };
  const control = async (route: string, method: string, body?: IngestionPolicy) => {
    ui.controls.push({ route, method, body });
    if (!ui.featureEnabled) throw new LocalControlError("Data-store setup and synchronization are experimental and disabled. Set experimental_data_store: true for this named server and restart it.", 409, "FEATURE_DISABLED");
    if (method === "PUT") {
      if (ui.conflict) throw new LocalControlError("Policy revision changed", 409, "CONFLICT");
      ui.policy = { ...body!, revision: body!.revision + 1 };
    }
    return { policy: ui.policy, sources: [] };
  };
  explorer.clientFor = async () => ({ endpoint,
    callTool: async (_name: string, args: Record<string, unknown>) => { ui.toolArgs.push(args); return listing; },
    requestLocalControl: control,
    withTemporaryDirectories: async (roots: string[], action: (request: typeof control) => Promise<unknown>) => {
      ui.temporaryRoots.push(roots); return action(control);
    }, refreshRegistration: async () => {
    assert.ok(ui.additional.includes(fs.realpathSync(target)), "store setting must be saved before registration");
    if (ui.registrationFailure) throw new Error("Synthetic lease refusal");
    // Force an asynchronous completion so forgetting to await the lease cannot pass this fixture.
    await Promise.resolve();
    ui.registered = true;
  } } as McpClient);
  explorer.logs.listing = async () => listing;
  try { await action(explorer, listing, source, target); }
  finally {
    explorer.dispose(); server.closeAllConnections();
    await new Promise<void>(resolve => server.close(() => resolve()));
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test("the organizer imports copies to a new chosen store only after saving and registering its directory", async () => {
  await fixture(async (explorer, listing, source, target) => {
    await ui.commands.get("wpilog-mcp.explorer.organizeLogs")!();
    assert.deepEqual(ui.errors, []); assert.equal(ui.requests.length, 1);
    assert.deepEqual(ui.requests[0], { store: fs.realpathSync(target),
      paths: [path.join(source, "fixture.wpilog"), path.join(source, "fixture.revlog")], move: false, stated_robot: null });
    assert.equal(ui.updates.length, 1); assert.equal(ui.updates[0].length, 2);
    assert.equal(ui.updates[0][1], fs.realpathSync(target)); assert.ok(ui.registered);
    assert.equal(fs.readFileSync(path.join(source, "fixture.wpilog"), "utf8"), "synthetic fixture");
    assert.equal(fs.existsSync(path.join(source, "store.json")), false);
  });
});

test("the existing Import context command can create a destination without any existing store", async () => {
  await fixture(async (explorer, listing, _source, target) => {
    ui.picks = ["Decide later"];
    await ui.commands.get("wpilog-mcp.explorer.importLogs")!({ spec: explorer.spec(), node: { kind: "log", log: listing.logs![0] } });
    assert.deepEqual(ui.errors, []); assert.equal(ui.dialogs, 1);
    assert.deepEqual(ui.requests, [{ store: fs.realpathSync(target), paths: [listing.logs![0].path], move: false, stated_robot: null }]);
  });
});

test("an existing store is an explicit destination choice and is not added to User roots twice", async () => {
  await fixture(async (explorer, listing, _source, target) => {
    const destination = fs.realpathSync(target);
    listing.stores = [{ path: destination }];
    ui.additional.push(destination);
    ui.picks = [path.basename(listing.log_directories![0] as string), "Import into another folder…", path.basename(destination), "Decide later"];
    await ui.commands.get("wpilog-mcp.explorer.organizeLogs")!();
    assert.deepEqual(ui.errors, []); assert.equal(ui.requests.length, 1);
    assert.equal(ui.requests[0].store, destination); assert.equal(ui.requests[0].move, false);
    assert.equal(ui.dialogs, 0); assert.deepEqual(ui.updates, []); assert.ok(ui.registered);
  });
});

test("cancelling the mode, destination or robot submits no request and saves no directory", async () => {
  for (const cancel of ["mode", "destination", "robot"]) await fixture(async (explorer, listing) => {
    if (cancel === "mode") ui.picks = [undefined];
    if (cancel === "destination") ui.destination = undefined;
    if (cancel === "robot") ui.picks = ["Import into another folder…", undefined];
    await ui.commands.get("wpilog-mcp.explorer.organizeLogs")!();
    assert.deepEqual(ui.errors, []); assert.deepEqual(ui.requests, []); assert.deepEqual(ui.updates, []);
  });
});

test("Copy and Move still use the source folder, with no destination setting or lease change", async () => {
  for (const move of [false, true]) await fixture(async (explorer, listing, source) => {
    ui.picks = [move ? "Move in this folder" : "Copy in this folder", "Decide later"];
    await ui.commands.get("wpilog-mcp.explorer.organizeLogs")!();
    assert.deepEqual(ui.errors, []); assert.equal(ui.requests.length, 1);
    assert.equal(ui.requests[0].store, source); assert.equal(ui.requests[0].move, move);
    assert.equal(ui.dialogs, 0); assert.deepEqual(ui.updates, []); assert.equal(ui.registered, false);
  });
});

test("a failed destination lease is reported without attempting an import", async () => {
  await fixture(async (explorer, listing) => {
    ui.registrationFailure = true;
    await ui.commands.get("wpilog-mcp.explorer.organizeLogs")!();
    assert.deepEqual(ui.errors, ["Synthetic lease refusal"]); assert.deepEqual(ui.requests, []);
  });
});

test("choosing the source itself or a remote URI explains the refusal without changing settings", async () => {
  for (const remote of [false, true]) await fixture(async (explorer, listing, source) => {
    ui.destination = { scheme: remote ? "vscode-remote" : "file", fsPath: source };
    await ui.commands.get("wpilog-mcp.explorer.organizeLogs")!();
    assert.equal(ui.errors.length, 1);
    assert.match(ui.errors[0], remote ? /local folder/ : /different from the source/);
    assert.deepEqual(ui.updates, []); assert.deepEqual(ui.requests, []);
  });
});


test("automatic discovery offers data-store setup rather than reorganizing the source", async () => {
  await fixture(async (explorer, listing) => {
    await explorer.offerOrganizing(listing);
    assert.equal(ui.requests.length, 0, "Automatic discovery must offer store setup, not perform one-shot organization");
  });
});


test("setup saves the reviewed source policy without changing User roots or running an import job", async () => {
  await fixture(async (_explorer, _listing, source, target) => {
    ui.picks = ["Copy and keep originals"];
    await ui.commands.get("wpilog-mcp.explorer.setupDataStore")!();
    assert.deepEqual(ui.errors, []); assert.deepEqual(ui.updates, []); assert.deepEqual(ui.requests, []);
    assert.deepEqual(ui.controls.filter(call => call.method === "PUT").map(call => call.body), [{ revision: 0, enabled: true,
      store: fs.realpathSync(target), store_id: null,
      sources: [{ path: fs.realpathSync(source), mode: "copy", enabled: true, completed_files: false, stated_robot: null }] }]);
    assert.deepEqual(ui.temporaryRoots, [[fs.realpathSync(target), fs.realpathSync(source)]]);
    assert.ok(ui.infos.some(message => message.includes("after VS Code closes")));
  });
});

test("cancelled final review and unacknowledged Move create neither policy nor temporary lease", async () => {
  for (const move of [false, true]) await fixture(async () => {
    ui.picks = [move ? "Move finished files" : "Copy and keep originals"];
    ui.confirmSetup = move;
    await ui.commands.get("wpilog-mcp.explorer.setupDataStore")!();
    assert.deepEqual(ui.errors, []); assert.deepEqual(ui.temporaryRoots, []);
    assert.equal(ui.controls.filter(call => call.method === "PUT").length, 0);
  });
});

test("Move records explicit finished-file consent and revision conflicts never overwrite or retry", async () => {
  await fixture(async (_explorer, _listing, _source, target) => {
    ui.picks = ["Move finished files"]; ui.confirmFinished = true; ui.conflict = true;
    await ui.commands.get("wpilog-mcp.explorer.setupDataStore")!();
    const saves = ui.controls.filter(call => call.method === "PUT");
    assert.equal(saves.length, 1); assert.equal(saves[0].body?.store, fs.realpathSync(target));
    assert.equal(saves[0].body?.sources[0].completed_files, true); assert.equal(saves[0].body?.sources[0].mode, "move");
    assert.equal(ui.errors.length, 1); assert.match(ui.errors[0], /Nothing was overwritten/);
    assert.equal(ui.policy?.revision, 0);
  });
});

test("an already configured shared server is not offered setup by another editor", async () => {
  await fixture(async (explorer, listing, source, target) => {
    ui.policy = { revision: 4, enabled: true, store: target, store_id: "saved", sources: [
      { path: source, enabled: true, mode: "copy", completed_files: false, stated_robot: null }] };
    await explorer.offerOrganizing(listing);
    assert.equal(ui.dialogs, 0); assert.deepEqual(ui.infos, []); assert.deepEqual(ui.temporaryRoots, []);
  });
});


test("the source toggle requests the shared all view and restores the server default", async () => {
  await fixture(async (explorer, _listing, _source, target) => {
    ui.policy = { revision: 1, enabled: true, store: target, store_id: "store", sources: [] };
    const listing = () => Object.getPrototypeOf(explorer.logs).listing.call(explorer.logs, explorer.spec());
    await listing();
    await ui.commands.get("wpilog-mcp.explorer.showAllSources")!(); await listing();
    await ui.commands.get("wpilog-mcp.explorer.showAllSources")!(); await listing();
    assert.deepEqual(ui.toolArgs, [{ limit: 500, offset: 0 }, { limit: 500, offset: 0, view: "all" }, { limit: 500, offset: 0 }]);
  });
});


test("manual setup does not race an automatic offer caused by its own log listing", async () => {
  await fixture(async (explorer) => {
    ui.picks = ["Copy and keep originals"];
    explorer.logs.listing = Object.getPrototypeOf(explorer.logs).listing.bind(explorer.logs);
    await ui.commands.get("wpilog-mcp.explorer.setupDataStore")!();
    assert.deepEqual(ui.errors, []);
    assert.ok(!ui.infos.some(message => message.startsWith("Create a data store")), "Manual setup must suppress the automatic offer while it lists logs");
  });
});


test("pausing an enrolled store does not re-register an offline source directory", async () => {
  await fixture(async (_explorer, _listing, source, target) => {
    ui.policy = { revision: 2, enabled: true, store: target, store_id: "saved", sources: [
      { path: path.join(source, "offline-drive"), enabled: true, mode: "copy", completed_files: false, stated_robot: null }] };
    await ui.commands.get("wpilog-mcp.explorer.pauseDataStore")!();
    assert.deepEqual(ui.errors, []);
    assert.deepEqual(ui.temporaryRoots, [], "Pause must not require an unavailable enrolled directory to exist");
    assert.equal(ui.policy.enabled, false); assert.equal(ui.policy.revision, 3);
  });
});


test("experimental store menus start disabled and automatic discovery stays silent without server opt-in", async () => {
  await fixture(async (explorer, listing) => {
    assert.equal(ui.contexts.get("wpilog-mcp.dataStoreEnabled"), false, "Data-store commands must start hidden");
    ui.featureEnabled = false;
    await explorer.offerOrganizing(listing);
    assert.equal(ui.contexts.get("wpilog-mcp.dataStoreEnabled"), false);
    assert.deepEqual(ui.infos, []); assert.deepEqual(ui.errors, []); assert.deepEqual(ui.output, []);
    assert.deepEqual(ui.requests, [], "Disabled setup must not fall back to one-shot organizing");
    assert.equal(ui.dialogs, 0);
  });
});

test("disabled store commands cannot bypass opt-in, including the all-sources toggle", async () => {
  for (const name of ["setupDataStore", "manageDataStore", "syncDataStore", "pauseDataStore", "showAllSources"]) {
    await fixture(async (explorer) => {
      ui.featureEnabled = false;
      await ui.commands.get(`wpilog-mcp.explorer.${name}`)!();
      assert.equal(ui.errors.length, 1, `${name} must explain the disabled feature`);
      assert.match(ui.errors[0], /experimental_data_store: true/); assert.match(ui.errors[0], /restart/);
      assert.doesNotMatch(ui.errors[0], /settings changed in another editor/);
      assert.equal(ui.dialogs, 0); assert.deepEqual(ui.temporaryRoots, []); assert.deepEqual(ui.requests, []);
      assert.ok(ui.controls.every(call => call.method === "GET"));
      await Object.getPrototypeOf(explorer.logs).listing.call(explorer.logs, explorer.spec());
      assert.deepEqual(ui.toolArgs, [{ limit: 500, offset: 0 }], "Disabled all-sources command must not toggle the listing");
    });
  }
});

test("listing discovers opt-in even when setup is suppressed or the listing already contains only stored logs", async () => {
  for (const suppressed of ["never", "configured", "empty", "already-offered"]) await fixture(async (explorer, listing, _source, target) => {
    if (suppressed === "never") ui.neverOffer = true;
    if (suppressed === "configured") {
      ui.policy = { revision: 1, enabled: true, store: target, store_id: "saved", sources: [] };
      listing.logs = [{ ...listing.logs![0], store: target }];
    }
    if (suppressed === "empty") listing.logs = [];
    if (suppressed === "already-offered") await explorer.offerOrganizing(listing);
    ui.infos = [];
    await explorer.offerOrganizing(listing);
    assert.equal(ui.contexts.get("wpilog-mcp.dataStoreEnabled"), true, suppressed);
    assert.deepEqual(ui.infos, []);
    ui.featureEnabled = false;
    await explorer.offerOrganizing(listing);
    assert.equal(ui.contexts.get("wpilog-mcp.dataStoreEnabled"), false, "A restarted server disabling the feature hides its commands");
    assert.deepEqual(ui.output, []);
  });
});
