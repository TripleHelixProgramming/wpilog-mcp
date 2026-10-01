/**
 * The log directories and settings passed to the server, and how they are passed. Pure functions
 * (no VS Code API) so they can be tested on their own.
 */
import * as crypto from "crypto";
import * as os from "os";
import * as path from "path";

/** Expands a leading `~` to the home directory. */
export function expandTilde(p: string): string {
  if (p.startsWith("~/") || p === "~") {
    return path.join(os.homedir(), p.slice(1));
  }
  return p;
}

/**
 * The main log directory followed by the additional ones (the `additionalLogDirectories` setting,
 * which may hold anything a user typed into settings.json): non-blank strings only, tilde-expanded,
 * each once, in that order. A relative path names a folder inside the project (such as one where
 * the robot code writes simulation logs) and is resolved against each of `projectDirs`; without a
 * project, it names nothing and is left out.
 */
export function combineLogDirectories(
  main: string | undefined,
  additional: unknown,
  projectDirs: string[] = []
): string[] {
  const extra = Array.isArray(additional) ? additional : [];
  const dirs: string[] = [];
  const add = (dir: string) => {
    if (!dirs.includes(dir)) dirs.push(dir);
  };
  for (const dir of [main, ...extra]) {
    if (typeof dir !== "string" || dir.trim() === "") continue;
    const expanded = expandTilde(dir.trim());
    if (path.isAbsolute(expanded)) {
      add(expanded);
    } else {
      projectDirs.forEach((project) => add(path.join(project, expanded)));
    }
  }
  return dirs;
}

/** The settings a project can override, as Claude Code's configuration file needs them. */
export interface LogSettings {
  logDirectory?: string;
  additionalLogDirectories?: unknown;
  teamNumber?: number;
}

/**
 * A project's settings as VS Code applies them: the project's own value where it has one (its
 * `.vscode/settings.json`, or folder settings), the user's elsewhere. A list replaces the user's
 * list rather than adding to it, as in VS Code.
 */
export function overlaySettings(user: LogSettings, project: LogSettings): LogSettings {
  return {
    logDirectory: project.logDirectory ?? user.logDirectory,
    additionalLogDirectories: project.additionalLogDirectories ?? user.additionalLogDirectories,
    teamNumber: project.teamNumber ?? user.teamNumber,
  };
}

/** The name of a project's configuration file: from a hash of its folder, so each has its own. */
export function projectConfigName(folderPath: string): string {
  return (
    crypto.createHash("sha256").update(path.resolve(folderPath)).digest("hex").slice(0, 16) +
    ".json"
  );
}

/**
 * Passes the log directories to the server: one `-logdir` per directory, and `WPILOG_DIR` with
 * them joined by the platform's path delimiter (`:`, or `;` on Windows), as the server reads it.
 */
export function addLogDirectories(
  args: string[],
  env: Record<string, string>,
  logDirs: string[],
  delimiter: string = path.delimiter
): void {
  for (const dir of logDirs) {
    args.push("-logdir", dir);
  }
  if (logDirs.length > 0) {
    env["WPILOG_DIR"] = logDirs.join(delimiter);
  }
}
