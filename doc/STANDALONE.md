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

A tag selects an exact published release; `--pre-release` selects the newest published release, including pre-releases. Add `--with-extension` to install both halves from that release. The example works once the maintainer publishes that tag; a branch or an untagged development version is not downloadable:

```bash
curl -fsSL https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.sh | sh -s -- --tag v0.10.0-dev1 --with-extension
```

In PowerShell, download the script to a temporary file, then run it with the same flags:

```powershell
$installer = Join-Path $env:TEMP 'wpilog-install.ps1'
try {
    Invoke-WebRequest https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.ps1 -OutFile $installer
    & $installer --tag v0.10.0-dev1 --with-extension
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
      max_file_bytes: 1073741824
      gateway: {port: 0}  # Set 5810 to serve dashboards through the pit server
      pull:
        enabled: false
        directories: [/home/lvuser/logs, /u/logs, /U/logs]
        settle_sec: 5
        rate_bytes: 1000000
        ssh: {user: lvuser}
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
| `capture.max_file_bytes` | File bound including declarations and finishes, default `1073741824` bytes (1 GiB); integer from `256` through `1099511627776` (1 TiB). Rollover stays in the same session |
| `capture.hot_window_sec` | Values retained in memory, default `600` seconds; expiry follows completion of the 250 ms asynchronous flush tick, at most four remaps per second. `0` reads flushed values from the capture file |
| `capture.gateway` | Optional read-only NT4 gateway; omitted means disabled; `{}` enables its default port |
| `capture.gateway.port` | Integer `0`–`65535`, default `5810` within the gateway block; `0` disables it. Binds to the same address as HTTP, on this separate port |
| `capture.pull` | Optional robot log pulling block; capture alone needs no SSH |
| `capture.pull.enabled` | Opt in to SFTP pulling, default `false` until the shop test passes |
| `capture.pull.directories` | Absolute remote directories, recursively scanned for `.wpilog` and `.revlog`; defaults `/home/lvuser/logs`, `/u/logs`, `/U/logs`. Missing USB directories are normal; links are skipped |
| `capture.pull.settle_sec` | Start after the connected robot has been disabled for `5` seconds by default; nonnegative seconds |
| `capture.pull.rate_bytes` | Read cap in bytes/second, default `1000000` (1 MB/s); positive integer through `2147483647` |
| `capture.pull.ssh` | Optional SSH connection and authentication block |
| `capture.pull.ssh.port` | Integer port, default `22`, range `1`–`65535`; the shop harness uses an unprivileged loopback port |
| `capture.pull.ssh.user` | Account, default `lvuser`; supports `${NAME}` |
| `capture.pull.ssh.password` | Password, default empty; supports `${NAME}`. Use an environment variable rather than a literal secret in shared YAML |
| `capture.pull.ssh.key` | Unencrypted private-key path instead of password; supports `~/` and `${NAME}`. Password and key cannot both be configured |
| `capture.pull.ssh.accept_changed_host_key` | Default `false`. Explicitly accept a changed pinned key when password or private-key authentication is configured; verify the replacement first and turn this off afterwards |

#### Pulled system logs (opt-in)

`capture.pull.system.enabled: true` adds a system-log phase to the same SSH worker,
disabled/connected gate and `capture.pull.rate_bytes` budget. It is independent of
`capture.pull.enabled`, which selects WPILOG/REV pulling. These are **unverified image
candidates**, not claims about the NI installation; collection is off by default.

| Key | Meaning |
| --- | --- |
| `capture.pull.system` | Optional system-log block |
| `capture.pull.system.enabled` | Boolean, default `false`; opt in after reviewing the paths |
| `capture.pull.system.kernel` | `dmesg` (default) or `off`; the kernel ring buffer, separate from the journal |
| `capture.pull.system.syslog` | Absolute paths, default `[/var/log/messages]`; includes dotted rotations of a selected file; may be empty |
| `capture.pull.system.journal` | Boolean, default `false`; replaces syslog-file collection with the whole journal, including program and service messages |
| `capture.pull.system.ni` | Absolute files/directories, default `[/var/local/natinst/log]`; directories are walked without following links |
| `capture.pull.system.jvm_crash` | Absolute files/directories, default `[/home/lvuser]`; only `hs_err_pid<N>.log` files |

Paths reject `..` and control characters. Missing configured paths are empty sources.
`journal: true` uses `journalctl -q --no-pager -o short-unix --show-cursor`, followed by
`--after-cursor` on later passes; its first pass uses `-b` alone for the current boot, without
requiring journalctl to accept a boot UUID. The reply header still records that UUID. A missing
or refused journal command stands that source down for the session with a recorded reason;
it never guesses a syslog file. Kernel collection continues independently. A missing or
permission-denied `dmesg` likewise records its reason. Exec replies are spooled in 64 KiB
blocks under the same gate and pacing as SFTP; a stalled block has a 30-second deadline.
A closed gate releases an exec channel and retries from its last committed cursor on resume;
leaving its output pipe full would stall other channels of the shared SSH connection. SFTP
files keep their content-checked byte offset.
A dmesg ring larger than 16 MiB is refused with a reason, not silently cut short.

`session.json` records `system_logs.files` with source, remote path, pass time, byte count
and hash for the session's kernel, NI and crash files, plus kernel continuity and journal
cursors. These files live under the session's `robot/system/`. Shared syslog snapshots live
under `robots/<serial>/system/`, each recorded once in its `index.json`, with their written
calendar span when known. Search selects spans overlapping the session or unknown spans;
unknown session spans cannot exclude a shared file. Existing shared receipts in session
manifests remain readable without being rewritten.
Journal text uses UTC-day files there in a session-id directory, so two FPGA sessions in
one kernel boot cannot accidentally share a cursor. Kernel overlap is removed using the
last recorded line and uptime; a lower uptime waits for the new capture session. A crash
file joins the session whose recorded program PID matches; no unique match leaves it in
`robot/system/unassigned/` with a note. No remote file is deleted or modified.

Ask `search_system_logs` with the capture's path for the pulled record; `search_strings`
reads the timely tail copy. Kernel timestamps need recorded `uptime_sec` pairs; journal
and ISO wall timestamps need the session's `systemTime`. Without evidence the text stays
visible with a null robot timestamp and a reason. A journald kernel message can occur twice:
source `kernel` means dmesg only; source `syslog` includes the whole journal.
The store door includes these receipts and shared indices in `/store/sessions`; file and
prefix-hash endpoints serve only their committed byte lengths. Peer sync carries the whole
shared index; a mirror carries shared files selected by its sessions' spans. Both verify the
advertised hash, keep provenance, and resume growing text through the held-prefix check.
Shared mirror bytes count once toward its cap and remain until their last retained session
is evicted, provided the origin still holds them. A manifest ahead of its local text returns
`not_applicable` naming the collecting server; synchronize the copy or query that server.

The shop must supply listings of `/var/local/natinst/log` and `/var/log`, presence of
`journalctl`, `dmesg` and `df`, dmesg permissions, whether dmesg prints `[seconds]` stamps
(needed to detect a wrapped kernel buffer), whether journalctl accepts a boot id, the console
path, and the program command line. Those facts will settle the candidates and defaults.

#### SSH stats and followed files

When `capture.pull.ssh` is present (even `{}`), or pulling is enabled, stats and the program
console follow are **on by default**. Pulling remains disabled unless explicitly enabled.
Stats and tails run while the robot is enabled too, and stop making SSH requests when NT4
is disconnected. One connection per configured host carries independent SFTP, sample and
follow channels; connection failures retry after 1, 2, 4, 8, 16, then 30 seconds.
The defaults make the providers available for shop testing; revisit their measured cost on
roboRIO 1 and 2 after the shop measurement.

| Key | Meaning |
|---|---|
| `capture.stats` | Optional system-stats block; absent with SSH configured means enabled |
| `capture.stats.enabled` | Boolean; set `false` to disable stats |
| `capture.stats.period_sec` | Positive seconds through `30`, default `2`; slow samples double the interval up to 30 seconds, fitting samples halve it toward this base |
| `capture.stats.budget_ms` | Positive milliseconds through `30000`, default `100`; the sample round-trip budget for adapting the period |
| `capture.tail` | List of files; absent with SSH configured follows `/home/lvuser/FRC_UserProgram.log` as `program_console`; `[]` disables following |
| `capture.tail[].path` | Absolute remote file path; required with `role` when not using `files` |
| `capture.tail[].role` | One entry-path component; known roles are `program_console`, `kernel`, `syslog`, `journal` |
| `capture.tail[].host` | Optional other host; omission means the connected robot and `capture.pull.ssh` settings |
| `capture.tail[].user` | Other host's account, default `lvuser`; supports `${NAME}` |
| `capture.tail[].password` | Other host's password, required to be an environment reference `${NAME}`, never a literal; omitted means empty |
| `capture.tail[].key` | Other host's unencrypted private-key path, required to be an environment reference `${NAME}`; choose key or password |
| `capture.tail[].port` | Other host's SSH port, default `22`; integer `1`–`65535` |
| `capture.tail[].files` | Alternative list of `{path, role}` under one host; do not combine with outer `path` or `role` |

```yaml
capture:
  robot: {team: 2363}
  store: ~/wpilog-store
  pull:
    ssh: {}                 # default lvuser, empty password; pulling is still off
  stats: {period_sec: 2, budget_ms: 100}
  tail:
    - {path: /home/lvuser/FRC_UserProgram.log, role: program_console}
    - {path: /var/log/messages, role: syslog}
    - host: coprocessor.local
      user: service
      key: ${COPROCESSOR_SSH_KEY}
      files:
        - {path: /var/log/application.log, role: program_console}
