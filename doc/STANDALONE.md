# The Standalone Server

The standalone install is the server used by the VS Code extension and by clients outside VS Code: Claude Code, Claude Desktop, Gemini, or any other MCP client. Most users put logs in `~/riologs` and let their client start the shared server automatically. The extension offers installation itself; the scripts below also work without VS Code.

The server is designed for and tested with Claude. Other MCP clients work too, but the depth and quality of the analysis depend on the model.

## Requirements

- JDK 17 or newer (the WPILib JDK is recommended)
- Nothing else: the WPILib libraries are bundled in the JAR

The launcher uses the newest WPILib JDK it finds, then `JAVA_HOME`, then `java` on your `PATH`. Building with `./gradlew` uses `JAVA_HOME` or `java` on your `PATH`. WPILib puts its JDK here:
- macOS and Linux: `~/wpilib/2026/jdk/bin/java`
- Windows: `C:\Users\Public\wpilib\2026\jdk\bin\java.exe`

## Install

Run the installer for your system to get the latest full release. In a terminal it prompts for log directories, a team number, and whether to install the matching VS Code extension. Existing server settings are kept, without asking you to enter them again. A piped or scripted run does not prompt and takes the defaults; `--interactive` asks anyway, and `--non-interactive` never asks.

macOS and Linux:
```bash
curl -fsSL https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.sh | sh
```

Windows (PowerShell):
```powershell
irm https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.ps1 | iex
```

To build the current code instead, clone the repository and run the Gradle installer:
```bash
git clone https://github.com/TripleHelixProgramming/wpilog-mcp.git
cd wpilog-mcp
./gradlew install
```

Both release installers download a temporary JAR and run its `install` verb, which owns the layout and prints the PATH hint. If the JAR rejects the verb with `Unknown option`, a basic install falls back to that release’s own tagged installer; actual installation failures are reported without falling back. Older releases cannot honor the new installer options: choose a release carrying the verb for those. The output names the path taken. The Gradle task runs the same verb with `--force`, so a development build becomes current even when its version is equal or older. To install a JAR you already have:

```bash
java -jar wpilog-mcp-<version>-all.jar install
java -jar wpilog-mcp-<version>-all.jar install --install-dir /path/to/install --logdir /path/to/logs --logdir /path/to/archive --team 2363 --json
```

`--install-dir` defaults to `~/.wpilog-mcp`. Each `--logdir` and `--team` seeds a new configuration only; existing `servers.yaml` or legacy `servers.json` is preserved. The TBA key is always left commented. `--json` prints one object with `install_dir`, `installed_version`, `launcher_version_before` (null when none), `launcher_version_after`, `repointed`, `config_created`, `config_path`, `launcher_path`, and `path_hint` (the folder to add to PATH, or null). Keeping a newer or equal current launcher is success; a write failure exits nonzero with its reason. Without `--json`, these results are printed as text.

The installer checks every destination against the canonical install directory before writing. An existing `bin`, `jars`, lock, configuration, JAR, or launcher that resolves outside it is refused, with the path named. The Unix current-launcher symlink is allowed when its target stays inside the install; a missing target inside it is treated as an older launcher and replaced.

### Trying a pre-release

A tag selects an exact release; `--pre-release` selects the newest published release, including pre-releases. Add `--with-extension` to install both halves from that release:

```bash
curl -fsSL https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.sh | sh -s -- --tag v0.9.2-dev3 --with-extension
```

In PowerShell, download the script to a temporary file, then run it with the same flags:

```powershell
$installer = Join-Path $env:TEMP 'wpilog-install.ps1'
try {
    Invoke-WebRequest https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.ps1 -OutFile $installer
    & $installer --pre-release --with-extension
} finally { Remove-Item $installer -ErrorAction SilentlyContinue }
```

`--with-extension` downloads the matching `.vsix` and passes `install --with-extension --vsix <file>` to the JAR. The JAR requires both flags and finds the current year's WPILib VS Code, then `code` on PATH. `--without-extension` skips the interactive offer. The extension never passes these flags: after bootstrap, it updates the server and the Marketplace updates the extension to the next full release.

The default layout is:
```
~/.wpilog-mcp/
├── jars/wpilog-mcp-{version}.jar        # one JAR per installed version
├── bin/
│   ├── wpilog-mcp-{version}[.bat]       # that version's launcher
│   └── wpilog-mcp[.bat]                 # the current version: a symlink (macOS, Linux) or a copy (Windows)
└── servers.yaml                         # server configurations
```

