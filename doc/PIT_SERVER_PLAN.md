# The Pit Server: Live Capture, Gateway, and the Long-Term Record

A proposal for the next large piece of wpilog-mcp: a program that runs in the shop and in the pit, listens to the robot all the time, keeps everything it hears, and answers questions about it, live and later. The first half of this document is for anyone on the team; the second half specifies the work for the developers who will build it.

## Part I: The Idea

### What it is

Today wpilog-mcp reads log files. A robot writes a `.wpilog` file while it runs; someone copies the file off the robot; the server reads it and an AI assistant answers questions about it. That works, and it has one weakness that every team knows: the log has to be pulled. The file that would have explained the strange match is the one nobody copied before the robot was powered down for the night.

The pit server closes that gap. It is one program, running on a laptop or a small computer on the robot's network in the shop, and on the Driver Station laptop in the pit. While the robot is on, the pit server subscribes to the robot's NetworkTables, the live stream of values the robot publishes, and records every change to a log file of its own. When the robot reboots, a new session begins. Nobody has to remember anything: if the robot ran, the data exists.

It also fetches the robot's own log files. Whenever the robot is sitting disabled on the robot's network, the pit server copies any log it has not seen yet from the roboRIO, slowly enough not to get in anyone's way, and stops the moment the robot is enabled. The pulled log is the full record the robot wrote; the recording is what the pit server heard; the pit server keeps both and knows which is which.

Because the pit server is the same program as wpilog-mcp, every tool the assistant already has works on those recordings, including the one still being written. The process that writes the recording is the process that answers questions about it, so it never has to read its own file back: the index and the values are already in memory as they arrive, and a question about the last ten seconds is answered as fast as one about a file read an hour ago. "What is the battery voltage right now?" and "what happened in that run twenty minutes ago?" are answered from the same place. Logs pulled from the robot are still imported, and where they overlap a recording they take precedence, since the robot's own log sees more than the network does.

### Why

- **Logs stop getting lost.** The recording exists for every session, whether or not the file on the robot was ever copied, and the file on the robot is copied anyway, on its own, the next time the robot sits disabled.
- **The robot can die and the data survives.** A robot that loses power mid-run leaves a truncated file on its own storage. The pit server has everything the robot published up to the moment it went quiet.
- **Questions can be asked while the robot is running.** A student tuning a mechanism asks the assistant what the last ten seconds looked like, without stopping to pull a file.
- **The robot sees one listener.** Every laptop in the shop that wants live data today connects to the robot itself, and the robot sends each one its own copy of everything. The pit server subscribes once and serves everyone else, so the robot's network link carries one stream however many people are watching.
- **The data gets its context.** A vision camera's detections mean little without knowing how the camera was configured. The pit server can ask the camera's coprocessor directly and record its calibration and pipeline settings beside the data.
- **The robot's computer is watched too.** The roboRIO is a Linux computer, and the robot program is a Java virtual machine. The pit server can ask the first how busy its processor is and how full its disk, and the second when it paused to collect garbage, and record both beside the data, so a loop that ran long can be laid against the pause that made it.
- **The shop gets a wall display.** The pit server exposes its latest values for Prometheus to scrape, so a Grafana dashboard on the shop wall shows the battery, the processor, the disk, and whether a recording is running, without anyone asking.
- **Every file knows which robot it came from.** A team with a competition robot and a practice robot has two robots with the same team number, the same name on the network, and usually the same code, and a log file says nothing about which one wrote it. The pit server asks the roboRIO for its serial number, which is unique to the device, and records it with every session and every pulled file, so a season's record is a record per robot.
- **It is the foundation of a long-term record.** Once every session is captured and cataloged, questions across a season become possible: how a mechanism's current draw drifted over the year, whether a fix held.

### What it is not

- **Not for the field.** At competition the only team device on the robot's network is the Driver Station laptop, and the field caps the robot's bandwidth. The pit server runs in the shop, on the practice-field network, and in the pit over a tether. It does not run during a match.
- **Not a replacement for the robot's log.** The robot's own log records what the code saw at loop rate; NetworkTables carries the subset the code chose to publish. The recording stands in for a missing log and fills gaps; a pulled log remains the record of truth where one exists.
- **Not a way to control the robot.** The pit server reads. Dashboards that write to the robot, to choose an autonomous routine or tune a value, keep talking to the robot directly.
- **Not a monitoring system.** It exposes its numbers for Prometheus to read and runs neither Prometheus nor Grafana; a team that wants the wall display runs those beside it.

### How it works, in one picture

```
  robot (NetworkTables server)         roboRIO Linux, and the robot program's JVM
        │  one subscription,                  │  SSH: its logs and system stats
        │  every change                       │  JMX: garbage collection, profiling
        ▼                                     ▼
  pit server ─────────────────────────────────────────────────────┐
    │ records each session as a .wpilog file          (the record) │
    │ copies the robot's own logs while it is disabled (the puller)│
    │ re-publishes what it hears as a NetworkTables   (the gateway)│
    │   server of its own                                          │
    │ answers MCP tool calls over HTTP                (the server) │
    │ serves its latest values for scraping          (the metrics) │
    └──────────────────────────────────────────────────────────────┘
        ▲                        ▲                        ▲
        │ NetworkTables          │ MCP over HTTP          │ GET /metrics
  AdvantageScope, Elastic,  VS Code extension,       Prometheus, and
  any dashboard             Claude Code, Cursor,     Grafana reading it
                            any MCP client
```

The gateway is what lets a dashboard or a visualizer point at the pit server instead of the robot, so the robot has one client. The MCP server is what the assistants talk to; it offers every tool wpilog-mcp has today, pointed at the recordings, plus a few for live data.

### Where it stands

Much of this exists. wpilog-mcp already runs as a background HTTP server with a lock and a PID file; it already reads a file that changes on disk and shows a caller the latest contents, which is how a laptop reads a recording it did not write; it already reads event, match, and team from a log's own data; and it already aligns two recordings of the same session by their data, which is how REV logs are synchronized today. The new pieces are the NetworkTables client, the session writer, the log puller, the gateway, a handful of live tools, the context providers, and the metrics endpoint.