```

Stats make one bounded exec request per sample for `/proc` and `df -Pk`, with the device's
clock tick and page sizes. `/Daemon/roboRIO/` entries carry explicit units, `source: ssh`,
`host`, `sampled: true` and `period_sec`. The command's **send** time is mapped to FPGA time;
samples sent before an NT4 estimate are counted and dropped. CPU and network interval rates
need two samples; a missing or ambiguous deployed JAR omits program fields with a reason.
The JAR path comes from the quoted `-jar` argument in `/home/lvuser/robotCommand`, then exact
arguments in `/proc/*/cmdline`. The first lookup and `getconf` run once per SSH connection.
If no unique robot program is found, that reason is logged once and discovery retries only
every tenth sample; other stats continue. Each sample checks a known PID's start ticks;
only a previously identified PID/start-time mismatch reports a changed process and requests
discovery on the next sample. Tick/page constants remain cached until SSH reconnects. Steady samples read fixed
`/proc` files with shell builtins and run one `df` for all selected filesystems. A custom launcher
that does not expose the JAR path is reported.
Exec replies are bounded to 64 KiB and 30 seconds; sample waits never occupy the NT4 loop.

Each tail uses `tail -n 0 -F -s 0.25`, with one string record per line at **receipt** time.
Kernel and journal roles use `dmesg -w` and `journalctl -f` when supported, else poll the
configured file by inode and byte offset at the current stats period. Missing/unreadable
sources log a reason and stand down for the session. An SSH reconnect resumes following,
but cannot recover lines written during the interruption; enable the separate system-log pull
for the retained files. Each file admits 200 lines per one-second bucket and bounds a line to 64 KiB and
pending history to 1000 lines. Excess is counted and one drop notice is recorded after a full second without another
drop, including when a sustained burst ends in silence. Pre-session lines retain receipt time, mapped through the next session's measured offset
and clamped at zero, with `buffered_before_session` in metadata.

`list_sessions` reports `providers[]` and `get_latest_values` includes these entries with
`source: ssh` or `tail`. Provider state and costs are also in `session.json` under
`capture_stats.providers`; `capture_stats.kernel_clock` pairs `uptime_sec`,
`fpga_timestamp_sec`, `offset_sec` (FPGA minus uptime), and `round_trip_ms`. Processor time
between samples measures the whole robot, not CPU attributed to the provider. Numeric values
appear as `nt_value` metrics, with provider duration, period, processor time and drop counters.
Unsupported stats output stands down for the session with its reason; a new session retries it. These are sampled views; the capture remains the record.

Other hosts pin fingerprints locally in `ssh-hosts.json`, never credentials. Robot contacts
continue to use `robot.json`; pins in this store are not copied from a peer. If another host's
key changes and authentication would send a password or key, verify it and remove that host's
pin before restarting. Never place authentication secrets directly in the tail block.

#### PhotonVision configuration context

`context.photonvision` is a list of coprocessor hosts beside `capture`, off when absent or empty.
It requires capture and inherits as a whole block from `defaults`; a named server can clear it
with `context: {photonvision: []}`. Hosts support environment interpolation, default to port 5800,
and may specify a port. URLs, credentials, paths, duplicate addresses and unknown keys are refused
with the configuration key in the error.

```yaml
servers:
  pit:
    transport: http
    capture:
      robot: {team: 2363}
      store: ~/wpilog-store
    context:
      photonvision: [photonvision.local]
```

The provider is pinned to **PhotonVision v2026.3.4**, whose UI routes are private, not a stable API.
It requests `GET /api/settings/photonvision_config.zip` at session start and validates the ZIP's
`photon.sqlite` member. Structured current configuration comes from binary MessagePack on
`ws://<host>:5800/websocket_data`, including calibration matrices and reprojection errors per
resolution, pipeline type/resolution/exposure/gain/3D/multi-tag/field layout, software version,
device type and hardware status. The archive is fingerprinted and discarded; no SQL or native
runtime is added. The provider sends no settings mutations. Keep this unauthenticated backend
on the private team network.

Each snapshot writes one JSON record per camera under `/Daemon/PhotonVision/<camera>/Settings`.
The UI supplies no snapshot timestamp: receipt is mapped through the NT4 robot-clock estimate,
with that basis and the pinned release in metadata. It never uses the HTTP fetch's local clock.
This release omits the camera ID from selective change notifications; the provider opens a new
read-only socket to receive complete state instead of guessing which camera changed. Refreshes
start at most once per second per backend; notifications inside that interval or during a
refresh coalesce into one subsequent refresh carrying current state.
Names must match the NT camera names exactly; an unrelated generic robot-code camera label is
not inferred to mean a particular coprocessor camera.

An unsupported version/shape, unavailable backend or exceeded bound logs the reason and stands
down until a new session; the last captured settings remain readable. HTTP bodies are bounded
to 64 MiB, expanded ZIP contents to 256 MiB, WebSocket messages to 4 MiB and snapshots to 64 cameras.
Requests/initial snapshots have a ten-second deadline; unanswered WebSocket pings have a
five-second deadline, with pongs stamped on the network callback so a delayed worker cannot
blame the backend. The worker owns network waits and decoding;
reading the next message waits for the previous snapshot's ordered delivery. `list_sessions`
and the manifest expose the provider's state/reason and record/byte/round-trip costs;
`get_latest_values` marks its entries `source: photonvision`. `wpilog_provider_state` reports
one sample labeled by provider and its current state. Presence of `/photonvision/<camera>/`
topics without configured hosts logs one configuration suggestion per capture service.

#### JVM context through JMX

`context.jvm` sits beside `capture`, like `context.photonvision`. Omit it to leave JVM polling
off; `context: {}` clears an inherited context block. It requires capture and uses the address
that NT4 actually connected to, with no SSH requirement. The team enables JMX in its robot
launch; the pit server never changes the robot program or its launch.

| Key | Default | Meaning |
|---|---|---|
| `context.jvm.port` | required when `jvm` is present | Integer 1–65535; the robot's JMX registry and RMI port. |
| `context.jvm.period_sec` | `1` | Poll interval after the previous delivery completes, 0.001–3600 seconds. |

```yaml
context:
  jvm: {port: 5809, period_sec: 1}
```

In the robot project's existing GradleRIO `frcJava` deploy artifact, add these launch arguments
(adjust the example address to the robot's reachable team-network address):

```groovy
jvmArgs.addAll([
    "-Dcom.sun.management.jmxremote.port=5809",
    "-Dcom.sun.management.jmxremote.rmi.port=5809",
    "-Djava.rmi.server.hostname=10.23.63.2",
    "-Dcom.sun.management.jmxremote.authenticate=false",
    "-Dcom.sun.management.jmxremote.ssl=false"
])
```

The registry and RMI port are deliberately the same, so the remote connector uses one port.
Choose an unused port; the example is separate from NT4 and the gateway. These flags disable
authentication and encryption and expose JVM management operations: **private team network only**.
See [WPILib's launch-argument guide](https://docs.wpilib.org/en/stable/docs/software/advanced-gradlerio/compiler-args.html)
and [JDK remote management](https://docs.oracle.com/en/java/javase/17/management/monitoring-and-management-using-jmx-technology.html).
The shop's `robot-facts` module probe must establish `jdk.management.agent` in the deployed JRE;
this desktop test cannot establish the roboRIO image's module set or polling cost.

The JDK connector polls memory (heap/non-heap used and committed bytes), cumulative collection
counts and seconds by collector, live/peak/daemon threads, loaded/total classes and process CPU
seconds. Unsupported negative counters and an absent process-CPU attribute are omitted.
Numeric entries live under `/Daemon/JVM/`; `Runtime` records VM name/version/input arguments
once per connection (sensitive property values are redacted). Metadata includes `source: jmx`,
`sampled: true`, `period_sec`, `clock: measured`, the host and `jvm_start_time_ms` as identity.

Samples are stamped at receipt through the NT4 robot-time estimate, before either delivery
queue. `uptime_sec`, `clock/offset_sec` (robot time minus JVM uptime) and
`clock/round_trip_bound_sec` are recorded each poll. The bound includes the complete JMX poll,
the NT4 round trip and one millisecond of uptime quantization. A mapping change beyond both
adjacent samples' bounds writes `ClockNote`; earlier records keep their timestamps. With no NT4
estimate a sample is counted and dropped. Start time is never a clock: `startTime + uptime`
stays anchored to the wall clock at JVM startup and does not track the Driver Station's later
correction. The provider does not try to detect that correction. The existing SSH stats pairing is kernel uptime to FPGA time, not wall time. A pulled log's
`systemTime` provides corrected wall-clock evidence; the separate `robot-facts` probe records
uptime beside `date` for shop inspection. Neither is replaced by this JVM uptime mapping.

A refused connection reports the launch flags and retries after 1 second, doubling to 30 seconds;
NT4 disconnection stands the provider down. One daemon I/O worker owns JMX and a separate
five-second watchdog reports a stalled call. If RMI will not return, no replacement call/thread
is started until it does; capture and shutdown never join it. Samples cannot cross sessions,
including a reply queued before a reboot. `list_sessions`, the manifest and metrics expose
state/reason, period, round trip and sample cost. `sample_bytes` counts encoded value payloads
(eight bytes per numeric value plus UTF-8 JSON), excluding RMI and WPILOG framing; provider
`bytes` counts recorded WPILOG bytes. `get_latest_values` marks these entries `source: jmx`.
`wpilog_provider_state{provider="jvm",state="..."}` is the state gauge; numeric entries also
appear as `nt_value`. Polling is a sampled view, not an exact pause trace. Flight Recorder
streaming remains the second half, after the shop establishes runtime modules.

The whole capture block can be inherited from `defaults`; a server's block replaces it.
Unknown capture keys and invalid values name the key in the startup error. Capture requires
`transport: http` and `idle_exit_minutes: 0` (the default). A robot that is off is normal: HTTP
starts immediately and the client retries indefinitely. A server without `capture` behaves as before.

Captures are ordinary `.wpilog` files under
`robots/address-<address>/sessions/<UTC-date>/<HHmmss>Z/capture.wpilog`. A new robot clock starts
a new session; a continuing clock resumes after a connection loss. Event and match facts appear
in the store listing as soon as the queued fact update runs. Imports never stall the NT4 writer:
manifest updates coalesce into one pending task, on changed facts or at most every five seconds.
The directory gains the event and match after close and reader release on every platform, keeping
its path stable during rollover and remapping. While recording, the manifest marks the current
file in `open_capture`; each closed file gets its own SHA-256 and final size. Shutdown waits at most 30 seconds for the writer and final manifest; if it cannot finish, the
server log says the next startup sweep will recover unowned captures. Creation, including a resumed file's removal from the hashed list, is synchronous. Excluded or thinned topics are a deliberate reduction in
capture fidelity; thinning is recorded in entry metadata.
The bound rolls to `capture-2.wpilog`, `capture-3.wpilog`, and so on. Each file redeclares active
entries and has its own live index; a tool still reads one file per call. Retained struct schemas
are copied into each new file at its rollover server time with `capture_schema_seed: true` in
that entry's metadata, so each file can decode its structs. Those seed records are not additional
received changes, and do not extend the file's time range back to the boot-time schema. If declarations, schemas, one value and finishes cannot fit the configured
bound, recording stops with an explained error. A write failure closes the session with
`end_reason` in its manifest and the reason in the server log, keeps the NT4 connection, and
suppresses recording until a new robot clock. A partial write is rolled back to its completed
record boundary when the filesystem permits it. Topic costs are logged every five minutes.
The service queues recovery after HTTP is listening, so a large abandoned capture cannot delay
the daemon health endpoint. Before NT4 starts, the store queue sweeps sessions still marked `open_capture`. Files with an
active writer are left alone. An abandoned readable WPILOG is finalized with its hash, size,
record time range, file modification time as `ended_at`, and
`end_reason: "server stopped while recording"`. An incomplete final record remains marked as
truncated. Unreadable or structurally damaged files stay open with a recovery reason in the
manifest and server log. This handles a crash, power loss, or daemon termination before shutdown
finishes, without changing the capture's bytes.
Every existing log tool accepts the open capture's path. Each call sees a fixed prefix, reported
as `inputs.session_time_range`; later calls can include newer records. The hot window controls
memory retention, not which records are available: older values are read from the file.

The listing reads `/SystemStats/SerialNumber` and `/SystemStats/Comments` (also prefixed `NT:`)
within the first 2000 records of any log, not only a store. Import inspection also finds identity
logged later. AdvantageKit records these conventions. DataLogManager teams can
make every robot log self-identifying by writing them once in `robotInit`:

```java
var log = DataLogManager.getLog();
new StringLogEntry(log, "/SystemStats/SerialNumber").append(RobotController.getSerialNumber());
new StringLogEntry(log, "/SystemStats/Comments").append(RobotController.getComments());
new IntegerLogEntry(log, "/SystemStats/TeamNumber").append(RobotController.getTeamNumber());
```

The entry classes are in `edu.wpi.first.util.datalog`; `DataLogManager` and `RobotController`
are in `edu.wpi.first.wpilibj`. A logged serial wins for its file. Device evidence learned over
SSH is stored by serial with host-key history and copied into `/Daemon/Robot/Identity` at capture
start and resume. Identity learned mid-session is written in place; the address directory moves
under the serial at session close, preserving old paths without waiting on the NT4 loop. New
sessions use the known serial immediately. The listing's `robot_candidates` apply only to store files,
using exact fingerprints persisted by import inspection. Older manifests without fingerprints have
no hints. Listing does not scan logs for this evidence, and candidates never assign identities.

Pulling is opt-in: set `capture.pull.enabled: true` after the [shop test](DEVELOPMENT.md#roborio-sftp-shop-test).
The gate uses bit 0 of `/FMSInfo/FMSControlData`; enabled, unknown or disconnected state pauses the
worker within its current 64 KiB block. Reopening the gate resumes at the held offset after a content
check. The cap also covers fallback comparison reads. SSH errors retry; they do not stop HTTP or NT4.
The server log reports starts, pauses, completion and failures; `list_sessions` reports matched imports. One
recursive listing serves each transfer pass, refreshing after ten seconds during a long pass.
Hash commands read the whole held prefix on the robot, using CPU and storage bandwidth outside
the transfer byte cap. Their deadline is 30 seconds plus one second per 256 KiB, rounded up; SSH
keepalives preserve the connection during a slow hash, with five-second connect timeouts.

The first SSH host key is trusted and its SHA-256 fingerprint recorded with the device serial.
A changed key is reported. With the default empty password it can continue automatically; with
a password or private key configured it is refused before authentication. Verify the replacement,
then set `capture.pull.ssh.accept_changed_host_key: true` temporarily, or remove the last contact
entry's `host_key_fingerprint` from that robot's `robot.json`. The next accepted contact records the
new fingerprint. First contact still trusts the team's robot network. The serial read afterwards
selects the manifest. A new serial at the same address
starts separate transfer state. Supported host keys are Ed25519 and RSA SHA-2. Serial and comments
come from the HAL's sources (see the plan's section 17); missing or conflicting serial evidence
refuses the contact, rather than assigning logs from its address.

Files wait in `robots/<serial>/pulled/` until the ordinary reader reaches EOF. A verified file moves
into a session's `robot/` directory and appears in the listing. Matching requires the same known
serial and strong data correlation within 250 ms of zero, with one candidate session. Manifest
time ranges filter candidates before loading them, allowing two hours of clock slack (sixteen for
filename clocks without a zone); an unknown or unset clock cannot exclude a candidate. Names alone
cannot match. Missing logged serials are marked `data_alone` in the manifest. No match starts its own
session. A logged/device serial disagreement is reported, and the logged serial wins for the file.
Confirmed growth temporarily returns a verified file to staging; its earlier tool paths still resolve
when it is placed again. Reused names retain the previous copy separately. Resume checks the hash
of exactly the held bytes; if exec is unavailable, the last 64 KiB is compared, which cannot prove
the earlier prefix. The robot's files are never deleted or modified.

#### Robot facts for the shop

Before changing the candidate NI paths or provider defaults, collect the image's own evidence:

```bash
wpilog-mcp robot-facts 172.22.11.2 --out rio-facts.md
wpilog-mcp robot-facts robot.local --user lvuser --port 22 --key /path/to/key --out rio-facts.md
# Reuse a named server's address candidates, store pins and capture.pull.ssh block:
wpilog-mcp robot-facts --server pit --config /path/to/servers.yaml --out rio-facts.md
```

The direct form defaults to `lvuser`, port `22`, and an empty password. There is no password
command-line flag; for password authentication use `${NAME}` in the named server's
`capture.pull.ssh.password`. `--server` takes its SSH settings as a block and cannot be combined
with `--user`, `--port` or `--key`. Pulling need not be enabled and the daemon need not be running.
The command uses one SSH connection, the puller's host-key rules, and the same store queue/lock
for pins. Direct-host pins live in `~/.wpilog-mcp/robot-facts/ssh-hosts.json`; named servers use
their capture store's pins. A busy store lock is an explained refusal, not permission to bypass it.

The report is dated in UTC. Without `--out` it creates `robot-facts-yyyyMMddTHHmmssZ.md` in the
current directory; it never overwrites a report. Each fixed read-only command records its exit
status, elapsed milliseconds, up to 32 KiB each of stdout and stderr, and any timeout/truncation.
Ordinary commands have a ten-second deadline with a five-second SSH connect bound. Refused
commands remain in the report and do not stop other probes. Connection/configuration/output
failures exit nonzero; collecting a sparse image successfully still exits zero.

Evidence includes the deployed program runtime's `--list-modules` via `/proc/<pid>/exe`, with
separate conclusions for `jdk.management.agent`, `jdk.jfr` and `jdk.management.jfr`. A missing
program/runtime or refused command is evidence, never an assumed module. It also covers
`uname`, candidate NI metadata (`/etc/os-release`,
`/etc/natinst/share/ni-rt.ini`, `/etc/natinst/share/ni-imaging-info.ini`), the six log/home/USB
directory listings, `which` for journalctl/dmesg/df/tail/sha256sum, utility versions, current-boot
and explicit-UUID journal queries, three kernel lines and their `[seconds]` shape, `df -Pk /`,
the program selected by its JAR in `robotCommand`, its command line, environment readability
(never environment contents), uptime beside epoch time, and the authentication method that
actually succeeded. Both `/home/lvuser/FRC_UserProgram.log` and the NI log-directory candidate
are checked; neither is assumed present. The conclusions mark facts **found**, **absent**, or
**refused**, and name the load, radio and rotation exercises this snapshot cannot perform.

The largest `.wpilog` or `.revlog` in the configured pull directories supplies the hash-cost
probe: `head -c N -- <quoted path> | sha256sum`, with N at most `104857600` bytes (100 MiB),
using the puller's size-scaled deadline and keepalives. The report always gives the requested
size beside the elapsed time. A growing/shrinking file can change how many bytes were available;
this is the bounded command's cost, not a whole-file measurement. Nothing is modified on the
robot. Known configured secrets/key paths and sensitive command-line option values are redacted
from the report and errors; host-key fingerprints and the successful authentication method remain.
Keep collected robot evidence outside this repository.

A local server reading another process's growing WPILOG now resumes its index on verified
append anchors and a known unchanged file identity. A partial final record is retried on growth.
Where the filesystem cannot supply identity, it loads afresh. Calls spanning a file change still
return the explained retry; a successful disk-backed call's `inputs.file_size_bytes` identifies
the size it read. The pit writer's own live index remains the faster, fixed-prefix path.

#### NT4 gateway for dashboards

Enable `capture.gateway: {port: 5810}` and point a dashboard or AdvantageScope's NT4 connection
at the pit computer's address and that port, rather than at the robot. The gateway accepts NT4.1
and NT4.0, mirrors exact topic names, types, properties and schema topics, and forwards the robot's
timestamps. It serves everything the NT4 client receives; capture exclusion and thinning affect
the file, not this stream. Subscriptions choose prefixes, periods, all changes or announcements only.
Client writes are acknowledged but ignored, with one warning per connection; dashboard controls
cannot change the robot. A slow subscriber is disconnected with an explained server-log reason.

The gateway follows `WPILOG_HTTP_BIND`, including its default `127.0.0.1`. Bind deliberately to
the team network for other machines to connect. This separate port has **no authentication** and
belongs on the **private network**; an HTTP proxy login does not protect it. HTTP's Origin and
loopback-control checks remain unchanged.

A busy gateway port does not stop capture or pulling. The listener enables address reuse and
retries forever, after 1, 2, 4, 8, 16, then 30 seconds between attempts. The server logs changes
between waiting and listening, rather than every attempt. `GET /health` and `list_sessions`
publish the same `gateway` object: `state` (`disabled`, `waiting`, `listening`, or `stopped`),
`port`, `cause` while waiting, and `since` in UTC. A recovered listener serves the topics already
received while it was waiting.

A robot disconnect unannounces its topics. Reconnection announces them with new gateway ids.
Time-sync replies use the measured robot clock; while that estimate is absent they use the pit
server's local monotonic clock, as an ntcore server does. The first valid estimate after each
robot connection resets downstream connections so clients synchronize again before reading the
new session. Dashboards and AdvantageScope against a real robot remain the user's manual check.

`wpilog_gateway_clients` counts downstream clients; `wpilog_nt_connected` is the robot connection.

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

2. Write `/etc/wpilog-mcp/servers.yaml`, using a named `pit` configuration with
   `transport: http`, `idle_exit_minutes: 0`, and absolute paths for its logs, capture store
   and mirrors under `/var/lib/wpilog-mcp/`. Keep the configuration readable by the service
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
   replace both with an available UTF-8 locale (see [Uploading logs](#uploading-logs)).

3. Print and review the units, then copy each section to its named file under
   `/etc/systemd/system/`:

   ```bash
   /opt/wpilog-mcp/bin/wpilog-mcp service-unit pit --config /etc/wpilog-mcp/servers.yaml > pit-units.txt
   # Copy the three sections from pit-units.txt to their named files, with sudo.
   sudo systemd-analyze verify /etc/systemd/system/wpilog-mcp-pit*.service /etc/systemd/system/wpilog-mcp-pit-health.timer
   sudo systemctl daemon-reload
   sudo systemctl enable --now wpilog-mcp-pit.service wpilog-mcp-pit-health.timer
   curl --fail http://127.0.0.1:2363/health
   sudo journalctl -u wpilog-mcp-pit.service -u wpilog-mcp-pit-health.service -f
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

On a private robot network without NTP, check the pit computer's calendar clock before
recording. Session directory dates, manifest calendar times and the puller's overlap filter
use that clock. Robot-clock record timestamps and monotonic timers do not repair a wrong
calendar date. The unit orders after `time-sync.target` but does not require an Internet time
source before starting capture; use a maintained RTC or a local time source when offline.

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

For browser-based or multi-client access, start the `http` server from `servers.yaml`:
```bash
wpilog-mcp start http
```

The server starts in the background, and the command returns once it answers. Clients connect to `http://127.0.0.1:2363/mcp`: the port is the server's `port`, and `WPILOG_HTTP_PATH` changes the path. The server writes its log to `~/.wpilog-mcp/logs/http.log` and its process ID to the first line of `~/.wpilog-mcp/run/http.pid`. `GET /health` answers as soon as the server is up, with the server's version, process ID, `managed` ownership and gateway state.

Running `start http` while it is up reports the running server rather than starting another. If the running server is another version, because you upgraded since it started, `start` stops it and starts the new version in its place, so an upgrade never leaves an old server serving. If something that is not wpilog-mcp holds the port, `start` says so and starts nothing; choose another port.

To stop the server:
```bash
wpilog-mcp stop http
```
It finishes the calls in progress, then exits, and `stop` returns once it has. A `stop` of a server that is not running succeeds too. The server accepts a stop only from this machine and only with a token that `start` wrote to `~/.wpilog-mcp/run/http.token`, a file only you can read, so no one else on the machine can stop it, and nothing on the network can; a server that does not answer the request within the start timeout is ended as a process. A server from before this version has no stop endpoint and is ended as a process straight away.

### One Server for Every Client

An `http` server can serve every MCP client on the machine at once, where a stdio server serves the one client that started it. A client that takes a URL connects to `http://127.0.0.1:2363/mcp` directly. A client whose configuration takes only a command to run (Claude Desktop's file is one) uses `connect`, which relays the client's standard input and output to the server:
```bash
wpilog-mcp connect http                      # the "http" server, started first if it is not running
wpilog-mcp connect --url http://pit:2363     # any server, by URL, started by nobody
```
`connect <name>` does what `start <name>` does first, so a client started with it gets a server whether or not one was running, and a client started beside a running one shares it; `connect --url` starts nothing. Each connected client gets a session of its own, ended when the client closes its end. With `idle_exit_minutes` set (see [Config Fields](#config-fields)), the server exits on its own once every client has gone and the time has passed, so a server that is started on demand need never be stopped by hand.

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

`path` is the log, as `list_available_logs` lists it; `names` is one or more entries, an entry with a field path appended (`/Drive/Pose.translation.x`, as `get_statistics` takes it), or a REV log signal's key as `list_revlog_signals` gives it (`REV/SparkMax_3/AppliedOutput`), comma separated. A REV signal streams on the wpilog's clock, as `get_revlog_data` reads it, and its entry metadata carries `rev` with the device, the signal, the bus, `sync_method`, `timestamps_aligned`, `offset_seconds`, and `sync_confidence`, so a reader knows the offset's basis; a REV log that could not be synchronized is refused with the reason, and one still synchronizing with a `503` and `Retry-After`. `start_time` and `end_time` are seconds, as every tool takes them. `max_points` buckets, by the same rule as `read_entry`'s `max_points` (see [TOOLS.md](TOOLS.md#read_entry)): at most that many buckets of equal duration over the window, each with its count, minimum, maximum, mean, first, and last.

- `format=arrow` (the default) is the Apache Arrow IPC streaming format, `application/vnd.apache.arrow.stream`: a `timestamp` column in the log's microseconds and a `value` column typed by the entry (a float, an integer, a boolean, text, a struct with the schema's fields, a list for an array), or, bucketed, `timestamp`, `count`, `min`, `max`, `mean` (null where no sample in the bucket is finite), `first`, and `last`. One stream has one schema, so the entries in one request must share a value type; bucketed, any numeric entries go together. Enum fields carry `value` and `label`; a number absent from the schema has a null label (CSV keeps the number at the field name and an empty `.label` cell). Each record batch is tagged with its entry in its message metadata, and the schema's metadata carries what a tool result would: the server version, `inputs`, the log's time range, and per entry its type, its sampling class (periodic, change-only, event), the unit its name states by a conventional suffix where it does, its decoded `sample_count`, original `total_records`, and, bucketed, its bucket length. An entry with decoding failures also carries `decode_problem` (`entry`, `failed_records`, `total_records`, and the first failure’s `reason`) and the same `warning` text tools return; CSV carries these fields in its `# entries:` comment. `pyarrow.ipc.open_stream` or `polars.read_ipc_stream` reads the response bytes; `read_next_batch_with_custom_metadata()` gives each batch's entry.
- `format=csv` is one table per entry in the form `export_csv` writes (`timestamp_sec`, then the value's flattened columns, or the bucket columns), after `#` comment lines with the server version, `inputs`, and the entries' metadata, and a `# entry: <name>` line before each table.

```bash
curl 'http://127.0.0.1:2363/data/entries?path=/Users/me/riologs/akit_26-03-21_16-29-56_vache_q10.wpilog&names=/SystemStats/BatteryVoltage&start_time=20&end_time=40&format=csv'
```

The endpoint reads only files inside configured or currently leased log directories, as every tool does, and refuses a request from a web page as the MCP endpoint does. A response is capped at 512 MB: a request over the cap is refused with the row count, the size, and the hint to narrow the window or pass `max_points`, never cut. A missing entry is an error naming it, with the tools that list entries and field paths. A file that changes while it streams ends the stream with an empty batch whose metadata says so (`file_changed`), or a `# file_changed:` line in CSV, and a reader discards what it received. `ETag` comes from the file and the query, plus each requested REV source file and its synchronization method, offset, drift, and confidence, so a repeated request for an unchanged file with `If-None-Match` is a `304`. `get_server_guide` names the endpoint as `data_endpoint` whenever the transport is HTTP; a stdio server has none, and `export_csv` is the way there.

### The Store Door

An HTTP server exposes stores inside its configured log directories, including `capture.store`.
Directories leased by an MCP client do not publish stores. Discovery refreshes within five seconds;
file requests read their owning manifest directly, without walking the catalog per transfer block.

| Request | Response |
|---|---|
| `GET /store` | `id`, `format_version`, `server_version`, `mirror`; with several stores, none, or an unreadable neighbor, a `stores` array of readable descriptors and `unreadable` entries naming each failed store's `path` and `reason` |
| `GET /store/robots` | `robots`, the robot manifests |
| `GET /store/sessions` | `sessions`, each with `robot_id`, store-relative `path`, and its complete `manifest` (including `system_logs` receipts); `unassigned` contains store-relative `path` and `file` records; `system_logs` lists each robot's shared index entries (`file` and nullable `written_span`) once |
| `GET /store/files/<store path>` | File bytes; a single `Range: bytes=start-end`, open-ended range, or suffix range returns 206 |
| `GET /store/files/<store path>/prefix-hash?bytes=N` | SHA-256 of exactly the first N bytes: `sha256`, `bytes`, and current `size_bytes` |

Append `?store=<id>` (or `&store=<id>`) to select a store when there are several. Session filters
are `since` (an ISO UTC instant, including sessions whose end is at or after it), `robot` (id or
serial), and `event` (exact event text). Filters omit unassigned files. A growing capture is
served at its current length, including by the prefix-hash endpoint; the session response refreshes
`open_capture.size_bytes` from the file. An out-of-bounds range or prefix returns 416. Control
manifests are JSON responses, never arbitrary file downloads; strays, inbox files, traversal and
paths outside catalog membership are refused. A manifested imported log keeps its original name,
even a name such as `robot.json`, within its separate payload directory. A payload named
`prefix-hash` is downloaded normally; the required `bytes` query field selects a prefix-hash
operation on the preceding file path.

The peer server must bind to an interface reachable by the other laptop (`WPILOG_HTTP_BIND=0.0.0.0`
in the peer server's environment); leases and key registration remain refused on that bind.
The door has no authentication: expose it only on a trusted network or behind the team's authenticated proxy.

### Syncing Stores Between Laptops

Run `wpilog-mcp sync http://other-laptop:2363` to copy missing files into the local server's
store. The peer needs an HTTP server bound to an address your laptop can reach, as above.
The default local server is `default`; `--server <name>` and `--config <path>` select another.
The destination defaults to the first configured store, or the first log directory to create
one. Use `--store <dir>` to choose another destination inside the configured directories, and
`?store=<id>` on the peer URL when it publishes several stores. The URL is remembered in
`store.json`; `wpilog-mcp sync` with no URL visits every remembered peer in turn.

A running daemon owns the job. Without one, the command runs under the same `store.lock` as
import; a daemon refusal or failed request never starts a second writer. Transfers reuse the
puller's content proofs, 64 KiB blocks and durable progress. HTTP sync defaults to unlimited
pacing; `--rate-bytes <bytes/sec>` sets a cap (`0` means unlimited). An interrupted file resumes
only after the peer proves the held prefix. Each completed copy must match the advertised hash
and load through the ordinary import inspection. An already imported power-cut tail retains
its truncation note. An open capture waits for its final hash; it is reported as a refusal for
this sync, while the read-only door continues to serve its current bytes.

Overlapping sessions of the same serial join, using the lexicographically smallest session id
so both transfer orders converge. Existing paths remain usable when closed session fragments
move together. Peer captures live under `peer/<sha256>/` inside that session, preserving their
filenames while reserving future `capture-N.wpilog` names for the local writer.
Files keep their original provenance and gain `copied_from` entries with the
peer's store id, URL and copy time. A peer's robot name or comments fill an empty local field;
otherwise disagreements appear in the session's `conflicts` and the local text stays. Hashes
already held are reported present. Nothing is deleted on either side, and mirrors are refused
as both source and destination. Use the mirror below for a scoped offline cache.

The command prints `job`, `progress` and `result` JSON lines (offline runs have no `job` line).
The result lists `sessions_created`, `files_copied` (including new network `bytes` and total
`size_bytes`), `files_present`, `conflicts`, `refusals`, and `stopped` with the peer, file and
held byte count. Exit 0 means no refusals or stopped peers, 1 reports an incomplete or refused
sync, and 2 is a command-line error. Partial bytes remain journaled; rerun the command to
resume. Before contacting any peer, the next sync completes pending file/manifest placements
left by a process exit.

The daemon API is `POST /store/sync` with optional `url`, `store` (local directory), and
`rate_bytes` fields, for example `{"url":"http://other-laptop:2363","rate_bytes":0}`.
An absent URL means remembered peers; an absent store requires one configured destination.
The body is limited to 64 KiB. A `202` response gives `job_id` and `url` (also `Location`);
`GET /store/sync/<job>` returns `state` (`queued`, `running`, `done`, `failed`), `progress`,
`result` and `error`. Both routes require a loopback connection and pass the Origin check,
including on a server bound to the network. A second sync for the same store is refused with
409; different stores have separate queues. Active jobs keep the daemon alive. The most recent
100 jobs are retained in memory, with completed jobs evicted first; they disappear at restart.

The extension's local picker uses `GET /store/sync` to list writable stores and their remembered
peer URLs, with unreadable headers named separately. It includes stores under local session
leases, which still do not publish through the network door. All sync controls require a
loopback connection, including this picker.

### The Mirror

A mirror is a local cache of a team's store, read by every tool while offline. The origin must
serve its HTTP door on an address this laptop can reach. The local server owns copying and
retention; imports, assignments, capture writers, inbox processing, and peer sync cannot write
into a mirror. Strays are reported and left alone. SSH contact history is never copied.

```yaml
servers:
  http:
    transport: http
    mirror:
      origin: http://pit-server:2363
      folder: ~/wpilog-mirror
      days: 14
      max_size_gb: 20
      robots: []
      events: []
      interval_sec: 30
      rate_bytes: 0
```

| Key | Meaning |
|-----|---------|
| `mirror.origin` | HTTP(S) origin URL; append `?store=<id>` if that server exposes several stores |
| `mirror.folder` | Empty destination, or this origin's existing mirror; also admitted as a log directory |
| `mirror.days` | Nonnegative calendar window in days, default `14` |
| `mirror.max_size_gb` | Positive decimal GB cap, default `20` (20,000,000,000 bytes) |
| `mirror.robots` | Robot serials to include; empty means all |
| `mirror.events` | Whole named events to include beyond the day window, subject to the robot filter |
| `mirror.interval_sec` | Positive integer seconds between attempts, default `30` |
| `mirror.rate_bytes` | Nonnegative integer bytes per second; default `0` means unlimited |

Pinned session IDs override the window and robot filter. The cap favors newer sessions and
evicts the oldest unpinned ones first. Pins can exceed the cap; that is reported, and none is
deleted. A copy whose origin no longer advertises its held content is retained with a reason,
not deleted on an assumption. Retention deletes only manifested mirror files, never a stray.
Open sessions advance to the origin's current prefix each pass, using the same content proof
and resume engine as peer sync; ordinary log reloads notice the appended bytes. A replaced
prefix is fetched from the start. Session IDs follow directory renames and robot moves.

`list_available_logs` adds `origin`, `complete`, `growing`, `last_sync`, and `age_sec` to a
mirrored log's `session`. `complete` means the origin closed it and all its advertised files
were verified. The bytes, provenance, and recorded matching offsets are the origin's; no REV
correlation is rerun. A disconnected origin leaves the existing listing and files readable.

Local user controls require a loopback connection and the normal Origin check:

| Endpoint | Result |
|----------|--------|
| `GET /store/mirror` | State (`disabled`, `waiting`, `synchronizing`, `synchronized`, `offline`, `partial`, `error`), configuration, last sync and age, remaining file/byte counts, result and origin policy |
| `POST /store/mirror/configure` | The mirror block as JSON; starts background synchronization, returns 202; folder must already be allowed by configuration or a directory lease |
| `POST /store/mirror/sync` | `{}` requests Sync Now, returns 202; an active pass returns 409 |
| `POST /store/mirror/pin_session` | `{ "session_id": "..." }` queues a pin; 202; polling status shows it applied |
| `POST /store/mirror/unpin_session` | The same body removes the pin; 202 |
| `DELETE /store/mirror` | Stops future passes and leaves existing files intact |

These controls are endpoints for a person's UI actions, not assistant tools. Mirror configuration
starts after the HTTP listener, so an unavailable or slow origin cannot delay daemon health.
The mirror uses additive fields in store format 1 and refuses a different origin store ID.

A prefix that changes repeatedly during one pass is retried once, then reported as partial
until the next interval; the journal keeps its place. Superseded private partial files are
removed after successful verification. The cap counts session payloads; manifests and the
private staging copy needed to verify a replacement require temporary additional space.
A growing local prefix can be evicted only when the origin's prefix hash proves it still exists.

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

### Uploading from a laptop

The extension's **Upload Logs to Pit Server** command streams selected files to its
`wpilog-mcp.pitServerUrl`, retaining the laptop originals. An HTTP client can do the same:

```text
POST /store/import?store=<store-id>&filename=<URL-encoded-basename>
Content-Type: application/octet-stream
Content-Length: <exact bytes>
X-WPILOG-SHA256: <lowercase source SHA-256>

<one file's bytes>
```

Get the store id from `GET /store`; it may be omitted when exactly one configured store
exists. Optional `stated_robot` names a robot where the file has no logged serial. Only
configured writable stores accept uploads; neither a mirror nor a leased directory becomes
a network upload target. The filename is one portable path component. Files above the
1 TiB (1,099,511,627,776-byte) file limit are refused before reception. Receipt uses bounded buffers into a hidden,
locked inbox transfer, outside the store queue; the inbox watcher cannot adopt half a file.
The declared size and hash must agree before the existing importer inspects or places it.
A disconnect removes the temporary bytes, and crash leftovers follow inbox recovery.
The `202` response and job polling are the same as a JSON import. Provenance records
`upload:<filename>` and `moved: false`; the sender's private laptop path is never sent.
Duplicates are reported as present. This adds byte transport; the command, inbox, content
inspection, grouping and duplicate recognition were already the explorer's import pipeline.

Run the receiving server with a UTF-8 locale to accept non-ASCII upload names (see the
service guidance above; the Docker image already sets it). An unrepresentable name is
refused before receiving bytes, with the native encoding and the instruction to restart
with a UTF-8 locale. Windows uses its Unicode path API even with a legacy locale.

The server has no authentication: anyone who can reach this upload route can add logs;
a team using a proxy should put its password on `/store/import` first. For example, with the
pit server bound to loopback, an nginx TLS server can protect MCP and upload together:

```nginx
# Inside an existing TLS server block; create the password file with htpasswd.
location / {
  auth_basic "Team pit server";
  auth_basic_user_file /etc/nginx/pit.htpasswd;
  proxy_pass http://127.0.0.1:2363;
  client_max_body_size 2047m;
  proxy_request_buffering off;
}
```

### Live session tools and a proxy login

A server with `capture` enabled adds `list_sessions`, `get_latest_values`, and
`wait_for_change` to its Live tool category. The first lists sessions and recorded value costs
(top ten topics, records and bytes, rates over the last minute); the others query and wait on
NT4 publications in memory. `inputs.session` names the capture for ordinary log tools.
Counts update when the 250 ms asynchronous flush completes; a slow disk delays that snapshot, not NT4 keepalives. Robot-clock timestamps and ages describe publication,
not a measurement requested by the caller. [TOOLS.md](TOOLS.md#live-tools) defines the fields,
missing-topic results, and the 30-second maximum wait.

For the reverse proxy's Basic login, the extension's **Set Pit Proxy Credential** command
keeps the credential in SecretStorage, scoped to the pit URL's HTTP origin. It sends it to
`POST /pit-credential` on the local loopback server for its live MCP session, like the TBA
key. The body is `{url, authorization}`; null authorization removes that session's lease.
Session deletion or expiry also removes it. Nothing persists it in the store, YAML, a URL
or a process command. Use HTTPS when carrying a proxy password across the network.

The local mirror and peer HTTP reader use the credential only for that origin and never
follow a redirect. Claude's URL bridge uses local `/pit-mcp?url=<encoded-pit-MCP-URL>` so
its registration contains no password. Forwarding requires an active lease for exactly that
MCP endpoint; it and registration are refused on a listener bound off loopback and retain
the Origin check. Re-register Claude after setting or clearing the credential, or changing
the local server's port. With VS Code closed, no window leases the credential: the configured
local mirror remains available for offline analysis, but password-protected origin access
requires the credential's window to be open. This is a client of the team's proxy, not new
authentication in the pit server.

For "right now", use `list_sessions` then `get_latest_values` (robot timestamps and ages), or
`wait_for_change` for the next publication. For "the last 10 seconds", pass `last_seconds: 10`
to any tool with a time scope. It ends at the open capture's current estimated robot time,
fixed when the call acquires its view, or at the last record of a closed log. Before time sync,
it uses the captured prefix's end; it never invents an offset. Do not combine it with
`start_time` or `end_time`; named scopes and explicit windows still intersect. `inputs.window`
shows the absolute bounds (`inputs.windows` by path for `compare_matches`). The listing's `open`
flag identifies the file being written; `inputs.session_time_range` describes the records the
call could see, which can end before the robot's current time.

MCP `resources/list` advertises `pit://session/current`. Read it with `resources/read` for
`session` (id, file, started_at, connected, identity), `gateway` and `providers`. It reads
published facts without waiting on capture or store work; with no open session, `status` is
`not_applicable` and `reason` explains why. `prompts/list` remains empty. Resource subscriptions
are not advertised; read again for a newer snapshot.

### Metrics and a starter dashboard

Every HTTP server serves `GET /metrics`, including a server without capture. It emits
[Prometheus text format 0.0.4](https://prometheus.io/docs/instrumenting/exposition_formats/)
with no extra server dependency. `/health` stays its short JSON answer. The Origin check
applies to metrics, too. The route has no authentication: anyone who can reach the HTTP
port can read it, so use the same private network or proxy as the MCP endpoint.

A dashboard is a **sampled view, not the record**. Changes between scrapes disappear from
that view; the capture remains the record of every publication. Samples have no exposition
timestamps. `nt_age_seconds` uses the robot clock to show the age of the latest publication,
can be negative for a future publisher timestamp, and is absent until time sync is known.
Disconnected topics disappear when the client clears its latest table.

Configure scope in a named server's `servers.yaml` block:

```yaml
metrics:
  include: []
  max_array_length: 16
```

| Key | Default | Meaning |
|-----|---------|---------|
| `metrics.include` | `[]` | NT4 topic prefixes; empty includes all numeric topics. This changes the scrape only, not capture. |
| `metrics.max_array_length` | `16` | Nonnegative maximum per array dimension; `0` omits array elements. |

`nt_value{topic="..."}` keeps exact NT4 names. Booleans are 0/1; numeric arrays gain an
`index` label. Structs use their published schemas and the tools' field paths in a `field`
label; a struct array has both labels. Nested field indexes stay in the field path, such as
`currents[1]`. Only declared numeric fields appear, including enums and booleans. Strings,
raw/protobuf payloads, missing or incompatible schemas, and unpublished fallback schemas
produce no values. Schema topics are used even when the prefix filter excludes them.
NaN and infinities retain their Prometheus spellings. Each emitted topic gets one age sample.

| Server metric | Meaning and labels |
|---------------|--------------------|
| `wpilog_nt_connected` | 0/1 NT4 connection state; 0 without capture. |
| `wpilog_nt_address_info` | 1 with the last connected `address`; absent before any connection. |
| `wpilog_capture_open` | 0/1 recording session state. |
| `wpilog_capture_topics` | Session entry count, including finished entries. |
| `wpilog_capture_records_total`, `wpilog_capture_bytes_total` | The live tools' recorder counts across rollover files: NT4 value records and bytes including record headers, excluding context/control records and copied schema seeds. The `session_started_at` label identifies the counter lifetime; snapshots refresh after the 250 ms asynchronous flush completes. |
| `wpilog_nt_time_offset_seconds`, `wpilog_nt_round_trip_seconds` | Selected clock estimate (robot minus local monotonic time) and its round trip; absent without sync. |
| `wpilog_gateway_clients` | Connected downstream gateway clients; 0 when the gateway is disabled. Independent of the robot-side connection. |
| `wpilog_pull_bytes_total`, `wpilog_pull_files_total` | Per-`robot` process counters: copied payload bytes, including retransfers, and successful verifications, including growing-file updates. |
| `wpilog_pull_files_waiting` | Per-`robot` known unfinished files at a closed gate, from the last listing; unknown remote files are not counted. |
| `wpilog_provider_sample_duration_seconds`, `wpilog_provider_sample_bytes` | Last completed sample's round trip and response bytes, by `provider`. |
| `wpilog_provider_period_seconds`, `wpilog_provider_robot_cpu_seconds` | Current sampling period and the robot's processor time between samples, by `provider`; unknown processor time is omitted. |
| `wpilog_provider_lines_per_second`, `wpilog_provider_dropped_lines_total` | Followed-file line rate and process-lifetime dropped lines, by `provider`. |
| `wpilog_provider_dropped_before_sync_total` | Process-lifetime stats samples dropped before an NT4 time estimate, by `provider`; their timestamps are never guessed. Tails use their bounded buffer while waiting. |
| `wpilog_jvm_memory_used_bytes`, `wpilog_jvm_memory_committed_bytes`, `wpilog_jvm_memory_max_bytes` | Platform MBean memory by `area` (`heap`, `nonheap`); an undefined maximum is omitted. |
| `wpilog_jvm_gc_collections_total`, `wpilog_jvm_gc_duration_seconds_total` | Collection count and total collection seconds by `collector`; unsupported MBean values are omitted. |

Topic count and record/byte counters have the same `session_started_at` label. Components are
independent published snapshots, as in the live tools; a scrape never waits for the NT4 loop,
the store queue, a remote listing, or a file read. It samples the server JVM's MBeans directly.

The ready-to-run files are in [metrics/](metrics/compose.yaml):

```bash
# From a checkout, or a copy of the doc/metrics directory with its subdirectories:
docker compose -f doc/metrics/compose.yaml up -d
```

[The Compose file](metrics/compose.yaml) pins Prometheus 3.15.0 and Grafana 13.2.3, stores
both services' data in Docker volumes, and exposes their UIs only on this laptop's loopback.
[Prometheus configuration](metrics/prometheus.yml) scrapes once a second. Edit its target
for a pit server on another machine; the default `host.docker.internal:2363` addresses the
Docker host and includes Linux's host-gateway mapping. The pit server must bind to an
address reachable from that container (for example, set `WPILOG_HTTP_BIND=0.0.0.0` before
`wpilog-mcp start pit`); binding only to loopback is not reachable through a Linux bridge.
The server runs neither of these services itself.

Open Grafana at `http://127.0.0.1:3000` (initial login `admin`/`admin`, then choose your
password) and select **Pit / Pit sampled telemetry**. Its
[provisioned dashboard](metrics/pit-dashboard.json) includes session state, capture rate,
battery, CAN utilization, loop duration, robot CPU, robot free disk, server heap, topic age,
pull gate and NT4 round trip. Fill the topic boxes with exact names and the units the boxes
state; empty boxes deliberately show no robot data. For a topic in other units, edit the
query's conversion. CPU/disk panels work when the robot publishes them; future context
providers will supply their own topics. The heap panel is the **server's** JVM. Use the
capture and analysis tools for full-resolution history, not the dashboard's sampled curve.

The configuration follows the official [Prometheus container setup](https://prometheus.io/docs/prometheus/latest/installation/)
and [Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/).

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
