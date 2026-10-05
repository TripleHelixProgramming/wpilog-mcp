/**
 * How Claude Code finds and configures the server. Pure functions (no VS Code API) so they can be
 * tested on their own.
 *
 * As with the standalone install, configuration lives in one file and each project's `.mcp.json`
 * entry only starts the server with it: the extension writes its settings into a configuration
 * file in its global storage (the format of the standalone's `servers.yaml`, as JSON), and the
 * entry runs `connect vscode --config <that file>`, the server's stdio bridge to the one HTTP
 * server every client shares (see oneServer.ts). A settings change rewrites that one file and
 * restarts the server; entries are the same in every project and change only with the Java path
 * or heap size. Entries written before the one server ran `start default` with a configuration
 * file per project; those files are kept until each project's entry is rewritten. The entry holds this computer's paths, so it belongs in a `.mcp.json` that
 * git ignores, not in one the repository shares, where it would be no use to others and each
 * person's extension would rewrite it. It never holds the TBA API key, which is in the
 * configuration file. Only the `wpilog-analyzer` entry is written; every other server and key in
 * `.mcp.json` is kept.
 */

/** The server's name under `mcpServers`. */
export const SERVER_NAME = "wpilog-analyzer";

/** A key reference left where an earlier version wrote the key in plaintext (see scrubTbaKey). */
export const TBA_KEY_REFERENCE = "${TBA_API_KEY:-}";

export interface ServerEntry {
  command: string;
  args: string[];
  env?: Record<string, string>;
}

/** The Claude Code extension for VS Code, whose sessions restart when the window reloads. */
export const CLAUDE_CODE_EXTENSION_ID = "anthropic.claude-code";

/**
 * What to tell the user once the entry is in a folder's `.mcp.json`. Claude Code reads that file
 * only when a session starts (a running session, a new conversation in it, or `/clear` does not
 * pick up a new server), so a session that was already running has no WPILog Analyzer until it
 * restarts. Reloading the window restarts the Claude Code extension's sessions; in a terminal,
 * `claude --continue` starts a new session that keeps the conversation.
 *
 * @param claudeCodeInVsCode whether the Claude Code extension for VS Code is installed
 * @returns the message, and whether to offer to reload the window
 */
export function claudeCodeRestartNotice(
  folderName: string,
  claudeCodeInVsCode: boolean
): { message: string; offerReload: boolean } {
  const restart = claudeCodeInVsCode
    ? "Reload the window to restart Claude Code in VS Code. In a terminal, exit Claude Code and " +
      "run claude --continue, which keeps your conversation."
    : "Exit Claude Code and run claude --continue, which keeps your conversation.";
  return {
    message:
      `WPILog Analyzer is now in ${folderName}/.mcp.json for Claude Code. Claude Code reads that ` +
      `file only when it starts, so a session already running does not have the server yet. ` +
      `${restart} Then approve wpilog-analyzer when Claude Code asks.`,
    offerReload: claudeCodeInVsCode,
  };
}

/**
 * Builds the entry: the JVM, its heap, the JAR, and the bridge to the one server (`connect
 * vscode`, see oneServer.ts) with the daemon's configuration file. The bridge starts the daemon
 * when none is running and joins it when one is, so Claude Code in a terminal with VS Code
 * closed gets a server, and Claude Code beside VS Code shares its server. The heap is the
 * daemon's, which inherits it from the JVM that starts it; the bridge itself uses little.
 */
export function buildServerEntry(
  javaPath: string,
  jarPath: string,
  maxHeap: string,
  configPath: string
): ServerEntry {
  return {
    command: javaPath,
    args: [`-Xmx${maxHeap}`, "-jar", jarPath, "connect", "vscode", "--config", configPath],
  };
}

/** What a project's configuration file holds. */
export interface ServerConfigValues {
  logDirs: string[];
  teamNumber: number;
  tbaKey?: string;
  /** The extension's own disk cache, never the standalone install's (see extensionCacheDir). */
  cacheDir?: string;
}

/**
 * The configuration file's text: one stdio server, `default`, with the log directories, the team
 * number (when set), the TBA key (when set), and the disk cache directory (when set). The server
 * reads it as it reads the standalone's `servers.yaml`.
 */
export function buildServerConfig(values: ServerConfigValues): string {
  const server: Record<string, unknown> = { transport: "stdio" };
  if (values.logDirs.length > 0) server.logdir = values.logDirs;
  if (values.teamNumber > 0) server.team = values.teamNumber;
  if (values.tbaKey) server.tba_key = values.tbaKey;
  if (values.cacheDir) server.diskcachedir = values.cacheDir;
  return JSON.stringify({ servers: { default: server } }, null, 2) + "\n";
}

/**
 * Whether to add or update the entry in a workspace folder. Only with Claude Code enabled (the
 * `wpilog-mcp.enableForClaudeCode` setting), and then in a WPILib robot project, wherever an entry
 * already exists (so an entry an earlier version wrote, pointing at its own since-deleted folder,
 * is brought up to date), or where the user asked for one (the "Add to Claude Code in This
 * Folder" command, which works even with the setting off).
 */
export function shouldWriteEntry(
  enabled: boolean,
  robotProject: boolean,
  hasEntry: boolean,
  requested = false
): boolean {
  return requested || (enabled && (robotProject || hasEntry));
}

