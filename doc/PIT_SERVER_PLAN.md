# The Pit Server: Live Capture, Gateway, and the Long-Term Record

A proposal for the next large piece of wpilog-mcp: a program that runs in the shop and in the pit, listens to the robot all the time, keeps everything it hears, and answers questions about it, live and later. The first half of this document is for anyone on the team; the second half specifies the work for the developers who will build it.

## Part I: The Idea

### What it is

Today wpilog-mcp reads log files. A robot writes a `.wpilog` file while it runs; someone copies the file off the robot; the server reads it and an AI assistant answers questions about it. That works, and it has one weakness that every team knows: the log has to be pulled. The file that would have explained the strange match is the one nobody copied before the robot was powered down for the night.

The pit server closes that gap. It is one program, running on a laptop or a small computer on the robot's network in the shop, and on the Driver Station laptop in the pit. While the robot is on, the pit server subscribes to the robot's NetworkTables, the live stream of values the robot publishes, and records every change to a log file of its own. When the robot reboots, a new session begins. Nobody has to remember anything: if the robot ran, the data exists.

It also fetches the robot's own log files. Whenever the robot is sitting disabled on the robot's network, the pit server copies any log it has not seen yet from the roboRIO, slowly enough not to get in anyone's way, and stops the moment the robot is enabled. The pulled log is the full record the robot wrote; the recording is what the pit server heard; the pit server keeps both and knows which is which. It copies the roboRIO's system logs the same way, so a kernel message about a CAN bus going off line, or a camera that unplugged itself, sits beside the data it explains. And a few files it is told to watch, the robot program's console among them, it follows as they are written, so what the program printed a second ago is in the recording a second later, where every tool that reads text can see it.

Because the pit server is the same program as wpilog-mcp, every tool the assistant already has works on those recordings, including the one still being written. The process that writes the recording is the process that answers questions about it, so it never has to read its own file back: the index and the values are already in memory as they arrive, and a question about the last ten seconds is answered as fast as one about a file read an hour ago. "What is the battery voltage right now?" and "what happened in that run twenty minutes ago?" are answered from the same place. Logs pulled from the robot are still imported, and where they overlap a recording they take precedence, since the robot's own log sees more than the network does.

### Why

- **Logs stop getting lost.** The recording exists for every session, whether or not the file on the robot was ever copied, and the file on the robot is copied anyway, on its own, the next time the robot sits disabled.
- **The robot can die and the data survives.** A robot that loses power mid-run leaves a truncated file on its own storage. The pit server has everything the robot published up to the moment it went quiet.
- **Questions can be asked while the robot is running.** A student tuning a mechanism asks the assistant what the last ten seconds looked like, without stopping to pull a file.
- **The robot sees one listener.** Every laptop in the shop that wants live data today connects to the robot itself, and the robot sends each one its own copy of everything. The pit server subscribes once and serves everyone else, so the robot's network link carries one stream however many people are watching.
- **The data gets its context.** A vision camera's detections mean little without knowing how the camera was configured. The pit server can ask the camera's coprocessor directly and record its calibration and pipeline settings beside the data.
- **The robot's computer is watched too.** The roboRIO is a Linux computer, and the robot program is a Java virtual machine. The pit server can ask the first how busy its processor is and how full its disk, and the second when it paused to collect garbage, and record both beside the data, so a loop that ran long can be laid against the pause that made it.
- **The shop gets a wall display.** The pit server exposes its latest values for Prometheus to scrape, so a Grafana dashboard on the shop wall shows the battery, the processor, the disk, and whether a recording is running, without anyone asking.
- **The record keeps itself in order.** The pit server owns its data directory. Every file in it, whether recorded, pulled from the robot, or brought in on a USB stick, was placed there by the server, by robot and by session, so a season's record is findable without anyone keeping it tidy.
- **Every file knows which robot it came from.** A team with a competition robot and a practice robot has two robots with the same team number, the same name on the network, and usually the same code, and a log file says nothing about which one wrote it. The pit server asks the roboRIO for its serial number, which is unique to the device, and records it with every session and every pulled file, so a season's record is a record per robot.
- **The data comes home.** A laptop with the extension keeps its own copy of the sessions it is told to keep, synchronized from the pit server whenever the two can see each other, so the analysis that started in the pit continues on the bus and at home, from the same files, with the same answers.
- **It is the foundation of a long-term record.** Once every session is captured and cataloged, questions across a season become possible: how a mechanism's current draw drifted over the year, whether a fix held.

### What it is not

- **Not for the field.** At competition the only team device on the robot's network is the Driver Station laptop, and the field caps the robot's bandwidth. The pit server runs in the shop, on the practice-field network, and in the pit over a tether. It does not run during a match.
- **Not a replacement for the robot's log.** The robot's own log records what the code saw at loop rate; NetworkTables carries the subset the code chose to publish. The recording stands in for a missing log and fills gaps; a pulled log remains the record of truth where one exists.
- **Not a way to control the robot.** The pit server reads. Dashboards that write to the robot, to choose an autonomous routine or tune a value, keep talking to the robot directly.
- **Not a folder to copy files into.** A file from elsewhere is imported, through a command, a drop folder, or an upload, so that the server reads it first and files it where it belongs. A file copied in by hand is reported and left alone.
- **Not a monitoring system.** It exposes its numbers for Prometheus to read and runs neither Prometheus nor Grafana; a team that wants the wall display runs those beside it.

### How it works, in one picture