## Part II: Specification

What follows is for the developers. It follows the project's rules in `CLAUDE.md` and the design principles in [ARCHITECTURE.md](ARCHITECTURE.md): the log decides, the server does not guess, every result says what it used, and every fix comes with a test that fails without it.

### 1. Terms

- **Session**: one robot boot as the pit server saw it, from the first value received after connecting to the last before the connection dropped. The roboRIO's FPGA clock resets on boot, so a session is also one continuous timebase.
- **Capture**: the `.wpilog` file the pit server writes for a session.
- **Gateway**: the pit server's own NetworkTables server, re-publishing the robot's topics.
- **Robot identity**: the roboRIO's serial number, unique to the device and unchanged by reimaging. Not the team number, which a team's two robots share; not the address or the hostname, which are the team number again; not the SSH host key, which a reimage regenerates.
- **Context provider**: a module that asks something other than the robot's NetworkTables (a vision coprocessor, the roboRIO's operating system, the robot program's JVM) for what the stream does not carry, during a session.

### 2. Decisions already made

These are settled; the sections after them follow from them.

1. **Captures are `.wpilog` files**, written through WPILib's DataLog writer. Every existing tool, the listing, the differential reader, and the reload path work on them without new code. A capture is a log like any other.
2. **Every change is captured.** The subscription asks for all value changes (`all: true`), not the latest per period. The capture is meant to stand in for a log, and a sampled stream cannot. The cost is measured and reported per topic, and a topic can be excluded or thinned by configuration, never silently.
3. **The NT4 client and server are pure Java**, written from the protocol document ([`ntcore/doc/networktables4.adoc`](https://github.com/wpilibsuite/allwpilib/blob/main/ntcore/doc/networktables4.adoc) in allwpilib). The JDK's `java.net.http.WebSocket` serves the client; the server side needs a small WebSocket server (RFC 6455 handshake and framing) over the JDK's HTTP server the transport already uses. MessagePack is already a dependency. No native libraries enter the standalone install.
4. **The gateway is read-only.** It re-publishes the robot's topics and accepts subscriptions. A `publish` from a gateway client is answered as the protocol requires but never forwarded to the robot, and the server log says so once per client.
5. **The gateway mirrors the robot's clock.** Its answers to the time-sync handshake are the robot's time, shifted by the offset the pit server measured, so a value's timestamp means the same thing through the gateway as from the robot, and the same as in the capture and in a pulled log.
6. **Live data has one source: the pit server.** The VS Code extension's local server reads files on its own laptop; live sessions and the team's recordings are reached by registering the pit server as a second MCP server. No per-laptop subscription to the robot.
7. **The pit server trusts its network.** It runs on the team's private network, in the shop and the pit, and has no authentication of its own, as the HTTP transport has none today. Binding it beyond the local machine is the deliberate configuration it already is (`WPILOG_HTTP_BIND`). A team that wants TLS or a login puts a reverse proxy such as nginx in front of it; the documentation says how, and the server does not grow a security layer of its own.
8. **Context is written into the capture**, as JSON entries, not into sidecar files. One file holds the data and what gives it meaning, timestamped when it was taken.
9. **The writer builds the index.** The process writing a capture is the process reading it, and it knows each record's byte offset as it writes it and holds each value already decoded. So an open session is served from an in-memory log the writer extends with every record, never by reading the file back; the file is the durable copy. Reading a growing file written by another process stays possible through an incremental rescan, as a secondary path.
10. **The robot's logs are pulled only while it is disabled, throttled, and never deleted from the robot.** The pit server reads the robot's state from the stream it already receives; a transfer runs only while that state has been disabled for a few seconds, pauses the moment it is not, and is capped to a configured rate. Removing a log from the robot is a later, opt-in step with verification, never a side effect of pulling.
11. **A sampled series says so.** A provider that polls at a period, rather than receiving changes, marks its entries' metadata as sampled with the period, and timestamps each sample as the pit server's time of asking, mapped to the robot's clock. The data quality scoring then classifies the series as periodic, and no tool reads a two-second sample as a change-only hold, or a provider's timestamp as the robot's.
12. **The robot program is instrumented only by its owners.** Observing the JVM needs the robot program launched with JMX enabled, and that launch is the team's `build.gradle`, never something the pit server changes on the robot. A robot that does not answer is reported, not worked around.
13. **Prometheus and Grafana are views, not the record.** The pit server serves a metrics endpoint for Prometheus to scrape and runs neither. What a scrape sees is the latest value at that moment; the capture remains the record of every change.
14. **A robot is its serial number.** Every session and every pulled file is mapped to a robot by the roboRIO's serial number, read from the device or logged by the robot program, with the basis stated. Nothing else identifies a robot: not the team number, the address, the entry set, or the CAN inventory, which are evidence for a candidate at most. A file that carries no serial and came from no connection the pit server made has no robot, and the listing says so rather than guessing one.

### 3. Architecture

Code map additions, following the existing package layout:

| Package | What it holds |
|---|---|
| `nt4` | The NT4 wire protocol: message records, the MessagePack value codec, type codes, the time-sync arithmetic. Shared by client and server; no I/O |
| `nt4/client` | The client: connection, subscription, reconnection, the latest-value table |
| `nt4/server` | The gateway: the WebSocket server, per-client subscriptions, announcement and value fan-out |
| `capture` | Session detection, the session writer (DataLog) and the live log it extends as it writes, topic cost accounting, exclusion and thinning policy |
| `capture/context` | Context providers: PhotonVision; the roboRIO's operating system over SSH; the robot program's JVM over JMX and Flight Recorder |
| `capture/pull` | The log puller: the robot-state gate, the remote listing and transfer over SFTP, resume, throttling, the pull manifest; the one SSH connection per robot, which the system-stats provider shares |
| `mcp` | The metrics endpoint (`GET /metrics`), rendered from the latest-value table and the server's counters, beside the existing HTTP transport |
| `tools` | The live tools (existing package, a new module `LiveTools`) |

The pit server is `wpilog-mcp start <name>` for a server whose configuration enables capture. One process holds the client, the writer, the gateway, and the HTTP transport. The capture thread, the gateway's fan-out, and the MCP request pool are separate; they share the latest-value table and the session registry, both concurrent structures, and nothing else.

### 4. The NT4 client

**Connection.** WebSocket to `ws://<robot>:5810/nt/<client name>`, subprotocol `v4.1.networktables.first.wpi.edu`, falling back to `networktables.first.wpi.edu` if the server offers only that. The robot's address comes from configuration: a team number (resolved to `roboRIO-<team>-FRC.local`, then `10.<te>.<am>.2`), a USB tether (`172.22.11.2`), or an explicit host. The client tries the candidates in order and keeps the one that answers.

**Reconnection.** A dropped connection is retried with backoff from 1 s to 10 s, forever. The pit server is a daemon; a robot that is off for the night is the normal case, not an error.

**Subscription.** One `subscribe` with `topics: [""]`, `options: {prefix: true, all: true, periodic: <configured, default 0.01>}`. The server sends `announce` for every topic (name, id, type string, properties) and `unannounce` when one goes away; both are recorded in the session's entry table.

**Values.** Each binary frame is a MessagePack array `[topic id, server timestamp µs, type code, value]`. Type codes and WPILOG type strings:

| Code | NT4 type string | WPILOG type |
|---|---|---|
| 0 | `boolean` | `boolean` |
| 1 | `double` | `double` |
| 2 | `int` | `int64` |
| 3 | `float` | `float` |
| 4 | `string`, `json` | `string`, `json` |
| 5 | `raw`, `rpc`, `msgpack`, `protobuf:<name>`, `struct:<name>`, `structschema` | the same string |
| 16 | `boolean[]` | `boolean[]` |
| 17 | `double[]` | `double[]` |
| 18 | `int[]` | `int64[]` |
| 19 | `float[]` | `float[]` |
| 20 | `string[]` | `string[]` |

The type string in the `announce` is authoritative; the code in a value frame only selects the decoder. The struct schema topics (`/.schema/struct:<name>`) arrive as topics like any other and must be captured, or struct values in the capture cannot be decoded.

**Time sync.** On connect, and every 3 s after, the client sends `[-1, 0, 2, <client time µs>]`; the server answers `[-1, <server time µs>, 2, <client time µs>]`. The offset is `server time + round trip / 2 - client time now`. The client keeps the most recent offset with the smallest round trip in a sliding window, as ntcore does. Every value frame carries the server's timestamp, so the offset is not needed to timestamp data; it is needed for "now" in robot time (the live tools' `age`) and for the gateway's clock.

**Latest-value table.** A concurrent map from topic name to (value, server timestamp, received at). Updated on every frame; read by the live tools and the gateway's `topicsonly` answers.

### 5. Sessions and the capture writer

**Boundaries.** A session begins when the connection is established and the first `announce` arrives, and ends when the connection drops. A reconnection to a robot whose server timestamps continue from where they were (within a tolerance of a few seconds of the expected elapsed time) resumes the same session; one whose timestamps restarted near zero begins a new one. The robot rebooting is a new session; a Wi-Fi blip is not.

**Naming.** A capture is named as DataLogManager names its logs, so the listing reads what it can from the name: `FRC_<yyyyMMdd>_<HHmmss>_<EVENT>_<MATCH>.wpilog` once the Driver Station entries supply an event and a match, else `FRC_<yyyyMMdd>_<HHmmss>.wpilog`, with the time in UTC from the pit server's clock at session start and a `_cap` marker before the extension (`..._cap.wpilog`) so a capture is never mistaken for a log pulled from the robot. The file is written under a `captures/` directory inside the configured log directory, and renamed when the event and match become known, which the reload logic handles as a change.

**Entry names.** A topic is written under its own name with the `NT:` prefix DataLogManager uses for the topics it logs (`NT:/SmartDashboard/...`), so a capture of a robot running DataLogManager looks like that robot's own log to the signal resolver. Entry metadata is `{"source":"nt4","robot":"<address>"}`. Robots running AdvantageKit publish under `/AdvantageKit/...`, while their logs use `/RealOutputs/...` and similar; the resolver's conventions for captures of such robots are added only once verified against real captures, per the no-guessing rule, and until then those entries are candidates like any other.

**Records.** Each value frame becomes one data record with the server timestamp, written through the DataLog writer's `append` for its type. An `announce` for a new topic starts an entry; `unannounce` finishes it. The writer flushes every 250 ms and on session end, so a reader of the growing file sees whole records at most a quarter second behind.

**Cost accounting.** Per topic: records, bytes, and the rate over the last minute. Reported by `list_sessions` and in the server log every five minutes while a session is open. Configuration may `exclude` topics by prefix or `thin` them to a period; a thinned topic's entry metadata records the period, so a result on it can say so.

**Context entries.** Providers write JSON entries under `/Daemon/<provider>/...` (see §8), with the server timestamp at which the snapshot was taken.

### 6. The live log: indexed as it is written

The writer and the reader are one process, so an open session is a `LogData` the writer extends, not a file the server re-reads. The `LiveLog` holds what a `LazyParsedLog` holds after its scan, built incrementally:

- **Entries** in announcement order, with the WPILOG entry id the writer assigned, the type string, and the metadata; an `unannounce` marks the entry finished but keeps it.
- **Offsets**: the byte position of every record, known at the moment the writer serializes it, appended per entry.
- **Time range**: the earliest and latest server timestamp accepted, updated per record.
- **Values**: the decoded value of each record is the value the client received, so no record is ever decoded from the file. Each entry keeps its values in an append-only array with a volatile length; a hot window (configurable, default the last 10 minutes) stays in memory, and older values are read from the file through their offsets, as a lazily parsed log reads any value, with the file mapped read-only beside the write channel and remapped as it grows.

**Readers see a consistent prefix.** A tool call takes each entry's length at the moment it first touches the entry, and the `values()` list it gets is a view bounded by that length, so a statistic is computed over a fixed set of records while the writer keeps appending. The single writer publishes a record by writing it, then advancing the length; readers never lock. The result's `inputs` carries the session's time range as the call saw it, so two calls a second apart can say why they differ.

**The log manager serves it directly.** The capture's path maps to the `LiveLog` while the session is open: `getOrLoad` returns the instance without a file check, since the file changes constantly by design, and the after-call check that discards a result read across a change does not apply, since the call read a fixed prefix. When the session ends, the instance stays in the cache as a finished log until it is evicted; a later load of the path reads the file like any other log, and the two must agree, which a test checks by comparing the live instance's answers with a fresh load of the finished file.

**Incremental rescan, the secondary path.** A process that is not the writer, such as a laptop's local server pointed at a capture on a shared folder, sees a file that grows. For it, `LogScan` gains `resume(LogScan previous, DataLogReader reader, Path path)`: it starts at the byte offset where the previous scan stopped, keeps the previous entries and offsets, and appends; a file that ends inside a record (the writer mid-flush) stops the scan there, records that position as the resume point, and does not count it as damage. The log manager reloads a file that grew with the same identity and an unchanged prefix by resuming, and anything else afresh. This is milestone 8, useful on its own and not needed by the pit server itself.

### 7. The gateway

An NT4 server on the pit server's own port 5810 (configurable), serving every topic the client has announced.

- **Handshake**: accepts the two subprotocols; the client name from the path is logged.
- **Announce**: on subscribe, every topic matching the subscription's prefixes is announced with the robot's type string and properties, and the gateway's own ids.
- **Values**: per client, per subscription, honoring `periodic`, `all`, `topicsonly`, and `prefix` as the robot would: with `all`, every change since the last send; without it, the latest value per topic per period. Sends are coalesced per period per client on the fan-out thread.
- **Time sync**: answered with the robot's clock (the client's offset applied to the local monotonic clock), so a gateway client's "server time" is robot time.
- **Publish**: a client's `publish` and `setproperties` are acknowledged as the protocol requires and otherwise ignored, with one warning per client in the server log. Nothing a gateway client sends reaches the robot.
- **Robot absent**: topics are unannounced to clients when the session ends, and announced again on the next.

