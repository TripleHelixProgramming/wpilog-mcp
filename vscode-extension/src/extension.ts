import { execFile } from "child_process";
import * as crypto from "crypto";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import { findJava } from "./javaFinder";
import { findLogDirectories } from "./logFinder";
import { addLogDirectories } from "./logDirectories";
import { findJar } from "./jarManager";
import {
  GitStatus,
  addToGitignore,
  buildServerEntry,
  gitAction,
  hasServerEntry,
  mergeServerEntry,
  otherWpilogServer,
  scrubTbaKey,
  shouldWriteEntry,
  writeMode,
} from "./mcpJson";

const PROVIDER_ID = "wpilog-analyzer.mcpServer";

/** Where the TBA API key is kept: VS Code's secret storage (the OS keychain), never a file. */
const TBA_SECRET = "wpilog-mcp.tbaApiKey";

const TBA_ACCOUNT_URL = "https://www.thebluealliance.com/account";

export function activate(context: vscode.ExtensionContext) {
  const outputChannel = vscode.window.createOutputChannel("WPILog Analyzer");
  const didChangeEmitter = new vscode.EventEmitter<void>();

  /**
   * Resolves the MCP server command and args from VS Code settings. The TBA API key is passed
   * only in the environment (TBA_API_KEY): a command-line argument is visible to other users of
   * the machine in the process list.
   */
  async function resolveServerConfig(): Promise<
    { command: string; args: string[]; env: Record<string, string> } | undefined
  > {
    const config = vscode.workspace.getConfiguration("wpilog-mcp");
    const maxHeap = config.get<string>("maxHeap") || "4g";

    const javaPath = await findJava();
    if (!javaPath) {
      outputChannel.appendLine("ERROR: Java 17+ not found.");
      vscode.window
        .showErrorMessage(
          "WPILog Analyzer: Java 17+ is required. Install the WPILib toolkit or set wpilog-mcp.javaPath.",
          "Open Settings"
        )
        .then((choice) => {
          if (choice === "Open Settings") {
            vscode.commands.executeCommand(
              "workbench.action.openSettings",
              "wpilog-mcp.javaPath"
            );
          }
        });
      return undefined;
    }

    const jarPath = findJar(context.extensionPath);
    if (!jarPath) {
      outputChannel.appendLine("ERROR: wpilog-mcp JAR not found.");
      vscode.window.showErrorMessage(
        "WPILog Analyzer: Server JAR not found. Try reinstalling the extension."
      );
      return undefined;
    }

    outputChannel.appendLine(`Java: ${javaPath}`);
    outputChannel.appendLine(`JAR: ${jarPath}`);

    const logDirs = await findLogDirectories();
    outputChannel.appendLine(
      `Log directories: ${logDirs.length > 0 ? logDirs.join(", ") : "(none)"}`
    );

    const teamNumber = config.get<number>("teamNumber") || 0;
    const tbaKey = (await context.secrets.get(TBA_SECRET)) || "";

    // The server reads WPILOG_DIR, WPILOG_TEAM, and TBA_API_KEY from its environment and also
    // accepts -logdir and -team; the key goes only in the environment.
    const args = [`-Xmx${maxHeap}`, "-jar", jarPath];
    const env: Record<string, string> = {};

    addLogDirectories(args, env, logDirs);
    if (teamNumber > 0) {
      args.push("-team", String(teamNumber));
      env["WPILOG_TEAM"] = String(teamNumber);
    }
    if (tbaKey) {
      env["TBA_API_KEY"] = tbaKey;
    }

    return { command: javaPath, args, env };
  }

  // ---- VS Code MCP provider ----

  const provider: vscode.McpServerDefinitionProvider = {
    onDidChangeMcpServerDefinitions: didChangeEmitter.event,

    provideMcpServerDefinitions: async () => {
      const resolved = await resolveServerConfig();
      if (!resolved) {
        return [];
      }

      outputChannel.appendLine(
        `Starting: ${resolved.command} ${resolved.args.join(" ")}`
      );
      const envKeys = Object.keys(resolved.env);
      if (envKeys.length > 0) {
        outputChannel.appendLine(
          `Env: ${envKeys.map(k => k === "TBA_API_KEY" ? "TBA_API_KEY=(set)" : `${k}=${resolved.env[k]}`).join(", ")}`
        );
      }

      return [
        new vscode.McpStdioServerDefinition(
          "WPILog Analyzer",
          resolved.command,
          resolved.args,
          resolved.env,
          context.extension.packageJSON.version,
        ),
      ];
    },

    resolveMcpServerDefinition: async (server) => {
      return server;
    },
  };

  context.subscriptions.push(
    vscode.lm.registerMcpServerDefinitionProvider(PROVIDER_ID, provider)
  );

  // ---- The TBA API key ----

  context.subscriptions.push(
    vscode.commands.registerCommand("wpilog-mcp.setTbaApiKey", async () => {
      const key = await vscode.window.showInputBox({
        title: "The Blue Alliance API Key",
        prompt: `Paste your read API key from ${TBA_ACCOUNT_URL}. It is kept in VS Code's secret storage.`,
        password: true,
        ignoreFocusOut: true,
      });
      if (key === undefined) {
        return; // cancelled
      }
      if (key.trim() === "") {
        vscode.window.showWarningMessage(
          "WPILog Analyzer: No key entered; the TBA API key was not changed."
        );
        return;
      }
      await context.secrets.store(TBA_SECRET, key.trim());
      vscode.window.showInformationMessage(
        "WPILog Analyzer: TBA API key saved. The server restarts to use it."
      );
    }),
    vscode.commands.registerCommand("wpilog-mcp.clearTbaApiKey", async () => {
      await context.secrets.delete(TBA_SECRET);
      await removeTbaKeyFile(context);
      vscode.window.showInformationMessage(
        "WPILog Analyzer: TBA API key removed. The server restarts without it."
      );
    }),
    // A stored or removed key restarts the server, and changes Claude Code's entry and key file
    context.secrets.onDidChange((e) => {
      if (e.key === TBA_SECRET) {
        didChangeEmitter.fire();
        scheduleMcpJsonUpdate();
      }
    })
  );

  // ---- Claude Code's .mcp.json ----

  // One update at a time, so overlapping triggers neither race on the files nor repeat a notice
  let mcpJsonUpdate: Promise<void> = Promise.resolve();
  function scheduleMcpJsonUpdate() {
    mcpJsonUpdate = mcpJsonUpdate
      .then(() => updateMcpJsonFiles(context, outputChannel))
      .catch((e) => outputChannel.appendLine(`Failed to update .mcp.json: ${e}`));
  }

  // Re-register when settings change, and update .mcp.json
  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration("wpilog-mcp.tbaApiKey")) {
        void moveTbaKeyToSecretStorage(context, outputChannel);
      }
      if (e.affectsConfiguration("wpilog-mcp")) {
        outputChannel.appendLine("Settings changed, restarting MCP server...");
        didChangeEmitter.fire();
        scheduleMcpJsonUpdate();
      }
    }),
    vscode.workspace.onDidChangeWorkspaceFolders(() => scheduleMcpJsonUpdate())
  );

  void (async () => {
    await moveTbaKeyToSecretStorage(context, outputChannel);
    await removeTbaKeyFromMcpJson(outputChannel);
    // Add or update Claude Code's entry in .mcp.json (robot projects, by default)
    scheduleMcpJsonUpdate();
  })();

  context.subscriptions.push(outputChannel);
  context.subscriptions.push(didChangeEmitter);

  outputChannel.appendLine("WPILog Analyzer extension activated.");
}

