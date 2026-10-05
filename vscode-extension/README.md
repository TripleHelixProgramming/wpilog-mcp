<table>
<tr>
<td width="180" valign="top" align="center">
  <!-- The leading <br> balances the h1's top margin, so the icon sits midway between the cell's top edge and the title. -->
  <p align="center"><br><img src="images/icon.png" alt="WPILog Analyzer icon" width="140" align="top"></p>
  <h1 align="center">WPILog Analyzer</h1>
</td>
<td valign="middle">

AI-powered FRC robot log analysis for VS Code. The extension analyzes `.wpilog` telemetry files from the roboRIO to help diagnose brownouts, CAN errors, swerve drive issues, loop timing problems, and more.

The extension runs an [MCP server](https://modelcontextprotocol.io/) whose tools let an AI agent analyze robot logs and extract their raw data for further processing. The server is designed for and tested with Claude. Copilot and other agents that use VS Code's MCP server registry find the server there. Claude Code reads `.mcp.json` instead, so the extension adds the server to your robot project's `.mcp.json` (see [Using It with Claude Code](#using-it-with-claude-code)). It also shows the logs without an assistant: WPILog Explorer lists your logs and opens one to its entries (see [Exploring Logs](#exploring-logs)).

</td>
</tr>
</table>

## Quick Start

1. Install the extension (see [Install](#install)).
2. Put your `.wpilog` files in `~/riologs`, or set another folder in [Settings](#settings).
3. Open your robot project in VS Code. With Claude Code, approve the `wpilog-analyzer` server when it asks. If Claude Code was already running, restart it first; it reads its servers only at startup (see [Using It with Claude Code](#using-it-with-claude-code)).
4. Ask your AI assistant about your logs:
   - *"What logs are available?"*
   - *"Can you walk me through the power delivery in our last match?"*
   - *"Help me understand if we had any CAN bus issues while enabled"*
   - *"How did our swerve modules perform?"*

How deep the analysis goes depends on the AI model you use. The server provides the tools and the data; the model does the reasoning.

## Requirements

- Java 17 or later. The WPILib installer includes a suitable JDK, which the extension finds by itself.
- VS Code 1.101 or later, with an MCP-capable AI agent.

## Install

Install **WPILog Analyzer** from the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=TripleHelixProgramming.wpilog-analyzer): open the Extensions view in VS Code (`Ctrl+Shift+X`, or `Cmd+Shift+X` on macOS), search for `WPILog Analyzer`, and click Install. To install a particular build instead, such as a test build of the next version, download `wpilog-analyzer-{version}.vsix` from the [releases page](https://github.com/TripleHelixProgramming/wpilog-mcp/releases), then:

1. Open the Extensions sidebar (`Ctrl+Shift+X`, or `Cmd+Shift+X` on macOS), click `...` at its top right, choose **Install from VSIX...**, and select the downloaded file.
2. Restart VS Code.

Or from the command line:
```bash
code --install-extension wpilog-analyzer-{version}.vsix
```

The WPILib VS Code distribution works the same way, from its own Extensions view or its own `code` binary; it and the system VS Code keep separate extension directories.

## How It Works

The extension starts once VS Code has finished starting up. It finds Java, the server JAR, and your log directories (see [Auto-Detection](#auto-detection)), writes them into a configuration file in its own storage, and starts a server in the background on this computer, listening on the loopback address on a port chosen once for that server. It registers the server with VS Code through the MCP server provider API, as an HTTP server at its URL, and gives Claude Code a bridge to the same server (see [Using It with Claude Code](#using-it-with-claude-code)). So VS Code's agents, Claude Code, and anything else on this computer share one server: a log loaded for one is loaded for all, and one JVM serves them. Changing a `wpilog-mcp` setting or the TBA key rewrites the configuration and restarts the server with the new values.

There is one server by default, named `default`, with the log directories and team number of your User settings, and every project uses it unless you say otherwise. To keep some logs apart (a second robot, an archive of another season, a folder of another team's logs), define more servers in the `wpilog-mcp.servers` User setting: each has a name, the directory or directories it lists, and its team number. Then name the server a project uses in its `wpilog-mcp.serverName` Workspace setting. Projects that name the same server share one, which lists all their logs along with the server's own; a project's own log directories (its Workspace settings, relative paths inside the project) join whichever server it uses. A project that names a server you have not defined uses the default one, and the output channel says so. Each server runs as long as something uses it. With no client connected for thirty minutes (the `wpilog-mcp.idleExitMinutes` setting), it exits on its own; whoever needs it next, VS Code's agents, Claude Code, or the extension, starts it again, which takes a few seconds. After an update, the next start replaces a server of the old version. **WPILog Analyzer: Restart Server** restarts a server by hand, and **WPILog Analyzer: Show Server Log** opens its log, `~/.wpilog-mcp/logs/vscode-<name>.log`; the **WPILog Analyzer** output channel shows what the extension did to start it.

Keep your robot project open in VS Code while you analyze logs. The agent can then read your code too: it can match logged entry names to the subsystems that write them, compare PID constants with the behavior in the log, and fit its analysis to your robot. The server tells the agent to do so: an entry's name does not say for certain what it measures; the code that logs it does.

## Exploring Logs

WPILog Explorer, in the activity bar, shows your logs without an assistant. The **Logs** view lists the logs the server finds in your log directories, newest first, grouped by event and date, each with its match, its size, and, with a TBA key set, its score. Click a log to open it; the filter button narrows the list by name, event, or match, and right-click reveals a log in your file manager or copies its path. Double-clicking a `.wpilog` file anywhere in VS Code opens it the same way (**Open With** still offers the other editors).

An open log shows its time range, its duration, how many entries it has, and a warning when the file was cut short, then every entry with its type and sample count; a filter box narrows them by name. Click an entry for its description: its first and last sample, its struct schema and where the schema came from, the numeric fields the server's tools address inside it (as `entry + path`, ready to paste into a prompt), and three representative samples. The **Entries** view shows the same entries as a tree by name, with a struct's fields beneath it. Every number shown comes from the same tools the assistant calls (`list_available_logs`, `list_entries`, `get_entry_info`), so the viewer and the assistant always agree.

**Plotting.** The **plot** button beside a numeric entry, a numeric field in an entry's details, or a click on one in the Entries view, draws it in the active pane; **Add pane** makes another, and each pane holds any number of entries. The timeline across the top shows the whole log with its enabled periods shaded by mode (autonomous, teleop, test, from `get_match_phases`) and the Driver Station's events marked (`get_ds_timeline`: brownouts in red, alerts in yellow, enables and disables as lines); drag on it to choose the window the panes show. In a pane, drag to zoom, roll the wheel to zoom about the cursor, shift+wheel or the arrow keys to pan, and double-click or Home for the whole log; the cursor is shared across panes, and the legend reads each series' value under it. A series is drawn from every sample where the log allows it (up to four million per series), so zooming and panning touch no server and a spike one sample wide is drawn where it is; a longer series is drawn from the server's buckets (`read_entry`'s `max_points` rule) as a band from each bucket's minimum to its maximum with the mean as the line, and the readout says so, until the window is small enough to fetch exactly. An entry the server classes as change-only is drawn as steps: a hold is a hold, never a slope through it. Click a series' name in its chip for its statistics over the visible window, from `get_statistics`: count, extremes, mean, median, standard deviation, quartiles, the data quality with its reasons, and the confidence level, as the server states them.

**Structs and arrays.** A struct's numeric fields and an array's elements are plotted by their field paths, as the tools address them: expand a struct or array entry in the Entries view, or open its details, and plot a field; a `[*]` path, which pools every element, expands to the elements (`[0].speed`, `[1].speed`, ...) in the tree, and **Plot Every Element** (in the tree's context menu, or the **plot all** button in the details) plots the first sixteen at once.

**Console.** The pane under the plot lists the log's text through `search_strings`: a pattern, a level (error, warning, info), and whether to search only the visible window; adjacent repeats are collapsed with their count, an alert shows when it cleared, and the counts are the server's totals. A click on a line moves the cursor to it in every pane, and the matches in the window are marked on the timeline by level.

**Field.** The pane draws the robot's pose over the window, top-down, on an outline of that season's field from the bundled game data (its length and width, the center line, and the alliance zones where the season's data gives them), with no field image. The pose entry is the one the server's signal resolver finds by convention, named with its basis; when the resolver does not find one, the server does not guess, and you pick among its candidates. The path is colored from blue to orange over the window, and the cursor's pose is drawn with its heading. REV signals follow in a later release ([the plan](../doc/EXPLORER_PLAN.md)).

The samples come from the server's data endpoint (`GET /data/entries`, see [the standalone guide](../doc/STANDALONE.md#the-data-endpoint)) as Apache Arrow streams, which a script or a notebook can read from the same server; the plot's page opens no connection of its own, and reads the streams with a small reader of its own.

The explorer reads a log through the server of the project the log belongs to (see [How It Works](#how-it-works)), which reads only the files inside its log directories. A log elsewhere gets the server's error with the setting to change; add its folder to `wpilog-mcp.additionalLogDirectories`, or move the file.

## Using It with Claude Code

Claude Code doesn't use VS Code's MCP server registry; it finds servers in a `.mcp.json` file in the folder it runs in. So in a WPILib robot project (a folder with `.wpilib/wpilib_preferences.json`), the extension adds a `wpilog-analyzer` entry to that folder's `.mcp.json`. It leaves other servers in the file alone, and doesn't touch a file that isn't valid JSON. One checkbox controls this: **Enable For Claude Code** (`wpilog-mcp.enableForClaudeCode`, on by default). It affects only Claude Code, since Copilot and other VS Code agents get the server from VS Code itself.

There is nothing to set up. Open the robot project in VS Code with the extension installed, start Claude Code there, and approve `wpilog-analyzer` when it asks. Claude Code asks once per project before starting a server from `.mcp.json`, and `/mcp` lists it. This works for Claude Code in VS Code and for the `claude` command in a terminal in that folder.

In a folder that is not a robot project (a folder of logs, say), run **WPILog Analyzer: Add to Claude Code in This Folder** from the Command Palette. The command works even with **Enable For Claude Code** off; while the setting is on, the extension keeps the entry up to date. To stop, turn off **Enable For Claude Code** and delete the `wpilog-analyzer` entry from `.mcp.json`.

Claude Code reads `.mcp.json` only when a session starts; a running session, a new conversation in it, and `/clear` don't pick up a new server. So when the extension adds the entry to a project, it tells you, once per project, that a Claude Code session already running there needs a restart. If the Claude Code extension for VS Code is installed, the notice offers **Reload Window**, which restarts Claude Code's sessions. In a terminal, exit Claude Code and run `claude --continue`, which starts a new session with your conversation. **Don't Show Again** turns the notice off, for those who use only Copilot or other VS Code agents.

The entry does not start a server of its own: it runs the server's bridge (`connect vscode-<name>`), which joins the server the project uses and starts it first when it is not running, as when Claude Code runs in a terminal with VS Code closed (see [How It Works](#how-it-works)). As in the [standalone install](../doc/STANDALONE.md), the entry holds only what runs the bridge (Java, heap size, and JAR), the server's name, and the path of its configuration file, which the extension keeps in its storage in the format of the standalone install's `servers.yaml`: the log directories, team number, TBA key, and port. The log directories are the server's (your User settings' for the default server) with each of its projects' own on top (their Workspace settings, relative paths inside each project).

When you change a setting, the TBA key, or a project's own settings, the extension rewrites that file and restarts the server, and Claude Code's next request reaches the new one; a Claude Code session whose connection was lost in the restart reconnects when you ask it to in `/mcp`, or in its next session. The entry itself changes only with the server the project uses, the Java path, or the heap size and is updated the next time the project is opened in VS Code. An entry written by an earlier version, which started a stdio server of its own with a configuration file per project, keeps working with that file until the project is next opened in VS Code, when the entry is rewritten to use the shared server and the file removed.

Keep `.mcp.json` out of git. Nothing in the entry is secret, but it holds this computer's Java, JAR, and configuration paths, which don't exist on a teammate's computer; each teammate's extension would rewrite it with their own. So the extension doesn't write into a `.mcp.json` that git already tracks (it tells you how to stop tracking it), and when git would pick the file up, it offers once to add `.mcp.json` to `.gitignore`.

The TBA key needs nothing extra: it reaches Claude Code's server through that configuration file and is never written into `.mcp.json`, which holds only the file's path (see [The Blue Alliance API Key](#the-blue-alliance-api-key)).

Updates don't break the entry. It points at a copy of the server JAR in the extension's storage, whose path doesn't change; after an update, the extension refreshes the copy the next time VS Code opens a project with the entry. An entry written by an earlier version (pointing at a folder VS Code deletes after an update) is rewritten when the project is next opened.

If you also use the standalone install with Claude Code, see [Using It Alongside the Standalone Install](#using-it-alongside-the-standalone-install).

## Settings

Your **User** settings apply to every project. A project's own settings (**Workspace**, its `.vscode/settings.json`) override the log directories and team number in that project, for Copilot and Claude Code alike; a project's list of additional directories replaces your User list there, as lists do in VS Code. A relative log path is a folder inside the project: in your User settings it names that folder in every project; in a project's own settings, only in that project. For example, if your robot code writes simulation logs to a `logs` folder, adding `logs` lists them. With no folder open, a relative path is ignored. Robot projects usually commit `.vscode/settings.json`, so in a project's settings prefer relative paths, which mean the same folder on every teammate's computer.

Paths work the same way on macOS, Linux, and Windows. A path starting with `~/` is in your home folder (`~/riologs` is `/Users/you/riologs` on a Mac and `C:\Users\you\riologs` on Windows), and forward slashes work on every system, so use them (`~/riologs`, `sim/logs`).

The Settings editor lists the settings in this order: where your logs are, your team and its Blue Alliance key, then Claude Code, and last the Java settings, which are detected for you.

| Setting | Description | Default |
|---------|-------------|---------|
| `wpilog-mcp.logDirectory` | Directory of `.wpilog` files (a relative path is inside the project) | auto-detect |
| `wpilog-mcp.additionalLogDirectories` | More directories of `.wpilog` files, listed along with `logDirectory` (an archive drive, logs another team published, or a folder inside the project, given as a relative path). REV logs are matched to a wpilog only within the directory that holds it | none |
| `wpilog-mcp.teamNumber` | Your FRC team number, for TBA lookups when a log doesn't record it | (empty) |
| `wpilog-mcp.tbaApiKey` | Your Blue Alliance read API key, for match data. A key pasted here is moved into VS Code's secret storage and the field is cleared (see [The Blue Alliance API Key](#the-blue-alliance-api-key)) | (empty) |
| `wpilog-mcp.enableForClaudeCode` | Claude Code only: add the server to robot projects' `.mcp.json`, where Claude Code finds it (see [Using It with Claude Code](#using-it-with-claude-code)) | on |
| `wpilog-mcp.servers` | More servers beside the default one, as a User setting: each with a name, the directory or directories it lists, and its team number (see [How It Works](#how-it-works)) | none |
| `wpilog-mcp.serverName` | The server this project uses, as a Workspace setting: one of the names in `wpilog-mcp.servers`, or blank for the default server | (blank) |
| `wpilog-mcp.javaPath` | Path to the `java` executable | auto-detect |
| `wpilog-mcp.wpiLibYear` | WPILib installation year whose JDK to use (e.g., `2026`) | latest installed |
| `wpilog-mcp.maxHeap` | JVM heap size, such as `2g`, `4g`, or `8g` | `4g` |
| `wpilog-mcp.idleExitMinutes` | Minutes the shared server keeps running with no client connected before it exits on its own; `0` keeps it running (see [How It Works](#how-it-works)) | `30` |

## The Blue Alliance API Key

Match data from The Blue Alliance needs a free read API key from [thebluealliance.com/account](https://www.thebluealliance.com/account). Paste it into the **Tba Api Key** field in your User settings (search the Settings editor for `wpilog-mcp`). A few seconds later the extension moves it into VS Code's secret storage (your operating system's keychain) and clears the field, which shows empty once you leave it. The key is never kept in a settings file, and Settings Sync never uploads it.

The field shows no key even when one is stored; to replace the key, paste a new one. To enter the key without it showing on screen, run **WPILog Analyzer: Set The Blue Alliance API Key** from the Command Palette (`Ctrl+Shift+P`) instead. **WPILog Analyzer: Clear The Blue Alliance API Key**, also linked from the field's description, removes the key.

The server VS Code starts reads the key from the configuration file the extension writes for it, which only you can read (see [Using It with Claude Code](#using-it-with-claude-code)). You set no environment variables. Clearing the key removes it from that file too.

A key in a project's own settings (its `.vscode/settings.json`) is moved out of that file the same way, but it never replaces a key you already stored, because it may be a teammate's that was committed with the project. The extension tells you to revoke it if the file was committed or shared.

If you are upgrading from 0.8.x:

- Earlier versions kept the key in the `wpilog-mcp.tbaApiKey` setting, which VS Code keeps in plaintext. The extension moves a key it finds there into secret storage and clears the setting.
- Earlier versions also wrote the key into `.mcp.json` in the workspace root. The extension removes it from that file. If the file (or a workspace `.vscode/settings.json` holding the key) was ever committed or shared, revoke the key on your TBA account page and set a new one.

## Auto-Detection

- **Java:** the `wpilog-mcp.javaPath` setting, if that file exists. Otherwise, when VS Code is itself a WPILib installation (it runs from a `wpilib/<year>/` folder), that year's JDK. Otherwise the JDK of an installed WPILib year under `~/wpilib/` (on Windows, also `C:\Users\Public\wpilib\`): the year in `wpilog-mcp.wpiLibYear` if it is installed, else the latest. Then `JAVA_HOME`, then a `java` on the `PATH` of version 17 or later.
- **Server JAR:** the copy bundled in the extension's `server/` folder. For Claude Code, the extension copies it into its storage (see [Using It with Claude Code](#using-it-with-claude-code)).
- **Log directory:** the `wpilog-mcp.logDirectory` setting. Otherwise the first of `~/riologs`, `~/wpilib/logs`, and `~/Documents/FRC/logs` that exists. If none does, the extension asks you to browse for a folder (saved as your User **Log Directory** setting) or create `~/riologs`. The configuration files for Claude Code use the same search but never ask.

## Upgrading

Installed from the Marketplace, the extension updates by itself. For a `.vsix` install, install the new `.vsix` over the existing one; VS Code replaces the previous version, so there is no need to uninstall first.

## Uninstalling

Open the Extensions sidebar, find **WPILog Analyzer**, click the gear icon, and select **Uninstall**. Or from the command line:
```bash
code --uninstall-extension TripleHelixProgramming.wpilog-analyzer
```

Besides its install directory, the extension writes:

- the `wpilog-analyzer` entry in robot projects' `.mcp.json` (delete the entry, or the file, if you no longer want it), and a `.mcp.json` line in a project's `.gitignore` if you accepted that offer;
- in VS Code's storage for the extension (`globalStorage/triplehelixprogramming.wpilog-analyzer`): its servers' disk cache (`cache/`, REV log sync results), a copy of the server JAR (`server/`), and, under `projects/`, a configuration file for each project with the entry, holding its settings and the TBA key for Claude Code;
- the servers' own files outside VS Code: each one's log, PID file, and stop token under `~/.wpilog-mcp/logs/` and `~/.wpilog-mcp/run/` (named `vscode-default`, or `vscode-` and the server's name), beside any standalone install's; a server still running exits on its own after the idle time, or at once when ended as a process
- your `wpilog-mcp` settings, which stay in VS Code's settings as any extension's do, and the `~/riologs` folder if you had the extension create it.

To remove the stored TBA API key, including from the projects' configuration files, run **WPILog Analyzer: Clear The Blue Alliance API Key** before uninstalling.

## Troubleshooting

- **Server not starting:** open the Output panel (`Ctrl+Shift+U`) and select **WPILog Analyzer** from the dropdown. It shows the Java path, the JAR path, the log directories, the command that started the server and what it printed, and any error messages. **WPILog Analyzer: Show Server Log** opens the server's own log. The extension tries again with growing pauses after a failed start and gives up after a few; a settings change or **WPILog Analyzer: Restart Server** makes it try again.
- **Port in use:** the server's port is chosen once for you and kept. If another program takes it, the extension chooses another the next time it starts the server, and VS Code's agents are told the new address; Claude Code's bridge reads the port from the configuration file, so its entry needs no change.
- **Java not found:** in WPILib VS Code the extension should find the bundled JDK by itself. Otherwise, set `wpilog-mcp.javaPath` to a JDK 17+ `java` executable.
- **Tools not appearing:** restart VS Code completely (quit and relaunch, not just reload the window).
- **Claude Code reports the server as failed after a settings change:** the server was restarted with the new settings, and the session's connection ended with the old one. Reconnect it in `/mcp`, or start a new session.
- **Claude Code doesn't list `wpilog-analyzer`:** check that **Enable For Claude Code** is on. The extension adds the entry only in robot projects; elsewhere, run **WPILog Analyzer: Add to Claude Code in This Folder**. It also leaves alone a `.mcp.json` that git tracks, that already runs wpilog-mcp, or that isn't valid JSON, and the **WPILog Analyzer** output says so when it does. In Claude Code, run `/mcp`: a server waiting for approval is listed as pending. A Claude Code session started before the entry was written needs restarting.
- **Out of memory with large logs:** set `wpilog-mcp.maxHeap` to `8g`.
- **A log looks corrupted:** a log cut short (by a power loss, for example) still loads. The server reads it up to the damage, marks it as truncated, and says what it skipped.

## Using It Alongside the Standalone Install

The [standalone install](../doc/STANDALONE.md) runs the same server for MCP clients outside VS Code: Claude Desktop, Claude Code without VS Code, or another client. Most people need only the extension (see [Extension or Standalone?](../README.md#extension-or-standalone)). If you install both, they stay out of each other's way:

- Each has its own configuration: the extension's settings in VS Code, and the standalone server's `servers.yaml`. Environment variables that the server reads, such as `TBA_API_KEY`, reach both if you have set them.
- Each has its own disk cache (the extension's is in its VS Code storage), so the two never discard each other's cached results, even when their versions differ.
- Copilot and other VS Code agents use the extension's server.
- For Claude Code, the extension adds no second server to a project. If the project's `.mcp.json` already has an entry that runs wpilog-mcp (such as the standalone install's `wpilog`), the extension leaves the file alone. Otherwise it adds its own entry (see [Using It with Claude Code](#using-it-with-claude-code)). If you add a standalone entry to a file that already has the extension's `wpilog-analyzer` entry, delete the `wpilog-analyzer` entry yourself; the extension won't remove it.
- If you registered the standalone server for all projects (`claude mcp add --scope user`), turn off **Enable For Claude Code** and delete the `wpilog-analyzer` entry from any project's `.mcp.json` that already has one. Otherwise Claude Code would start both servers there.

## More Information

- [Main README](../README.md): project overview, the tools, and links to the rest of the documentation
- [TOOLS.md](../doc/TOOLS.md): complete tool reference
- [wpilog-mcp on GitHub](https://github.com/TripleHelixProgramming/wpilog-mcp)
