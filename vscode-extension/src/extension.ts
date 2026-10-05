import { execFile } from "child_process";
import * as crypto from "crypto";
import * as fs from "fs";
import * as os from "os";
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
  DAEMON_NAME,
  DEFAULT_IDLE_EXIT_MINUTES,
  ServerProject,
  entryUsesConnect,
  resolveServer,
} from "./projectServers";
import { removeTbaKeyFromConfigs, writeConfigFile, writeEntry } from "./projectConfigs";
import { DaemonInputs, DaemonSpec, OwnDaemonSpec, ServerManager, StandaloneDaemonSpec } from "./serverManager";
import { STANDALONE_SERVER, buildStandaloneEntry, findStandaloneInstall } from "./standaloneServer";
import { ServerEntry } from "./mcpJson";
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

export function activate(context: vscode.ExtensionContext) {
  const outputChannel = vscode.window.createOutputChannel("WPILog Analyzer");
  const didChangeEmitter = new vscode.EventEmitter<void>();

  /**
   * What the server is started from (see projectServers.ts): Java, the JAR copy that survives
   * updates, and the configuration's values. The log directories are the User settings',
   * together with those of every project, open or remembered, each project's resolved with its
   * own settings on top of the user's; the team number is the first a project sets, else the
   * user's. With no directory set at all, the server lists what auto-detection finds (a
   * well-known folder, else, when asked in the foreground, the folder the user picks). The TBA
   * API key goes into the configuration file, which only this user can read, never on a command
   * line, which the process list shows to every user of the machine.
   */
  async function resolveInputsFor(spec: OwnDaemonSpec, prompt: boolean): Promise<DaemonInputs | undefined> {
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

    const user = userSettings();
    const additional = Array.isArray(user.additionalLogDirectories)
      ? user.additionalLogDirectories.filter((d) => typeof d === "string" && d.trim() !== "")
      : [];
    if (!user.logDirectory?.trim() && additional.length === 0) {
      user.logDirectory = await findLogDirectory(prompt);
    }
    const projects: ServerProject[] = spec.folderPaths.map((folderPath) => ({
      folderPath,
      own: settingsOf(context, folderPath),
    }));
    const { logDirs, teamNumber } = resolveServer(user, projects);
    outputChannel.appendLine(
      `${spec.name}: log directories ${logDirs.length > 0 ? logDirs.join(", ") : "(none)"}`
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

  // ---- The server, and VS Code's MCP provider ----

  // One server, shared by every project, runs in the background on the loopback address and
  // serves VS Code's agents, Claude Code (through the bridge in .mcp.json), and the extension
  // itself: the extension's own daemon, or the standalone install's server when the setting says
  // so. VS Code is told to look again whenever its URL changes, and after a restart, whose
  // sessions it must open again.
  const serverManager = new ServerManager(context, outputChannel, resolveInputsFor, () => {
    didChangeEmitter.fire();
    explorer.serversChanged();
  });
  context.subscriptions.push(serverManager);

  // ---- WPILog Explorer: the views and the editor, a client of the same server ----
  const explorer = new Explorer(context, outputChannel, serverManager, () => serverSpec(context));
  context.subscriptions.push(explorer);
  function restartServerForSettings() {
    void serverManager.ensure(serverSpec(context), false).then((url) => {
      if (url) didChangeEmitter.fire();
    });
  }

  const provider: vscode.McpServerDefinitionProvider = {
    onDidChangeMcpServerDefinitions: didChangeEmitter.event,

    provideMcpServerDefinitions: async () => {
      // VS Code asks when it needs the server: make sure it runs, then hand over its URL. One
      // definition, whatever the window's folders: they all share the server
      const url = await serverManager.ensure(serverSpec(context), true);
      if (!url) return [];
      return [
        new vscode.McpHttpServerDefinition(
          "WPILog Analyzer",
          vscode.Uri.parse(url),
          undefined,
          context.extension.packageJSON.version
        ),
      ];
    },

    resolveMcpServerDefinition: async (server) => {
      return server;
    },
  };

  context.subscriptions.push(
    vscode.lm.registerMcpServerDefinitionProvider(PROVIDER_ID, provider),
    vscode.commands.registerCommand("wpilog-mcp.showServerLog", () =>
      serverManager.showLog(serverSpec(context))
    ),
    vscode.commands.registerCommand("wpilog-mcp.restartServer", async () => {
      const url = await serverManager.restart(serverSpec(context));
      if (url) {
        didChangeEmitter.fire();
        vscode.window.showInformationMessage(`WPILog Analyzer: the server is running at ${url}.`);
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
  // A project given the entry joins the server's log directories, so the server is looked at
  // again afterwards: it restarts when its configuration changed, else nothing
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
    // The window's folders are among the server's log directories
    vscode.workspace.onDidChangeWorkspaceFolders(() => {
      restartServerForSettings();
      scheduleMcpJsonUpdate();
      explorer.serversChanged();
    })
  );

  void (async () => {
    await moveTbaKeyNow();
    await removeTbaKeyFromMcpJson(outputChannel);
    // Start the server now, so the first agent to ask finds it up, then add or update Claude
    // Code's entry in .mcp.json (robot projects, by default)
    await serverManager.ensure(serverSpec(context), false);
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
/** A project's own settings as last seen. */
type KnownProjects = Record<string, LogSettings>;

/** The settings a remembered project holds, and nothing else an earlier version stored with them. */
function ownSettings(known: LogSettings | undefined): LogSettings {
  const own: LogSettings = {};
  if (known?.logDirectory !== undefined) own.logDirectory = known.logDirectory;
  if (known?.additionalLogDirectories !== undefined) own.additionalLogDirectories = known.additionalLogDirectories;
  if (known?.teamNumber !== undefined) own.teamNumber = known.teamNumber;
  return own;
}

/** A project's own settings: the open folder's, else as last seen when it had the entry. */
function settingsOf(context: vscode.ExtensionContext, folderPath: string): LogSettings {
  const open = (vscode.workspace.workspaceFolders ?? []).find(
    (folder) => folder.uri.scheme === "file" && folder.uri.fsPath === folderPath
  );
  if (open) return projectSettings(open);
  const known = context.globalState.get<KnownProjects>(PROJECTS_KEY) ?? {};
  return ownSettings(known[folderPath]);
}

/** The daemon's configuration file, in the extension's storage. */
function serverConfigPath(context: vscode.ExtensionContext): string {
  return path.join(context.globalStorageUri.fsPath, "servers", `${DAEMON_NAME}.json`);
}

/**
 * The server: the standalone install's when the `wpilog-mcp.useStandaloneServer` setting is on
 * (standaloneServer.ts; the install's configuration then decides what it lists), else the
 * extension's own daemon with every project it serves: the window's folders, and the known
 * projects, open or not, since the server lists its projects' logs whether or not they are open
 * (Claude Code in a terminal reaches it through their entries with VS Code closed).
 */
function serverSpec(context: vscode.ExtensionContext): DaemonSpec {
  if (useStandaloneServer()) return standaloneSpec();
  const folders = new Set<string>();
  for (const folder of (vscode.workspace.workspaceFolders ?? []).filter((f) => f.uri.scheme === "file")) {
    folders.add(folder.uri.fsPath);
  }
  const known = context.globalState.get<KnownProjects>(PROJECTS_KEY) ?? {};
  for (const folderPath of Object.keys(known)) folders.add(folderPath);
  return { kind: "own", name: DAEMON_NAME, configPath: serverConfigPath(context), folderPaths: [...folders] };
}

/** Whether the standalone install's server is to be used instead of the extension's own. */
function useStandaloneServer(): boolean {
  return vscode.workspace.getConfiguration("wpilog-mcp").get<boolean>("useStandaloneServer", false);
}

/** The standalone install's server, as the install is laid out now (looked for at each call). */
function standaloneSpec(): StandaloneDaemonSpec {
  const isFile = (file: string) => {
    try {
      return fs.statSync(file).isFile();
    } catch {
      return false;
    }
  };
  return { kind: "standalone", name: STANDALONE_SERVER, ...findStandaloneInstall(os.homedir(), process.platform, isFile) };
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

/** Remembers a project that has the entry, with its own settings as they are now. */
async function rememberProject(context: vscode.ExtensionContext, folderPath: string, own: LogSettings) {
  const known = context.globalState.get<KnownProjects>(PROJECTS_KEY) ?? {};
  known[folderPath] = ownSettings(own);
  await context.globalState.update(PROJECTS_KEY, known);
}

/**
 * Looks after every known project except `skip` (those just written). A project whose .mcp.json
 * no longer has the entry is forgotten and its per-project file removed. A project whose entry
 * uses the shared server needs no file of its own, so any left from an earlier entry is removed.
 * A project whose entry still runs a server of its own (written before the shared server, and
 * not yet opened in VS Code since) gets its file rewritten from the user's settings and the
 * project's own as last seen, so that entry keeps working until it is rewritten. The shared
 * server's configuration is then rewritten from every project, since a project forgotten here
 * leaves its directories; the server is restarted after this when the configuration changed.
 */
async function refreshKnownProjects(
  context: vscode.ExtensionContext,
  serverManager: ServerManager,
  skip: Set<string>
) {
  const known = context.globalState.get<KnownProjects>(PROJECTS_KEY) ?? {};
  const user = userSettings();
  let forgotten = false;
  for (const [folderPath, project] of Object.entries(known)) {
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
    if (entryUsesConnect(text)) {
      await fs.promises.rm(projectConfigPath(context, folderPath), { force: true });
      continue;
    }
    await writeProjectConfig(context, folderPath, overlaySettings(user, ownSettings(project)));
  }
  if (forgotten) {
    await context.globalState.update(PROJECTS_KEY, known);
  }
  await serverManager.writeConfig(serverSpec(context));
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
  // The entry runs the bridge to the server: the extension's own daemon from the stable JAR, or
  // the standalone install's server through the install's launcher
  const spec = serverSpec(context);
  let entry: ServerEntry;
  if (spec.kind === "standalone") {
    if (spec.missing !== undefined) {
      outputChannel.appendLine(`Claude Code's entry was not written: the standalone install cannot be used (${spec.missing}).`);
      return written;
    }
    entry = buildStandaloneEntry(spec.launcher, spec.configPath, process.platform);
  } else {
    const javaPath = await findJava();
    if (!javaPath) return written;
    const bundled = findJar(context.extensionPath);
    if (!bundled) return written;
    const jar = await stableJar(context, bundled, outputChannel);
    const maxHeap = vscode.workspace.getConfiguration("wpilog-mcp").get<string>("maxHeap") || "4g";
    entry = buildServerEntry(javaPath, jar, maxHeap, spec.name, spec.configPath);
  }

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

    // This project's own settings are remembered first, so that its directories are in the
    // server's configuration, which is written before the entry (the extension's own server;
    // the standalone server's configuration is the user's, and the project's directories do
    // not join it). The server's file is shared, so it is never removed here; the per-project
    // file an earlier entry used is, once the new entry is in place. The entry connects to the
    // server by its name.
    const own = projectSettings(folder);
    const oldConfigPath = projectConfigPath(context, folder.uri.fsPath);
    await rememberProject(context, folder.uri.fsPath, own);
    const outcome = await writeEntry(
      text,
      entry,
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