/**
 * Moves a TBA API key from the (deprecated) `wpilog-mcp.tbaApiKey` setting into secret storage
 * and clears it from settings, where it was plaintext in settings.json.
 */
async function moveTbaKeyToSecretStorage(
  context: vscode.ExtensionContext,
  outputChannel: vscode.OutputChannel
) {
  const config = vscode.workspace.getConfiguration("wpilog-mcp");
  const inspected = config.inspect<string>("tbaApiKey");
  const workspaceKey = inspected?.workspaceValue?.trim() || "";
  const globalKey = inspected?.globalValue?.trim() || "";
  const key = workspaceKey || globalKey;
  if (!key) {
    return;
  }

  if (!(await context.secrets.get(TBA_SECRET))) {
    await context.secrets.store(TBA_SECRET, key);
  }
  if (inspected?.globalValue !== undefined) {
    await config.update("tbaApiKey", undefined, vscode.ConfigurationTarget.Global);
  }
  if (inspected?.workspaceValue !== undefined) {
    await config.update("tbaApiKey", undefined, vscode.ConfigurationTarget.Workspace);
  }
  outputChannel.appendLine("Moved the TBA API key from settings into secret storage.");

  const where = workspaceKey
    ? "the workspace's .vscode/settings.json"
    : "your user settings";
  const message =
    `WPILog Analyzer: Your TBA API key was in ${where} in plaintext; it is now kept in ` +
    "VS Code's secret storage and removed from settings." +
    (workspaceKey
      ? " If that settings file was committed or shared, revoke the key and set a new one " +
        "(WPILog Analyzer: Set The Blue Alliance API Key)."
      : "");
  const choice = await vscode.window.showInformationMessage(
    message,
    ...(workspaceKey ? ["Open TBA Account"] : [])
  );
  if (choice === "Open TBA Account") {
    vscode.env.openExternal(vscode.Uri.parse(TBA_ACCOUNT_URL));
  }
}

