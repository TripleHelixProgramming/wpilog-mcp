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
| `diskcachedir` | Directory for the persistent disk cache | `WPILOG_DISK_CACHE_DIR` from the environment, else the system's default (below) |
| `diskcachesize` | Maximum disk cache size, in MB | `8192` |
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

`wpilog-mcp service-unit <name> [--config <file>]` prints three files separated by
`# file: <name>` comments: `wpilog-mcp-<name>.service`, its `-health.service` probe and
`-health.timer`. It validates an HTTP configuration with no idle exit, prints to stdout, and
never writes under `/etc`. Names use ASCII letters, digits, underscores and hyphens.

On a Linux host with systemd, Java 17 or newer available as `java`, and `/usr/bin/curl`:

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
   # Copy the three sections from pit-units.txt to their named files, with sudo.
   sudo systemd-analyze verify /etc/systemd/system/wpilog-mcp-http*.service /etc/systemd/system/wpilog-mcp-http-health.timer
   sudo systemctl daemon-reload
   sudo systemctl enable --now wpilog-mcp-http.service wpilog-mcp-http-health.timer
   curl --fail http://127.0.0.1:2363/health
   sudo journalctl -u wpilog-mcp-http.service -u wpilog-mcp-http-health.service -f
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

The unprivileged health timer probes every 30 seconds with a five-second HTTP deadline and
records failures in the journal. It reports; it does not restart a process merely because a
probe failed. Stopping the main service stops its timer too; start both again when resuming.
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
import. **Manage Data Store…** edits the shared policy, shows status and opens the store folder;
**Sync Data Store Now** requests discovery and retries unchanged refusals,
**Pause/Resume Data Store Sync** controls ingestion,
and **Show All Sources** switches the listing. Pausing retains files and the readable store.

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

The default `list_available_logs` view searches the preferred store and enrolled sources, hiding
an original only when its current version has a receipt and its stored copy still exists.
Pending, changed and refused source files remain visible. `view: all` includes the originals and
other readable directories. Explicit file paths and the standalone reader's root selection keep
their meaning. A REV file arriving before its WPILOG is reconsidered when the store gains a new
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

**With the daemon running.** The command posts sources inside the configured directories to the daemon's import endpoint and prints the job's progress and its complete result. Sources outside them, such as a USB stick, are copied (or moved, with `--move`) into the store's inbox for the daemon to import; the command says where it put them, and their results land in `inbox/imported.log`. A failed HTTP request is reported, and no second importer is started.

With `--robot`, the command writes `batch.json` beside the batch files (`{"stated_robot":"practice"}`), and the daemon uses that stated robot when a log has no logged identity. A robot name uses letters, digits, dots, hyphens and underscores, and must be a portable filename (no Windows device names, no trailing dot).

**Without a daemon.** The command imports in its own process, staging outside files through the inbox as needed, with `--robot` applied to that import. It holds a `FileChannel` lock on `store.lock` for the whole import, as the daemon does; if another process holds the lock, the import fails promptly with a retry message. Do not delete `store.lock`: the persistent file is what lets every process lock the same object. A lock file that is a symbolic link, even a dangling one, is refused before an HTTP import or assignment is accepted, and lock opens never follow links.

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
At most 32 MCP requests run at once (fewer on small machines), with overload reported as HTTP
503 rather than an unbounded wait. Data streams and native reads use 16 separate workers;
store transfers and imports have another four, so readers cannot exhaust upload capacity.
Excess transfers receive JSON `BUSY` with HTTP 503 and a one-second retry hint. Ten seconds without IO progress ends a transfer, releasing
its reads, so clients that stop reading cannot occupy health's dispatch workers indefinitely.
Writes use progress units of at most 64 KiB. Health and registration use a dispatch pool growing
from four to 32 workers without a waiting queue. If other handlers occupy all 32, the JDK still
rejects the next exchange by closing its connection without an HTTP response. That reset differs
from the explicit MCP and streaming admission refusals.

For browser-based or multi-client access, start the `http` server from `servers.yaml`:
```bash
wpilog-mcp start http
```

The server starts in the background, and the command returns once it answers. Clients connect to `http://127.0.0.1:2363/mcp`: the port is the server's `port`, and `WPILOG_HTTP_PATH` changes the path. The server writes its log to `~/.wpilog-mcp/logs/http.log` and its process ID to the first line of `~/.wpilog-mcp/run/http.pid`. `GET /health` answers as soon as the server is up, with the server's version, configuration `name`, process ID, `managed` ownership, `server_instance_id`, and `working_state`. `name` is null for HTTP started with the unnamed legacy flags; stdio has no health route.

