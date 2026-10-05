/**
 * The one server the extension runs on this computer: a named background daemon of the server
 * JAR, on HTTP on the loopback address, on a port of its own, which VS Code's agents reach by
 * URL, Claude Code reaches through the server's stdio bridge (`connect`), and the extension
 * itself reaches for the explorer. Its log directories and team number are the User settings',
 * and each project's own settings (relative paths inside it, its team) join them, so the
 * server's configuration is the user's together with that of every project, open or
 * remembered. There is no second server: a user who wants some logs kept apart keeps them in
 * another directory (see doc/IDEAS.md for a team number per directory). The server exits on its
 * own when unused. Pure functions (no VS Code API) so they can be tested on their own;
 * `serverManager.ts` is the glue.
 */
import * as path from "path";
import { LogSettings, combineLogDirectories } from "./logDirectories";

/**
 * The daemon's name, as `start`, `stop`, and `connect` know it: `vscode-default`, so the
 * extension's server is never the standalone install's `default` under `~/.wpilog-mcp/` (its
 * PID file, token, and log), and the name says whose it is beside a standalone install's servers.
 */
export const DAEMON_NAME = "vscode-default";

/** How long a daemon runs with no client before it exits, unless a setting says otherwise. */
export const DEFAULT_IDLE_EXIT_MINUTES = 30;

/** Starts that fail in a row before the extension stops trying until something changes. */
export const MAX_START_ATTEMPTS = 6;

/** A project the server serves: its folder, and its own settings. */
export interface ServerProject {
  folderPath: string;
  own: LogSettings;
}

/**
 * The server's log directories and team: the user's directories (absolute ones; the User
 * settings belong to no project, so a relative one names nothing there), then each project's,
 * resolved as VS Code applies a project's settings (the project's own main and additional
 * directories where it sets them, else the user's, relative paths inside the project), each
 * once. The team is the first a project sets, else the user's.
 *
 * @param user the User settings (the main directory auto-detected when the setting is blank)
 * @param pathApi the platform's path rules; tests pass `path.win32` or `path.posix`
 */
export function resolveServer(
  user: LogSettings,
  projects: ServerProject[],
  pathApi: path.PlatformPath = path
): { logDirs: string[]; teamNumber: number } {
  const lists: string[][] = [combineLogDirectories(user.logDirectory, user.additionalLogDirectories, [], pathApi)];
  let teamNumber = 0;
  for (const project of projects) {
    const main = project.own.logDirectory?.trim() ? project.own.logDirectory : user.logDirectory;
    const additional = project.own.additionalLogDirectories ?? user.additionalLogDirectories;
    lists.push(combineLogDirectories(main, additional, [project.folderPath], pathApi));
    if (teamNumber === 0 && project.own.teamNumber) teamNumber = project.own.teamNumber;
  }
  return {
    logDirs: unionLogDirectories(lists, pathApi),
    teamNumber: teamNumber || user.teamNumber || 0,
  };
}

/**
 * The shared server's log directories: the user's and every project's, each once, in order, two
 * paths that name the same folder counting once, as a window's own list is built. Absolute paths only: each
 * list was resolved against its own project already.
 */
export function unionLogDirectories(
  lists: string[][],
  pathApi: path.PlatformPath = path
): string[] {
  return combineLogDirectories(undefined, lists.flat(), [], pathApi);
}

/** What a daemon's configuration file holds. */
export interface DaemonConfigValues {
  name: string;
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
 * The configuration file's text: one `http` server under the daemon's name, bound to the
 * loopback address by the server's default, on the port, with the log directories, team number,
 * TBA key, disk cache, and idle exit. The server reads it as it reads the standalone install's
 * `servers.yaml`.
 */
export function buildDaemonConfig(values: DaemonConfigValues): string {
  const server: Record<string, unknown> = { transport: "http", port: values.port };
  if (values.logDirs.length > 0) server.logdir = values.logDirs;
  if (values.teamNumber > 0) server.team = values.teamNumber;
  if (values.tbaKey) server.tba_key = values.tbaKey;
  if (values.cacheDir) server.diskcachedir = values.cacheDir;
  if (values.idleExitMinutes > 0) server.idle_exit_minutes = values.idleExitMinutes;
  return JSON.stringify({ servers: { [values.name]: server } }, null, 2) + "\n";
}

/** The port a daemon's configuration file names, or undefined when the text is not one. */
export function portInConfig(text: string | undefined, name: string): number | undefined {
  if (text === undefined) return undefined;
  try {
    const doc = JSON.parse(text) as { servers?: Record<string, { port?: unknown }> };
    const port = doc?.servers?.[name]?.port;
    return typeof port === "number" && Number.isInteger(port) && port > 0 ? port : undefined;
  } catch {
    return undefined;
  }
}

/**
 * The arguments that start a daemon from the JAR: `start <name> --config <file>`. The heap is
 * the daemon's too, which inherits the `-Xmx` of the JVM that starts it.
 */
export function startCommand(
  maxHeap: string,
  jarPath: string,
  name: string,
  configPath: string
): string[] {
  return [`-Xmx${maxHeap}`, "-jar", jarPath, "start", name, "--config", configPath];
}

/** The arguments that stop a daemon: `stop <name>`. */
export function stopCommand(maxHeap: string, jarPath: string, name: string): string[] {
  return [`-Xmx${maxHeap}`, "-jar", jarPath, "stop", name];
}

/** A daemon's MCP endpoint, which VS Code's agents are given. */
export function serverUrl(port: number): string {
  return `http://127.0.0.1:${port}/mcp`;
}

/** A daemon's health endpoint, which says whether it is up, and which version. */
export function healthUrl(port: number): string {
  return `http://127.0.0.1:${port}/health`;
}

/** Who answers on a daemon's port. */
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
 * Whether to keep the port chosen earlier or choose another. A port is chosen once per daemon
 * and kept for as long as it is free or ours, so the URL VS Code's agents hold stays good; it is
 * chosen again only when something else has taken it, or when none was chosen yet.
 */
export function keepPort(saved: number | undefined, verdict: HealthVerdict): boolean {
  return saved !== undefined && verdict.kind !== "stranger";
}

/**
 * The delays between attempts to start a daemon after a failure: doubling from two seconds to a
 * minute, so a daemon that fails for a passing reason (a port not yet released, a slow disk) is
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
 * Whether a project's `.mcp.json` entry runs the bridge (`connect`), as entries written since
 * the shared server do, rather than a stdio server of its own, as earlier entries did
 * (`start default`). An old entry keeps its configuration file in the old format until the
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
  return Array.isArray(args) && args.includes("connect");
}

/** Where a daemon writes its log: `~/.wpilog-mcp/logs/<name>.log`, as the server names it. */
export function serverLogPath(
  homeDir: string,
  name: string,
  pathApi: path.PlatformPath = path
): string {
  return pathApi.join(homeDir, ".wpilog-mcp", "logs", `${name}.log`);
}