/** Each local workspace folder's .mcp.json (Claude Code reads the one in the folder it runs in). */
function mcpJsonFiles(): { folder: vscode.WorkspaceFolder; uri: vscode.Uri }[] {
  return (vscode.workspace.workspaceFolders ?? [])
    .filter((folder) => folder.uri.scheme === "file")
    .map((folder) => ({ folder, uri: vscode.Uri.joinPath(folder.uri, ".mcp.json") }));
}

/** The file's text, or undefined when it does not exist. */
async function readText(uri: vscode.Uri): Promise<string | undefined> {
  try {
    return Buffer.from(await vscode.workspace.fs.readFile(uri)).toString("utf8");
  } catch {
    return undefined;
  }
}

async function exists(uri: vscode.Uri): Promise<boolean> {
  try {
    await vscode.workspace.fs.stat(uri);
    return true;
  } catch {
    return false;
  }
}

/**
 * Removes a TBA API key that earlier versions wrote into this server's .mcp.json entry in
 * plaintext, whatever the writeMcpJson setting: a file in the workspace root is easily committed.
 */
async function removeTbaKeyFromMcpJson(outputChannel: vscode.OutputChannel) {
  for (const { uri } of mcpJsonFiles()) {
    const edit = scrubTbaKey(await readText(uri));
    if (!edit.ok || !edit.changed) {
      continue;
    }
    try {
      await vscode.workspace.fs.writeFile(uri, Buffer.from(edit.text));
    } catch (e) {
      outputChannel.appendLine(`Failed to remove the TBA API key from ${uri.fsPath}: ${e}`);
      continue;
    }
    outputChannel.appendLine(`Removed the TBA API key from ${uri.fsPath}.`);
    const choice = await vscode.window.showWarningMessage(
      "WPILog Analyzer: An earlier version wrote your TBA API key into .mcp.json in plaintext; " +
        "it has been removed. If that file was ever committed or shared, revoke the key and set " +
        "a new one (WPILog Analyzer: Set The Blue Alliance API Key).",
      "Open TBA Account"
    );
    if (choice === "Open TBA Account") {
      vscode.env.openExternal(vscode.Uri.parse(TBA_ACCOUNT_URL));
    }
  }
}

/** A command's exit code, or undefined when it could not be run (git not installed, a timeout). */
function exitCode(command: string, args: string[], cwd: string): Promise<number | undefined> {
  return new Promise((resolve) => {
    execFile(command, args, { cwd, timeout: 5000, windowsHide: true }, (error) => {
      if (!error) {
        resolve(0);
        return;
      }
      const code = (error as { code?: unknown }).code;
      resolve(typeof code === "number" ? code : undefined);
    });
  });
}

/** Whether git tracks, ignores, or would pick up the folder's .mcp.json. */
async function gitStatusOf(folder: string): Promise<GitStatus> {
  const tracked = await exitCode("git", ["ls-files", "--error-unmatch", "--", ".mcp.json"], folder);
  if (tracked === 0) return "tracked";
  if (tracked !== 1) return "none"; // not a repository, or no git
  const ignored = await exitCode("git", ["check-ignore", "-q", "--", ".mcp.json"], folder);
  return ignored === 0 ? "ignored" : ignored === 1 ? "untracked" : "none";
}

