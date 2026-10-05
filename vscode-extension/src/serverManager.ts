/**
 * Runs the servers the projects use (see projectServers.ts): writes each daemon's configuration,
 * chooses its port, starts it with the server's own `start`, which is idempotent and restarts a
 * daemon of another version, restarts it when its configuration changes, and tries again with
 * backoff when a start fails. This is the VS Code side; the decisions it makes are the pure
 * functions in projectServers.ts.
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

/** A daemon: its name, the server it runs, its configuration file, and the projects it serves. */
export interface DaemonSpec {
  /** The daemon's name, as `start`, `stop`, and `connect` know it (see daemonNameFor). */
  name: string;
  /** The server's name as the user defined it (`default`, or an entry of `wpilog-mcp.servers`). */
  serverName: string;
  configPath: string;
  /** What VS Code shows for it. */
  label: string;
  /** The projects using the server; none for a window with no folder. */
  folderPaths: string[];
}

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
  errorShown: boolean;
}

export class ServerManager implements vscode.Disposable {
  private readonly states = new Map<string, DaemonState>();

  /**
   * @param resolveInputs what to start a daemon from, or undefined when it cannot be started
   *     (no Java, no JAR), which the resolver has already reported
   * @param onChanged called when a daemon's URL changes, so VS Code's agents are re-registered
   */
  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly output: vscode.OutputChannel,
    private readonly resolveInputs: (spec: DaemonSpec, prompt: boolean) => Promise<DaemonInputs | undefined>,
    private readonly onChanged: () => void
  ) {}

  private state(name: string): DaemonState {
    let state = this.states.get(name);
    if (!state) {
      state = { backoff: new Backoff(), errorShown: false };
      this.states.set(name, state);
    }
    return state;
  }

  /** The port chosen for a daemon, from the extension's state, else from its configuration file. */
  async portFor(spec: DaemonSpec): Promise<number | undefined> {
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

  /** The log directories a daemon was last started or configured with; none before then. */
  logDirsOf(spec: DaemonSpec): string[] {
    return this.states.get(spec.name)?.lastInputs?.logDirs ?? [];
  }

  /** Where a daemon writes its log. */
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
   * Writes a daemon's configuration file from the current settings without starting anything;
   * true when the file changed. The port is kept, or chosen when there is none yet.
   */
  async writeConfig(spec: DaemonSpec): Promise<boolean> {
    const inputs = await this.resolveInputs(spec, false);
    if (!inputs) return false;
    this.state(spec.name).lastInputs = inputs;
    const port = (await this.portFor(spec)) ?? (await this.choosePort());
    await this.rememberPort(spec.name, port);
    return writeConfigFile(spec.configPath, buildDaemonConfig({ ...inputs, name: spec.name, port }));
  }

  /**
   * Stops a daemon if one answers on its port: for a project whose configuration changed while
   * it was closed, so that whoever needs it next starts it with the new configuration.
   */
  async stopIfRunning(spec: DaemonSpec): Promise<void> {
    const port = await this.portFor(spec);
    if (port === undefined) return;
    if ((await this.probe(port)).kind !== "ours") return;
    const inputs = this.state(spec.name).lastInputs ?? (await this.resolveInputs(spec, false));
    if (!inputs) return;
    this.output.appendLine(`${spec.label}: the configuration changed; stopping its server.`);
    await this.run(inputs, stopCommand(inputs.maxHeap, inputs.jarPath, spec.name));
  }

  /** Stops a daemon and starts it again from the current configuration. */
  async restart(spec: DaemonSpec): Promise<string | undefined> {
    const state = this.state(spec.name);
    this.cancelRetry(state);
    state.backoff.reset();
    state.errorShown = false;
    const inputs = state.lastInputs ?? (await this.resolveInputs(spec, false));
    if (inputs) {
      await this.run(inputs, stopCommand(inputs.maxHeap, inputs.jarPath, spec.name));
    }
    return this.ensure(spec);
  }

  private async doEnsure(spec: DaemonSpec, state: DaemonState, prompt: boolean): Promise<string | undefined> {
    this.cancelRetry(state);
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
      this.output.appendLine(`${spec.label}: port ${port} is held by another program; choosing another.`);
      port = undefined;
    }
    if (port === undefined) {
      this.output.appendLine(`ERROR: no free port could be found for ${spec.label}.`);
      return undefined;
    }
    await this.rememberPort(spec.name, port);

    const changed = await writeConfigFile(
      spec.configPath,
      buildDaemonConfig({ ...inputs, name: spec.name, port })
    );
    if (verdict.kind === "ours" && !changed && daemonIsCurrent(verdict, inputs.version)) {
      return this.started(spec, state, port);
    }
    if (verdict.kind === "ours" && changed) {
      this.output.appendLine(`${spec.label}: the configuration changed; restarting its server.`);
      await this.run(inputs, stopCommand(inputs.maxHeap, inputs.jarPath, spec.name));
    }

    const result = await this.run(
      inputs,
      startCommand(inputs.maxHeap, inputs.jarPath, spec.name, spec.configPath)
    );
    if (result.code === 0) {
      return this.started(spec, state, port);
    }
    this.output.appendLine(`ERROR: the server for ${spec.label} did not start (exit ${result.code}).`);
    this.scheduleRetry(spec, state);
    return undefined;
  }

  private started(spec: DaemonSpec, state: DaemonState, port: number): string {
    state.backoff.reset();
    state.errorShown = false;
    const url = serverUrl(port);
    if (url !== state.lastUrl) {
      state.lastUrl = url;
      this.output.appendLine(`${spec.label}: server at ${url} (log: ${this.logPath(spec)})`);
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
            `WPILog Analyzer: the server for ${spec.label} could not be started. The output ` +
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
    this.output.appendLine(`${spec.label}: trying again in ${Math.round(delay / 1000)} s.`);
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
        `WPILog Analyzer: no server log at ${file} yet; the server for ${spec.label} has not been started.`
      );
    }
  }

  /** Runs the JAR with the arguments, logging what it prints; never throws. */
  private run(
    inputs: DaemonInputs,
    args: string[]
  ): Promise<{ code: number | undefined; output: string }> {
    this.output.appendLine(`Running: ${inputs.javaPath} ${args.join(" ")}`);
    return new Promise((resolve) => {
      execFile(
        inputs.javaPath,
        args,
        { timeout: COMMAND_TIMEOUT_MS, windowsHide: true, maxBuffer: 1 << 20 },
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
