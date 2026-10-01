<table>
<tr>
<td width="180" valign="top" align="center">
  <img src="images/icon.png" alt="WPILog Analyzer icon" width="140"><br>
  <h1>WPILog Analyzer</h1>
</td>
<td valign="top">

AI-powered FRC robot log analysis for VS Code. Analyzes `.wpilog` telemetry files from the roboRIO to help diagnose brownouts, CAN errors, swerve drive issues, loop timing problems, and more.

This extension registers an [MCP server](https://modelcontextprotocol.io/) that gives AI agents access to semantically described robot log analysis tools, including the ability to extract the raw data for further processing. The server is designed for and tested with Claude. Copilot and other agents that use VS Code's MCP server registry find it there; for Claude Code, which reads `.mcp.json` instead, the extension adds it to your robot project's `.mcp.json` (see [Using It with Claude Code](#using-it-with-claude-code)).

</td>
</tr>
</table>

## Quick Start

1. **Install this extension** (see below)
2. **Put your `.wpilog` files** in `~/riologs` (or configure a custom path in settings)
3. **Open your robot project** in VS Code. With Claude Code, approve the `wpilog-analyzer` server the first time it asks in that project.
4. **Ask your AI assistant** about your logs:
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

## Using It with Claude Code

Claude Code doesn't use VS Code's MCP server registry; it finds servers in a `.mcp.json` file in the folder it runs in. So in a WPILib robot project (a folder with `.wpilib/wpilib_preferences.json`), the extension adds a `wpilog-analyzer` entry to that folder's `.mcp.json`. Other servers in the file are left alone, and a file that isn't valid JSON is not touched. All of this is controlled by one checkbox, **Enable For Claude Code** (`wpilog-mcp.enableForClaudeCode`, on by default); it affects only Claude Code, since Copilot and other VS Code agents get the server from VS Code itself.

It works like the [standalone install](../doc/STANDALONE.md): the entry only starts the server (Java, heap size, JAR) with a configuration file, and the configuration lives in that file, which the extension keeps in its storage, one per project (in the format of the standalone's `servers.yaml`): the project's log directories, team number, and TBA key. Claude Code in a project gets the same settings VS Code uses there (see [Settings](#settings)).

- **Nothing to set up.** Open the robot project in VS Code with the extension installed, start Claude Code there, and approve `wpilog-analyzer` when it asks (Claude Code asks once per project before starting a server from `.mcp.json`; `/mcp` lists it). This works for Claude Code in VS Code and for the `claude` command in a terminal in that folder.
- **Settings changes reach the right projects.** Changing a User setting (or the TBA key) rewrites the configuration file of every project with the entry, open or not; changing a project's own setting rewrites only that project's. Claude Code picks the change up the next time it starts the server (a new session, or reconnecting the server in `/mcp`). The entry itself changes only with the Java path or heap size, and is updated when the project is next opened in VS Code; so is a project's own setting edited outside VS Code.
- **Keep `.mcp.json` out of git.** Nothing in the entry is secret (the TBA key is never in it), but it holds this computer's Java, JAR, and configuration paths, which don't exist on a teammate's computer, and each teammate's extension would rewrite it with their own. So the extension doesn't write into a `.mcp.json` that git already tracks (it tells you how to stop tracking it), and when git would pick the file up, it offers to add `.mcp.json` to `.gitignore`.
- **The TBA key needs nothing extra.** The key you set with **WPILog Analyzer: Set The Blue Alliance API Key** reaches Claude Code's server through the configuration file, which only you can read; it is never in `.mcp.json`, and you set no environment variable.
- **Updates don't break it.** The entry points at a copy of the server JAR in the extension's storage, refreshed when the extension updates, so its path never changes. An entry an earlier version wrote (pointing at a folder VS Code deletes after an update) is rewritten when the project is next opened.
- **Using the standalone install with Claude Code too?** If the project's `.mcp.json` already has an entry that runs wpilog-mcp (such as the standalone install's `wpilog`), the extension leaves the file alone, so Claude Code doesn't start two servers. If you registered the standalone server for all projects (`claude mcp add --scope user`), turn off **Enable For Claude Code**. The extension's servers keep their own disk cache, so a standalone install of another version never discards their cached logs, nor they its (see [Extension or Standalone?](../README.md#extension-or-standalone)).
- **Other folders:** in a folder that is not a robot project (a folder of logs, say), run **WPILog Analyzer: Add to Claude Code in This Folder** from the Command Palette; the entry is then kept up to date there too. To stop, turn off **Enable For Claude Code** and delete the `wpilog-analyzer` entry from `.mcp.json`.

## Requirements

- **Java 17+** — The WPILib toolkit includes a compatible JDK (auto-detected)
- **VS Code 1.101+** with an MCP-compatible AI agent

## Settings

Your **User** settings apply to every project. A project's own settings (**Workspace**, its `.vscode/settings.json`) override the log directories and team number in that project, for Copilot and Claude Code alike; a project's list of additional directories replaces your User list there, as lists do in VS Code. A relative log path is a folder inside the project: in your User settings it names that folder in every project, and in a project's settings, in that project. For example, if your robot code writes simulation logs to a `logs` folder, adding `logs` lists them. Robot projects usually commit `.vscode/settings.json`, so in a project's settings prefer relative paths, which mean the same folder on every teammate's computer.

| Setting | Description | Default |
|---------|-------------|---------|
| `wpilog-mcp.javaPath` | Path to `java` executable | auto-detect |
| `wpilog-mcp.wpiLibYear` | WPILib installation year (e.g., `2026`) | auto-detect latest |
| `wpilog-mcp.logDirectory` | Path to `.wpilog` files (relative: inside the project) | auto-detect |
| `wpilog-mcp.additionalLogDirectories` | More directories of `.wpilog` files, listed along with `logDirectory` (an archive drive, logs another team published, or a folder inside the project, given as a relative path); REV logs are matched only within the directory holding each wpilog | none |
| `wpilog-mcp.teamNumber` | FRC team number for TBA lookups | `2363` |
| `wpilog-mcp.maxHeap` | JVM heap size | `4g` |
| `wpilog-mcp.enableForClaudeCode` | **Claude Code only:** add the server to robot projects' `.mcp.json`, where Claude Code finds it (see [Using It with Claude Code](#using-it-with-claude-code)) | on |

## The Blue Alliance API Key

Match data from The Blue Alliance needs a free read API key from [thebluealliance.com/account](https://www.thebluealliance.com/account). Run **WPILog Analyzer: Set The Blue Alliance API Key** from the Command Palette (`Ctrl+Shift+P`) and paste it. The key is kept in VS Code's secret storage (your operating system's keychain), not in a settings file. It reaches the server VS Code starts through that server's environment, and Claude Code's through the configuration file the extension writes for it, which only you can read (see [Using It with Claude Code](#using-it-with-claude-code)); you set no environment variables. **WPILog Analyzer: Clear The Blue Alliance API Key** removes it, from that file too.

- **Upgrading from 0.8.x:** earlier versions kept the key in the `wpilog-mcp.tbaApiKey` setting, which is stored in plaintext. The extension moves a key found there into secret storage and clears the setting.
- **Earlier versions also wrote the key into `.mcp.json`** in the workspace root. The extension removes it from that file. If the file (or a workspace `.vscode/settings.json` holding the key) was ever committed or shared, revoke the key on your TBA account page and set a new one.
- **Claude Code:** the `.mcp.json` entry never contains the key, only the path of the file that holds it.

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

Besides its install directory, the extension writes the `wpilog-analyzer` entry in robot projects' `.mcp.json` (delete the entry, or the file, if you no longer want it), and, in VS Code's storage for the extension (`globalStorage/triplehelixprogramming.wpilog-analyzer`), its servers' disk cache (`cache/`, parsed logs and REV log sync results), a copy of the server JAR, and, under `projects/`, a configuration file for each project with the entry (holding its settings and the TBA key) for Claude Code. To remove the stored TBA API key, from those files too, run **WPILog Analyzer: Clear The Blue Alliance API Key** before uninstalling.

## Troubleshooting

- **Server not starting** — Open the Output panel (`Ctrl+Shift+U`) and select **WPILog Analyzer** from the dropdown. This shows the Java path, JAR path, and any error messages.
- **Java not found** — If you're using WPILib VS Code, the extension should find the bundled JDK automatically. Otherwise, set `wpilog-mcp.javaPath` in VS Code settings to point to a JDK 17+ `java` executable.
- **Tools not appearing** — Restart VS Code completely (quit and relaunch, not just reload the window).
- **Claude Code doesn't list `wpilog-analyzer`** — Check that **Enable For Claude Code** is on. The **WPILog Analyzer** output says what the extension did with each folder's `.mcp.json`: no entry outside robot projects (run **WPILog Analyzer: Add to Claude Code in This Folder**), none in a `.mcp.json` that git tracks or that already runs wpilog-mcp. In Claude Code, run `/mcp`: a server waiting for approval is listed as pending. A Claude Code session started before the entry was written needs restarting.
- **Out of memory with large logs** — Set `wpilog-mcp.maxHeap` to `8g` in VS Code settings.
- **Log files show as corrupted** — Truncated logs (from robot power loss) are handled gracefully. The server recovers as much data as possible and marks the log as truncated.

## Standalone Install (without VS Code)

If you use Claude Desktop, or Claude Code without VS Code, or another MCP client, see [doc/STANDALONE.md](../doc/STANDALONE.md) for standalone installation and configuration.

## More Information

- [Main README](../README.md) — Full project overview, available tools, supported data types
- [TOOLS.md](../doc/TOOLS.md) — Complete tool reference
- [wpilog-mcp on GitHub](https://github.com/TripleHelixProgramming/wpilog-mcp)
