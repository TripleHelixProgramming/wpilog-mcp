import { execFile } from "child_process";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import { findJava } from "./javaFinder";
import { wellKnownLogDirectory } from "./logFinder";
import { LogSettings, combineLogDirectories } from "./logDirectories";
import { findJar } from "./jarManager";
import { ServerManager, StandaloneDaemonSpec } from "./serverManager";
import { STANDALONE_SERVER, findStandaloneInstall } from "./standaloneServer";
import { InstallSummary, installAction, installArgs, launcherVersion, parseInstallSummary } from "./standaloneInstall";
import { directoryRegistration, LEASE_SETTINGS, SessionRegistration } from "./directoryLease";
import { claudeCommand, claudeCommandText, findClaude } from "./claudeRegistration";
import { GitStatus, PROJECT_FILE, ignoreProjectFile, projectFileOffer, projectFileText } from "./projectFile";
import { legacyCleanupAction, retireLegacyEntry } from "./legacyMigration";
import { Explorer } from "./explorer";
import { TBA_KEY_QUIET_MS, TBA_KEY_SETTING, planTbaKeyMove } from "./tbaKey";

const PROVIDER_ID = "wpilog-analyzer.mcpServer";
const STANDALONE_INSTALLED_VERSION = "wpilog-mcp.standaloneInstalledVersion";
/** A secret reaches the shared daemon only through this window's in-memory session lease. */
const TBA_SECRET = "wpilog-mcp.tbaApiKey";
const TBA_ACCOUNT_URL = "https://www.thebluealliance.com/account";
const CLAUDE_REGISTERED = "wpilog-mcp.claudeUserRegistration";
const LEGACY_RETIRED = "wpilog-mcp.retiredOwnedDaemon";

