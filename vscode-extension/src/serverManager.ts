/**
 * Runs the server the extension shares with every client: its own daemon (see
 * projectServers.ts), whose configuration it writes, whose port it chooses, which it starts
 * with the server's own `start` (idempotent, and restarting a daemon of another version),
 * restarts when its configuration changes, and tries again with backoff when a start fails; or
 * the standalone install's server (see standaloneServer.ts), prepared by the activation
 * callback before it starts with the install's launcher and asks for its port. The existing
 * install configuration remains the user's. This is the VS Code side; its decisions are pure
 * functions in the server and install modules. The manager keeps its state per daemon name, so a second daemon would cost
 * nothing here; there is one at a time because the extension found no use for more
 * (doc/EXPLORER_PLAN.md, decision 5).
 */
import { execFile } from "child_process";
import * as fs from "fs";
import * as http from "http";
import * as net from "net";
import * as os from "os";
import * as vscode from "vscode";
import {
  Backoff,
  HealthVerdict,
  MAX_START_ATTEMPTS,
  buildDaemonConfig,
  classifyHealth,
  daemonIsCurrent,
  healthUrl,
  keepPort,
  portInConfig,
  serverLogPath,
  serverUrl,
  startCommand,
  stopCommand,
} from "./projectServers";
import { writeConfigFile } from "./projectConfigs";
import {
  STANDALONE_GUIDE_URL,
  StandaloneInstall,
  launcherCommand,
  olderVersion,
  portInPidFile,
  standaloneStartArgs,
  standaloneStopArgs,
} from "./standaloneServer";

/** The extension's own daemon: its name, its configuration file, and the projects it serves. */
export interface OwnDaemonSpec {
  kind: "own";
  /** The daemon's name, as `start`, `stop`, and `connect` know it (see DAEMON_NAME). */
  name: string;
  configPath: string;
  /** The projects the server lists the logs of, open or remembered; none when there is no project. */
  folderPaths: string[];
}

/** The standalone install's server: where the install is, and the server's name there. */
export interface StandaloneDaemonSpec extends StandaloneInstall {
  kind: "standalone";
  /** The server's name in the install's configuration file (see STANDALONE_SERVER). */
  name: string;
}

/** The server the extension runs: its own daemon, or the standalone install's server. */
export type DaemonSpec = OwnDaemonSpec | StandaloneDaemonSpec;

/** What a daemon is started from: the JVM, the JAR, and the configuration's values. */
export interface DaemonInputs {
  javaPath: string;
  jarPath: string;
  maxHeap: string;
  logDirs: string[];
  teamNumber: number;
  tbaKey?: string;
  cacheDir: string;
  idleExitMinutes: number;
  /** The extension's version, which the bundled JAR reports from `/health`. */
  version: string;
}

/** Remembers the port chosen for each daemon, by name. */
const PORTS_KEY = "wpilog-mcp.serverPorts";

/** How long a `start` or `stop` may take: a JVM to boot and a daemon to answer, or to drain. */
const COMMAND_TIMEOUT_MS = 120_000;

/** What the manager keeps per daemon. */
interface DaemonState {
  backoff: Backoff;
  retryTimer?: ReturnType<typeof setTimeout>;
  inFlight?: Promise<string | undefined>;
  lastUrl?: string;
  lastInputs?: DaemonInputs;
  startedInputs?: DaemonInputs;
  restartPending: boolean;
  restartRevision: number;
  errorShown: boolean;
}

export class ServerManager implements vscode.Disposable {
  private readonly states = new Map<string, DaemonState>();

