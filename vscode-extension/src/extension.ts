import { execFile } from "child_process";
import * as crypto from "crypto";
import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import { findJava } from "./javaFinder";
import { findLogDirectory } from "./logFinder";
import {
  LogSettings,
  combineLogDirectories,
  overlaySettings,
  projectConfigName,
} from "./logDirectories";
import { findJar } from "./jarManager";
import {
  GitStatus,
  addToGitignore,
  buildServerConfig,
  buildServerEntry,
  CLAUDE_CODE_EXTENSION_ID,
  claudeCodeRestartNotice,
  gitAction,
  hasServerEntry,
  otherWpilogServer,
  scrubTbaKey,
  shouldWriteEntry,
} from "./mcpJson";
import {
  DEFAULT_IDLE_EXIT_MINUTES,
  ServerDefinition,
  ServerProject,
  daemonNameFor,
  definedServers,
  entryUsesConnect,
  resolveServer,
  serverFor,
  serverNameFor,
} from "./projectServers";
import { removeTbaKeyFromConfigs, writeConfigFile, writeEntry } from "./projectConfigs";
import { DaemonInputs, DaemonSpec, ServerManager } from "./serverManager";
import { Explorer } from "./explorer";
import {
  TBA_KEY_QUIET_MS,
  TBA_KEY_SETTING,
  planTbaKeyMove,
  settingsThatRestartTheServer,
} from "./tbaKey";

const PROVIDER_ID = "wpilog-analyzer.mcpServer";

/**
 * Where the TBA API key is kept: VS Code's secret storage (the OS keychain), never a settings
 * file. Claude Code's server, outside VS Code, reads a copy from its configuration file, which
 * only this user can read (see writeProjectConfig).
 */
const TBA_SECRET = "wpilog-mcp.tbaApiKey";

const TBA_ACCOUNT_URL = "https://www.thebluealliance.com/account";

/** The extension's output, for notes written outside activate (see noteOnce). */
let output: vscode.OutputChannel | undefined;
const noted = new Set<string>();

/** Writes a note to the output once per activation: for a setting that is wrong on every look. */
function noteOnce(message: string) {
  if (noted.has(message)) return;
  noted.add(message);
  output?.appendLine(message);
}

