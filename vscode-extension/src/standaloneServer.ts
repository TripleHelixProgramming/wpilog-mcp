/** The shared standalone layout and launcher, independent of VS Code. */
import * as path from "path";

/** The server of the install's configuration file the extension uses: the one over HTTP. */
export const STANDALONE_SERVER = "http";

/** The install directory under the home folder, as the installers and the server name it. */
export const STANDALONE_DIR = ".wpilog-mcp";

/** Release docs must match this extension; only an unversioned local build follows main. */
export function standaloneGuideUrl(version: unknown): string {
  const tagged = typeof version === "string" && /^\d+\.\d+\.\d+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?$/.test(version);
  return `https://github.com/TripleHelixProgramming/wpilog-mcp/blob/${tagged ? `v${version}` : "main"}/doc/STANDALONE.md`;
}

/** Where the standalone install is, and whether it is there. */
export interface StandaloneInstall {
  /** `~/.wpilog-mcp`. */
  installDir: string;
  /** The current version's launcher: `bin/wpilog-mcp`, or `bin/wpilog-mcp.bat` on Windows. */
  launcher: string;
  /** The configuration file the launcher reads: `servers.yaml`, else `servers.json` (older installs). */
  configPath: string;
  /** The PID file `start` writes for the server. */
  pidFile: string;
  /** What is not there, in a sentence, when the install cannot be used; undefined when it can. */
  missing?: string;
}

/**
 * The install as the installers lay it out. The launcher and a configuration file must both be
 * there: the launcher runs the server, and the file names the server to run.
 *
 * @param exists whether a path is a file; tests pass a stub
 * @param pathApi the platform's path rules; tests pass `path.win32` or `path.posix`
 */
export function findStandaloneInstall(
  homeDir: string,
  platform: NodeJS.Platform,
  exists: (file: string) => boolean,
  pathApi: path.PlatformPath = path
): StandaloneInstall {
  const installDir = pathApi.join(homeDir, STANDALONE_DIR);
  const launcher = pathApi.join(installDir, "bin", platform === "win32" ? "wpilog-mcp.bat" : "wpilog-mcp");
  const yaml = pathApi.join(installDir, "servers.yaml");
  const json = pathApi.join(installDir, "servers.json");
  const configPath = exists(yaml) ? yaml : exists(json) ? json : yaml;
  const pidFile = pathApi.join(installDir, "run", `${STANDALONE_SERVER}.pid`);
  let missing: string | undefined;
  if (!exists(launcher)) {
    missing = `no launcher at ${launcher}`;
  } else if (!exists(configPath)) {
    missing = `no configuration file at ${yaml}`;
  }
  return { installDir, launcher, configPath, pidFile, missing };
}

/**
 * The port in a PID file: its second line, a port number. Undefined for a file that is not
 * one (missing, empty, one line, or not a number), which means no server is recorded.
 */
export function portInPidFile(text: string | undefined): number | undefined {
  if (text === undefined) return undefined;
  const lines = text.split(/\r?\n/);
  if (lines.length < 2) return undefined;
  const port = Number(lines[1].trim());
  return Number.isInteger(port) && port > 0 && port <= 65535 ? port : undefined;
}

/** A command to run: the program, its arguments, and how to hand them over. */
export interface LauncherCommand {
  command: string;
  args: string[];
  /** On Windows, the one argument is a command line written for cmd.exe, passed as it is. */
  windowsVerbatimArguments: boolean;
}

/**
 * The launcher with arguments, as a process. On Windows the launcher is a batch file, which
 * only cmd.exe runs: `cmd.exe /d /s /c "<launcher> <args>"`, each word in double quotes, so a
 * home folder with a space in its name works; `/s` makes cmd strip the outer quotes and keep
 * the inner ones. Elsewhere the launcher is a script the system runs by itself.
 */
export function launcherCommand(
  launcher: string,
  args: string[],
  platform: NodeJS.Platform
): LauncherCommand {
  if (platform !== "win32") return { command: launcher, args, windowsVerbatimArguments: false };
  const quoted = [launcher, ...args].map((word) => `"${word}"`).join(" ");
  return { command: "cmd.exe", args: ["/d", "/s", "/c", `"${quoted}"`], windowsVerbatimArguments: true };
}

/** The arguments that start the standalone server: `start http --config <file>`. */
export function standaloneStartArgs(configPath: string): string[] {
  return ["start", STANDALONE_SERVER, "--config", configPath];
}

/** The arguments that stop it: `stop http --config <file>`. */
export function standaloneStopArgs(configPath: string): string[] {
  return ["stop", STANDALONE_SERVER, "--config", configPath];
}

/** The variable the launcher reads for the JVM's maximum heap (`-Xmx`). */
export const MAX_HEAP_VARIABLE = "WPILOG_MAX_HEAP";

/**
 * The environment the launcher starts the server in: the extension's, with the `maxHeap`
 * setting as the launcher's heap variable. The launcher turns it into `-Xmx` for the daemon
 * it starts, so the setting sizes the server as it did when the extension ran the JAR itself,
 * and the size never appears on a command line. A blank setting leaves the environment alone,
 * so the launcher's own default (or a variable the user exported) applies.
 */
export function launcherEnvironment(base: NodeJS.ProcessEnv, maxHeap: string | undefined): NodeJS.ProcessEnv {
  const heap = maxHeap?.trim();
  return heap ? { ...base, [MAX_HEAP_VARIABLE]: heap } : { ...base };
}

/**
 * Whether a server's version is older than the extension's, by the numbers of the version
 * (`major.minor.patch`); a suffix such as `-dev2` is not compared. This decides whether to
 * request an automatic install. The JAR makes the final no-downgrade decision with suffixes
 * included; the on-demand install command can therefore advance a development suffix too.
 */
export function olderVersion(server: string | undefined, extension: string): boolean {
  if (server === undefined) return true;
  const numbers = (version: string) =>
    version.split("-")[0].split(".").map((part) => Number.parseInt(part, 10) || 0);
  const a = numbers(server);
  const b = numbers(extension);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const x = a[i] ?? 0;
    const y = b[i] ?? 0;
    if (x !== y) return x < y;
  }
  return false;
}
