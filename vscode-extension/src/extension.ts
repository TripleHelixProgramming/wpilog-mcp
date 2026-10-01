import { execFile } from "child_process";
import * as crypto from "crypto";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import { findJava } from "./javaFinder";
import { findLogDirectories, logDirectoriesFor } from "./logFinder";
import {
  LogSettings,
  addLogDirectories,
  overlaySettings,
  projectConfigName,
} from "./logDirectories";
import { findJar } from "./jarManager";
import {
  GitStatus,
  addToGitignore,
  buildServerConfig,
  buildServerEntry,
  gitAction,
  hasServerEntry,
  otherWpilogServer,
  scrubTbaKey,
  shouldWriteEntry,
} from "./mcpJson";
import { removeTbaKeyFromConfigs, writeConfigFile, writeEntry } from "./projectConfigs";

const PROVIDER_ID = "wpilog-analyzer.mcpServer";

/**
 * Where the TBA API key is kept: VS Code's secret storage (the OS keychain), never a settings
 * file. Claude Code's server, outside VS Code, reads a copy from its configuration file, which
 * only this user can read (see writeProjectConfig).
 */
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
    args.push("-diskcachedir", extensionCacheDir(context));

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
      // Out of Claude Code's configuration files too, even with Claude Code turned off: the
      // remembered projects' are rewritten, and any other file there is cleaned as well
      await refreshKnownProjects(context, new Set());
      const cleaned = await removeTbaKeyFromConfigs(projectsDir(context), new Set());
      for (const file of [...cleaned.scrubbed, ...cleaned.removed]) {
        outputChannel.appendLine(`Removed the TBA API key from ${file}`);
      }
      vscode.window.showInformationMessage(
        "WPILog Analyzer: TBA API key removed. The server restarts without it."
      );
    }),
    // A stored or removed key restarts the server, and rewrites Claude Code's configuration file
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
  function scheduleMcpJsonUpdate(requested?: vscode.WorkspaceFolder) {
    mcpJsonUpdate = mcpJsonUpdate
      .then(() => updateMcpJsonFiles(context, outputChannel, requested))
      .catch((e) => outputChannel.appendLine(`Failed to update .mcp.json: ${e}`));
    return mcpJsonUpdate;
  }

  // For a folder that is not a robot project (robot projects get the entry by themselves)
  context.subscriptions.push(
    vscode.commands.registerCommand("wpilog-mcp.addToClaudeCode", async () => {
      const folders = (vscode.workspace.workspaceFolders ?? []).filter(
        (f) => f.uri.scheme === "file"
      );
      if (folders.length === 0) {
        vscode.window.showWarningMessage(
          "WPILog Analyzer: Open a folder first; Claude Code finds the server in that folder's .mcp.json."
        );
        return;
      }
      const folder =
        folders.length === 1
          ? folders[0]
          : await vscode.window.showWorkspaceFolderPick({
              placeHolder: "Folder in which Claude Code should find WPILog Analyzer",
            });
      if (folder) {
        await scheduleMcpJsonUpdate(folder);
      }
    })
  );

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
 * plaintext, whatever the enableForClaudeCode setting: a file in the workspace root is easily
 * committed.
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

/**
 * The projects that have Claude Code's entry, each with its own settings as last seen, so that a
 * change to the user's settings rewrites their configuration files too, open or not.
 */
const PROJECTS_KEY = "wpilog-mcp.claudeCodeProjects";
type KnownProjects = Record<string, LogSettings>;

/** The user's values: User settings, else the defaults. */
function userSettings(): LogSettings {
  const config = vscode.workspace.getConfiguration("wpilog-mcp");
  const value = <T>(key: string) => {
    const inspected = config.inspect<T>(key);
    return inspected?.globalValue ?? inspected?.defaultValue;
  };
  return {
    logDirectory: value<string>("logDirectory"),
    additionalLogDirectories: value<unknown>("additionalLogDirectories"),
    teamNumber: value<number>("teamNumber"),
  };
}

/** A folder's own values (its folder settings, else the workspace's), undefined where it has none. */
function projectSettings(folder: vscode.WorkspaceFolder): LogSettings {
  const config = vscode.workspace.getConfiguration("wpilog-mcp", folder.uri);
  const value = <T>(key: string) => {
    const inspected = config.inspect<T>(key);
    return inspected?.workspaceFolderValue ?? inspected?.workspaceValue;
  };
  return {
    logDirectory: value<string>("logDirectory"),
    additionalLogDirectories: value<unknown>("additionalLogDirectories"),
    teamNumber: value<number>("teamNumber"),
  };
}

/** Where the projects' configuration files are, in the extension's storage. */
function projectsDir(context: vscode.ExtensionContext): string {
  return path.join(context.globalStorageUri.fsPath, "projects");
}

/** A project's configuration file, in the extension's storage. */
function projectConfigPath(context: vscode.ExtensionContext, folderPath: string): string {
  return path.join(projectsDir(context), projectConfigName(folderPath));
}

