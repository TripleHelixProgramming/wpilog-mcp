/**
 * The workspace `.mcp.json` entry through which Claude Code finds the server. Pure functions (no
 * VS Code API) so they can be tested on their own.
 *
 * The entry holds this computer's paths, so it belongs in a `.mcp.json` that git ignores, never in
 * one the repository shares. It never holds the TBA API key: the key reaches the server through a
 * file only this user can read (`-tba-key-file`), or `${TBA_API_KEY:-}` from Claude Code's own
 * environment. Only the `wpilog-analyzer` entry is written; every other server and key in the file
 * is kept.
 */
import { addLogDirectories } from "./logDirectories";

/** The server's name under `mcpServers`. */
export const SERVER_NAME = "wpilog-analyzer";

/** The key as Claude Code should pass it: from its environment, empty when unset. */
export const TBA_KEY_REFERENCE = "${TBA_API_KEY:-}";

export interface ServerEntry {
  command: string;
  args: string[];
  env: Record<string, string>;
}

/**
 * Builds the entry. The key is never included: `tbaKeyFile` names the file holding it, and the
 * environment reference covers a key set where Claude Code runs.
 */
export function buildServerEntry(
  javaPath: string,
  jarPath: string,
  maxHeap: string,
  logDirs: string[],
  teamNumber: number,
  tbaKeyFile?: string
): ServerEntry {
  const args = [`-Xmx${maxHeap}`, "-jar", jarPath];
  const env: Record<string, string> = {};
  addLogDirectories(args, env, logDirs);
  if (teamNumber > 0) {
    args.push("-team", String(teamNumber));
    env["WPILOG_TEAM"] = String(teamNumber);
  }
  if (tbaKeyFile) {
    args.push("-tba-key-file", tbaKeyFile);
  }
  env["TBA_API_KEY"] = TBA_KEY_REFERENCE;
  return { command: javaPath, args, env };
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
