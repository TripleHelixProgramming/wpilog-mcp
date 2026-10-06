/** Retire only entries whose bridge and storage path identify the extension as their owner. */
import * as path from "path";
import { GitStatus } from "./projectFile";

export interface LegacyEdit {
  changed: boolean;
  text: string | undefined;
  note?: string;
}

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** A custom entry or shared file belongs to the user, even when its name is ours. */
export function retireLegacyEntry(text: string | undefined, git: GitStatus, storage: string, project: string,
  pathApi: path.PlatformPath = path, canonicalize: (file: string) => string = file => file): LegacyEdit {
  const keep = (note?: string): LegacyEdit => ({ changed: false, text, ...(note ? { note } : {}) });
  if (text === undefined) return keep();
  let doc: unknown;
  try {
    doc = JSON.parse(text);
  } catch {
    return keep("Left .mcp.json alone: it is not valid JSON.");
  }
  if (!object(doc) || !object(doc.mcpServers)) return keep("Left .mcp.json alone: its server entries are not an object.");
  const entry = doc.mcpServers["wpilog-analyzer"];
  if (entry === undefined) return keep();
  if (git !== "untracked" && git !== "ignored") {
    return keep(git === "tracked"
      ? "Left tracked .mcp.json alone. Remove its old wpilog-analyzer entry yourself to use the user-scope registration."
      : "Left .mcp.json alone: git did not identify it as untracked or ignored.");
  }
  const args = object(entry) && Array.isArray(entry.args) && entry.args.every(arg => typeof arg === "string")
    ? entry.args as string[] : [];
  const connect = args.indexOf("connect");
  let owned = connect >= 0 && args[connect + 1] === "vscode-default";
  if (connect >= 0 && args[connect + 1] === "http" && args[connect + 2] === "--config" && args[connect + 3]) {
    try {
      const root = canonicalize(pathApi.resolve(storage));
      const config = canonicalize(pathApi.resolve(project, args[connect + 3]));
      const relative = pathApi.relative(root, config);
      owned = relative !== "" && relative !== ".." && !relative.startsWith(`..${pathApi.sep}`) && !pathApi.isAbsolute(relative);
    } catch {
      return keep("Left wpilog-analyzer alone: its configuration path could not be resolved.");
    }
  }
  if (!owned) return keep("Left custom wpilog-analyzer entry alone; review it if it duplicates the user-scope registration.");
  delete doc.mcpServers["wpilog-analyzer"];
  return { changed: true, text: JSON.stringify(doc, null, 2) + "\n",
    note: "Removed the extension's old wpilog-analyzer entry; Register with Claude Code sets up its user-scope replacement." };
}

/** Do not delete the old private settings until its daemon is stopped; retry a failed stop next activation. */
export function legacyCleanupAction(completed: boolean, recordedDaemon: boolean,
  stopped?: boolean): "none" | "stop" | "remove" | "retry" {
  if (completed) return "none";
  if (!recordedDaemon) return "remove";
  if (stopped === undefined) return "stop";
  return stopped ? "remove" : "retry";
}
