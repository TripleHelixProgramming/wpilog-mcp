/** User-scope registration contains only the bridge, so every project's cwd remains its own decision. */
import * as path from "path";
import { launcherCommand, LauncherCommand } from "./standaloneServer";

export function claudeArgs(launcher: string, platform: NodeJS.Platform): string[] {
  return ["mcp", "add", "--scope", "user", "wpilog-analyzer", "--",
    ...(platform === "win32" ? ["cmd", "/c", launcher] : [launcher]), "connect", "http"];
}

/** The CLI itself can be a .cmd shim too; quote each word once for the outer shell. */
export function claudeCommand(cli: string, launcher: string, platform: NodeJS.Platform): LauncherCommand {
  return launcherCommand(cli, claudeArgs(launcher, platform), platform);
}

export function claudeCommandText(launcher: string, platform: NodeJS.Platform): string {
  const quote = (word: string) => platform === "win32" ? `"${word.replace(/"/g, '""')}"`
    : `'${word.replace(/'/g, "'\\''")}'`;
  return ["claude", ...claudeArgs(launcher, platform).map(quote)].join(" ");
}

/** No shell lookup is needed to find an executable or a Windows command shim. */
export function findClaude(searchPath: string, platform: NodeJS.Platform, executable: (file: string) => boolean,
  pathApi: path.PlatformPath = path): string | undefined {
  const names = platform === "win32" ? ["claude.exe", "claude.cmd", "claude.bat"] : ["claude"];
  return searchPath.split(pathApi.delimiter).filter(Boolean)
    .flatMap(dir => names.map(name => pathApi.join(dir, name))).find(executable);
}
