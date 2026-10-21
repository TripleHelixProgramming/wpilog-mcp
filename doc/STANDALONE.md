# The Standalone Server

The standalone install is the server used by the VS Code extension and by clients outside VS Code: Claude Code, Claude Desktop, Gemini, or any other MCP client. Most users put logs in `~/riologs` and let their client start the shared server automatically. The extension offers installation itself; the scripts below also work without VS Code.

This guide covers the shared server's configuration, command-line flags, environment variables
and HTTP routes, with their bounds and defaults, plus installation and client setup. For the
steps to use the tools and run the shared server, see the [operations and usage manual](OPERATIONS.md).

The server is designed for and tested with Claude. Other MCP clients work too, but the depth and quality of the analysis depend on the model.

The shared-server features described on this branch require 0.10.0-dev5 or newer.
The latest full release can be older. Until this version and its installer are published,
install the `development` checkout below and check `wpilog-mcp -version` before continuing.

## Shared-server capabilities

The shared server can record NetworkTables into ordinary WPILOG files, applying exclusion and
thinning policies while the existing log tools read the open recording. Recording remains
experimental and has limited controlled-shop evidence; the [configuration and limits](pit-server/STANDALONE.md#pit-server)
are retained for controlled evaluation.

The live tools, `list_sessions`, `get_latest_values` and `wait_for_change`, are registered on
every server. They report current session facts and publications; without a recording they
answer `not_applicable` with a reason. See the [tool reference](TOOLS.md#live-tools).

Every HTTP server exposes Prometheus metrics at `/metrics`. A scrape is a sampled view of
latest values, ages and server counters, not a replacement for a recording. The
[metrics guide](pit-server/STANDALONE.md#metrics-and-a-starter-dashboard) holds the
configuration and starter dashboard.

A local store catalogs recorded files by robot and session in manifests, preserving filenames
and provenance. Import and the inbox organize existing logs; [store transfer and mirror details](pit-server/STANDALONE.md#the-store-door)
are separate from ordinary local analysis.

## Requirements

- JDK 17 or newer (the WPILib JDK is recommended)
- Nothing else: the WPILib libraries are bundled in the JAR

The launcher uses the newest WPILib JDK it finds, then `JAVA_HOME`, then `java` on your `PATH`. Building with `./gradlew` uses `JAVA_HOME` or `java` on your `PATH`. WPILib puts its JDK here:
- macOS and Linux: `~/wpilib/2026/jdk/bin/java`
- Windows: `C:\Users\Public\wpilib\2026\jdk\bin\java.exe`

## Install

Run the installer for your system to get the latest full release. The installer shipped with these changes prompts in a terminal for log directories and a team number, then offers the matching VS Code extension for each WPILib VS Code it finds that accepts it (see [The matching extension](#the-matching-extension)). Existing server settings are kept, without asking you to enter them again. A run without a terminal does not prompt and takes the defaults; `--interactive` asks anyway, and `--non-interactive` never asks. Older published installers offer fewer options.

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

For these changes before release, clone with `--branch development`, or use your existing
checkout of that branch. On a Mac using WPILib's JDK, first run
`export JAVA_HOME="$HOME/wpilib/2026/jdk"`; Gradle does not use the launcher's JDK search.

Both release installers download a temporary JAR and run its `install` verb, which owns the layout and prints the PATH hint. If the JAR rejects the verb with `Unknown option`, a basic install falls back to that release’s own tagged installer; actual installation failures are reported without falling back. Older releases cannot honor the new installer options: choose a release carrying the verb for those. The output names the path taken. The Gradle task runs the same verb with `--force`, so a development build becomes current even when its version is equal or older. To install a JAR you already have:

```bash
java -jar wpilog-mcp-<version>-all.jar install
java -jar wpilog-mcp-<version>-all.jar install --install-dir /path/to/install --logdir /path/to/logs --logdir /path/to/archive --team 2363 --json
```

`--install-dir` defaults to `~/.wpilog-mcp`. Each `--logdir` and `--team` seeds a new configuration only; existing `servers.yaml` or legacy `servers.json` is preserved. The TBA key is always left commented. `--json` prints one object with `install_dir`, `installed_version`, `launcher_version_before` (null when none), `launcher_version_after`, `repointed`, `config_created`, `config_path`, `launcher_path`, and `path_hint` (the folder to add to PATH, or null). Keeping a newer or equal current launcher is success; a write failure exits nonzero with its reason. Without `--json`, these results are printed as text.

The installer checks every destination against the canonical install directory before writing. An existing `bin`, `jars`, lock, configuration, JAR, or launcher that resolves outside it is refused, with the path named. The Unix current-launcher symlink is allowed when its target stays inside the install; a missing target inside it is treated as an older launcher and replaced.

### Trying a pre-release

A tag selects an exact published release; `--pre-release` selects the newest published release, including pre-releases. Add `--with-extension` to install both halves from that release. The example works once the maintainer publishes that tag; a branch or an untagged development version is not downloadable.

For these changes before release, use the installers from `development`: those on `main`
predate `--tag` until this version is released, when both installer URLs below return to `main`.

```bash
curl -fsSL https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/development/install.sh | sh -s -- --tag v0.10.0-dev5 --with-extension
```

In PowerShell, download the script to a temporary file, then run it with the same flags:

```powershell
$installer = Join-Path $env:TEMP 'wpilog-install.ps1'
try {
    Invoke-WebRequest https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/development/install.ps1 -OutFile $installer
    & $installer --tag v0.10.0-dev5 --with-extension
} finally { Remove-Item $installer -ErrorAction SilentlyContinue }
```

### The matching extension

`--with-extension` downloads the matching `.vsix` and passes `install --with-extension --vsix <file>` to the JAR, which installs it into WPILib's VS Code. The JAR looks in every season's folder, `~/wpilib/<season>/vscode` (on Windows, `C:\Users\Public\wpilib\<season>\vscode` first, then the one in your user folder), including the app bundle there on a Mac, so `code` need not be on your PATH. An editor older than the extension accepts (the `engines.vscode` in the extension's `package.json`) is never used, since VS Code would refuse the install; of the rest, the newest season is the default. When no WPILib VS Code accepts the extension, `--with-extension` fails before anything is installed and names where it looked.

In a terminal, the installers ask instead of choosing. One editor gets a yes-or-no question, and an editor too old for the extension is named with its version instead of offered. With WPILib 2025 and 2026 installed, for example:

```
WPILib VS Code 2025 (version 1.96.2) is older than the extension needs (VS Code ^1.101.0), so it is not offered.
Install the matching extension into WPILib VS Code 2026 (version 1.105.1)? [Y/n]:
```

Several editors get a numbered menu instead, newest season first and the default, with `n` for none. Without a WPILib VS Code that accepts the extension, the installers do not ask at all. The version reads `unknown` when the editor's own `package.json` cannot be read; such an editor is offered, and VS Code itself reports a refusal.

`--vscode <season>` names one WPILib season, such as `--vscode 2025`. `--vscode system` installs into a VS Code installed apart from WPILib instead, which is never chosen otherwise: on Windows the user installer's folder, then the system installer's; on a Mac, `Visual Studio Code.app` in `/Applications`, then in `~/Applications`; then `code` on PATH. In the installers either form implies `--with-extension`; the JAR requires both. `--without-extension` skips the offer. `java -jar wpilog-mcp-<version>-all.jar install --list-vscode` prints what the installers would offer, newest first, one editor per line as its season, a tab and its version; other lines name the editors left out and why.

The extension never passes these flags: after bootstrap, it updates the server and the Marketplace updates the extension to the next full release.

### Layout

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

The defaults need no editing: logs in `~/riologs`, one shared `http` server on loopback port 2363, and no team assumed. Edit `~/.wpilog-mcp/servers.yaml` for permanent directories, team, port, cache, or idle policy. The installer creates it only when absent; `install --team` and `--logdir` seed a new file. VS Code’s Settings UI supplies temporary session leases and does not read or edit this YAML. Restart the daemon after editing it (`wpilog-mcp stop http`, then reconnect). Replace example directories with your own: a missing archive produces a `partial` listing with that directory in `skipped`.

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
| `port` | HTTP port, 1–65535; `run` also accepts `0` for an OS-assigned port | `2363` |
| `diskcachedir` | Shared directory for persistent scan indexes and REV synchronization results | `WPILOG_DISK_CACHE_DIR` from the environment, else the system's default (below) |
| `diskcachesize` | Maximum shared disk cache size, in MB; still enforced by startup cleanup | `8192` |
| `diskcachedisable` | Turn off the persistent disk cache | `false` |
| `exportdir` | Directory for CSV exports | `wpilog-export` in the system's temporary directory |
| `scandepth` | How many directory levels to search for `.wpilog` and `.revlog` files | `5` |
| `experimental_data_store` | Enable experimental continuous folder imports and their extension controls for this named server; restart after changing it | `false` |
| `idle_exit_minutes` | Minutes without a session, recent MCP request, or active import before a background HTTP server exits; `0` keeps it running. Applies whether a person or a client started it | `0` |
| `debug` | Debug logging | `false` |

With `experimental_data_store: true`, enabled data-store sync prevents idle exit; pausing sync
restores the configured idle-exit rule. With the feature flag off, saved policies do not inhibit
idle exit.

`logdir`, `tba_key`, `diskcachedir`, and `exportdir` can include environment variables, written `${NAME}`. A variable that is not set is left as written, and the server warns about it at startup; in `tba_key` the key then counts as not set.

In `logdir`, `diskcachedir`, and `exportdir`, a path starting with `~/` is in your home folder on macOS, Linux, and Windows alike. Forward slashes work in paths on every system, so prefer them (`~/riologs`, `/Volumes/LOGS/archive`, `D:/frc-logs`).

The default disk cache directory is `~/Library/Application Support/wpilog-mcp/cache` on macOS, `wpilog-mcp/cache` under `%LOCALAPPDATA%` on Windows, and `wpilog-mcp/cache` under `$XDG_DATA_HOME` (or `~/.local/share`) on Linux.

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

After `start <name>`, the server reads only `--config` and `-debug` from the command line and ignores other flags. `stop <name> [--config <path>]` uses that name’s PID record and checks the configured HTTP port for a managed service; a removed configuration does not prevent stopping a recorded daemon. Of the environment variables in [Command-Line Flags](#command-line-flags), it reads only these:
- `TBA_API_KEY`, when the file sets no `tba_key`
- `WPILOG_DISK_CACHE_DIR`, when the file sets no `diskcachedir`
- `WPILOG_DEBUG`
- `WPILOG_HTTP_BIND`, `WPILOG_HTTP_PATH`, and `WPILOG_HTTP_ALLOWED_ORIGINS`, for an `http` server (see [HTTP Transport](#http-transport))

### Running as a service

`wpilog-mcp run <name> [--config <file>] [--managed]` runs the named configuration in the
foreground. Logs go to stderr; it writes no PID file and spawns no background server. A clean
transport stop returns 0; a bad configuration or failed HTTP bind returns nonzero with the
reason. Ctrl-C or SIGTERM requests exit 0 after the shutdown hooks finish. Windows' forced
process termination cannot be caught; a stdio EOF is a clean stop there.

Server logs use a 1 MiB memory buffer and a daemon that writes to stderr. A blocked terminal,
pipe or journal does not stop recording or request work. Lines that do not fit are dropped
whole; when output resumes, a line such as
`[wpilog] Dropped log output: lines=2, bytes=80` reports the loss before later accepted lines.
The `wpilog_log_dropped_lines_total` metric counts lost lines for the process. Shutdown
finishes server cleanup, then waits at most two seconds for log output. A hard crash or a
still-blocked sink can lose buffered lines. Short-lived command errors remain direct and
ordered; MCP JSON-RPC output keeps its separate stdout.

For a temporary foreground HTTP server, `port: 0` lets the operating system bind an available
port in one step. Read the actual port from the `MCP HTTP server listening on` line on stderr.
`start`, `connect`, the internal daemon and service units still require a fixed port from 1
through 65535, so clients and the health timer know where to reach them.

Only the explicit `--managed` flag makes both `GET /health` and `list_sessions` report
`managed: true`. A hand-written service unit must pass `--managed`, as the printed unit does.
`INVOCATION_ID` is ignored: shells and CI jobs also inherit it from systemd.
`start` will neither adopt a managed process nor replace it
with a newer JAR. `stop` refuses it and prints the `sudo systemctl stop wpilog-mcp-<name>.service`
command. Use `--config` when the service's configuration is not in your normal search path.

`wpilog-mcp service-unit <name> [--config <file>]` prints four files separated by
`# file: <name>` comments: `wpilog-mcp-<name>.service`, its `-health.service` probe,
`-health.timer` and `-restart.service`. It validates an HTTP configuration with no idle exit, prints to stdout, and
never writes under `/etc`. Names use ASCII letters, digits, underscores and hyphens.

On a Linux host with systemd, Java 17 or newer available as `java`, and `/usr/bin/curl`
supporting `--retry-all-errors`:

1. Create the account and install the downloaded JAR outside a user's home:

   ```bash
   sudo useradd --system --user-group --home-dir /var/lib/wpilog-mcp --shell /usr/sbin/nologin wpilog-mcp
   sudo java -jar wpilog-mcp.jar install --install-dir /opt/wpilog-mcp
   sudo install -d -m 0755 /etc/wpilog-mcp
   ```

   Installed program files are readable by every user: the JAR is `0644`, the launcher and
   its `bin` and `jars` directories are `0755` on POSIX. The service account must be able to
   read files installed by root. Reinstall repairs these modes, but keeps existing configuration
   permissions and parent-directory permissions; every parent must allow the service user to
   traverse it. Keep secrets in the private environment file below.

2. Write `/etc/wpilog-mcp/servers.yaml`, using a named `http` configuration with
   `transport: http`, `idle_exit_minutes: 0`, and absolute paths for its logs
   and local stores under `/var/lib/wpilog-mcp/`. Keep the configuration readable by the service
   account. Put secrets in `/etc/wpilog-mcp/environment`, mode `0600`, owned by root:

   ```ini
   TBA_API_KEY=your-key
   # Optional JVM heap; the unit has no MemoryMax:
   WPILOG_MAX_HEAP=4g
   # If java is not on root's default PATH, point to the service-readable JDK:
   # JAVA_HOME=/opt/jdk-17
   ```

   That file uses systemd environment-file syntax, not shell `export`. Refer to other secrets
   with `${NAME}` in YAML. Set `JAVA_HOME` there on hosts whose JDK is available only through
   a login shell's PATH (including CI's hosted toolcache); systemd does not inherit that PATH.
   The unit sets `LANG=C.UTF-8 LC_ALL=C.UTF-8`; install that locale or
   replace both with an available UTF-8 locale (see [HTTP transport](#http-transport)).

3. Print and review the units, then copy each section to its named file under
   `/etc/systemd/system/`:

   ```bash
   /opt/wpilog-mcp/bin/wpilog-mcp service-unit http --config /etc/wpilog-mcp/servers.yaml > pit-units.txt
   # Copy the four sections from pit-units.txt to their named files, with sudo.
   sudo systemd-analyze verify /etc/systemd/system/wpilog-mcp-http*.service /etc/systemd/system/wpilog-mcp-http-health.timer
   sudo systemctl daemon-reload
   sudo systemctl enable --now wpilog-mcp-http.service wpilog-mcp-http-health.timer
   curl --fail http://127.0.0.1:2363/health
   sudo journalctl -u wpilog-mcp-http.service -u wpilog-mcp-http-health.service -u wpilog-mcp-http-restart.service -f
   ```

The unit's `StateDirectory=wpilog-mcp` creates and owns `/var/lib/wpilog-mcp`. Its hardening
makes the rest of the filesystem read-only and hides home directories, so put writable data
there; explicitly amend `ReadWritePaths` if you choose another location. It retains JVM JIT
support (no `MemoryDenyWriteExecute`) and sets no `MemoryMax`. `TimeoutStopSec=90s` and
`KillMode=mixed` let the main JVM drain before systemd ends remaining processes.
`SuccessExitStatus=143` also treats SIGTERM's exit code as a clean stop when a JVM lacks the
signal handler. `Restart=always`
with a five-second delay and no start-rate limit retries failures indefinitely; an explicit
`systemctl stop` stays stopped. Use `systemctl restart` after installing an update.

The unprivileged health timer starts a probe every 30 seconds. The probe makes up to three
HTTP attempts, each bounded by five seconds, with ten seconds between failed attempts; its
40-second unit deadline includes the attempts and retry delays. Only an exhausted probe
activates `wpilog-mcp-<name>-restart.service`. That narrow root handler runs
`systemctl --no-block try-restart` for this named server, so an explicitly stopped service
stays stopped. The probe itself keeps the `wpilog-mcp` account. Stopping the main service stops
its timer too; start both again when resuming. A stopped or low-disk capture still answers
process health with `status: ok`, so recorder state alone does not trigger a restart.
The probe uses the configured port on `127.0.0.1`; if you bind only to a particular network
address with `WPILOG_HTTP_BIND`, update the probe URL to that address as well. The service keeps
the existing [HTTP network policy](#http-transport).

### Keeping a data store up to date

Continuous folder imports are experimental and disabled by default. To test them, add
`experimental_data_store: true` to the named server in `~/.wpilog-mcp/servers.yaml`, then
restart that server. For the extension's shared server, keep its existing fields and add:

```yaml
servers:
  http:
    transport: http
    port: 2363
    experimental_data_store: true
```

Absent or `false`, the flag leaves the setup offer and five data-store commands hidden. The
server does not load a saved ingestion policy, grant its directories, prefer its store in
listings, or run its background imports. Policy and store files stay on disk; enabling the
flag again and restarting restores the saved policy. A store or source that is also configured
through `logdir` or a client lease remains readable through that separate grant. Manual
Organize Logs and one-time imports remain available without the flag.

When enabled, **WPILog Explorer: Set Up Data Store…** replaces the automatic organizing offer.
Choose an existing empty folder or store, review source folders, choose Copy or Move for each,
and confirm the summary. Copy keeps the originals. Move requires your acknowledgment
that the folder receives completed files; a quiet writer could otherwise append after a move.
Identical content is reported as present and left at its source, even in Move mode. A successful
Move stays marked as imported while its manifested store copy exists. The optional robot name
supplies identity only when the log does not record it.

User and permanent server folders are preselected. Project folders, session-only folders and OS
temporary folders start unchecked; you can explicitly select them. Newly configured folders are
never enrolled automatically. Cancellation before confirmation changes no setting and submits no
import. **Manage Data Store…** edits the shared policy; its **Show source status** item reports
per-source states and reasons, and writes the first 100 file receipts to the output channel.
When more receipts exist, it reports the total and the endpoint's `offset` and `limit` parameters.
**Sync Data Store Now** requests discovery and retries unchanged refusals,
**Pause/Resume Data Store Sync** controls ingestion,
and the existing Explorer controls manage the import listing. MCP reads now require explicit
source selection; Logs shows separate connection and source-scope roots. Pausing retains files and the readable store.

The named local server owns one preferred store and the persistent source policy. Closing VS Code
or its session does not stop syncing; the daemon keeps running while the policy is enabled and
restores it on restart. Stopping the server does stop it. All editors and profiles see that same
policy, and an edit based on an old revision is refused rather than overwriting a newer one.
With the feature enabled, every session on the named server can read the store and enrolled
folders, and the policy loads whatever address the server binds to; a server bound beyond
loopback serves them to its network.
Settings remain in `servers.yaml`; setup never rewrites it. Enrollment is separate machine-local
state in `~/.wpilog-mcp/state/local-ingestion/<configuration-identity>.json`, keyed by the selected
configuration file's canonical path and server name. Copying a store to another computer grants
no local directory access and enrolls no sources there.

Discovery runs on startup, on Sync Now and every 30 seconds, with pending files checked every
three seconds. Size, modification time and filesystem identity must stay unchanged for at least
three seconds, then be checked again on the store queue. This admits settled immutable versions,
not a live mirror: later growth waits and imports another version without replacing the old one.
A rewrite preserving all three attributes is outside that cheap detection rule. Source-root links
are resolved, but links below the root, other stores, mirrors and staging directories are skipped.
Unavailable sources report their reason; a missing or replaced destination suspends work rather
than silently creating a different store. Removing a source stops future imports, not an already
admitted transaction. Imported files, original paths and duplicate origins remain recorded.

`list_source_scopes` enumerates separate managed-store, mirror and ordinary-directory sources.
There is no default read source. With multiple choices, `list_available_logs` requires the chosen
`scope_id` and instructs the agent to ask the user when it is absent. Import destination preferences
do not choose reads. Legacy `view: default` and `view: all` both stay within the selected scope.
Explicit absolute paths keep their meaning; a relative log path with several configured scopes
requires `scope_id`. Source policy, pending files and receipts remain in the controls below.
A REV file arriving before its WPILOG is reconsidered when the store gains a new
session-owned WPILOG; only the existing unique, strong, near-zero correlation can assign it.
Ambiguous files remain unassigned with their reason.

Local controls are `GET/PUT /store/local-ingestion`, `POST /store/local-ingestion/scan` and
`GET /store/local-ingestion/files?offset=0&limit=100`. They require a named server bound to
loopback, a loopback peer, an allowed Origin and a live `Mcp-Session-Id`. With the feature flag
off, these controls return HTTP 409 with `FEATURE_DISABLED`, including the status request;
they read no saved policy. PUT sends the full
policy with its current `revision`, `enabled`, `store`, `store_id` and `sources`; each source has
`path`, `mode`, `enabled`, `completed_files` and nullable `stated_robot`. A stale revision returns
409. The file page carries `files`, `offset`, `limit` and `total`. These are user controls, never
MCP tools. Policy bodies are bounded at 64 KiB, with at most 64 sources; discovery retains at most
100,000 file states and reports the limit if reached. One batch admits at most 32 files.

### Importing Logs

In VS Code, **Import into another folder…** in the manual **Organize Logs…** command copies logs into a
data-store folder you choose, leaving the sources untouched. The destination is saved in User
**Additional Log Directories** and leased to the shared server before importing; `servers.yaml`
is not changed. You can choose a new empty folder or an existing store. Repeated content is
reported as present. **Copy in this folder** and **Move in this folder** instead organize
within the source folder, retaining or removing the originals respectively after verification.

```bash
wpilog-mcp import [--server <name>] [--config <path>] [--store <dir>] [--move] [--robot <name>] <path>...
wpilog-mcp import --robot practice ~/riologs/downloads
wpilog-mcp import --server http /media/usb/logs
```

**How an import is placed.** For a known robot, import first nominates sessions by calendar
range, with the puller's clock slack. If a candidate holds a capture or a data-matched WPILOG,
joining it needs strong, unique correlation within 250 ms of zero; a failed or ambiguous proof
starts a separate session with its reason. Without such an anchor, exact calendar overlap still
groups the files, and the manifest says `placement_method: by_time_overlap` with no measured
offset. An imported REV companion also needs the near-zero proof; other boots remain unassigned.
A matched session keeps its anchor's calendar even when the imported file's own clock was unset.

Earlier versions trusted overlap alone for ordinary WPILOG imports and could place a REV
companion with seconds of offset. Existing placements stay where they are, with their original
evidence.

**Configuration and destination.** `import` loads the named configuration (default `default`) with the same discovery as `start`. The destination is the first configured directory that is already a store, or else the first configured directory; `--store` chooses another directory inside the configured log directories. The first import creates the store and its `inbox/`.

**What it accepts.** Files and directories are accepted. Content identifies WPILOG and REV logs, and an unsupported file is refused with a reason. `--robot` states a robot name for logs that supply no serial number. A duplicate is reported with its stored path and left at its source.

**Copy or move.** Copying is the default; `--move` moves the originals. Moving a file that another store lists is refused with that store’s path: copy it instead, so both catalogs stay intact. Unmanaged files in another store can still be moved.

**Unassigned files.** Unassigned payloads live under `unassigned/<hash prefix>/robot/`, apart from `import.json`; older entries remain readable and migrate on the next import. A file with a control filename is placed in a hash subdirectory, keeping its original name and bytes.

**With a server answering.** The command probes the named configuration's port and checks the
configuration name returned by `/health`. A systemd-managed server owns imports the same way as
a launcher daemon; no PID record is required, and the command does not adopt or restart it.
Sources inside configured directories are posted to that server's import endpoint, and the
command prints the job's progress and complete result. Outside sources, such as a USB stick,
are copied (or moved with `--move`) into the store's inbox for that server to import; their
results land in `inbox/imported.log`. A different server name, an unrecognized response, a
timeout or a failed HTTP import is reported, and no second importer is started.

Run the command with the same `WPILOG_HTTP_BIND` environment as the server. Explicit loopback
addresses, including `::1`, are supported; wildcard binds use loopback for path imports.
A specific non-loopback bind is refused: server-path imports require a direct loopback
connection, and a failed loopback probe cannot establish that a server bound elsewhere is
stopped. Use the byte-upload endpoint or configure a loopback or wildcard bind.

With `--robot`, the command writes `batch.json` beside the batch files (`{"stated_robot":"practice"}`), and the daemon uses that stated robot when a log has no logged identity. A robot name uses letters, digits, dots, hyphens and underscores, and must be a portable filename (no Windows device names, no trailing dot).

**Without an answering server.** Only a refused connection on the configured loopback endpoint
permits an offline import. The command imports in its own process, staging outside files through
the inbox as needed, with `--robot` applied to that import. Hashing, inspection, correlation and
copy verification run outside `store.lock`; short publication turns recheck the source and
manifest before placement. Proven file pairs retain one atomic group publication. If another
process holds the lock, imports fail promptly with a retry message; capture admission retries
within its existing deadline. Do not delete `store.lock`: the persistent file lets every process
lock the same object. Symbolic lock paths, including dangling ones, are refused, and lock opens
never follow links.

**Exit codes.** 0 for a completed import or an accepted inbox transfer, 1 for a failure or a refused direct file, and 2 for a command-line error. An accepted inbox transfer may still be refused later; check its receipt.

**The inbox.** You can also drop files into a store's `inbox/`. The owning server polls every three seconds and imports a file by move once its size and modification time agree across two looks at least three seconds apart; a file that changes while it waits returns to waiting. Symbolic links and special files are refused.

The server writes only `inbox/imported.log` in the inbox: one JSON object per line with the time, original path, status, destination and reason. A refused file stays there and is not retried until it changes or the server restarts. A duplicate stays too, with its existing destination explained. `list_available_logs` reports these files under `inbox`, with their size and a waiting, importing or refused state, rather than under `unmanaged`.

A plain directory is never turned into a store because the watcher saw it. Stores are discovered when watching starts, whenever a listing scans, and otherwise at most once a minute; the three-second polls visit known inboxes only.

**Batches.** A directory dropped into the inbox can carry `batch.json` with a `stated_robot` beside its files. The whole batch, sidecar included, must settle before import. A malformed sidecar refuses its files with receipts; correcting it permits a retry. Loose files have no stated robot. Dot-prefixed files and directories under the inbox are neither imported nor listed.

The command copies into `inbox/.transfer-<id>/` under a separate transfer lock and renames the directory atomically to `inbox/batch-<id>/` when complete. On its next poll the server removes an abandoned transfer and records the cleanup in `imported.log`; an active transfer is left untouched. With `--move`, the originals are removed only after the complete batch is published.

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

A configured directory may be a symbolic link. Discovery resolves that root, but does not
follow links below it. Ordinary WPILOG and REV paths keep the configured spelling; store
members keep the catalog's resolved paths. Either spelling returned by the listing can be
passed back to a reading tool. Server startup logs each configured directory once; internal
containment validators report only at DEBUG, including during installation and library reads.

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

`claude mcp get wpilog-analyzer` checks the registration and connection from a terminal.
If `add` says the name already exists, inspect it with `get`; a correct entry needs no change.
The extension makes that check itself when another editor or profile registered the same
user-scope launcher and arguments: it remembers the entry and writes one line to Output,
without warning or changing the registration. A different scope, launcher or arguments gets
a conflict warning with manual instructions in **WPILog Analyzer** Output; the extension never
replaces the entry. **Copy Command** copies only the registration command, not a removal.
To replace an obsolete user entry, run `claude mcp remove wpilog-analyzer --scope user`, then
the add command above. This does not remove a project's separate registration.

The bridge starts `http` when needed and joins it when running, including with VS Code closed. It takes the server's settings from the home file. For a project's own directories, put top-level `logdir` and optional `team` in `.wpilog-mcp.yaml`, or use `connect http --logdir logs --team 1234`. These grant a session lease; they do not redefine the server or its port. See [Directories by lease](#directories-by-lease).

A permanent TBA key can stay in `servers.yaml` or `TBA_API_KEY`; while VS Code is open its registered key takes precedence.

### Moving from a project `.mcp.json`

Versions through 0.9.1 wrote a `wpilog-analyzer` entry into each robot project's `.mcp.json`, each starting a private `vscode-default` daemon. The user-scope registration replaces all of them, and a leftover project entry takes precedence over it, so Claude Code would keep starting the old daemon from that project. To finish the move:

1. Register once at user scope, with the command above or the extension's **Register with Claude Code**.
2. Remove the old entries. When a project opens, the extension removes the entry it wrote from an untracked or ignored `.mcp.json`, stops the old `vscode-default` daemon once, and says so in its output. A `.mcp.json` that git tracks is left alone: delete its `wpilog-analyzer` entry (or the file, if nothing else is in it) and commit, so your teammates' Claude Code stops looking for a daemon that no longer exists. An entry you wrote by hand is also left alone, with a note; remove it if it only duplicates the registration.
3. Supply project directories the old entry carried, if any, through `.wpilog-mcp.yaml` (the extension offers to write it) or `connect http --logdir`.
4. Restart Claude Code sessions. `claude mcp get wpilog-analyzer` should show the user scope and the launcher under `~/.wpilog-mcp/bin`; `/mcp` in a session shows it connected.

### Codex

Codex takes a command in `~/.codex/config.toml` (or a trusted project's `.codex/config.toml`):
```toml
[mcp_servers.wpilog-analyzer]
command = "/Users/you/.wpilog-mcp/bin/wpilog-mcp"
args = ["connect", "http"]
```
or, from a terminal, `codex mcp add wpilog-analyzer -- "$HOME/.wpilog-mcp/bin/wpilog-mcp" connect http`. On Windows, `command = "cmd"` and `args = ["/c", "C:\\Users\\you\\.wpilog-mcp\\bin\\wpilog-mcp.bat", "connect", "http"]`. `codex mcp list` shows what Codex sees. Codex also accepts a URL (`codex mcp add wpilog-analyzer --url http://127.0.0.1:2363/mcp`), which works only while the `http` server is running; the command form starts it.

### Antigravity

Antigravity reads `~/.gemini/antigravity/mcp_config.json` (`%USERPROFILE%\.gemini\antigravity\mcp_config.json` on Windows). In the IDE, the Agent panel's **...** menu opens **MCP Servers**, then **Manage MCP Servers** and **View raw config**:
```json
{
  "mcpServers": {
    "wpilog-analyzer": {
      "command": "/Users/you/.wpilog-mcp/bin/wpilog-mcp",
      "args": ["connect", "http"]
    }
  }
}
```
On Windows, use `"command": "cmd"` and `"args": ["/c", "C:\\Users\\you\\.wpilog-mcp\\bin\\wpilog-mcp.bat", "connect", "http"]`. The URL form is `"serverUrl": "http://127.0.0.1:2363/mcp"` in place of the command, for a server already running.

### Other clients

Every MCP client takes one of two shapes. A **command** entry runs the launcher with `connect http`; it starts the shared server when nothing is running and joins it otherwise, so it is the form to prefer. A **URL** entry points at `http://127.0.0.1:2363/mcp` and needs the server running already (`wpilog-mcp start http`, or the extension or a bridge keeping it up). Cursor, Gemini CLI, and GitHub Copilot outside VS Code take either. Give the launcher's full path in a GUI application's file, since those do not read your shell's `PATH`; see [modelcontextprotocol.io](https://modelcontextprotocol.io/) for the file each client reads.

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

## HTTP Transport

MCP request bodies are limited to 4 MiB and ten seconds; an unfinished body is disconnected.
JSON body readers share bounded refusal draining: after reading the route's limit plus one byte,
they discard up to another 4 MiB within ten seconds, or the route's earlier deadline, before
replying. This includes read/follow, registration, visualization, store JSON and MCP requests;
binary uploads keep their own streaming limits. A body ending before that discard bound and deadline receives
the route's normal size refusal. At the bound or deadline, the exchange closes before response
headers, so closing a response cannot start another blocking drain; the sender may lose the reply.
At most 32 MCP requests run at once (fewer on small machines), with overload reported as HTTP
503 when refusal capacity is available. Data streams and native reads use 16 separate workers;
store transfers and imports have another four, so readers cannot exhaust upload capacity.
Excess transfers normally receive JSON `BUSY` with HTTP 503 and a one-second retry hint. Each
streaming pool has two final-refusal workers with the same I/O deadline and no waiting queue;
MCP execution overload uses the ordinary pool's refusal workers. If those workers are also full,
the exchange closes before response headers, without draining its unread body on a dispatch
worker. The client can therefore receive a disconnect instead of a refusal. Ten seconds without IO progress ends a transfer, releasing
its reads, so clients that stop reading cannot occupy health's dispatch workers indefinitely.
Writes use progress units of at most 64 KiB. Health and registration use a dispatch pool growing
from four to 32 workers without a waiting queue. If other handlers occupy all 32, the JDK still
rejects the next exchange by closing its connection without an HTTP response. That reset differs
from the bounded refusal-worker fallback above.

For browser-based or multi-client access, start the `http` server from `servers.yaml`:
```bash
wpilog-mcp start http
```

The server starts in the background, and the command returns once it answers. Clients connect to `http://127.0.0.1:2363/mcp`: the port is the server's `port`, and `WPILOG_HTTP_PATH` changes the path. The server writes its log to `~/.wpilog-mcp/logs/http.log` and its process ID to the first line of `~/.wpilog-mcp/run/http.pid`. `GET /health` answers as soon as the server is up, with the server's version, configuration `name`, process ID, `managed` ownership, `server_instance_id`, and `working_state`. `name` is null for HTTP started with the unnamed legacy flags; stdio has no health route.

`store_filesystems` adds one row per registered filesystem with an opaque `filesystem_id`,
`state` (`available` or `unknown`), nullable `free_bytes`, `reserved_bytes` and
`capture_reserve_bytes`. These are published samples; health does no filesystem I/O and
exposes no store paths. `status` remains `ok` when space is low or unknown.
The capture keys `disk_reserve_bytes`, `disk_floor_bytes` and `disk_resume_margin_bytes`
default to 2 GiB, 256 MiB and 512 MiB. See
[capture configuration](pit-server/STANDALONE.md#pit-server) for exact bounds, reserve ownership
and the pending legacy copy integrations.

`capture` contains `configured_count` and a `captures` array, currently zero or one row. Each
row reports `state` (`waiting_admission`, `recording` or `stopped`), a finite `reason` when
stopped, nullable `last_write_age_ms`, `longest_listener_loop_stall_us`, and
`longest_store_queue_turn_us`. Before the first completed write, the age is null. Reasons
distinguish not started, waiting for a clock or topic, disconnection, admission failure, write
failure, startup failure, low disk, close and shutdown. No path, robot identity, recorder
identifier or raw error message is included. The two maxima cover observations since the
preceding health probe, shared by all callers; a racing completion is not lost and an ongoing
stall remains visible across probes. The store maximum measures the mutation action and
currently excludes a blocked completion-report callback; see
[the remaining reporting limit](ARCHITECTURE_HARDENING_PLAN.md#implementation-disposition--2026-10-20).
Health reads published state without joining the listener, store queue or filesystem.
`status` remains `ok` while HTTP answers, including a stopped recorder.

### Recorded visualization preview

Named servers persist their local visualization owner identity in
`~/.wpilog-mcp/state/visualizations/<config-key>/server.json`. The key is SHA-256 of the real
configuration path, a newline, and the server name; changing the port preserves it. Health and
`get_server_guide` report the same `server_instance_id` with `working_state: "durable"`.
If that state directory cannot be opened (including a refused symlink or permission denial),
the server logs a warning and continues with a fresh ephemeral identity. Unnamed servers also
report `working_state: "ephemeral"`. In both cases, `working_state_reason` explains why durable
recovery is unavailable; restarting may change the owner ID. This fallback does not stop log
analysis or recording.
This identity creates no log store or ingestion policy.

Qualified source references are available through `/visualizations/v2` and MCP visualization
`api_version: 2`. The v1 routes and API 1 reject qualified references, including operations on
working views containing them. API 1 remains local-only. API 2 admits remote reads, working-view operations and server-library
operations through the same HTTP/MCP policy. Store enumeration and legacy library migration
remain local administration; directory, key and proxy-credential registration also remain local.
Denied Origins never receive read authority. Forwarded requests can use API 2 but do not gain
local authority. This admission policy is not an authentication system. API 2 adds:

- `POST /source/scopes` with optional integer `offset` and `limit` (1–64), a 64 KiB request
  limit and bounded descriptors; unknown fields and lossy integer values refuse.
- `POST /source/qualify` with a legacy `source_ref` and optional explicit `scope_id`.
- `POST /source` with `path` and optional `scope_id`, returning a qualified reference.

A qualified reference has `schema_version: 2`, persistent `server_instance_id`, `scope_id`,
and strict `content` (`recorded`, anchored `session`, `unmanaged`, or `file_version`). A store scope
uses its own UUID, including a mirror's UUID. Directory scope UUIDs are derived from canonical
configured roots, stable across restart and reordering; moving a directory changes that scope.
These IDs grant no access. Reads revalidate current grants, server identity and serving scope;
matching hashes on a different mirror or master never supply a fallback. Legacy origin/hash
references with several serving copies refuse before searching for the hash. After explicit format-2
migration, `POST /source` returns `file_version` for files with replica identities. API 2 resolves
the current generation within that selected scope and carries its verification and authority basis;
see [the mirror reference](pit-server/STANDALONE.md#the-mirror).

The raw `/read/v1/follows` source/query form rejects qualified references and `scope_id` with
400 `INVALID_REQUEST`. Follow an API 2 working view with `view_binding` to retain its serving
owner and scope; see [Following a recording view](READ_API.md#following-a-recording-view).

The existing recorded-preview routes remain available under
`/visualizations/v1` on a loopback-bound HTTP server, with a loopback peer, the normal Origin
check and a live `Mcp-Session-Id`. Network-bound servers refuse these routes even to a local peer.
The `visualization_*` MCP tools share the same application owner and require trusted local
transport authority (direct stdio or loopback HTTP). The HTTP preview adapter uses GET for `/capabilities`, `/stores`
and `/views/<uuid>`; POST supplies `/window`, `/source`, `/source/describe`, `/source/health`, `/source/resolve`,
`/source/value`, `/source/views`, `/views`, `/views/<uuid>`, `/views/<uuid>/attach`,
`/views/<uuid>/detach`, `/views/<uuid>/describe`, `/views/<uuid>/save` and `/saved/open`.
POST also supplies `/source/reduction`, `/source/statistics`, `/source/histogram`, `/source/scatter`, `/source/raw`, and `/templates/list`, `/templates/get`,
`/templates/check`, `/templates/create`, `/templates/update`, `/templates/import-preview`,
`/templates/import`, `/templates/export`. The import preview is read-only; mutation rechecks
under the store lock. MCP schemas and results are listed in [TOOLS.md](TOOLS.md).
`POST /window` reports the opening window's `decoded_buffer_budget_bytes`, an integer whole
MiB from 16 to 1024. Identity comes from the live transport session; no caller-supplied identity
is accepted. The reply and capabilities label the allowance `basis: client_reported`: it is
neither a server allocation nor an access grant. At most 256 windows are registered, with
removal on session close/expiry and no persistence across restart. Capabilities expose
`decoded_buffer_budget` (default 64 MiB, range, setting and reload requirement) and
`attached_windows` (`client_id`, `current_session`, `decoded_buffer_budget_bytes`, `basis`). The extension reports
again on connection/control resynchronization. Settings changes require a window reload.
A session ID is a credential: presenting it acts as that window for its attachments, inventory
handles, view follows and quotas. No visualization reply contains one, the caller's own included.
`client_id` is an opaque ID for a window, fixed for its session while the server runs, and a
working view's `attached_client_id` names its window by the same ID. It is not accepted as a
session. `current_session` marks the caller's own window; the `/window` reply is that row.
Requests preserve raw JSON for strict duplicate-key/schema validation; configuration mutations
use expected revisions and session ownership. Working drafts detach on session removal/expiry
and expire after 30 days. Save writes `visualizations/saved-views/<uuid>.json` through the store's
normal containment, lock, atomic-write and revision checks. First save can create an empty store
without enrollment. It never grants access to a source outside configured or leased directories.

The preview supports recorded local WPILOGs: managed identity is origin-store UUID plus content
hash; an unmanaged source uses an owner-local fingerprint locator in `sources.json`. Neither
locator nor credential is embedded in portable configuration. Managed bytes must match the
manifested content hash; proofs are cached only for unchanged file boundaries. An unmanaged
file is refused if it disappears or its fingerprint (file identity, size, modification and creation
times) changes. This is not a content hash: an in-place rewrite preserving all those attributes
can be read under the same reference. An unmanaged overview's unavailable robot/session/match
facts mean there is no store catalog for the file; they do not establish what its signals record.
Locators are bounded to 1,024 entries and 1 MiB within the working-state budget. Restart removes
locators with no unexpired working view; draft expiry releases its unused locator, and quota
pressure prunes other unreferenced locators before refusing new ones. Recent identifications can
coexist before a draft is created. Reopening an unchanged file in Source Overview restores its
deterministic locator, including for a saved view whose working copies have expired. Deleted views
do not retain locators under pressure. Capabilities report 256 non-deleted working views, 30-day detached recovery and the current plot
limits (up to 65,536 recorded plot points, 4,096 live plot points per selection and 32 series per
view). The recorded client chooses a smaller point limit from its available window allowance,
series count and plot/table copies. HTTP JSON replies are bounded to 16 MiB; recorded sample replies
have a client admission limit up to 4 MiB, reserved before download. Recorded/live sample and
paged-inventory buffers share the configured window allowance, with a separate 64 MiB aggregate
encoded allowance. These are conservative accounted buffers, not a total process-heap bound. Saved metadata listing stops at 1,024 records or
8 MiB per store. Invalid/newer records are skipped intact and reported in `unreadable` with
`store_id`, `file` and `reason`. A bounded partial listing sets `listing_incomplete`; opening a
known saved UUID reads only that record and is independent of the listing budget. Saved/draft
overview lists carry summaries, not configuration buffers. A full or unavailable state workspace
explains the refusal; health remains available. Plots use the shared Arrow reader, while cursor
tables select original samples and their decoder diagnostics. Title, geometry and style edits
reuse loaded series and values; changing the required series, display-copy counts or recorded
range starts a new admission and query. Statistics delegate to `get_statistics`; raw pages use the reader's physical ordinals
and retain equal/backward timestamps. The API permits 4,096 records and 1 MiB per raw response;
Explorer requests at most 256, reduced by available buffer headroom, and replaces its previous page. The source catalog is owned once per
workspace and replaced when its configured roots change; access is revalidated on each operation.

Recording inspection is the first live-panel integration checkpoint. On an open manifested
capture, `POST /source` and `visualization_source_inspect` return an anchored `session`
reference. `POST /source/describe` and the same MCP tool accept that reference across growth,
member renames, retained session aliases and finalization, using the native follower's admission
owner. `members` preserves each member's descriptor and final hash; `entries` summarizes only
names with matching types and metadata, and `entry_conflicts` explains the omitted names.
Member schemas remain separate and field bindings are not inferred from this summary.
The default full inventory is capped at 4 MiB (`max_recording_inventory_bytes`); its native member admission
retains the follower's 64-member and 128 MiB index limits. It is a detached inspection, not a
retained read lease or a guarantee that later requests see the same prefix.
Repeated inspections and recorded-file admissions share up to 256 successful finalized-file
hash proofs in workspace memory, expiring after ten idle minutes and cleared at workspace
close. A changed path, file boundary or expected manifest hash requires verification again;
current access checks still run. The memory retains no file handle or sample data. It cannot
detect edits that preserve all observed file attributes, and a cold/evicted proof still needs
a full file hash. Concurrent cold requests may duplicate that read.
Typed native-reader refusals keep their code and boolean `retryable` through both inspection
adapters: a moving catalog can be retried, changed writer history stops, and metadata limits
return a non-retryable 413 over HTTP. Temporary refusals also supply `retry_after_ms: 1000`,
including recording compatibility and view-admission pressure. Permanent refusals omit that delay.
Inspection does not automatically retry for a caller.

The inventory's `clock.robot_time_sec` is the shared NT4 estimate observed when the fixed view
opens. Missing/non-finite estimates are null; `right_edge_sec` then uses the indexed
`last_sample_time_sec`, if any. `basis` says `robot_estimate`, `last_sample`,
`waiting_for_time_sync`, or `unavailable` for an empty finalized recording.
`waiting_for_time_sync` is true only while unfinalized without a usable estimate. This call
does not retain a previously valid display edge or mix receipt/wall time into sample timestamps.
Capabilities say `live_source_inspection: true`, `recording_bindings: true` and
`recording_views: true` and `recording_view_follow: true`, but still `live: false`. Recording-backed working and saved metadata
is available through the API. The editor now has a local recording-panel preview using
revision-bound follows; the complete Stage 3 feature is still gated by resource/performance and
remote-source work, hence `live: false`. Value/statistics routes now accept recording references
through the shared analysis operation described below. Single-file resolution, Arrow and raw-page
routes still refuse to choose a member. The separate compatibility, metadata and revision-bound
binding operations are described below.
The overview can open recording layouts. Refresh signals replaces its frozen inventory; a visible overview also refreshes at
absolute expiry. A failed reopen keeps known-expired rows disabled until a valid replacement
page is admitted. Unavailable declarations use a separate, explicitly labeled traversal without
name/type filters; ordinary filters are retained for switching back. A finalized file opened afresh retains the recorded
content-hash workflow; an already-held recording reference keeps its original identity.

Source history reduction uses `POST /source/reduction` or MCP
`visualization_source_reduction`. Pass `source_ref`, exact `entry`, optional scalar `field`,
optional inclusive `start_time`/`end_time` in seconds, `max_points` (1–4096, default 1024)
and `max_bytes` (4096–1048576, default 1048576). Missing bounds use the admitted source span.
Alternatively, `last_seconds` uses the shared relative-window resolver on the admitted clock
(robot estimate when available, otherwise recorded time); it cannot combine with explicit
bounds, and the result reports absolute bounds. A fresh prefix proves all members; values are merged in timestamp/member/ordinal order before
using the native min/max/mean reducer. Scalar struct-angle fields unwrap on that merged
trajectory, across rollover members and input batches, starting anew for each requested
interval. Plain numbers remain arithmetic; the preview has no angle override control.
The response identifies that independent prefix and
absolute `window`, includes counts and chronological first/last, and reports decode problems
with at most 100 member-qualified details. Empty windows are `no_match` with a refresh hint.
Nonfinite values, absent projected array elements and integers outside ±(2^53−1) become
counted gaps. Signed and unsigned integers, including enum fields, are checked before floating-point
conversion; enum values otherwise contribute their stored number. All-gap buckets retain their
sample count with null extrema/mean. Exact raw/value reads remain available. These drawing buckets never feed statistics.

Capabilities expose `source_reduction` and `source_reduction_limits`: two concurrent reads,
4,194,304 selected records (including out-of-window records, checked before sorting), 64 KiB
per decoded record, 8 MiB merge lookahead payload, 1 MiB maximum result, 64 KiB raw request
and a cooperative 10 s work budget. Native time ordering also uses its existing shared sort
budget (12 bytes per selected record at peak when ordering is needed). Busy admission is
retryable with a 1000 ms delay; work, record and result limits refuse permanently for that
request. For a result limit, reduce `max_points` or raise `max_bytes` within its cap; for a
selected-record limit, read individual members. There is no partial-history success on a limit.
The local-authority, active-session, bounded dispatch and refusal-drain rules apply to both
HTTP and MCP; MCP includes all raw batch bytes in request admission.

Source chart reads use `POST /source/histogram` and `POST /source/scatter`, or MCP
`visualization_source_histogram` and `visualization_source_scatter` with `api_version: 1`.
Both take `source_ref`, optional inclusive `start_time`/`end_time` or mutually exclusive
`last_seconds`. Histogram takes exact `entry` and optional `field`; scatter takes `x_entry`,
`y_entry`, optional `x_field`/`y_field`, nonnegative `offset` and `limit` 1–4096 (default 256).
Fields are explicit numeric scalar paths or indices. Active recordings require a requested
1–300-second interval; finalized recordings and single files may use their complete duration.
A recent window uses the same admitted-clock resolver as statistics, with fewer samples allowed
when the source is younger than the requested duration. An explicit null `last_seconds` has the
same meaning as omitting it, including in the reported inputs.

Before any source sample exists, relative statistics, chart and reduction reads return `no_match`
with a hint. The available clock estimate still sets the right edge; with neither measured bounds
nor an estimate, the empty scope uses zero. These scope bounds never become a measured session
span. Live analysis remains eligible to refresh when a later prefix contains samples.

The chart result identifies its independent `prefix` (file or session, finalized state,
member writer/hash/boundary evidence and clock). It is not the plot frontier. Original finite
samples use the image chart's binning and one-to-one exact timestamp pairing, stable by member
ID then physical ordinal at ties. Numeric values use the existing binary64 analysis rules,
including struct-angle unwrapping. Native timestamps that cannot round-trip through numeric
seconds within the supported exact-microsecond range refuse instead of pairing unequal times.
Histogram edges that cannot be represented as distinct finite numbers also refuse.
No source path or reusable permission is returned. Missing finite data/pairs are `no_match`
with a remedy; decode problems retain exact totals and at most 100 details.

Chart reads share two nonqueued slots per workspace, separate from value/statistics slots.
Each call bounds visits at 1,048,576 records, retained original samples at 262,144 across unique
selected entries, retained-value estimates at 32 MiB, individual payloads at 64 KiB and operation
JSON at 1 MiB before the outer MCP envelope. Capabilities publish these as `source_chart_limits`
and `source_charts: true`. A ten-second work budget and cancellation are cooperative; admission
I/O and individual arithmetic passes can outlast a check. Scratch storage and shared indexes
are outside the retained-value estimate. Slot pressure is retryable with `retry_after_ms: 1000`;
fixed limits refuse without approximation. Grants, boundaries and session liveness are checked
before publication. Scatter page totals cover the full interval; each call is fresh, so compare
prefixes before combining pages during appends. The preview exposes both controls for files and
recordings. It replaces scatter pages rather than combining them, retains the current offset on
automatic refresh, and supplies First pairs to reset it. Its client limit is at most 256 pairs,
reduced by the shared window allowance. Histograms may overlay several independent server bin
sets; scatter uses the first selected signal for X and the second for Y, with an explicit swap.
Charts join the recording-analysis queue for the window and its at-most-one-Hz refresh cadence. The
client reserves shared bytes before I/O, caps requests at 64 KiB, responses at no more than
1 MiB and calls at 15 seconds. It rejects foreign/malformed chart results as a whole. Pause,
hide, close and stale-generation guards apply to both types; chart axes are independent of the
time cursor. See the [panel steps](OPERATIONS.md#52-recording-panel-preview).

Recording analysis uses `POST /source/value` (`source_ref`, exact `entry`, optional scalar `field`
and inclusive `at_sec`) and `POST /source/statistics` (`source_ref`, exact `entry`, optional scalar
`field`, inclusive `start_time` and `end_time`, or positive finite `last_seconds`). The MCP counterparts are
`visualization_source_value` and `visualization_source_statistics`, with `api_version: 1`.
These are fresh, independent prefix reads, not answers tied to the plot's last displayed frontier.
All present members must agree on type, metadata and selected schema; absent members are reported.
Recording fields use the existing numeric-scalar compatibility rule and require a recorded witness;
whole-entry values preserve other declared types.
The value is the greatest timestamp at or before the cursor (or greatest overall when omitted).
Equal timestamps choose the greatest original member ID in Java string order, then the last
physical entry ordinal. A backward physical tail does not replace a newer timestamp. Tagged
values retain exact integers and non-finite floating bits; no interpolation or previous-value
substitution is applied to a recorded missing field. Results include `member_id`, `ordinal`,
`timestamp_us`, `basis`, `tie_basis`, member-qualified `problems`, their full `problem_count` and
`problems_truncated`. Statistics use original samples, including equal/backward timestamps,
through the same field/angle, percentile, sample standard deviation and quality rules as
`get_statistics`. Non-finite samples are counted by `data_quality.nan_filtered`. A valid numeric
window without finite samples returns `no_match` with `count: 0`, `looked_for`, a hint and the
same inputs and prefix proof; no mean or other computed statistic is invented. It remains
eligible for refresh when a new recording prefix completes. Invalid selectors and resource
limits keep their existing refusals.
`last_seconds` uses the shared relative-window resolver before retaining samples and cannot
combine with absolute bounds. It ends at the admitted robot estimate, no earlier than the last
recorded timestamp, or the last timestamp when no estimate exists. `inputs.window` and
`inputs.last_seconds` report that decision; `prefix.clock` carries its clock evidence.

Recording results include `inputs.source_ref`, exact entry/field, `records_visited` and `prefix`
with recording ID, finalized state and every member's writer/hash/boundary, presence and entry
record count. Analysis retains no mapping pin, follow lease or decoded-result cache after the call. Grants, session
liveness and finalized boundaries are checked before publication. Capabilities expose
`recording_analysis: true` and `recording_analysis_limits`: two concurrent reads without a queue,
1,048,576 visited records, 262,144 materialized statistics samples, a conservative 32 MiB retained
value estimate, 64 KiB per original record payload, 100 problem details, 1 MiB of operation JSON
before the MCP envelope and a cooperative ten-second work budget including admission. The estimate excludes shared native
indexes and statistics scratch space; it is not a total-heap claim. Deadline and cancellation are
checked between admission, batches and calculation, not by interrupting filesystem I/O or an
individual arithmetic operation. File-order traversal counts records outside the requested
window too. A narrower range can reduce retained data but cannot bypass the visit ceiling.
Exceeding a bound refuses with `RESOURCE_LIMIT` rather than returning partial statistics; only
occupied slots are retryable, with `retry_after_ms: 1000`. Use smaller ranges for retained-value
pressure or read individual members. The local value/statistics panels use these operations with one queued analysis at a time per
window, at most 32 waiting views, a 15-second client deadline and a window-budget-sized response
cap no larger than 1 MiB. Active statistics intervals are 1–300 seconds; finalized layouts may
request their full recording under the same server bounds. Each result identifies its own
analysis prefix, which need not equal the displayed plot frontier.

Paged recording discovery is shared by the overview and local HTTP/MCP callers. The overview
negotiates support before opening, requires the paged-mode echo, and caps responses at 1 MiB
and requests at 64 KiB under a 15-second client deadline per request. Unsupported servers
receive an update hint, with no automatic full-inventory fallback. Capabilities publish `recording_inventory_paging: true` and
`recording_inventory_limits`. POST `/source/describe` with `source_ref` and
`inventory_mode: "paged"` returns a fixed summary, anchored source, member proofs, clock facts,
exact totals, `inventory_id`, `server_epoch` and `expires_at`. Omitting the mode or choosing
`full` keeps the existing reply; unknown modes refuse. Only anchored recording sources support
paged mode. A client must check the capability and mode echo, and cap its paged reply at 1 MiB.

POST `/source/entries/list` with `inventory_id` and optional `query`, `types`, `unavailable`,
`limit`; subsequent pages use only `inventory_id` and `cursor`. Names use literal substring
matching with `Locale.ROOT` folding and exact UTF-16 ordering; types match exact declarations.
A conflicting row matches any of its member types. Pages carry `entries`, `returned`,
`total_matching`, `total_entry_names`, `unavailable_entry_names`, `complete` and `next_cursor`.
A cursor binds the epoch, inventory, filter, row ceiling and next position; retries return the
same page. Unavailable declarations have a separate `unavailable: true` traversal with bounded
previews and original lengths. Previews cannot be used as binding names. Large metadata alone
does not hide a signal. Rows retain metadata comparison digests internally, never raw metadata.

POST `/source/entries/get` with `inventory_id` and 1–32 distinct `row_ids` reopens admission and
returns fresh member descriptors with selected entries and transitive schemas, `detail_revision`,
`count_basis` and `counts_grew_since_inventory`. The returned `selected_rows` describe the old
listing; current counts belong to the member descriptors. Declaration/type/metadata changes,
new members or changed writer identity require refresh and reselection. Finalization continues
only with matching writer proof, and fresh details include the verified final hash. Missing,
invalid or conflicting schemas remain explicit; details do not decide binding compatibility.
POST `/source/inventory/close` with `inventory_id` is idempotent and works after grant revocation.
The MCP counterparts and their result envelopes are in the [tool reference](TOOLS.md#visualization_source_entries_list).

Every handle and cursor belongs to the opening MCP session, including requests made through
HTTP. Stdio uses its process session. Session removal, shutdown, 60 seconds of idle time or
five minutes of absolute age release the inventory; successful reads renew only idle expiry.
Listing retains a fixed prefix's compact facts and closes all native mappings before publishing
the handle. Every read, including replay, rechecks current grants without reopening payloads.
A missing old pathname may trigger catalog-only alias resolution; ordinary pages do not scan
or hash the recording again. Details use fresh admission and the workspace's verified-hash memory.

The server admits eight handles total and two per session, including builds; each inventory
has 32 MiB of accounted metadata within a 64 MiB aggregate, and at most 200,000 member-entry
occurrences. Each logical inventory reply is at most 1 MiB including the larger HTTP/MCP
encoding. Pages contain at most 256 names. Requests admit at most 64 KiB of raw UTF-8 input,
including whitespace and overwritten JSON fields. For MCP over HTTP this counts the entire
exchange, including batch syntax, and applies to each inventory operation in that exchange;
other batch members retain their normal dispatch. Stdio counts the raw message line without
its newline. Filters allow a 256-code-point query and 16 exact types and must also fit their
authenticated continuation inside the request cap.

MCP HTTP batches containing inventory operations have a separate 8 MiB aggregate encoded
reply cap, advertised as `recording_inventory_limits.max_mcp_batch_reply_bytes`. This includes
all replies and JSON array framing, even results from other tools. Crossing it stops the
remaining batch and returns HTTP 413 `RESOURCE_LIMIT`, `retryable: false`; earlier calls may
already have executed. Inspect their state before submitting smaller batches. A batch is not
a transaction. Single-operation clients keep the 1 MiB logical inventory reply limit.

Four dedicated request workers and an 8 MiB response reservation pool bound open/list/detail
work; reservations last through response writing. Close, health and session control do not use
those four slots. Capacity refusals retain normal local-access/session admission, basic JSON
and JSON-RPC validation, notification suppression and mixed-batch control dispatch. Only the
affected inventory data operation gets a retryable capacity refusal. Refusal body reads and
writes have the ordinary streaming I/O deadline; if final-refusal workers are also full, the
exchange closes before response headers as described under HTTP Transport.
Two build workers have no waiting queue, reserve 32 MiB before admission, and have a ten-second
response deadline. A blocked OS read may outlive cancellation: its reservation remains until
it unwinds, its prefix closes, and it cannot publish a handle. These are admission bounds, not
measured heap or disk-latency guarantees.

Refusals are 400 for invalid mode/filter/cursor, 403 for access denial, 410 `INVENTORY_EXPIRED`
with a reopen-and-restart remedy, 409 `INVENTORY_CHANGED` with a refresh-and-reselect remedy,
or 413 `RESOURCE_LIMIT`. Resource refusals include boolean `retryable`; temporary pressure
also includes `retry_after_ms: 1000`. Existing native source refusals retain their codes. HTTP
and MCP return the same page facts; MCP adds true-total `limits` and `no_match` with a hint when
nothing matches. Old handles cannot survive a server restart. This inventory service does not create views or render panels. Recording-backed metadata
creation is a separate operation below; the connected panel preview uses it.

`POST /templates/check` and `visualization_template_check` also accept an anchored `session`
source reference. They admit a fresh recording prefix and resolve every requirement using each
member's complete entry set, independently of overview pages. A returned binding resolves only
when its present members agree on the exact name, type, metadata and transitive struct schemas.
Missing members stay `absent`; missing everywhere, heuristic candidates, ambiguous roles, unit
mismatches and conflicting declarations remain unresolved with per-member reasons. No unit
conversion or preferred-member selection occurs. Dynamic fields/array indices need a recorded
numeric witness; a valid declaration with no such sample stays unresolved. Schema fallbacks
retain their reported basis in warnings.

The recording preview's **Check and add field** uses the existing visualization
`POST /visualizations/v1/templates/check` operation with one exact numeric requirement and an
empty panel list. It does not create a new field-discovery API or grant a later read. The
client keeps the same session and directory grants, checks the returned source and selector,
and saves the layout only if its local intent is still current. The editor bounds a check to
15 seconds, 64 KiB of request JSON and at most 1 MiB of response JSON (256 KiB with a 16 MiB
window allowance). A capacity refusal leaves the layout unchanged and requires a new gesture.

Recording compatibility returns `source_ref`, `recording_id`, `catalog_revision`, `finalized`,
`binding_basis: fresh_recording_prefix`, `bindings`, `compatibility`,
`evidence_records_visited` and `evidence_payload_bytes`. Each binding carries member evidence
with writer identity/final hash and exact string sample counts, plus `problems` and warnings.
This is evidence for that admitted prefix, not an authorization token or a continuing lease.
A later bind/follow must admit again. Checks release every native prefix/cursor, perform no
store write and recheck grants and finalized-file boundaries before returning.

Capabilities expose `recording_binding_limits`: two concurrent checks without a waiting queue,
200,000 member-entry occurrences, at most 128 candidates for one role/member, 65,536 visited
records and 8 MiB of evaluated payload per check, a 64 KiB individual evidence-record cap, and
a 1 MiB compatibility-result allowance (`max_result_bytes`) in ordinary and MCP-escaped
encoding, with conservative serialization headroom. This result allowance excludes the outer
JSON-RPC request ID/envelope, which retain the general transport limits. Evidence scans
stream one record at a time and stop at the first witness where possible; work does not grow
with an entire selected history in memory. Crossing the cumulative payload limit can read one
additional capped record before refusing. A cooperative ten-second work budget checks between
operations; it is **not** an interruptible filesystem deadline, so cold hashes or OS reads can
outlast it while retaining their slot. The source owner's index/hash bounds still apply.
Temporary concurrency pressure returns retryable `RESOURCE_LIMIT`; fixed limits do not retry
in a loop. Select exact entries or fewer requirements when the stated limit permits that remedy.
Unreadable evidence keeps the native reader error instead of becoming a negative match.
These bounds are functional admission limits, not measured heap or throughput claims.

`POST /views/<view-id>/bindings` takes `expected_revision`; `visualization_view_bind` additionally
names `view_id` and the selected `api_version` (1 or 2). Both return the same detached binding plan. Revision is
checked before source work and again after it, without holding the working-state queue. The
plan includes all unresolved requirements and deduplicated resolved entry/field queries, at most
32; exceeding that cap refuses rather than dropping a series. `compatibility.bindings` includes
`evidence_identity`, an opaque identity of the selected declaration and transitive schemas;
it is not another source of unit or type inference. A zero-selection MCP result is `no_match`.
A read of the plan retains no prefix or follow. Direct creation of a view follow uses
[the read route](READ_API.md#following-a-recording-view) with the same revision and an active
MCP session; API 2 view bindings include `api_version: 2` inside `view_binding`. Callers do not need a preceding check. Avoid issuing a check and creation
simultaneously: they share the two compatibility slots.

`POST /views` and `visualization_view_create` accept anchored recording references for metadata
creation, and saved-open and recording clones admit that same source again. Admission shares the
two recording-compatibility slots and limits, using an empty requirement set; it does not insist
that every requirement already resolves. Each native prefix closes before the working-state
queue persists the layout. Reads of existing view metadata, edits and saves remain available
when the source is offline; new create/clone/open operations can refuse. Retry the same request
ID and intent after retryable pressure; changes require a new ID. No create/open response is
an authorization token for a later read, and a grant can change after admission.

The existing configuration contains both recorded full-range and live following-30-seconds
policies; explicit ranges and pinned template snapshots are retained. API 2 saves write metadata
into the server visualization library; API 1 retains caller-selected store saves. Reopen creates an independent working UUID, with the
anchored recording identity retained through aliases, restart and finalization. No operation
here starts a recorder, follow lease or renderer. The native Source Overview uses these metadata
operations to resume working views or open independent saved views.

API 2 library controls use `/visualizations/v2`. `/templates/list`, `/templates/get`,
`/templates/create`, `/templates/update`, `/templates/import-preview`, `/templates/import`
and `/templates/export` share the MCP application operations. Creation/import omit `store_id`;
an API 2 request that supplies it refuses. `/library/list` accepts `kind` (`view` or `template`),
optional title `query`, `limit` (1–256, default 256), and the prior `next` key as `after`.
Rows contain a pinned reference and title or an unavailable reason. Totals include unreadable
rows whose titles cannot be checked; `total_unavailable` reports that subset. Template-tool
`limits` count the `templates` and `unreadable` arrays separately. Metadata pages reflect current
state, not a fixed snapshot. `LIBRARY_CHECKPOINT_PENDING` means the definition was committed but
the working checkpoint needs recovery: retry the identical request. A definitive refusal before
publication permits a corrected save; an unknown transport outcome retains the original intent.
`/source/views` accepts the same continuation controls for source-matching saved views, alongside
working summaries. `/saved/open` accepts `request_id` and `saved_view_ref`. `/library/migrate`
accepts `kind` and a pinned store-backed `legacy_ref`; it copies one definition idempotently,
retains originals and atomically records the alias. No route creates a log store or enables ingestion.

Saved references contain `server_instance_id`, `library_id`, `saved_view_id` and `revision`;
template references use `origin: "library"`, those owner IDs, `template_id` and `revision`.
API 1 refuses library references. Saving an existing legacy working view through API 2 first
migrates its old checkpoint; a later legacy change conflicts instead of overwriting the library.
Migration does not guess a new source scope. Unavailable or ambiguous sources remain unresolved
and originals remain exportable. API 2 reference ownership does not grant source access.

Library files live in `library/` below the server's visualization state directory: `identity.json`,
`objects/view-<uuid>.json` and `objects/template-<uuid>.json`. Library mutations have a dedicated
worker, cross-process transaction lock, eight pending queue slots, and an 8 MiB serialized-input
reservation pool (2 MiB per admitted input, four admitted operations through completion).
The limits are 1,024 objects, 64 MiB accounted disk metadata including quarantined/unknown files
and temporary replacement bytes, 4 MiB per record including at most 64 combined receipts,
and 1 MiB per configuration. Ordinary controls are 64 KiB; configuration/import bodies keep
1 MiB. Definition and page replies fit 1 MiB including MCP escaping and bounded envelope/ID
space. Working save/open preflight their larger repeated configuration projections too.
Template admission also checks the extra escaping in the portable export reply.
A configuration below its raw cap can still exceed the encoded reply cap and refuse before commit.

Library HTTP/MCP requests share the four inventory request workers and 8 MiB encoded-response
pool. Reservations remain held through encoding and socket completion. Health, ping and inventory
close retain control capacity. Request/write deadlines do not release library inputs while disk
work still runs. One disk worker bounds record parsing and temporary JSON/encoding copies; four
bounded completion slots keep caller callbacks off its queue and remain charged until those
callbacks finish. These byte accounting limits are not measured JVM heap bounds. Oversized existing libraries
open for paged inspection; unreadable records remain in place and block mutations at quota, never
being deleted to make space. `LIBRARY_BUSY` is retryable; `LIBRARY_LIMIT` and `LIBRARY_UNAVAILABLE`
require changing the request or server state. An ephemeral server has no durable library and never
substitutes a laptop or recording store. Capabilities report library availability and its bounds.

Legacy API 1 store templates use `visualizations/templates/<uuid>.json`. Each atomic metadata record includes
the definition and retry/import receipts, under the existing store queue/lock. A file is bounded
to 1 MiB, each receipt map to 64 entries, and listings to 1,024 records or 8 MiB per store.
Unknown/newer/bad neighbors are reported intact. Imports require a complete readable registry
before deciding identity conflicts. Receipt exhaustion refuses rather than forgetting proof;
save a new template copy. Bundled templates are immutable. Exported JSON contains format,
schema version, definition and an RFC 8785 SHA-256 digest, never store locators or samples.
The raw envelope is limited to 1 MiB; HTTP's entire JSON request has the same 1 MiB bound,
including escaping/argument overhead. Unknown fields, duplicate keys, invalid Unicode, unsafe
revisions, non-finite numbers and excessive depth are refused. Exact reimports keep a durable
portable identity/revision/digest receipt even when the imported copy got another UUID or local
revision. A higher revision updates only with explicit mode/expected revision; copy is the default.
If a store template file is removed, get, compatibility, apply, export and update return
`TEMPLATE_UNAVAILABLE` with a missing-template reason and a hint to refresh the catalog or save
a new template from an existing view. A missing file is not revision zero. Existing working
and saved views retain their copied configurations and pinned references.

Non-deleted working state also retains acknowledged operation receipts until its detached expiry. Save and
clone reserve their input snapshot before crossing to another owner; their original answers
survive retries/restarts. Save checkpoints its reserved configuration, leaving intervening edits
dirty. Records, checkpoints and receipts share the 256 MiB aggregate quota, a 4 MiB per-record
limit and 4,096 receipts per working view. Capacity refuses without evicting unexpired work.
Deleting a working view releases its live count and byte quota. Its deleted record and receipts
remain retryable for up to six hours, under separate limits of 256 records and 8 MiB; pressure
evicts the oldest first. After expiry or eviction, a delete retry returns `VIEW_UNAVAILABLE`;
the old creation request no longer reserves that UUID. Capabilities expose
`max_deleted_view_receipts`, `max_deleted_view_metadata_bytes` and `deleted_view_lifetime_hours`.
Recovery enforces the same bounds and shortens legacy longer deadlines to at most six hours
from recovery, persisting the deadline so later restarts cannot extend it. A live quota refusal
names the records or metadata holding capacity. Saving a view alone does not release its slot;
close its editor and delete the unneeded working copy. Saved definitions and source files stay.
A new view can be created from the inspectable configuration. Saved views preserve the starting
template reference as provenance; subsequent edits remain their own configuration snapshots.
References are validated on admission and recovery. A malformed saved reference is reported
as an unreadable record; a malformed working reference or retained receipt is quarantined intact.

Clients opt into `capabilities.experimental["wpilog/visualizationNotifications"]: true` during
MCP initialize. The server advertises the same namespaced capability. Notifications use
`notifications/experimental/visualization`, with API version, server epoch, sequence, kind,
view UUID and revision. They contain no samples/configurations. Only negotiated sessions
watching that view or owning its attachment receive them. GET `/mcp` carries SSE `id:` values;
reconnect with `Last-Event-ID`. A ring holds at most 256 events or 1 MiB per session. Expired
replay or an epoch mismatch returns `resync_required`, so clients reread/reattach current views.
If optional visualization state cannot initialize, ordinary SSE heartbeats remain available;
preview routes remain unavailable and the stream stays ping-only until the server is restarted.
Streams use the already-open notification owner; shutdown drains streams and admitted calls
before closing that workspace.
Explorer reinitializes and re-registers its grants after session replacement, with bounded
reconnection backoff. `connect` reconnects a dropped stream with replay IDs; an expired session
still needs the bridge's existing new-initialize/restart remedy. Direct stdio serializes control
notifications beside responses. This stage delivers revisions only: display requests, client
selection, automatic window opening, live presentation and telemetry follow are later stages.

Without a PID record, `start` and `connect` adopt a server only when its health name matches
the requested configuration. Another name is refused with its owner, PID, stop command and
the option to choose a different port. A server that reports no name keeps the previous
adoption rules, with a warning naming its version; its identity cannot be checked this way.

Running `start http` while it is up reports the running server rather than starting another. If the running server is another version, because you upgraded since it started, `start` stops it and starts the new version in its place, so an upgrade never leaves an old server serving. If something that is not wpilog-mcp holds the port, `start` says so and starts nothing; choose another port.

To stop the server:
```bash
wpilog-mcp stop http
```
It finishes the calls in progress, then exits, and `stop` returns once it has. A `stop` of a server that is not running succeeds too. The server accepts a stop only from this machine, and only with a token that `start` wrote to `~/.wpilog-mcp/run/http.token`, a file only you can read: no one else on the machine can stop it, and nothing on the network can. A server that does not answer the request within the start timeout is ended as a process. A server from before this version has no stop endpoint and is ended as a process straight away.

### One Server for Every Client

An `http` server can serve every MCP client on the machine at once, where a stdio server serves the one client that started it. A client that takes a URL connects to `http://127.0.0.1:2363/mcp` directly. A client whose configuration takes only a command to run (Claude Desktop's file is one) uses `connect`, which relays the client's standard input and output to the server:
```bash
wpilog-mcp connect http                      # the "http" server, started first if it is not running
wpilog-mcp connect --url http://other-laptop:2363     # any server, by URL, started by nobody
```
`connect <name>` does what `start <name>` does first, so a client started with it gets a server
whether or not one was running, and a client started beside a running one shares it; `connect
--url` starts nothing. Each connected client gets a session of its own, ended when the client
closes its end. With `idle_exit_minutes` set (see [Config Fields](#config-fields)) and
data-store sync paused, not configured or disabled by the feature flag, the server exits on its own once every client has
gone and the time has passed, so a server that is started on demand need never be stopped by
hand.

The VS Code extension always uses this install’s `http` server. It starts the launcher with the home configuration explicitly, then registers its window’s directories and secret-storage key by lease. Every client shares the loaded logs and disk cache ([extension guide](../vscode-extension/README.md#the-standalone-install-is-the-server)).

The server listens only on `127.0.0.1` unless `WPILOG_HTTP_BIND` says otherwise (see [Command-Line Flags](#command-line-flags)); set it before `start`. The HTTP transport has no authentication, so anyone who can reach the port can use the server.
The routes this guide calls loopback-only also refuse a request that carries a header proxies add
(`Forwarded`, `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Real-IP` or `Via`),
even over loopback, because a reverse proxy on the same machine connects from loopback too. A
proxy in front of the server must add one; nginx does not unless configured, as in the
[pit guide's example](pit-server/STANDALONE.md).

Started with flags, `wpilog-mcp --http` runs the HTTP server in the foreground instead, as the Docker image below does.

For a systemd service or a bare terminal, set a UTF-8 locale before starting the JVM:
`LANG=C.UTF-8 LC_ALL=C.UTF-8` on Linux (install that locale if the operating system does not
provide it). A systemd unit can use `Environment=LANG=C.UTF-8 LC_ALL=C.UTF-8` in `[Service]`;
restart an already running server after changing it. The Temurin Docker image below already
sets a UTF-8 locale. Without one, opening a store warns once, and filenames the platform
cannot represent are refused with the encoding and this remedy. Changing `file.encoding`
alone does not change the JVM's native filename encoding.

### Directories by lease

A local client may grant the shared server access to directories for the life of its MCP session. This is an HTTP registration endpoint, not an MCP tool: the person decides which files the server may read. Leases are visible to all sessions, including agents that connect independently. Both routes require `Mcp-Session-Id`, pass the MCP Origin check, and return 403 when the server is bound to anything except loopback, even for a request arriving locally, and for a request forwarded by a proxy.

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
GET /data/entries?path=<log>&names=<entry>[,<entry>...][&start_time=<s>][&end_time=<s>][&max_points=<n>][&format=arrow|csv][&utc=true|false][&angle=radians|degrees]
```

`path` is the log, as `list_available_logs` lists it. `names` is a comma-separated list of one or more of: an entry; an entry with a field path appended (`/Drive/Pose.translation.x`, as `get_statistics` takes it); or a REV log signal's key as `list_revlog_signals` gives it (`REV/SparkMax_3/AppliedOutput`). `start_time` and `end_time` are seconds, as every tool takes them. `max_points` buckets by the same rule as `read_entry`'s `max_points` (see [TOOLS.md](TOOLS.md#read_entry)): at most that many buckets of equal duration over the window, each with its count, minimum, maximum, mean, first, and last.

WPILOG bucket membership and first/last order use original integer microseconds; equal original
timestamps retain file order. Explicit second bounds round to the nearest microsecond, and the
last bucket includes the window end. Arrow retains original long timestamps and exact bucket
starts. CSV seconds labels can round adjacent large timestamps to the same displayed number.

Selected scalar struct-angle fields unwrap in time order before exact output or reduction:
179° followed by −179° becomes 179°, 181°. For plain numeric values, `angle=radians` or
`angle=degrees` requests the same treatment (`rad` and `deg` are accepted aliases).
The option applies to every selected series and is validated even for an empty window.
Each requested window starts with its first logged value; nonfinite rows stay counted and
do not reset continuity. Wildcard fields remain unordered arithmetic pools. An explicit
angle on a whole struct is refused: select its numeric field. An unwrapped integer series
uses Arrow float64 so fractional turns are not truncated; ordinary typed reads keep their types.

`utc=true` adds measured calendar time beside the existing log timestamp. Only the literal
values `true` and `false` are accepted, once; omission or `utc=false` preserves the existing
columns and metadata. The clock comes from the log's selected `systemTime` or supported
`EpochTimeMicros` entry, including its existing `NT:` and AdvantageKit aliases. The existing
clock-set policy retains plausible readings after the last clock jump. UTC interpolates epoch
microseconds between those readings, keeps the last reading's offset afterward, and is null
before the first valid reading. The final epoch is rounded once to the nearest microsecond,
ties to the even epoch microsecond. A filename, file modification time or server clock never
supplies this value. Reduced rows map the bucket's exact start, which can precede the first
valid clock reading even when a later sample in the bucket has a set clock.

The wall-clock inverse used by [`search_system_logs`](TOOLS.md#search_system_logs) shares
the exact mapping arithmetic and ambiguity checks, rounding only its final robot-seconds
result to a binary double. It retains its existing first/last-offset extrapolation and
session bounds; the data endpoint's before-first-reading UTC value remains null.

A REV signal streams on the wpilog's clock, as `get_revlog_data` reads it. Its entry metadata carries `rev` with the device, the signal, the bus, `sync_method`, `timestamps_aligned`, `offset_seconds`, and `sync_confidence`, so a reader knows the offset's basis. A REV log that could not be synchronized is refused with the reason; one still synchronizing answers `503` with `Retry-After`.

- `format=arrow` (the default) is the Apache Arrow IPC streaming format, `application/vnd.apache.arrow.stream`.
  - Columns: a `timestamp` column in the log's microseconds and a `value` column typed by the entry (a float, an integer, a boolean, text, a struct with the schema's fields, a list for an array). Bucketed: `timestamp`, `count`, `min`, `max`, `mean` (null where no sample in the bucket is finite), `first`, and `last`.
  - With `utc=true`, nullable `timestamp_utc` follows `timestamp`; it is an Arrow timestamp with microsecond units and zone `UTC`. The original timestamp remains unchanged.
  - One stream has one schema, so the entries in one request must share a value type; bucketed, any numeric entries go together.
  - Enum fields carry `value` and `label`; a number absent from the schema has a null label (CSV keeps the number at the field name and an empty `.label` cell).
  - Each record batch is tagged with its entry in its message metadata. The schema's metadata carries what a tool result would: the server version, `inputs`, the log's time range, and per entry its type, its sampling class (periodic, change-only, event), the unit its name states by a conventional suffix where it does, its decoded `sample_count`, its original `total_records`, and, bucketed, its bucket length.
  - An entry with decoding failures also carries `decode_problem` (`entry`, `failed_records`, `total_records`, and the first failure’s `reason`) and the same `warning` text tools return; CSV carries these fields in its `# entries:` comment.
  - `pyarrow.ipc.open_stream` or `polars.read_ipc_stream` reads the response bytes; `read_next_batch_with_custom_metadata()` gives each batch's entry.
- `format=csv` is one table per entry in the form `export_csv` writes (`timestamp_sec`, then the value's flattened columns, or the bucket columns), after `#` comment lines with the server version, `inputs`, and the entries' metadata, and a `# entry: <name>` line before each table.
  With `utc=true`, `timestamp_utc` follows `timestamp_sec`, in ISO 8601 with six fractional
  digits and `Z`; null is an empty cell. Expanded array rows repeat UTC and keep their index.

With UTC enabled, `inputs.utc` is true and each entry's metadata adds `utc_basis`, in the
Arrow schema's `entries` metadata or CSV's `# entries:` comment:

| Field | Meaning |
|---|---|
| `entry` | Exact selected wall-clock entry name, or null when none exists. |
| `reading_count` | Retained valid readings, including identical repeated pairs. |
| `first_offset_us` | First retained epoch minus its log coordinate, as an exact decimal string. |
| `last_offset_us` | Last retained epoch minus its log coordinate, as an exact decimal string. |
| `max_offset_change_us` | Largest absolute change between consecutive retained offsets, as an exact decimal string; zero with one reading. |
| `rounding` | `nearest_microsecond_ties_to_even`. |
| `null_sample_count` | Null logical sample or bucket coordinates, counted before CSV array expansion. |
| `null_reasons` | Objects with `reason` and `count`, one for each cause of null coordinates. |

The three offset fields are null when there are no valid readings. An ambiguous clock retains
its measured basis but supplies no UTC timestamps. Identical repeated pairs are redundant;
conflicting duplicate coordinates or a backward epoch mapping make the retained mapping
unavailable. `null_reasons` uses these reasons:

- `No wall-clock entry`
- `No valid wall-clock readings`
- `Before the first valid wall-clock reading`
- `Ambiguous wall-clock mapping: one log timestamp has conflicting epoch readings`
- `Ambiguous wall-clock mapping: one epoch reading names multiple log timestamps`
- `Ambiguous wall-clock mapping: epoch readings move backwards`
- `Mapped UTC timestamp is outside the signed 64-bit microsecond range`

The added UTC payload participates in the response size estimate. `/read/v1` has no UTC option.

```bash
curl 'http://127.0.0.1:2363/data/entries?path=/Users/me/riologs/akit_26-03-21_16-29-56_vache_q10.wpilog&names=/SystemStats/BatteryVoltage&start_time=20&end_time=40&format=csv'
```

**Access and limits.** The endpoint reads only files inside configured or currently leased log directories, as every tool does, and refuses a request from a web page as the MCP endpoint does. A response is capped at 512 MiB: a request over the cap is refused with the row count, the size, and the hint to narrow the window or pass `max_points`; it is never cut short. A missing entry is an error naming it, with the tools that list entries and field paths.

**Changing files and caching.** A file that changes while it streams ends the stream with an empty batch whose metadata says so (`file_changed`), or a `# file_changed:` line in CSV, and a reader discards what it received. `ETag` comes from the file and the query, plus each requested REV source file and its synchronization method, offset, drift, and confidence, so a repeated request for an unchanged file with `If-None-Match` is a `304`.

`get_server_guide` names the endpoint as `data_endpoint` whenever the transport is HTTP; a stdio server has none, and `export_csv` is the way there.

### The native read API

`/read/v1` is the exact typed-read protocol for custom applications: a manifest catalog, retained
WPILOG snapshots and bounded sample queries in Arrow or typed NDJSON. Viewers, scripts and
dashboards use the bulk presentation route `/data/entries`; the native protocol serves clients
that need explicit snapshot, session or follow lifetimes and typed values. Queries use exact integer
microseconds and preserve native values; snapshots expose schema provenance and file boundaries.
Queries can project struct fields, reduce numeric values to min/max buckets, request several
entries or combine session files with recorded clock proof; REV queries decode the named signal.
Session reads reuse the server's indexes and live recording prefixes; reopen to see new samples.
`/read/v1/follows` adds acknowledged ordinal pages for one provenance-anchored recording,
including its rollover files. Session merge aliases survive store writes, peer synchronization
and mirror copies; an alias cannot widen a follow to another recording in the merged session.
Follows retain at most 32 leases, expire after 60 idle seconds, and wait up to 25 seconds on
16 separate workers. Creation and closure use ordinary read workers so waiting followers cannot
occupy their control capacity. See the [follow protocol](READ_API.md#following-a-server-recording)
for retry cursors, final hashes, explicit history loss and cancellation. Pages carry `ended`;
acknowledging the terminal page returns 410 `FOLLOW_ENDED` until closure or expiry. Follow 413
refusals carry `retryable` so clients distinguish temporary byte pressure from structural limits.
Native-read 409 refusals carry it too: a catalog still changing after two scans returns
`FILE_CHANGED` with `retryable: true`. Retry the same request/cursor with backoff; ordinary
open-capture growth is accepted during catalog refresh. Permanent history/source refusals carry
`retryable: false` and require their stated remedy, rather than repeated polling.
Writer identity persists through finalization, and bounded turns share pages among selected signals.
Callers may explicitly negotiate `frontier_scope: "queries"` to keep metadata for queried entries
and their required struct schemas within 1 MiB pages, while retaining every recording member's
identity and the full internal history proof. Omission keeps complete descriptors. The create
reply acknowledges the scope; an older server ignoring the option has not negotiated it.
This does not enlarge the default full inspection's separate 4 MiB limit; Source Overview now uses the 1 MiB paged inventory operations.
These are custom-reader
routes; the optional visualization editor still provides the recorded workflow.
A standalone library excludes open captures it cannot freeze and names the recording-server route
as the remedy. Session indexes share a bounded catalog allowance as described in the API guide.
A terminal status proves stream completion. These additive routes follow the same Origin and path
admission as other read routes, and store files must be manifested. They make no store changes.
The `/data/entries` and MCP admission and wire formats remain separate; WPILOG reductions now
share the documented integer-microsecond bucket rule. [READ_API.md](READ_API.md) documents the
routes, limits, Java local/remote library, recorded REV access and Python example.

### The Import Endpoint

The HTTP transport accepts imports beside the data endpoint. Server-path JSON imports and
assignments require a direct loopback connection, not one forwarded by a proxy; the byte-upload
route below also accepts network connections. All keep the Origin check:

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

Installation adds its versioned JAR and launcher, and leaves the configuration alone. It repoints `wpilog-mcp` only when the installing version is newer, or with `--force`. Version numbers compare numerically; an unsuffixed release follows a suffixed version with the same numbers, and suffixes compare their numeric and text components (`dev10` follows `dev9`). An unreadable or unmarked current launcher counts as missing.

A second install with identical files leaves them alone and reports `repointed: false`; changed bytes of the same version are refreshed atomically, as development builds need. `./gradlew install` always passes `--force`. An `install.lock` file serializes simultaneous installers, and a busy install is reported.

Ordinary installation never stops or restarts a running daemon. Older versions stay in `jars/` and `bin/` until you delete them. MCP clients run the new version the next time they start the server. A running HTTP server keeps the old version until the next `start` or `connect` of its name, which stops it and starts the new version, or until you `stop` it.

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
  - Claude Code: `~/.claude.json` for the current user-scope registration; a project's `.mcp.json` may still contain a retained manual or legacy entry (see [migration](#moving-from-a-project-mcpjson))
  - Claude Desktop: `~/Library/Application Support/Claude/claude_desktop_config.json`
  - Codex: `~/.codex/config.toml`; Antigravity: `~/.gemini/antigravity/mcp_config.json`
  - HTTP server log: `~/.wpilog-mcp/logs/<name>.log`; its process ID and port: `~/.wpilog-mcp/run/<name>.pid`; the token `stop` presents: `~/.wpilog-mcp/run/<name>.token`
- **Server times out**: usually the Java version or a wrong path. The launcher needs Java 17 or newer; [Requirements](#requirements) gives the order in which it looks. Check the one it would use:
  ```bash
  ~/wpilib/2026/jdk/bin/java -version   # or: java -version
  ```
- **Out of memory with large logs**: set `WPILOG_MAX_HEAP=8g` in the environment the server starts in. For an MCP client, that is the `env` of the server's entry, since the client starts the launcher. In VS Code, set the extension's `maxHeap` setting and restart the server; the extension passes it to the launcher.
- **Log reported as truncated**: a log cut short (by a power loss on the robot, for example) still loads. The server reads every complete record and reports the log as `truncated`.

### Recording health and view recovery

`POST /visualizations/v1/source/health` accepts `{ "source_ref": ... }` for an anchored
recording; `visualization_source_health` is the same operation for agents. It reports current
acquisition separately from manifested history and rechecks grants without reading log payloads.
`recording` means acquisition is connected and synchronized, including when selected signals
have not changed. `reconnecting` refers only to that recording's acquisition. `inactive` never
borrows another recording's connection; a history manifested as open with no matching local
acquisition says so explicitly. The result is metadata, not proof that a later data read will
be admitted. Follow admission still checks writer identity, file boundaries and hashes.

Visible panels share one health read per source, at most every five seconds after the previous
read finishes, with a five-second client deadline and a 16 KiB reply cap. Health uses the same
bounded dispatch pool and 64 KiB request limit as inventory reads. A failed check is shown as
unavailable; it does not interrupt a healthy follow. Hiding or closing the last view cancels
that health read. A completed follow automatically bootstraps again only when the same anchored
recording becomes live; the old buffers are replaced, so replay cannot append duplicates.

When the server replaces an editor's session, the editor suspends old data generations,
re-registers its window allowance, and reattaches the same working UUID before rebootstrap.
Registration and attachment failures retry with a one-to-ten-second backoff. Configuration,
source anchor and display pause intent survive; old handles and sample buffers do not.
Pending local edits retain the controller's conflict rules. A missing working view, changed
server identity, unavailable source or revoked grant stays explained; recovery creates no
replacement view and starts no acquisition. Closing a view cancels its recovery.

The display reports host page receipt to render acknowledgement and renderer-local elapsed
milliseconds after animation-frame scheduling. Health can report the server's latest capture
receipt-to-publication duration with writer/revision coordinates. These independent clock-domain
measurements are not source-to-screen latency; end-to-end percentiles still require clock
calibration and the workload gate.

### Qualified data requests

`GET /data/entries` accepts optional `scope_id` and `server_instance_id`. A mismatched owner
returns 409 before reading, and the shared log admission enforces the selected scope. A source
selection refusal also returns 409 with its `code`, bounded `source_scopes` choices and `hint`;
a missing file still returns 404. The
optional URL-encoded `source_ref` is a complete schema-2 JSON reference; its scope must agree
with `scope_id` if both are present. The source is freshly resolved and its path/file boundary
checked before replying, including cached 304 replies. Invalid qualification refuses instead
of falling back to another path or serving copy. Query text is capped at 64 KiB. The direct
Explorer uses these checks for samples; notebooks retain owner and scope without embedding
session IDs or credentials. Existing unqualified requests retain their legacy behavior.

### Explicit replica-format migration

Format-1 mirrors keep automatic transfers paused; format-2 mirrors pull finalized replicas without retention eviction. Existing files remain
readable. Configure a mirror, observe `migration_required`, then use its local
`POST /store/mirror/migrate` control with the exact store UUID. A successful upgrade reports
`ready`; the next synchronization pass contacts the configured master and eligible peers.
Generation recovery and explicit local cleanup are available; see the mirror reference for revision-bound requests.
The replica summary's `catalog_capacity` reports record and exclusion counts, their limits,
and `near_limit`, which becomes true at 3,277 of either 4,096-count limit. A missing catalog
capacity means no valid catalog facts are available, not an empty catalog. Encoded metadata
limits and payload capacity are separate. See the [mirror reference](pit-server/STANDALONE.md#the-mirror)
for metadata limits and the count gauges.

For a master, `POST /store/replicas/migrate` accepts `{ "store_id": "<UUID>" }` and returns 202
with `job_id`. Poll `GET /store/replicas/jobs/<job_id>` for `running`, `complete` or `error`.
At most eight jobs are remembered per transport; completed jobs make room first, and restart
clears job history. Retrying migration preserves published identities. These routes require
a direct loopback connection and the usual Origin checks. Unknown selections are 409; malformed
requests are 400, forbidden callers 403 and unknown jobs 404. Migration bodies are limited to
64 KiB. Mirror and migration controls share the four store streaming workers and their I/O
progress deadlines; saturation returns retryable 503 without occupying health dispatch.

See [the mirror reference](pit-server/STANDALONE.md#the-mirror) for preservation, capacity,
format backups, page limits, the replica wire protocol and discovery configuration. No production migration
is performed merely by configuring or starting the server.

### Remote replica publication status

After `GET /replicas/v1/handshake`, send its `session_id` in `Replica-Session` and
`POST /replicas/v1/status` with `store_id` from that handshake. The optional `limit` is
1–256 (default 64). This network read route uses the ordinary Origin checks, 64 KiB request
cap, 1 MiB encoded reply cap and replica metadata workers; it does not require loopback.
The handshake advertises the additive `publication_status` capability.

A complete reply has `status: "ok"`, `store_id`, `catalog_epoch`, `catalog_revision`,
`catalog_capacity`, `publication_state: "ready"`, `pending_total`, `known_pending_count`,
`oldest_failed_at`, `oldest_time_complete` and a `pending_publications` array. Each entry
contains `session_id`, `code`, a safe `message`, `first_failed_at`, `failed_at` and
`cause_durable`. Missing old diagnostic fields mean `CAUSE_NOT_RECORDED` and null times.
An unknown first-failure time makes `oldest_time_complete` false and `oldest_failed_at` null.
Count or byte shortening adds `limits.pending_publications` with `returned`, true `total`
and requested `limit`; it does not change the total.

Add exact `session_id` to look up a session beyond the summary. A known pending receipt
returns HTTP 409, `code: "PUBLICATION_PENDING"`, `retryable: true`, the selected IDs and
its structured `cause`. A published session returns `publication_state: "published"`.
A proven absent session returns nonretryable `SOURCE_UNAVAILABLE`. Unfinished or incomplete
recovery returns retryable 503 `BUSY`, except that a known pending session may still return
its specific cause. Duplicate pending session identities refuse selection. Failed disk
annotation reports `cause_durable: false`; the safe cause is visible in memory, but may not
survive restart. Status never initiates recovery, discovers roots or waits for a store turn.

The route accepts only `store_id`, `session_id` and `limit`; malformed or unknown fields
return 400, denied Origins 403, oversized bodies 413 and expired sessions 409. Retryable
status refusals include `Retry-After: 1`. A revoked directory grant or replaced store is
rechecked on every read. New stores require a new handshake. `/replicas/v1/file` still
requires an already published immutable file identity and SHA-256; session status does not
grant file access or invent an identity for unpublished content.
