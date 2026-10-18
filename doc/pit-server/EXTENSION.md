# Pit server: Extension integration

> **Experimental:** the pit server has limited controlled-shop evidence. These documents are
> retained for development and controlled shop evaluation, not as a recommendation for team use.
> Automated tests and simulation do not establish reliability on a real robot.

## Pit server and offline mirror

Set `wpilog-mcp.pitServerUrl` to the pit server's HTTP MCP URL, such as `http://pit:2363/mcp`.
The server must be bound to the team network for another laptop to reach it; this exposes its
logs to anyone who can reach the port. VS Code's agents see **WPILog Pit Server** beside
**WPILog Analyzer**. The remote client sends no local directory lease or TBA key.

Dashboards and AdvantageScope can use the pit server's optional read-only NT4 gateway on its
separate port (default 5810 when enabled). See the standalone guide's
[NT4 gateway configuration](STANDALONE.md#nt4-gateway-for-dashboards).
It carries no authentication and belongs on the private network; the HTTP proxy login does not
protect it. Writes are ignored. Checking a real dashboard or AdvantageScope against a robot
remains the user's manual step.

This laptop also records live data from that gateway when a pit URL is set. The local server
connects to the gateway on the pit's host, port 5810 by default, and records its NetworkTables
into a folder under the extension's storage, kept apart from the mirror's. Live views and the
live tools then read this laptop's own files. The recording has the robot's timestamps, with
gaps wherever this laptop's network dropped. The pit server must have its gateway enabled and
reachable on the team's network. The Output channel reports the live source's state each time it
is set. Turn it off with `wpilog-mcp.live.enabled`. Changing the pit URL, live settings or
mirror settings queues the complete local configuration operations, including stopping the old
capture. Startup uses that same queue; a later change waits for the earlier operation to finish.

The Logs view gains **This laptop** and **Pit server** roots. Pit sessions are newest first,
with the robot, event/match, open state, and whether the mirror holds them. Opening one uses
its exact path through the pit server. **Follow live session** polls once a second: the window
tracks the latest time and visible series append from their last timestamp. Console follow
pages the new text and keeps the latest 500 matches without collapsing repeats. Turning follow
off restores ordinary browsing. No viewer connects to the robot.

The mirror starts by default when a URL is set. The local Java server copies the last 14 days,
plus whole named events and pinned sessions, under a 20 GB payload cap. Pins are never evicted;
a copy missing from the origin is retained and reported even if that exceeds the cap. Resuming
checks the held prefix first. The mirror copies the logs the pit pulled or was given, not its
captures: this laptop records live data itself. Copies have the same verified bytes as the origin.
The mirror is read-only to imports and is never offered for organizing.

**Open Source Overview** on a pit file reads only this laptop's files. A file the pit pulled or
was given opens from the mirror's copy; before the mirror has copied it, a message says so with
the mirror's progress (files and bytes remaining, or the last synchronization and why it may
not have copied the file) and offers **Mirror Status** or **Pin Session…**. The pit's capture
stays on the pit: its overview opens this laptop's own recording of that session, a capture in
one of this laptop's stores of the same robot whose session overlaps the pit's (the longest
overlap when there are several), or says there is none. A capture the mirror copied in
0.10.0-dev1 to dev4 opens as that copy, labeled as no longer refreshed. On a REV log the overview
is that of the log it is listed under.

For a recording still open on the laptop, the overview uses the current member named by its
store manifest, including after file rollovers. If that member is missing from the listing,
refresh Logs; update the local server if its listing does not report `session.open_capture`.
An editor left open from a different pit connection refuses before using the configured pit's
catalog. Restore `wpilog-mcp.pitServerUrl` to that editor's server, or open a log from the
currently configured pit. A connection change during lookup also refuses.

The status bar shows **Mirror synchronized**, the remaining file count and size, or **Mirror
offline** with the last-sync age. Click it for the mirror actions. When the pit server is
unreachable, a remote editor can use the mirrored file with the same origin, session id and
relative path; the editor says which copy it is reading and when it synchronized. The local
Logs root also remains available offline. A failed verification or retention exception appears
in the status tooltip. Settings apply through the local server without rewriting home YAML.

| Command | What it does |
|---|---|
| `wpilog-mcp.mirrorActions` | Open the status bar's action picker |
| `wpilog-mcp.pinSession` | Pick a recent session to retain outside scope and cap |
| `wpilog-mcp.unpinSession` | Pick a pinned session to return to normal retention |
| `wpilog-mcp.syncNow` | Start a mirror pass immediately |
| `wpilog-mcp.openMirrorFolder` | Reveal the mirror directory in the OS file explorer |
| `wpilog-mcp.syncFromLaptop` | Pull missing files into a local writable store from another laptop |
| `wpilog-mcp.uploadToPitServer` | Select laptop files and upload verified copies to the pit store |
| `wpilog-mcp.registerPitWithClaudeCode` | Register the pit URL as the second user-scope Claude server |

