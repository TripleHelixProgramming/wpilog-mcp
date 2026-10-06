<table>
<tr>
<td width="180" valign="top" align="center">
  <!-- The leading <br> balances the h1's top margin, so the icon sits midway between the cell's top edge and the title. -->
  <p align="center"><br><img src="images/icon.png" alt="WPILog Analyzer icon" width="140" align="top"></p>
  <h1 align="center">WPILog Analyzer</h1>
</td>
<td valign="middle">

AI-powered FRC robot log analysis for VS Code. The extension analyzes `.wpilog` telemetry files from the roboRIO to help diagnose brownouts, CAN errors, swerve drive issues, loop timing problems, and more.

The extension gives an AI assistant tools to analyze robot logs, and lets you explore the same data without an assistant. Copilot and other VS Code agents use the shared server directly; Claude Code uses its bridge. WPILog Explorer provides the Logs and Entries views and the log editor.

</td>
</tr>
</table>

## Quick Start

1. Install **WPILog Analyzer** and accept its offer to install the server.
2. Put your `.wpilog` files in `~/riologs`.
3. Open your robot project in VS Code. Click **WPILog Explorer** in the activity bar to browse your logs, or ask your assistant “What logs are available?”
4. With Claude Code, approve `wpilog-analyzer` when asked. Restart an existing Claude Code session after registration.

