/**
 * The log directories and settings passed to the server, and how they are passed. Pure functions
 * (no VS Code API) so they can be tested on their own.
 */
import * as os from "os";
import * as path from "path";

/** Expands a leading `~` to the home directory. */
export function expandTilde(p: string, pathApi: path.PlatformPath = path): string {
  if (p.startsWith("~/") || p === "~") {
    return pathApi.join(os.homedir(), p.slice(1));
  }
  return p;
}

/**
 * A directory as the file system names it, for comparing two paths: normalized (on Windows, `/`
 * becomes `\`, and `.` and `..` are resolved) with no trailing separator, and in lower case on
 * Windows, whose file names ignore case.
 */
export function directoryIdentity(dir: string, pathApi: path.PlatformPath): string {
  const normalized = pathApi.normalize(dir);
  const root = pathApi.parse(normalized).root;
  let trimmed = normalized;
  while (trimmed.length > root.length && trimmed.endsWith(pathApi.sep)) {
    trimmed = trimmed.slice(0, -1);
  }
  return pathApi.sep === "\\" ? trimmed.toLowerCase() : trimmed;
}

/**
 * The main log directory followed by the additional ones (the `additionalLogDirectories` setting,
 * which may hold anything a user typed into settings.json): non-blank strings only, tilde-expanded,
 * each once, in that order. A relative path names a folder inside the project (such as one where
 * the robot code writes simulation logs) and is resolved against each of `projectDirs`; without a
 * project, it names nothing and is left out. Two paths that name the same folder (`C:/robot/logs`
 * and `logs` in `C:\robot` on Windows, or one with a trailing separator) count once, and the first
 * is passed as written.
 *
 * @param pathApi the platform's path rules; tests pass `path.win32` or `path.posix`
 */
export function combineLogDirectories(
  main: string | undefined,
  additional: unknown,
  projectDirs: string[] = [],
  pathApi: path.PlatformPath = path
): string[] {
  const extra = Array.isArray(additional) ? additional : [];
  const dirs: string[] = [];
  const seen = new Set<string>();
  const add = (dir: string) => {
    const identity = directoryIdentity(dir, pathApi);
    if (!seen.has(identity)) {
      seen.add(identity);
      dirs.push(dir);
    }
  };
  for (const dir of [main, ...extra]) {
    if (typeof dir !== "string" || dir.trim() === "") continue;
    const expanded = expandTilde(dir.trim(), pathApi);
    if (pathApi.isAbsolute(expanded)) {
      add(expanded);
    } else {
      projectDirs.forEach((project) => add(pathApi.join(project, expanded)));
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