async function sameContent(a: string, b: string): Promise<boolean> {
  try {
    const [sa, sb] = await Promise.all([fs.promises.stat(a), fs.promises.stat(b)]);
    if (sa.size !== sb.size) return false;
    const hash = async (file: string) =>
      crypto.createHash("sha256").update(await fs.promises.readFile(file)).digest("hex");
    return (await hash(a)) === (await hash(b));
  } catch {
    return false;
  }
}

/**
 * A copy of the bundled server JAR at a path that does not change when the extension updates
 * (the extension's global storage), for Claude Code's entry: the bundled JAR's path names the
 * extension's version, and VS Code deletes that folder after an update. Refreshed whenever it
 * differs from the bundled JAR, by rename, so a server still running the old copy keeps it. When
 * it cannot be refreshed (a running server holds it on Windows), the previous copy is used and
 * the refresh is retried at the next start.
 */
async function stableJar(
  context: vscode.ExtensionContext,
  bundled: string,
  outputChannel: vscode.OutputChannel
): Promise<string> {
  const target = path.join(context.globalStorageUri.fsPath, "server", "wpilog-mcp-all.jar");
  try {
    if (await sameContent(bundled, target)) {
      return target;
    }
    await fs.promises.mkdir(path.dirname(target), { recursive: true });
    const temp = `${target}.${process.pid}.${Date.now()}.tmp`;
    await fs.promises.copyFile(bundled, temp);
    try {
      await fs.promises.rename(temp, target);
    } finally {
      await fs.promises.rm(temp, { force: true });
    }
    outputChannel.appendLine(`Copied the server JAR for Claude Code to ${target}`);
    return target;
  } catch (e) {
    outputChannel.appendLine(`Could not refresh ${target}: ${e}`);
    return fs.existsSync(target) ? target : bundled;
  }
}

/** Where the TBA key is kept for the server Claude Code starts. */
function tbaKeyFilePath(context: vscode.ExtensionContext): string {
  return path.join(context.globalStorageUri.fsPath, "tba-api-key");
}

async function removeTbaKeyFile(context: vscode.ExtensionContext) {
  await fs.promises.rm(tbaKeyFilePath(context), { force: true });
}

/**
 * The TBA key for the server Claude Code starts, which runs outside VS Code and cannot read its
 * secret storage: written to a file only this user can read, in the extension's global storage,
 * and passed by path (-tba-key-file), so the key is in neither .mcp.json nor the environment and
 * the user sets nothing. Undefined, and the file removed, when no key is stored.
 */
async function syncTbaKeyFile(context: vscode.ExtensionContext): Promise<string | undefined> {
  const file = tbaKeyFilePath(context);
  const key = await context.secrets.get(TBA_SECRET);
  if (!key) {
    await removeTbaKeyFile(context);
    return undefined;
  }
  const current = await fs.promises.readFile(file, "utf8").catch(() => undefined);
  if (current?.trim() !== key) {
    await fs.promises.mkdir(path.dirname(file), { recursive: true });
    const temp = `${file}.${process.pid}.tmp`;
    await fs.promises.writeFile(temp, key + "\n", { mode: 0o600 });
    await fs.promises.rename(temp, file);
  }
  await fs.promises.chmod(file, 0o600);
  return file;
}

/** Shows a message once per workspace (remembered under `key`); returns the button chosen. */
async function showOnce(
  context: vscode.ExtensionContext,
  key: string,
  show: () => Thenable<string | undefined>
): Promise<string | undefined> {
  if (context.workspaceState.get<boolean>(key)) {
    return undefined;
  }
  await context.workspaceState.update(key, true);
  return show();
}

/** Offers, once, to add .mcp.json to the folder's .gitignore. */
async function offerToIgnore(
  context: vscode.ExtensionContext,
  folder: vscode.WorkspaceFolder,
  outputChannel: vscode.OutputChannel
) {
  const add = "Add .mcp.json to .gitignore";
  const choice = await showOnce(context, `wpilog-mcp.gitignoreOffered:${folder.uri}`, () =>
    vscode.window.showInformationMessage(
      `WPILog Analyzer added its server to ${folder.name}/.mcp.json so Claude Code can use it. ` +
        "The entry holds paths for this computer only, and git does not ignore .mcp.json in " +
        "this repository, so it could be committed and shared.",
      add,
      "Not now"
    )
  );
  if (choice !== add) {
    return;
  }
  const gitignore = vscode.Uri.joinPath(folder.uri, ".gitignore");
  try {
    const text = addToGitignore(await readText(gitignore));
    await vscode.workspace.fs.writeFile(gitignore, Buffer.from(text));
    outputChannel.appendLine(`Added .mcp.json to ${gitignore.fsPath}`);
  } catch (e) {
    outputChannel.appendLine(`Failed to update ${gitignore.fsPath}: ${e}`);
  }
}

