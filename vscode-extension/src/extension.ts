import * as vscode from "vscode";
import { findJava } from "./javaFinder";
import { findLogDirectories } from "./logFinder";
import { addLogDirectories } from "./logDirectories";
import { findJar } from "./jarManager";
import { buildServerEntry, mergeServerEntry, scrubTbaKey } from "./mcpJson";

const PROVIDER_ID = "wpilog-analyzer.mcpServer";

/** Where the TBA API key is kept: VS Code's secret storage (the OS keychain), never a file. */
const TBA_SECRET = "wpilog-mcp.tbaApiKey";

const TBA_ACCOUNT_URL = "https://www.thebluealliance.com/account";

export function activate(context: vscode.ExtensionContext) {
  const outputChannel = vscode.window.createOutputChannel("WPILog Analyzer");
  const didChangeEmitter = new vscode.EventEmitter<void>();

  /**
   * Resolves the MCP server command and args from VS Code settings. The TBA API key is passed
   * only in the environment (TBA_API_KEY): a command-line argument is visible to other users of
   * the machine in the process list.
   */
  async function resolveServerConfig(): Promise<
    { command: string; args: string[]; env: Record<string, string> } | undefined
  > {
    const config = vscode.workspace.getConfiguration("wpilog-mcp");
    const maxHeap = config.get<string>("maxHeap") || "4g";

    const javaPath = await findJava();
    if (!javaPath) {
      outputChannel.appendLine("ERROR: Java 17+ not found.");
      vscode.window
        .showErrorMessage(
          "WPILog Analyzer: Java 17+ is required. Install the WPILib toolkit or set wpilog-mcp.javaPath.",
          "Open Settings"
        )
        .then((choice) => {
          if (choice === "Open Settings") {
            vscode.commands.executeCommand(
              "workbench.action.openSettings",
              "wpilog-mcp.javaPath"
            );
          }
        });
      return undefined;
    }

    const jarPath = findJar(context.extensionPath);
    if (!jarPath) {
      outputChannel.appendLine("ERROR: wpilog-mcp JAR not found.");
      vscode.window.showErrorMessage(
        "WPILog Analyzer: Server JAR not found. Try reinstalling the extension."
      );
      return undefined;
    }

    outputChannel.appendLine(`Java: ${javaPath}`);
    outputChannel.appendLine(`JAR: ${jarPath}`);

    const logDirs = await findLogDirectories();
    outputChannel.appendLine(
      `Log directories: ${logDirs.length > 0 ? logDirs.join(", ") : "(none)"}`
    );

    const teamNumber = config.get<number>("teamNumber") || 0;
    const tbaKey = (await context.secrets.get(TBA_SECRET)) || "";

    // The server reads WPILOG_DIR, WPILOG_TEAM, and TBA_API_KEY from its environment and also
    // accepts -logdir and -team; the key goes only in the environment.
    const args = [`-Xmx${maxHeap}`, "-jar", jarPath];
    const env: Record<string, string> = {};

    addLogDirectories(args, env, logDirs);
    if (teamNumber > 0) {
      args.push("-team", String(teamNumber));
      env["WPILOG_TEAM"] = String(teamNumber);
    }
    if (tbaKey) {
      env["TBA_API_KEY"] = tbaKey;
    }

    return { command: javaPath, args, env };
  }

  // ---- VS Code MCP provider ----

  const provider: vscode.McpServerDefinitionProvider = {
    onDidChangeMcpServerDefinitions: didChangeEmitter.event,

    provideMcpServerDefinitions: async () => {
      const resolved = await resolveServerConfig();
      if (!resolved) {
        return [];
      }

      outputChannel.appendLine(
        `Starting: ${resolved.command} ${resolved.args.join(" ")}`
      );
      const envKeys = Object.keys(resolved.env);
      if (envKeys.length > 0) {
        outputChannel.appendLine(
          `Env: ${envKeys.map(k => k === "TBA_API_KEY" ? "TBA_API_KEY=(set)" : `${k}=${resolved.env[k]}`).join(", ")}`
        );
      }

      return [
        new vscode.McpStdioServerDefinition(
          "WPILog Analyzer",
          resolved.command,
          resolved.args,
          resolved.env,
          context.extension.packageJSON.version,
        ),
      ];
    },

    resolveMcpServerDefinition: async (server) => {
      return server;
    },
  };

  context.subscriptions.push(
    vscode.lm.registerMcpServerDefinitionProvider(PROVIDER_ID, provider)
  );

  // ---- The TBA API key ----

  context.subscriptions.push(
    vscode.commands.registerCommand("wpilog-mcp.setTbaApiKey", async () => {
      const key = await vscode.window.showInputBox({
        title: "The Blue Alliance API Key",
        prompt: `Paste your read API key from ${TBA_ACCOUNT_URL}. It is kept in VS Code's secret storage.`,
        password: true,
        ignoreFocusOut: true,
      });
      if (key === undefined) {
        return; // cancelled
      }
      if (key.trim() === "") {
        vscode.window.showWarningMessage(
          "WPILog Analyzer: No key entered; the TBA API key was not changed."
        );
        return;
      }
      await context.secrets.store(TBA_SECRET, key.trim());
      vscode.window.showInformationMessage(
        "WPILog Analyzer: TBA API key saved. The server restarts to use it."
      );
    }),
    vscode.commands.registerCommand("wpilog-mcp.clearTbaApiKey", async () => {
      await context.secrets.delete(TBA_SECRET);
      vscode.window.showInformationMessage(
        "WPILog Analyzer: TBA API key removed. The server restarts without it."
      );
    }),
    // A stored or removed key restarts the server
    context.secrets.onDidChange((e) => {
      if (e.key === TBA_SECRET) {
        didChangeEmitter.fire();
      }
    })
  );

  // Re-register when settings change, and update .mcp.json
  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration("wpilog-mcp.tbaApiKey")) {
        void moveTbaKeyToSecretStorage(context, outputChannel);
      }
      if (e.affectsConfiguration("wpilog-mcp")) {
        outputChannel.appendLine("Settings changed, restarting MCP server...");
        didChangeEmitter.fire();
        void writeMcpJson(outputChannel);
      }
    })
  );

  void (async () => {
    await moveTbaKeyToSecretStorage(context, outputChannel);
    await removeTbaKeyFromMcpJson(outputChannel);
    // Write .mcp.json for Claude Code when the user has turned that on
    await writeMcpJson(outputChannel);
  })();

  context.subscriptions.push(outputChannel);
  context.subscriptions.push(didChangeEmitter);

  outputChannel.appendLine("WPILog Analyzer extension activated.");
}

