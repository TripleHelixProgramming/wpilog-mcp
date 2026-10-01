/**
 * The workspace `.mcp.json` entry through which Claude Code finds the server. Pure functions (no
 * VS Code API) so they can be tested on their own.
 *
 * The entry never holds the TBA API key: it passes `${TBA_API_KEY:-}`, which Claude Code expands
 * from its own environment (empty when unset). Only the `wpilog-analyzer` entry is written; every
 * other server and key in the file is kept.
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

/** Builds the entry. The key is referenced, never included. */
export function buildServerEntry(
  javaPath: string,
  jarPath: string,
  maxHeap: string,
  logDirs: string[],
  teamNumber: number
): ServerEntry {
  const args = [`-Xmx${maxHeap}`, "-jar", jarPath];
  const env: Record<string, string> = {};
  addLogDirectories(args, env, logDirs);
  if (teamNumber > 0) {
    args.push("-team", String(teamNumber));
    env["WPILOG_TEAM"] = String(teamNumber);
  }
  env["TBA_API_KEY"] = TBA_KEY_REFERENCE;
  return { command: javaPath, args, env };
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
