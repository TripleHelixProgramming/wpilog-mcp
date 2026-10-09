/** Runs inside the real extension host, with actual VS Code commands and tree items. */
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";

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
    record("activation");

    await within(api.ready, "shared server startup");
    const pidFile = path.join(testHome, ".wpilog-mcp", "run", "http.pid");
    assert.ok(fs.existsSync(pidFile), "extension starts the shared daemon in the isolated home");
    const [, port] = fs.readFileSync(pidFile, "utf8").split(/\r?\n/);
    assert.ok(Number(port) > 0, "start records the chosen port");
    const base = `http://127.0.0.1:${port}`;
    const health = await within(fetch(`${base}/health`), "daemon health");
    assert.equal(health.status, 200);
    const info = await health.json() as { version: string };
    assert.equal(info.version, extension.packageJSON.version);
    record("shared standalone server starts");

    const find = async (parent?: LogItem): Promise<LogItem | undefined> => {
      for (const item of await api.logs.getChildren(parent)) {
        if (item.node.kind === "log" && item.node.log?.path === fixture) return item;
        if (item.collapsibleState !== vscode.TreeItemCollapsibleState.None) {
          const child = await find(item); if (child) return child;
        }
      }
      return undefined;
    };
    const item = await within(find(), "Logs tree listing");
    assert.ok(item, "actual Logs provider lists the generated fixture, through its window lease");
    record("Logs tree lists generated fixture");

    assert.equal(item.command?.command, "wpilog-mcp.explorer.openLog");
    await within(vscode.commands.executeCommand(item.command!.command, ...item.command!.arguments!), "fixture editor opens");
    assert.ok(vscode.window.tabGroups.all.flatMap(group => group.tabs).some(tab =>
      tab.input instanceof vscode.TabInputCustom && tab.input.viewType === "wpilog-mcp.explorer"
      && tab.input.uri.fsPath === fixture), "the custom editor, not a text editor, opened this fixture");
    record("custom editor opens the fixture");

    assert.equal(await within(api.smokeData(["/Value"], 0, 1), "Perspective fixture rows"), 2);
    fs.appendFileSync(fixture, fs.readFileSync(path.join(path.dirname(fixture), "append.bin")));
    assert.equal(await within(api.smokeData(["/Value"], 0, 2, true), "Perspective appended rows"), 3);
    record("data view renders two fixture samples and appends the third through its worker");

    const url = `${base}/mcp`;
    await vscode.workspace.getConfiguration("wpilog-mcp").update("pitServerUrl", url, vscode.ConfigurationTarget.Global);
    await within(vscode.commands.executeCommand("wpilog-mcp.registerPitWithClaudeCode"), "pit registration command");
    const args = JSON.parse(fs.readFileSync(process.env.WPILOG_SMOKE_CALLS!, "utf8"));
    assert.deepEqual(args, ["mcp", "add", "--scope", "user", "wpilog-pit", "--",
      path.join(testHome, ".wpilog-mcp", "bin", "wpilog-mcp"), "connect", "--url", url],
    "only a user-scope URL bridge reaches Claude; no key, password, headers or local log paths");
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