```
  robot (NetworkTables server)         roboRIO Linux, and the robot program's JVM
        │  one subscription,                  │  SSH: its logs and system stats
        │  every change                       │  JMX: garbage collection, profiling
        ▼                                     ▼
  pit server ─────────────────────────────────────────────────────┐
    │ records each session as a .wpilog file          (the record) │
    │ keeps all of it by robot and by session           (the store)│
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
- **Store**: the pit server's data directory, laid out by robot and by session as §11 specifies, and written only by the server.
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
15. **The pit server owns its store.** Its data directory has one layout, defined and kept by the server, and every file in it was placed there by the server after reading it: recorded, pulled, or imported through a command, the drop folder, or an upload. Nothing is copied in by hand; a file that appears otherwise is reported and not indexed. One organization is what makes a season's record a directory walk rather than a search, and reading before placing is what lets the server say what each file is, since a name is not evidence (decision 14, §10).
16. **A mirror is a store, owned by the sync.** A laptop's copy of the pit server's data has the store's layout and manifests, is written only by the synchronization, and is read by the local server as any store, so the laptop offline answers as the pit server does, from the same bytes. It is a cache with a scope and a size, not a second record: it may drop what falls out of scope, and never holds a file the pit server does not.

### 3. Architecture

Code map additions, following the existing package layout:

| Package | What it holds |
|---|---|
| `nt4` | The NT4 wire protocol: message records, the MessagePack value codec, type codes, the time-sync arithmetic. Shared by client and server; no I/O |
| `nt4/client` | The client: connection, subscription, reconnection, the latest-value table |
| `nt4/server` | The gateway: the WebSocket server, per-client subscriptions, announcement and value fan-out |
| `capture` | Session detection, the session writer (DataLog) and the live log it extends as it writes, topic cost accounting, exclusion and thinning policy |
| `capture/context` | Context providers: PhotonVision; the roboRIO's operating system over SSH; the robot program's JVM over JMX and Flight Recorder; files followed in real time over SSH |
| `store` | The store: its layout and format version, the manifests, the import pipeline (classify by content, hash, read identity and time, place, verify), the inbox, the upload; the store's HTTP face (listing and files) |
| `store/mirror` | The mirror: a laptop's synchronized copy of a pit server's store, its scope and size, the pin list, eviction |
| `sync` (existing) | Gains the transfer logic the puller and the mirror share: listing comparison, resume by content check, pacing, verification, manifest, over a transport interface (SFTP from the robot, HTTP from a store) |
| `capture/pull` | The log puller: the robot-state gate, the SFTP transport for the shared transfer logic, the pull manifest; the one SSH connection per host, which the system-stats and tail providers share |
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

**Placement.** A capture is written as `capture.wpilog` in its session's directory in the store (§11), `robots/<serial>/sessions/<yyyy-MM-dd>/<HHmmss>Z/`, named by the session's start in UTC from the pit server's clock. The directory is renamed with the event and match when the Driver Station entries supply them, which the reload logic handles as a change. The file's name says what it is, so a capture is never mistaken for a log pulled from the robot, and a pulled log keeps the robot's own name beside it under `robot/`.

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

Each sample is timestamped in the robot's clock as the pit server's time of sending the command, mapped through the NT4 time offset; a sample is not an event the robot timestamped, and the metadata says so. The `uptime_sec` sample does one more job: paired with its FPGA timestamp, it is the measured offset between the kernel's clock, which the kernel log and `dmesg` use, and the robot's clock, which the system log reader needs (§10). The metadata: `{"source":"ssh","host":"<address>","sampled":true,"period_sec":<period>}`.

#### 8.3 The robot program's JVM

The robot program is a JVM, and the JVM's own state explains a class of mystery no robot log records: a loop overrun that is a garbage collection pause, a slow first cycle that is class loading, a heap climbing across a match. The JVM reports all of it through two mechanisms the JDK carries, which the pit server consumes without a dependency:

- **JMX** (`java.lang:type=Memory`, `GarbageCollector`, `Threading`, `ClassLoading`, `OperatingSystem`), polled at a period (default 1 s): heap used and committed, collections and their total time per collector, thread count, loaded classes, the process's CPU time. Sampled, like the system stats, and marked so.
- **Flight Recorder (JFR)**, streamed over the same JMX connection with `jdk.management.jfr`'s `RemoteRecordingStream` (JDK 16 and later): the JVM's own events, timestamped by the JVM, which make the record exact where polling only suggests. The default set is small and chosen for what a robot team can act on: `jdk.GarbageCollection` (each pause with its duration), `jdk.GCPhasePause`, `jdk.SafepointBegin`, `jdk.ThreadStart` and `jdk.ThreadEnd`, `jdk.ExecutionSample` at a low rate (every 20 ms) for a profile of where the program spends its time, `jdk.ObjectAllocationSample`, and `jdk.SocketRead` and `jdk.FileWrite` above a duration threshold. Each becomes entries under `/Daemon/JVM/<event>/...` with the event's fields; an execution sample becomes a string entry of the top frame and a JSON entry of the stack, from which a profiling tool later builds the usual summary (time by method, by thread). JFR is designed to cost a percent or two; the pit server measures it on a real roboRIO before the default set is settled, and the set is configured.

Two things are not the pit server's to do. **It does not instrument the robot program** (decision 12). JMX remote is off in a JVM unless its launch asks for it, and the launch is the team's: the robot project's `build.gradle` adds the arguments to the deployed program (`jvmArgs` on the deploy artifact in GradleRIO), and the standalone guide gives them (`-Dcom.sun.management.jmxremote.port=<port>`, the RMI port set to the same so one port serves, `java.rmi.server.hostname` set to the robot's address on the team's network, authentication and SSL off, which is the trust model of decision 7 and belongs on the private network only). The provider is on only when a robot's configuration names the port, and a robot that does not answer there is reported in `list_sessions`, not worked around. **It does not map clocks by guessing.** JFR events and JMX samples are on the JVM's wall clock, and the capture is on the FPGA clock. The offset between the two is measured on the same connection: the `Runtime` MBean's start time plus its uptime is the robot's wall clock now, within a round trip, and the NT4 time sync gives the FPGA clock now, within another; their difference maps one to the other. It is remeasured each period, because the Driver Station sets the robot's wall clock when it connects and the offset jumps by years at that moment; a jump is recorded as a note entry, and events before it keep the mapping that held when they were stamped. Where a pulled log of the same session carries the `systemTime` entry DataLogManager writes, which records the wall clock against the FPGA clock from inside the robot program, the import matching prefers it, and the metadata of every JVM entry says which mapping it got (`"clock":"measured"` or `"clock":"systemTime"`).

A JFR recording to a file on the robot (`-XX:StartFlightRecording` with a file under the log directory) is the counterpart of the robot's own log: it survives the pit server's absence, costs the network nothing during the run, and is pulled by the puller like any other log once `.jfr` is in its patterns. Reading it is `jdk.jfr.consumer.RecordingFile`, in the JDK, and importing it makes the same entries the stream would have, with the file's own timestamps and the `systemTime` mapping. That import is a later milestone, after the stream has shown which events matter.

#### 8.4 Followed files

Pulling brings the roboRIO's logs in after the fact (§10), when the robot is disabled, which is minutes or hours after the line that would have explained the moment. Some files are worth following as they are written: the robot program's console log first of all, since the capture otherwise has no console at all (DataLogManager writes the console into the robot's own log as `messages`, not onto the network), and the kernel log and the system logger's file after it. The provider follows a configured list of host and file pairs in real time and writes each new line into the capture as it arrives, as a record of a string entry, so a line is in the live log within about a second of being written on the robot, and `search_strings`, the DS timeline's error counts, `can_health`'s reading of CAN failure text, and every other tool that reads text see it there without a pull.

- **Configuration**: `tail: [{host, user, auth, files: [{path, role}]}]`. The host is the roboRIO by default, on the puller's SSH connection; it may be any host the team can reach over SSH, a vision coprocessor among them, with its own credentials (a key by preference; a password through the configuration's environment variable interpolation, never in a project file). One SSH connection per host, shared by the puller, the system stats provider, and this one; the connection manager moves from `capture/pull` to a shared place. A follow is a long-running `tail -F` on a channel of that connection, with a short sleep interval, which is the cheapest watcher a Linux host has; `-F` follows the name across a rotation. The kernel log and the journal are followed with their own tools where the image has them (`dmesg -w`, `journalctl -f`), else polled from an offset at the system stats period.
- **Entries**: `/Daemon/Tail/<host>/<role>`, a string entry, one record per line, with metadata `{"source":"tail","host":..,"path":..,"timestamp":"received"}`. The `role` is the configured name; four are known to the server and documented as its convention, so the signal resolver can use them: `program_console` (the console text role, which `search_strings` and the tools that take console text then find by convention in a capture, as they find `messages` in a robot's log), `kernel`, `syslog`, and `journal`. Anything else is a string entry like any other.
- **Timestamps**: a line is timestamped at receipt on the pit server, mapped to the robot's clock through the NT4 offset, and the metadata says so: the record's time is when the pit server heard the line, within the follow's latency, not when the robot wrote it. The line's own timestamp stays in its text, and `search_system_logs` on the pulled file (§10) gives the exact mapping where exactness matters. Lines received while no session is open (the program's startup output arrives before its NetworkTables server does, and the SSH connection outlives sessions) are held in a bounded buffer (default 1000 lines) and written at the next session's start, mapped through the offset once it is measured, clamped at zero, with a metadata note that they preceded the session.
- **Cost**: a follow costs the robot nothing while the file is quiet. A program that prints in a tight loop is the risk, to the capture rather than the robot, so each followed file has a rate cap (default 200 lines per second); lines beyond it are dropped, counted, and the count written as one record when the burst ends, so the drop is in the record. Followed files appear in the session's cost accounting beside the topics.
- **Not a replacement for pulling.** What the provider heard is the pit server's record of the file, as the capture is of the stream; the file pulled later is the robot's. Both are kept, and the manifest says which is which. The follow may miss lines across an SSH reconnection, and a pull does not.

#### 8.5 Robot identity

Nothing in a DataLogManager log identifies the roboRIO that wrote it, and nothing the pit server sees on the network does either: the address and the hostname are the team number, which the team's competition robot and practice robot share, and the SSH host key changes whenever the roboRIO is reimaged. The one identifier that is unique to the device and survives reimaging is the roboRIO's serial number, which the HAL exposes to the robot program as `RobotController.getSerialNumber()` and which the device itself can be asked for (decision 14).

The provider reads it over the SSH connection on first contact with a robot, from the same source the HAL reads, together with the roboRIO's comments field, which teams fill with a name such as "practice bot" in the roboRIO's web dashboard. It writes both as JSON into the capture at session start, under `/Daemon/Robot/Identity` (`serial_number`, `comments`, `address`, `host_key_fingerprint`, `basis: "device"`), and the session manifest and the pull manifest carry the serial, so a capture and every file pulled over that connection are mapped to the robot before any data is read. A robot program that logs the serial itself is read the same way: AdvantageKit writes it on every boot as `/SystemStats/SerialNumber`, beside `/SystemStats/Comments`, and a DataLogManager robot does so with one line in `robotInit`, which the guide recommends as the way to make every log of a robot self-identifying for the rest of its life, wherever the file travels: log the serial number, the team number, and the comments once at startup. Where both a device reading and a logged value exist they must agree; a disagreement is reported in `list_sessions`, since it means the connection and the file are not the same robot, and the file's own value wins for the file.

The listing then shows a `robot` for every log that has one: `serial_number`, `comments` when known, and `basis` (`logged` from the file's own entry, `device` from the manifest of a connection the pit server made). A log with neither has no `robot` field; the listing adds a `robot_candidates` note only when a fingerprint (the team number from a logged entry, the entry set, the CAN device inventory from a REV log) matches one known robot and no other, naming the evidence and never promoting it. The same reading applies in the local server, not only the pit server: a logged serial is read by the listing wherever the file is.

Identity gates matching (§10): a pulled log is matched only to a session of the same serial, so a practice robot's log can never be matched to the competition robot's session however well their signals happen to line up; where either side has no serial, the match is made on data alone and the manifest says so. Identity also keys the puller's state, so a reimaged roboRIO with a new host key is the same robot with the same manifest, after the host key change is reported.

### 9. Live tools

Three tools, in a `LiveTools` module registered only when capture is enabled, and listed in the catalog under a new `Live` category. They read the latest-value table, the live log, and the session registry; they open no file. Every other tool reaches a live session through its path, as any log, and gets the live log's in-memory index and values (§6).

**`list_sessions`**: `sessions[]`, newest first, each with `path` (the capture), `started_at`, `ended_at` (null while open), `robot` (`serial_number`, `comments`, `address`, `basis`, per §8.5), `connected`, `topic_count`, `records`, `bytes`, `bytes_per_sec` (last minute, open sessions), `event`, `match` (when known), `cost[]` (the ten most expensive topics with `records` and `bytes_per_sec`), `thinned[]` and `excluded[]` from configuration, and `imports[]`: pulled logs matched to this session with the method (`by_time_overlap`, `by_correlation`) and the offset. `limits.sessions` when cut. `not_applicable` with a reason when capture is not enabled.

**`get_latest_values`**: `entries[]` (required), returns per entry `name`, `value`, `timestamp_sec` (robot clock), `age_ms` (robot now minus timestamp), `type`; unknown names are listed in `missing` and the status is `partial` when any is missing, `no_match` when all are. `not_applicable` when no session is open, with `last_session` and when it ended.

**`wait_for_change`**: `entry` (required), `timeout_ms` (default 5000, capped at 30000), returns the first value after the call began with its `timestamp_sec`, or `status: ok` with `changed: false` on timeout. One outstanding wait per entry per session; a second returns `error`.

Every result carries `inputs.session` (the capture path). Descriptions say what the values are and are not: the latest published, not measured, values; a stale `age_ms` means the topic stopped publishing, not that the robot stopped. The claim checks apply as to every tool.

### 10. Imported logs: pulling from the robot, and matching to sessions

**Pulling.** The robot writes its own logs to its storage: DataLogManager to `/home/lvuser/logs`, or to `/u/logs` when a USB drive is present; AdvantageKit to the USB drive's `/U/logs`; REVLib's status logger wherever it is configured. The puller copies those directories into the store (§11), keeping the robot's file names, which encode the time, event, and match that the listing reads: a file lands under `robots/<serial>/pulled/` while it is transferred and verified, and is moved into its session's `robot/` directory once it is matched (below). It behaves as `rsync` would, without the tool:

- **Transport**: SFTP over SSH to the roboRIO, as the `lvuser` account (no password by default; a key or a password may be configured). The host key is pinned on first contact per robot and a change is reported, since a reimaged roboRIO has a new one; the robot's identity is its serial number (§8.5), which the reimage does not change, so a reported key change under the same serial continues the same pull manifest. The SSH client is a library dependency, the smallest maintained one that serves; the choice is an open question (§17).
- **The gate**: a transfer step runs only while the robot has been disabled for at least a configured settle time (default 5 s), read from the control word the robot publishes (`/FMSInfo/FMSControlData`, the enabled bit), and while the pit server's NT4 connection is up, so the state is current. The moment the state is anything else, the step in progress finishes its current block and the transfer pauses; it resumes from where it stopped when the gate reopens. With no NT4 connection the puller does nothing: it will not guess that a robot it cannot hear is idle.
- **What to copy**: the remote listing (name, size, modification time) against a manifest of what has been pulled (`robots/<serial>/pull.json`: remote name, size, modification time, bytes copied, verified). A file not in the manifest is new; one whose size grew is fetched from the bytes already copied, since the logs are append-only, but only once the robot has confirmed that those bytes are the ones the puller holds (below); one whose size shrank, whose modification time went backward, or whose content does not match is a different file under the same name, and the local copy is kept under a disambiguated name while the new file is fetched from the start. The file the robot is writing now is copied like any other and grows across passes; the reload path handles the local copy's growth. When DataLogManager renames an open log once the Driver Station supplies the time and match, the old name disappears and a new name appears with the same content; the puller recognizes it by the same content check over the whole partial copy and renames the local copy rather than copying again.
- **Identity is content, never a name.** A name on the robot does not identify a file. REVLib names its log by the roboRIO's clock, which reads the same default date on every boot until the Driver Station sets it, so the same `REV_<date>_<time>.revlog` name recurs boot after boot, with the same modification time; and two boots of the same code declare the same entries in the same order, so the first tens of kilobytes of two different logs can be byte-identical. A resume that trusted the name would append one boot's log to another's and produce a file that loads with timestamps running backward in the middle. So before resuming or renaming, the puller asks the robot for the hash of exactly the bytes it already holds (`head -c <N> <file> | sha256sum` over the SSH session, which costs the robot a few seconds of reading and the network nothing) and compares it with the local copy; only a match resumes. Where command execution is unavailable, the fallback fetches the last 64 KB of the known range and compares that, where the microsecond timestamps of two boots have long since diverged.
- **Throttle**: a configured rate cap (default 1 MB/s), enforced on the reading side by pacing block reads, and one transfer at a time. The roboRIO's processor is small and SSH encryption costs it; the cap protects the robot as much as the network.
- **Verification**: after a file's size has been stable on the robot for one pass, the local copy is loaded through the normal path; a copy that loads, with its scan ending at the file's end, is marked verified in the manifest. A copy that does not is fetched again from the start, once.
- **Deletion**: never, in this version. Freeing the robot's storage is a later, opt-in action that requires a verified copy and says what it removed.
- **Reporting**: `list_sessions` gains `pulls[]` per robot: the last pass, files copied, bytes, and files waiting for the gate; the server log says when a pull starts, pauses, resumes, and completes.

**System logs.** The roboRIO keeps logs of its own, and they answer what no robot log can: the kernel reports a CAN interface going bus-off, a USB camera enumerating and vanishing, a network link flapping, a process killed for memory, and the cause of a reset; NI's daemons log the robot program's starts and stops and its console output; and a JVM that dies leaves a fatal error file. The puller copies them under the same gate, throttle, and content-identity rules as the robot's logs, in the same pass, from a configured set with these defaults, each verified on a real roboRIO 1 and 2 before it is a default (§17):

- The system logger's files under `/var/log/` (`messages` and its rotations), or the journal where the image runs systemd, read as text with `journalctl` from a cursor the manifest keeps, so each pass fetches only what is new.
- The kernel ring buffer, which is not a file: `dmesg` is run over the SSH session on each pass and its output appended to the session's `dmesg.txt`, from the first line after the last one already held, which the kernel's monotonic timestamps identify.
- NI's logs under `/var/local/natinst/log/`, the robot program's console log among them.
- The robot program's fatal error files, `hs_err_pid<pid>.log` in its working directory, which name their pid, so the session is the one whose `program/pid` (§8.2) matches.

Where each lands follows one rule: a file the robot keeps per boot or per program run belongs to that boot's session, under `robot/system/`; a file that spans boots, such as a rotated `messages`, belongs to the robot, under `robots/<serial>/system/`, and is reached from every session its time range covers. A rotation is a rename the content check recognizes, as it recognizes DataLogManager's.

System logs are kept as files, unmodified, as the robot's logs are; they are not written into the capture, since they arrive after the fact and belong to the robot's record, not the pit server's. The lines the tail provider heard as they were written (§8.4) are in the capture already, timestamped at receipt; the pulled file is the exact record. A tool reads them: `search_system_logs`, with the session's `path`, a `source` (`kernel`, `syslog`, `program`, `jvm_crash`, or all), the `pattern` and the time window `search_strings` takes, and the same line rule for severity. Each returned line carries its original timestamp and, where the mapping is known, `timestamp_sec` on the robot's clock with its basis: the kernel's seconds since boot are mapped by the `uptime_sec` samples the system stats provider pairs with FPGA time (§8.2), and wall-clock lines by `systemTime` from the session's pulled log or the measured offset (§8.3). A line whose mapping is unknown, which is every kernel line of a session the stats provider did not sample, is returned on its own clock with `timestamp_sec` null and the reason stated, never shifted by a guess; the kernel's clock starts before the FPGA's, by an interval the plan does not assume. Severity and pattern searches work either way; alignment with a tool's result needs the mapping, and the result says which lines have it.

**Matching.** A pulled log of the same boot is matched to a session of the same robot (§8.5; a match across robots is never made, and a match where either side has no serial is marked as made on data alone) by the machinery REV synchronization uses: names nominate (a Driver Station entry both carry, a battery voltage, a loop count), the data decides (cross-correlation), and the offset must be near zero since both are on the FPGA clock. A match moves the pulled log into the session's directory and records it in the session's manifest (`session.json`, §11: the session's facts and every file in it with its provenance), which `list_sessions` reads and the listing shows as `session`. The pulled log is the authoritative record of its session; the capture stands in where no pulled log exists and fills the gap where the pulled log is truncated. No database: manifests are files, in keeping with the project's "nothing to operate" goal; a catalog over many seasons is a later decision, made when the files are many.

### 11. The store: the pit server owns its data

The pit server's data directory is the store, and the store is the server's (decision 15). Every file in it was placed there by the server, which read the file first and filed it by what it read: which robot, which session, what kind of file. Nothing is copied in by hand. The reason is the long-term record. A directory people copy files into has whatever organization its last visitor left, and a file in it is known only by its name, which §10 shows is not evidence of anything. A directory the server keeps has one organization, every file in it has a manifest saying where it came from and what it is, and a question across a season ("every session of the practice robot since the last firmware change") is a walk of the directory rather than a search.

**Layout.** By robot, then by session:

```
<store>/
  store.json                       the store's format version, and when it was created
  inbox/                           the drop folder: anything placed here is imported, then removed
  robots/<serial>/
    robot.json                     serial, comments, team number, the addresses and host keys seen
    pull.json                      the puller's manifest for this robot (§10)
    pulled/                        files in transfer from the robot, until verified and matched
    sessions/<yyyy-MM-dd>/<HHmmss>Z[_<EVENT>_<MATCH>]/
      session.json                 the session's facts, and every file in it with its provenance
      capture.wpilog               the pit server's recording (§5)
      robot/FRC_20260307_142233_CAVAL_Q12.wpilog    pulled or imported, under the robot's own name
      robot/REV_20260307_142233.revlog
      robot/robot.jfr
      robot/system/                the roboRIO's per-boot logs: dmesg.txt, the program's console log, hs_err files
    system/                        the roboRIO's logs that span boots: messages and its rotations, the journal
  unassigned/<sha256 prefix>/      imported files whose robot or session is not yet known
