/**
 * Runs the one server (see oneServer.ts): writes its configuration, chooses its port, starts it
 * with the server's own `start`, which is idempotent and restarts a daemon of another version,
 * restarts it when its configuration changes, and tries again with backoff when a start fails.
 * This is the VS Code side; the decisions it makes are the pure functions in oneServer.ts.
 */
import { execFile } from "child_process";
import * as http from "http";
import * as net from "net";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import {
  Backoff,
  CONFIG_FILE_NAME,
  HealthVerdict,
  MAX_START_ATTEMPTS,
  buildDaemonConfig,
  classifyHealth,
  daemonIsCurrent,
  healthUrl,
  keepPort,
  serverLogPath,
  serverUrl,
  startCommand,
  stopCommand,
} from "./oneServer";
import { writeConfigFile } from "./projectConfigs";

/** What the daemon is started from: the JVM, the JAR, and the configuration's values. */
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

/** Remembers the port chosen for this user. */
const PORT_KEY = "wpilog-mcp.serverPort";

/** How long a `start` or `stop` may take: a JVM to boot and a daemon to answer, or to drain. */
const COMMAND_TIMEOUT_MS = 120_000;

export class ServerManager implements vscode.Disposable {
  private readonly backoff = new Backoff();
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private inFlight: Promise<string | undefined> | undefined;
  private lastUrl: string | undefined;
  private lastInputs: DaemonInputs | undefined;
  private errorShown = false;

  /**
   * @param resolveInputs what to start the daemon from, or undefined when it cannot be started
   *     (no Java, no JAR), which the resolver has already reported
   * @param onChanged called when the daemon's URL changes, so VS Code's agents are re-registered
   */
  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly output: vscode.OutputChannel,
    private readonly resolveInputs: (prompt: boolean) => Promise<DaemonInputs | undefined>,
    private readonly onChanged: () => void
  ) {}

  /** The daemon's configuration file, in the extension's global storage. */
  get configPath(): string {
    return path.join(this.context.globalStorageUri.fsPath, CONFIG_FILE_NAME);
  }

  /** The port chosen for this user, once one has been. */
  get port(): number | undefined {
    return this.context.globalState.get<number>(PORT_KEY);
  }

  /** The daemon's MCP endpoint, once a port has been chosen. */
  url(): string | undefined {
    const port = this.port;
    return port === undefined ? undefined : serverUrl(port);
  }

  /** Where the daemon writes its log. */
  logPath(): string {
    return serverLogPath(os.homedir());
  }

  /**
   * Makes sure a daemon of this version runs with the current configuration, and returns its
   * URL; undefined when it could not be started, in which case a retry is scheduled with
   * backoff. Calls made while one is in progress share its outcome.
   *
   * @param prompt whether a missing log directory may be asked for (not in the background)
   */
  ensure(prompt = false): Promise<string | undefined> {
    if (!this.inFlight) {
      this.inFlight = this.doEnsure(prompt).finally(() => {
        this.inFlight = undefined;
      });
    }
    return this.inFlight;
  }

  /** Writes the configuration file from the current settings without starting anything. */
  async writeConfig(): Promise<boolean> {
    const inputs = await this.resolveInputs(false);
    if (!inputs) return false;
    this.lastInputs = inputs;
    const port = this.port ?? (await this.choosePort());
    await this.context.globalState.update(PORT_KEY, port);
    return writeConfigFile(this.configPath, buildDaemonConfig({ ...inputs, port }));
  }

  /** Stops the daemon and starts it again from the current configuration. */
  async restart(): Promise<string | undefined> {
    this.cancelRetry();
    this.backoff.reset();
    this.errorShown = false;
    const inputs = this.lastInputs ?? (await this.resolveInputs(false));
    if (inputs) {
      await this.run(inputs, stopCommand(inputs.maxHeap, inputs.jarPath));
    }
    return this.ensure();
  }

  private async doEnsure(prompt: boolean): Promise<string | undefined> {
    this.cancelRetry();
    const inputs = await this.resolveInputs(prompt);
    if (!inputs) return undefined;
    this.lastInputs = inputs;

    // The port: the one chosen before, unless something else has taken it
    let port = this.port;
    let verdict: HealthVerdict = { kind: "free" };
    for (let attempt = 0; attempt < 3; attempt++) {
      if (port === undefined) port = await this.choosePort();
      verdict = await this.probe(port);
      if (keepPort(port, verdict)) break;
      this.output.appendLine(`Port ${port} is held by another program; choosing another.`);
      port = undefined;
    }
    if (port === undefined) {
      this.output.appendLine("ERROR: no free port could be found for the server.");
      return undefined;
    }
    await this.context.globalState.update(PORT_KEY, port);

    const changed = await writeConfigFile(this.configPath, buildDaemonConfig({ ...inputs, port }));
    if (verdict.kind === "ours" && !changed && daemonIsCurrent(verdict, inputs.version)) {
      return this.started(port);
    }
    if (verdict.kind === "ours" && changed) {
      this.output.appendLine("The server's configuration changed; restarting it.");
      await this.run(inputs, stopCommand(inputs.maxHeap, inputs.jarPath));
    }

    const result = await this.run(inputs, startCommand(inputs.maxHeap, inputs.jarPath, this.configPath));
    if (result.code === 0) {
      return this.started(port);
    }
    this.output.appendLine(`ERROR: the server did not start (exit ${result.code}).`);
    this.scheduleRetry();
    return undefined;
  }

  private started(port: number): string {
    this.backoff.reset();
    this.errorShown = false;
    const url = serverUrl(port);
    if (url !== this.lastUrl) {
      this.lastUrl = url;
      this.output.appendLine(`Server: ${url} (log: ${this.logPath()})`);
      this.onChanged();
    }
    return url;
  }

  /** Tries again after a failure, with the backoff's delay, up to the attempt limit. */
  private scheduleRetry() {
    if (this.backoff.attempts >= MAX_START_ATTEMPTS) {
      if (!this.errorShown) {
        this.errorShown = true;
        void vscode.window
          .showErrorMessage(
            "WPILog Analyzer: the server could not be started. The output shows why; " +
              "the server's own log may say more.",
            "Show Output",
            "Show Server Log"
          )
          .then((choice) => {
            if (choice === "Show Output") this.output.show();
            if (choice === "Show Server Log") void this.showLog();
          });
      }
      return;
    }
    const delay = this.backoff.next();
    this.output.appendLine(`Trying again in ${Math.round(delay / 1000)} s.`);
    this.retryTimer = setTimeout(() => void this.ensure(), delay);
  }

  private cancelRetry() {
    if (this.retryTimer !== undefined) {
      clearTimeout(this.retryTimer);
      this.retryTimer = undefined;
    }
  }

  /** Opens the daemon's log in an editor. */
  async showLog(): Promise<void> {
    const file = this.logPath();
    try {
      const document = await vscode.workspace.openTextDocument(vscode.Uri.file(file));
      await vscode.window.showTextDocument(document, { preview: false });
    } catch {
      vscode.window.showInformationMessage(
        `WPILog Analyzer: no server log at ${file} yet; the server has not been started.`
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
    this.cancelRetry();
  }
}
