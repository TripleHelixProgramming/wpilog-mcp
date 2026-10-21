import { claudeHttpArgs, claudeHttpCommandText } from "./claudeRegistration";
import { connectionDefinitions } from "./connections";
import { PIT_SECRET_PREFIX, basicCredential, pitHeaders, pitRegistration, pitSecretKey } from "./pitCredential";
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
import { STANDALONE_SERVER, findStandaloneInstall, LauncherCommand } from "./standaloneServer";
import { InstallSummary, installAction, installArgs, launcherVersion, parseInstallSummary } from "./standaloneInstall";
import { directoryRegistration, LEASE_SETTINGS, SessionRegistration } from "./directoryLease";
import { claudeArgs, claudeCommandText, findClaude, registerWithClaude, ClaudeReply, ClaudeRegistrationAttempt, claudeRegistrationFeedback } from "./claudeRegistration";
import { GitStatus, PROJECT_FILE, ignoreProjectFile, projectFileOffer, projectFileText } from "./projectFile";
import { legacyCleanupAction, retireLegacyEntry } from "./legacyMigration";
import { MirrorStatus, liveRequest, mirrorConfigurations, mirrorChoices, mirrorStatusText, peerUrl, pitEndpoint, sessionPicks } from "./explorer/pitServer";
import { SESSION_END_MS } from "./sessionEnd";
import { uploadLog } from "./explorer/upload";
import { resultSummary, resultDetails, progressText } from "./explorer/importJobs";
import { StoreClient, rememberedPeers, syncSummary } from "./explorer/storeClient";
import { Explorer, LogsProvider } from "./explorer";
import { TBA_KEY_QUIET_MS, TBA_KEY_SETTING, planTbaKeyMove } from "./tbaKey";

const PROVIDER_ID = "wpilog-analyzer.mcpServer";
const STANDALONE_INSTALLED_VERSION = "wpilog-mcp.standaloneInstalledVersion";
/** A secret reaches the shared daemon only through this window's in-memory session lease. */
const TBA_SECRET = "wpilog-mcp.tbaApiKey";
const TBA_ACCOUNT_URL = "https://www.thebluealliance.com/account";
const CLAUDE_REGISTERED = "wpilog-mcp.claudeUserRegistration";
const LEGACY_RETIRED = "wpilog-mcp.retiredOwnedDaemon";

/** A read-only extension API; never export context, credentials or mutable service owners. */
export interface ExtensionApi {
  ready: Promise<void>;
  logs: Pick<LogsProvider, "getChildren">;
  smokeData?: (names: string[], start: number, end: number, append?: boolean) => Promise<number>;
}