The gateway doubles as the test fixture for the client: the test suite runs a gateway fed from a fixture log and connects the client to it, so the whole path is exercised without a robot, on every platform CI runs.

### 8. Context providers

A context provider asks something other than the robot's NetworkTables for what the stream does not carry, and writes it into the capture as entries under `/Daemon/<provider>/...`, timestamped in the robot's clock. Three are planned, and they share two rules. A provider that cannot reach its device, or finds a device of a version it was not written against, logs why and stands down for the session: it never guesses at a shape, and never retries in a way the device would feel. And a provider that samples at a period says so in its entries' metadata (`"sampled":true`, `"period_sec":2`), so the data quality scoring classifies the series as periodic and a tool never reads a two-second sample as a change-only hold (decision 11).

#### 8.1 PhotonVision

PhotonVision publishes its results to the robot's NetworkTables under `/photonvision/<camera>/`, so detections, latency, and the raw result packet are already in the capture. What is not on the wire is the configuration that gives them meaning, which lives only on the coprocessor:

- camera calibration per resolution, with its reprojection error;
- the active pipeline: type, resolution, exposure and gain, 3D mode, multi-tag, the AprilTag field layout in use;
- the software version, device type, and hardware status.

The provider connects to the coprocessor's web UI backend on port 5800, requests the settings export at session start, subscribes to the UI's WebSocket for settings changes, and writes each snapshot as a JSON entry `/Daemon/PhotonVision/<camera>/Settings` with the server timestamp of the snapshot. The UI's routes are not a published API and move between PhotonVision releases, so the provider is written against a named version, says which in its entry metadata, and a test pins the shape it expects; a mismatch is logged and the provider stands down for the session rather than guessing.

