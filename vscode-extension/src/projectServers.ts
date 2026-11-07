/** Health and retry decisions shared by the standalone manager, independent of VS Code. */
import * as path from "path";

export const MAX_START_ATTEMPTS = 6;

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

/** Where a daemon writes its log: `~/.wpilog-mcp/logs/<name>.log`, as the server names it. */
export function serverLogPath(
  homeDir: string,
  name: string,
  pathApi: path.PlatformPath = path
): string {
  return pathApi.join(homeDir, ".wpilog-mcp", "logs", `${name}.log`);
}