Starting an HTTP server with `start` (see [HTTP Transport](#http-transport)) adds `run/`, holding the server's process ID and a lock file that makes starts take turns, and `logs/`, holding its log.

MCP clients start the launcher by its full path, so you need it on your `PATH` only to run `wpilog-mcp` in a terminal. On macOS and Linux, add this to your shell profile (`~/.zshrc` or `~/.bashrc`):
```bash
export PATH="${HOME}/.wpilog-mcp/bin:$PATH"
```
On Windows, the installer prints the folder to add to your `Path`.

## Configuration

The defaults need no editing: logs in `~/riologs`, one shared `http` server on loopback port 2363, and no team assumed. Edit `~/.wpilog-mcp/servers.yaml` for permanent directories, team, port, cache, or idle policy. The installer creates it only when absent; `install --team` and `--logdir` seed a new file. VS Code’s Settings UI supplies temporary session leases and does not read or edit this YAML. Restart the daemon after editing it (`wpilog-mcp stop http`, then reconnect).

```yaml
# Your FRC team number, for The Blue Alliance match data when a log does not record one.
# Remove the # and put in your number:
# team: 1234

# Directory containing .wpilog files downloaded from the roboRIO
logdir: ~/riologs
# Or several directories, listed together (for example an archive drive, or logs another
# team published):
# logdir:
#   - ~/riologs
#   - /Volumes/LOGS/archive

# The Blue Alliance API key (get one at https://www.thebluealliance.com/account).
# Remove the # and put in your key, or set the TBA_API_KEY environment variable instead:
# tba_key: your-key-here

servers:

  # Default: stdio transport for MCP clients (Claude Desktop, etc.)
  default:
    transport: stdio

  # HTTP transport for browser-based or multi-client access
  http:
    transport: http
    port: 2363
```

Top-level settings such as `team` and `logdir` apply to every server, and a server's own value overrides the top-level one.

The installer's file also has a `stresstest` server, which only the project's stress tests use. It has no settings of its own, so it takes the ones at the top, the TBA key among them. Put the key at the top level, not under one server: a key under `stresstest` reaches no other server.

### Config Fields

| Field | Description | Default |
|-------|-------------|---------|
| `logdir` | Directory of log files (subdirectories are searched too), or a list of directories | None; the installed file sets `~/riologs`. Clients can add directories for a session (see [Directories by lease](#directories-by-lease)) |
| `team` | Your team number, for logs that do not record one | None |
| `tba_key` | The Blue Alliance API key | `TBA_API_KEY` from the environment |
| `transport` | `stdio` or `http` | `stdio` |
| `port` | HTTP port | `2363` |
| `diskcachedir` | Directory for the persistent disk cache | `WPILOG_DISK_CACHE_DIR` from the environment, else the system's default (below) |
| `diskcachesize` | Maximum disk cache size, in MB | `8192` |
| `diskcachedisable` | Turn off the persistent disk cache | `false` |
| `exportdir` | Directory for CSV exports | `wpilog-export` in the system's temporary directory |
| `scandepth` | How many directory levels to search for `.wpilog` and `.revlog` files | `5` |
| `idle_exit_minutes` | Minutes without a session, recent MCP request, or active import before a background HTTP server exits; `0` keeps it running. Applies whether a person or a client started it | `0` |
| `debug` | Debug logging | `false` |

`logdir`, `tba_key`, `diskcachedir`, and `exportdir` can include environment variables, written `${NAME}`. A variable that is not set is left as written, and the server warns about it at startup; in `tba_key` the key then counts as not set.

In `logdir`, `diskcachedir`, and `exportdir`, a path starting with `~/` is in your home folder on macOS, Linux, and Windows alike. Forward slashes work in paths on every system, so prefer them (`~/riologs`, `/Volumes/LOGS/archive`, `D:/frc-logs`).

The default disk cache directory is `~/Library/Application Support/wpilog-mcp/cache` on macOS, `wpilog-mcp/cache` under `%LOCALAPPDATA%` on Windows, and `wpilog-mcp/cache` under `$XDG_DATA_HOME` (or `~/.local/share`) on Linux.

### Pit server

Add a `capture` section to a named HTTP server, then run `wpilog-mcp start pit`:

```yaml
servers:
  pit:
    transport: http
    idle_exit_minutes: 0
    capture:
      robot: {team: 2363}
      store: ~/pit-store
      period_sec: 0.01
      exclude: []
      thin: {}
      hot_window_sec: 600
```

| Key | Meaning and default |
|-----|---------------------|
| `capture.robot` | Required: exactly one address selector below |
| `capture.robot.team` | Team number: try the roboRIO mDNS name, then `10.TE.AM.2`; reconnect starts at the last successful address |
| `capture.robot.usb` | `true` selects the USB tether, `172.22.11.2` |
| `capture.robot.host` | An explicit host name or IP address |
| `capture.robot.port` | NT4 port, default `5810` |
| `capture.store` | Required directory, also added to this server's log directories; supports `~/` and `${NAME}` |
| `capture.period_sec` | Subscription period in seconds, default `0.01`; every change is requested |
| `capture.exclude` | List of topic prefixes to omit, default `[]` |
| `capture.thin` | Map of topic prefixes to positive periods in seconds, default `{}`; longest prefix wins, exclusion takes precedence |
| `capture.hot_window_sec` | Values retained in memory, default `600` seconds; `0` reads every value from the capture file |

The whole capture block can be inherited from `defaults`; a server's block replaces it.
Unknown capture keys and invalid values name the key in the startup error. Capture requires
`transport: http` and `idle_exit_minutes: 0` (the default). A robot that is off is normal: HTTP
starts immediately and the client retries indefinitely. A server without `capture` behaves as before.

Captures are ordinary `.wpilog` files under
`robots/address-<address>/sessions/<UTC-date>/<HHmmss>Z/capture.wpilog`. A new robot clock starts
a new session; a continuing clock resumes after a connection loss. Event and match facts appear
in the store listing immediately. The directory gains those names when it can be renamed, or
after close on Windows. While recording, the manifest marks the capture open; after close it
records the SHA-256 and final size. Excluded or thinned topics are a deliberate reduction in
capture fidelity; thinning is recorded in entry metadata. Topic costs are logged every five minutes.

### Several Log Directories

`logdir` takes one directory or a list. `list_available_logs` lists the logs of every directory together, newest first, and names the directories in `log_directories`; a log reached from two directories (one inside the other, or the same directory under two names) is listed once. Tools can load logs from any of the directories, and from nowhere else.

- A directory that cannot be read (a drive not mounted, no permission) does not stop the others: the listing is `partial` and names it in `skipped` with the reason. The server warns at startup about a directory that does not exist yet.
- REV logs are matched to a wpilog only within the configured directory that holds it (and the wpilog's own folder). The other directories are not searched, so a REV log another team recorded at the same event, which would match by time, is never synchronized with your log.
- A server's own `logdir` replaces the top-level list rather than adding to it.
- On the command line and in `WPILOG_DIR`, see [Command-Line Flags](#command-line-flags).
- `scandepth` counts from each directory.

### Named Server Configurations

Advanced use only: the normal shared server is `http`. With no arguments, `wpilog-mcp` starts the separate stdio `default` server; `start <name>` selects a named configuration:

```bash
wpilog-mcp                                   # the "default" server
wpilog-mcp start http                        # the "http" server
wpilog-mcp start default --config ~/frc/wpilog.yaml
```

The server reads the first configuration file it finds:
1. The file given with `--config <path>`
2. `.wpilog-mcp.yaml` in the current directory
3. `.wpilog-mcp.json` in the current directory
4. `~/.wpilog-mcp/servers.yaml`
5. `~/.wpilog-mcp/servers.json` (older installs)

A JSON file uses the same keys as the YAML one. This project-first order applies to `start` and the bare launcher. `connect` joins a shared server: its server configuration comes only from `--config` or the home `servers.yaml` / `servers.json`, never a project file. Separately, it reads only top-level `logdir` and `team` from the working directory's `.wpilog-mcp.yaml` as a temporary directory lease. A `servers` section there is ignored, with a line in the server log. Project JSON does not supply leases.

After `start <name>`, the server reads only `--config` and `-debug` from the command line and ignores other flags. `stop <name>` uses that name’s PID record; it does not load configuration. Of the environment variables in [Command-Line Flags](#command-line-flags), it reads only these:
- `TBA_API_KEY`, when the file sets no `tba_key`
- `WPILOG_DISK_CACHE_DIR`, when the file sets no `diskcachedir`
- `WPILOG_DEBUG`
- `WPILOG_HTTP_BIND`, `WPILOG_HTTP_PATH`, and `WPILOG_HTTP_ALLOWED_ORIGINS`, for an `http` server (see [HTTP Transport](#http-transport))

### Importing Logs

```bash
wpilog-mcp import [--server <name>] [--config <path>] [--store <dir>] [--move] [--robot <name>] <path>...
wpilog-mcp import --robot practice ~/riologs/downloads
wpilog-mcp import --server pit /media/usb/logs
```

`import` loads the named configuration (default `default`) using the same discovery as `start`. The destination is the first configured directory that is already a store, or the first configured directory if none is yet. `--store` chooses another directory inside the configured log directories. The first import creates the store and its `inbox/`. Files and directories are accepted; content identifies WPILOG and REV logs, and unsupported files are refused with a reason. Copies are the default; `--move` moves the originals. A move of a file listed by another store is refused with that store’s path; copy it instead to preserve both catalogs. Unmanaged files in another store can still be moved. `--robot` states a robot name when the log supplies no serial number. Duplicates are reported with their stored path and left at their source. Unassigned payloads live under `unassigned/<hash prefix>/robot/`, separate from `import.json`; older entries remain readable and migrate on the next import. Control filenames are placed in a hash subdirectory, keeping their original name and bytes.

When the named daemon is running, the command posts sources inside configured directories to its import endpoint and prints the job's progress and complete result. Outside sources, such as a USB stick, are copied (or moved with `--move`) into the store's inbox for the daemon to import. The command says where it placed them; their eventual results are in `inbox/imported.log`. With `--robot`, the command writes `batch.json` beside the batch files (`{"stated_robot":"practice"}`); the daemon uses that stated robot when the log has no logged identity. Names use letters, digits, dots, hyphens, and underscores, and must also be portable filenames (no Windows device names or trailing dot). A failed HTTP request is reported, without starting a second importer.

When no daemon is running, the command imports in its own process, staging outside files through the inbox as needed, with `--robot` applied to that import. It holds a `FileChannel` lock on `store.lock` for each whole import, as the daemon does. If another process holds it, the import fails promptly with a retry message. Do not delete `store.lock`: the persistent file lets every process lock the same object. A symbolic-link lock file, including a dangling link, is refused before an HTTP import or assignment is accepted; lock opens never follow links. Exit codes are 0 for completed imports or accepted inbox transfers, 1 for a failure or a refused direct file, and 2 for command-line errors. An accepted inbox transfer may later be refused; check its receipt.

You can also drop files directly into a store's `inbox/`. The owning server polls every three seconds, importing by move after size and modification time match across two looks at least three seconds apart. A file changed while waiting in the queue returns to waiting. Only `inbox/imported.log` is written in the inbox by the server: one JSON object per line, with the time, original path, status, destination, and reason. A refused file remains there and is not retried until it changes or the server restarts. A duplicate also stays, with its existing destination explained. `list_available_logs` reports these files under `inbox`, with size and waiting/importing/refused state, rather than under `unmanaged`. Symbolic links and special files are refused. Plain directories are never turned into stores just because the watcher sees them. Stores are discovered when watching starts, whenever a listing scans, and otherwise at most once a minute; the three-second polls visit known inboxes only.

A directory dropped into the inbox can carry `batch.json` with a `stated_robot` beside its files. The batch, including the sidecar, must settle before import. A malformed sidecar refuses its files with receipts; correcting it permits a retry. Loose files have no stated robot. Dot-prefixed files and directories under the inbox are neither imported nor listed. The command copies into `inbox/.transfer-<id>/`, holding a separate transfer lock, and atomically renames it to `inbox/batch-<id>/` when complete. On the next poll the server removes an abandoned transfer and records that cleanup in `imported.log`; an active transfer remains untouched. Originals are removed for `--move` only after the complete batch is published.

### Command-Line Flags

Started with flags instead of `start <name>`, the server reads no configuration file. It takes its settings from the flags and from environment variables, and a flag overrides its variable:

```bash
wpilog-mcp -logdir /media/usb/logs -debug
wpilog-mcp -logdir ~/riologs -logdir /media/usb/archive
wpilog-mcp -logdir ~/riologs --http --port 9000
```

| Flag | Environment variable | Config field |
|------|----------------------|--------------|
| `-logdir <path>` (repeatable) | `WPILOG_DIR` (several separated by `:`, or `;` on Windows) | `logdir` |
| `-team <number>` | `WPILOG_TEAM` | `team` |
| `-tba-key <key>` | `TBA_API_KEY` | `tba_key` |
| `-diskcachedir <path>` | `WPILOG_DISK_CACHE_DIR` | `diskcachedir` |
| `-diskcachesize <mb>` | `WPILOG_DISK_CACHE_SIZE` | `diskcachesize` |
| `-diskcachedisable` | `WPILOG_DISK_CACHE_DISABLE=true` | `diskcachedisable` |
| `-exportdir <path>` | `WPILOG_EXPORT_DIR` | `exportdir` |
| `-scandepth <n>` | `WPILOG_SCAN_DEPTH` | `scandepth` |
| `--http` | `WPILOG_HTTP=true` | `transport: http` |
| `--port <port>` | `WPILOG_HTTP_PORT` | `port` |
| `-debug` | `WPILOG_DEBUG=true` | `debug` |

Prefer `tba_key` in the configuration file, or `TBA_API_KEY`, to `-tba-key`: a command-line argument is visible to every user of the computer in the process list, and the key is a secret.

Several log directories: repeat `-logdir`, or separate them in `WPILOG_DIR` as in `PATH` (`:`, or `;` on Windows); `-logdir` replaces `WPILOG_DIR`'s directories. The server expands `~` only in the configuration file. In a flag or an environment variable, your shell expands it or nothing does: macOS and Linux shells do, but Windows Command Prompt and MCP client configurations do not, so give the full path there.

`wpilog-mcp -debug` on its own is a start with flags too, so it reads no configuration file. To debug a configured server, run `wpilog-mcp start default -debug` or set `debug: true`.

Three environment variables, with no flag or config field, set up the HTTP transport whether the server was started with flags or with `start`:
- `WPILOG_HTTP_BIND`: the address to listen on (default `127.0.0.1`; `0.0.0.0` accepts connections from other machines)
- `WPILOG_HTTP_PATH`: the endpoint path (default `/mcp`)
- `WPILOG_HTTP_ALLOWED_ORIGINS`: comma-separated host names to accept in a request's `Origin` header, besides `localhost`, `127.0.0.1`, and `[::1]`

`-help` prints every flag and variable, and `-version` prints the version.

The launcher sets the JVM's maximum heap from `WPILOG_MAX_HEAP` (default `4g`), and a server started in the background with `start` gets the same heap. Logs of several hundred MB may need `8g` or more.

## MCP Client Setup

### Claude Code

Register the shared bridge once for your user account:

```bash
claude mcp add --scope user wpilog-analyzer -- "$HOME/.wpilog-mcp/bin/wpilog-mcp" connect http
```

Windows (Command Prompt):

```bat
claude mcp add --scope user wpilog-analyzer -- cmd /c "%USERPROFILE%\.wpilog-mcp\bin\wpilog-mcp.bat" connect http
```

The [VS Code extension](../vscode-extension/README.md#using-it-with-claude-code) does this when it finds the Claude CLI, so do not register a second copy under another name. Registration stays in your Claude Code user configuration and survives server updates. Restart an existing Claude session, approve the server if asked, and check `/mcp`.

The bridge starts `http` when needed and joins it when running, including with VS Code closed. It takes the server's settings from the home file. For a project's own directories, put top-level `logdir` and optional `team` in `.wpilog-mcp.yaml`, or use `connect http --logdir logs --team 1234`. These grant a session lease; they do not redefine the server or its port. See [Directories by lease](#directories-by-lease).

If an old project `.mcp.json` entry takes precedence over the user registration, remove that entry. The extension retires only recognized entries it owned, and only from untracked or ignored files; custom and tracked files are left with a note. A permanent TBA key can stay in `servers.yaml` or `TBA_API_KEY`; while VS Code is open its registered key takes precedence.

### Claude Desktop

Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows). Give the launcher's full path, because Claude Desktop does not search your shell's `PATH`:
```json
{
  "mcpServers": {
    "wpilog": {
      "command": "/Users/you/.wpilog-mcp/bin/wpilog-mcp",
      "args": ["connect", "http"]
    }
  }
}
```
On Windows, use `"command": "cmd"` and `"args": ["/c", "C:\\Users\\you\\.wpilog-mcp\\bin\\wpilog-mcp.bat", "connect", "http"]`.

See [modelcontextprotocol.io](https://modelcontextprotocol.io/) for other MCP clients.

## HTTP Transport

For browser-based or multi-client access, start the `http` server from `servers.yaml`:
```bash
wpilog-mcp start http
```

The server starts in the background, and the command returns once it answers. Clients connect to `http://127.0.0.1:2363/mcp`: the port is the server's `port`, and `WPILOG_HTTP_PATH` changes the path. The server writes its log to `~/.wpilog-mcp/logs/http.log` and its process ID to the first line of `~/.wpilog-mcp/run/http.pid`. `GET /health` answers as soon as the server is up, with the server's version and process ID.

Running `start http` while it is up reports the running server rather than starting another. If the running server is another version, because you upgraded since it started, `start` stops it and starts the new version in its place, so an upgrade never leaves an old server serving. If something that is not wpilog-mcp holds the port, `start` says so and starts nothing; choose another port.

To stop the server:
```bash
wpilog-mcp stop http
```
It finishes the calls in progress, then exits, and `stop` returns once it has. A `stop` of a server that is not running succeeds too. The server accepts a stop only from this machine and only with a token that `start` wrote to `~/.wpilog-mcp/run/http.token`, a file only you can read, so no one else on the machine can stop it, and nothing on the network can; a server that does not answer the request within the start timeout is ended as a process. A server from before this version has no stop endpoint and is ended as a process straight away.

### One Server for Every Client

An `http` server can serve every MCP client on the machine at once, where a stdio server serves the one client that started it. A client that takes a URL (Claude Code's `.mcp.json` does, with `"type": "http"`) connects to `http://127.0.0.1:2363/mcp` directly. A client whose configuration takes only a command to run (Claude Desktop's file is one) uses `connect`, which relays the client's standard input and output to the server:
```bash
wpilog-mcp connect http                      # the "http" server, started first if it is not running
wpilog-mcp connect --url http://pit:2363     # any server, by URL, started by nobody
```
`connect <name>` does what `start <name>` does first, so a client started with it gets a server whether or not one was running, and a client started beside a running one shares it; `connect --url` starts nothing. Each connected client gets a session of its own, ended when the client closes its end. With `idle_exit_minutes` set (see [Config Fields](#config-fields)), the server exits on its own once every client has gone and the time has passed, so a server that is started on demand need never be stopped by hand.

The VS Code extension always uses this install’s `http` server. It starts the launcher with the home configuration explicitly, then registers its window’s directories and secret-storage key by lease. Every client shares the loaded logs and disk cache ([extension guide](../vscode-extension/README.md#the-standalone-install-is-the-server)).

The server listens only on `127.0.0.1` unless `WPILOG_HTTP_BIND` says otherwise (see [Command-Line Flags](#command-line-flags)); set it before `start`. The HTTP transport has no authentication, so anyone who can reach the port can use the server.

Started with flags, `wpilog-mcp --http` runs the HTTP server in the foreground instead, as the Docker image below does.

### Directories by lease

A local client may grant the shared server access to directories for the life of its MCP session. This is an HTTP registration endpoint, not an MCP tool: the person decides which files the server may read. Leases are visible to all sessions, including agents that connect independently. Both routes require `Mcp-Session-Id`, pass the MCP Origin check, and return 403 when the server is bound to anything except loopback, even for a request arriving locally.

```
POST /directories
{"paths":["/absolute/logs", {"path":"/another/project/logs","team":1234}],"team":2363}
DELETE /directories
POST /tba-key
{"key":"the-key"}
```

`POST /directories` replaces that session's directories atomically. Paths must be absolute, existing directories; symlinks resolve to their targets. An invalid path is refused with its name (400), and the previous lease remains intact. Each object may override the request's default team, including with null. Logs use their recorded team first, then the most specific matching lease with a team, then the configured default. The most recently registered lease wins equal-path team conflicts. `list_available_logs` returns origins and teams in `log_directories`, and plain strings in `log_directory_paths`. Leased directories also supply import sources, store discovery, and inbox polling.

`POST /tba-key` keeps the key in memory only. The most recently registered live key wins over the configured key; `{"key":null}` clears this session's key and restores the next live key or the configured one. The response never contains it. A missing session header is 400; an unknown or expired session is 404. Session DELETE or expiry removes both leases; `DELETE /directories` removes only the directories. Later reads, including cached logs, are refused unless another live lease or permanent configuration still admits them. A server with no `logdir` and no lease admits no file at all.

The bridge registers after initialization and releases its session when standard input closes:

```bash
wpilog-mcp connect http --logdir logs --logdir /Volumes/LOGS --team 2363
wpilog-mcp connect --url http://localhost:2363 --logdir logs
```

Paths from flags and the project YAML are combined and resolved against the bridge's working directory; `--team` overrides the project's team. A new bridge connection after a daemon restart registers again. Only top-level `logdir` and `team` in the project YAML contribute to the lease. Server addresses, ports, keys, and cache settings always come from the home or explicit server configuration. The bridge reports an ignored project `servers` section through its registration so the daemon's log records the decision.

### The Data Endpoint

An `http` server also serves every sample of an entry in one request, for a script, a notebook, a dashboard, or the VS Code extension's viewer, where a tool result cannot carry a hundred thousand samples:

```
GET /data/entries?path=<log>&names=<entry>[,<entry>...][&start_time=<s>][&end_time=<s>][&max_points=<n>][&format=arrow|csv]
```

`path` is the log, as `list_available_logs` lists it; `names` is one or more entries, an entry with a field path appended (`/Drive/Pose.translation.x`, as `get_statistics` takes it), or a REV log signal's key as `list_revlog_signals` gives it (`REV/SparkMax_3/AppliedOutput`), comma separated. A REV signal streams on the wpilog's clock, as `get_revlog_data` reads it, and its entry metadata carries `rev` with the device, the signal, the bus, `sync_method`, `timestamps_aligned`, `offset_seconds`, and `sync_confidence`, so a reader knows the offset's basis; a REV log that could not be synchronized is refused with the reason, and one still synchronizing with a `503` and `Retry-After`. `start_time` and `end_time` are seconds, as every tool takes them. `max_points` buckets, by the same rule as `read_entry`'s `max_points` (see [TOOLS.md](TOOLS.md#read_entry)): at most that many buckets of equal duration over the window, each with its count, minimum, maximum, mean, first, and last.

- `format=arrow` (the default) is the Apache Arrow IPC streaming format, `application/vnd.apache.arrow.stream`: a `timestamp` column in the log's microseconds and a `value` column typed by the entry (a float, an integer, a boolean, text, a struct with the schema's fields, a list for an array), or, bucketed, `timestamp`, `count`, `min`, `max`, `mean` (null where no sample in the bucket is finite), `first`, and `last`. One stream has one schema, so the entries in one request must share a value type; bucketed, any numeric entries go together. Enum fields carry `value` and `label`; a number absent from the schema has a null label (CSV keeps the number at the field name and an empty `.label` cell). Each record batch is tagged with its entry in its message metadata, and the schema's metadata carries what a tool result would: the server version, `inputs`, the log's time range, and per entry its type, its sampling class (periodic, change-only, event), the unit its name states by a conventional suffix where it does, its decoded `sample_count`, original `total_records`, and, bucketed, its bucket length. An entry with decoding failures also carries `decode_problem` (`entry`, `failed_records`, `total_records`, and the first failure’s `reason`) and the same `warning` text tools return; CSV carries these fields in its `# entries:` comment. `pyarrow.ipc.open_stream` or `polars.read_ipc_stream` reads the response bytes; `read_next_batch_with_custom_metadata()` gives each batch's entry.
- `format=csv` is one table per entry in the form `export_csv` writes (`timestamp_sec`, then the value's flattened columns, or the bucket columns), after `#` comment lines with the server version, `inputs`, and the entries' metadata, and a `# entry: <name>` line before each table.

```bash
curl 'http://127.0.0.1:2363/data/entries?path=/Users/me/riologs/akit_26-03-21_16-29-56_vache_q10.wpilog&names=/SystemStats/BatteryVoltage&start_time=20&end_time=40&format=csv'
```

The endpoint reads only files inside configured or currently leased log directories, as every tool does, and refuses a request from a web page as the MCP endpoint does. A response is capped at 512 MB: a request over the cap is refused with the row count, the size, and the hint to narrow the window or pass `max_points`, never cut. A missing entry is an error naming it, with the tools that list entries and field paths. A file that changes while it streams ends the stream with an empty batch whose metadata says so (`file_changed`), or a `# file_changed:` line in CSV, and a reader discards what it received. `ETag` comes from the file and the query, plus each requested REV source file and its synchronization method, offset, drift, and confidence, so a repeated request for an unchanged file with `If-None-Match` is a `304`. `get_server_guide` names the endpoint as `data_endpoint` whenever the transport is HTTP; a stdio server has none, and `export_csv` is the way there.

### The Import Endpoint

The HTTP transport accepts imports beside the data endpoint:

```http
POST /store/import
Content-Type: application/json

{"store":"/home/user/riologs","paths":["/home/user/riologs/download.wpilog"],"move":true,"stated_robot":"practice"}
```

`store` and every source in `paths` must be inside configured log directories, with symlinks resolved. With no configured directory, the endpoint refuses all imports. Outside paths go through the user's inbox; an unauthenticated HTTP server cannot offer a move-anything operation. `move` is required; `stated_robot` is optional or null. An empty `paths` array creates an empty store under the same lock. The body is limited to 1 MiB.

A `202` response contains `job_id` and a relative `url` such as `/store/import/<job>` (also in `Location`). `GET` that URL to poll. The job has `state` (`queued`, `running`, `done`, or `failed`), its latest `progress` (`phase`, `path`, `completed`, `total`, or null before it starts), `result` on completion, and `error` on failure. `result` is the Java importer's complete result: `files` with `original_path`, `status`, `path`, and `reason`, plus `same_robots` with `serial_number` and `directories`. A done job can contain refused files: inspect their statuses. Progress counts apply to the named phase; the final phase is `complete`. A second import to the same store queues behind the first.

`POST /store/assign` takes `store`, `paths`, and a required `stated_robot`. It moves only manifested unassigned files within that store, retaining their original provenance; ordinary imports still report duplicates as `present`. Assignment uses the same job response, polling URL, queue, lock, and path/Origin checks. Files already assigned, files outside this store's unassigned manifests, and REV files without a unique correlated wpilog are refused. An active HTTP or inbox import keeps an otherwise idle daemon alive until it finishes.

At most 100 jobs are retained in memory. Completed jobs are evicted first when a new job needs room; if all slots are active, admission returns `503` with `Retry-After`. History disappears at restart. Refusals are JSON with `error` and `hint`: `400` for malformed input, `403` for paths outside configured directories (with the inbox hint), and `404` for an unknown or expired job. The endpoint shares `/mcp`'s Origin refusal and the transport's loopback default. The server log records job IDs and outcomes, never uploaded file contents; this endpoint accepts paths, not uploaded bytes.

## Containerization

You can run wpilog-mcp in a Docker container for a team-shared or cloud-hosted server. This `Dockerfile` uses a multi-stage build to keep the runtime image small.

*Thanks to [Godmar Back](https://github.com/godmar) for contributing this setup.*

**`Dockerfile`:**
```dockerfile
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy Gradle wrapper and config first for dependency caching
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

# Copy source and build fat JAR
COPY src/ src/
RUN ./gradlew --no-daemon shadowJar -x test && \
    cp build/libs/wpilog-mcp-*-all.jar build/libs/wpilog-mcp.jar

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/build/libs/wpilog-mcp.jar ./wpilog-mcp.jar

RUN mkdir -p /logs

ENV WPILOG_DIR=/logs
ENV TBA_API_KEY=""
ENV WPILOG_TEAM=""
ENV WPILOG_HTTP=true
ENV WPILOG_HTTP_PORT=8000
ENV WPILOG_HTTP_BIND=0.0.0.0
ENV WPILOG_HTTP_PATH=/wpilogmcp

EXPOSE 8000

ENTRYPOINT ["java", "-Xmx4g", "-jar", "/app/wpilog-mcp.jar", "--http", "--port", "8000"]
```

**Build and run:**
```bash
docker build -t wpilog-mcp .
docker run -p 8000:8000 \
  -v /path/to/your/logs:/logs \
  -e TBA_API_KEY=your_key_here \
  -e WPILOG_TEAM=2363 \
  wpilog-mcp
```

The server is then at `http://localhost:8000/wpilogmcp`. Mount your log directory at `/logs` and pass the rest of the configuration as environment variables (see [Command-Line Flags](#command-line-flags)): the container starts the server with flags, so it reads no `servers.yaml`. The heap is set by `-Xmx4g` in `ENTRYPOINT`, since the image does not use the launcher. A browser-based client sends an `Origin` header; if it names a host other than `localhost`, `127.0.0.1`, or `[::1]`, add that host name to `WPILOG_HTTP_ALLOWED_ORIGINS`. The server listens on every interface here and has no authentication (see [HTTP Transport](#http-transport)), so put it behind something that controls who can reach port 8000.

## Upgrading

Run the installer again. The one-line installer gets the latest release; for the Gradle installer, pull the latest code first:
```bash
cd wpilog-mcp
git pull
./gradlew install
```

Installation adds its versioned JAR and launcher, and leaves the configuration alone. It repoints `wpilog-mcp` only when the installing version is newer, or with `--force`. Version numbers compare numerically; an unsuffixed release follows a suffixed version with the same numbers, and suffixes compare their numeric and text components (`dev10` follows `dev9`). An unreadable or unmarked current launcher counts as missing. A second install with identical files leaves them alone and reports `repointed: false`; changed bytes of the same version are refreshed atomically, as needed for development builds. `./gradlew install` always passes `--force`. An `install.lock` file serializes simultaneous installers; a busy install is reported. Ordinary installation never stops or restarts a running daemon. Older versions stay in `jars/` and `bin/` until you delete them. MCP clients run the new version the next time they start the server. A running HTTP server keeps the old version until the next `start` or `connect` of its name, which stops it and starts the new version, or until you `stop` it.

### Replacing an old install

Pass `--refresh` to either release script, or run a downloaded/built JAR outside the install directory with `install --refresh`. It stops the install's recorded daemons, renames the entire directory to a sibling `.wpilog-mcp.backup-<time>-<id>`, creates a fresh layout, and copies `servers.yaml` and legacy `servers.json` byte for byte, including comments and keys. Logs and caches outside the install directory stay where they are. Old JARs, runtime files, and anything else inside the install remain in the backup; the output names it (`backup_dir` in JSON). Keep it until satisfied, then delete it yourself.

A busy install or a daemon already starting/stopping is refused promptly. A guard prevents new daemon starts while the layout is retired. Refresh still refuses a downgrade unless `--force` is also given. It cannot run from the installed JAR itself, because Windows cannot rename the running JAR's directory. If rebuilding fails, the backup is retained and its location is reported. The extension's automatic updates never refresh.

## Uninstalling

Disable or uninstall the extension first so it does not offer to reinstall the server. Stop the shared server (`wpilog-mcp stop http`) and any additional HTTP server you configured, then delete the install directory (on Windows, the `.wpilog-mcp` folder in your user folder):
```bash
rm -rf ~/.wpilog-mcp
```

Then take the `bin` folder off your `PATH` (the `export PATH=...` line in your shell profile) and remove the server from your MCP client's configuration. The disk cache lives outside the install directory (its location is under [Config Fields](#config-fields)); delete it too to free the space.

## Troubleshooting

- **Server shows "Failed"**: run it by hand and read what it prints. A working server answers with a line of JSON; errors such as a missing or invalid configuration file are logged to standard error.
  ```bash
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | wpilog-mcp
  ```
- **Where the files are**:
  - Server: `~/.wpilog-mcp/servers.yaml`. `start` also searches the working directory; `connect` reads its project YAML only for directory/team leases
  - Claude Code: `~/.claude.json` (user scope) or the project's `.mcp.json`
  - Claude Desktop: `~/Library/Application Support/Claude/claude_desktop_config.json`
  - HTTP server log: `~/.wpilog-mcp/logs/<name>.log`; its process ID and port: `~/.wpilog-mcp/run/<name>.pid`; the token `stop` presents: `~/.wpilog-mcp/run/<name>.token`
- **Server times out**: usually the Java version or a wrong path. The launcher needs Java 17 or newer; [Requirements](#requirements) gives the order in which it looks. Check the one it would use:
  ```bash
  ~/wpilib/2026/jdk/bin/java -version   # or: java -version
  ```
- **Out of memory with large logs**: set `WPILOG_MAX_HEAP=8g` in the environment the server starts in. For an MCP client, that is the `env` of the server's entry, since the client starts the launcher. In VS Code, set the extension's `maxHeap` setting and restart the server; the extension passes it to the launcher.
- **Log reported as truncated**: a log cut short (by a power loss on the robot, for example) still loads. The server reads every complete record and reports the log as `truncated`.