Coprocessor addresses come from configuration (`context.photonvision: [host, ...]`), or from the `/photonvision/<camera>/` topics' presence, which prompts a log line suggesting the configuration. Limelight follows the same shape later, from its HTTP API on port 5807.

The vision tools then read the settings entry of the session they analyze and report calibration error and pipeline mode as stated context, under their own field (`camera_settings`), never as inference.

#### 8.2 The roboRIO's operating system

What the robot publishes is in the capture already: a robot running AdvantageKit publishes its CPU temperature, CAN utilization, and rail voltages under `/SystemStats/`, and a tool reads those as any topic. What nothing publishes is the state of the roboRIO as a Linux computer: how busy its processor is, how much memory is free, how full the storage the logs go to is, how much the network interface carries, how long it has been up, and whether the robot program is the same process it was a minute ago. Each explains something a log alone cannot: loop overruns that line up with processor saturation, a log that stopped because the disk filled, a program that restarted because the kernel killed it for memory.

The provider reads them over the SSH connection the puller holds (one connection per robot, with the puller's SFTP channel and the provider's shell channel on it), with one shell command per sample that prints `/proc/loadavg`, `/proc/stat`, `/proc/meminfo`, `/proc/uptime`, `/proc/net/dev`, the free space of the log file systems, and the robot program's `/proc/<pid>/stat` (the process found by its JAR name): a few kilobytes of text, parsed on the pit server. Unlike the puller, it runs whether the robot is enabled or not, since the enabled robot is the interesting one, and the cost of that is the one point to be careful about. The roboRIO 1's processor is small, and a sample is a shell command and a few reads, cheap but not free. So the period is configured (default 2 s); the provider measures the round trip of each sample and the processor time the robot spent between samples, reports both in the session's cost accounting beside the topics, and doubles its period, up to 30 s, while a sample's round trip exceeds a budget (default 100 ms). The numbers for a roboRIO 1 and a roboRIO 2 are measured before the provider is on by default.

Entries, each with its unit in its name:

