/**
 * The one server every MCP client on this computer shares: the server's named background daemon
 * (`vscode`), on HTTP on the loopback address, which VS Code's agents reach by URL, Claude Code
 * reaches through the server's stdio bridge (`connect vscode`), and the extension itself will
 * reach for the explorer. Pure functions (no VS Code API) so they can be tested on their own;
 * `serverManager.ts` is the glue that runs them.
 *
 * One daemon means one configuration: the daemon cannot have one set of log directories per
 * project as the per-project stdio servers did, so its directories are the union of the window's
 * and every known project's (see unionLogDirectories), and a change to them restarts it.
 */
import * as path from "path";
import { combineLogDirectories } from "./logDirectories";

/** The daemon's name in its configuration file, its PID file, and its log. */
export const DAEMON_NAME = "vscode";

/** The configuration file's name, in the extension's global storage. */
export const CONFIG_FILE_NAME = "vscode.json";

/** How long the daemon runs with no client before it exits, unless a setting says otherwise. */
export const DEFAULT_IDLE_EXIT_MINUTES = 30;

/** Starts that fail in a row before the extension stops trying until something changes. */
export const MAX_START_ATTEMPTS = 6;

/** What the daemon's configuration file holds. */
export interface DaemonConfigValues {
  port: number;
  logDirs: string[];
  teamNumber: number;
  tbaKey?: string;
  /** The extension's own disk cache (see extensionCacheDir in extension.ts). */
  cacheDir?: string;
  /** Minutes with no client before the daemon exits; 0 for never. */
  idleExitMinutes: number;
}

/**
 * The configuration file's text: one `http` server named `vscode`, bound to the loopback address
 * by the server's default, on the port, with the log directories, team number, TBA key, disk
 * cache, and idle exit. The server reads it as it reads the standalone install's `servers.yaml`.
 */
export function buildDaemonConfig(values: DaemonConfigValues): string {
  const server: Record<string, unknown> = { transport: "http", port: values.port };
  if (values.logDirs.length > 0) server.logdir = values.logDirs;
  if (values.teamNumber > 0) server.team = values.teamNumber;
  if (values.tbaKey) server.tba_key = values.tbaKey;
  if (values.cacheDir) server.diskcachedir = values.cacheDir;
  if (values.idleExitMinutes > 0) server.idle_exit_minutes = values.idleExitMinutes;
  return JSON.stringify({ servers: { [DAEMON_NAME]: server } }, null, 2) + "\n";
}

/**
 * The arguments that start the daemon from the JAR: `start vscode --config <file>`. The heap is
 * the daemon's too, which inherits the `-Xmx` of the JVM that starts it.
 */
export function startCommand(maxHeap: string, jarPath: string, configPath: string): string[] {
  return [`-Xmx${maxHeap}`, "-jar", jarPath, "start", DAEMON_NAME, "--config", configPath];
}

/** The arguments that stop the daemon: `stop vscode`. */
export function stopCommand(maxHeap: string, jarPath: string): string[] {
  return [`-Xmx${maxHeap}`, "-jar", jarPath, "stop", DAEMON_NAME];
}

/** The daemon's MCP endpoint, which VS Code's agents are given. */
export function serverUrl(port: number): string {
  return `http://127.0.0.1:${port}/mcp`;
}

/** The daemon's health endpoint, which says whether it is up, and which version. */
export function healthUrl(port: number): string {
  return `http://127.0.0.1:${port}/health`;
}

/** Who answers on the daemon's port. */
export type HealthVerdict =
  /** A wpilog-mcp server, with the version and process ID it reports (older servers report none). */
  | { kind: "ours"; version?: string; pid?: number }
  /** Something that is not a wpilog-mcp server: the port is taken. */
  | { kind: "stranger" }
  /** Nothing: the connection was refused. */
  | { kind: "free" };

/**
 * What a `GET /health` found. A refused connection is nobody. A 200 whose JSON says `status: ok`
 * and counts `sessions` is the server; anything else that answered, or a connection that was
 * accepted and then failed (a timeout, no HTTP), is a stranger, as the server's own `start`
 * judges it.
 */