export function activate(context: vscode.ExtensionContext) {
  const outputChannel = vscode.window.createOutputChannel("WPILog Analyzer");
  const didChangeEmitter = new vscode.EventEmitter<void>();

  /** Installation uses the bundled JAR itself; updates must not bootstrap the extension or refresh the install. */
  async function resolveRuntime(): Promise<{ javaPath: string; jarPath: string; maxHeap: string } | undefined> {
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

    return { javaPath, jarPath, maxHeap };
  }

  // A window shares one install/offer between activation, the MCP provider, and the explorer.
  // The JAR's cross-process lock also protects different windows installing at the same time.
  let installation: Promise<InstallSummary | undefined> | undefined;
  let updateCheck: Promise<void> | undefined;
  let migration: Promise<void> | undefined;
  let offerDismissed = false;
  const migrationNotes = new Set<string>();

  /** Retire project entries first; a failed stop keeps its settings for a later activation. */
  function migrateLegacy(): Promise<void> {
    if (!migration) migration = (async () => {
      await retireOpenProjects();
      const completed = context.globalState.get<boolean>(LEGACY_RETIRED) === true;
      const pidFile = path.join(os.homedir(), ".wpilog-mcp", "run", "vscode-default.pid");
      const recorded = fs.existsSync(pidFile);
      let action = legacyCleanupAction(completed, recorded);
      if (action === "stop") {
        const runtime = await resolveRuntime();
        if (!runtime) return;
        const stopped = await new Promise<boolean>(resolve => execFile(runtime.javaPath,
          [`-Xmx${runtime.maxHeap}`, "-jar", runtime.jarPath, "stop", "vscode-default"],
          { timeout: 120_000, windowsHide: true }, (error, stdout, stderr) => {
            if (stdout.trim()) outputChannel.appendLine(stdout.trim());
            if (stderr.trim()) outputChannel.appendLine(stderr.trim());
            resolve(!error);
          }));
        action = legacyCleanupAction(completed, recorded, stopped);
      }
      if (action === "retry") {
        outputChannel.appendLine("Could not stop the old vscode-default daemon; kept its settings and will retry next activation.");
      } else if (action === "remove") {
        for (const directory of ["servers", "projects"]) {
          await fs.promises.rm(path.join(context.globalStorageUri.fsPath, directory), { recursive: true, force: true });
        }
        await context.globalState.update("wpilog-mcp.claudeCodeProjects", undefined);
        await context.globalState.update(LEGACY_RETIRED, true);
        outputChannel.appendLine("Retired the extension's private server settings; the standalone http server is now shared.");
      }
    })().catch(error => outputChannel.appendLine(`Legacy migration did not complete: ${String(error)}`));
    return migration;
  }

  async function retireOpenProjects() {
    for (const folder of localFolders()) {
      const file = path.join(folder.uri.fsPath, ".mcp.json");
      try {
        const stat = await fs.promises.lstat(file);
        if (stat.isSymbolicLink()) {
          if (!migrationNotes.has(file)) outputChannel.appendLine(`Left symbolic link alone: ${file}`);
          migrationNotes.add(file);
          continue;
        }
        const text = await fs.promises.readFile(file, "utf8");
        const git = await gitStatusOf(folder.uri.fsPath, ".mcp.json");
        const edit = retireLegacyEntry(text, git, context.globalStorageUri.fsPath, folder.uri.fsPath,
          path, canonicalMigrationPath);
        if (edit.changed) {
          // Preserve an edit or git add made while the initial read and git query were pending.
          const currentGit = await gitStatusOf(folder.uri.fsPath, ".mcp.json");
          if ((currentGit !== "untracked" && currentGit !== "ignored") ||
            (await fs.promises.lstat(file)).isSymbolicLink() || await fs.promises.readFile(file, "utf8") !== text) {
            outputChannel.appendLine(`Left ${file} alone: it changed during migration.`);
            continue;
          }
          await fs.promises.writeFile(file, edit.text!);
        }
        if (edit.note && !migrationNotes.has(file)) outputChannel.appendLine(`${file}: ${edit.note}`);
        migrationNotes.add(file);
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") outputChannel.appendLine(`Could not migrate ${file}: ${String(error)}`);
      }
    }
  }

  function installStandalone(seed: boolean, updating: boolean): Promise<InstallSummary | undefined> {
    if (!installation) {
      installation = performStandaloneInstall(seed, updating).finally(() => { installation = undefined; });
    }
    return installation;
  }

  async function performStandaloneInstall(seed: boolean, updating: boolean): Promise<InstallSummary | undefined> {
    try {
      const runtime = await resolveRuntime();
      if (!runtime) return undefined;
      const user = userSettings();
      const args = installArgs(seed ? {
        logDirs: combineLogDirectories(user.logDirectory, user.additionalLogDirectories,
          []),
        teamNumber: user.teamNumber,
      } : undefined);
      const summary = await vscode.window.withProgress({
        location: vscode.ProgressLocation.Notification,
        title: updating ? "WPILog Analyzer: updating the standalone server" : "WPILog Analyzer: installing the standalone server",
      }, async () => {
        const json = await new Promise<string>((resolve, reject) => {
          execFile(runtime.javaPath, [`-Xmx${runtime.maxHeap}`, "-jar", runtime.jarPath, ...args],
            { timeout: 120_000, windowsHide: true, maxBuffer: 1 << 20 }, (error, stdout, stderr) => {
              if (stderr.trim()) outputChannel.appendLine(stderr.trim());
              if (error) reject(new Error(stderr.trim() || error.message));
              else resolve(stdout);
            });
        });
        return parseInstallSummary(json);
      });
      outputChannel.appendLine(`Standalone install: installed ${summary.installed_version} in ${summary.install_dir}.`);
      outputChannel.appendLine(`Standalone launcher: ${summary.launcher_version_before ?? "missing"} -> ${summary.launcher_version_after}` +
        (summary.repointed ? "." : " (kept the current launcher)."));
      outputChannel.appendLine(`${summary.config_created ? "Created" : "Kept"} configuration: ${summary.config_path}`);
      if (summary.path_hint) outputChannel.appendLine(`Add to PATH: ${summary.path_hint}`);
      await context.globalState.update(STANDALONE_INSTALLED_VERSION, summary.installed_version);
      if (updating) {
        if (summary.repointed) {
          void vscode.window.showInformationMessage(
            `WPILog Analyzer: standalone server updated from ${summary.launcher_version_before ?? "unknown"} to ${summary.launcher_version_after}. ` +
            "The next start replaces the running server.");
        }
      } else {
        void vscode.window.showInformationMessage(
          `WPILog Analyzer: installed standalone server ${summary.installed_version}.` +
          (summary.repointed ? "" : ` Kept current launcher ${summary.launcher_version_after}.`) +
          (summary.path_hint ? ` Add ${summary.path_hint} to PATH.` : " Its launcher is already on PATH."));
      }
      return summary;
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      outputChannel.appendLine(`ERROR: standalone install failed: ${reason}`);
      void vscode.window.showErrorMessage(`WPILog Analyzer: standalone install failed: ${reason}`);
      return undefined;
    }
  }

  async function standaloneInstallAction(spec: StandaloneDaemonSpec) {
    const text = await fs.promises.readFile(spec.launcher, "utf8").catch(() => "");
    return installAction({
      launcherVersion: launcherVersion(text),
      extensionVersion: context.extension.packageJSON.version,
      missing: spec.missing !== undefined,
    });
  }

  /** Check once per activation; every window uses the standalone install. */
  function updateStandaloneAtActivation(): Promise<void> {
    if (!updateCheck) {
      updateCheck = (async () => {
        const spec = standaloneSpec();
        const action = await standaloneInstallAction(spec);
        if (action === "update") {
          await installStandalone(false, true);
        } else if (action === "none" && spec.missing === undefined) {
          const text = await fs.promises.readFile(spec.launcher, "utf8").catch(() => "");
          outputChannel.appendLine(`Standalone launcher ${launcherVersion(text) ?? "unknown"}: kept; no automatic install requested for extension ${context.extension.packageJSON.version}.`);
        }
      })();
    }
    return updateCheck;
  }

  /** A declined offer lasts for this activation; an explicit command can still install later. */
  async function prepareStandalone(): Promise<StandaloneDaemonSpec | undefined> {
    await migrateLegacy();
    await updateStandaloneAtActivation();
    if (installation) await installation;
    const spec = standaloneSpec();
    if (spec.missing === undefined) return spec;
    if (offerDismissed || await standaloneInstallAction(spec) !== "offer") return undefined;
    offerDismissed = true;
    outputChannel.appendLine(`Standalone install missing: ${spec.missing}.`);
    const choice = await vscode.window.showInformationMessage(
      `WPILog Analyzer: no standalone install was found. Install one from this extension's server, version ${context.extension.packageJSON.version}?`,
      "Install", "Not now", "Open Settings");
    if (choice === "Open Settings") {
      void vscode.commands.executeCommand("workbench.action.openSettings", "wpilog-mcp");
    }
    if (choice !== "Install" || !await installStandalone(true, false)) return undefined;
    return standaloneSpec();
  }

  const serverManager = new ServerManager(context, outputChannel, () => {
    didChangeEmitter.fire();
    explorer.serversChanged();
  }, prepareStandalone);

  const projects = () => localFolders().map(folder => ({ folderPath: folder.uri.fsPath, own: projectSettings(folder) }));
  async function registration(): Promise<SessionRegistration> {
    const user = userSettings();
    const additional = Array.isArray(user.additionalLogDirectories) ? user.additionalLogDirectories : [];
    if (!user.logDirectory?.trim() && !additional.some(dir => typeof dir === "string" && dir.trim())) {
      user.logDirectory = wellKnownLogDirectory();
    }
    return { directories: directoryRegistration(user, projects()), key: await context.secrets.get(TBA_SECRET) || null };
  }
  const explorer = new Explorer(context, outputChannel, serverManager, standaloneSpec,
    () => directoryRegistration(userSettings(), projects()).paths.map(dir => dir.path), registration);
  context.subscriptions.push(serverManager, explorer, outputChannel, didChangeEmitter);

  /** Registration finishes before an agent receives its URL; a third session can then read the lease. */
  async function connectWindow(): Promise<string | undefined> {
    try {
      const client = await explorer.clientFor(standaloneSpec());
      await client.refreshRegistration();
      return client.endpoint;
    } catch (error) {
      outputChannel.appendLine(`Could not register this window's directories: ${error instanceof Error ? error.message : String(error)}`);
      return undefined;
    }
  }
  let refreshing = Promise.resolve();
  function refreshLease() {
    refreshing = refreshing.then(async () => {
      if (await connectWindow()) explorer.serversChanged();
    }).catch(error => outputChannel.appendLine(`Could not refresh this window's lease: ${String(error)}`));
    return refreshing;
  }

  const provider: vscode.McpServerDefinitionProvider = {
    onDidChangeMcpServerDefinitions: didChangeEmitter.event,
    provideMcpServerDefinitions: async () => {
      const url = await connectWindow();
      return url ? [new vscode.McpHttpServerDefinition("WPILog Analyzer", vscode.Uri.parse(url),
        undefined, context.extension.packageJSON.version)] : [];
    },
    resolveMcpServerDefinition: async server => server,
  };
  context.subscriptions.push(
    vscode.lm.registerMcpServerDefinitionProvider(PROVIDER_ID, provider),
    vscode.commands.registerCommand("wpilog-mcp.showServerLog", () => serverManager.showLog(standaloneSpec())),
    vscode.commands.registerCommand("wpilog-mcp.installStandaloneServer", async () => {
      if (await installStandalone(true, false)) {
        await connectWindow();
        scheduleProjectOffers();
      }
    }),
    vscode.commands.registerCommand("wpilog-mcp.restartServer", async () => {
      if (await serverManager.restart(standaloneSpec())) {
        await connectWindow();
        didChangeEmitter.fire();
        explorer.serversChanged();
      }
    })
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("wpilog-mcp.setTbaApiKey", async () => {
      const key = await vscode.window.showInputBox({ title: "The Blue Alliance API Key",
        prompt: `Paste your read key from ${TBA_ACCOUNT_URL}. It stays in VS Code's secret storage.`,
        password: true, ignoreFocusOut: true });
      if (key === undefined) return;
      if (!key.trim()) {
        void vscode.window.showWarningMessage("WPILog Analyzer: No key entered; the TBA API key was not changed.");
        return;
      }
      await context.secrets.store(TBA_SECRET, key.trim());
      void vscode.window.showInformationMessage("WPILog Analyzer: TBA API key saved. It is registered with this window's session.");
    }),
    vscode.commands.registerCommand("wpilog-mcp.clearTbaApiKey", async () => {
      await context.secrets.delete(TBA_SECRET);
      await refreshLease();
      void vscode.window.showInformationMessage("WPILog Analyzer: this window's TBA key registration was removed.");
    }),
    context.secrets.onDidChange(event => {
      if (event.key === TBA_SECRET) void refreshLease();
    })
  );

  // One queue keeps registration and file offers from appearing over one another.
  let projectOffers = Promise.resolve();
  let registrationOffered = false;
  async function registerClaude(requested: boolean) {
    if (!requested && (registrationOffered || !vscode.workspace.getConfiguration("wpilog-mcp").get<boolean>("enableForClaudeCode"))) return;
    const spec = await prepareStandalone();
    if (!spec) return;
    if (!requested && context.globalState.get<string>(CLAUDE_REGISTERED) === spec.launcher) return;
    registrationOffered = true;
    const cli = findClaude(process.env.PATH ?? "", process.platform, file => {
      try { fs.accessSync(file, process.platform === "win32" ? fs.constants.F_OK : fs.constants.X_OK); return fs.statSync(file).isFile(); }
      catch { return false; }
    });
    const text = claudeCommandText(spec.launcher, process.platform);
    if (!cli) {
      outputChannel.appendLine(`Register with Claude Code: ${text}`);
      const choice = await vscode.window.showInformationMessage(`WPILog Analyzer: run ${text}`, "Copy Command", "Not now");
      if (choice === "Copy Command") await vscode.env.clipboard.writeText(text);
      return;
    }
    const command = claudeCommand(cli, spec.launcher, process.platform);
    try {
      await new Promise<void>((resolve, reject) => execFile(command.command, command.args,
        { timeout: 30_000, windowsHide: true, windowsVerbatimArguments: command.windowsVerbatimArguments }, (error, stdout, stderr) => {
          if (stdout.trim()) outputChannel.appendLine(stdout.trim());
          if (stderr.trim()) outputChannel.appendLine(stderr.trim());
          if (error) reject(error); else resolve();
        }));
      await context.globalState.update(CLAUDE_REGISTERED, spec.launcher);
      void vscode.window.showInformationMessage("WPILog Analyzer registered with Claude Code for your user account. Restart existing Claude Code sessions to use it.");
    } catch {
      outputChannel.appendLine(`Claude Code registration did not complete. Run: ${text}`);
      const choice = await vscode.window.showWarningMessage("WPILog Analyzer: Claude Code registration failed; the output shows the command.", "Copy Command");
      if (choice === "Copy Command") await vscode.env.clipboard.writeText(text);
    }
  }
  function scheduleProjectOffers(requested = false) {
    projectOffers = projectOffers.then(async () => {
      await migrateLegacy();
      await retireOpenProjects();
      await registerClaude(requested);
      if (!vscode.workspace.getConfiguration("wpilog-mcp").get<boolean>("enableForClaudeCode") && !requested) return;
      for (const folder of localFolders()) await offerProjectFile(context, outputChannel, folder);
    }).catch(error => outputChannel.appendLine(`Claude Code setup failed: ${String(error)}`));
    return projectOffers;
  }
  context.subscriptions.push(vscode.commands.registerCommand("wpilog-mcp.registerWithClaudeCode", () => scheduleProjectOffers(true)));

  let tbaKeyMove = Promise.resolve();
  let tbaKeyTimer: ReturnType<typeof setTimeout> | undefined;
  function moveTbaKeyNow() {
    tbaKeyMove = tbaKeyMove.then(() => moveTbaKeyToSecretStorage(context, outputChannel))
      .catch(() => outputChannel.appendLine("Could not move the TBA key to secret storage; its setting was kept."));
    return tbaKeyMove;
  }
  context.subscriptions.push({ dispose: () => clearTimeout(tbaKeyTimer) },
    vscode.workspace.onDidChangeConfiguration(event => {
      if (event.affectsConfiguration(TBA_KEY_SETTING)) {
        clearTimeout(tbaKeyTimer);
        tbaKeyTimer = setTimeout(() => void moveTbaKeyNow(), TBA_KEY_QUIET_MS);
      }
      if (LEASE_SETTINGS.some(key => event.affectsConfiguration(key))) {
        void refreshLease();
        void scheduleProjectOffers();
      }
      if (event.affectsConfiguration("wpilog-mcp.enableForClaudeCode")) void scheduleProjectOffers();
    }),
    vscode.workspace.onDidChangeWorkspaceFolders(() => {
      void refreshLease();
      void scheduleProjectOffers();
    })
  );

  void (async () => {
    await moveTbaKeyNow();
    await updateStandaloneAtActivation();
    if (await connectWindow()) void explorer.offerOrganizing();
    void scheduleProjectOffers();
  })();
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

/** The standalone install's server, as the install is laid out now (looked for at each call). */
function standaloneSpec(): StandaloneDaemonSpec {
  const isFile = (file: string) => {
    try {
      return fs.statSync(file).isFile();
    } catch {
      return false;
    }
  };
  const maxHeap = vscode.workspace.getConfiguration("wpilog-mcp").get<string>("maxHeap");
  return { kind: "standalone", name: STANDALONE_SERVER, maxHeap, ...findStandaloneInstall(os.homedir(), process.platform, isFile) };
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

function localFolders(): vscode.WorkspaceFolder[] {
  return (vscode.workspace.workspaceFolders ?? []).filter(folder => folder.uri.scheme === "file");
}

/** An existing project file belongs to the user; create-new prevents overwriting a concurrent edit. */
async function offerProjectFile(context: vscode.ExtensionContext, output: vscode.OutputChannel, folder: vscode.WorkspaceFolder) {
  const file = path.join(folder.uri.fsPath, PROJECT_FILE);
  const own = projectSettings(folder);
  const existing = await fs.promises.readFile(file, "utf8").catch(() => undefined);
  const key = `wpilog-mcp.projectFileOffered:${folder.uri.fsPath}`;
  if (!projectFileOffer(own, existing, context.globalState.get<boolean>(key) === true)) return;
  await context.globalState.update(key, true);
  const git = await gitStatusOf(folder.uri.fsPath, PROJECT_FILE);
  if (!projectFileOffer(own, existing, false, git)) {
    output.appendLine(`Left tracked project configuration alone: ${file}`);
    return;
  }
  const choice = await vscode.window.showInformationMessage(
    `WPILog Analyzer: write ${PROJECT_FILE} in ${folder.name} so Claude Code in a terminal can lease this project's log directories and team?`,
    "Write File", ...(git === "untracked" ? ["Write File and Ignore"] : []), "Not now");
  if (choice !== "Write File" && choice !== "Write File and Ignore") return;
  try {
    await fs.promises.writeFile(file, projectFileText(own), { flag: "wx" });
    output.appendLine(`Wrote ${file}. The bridge reads it from its working directory.`);
    if (choice === "Write File and Ignore") {
      const ignore = path.join(folder.uri.fsPath, ".gitignore");
      const text = await fs.promises.readFile(ignore, "utf8").catch(() => "");
      await fs.promises.writeFile(ignore, ignoreProjectFile(text));
    }
  } catch (error) {
    output.appendLine(`Could not write ${file}: ${String(error)}`);
  }
}

function exitCode(command: string, args: string[], cwd: string): Promise<number | undefined> {
  return new Promise(resolve => execFile(command, args, { cwd, timeout: 5000, windowsHide: true }, error => {
    if (!error) resolve(0);
    else {
      const code = (error as { code?: unknown }).code;
      resolve(typeof code === "number" ? code : undefined);
    }
  }));
}

async function gitStatusOf(folder: string, file: string): Promise<GitStatus> {
  const tracked = await exitCode("git", ["ls-files", "--error-unmatch", "--", file], folder);
  if (tracked === 0) return "tracked";
  if (tracked !== 1) return "none";
  return await exitCode("git", ["check-ignore", "-q", "--", file], folder) === 0 ? "ignored" : "untracked";
}

/** Missing old config files still have a location; an existing symlink's target decides ownership. */
function canonicalMigrationPath(file: string): string {
  const suffix: string[] = [];
  let ancestor = path.resolve(file);
  for (;;) {
    try {
      fs.lstatSync(ancestor);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT" || path.dirname(ancestor) === ancestor) throw error;
      suffix.unshift(path.basename(ancestor));
      ancestor = path.dirname(ancestor);
      continue;
    }
    return path.join(fs.realpathSync(ancestor), ...suffix);
  }
}

export function deactivate() {}
