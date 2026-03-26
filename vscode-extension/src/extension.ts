import * as vscode from "vscode";
import { findJava } from "./javaFinder";
import { findLogDirectory } from "./logFinder";
import { findJar } from "./jarManager";

const PROVIDER_ID = "wpilog-analyzer.mcpServer";

export function activate(context: vscode.ExtensionContext) {
  const outputChannel = vscode.window.createOutputChannel("WPILog Analyzer");
  const didChangeEmitter = new vscode.EventEmitter<void>();

  /**
   * Resolves the MCP server command and args from VS Code settings.
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

    const logDir = await findLogDirectory();
    outputChannel.appendLine(`Log directory: ${logDir ?? "(none)"}`);

    const teamNumber = config.get<number>("teamNumber") || 0;
    const tbaKey = config.get<string>("tbaApiKey") || "";

    // Pass settings as both CLI args and env vars. The server reads env vars
    // in handleLegacyCli (WPILOG_DIR, WPILOG_TEAM, TBA_API_KEY) and also
    // accepts CLI flags (-logdir, -team, -tba-key). Using both ensures the
    // settings are picked up regardless of how the MCP host launches the process.
    const args = [`-Xmx${maxHeap}`, "-jar", jarPath];
    const env: Record<string, string> = {};

    if (logDir) {
      args.push("-logdir", logDir);
      env["WPILOG_DIR"] = logDir;
    }
    if (teamNumber > 0) {
      args.push("-team", String(teamNumber));
      env["WPILOG_TEAM"] = String(teamNumber);
    }
    if (tbaKey) {
      args.push("-tba-key", tbaKey);
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

  // Re-register when settings change, and update .mcp.json
  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration("wpilog-mcp")) {
        outputChannel.appendLine("Settings changed, restarting MCP server...");
        didChangeEmitter.fire();
        writeMcpJson(outputChannel);
      }
    })
  );

  // Write .mcp.json for Claude Code compatibility (it discovers servers from this file)
  writeMcpJson(outputChannel);

  context.subscriptions.push(outputChannel);
  context.subscriptions.push(didChangeEmitter);

  outputChannel.appendLine("WPILog Analyzer extension activated.");
}

/**
 * Writes a .mcp.json file in the workspace root so Claude Code can discover
 * the MCP server. Claude Code reads .mcp.json rather than using the
 * McpServerDefinitionProvider API.
 */
async function writeMcpJson(outputChannel: vscode.OutputChannel) {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) {
    return;
  }

  const config = vscode.workspace.getConfiguration("wpilog-mcp");
  const maxHeap = config.get<string>("maxHeap") || "4g";
  const teamNumber = config.get<number>("teamNumber") || 0;
  const tbaKey = config.get<string>("tbaApiKey") || "";

  const javaPath = await findJava();
  if (!javaPath) return;

  const jarPath = findJar(
    vscode.extensions.getExtension("TripleHelixProgramming.wpilog-analyzer")
      ?.extensionPath ?? ""
  );
  if (!jarPath) return;

  const logDir = await findLogDirectory();

  const args: string[] = [`-Xmx${maxHeap}`, "-jar", jarPath];
  if (logDir) args.push("-logdir", logDir);
  if (teamNumber > 0) args.push("-team", String(teamNumber));
  if (tbaKey) args.push("-tba-key", tbaKey);

  const mcpConfig = {
    mcpServers: {
      "wpilog-analyzer": {
        command: javaPath,
        args,
        env: {
          ...(logDir ? { WPILOG_DIR: logDir } : {}),
          ...(teamNumber > 0 ? { WPILOG_TEAM: String(teamNumber) } : {}),
          ...(tbaKey ? { TBA_API_KEY: tbaKey } : {}),
        },
      },
    },
  };

  const mcpJsonPath = vscode.Uri.joinPath(folders[0].uri, ".mcp.json");
  try {
    await vscode.workspace.fs.writeFile(
      mcpJsonPath,
      Buffer.from(JSON.stringify(mcpConfig, null, 2) + "\n")
    );
    outputChannel.appendLine(`Wrote ${mcpJsonPath.fsPath}`);
  } catch (e) {
    outputChannel.appendLine(`Failed to write .mcp.json: ${e}`);
  }
}

export function deactivate() {}
