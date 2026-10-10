# Operations and Usage Manual

What to type, click and ask, step by step, to get answers from your robot's logs and to run
the server in the shop and the pit. The [standalone guide](STANDALONE.md) is the reference for
every setting, flag and route, and the [tool reference](TOOLS.md) describes every tool's
parameters and results. This manual says which to use, in what order, and what you should see.

The shared-server and pit instructions describe 0.10.0-dev1 or newer. The latest full release
can be older than this branch. Check the version before following them; if it is not published
yet, install the checkout as described in [6.1](#61-install).

- [Part I: Using the tools](#part-i-using-the-tools): the setup check, how a question becomes
  tool calls, how to read an answer, recipes for the common questions, and the explorer.
- [Part II: Running the server](#part-ii-running-the-server): the shared server on a laptop,
  the pit server, the shop day, stores, and maintenance.
- [Part III: When something is wrong](#part-iii-when-something-is-wrong): symptoms, the check
  that decides, and the fix.

## Part I: Using the tools

### 1. Before the first question

#### 1.1 Which setup you have

| Setup | What runs | When to choose it |
|---|---|---|
| VS Code extension | **WPILog Analyzer** installs and starts the shared server; Copilot and other VS Code agents use it directly, Claude Code through a bridge | Most people. You get the explorer too |
| Standalone server | The same server, installed by a script, used by Claude Code, Claude Desktop, Codex or any MCP client | No VS Code, or a terminal-first workflow |
| Pit server | A standalone HTTP server with a `capture` block: it records the robot's NetworkTables, pulls the robot's logs and serves everyone on the network | The shop and the pit |

All three are one program. The extension and the standalone install share `~/.wpilog-mcp`,
one server and one cache. A pit server is a named configuration of the same install.

#### 1.2 Check that the server is up

Extension: open **WPILog Explorer** in the activity bar. The **Logs** view lists your logs.
If it is empty or shows an error, select **WPILog Analyzer** in the Output panel and read it.

Terminal: install first ([6.1](#61-install)). If `wpilog-mcp` is not found, add its launcher
directory to this shell's PATH, then check it:

```bash
export PATH="$HOME/.wpilog-mcp/bin:$PATH"
wpilog-mcp -version
wpilog-mcp start http
curl http://127.0.0.1:2363/health
```

`start http` prints that the server is running (or already was); `/health` answers with the
version, configuration `name`, process ID, `managed` and gateway state. The server's log is at
`~/.wpilog-mcp/logs/http.log`.

#### 1.3 Check that your assistant sees the tools

- Claude Code: type `/mcp`. `wpilog-analyzer` should be connected; approve it if asked.
  If it is missing, run the registration in [6.4](#64-register-your-assistant).
  From a terminal, `claude mcp get wpilog-analyzer` also checks the registration and connection.
- Any assistant: ask *"What logs are available?"*. The answer should list files with friendly
  names such as "VACHE Qualification 10". An assistant that answers from memory, or writes
  Python to parse the file, is not connected to the server.

#### 1.4 Put the logs where the server looks

The default directory is `~/riologs`. Copy `.wpilog` files there with the names the robot gave
them (`FRC_20260321_162956_VACHE_Q10.wpilog`, `akit_26-03-21_16-29-56_vache_q10.wpilog`):
the event and match in the name are what the listing reads when the log's first records do
not carry them. Keep a `.revlog` next to the `.wpilog` it was recorded with.

To use other directories, see [6.3](#63-configure-the-server) and [6.5](#65-project-directories).
A log outside every configured directory is refused by name, not read.

#### 1.5 Team number and The Blue Alliance key (optional)

Neither is needed to read a log. The team number labels logs that record none; the key adds
match results, alliances and times to the listing and enables `get_tba_match_data`.

- Extension: the **Team Number** setting, and **WPILog Analyzer: Set The Blue Alliance API Key**.
- Standalone: `team` and `tba_key` in `~/.wpilog-mcp/servers.yaml`, or `TBA_API_KEY` in the
  environment. Never put the key on a command line.

#### 1.6 Open the robot project

Keep the robot project open in the same window. A log records names and numbers, not what
they mean. The assistant is told to read the code that logs an entry to learn which mechanism
it belongs to, its units, and whether it is measured or commanded. Without the code it must
say that a mapping is an assumption taken from the name.

### 2. How a question becomes tool calls

The server hands every connected assistant its reasoning rules on connect, and
`get_server_guide` returns the long form. You do not have to drive the tools yourself, but
knowing the shape of a good investigation lets you steer one.

1. **Find the log.** `list_available_logs` with an `event` or `name` filter; the assistant
   should name the file it chose.
2. **Learn the timeline.** `get_match_phases` gives the enabled segments, auto and teleop.
   Every statistic should be scoped to a phase or an enabled segment, because whole-log numbers
   mix in disabled time and the boot.
3. **Confirm the event happened.** `get_ds_timeline`, `find_condition` or `search_strings`
   shows the brownout, the error or the disable the question is about. An event that is not
   in the log is not explained; it is reported as absent.
4. **Test, one call per prediction.** `get_statistics`, `find_condition`, `read_entry` with a
   window, `time_correlate`, each scoped to the phase and window in question.
5. **Conclude in three tiers.** A logged event is a fact. A statistic is an inference, bounded
   by the result's `confidence_level`. A cause outside the telemetry is a hypothesis that
   needs a physical check.

How to ask so the first answer is useful:

| If you want | Say |
|---|---|
| A specific match | The event and match: "Q42 at VACHE". The assistant should state the file it used |
| A quick answer before a match | "pit mode" or "we have a match in ten minutes": two to four sentences, one thing to check |
| The full investigation | "deep dive": ranked findings with evidence, alternatives and confidence |
| A mechanism's behaviour | Its name as the code logs it, or where to find it: "the elevator, under /RealOutputs/Elevator" |
| Your own theory tested | State it: "I think the compressor caused it". It becomes the first hypothesis and gets a rival |

### 3. Reading an answer

An assistant that follows the rules cites the entry, the window, the sample count and the
statistic for every number. Ask for them when it does not. Behind each number is a tool
result, and three fields in it decide how far to trust it.

| Field | What to read from it |
|---|---|
| `status` | `ok`; `partial` (something was skipped, named in `skipped`); `not_applicable` (the tool does not apply, with a `reason`); `no_match` (nothing matched the requested entries, condition or window, with `looked_for` and a `hint`); `error`. `no_match` means not found, never "nothing wrong" |
| `data_quality` and `server_analysis_directives.confidence_level` | How much the samples can support a statistic: `high`, `medium`, `low` or `insufficient`, with penalties in `data_quality.reasons`. The confidence level is inside `server_analysis_directives`, not at the top level. A `low` level on a sparse change-only signal does not weaken an event the log plainly records |
| `inputs` | Which entries and which time range the result was computed from. If the entry is not the one you meant, say so and name the right one |

Things that are often misread:

- **Brownouts.** `BROWNOUT_START` in `get_ds_timeline` is the battery voltage crossing a
  threshold (the log's own `BrownoutVoltage`, else 6.8 V, stated as an assumption). Only
  `RIO_BROWNOUT_START`, from a logged flag such as AdvantageKit's `/SystemStats/BrownedOut`,
  means the roboRIO cut its outputs. A log without the flag cannot say whether that happened.
- **The log ends early.** A file that stops mid-phase says "log ends at X s", not "the match
  ended". Most robot logs end inside their last record because the robot was switched off; that
  alone is a note, not a warning.
- **Scores.** The telemetry does not record the score. Results come from The Blue Alliance,
  through `get_tba_match_data` or the `tba` field of the listing.
- **One log is one sample.** "Happened in 4 of 6 matches" is a pattern; "happened once" is an
  event. Ask for `compare_matches` or the same statistic on each log before accepting a trend.
- **Correlation.** A mechanism that fires only while scoring correlates with voltage because
  of timing. Ask for the mechanism's current around the voltage minimum instead.

Useful follow-ups: *"Which entry did you use, and why that one?"* *"Run it again with scope
teleop."* *"Show me the candidates."* *"What is the one thing to check before the next match?"*

### 4. Recipes

Each recipe names the tools in order with the parameters that matter. The assistant usually
runs them itself; the sequence is what to expect, and what to ask for when it stops short.

#### 4.1 The robot died or stuttered in a match

1. `get_ds_timeline` with the log's `path`: enable and disable transitions, `BROWNOUT_START`
   and `BROWNOUT_END` crossings, `RIO_BROWNOUT_START` when the log has the flag, and a
   summary of errors and warnings. Note `brownout_voltage_entry` and `brownout_threshold_basis`.
2. `power_analysis` with `scope: "enabled"`: `voltage_analysis` for the minimum and the samples
   below threshold, `channel_analysis` for every current entry's peak with its time, largest
   first. Per-channel arrays such as `/PowerDistribution/ChannelCurrent` are expanded per index.
3. `find_condition` on the voltage entry, `operator: "lt"`, `threshold` at the brownout
   level, `scope: "enabled"`: the intervals, each with its start, end and duration.
4. `read_entry` on the suspect current with `start_time` and `end_time` two seconds around a
   crossing: was the mechanism drawing current when the voltage fell?
5. `can_health`: a burst of CAN errors within about a second after the dip is a consequence of
   the brownout, not its cause; look for enabled-state errors before it.

What to look for: a current peak at the voltage minimum names the load; a steady draw such as a
compressor shows near-zero correlation even when it contributes. Which device sits on a channel
is not in the log; the team's wiring map is.

#### 4.2 CAN problems

1. `can_health`: text reports of CAN failures classified by the robot's state (an error while
   disabled is normal, an error while enabled is not), the bus counters, and an assessment with
   its basis. `not_applicable` means the log has neither text nor counters.
2. `analyze_can_bus` with `bus_name` for one bus (`rio`, or a CANivore name) and a window:
   utilization, transmit and receive errors, bus-off and TX-full counts.
3. `search_strings` with `pattern: "CAN"`, `level: "error"` and a window for the full, paged
   text; `has_more` says whether another page exists.

#### 4.3 Loop overruns

1. `analyze_loop_timing` with `scope: "enabled"`: how often the loop exceeded `threshold_ms`
   (default 20 ms) and the distribution. The unit comes from the entry name, else from the
   data, and the result says which. Pass `entry` when the log has several candidates.
2. `find_condition` on the loop time entry, `operator: "gt"`, `threshold` in the entry's unit,
   to list the overruns as intervals.
3. `search_strings` with `start_time` and `end_time` around the worst one: what the program
   printed then.
4. On a pit server capture with JVM context, `read_entry` on `/Daemon/JVM/` entries around the
   same time: a garbage collection count or time that jumps there is the pause.

#### 4.4 Swerve

1. `analyze_swerve` with `scope: "enabled"`: speed per module, tracking against setpoints when
   they are logged, slip and steer events. Modules are numbered by array index; a position such
   as front-left is an assumption the result states. When the log has two setpoint arrays the
   tool lists them as candidates: pass `measured_entry` and `setpoint_entry`.
2. `compare_entries` on one module's measured and setpoint speed, with a `windows` list from
   `find_condition` (while driving), for RMSE and the lag that minimizes it.
3. `pose_corrections` or `compare_poses` between odometry and the vision estimate for drift.

#### 4.5 Autonomous that works in practice but not on the field

1. `analyze_auto`: every autonomous period, the routine the chooser selected, and how closely
   the robot followed its path. Pass `chooser_entry` when more than one chooser exists.
2. `compare_matches` with `scope: "auto"` and the pose or path error entry on the practice log
   and the match log; each log's phase is resolved in its own timeline.
3. `get_code_metadata` on both logs: a different git SHA is a confounder for any difference.
4. Keep only FMS-connected logs for the comparison; `list_available_logs` names the event and
   match of each.

#### 4.6 A mechanism: elevator, arm, intake

1. Find the entries: `search_entries` or `list_entries` with the mechanism's name as `pattern`,
   then the robot code for which is the setpoint, the measurement, the current.
2. `profile_mechanism` with explicit `setpoint_entry`, `measurement_entry`, `velocity_entry`,
   `current_entry` and `temperature_entry` as the log has them: following error, step response,
   stalls (`stall_current_threshold`, default 30 A) and temperature. It uses only the entries
   you pass; `mechanism_name` only finds candidates.
3. `find_condition` with `conditions: {"all": [...]}` for current above a threshold while the
   velocity is near zero: the stalls, as intervals.
4. `get_statistics` on the current with `windows` set to those intervals.

#### 4.7 Vision

1. `analyze_vision`: pose observation streams, target streams, has-target flags and pose
   jumps, for the entries the AdvantageKit template, PhotonVision and Limelight publish. Other
   entries are listed as candidates; pass them as `vision_entries` once the code confirms them.
2. `pose_corrections`: how large the corrections to the robot pose were and when.
3. On a pit server capture with `context.photonvision` configured, the camera's calibration,
   pipeline and exposure settings are recorded under `/Daemon/PhotonVision/<camera>/Settings`;
   `analyze_vision` reports them as `camera_settings`.

#### 4.8 Console errors and alerts

1. `get_ds_timeline`: `text_event_counts` and `text_event_summary` group the messages, with
   one example per group and how many distinct texts it covers.
2. `search_strings` with `level: "error"` (or `warning`), a `pattern` or a `regex`, and paging
   with `offset` and `limit`: the complete list, in time order. `collapse_repeats: true` folds
   runs of the same line.
3. On a pit server session with system-log pulling enabled, `search_system_logs` with the
   capture's `path` and `source` (`kernel`, `syslog`, `program`, `jvm_crash`) reads the
   roboRIO's own logs: a CAN interface going bus-off, a camera unplugging, a JVM crash file.

#### 4.9 Across an event

1. `list_available_logs` with `event` set to the event code: every log, newest first, with
   TBA results when the key is configured.
2. `compare_matches` for two logs, or `get_statistics` with the same `name` and `scope` on each
   log, tabulated. Use `scope: "enabled"` or `"teleop"`; a whole-log maximum is often the boot.
3. `get_tba_match_data` with `year`, `event_code`, `match_type` and `match_number` for
   outcomes. The event code is TBA's, not necessarily the abbreviation in the file name.
4. `get_code_metadata` on each log before attributing a change in behaviour to anything else.

#### 4.10 REV motor controller data

1. `list_revlog_signals` with the wpilog's `path`: devices, signals, units, and each REV log's
   synchronization status. The REV log is found beside the wpilog and put on its clock by
   correlating signals both logs record.
2. `sync_status`, or `wait_for_sync` right after loading: the confidence, offset and drift. If
   the accuracy is coarser than the interval you care about, the ordering of a REV event and a
   wpilog event cannot be determined. When you know the offset from a distinctive event in
   both logs, `set_revlog_offset` with `offset_ms` sets it by hand.
3. `get_revlog_data` with the `signal_key` from step 1, a window and `include_stats: true`.
   Stall current, temperature and fault flags are valid without precise alignment.

#### 4.11 Getting the numbers out

- `export_csv` with `name`, a window and `inline: true` for up to 5,000 rows in the answer, or
  a file under the export directory; it is the route when no tool computes what you need.
- `render_chart` with `name` or `entries`, `kind` (`time_series`, `histogram`, `scatter`,
  `field`) and a window: a PNG with the statistics of the visible window beside it, and an
  **Open in Explorer** link in VS Code.
- `generate_report`: a one-call summary of a log, each section by the same rules as the tool
  that covers it in depth.
- For a notebook or a script, the HTTP server's data endpoint streams every sample of an entry
  as Arrow or CSV: `GET /data/entries?path=<log>&names=<entry>&format=csv`
  ([the data endpoint](STANDALONE.md#the-data-endpoint)). The explorer's **Open in Notebook**
  writes a three-cell notebook that does this.

#### 4.12 Live, in the shop

On a pit server, three tools answer for the present, and every log tool reads the recording
being written.

1. `list_sessions`: the current and recent sessions, whether the robot is connected, the
   records and bytes per second, the ten most expensive topics, imports and providers. Its
   `inputs.session` is the path of the open capture.
2. `get_latest_values` with `entries`: the latest values by topic name, with the robot's
   timestamp and the age in milliseconds. A stale age means the topic stopped publishing.
3. `wait_for_change` with `entry` and `timeout_ms`: the next publication.
4. Any tool with a time scope accepts `last_seconds`: "the battery over the last 30 seconds"
   is `get_statistics` with `last_seconds: 30` on the open capture's path.

### 5. The explorer

WPILog Explorer is the extension's viewer: the same server's numbers, plotted, with no assistant
needed. The [extension guide](../vscode-extension/README.md#exploring-logs) has the details.

1. Click a log in the **Logs** view. The editor shows the time range, entries, sample counts
   and any truncation note. The **Entries** view follows the active log.
2. Click an entry to plot it; expand a struct or array to its numeric fields. Drag to zoom,
   wheel to zoom about the cursor, shift-wheel or arrow keys to pan, double-click to reset.
3. The **Timeline** pane shows the Driver Station segments; drag a region to select a window.
   The **Console** pane filters text by pattern, level and window. The **Field** pane draws the
   logged pose on the season's field. The **REV** pane shows REV signals by bus and device.
4. **Data view of selected entries** opens the exact samples in a grid with filtering, pivots,
   quick charts and CSV export.
5. **WPILog Explorer: Ask about this selection** sends the path, entries and window to chat.
   **Open in Notebook** writes a Python notebook that fetches the same samples.
6. With a pit server URL set, the Logs view gains a **Pit server** root. **Follow live session**
   polls once a second so the plot tracks the robot.

## Part II: Running the server

### 6. The shared server on a laptop

#### 6.1 Install

One command installs the latest full release. The installer shipped with these changes offers
log directories, a team number and the matching extension when run in a terminal. It keeps
existing settings; without a terminal it takes the defaults. Older installers offer fewer
options, and the latest full release may not yet carry the commands in this manual:

```bash
curl -fsSL https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.sh | sh
```

```powershell
irm https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.ps1 | iex
```

The layout is `~/.wpilog-mcp/` with `bin/` (the `wpilog-mcp` launcher), `jars/` and
`servers.yaml`. Add `~/.wpilog-mcp/bin` to your `PATH` to run `wpilog-mcp` in a terminal; MCP
clients use the full path. Java 17 or newer is required; the WPILib JDK is found first.
Details, a pre-release install and an install from a checkout: [Install](STANDALONE.md#install).

Until this version and its installer are published, use the `pit-server` checkout. In its
directory, run:

```bash
export JAVA_HOME="$HOME/wpilib/2026/jdk"
./gradlew install
export PATH="$HOME/.wpilog-mcp/bin:$PATH"
wpilog-mcp -version
```

Use your own JDK path if it differs. Continue only when the printed version is 0.10.0-dev1 or
newer; a completed download alone does not show that the installed launcher works.

#### 6.2 Start, stop, status

```bash
wpilog-mcp start http            # background; returns once the server answers
curl http://127.0.0.1:2363/health
wpilog-mcp stop http             # finishes calls in progress, then exits
```

`start http` while it runs reports the running server. After an upgrade, `start` stops the old
version and starts the new one. MCP clients that run `connect http` start the server themselves,
so you rarely need `start` by hand. Logs: `~/.wpilog-mcp/logs/http.log`.

#### 6.3 Configure the server

Edit `~/.wpilog-mcp/servers.yaml`, then restart (`wpilog-mcp stop http`, and reconnect).
Replace the example directories with yours; leave out the archive line unless that disk exists:

```yaml
team: 2363
logdir:
  - ~/riologs
  - /Volumes/LOGS/archive
# tba_key: your-key-here       # or TBA_API_KEY in the environment
servers:
  http:
    transport: http
    port: 2363
```

Every field, with its default: [Config Fields](STANDALONE.md#config-fields). VS Code's Settings
UI does not edit this file; its directories are temporary leases ([6.5](#65-project-directories)).

#### 6.4 Register your assistant

Claude Code, once per user account:

```bash
claude mcp add --scope user wpilog-analyzer -- "$HOME/.wpilog-mcp/bin/wpilog-mcp" connect http
```

If the command says the name already exists, check `claude mcp get wpilog-analyzer`: the
command should be this install's launcher, with arguments `connect http`. Do not add a second
name. If that existing registration is obsolete, remove just this user entry with
`claude mcp remove wpilog-analyzer --scope user`, then run the add command again.

Restart an open Claude Code session afterwards and check `/mcp`. The extension runs this
command for you when it finds the Claude CLI (**WPILog Analyzer: Register with Claude Code**
repeats it). Claude Desktop, Codex, Antigravity and other clients take the same command form or
the URL `http://127.0.0.1:2363/mcp`: [MCP Client Setup](STANDALONE.md#mcp-client-setup).

#### 6.5 Project directories

A robot project's own logs (simulation output, a `logs/` folder) can be leased to the shared
server for the life of a session instead of configured permanently:

- In VS Code, the User and Workspace log directory settings become the window's lease.
- From a terminal, put `logdir: ["logs", "sim/logs"]` and optionally `team` in the project's
  `.wpilog-mcp.yaml` (the extension offers to write it), or register the bridge with
  `connect http --logdir logs --team 1234`.

Leases are visible to every connected client while the session lives; see
[Directories by lease](STANDALONE.md#directories-by-lease).

#### 6.6 Upgrade, replace, uninstall

Run the installer for the version you want again; a checkout install is `./gradlew install`
from that checkout, while the release script selects a published release. It keeps your
configuration. A running server keeps the old version until the next `start` or `connect`.
For a clean reinstall that preserves settings, pass
`--refresh`. To uninstall, stop the servers, delete `~/.wpilog-mcp`, remove the `PATH` line
and the client registration, and delete the disk cache:
[Upgrading](STANDALONE.md#upgrading), [Uninstalling](STANDALONE.md#uninstalling).

### 7. The pit server

#### 7.1 What you need

- A computer on the robot's network: in the shop, on the practice-field network, or the Driver
  Station laptop in the pit over the tether. The pit server does not run during a match.
- The standalone install ([6.1](#61-install)) and Java 17 or newer.
- The robot's team number, or its address, or the USB tether.
- A directory for the store, where every recording and pulled log will live.
- A calendar clock you trust on that computer: session directories and the matching of pulled
  logs use it, and a robot network usually has no time source.

#### 7.2 Configure

Add `pit` under the existing `servers` mapping in `~/.wpilog-mcp/servers.yaml`; do not add a
second `servers` key. Give it a `capture` block. The smallest useful
configuration records the robot and serves dashboards:

```yaml
servers:
  pit:
    transport: http
    idle_exit_minutes: 0
    capture:
      robot: {team: 2363}          # or {usb: true}, or {host: 10.23.63.2}
      store: ~/pit-store
      gateway: {port: 5810}        # optional: dashboards connect here instead of the robot
```

Add what you want recorded beside the data:

```yaml
      pull:
        enabled: false             # turn on after the shop test (section 8)
        ssh: {}                    # lvuser, empty password: enables stats and the console tail
    context:
      photonvision: [photonvision.local]
      jvm: {port: 5809, period_sec: 1}   # needs the JMX flags in the robot program (8.4)
```

Every key, bound and default: [Pit server](STANDALONE.md#pit-server). Capture requires
`transport: http` and `idle_exit_minutes: 0`.

#### 7.3 Start

The examples use the same HTTP port, 2363, as the laptop's `http` server. If that server is
running, stop it before starting `pit`:

```bash
wpilog-mcp stop http
```

One port serves one configuration. To keep both servers running, give `pit` a different
`port` and use that port in the health checks and client URLs. A different name on an
already-served port is refused: "Port 2363 is served by 'http' (PID 1234); stop it with
`wpilog-mcp stop http`, or give 'pit' its own port." The PID in the message is the running
process. An older server that reports no name keeps the compatibility behavior: it may be
adopted, with a warning naming its version, so check `/health` before continuing.

```bash
wpilog-mcp start pit
```

For other laptops to reach it, bind to the network first; the server has no authentication, so
this is for the team's private network:

```bash
WPILOG_HTTP_BIND=0.0.0.0 wpilog-mcp start pit
```

Choose the bind before starting the server. If it is already running, stop it first; repeating
`start` joins that process and does not change its listening address.

Other ways to run it: `wpilog-mcp run pit` in the foreground, logging to the terminal; or a
systemd service on a Linux box that stays in the shop
([Running as a service](STANDALONE.md#running-as-a-service)). A robot that is off is normal: the
server starts at once and the client retries until the robot answers.

#### 7.4 Verify, in order

1. `curl http://127.0.0.1:2363/health` answers with the version and the `gateway` state:
   `listening` on its port, or `waiting` with a `cause` (usually another program on the port).
2. Before the robot connects, an empty store gives `list_sessions` status `not_applicable`,
   reason "No capture or imported session has been recorded", and `sessions: []`. There is
   no session row to hold `providers[]` yet. Check the offline providers with:
   ```bash
   curl -fsS http://127.0.0.1:2363/metrics | grep -E '^wpilog_(nt_connected|capture_open|provider_state)'
   ```
   `wpilog_nt_connected` and `wpilog_capture_open` are `0`. The configured stats, console
   tail and JVM providers report `offline`; PhotonVision initially reports `waiting` for a
   session and an NT4 clock estimate. This is normal with the robot off. A health answer with
   gateway `disabled` despite the example's port 5810 means you should check which configuration
   owns the HTTP port before proceeding.
3. Turn the robot on. Ask the assistant for `list_sessions`, or call it from any client. A
   session appears with `connected: true`, `records` and `bytes_per_sec` growing, and the
   `robot` address. `wpilog_nt_connected 1` appears at `GET /metrics`.
4. `list_available_logs` lists the capture with `session.open: true`. Its path is the one every
   log tool reads while it is being written.
5. `get_latest_values` with a topic you know the robot publishes, such as
   `/SystemStats/BatteryVoltage` (as `NT:/SystemStats/BatteryVoltage` in the capture): the
   value, the robot's timestamp and a small age.
6. Each session's `providers[]` in `list_sessions`, one entry per provider by name: with `pull.ssh`
   configured, `roboRIO` (system stats) reads `sampling` and `tail/robot/program_console`
   reads `following`; with PhotonVision configured, `photonvision/<host>:5800` reads `following`;
   with JVM configured, `jvm` reads `sampling`. A provider that reads `stand_down` carries
   the reason, and the fix is almost always in it.
7. Reboot the robot. `list_sessions` shows a new session; the old one has `ended_at` and an
   `end_reason`.

#### 7.5 Dashboards through the gateway

With `capture.gateway` enabled, point AdvantageScope, Elastic or any NT4 dashboard at the pit
computer's address and port 5810 instead of the robot. The gateway serves everything the pit
server receives, forwards the robot's timestamps, and ignores writes with one warning per
connection. It follows `WPILOG_HTTP_BIND`, carries no authentication, and belongs on the
private network. `wpilog_gateway_clients` at `/metrics` counts the dashboards.

#### 7.6 Pulling the robot's logs

With `capture.pull.enabled: true`, the pit server copies the robot's own log files whenever the
robot has sat disabled for the settle time (5 seconds by default) with the NT4 connection up.
It pauses the moment the robot is enabled and resumes where it stopped.

- Files wait under `robots/<serial>/pulled/` until they load cleanly, then move into their
  session's `robot/` directory. A pulled log joins a session only on strong data correlation
  within 250 ms of the capture; otherwise it starts a session of its own with
  `matching_reason` in the listing.
- `list_sessions` shows each session's `imports[]` with the method and offset.
  `wpilog_pull_files_total` and `wpilog_pull_files_waiting` count them at `/metrics`.
- Nothing on the robot is deleted or modified.
- The roboRIO's own logs (`dmesg`, `/var/log/messages` or the journal, NI's logs, JVM crash
  files) are a separate opt-in, `capture.pull.system.enabled: true`, read by
  `search_system_logs` ([Pulled system logs](STANDALONE.md#pulled-system-logs-opt-in)).

Pulling is off by default until the shop test ([section 8](#8-the-shop-day)) has shown that the
roboRIO accepts the connection and that hashing its files costs what the design expects.

#### 7.7 Where the files are

```
<store>/
  store.json
  inbox/                                    drop files here to import them
  robots/<serial>/
    robot.json, pull.json
    pulled/                                 transfers in progress
    sessions/<date>/<time>Z[_<EVENT>_<MATCH>]/
      session.json                          the facts, and every file with its provenance
      capture.wpilog, capture-2.wpilog ...  the recording, rolled at the size bound
      robot/                                pulled and imported logs, under the robot's names
      robot/system/                         the roboRIO's per-boot logs
    system/                                 the roboRIO's logs that span boots
  unassigned/<hash>/                        imported files whose robot or session is unknown
```

Do not move or rename files inside a store by hand: the server reports a stray and leaves it
alone. Bring files in through the inbox, the import command or an upload
([section 9](#9-stores-import-inbox-sync-mirror)).

#### 7.8 Stop, and what a crash does

`wpilog-mcp stop pit` finishes calls in progress, closes the recording and writes the final
manifest, waiting at most 30 seconds for the writer. A hand-run `run pit` stops with Ctrl-C.

A power loss or a kill leaves a session marked open. The next start sweeps the store before
NT4 starts. A readable capture is finalized with its hash and size and
`end_reason: "server stopped while recording"`. A damaged one stays open, with the reason in
the manifest and the server log. Nothing in the capture's bytes is changed.

### 8. The shop day

The first day with a real robot settles the facts the server cannot know in advance: what the
roboRIO's image permits, what it costs, and what the coprocessor really runs. Do these in order.

#### 8.1 Collect the roboRIO's facts

Connect to the robot's network and run, from any computer with the install:

```bash
wpilog-mcp robot-facts 172.22.11.2 --out rio-facts.md          # over USB
wpilog-mcp robot-facts roboRIO-2363-FRC.local --out rio-facts.md
wpilog-mcp robot-facts --server pit --out rio-facts.md          # the pit server's address and SSH settings
```

It makes one read-only SSH connection and writes a dated report. The report records:

- which commands exist (`journalctl`, `dmesg`, `df`, `sha256sum`), and their versions;
- whether `lvuser` can read the robot program's `/proc` entries;
- the log directories, and the kernel's timestamp format;
- the deployed runtime's modules: `jdk.management.agent` for JMX, `jdk.jfr` for Flight Recorder;
- uptime beside the date, and how long hashing the largest log takes.

Each fact is **found**, **absent** or **refused**. The report never contains a password. Keep it
outside the repository, and send the conclusions to whoever maintains the configuration.
Details: [Robot facts](STANDALONE.md#robot-facts-for-the-shop).

#### 8.2 Decide the pull settings

From the report: if sshd accepts `lvuser` with an empty password and `sha256sum` exists, set
`capture.pull.enabled: true`. If the hash cost of the largest log is acceptable, leave
`rate_bytes` at its default of 1 MB/s. Set `capture.pull.system.enabled: true` only after
checking which of `dmesg`, the journal and `/var/log/messages` the image provides.

#### 8.3 Watch the first pull

1. Start the pit server, turn the robot on, leave it disabled.
2. Once the settle time has passed, the server log reports the pull starting, and
   `list_sessions` gains `imports`.
3. Enable the robot: the log reports the pause. Disable it: the pull resumes.
4. In `list_available_logs`, the pulled log appears under its session, or on its own with a
   `matching_reason` if the correlation with the capture was not strong enough.

#### 8.4 JVM context

Add the five JMX launch arguments to the robot program's GradleRIO deploy configuration, with
the robot's reachable address, and redeploy:

```groovy
jvmArgs.addAll([
    "-Dcom.sun.management.jmxremote.port=5809",
    "-Dcom.sun.management.jmxremote.rmi.port=5809",
    "-Djava.rmi.server.hostname=10.23.63.2",
    "-Dcom.sun.management.jmxremote.authenticate=false",
    "-Dcom.sun.management.jmxremote.ssl=false"
])
```

These flags disable authentication and encryption on that port: private team network only.
With `context: {jvm: {port: 5809}}` on the pit server, the `jvm` provider should read
`sampling` within a few seconds of the robot program starting. A reason that names the launch
flags means the port did not answer: check the flags and the address in them. The report from
8.1 says whether the runtime carries `jdk.management.agent`, without which JMX cannot work.

#### 8.5 The vision coprocessor

Confirm that the coprocessor runs **PhotonVision v2026.3.4**, the release the provider is pinned
to, and that the pit computer can open its web page. Put its host in `context.photonvision`
and check `providers[]`: `following` means the settings were captured. Camera names must match
the NetworkTables names exactly. Change a pipeline setting on the camera and ask `analyze_vision`
for `camera_settings` afterwards. Another PhotonVision version is refused with its reason; the
provider is not relaxed to guess at a different release.

#### 8.6 A real dashboard

Point AdvantageScope or the team's dashboard at the gateway ([7.5](#75-dashboards-through-the-gateway))
and drive. The values should match what the robot shows directly, and a choice made on the
dashboard should not reach the robot.

### 9. Stores: import, inbox, sync, mirror

#### 9.1 Import from a USB stick or a folder

```bash
wpilog-mcp import --server pit /media/usb/logs
wpilog-mcp import --robot practice ~/riologs/downloads     # name the robot when the log has no serial
```

Files are copied (`--move` moves them), identified by content, hashed, loaded to check them,
and placed by robot and session. The output lists each file as imported, present (a duplicate,
left at its source), unassigned (no robot or session known yet) or refused, with the reason.
When the pit server is running, sources inside its directories are posted to it; a USB stick is
copied into the store's `inbox/` for the server to import. From VS Code, **Upload Logs to Pit
Server** streams files to the pit server over the network, and the Logs view's **Import** does
the same for a plain file or folder. Reference: [Importing Logs](STANDALONE.md#importing-logs).

A WPILOG import of a known robot joins a session that already has a capture or a data-matched
log only on strong correlation within 250 ms. Without such an anchor it is grouped by calendar
overlap, and the manifest says so.

#### 9.2 The inbox

Drop files into `<store>/inbox/`. The owning server imports each file once its size has been
stable for three seconds, and writes one line per file to `inbox/imported.log` with the
outcome. A refused file stays there with its reason until it changes. A folder with a
`batch.json` naming `stated_robot` imports as one batch for that robot.

#### 9.3 Assigning a file to a robot

An unassigned file appears under **Unassigned** in the Logs view and under `unassigned` in
`list_available_logs`. **Assign to Robot…** moves it into the chosen robot's history through
the server; the HTTP route is `POST /store/assign`
([The Import Endpoint](STANDALONE.md#the-import-endpoint)).

#### 9.4 Two laptops with stores

When the logs of an event ended up split between two laptops, each pulls what it lacks from
the other over the store door. Both servers must be bound to the network.

```bash
WPILOG_HTTP_BIND=0.0.0.0 wpilog-mcp start http      # on each laptop
wpilog-mcp sync http://other-laptop:2363            # on one, then the other
```

Files are copied with their provenance, verified by hash and by loading; overlapping sessions
of the same robot join; disagreements about names and comments are listed as `conflicts`, and
nothing is deleted on either side. The peer URL is remembered, so the next run is
`wpilog-mcp sync` alone. In VS Code: **Sync from Laptop**. Reference:
[Syncing Stores Between Laptops](STANDALONE.md#syncing-stores-between-laptops).

#### 9.5 A laptop's mirror of the pit server

A mirror is a scoped copy of the pit server's store that every tool reads offline: on the bus,
at home, the night before finals.

- In VS Code, set `wpilog-mcp.pitServerUrl` (for example `http://pit:2363/mcp`). The mirror
  starts by default: the last 14 days plus named events and pinned sessions, under a 20 GB cap.
  The status bar shows **Mirror synchronized** or **Mirror offline** with the age; click it to
  pin a session, sync now or open the folder.
- Without VS Code, put a `mirror` block in `servers.yaml` with the same `origin` and `folder`
  ([The Mirror](STANDALONE.md#the-mirror)); the local server keeps syncing whenever the origin
  answers.

A mirrored log's listing carries `origin`, `complete`, `growing` and `last_sync`. Imports never
write into a mirror, and nothing is deleted except by the mirror's own cap, and only when the
origin still holds the file.

#### 9.6 Claude Code with VS Code closed

The window's directory lease ends when VS Code closes. For a terminal session, either the
`mirror` block above (the folder stays admitted and synced) or
`connect http --logdir /path/to/mirror` for temporary access.

### 10. Maintenance

- **Upgrading.** Run the installer again ([6.6](#66-upgrade-replace-uninstall)). A background
  server is replaced at its next `start` or `connect`; a systemd service needs
  `sudo systemctl restart wpilog-mcp-pit.service`. A pre-release installs with
  `--tag <version> --with-extension` ([Trying a pre-release](STANDALONE.md#trying-a-pre-release)).
- **Backing up a store.** Stop the server and copy the whole store directory, or sync it to a
  second laptop ([9.4](#94-two-laptops-with-stores)). Never copy files out of or into a store
  while a server owns it.
- **Disk space.** The disk cache (REV synchronizations) lives outside the install; its location
  and the `diskcachesize` cap are under [Config Fields](STANDALONE.md#config-fields). Capture
  files roll at `capture.max_file_bytes` (1 GiB by default); old sessions are never deleted by
  the server.
- **Memory.** `WPILOG_MAX_HEAP` (default `4g`) sets the server's heap; logs of several hundred
  megabytes may need `8g`. The extension's `maxHeap` setting does the same for the server it
  starts.
- **Secrets.** The TBA key goes in `servers.yaml`, the environment or the extension's secret
  storage, never on a command line. An SSH password for pulling is written as `${NAME}` and
  supplied through the environment. Proxy credentials stay in VS Code's secret storage.
- **Logs to read when something is odd.** `~/.wpilog-mcp/logs/<name>.log` for a background
  server; the terminal for `run`; `journalctl -u wpilog-mcp-pit.service` for a service; the
  **WPILog Analyzer** output channel in VS Code.

## Part III: When something is wrong

| Symptom | Check | Fix |
|---|---|---|
| The assistant has no wpilog tools | Claude Code: `/mcp`. Others: the client's MCP configuration | Register ([6.4](#64-register-your-assistant)); restart the assistant session; approve the server |
| The extension shows "Failed" or no logs | Output panel, **WPILog Analyzer**; **Show Server Log** | A refused directory is named: create it or fix the setting. **Restart Server** |
| `wpilog-mcp` times out or prints a Java error | `java -version`; the launcher wants Java 17 or newer | Install the WPILib JDK, or set `JAVA_HOME` |
| Port 2363 is busy | `/health` answers from something else | Change `servers.http.port` in `servers.yaml` and restart |
| Every tool answers `no_match` or an error naming the directory | `list_available_logs`: `log_directories` | The log is outside every configured or leased directory: add it ([6.3](#63-configure-the-server), [6.5](#65-project-directories)) |
| Out of memory on a large log | The server log | `WPILOG_MAX_HEAP=8g`, or the extension's `maxHeap`, then restart |
| A log is reported truncated | `_metadata.log_truncation` in results | Normal for a robot switched off while logging; every result says what was read |
| The pit server never connects | `list_sessions`: `connected: false`; the server log | Check `capture.robot`: team number for mDNS and `10.TE.AM.2`, `usb: true` on the tether, or `host`. The robot must be on and on the same network |
| The gateway reads `waiting` | `/health`: `gateway.cause` | Another program holds the port: stop it or change `capture.gateway.port`; capture continues meanwhile |
| Pulling never starts | `list_sessions`: the robot must be `connected` and disabled for `settle_sec`; the server log | `capture.pull.enabled: true`; the robot disabled; SSH accepted. A changed host key is reported: verify, then `accept_changed_host_key: true` once, or clear the pin in `robot.json` |
| A provider reads `stand_down` | Its `reason` in `list_sessions` | PhotonVision: wrong version or unreachable host. JVM: the launch flags, or the port. SSH stats or tail: the command or file the reason names |
| A pulled or imported log sits in its own session | `matching_reason` in `list_available_logs` | The data proof failed or was ambiguous; the file is still readable by path. Nothing is guessed |
| An import is refused as outside the configured directories | The command's output | Copy the files into the store's `inbox/` or use the extension's upload |
| `sync` answers 409 | A sync for that store is already running | Wait for it; `GET /store/sync/<job>` shows its state |
| The mirror reads `offline` | The pit server is unreachable, or bound to loopback | `WPILOG_HTTP_BIND=0.0.0.0` on the pit server; the mirror keeps serving what it holds |
| Non-ASCII filenames are refused | The server log names the native encoding | Run the server with a UTF-8 locale (`LANG=C.UTF-8 LC_ALL=C.UTF-8`) |

More: [Troubleshooting](STANDALONE.md#troubleshooting) in the standalone guide and
[Troubleshooting](../vscode-extension/README.md#troubleshooting) in the extension guide.