/**
 * Moves a TBA API key from the (deprecated) `wpilog-mcp.tbaApiKey` setting into secret storage
 * and clears it from settings, where it was plaintext in settings.json.
 */
async function moveTbaKeyToSecretStorage(
  context: vscode.ExtensionContext,
  outputChannel: vscode.OutputChannel
) {
  const config = vscode.workspace.getConfiguration("wpilog-mcp");
  const inspected = config.inspect<string>("tbaApiKey");
  const workspaceKey = inspected?.workspaceValue?.trim() || "";
  const globalKey = inspected?.globalValue?.trim() || "";
  const key = workspaceKey || globalKey;
  if (!key) {
    return;
  }

  if (!(await context.secrets.get(TBA_SECRET))) {
    await context.secrets.store(TBA_SECRET, key);
  }
  if (inspected?.globalValue !== undefined) {
    await config.update("tbaApiKey", undefined, vscode.ConfigurationTarget.Global);
  }
  if (inspected?.workspaceValue !== undefined) {
    await config.update("tbaApiKey", undefined, vscode.ConfigurationTarget.Workspace);
  }
  outputChannel.appendLine("Moved the TBA API key from settings into secret storage.");

  const where = workspaceKey
    ? "the workspace's .vscode/settings.json"
    : "your user settings";
  const message =
    `WPILog Analyzer: Your TBA API key was in ${where} in plaintext; it is now kept in ` +
    "VS Code's secret storage and removed from settings." +
    (workspaceKey
      ? " If that settings file was committed or shared, revoke the key and set a new one " +
        "(WPILog Analyzer: Set The Blue Alliance API Key)."
      : "");
  const choice = await vscode.window.showInformationMessage(
    message,
    ...(workspaceKey ? ["Open TBA Account"] : [])
  );
  if (choice === "Open TBA Account") {
    vscode.env.openExternal(vscode.Uri.parse(TBA_ACCOUNT_URL));
  }
}

