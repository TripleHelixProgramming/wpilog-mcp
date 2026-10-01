/**
 * The log directories passed to the server, and how they are passed. Pure functions (no VS Code
 * API) so they can be tested on their own.
 */
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
 * each once, in that order.
 */
export function combineLogDirectories(main: string | undefined, additional: unknown): string[] {
  const extra = Array.isArray(additional) ? additional : [];
  const dirs: string[] = [];
  for (const dir of [main, ...extra]) {
    if (typeof dir !== "string" || dir.trim() === "") continue;
    const expanded = expandTilde(dir.trim());
    if (!dirs.includes(expanded)) dirs.push(expanded);
  }
  return dirs;
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