| Entry under `/Daemon/roboRIO/` | From |
|---|---|
| `cpu_busy_fraction` | `/proc/stat`: the busy share of the interval since the previous sample (none for the first) |
| `load_1min`, `load_5min`, `load_15min`, `runnable_tasks` | `/proc/loadavg` |
| `mem_available_bytes`, `mem_free_bytes` | `/proc/meminfo` |
| `disk/<mount>/free_bytes`, for `/home/lvuser`, `/u`, and `/U` where present | `df` |
| `net/<interface>/rx_bytes_per_sec`, `tx_bytes_per_sec` | `/proc/net/dev`, as rates over the interval |
| `uptime_sec` | `/proc/uptime` |
| `program/cpu_fraction`, `program/rss_bytes`, `program/threads`, `program/pid` | the robot program's `/proc/<pid>/stat`; a change of `pid` is a program restart |

Each sample is timestamped in the robot's clock as the pit server's time of sending the command, mapped through the NT4 time offset; a sample is not an event the robot timestamped, and the metadata says so: `{"source":"ssh","host":"<address>","sampled":true,"period_sec":<period>}`.

#### 8.3 The robot program's JVM

The robot program is a JVM, and the JVM's own state explains a class of mystery no robot log records: a loop overrun that is a garbage collection pause, a slow first cycle that is class loading, a heap climbing across a match. The JVM reports all of it through two mechanisms the JDK carries, which the pit server consumes without a dependency:

- **JMX** (`java.lang:type=Memory`, `GarbageCollector`, `Threading`, `ClassLoading`, `OperatingSystem`), polled at a period (default 1 s): heap used and committed, collections and their total time per collector, thread count, loaded classes, the process's CPU time. Sampled, like the system stats, and marked so.
- **Flight Recorder (JFR)**, streamed over the same JMX connection with `jdk.management.jfr`'s `RemoteRecordingStream` (JDK 16 and later): the JVM's own events, timestamped by the JVM, which make the record exact where polling only suggests. The default set is small and chosen for what a robot team can act on: `jdk.GarbageCollection` (each pause with its duration), `jdk.GCPhasePause`, `jdk.SafepointBegin`, `jdk.ThreadStart` and `jdk.ThreadEnd`, `jdk.ExecutionSample` at a low rate (every 20 ms) for a profile of where the program spends its time, `jdk.ObjectAllocationSample`, and `jdk.SocketRead` and `jdk.FileWrite` above a duration threshold. Each becomes entries under `/Daemon/JVM/<event>/...` with the event's fields; an execution sample becomes a string entry of the top frame and a JSON entry of the stack, from which a profiling tool later builds the usual summary (time by method, by thread). JFR is designed to cost a percent or two; the pit server measures it on a real roboRIO before the default set is settled, and the set is configured.

Two things are not the pit server's to do. **It does not instrument the robot program** (decision 12). JMX remote is off in a JVM unless its launch asks for it, and the launch is the team's: the robot project's `build.gradle` adds the arguments to the deployed program (`jvmArgs` on the deploy artifact in GradleRIO), and the standalone guide gives them (`-Dcom.sun.management.jmxremote.port=<port>`, the RMI port set to the same so one port serves, `java.rmi.server.hostname` set to the robot's address on the team's network, authentication and SSL off, which is the trust model of decision 7 and belongs on the private network only). The provider is on only when a robot's configuration names the port, and a robot that does not answer there is reported in `list_sessions`, not worked around. **It does not map clocks by guessing.** JFR events and JMX samples are on the JVM's wall clock, and the capture is on the FPGA clock. The offset between the two is measured on the same connection: the `Runtime` MBean's start time plus its uptime is the robot's wall clock now, within a round trip, and the NT4 time sync gives the FPGA clock now, within another; their difference maps one to the other. It is remeasured each period, because the Driver Station sets the robot's wall clock when it connects and the offset jumps by years at that moment; a jump is recorded as a note entry, and events before it keep the mapping that held when they were stamped. Where a pulled log of the same session carries the `systemTime` entry DataLogManager writes, which records the wall clock against the FPGA clock from inside the robot program, the import matching prefers it, and the metadata of every JVM entry says which mapping it got (`"clock":"measured"` or `"clock":"systemTime"`).

A JFR recording to a file on the robot (`-XX:StartFlightRecording` with a file under the log directory) is the counterpart of the robot's own log: it survives the pit server's absence, costs the network nothing during the run, and is pulled by the puller like any other log once `.jfr` is in its patterns. Reading it is `jdk.jfr.consumer.RecordingFile`, in the JDK, and importing it makes the same entries the stream would have, with the file's own timestamps and the `systemTime` mapping. That import is a later milestone, after the stream has shown which events matter.

#### 8.4 Robot identity

Nothing in a DataLogManager log identifies the roboRIO that wrote it, and nothing the pit server sees on the network does either: the address and the hostname are the team number, which the team's competition robot and practice robot share, and the SSH host key changes whenever the roboRIO is reimaged. The one identifier that is unique to the device and survives reimaging is the roboRIO's serial number, which the HAL exposes to the robot program as `RobotController.getSerialNumber()` and which the device itself can be asked for (decision 14).

The provider reads it over the SSH connection on first contact with a robot, from the same source the HAL reads, together with the roboRIO's comments field, which teams fill with a name such as "practice bot" in the roboRIO's web dashboard. It writes both as JSON into the capture at session start, under `/Daemon/Robot/Identity` (`serial_number`, `comments`, `address`, `host_key_fingerprint`, `basis: "device"`), and the session manifest and the pull manifest carry the serial, so a capture and every file pulled over that connection are mapped to the robot before any data is read. A robot program that logs the serial itself is read the same way: AdvantageKit writes it on every boot as `/SystemStats/SerialNumber`, beside `/SystemStats/Comments`, and a DataLogManager robot does so with one line in `robotInit`, which the guide recommends as the way to make every log of a robot self-identifying for the rest of its life, wherever the file travels: log the serial number, the team number, and the comments once at startup. Where both a device reading and a logged value exist they must agree; a disagreement is reported in `list_sessions`, since it means the connection and the file are not the same robot, and the file's own value wins for the file.