<a id="recorded-visualization-preview"></a>

Named servers persist their local visualization owner identity in
`~/.wpilog-mcp/state/visualizations/<config-key>/server.json`. The key is SHA-256 of the real
configuration path, a newline, and the server name; changing the port preserves it. Health and
`get_server_guide` report the same `server_instance_id` with `working_state: "durable"`.
If that state directory cannot be opened (including a refused symlink or permission denial),
the server logs a warning and continues with a fresh ephemeral identity. Unnamed servers also
report `working_state: "ephemeral"`. In both cases, `working_state_reason` explains why durable
recovery is unavailable; restarting may change the owner ID. This fallback does not stop log
analysis or recording.
This identity creates no log store or ingestion policy. The explicit recorded preview uses
`/visualizations/v1` on a loopback-bound HTTP server, with a loopback peer, the normal Origin
check and a live `Mcp-Session-Id`. Network-bound servers refuse these routes even to a local peer.
The `visualization_*` MCP tools share the same application owner and require trusted local
transport authority (direct stdio or loopback HTTP). The HTTP preview adapter uses GET for `/capabilities`, `/stores`
and `/views/<uuid>`; POST supplies `/source`, `/source/describe`, `/source/resolve`,
`/source/value`, `/source/views`, `/views`, `/views/<uuid>`, `/views/<uuid>/attach`,
`/views/<uuid>/detach`, `/views/<uuid>/describe`, `/views/<uuid>/save` and `/saved/open`.
POST also supplies `/source/statistics`, `/source/raw`, and `/templates/list`, `/templates/get`,
`/templates/check`, `/templates/create`, `/templates/update`, `/templates/import-preview`,
`/templates/import`, `/templates/export`. The import preview is read-only; mutation rechecks
under the store lock. MCP schemas and results are listed in [TOOLS.md](TOOLS.md).
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
limits (4,096 points and 32 series per view). HTTP JSON replies are bounded to 16 MiB; plot replies
are bounded to 1 MiB each in the preview client. Saved metadata listing stops at 1,024 records or
8 MiB per store. Invalid/newer records are skipped intact and reported in `unreadable` with
`store_id`, `file` and `reason`. A bounded partial listing sets `listing_incomplete`; opening a
known saved UUID reads only that record and is independent of the listing budget. Saved/draft
overview lists carry summaries, not configuration buffers. A full or unavailable state workspace
explains the refusal; health remains available. Plots use the shared Arrow reader, while cursor
tables select original samples and their decoder diagnostics. Title, geometry and style edits
reuse loaded series and values; changing the required series or recorded range starts a new
query. Statistics delegate to `get_statistics`; raw pages use the reader's physical ordinals
and retain equal/backward timestamps. The API permits 4,096 records and 1 MiB per raw response;
Explorer requests 256 and replaces its previous page. The source catalog is owned once per
workspace and replaced when its configured roots change; access is revalidated on each operation.

Store templates use `visualizations/templates/<uuid>.json`. Each atomic metadata record includes
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

Started with flags, `wpilog-mcp --http` runs the HTTP server in the foreground instead, as the Docker image below does.

For a systemd service or a bare terminal, set a UTF-8 locale before starting the JVM:
`LANG=C.UTF-8 LC_ALL=C.UTF-8` on Linux (install that locale if the operating system does not
provide it). A systemd unit can use `Environment=LANG=C.UTF-8 LC_ALL=C.UTF-8` in `[Service]`;
restart an already running server after changing it. The Temurin Docker image below already
sets a UTF-8 locale. Without one, opening a store warns once, and filenames the platform
cannot represent are refused with the encoding and this remedy. Changing `file.encoding`
alone does not change the JVM's native filename encoding.

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

