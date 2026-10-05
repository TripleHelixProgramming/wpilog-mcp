/**
 * Which of the window's servers to open a log with. A server reads only the files inside its
 * log directories (the security validator refuses the rest), so a log is opened with a server
 * whose directories hold it, and only failing that with the others, in the window's order. Pure,
 * so the ranking is tested with both platforms' path rules.
 */
import * as path from "path";

/** A server as a candidate: whatever stands for it, and the directories it was started with. */
export interface ServerCandidate<T> {
  server: T;
  logDirs: string[];
}

/** A path as the file system compares it: normalized, no trailing separator, and case-folded on Windows. */
function identity(p: string, pathApi: path.PlatformPath): string {
  let normalized = pathApi.normalize(p);
  const root = pathApi.parse(normalized).root;
  while (normalized.length > root.length && normalized.endsWith(pathApi.sep)) {
    normalized = normalized.slice(0, -1);
  }
  return pathApi.sep === "\\" ? normalized.toLowerCase() : normalized;
}

/** Whether a file is inside a directory, or in a folder below it. */
export function fileInside(filePath: string, dir: string, pathApi: path.PlatformPath = path): boolean {
  const file = identity(filePath, pathApi);
  const folder = identity(dir, pathApi);
  const prefix = folder.endsWith(pathApi.sep) ? folder : folder + pathApi.sep;
  return file.startsWith(prefix);
}

/**
 * The candidates in the order to try: those whose directories hold the file first, in the
 * order given, then the rest, in the order given. Every candidate is returned, since a server
 * started before its configuration changed may hold the file although the extension's record
 * of its directories does not say so.
 */
export function rankServersForFile<T>(
  filePath: string,
  candidates: ServerCandidate<T>[],
  pathApi: path.PlatformPath = path
): T[] {
  const holding = candidates.filter((c) => c.logDirs.some((dir) => fileInside(filePath, dir, pathApi)));
  const others = candidates.filter((c) => !holding.includes(c));
  return [...holding, ...others].map((c) => c.server);
}