/**
 * The disk cache of every server the extension starts (Copilot's and Claude Code's), in its own
 * storage: never the standalone install's cache, so the two never delete each other's files when
 * their cache formats differ, and uninstalling the extension removes it.
 */
function extensionCacheDir(context: vscode.ExtensionContext): string {
  return path.join(context.globalStorageUri.fsPath, "cache");
}

/**
 * Writes one project's configuration file, which its .mcp.json entry starts the server with, as
 * the standalone install's servers.yaml does for it: the log directories (relative ones inside
 * the project), the team number, the TBA key, and the extension's own disk cache. The server
 * Claude Code starts runs outside VS Code and cannot read its settings or secret storage; this
 * file, which only this user can read, is where it finds them, so the user sets nothing. Written
 * only when its contents change.
 */
async function writeProjectConfig(
  context: vscode.ExtensionContext,
  folderPath: string,
  settings: LogSettings
): Promise<string> {
  const text = buildServerConfig({
    logDirs: logDirectoriesFor(settings, folderPath),
    teamNumber: settings.teamNumber || 0,
    tbaKey: await context.secrets.get(TBA_SECRET),
    cacheDir: extensionCacheDir(context),
  });
  const file = projectConfigPath(context, folderPath);
  await writeConfigFile(file, text);
  return file;
}

/** Remembers a project that has the entry, with its own settings as they are now. */
async function rememberProject(
  context: vscode.ExtensionContext,
  folderPath: string,
  own: LogSettings
) {
  const known = context.globalState.get<KnownProjects>(PROJECTS_KEY) ?? {};
  known[folderPath] = own;
  await context.globalState.update(PROJECTS_KEY, known);
}

/**
 * Rewrites the configuration file of every known project except `skip` (those just written),
 * from the user's settings and the project's own as last seen; a project's own settings changed
 * while it was closed are picked up when it is next opened. A project whose .mcp.json no longer
 * has the entry is forgotten and its file removed.
 */
