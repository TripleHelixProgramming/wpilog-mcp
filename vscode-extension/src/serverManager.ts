/** Starts the shared standalone server. Directory and secret changes belong to session leases. */
import { execFile } from "child_process";
import * as fs from "fs";
import * as http from "http";
import * as os from "os";
import * as vscode from "vscode";
import {
  Backoff,
  HealthVerdict,
  MAX_START_ATTEMPTS,
  classifyHealth,
  healthUrl,
  serverLogPath,
  serverUrl,
} from "./projectServers";
import {
  STANDALONE_GUIDE_URL,
  StandaloneInstall,
  launcherCommand,
  olderVersion,
  portInPidFile,
  standaloneStartArgs,
  standaloneStopArgs,
} from "./standaloneServer";

/** The standalone install's server: where the install is, and the server's name there. */
export interface StandaloneDaemonSpec extends StandaloneInstall {
  kind: "standalone";
  /** The server's name in the install's configuration file (see STANDALONE_SERVER). */
  name: string;
}

export type DaemonSpec = StandaloneDaemonSpec;

/** How long a `start` or `stop` may take: a JVM to boot and a daemon to answer, or to drain. */
const COMMAND_TIMEOUT_MS = 120_000;

/** What the manager keeps per daemon. */
interface DaemonState {
  backoff: Backoff;
  retryTimer?: ReturnType<typeof setTimeout>;
  inFlight?: Promise<string | undefined>;
  lastUrl?: string;
  errorShown: boolean;
}

export class ServerManager implements vscode.Disposable {
  private readonly states = new Map<string, DaemonState>();

  /**
   * @param onChanged called when a daemon's URL changes, so VS Code's agents are re-registered
   * @param prepareStandalone completes an install offer or update before starting from the launcher
   */
  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly output: vscode.OutputChannel,
    private readonly onChanged: () => void,
    private readonly prepareStandalone: () => Promise<StandaloneDaemonSpec | undefined>
  ) {}

  private state(name: string): DaemonState {
    let state = this.states.get(name);
    if (!state) {
      state = { backoff: new Backoff(), errorShown: false };
      this.states.set(name, state);
    }
    return state;
  }

  /** Start records the actual port; the extension never chooses one or rewrites servers.yaml. */
  async portFor(spec: DaemonSpec): Promise<number | undefined> {
    return portInPidFile(await fs.promises.readFile(spec.pidFile, "utf8").catch(() => undefined));
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
   */
  ensure(spec: DaemonSpec): Promise<string | undefined> {
    const state = this.state(spec.name);
    if (!state.inFlight) {
      state.inFlight = this.doEnsure(state).finally(() => {
        state.inFlight = undefined;
      });
    }
    return state.inFlight;
  }

  /** Manual restart is the only extension action that explicitly stops a healthy server. */
  async restart(spec: DaemonSpec): Promise<string | undefined> {
    const state = this.state(spec.name);
    await state.inFlight;
    this.cancelRetry(state);
    state.backoff.reset();
    state.errorShown = false;
    if (spec.missing === undefined) {
      const stop = launcherCommand(spec.launcher, standaloneStopArgs(spec.configPath), process.platform);
      const result = await this.run(stop.command, stop.args, stop.windowsVerbatimArguments);
      if (result.code !== 0) return undefined;
    }
    return this.ensure(spec);
  }

  private async doEnsure(state: DaemonState): Promise<string | undefined> {
    this.cancelRetry(state);
    const spec = await this.prepareStandalone();
    return spec ? this.ensureStandalone(spec, state) : undefined;
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
        `WPILog Analyzer: the standalone install was not found (${spec.missing}).`,
        "Install Standalone Server",
        () => vscode.commands.executeCommand("wpilog-mcp.installStandaloneServer")
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
      this.scheduleRetry(spec, state);
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

  dispose(): void {
    for (const state of this.states.values()) this.cancelRetry(state);
  }
}