export function classifyHealth(
  status: number | undefined,
  body: string | undefined,
  error?: { code?: string }
): HealthVerdict {
  if (error) {
    return error.code === "ECONNREFUSED" ? { kind: "free" } : { kind: "stranger" };
  }
  if (status !== 200 || body === undefined) return { kind: "stranger" };
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return { kind: "stranger" };
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return { kind: "stranger" };
  }
  const health = parsed as Record<string, unknown>;
  if (health.status !== "ok" || !("sessions" in health)) return { kind: "stranger" };
  return {
    kind: "ours",
    version: typeof health.version === "string" ? health.version : undefined,
    pid: typeof health.pid === "number" ? health.pid : undefined,
  };
}

/**
 * Whether a server that answered is the one this extension would start: ours, and of the
 * extension's own version (the bundled JAR carries the extension's version). Anything else
 * goes through `start`, which restarts a daemon of another version.
 */
export function daemonIsCurrent(verdict: HealthVerdict, extensionVersion: string): boolean {
  return verdict.kind === "ours" && verdict.version === extensionVersion;
}

/**
 * Whether to keep the port chosen earlier or choose another. The port is chosen once and kept
 * for as long as it is free or ours, so the URL VS Code's agents and the explorer hold stays
 * good; it is chosen again only when something else has taken it, or when none was chosen yet.
 */
export function keepPort(saved: number | undefined, verdict: HealthVerdict): boolean {
  return saved !== undefined && verdict.kind !== "stranger";
}

/**
 * The delays between attempts to start the daemon after a failure: doubling from two seconds to
 * a minute, so a daemon that fails for a passing reason (a port not yet released, a slow disk) is
 * tried again soon, and one that fails for good does not spin.
 */
export class Backoff {
  private attemptsMade = 0;

  constructor(
    private readonly initialMs = 2_000,
    private readonly maxMs = 60_000
  ) {}

  /** The delay before the next attempt, in milliseconds, and counts the attempt. */
  next(): number {
    const delay = Math.min(this.initialMs * 2 ** this.attemptsMade, this.maxMs);
    this.attemptsMade++;
    return delay;
  }

  /** Attempts made since the last success. */
  get attempts(): number {
    return this.attemptsMade;
  }

  /** A success: the next failure starts over from the shortest delay. */
  reset(): void {
    this.attemptsMade = 0;
  }
}

/**
 * The daemon's log directories: every list's directories, each once, in order, with two paths
 * that name the same folder counting once, as the window's own list is built. Absolute paths
 * only: each list was resolved against its own project already.
 */
export function unionLogDirectories(
  lists: string[][],
  pathApi: path.PlatformPath = path
): string[] {
  return combineLogDirectories(undefined, lists.flat(), [], pathApi);
}

/**
 * Whether a project's `.mcp.json` entry already runs the bridge (`connect`), as entries written
 * since the one server do, rather than a stdio server of its own from a per-project
 * configuration file, as earlier entries did. An old entry keeps its per-project file until the
 * project is opened and the entry rewritten.
 */
export function entryUsesConnect(existing: string | undefined): boolean {
  if (existing === undefined) return false;
  let doc: unknown;
  try {
    doc = JSON.parse(existing);
  } catch {
    return false;
  }
  const servers = (doc as { mcpServers?: unknown } | null)?.mcpServers;
  const entry = (servers as Record<string, unknown> | undefined)?.["wpilog-analyzer"];
  const args = (entry as { args?: unknown } | undefined)?.args;
  return Array.isArray(args) && args.includes("connect") && args.includes(DAEMON_NAME);
}

/** Where the daemon writes its log: `~/.wpilog-mcp/logs/vscode.log`, as the server names it. */
export function serverLogPath(homeDir: string, pathApi: path.PlatformPath = path): string {
  return pathApi.join(homeDir, ".wpilog-mcp", "logs", `${DAEMON_NAME}.log`);
}