async function refreshKnownProjects(context: vscode.ExtensionContext, skip: Set<string>) {
  const known = context.globalState.get<KnownProjects>(PROJECTS_KEY) ?? {};
  const user = userSettings();
  let forgotten = false;
  for (const [folderPath, own] of Object.entries(known)) {
    if (skip.has(folderPath)) continue;
    const text = await fs.promises
      .readFile(path.join(folderPath, ".mcp.json"), "utf8")
      .catch(() => undefined);
    if (!hasServerEntry(text)) {
      delete known[folderPath];
      forgotten = true;
      await fs.promises.rm(projectConfigPath(context, folderPath), { force: true });
      continue;
    }
    await writeProjectConfig(context, folderPath, overlaySettings(user, own));
  }
  if (forgotten) {
    await context.globalState.update(PROJECTS_KEY, known);
  }
  // A configuration file of no remembered project never holds the key: nothing refreshes it
  // when the key changes or is cleared
  const remembered = new Set(
    [...Object.keys(known), ...skip].map((folderPath) => projectConfigPath(context, folderPath))
  );
  await removeTbaKeyFromConfigs(projectsDir(context), remembered);
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
        "The entry holds paths for this computer only, so it would be no use to teammates if " +
        "committed, and git does not ignore .mcp.json in this repository.",
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
 * Adds or updates the `wpilog-analyzer` entry in each workspace folder's .mcp.json, which only
 * Claude Code reads (it does not use the McpServerDefinitionProvider API): with
 * `wpilog-mcp.enableForClaudeCode` on, in WPILib robot projects and wherever an entry already
 * exists, and in `requested`, the folder the user named with the Add to Claude Code command
 * (whose outcome is then reported directly). Then rewrites the configuration files of the other
 * projects that have the entry, so a change to the user's settings reaches them too.
 */
async function updateMcpJsonFiles(
  context: vscode.ExtensionContext,
  outputChannel: vscode.OutputChannel,
  requested?: vscode.WorkspaceFolder
) {
  const config = vscode.workspace.getConfiguration("wpilog-mcp");
  const enabled = config.get<boolean>("enableForClaudeCode", true);
  if (!enabled && !requested) {
    return;
  }

  const isRequested = (folder: vscode.WorkspaceFolder) =>
    requested !== undefined && folder.uri.toString() === requested.uri.toString();
  const targets: McpJsonTarget[] = [];
  for (const { folder, uri } of mcpJsonFiles()) {
    const text = await readText(uri);
    const robotProject = await exists(
      vscode.Uri.joinPath(folder.uri, ".wpilib", "wpilib_preferences.json")
    );
    if (shouldWriteEntry(enabled, robotProject, hasServerEntry(text), isRequested(folder))) {
      targets.push({ folder, uri, text, asked: isRequested(folder) });
    }
  }
  const written = await writeEntries(context, outputChannel, targets);
  if (enabled) {
    await refreshKnownProjects(context, written);
  }
}

interface McpJsonTarget {
  folder: vscode.WorkspaceFolder;
  uri: vscode.Uri;
  text: string | undefined;
  /** The user named this folder (the Add command): report the outcome now. */
  asked: boolean;
}

/**
 * Writes each target folder's configuration file and its .mcp.json entry, which only starts the
 * server (from a JAR path that survives extension updates) with that file. Every other entry in
 * .mcp.json is kept. The entry holds this computer's paths, so a .mcp.json that git tracks (the
 * repository shares it) is left alone, and one git would pick up comes with an offer to ignore
 * it. Returns the folders written.
 */
async function writeEntries(
  context: vscode.ExtensionContext,
  outputChannel: vscode.OutputChannel,
  targets: McpJsonTarget[]
): Promise<Set<string>> {
  const written = new Set<string>();
  if (targets.length === 0) return written;
  const javaPath = await findJava();
  if (!javaPath) return written;
  const bundled = findJar(context.extensionPath);
  if (!bundled) return written;
  const jar = await stableJar(context, bundled, outputChannel);
  const maxHeap = vscode.workspace.getConfiguration("wpilog-mcp").get<string>("maxHeap") || "4g";
  const user = userSettings();

  for (const { folder, uri, text, asked } of targets) {
    // A folder the user asked for hears the outcome now; any other, once at most
    const other = otherWpilogServer(text);
    if (other) {
      outputChannel.appendLine(
        `Left ${uri.fsPath} alone: its "${other}" entry already runs wpilog-mcp for Claude Code.`
      );
      if (asked) {
        vscode.window.showInformationMessage(
          `WPILog Analyzer: ${folder.name}/.mcp.json already runs wpilog-mcp as "${other}", so ` +
            "Claude Code already finds it there; no second entry was added."
        );
      }
      continue;
    }
    const action = gitAction(await gitStatusOf(folder.uri.fsPath));
    if (action === "skipShared") {
      outputChannel.appendLine(`Left ${uri.fsPath} alone: git tracks it (the repository shares it).`);
      const message =
        `WPILog Analyzer did not add its server to ${folder.name}/.mcp.json for Claude Code: ` +
        "git tracks that file, and the entry holds paths for this computer only, so adding it " +
        "would change the shared file for everyone (and each teammate's extension would change " +
        "it again). To use WPILog Analyzer from Claude Code here, stop tracking the file " +
        "(git rm --cached .mcp.json), add .mcp.json to .gitignore, and reload the window.";
      if (asked) {
        vscode.window.showWarningMessage(message);
      } else {
        void showOnce(context, `wpilog-mcp.sharedMcpJsonNoticed:${folder.uri}`, () =>
          vscode.window.showWarningMessage(message)
        );
      }
      continue;
    }

    // This project's settings: the user's, with the project's own on top. Its configuration
    // file holds the TBA key, so it is written only once .mcp.json can take the entry, and
    // does not outlive an entry that could not be written.
    const own = projectSettings(folder);
    const configPath = projectConfigPath(context, folder.uri.fsPath);
    const outcome = await writeEntry(
      text,
      buildServerEntry(javaPath, jar, maxHeap, configPath),
      {
        writeConfig: async () => {
          await writeProjectConfig(context, folder.uri.fsPath, overlaySettings(user, own));
        },
        removeConfig: () => fs.promises.rm(configPath, { force: true }),
        writeMcpJson: async (updated) => {
          await vscode.workspace.fs.writeFile(uri, Buffer.from(updated));
        },
      }
    );
    if (!outcome.ok) {
      if (outcome.refused) {
        outputChannel.appendLine(`Did not update ${uri.fsPath}: ${outcome.error}`);
        vscode.window.showWarningMessage(
          `WPILog Analyzer: ${outcome.error}, so it was not updated for Claude Code. Fix the file, or turn off wpilog-mcp.enableForClaudeCode.`
        );
      } else {
        outputChannel.appendLine(`Failed to write ${uri.fsPath}: ${outcome.error}`);
        if (asked) {
          vscode.window.showErrorMessage(
            `WPILog Analyzer: could not write ${uri.fsPath}: ${outcome.error}`
          );
        }
      }
      if (outcome.entryExists) {
        // Its earlier entry still starts the server with the configuration file just written
        await rememberProject(context, folder.uri.fsPath, own);
        written.add(folder.uri.fsPath);
      }
      continue;
    }
    if (outcome.changed) {
      outputChannel.appendLine(`Updated the wpilog-analyzer entry in ${uri.fsPath}`);
    }
    await rememberProject(context, folder.uri.fsPath, own);
    written.add(folder.uri.fsPath);
    if (asked) {
      vscode.window.showInformationMessage(
        `WPILog Analyzer is in ${folder.name}/.mcp.json. Start (or restart) Claude Code in that ` +
          "folder and approve wpilog-analyzer when it asks."
      );
    }
    if (action === "writeAndOfferIgnore") {
      void offerToIgnore(context, folder, outputChannel);
    }
  }
  return written;
}

export function deactivate() {}