/**
 * Adds or updates the `wpilog-analyzer` entry in each workspace folder's .mcp.json, which Claude
 * Code reads to find MCP servers (it does not use the McpServerDefinitionProvider API): by
 * default in WPILib robot projects and wherever an entry already exists (see
 * `wpilog-mcp.writeMcpJson`). Every other entry in the file is kept. The entry holds this
 * computer's paths, so a .mcp.json that git tracks (the repository shares it) is left alone, and
 * one git would pick up comes with an offer to ignore it. The entry points at a JAR path that
 * survives extension updates and passes the TBA key by file, never in the entry.
 */
async function updateMcpJsonFiles(
  context: vscode.ExtensionContext,
  outputChannel: vscode.OutputChannel
) {
  const config = vscode.workspace.getConfiguration("wpilog-mcp");
  const mode = writeMode(config.get<unknown>("writeMcpJson"));
  if (mode === "never") {
    return;
  }

  const targets: { folder: vscode.WorkspaceFolder; uri: vscode.Uri; text: string | undefined }[] = [];
  for (const { folder, uri } of mcpJsonFiles()) {
    const text = await readText(uri);
    const robotProject = await exists(
      vscode.Uri.joinPath(folder.uri, ".wpilib", "wpilib_preferences.json")
    );
    if (shouldWriteEntry(mode, robotProject, hasServerEntry(text))) {
      targets.push({ folder, uri, text });
    }
  }
  if (targets.length === 0) {
    return;
  }

  const javaPath = await findJava();
  if (!javaPath) return;
  const bundled = findJar(context.extensionPath);
  if (!bundled) return;

  const entry = buildServerEntry(
    javaPath,
    await stableJar(context, bundled, outputChannel),
    config.get<string>("maxHeap") || "4g",
    await findLogDirectories(false),
    config.get<number>("teamNumber") || 0,
    await syncTbaKeyFile(context)
  );

  for (const { folder, uri, text } of targets) {
    const other = otherWpilogServer(text);
    if (other) {
      outputChannel.appendLine(
        `Left ${uri.fsPath} alone: its "${other}" entry already runs wpilog-mcp for Claude Code.`
      );
      continue;
    }
    const action = gitAction(await gitStatusOf(folder.uri.fsPath));
    if (action === "skipShared") {
      outputChannel.appendLine(`Left ${uri.fsPath} alone: git tracks it (the repository shares it).`);
      void showOnce(context, `wpilog-mcp.sharedMcpJsonNoticed:${folder.uri}`, () =>
        vscode.window.showWarningMessage(
          `WPILog Analyzer did not add its server to ${folder.name}/.mcp.json for Claude Code: ` +
            "git tracks that file, so the repository shares it, and the entry holds paths for " +
            "this computer only. To use WPILog Analyzer from Claude Code here, stop tracking the " +
            "file (git rm --cached .mcp.json), add .mcp.json to .gitignore, and reload the window."
        )
      );
      continue;
    }
    const edit = mergeServerEntry(text, entry);
    if (!edit.ok) {
      outputChannel.appendLine(`Did not update ${uri.fsPath}: ${edit.error}`);
      vscode.window.showWarningMessage(
        `WPILog Analyzer: ${edit.error}, so it was not updated. Fix the file, or set wpilog-mcp.writeMcpJson to never.`
      );
      continue;
    }
    if (edit.changed) {
      try {
        await vscode.workspace.fs.writeFile(uri, Buffer.from(edit.text));
        outputChannel.appendLine(`Updated the wpilog-analyzer entry in ${uri.fsPath}`);
      } catch (e) {
        outputChannel.appendLine(`Failed to write ${uri.fsPath}: ${e}`);
        continue;
      }
    }
    if (action === "writeAndOfferIgnore") {
      void offerToIgnore(context, folder, outputChannel);
    }
  }
}

export function deactivate() {}