export function activate(context: vscode.ExtensionContext) {
  const outputChannel = vscode.window.createOutputChannel("WPILog Analyzer");
  output = outputChannel;
  noted.clear();
  const didChangeEmitter = new vscode.EventEmitter<void>();

  /**
   * What a server is started from (see projectServers.ts): Java, the JAR copy that survives
   * updates, and the configuration's values. The log directories are the server's own as the
   * user defined it, together with those of every project that uses it, each project's resolved
   * with its own settings on top of the server's; the team number is the first a project sets,
   * else the server's. A server defined without a directory lists what auto-detection finds (the
   * user's log directory setting, else a well-known folder, else, when asked in the foreground,
   * the folder the user picks), as the default server does. The TBA API key goes into the
   * configuration file, which only this user can read, never on a command line, which the
   * process list shows to every user of the machine.
   */
  async function resolveInputsFor(spec: DaemonSpec, prompt: boolean): Promise<DaemonInputs | undefined> {
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

    const jar = await stableJar(context, jarPath, outputChannel);
    outputChannel.appendLine(`Java: ${javaPath}`);
    outputChannel.appendLine(`JAR: ${jar}`);

    const defined = definedServer(spec.serverName);
    const server: ServerDefinition = defined.logDirectory
      ? defined
      : { ...defined, logDirectory: await findLogDirectory(prompt) };
    const projects: ServerProject[] = spec.folderPaths.map((folderPath) => ({
      folderPath,
      own: settingsOf(context, folderPath),
    }));
    const { logDirs, teamNumber } = resolveServer(server, projects);
    outputChannel.appendLine(
      `${spec.label}: log directories ${logDirs.length > 0 ? logDirs.join(", ") : "(none)"}`
    );

    return {
      javaPath,
      jarPath: jar,
      maxHeap,
      logDirs,
      teamNumber,
      tbaKey: (await context.secrets.get(TBA_SECRET)) || undefined,
      cacheDir: extensionCacheDir(context),
      idleExitMinutes: config.get<number>("idleExitMinutes") ?? DEFAULT_IDLE_EXIT_MINUTES,
      version: context.extension.packageJSON.version,
    };
  }

  // ---- The projects' servers, and VS Code's MCP provider ----

  // Each server the window's projects use (the default, or one the user defined and the
  // project's setting names, shared with every project that names the same) runs in the
  // background on the loopback address and serves VS Code's agents, Claude Code (through the
  // bridge in .mcp.json), and the extension itself. VS Code is told to look again whenever a
  // URL changes, and after a restart, whose sessions it must open again.
  const serverManager = new ServerManager(context, outputChannel, resolveInputsFor, () => {
    didChangeEmitter.fire();
    explorer.serversChanged();
  });
  context.subscriptions.push(serverManager);

  // ---- WPILog Explorer: the views and the editor, clients of the same servers ----
  const explorer = new Explorer(context, outputChannel, serverManager, () => windowSpecs(context));
  context.subscriptions.push(explorer);
  async function ensureWindowServers(prompt: boolean): Promise<{ spec: DaemonSpec; url: string }[]> {
    const up: { spec: DaemonSpec; url: string }[] = [];
    for (const spec of windowSpecs(context)) {
      const url = await serverManager.ensure(spec, prompt);
      if (url) up.push({ spec, url });
    }
    return up;
  }
  function restartServerForSettings() {
    void ensureWindowServers(false).then((up) => {
      if (up.length > 0) didChangeEmitter.fire();
    });
  }

  const provider: vscode.McpServerDefinitionProvider = {
    onDidChangeMcpServerDefinitions: didChangeEmitter.event,

    provideMcpServerDefinitions: async () => {
      // VS Code asks when it needs the servers: make sure each runs, then hand over its URL.
      // One definition per server, so two folders that share a server share the definition
      const up = await ensureWindowServers(true);
      return up.map(
        ({ spec, url }) =>
          new vscode.McpHttpServerDefinition(
            up.length === 1 ? "WPILog Analyzer" : `WPILog Analyzer (${spec.serverName})`,
            vscode.Uri.parse(url),
            undefined,
            context.extension.packageJSON.version
          )
      );
    },

    resolveMcpServerDefinition: async (server) => {
      return server;
    },
  };

  context.subscriptions.push(
    vscode.lm.registerMcpServerDefinitionProvider(PROVIDER_ID, provider),
    vscode.commands.registerCommand("wpilog-mcp.showServerLog", async () => {
      const spec = await pickWindowServer(context, "Server whose log to show");
      if (spec) await serverManager.showLog(spec);
    }),
    vscode.commands.registerCommand("wpilog-mcp.restartServer", async () => {
      const spec = await pickWindowServer(context, "Server to restart");
      if (!spec) return;
      const url = await serverManager.restart(spec);
      if (url) {
        didChangeEmitter.fire();
        vscode.window.showInformationMessage(
          `WPILog Analyzer: the server ${spec.serverName} is running at ${url}.`
        );
      }
    })
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
      await refreshKnownProjects(context, serverManager, new Set());
      const cleaned = await removeTbaKeyFromConfigs(projectsDir(context), new Set());
      for (const file of [...cleaned.scrubbed, ...cleaned.removed]) {
        outputChannel.appendLine(`Removed the TBA API key from ${file}`);
      }
      vscode.window.showInformationMessage(
        "WPILog Analyzer: TBA API key removed. The server restarts without it."
      );
    }),
    // A stored or removed key restarts the server, whose configuration holds the key, and
    // rewrites the configuration files of projects whose entries still run their own server
    context.secrets.onDidChange((e) => {
      if (e.key === TBA_SECRET) {
        restartServerForSettings();
        scheduleMcpJsonUpdate();
      }
    })
  );

  // ---- Claude Code's .mcp.json ----

  // One update at a time, so overlapping triggers neither race on the files nor repeat a notice.
  // A project given the entry joins its server's log directories, so the window's servers are
  // looked at again afterwards: one restarts when its configuration changed, else nothing
  let mcpJsonUpdate: Promise<void> = Promise.resolve();
  function scheduleMcpJsonUpdate(requested?: vscode.WorkspaceFolder) {
    mcpJsonUpdate = mcpJsonUpdate
      .then(() => updateMcpJsonFiles(context, outputChannel, serverManager, requested))
      .then(() => restartServerForSettings())
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

  // A key entered in the TBA API key field is moved into secret storage, one move at a time. While
  // the field is being edited, the move waits until it has been quiet for a moment (TBA_KEY_QUIET_MS)
  let tbaKeyMove: Promise<void> = Promise.resolve();
  let tbaKeyTimer: ReturnType<typeof setTimeout> | undefined;
  function moveTbaKeyNow(): Promise<void> {
    tbaKeyMove = tbaKeyMove
      .then(() => moveTbaKeyToSecretStorage(context, outputChannel))
      .catch((e) => outputChannel.appendLine(`Failed to move the TBA API key into secret storage: ${e}`));
    return tbaKeyMove;
  }
  function scheduleTbaKeyMove() {
    clearTimeout(tbaKeyTimer);
    tbaKeyTimer = setTimeout(() => void moveTbaKeyNow(), TBA_KEY_QUIET_MS);
  }
  context.subscriptions.push({ dispose: () => clearTimeout(tbaKeyTimer) });

  // Re-register when settings change, and update .mcp.json. The TBA API key field is not among
  // these settings: a key entered there restarts the server once it is stored (secrets.onDidChange)
  const serverSettings = settingsThatRestartTheServer(context.extension.packageJSON);
  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration(TBA_KEY_SETTING)) {
        scheduleTbaKeyMove();
      }
      if (serverSettings.some((key) => e.affectsConfiguration(key))) {
        outputChannel.appendLine("Settings changed, restarting the server...");
        restartServerForSettings();
        scheduleMcpJsonUpdate();
        explorer.serversChanged();
      }
    }),
    // The window's folders are among the server's log directories, and decide its servers
    vscode.workspace.onDidChangeWorkspaceFolders(() => {
      restartServerForSettings();
      scheduleMcpJsonUpdate();
      explorer.serversChanged();
    })
  );

  void (async () => {
    await moveTbaKeyNow();
    await removeTbaKeyFromMcpJson(outputChannel);
    // Start the window's servers now, so the first agent to ask finds them up, then add or
    // update Claude Code's entry in .mcp.json (robot projects, by default)
    await ensureWindowServers(false);
    scheduleMcpJsonUpdate();
  })();

  context.subscriptions.push(outputChannel);
  context.subscriptions.push(didChangeEmitter);

  outputChannel.appendLine("WPILog Analyzer extension activated.");
}