```

A session directory is named by the session's start in UTC, and the event and match are appended by a rename when the Driver Station supplies them, which the reload logic handles as a change. A robot whose serial is not known yet (no SSH connection and no logged serial) has its sessions under `robots/address-<address>/`, moved under the serial when it is learned. Every move within the store is the server's to make, and every file keeps a path a tool can take, so nothing changes for the tools: a session is a directory of logs.

**Three doors in.** Capture (§5) and pull (§10) place their files directly. Everything else is an import:

- **`wpilog-mcp import <path>...`**: files, directories, a USB stick. Each file is classified by its content (a WPILOG header, a REV log header, a JFR header; anything else is refused by name), hashed, and read for what places it: a logged serial (§8.5), its time range, `systemTime` where present, and the event and match from its Driver Station entries. A session of the same robot overlapping its time range receives it, matched by data as §10 matches a pulled log; otherwise it starts a session of its own, or waits under `unassigned/` when neither robot nor time is known. The original is copied, not moved, unless `--move` is given, and the copy is verified by loading before the manifest records it. A file whose hash the store already holds is reported as present, with its path, and not copied twice.
- **The inbox**, for the person with a USB stick and no terminal: a file placed in `inbox/` is imported as above and removed from the inbox once its copy is verified. The result is written beside it in `inbox/imported.log`, the one file in the inbox the server writes, so a refused file is explained where it was dropped.
- **`POST /import`**, an HTTP upload through the same path, so the VS Code extension and the data browser (IDEAS 9.2) can send a file from a laptop to the pit server. It is a write surface on a server with no authentication (decision 7); a team that fronts the server with nginx puts the password on this path first.

**A stray is reported, not adopted.** A file that appears in the store outside the inbox, placed by hand, is not indexed: `list_sessions` reports it under `unmanaged[]` with the import command, the server log says the same once, and it is never read into a session and never deleted. The security validator still admits it to a tool given its path, since it is inside a configured directory, but it belongs to no robot and no session until it is imported, and a result about it says so.

**Manifests, not a database.** `session.json` lists each file with its hash, size, kind, and provenance (`captured`; `pulled`, with the remote name; `imported`, with the source path and the time), the matching method and offset for a file matched by data, and whether it was verified. `robot.json` and `pull.json` are as §8.5 and §10 say. The listing recognizes a store by `store.json` and reads sessions from the manifests rather than from file names, so a pulled log keeps the robot's name and still lists under its session. `store.json` carries a format version: a server that finds a newer one refuses to start with a message, and an older one is migrated in place by an explicit command, never silently, in the spirit of the disk cache's version.

**Removal is the server's too.** Nothing in the store is deleted by the server in this version, and nothing should be deleted by hand. A later `wpilog-mcp forget` removes a session or a file with its manifest entry and says what it removed.

**The store over HTTP.** The pit server serves its store read-only on the same port as the MCP transport, so a laptop can copy from it without a shared folder: `GET /store` (the store's id, format version, and server version), `GET /store/robots`, `GET /store/sessions` with `since`, `robot`, and `event` filters, each session as its manifest, `GET /store/files/<store path>` with `Range` support, and `GET /store/files/<store path>/prefix-hash?bytes=<N>`, the hash of a file's first N bytes, which is the content check the puller asks the robot for (§10), served here for a file still growing. Every manifest carries its files' hashes, so a client knows what it lacks from the listing alone. The endpoints are reads under decision 7, behind the same proxy if a team adds one.

**The mirror.** A laptop keeps a synchronized copy of part of the store (decision 16), so that analysis continues where the pit server is not: on the bus, at home, in a hotel the night before finals. The local server does the copying, in Java, with the puller's own transfer logic over HTTP instead of SFTP: the listing comparison, resume from the bytes already held after the prefix hash confirms them, pacing under a configured cap, verification by hash and by loading, and a manifest. The mirror is a directory in the store's layout with its own `store.json`, which names the pit server's store id and URL and marks the directory a mirror, so the local server reads it as a store (sessions from manifests, robots by serial, the matching offsets in `session.json` used as if the user had set them, so a REV synchronization is never recomputed on the laptop) and never imports into it or adopts a stray in it. Paths are the same relative to each store, so a session the agent looked at on the pit server is the same path under the mirror, and `list_available_logs` shows for each mirrored session its `origin` (the pit server's URL), whether it is `complete` or still growing there, and the time of the last synchronization.

What it keeps is a scope, not everything: sessions of the last N days (default 14), whole events the user names, and sessions the user pins, from the extension or the data browser (IDEAS 9.2) or a `pin_session` tool, under a size cap (default 20 GB) that evicts the oldest unpinned sessions first and never a pinned one. A session that is open on the pit server is mirrored as it grows, through the resume path and the laptop's reload logic, at the mirror's sync interval (default 30 s); a session the pit server renamed or moved under a serial is followed by its id in the manifest, not its path. The mirror runs whenever the pit server answers and stands down quietly when it does not: offline, the listing still answers from the mirror and says how old it is. Deletion inside the mirror is the sync's, under the scope and the cap, and nothing else's: it is the one place in this design where a file is removed without a person asking, and only because the pit server still has it.

**The local server is unchanged otherwise.** The store is the pit server's, and the mirror is the sync's. The extension's local server, and a standalone server configured with log directories, read directories as they do today, organized however their owner likes, beside any mirror they are given. A laptop pointed at a pit server's store over a shared folder reads it as any directory, since every file in it is a log in a directory.

### 12. Metrics: Prometheus and Grafana

Some of what the pit server knows is wanted on a wall, not in a conversation: is the robot on, what is the battery at, is the roboRIO's disk filling, is a capture running. Prometheus is the ordinary way to put numbers on a wall and Grafana the ordinary way to draw them, and both consume a format that costs nothing to serve. So the pit server exposes `GET /metrics` in the Prometheus text exposition format, rendered on each scrape from the latest-value table and the server's own counters, with no dependency and no state of its own.

What it carries:

- **Every numeric topic**, as one metric with the topic as a label, `nt_value{topic="/SmartDashboard/Battery Voltage"}`, so a topic keeps its exact name and a dashboard query is a label match. A boolean is 0 or 1; an array is one sample per element with an `index` label, up to a configured length (default 16, enough for the swerve module arrays and not for a vision frame); a struct is expanded by its schema into one sample per numeric field with a `field` label, through the field paths the tools use; strings are not carried, since Prometheus has no strings. Beside each, `nt_age_seconds{topic}`: how long since the topic last changed, in the robot's clock, so a dashboard shows staleness instead of a frozen number. A topic filter in configuration (`metrics.include` prefixes) narrows this where a robot publishes thousands of topics; the default is everything numeric, which Prometheus handles comfortably at a scrape per second for a few thousand series.
- **The providers' entries** (§8), which are topics in the latest-value table like any other, so the roboRIO's processor, its disk, and the JVM's heap arrive with no further work.
- **The pit server itself**: whether the NT4 connection is up and to which address, the session's topic count, records, and bytes written, the measured time offset and its round trip, the gateway's client count, the puller's bytes and files per robot and the files waiting at the gate, each provider's sample cost, and the pit server JVM's own memory and collections from the platform MBeans, under `wpilog_...` names in Prometheus's conventions (base units, the unit in the name: `wpilog_capture_bytes_total`, `wpilog_nt_time_offset_seconds`). `GET /health` stays the one-line answer; `/metrics` is the detailed one.

What it does not do. **It is a view, not the record** (decision 13). A scrape sees the latest value at the moment of the scrape, and a value that changed twice between two scrapes is seen once; Prometheus at its default 15 s, or at 1 s, is a sampled view of a 50 Hz signal. The capture remains the record of every change, and the documentation says so where a team is tempted to analyze a dashboard. Samples carry no timestamp in the exposition, since the robot's clock is not the wall clock and Prometheus drops a stale one; staleness is `nt_age_seconds`. **It runs neither Prometheus nor Grafana**, in keeping with the "nothing to operate" goal: a team that wants the wall display runs the two beside the pit server, and the standalone guide gives a compose file with Prometheus scraping the pit server and Grafana provisioned with a starter dashboard (battery, CAN, loop time, processor, disk, heap, session state), which is the whole setup. **It has no authentication**, as decision 7 says: the endpoint is on the same port as the MCP transport, and behind the same proxy if a team adds one.

A later step, when a dashboard wants the record and not its samples: the data endpoint the explorer plan specifies ([EXPLORER_PLAN.md](EXPLORER_PLAN.md) §6), which any server on the HTTP transport serves, giving an entry's values over a range, every sample or reduced to a requested number of points by the minimum, maximum, and mean per bucket, as an Arrow stream or CSV. A `json` format for Grafana's data source plugins is the addition, when someone needs full-resolution history in Grafana rather than in the explorer or a tool. Prometheus first.

### 13. Network exposure

The pit server binds to an address on the team's network (`WPILOG_HTTP_BIND`, as today) and serves the MCP endpoint, the gateway, and `GET /health` without authentication. The `Origin` check stays: it protects a browser on the network from being used against the server by a web page, and costs non-browser clients nothing.

The standalone guide gains a short section for teams that want more: an nginx configuration that terminates TLS and asks for a password in front of the MCP endpoint, with the pit server itself bound to loopback behind it. The gateway's NT4 port is a separate matter; a dashboard cannot present a password to it, and the protocol has no place for one, so it is exposed on the private network or not at all.

### 14. The VS Code extension

- A setting `wpilog-mcp.pitServerUrl` (order after the TBA key). With it set, the extension registers the pit server with VS Code's MCP registry as an HTTP server, beside the local one, and adds an HTTP entry for it to a robot project's `.mcp.json` for Claude Code, under the same rules as the local entry. The entry holds a URL and nothing secret, so a team may commit it: every teammate's Claude Code then finds the pit server from the project.
- Both servers' instructions and `get_server_guide` say which server is which: the local one for files on this laptop and the mirror, the pit server for the team's sessions and for anything live. A session that is in the mirror is answered the same by either, from the same bytes, and the instructions say so, so an agent offline knows the mirror is not a lesser copy.
- **Synchronization** (§11, the mirror), transparent once turned on: `wpilog-mcp.mirror.enabled` (default off until a pit server URL is set, then on), `wpilog-mcp.mirror.folder` (default under the extension's own storage), `wpilog-mcp.mirror.days`, `wpilog-mcp.mirror.maxSizeGb`, and `wpilog-mcp.mirror.robots` to narrow to some serials. The local server does the work; the extension passes the settings and shows the state in a status bar item: synchronized, the count and size still to copy, or offline with the age of the mirror. Commands: `wpilog-mcp.pinSession` and `wpilog-mcp.unpinSession` (from the status bar's quick pick, listing the pit server's recent sessions), `wpilog-mcp.syncNow`, and `wpilog-mcp.openMirrorFolder`. The settings declare an `order` after the pit server URL, and the tests pin them as they pin the others.
- When the pit server is behind a proxy that asks for a password, the credential is entered once through a command and kept in VS Code's secret storage, passed to the local server the way the TBA key is, and never written to `.mcp.json` or a settings file. The `pitServerUrl` entry in `.mcp.json` stays a URL and nothing else.

### 15. Milestones

Each leaves the project working and tested on its own.

1. **NT4 protocol and client** (§4), with the gateway's core as its test fixture.
2. **Session writer, live log, and the store** (§5, §6, §11): captures appear in the store by robot and session, and every existing tool works on an open session from memory and on a finished one from its file. Stress test on a real robot in the shop.
3. **Log puller** (§10) and **robot identity** (§8.5): the robot's logs arrive on their own, mapped to the robot by its serial; tested against a real roboRIO in the shop before it is on by default. The system logs and `search_system_logs` follow in the puller's second pass, once the file set is verified on real hardware. The listing's reading of a logged serial comes first, since it needs no pit server.
4. **Import** (§11): the command and the inbox; a USB stick's worth of logs lands by robot and session, duplicates recognized. The upload comes with the extension's pit server setting.
5. **Live tools** (§9), and the extension's pit server setting (§14).
6. **Mirror** (§11, §14): the store over HTTP, the local server's synchronization with the shared transfer logic, the extension's settings, status bar, and pins. From here the laptop analyzes offline.
7. **Metrics endpoint** (§12): the pit server's own counters and every numeric topic; the compose file and the starter dashboard in the standalone guide.
8. **Gateway** (§7) complete: dashboards and AdvantageScope pointed at the pit server.
9. **PhotonVision provider** (§8.1) and the vision tools' `camera_settings`.
10. **roboRIO system stats** (§8.2) and **followed files** (§8.4), on the puller's SSH connection; the stats provider's cost measured on a roboRIO 1 and a roboRIO 2 before it is on by default, and the program's console followed by default once its file is verified (§17).
11. **JVM provider** (§8.3): JMX polling first, then the Flight Recorder stream, each gated on what the roboRIO's JRE turns out to carry (§17).
12. **Session manifests and import matching** (§10, §11).
13. **Incremental rescan** (§6, secondary path): a growing capture read by another process.
14. **JFR file import** (§8.3) and the Grafana query endpoint (§12), each when a need shows.

### 16. Testing

- **Protocol**: the client and the gateway against each other in-process, over a loopback WebSocket, on every fixture log replayed as a robot would publish it. Message encoding is checked against hand-encoded frames taken from the protocol document.
- **Capture fidelity**: a replayed fixture captured through the client and the writer must pass the differential reader against the fixture it came from: same entries, same values, same timestamps.
- **Sessions**: reconnection with continuing timestamps resumes; with restarted timestamps begins a new session; the capture is renamed when event and match arrive.
- **Live log**: a session replayed through the writer answers every tool the same as a fresh load of the finished file (entries, sample counts, statistics, time range); a reader that starts mid-session sees a consistent prefix while the writer appends from another thread, checked under the stress test's concurrent calls; values past the hot window read from the file equal the values that were in memory.
- **Growing files** (§6, secondary path): the resumed scan equals a fresh scan.
- **Gateway**: a client with `all` receives every change; one without receives the latest per period; a `publish` from a client changes nothing upstream; the time-sync answer is robot time within the measured offset's error.
- **Puller**: the gate, the listing comparison, resume offsets, the content check, the rename rule, the throttle's pacing, and the manifest are pure logic tested against a fake remote in memory: a file that grew with matching content is fetched from its old size; a file that shrank is a new file; a larger file under a seen name whose content does not match is fetched from the start and never concatenated with the old copy, which is kept, both for a REV log named by an unset clock and for two logs of the same code that share a prefix; the DataLogManager rename is recognized by content; a transfer in progress pauses within one block of the state leaving disabled and resumes at the same offset; the manifest round-trips. The SFTP client itself is covered by an opt-in test against a real roboRIO, named by a property, like the real-log suites.
- **Live tools**: the claim checks, the conformance sweep (with capture enabled on a replayed fixture), and determinism.
- **Mirror**: against a pit server's store served in-process over the HTTP transport on loopback: a session in scope appears locally with the same hashes and the same manifests; every tool's result on the mirrored file equals its result on the store's file (the conformance sweep run on both); a growing capture grows locally through resume after the prefix hash, and a prefix that stopped matching is fetched from the start; a session renamed or moved on the pit server is followed by its id; the scope's window moving on evicts the oldest unpinned session and never a pinned one, and the cap is respected; with the pit server stopped, the listing answers from the mirror with its age and no error, and the sync resumes without a duplicate when it returns; the local server refuses to import into a mirror and reports a stray in it; the extension's settings, order, commands, and status text are pinned by its Node tests.
- **Store and import**: a fixture imported from a temporary directory lands where its content says (its robot's directory, its session by time overlap, else a session of its own, else `unassigned/`), with the original untouched; the same file imported twice is recognized by its hash and not copied again; a REV log imported after its wpilog lands in the same session and is synchronized; a file dropped in the inbox is imported and removed, and a refused one is explained in the inbox's log; a file copied by hand into a session directory is reported as unmanaged and not indexed; a session under an address is moved under the serial when a pulled log supplies it, with every path the listing showed before still answering; a store with a newer format version is refused with a message; a session directory's rename on event and match arriving. Each path is built with the path API, and the suite runs on Windows, where a pulled file being moved into its session must not be mapped at the time.
- **Followed files**: the follow logic against a fake channel in memory that emits lines, stalls, rotates the file, reconnects, and bursts: every line arrives once as a record in order with the receipt mapping; a rotation loses nothing; a burst beyond the cap is dropped with its count recorded when it ends; lines before a session are held, bounded, and written at the session's start with the clamp and the note; a replayed fixture captured with a followed console answers `search_strings` and the DS timeline's error counts as the same lines in a robot's `messages` entry do, which checks the resolver's convention for the `program_console` role.
- **System logs**: the `dmesg` append from text fixtures of two passes with overlapping output, fetching from the first new line and never duplicating one; a rotated `messages` recognized by content and not fetched again; a journal cursor round-tripped through the manifest; an `hs_err` file placed in the session whose program pid it names, and left unassigned with a note when none matches; `search_system_logs` on fixture text, with kernel lines mapped through planted `uptime_sec` samples to values worked out by hand, wall-clock lines through a planted `systemTime`, and lines without a mapping returned with `timestamp_sec` null and the reason; the severity rule shared with `search_strings`, checked on the same lines. The fixtures are text written by the tests, not copied from a robot.
- **Robot identity**: a fixture that logs a serial is listed with `robot.basis: logged`, and one that logs none has no `robot` field; a capture from a fake robot whose device reading and logged value differ is reported, with the file's value winning for the file; two sessions of two serials with byte-identical data are never matched to each other's pulled logs, and a pulled log without a serial is matched on data with the manifest saying so; a host key change under the same serial continues the pull manifest, and a new serial under the same address starts a new one; `robot_candidates` names its evidence and appears only when exactly one known robot matches.
- **System stats**: the parser against `/proc` text and `df` output captured from a real roboRIO and kept as text fixtures (not a robot log, so they may be committed); the busy fraction and the rates against values worked out by hand from two samples; the back-off from a planted slow round trip; a missing mount or a restarted program (a new pid) handled without a gap in the other entries.
- **JVM provider**: against the test JVM itself, with a JMX connector server started in-process on loopback and a Flight Recorder stream from it, so collections the test provokes arrive as entries with the fields the provider promises; the clock mapping against a planted offset, and a planted jump of years in the wall clock recorded as a note with the earlier events unchanged; a JVM without `jdk.jfr` simulated, with the provider standing down to JMX polling and saying so.
- **Metrics**: the exposition text checked line by line against the format (a `# TYPE` line per metric; label values escaped for quotes, backslashes, and newlines, which topic names can hold); every numeric topic of a replayed fixture present with its latest value, and no string topic; an array beyond the configured length excluded; the server counters against what the session registry says; and a scrape during capture under the stress test's concurrent calls.
- **Windows**: the capture file is open for writing while the server reads it; the tests cover that on Windows, where a mapped file cannot be replaced but can be appended to and read.