/** The workspace root's .mcp.json, or undefined without a workspace. */
function mcpJsonUri(): vscode.Uri | undefined {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) {
    return undefined;
  }
  return vscode.Uri.joinPath(folders[0].uri, ".mcp.json");
}

/** The file's text, or undefined when it does not exist. */
async function readText(uri: vscode.Uri): Promise<string | undefined> {
  try {
    return Buffer.from(await vscode.workspace.fs.readFile(uri)).toString("utf8");
  } catch {
    return undefined;
  }
}

/**
 * Removes a TBA API key that earlier versions wrote into this server's .mcp.json entry in
 * plaintext, whatever the writeMcpJson setting: a file in the workspace root is easily committed.
 */
async function removeTbaKeyFromMcpJson(outputChannel: vscode.OutputChannel) {
  const uri = mcpJsonUri();
  if (!uri) {
    return;
  }
  const edit = scrubTbaKey(await readText(uri));
  if (!edit.ok || !edit.changed) {
    return;
  }
  try {
    await vscode.workspace.fs.writeFile(uri, Buffer.from(edit.text));
  } catch (e) {
    outputChannel.appendLine(`Failed to remove the TBA API key from ${uri.fsPath}: ${e}`);
    return;
  }
  outputChannel.appendLine(`Removed the TBA API key from ${uri.fsPath}.`);
  const choice = await vscode.window.showWarningMessage(
    "WPILog Analyzer: An earlier version wrote your TBA API key into .mcp.json in plaintext; " +
      "it has been removed. If that file was ever committed or shared, revoke the key and set " +
      "a new one (WPILog Analyzer: Set The Blue Alliance API Key).",
    "Open TBA Account"
  );
  if (choice === "Open TBA Account") {
    vscode.env.openExternal(vscode.Uri.parse(TBA_ACCOUNT_URL));
  }
}

/**
 * Adds or updates the `wpilog-analyzer` entry in the workspace's .mcp.json, which Claude Code
 * reads to find MCP servers (it does not use the McpServerDefinitionProvider API). Only when the
 * `wpilog-mcp.writeMcpJson` setting is on; every other entry in the file is kept, and the TBA key
 * is referenced from Claude Code's environment, never written.
 */
async function writeMcpJson(outputChannel: vscode.OutputChannel) {
  const config = vscode.workspace.getConfiguration("wpilog-mcp");
  if (!config.get<boolean>("writeMcpJson")) {
    return;
  }
  const uri = mcpJsonUri();
  if (!uri) {
    return;
  }

  const javaPath = await findJava();
  if (!javaPath) return;

  const jarPath = findJar(
    vscode.extensions.getExtension("TripleHelixProgramming.wpilog-analyzer")
      ?.extensionPath ?? ""
  );
  if (!jarPath) return;

  const entry = buildServerEntry(
    javaPath,
    jarPath,
    config.get<string>("maxHeap") || "4g",
    await findLogDirectories(),
    config.get<number>("teamNumber") || 0
  );
  const edit = mergeServerEntry(await readText(uri), entry);
  if (!edit.ok) {
    outputChannel.appendLine(`Did not update ${uri.fsPath}: ${edit.error}`);
    vscode.window.showWarningMessage(
      `WPILog Analyzer: ${edit.error}, so it was not updated. Fix the file, or turn off wpilog-mcp.writeMcpJson.`
    );
    return;
  }
  if (!edit.changed) {
    return;
  }
  try {
    await vscode.workspace.fs.writeFile(uri, Buffer.from(edit.text));
    outputChannel.appendLine(`Updated the wpilog-analyzer entry in ${uri.fsPath}`);
  } catch (e) {
    outputChannel.appendLine(`Failed to write ${uri.fsPath}: ${e}`);
  }
}

export function deactivate() {}
