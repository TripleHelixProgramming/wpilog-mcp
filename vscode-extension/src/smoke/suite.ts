/** Runs inside the real extension host, with actual VS Code commands and tree items. */
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import { readSourceUri } from "../sourceUri";

interface LogItem extends vscode.TreeItem { node: { kind: string; log?: { path: string } }; }
interface Api {
  ready: Promise<void>;
  smokeData(names: string[], start: number, end: number, append?: boolean): Promise<number>;
  logs: { getChildren(item?: LogItem): Promise<LogItem[]> };
}

async function within<T>(task: PromiseLike<T>, label: string): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  try {
    return await Promise.race([task, new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error(`Timed out: ${label}`)), 30_000);
    })]);
  } finally { clearTimeout(timer); }
}

// Report an assertion as a failing test count, not an extension-host infrastructure error
// (older editors can show the latter as a modal dialog instead of exiting in headless CI).
export function run(_testsPath: string, done: (error: Error | null, failures: number) => void): void {
  void smoke().then(() => done(null, 0), () => done(null, 1));
}

async function smoke(): Promise<void> {
  const fixture = process.env.WPILOG_SMOKE_FIXTURE!, testHome = process.env.WPILOG_SMOKE_HOME!;
  const result = { vscode: vscode.version, status: "running", checks: [] as string[], error: undefined as string | undefined };
  const record = (check: string) => { result.checks.push(check); console.log(`SMOKE PASS: ${check}`); };
  try {
    const extension = vscode.extensions.getExtension<Api>("TripleHelixProgramming.wpilog-analyzer");
    assert.ok(extension, "development extension is discovered");
    const api = await within(extension.activate(), "activation");
    assert.ok(extension.isActive, "extension activated in VS Code");
    assert.ok(typeof api?.ready?.then === "function" && api?.logs, "activation exposes only readiness and the actual Logs provider's read interface");
    await within(api.ready, "activation readiness");
    // Local services are opt-in: nothing starts the shared server until a request needs it.
    const pidFile = path.join(testHome, ".wpilog-mcp", "run", "http.pid");
    assert.ok(!fs.existsSync(pidFile), "activation alone starts no shared server");
    record("activation");

    // What the search walked, for a failure message: a missing fixture otherwise hides the reason.
    const walked: string[] = [];
    const find = async (parent?: LogItem, depth = 0): Promise<LogItem | undefined> => {
      for (const item of await api.logs.getChildren(parent)) {
        walked.push(`${"  ".repeat(depth)}${item.node.kind}: ${String(item.label)}${item.description ? ` (${String(item.description)})` : ""}`);
        if (item.node.kind === "log" && item.node.log?.path === fixture) return item;
        if (item.collapsibleState !== vscode.TreeItemCollapsibleState.None) {
          const child = await find(item, depth + 1); if (child) return child;
        }
      }
      return undefined;
    };
    // Listing local logs is the first request that needs the local server. A refresh (the server
    // starting, an agent connecting) cancels a listing in flight, and the tree view asks again
    // when told to; this check asks again too, until the fixture appears or a deadline passes.
    let item: LogItem | undefined;
    for (const deadline = Date.now() + 30_000; ;) {
      walked.length = 0;
      item = await within(find(), "Logs tree listing");
      if (item || Date.now() > deadline) break;
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    assert.ok(fs.existsSync(pidFile), "listing local logs starts the shared daemon in the isolated home");
    const [, port] = fs.readFileSync(pidFile, "utf8").split(/\r?\n/);
    assert.ok(Number(port) > 0, "start records the chosen port");
    const base = `http://127.0.0.1:${port}`;
    const health = await within(fetch(`${base}/health`), "daemon health");
    assert.equal(health.status, 200);
    const info = await health.json() as { version: string };
    assert.equal(info.version, extension.packageJSON.version);
    record("listing local logs starts the shared standalone server");

    assert.ok(item, `actual Logs provider lists the generated fixture, through its window lease; it walked:\n${walked.join("\n") || "(nothing)"}`);
    record("Logs tree lists generated fixture");

    assert.equal(item.command?.command, "wpilog-mcp.explorer.openLog");
    await within(vscode.commands.executeCommand(item.command!.command, ...item.command!.arguments!), "fixture editor opens");
    assert.ok(vscode.window.tabGroups.all.flatMap(group => group.tabs).some(tab =>
      tab.input instanceof vscode.TabInputCustom && tab.input.viewType === "wpilog-mcp.explorer"
      // The editor opens a source document, whose address names the connection, server, scope and path.
      && tab.input.uri.scheme === "wpilog-source" && readSourceUri(tab.input.uri.toString()).path === fixture),
      "the custom editor, not a text editor, opened this fixture");
    record("custom editor opens the fixture");

    assert.equal(await within(api.smokeData(["/Value"], 0, 1), "Perspective fixture rows"), 2);
    fs.appendFileSync(fixture, fs.readFileSync(path.join(path.dirname(fixture), "append.bin")));
    assert.equal(await within(api.smokeData(["/Value"], 0, 2, true), "Perspective appended rows"), 3);
    record("data view renders two fixture samples and appends the third through its worker");

    const commands = await vscode.commands.getCommands(true);
    for (const name of ["setupDataStore", "manageDataStore", "syncDataStore", "pauseDataStore"]) {
      assert.ok(commands.includes(`wpilog-mcp.explorer.${name}`), `data-store command is registered: ${name}`);
    }
    assert.ok(!commands.includes("wpilog-mcp.explorer.showAllSources"), "obsolete source toggle is absent");
    assert.ok(await within(find(), "source tree listing"), "source scopes preserve ordinary log access");
    record("data-store commands preserve ordinary log access without a source toggle");

    const url = `${base}/mcp`;
    await vscode.workspace.getConfiguration("wpilog-mcp").update("pitServerUrl", url, vscode.ConfigurationTarget.Global);
    await within(vscode.commands.executeCommand("wpilog-mcp.registerPitWithClaudeCode"), "pit registration command");
    const args = JSON.parse(fs.readFileSync(process.env.WPILOG_SMOKE_CALLS!, "utf8"));
    // Since direct connections the pit is registered as Claude Code's own HTTP server: no launcher.
    assert.deepEqual(args, ["mcp", "add", "--scope", "user", "wpilog-pit", "--transport", "http", url],
    "only a user-scope URL registration reaches Claude; no key, password, headers or local log paths");
    assert.ok(!fs.existsSync(path.join(vscode.workspace.workspaceFolders![0].uri.fsPath, ".mcp.json")));
    record("pit command produces secret-free user-scope arguments");
    result.status = "ok";
  } catch (error) {
    result.status = "failed";
    result.error = error instanceof Error ? error.stack : String(error);
    console.error(error);
    throw error;
  } finally {
    fs.writeFileSync(process.env.WPILOG_SMOKE_RESULT!, JSON.stringify(result, null, 2) + "\n");
  }
}
