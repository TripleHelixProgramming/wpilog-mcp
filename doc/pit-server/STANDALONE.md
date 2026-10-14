# Pit server: Standalone

> **Experimental:** the pit server has limited controlled-shop evidence. These documents are
> retained for development and controlled shop evaluation, not as a recommendation for team use.
> Automated tests and simulation do not establish reliability on a real robot.

### Pit server

Add a `capture` section to a named HTTP server, then run `wpilog-mcp start pit`:

Add the name under the file's existing `servers` mapping. The default HTTP port is 2363,
also used by `http`: stop `http` first, or give `pit` a different `port` and adjust client
URLs. Starting a second name on an occupied port is refused when `/health` names another
configuration; an older server without a name can still be adopted, with a versioned warning.

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

The whole capture block can be inherited from `defaults`; a server's block replaces it. An
unknown capture key or an invalid value names the key in the startup error. Capture requires
`transport: http` and `idle_exit_minutes: 0` (the default). A robot that is off is normal: HTTP
starts at once and the client retries indefinitely. A server without `capture` behaves as before.

#### Pulled system logs (opt-in)

`capture.pull.system.enabled: true` adds a system-log phase to the same SSH worker, the same
disabled-and-connected gate and the same `capture.pull.rate_bytes` budget. It is independent of
`capture.pull.enabled`, which selects WPILOG and REV pulling. The default paths are **unverified
image candidates**, not claims about the NI installation, so collection is off by default.

| Key | Meaning |
| --- | --- |
| `capture.pull.system` | Optional system-log block |
| `capture.pull.system.enabled` | Boolean, default `false`; opt in after reviewing the paths |
| `capture.pull.system.kernel` | `dmesg` (default) or `off`; the kernel ring buffer, separate from the journal |
| `capture.pull.system.syslog` | Absolute paths, default `[/var/log/messages]`; includes dotted rotations of a selected file; may be empty |
| `capture.pull.system.journal` | Boolean, default `false`; replaces syslog-file collection with the whole journal, including program and service messages |
| `capture.pull.system.ni` | Absolute files/directories, default `[/var/local/natinst/log]`; directories are walked without following links |
| `capture.pull.system.jvm_crash` | Absolute files/directories, default `[/home/lvuser]`; only `hs_err_pid<N>.log` files |

**How collection behaves.** Paths reject `..` and control characters. A configured path that is
missing is an empty source, not an error.

`journal: true` runs `journalctl -q --no-pager -o short-unix --show-cursor`. The first pass adds
`-b` alone, for the current boot, so journalctl need not accept a boot UUID; the reply header
still records that UUID. Later passes add `--after-cursor`.

A missing or refused journal command stands that source down for the session with a recorded
reason; the server never guesses a syslog file instead. Kernel collection continues on its own.
A missing or permission-denied `dmesg` records its reason the same way. A dmesg ring larger
than 16 MiB is refused with a reason, not silently cut short.

Exec replies are read in 64 KiB blocks under the same gate and pacing as SFTP, and a stalled
block has a 30-second deadline. When the gate closes, the exec channel is released, because a
full output pipe would stall the other channels of the shared SSH connection; the next pass
resumes from the last committed cursor. SFTP files resume at their content-checked byte offset.

**Where the files go.**

- The session's kernel, NI and crash files live under its `robot/system/`. `session.json` records
  them in `system_logs.files`: source, remote path, pass time, byte count and hash, plus kernel
  continuity and journal cursors.
- Shared syslog snapshots live under `robots/<serial>/system/`, each recorded once in its
  `index.json` with its written calendar span when known. A search selects the spans that overlap
  the session, and unknown spans; a session whose own span is unknown cannot exclude a shared
  file. Older shared receipts in session manifests stay readable without being rewritten.
- Journal text is kept there in UTC-day files inside a session-id directory, so two FPGA sessions
  in one kernel boot cannot share a cursor by accident.
- Kernel overlap is removed using the last recorded line and the uptime; a lower uptime waits for
  the new capture session.
- A crash file joins the session whose recorded program PID matches it. Without a unique match it
  goes to `robot/system/unassigned/` with a note.
- No remote file is deleted or modified.