**Upload Logs to Pit Server** uses `wpilog-mcp.pitServerUrl`. Pick files (a USB drive is
fine) and, if the pit server has several stores, a store id. Files stream one at a time;
the importer verifies their hash and content, groups them by robot and session, and reports
imports, duplicates, unassigned files and refusals in WPILog Analyzer output. Originals stay
on the laptop. The pit server must be bound to the network (or reachable through its proxy).
A lost connection does not silently resubmit; retrying explicitly recognizes a completed copy
by its hash. A mirror cannot receive uploads. With a proxy, protect `/store/import` first.

**Sync from Laptop** needs no pit-server setting. Choose a local store, enter the peer's
`host:port` once, then choose its remembered URL on later runs. The peer must bind its server
to the network. The local store job reports sessions created, copied files and bytes, files
already present, conflicts, refusals and interruptions; full details go to **WPILog Analyzer**
output. Nothing is deleted, and mirrors cannot be sources or destinations of peer sync.

The extension offers Claude Code registration for the pit URL at user scope using the installed
bridge: `claude mcp add --scope user wpilog-pit -- <launcher> connect --url <url>` (through
`cmd /c` on Windows). No project `.mcp.json` is written. The copy-command fallback and the
on-demand command remain available; restart existing Claude sessions after registration.
If another editor or profile already registered the same user-scope launcher and URL bridge,
the extension verifies it with `claude mcp get wpilog-pit`, remembers it, and writes one line
to Output without warning. A different entry gets a conflict warning and manual instructions
in **WPILog Analyzer** Output. Inspect it with `claude mcp get wpilog-pit`; only if you choose
to replace an obsolete user entry, run `claude mcp remove wpilog-pit --scope user`, then the
registration command. A project or local entry belongs to its own scope. **Copy Command**
copies only the registration command. The extension never removes or replaces an entry.

### Live tools and a pit proxy password

A capture-enabled pit server gives agents `list_sessions` (current/recent sessions, recorder
costs and matched imports), `get_latest_values` (named publications with robot timestamps and
ages), and `wait_for_change` (first publication, at most 30 seconds). Ordinary log tools use the
capture path returned in `inputs.session`; the local mirror has the same finished file answers.

**Set Pit Proxy Credential** (`wpilog-mcp.setPitProxyCredential`) asks for the reverse proxy's
username and masked password. The credential stays in SecretStorage by HTTP origin, never in
settings or `.mcp.json`. MCP, plots, uploads and mirror reads use it for that origin only, with
no redirects. Use HTTPS across the network. **Clear Pit Proxy Credential**
(`wpilog-mcp.clearPitProxyCredential`) removes it and its local lease.

After setting or clearing one, run **Register Pit Server with Claude Code** again. With a
credential, that registration uses a secret-free URL through the local server; it needs the
extension's window open to hold the in-memory credential lease. Re-register if the local server
port changes. After VS Code closes, analyze the offline mirror through its `servers.yaml`
mirror block (preferred for regular terminal use) or an explicit bridge `--logdir`; a window's
folder lease alone does not survive. A proxy password is not put in permanent YAML.

### Sampled dashboards

The local and pit HTTP servers also serve `/metrics`. The
[standalone metrics guide](STANDALONE.md#metrics-and-a-starter-dashboard) provides
Prometheus/Grafana Compose files and a starter dashboard. Configure `metrics` in the server's
YAML; this adds no extension setting. Scrapes show latest values and ages, while the capture
and the explorer retain the record between scrapes.

## Settings

| Setting | Purpose | Default |
|---|---|---|
| `wpilog-mcp.pitServerUrl` | Second MCP server and remote Logs root | empty |
| `wpilog-mcp.mirror.enabled` | Enable mirroring when a pit URL is set | on with URL |
| `wpilog-mcp.mirror.folder` | Absolute mirror directory | extension storage `/mirror` |
| `wpilog-mcp.mirror.days` | Recent-session window | `14` |
| `wpilog-mcp.mirror.maxSizeGb` | Payload cap, decimal GB | `20` |
| `wpilog-mcp.mirror.robots` | Serial filter; empty includes all | none |
| `wpilog-mcp.mirror.events` | Exact event names to keep beyond the window | none |
| `wpilog-mcp.mirror.intervalSec` | Seconds between attempts | `30` |
| `wpilog-mcp.live.enabled` | Record the pit gateway's NetworkTables on this laptop when a pit URL is set | on with URL |
| `wpilog-mcp.live.folder` | Absolute folder for live recordings, apart from the mirror's | extension storage `/live` |
| `wpilog-mcp.live.gatewayPort` | The pit gateway's port, on the pit's host | `5810` |

## Terminal use with VS Code closed

With VS Code closed, the extension's directory lease expires. The mirror remains on disk,
but a terminal's Claude Code can read it only when the local server admits its folder through
a `mirror` block in `servers.yaml`, or the bridge registers it with `--logdir`.
For regular offline analysis away from VS Code, use the `mirror` block in `servers.yaml`
with the same folder and origin as the extension: it keeps the folder available and continues
syncing when the origin answers. Use bridge `--logdir /absolute/path/to/mirror` for temporary
read access without configuring background mirroring. See the [standalone mirror configuration](STANDALONE.md#the-mirror).
