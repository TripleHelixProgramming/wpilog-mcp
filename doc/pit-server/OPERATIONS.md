# Pit server: Operations

> **Experimental:** the pit server has limited controlled-shop evidence. These documents are
> retained for development and controlled shop evaluation, not as a recommendation for team use.
> Automated tests and simulation do not establish reliability on a real robot.

## 1. Controlled setup

### 1.1 What you need

- A computer on the robot's network: in the shop, on the practice-field network, or the Driver
  Station laptop in the pit over the tether. The pit server does not run during a match.
- The standalone install ([6.1](../OPERATIONS.md#61-install)) and Java 17 or newer.
- The robot's team number, or its address, or the USB tether.
- A directory for the store, where every recording and pulled log will live.
- A calendar clock you trust on that computer: session directories and the matching of pulled
  logs use it, and a robot network usually has no time source.

### 1.2 Configure

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

### 1.3 Start

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
([Running as a service](../STANDALONE.md#running-as-a-service)). A robot that is off is normal: the
server starts at once and the client retries until the robot answers.

### 1.4 Verify, in order

1. `curl http://127.0.0.1:2363/health` answers with the version, `capture`, and the `gateway` state:
   `listening` on its port, or `waiting` with a `cause` (usually another program on the port).
   `capture.captures[].state` distinguishes `waiting_admission`, `recording` and `stopped`;
   a stopped row carries a `reason` while process `status` stays `ok`. `low_disk` resumes
   automatically above the configured floor plus margin, and `waiting_for_store` once the store
   is free; its `cause` says what held it. For `startup_failed`,
   `admission_failed` or `write_failed`, read the private server log for details.
   `last_write_age_ms` is null before a completed write; a high age alone is not proof of
   missing samples because some topics publish only changes.
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
   configured, `roboRIO` first reads `waiting`, with a reason naming the current
   device-identity read, program discovery or first statistics read. These are startup steps
   on an established SSH connection; a missing NT4 clock estimate reads `waiting_for_sync`.
   Once ready, `roboRIO` (system stats) reads `sampling` and `tail/robot/program_console`
   reads `following`; with PhotonVision configured, `photonvision/<host>:5800` reads `following`;
   with JVM configured, `jvm` reads `sampling`. A provider that reads `stand_down` carries
   the reason, and the fix is almost always in it.
7. Reboot the robot. `list_sessions` shows a new session; the old one has `ended_at` and an
   `end_reason`.

### 1.5 Dashboards through the gateway

With `capture.gateway` enabled, point AdvantageScope, Elastic or any NT4 dashboard at the pit
computer's address and port 5810 instead of the robot. The gateway serves everything the pit
server receives, forwards the robot's timestamps, and ignores writes with one warning per
connection. It follows `WPILOG_HTTP_BIND`, carries no authentication, and belongs on the
private network. `wpilog_gateway_clients` at `/metrics` counts the dashboards.

AdvantageScope connects only after its request for `GET /` on port 5810 succeeds within 250 ms,
then opens a second, clock-only connection ([NT4 gateway for dashboards](STANDALONE.md#nt4-gateway-for-dashboards)).
Once it is connected, the server log shows `NT4 gateway client connected: AdvantageScope` twice,
once with `v4.1.networktables.first.wpi.edu` and once with `rtt.networktables.first.wpi.edu`, and
`wpilog_gateway_clients` reads 1.

### 1.6 Pulling the robot's logs

With `capture.pull.enabled: true`, the pit server copies the robot's own log files whenever the
robot has sat disabled for the settle time (5 seconds by default) with the NT4 connection up.
It pauses the moment the robot is enabled and resumes where it stopped.

The server log names why pulling is paused: `NT4 is disconnected`, `Robot is enabled or its enabled state is unknown`, or `Waiting for disabled-state settling`. A changed reason is logged once; repeated checks of the same state stay quiet. An I/O retry after NT4 disconnects includes that reason and the original error at INFO. Known local filesystem failures, I/O errors while NT4 remains connected, and unexpected non-I/O exceptions remain WARN. A remote-handle close after a successful checkpoint is identified separately; a disconnected close is INFO, while a local checkpoint failure remains WARN even if closing the remote handle also fails.

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

Pulling is off by default until the shop test ([section 8](#2-the-shop-day)) has shown that the
roboRIO accepts the connection and that hashing its files costs what the design expects.

### 1.7 Where the files are

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
([local import guide](../OPERATIONS.md#7-stores-import-and-inbox)).

### 1.8 Stop, and what a crash does

`wpilog-mcp stop pit` finishes calls in progress, closes the recording and writes the final
manifest, waiting at most 30 seconds for the writer. A hand-run `run pit` stops with Ctrl-C.

A power loss or a kill leaves a session marked open. The next start sweeps the store before
NT4 starts. A readable capture is finalized with its hash and size and
`end_reason: "server stopped while recording"`. A damaged one stays open, with the reason in
the manifest and the server log. Nothing in the capture's bytes is changed.

## 2. The shop day

The first day with a real robot settles the facts the server cannot know in advance: what the
roboRIO's image permits, what it costs, and what the coprocessor really runs. Do these in order.

### 2.1 Collect the roboRIO's facts

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
For an installed service, run the named-server form as the service's account. On Ubuntu, for
example: `sudo -u wpilog-mcp wpilog-mcp robot-facts --server pit --config /etc/wpilog-mcp/servers.yaml --out /tmp/rio-facts.md`.
Choose a new output path that account can write. On POSIX file systems, a foreign or unverifiable
store owner is refused before local pins are written, including when invoked as root or when
group permissions permit writing. Windows uses actual filesystem permissions instead of comparing
the owner with the current user; an ACL denial explains the permission failure and advises using
the service's account or the direct-address form. That form uses your own account's host-key pins.

Details: [Robot facts](STANDALONE.md#robot-facts-for-the-shop).

### 2.2 Decide the pull settings

From the report: if sshd accepts `lvuser` with an empty password and `sha256sum` exists, set
`capture.pull.enabled: true`. If the hash cost of the largest log is acceptable, leave
`rate_bytes` at its default of 1 MB/s. Set `capture.pull.system.enabled: true` only after
checking which of `dmesg`, the journal and `/var/log/messages` the image provides.

### 2.3 Watch the first pull

1. Start the pit server, turn the robot on, leave it disabled.
2. Once the settle time has passed, the server log reports the pull starting, and
   `list_sessions` gains `imports`.
3. Enable the robot: the log reports the pause. Disable it: the pull resumes.
4. In `list_available_logs`, the pulled log appears under its session, or on its own with a
   `matching_reason` if the correlation with the capture was not strong enough.

### 2.4 JVM context

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

### 2.5 The vision coprocessor

Confirm that the coprocessor runs **PhotonVision v2026.3.4**, the release the provider is pinned
to, and that the pit computer can open its web page. Put its host in `context.photonvision`
and check `providers[]`: `following` means the settings were captured. Camera names must match
the NetworkTables names exactly. Change a pipeline setting on the camera and ask `analyze_vision`
for `camera_settings` afterwards. Another PhotonVision version is refused with its reason; the
provider is not relaxed to guess at a different release.

### 2.6 A real dashboard

Point AdvantageScope or the team's dashboard at the gateway ([7.5](#15-dashboards-through-the-gateway))
and drive. The values should match what the robot shows directly, and a choice made on the
dashboard should not reach the robot.

## 3. Live analysis in the shop

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

## 4. Sync and mirrors

### 4.1 Two laptops with stores

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

### 4.2 A laptop's mirror of the pit server

A mirror is a scoped copy of the pit server's store that every tool reads offline: on the bus,
at home, the night before finals. It copies the logs the pit pulled or was given, not the pit's
captures; this laptop records its own live data.

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

### 4.3 Claude Code with VS Code closed

The window's directory lease ends when VS Code closes. For a terminal session, either the
`mirror` block above (the folder stays admitted and synced) or
`connect http --logdir /path/to/mirror` for temporary access.

## 5. Additional recipes

### Loop timing

On a pit server capture with JVM context, `read_entry` on `/Daemon/JVM/` entries around the
   same time: a garbage collection count or time that jumps there is the pause.

### Vision

On a pit server capture with `context.photonvision` configured, the camera's calibration,
   pipeline and exposure settings are recorded under `/Daemon/PhotonVision/<camera>/Settings`;
   `analyze_vision` reports them as `camera_settings`.

### System logs

On a pit server session with system-log pulling enabled, `search_system_logs` with any log of
   the session as `path` and a `source` (`kernel`, `syslog`, `program`, `jvm_crash`) reads the
   roboRIO's own logs: a CAN interface going bus-off, a camera unplugging, a JVM crash file. A
   laptop's mirror gives the same kernel times once the session's capture file has closed.

### REV data from the pit

On a pit server session, the REV logs it pulled read on the clock of its capture and on that of
   the robot's own log: `list_available_logs` lists each under both, with `sync_method`
   `COMBINED` under the robot's log, and `list_revlog_signals`, `get_revlog_data` and
   `sync_status` take either as `path`. A laptop's mirror holds the robot's log, not the
   capture, and gives the same times on it. `sync_status` names the two measurements it combined
   and `max_error_ms`, the most the combination can be off.

### Explorer follow

With a pit server URL set, the Logs view gains a **Pit server** root. **Follow live session**
   polls once a second so the plot tracks the robot.

## 6. Imports over the network

When the pit server is running, sources inside its directories are posted to it; a USB stick is
copied into the store's `inbox/` for the server to import. From VS Code, **Upload Logs to Pit
Server** streams files to the pit server over the network, and the Logs view's **Import** does
the same for a plain file or folder. Reference: [Importing Logs](../STANDALONE.md#importing-logs).

## 7. Maintenance

- **Upgrading.** Run the installer again ([6.6](../OPERATIONS.md#66-upgrade-replace-uninstall)). A background
  server is replaced at its next `start` or `connect`; a systemd service needs
  `sudo systemctl restart wpilog-mcp-pit.service`. A pre-release installs with
  `--tag <version> --with-extension` ([Trying a pre-release](../STANDALONE.md#trying-a-pre-release)).
- **Backing up a store.** Stop the server and copy the whole store directory, or sync it to a
  second laptop ([9.4](#41-two-laptops-with-stores)). Never copy files out of or into a store
  while a server owns it.
- **Disk space.** The disk cache (REV synchronizations, scan indexes and column files) lives
  outside the install; its location and the `diskcachesize` cap are under
  [Config Fields](../STANDALONE.md#config-fields). Capture
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

## 8. Troubleshooting

| Symptom | Check | Fix |
|---|---|---|
| The pit server never connects | `list_sessions`: `connected: false`; the server log | Check `capture.robot`: team number for mDNS and `10.TE.AM.2`, `usb: true` on the tether, or `host`. The robot must be on and on the same network |
| The gateway reads `waiting` | `/health`: `gateway.cause` | Another program holds the port: stop it or change `capture.gateway.port`; capture continues meanwhile |
| AdvantageScope never connects to the gateway | Open `http://<pit>:5810/` from that computer: it should show "WebSockets must be used to access NetworkTables." | A 404 means the pit server predates AdvantageScope support: update it. If the page loads but slowly, the link is too slow for AdvantageScope's 250 ms check: connect from the pit network |
| Pulling never starts | `list_sessions`: the robot must be `connected` and disabled for `settle_sec`; the server log | `capture.pull.enabled: true`; the robot disabled; SSH accepted. A changed host key is reported: verify, then `accept_changed_host_key: true` once, or clear the pin in `robot.json` |
| A provider reads `stand_down` | Its `reason` in `list_sessions` | PhotonVision: wrong version or unreachable host. JVM: the launch flags, or the port. SSH stats or tail: the command or file the reason names |
| `sync` answers 409 | A sync for that store is already running | Wait for it; `GET /store/sync/<job>` shows its state |
| The mirror reads `offline` | The pit server is unreachable, or bound to loopback | `WPILOG_HTTP_BIND=0.0.0.0` on the pit server; the mirror keeps serving what it holds |

