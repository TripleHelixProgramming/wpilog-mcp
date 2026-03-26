# WPILog Analyzer

AI-powered FRC robot log analysis for VS Code. Analyzes `.wpilog` telemetry files from the roboRIO to help diagnose brownouts, CAN errors, swerve drive issues, loop timing problems, and more.

This extension registers an [MCP server](https://modelcontextprotocol.io/) that gives AI agents access to semantically described robot log analysis tools, including the ability to extract the raw data for further processing. The server is designed for and tested with Claude. It should work with any AI agent that uses the VS Code MCP server registry (Claude Code, Copilot, etc.).

## Quick Start

1. **Install this extension** (see below)
2. **Put your `.wpilog` files** in `~/riologs` (or configure a custom path in settings)
3. **Ask your AI assistant** about your logs:
   - *"What logs are available?"*
   - *"Can you walk me through the power delivery in our last match?"*
   - *"Help me understand if we had any CAN bus issues while enabled"*
   - *"How did our swerve modules perform?"*

> The depth of analysis depends on the AI model you use. The server provides the tools and data — the model provides the comprehension and reasoning.

## Install

Download `wpilog-analyzer-{version}.vsix` from the [latest release](https://github.com/TripleHelixProgramming/wpilog-mcp/releases/latest), then:

1. Open the Extensions sidebar (`Ctrl+Shift+X`) → click `...` (top-right) → **Install from VSIX...** → select the downloaded file
2. Restart VS Code

Or from the command line:
```bash
code --install-extension wpilog-analyzer-{version}.vsix
```

> **WPILib VS Code:** If you use the WPILib VS Code distribution, use its `code` binary for the command-line install, or install via its Extensions UI. The system VS Code and WPILib VS Code maintain separate extension directories.

## How It Works

On activation, the extension finds the WPILib JDK and server JAR, then registers a stdio-based MCP server via the VS Code `McpServerDefinitionProvider` API. Settings changes trigger automatic re-registration.

**Tip:** Open your robot project in VS Code while analyzing logs. The AI agent can cross-reference telemetry data with your source code — mapping logged entry names back to the subsystems that produce them, correlating PID tuning constants with observed behavior, and providing analysis tailored to your team's specific robot architecture.

## Requirements

- **Java 17+** — The WPILib toolkit includes a compatible JDK (auto-detected)
- **VS Code 1.100+** with an MCP-compatible AI agent

## Settings

| Setting | Description | Default |
|---------|-------------|---------|
| `wpilog-mcp.javaPath` | Path to `java` executable | auto-detect |
| `wpilog-mcp.wpiLibYear` | WPILib installation year (e.g., `2026`) | auto-detect latest |
| `wpilog-mcp.logDirectory` | Path to `.wpilog` files | auto-detect |
| `wpilog-mcp.teamNumber` | FRC team number for TBA lookups | `2363` |
| `wpilog-mcp.tbaApiKey` | The Blue Alliance API key | — |
| `wpilog-mcp.maxHeap` | JVM heap size | `4g` |

## Auto-Detection

The extension automatically finds:

- **Java:** If running inside WPILib VS Code, uses that distribution's bundled JDK (matching the season). Otherwise scans `~/wpilib/{year}/jdk/` (latest year preferred), then `JAVA_HOME`, then `java` on PATH.
- **JAR:** Looks in the extension's bundled `server/` directory, then `~/.wpilog-mcp/jars/`, then workspace `build/libs/`.
- **Log directory:** Checks `~/riologs`, `~/wpilib/logs`, `~/Documents/FRC/logs` in order, then prompts to browse or create `~/riologs`.

## Upgrading

To upgrade, install the new `.vsix` over the existing one — VS Code replaces the previous version automatically. No need to uninstall first.

## Uninstalling

Open the Extensions sidebar (`Ctrl+Shift+X`), find **WPILog Analyzer**, click the gear icon, and select **Uninstall**. Or from the command line:
```bash
code --uninstall-extension TripleHelixProgramming.wpilog-analyzer
```

The extension does not write files outside its own install directory, so no cleanup is needed.

## Troubleshooting

- **Server not starting** — Open the Output panel (`Ctrl+Shift+U`) and select **WPILog Analyzer** from the dropdown. This shows the Java path, JAR path, and any error messages.
- **Java not found** — If you're using WPILib VS Code, the extension should find the bundled JDK automatically. Otherwise, set `wpilog-mcp.javaPath` in VS Code settings to point to a JDK 17+ `java` executable.
- **Tools not appearing** — Restart VS Code completely (quit and relaunch, not just reload the window).
- **Out of memory with large logs** — Set `wpilog-mcp.maxHeap` to `8g` in VS Code settings.
- **Log files show as corrupted** — Truncated logs (from robot power loss) are handled gracefully. The server recovers as much data as possible and marks the log as truncated.

## Standalone Install (without VS Code)

If you use Claude Desktop, Claude Code CLI, or another MCP client, see [doc/STANDALONE.md](../doc/STANDALONE.md) for standalone installation and configuration.

## More Information

- [Main README](../README.md) — Full project overview, available tools, supported data types
- [TOOLS.md](../doc/TOOLS.md) — Complete tool reference
- [wpilog-mcp on GitHub](https://github.com/TripleHelixProgramming/wpilog-mcp)