### 17. Open questions

- The AdvantageKit topic layout versus its log layout (§5): verify on a real capture before adding resolver conventions.
- Whether thinning should ever be on by default for known high-rate topics, or stay a configured choice. The proposal is configured only.
- Whether sessions should be grouped by event under a robot (`sessions/<EVENT>/...`) rather than by date (§11). By date is proposed: a shop session has no event, and the event is a rename away when it arrives.
- When a catalog beyond manifest files is warranted (§10).
- The SSH library for the puller (§10): the maintained JSch fork is small; Apache MINA SSHD is large but has a test server. The choice weighs the standalone install's size against testability.
- Whether the puller should also copy files the server does not read, such as CTRE's `.hoot` signal logs, so that the robot's storage holds nothing the pit server lacks. The proposal is a configured list of patterns, with `.wpilog` and `.revlog` by default, and `.jfr` added when the JVM provider is configured.
- Whether the roboRIO's JRE, which WPILib builds with `jlink` from a chosen set of modules, carries `jdk.management.agent` (needed for JMX remote) and `jdk.jfr` with `jdk.management.jfr` (needed for the Flight Recorder stream). The first thing to check on a real roboRIO; if either is absent, the provider does what the present modules allow, and the question of adding them goes to WPILib.
- The JMX port. The field's allowed port ranges do not bind the pit server, which does not run there, but a team that leaves the flags in its deployed code carries them to competition; the guide proposes a port in the team-usable range (5800 to 5810), so nothing changes between the shop and the field, and says the listener is harmless there.
- The system-stats period and budget on a roboRIO 1: measured, not chosen.
- Which files to follow by default (§8.4): the program's console log once its path is verified, and whether the kernel log should follow by default or wait for a pull. Also whether the image's `tail` is busybox's, whose `-F` and sleep interval options differ, and whether `dmesg -w` and `journalctl -f` exist on it.
- The roboRIO's system log set (§10): whether the image's logger writes `/var/log/messages` or a systemd journal, what NI keeps under `/var/local/natinst/log/` and which file holds the program's console output, where the JVM's fatal error files land, and how far the kernel's clock runs ahead of the FPGA's; each verified on a roboRIO 1 and a roboRIO 2 and written into the defaults with the image version they were checked against.
- Where on the roboRIO the serial number is read from over SSH (the HAL's source), and whether the comments field is readable from the same place; verified on a real roboRIO 1 and 2, with the file paths in the provider's metadata.
- Whether to ask WPILib to have DataLogManager log the serial number, team number, and comments at startup, as AdvantageKit does, so no team has to add the line.
- Whether `nt_value` with a topic label or a metric name derived from each topic serves Grafana better. The label keeps names exact and the cardinality is the same; derived names read better in a query editor. The proposal is the label, with a sanitized `name` label beside it if that turns out to matter.
- Whether the mirror's scope should also follow use: a session any tool on the pit server read for this laptop's client would be mirrored without a pin. It is the most transparent rule and needs the pit server to attribute calls to clients, which the HTTP sessions allow; proposed for after the explicit scope has been lived with.
- Whether the Grafana query endpoint (§12) is worth building before the data browser (IDEAS 9.2) covers the same need inside VS Code.