/**
 * Moves a TBA API key entered in the `wpilog-mcp.tbaApiKey` setting into secret storage and clears
 * the setting, so the key does not stay in a settings file in plaintext (see planTbaKeyMove). The
 * key is stored before the setting is cleared: if storing fails, the key is still in the setting,
 * and the next move tries again. The message is not waited for: moves run one at a time, and a
 * notification nobody closes would hold up every later one.
 */
async function moveTbaKeyToSecretStorage(
  context: vscode.ExtensionContext,
  outputChannel: vscode.OutputChannel
) {
  const config = vscode.workspace.getConfiguration("wpilog-mcp");
  const inspected = config.inspect<unknown>("tbaApiKey");
  if (inspected?.globalValue === undefined && inspected?.workspaceValue === undefined) {
    return;
  }

  const workspaceFile = vscode.workspace.workspaceFile;
  const move = planTbaKeyMove({
    userValue: inspected?.globalValue,
    workspaceValue: inspected?.workspaceValue,
    storedKey: await context.secrets.get(TBA_SECRET),
    workspaceSettingsName:
      workspaceFile && workspaceFile.scheme === "file"
        ? path.basename(workspaceFile.fsPath)
        : ".vscode/settings.json",
  });

  if (move.store !== undefined) {
    await context.secrets.store(TBA_SECRET, move.store);
    outputChannel.appendLine("Stored the TBA API key from settings in secret storage.");
  }
  if (move.clearUser) {
    await config.update("tbaApiKey", undefined, vscode.ConfigurationTarget.Global);
  }
  if (move.clearWorkspace) {
    await config.update("tbaApiKey", undefined, vscode.ConfigurationTarget.Workspace);
  }
  if (move.clearUser || move.clearWorkspace) {
    outputChannel.appendLine("Cleared the TBA API key setting.");
  }

  if (move.message && move.offerRevoke) {
    void vscode.window
      .showWarningMessage(move.message, "Open TBA Account")
      .then((choice) => {
        if (choice === "Open TBA Account") {
          void vscode.env.openExternal(vscode.Uri.parse(TBA_ACCOUNT_URL));
        }
      });
  } else if (move.message) {
    void vscode.window.showInformationMessage(move.message);
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
/**
 * A project's own settings as last seen, and the server its setting asked for (none for an entry
 * from before the shared servers), resolved again at each look, so a server defined or removed
 * since changes which one the project uses.
 */
type KnownProject = LogSettings & { server?: string };
type KnownProjects = Record<string, KnownProject>;

/** A project's own settings: the open folder's, else as last seen when it had the entry. */
function settingsOf(context: vscode.ExtensionContext, folderPath: string): LogSettings {
  const open = (vscode.workspace.workspaceFolders ?? []).find(
    (folder) => folder.uri.scheme === "file" && folder.uri.fsPath === folderPath
  );
  if (open) return projectSettings(open);
  const known = context.globalState.get<KnownProjects>(PROJECTS_KEY) ?? {};
  const { server: _server, ...own } = known[folderPath] ?? {};
  return own;
}

/**
 * The servers the user defined (see definedServers): the default, from the User settings, and
 * the `wpilog-mcp.servers` setting's. An entry that cannot be used is noted once.
 */
function serversDefined(): Map<string, ServerDefinition> {
  const setting = vscode.workspace.getConfiguration("wpilog-mcp").get<unknown>("servers");
  const { servers, skipped } = definedServers(userSettings(), setting);
  for (const entry of skipped) {
    noteOnce(`The wpilog-mcp.servers entry ${entry} has no usable name, or repeats one, and is skipped.`);
  }
  return servers;
}

/** A server as the user defined it, by name; the default's definition for a name not defined. */
function definedServer(name: string): ServerDefinition {
  return serverFor(name, serversDefined()).server;
}

/**
 * The server a name asks for, as a project's setting or its remembered setting gives it: the
 * server of that name when the user defined one, else the default, with a note for a name
 * nobody defined, which the setting's author will want to know.
 */
function serverNamed(asked: unknown, where: string): string {
  const name = serverNameFor(asked);
  const { server, unknown } = serverFor(name, serversDefined());
  if (unknown) {
    noteOnce(
      `${where} names the server "${name}", which wpilog-mcp.servers does not define; ` +
        "it uses the default server until it is defined."
    );
  }
  return server.name;
}

/** The server a folder uses: the one its Workspace setting names, when defined, else the default. */
function serverNameOf(folder: vscode.WorkspaceFolder): string {
  const setting = vscode.workspace.getConfiguration("wpilog-mcp", folder.uri).get<unknown>("serverName");
  return serverNamed(setting, folder.uri.fsPath);
}

/** A daemon's configuration file, in the extension's storage. */
function serverConfigPath(context: vscode.ExtensionContext, daemonName: string): string {
  return path.join(context.globalStorageUri.fsPath, "servers", `${daemonName}.json`);
}

/**
 * A server, with every project that uses it: the window's folders that resolve to it, and the
 * known projects that do, open or not, since a server lists its projects' logs whether or not
 * they are open. The daemon is named for the server (see daemonNameFor).
 */
function specFor(context: vscode.ExtensionContext, serverName: string): DaemonSpec {
  const folders = new Set<string>();
  for (const folder of (vscode.workspace.workspaceFolders ?? []).filter((f) => f.uri.scheme === "file")) {
    if (serverNameOf(folder) === serverName) folders.add(folder.uri.fsPath);
  }
  const known = context.globalState.get<KnownProjects>(PROJECTS_KEY) ?? {};
  for (const [folderPath, project] of Object.entries(known)) {
    if (project.server !== undefined && serverNamed(project.server, folderPath) === serverName) {
      folders.add(folderPath);
    }
  }
  const name = daemonNameFor(serverName);
  return {
    name,
    serverName,
    configPath: serverConfigPath(context, name),
    label: serverName,
    folderPaths: [...folders],
  };
}

/** The window's servers: one per distinct server among its folders, or the default with no folder. */
function windowSpecs(context: vscode.ExtensionContext): DaemonSpec[] {
  const folders = (vscode.workspace.workspaceFolders ?? []).filter((f) => f.uri.scheme === "file");
  const names = folders.length === 0 ? [serverNamed(undefined, "")] : [...new Set(folders.map(serverNameOf))];
  return names.map((name) => specFor(context, name));
}

/** The window's one server, or the one the user picks when there are several. */
async function pickWindowServer(
  context: vscode.ExtensionContext,
  placeHolder: string
): Promise<DaemonSpec | undefined> {
  const specs = windowSpecs(context);
  if (specs.length === 1) return specs[0];
  const picked = await vscode.window.showQuickPick(
    specs.map((spec) => ({ label: spec.serverName, description: spec.folderPaths.join(", "), spec })),
    { placeHolder }
  );
  return picked?.spec;
}

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
 * Writes one project's configuration file for an entry written before the one server, which
 * starts a stdio server of its own with it, as the standalone install's servers.yaml does: the
 * log directories (relative ones inside the project), the team number, the TBA key, and the
 * extension's own disk cache. Such an entry keeps working, with its settings kept current, until
 * the project is opened in VS Code and the entry rewritten to use the one server, when the file
 * is removed. Written only when its contents change.
 */
async function writeProjectConfig(
  context: vscode.ExtensionContext,
  folderPath: string,
  settings: LogSettings
): Promise<string> {
  const text = buildServerConfig({
    logDirs: combineLogDirectories(
      settings.logDirectory?.trim() ? settings.logDirectory : undefined,
      settings.additionalLogDirectories,
      [folderPath]
    ),
    teamNumber: settings.teamNumber || 0,
    tbaKey: await context.secrets.get(TBA_SECRET),
    cacheDir: extensionCacheDir(context),
  });
  const file = projectConfigPath(context, folderPath);
  await writeConfigFile(file, text);
  return file;
}

/** Remembers a project that has the entry, with its own settings as they are now and the server it asks for. */
async function rememberProject(
  context: vscode.ExtensionContext,
  folderPath: string,
  own: LogSettings,
  server: string
) {
  const known = context.globalState.get<KnownProjects>(PROJECTS_KEY) ?? {};
  known[folderPath] = { ...own, server };
  await context.globalState.update(PROJECTS_KEY, known);
}

/**
 * Looks after every known project except `skip` (those just written). A project whose .mcp.json
 * no longer has the entry is forgotten and its per-project file removed. A project whose entry
 * uses a shared server needs no file of its own, so any left from an earlier entry is removed,
 * and its server's configuration (the server it asks for, as now defined) is rewritten from
 * every project that uses it; a server not in
 * this window whose configuration changed is stopped, so whoever needs it next starts it with
 * the new one (the window's own are restarted after this). A project whose entry still runs a
 * server of its own (written before the shared servers, and not yet opened in VS Code since)
 * gets its file rewritten from the user's settings and the project's own as last seen, so that
 * entry keeps working until it is rewritten.
 */
async function refreshKnownProjects(
  context: vscode.ExtensionContext,
  serverManager: ServerManager,
  skip: Set<string>
) {
  const known = context.globalState.get<KnownProjects>(PROJECTS_KEY) ?? {};
  const user = userSettings();
  let forgotten = false;
  const servers = new Set<string>();
  for (const [folderPath, project] of Object.entries(known)) {
    if (skip.has(folderPath)) {
      if (project.server !== undefined) servers.add(serverNamed(project.server, folderPath));
      continue;
    }
    const text = await fs.promises
      .readFile(path.join(folderPath, ".mcp.json"), "utf8")
      .catch(() => undefined);
    if (!hasServerEntry(text)) {
      delete known[folderPath];
      forgotten = true;
      await fs.promises.rm(projectConfigPath(context, folderPath), { force: true });
      continue;
    }
    if (entryUsesConnect(text) && project.server !== undefined) {
      await fs.promises.rm(projectConfigPath(context, folderPath), { force: true });
      servers.add(serverNamed(project.server, folderPath));
      continue;
    }
    const { server: _server, ...own } = project;
    await writeProjectConfig(context, folderPath, overlaySettings(user, own));
  }
  if (forgotten) {
    await context.globalState.update(PROJECTS_KEY, known);
  }
  const inWindow = new Set(windowSpecs(context).map((spec) => spec.serverName));
  for (const name of servers) {
    const spec = specFor(context, name);
    const changed = await serverManager.writeConfig(spec);
    if (changed && !inWindow.has(name)) {
      await serverManager.stopIfRunning(spec);
    }
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

/** A global switch the notice's Don't Show Again button sets. */
const RESTART_NOTICE_OFF = "wpilog-mcp.claudeCodeRestartNoticeOff";

/**
 * Tells the user that Claude Code must restart to find the entry just added to the folder's
 * .mcp.json, and offers to reload the window when the Claude Code extension is installed (which
 * restarts its sessions). A notice the extension shows by itself can be turned off for good, for
 * those who use only Copilot or other VS Code agents.
 */
async function showRestartNotice(
  context: vscode.ExtensionContext,
  folder: vscode.WorkspaceFolder,
  canTurnOff: boolean
): Promise<string | undefined> {
  if (canTurnOff && context.globalState.get<boolean>(RESTART_NOTICE_OFF)) {
    return undefined;
  }
  const notice = claudeCodeRestartNotice(
    folder.name,
    vscode.extensions.getExtension(CLAUDE_CODE_EXTENSION_ID) !== undefined
  );
  const buttons = [
    ...(notice.offerReload ? ["Reload Window"] : []),
    ...(canTurnOff ? ["Don't Show Again"] : []),
  ];
  const choice = await vscode.window.showInformationMessage(notice.message, ...buttons);
  if (choice === "Reload Window") {
    await vscode.commands.executeCommand("workbench.action.reloadWindow");
  } else if (choice === "Don't Show Again") {
    await context.globalState.update(RESTART_NOTICE_OFF, true);
  }
  return choice;
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
  serverManager: ServerManager,
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
  const written = await writeEntries(context, outputChannel, serverManager, targets);
  if (enabled) {
    await refreshKnownProjects(context, serverManager, written);
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
 * Writes each target folder's .mcp.json entry, which runs the bridge to the one server (from a
 * JAR path that survives extension updates) with the server's configuration file, written first
 * so the entry never points at a file that is not there. Every other entry in .mcp.json is
 * kept. The entry holds this computer's paths, so a .mcp.json that git tracks (the repository
 * shares it) is left alone, and one git would pick up comes with an offer to ignore it. A
 * per-project configuration file left by an earlier entry is removed once the entry is
 * rewritten. Returns the folders written.
 */
async function writeEntries(
  context: vscode.ExtensionContext,
  outputChannel: vscode.OutputChannel,
  serverManager: ServerManager,
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

    // This project's own settings and the server it asks for are remembered first, so that its
    // directories are in the server's configuration, which is written before the entry. The
    // server's file may be shared, so it is never removed here; the per-project file an earlier
    // entry used is, once the new entry is in place. The entry connects to the daemon by the
    // daemon's name.
    const own = projectSettings(folder);
    const askedServer = serverNameFor(
      vscode.workspace.getConfiguration("wpilog-mcp", folder.uri).get<unknown>("serverName")
    );
    const oldConfigPath = projectConfigPath(context, folder.uri.fsPath);
    await rememberProject(context, folder.uri.fsPath, own, askedServer);
    const spec = specFor(context, serverNameOf(folder));
    const outcome = await writeEntry(
      text,
      buildServerEntry(javaPath, jar, maxHeap, spec.name, spec.configPath),
      {
        writeConfig: async () => {
          await serverManager.writeConfig(spec);
        },
        removeConfig: async () => {},
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
        // Its earlier entry still works: with the server's file just written, or, written
        // before the shared servers, with its own file, which the refresh keeps current
        written.add(folder.uri.fsPath);
      }
      continue;
    }
    if (outcome.changed) {
      outputChannel.appendLine(`Updated the wpilog-analyzer entry in ${uri.fsPath}`);
    }
    await fs.promises.rm(oldConfigPath, { force: true });
    written.add(folder.uri.fsPath);
    // A Claude Code session that is already running does not see a new entry. The folder the user
    // asked for always hears how to restart; a folder the entry was added to by itself, once
    if (asked) {
      void showRestartNotice(context, folder, false);
    } else if (!hasServerEntry(text)) {
      void showOnce(context, `wpilog-mcp.claudeCodeRestartNoticed:${folder.uri}`, () =>
        showRestartNotice(context, folder, true)
      );
    }
    if (action === "writeAndOfferIgnore") {
      void offerToIgnore(context, folder, outputChannel);
    }
  }
  return written;
}

export function deactivate() {}