export function activate(context: vscode.ExtensionContext): ExtensionApi {
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
  const pitUrl = () => vscode.workspace.getConfiguration("wpilog-mcp").get<string>("pitServerUrl");
  const proxyHeaders = pitHeaders(() => connectionDefinitions(vscode.workspace.getConfiguration("wpilog-mcp").get("connections"), pitUrl())
    .filter(connection => connection.kind === "remote").map(connection => connection.url!), context.secrets);
  const mirrorSettings = () => {
    const config = vscode.workspace.getConfiguration("wpilog-mcp");
    return { enabled: config.get<boolean>("mirror.enabled"), folder: config.get<string>("mirror.folder"),
      days: config.get<number>("mirror.days"), maxSizeGb: config.get<number>("mirror.maxSizeGb"),
      robots: config.get<string[]>("mirror.robots"), events: config.get<string[]>("mirror.events"), intervalSec: config.get<number>("mirror.intervalSec") };
  };
  const mirrorConfiguration = () => mirrorConfigurations(pitUrl(), mirrorSettings(),
    vscode.workspace.getConfiguration("wpilog-mcp").get("mirrors"), context.globalStorageUri.fsPath);
  const liveSettings = () => {
    const config = vscode.workspace.getConfiguration("wpilog-mcp");
    return { enabled: config.get<boolean>("live.enabled"), folder: config.get<string>("live.folder"), gatewayPort: config.get<number>("live.gatewayPort") };
  };
  /** Live data reaches this laptop as NetworkTables from the pit's gateway, recorded locally, so views read local files only. */
  const liveConfiguration = () => liveRequest(pitUrl(), liveSettings(), context.globalStorageUri.fsPath);
  async function registration(): Promise<SessionRegistration> {
    const user = userSettings();
    const additional = Array.isArray(user.additionalLogDirectories) ? user.additionalLogDirectories : [];
    if (!user.logDirectory?.trim() && !additional.some(dir => typeof dir === "string" && dir.trim())) {
      user.logDirectory = wellKnownLogDirectory();
    }
    const directories = directoryRegistration(user, projects());
    for (const mirror of mirrorConfiguration())
      if (!directories.paths.some(p => p.path === mirror.folder)) directories.paths.push({ path: mirror.folder, team: null });
    const live = liveRequest(pitUrl(), liveSettings(), context.globalStorageUri.fsPath);
    if (live && !directories.paths.some(p => p.path === live.store)) directories.paths.push({ path: live.store, team: null });
    return { directories, key: await context.secrets.get(TBA_SECRET) || null, pitCredential: await pitRegistration(pitUrl(), context.secrets) };
  }
  const explorer = new Explorer(context, outputChannel, serverManager, standaloneSpec,
    () => directoryRegistration(userSettings(), projects()).paths.map(dir => dir.path), registration, pitUrl, proxyHeaders,
    () => { const user = userSettings(); const roots = combineLogDirectories(user.logDirectory, user.additionalLogDirectories);
      const fallback = wellKnownLogDirectory(); return roots.length ? roots : fallback ? [fallback] : []; });
  context.subscriptions.push(serverManager, explorer, outputChannel, didChangeEmitter);
  endWindowSessions = () => explorer.endSessions(SESSION_END_MS);

  /** Registration finishes before an agent receives its URL; a third session can then read the lease. */
  let localConnected = false;
  async function connectWindow(): Promise<string | undefined> {
    try {
      const client = await explorer.clientFor(standaloneSpec());
      await client.refreshRegistration();
      localConnected = true;
      return client.endpoint;
    } catch (error) {
      outputChannel.appendLine(`Could not register this window's directories: ${error instanceof Error ? error.message : String(error)}`);
      return undefined;
    }
  }
  let refreshing = Promise.resolve();
  function refreshLease() {
    refreshing = refreshing.then(async () => {
      await explorer.connections.refreshLocalRegistrations();
      if (localConnected && await connectWindow()) explorer.serversChanged();
    }).catch(error => outputChannel.appendLine(`Could not refresh this window's lease: ${String(error)}`));
    return refreshing;
  }

  const provider: vscode.McpServerDefinitionProvider = {
    onDidChangeMcpServerDefinitions: didChangeEmitter.event,
    provideMcpServerDefinitions: () => explorer.connections.definitions.map(connection => new vscode.McpHttpServerDefinition(
      `WPILog: ${connection.label} [${connection.id}]`, vscode.Uri.parse(connection.url ?? "http://127.0.0.1:0/mcp"), {}, context.extension.packageJSON.version)),
    resolveMcpServerDefinition: async server => {
      const connection = explorer.connections.definitions.find(item => server.label === `WPILog: ${item.label} [${item.id}]`);
      if (!connection) throw new Error("This connection changed; refresh the MCP server list");
      const client = await explorer.connections.context(connection.id).client();
      if (connection.kind === "local") localConnected = true;
      return new vscode.McpHttpServerDefinition(server.label, vscode.Uri.parse(client.endpoint), await proxyHeaders(client.endpoint), context.extension.packageJSON.version);
    },
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
  function runClaude(command: LauncherCommand): Promise<ClaudeReply> {
    return new Promise(resolve => execFile(command.command, command.args,
      { timeout: 30_000, windowsHide: true, windowsVerbatimArguments: command.windowsVerbatimArguments }, (error, stdout, stderr) => {
        // The pure registration policy decides which add diagnostics to show; never log get.
        resolve({ ok: !error, stdout, stderr });
      }));
  }
  async function finishClaudeRegistration(result: ClaudeRegistrationAttempt, name: string, key: string, value: string, command: string) {
    let feedback = claudeRegistrationFeedback(result, name, command);
    if (feedback.remember) {
      try { await context.globalState.update(key, value); }
      catch { feedback = claudeRegistrationFeedback({ ...result, result: "failed" }, name, command); }
    }
    if (feedback.output) outputChannel.appendLine(feedback.output);
    if (feedback.information) void vscode.window.showInformationMessage(feedback.information);
    if (feedback.warning && await vscode.window.showWarningMessage(feedback.warning, "Copy Command") === "Copy Command")
      await vscode.env.clipboard.writeText(feedback.copyCommand!);
  }
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
    const result = await registerWithClaude(cli, claudeArgs(spec.launcher, process.platform), process.platform, runClaude);
    await finishClaudeRegistration(result, "wpilog-analyzer", CLAUDE_REGISTERED, spec.launcher, text);
  }
  function scheduleProjectOffers(requested = false) {
    projectOffers = projectOffers.then(async () => {
      if (!requested && !localConnected) return;
      await migrateLegacy();
      await retireOpenProjects();
      await registerClaude(requested);
      if (!vscode.workspace.getConfiguration("wpilog-mcp").get<boolean>("enableForClaudeCode") && !requested) return;
      for (const folder of localFolders()) await offerProjectFile(context, outputChannel, folder);
    }).catch(error => outputChannel.appendLine(`Claude Code setup failed: ${String(error)}`));
    return projectOffers;
  }
  context.subscriptions.push(vscode.commands.registerCommand("wpilog-mcp.registerWithClaudeCode", () => scheduleProjectOffers(true)));

  // The server owns disk policy and transfers; this window supplies settings and user choices.
  const mirrorBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 20);
  mirrorBar.command = "wpilog-mcp.mirrorActions";
  let mirrorState: MirrorStatus = { state: "disabled" }, mirrorPolling = false;
  let lastConfigured: string[] = [];
  explorer.mirrorStatus = () => mirrorState;
  let mirrorTimer: ReturnType<typeof setInterval> | undefined;
  async function storeClient(): Promise<StoreClient> {
    const endpoint = await connectWindow();
    if (!endpoint) throw new Error("The local server could not be started; see WPILog Analyzer output.");
    return new StoreClient(endpoint);
  }
  function showMirror(status: MirrorStatus) {
    mirrorState = status;
    mirrorBar.text = mirrorStatusText(status);
    mirrorBar.tooltip = mirrorChoices(status).flatMap(item => [item.config?.folder,
      item.last_sync ? `Last synchronized: ${item.last_sync}` : undefined,
      item.result?.reason, ...(item.result?.refusals ?? []), ...(item.result?.retained ?? [])]).filter(Boolean).join("\n");
    if (pitUrl()?.trim() || status.state !== "disabled") mirrorBar.show(); else mirrorBar.hide();
  }
  async function pollMirror() {
    if (mirrorPolling) return;
    mirrorPolling = true;
    try {
      const client = await storeClient(), status = await client.status();
      const changed = JSON.stringify(mirrorChoices(mirrorState).map(s => s.last_sync)) !== JSON.stringify(mirrorChoices(status).map(s => s.last_sync));
      showMirror(status); if (changed) explorer.serversChanged();
    } catch (error) {
      showMirror({ ...mirrorState, state: "offline", result: { reason: error instanceof Error ? error.message : String(error) } });
    } finally { mirrorPolling = false; }
  }
  async function configureMirror() {
    const configs = mirrorConfiguration();
    if (!configs.length && !lastConfigured.length) return;
    const client = await storeClient();
    // Only an explicit settings removal stops a service this window configured. Disposing the
    // window releases its UI resources; the server's grants and synchronization remain alive.
    const current = mirrorChoices(await client.status());
    for (const folder of lastConfigured.filter(folder => !configs.some(config => config.folder === folder))) {
      const selected = current.find(item => item.config?.folder === folder);
      if (selected) {
        if (current.length > 1 && !selected.mirror_id) throw new Error("Mirror is initializing; retry after its identity is available.");
        await client.disable(selected.mirror_id);
      }
    }
    for (const config of configs) await client.configure(config);
    lastConfigured = configs.map(config => config.folder);
    showMirror(await client.status());
    clearInterval(mirrorTimer);
    if (configs.length || mirrorState.state !== "disabled") mirrorTimer = setInterval(() => void pollMirror(), 5000);
  }
  async function chooseMirror(client: StoreClient): Promise<MirrorStatus | undefined> {
    const choices = mirrorChoices(await client.status());
    if (!choices.length) throw new Error("Configure a mirror first.");
    if (choices.length === 1) return choices[0];
    const selected = (await vscode.window.showQuickPick(choices.map(status => ({ label: status.config?.folder ?? "Initializing mirror",
      description: `${status.state} · ${status.config?.origin ?? status.origin?.url ?? ""}`, status })),
    { title: "Choose the mirror for this action", placeHolder: "No mirror is the default" }))?.status;
    if (selected && !selected.mirror_id) throw new Error("Mirror is initializing; retry after its identity is available.");
    return selected;
  }
  let liveConfigured: string | undefined;
  /** Sets or stops the server's live source; a gateway that cannot be reached yet is the source's state, not an error. */
  async function configureLive() {
    try {
      const config = liveConfiguration();
      if (!config && !liveConfigured) return;
      const client = await storeClient();
      if (config) {
        const status = await client.configureLive(config); liveConfigured = JSON.stringify(config);
        outputChannel.appendLine(`Live source: ${status.state}, recording ${config.robot.host}:${config.robot.port} into ${config.store}`);
      } else if (liveConfigured) { await client.disableLive(); liveConfigured = undefined; outputChannel.appendLine("Live source stopped"); }
    } catch (error) { outputChannel.appendLine(`Could not set the live source: ${error instanceof Error ? error.message : String(error)}`); }
  }
  // Both routes hold the server's configuration guard, including stopping the old capture.
  // Serialize the whole operation, across activation and settings events, not just its lease.
  let configuring = Promise.resolve();
  function configureServices(live: boolean, mirror: boolean): Promise<void> {
    configuring = configuring.then(async () => {
      await refreshLease();
      if (live) await configureLive();
      if (mirror) await command(configureMirror)();
    }).catch(error => outputChannel.appendLine(`Could not configure local services: ${String(error)}`));
    return configuring;
  }
  function command(action: () => Promise<unknown>) {
    return async () => { try { await action(); } catch (error) {
      const text = error instanceof Error ? error.message : String(error); outputChannel.appendLine(text); void vscode.window.showErrorMessage(text);
    } };
  }
  async function pinSession(pinned: boolean) {
    const client = await storeClient(); const status = await chooseMirror(client); if (!status) return;
    if (!status.origin) throw new Error("Configure a mirror and synchronize it before pinning sessions.");
    if (status.replicas?.format_version === 2 && status.mirror_id) {
      const ids = pinned ? await client.mirrorSessions(status.mirror_id) : status.origin.pinned_sessions;
      const choices = ids.map(id => ({ label: id, id }));
      if (pinned) choices.push({ label: "Enter a session ID…", id: "" });
      const choice = await vscode.window.showQuickPick(choices, { title: pinned ? "Include session for acquisition" : "Remove explicit session inclusion" });
      if (!choice) return;
      const id = choice.id || await vscode.window.showInputBox({ title: "Session ID from the selected master's catalog", validateInput: value => value.trim() && value.length <= 1024 ? undefined : "Enter a session ID (up to 1024 characters)" });
      if (!id) return;
      await client.pin(id, pinned, status.mirror_id); await pollMirror(); return;
    }
    explorer.logs.refresh();
    const candidate = explorer.pitSpec();
    const pit = candidate && pitUrl() && peerUrl(pitUrl()!) === status.origin.url ? candidate : undefined;
    const listing = pit ? await explorer.logs.listing(pit) : undefined;
    const local = listing && listing.status !== "error" ? undefined : await explorer.logs.listing(explorer.spec());
    const available = local ? { ...local, logs: local.logs?.filter(log => log.session?.origin === status.origin!.url) } : listing!;
    const choices = sessionPicks(available, status.origin.pinned_sessions, !pinned);
    const choice = await vscode.window.showQuickPick(choices, { title: pinned ? "Pin session" : "Unpin session", placeHolder: "Pins include a session for acquisition; the storage cap still applies" });
    if (!choice) return;
    await client.pin(choice.id, pinned, status.mirror_id);
    void vscode.window.showInformationMessage(`${pinned ? "Pin" : "Unpin"} queued for ${choice.label}. It takes effect after the current store job.`);
    await pollMirror();
  }
  async function syncFromLaptop() {
    const client = await storeClient(), inventory = await client.targets();
    for (const failed of inventory.unreadable) outputChannel.appendLine(`${failed.path}: ${failed.reason}`);
    if (!inventory.stores.length) throw new Error("No writable store is configured. Use Organize Logs to create a store first.");
    const target = inventory.stores.length === 1 ? inventory.stores[0] : (await vscode.window.showQuickPick(
      inventory.stores.map(store => ({ label: path.basename(store.path), description: store.path, store })), { title: "Sync into which store?" }))?.store;
    if (!target) return;
    const remembered = rememberedPeers(target);
    const choice = remembered.length ? await vscode.window.showQuickPick([...remembered.map(url => ({ label: url, url })),
      { label: "Another laptop…", url: "" }], { title: "Sync from Laptop", placeHolder: "The other server must be bound to the network" }) : { url: "" };
    if (!choice) return;
    const typed = choice.url || await vscode.window.showInputBox({ title: "Sync from Laptop", prompt: "Other laptop's host:port, or HTTP URL",
      validateInput: value => { try { peerUrl(value); return undefined; } catch (error) { return String(error); } } });
    if (!typed) return;
    const url = peerUrl(typed);
    const result = await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: "Sync from Laptop", cancellable: false }, progress =>
      client.sync(target.path, url, job => progress.report({ message: job.progress ? `${job.progress.phase}: ${job.progress.path ?? ""}` : job.state })));
    outputChannel.appendLine(JSON.stringify(result, null, 2));
    void vscode.window.showInformationMessage(syncSummary(result)); explorer.serversChanged();
  }
  async function uploadToPitServer() {
    const endpoint = pitEndpoint(pitUrl()); if (!endpoint) throw new Error("Set wpilog-mcp.pitServerUrl first.");
    const inventory = await new StoreClient(endpoint, undefined, proxyHeaders).uploadTargets();
    for (const failed of inventory.unreadable) outputChannel.appendLine(`${failed.path}: ${failed.reason}`);
    if (!inventory.stores.length) throw new Error("The pit server has no configured writable store.");
    const target = inventory.stores.length === 1 ? inventory.stores[0] : (await vscode.window.showQuickPick(
      inventory.stores.map(store => ({ label: store.id, store })), { title: "Upload into which pit store?" }))?.store;
    if (!target) return;
    const files = await vscode.window.showOpenDialog({ title: "Upload Logs to Pit Server", canSelectMany: true, canSelectFolders: false,
      filters: { "Robot logs": ["wpilog", "revlog"], "All files": ["*"] } });
    if (!files?.length) return;
    await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: "Upload Logs to Pit Server", cancellable: false }, async progress => {
      for (const file of files) {
        progress.report({ message: path.basename(file.fsPath) });
        const result = await uploadLog(endpoint, file.fsPath, target.id, job => progress.report({ message: progressText(job) }), undefined, proxyHeaders);
        for (const line of resultDetails(result)) outputChannel.appendLine(line);
        void vscode.window.showInformationMessage(`${path.basename(file.fsPath)}: ${resultSummary(result)}`);
      }
    });
    explorer.serversChanged();
  }
  async function registerPitClaude(requested: boolean) {
    const choices = explorer.connections.definitions.filter(item => item.kind === "remote");
    if (!choices.length) { if (requested) throw new Error("Configure a remote connection first"); return; }
    if (!requested) return; // Configuration alone never installs software or registers an agent.
    const chosen = choices.length === 1 ? choices[0] : (await vscode.window.showQuickPick(choices.map(connection => ({ label: connection.label, connection })),
      { title: "Choose the connection to register with Claude Code" }))?.connection;
    if (!chosen) return;
    const url = chosen.url!, name = chosen.id === "pit" ? "wpilog-pit" : `wpilog-${chosen.id}`;
    await explorer.connections.context(chosen.id).client();
    const cli = findClaude(process.env.PATH ?? "", process.platform, file => {
      try { fs.accessSync(file, process.platform === "win32" ? fs.constants.F_OK : fs.constants.X_OK); return fs.statSync(file).isFile(); } catch { return false; }
    });
    const credential = await pitRegistration(url, context.secrets);
    if (credential.authorization) throw new Error("Configure proxy authentication in Claude Code for this direct HTTP connection. SecretStorage credentials are not copied into CLI arguments or a local bridge.");
    const text = claudeHttpCommandText(name, url, process.platform);
    if (!cli) {
      outputChannel.appendLine(text);
      if (await vscode.window.showInformationMessage("Claude Code CLI was not found. Copy its user registration command?", "Copy Command") === "Copy Command") await vscode.env.clipboard.writeText(text);
      return;
    }
    const result = await registerWithClaude(cli, claudeHttpArgs(name, url), process.platform, runClaude);
    await finishClaudeRegistration(result, name, `wpilog-mcp.claudeConnection.${chosen.id}`, url, text);
  }

  async function changePitCredential(clear: boolean) {
    const url = pitEndpoint(pitUrl()); if (!url) throw new Error("Set wpilog-mcp.pitServerUrl first.");
    if (clear) await context.secrets.delete(pitSecretKey(url));
    else {
      const user = await vscode.window.showInputBox({ title: `Proxy username for ${new URL(url).host}`, ignoreFocusOut: true });
      if (user === undefined) return;
      const password = await vscode.window.showInputBox({ title: "Pit proxy password", password: true, ignoreFocusOut: true });
      if (password === undefined) return;
      await context.secrets.store(pitSecretKey(url), basicCredential(user, password));
    }
    await refreshLease(); didChangeEmitter.fire(); explorer.serversChanged();
    void vscode.window.showInformationMessage(clear ? "Pit proxy credential cleared. Re-register Claude Code to use the direct pit URL."
      : "Pit proxy credential saved in SecretStorage for direct Explorer and VS Code agent requests. Configure Claude Code authentication independently.");
  }
  context.subscriptions.push(context.secrets.onDidChange(event => {
    if (event.key.startsWith(PIT_SECRET_PREFIX)) { void refreshLease(); didChangeEmitter.fire(); explorer.serversChanged(); }
  }));
  const mirrorActions = [
    { label: "Show Mirror Status", id: "status" }, { label: "Show Peer Discovery", id: "peers" },
    { label: "Migrate Mirror for Replica Synchronization", id: "migrate" },
    { label: "Sync Now", id: "syncNow" }, { label: "Pin Session", id: "pinSession" },
    { label: "Unpin Session", id: "unpinSession" }, { label: "Open Mirror Folder", id: "openMirrorFolder" },
    { label: "Sync from Laptop", id: "syncFromLaptop" },
  ];
  context.subscriptions.push(mirrorBar, { dispose: () => clearInterval(mirrorTimer) },
    vscode.commands.registerCommand("wpilog-mcp.mirrorActions", command(async () => {
      const action = await vscode.window.showQuickPick(mirrorActions, { title: mirrorStatusText(mirrorState).replace(/\$\([^)]+\) /g, "") });
      if (action?.id === "status" || action?.id === "peers") {
        const client = await storeClient();
        const value = action.id === "peers" ? await client.peers() : await chooseMirror(client);
        if (value) { outputChannel.appendLine(JSON.stringify(value, null, 2)); outputChannel.show(true); }
      } else if (action?.id === "migrate") {
        const client = await storeClient(), selected = await chooseMirror(client); if (!selected) return;
        if (!selected.mirror_id) throw new Error("Mirror is initializing; retry when its identity is available.");
        if (selected.replicas?.format_version === 2) { void vscode.window.showInformationMessage("This mirror already uses replica synchronization."); return; }
        const confirmed = await vscode.window.showWarningMessage(`Migrate ${selected.config?.folder}? Existing files are preserved. Old mirror copies remain unconfirmed until their master identity can be established.`, { modal: true }, "Migrate");
        if (confirmed === "Migrate") { await client.migrate(selected.mirror_id); await pollMirror(); }
      } else if (action) await vscode.commands.executeCommand(`wpilog-mcp.${action.id}`);
    })),
    vscode.commands.registerCommand("wpilog-mcp.pinSession", command(() => pinSession(true))),
    vscode.commands.registerCommand("wpilog-mcp.unpinSession", command(() => pinSession(false))),
    vscode.commands.registerCommand("wpilog-mcp.syncNow", command(async () => {
      const client = await storeClient(), selected = await chooseMirror(client); if (!selected) return;
      await client.syncNow(selected.mirror_id); await pollMirror();
    })),
    vscode.commands.registerCommand("wpilog-mcp.openMirrorFolder", command(async () => {
      const selected = await chooseMirror(await storeClient()); if (!selected) return;
      const folder = selected.config?.folder;
      if (!folder) throw new Error("Configure a mirror first.");
      await vscode.commands.executeCommand("revealFileInOS", vscode.Uri.file(folder));
    })),
    vscode.commands.registerCommand("wpilog-mcp.syncFromLaptop", command(syncFromLaptop)),
    vscode.commands.registerCommand("wpilog-mcp.uploadToPitServer", command(uploadToPitServer)),
    vscode.commands.registerCommand("wpilog-mcp.setPitProxyCredential", command(() => changePitCredential(false))),
    vscode.commands.registerCommand("wpilog-mcp.clearPitProxyCredential", command(() => changePitCredential(true))),
    vscode.commands.registerCommand("wpilog-mcp.registerPitWithClaudeCode", command(() => registerPitClaude(true)))
  );

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
      const liveChanged = event.affectsConfiguration("wpilog-mcp.pitServerUrl") || event.affectsConfiguration("wpilog-mcp.live");
      const mirrorChanged = event.affectsConfiguration("wpilog-mcp.pitServerUrl") || event.affectsConfiguration("wpilog-mcp.mirror") || event.affectsConfiguration("wpilog-mcp.mirrors");
      if (liveChanged || mirrorChanged) void configureServices(liveChanged, mirrorChanged);
      if (mirrorChanged || event.affectsConfiguration("wpilog-mcp.connections")) {
        didChangeEmitter.fire(); explorer.serversChanged();
        if (event.affectsConfiguration("wpilog-mcp.pitServerUrl")) void command(() => registerPitClaude(false))();
      }
      if (event.affectsConfiguration("wpilog-mcp.enableForClaudeCode")) void scheduleProjectOffers();
    }),
    vscode.workspace.onDidChangeWorkspaceFolders(() => {
      void refreshLease();
      void scheduleProjectOffers();
    })
  );

  const ready = (async () => {
    await moveTbaKeyNow();
    // Opening Logs or enumerating agent servers is network-free. Local services are opt-in.
    await configureServices(true, true);
  })();
  outputChannel.appendLine("WPILog Analyzer extension activated.");
  return { ready, logs: { getChildren: explorer.logs.getChildren.bind(explorer.logs) },
    ...(context.extensionMode === vscode.ExtensionMode.Test ? { smokeData: explorer.editor.inspectData.bind(explorer.editor) } : {}) };
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

/** Set by activate: ends the window's server sessions as it closes or reloads. */
let endWindowSessions: (() => Promise<boolean>) | undefined;

/** The editor waits for this before shutting the extension down, so the sessions' endings are not
 * cut off: a lost one left the window's views attached on the server for an hour. */
export async function deactivate(): Promise<void> {
  await endWindowSessions?.();
}