Most users need no configuration changes. Keep the robot project open: the assistant can then read the code that gives logged signals their meaning. A team number and a [Blue Alliance key](#the-blue-alliance-api-key) add match data; neither is required to read logs.

## Requirements

- Java 17 or later; the WPILib toolkit includes a suitable JDK.
- VS Code 1.101 or later. An MCP-capable assistant is optional; the explorer works without one.

## Install

Find **WPILog Analyzer** in VS Code's Extensions view, or on the [Marketplace](https://marketplace.visualstudio.com/items?itemName=TripleHelixProgramming.wpilog-analyzer). For a test build, download its `.vsix` from [Releases](https://github.com/TripleHelixProgramming/wpilog-mcp/releases) and choose **Install from VSIX…** in the Extensions view, then reload the window.

The [standalone installer](../doc/STANDALONE.md#trying-a-pre-release) can install both the server and the matching extension from one release. WPILib VS Code and system VS Code keep separate extension directories; install into the editor you use.

## How It Works

On activation, the extension starts the standalone install's `http` server. VS Code's agents, the explorer, Claude Code, and other local clients share its loaded logs and cache. The launcher starts it on demand and joins it when it is already running; no operating-system service is installed.

Each window registers its User log directories and open projects' directories with the server. These are **leases**: every client can read them while the window's MCP session lives. Settings, key, and folder changes replace the lease without restarting the server. Closing a window releases its lease; another window or a terminal bridge can keep the same directories available. The client re-registers after a daemon restart and keeps its session alive while the window is open.

The server's permanent settings live in `~/.wpilog-mcp/servers.yaml`. **VS Code's Settings UI does not read or edit that file.** It supplies the window's directories and team through the lease. Edit YAML for permanent directories, port, cache, or idle policy. The installer creates an absent configuration and preserves an existing one.

**WPILog Analyzer: Show Server Log** opens `~/.wpilog-mcp/logs/http.log`; **Restart Server** restarts the shared daemon. With `idle_exit_minutes` set in YAML, it exits when unused; the installed default is `0` (keep running). An active import also prevents idle exit.

## Exploring Logs

The **Logs** view shows each directory's origin (`configured` or `leased`) and team when known. Plain directories group logs by event and date; stores group them by robot and session. Filter by name, event, or match; click a log to open it, or right-click to reveal it or copy its path. Double-clicking a `.wpilog` file opens the same editor. A file outside the server's directories is refused; add its directory in Settings or import it through a store's inbox.

The editor shows the time range, entries, sample counts, and any truncation note. The **Entries** view follows the active log. Expand a struct or array to its numeric fields and elements, then click to plot; **Plot Every Element** plots up to sixteen at once.

- **Plots:** drag to zoom, roll the wheel to zoom about the cursor, shift-wheel or arrow keys to pan, and double-click or Home to reset. Panes share a cursor and readout. Change-only signals use steps. Up to four million samples per series are loaded at once; longer series use min/max buckets with a mean line until zoomed in. Chips show the server's decode warnings and statistics for the visible window.
- **Timeline:** Driver Station mode segments and events come from the log. Drag a region to select a time window.
- **Console:** filter text by pattern, level, and window. Repeats collapse with their count; clicking a line moves the shared cursor.
- **Field:** the logged pose is drawn on the season's field outline, colored by time. The server's signal resolver selects a documented convention or asks you to choose among candidates.
- **REV:** signals appear by bus and device with their synchronization method, confidence, and offset. They plot on the wpilog's clock; a bus that could not be synchronized is explained and not plotted.

The extension host calls the same tools as the assistant and fetches samples through the [data endpoint](../doc/STANDALONE.md#the-data-endpoint). The webview draws those results and opens no network connection. Pit-server views, notebooks, and assistant chart links remain [planned work](../doc/EXPLORER_PLAN.md).

## Organizing Your Logs

When a log directory has files to organize, WPILog Explorer offers **Organize**, **Not now**, or **Never for this folder**. Nothing moves until you choose Organize and complete the choices. Not now (or dismissing the offer) lasts until the next activation; Never is remembered for that folder. **WPILog Explorer: Organize Logs…**, also in the Logs view's title bar, can ask again at any time. With several directories it first asks which one. A store with unmanaged files gets the same offer, with their count and the first few names.

Choose **Move** (the default) to organize in place, or **Copy** to keep the originals too. Then choose a known robot by name and serial, **New robot…**, or **Decide later**. Names allow letters, digits, dots, hyphens, and underscores, and must be portable filenames. The log's own identity wins when present; without one, Decide later leaves the files under Unassigned. The server imports the batch while the notification shows the phase, file, and completed/total counts. Afterwards it reports imported, present, unassigned, and refused counts; each file's result and the server's reasons go to the **WPILog Analyzer** output channel. A shared serial in two robot directories is reported with both paths for a later explicit merge.

A store's Logs tree shows robots (with their identity basis in the tooltip), dates, sessions with their times and known event/match, then each wpilog and its correlated REV companions. Clicking a REV companion opens its wpilog, where the REV pane shows its signals. **Unassigned**, **Inbox**, and **Unmanaged** come last with import actions; Inbox files show waiting, importing, or refused and its reason. **Assign to Robot…** on an unassigned file moves it into the chosen robot's history through the server. Plain folders keep their event/date groups. A window containing both shows a store node and a directory node; Import on a plain file or directory copies it into an existing store.

HTTP import sources must be inside the server's configured log directories. For a USB stick or another outside path, copy files into the store's **inbox/**, or use `wpilog-mcp import --robot practice /path/to/usb/logs` with the standalone server's name/configuration as needed. The command publishes a complete inbox batch, carrying the robot choice in `batch.json`; the daemon imports it once it stops growing and writes each outcome in `inbox/imported.log`. See the [standalone import guide](../doc/STANDALONE.md#importing-logs). Refresh the Logs view to see automatic inbox imports.

Direct imports keep original paths and filenames in the session manifest's file provenance (or `unassigned/<hash>/import.json` before assignment); the listing reports moved paths for seven days. Inbox receipts keep the inbox path and its destination. Duplicate content is reported as present and its source is left in place. Organizing runs in the same server the viewer already uses, whether owned by the extension or the standalone install.

## Using It with Claude Code

With **Enable For Claude Code** on (the default), the extension runs `claude mcp add --scope user wpilog-analyzer -- <launcher> connect http` once when it finds the Claude CLI. On Windows it registers the batch launcher through `cmd /c`. If the CLI is unavailable, it shows the exact command with **Copy Command**. **WPILog Analyzer: Register with Claude Code** repeats setup on demand. Existing Claude Code sessions need restarting after registration.

No project `.mcp.json` entry is written. The user-scope bridge works from a terminal with VS Code closed: it starts the same server from the home configuration and leases directories from flags or the current project's `.wpilog-mcp.yaml`.

For a project with its own Workspace directories, the extension offers once to write that YAML, with an option to add it to `.gitignore`. Nothing is written without your choice, and an existing or tracked file is left alone. The file contains only directories and an optional team:

```yaml
logdir: ["logs", "sim/logs"]
team: 1234
```

Paths are relative to the bridge's working directory. This file is yours afterwards; changing VS Code settings does not rewrite it. Its `servers` section, if any, cannot configure the shared daemon: `connect` ignores it and records that fact in the server log. See [Directories by lease](../doc/STANDALONE.md#directories-by-lease).

On upgrade, recognized extension-owned entries are removed from untracked or ignored `.mcp.json` files as projects open. Tracked, custom, and unrecognized entries are left with a note in the output. The old `vscode-default` daemon is stopped once, then its private `servers/` and `projects/` settings are removed. A failed stop keeps those settings and is retried next activation.

## Settings

User directories remain available for the window; open projects add their own directories and team. A project's Workspace settings override inherited settings for that project, including replacing its additional-directory list. Relative paths resolve inside each applicable project; with no project open they are left out. `~/` expands to your home on every platform, and forward slashes work on Windows too.

| Setting | Purpose | Default |
|---|---|---|
| `wpilog-mcp.logDirectory` | Main directory leased by the window | auto-detect |
| `wpilog-mcp.additionalLogDirectories` | More directories; relative paths can name project simulation logs | none |
| `wpilog-mcp.teamNumber` | Fallback team for logs that record none | unset |
| `wpilog-mcp.tbaApiKey` | Write-only field; moved to secret storage and cleared | empty |
| `wpilog-mcp.enableForClaudeCode` | User-scope bridge registration and project YAML offers | on |
| `wpilog-mcp.javaPath` | Java executable for the bundled installer | auto-detect |
| `wpilog-mcp.wpiLibYear` | WPILib JDK year for the installer | latest installed |
| `wpilog-mcp.maxHeap` | Maximum JVM heap for the server, applied when the extension starts or restarts it, and for the installer | `4g` |

`useStandaloneServer` and `idleExitMinutes` are removed. Every window uses the standalone install; idle policy is `idle_exit_minutes` in YAML. The installed launcher's own Java lookup runs the daemon, not the installer's `javaPath` and `wpiLibYear`. The heap is the extension's `maxHeap` when the extension starts the daemon, passed to the launcher as `WPILOG_MAX_HEAP`; a daemon another client started keeps the heap it was given until it is restarted.

## The Blue Alliance API Key

Get a free read key from [The Blue Alliance](https://www.thebluealliance.com/account). Use **WPILog Analyzer: Set The Blue Alliance API Key** for a hidden input, or paste it into the User **Tba Api Key** setting. After typing stops, the extension moves it into secret storage and clears the setting; Settings Sync excludes that field.

The key goes to the server only through the window's loopback session, stays in memory there, and is never put on a command line or written to server/project configuration. **Clear The Blue Alliance API Key** removes the stored key and this window's registration. Another live registration or a key you put in `servers.yaml` can still apply. To use TBA from a terminal with VS Code closed, configure `tba_key` in the home YAML or `TBA_API_KEY` in the environment.

A key found in Workspace settings is cleared too, but never replaces an already stored key: it may belong to a teammate. If a key was ever committed or shared, revoke it and enter a new one. Custom and tracked legacy `.mcp.json` entries are not scrubbed automatically; review the migration note in the output.

## The Standalone Install Is the Server

The extension and [standalone clients](../doc/STANDALONE.md) use the same install at `~/.wpilog-mcp`. No ownership setting or second daemon is needed.

When missing, the extension offers **Install**, **Not now**, or **Open Settings**. Install runs its bundled JAR under a progress notification, reports the version and PATH hint, then starts the server. Not now lasts until the next activation. **Install Standalone Server** runs it on demand.

A new configuration is seeded with absolute User log directories and team only. Relative and Workspace directories stay leases; the TBA key is never seeded. Without seeds the default is `~/riologs`, with no team. Existing YAML or legacy `servers.json` is preserved.

On activation, an older launcher is updated from the bundled JAR without configuration seeds. The automatic comparison uses version numbers and ignores development suffixes; use the install command to advance a suffix. The JAR's own comparison includes suffixes and never downgrades, even if a newer install appeared during the check. Installation leaves a running daemon alone; the next start replaces a different running version.

For a complete reinstall with settings preserved, use the standalone installer's explicit [`--refresh`](../doc/STANDALONE.md#replacing-an-old-install). Extension updates never refresh or bootstrap the extension itself; the Marketplace updates the extension.

## Auto-Detection

The installer uses the configured Java path, the running WPILib VS Code's JDK or selected/latest WPILib JDK, then `JAVA_HOME`, then Java 17+ on PATH. The installed launcher independently uses the newest WPILib JDK, `JAVA_HOME`, then PATH.

With no User directory settings, the window leases the first existing directory among `~/riologs`, `~/wpilib/logs`, and `~/Documents/FRC/logs`. The permanent YAML's directories also remain visible. Explicit leased directories must already exist; the server names any it refuses.

## Upgrading

Marketplace installs update automatically. Install a newer `.vsix` over an older one, then reload VS Code. The server update keeps your configuration; no reconfiguration is needed.

## Uninstalling

Before uninstalling, use **Clear The Blue Alliance API Key** if you want it removed from secret storage. Uninstall the extension in VS Code's Extensions view. Its leases end, but the shared install and Claude Code's user registration remain useful outside VS Code. Remove that registration and [uninstall the standalone server](../doc/STANDALONE.md#uninstalling) separately if no client needs them. Project YAML files, accepted `.gitignore` additions, and VS Code settings remain yours. Old extension-storage `server/` and `cache/` folders can be deleted when no retained legacy entry needs them.

## Troubleshooting

- **Server or registration failed:** select **WPILog Analyzer** in Output, then try **Show Server Log**. Errors name refused directories; create the directory or correct its setting. **Restart Server** restarts the shared daemon manually.
- **Port occupied:** change `servers.http.port` in `servers.yaml`, then restart. The extension reads the actual port from the daemon's PID file.
- **Java missing:** install the WPILib JDK or configure the installer Java path. The launcher also needs a JDK through WPILib, `JAVA_HOME`, or PATH.
- **Claude Code lacks tools:** run **Register with Claude Code**, restart the Claude session, and check `/mcp` for approval. A retained project entry can override the user registration; the output explains why it was left.
- **Large logs exhaust memory:** set `wpilog-mcp.maxHeap` to `8g` and run **WPILog Analyzer: Restart Server**. For a daemon another client starts, set `WPILOG_MAX_HEAP=8g` in that client's environment instead.

## More Information

- [Main README](../README.md): overview and tools
- [Standalone guide](../doc/STANDALONE.md): configuration, installation, and other clients
- [Tool reference](../doc/TOOLS.md): parameters and result contracts