`path` is the log, as `list_available_logs` lists it. `names` is a comma-separated list of one or more of: an entry; an entry with a field path appended (`/Drive/Pose.translation.x`, as `get_statistics` takes it); or a REV log signal's key as `list_revlog_signals` gives it (`REV/SparkMax_3/AppliedOutput`). `start_time` and `end_time` are seconds, as every tool takes them. `max_points` buckets by the same rule as `read_entry`'s `max_points` (see [TOOLS.md](TOOLS.md#read_entry)): at most that many buckets of equal duration over the window, each with its count, minimum, maximum, mean, first, and last.

A REV signal streams on the wpilog's clock, as `get_revlog_data` reads it. Its entry metadata carries `rev` with the device, the signal, the bus, `sync_method`, `timestamps_aligned`, `offset_seconds`, and `sync_confidence`, so a reader knows the offset's basis. A REV log that could not be synchronized is refused with the reason; one still synchronizing answers `503` with `Retry-After`.

- `format=arrow` (the default) is the Apache Arrow IPC streaming format, `application/vnd.apache.arrow.stream`.
  - Columns: a `timestamp` column in the log's microseconds and a `value` column typed by the entry (a float, an integer, a boolean, text, a struct with the schema's fields, a list for an array). Bucketed: `timestamp`, `count`, `min`, `max`, `mean` (null where no sample in the bucket is finite), `first`, and `last`.
  - One stream has one schema, so the entries in one request must share a value type; bucketed, any numeric entries go together.
  - Enum fields carry `value` and `label`; a number absent from the schema has a null label (CSV keeps the number at the field name and an empty `.label` cell).
  - Each record batch is tagged with its entry in its message metadata. The schema's metadata carries what a tool result would: the server version, `inputs`, the log's time range, and per entry its type, its sampling class (periodic, change-only, event), the unit its name states by a conventional suffix where it does, its decoded `sample_count`, its original `total_records`, and, bucketed, its bucket length.
  - An entry with decoding failures also carries `decode_problem` (`entry`, `failed_records`, `total_records`, and the first failure’s `reason`) and the same `warning` text tools return; CSV carries these fields in its `# entries:` comment.
  - `pyarrow.ipc.open_stream` or `polars.read_ipc_stream` reads the response bytes; `read_next_batch_with_custom_metadata()` gives each batch's entry.
- `format=csv` is one table per entry in the form `export_csv` writes (`timestamp_sec`, then the value's flattened columns, or the bucket columns), after `#` comment lines with the server version, `inputs`, and the entries' metadata, and a `# entry: <name>` line before each table.

```bash
curl 'http://127.0.0.1:2363/data/entries?path=/Users/me/riologs/akit_26-03-21_16-29-56_vache_q10.wpilog&names=/SystemStats/BatteryVoltage&start_time=20&end_time=40&format=csv'
```

**Access and limits.** The endpoint reads only files inside configured or currently leased log directories, as every tool does, and refuses a request from a web page as the MCP endpoint does. A response is capped at 512 MiB: a request over the cap is refused with the row count, the size, and the hint to narrow the window or pass `max_points`; it is never cut short. A missing entry is an error naming it, with the tools that list entries and field paths.

**Changing files and caching.** A file that changes while it streams ends the stream with an empty batch whose metadata says so (`file_changed`), or a `# file_changed:` line in CSV, and a reader discards what it received. `ETag` comes from the file and the query, plus each requested REV source file and its synchronization method, offset, drift, and confidence, so a repeated request for an unchanged file with `If-None-Match` is a `304`.

`get_server_guide` names the endpoint as `data_endpoint` whenever the transport is HTTP; a stdio server has none, and `export_csv` is the way there.

### The native read API

`/read/v1` gives custom applications the shared reader directly: a manifest catalog, retained
WPILOG snapshots and bounded sample queries in Arrow or typed NDJSON. Queries use exact integer
microseconds and preserve native values; snapshots expose schema provenance and file boundaries.
Queries can project struct fields, reduce numeric values to min/max buckets, request several
entries or combine session files with recorded clock proof; REV queries decode the named signal.
Session reads reuse the server's indexes and live recording prefixes; reopen to see new samples.
A standalone library excludes open captures it cannot freeze and names the recording-server route
as the remedy. Session indexes share a bounded catalog allowance as described in the API guide.
A terminal status proves stream completion. These additive routes follow the same Origin and path
admission as other read routes, and store files must be manifested. They make no store changes.
The existing `/data/entries` format and MCP tools keep their behavior. [READ_API.md](READ_API.md)
documents the routes, limits, Java local/remote library, recorded REV access and Python example.

### The Import Endpoint

The HTTP transport accepts imports beside the data endpoint. Server-path JSON imports and
assignments require a loopback connection; the byte-upload route below also accepts network
connections. All keep the Origin check:

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
