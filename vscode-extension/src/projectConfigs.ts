/**
 * The configuration files the extension keeps for Claude Code's server, one per project, in its
 * global storage. A file holds the TBA API key, so it is written only for a project whose
 * `.mcp.json` ends up with the entry that starts the server with it, and clearing the key
 * reaches every file in the directory, remembered project or not. The servers' configuration
 * files (see projectServers.ts) are written with the same care, by writeConfigFile. No VS Code
 * API, so this can be tested on its own.
 */
import * as fs from "fs";
import * as path from "path";
import { Edit, ServerEntry, hasServerEntry, mergeServerEntry } from "./mcpJson";

/** The file operations of writing one project's configuration file and `.mcp.json` entry. */
export interface EntryWriteIo {
  /** Writes the project's configuration file. */
  writeConfig(): Promise<void>;
  /** Removes it again. */
  removeConfig(): Promise<void>;
  /** Writes `.mcp.json`. */
  writeMcpJson(text: string): Promise<void>;
}

export type EntryWriteResult =
  | { ok: true; changed: boolean }
  | {
      ok: false;
      error: string;
      /** `.mcp.json` cannot take the entry (it is not a JSON object); nothing was written. */
      refused: boolean;
      /** The project's `.mcp.json` has an entry (an earlier one), which its configuration file serves. */
      entryExists: boolean;
    };

/**
 * Writes a project's configuration file and then its `.mcp.json` entry, in that order, so the
 * entry never points at a file that is not there. A `.mcp.json` that cannot take the entry (not a
 * JSON object) gets no configuration file at all. If the entry cannot be written, a configuration
 * file is kept only when an earlier entry in the file still starts the server with it.
 */
export async function writeEntry(
  existing: string | undefined,
  entry: ServerEntry,
  io: EntryWriteIo
): Promise<EntryWriteResult> {
  const edit = mergeServerEntry(existing, entry);
  if (!edit.ok) return { ok: false, error: edit.error, refused: true, entryExists: false };
  await io.writeConfig();
  if (edit.changed) {
    try {
      await io.writeMcpJson(edit.text);
    } catch (e) {
      const entryExists = hasServerEntry(existing);
      if (!entryExists) await io.removeConfig();
      return { ok: false, error: String(e), refused: false, entryExists };
    }
  }
  return { ok: true, changed: edit.changed };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * The configuration file's text without the TBA API key of any server in it. Not ok when the
 * text is not a configuration file's (it cannot be cleaned).
 */
export function removeTbaKeyFromConfig(text: string): Edit {
  let doc: unknown;
  try {
    doc = JSON.parse(text);
  } catch (e) {
    return { ok: false, error: `not valid JSON (${(e as Error).message})` };
  }
  if (!isObject(doc)) return { ok: false, error: "not a JSON object" };
  let changed = false;
  if ("tba_key" in doc) {
    delete doc.tba_key;
    changed = true;
  }
  if (isObject(doc.servers)) {
    for (const server of Object.values(doc.servers)) {
      if (isObject(server) && "tba_key" in server) {
        delete server.tba_key;
        changed = true;
      }
    }
  }
  return changed
    ? { ok: true, text: JSON.stringify(doc, null, 2) + "\n", changed }
    : { ok: true, text, changed };
}

/**
 * Removes the TBA API key from every configuration file (`*.json`) in `dir` except those in
 * `keep` (the remembered projects', which their own refresh rewrites). A file that cannot be
 * read as a configuration file is removed: it may hold the key and cannot start a server.
 *
 * @returns the files rewritten without the key, and the files removed
 */
export async function removeTbaKeyFromConfigs(
  dir: string,
  keep: Set<string>
): Promise<{ scrubbed: string[]; removed: string[] }> {
  const scrubbed: string[] = [];
  const removed: string[] = [];
  const names = await fs.promises.readdir(dir).catch(() => [] as string[]);
  for (const name of names.sort()) {
    const file = path.join(dir, name);
    if (!name.endsWith(".json") || keep.has(file)) continue;
    const text = await fs.promises.readFile(file, "utf8").catch(() => undefined);
    if (text === undefined) continue;
    const edit = removeTbaKeyFromConfig(text);
    if (!edit.ok) {
      await fs.promises.rm(file, { force: true });
      removed.push(file);
    } else if (edit.changed) {
      await writeConfigFile(file, edit.text);
      scrubbed.push(file);
    }
  }
  return { scrubbed, removed };
}

/** Writes a configuration file whole, readable by this user only; false when it was unchanged. */
export async function writeConfigFile(file: string, text: string): Promise<boolean> {
  const current = await fs.promises.readFile(file, "utf8").catch(() => undefined);
  let written = false;
  if (current !== text) {
    await fs.promises.mkdir(path.dirname(file), { recursive: true });
    const temp = `${file}.${process.pid}.${Date.now()}.tmp`;
    try {
      await fs.promises.writeFile(temp, text, { mode: 0o600 });
      await fs.promises.rename(temp, file);
    } finally {
      await fs.promises.rm(temp, { force: true });
    }
    written = true;
  }
  await fs.promises.chmod(file, 0o600);
  return written;
}