  /**
   * @param resolveInputs what to start a daemon from, or undefined when it cannot be started
   *     (no Java, no JAR), which the resolver has already reported
   * @param onChanged called when a daemon's URL changes, so VS Code's agents are re-registered
   * @param prepareStandalone completes an install offer or update before starting from the launcher
   */
  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly output: vscode.OutputChannel,
    private readonly resolveInputs: (spec: OwnDaemonSpec, prompt: boolean) => Promise<DaemonInputs | undefined>,
    private readonly onChanged: () => void,
    private readonly prepareStandalone: () => Promise<StandaloneDaemonSpec | undefined>
  ) {}

  private state(name: string): DaemonState {
    let state = this.states.get(name);
    if (!state) {
      state = { backoff: new Backoff(), errorShown: false, restartPending: false, restartRevision: 0 };
      this.states.set(name, state);
    }
    return state;
  }

  /**
   * The port chosen for the extension's daemon, from the extension's state, else from its
   * configuration file; the standalone server's, from the PID file its start wrote.
   */
  async portFor(spec: DaemonSpec): Promise<number | undefined> {
    if (spec.kind === "standalone") {
      return portInPidFile(await fs.promises.readFile(spec.pidFile, "utf8").catch(() => undefined));
    }
    const ports = this.context.globalState.get<Record<string, number>>(PORTS_KEY) ?? {};
    if (ports[spec.name] !== undefined) return ports[spec.name];
    const text = await fs.promises.readFile(spec.configPath, "utf8").catch(() => undefined);
    return portInConfig(text, spec.name);
  }

  private async rememberPort(name: string, port: number) {
    const ports = this.context.globalState.get<Record<string, number>>(PORTS_KEY) ?? {};
    if (ports[name] !== port) {
      await this.context.globalState.update(PORTS_KEY, { ...ports, [name]: port });
    }
  }

  /** A daemon's MCP endpoint, once a port has been chosen. */
  async urlFor(spec: DaemonSpec): Promise<string | undefined> {
    const port = await this.portFor(spec);
    return port === undefined ? undefined : serverUrl(port);
  }

  /** Where a daemon writes its log; the standalone server's is under the same folder, by its name. */
  logPath(spec: DaemonSpec): string {
    return serverLogPath(os.homedir(), spec.name);
  }

  /**
   * Makes sure a daemon of this version runs with the current configuration, and returns its
   * URL; undefined when it could not be started, in which case a retry is scheduled with
   * backoff. Calls made for one daemon while one is in progress share its outcome.
   *
   * @param prompt whether a missing log directory may be asked for (not in the background)
   */
  ensure(spec: DaemonSpec, prompt = false): Promise<string | undefined> {
    const state = this.state(spec.name);
    if (!state.inFlight) {
      state.inFlight = this.doEnsure(spec, state, prompt).finally(() => {
        state.inFlight = undefined;
      });
    }
    return state.inFlight;
  }

  /**
   * Writes the extension's daemon's configuration file from the current settings without
   * starting anything; true when the file changed. The port is kept, or chosen when there is
   * none yet. The standalone server's configuration is the user's file: nothing is written.
   */
  async writeConfig(spec: DaemonSpec): Promise<boolean> {
    if (spec.kind === "standalone") return false;
    const inputs = await this.resolveInputs(spec, false);
    if (!inputs) return false;
    const state = this.state(spec.name);
    state.lastInputs = inputs;
    const port = (await this.portFor(spec)) ?? (await this.choosePort());
    await this.rememberPort(spec.name, port);
    return this.writeOwnConfig(spec, state, inputs, port);
  }

  /** A disk write is not a running daemon update; retain it until a restart succeeds. */
  private async writeOwnConfig(spec: OwnDaemonSpec, state: DaemonState, inputs: DaemonInputs,
    port: number): Promise<boolean> {
    const changed = await writeConfigFile(
      spec.configPath, buildDaemonConfig({ ...inputs, name: spec.name, port })
    );
    const previous = state.startedInputs;
    if (changed || (previous && (previous.javaPath !== inputs.javaPath ||
      previous.maxHeap !== inputs.maxHeap || previous.jarPath !== inputs.jarPath))) {
      state.restartPending = true;
      state.restartRevision++;
    }
    return changed;
  }

  /** Stops a daemon and starts it again from the current configuration. */
  async restart(spec: DaemonSpec): Promise<string | undefined> {
    const state = this.state(spec.name);
    this.cancelRetry(state);
    state.backoff.reset();
    state.errorShown = false;
    if (spec.kind === "standalone") {
      if (spec.missing === undefined) {
        const stop = launcherCommand(spec.launcher, standaloneStopArgs(spec.configPath), process.platform);
        await this.run(stop.command, stop.args, stop.windowsVerbatimArguments);
      }
      return this.ensure(spec);
    }
    const inputs = state.lastInputs ?? (await this.resolveInputs(spec, false));
    if (inputs) {
      await this.run(inputs.javaPath, stopCommand(inputs.maxHeap, inputs.jarPath, spec.name));
    }
    return this.ensure(spec);
  }

  private async doEnsure(spec: DaemonSpec, state: DaemonState, prompt: boolean): Promise<string | undefined> {
    this.cancelRetry(state);
    if (spec.kind === "standalone") {
      const prepared = await this.prepareStandalone();
      return prepared ? this.ensureStandalone(prepared, state) : undefined;
    }
    const inputs = await this.resolveInputs(spec, prompt);
    if (!inputs) return undefined;
    state.lastInputs = inputs;

    // The port: the one chosen before, unless something else has taken it
    let port = await this.portFor(spec);
    let verdict: HealthVerdict = { kind: "free" };
    for (let attempt = 0; attempt < 3; attempt++) {
      if (port === undefined) port = await this.choosePort();
      verdict = await this.probe(port);
      if (keepPort(port, verdict)) break;
      this.output.appendLine(`${spec.name}: port ${port} is held by another program; choosing another.`);
      port = undefined;
    }
    if (port === undefined) {
      this.output.appendLine(`ERROR: no free port could be found for ${spec.name}.`);
      return undefined;
    }
    await this.rememberPort(spec.name, port);

    await this.writeOwnConfig(spec, state, inputs, port);
    if (verdict.kind === "ours" && !state.restartPending && daemonIsCurrent(verdict, inputs.version)) {
      state.startedInputs = inputs;
      return this.started(spec, state, port);
    }
    const revision = state.restartRevision;
    if (verdict.kind === "ours" && state.restartPending) {
      this.output.appendLine(`${spec.name}: the configuration or launch inputs changed; restarting the server.`);
      const stopped = await this.run(inputs.javaPath, stopCommand(inputs.maxHeap, inputs.jarPath, spec.name));
      if (stopped.code !== 0) {
        this.output.appendLine(`ERROR: the server ${spec.name} did not stop (exit ${stopped.code}).`);
        this.scheduleRetry(spec, state);
        return undefined;
      }
    }

    const result = await this.run(
      inputs.javaPath,
      startCommand(inputs.maxHeap, inputs.jarPath, spec.name, spec.configPath)
    );
    if (result.code === 0) {
      state.startedInputs = inputs;
      // A write while the child starts belongs to the next restart, not this one.
      if (state.restartRevision === revision) state.restartPending = false;
      return this.started(spec, state, port);
    }
    this.output.appendLine(`ERROR: the server ${spec.name} did not start (exit ${result.code}).`);
    this.scheduleRetry(spec, state);
    return undefined;
  }

  /**
   * Makes sure the standalone install's server runs, with the install's own launcher: `start`
   * returns at once when it is running, else starts it, and the PID file then holds its port.
   * The activation callback has completed any install offer or update; the existing config
   * remains the user's. A start failure is reported once, and tried again when something next
   * needs the server. An older server is still reported if an attempted install could not
   * advance its launcher, since the explorer may need endpoints that version does not have.
   */
  private async ensureStandalone(spec: StandaloneDaemonSpec, state: DaemonState): Promise<string | undefined> {
    if (spec.missing !== undefined) {
      this.output.appendLine(`ERROR: the standalone install cannot be used: ${spec.missing}.`);
      this.reportStandaloneOnce(
        state,
        `WPILog Analyzer: wpilog-mcp.useStandaloneServer is on, but the standalone install was not found (${spec.missing}). ` +
          "Install it, or turn the setting off to use the extension's own server.",
        "Open Settings",
        () => vscode.commands.executeCommand("workbench.action.openSettings", "wpilog-mcp.useStandaloneServer")
      );
      return undefined;
    }
    const start = launcherCommand(spec.launcher, standaloneStartArgs(spec.configPath), process.platform);
    const result = await this.run(start.command, start.args, start.windowsVerbatimArguments);
    const port = result.code === 0 ? await this.portFor(spec) : undefined;
    const verdict = port === undefined ? undefined : await this.probe(port);
    if (port === undefined || verdict?.kind !== "ours") {
      this.output.appendLine(
        `ERROR: the standalone server ${spec.name} did not start` +
          (result.code === 0 ? ` (no port recorded in ${spec.pidFile}).` : ` (exit ${result.code}).`)
      );
      this.reportStandaloneOnce(
        state,
        `WPILog Analyzer: the standalone install's server ${spec.name} could not be started. The output shows why; ` +
          "the server's own log may say more.",
        "Show Server Log",
        () => this.showLog(spec)
      );
      return undefined;
    }
    const extensionVersion = this.context.extension.packageJSON.version as string;
    if (olderVersion(verdict.version, extensionVersion)) {
      this.output.appendLine(
        `WARNING: the standalone server is version ${verdict.version ?? "unknown"}, older than the extension ` +
          `(${extensionVersion}); WPILog Explorer may not work until the standalone install is upgraded (${STANDALONE_GUIDE_URL}).`
      );
    }
    return this.started(spec, state, port);
  }

  /** Shows a standalone failure once per activation (until a restart), with the output offered. */
  private reportStandaloneOnce(state: DaemonState, message: string, action: string, act: () => unknown) {
    if (state.errorShown) return;
    state.errorShown = true;
    void vscode.window.showErrorMessage(message, "Show Output", action).then((choice) => {
      if (choice === "Show Output") this.output.show();
      if (choice === action) void act();
    });
  }

  private started(spec: DaemonSpec, state: DaemonState, port: number): string {
    state.backoff.reset();
    state.errorShown = false;
    const url = serverUrl(port);
    if (url !== state.lastUrl) {
      state.lastUrl = url;
      this.output.appendLine(`${spec.name}: server at ${url} (log: ${this.logPath(spec)})`);
      this.onChanged();
    }
    return url;
  }

  /** Tries again after a failure, with the backoff's delay, up to the attempt limit. */
  private scheduleRetry(spec: DaemonSpec, state: DaemonState) {
    if (state.backoff.attempts >= MAX_START_ATTEMPTS) {
      if (!state.errorShown) {
        state.errorShown = true;
        void vscode.window
          .showErrorMessage(
            `WPILog Analyzer: the server ${spec.name} could not be started. The output ` +
              "shows why; the server's own log may say more.",
            "Show Output",
            "Show Server Log"
          )
          .then((choice) => {
            if (choice === "Show Output") this.output.show();
            if (choice === "Show Server Log") void this.showLog(spec);
          });
      }
      return;
    }
    const delay = state.backoff.next();
    this.output.appendLine(`${spec.name}: trying again in ${Math.round(delay / 1000)} s.`);
    state.retryTimer = setTimeout(() => void this.ensure(spec), delay);
  }

  private cancelRetry(state: DaemonState) {
    if (state.retryTimer !== undefined) {
      clearTimeout(state.retryTimer);
      state.retryTimer = undefined;
    }
  }

  /** Opens a daemon's log in an editor. */
  async showLog(spec: DaemonSpec): Promise<void> {
    const file = this.logPath(spec);
    try {
      const document = await vscode.workspace.openTextDocument(vscode.Uri.file(file));
      await vscode.window.showTextDocument(document, { preview: false });
    } catch {
      vscode.window.showInformationMessage(
        `WPILog Analyzer: no server log at ${file} yet; the server ${spec.name} has not been started.`
      );
    }
  }

  /** Runs a program with the arguments, logging what it prints; never throws. */
  private run(
    command: string,
    args: string[],
    windowsVerbatimArguments = false
  ): Promise<{ code: number | undefined; output: string }> {
    this.output.appendLine(`Running: ${command} ${args.join(" ")}`);
    return new Promise((resolve) => {
      execFile(
        command,
        args,
        { timeout: COMMAND_TIMEOUT_MS, windowsHide: true, maxBuffer: 1 << 20, windowsVerbatimArguments },
        (error, stdout, stderr) => {
          const output = `${stdout}${stderr}`.trim();
          if (output) this.output.appendLine(output);
          if (!error) {
            resolve({ code: 0, output });
            return;
          }
          const code = (error as { code?: unknown }).code;
          resolve({ code: typeof code === "number" ? code : undefined, output });
        }
      );
    });
  }

  /** Asks the port's health endpoint who holds it. */
  private probe(port: number): Promise<HealthVerdict> {
    return new Promise((resolve) => {
      const request = http.get(healthUrl(port), { timeout: 2_000 }, (response) => {
        let body = "";
        response.setEncoding("utf8");
        response.on("data", (chunk: string) => {
          if (body.length < 4096) body += chunk;
        });
        response.on("end", () => resolve(classifyHealth(response.statusCode, body)));
        response.on("error", () => resolve({ kind: "stranger" }));
      });
      request.on("timeout", () => {
        request.destroy(new Error("timeout"));
      });
      request.on("error", (error: NodeJS.ErrnoException) =>
        resolve(classifyHealth(undefined, undefined, { code: error.code }))
      );
    });
  }

  /** A port nothing listens on, from the operating system. */
  private choosePort(): Promise<number> {
    return new Promise((resolve, reject) => {
      const server = net.createServer();
      server.once("error", reject);
      server.listen(0, "127.0.0.1", () => {
        const address = server.address();
        const port = typeof address === "object" && address ? address.port : undefined;
        server.close(() => (port === undefined ? reject(new Error("no port")) : resolve(port)));
      });
    });
  }

  dispose(): void {
    for (const state of this.states.values()) this.cancelRetry(state);
  }
}
