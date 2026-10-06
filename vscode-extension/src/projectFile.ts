/** Project YAML is offered, never silently added or used to define the shared server. */
import { LogSettings } from "./logDirectories";

export type GitStatus = "tracked" | "ignored" | "untracked" | "none";
export const PROJECT_FILE = ".wpilog-mcp.yaml";

export function projectFileOffer(own: LogSettings, existing: string | undefined, offered: boolean,
  git: GitStatus = "none"): boolean {
  return git !== "tracked" && !offered && existing === undefined && projectDirectories(own).length > 0;
}

function projectDirectories(own: LogSettings): string[] {
  return [own.logDirectory, ...(Array.isArray(own.additionalLogDirectories) ? own.additionalLogDirectories : [])]
    .filter((dir): dir is string => typeof dir === "string" && dir.trim() !== "");
}

/** JSON-quoted scalars are YAML scalars too, including Windows paths, quotes, and '#' in a name. */
export function projectFileText(own: LogSettings): string {
  const lines = ["# Directories leased by wpilog-mcp connect from this project.",
    `logdir: ${JSON.stringify(projectDirectories(own))}`];
  if (typeof own.teamNumber === "number" && Number.isSafeInteger(own.teamNumber) && own.teamNumber > 0) {
    lines.push(`team: ${own.teamNumber}`);
  }
  return lines.join("\n") + "\n";
}

export function ignoreProjectFile(text = ""): string {
  if (text.split(/\r?\n/).some(line => [PROJECT_FILE, `/${PROJECT_FILE}`].includes(line.trim()))) return text;
  const newline = text.includes("\r\n") ? "\r\n" : "\n";
  return text + (text && !text.endsWith("\n") ? newline : "") + PROJECT_FILE + newline;
}