/** Whether the file's text holds this server's entry (a file that is not a JSON object holds none). */
export function hasServerEntry(existing: string | undefined): boolean {
  const parsed = parse(existing);
  return parsed.ok && isObject(parsed.doc.mcpServers) && isObject(parsed.doc.mcpServers[SERVER_NAME]);
}

/**
 * The name of another entry in the file that already runs wpilog-mcp (the standalone install's
 * launcher, or a wpilog-mcp JAR), if any: adding this server beside it would give Claude Code two
 * copies of every tool.
 */
export function otherWpilogServer(existing: string | undefined): string | undefined {
  const parsed = parse(existing);
  if (!parsed.ok || !isObject(parsed.doc.mcpServers)) return undefined;
  for (const [name, server] of Object.entries(parsed.doc.mcpServers)) {
    if (name === SERVER_NAME || !isObject(server)) continue;
    const words = [server.command, ...(Array.isArray(server.args) ? server.args : [])];
    if (words.some((w) => typeof w === "string" && /wpilog-mcp/i.test(w))) return name;
  }
  return undefined;
}

/** What git makes of a folder's `.mcp.json`; `none` when it is not in a repository or git is unavailable. */
export type GitStatus = "ignored" | "untracked" | "tracked" | "none";

/**
 * What to do with `.mcp.json` given git's view of it. The entry holds this computer's paths, so it
 * is never written into a file the repository shares (`tracked`); a file git would pick up
 * (`untracked`) is written, with an offer to ignore it.
 */
export function gitAction(status: GitStatus): "write" | "writeAndOfferIgnore" | "skipShared" {
  switch (status) {
    case "tracked":
      return "skipShared";
    case "untracked":
      return "writeAndOfferIgnore";
    default:
      return "write";
  }
}

/** The `.gitignore` text with `.mcp.json` added at the end, under a comment saying why. */
export function addToGitignore(existing: string | undefined): string {
  const text = existing ?? "";
  const lead = text === "" ? "" : text.endsWith("\n") ? "\n" : "\n\n";
  return text + lead + "# Claude Code's MCP servers (paths for this computer only)\n.mcp.json\n";
}

export type Edit = { ok: true; text: string; changed: boolean } | { ok: false; error: string };

type Json = Record<string, unknown>;

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Parses the file's text; an absent or blank file is an empty object. */
function parse(existing: string | undefined): { ok: true; doc: Json } | { ok: false; error: string } {
  if (existing === undefined || existing.trim() === "") {
    return { ok: true, doc: {} };
  }
  let doc: unknown;
  try {
    doc = JSON.parse(existing);
  } catch (e) {
    return { ok: false, error: `.mcp.json is not valid JSON (${(e as Error).message})` };
  }
  if (!isObject(doc)) {
    return { ok: false, error: ".mcp.json does not hold a JSON object" };
  }
  if (doc.mcpServers !== undefined && !isObject(doc.mcpServers)) {
    return { ok: false, error: ".mcp.json's mcpServers is not an object" };
  }
  return { ok: true, doc };
}

function format(doc: Json): string {
  return JSON.stringify(doc, null, 2) + "\n";
}

/**
 * Puts the entry into the file's text, keeping everything else. Refuses (does not overwrite) a
 * file that is not a JSON object.
 */
export function mergeServerEntry(existing: string | undefined, entry: ServerEntry): Edit {
  const parsed = parse(existing);
  if (!parsed.ok) return parsed;
  const doc = parsed.doc;
  const servers = isObject(doc.mcpServers) ? doc.mcpServers : {};
  servers[SERVER_NAME] = entry;
  doc.mcpServers = servers;
  const text = format(doc);
  return { ok: true, text, changed: text !== existing };
}

/**
 * Removes a TBA API key that an earlier version wrote into this server's entry in plaintext: the
 * `-tba-key <key>` arguments, and a literal `TBA_API_KEY` value (replaced by the reference). Other
 * entries are left alone. `changed` is false when there was nothing to remove.
 */
export function scrubTbaKey(existing: string | undefined): Edit {
  if (existing === undefined) return { ok: true, text: "", changed: false };
  const parsed = parse(existing);
  if (!parsed.ok) return parsed;
  const servers = parsed.doc.mcpServers;
  const entry = isObject(servers) ? servers[SERVER_NAME] : undefined;
  if (!isObject(entry)) return { ok: true, text: existing, changed: false };

  let changed = false;
  if (Array.isArray(entry.args)) {
    const args = entry.args as unknown[];
    const kept: unknown[] = [];
    for (let i = 0; i < args.length; i++) {
      if (args[i] === "-tba-key") {
        i++; // and its value
        changed = true;
        continue;
      }
      kept.push(args[i]);
    }
    entry.args = kept;
  }
  if (isObject(entry.env)) {
    const key = entry.env["TBA_API_KEY"];
    if (typeof key === "string" && key !== "" && !key.startsWith("${")) {
      entry.env["TBA_API_KEY"] = TBA_KEY_REFERENCE;
      changed = true;
    }
  }
  return changed ? { ok: true, text: format(parsed.doc), changed } : { ok: true, text: existing, changed };
}
