import * as fs from "fs";
import * as path from "path";
import * as os from "os";
import * as vscode from "vscode";
import { LogSettings, combineLogDirectories, expandTilde } from "./logDirectories";

/** The local workspace folders' paths, against which relative log paths resolve. */
export function localWorkspaceFolders(): string[] {
  return (vscode.workspace.workspaceFolders ?? [])
    .filter((folder) => folder.uri.scheme === "file")
    .map((folder) => folder.uri.fsPath);
}

/**
 * The directories to pass to the server VS Code starts: the main one (see findLogDirectory), then
 * those in wpilog-mcp.additionalLogDirectories, each once, relative paths resolved inside each of
 * the window's folders. With `prompt` false, a missing main directory is left out rather than
 * asked for (for work done in the background).
 */
export async function findLogDirectories(prompt = true): Promise<string[]> {
  const config = vscode.workspace.getConfiguration("wpilog-mcp");
  return combineLogDirectories(
    await findLogDirectory(prompt),
    config.get<unknown>("additionalLogDirectories"),
    localWorkspaceFolders()
  );
}

/**
 * One project's directories, from settings already resolved for it (the user's, with the
 * project's own on top): relative paths inside that project, and a well-known folder when no main
 * directory is set. Never prompts.
 */
export function logDirectoriesFor(settings: LogSettings, projectDir: string): string[] {
  const main = settings.logDirectory?.trim() ? settings.logDirectory : wellKnownLogDirectory();
  return combineLogDirectories(main, settings.additionalLogDirectories, [projectDir]);
}

/** The first of ~/riologs, ~/wpilib/logs, ~/Documents/FRC/logs that exists. */
export function wellKnownLogDirectory(): string | undefined {
  const home = os.homedir();
  return [
    path.join(home, "riologs"),
    path.join(home, "wpilib", "logs"),
    path.join(home, "Documents", "FRC", "logs"),
  ].find((dir) => fs.existsSync(dir));
}

/**
 * Finds the directory containing .wpilog files.
 *
 * Search order:
 * 1. User setting (wpilog-mcp.logDirectory)
 * 2. ~/riologs
 * 3. ~/wpilib/logs
 * 4. ~/Documents/FRC/logs
 * 5. Prompt user with a folder picker (unless `prompt` is false)
 */
export async function findLogDirectory(prompt = true): Promise<string | undefined> {
  const config = vscode.workspace.getConfiguration("wpilog-mcp");

  // 1. User override — trust the path without checking existence
  //    (network shares and mounted drives may not respond to existsSync)
  const userDir = config.get<string>("logDirectory");
  if (userDir) {
    return expandTilde(userDir);
  }

  // 2-4. Well-known paths
  const wellKnown = wellKnownLogDirectory();
  if (wellKnown) {
    return wellKnown;
  }
  const home = os.homedir();

  // 5. Prompt user
  if (!prompt) {
    return undefined;
  }
  const choice = await vscode.window.showInformationMessage(
    "WPILog Analyzer: No log directory found. Where are your .wpilog files?",
    "Browse...",
    "Create ~/riologs"
  );

  if (choice === "Browse...") {
    const uris = await vscode.window.showOpenDialog({
      canSelectFolders: true,
      canSelectFiles: false,
      canSelectMany: false,
      openLabel: "Select Log Directory",
    });
    if (uris && uris.length > 0) {
      const selected = uris[0].fsPath;
      await config.update("logDirectory", selected, vscode.ConfigurationTarget.Global);
      return selected;
    }
  } else if (choice === "Create ~/riologs") {
    const riologs = path.join(home, "riologs");
    fs.mkdirSync(riologs, { recursive: true });
    return riologs;
  }

  return undefined;
}
