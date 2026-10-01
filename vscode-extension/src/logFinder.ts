import * as fs from "fs";
import * as path from "path";
import * as os from "os";
import * as vscode from "vscode";
import { combineLogDirectories, expandTilde } from "./logDirectories";

/**
 * The directories to pass to the server: the main one (see findLogDirectory), then those in
 * wpilog-mcp.additionalLogDirectories, each once. With `prompt` false, a missing main directory is
 * left out rather than asked for (for work done in the background).
 */
export async function findLogDirectories(prompt = true): Promise<string[]> {
  const config = vscode.workspace.getConfiguration("wpilog-mcp");
  return combineLogDirectories(
    await findLogDirectory(prompt),
    config.get<unknown>("additionalLogDirectories")
  );
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
  const home = os.homedir();
  const candidates = [
    path.join(home, "riologs"),
    path.join(home, "wpilib", "logs"),
    path.join(home, "Documents", "FRC", "logs"),
  ];

  for (const dir of candidates) {
    if (fs.existsSync(dir)) {
      return dir;
    }
  }

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