**Reading them.** `search_system_logs` with the capture's path reads the pulled record;
`search_strings` reads the timely copy that a followed file provides (see
[SSH stats and followed files](#ssh-stats-and-followed-files)). Kernel timestamps need recorded
`uptime_sec` pairs; journal and ISO wall-clock timestamps need the session's `systemTime`. Without
that evidence the text stays visible, with a null robot timestamp and a reason. A journald kernel
message can appear twice: source `kernel` means dmesg only, and source `syslog` includes the
whole journal.

**Sharing them.** The store door includes these receipts and the shared indices in
`/store/sessions`; the file and prefix-hash routes serve only committed byte lengths. Peer sync
carries the whole shared index; a mirror carries the shared files that its sessions' spans
select. Both verify the advertised hash, keep provenance, and resume growing text through the
held-prefix check. Shared bytes in a mirror count once toward its cap and stay until their last
retained session is evicted, provided the origin still holds them. A manifest ahead of its local
text returns `not_applicable` naming the collecting server: synchronize the copy, or query that
server.

**What still needs the shop.** The candidate paths and defaults above are settled by facts only
the robot can supply; [Robot facts for the shop](#robot-facts-for-the-shop) collects them:

- listings of `/var/local/natinst/log` and `/var/log`;
- whether `journalctl`, `dmesg` and `df` exist, and whether the SSH account may run dmesg;
- whether dmesg prints `[seconds]` stamps, which detecting a wrapped kernel buffer needs;
- whether journalctl accepts a boot id;
- the console path and the program command line.

#### SSH stats and followed files

When `capture.pull.ssh` is present (even `{}`), or pulling is enabled, system stats and the
program console follow are **on by default**. Pulling itself stays off unless `capture.pull.enabled`
is `true`. Stats and tails run while the robot is enabled too, and stop making SSH requests
while NT4 is disconnected.

One SSH connection per configured host carries independent SFTP, sample and follow channels. A
failed connection is retried after 1, 2, 4, 8, 16, then 30 seconds. The defaults make the
providers available for shop testing; revisit their measured cost on roboRIO 1 and 2 after the
shop measurement.

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

**System stats: what you see.** Each sample is one bounded exec request that reads `/proc` and
`df -Pk`, with the device's clock tick and page size. The values are recorded as
`/Daemon/roboRIO/` entries with explicit units and the metadata `source: ssh`, `host`,
`sampled: true` and `period_sec`. The command's **send** time is mapped to FPGA time; a sample
sent before an NT4 time estimate exists is counted and dropped. CPU and network rates are per
interval and need two samples.

**Startup state.** As soon as SSH connects, the stats provider reports `waiting` with
`Reading the device identity`, then `Discovering the robot program` and
`Reading robot statistics` while those operations run. A completed sample becomes
`sampling`, or `waiting_for_sync` if no NT4 clock estimate exists. The device identity is
read once per SSH connection and shared with pulls; an unavailable identity is also retained
for that connection, and reconnect starts a fresh attempt.

**Finding the robot program.** The program fields come from the deployed JAR. Its path is the
quoted `-jar` argument in `/home/lvuser/robotCommand`, then the exact arguments in
`/proc/*/cmdline`. A missing or ambiguous JAR omits the program fields with a reason, and a
custom launcher that does not expose the JAR path is reported. When no unique program is found,
the reason is logged once and discovery is retried every tenth sample while the other stats
continue. Each sample checks the known PID's start ticks; only a mismatch against a previously
identified PID and start time reports a changed process and asks for discovery on the next
sample.

**Cost.** Initial connection discovery reads the device's tick/page units and resolves the
candidate filesystem directories with `cd -- <path>` and `pwd -P`. Those facts stay cached
until SSH reconnects; a program restart only rediscovers its PID. Aliases of the same real
directory supply one argument to the steady command's single `df -Pk` invocation. Filesystem
entry names use the resolved directory. Steady proc reads use shell builtins. While the
sampling period remains above its configured base, the sampling reason names the last round
trip and configured budget, including during recovery. Exec replies remain bounded to 64 KiB
and 30 seconds, with no wait on NT4.

A follow command that exits reports its exit status and the first 4096 bytes of stderr in
the provider stand-down reason and state-change log. Excess stderr is discarded without
blocking the channel; a truncated prefix is labeled. Completion waits at most five seconds
on the follower reader, leaving capture and the shared connection independent.

**Followed files: what you see.** Each tail runs `tail -n 0 -F -s 1` and records one string
record per line, stamped at **receipt** time. BusyBox accepts the integer one-second polling
interval; a line can arrive up to one interval after it was written, plus transport and scheduling
delay. GNU tail uses inotify where available and this interval when it falls back to polling.
The native kernel/journal streams report a zero polling period because they deliver events. The `kernel` and `journal` roles use `dmesg -w` and
`journalctl -f` where the image supports them; otherwise they poll the configured file by inode
and byte offset at the current stats period. Lines received before a session starts keep their
receipt time, mapped through the next session's measured offset and clamped at zero, with
`buffered_before_session` in their metadata.

**Bounds.** Each file admits 200 lines per one-second bucket, bounds a line to 64 KiB, and keeps
at most 1000 pending lines. Excess lines are counted, and one drop notice is recorded after a
full second without another drop, including when a sustained burst ends in silence.

**Failure handling.** A missing or unreadable source logs a reason and stands down for the
session. An SSH reconnect resumes following but cannot recover the lines written during the
interruption; enable the separate system-log pull to get the retained files.

**In the tools, the manifest and the metrics.** `list_sessions` reports `providers[]`, and
`get_latest_values` includes these entries with `source: ssh` or `tail`. `session.json` records
provider state and costs under `capture_stats.providers`; `capture_stats.kernel_clock` pairs
`uptime_sec`, `fpga_timestamp_sec`, `offset_sec` (FPGA minus uptime) and `round_trip_ms`. The
processor time between samples measures the whole robot, not CPU attributed to the provider.
Numeric values appear as `nt_value` metrics, beside the provider duration, period, processor
time and drop counters. Stats output the server cannot parse stands the provider down for the
session with its reason; a new session retries it. These are sampled views; the capture remains
the record.

**Other hosts.** A tail on another host pins that host's fingerprint locally in `ssh-hosts.json`,
never its credentials. Robot contacts continue to use `robot.json`, and pins in this store are
not copied from a peer. If another host's key changes and authentication would send a password
or key, verify the new key and remove that host's pin before restarting. Never put
authentication secrets directly in the tail block.

#### PhotonVision configuration context

`context.photonvision` is a list of coprocessor hosts beside `capture`; it is off when absent or
empty. It requires capture and is inherited as a whole block from `defaults`; a named server can
clear it with `context: {photonvision: []}`. Hosts support environment interpolation, default to
port 5800, and may give a port. URLs, credentials, paths, duplicate addresses and unknown keys are
refused, with the configuration key in the error.

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

**How it reads the coprocessor.** The provider is pinned to **PhotonVision v2026.3.4**, whose UI
routes are private, not a stable API. At session start it requests
`GET /api/settings/photonvision_config.zip` and validates the ZIP's `photon.sqlite` member; the
archive is fingerprinted and discarded, so no SQL or native runtime is added. The current
configuration comes as binary MessagePack on `ws://<host>:5800/websocket_data`: calibration
matrices and reprojection errors per resolution; pipeline type, resolution, exposure, gain, 3D,
multi-tag and field layout; software version, device type and hardware status. The provider
sends no settings changes. Keep this unauthenticated backend on the private team network.

**What you see.** Each snapshot writes one JSON record per camera under
`/Daemon/PhotonVision/<camera>/Settings`. The UI supplies no snapshot timestamp, so the receipt
time is mapped through the NT4 robot-clock estimate, with that basis and the pinned release in
the metadata; the HTTP fetch's local clock is never used. Camera names must match the NT camera
names exactly: a generic camera label in the robot code is not taken to mean a particular
coprocessor camera.

**Refreshes.** This PhotonVision release omits the camera ID from its change notifications, so
the provider opens a new read-only socket and receives the complete state instead of guessing
which camera changed. Refreshes start at most once per second per backend; notifications inside
that interval, or during a refresh, coalesce into one later refresh that carries the current
state.

**Bounds.** HTTP bodies are bounded to 64 MiB, expanded ZIP contents to 256 MiB, WebSocket
messages to 4 MiB and a snapshot to 64 cameras. A request or initial snapshot has a ten-second
deadline. An unanswered WebSocket ping has a five-second deadline; pongs are stamped on the
network callback, so a delayed worker cannot blame the backend.

**Failure handling.** An unsupported version or shape, an unknown top-level message key (even
beside a valid snapshot), an unavailable backend or an exceeded bound logs the reason and stands
the provider down until a new session; the last captured settings remain readable. One worker
owns the network waits and the decoding, and the next message is read only after the previous
snapshot has been delivered in order.

**In the tools and the metrics.** `list_sessions` and the manifest show the provider's state and
reason and its record, byte and round-trip costs; `get_latest_values` marks its entries
`source: photonvision`. `wpilog_provider_state` reports one sample per provider, labeled with its
current state. `/photonvision/<camera>/` topics seen without a configured host produce one
configuration suggestion in the log per capture service.

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

**What you see.** Each poll reads memory (heap and non-heap used and committed bytes), cumulative
collection counts and seconds by collector, live, peak and daemon threads, loaded and total
classes, and process CPU seconds. A negative (unsupported) counter and an absent process-CPU
attribute are omitted. The numeric entries live under `/Daemon/JVM/`; `Runtime` records the VM
name, version and input arguments once per connection, with sensitive property values redacted.
The metadata carries `source: jmx`, `sampled: true`, `period_sec`, `clock: measured`, the host,
and `jvm_start_time_ms` as the JVM's identity.

**Timestamps.** A sample is stamped at receipt through the NT4 robot-time estimate, before either
delivery queue; with no estimate it is counted and dropped. Each poll records `uptime_sec`,
`clock/offset_sec` (robot time minus JVM uptime) and `clock/round_trip_bound_sec`. The bound
covers the complete JMX poll, the NT4 round trip and one millisecond of uptime quantization.

**ClockNote.** When the mapping moves by more than both adjacent samples' bounds, the provider
writes a `ClockNote`; earlier records keep their timestamps. The note records
`previous_offset_sec`, `current_offset_sec`, `change_sec`, `previous_round_trip_bound_sec`,
`current_round_trip_bound_sec`, and both `previous_jvm_start_time_ms` and
`current_jvm_start_time_ms`. It states that the mapping moved beyond the sum of the bounds, not
why: it does not diagnose a restart, skew or a wall-clock correction. A new session resets the
comparison.

**What the start time is not.** The JVM start time is never a clock: `startTime + uptime` stays
anchored to the wall clock at JVM startup and does not follow the Driver Station's later
correction, which the provider does not try to detect. The SSH stats pair kernel uptime with
FPGA time, not wall time. Corrected wall-clock evidence comes from a pulled log's `systemTime`,
and the `robot-facts` probe records uptime beside `date` for the shop; this mapping replaces
neither.

**Failure handling.** A refused connection reports the launch flags and retries after 1 second,
doubling to 30 seconds; an NT4 disconnection stands the provider down. One daemon I/O worker owns
JMX, and a separate five-second watchdog reports a stalled call. If RMI does not return, no
replacement call or thread is started until it does; capture and shutdown never wait for it.
Samples cannot cross sessions, including a reply queued before a reboot. A failed sample delivery
stands the provider down for the session, including across an NT4 resume, with its reason; the
next session admits polling again.

**In the tools and the metrics.** `list_sessions`, the manifest and the metrics show the state and
reason, the period, the round trip and the sample cost. `sample_bytes` counts the encoded value
payloads (eight bytes per numeric value plus the UTF-8 JSON), excluding RMI and WPILOG framing;
the provider's `bytes` counts recorded WPILOG bytes. `get_latest_values` marks these entries
`source: jmx`. `wpilog_provider_state{provider="jvm",state="..."}` is the state gauge, and the
numeric entries also appear as `nt_value`. Polling is a sampled view, not an exact pause trace.

**What still needs the shop.** The `robot-facts` module probe must establish that the deployed
JRE carries `jdk.management.agent`; a desktop test cannot establish the roboRIO image's module
set or the polling cost. Flight Recorder streaming is the second half of JVM context, after the
shop establishes the runtime's modules.

#### Sessions and capture files

**Where a capture lives.** A capture is an ordinary `.wpilog` file under
`robots/address-<address>/sessions/<UTC-date>/<HHmmss>Z/capture.wpilog`. A new robot clock starts
a new session; a continuing clock resumes the session after a connection loss. The directory
gains the event and match after the file is closed and its readers released, on every platform,
so its path stays stable during rollover and remapping. Once the robot's serial number is known,
the address directory moves under it at session close (see [Robot identity](#robot-identity)).

**The manifest.** While recording, the manifest marks the current file in `open_capture`; each
closed file gets its own SHA-256 and final size. Event and match facts appear in the store
listing as soon as the queued fact update runs. Imports never stall the NT4 writer: manifest
updates coalesce into one pending task, run on changed facts or at most every five seconds.
Creation, including a resumed file's removal from the hashed list, is asynchronous. While the
store is busy, capture retains arrival order and original timestamps within a 65,536-event and
16 MiB event-accounting budget, with a 30-second admission deadline. Overflow or timeout logs
an explicit recording failure and unrecorded pending-event count; any late session manifest keeps
that end reason. A new robot clock permits another attempt. Backlog replay yields after 256 events
or two milliseconds checked between events; individual filesystem operations can take longer.
A buffered disconnect closes the recording at its original time. Explicit server shutdown instead
abandons pending admission with a logged reason and pending-event count. Context providers begin delivery
after the backlog drains. Until a session exists, admission failures appear in the server log;
there is no synthetic session row in `list_sessions`.

**Rollover.** At `capture.max_file_bytes` the recording rolls to `capture-2.wpilog`,
`capture-3.wpilog`, and so on, in the same session. Each file redeclares the active entries and
has its own live index; a tool still reads one file per call. Retained struct schemas are copied
into each new file at its rollover server time, with `capture_schema_seed: true` in that entry's
metadata, so each file can decode its structs. Those seed records are not additional received
changes, and they do not extend the file's time range back to the boot-time schema. If the
declarations, schemas, one value and the finishes cannot fit the configured bound, recording
stops with an explained error.

**Fidelity.** Excluded or thinned topics are a deliberate reduction in capture fidelity; thinning
is recorded in the entry metadata. Every five minutes, one INFO topic-cost summary reports the
topic count, cumulative recorded records and bytes, dropped and thinned counts, and up to five
topics with the largest cumulative recorded-byte costs. Per-topic records, bytes, dropped and
thinned counts and last-minute rates remain in DEBUG logging and `list_sessions`.

**Write failures.** A write failure closes the session with `end_reason` in its manifest and the
reason in the server log, keeps the NT4 connection, and suppresses recording until a new robot
clock. A partial write is rolled back to its completed record boundary when the filesystem
permits it.

**Shutdown.** Shutdown waits at most 30 seconds for the writer and the final manifest; if it
cannot finish, the server log says that the next startup sweep will recover the unowned
captures.

**Recovery at startup.** The service queues recovery after HTTP is listening, so a large
abandoned capture cannot delay the health endpoint, and runs it before NT4 starts: the store
queue sweeps the sessions still marked `open_capture`, leaving files with an active writer
alone. An abandoned readable WPILOG is finalized with its hash, size, record time range, the
file modification time as `ended_at`, and `end_reason: "server stopped while recording"`; an
incomplete final record stays marked as truncated. An unreadable or structurally damaged file
stays open, with a recovery reason in the manifest and the server log. This covers a crash, a
power loss or a daemon ended before shutdown finished, and it never changes the capture's bytes.

**Reading the open capture.** Every log tool accepts the open capture's path. Each call sees a
fixed prefix, reported as `inputs.session_time_range`; a later call can include newer records.
The hot window controls memory retention, not which records are available: older values are
read from the file.

A local server reading a growing WPILOG that another process writes resumes its index on
verified append anchors and a known unchanged file identity, and retries a partial final record
on growth. Where the filesystem cannot supply an identity, it loads the file afresh. A call that
spans a file change still returns the explained retry; a successful disk-backed call's
`inputs.file_size_bytes` identifies the size it read. The pit writer's own live index remains
the faster, fixed-prefix path.

#### Robot identity

A robot is known by its roboRIO serial number. The listing reads `/SystemStats/SerialNumber` and
`/SystemStats/Comments` (also with the `NT:` prefix) within the first 2000 records of any log,
not only a store's; import inspection also finds identity logged later. AdvantageKit records
these conventions. Teams using DataLogManager can make every robot log self-identifying by
writing them once in `robotInit`:

```java
var log = DataLogManager.getLog();
new StringLogEntry(log, "/SystemStats/SerialNumber").append(RobotController.getSerialNumber());
new StringLogEntry(log, "/SystemStats/Comments").append(RobotController.getComments());
new IntegerLogEntry(log, "/SystemStats/TeamNumber").append(RobotController.getTeamNumber());
```

The entry classes are in `edu.wpi.first.util.datalog`; `DataLogManager` and `RobotController`
are in `edu.wpi.first.wpilibj`.

**How identity is applied.** A logged serial wins for its file. Device evidence learned over SSH
is stored by serial with its host-key history, and copied into `/Daemon/Robot/Identity` at
capture start and resume. Identity learned in the middle of a session is written in place; at
session close the address directory moves under the serial, preserving the old paths, without
waiting on the NT4 loop. New sessions use the known serial at once.

**Candidates.** The listing's `robot_candidates` apply only to store files and use the exact
fingerprints persisted by import inspection; older manifests without fingerprints have no hints.
The listing does not scan logs for this evidence, and a candidate never assigns an identity.

#### Pulling the robot's logs

Pulling is opt-in: set `capture.pull.enabled: true` after the
[shop test](DEVELOPMENT.md#roborio-sftp-shop-test).

**The gate.** The gate reads bit 0 of `/FMSInfo/FMSControlData`. An enabled, unknown or
disconnected state pauses the worker within its current 64 KiB block; when the gate reopens, the
transfer resumes at the held offset after a content check. The server log reports starts,
pauses, completion and failures, and `list_sessions` reports the matched imports.

The server log names why pulling is paused: `NT4 is disconnected`, `Robot is enabled or its enabled state is unknown`, or `Waiting for disabled-state settling`. A changed reason is logged once; repeated checks of the same state stay quiet. An I/O retry after NT4 disconnects includes that reason and the original error at INFO. Known local filesystem failures, I/O errors while NT4 remains connected, and unexpected non-I/O exceptions remain WARN. A remote-handle close after a successful checkpoint is identified separately; a disconnected close is INFO, while a local checkpoint failure remains WARN even if closing the remote handle also fails.

**Cost and pacing.** `capture.pull.rate_bytes` caps the transfer, and the cap also covers the
fallback comparison reads. One recursive listing serves each transfer pass, refreshed after
ten seconds during a long pass. Hash commands read the whole held prefix on the robot, using CPU
and storage bandwidth outside the transfer byte cap. Their deadline is 30 seconds plus one second
per 256 KiB, rounded up; SSH keepalives preserve the connection during a slow hash, and a connect
times out after five seconds. SSH errors are retried; they never stop HTTP or NT4.

Data blocks share one remote handle while a file is active. Local staging and progress are
checkpointed together at 1 MiB or a one-second deadline, and at file completion or pause.
An in-flight remote or filesystem operation can delay the checkpoint. The data is forced
before the manifest counts it. After a crash, a staged tail beyond the saved count is discarded
and only those uncounted bytes are recopied; the saved prefix is still checked against the robot.
The rate allowance is bounded to 64 KiB, including after an idle gap.

**Host keys.** The first SSH host key is trusted (first contact trusts the team's robot network)
and its SHA-256 fingerprint is recorded with the device serial. Supported host keys are Ed25519
and RSA SHA-2. A changed key is reported: with the default empty password the contact can
continue automatically, but with a password or private key configured it is refused before
authentication. Verify the replacement, then either set
`capture.pull.ssh.accept_changed_host_key: true` temporarily or remove the last contact entry's
`host_key_fingerprint` from that robot's `robot.json`; the next accepted contact records the new
fingerprint.

**The serial.** The serial read after the connection selects the manifest, and a new serial at
the same address starts separate transfer state. The serial and comments come from the HAL's
sources (see the [pit server plan](PIT_SERVER_PLAN.md#85-robot-identity), §8.5). Missing or
conflicting serial evidence refuses the contact rather than assigning logs by address.

**Where a pulled file goes.** A file waits in `robots/<serial>/pulled/` until the ordinary reader
reads it to the end. A verified file moves into a session's `robot/` directory and appears in the
listing. A file whose growth is confirmed returns to staging for a while; its earlier tool paths
still resolve once it is placed again. A reused name keeps the previous copy separately.

**Matching a file to a session.** A match needs the same known serial, strong data correlation
within 250 ms of zero, and exactly one candidate session. Manifest time ranges filter the
candidates before any is loaded, with two hours of clock slack (sixteen for filename clocks
without a zone); an unknown or unset clock cannot exclude a candidate. Names alone never match.
A file with no logged serial is marked `data_alone` in the manifest. A file that matches nothing
starts its own session. A disagreement between the logged and the device serial is reported,
and the logged serial wins for the file.

**USB aliases.** Each recursive listing resolves its configured roots with SFTP realpath and
walks each physical root once, keeping the first configured spelling. A saved manifest using
another alias is recovered by the same content proof as a renamed file: verified bytes and
partial checkpoints are kept. Missing roots are optional; permission failures are reported.

**Resume.** A resumed transfer checks the hash of exactly the held bytes. If exec is unavailable,
the last 64 KiB is compared instead, which cannot prove the earlier prefix. The robot's files are
never deleted or modified.

#### Robot facts for the shop

Before changing the candidate NI paths or provider defaults, collect the image's own evidence:

```bash
wpilog-mcp robot-facts 172.22.11.2 --out rio-facts.md
wpilog-mcp robot-facts robot.local --user lvuser --port 22 --key /path/to/key --out rio-facts.md
# Reuse a named server's address candidates, store pins and capture.pull.ssh block:
wpilog-mcp robot-facts --server pit --config /path/to/servers.yaml --out rio-facts.md
```

**Options.** The direct form defaults to `lvuser`, port `22` and an empty password. There is no
password flag: for password authentication, use `${NAME}` in the named server's
`capture.pull.ssh.password`. `--server` takes its SSH settings as a block and cannot be combined
with `--user`, `--port` or `--key`. Pulling need not be enabled, and the daemon need not be
running.

The command uses one SSH connection, the puller's host-key rules, and the same store queue and
lock for its pins. Direct-host pins live in `~/.wpilog-mcp/robot-facts/ssh-hosts.json`; a named
server uses its capture store's pins. A busy store lock is an explained refusal, not permission
to bypass it.

The named-server form writes the configured store's local host-key pins. Use the service's
account when collecting facts for a service-managed store. For the installed Ubuntu service,
for example:

```bash
sudo -u wpilog-mcp wpilog-mcp robot-facts --server pit --config /etc/wpilog-mcp/servers.yaml --out /tmp/rio-facts.md
```

Choose a new output path that the service account can write. If your account cannot access that
store, use the service account or give the robot's address directly; the direct form keeps pins
under your own home directory. On file systems supporting POSIX attributes, a named store must
have the same operating-system owner as the invoking account, even when that account is root
or group permissions allow writing. A missing store uses its nearest existing ancestor's file
system and owner for this check. An unavailable POSIX ownership check is a refusal. This prevents
foreign-owned `store.lock`, `store.json` or `ssh-hosts.json` files from blocking the service.

On Windows, the owner comparison is not used: actual filesystem permissions determine whether
the command can access the store and write its pins. A writable directory owned by the
Administrators group can therefore proceed. An ACL denial reports the permission failure and
advises running as the service's account or naming the robot's address directly, without a
`sudo` command. Do not delete the permanent lock or change ownership to bypass a refusal.

**The report.** The report is dated in UTC. Without `--out` it is written as
`robot-facts-yyyyMMddTHHmmssZ.md` in the current directory, and an existing report is never
overwritten. Each fixed read-only command records its exit status, its elapsed milliseconds, up
to 32 KiB each of stdout and stderr, and any timeout or truncation. An ordinary command has a
ten-second deadline; stats discovery and timing use the provider's 30-second bound.
The SSH connect has a five-second bound. A refused command stays in the
report and does not stop the other probes. A connection, configuration or output failure exits
nonzero; a sparse image collected successfully still exits zero.

**What it collects.**

- The deployed program runtime's `--list-modules`, run through `/proc/<pid>/exe`, with separate
  conclusions for `jdk.management.agent`, `jdk.jfr` and `jdk.management.jfr`. A missing program
  or runtime, or a refused command, is evidence, never an assumed module.
- `uname`, and the candidate NI metadata: `/etc/os-release`, `/etc/natinst/share/ni-rt.ini` and
  `/etc/natinst/share/ni-imaging-info.ini`.
- The six log, home and USB directory listings.
- `which` for journalctl, dmesg, df, tail and sha256sum. Separate help/version probes identify
  the actual selected tail, stat, head, dmesg, df, sed, tr, grep, sha256sum, cat, ls, date and
  getconf executables: a BusyBox banner identifies that applet and its version, a GNU or
  util-linux banner identifies the alternative, and unrecognized output stays unknown.
- A current-boot journal query and one with an explicit boot UUID.
- Three kernel lines and whether they carry `[seconds]` stamps.
- `df -Pk /`.
- The program selected by its JAR in `robotCommand`, its command line, and whether its
  environment is readable (never the environment's contents).
- Uptime beside the epoch time, and the authentication method that succeeded.
- Both `/home/lvuser/FRC_UserProgram.log` and the NI log-directory candidate; neither is assumed
  present.

The conclusions mark each fact **found**, **absent** or **refused**, and name the load, radio and
rotation exercises this snapshot cannot perform.

**Stats command timings.** After initial stats discovery, the report records three
`stats-steady-N` executions of the actual provider command, `stats-proc`, and one
`stats-df:<resolved directory>` for each unique path. Their elapsed milliseconds include SSH
round trips; component times cannot be added as though the network overhead occurred once.
Refused, truncated or unsupported discovery prevents guessed timings. These measurements do
not measure robot CPU consumption or replace the loaded shop exercise.

**The hash-cost probe.** The largest `.wpilog` or `.revlog` in the configured pull directories is
hashed with `head -c N -- <quoted path> | sha256sum`, with N at most `104857600` bytes (100 MiB),
under the puller's size-scaled deadline and keepalives. The report gives the requested size
beside the elapsed time. A file that grows or shrinks can change how many bytes were available,
so this is the cost of the bounded command, not a whole-file measurement. Nothing on the robot
is modified.

**Secrets.** Configured secrets, key paths and sensitive command-line option values are redacted
from the report and from errors; host-key fingerprints and the successful authentication method
remain. Keep collected robot evidence outside this repository.

#### NT4 gateway for dashboards

Enable `capture.gateway: {port: 5810}` and point a dashboard or AdvantageScope's NT4 connection
at the pit computer's address and that port, rather than at the robot.

**What it serves.** The gateway accepts NT4.1 and NT4.0, mirrors exact topic names, types,
properties and schema topics, and forwards the robot's timestamps. It serves everything the NT4
client receives; capture exclusion and thinning affect the file, not this stream. Subscriptions
choose prefixes, periods, all changes, or announcements only. Client writes are acknowledged but
ignored, with one warning per connection, so a dashboard control cannot change the robot. A slow
subscriber is disconnected, with an explained reason in the server log. Pending work is charged
per peer: one peer exceeding its allowance does not disconnect the others. The shared admission
bound is 32 MiB and 65,536 tasks; its global stall fallback requires five seconds without fan-out
progress. A short stall that fits the budget drops no connection. If the gateway refuses an
upstream publication, every view is disconnected as soon as the loop resumes, with an explained
reason, even below five seconds. A dashboard sees the disconnect rather than silently keeping a
missed change-only value. Recording continues without waiting for gateway cleanup.

The gateway follows `WPILOG_HTTP_BIND`, including its default `127.0.0.1`. Bind deliberately to
the team network for other machines to connect. This separate port has **no authentication** and
belongs on the **private network**; an HTTP proxy login does not protect it. HTTP's Origin and
loopback-control checks remain unchanged.

**A busy port.** A busy gateway port does not stop capture or pulling. The listener enables
address reuse and retries forever, after 1, 2, 4, 8, 16, then 30 seconds between attempts. The
server logs the changes between waiting and listening, not every attempt. A recovered listener
serves the topics already received while it was waiting.

**What you see.** `GET /health` and `list_sessions` publish the same `gateway` object: `state`
(`disabled`, `waiting`, `listening`, or `stopped`), `port`, `cause` while waiting, and `since` in
UTC. `wpilog_gateway_clients` counts the downstream clients; `wpilog_nt_connected` is the robot
connection.

**Reconnection and time sync.** A robot disconnect unannounces its topics; a reconnection
announces them with new gateway ids. Time-sync replies use the measured robot clock; while that
estimate is absent they use the pit server's local monotonic clock, as an ntcore server does.
The first valid estimate after each robot connection resets the downstream connections, so
clients synchronize again before reading the new session.

**What still needs the shop.** Dashboards and AdvantageScope against a real robot remain the
user's manual check.

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

**Selecting and filtering.** Append `?store=<id>` (or `&store=<id>`) to select a store when there
are several. Session filters are `since` (an ISO UTC instant; a session whose end is at or after
it is included), `robot` (id or serial), and `event` (exact event text). Filters omit unassigned
files.

**Growing files and ranges.** A growing capture is served at its current length, by the
prefix-hash endpoint too, and the session response refreshes `open_capture.size_bytes` from the
file. An out-of-bounds range or prefix returns 416.

**What is refused.** Control manifests are JSON responses, never arbitrary file downloads.
Strays, inbox files, traversal, and paths outside catalog membership are refused. A manifested
imported log keeps its original name, even a name such as `robot.json`, within its separate
payload directory. A payload named `prefix-hash` is downloaded normally; only the required
`bytes` query field selects a prefix-hash operation on the preceding file path.

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

**Who runs it.** A running daemon owns the job. Without one, the command runs under the same
`store.lock` as import; a daemon refusal or a failed request never starts a second writer.

**Transfers.** Transfers reuse the puller's content proofs, 64 KiB blocks and durable progress.
HTTP sync defaults to unlimited pacing; `--rate-bytes <bytes/sec>` sets a cap (`0` means
unlimited). An interrupted file resumes only after the peer proves the held prefix. Each
completed copy must match the advertised hash and load through the ordinary import inspection.
An already imported power-cut tail keeps its truncation note. An open capture waits for its
final hash: it is reported as a refusal for this sync, while the read-only door continues to
serve its current bytes.

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

**The daemon API.** `POST /store/sync` takes optional `url`, `store` (local directory) and
`rate_bytes` fields, for example `{"url":"http://other-laptop:2363","rate_bytes":0}`. An absent
URL means the remembered peers; an absent store requires one configured destination. The body
is limited to 64 KiB. A `202` response gives `job_id` and `url` (also in `Location`);
`GET /store/sync/<job>` returns `state` (`queued`, `running`, `done`, `failed`), `progress`,
`result` and `error`.

Both routes require a loopback connection and pass the Origin check, including on a server
bound to the network. A second sync for the same store is refused with 409; different stores
have separate queues. Active jobs keep the daemon alive. The most recent 100 jobs are retained
in memory, with completed jobs evicted first; they disappear at restart.

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

**Request rules.** Get the store id from `GET /store`; it may be omitted when exactly one
configured store exists. The optional `stated_robot` query field names a robot where the file
has no logged serial. Only configured writable stores accept uploads: neither a mirror nor a
leased directory becomes a network upload target. The filename is one portable path component.
A file above the 1 TiB (1,099,511,627,776-byte) file limit is refused before reception.

**Receipt.** The bytes are received through bounded buffers into a hidden, locked inbox
transfer, outside the store queue, so the inbox watcher cannot adopt half a file. The declared
size and hash must agree before the existing importer inspects or places the file. A disconnect
removes the temporary bytes, and crash leftovers follow inbox recovery.

**Result.** The `202` response and job polling are the same as for a JSON import. Provenance
records `upload:<filename>` and `moved: false`; the sender's private laptop path is never sent.
A duplicate is reported as present. The route adds only byte transport: the command, inbox,
content inspection, grouping and duplicate recognition are the explorer's existing import
pipeline.

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

Every server registers the three Live tools, `list_sessions`, `get_latest_values` and
`wait_for_change`. They answer for the present only on a server with `capture`; elsewhere they
return `not_applicable`, and `list_sessions` still reports `managed` and the gateway state.
The first lists sessions and recorded value costs (top ten topics, records and bytes, rates over
the last minute); the others query and wait on NT4 publications in memory. `inputs.session`
names the capture for ordinary log tools.

Before the first session in an empty capture store, `list_sessions` is also `not_applicable`,
with `sessions: []` and reason "No capture or imported session has been recorded". There is
no session `providers[]` yet. `/metrics` still publishes `wpilog_provider_state`: stats,
tails and JVM are `offline` without NT4; PhotonVision initially waits for a session and a
clock estimate. `wpilog_nt_connected` and `wpilog_capture_open` are both `0` then.

Counts update when the 250 ms asynchronous flush completes; a slow disk delays that snapshot,
not NT4 keepalives. Robot-clock timestamps and ages describe publication, not a measurement
requested by the caller. [TOOLS.md](../TOOLS.md#live-tools) defines the fields, missing-topic
results, and the 30-second maximum wait.

For the reverse proxy's Basic login, the extension's **Set Pit Proxy Credential** command
keeps the credential in SecretStorage, scoped to the pit URL's HTTP origin. It sends it to
`POST /pit-credential` on the local loopback server for its live MCP session, like the TBA
key. The body is `{url, authorization}`; null authorization removes that session's lease.
Session deletion or expiry also removes it. Nothing persists it in the store, YAML, a URL
or a process command. Use HTTPS when carrying a proxy password across the network.

The local mirror and the peer HTTP reader use the credential only for that origin and never
follow a redirect. Claude's URL bridge uses the local `/pit-mcp?url=<encoded-pit-MCP-URL>`
route, so its registration contains no password. Forwarding requires an active lease for
exactly that MCP endpoint. Forwarding and registration are refused on a listener bound off
loopback, and both keep the Origin check. Re-register Claude after setting or clearing the
credential, or after changing the local server's port.

With VS Code closed, no window leases the credential: the configured local mirror remains
available for offline analysis, but access to a password-protected origin needs the
credential's window to be open. This is a client of the team's proxy, not new authentication
in the pit server.

**Time scopes on a live capture.** For "right now", use `list_sessions`, then
`get_latest_values` (robot timestamps and ages), or `wait_for_change` for the next publication.
For "the last 10 seconds", pass `last_seconds: 10` to any tool with a time scope. That window
ends at the open capture's current estimated robot time, fixed when the call acquires its view,
or at the last record of a closed log. Before time sync it ends at the captured prefix's end; it
never invents an offset. Do not combine it with `start_time` or `end_time`; named scopes and
explicit windows still intersect.

**What the result shows.** `inputs.window` shows the absolute bounds (`inputs.windows` by path
for `compare_matches`). The listing's `open` flag identifies the file being written;
`inputs.session_time_range` describes the records the call could see, which can end before the
robot's current time.

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
| `wpilog_pull_bytes_total`, `wpilog_pull_files_total` | Per-`robot` process counters: received transfer payload bytes, including retransfers and bytes still awaiting a local checkpoint, and successful verifications, including growing-file updates. The byte count is not a durable-offset receipt. |
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
# From a checkout, or a copy of the doc/pit-server/metrics directory with its subdirectories:
docker compose -f doc/pit-server/metrics/compose.yaml up -d
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

## Running under systemd

Use the [service installation steps](../STANDALONE.md#running-as-a-service)
with the capture configuration instead of the example local-file configuration.

On a private robot network without NTP, check the pit computer's calendar clock before
recording. Session directory dates, manifest calendar times and the puller's overlap filter
use that clock. Robot-clock record timestamps and monotonic timers do not repair a wrong
calendar date. The unit orders after `time-sync.target` but does not require an Internet time
source before starting capture; use a maintained RTC or a local time source when offline.
