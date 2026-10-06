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

The extension starts once VS Code has finished starting up. It finds Java, the server JAR, and your log directories (see [Auto-Detection](#auto-detection)), writes them into a configuration file in its own storage, and starts a server in the background on this computer, listening on the loopback address on a port chosen once for that server. It registers the server with VS Code through the MCP server provider API, as an HTTP server at its URL, and gives Claude Code a bridge to the same server (see [Using It with Claude Code](#using-it-with-claude-code)). So VS Code's agents, Claude Code, and anything else on this computer share one server: a log loaded for one is loaded for all, and one JVM serves them. Changing a `wpilog-mcp` setting or the TBA key rewrites the configuration and restarts the server with the new values. Java-path and heap changes also restart it. A pending change stays pending until the restart succeeds, even if another operation has already written the configuration file.

There is one server per computer, named `vscode-default`, with the log directories and team number of your User settings; every project you open, and every project that has the Claude Code entry, adds its own log directories (its Workspace settings, relative paths inside the project) to it, so one server lists all your logs, and the first project with a team number of its own sets the server's. Logs you want kept apart (a second robot, an archive of another season, a folder of another team's logs) go in a directory of their own, listed under **Additional Log Directories**; a tool tells its logs apart by path. The server runs as long as something uses it. With no client connected for thirty minutes (the `wpilog-mcp.idleExitMinutes` setting), it exits on its own; whoever needs it next, VS Code's agents, Claude Code, or the extension, starts it again, which takes a few seconds. After an update, the next start replaces a server of the old version. **WPILog Analyzer: Restart Server** restarts it by hand, and **WPILog Analyzer: Show Server Log** opens its log, `~/.wpilog-mcp/logs/vscode-default.log`; the **WPILog Analyzer** output channel shows what the extension did to start it. The server is the extension's own: it reads none of the [standalone install](#using-it-alongside-the-standalone-install)'s servers, and a standalone server reads none of the extension's settings. If you have the standalone install and want everything on its server instead, turn on **Use Standalone Server** (see [Using the Standalone Server](#using-the-standalone-server)).

Keep your robot project open in VS Code while you analyze logs. The agent can then read your code too: it can match logged entry names to the subsystems that write them, compare PID constants with the behavior in the log, and fit its analysis to your robot. The server tells the agent to do so: an entry's name does not say for certain what it measures; the code that logs it does.

## Exploring Logs

WPILog Explorer, in the activity bar, shows your logs without an assistant. The **Logs** view lists the logs the server finds in your log directories, newest first, grouped by event and date, each with its match, its size, and, with a TBA key set, its score. Click a log to open it; the filter button narrows the list by name, event, or match, and right-click reveals a log in your file manager or copies its path. Double-clicking a `.wpilog` file anywhere in VS Code opens it the same way (**Open With** still offers the other editors).

An open log shows its time range, its duration, how many entries it has, and a warning when the file was cut short, then every entry with its type and sample count; a filter box narrows them by name. Click an entry for its description: its first and last sample, its struct schema and where the schema came from, the numeric fields the server's tools address inside it (as `entry + path`, ready to paste into a prompt), and three representative samples. The **Entries** view shows the same entries as a tree by name, with a struct's fields beneath it. Every number shown comes from the same tools the assistant calls (`list_available_logs`, `list_entries`, `get_entry_info`), so the viewer and the assistant always agree.

**Plotting.** The **plot** button beside a numeric entry, a numeric field in an entry's details, or a click on one in the Entries view, draws it in the active pane; **Add pane** makes another, and each pane holds any number of entries. The timeline across the top shows the whole log with its enabled periods shaded by mode (autonomous, teleop, test, from `get_match_phases`) and the Driver Station's events marked (`get_ds_timeline`: brownouts in red, alerts in yellow, enables and disables as lines); drag on it to choose the window the panes show. In a pane, drag to zoom, roll the wheel to zoom about the cursor, shift+wheel or the arrow keys to pan, and double-click or Home for the whole log; the cursor is shared across panes, and the legend reads each series' value under it. A series is drawn from every sample where the log allows it (up to four million per series), so zooming and panning touch no server and a spike one sample wide is drawn where it is; a longer series is drawn from the server's buckets (`read_entry`'s `max_points` rule) as a band from each bucket's minimum to its maximum with the mean as the line, and the readout says so, until the window is small enough to fetch exactly. An entry the server classes as change-only is drawn as steps: a hold is a hold, never a slope through it. If records could not be decoded, the series’ chip shows the server’s warning with the failed and original record counts and the first failure; the plotted samples are the surviving records. Click a series' name in its chip for its statistics over the visible window, from `get_statistics`: count, extremes, mean, median, standard deviation, quartiles, the data quality with its reasons, and the confidence level, as the server states them.

**Structs and arrays.** A struct's numeric fields and an array's elements are plotted by their field paths, as the tools address them: expand a struct or array entry in the Entries view, or open its details, and plot a field; a `[*]` path, which pools every element, expands to the elements (`[0].speed`, `[1].speed`, ...) in the tree, and **Plot Every Element** (in the tree's context menu, or the **plot all** button in the details) plots the first sixteen at once.

**Console.** The pane under the plot lists the log's text through `search_strings`: a pattern, a level (error, warning, info), and whether to search only the visible window; adjacent repeats are collapsed with their count, an alert shows when it cleared, and the counts are the server's totals. A click on a line moves the cursor to it in every pane, and the matches in the window are marked on the timeline by level.

**Field.** The pane draws the robot's pose over the window, top-down, on an outline of that season's field from the bundled game data (its length and width, the center line, and the alliance zones where the season's data gives them), with no field image. The pose entry is the one the server's signal resolver finds by convention, named with its basis; when the resolver does not find one, the server does not guess, and you pick among its candidates. The path is colored from blue to orange over the window, and the cursor's pose is drawn with its heading.

**REV logs.** The pane lists the signals of the REV motor controller logs the server found beside the wpilog (`list_revlog_signals`), by CAN bus and device, each with its unit and sample count, and for each bus how its timestamps were put on the robot's clock: the synchronization method (cross-correlation, the wall clock alone, or an offset you gave), its confidence, and the offset. A signal plots in the same panes as the log's entries, on the robot's clock, and its chip says how it was aligned. A bus the server could not synchronize is said so, with the server's reason, and its signals are not plotted: nothing is drawn on a clock the server did not align; `set_revlog_offset` gives such a bus an offset. The synchronization runs in the background when a log is first opened, and the pane waits for it. The data view and the notebook follow in a later release ([the plan](../doc/EXPLORER_PLAN.md)).

The samples come from the server's data endpoint (`GET /data/entries`, see [the standalone guide](../doc/STANDALONE.md#the-data-endpoint)) as Apache Arrow streams, which a script or a notebook can read from the same server; the plot's page opens no connection of its own, and reads the streams with a small reader of its own.

The explorer reads a log through the server of the project the log belongs to (see [How It Works](#how-it-works)), which reads only the files inside its log directories. A log elsewhere gets the server's error with the setting to change; add its folder to `wpilog-mcp.additionalLogDirectories`, or move the file.

## Organizing Your Logs

When a log directory has files to organize, WPILog Explorer offers **Organize**, **Not now**, or **Never for this folder**. Nothing moves until you choose Organize and complete the choices. Not now (or dismissing the offer) lasts until the next activation; Never is remembered for that folder. **WPILog Explorer: Organize Logs…**, also in the Logs view's title bar, can ask again at any time. With several directories it first asks which one. A store with unmanaged files gets the same offer, with their count and the first few names.

Choose **Move** (the default) to organize in place, or **Copy** to keep the originals too. Then choose a known robot by name and serial, **New robot…**, or **Decide later**. Names allow letters, digits, dots, hyphens, and underscores, and must be portable filenames. The log's own identity wins when present; without one, Decide later leaves the files under Unassigned. The server imports the batch while the notification shows the phase, file, and completed/total counts. Afterwards it reports imported, present, unassigned, and refused counts; each file's result and the server's reasons go to the **WPILog Analyzer** output channel. A shared serial in two robot directories is reported with both paths for a later explicit merge.

A store's Logs tree shows robots (with their identity basis in the tooltip), dates, sessions with their times and known event/match, then each wpilog and its correlated REV companions. Clicking a REV companion opens its wpilog, where the REV pane shows its signals. **Unassigned**, **Inbox**, and **Unmanaged** come last with import actions; Inbox files show waiting, importing, or refused and its reason. **Assign to Robot…** on an unassigned file moves it into the chosen robot's history through the server. Plain folders keep their event/date groups. A window containing both shows a store node and a directory node; Import on a plain file or directory copies it into an existing store.

HTTP import sources must be inside the server's configured log directories. For a USB stick or another outside path, copy files into the store's **inbox/**, or use `wpilog-mcp import --robot practice /path/to/usb/logs` with the standalone server's name/configuration as needed. The command publishes a complete inbox batch, carrying the robot choice in `batch.json`; the daemon imports it once it stops growing and writes each outcome in `inbox/imported.log`. See the [standalone import guide](../doc/STANDALONE.md#importing-logs). Refresh the Logs view to see automatic inbox imports.

Direct imports keep original paths and filenames in the session manifest's file provenance (or `unassigned/<hash>/import.json` before assignment); the listing reports moved paths for seven days. Inbox receipts keep the inbox path and its destination. Duplicate content is reported as present and its source is left in place. Organizing runs in the same server the viewer already uses, whether owned by the extension or the standalone install.

## Using It with Claude Code

Claude Code doesn't use VS Code's MCP server registry; it finds servers in a `.mcp.json` file in the folder it runs in. So in a WPILib robot project (a folder with `.wpilib/wpilib_preferences.json`), the extension adds a `wpilog-analyzer` entry to that folder's `.mcp.json`. It leaves other servers in the file alone, and doesn't touch a file that isn't valid JSON. One checkbox controls this: **Enable For Claude Code** (`wpilog-mcp.enableForClaudeCode`, on by default). It affects only Claude Code, since Copilot and other VS Code agents get the server from VS Code itself.

There is nothing to set up. Open the robot project in VS Code with the extension installed, start Claude Code there, and approve `wpilog-analyzer` when it asks. Claude Code asks once per project before starting a server from `.mcp.json`, and `/mcp` lists it. This works for Claude Code in VS Code and for the `claude` command in a terminal in that folder.

In a folder that is not a robot project (a folder of logs, say), run **WPILog Analyzer: Add to Claude Code in This Folder** from the Command Palette. The command works even with **Enable For Claude Code** off; while the setting is on, the extension keeps the entry up to date. To stop, turn off **Enable For Claude Code** and delete the `wpilog-analyzer` entry from `.mcp.json`.

Claude Code reads `.mcp.json` only when a session starts; a running session, a new conversation in it, and `/clear` don't pick up a new server. So when the extension adds the entry to a project, it tells you, once per project, that a Claude Code session already running there needs a restart. If the Claude Code extension for VS Code is installed, the notice offers **Reload Window**, which restarts Claude Code's sessions. In a terminal, exit Claude Code and run `claude --continue`, which starts a new session with your conversation. **Don't Show Again** turns the notice off, for those who use only Copilot or other VS Code agents.

The entry does not start a server of its own: it runs the server's bridge (`connect vscode-default`), which joins the shared server and starts it first when it is not running, as when Claude Code runs in a terminal with VS Code closed (see [How It Works](#how-it-works)). As in the [standalone install](../doc/STANDALONE.md), the entry holds only what runs the bridge (Java, heap size, and JAR), the server's name, and the path of its configuration file, which the extension keeps in its storage in the format of the standalone install's `servers.yaml`: the log directories, team number, TBA key, and port. The log directories are your User settings' with each project's own on top (their Workspace settings, relative paths inside each project), for every project with the entry, open or not, so Claude Code in any of them finds all their logs.

When you change a setting, the TBA key, or a project's own settings, the extension rewrites that file and restarts the server, and Claude Code's next request reaches the new one; a Claude Code session whose connection was lost in the restart reconnects when you ask it to in `/mcp`, or in its next session. The entry itself changes only with the Java path or the heap size and is updated the next time the project is opened in VS Code. An entry written by an earlier version, which started a stdio server of its own with a configuration file per project, keeps working with that file until the project is next opened in VS Code, when the entry is rewritten to use the shared server and the file removed.

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
| `wpilog-mcp.useStandaloneServer` | Use the standalone install's `http` server instead of the extension's own, with that install's settings (see [Using the Standalone Server](#using-the-standalone-server)) | off |
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
- in VS Code's storage for the extension (`globalStorage/triplehelixprogramming.wpilog-analyzer`): the server's disk cache (`cache/`, REV log sync results), a copy of the server JAR (`server/`), the server's configuration file (`servers/vscode-default.json`, holding the log directories, the port, and the TBA key), and, under `projects/`, a configuration file for each project whose entry was written by an earlier version and still runs a server of its own;
- the server's own files outside VS Code: its log, PID file, and stop token under `~/.wpilog-mcp/logs/` and `~/.wpilog-mcp/run/` (named `vscode-default`), beside any standalone install's; a server still running exits on its own after the idle time, or at once when ended as a process. With **Use Standalone Server** on, the extension writes only what a `start http` of the standalone install writes (its `http.pid`, token, and log)
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

- Each has its own configuration: the extension's settings in VS Code, and the standalone server's `servers.yaml`. The extension never reads `servers.yaml`, so a server defined there is not one the extension can use, and the extension's server (`vscode-default`) never appears among the standalone install's. Environment variables that the server reads, such as `TBA_API_KEY`, reach both if you have set them.
- Each has its own disk cache (the extension's is in its VS Code storage), so the two never discard each other's cached results, even when their versions differ.
- Copilot and other VS Code agents use the extension's server.
- For Claude Code, the extension adds no second server to a project. If the project's `.mcp.json` already has an entry that runs wpilog-mcp (such as the standalone install's `wpilog`), the extension leaves the file alone. Otherwise it adds its own entry (see [Using It with Claude Code](#using-it-with-claude-code)). If you add a standalone entry to a file that already has the extension's `wpilog-analyzer` entry, delete the `wpilog-analyzer` entry yourself; the extension won't remove it.
- If you registered the standalone server for all projects (`claude mcp add --scope user`), turn off **Enable For Claude Code** and delete the `wpilog-analyzer` entry from any project's `.mcp.json` that already has one. Otherwise Claude Code would start both servers there.

Or make them one: turn on **Use Standalone Server**, and the extension uses the standalone install's server instead of its own.

### Using the Standalone Server

The extension's own server remains the default. With **Use Standalone Server** on, it uses the `http` server of `~/.wpilog-mcp/servers.yaml`, started with the install's launcher (`wpilog-mcp start http`, as the standalone guide's [One Server for Every Client](../doc/STANDALONE.md#one-server-for-every-client) does). VS Code's agents, WPILog Explorer, Claude Code through `connect http`, and the install's other clients share that server, its loaded logs, and its disk cache. **Restart Server** and **Show Server Log** act on it (`~/.wpilog-mcp/logs/http.log`). Legacy `servers.json` is also supported.

If the install is missing, the extension offers **Install**, **Not now**, or **Open Settings**. Install runs the bundled server JAR's `install --json` verb with the extension's Java and heap settings, under a progress notification. It reports the installed version and the folder to add to PATH, remembers that it installed the server, then starts it. Not now or dismissing the offer lasts until the next activation.

A new `servers.yaml` is seeded with your **User** Log Directory, Additional Log Directories, and Team Number. Relative User directories resolve inside the window's open local projects, as they do for the extension's own server; without a project they are left out. Workspace overrides are not seeded. With no directories or team set, the install keeps its defaults (`~/riologs`, no team). The TBA key is never passed to the installer or copied out of secret storage; configure the standalone server's key separately. Existing YAML or legacy JSON is never overwritten.

After installation that file controls the server's directories, team, TBA key, disk cache, and idle exit. Project directories do not join it automatically. The launcher's Java lookup and `WPILOG_MAX_HEAP` control its Java and heap; the extension's Java and heap settings apply to the installer, not to the standalone daemon. A file outside the configured directories is refused with the server's reason.

On activation, if the setting is on **or** this extension previously installed the server, an older launcher is updated from the bundled JAR, without configuration seeds. The automatic check compares the version numbers (`1.10.0` follows `1.9.9`); it ignores development suffixes. The install verb also compares suffixes and never downgrades, including when a newer install appeared after the check. The output records before and after versions; an update reports once that the next start replaces the running server. Installing does not stop a daemon. An equal or newer hand-installed launcher is kept. If you deleted a previously installed layout and the setting is off, activation leaves it absent.

**WPILog Analyzer: Install Standalone Server**, in the Command Palette and linked from the setting, runs the install whenever you ask, even with the setting off, then offers to turn it on. Use this command to install a newer development suffix too. Turning the setting off returns to the extension's own server; the standalone server keeps running until stopped or its idle time passes, and the extension's own server exits after its idle time when you switch to standalone.

## More Information

- [Main README](../README.md): project overview, the tools, and links to the rest of the documentation
- [TOOLS.md](../doc/TOOLS.md): complete tool reference
- [wpilog-mcp on GitHub](https://github.com/TripleHelixProgramming/wpilog-mcp)