The listing then shows a `robot` for every log that has one: `serial_number`, `comments` when known, and `basis` (`logged` from the file's own entry, `device` from the manifest of a connection the pit server made). A log with neither has no `robot` field; the listing adds a `robot_candidates` note only when a fingerprint (the team number from a logged entry, the entry set, the CAN device inventory from a REV log) matches one known robot and no other, naming the evidence and never promoting it. The same reading applies in the local server, not only the pit server: a logged serial is read by the listing wherever the file is.

Identity gates matching (§10): a pulled log is matched only to a session of the same serial, so a practice robot's log can never be matched to the competition robot's session however well their signals happen to line up; where either side has no serial, the match is made on data alone and the manifest says so. Identity also keys the puller's state, so a reimaged roboRIO with a new host key is the same robot with the same manifest, after the host key change is reported.

### 9. Live tools

Three tools, in a `LiveTools` module registered only when capture is enabled, and listed in the catalog under a new `Live` category. They read the latest-value table, the live log, and the session registry; they open no file. Every other tool reaches a live session through its path, as any log, and gets the live log's in-memory index and values (§6).

**`list_sessions`**: `sessions[]`, newest first, each with `path` (the capture), `started_at`, `ended_at` (null while open), `robot` (`serial_number`, `comments`, `address`, `basis`, per §8.4), `connected`, `topic_count`, `records`, `bytes`, `bytes_per_sec` (last minute, open sessions), `event`, `match` (when known), `cost[]` (the ten most expensive topics with `records` and `bytes_per_sec`), `thinned[]` and `excluded[]` from configuration, and `imports[]`: pulled logs matched to this session with the method (`by_time_overlap`, `by_correlation`) and the offset. `limits.sessions` when cut. `not_applicable` with a reason when capture is not enabled.

**`get_latest_values`**: `entries[]` (required), returns per entry `name`, `value`, `timestamp_sec` (robot clock), `age_ms` (robot now minus timestamp), `type`; unknown names are listed in `missing` and the status is `partial` when any is missing, `no_match` when all are. `not_applicable` when no session is open, with `last_session` and when it ended.

**`wait_for_change`**: `entry` (required), `timeout_ms` (default 5000, capped at 30000), returns the first value after the call began with its `timestamp_sec`, or `status: ok` with `changed: false` on timeout. One outstanding wait per entry per session; a second returns `error`.

Every result carries `inputs.session` (the capture path). Descriptions say what the values are and are not: the latest published, not measured, values; a stale `age_ms` means the topic stopped publishing, not that the robot stopped. The claim checks apply as to every tool.

### 10. Imported logs: pulling from the robot, and matching to sessions

**Pulling.** The robot writes its own logs to its storage: DataLogManager to `/home/lvuser/logs`, or to `/u/logs` when a USB drive is present; AdvantageKit to the USB drive's `/U/logs`; REVLib's status logger wherever it is configured. The puller copies those directories to the pit server's log directory, under `pulled/<robot>/`, keeping the robot's file names, which encode the time, event, and match that the listing reads. It behaves as `rsync` would, without the tool:

- **Transport**: SFTP over SSH to the roboRIO, as the `lvuser` account (no password by default; a key or a password may be configured). The host key is pinned on first contact per robot and a change is reported, since a reimaged roboRIO has a new one; the robot's identity is its serial number (§8.4), which the reimage does not change, so a reported key change under the same serial continues the same pull manifest. The SSH client is a library dependency, the smallest maintained one that serves; the choice is an open question (§16).
- **The gate**: a transfer step runs only while the robot has been disabled for at least a configured settle time (default 5 s), read from the control word the robot publishes (`/FMSInfo/FMSControlData`, the enabled bit), and while the pit server's NT4 connection is up, so the state is current. The moment the state is anything else, the step in progress finishes its current block and the transfer pauses; it resumes from where it stopped when the gate reopens. With no NT4 connection the puller does nothing: it will not guess that a robot it cannot hear is idle.
- **What to copy**: the remote listing (name, size, modification time) against a manifest of what has been pulled (`pulled/<robot>/.pull-manifest.json`: remote name, size, modification time, bytes copied, verified). A file not in the manifest is new; one whose size grew is fetched from the bytes already copied, since the logs are append-only, but only once the robot has confirmed that those bytes are the ones the puller holds (below); one whose size shrank, whose modification time went backward, or whose content does not match is a different file under the same name, and the local copy is kept under a disambiguated name while the new file is fetched from the start. The file the robot is writing now is copied like any other and grows across passes; the reload path handles the local copy's growth. When DataLogManager renames an open log once the Driver Station supplies the time and match, the old name disappears and a new name appears with the same content; the puller recognizes it by the same content check over the whole partial copy and renames the local copy rather than copying again.
- **Identity is content, never a name.** A name on the robot does not identify a file. REVLib names its log by the roboRIO's clock, which reads the same default date on every boot until the Driver Station sets it, so the same `REV_<date>_<time>.revlog` name recurs boot after boot, with the same modification time; and two boots of the same code declare the same entries in the same order, so the first tens of kilobytes of two different logs can be byte-identical. A resume that trusted the name would append one boot's log to another's and produce a file that loads with timestamps running backward in the middle. So before resuming or renaming, the puller asks the robot for the hash of exactly the bytes it already holds (`head -c <N> <file> | sha256sum` over the SSH session, which costs the robot a few seconds of reading and the network nothing) and compares it with the local copy; only a match resumes. Where command execution is unavailable, the fallback fetches the last 64 KB of the known range and compares that, where the microsecond timestamps of two boots have long since diverged.
- **Throttle**: a configured rate cap (default 1 MB/s), enforced on the reading side by pacing block reads, and one transfer at a time. The roboRIO's processor is small and SSH encryption costs it; the cap protects the robot as much as the network.
- **Verification**: after a file's size has been stable on the robot for one pass, the local copy is loaded through the normal path; a copy that loads, with its scan ending at the file's end, is marked verified in the manifest. A copy that does not is fetched again from the start, once.
- **Deletion**: never, in this version. Freeing the robot's storage is a later, opt-in action that requires a verified copy and says what it removed.
- **Reporting**: `list_sessions` gains `pulls[]` per robot: the last pass, files copied, bytes, and files waiting for the gate; the server log says when a pull starts, pauses, resumes, and completes.

**Matching.** A pulled log of the same boot is matched to a session of the same robot (§8.4; a match across robots is never made, and a match where either side has no serial is marked as made on data alone) by the machinery REV synchronization uses: names nominate (a Driver Station entry both carry, a battery voltage, a loop count), the data decides (cross-correlation), and the offset must be near zero since both are on the FPGA clock. A match is recorded in a manifest beside the capture (`<capture>.session.json`: the session's facts and its imports) that `list_sessions` reads and the listing shows as `session`. The pulled log is the authoritative record of its session; the capture stands in where no pulled log exists and fills the gap where the pulled log is truncated. No database: manifests are files, in keeping with the project's "nothing to operate" goal; a catalog over many seasons is a later decision, made when the files are many.

### 11. Metrics: Prometheus and Grafana

Some of what the pit server knows is wanted on a wall, not in a conversation: is the robot on, what is the battery at, is the roboRIO's disk filling, is a capture running. Prometheus is the ordinary way to put numbers on a wall and Grafana the ordinary way to draw them, and both consume a format that costs nothing to serve. So the pit server exposes `GET /metrics` in the Prometheus text exposition format, rendered on each scrape from the latest-value table and the server's own counters, with no dependency and no state of its own.

What it carries:

- **Every numeric topic**, as one metric with the topic as a label, `nt_value{topic="/SmartDashboard/Battery Voltage"}`, so a topic keeps its exact name and a dashboard query is a label match. A boolean is 0 or 1; an array is one sample per element with an `index` label, up to a configured length (default 16, enough for the swerve module arrays and not for a vision frame); a struct is expanded by its schema into one sample per numeric field with a `field` label, through the field paths the tools use; strings are not carried, since Prometheus has no strings. Beside each, `nt_age_seconds{topic}`: how long since the topic last changed, in the robot's clock, so a dashboard shows staleness instead of a frozen number. A topic filter in configuration (`metrics.include` prefixes) narrows this where a robot publishes thousands of topics; the default is everything numeric, which Prometheus handles comfortably at a scrape per second for a few thousand series.
- **The providers' entries** (§8), which are topics in the latest-value table like any other, so the roboRIO's processor, its disk, and the JVM's heap arrive with no further work.
- **The pit server itself**: whether the NT4 connection is up and to which address, the session's topic count, records, and bytes written, the measured time offset and its round trip, the gateway's client count, the puller's bytes and files per robot and the files waiting at the gate, each provider's sample cost, and the pit server JVM's own memory and collections from the platform MBeans, under `wpilog_...` names in Prometheus's conventions (base units, the unit in the name: `wpilog_capture_bytes_total`, `wpilog_nt_time_offset_seconds`). `GET /health` stays the one-line answer; `/metrics` is the detailed one.

What it does not do. **It is a view, not the record** (decision 13). A scrape sees the latest value at the moment of the scrape, and a value that changed twice between two scrapes is seen once; Prometheus at its default 15 s, or at 1 s, is a sampled view of a 50 Hz signal. The capture remains the record of every change, and the documentation says so where a team is tempted to analyze a dashboard. Samples carry no timestamp in the exposition, since the robot's clock is not the wall clock and Prometheus drops a stale one; staleness is `nt_age_seconds`. **It runs neither Prometheus nor Grafana**, in keeping with the "nothing to operate" goal: a team that wants the wall display runs the two beside the pit server, and the standalone guide gives a compose file with Prometheus scraping the pit server and Grafana provisioned with a starter dashboard (battery, CAN, loop time, processor, disk, heap, session state), which is the whole setup. **It has no authentication**, as decision 7 says: the endpoint is on the same port as the MCP transport, and behind the same proxy if a team adds one.

A later step, when a dashboard wants the record and not its samples: an HTTP query endpoint over a capture's values, which Grafana's JSON data source plugins read, returning an entry's values over a range, reduced to a requested number of points by the minimum, maximum, and mean per bucket, through the code `read_entry` uses. That is a thin face over the reading code, worth adding when someone needs full-resolution history in Grafana rather than in the data browser (IDEAS 9.2) or a tool. Prometheus first.

### 12. Network exposure

The pit server binds to an address on the team's network (`WPILOG_HTTP_BIND`, as today) and serves the MCP endpoint, the gateway, and `GET /health` without authentication. The `Origin` check stays: it protects a browser on the network from being used against the server by a web page, and costs non-browser clients nothing.

The standalone guide gains a short section for teams that want more: an nginx configuration that terminates TLS and asks for a password in front of the MCP endpoint, with the pit server itself bound to loopback behind it. The gateway's NT4 port is a separate matter; a dashboard cannot present a password to it, and the protocol has no place for one, so it is exposed on the private network or not at all.

### 13. The VS Code extension

- A setting `wpilog-mcp.pitServerUrl` (order after the TBA key). With it set, the extension registers the pit server with VS Code's MCP registry as an HTTP server, beside the local one, and adds an HTTP entry for it to a robot project's `.mcp.json` for Claude Code, under the same rules as the local entry. The entry holds a URL and nothing secret, so a team may commit it: every teammate's Claude Code then finds the pit server from the project.
- Both servers' instructions and `get_server_guide` say which server is which: the local one for files on this laptop, the pit server for the team's sessions and for anything live.

### 14. Milestones

Each leaves the project working and tested on its own.

1. **NT4 protocol and client** (§4), with the gateway's core as its test fixture.
2. **Session writer and live log** (§5, §6): captures appear in the log directory, and every existing tool works on an open session from memory and on a finished one from its file. Stress test on a real robot in the shop.
3. **Log puller** (§10) and **robot identity** (§8.4): the robot's logs arrive on their own, mapped to the robot by its serial; tested against a real roboRIO in the shop before it is on by default. The listing's reading of a logged serial comes first, since it needs no pit server.
4. **Live tools** (§9), and the extension's pit server setting (§13).
5. **Metrics endpoint** (§11): the pit server's own counters and every numeric topic; the compose file and the starter dashboard in the standalone guide.
6. **Gateway** (§7) complete: dashboards and AdvantageScope pointed at the pit server.
7. **PhotonVision provider** (§8.1) and the vision tools' `camera_settings`.
8. **roboRIO system stats** (§8.2), on the puller's SSH connection; its cost measured on a roboRIO 1 and a roboRIO 2 before it is on by default.
9. **JVM provider** (§8.3): JMX polling first, then the Flight Recorder stream, each gated on what the roboRIO's JRE turns out to carry (§16).
10. **Session manifests and import matching** (§10).
11. **Incremental rescan** (§6, secondary path): a growing capture read by another process.
12. **JFR file import** (§8.3) and the Grafana query endpoint (§11), each when a need shows.

### 15. Testing

- **Protocol**: the client and the gateway against each other in-process, over a loopback WebSocket, on every fixture log replayed as a robot would publish it. Message encoding is checked against hand-encoded frames taken from the protocol document.
- **Capture fidelity**: a replayed fixture captured through the client and the writer must pass the differential reader against the fixture it came from: same entries, same values, same timestamps.
- **Sessions**: reconnection with continuing timestamps resumes; with restarted timestamps begins a new session; the capture is renamed when event and match arrive.
- **Live log**: a session replayed through the writer answers every tool the same as a fresh load of the finished file (entries, sample counts, statistics, time range); a reader that starts mid-session sees a consistent prefix while the writer appends from another thread, checked under the stress test's concurrent calls; values past the hot window read from the file equal the values that were in memory.
- **Growing files** (§6, secondary path): the resumed scan equals a fresh scan.
- **Gateway**: a client with `all` receives every change; one without receives the latest per period; a `publish` from a client changes nothing upstream; the time-sync answer is robot time within the measured offset's error.
- **Puller**: the gate, the listing comparison, resume offsets, the content check, the rename rule, the throttle's pacing, and the manifest are pure logic tested against a fake remote in memory: a file that grew with matching content is fetched from its old size; a file that shrank is a new file; a larger file under a seen name whose content does not match is fetched from the start and never concatenated with the old copy, which is kept, both for a REV log named by an unset clock and for two logs of the same code that share a prefix; the DataLogManager rename is recognized by content; a transfer in progress pauses within one block of the state leaving disabled and resumes at the same offset; the manifest round-trips. The SFTP client itself is covered by an opt-in test against a real roboRIO, named by a property, like the real-log suites.
- **Live tools**: the claim checks, the conformance sweep (with capture enabled on a replayed fixture), and determinism.
- **Robot identity**: a fixture that logs a serial is listed with `robot.basis: logged`, and one that logs none has no `robot` field; a capture from a fake robot whose device reading and logged value differ is reported, with the file's value winning for the file; two sessions of two serials with byte-identical data are never matched to each other's pulled logs, and a pulled log without a serial is matched on data with the manifest saying so; a host key change under the same serial continues the pull manifest, and a new serial under the same address starts a new one; `robot_candidates` names its evidence and appears only when exactly one known robot matches.
- **System stats**: the parser against `/proc` text and `df` output captured from a real roboRIO and kept as text fixtures (not a robot log, so they may be committed); the busy fraction and the rates against values worked out by hand from two samples; the back-off from a planted slow round trip; a missing mount or a restarted program (a new pid) handled without a gap in the other entries.
- **JVM provider**: against the test JVM itself, with a JMX connector server started in-process on loopback and a Flight Recorder stream from it, so collections the test provokes arrive as entries with the fields the provider promises; the clock mapping against a planted offset, and a planted jump of years in the wall clock recorded as a note with the earlier events unchanged; a JVM without `jdk.jfr` simulated, with the provider standing down to JMX polling and saying so.
- **Metrics**: the exposition text checked line by line against the format (a `# TYPE` line per metric; label values escaped for quotes, backslashes, and newlines, which topic names can hold); every numeric topic of a replayed fixture present with its latest value, and no string topic; an array beyond the configured length excluded; the server counters against what the session registry says; and a scrape during capture under the stress test's concurrent calls.
- **Windows**: the capture file is open for writing while the server reads it; the tests cover that on Windows, where a mapped file cannot be replaced but can be appended to and read.

### 16. Open questions

- The AdvantageKit topic layout versus its log layout (§5): verify on a real capture before adding resolver conventions.
- Whether thinning should ever be on by default for known high-rate topics, or stay a configured choice. The proposal is configured only.
- Whether a capture's `_cap` marker should instead be a directory convention. The marker keeps the listing's name parsing unchanged.
- When a catalog beyond manifest files is warranted (§10).
- The SSH library for the puller (§10): the maintained JSch fork is small; Apache MINA SSHD is large but has a test server. The choice weighs the standalone install's size against testability.
- Whether the puller should also copy files the server does not read, such as CTRE's `.hoot` signal logs, so that the robot's storage holds nothing the pit server lacks. The proposal is a configured list of patterns, with `.wpilog` and `.revlog` by default, and `.jfr` added when the JVM provider is configured.
- Whether the roboRIO's JRE, which WPILib builds with `jlink` from a chosen set of modules, carries `jdk.management.agent` (needed for JMX remote) and `jdk.jfr` with `jdk.management.jfr` (needed for the Flight Recorder stream). The first thing to check on a real roboRIO; if either is absent, the provider does what the present modules allow, and the question of adding them goes to WPILib.
- The JMX port. The field's allowed port ranges do not bind the pit server, which does not run there, but a team that leaves the flags in its deployed code carries them to competition; the guide proposes a port in the team-usable range (5800 to 5810), so nothing changes between the shop and the field, and says the listener is harmless there.
- The system-stats period and budget on a roboRIO 1: measured, not chosen.
- Where on the roboRIO the serial number is read from over SSH (the HAL's source), and whether the comments field is readable from the same place; verified on a real roboRIO 1 and 2, with the file paths in the provider's metadata.
- Whether to ask WPILib to have DataLogManager log the serial number, team number, and comments at startup, as AdvantageKit does, so no team has to add the line.
- Whether `nt_value` with a topic label or a metric name derived from each topic serves Grafana better. The label keeps names exact and the cardinality is the same; derived names read better in a query editor. The proposal is the label, with a sanitized `name` label beside it if that turns out to matter.
- Whether the Grafana query endpoint (§11) is worth building before the data browser (IDEAS 9.2) covers the same need inside VS Code.
