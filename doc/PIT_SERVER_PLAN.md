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

1. **Captures are `.wpilog` files**, written by a pure-Java writer from WPILib's file format specification (§17). Every existing tool, the listing, the differential reader, and the reload path work on them without new code. A capture is a log like any other.
2. **Every change is captured.** The subscription asks for all value changes (`all: true`), not the latest per period. The capture is meant to stand in for a log, and a sampled stream cannot. The cost is measured and reported per topic, and a topic can be excluded or thinned by configuration, never silently.
3. **The NT4 client and server are pure Java**, written from the protocol document ([`ntcore/doc/networktables4.adoc`](https://github.com/wpilibsuite/allwpilib/blob/main/ntcore/doc/networktables4.adoc) in allwpilib). The JDK's `java.net.http.WebSocket` serves the client; Java-WebSocket supplies RFC 6455 handshake and framing on a separate socket for the gateway. The NT4 MessagePack subset is written from its specification, independently of the existing disk-cache MessagePack dependency. No native libraries enter the standalone install. Milestone 1's choices and the protocol's precedence are recorded in §17.
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
17. **Laptops without a pit server sync by pulling from each other.** Two laptops in a pit coalesce their stores the way two git clones do: each pulls from the other what it lacks, over the store's read-only HTTP door (§11), and nothing pushes, so no machine writes to another and there is nothing to coordinate. The merge needs no rule for the files, which are identified by their hashes and placed by robot serial and session as any import is, so a pull converges in any order; a field a person typed (a robot's name, a comment) is taken from the peer only where the local one is empty, and a disagreement is reported, never overwritten. Discovery is an address a person types, not a protocol.

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
| `store/MirrorSync`, `store/MirrorService` | The mirror: a laptop's synchronized copy of a pit server's store, its scope and size, the pin list, eviction |
| `sync` (existing) | Gains the transfer logic the puller and the mirror share: listing comparison, resume by content check, pacing, verification, manifest, over a transport interface (SFTP from the robot, HTTP from a store) |
| `ssh` | One connection per host, key pinning and channel ownership, shared by pulling, stats and tails |
| `capture/pull` | The log puller: the robot-state gate, a borrowed SFTP channel for the shared transfer logic, and the pull manifest |
| `mcp` | The metrics endpoint (`GET /metrics`), rendered from the latest-value table and the server's counters, beside the existing HTTP transport |
| `tools` | The live tools (existing package, a new module `LiveTools`) |

The pit server is `wpilog-mcp start <name>` for a server whose configuration enables capture. One process holds the client, the writer, the gateway, and the HTTP transport. The capture thread, the gateway's fan-out, and the MCP request pool are separate; they share the latest-value table and the session registry, both concurrent structures, and nothing else.

### 4. The NT4 client

**Connection.** WebSocket to `ws://<robot>:5810/nt/<client name>`, subprotocol `v4.1.networktables.first.wpi.edu`, falling back to `networktables.first.wpi.edu` if the server offers only that. The robot's address comes from configuration: a team number (resolved to `roboRIO-<team>-FRC.local`, then `10.<te>.<am>.2`), a USB tether (`172.22.11.2`), or an explicit host. Until a connection succeeds, each sweep tries the candidates in configured order. After that, each sweep starts with the last candidate that connected, then tries the others in configured order.

**Reconnection.** A dropped connection is retried with backoff from 1 s to 10 s, forever. The pit server is a daemon; a robot that is off for the night is the normal case, not an error.

**Keepalives.** In 4.1 both client and gateway send pings every 200 ms. Only a sent, unanswered
ping can expire after one second; later pings do not reset its deadline. Pongs are stamped on
network receipt, and replies bypass the application loops. The client's receive demand continues
while its ordered listener runs, with a bounded copied-message queue. A loop stall alone is not
evidence of a dead peer. The 4.0 time-sync timers remain the choice recorded in §17.

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

**Latest-value table.** A concurrent map from topic name to (value, server timestamp, received at, authoritative type). Following the protocol, cached values update only for greater/equal timestamps and `cached: false` suppresses retention; every frame still reaches the listener, including older timestamps. Unannounce and disconnect clear the corresponding values. A `topicsonly` subscription receives announcements, never values. Live tools read the table. The type belongs to the stored value: reading it separately from the announcement table could pair an old value with a new type after a concurrent unannounce and redeclaration.

### 5. Sessions and the capture writer

**Boundaries.** A session begins when the connection is established and the first `announce` arrives, and ends when the connection drops. A reconnection to a robot whose server timestamps continue from where they were (within a tolerance of a few seconds of the expected elapsed time) resumes the same session; one whose timestamps restarted near zero begins a new one. The robot rebooting is a new session; a Wi-Fi blip is not.

**Placement.** A capture is written as `capture.wpilog` in its session's directory in the store (§11), `robots/<serial>/sessions/<yyyy-MM-dd>/<HHmmss>Z/`, named by the session's start in UTC from the pit server's clock. Before identity is available, the robot directory is `address-<address>`. The manifest records event and match immediately when the Driver Station entries supply them. The directory rename waits until session end and reader release on every platform (§17), keeping the path stable for asynchronous manifest work, rollover opens, and mapped reads. The listing reads the facts from the manifest either way. The file's name says what it is, so a capture is never mistaken for a log pulled from the robot, and a pulled log keeps the robot's own name beside it under `robot/`.

**Entry names.** A topic is written under its own name with the `NT:` prefix DataLogManager uses for the topics it logs (`NT:/SmartDashboard/...`), so a capture of a robot running DataLogManager looks like that robot's own log to the signal resolver. Entry metadata is `{"source":"nt4","robot":"<address>"}`. Robots running AdvantageKit publish under `/AdvantageKit/...`, while their logs use `/RealOutputs/...` and similar; the resolver's conventions for captures of such robots are added only once verified against real captures, per the no-guessing rule, and until then those entries are candidates like any other.

**Records.** Each value frame becomes one data record with the server timestamp, written by the pure-Java WPILOG writer. An `announce` for a new topic starts an entry; `unannounce` finishes it. Every 250 ms the writer requests a flush on its own daemon thread, coalescing while a force is pending. Disk completion supplies `observedAtUs` and the ordered observer notification; a slow disk does not hold the NT4 loop. Close and rollover wait for a pending force before closing the file. Record writes remain on the loop, and the live index publishes complete writes without waiting for disk force. A wrong MessagePack family is dropped and counted, without disconnecting or losing other frames in its packet.

**Bounded files.** `capture.max_file_bytes` defaults to 1,073,741,824 bytes (1 GiB) and accepts 256 through 1,099,511,627,776 bytes (1 TiB). Windowed mapping permits larger individual files; rollover remains a size policy. Reserve room for every finish and roll before a record would exceed the bound: finish and close the current file, then start `capture-2.wpilog`, `capture-3.wpilog`, etc. in the same session with active entries redeclared. Each closed file has its own hash and size in `files`; `open_capture` names only the current file. Each file has its own live index and tools still read one log per call. Retained struct schemas seed a new file at the rollover's server time (the time used for its declarations), with `capture_schema_seed: true` in the entry metadata; these are explicitly marked copies, not new received changes. A bound too small for declarations, schema seeds, one record and finishes stops recording with the reason.

**Manifest work and failures.** Creation stays synchronous, including removing a resumed file's old hash before reopening it. Every other update is queued without waiting, coalesced to one pending task: changed event, match, team and close facts are immediate queue work; ordinary progress writes occur at most every five seconds. Shutdown waits at most 30 seconds in total for the writer and close update, then logs that the next startup sweep will finish recovery. A writer IOException closes the session with `end_reason` in the manifest and the reason in the server log, without disconnecting NT4. Recording stays off until a new robot clock, including across a Wi-Fi reconnection. A partial record is truncated back to its completed prefix where possible; if rollback fails, no later finish may be appended to the broken output.

**Startup recovery.** Before the client starts, sweep every session with `open_capture` through the store queue and lock. A persistent per-file writer lease proves no writer owns the file, including in another process. An unowned readable WPILOG becomes a normal captured `files` member: hash and size from the file, time range from the normal scan, `ended_at` from file modification time, and `end_reason: "server stopped while recording"`. Keep a partial final record's truncation flag; never change the bytes. An unreadable or damaged file stays in `open_capture`, with the recovery failure in `end_reason` and the server log; other sessions still recover. An active writer is left alone. This also handles the daemon's earlier process-termination deadline, crashes and power loss.

**Cost accounting.** Per topic: records, bytes, and the rate over the last minute. Reported by `list_sessions` and in the server log every five minutes while a session is open. Configuration may `exclude` topics by prefix or `thin` them to a period; a thinned topic's entry metadata records the period, so a result on it can say so.

**Context entries.** Providers write typed entries under `/Daemon/<provider>/...` (see §8): numeric system stats, followed strings, and JSON identity or structured context, timestamped in the robot's clock.

### 6. The live log: indexed as it is written

The writer and the reader are one process, so an open session is a `LogData` the writer extends, not a file the server re-reads. The `LiveLog` holds what a `LazyParsedLog` holds after its scan, built incrementally:

- **Entries** in announcement order, with the WPILOG entry id the writer assigned, the type string, and the metadata; an `unannounce` marks the entry finished but keeps it.
- **Offsets**: the byte position of every record, known at the moment the writer serializes it, appended per entry.
- **Time range**: the earliest and latest server timestamp accepted, updated per record.
- **Values**: hot values come directly from the client, with arrays and structs converted for the tools. Each entry keeps its values in an append-only array with a volatile length; a hot window (configurable, default the last 10 minutes) stays in memory, and older values are read from the file through their offsets, as a lazily parsed log reads any value, with the file mapped read-only beside the write channel and remapped as it grows.

**Readers see a consistent prefix.** A tool call captures a global publication boundary and takes each entry's length when it first touches the entry, capped at that boundary. Its values and schemas describe the same fixed prefix as its time range. The single writer publishes a record by writing it, then advancing the length and the boundary; data readers never take the writer's lock. The result's `inputs` carries the session's time range as the call saw it, so two calls a second apart can say why they differ.

**The log manager serves it directly.** The capture's path maps to the `LiveLog` while the session is open: `getOrLoad` returns the instance without a file check, since the file changes constantly by design, and the after-call check that discards a result read across a change does not apply, since the call read a fixed prefix. When the session ends, the instance stays in the cache as a finished log until it is evicted; a later load of the path reads the file like any other log, and the two must agree, which a test checks by comparing the live instance's answers with a fresh load of the finished file.

**Incremental rescan, the secondary path.** A process that is not the writer, such as a laptop's local server pointed at a capture on a shared folder, sees a file that grows. `LogScan.resume(LogScan previous, LogReader reader, Path path)` starts at the byte offset where the previous scan stopped, copies the previous entries and offsets, and appends; a file that ends inside a record (the writer mid-flush) stops the scan there, records that position as the resume point, and does not count that partial record as damage. The log manager resumes growth with a known unchanged file identity and matching header/last-complete-record anchors, and loads anything else afresh. The anchors do not prove every interior byte unchanged; ARCHITECTURE.md records the cost and limits. This is milestone 14, useful on its own and not needed by the pit server itself.

### 7. The gateway

An NT4 server on the pit server's own port, serving every topic the client has announced.
`capture.gateway` is opt-in: omission or `capture.gateway.port: 0` disables it; an empty block
defaults to port 5810. It follows the HTTP bind address on that separate port. Capture exclusion
and thinning do not filter the gateway's feed.

The listener enables address reuse and retries a busy bind forever with 1 s doubling to 30 s.
Capture and pulling start independently. `GET /health` and `list_sessions` publish its state,
port, cause while waiting and UTC time of the state change; the log records state changes only.

- **Handshake**: accepts the two subprotocols; the client name from the path is logged.
- **Announce**: on subscribe, every topic matching the subscription's prefixes is announced with the robot's type string and properties, and the gateway's own ids.
- **Values**: per client, per subscription, honoring `periodic`, `all`, `topicsonly`, and `prefix` as the robot would: with `all`, every change since the last send; without it, the latest value per topic per period. Sends are coalesced per period per client on the fan-out thread.
- **Time sync**: answered with the robot's clock (the client's offset applied to the local monotonic clock), so a gateway client's "server time" is robot time. Without that estimate, replies use local monotonic time, as an ntcore server does; the first valid estimate after each robot connection resets downstream connections so their first time-sync reply uses the new clock.
- **Publish**: a client's `publish` and `setproperties` are acknowledged as the protocol requires and otherwise ignored, with one warning per client in the server log. Nothing a gateway client sends reaches the robot.
- **Robot absent**: topics are unannounced to clients when the session ends, and announced again on the next.
- **Slow clients**: both period-pending values and socket send queues are bounded per client. Overflow drops that connection with a logged reason, without waiting on the NT4 loop, writer, store or other subscribers. The published connected count feeds `wpilog_gateway_clients`; `wpilog_nt_connected` stays robot-side.

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

The provider reads them over the shared SSH connection (one connection per host, with the puller's SFTP channel and the provider's exec channel on it), with one shell command per sample that prints `/proc/loadavg`, `/proc/stat`, `/proc/meminfo`, `/proc/uptime`, `/proc/net/dev`, the free space of the log file systems, and the robot program's `/proc/<pid>/stat` (the process found by its JAR name): a few kilobytes of text, parsed on the pit server. Unlike the puller, it runs whether the robot is enabled or not, since the enabled robot is the interesting one, and the cost of that is the one point to be careful about. The roboRIO 1's processor is small, and a sample is a shell command and a few reads, cheap but not free. So the period is configured (default 2 s); the provider measures the round trip of each sample and the processor time the robot spent between samples, reports both in the session's cost accounting beside the topics, and doubles its period, up to 30 s, while a sample's round trip exceeds a budget (default 100 ms). Stats and the default console follow are on wherever SSH is configured, so the maintainer can test them before the shop measurement. Defaults are revisited after measuring roboRIO 1 and 2. A fitting sample halves the period toward the configured base.

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
- **Flight Recorder (JFR)**, streamed over the same JMX connection with `jdk.management.jfr`'s `RemoteRecordingStream` (JDK 16 and later): the JVM's own events, timestamped by the JVM, which make the record exact where polling only suggests. The default set is small and chosen for what a robot team can act on: `jdk.GarbageCollection` (each collection with its duration; pause events are separate), `jdk.GCPhasePause`, `jdk.SafepointBegin`, `jdk.ThreadStart` and `jdk.ThreadEnd`, `jdk.ExecutionSample` at a low rate (every 20 ms) for a profile of where the program spends its time, `jdk.ObjectAllocationSample`, and `jdk.SocketRead` and `jdk.FileWrite` above a duration threshold. Each becomes entries under `/Daemon/JVM/<event>/...` with the event's fields; an execution sample becomes a string entry of the top frame and a JSON entry of the stack, from which a profiling tool later builds the usual summary (time by method, by thread). JFR is designed to cost a percent or two; the pit server measures it on a real roboRIO before the default set is settled, and the set is configured.

Two things are not the pit server's to do. **It does not instrument the robot program** (decision 12). JMX remote is off in a JVM unless its launch asks for it, and the launch is the team's: the robot project's `build.gradle` adds the arguments to the deployed program (`jvmArgs` on the deploy artifact in GradleRIO), and the standalone guide gives them (`-Dcom.sun.management.jmxremote.port=<port>`, the RMI port set to the same so one port serves, `java.rmi.server.hostname` set to the robot's address on the team's network, authentication and SSL off, which is the trust model of decision 7 and belongs on the private network only). The provider is on only when a robot's configuration names the port, and a robot that does not answer there is reported in `list_sessions`, not worked around. **It does not map clocks by guessing.** JMX samples are readings of now, stamped at receipt using the NT4 FPGA-time estimate. Every sample records JVM uptime and FPGA minus uptime, with the complete JMX poll plus NT4 round-trip bound and one millisecond of uptime quantization. Remeasure each period; a change beyond both adjacent bounds writes a note without retiming earlier samples. Runtime start time is read once per connection as JVM identity metadata, never as a clock. The earlier plan's claim that start time plus uptime tracks wall-clock corrections was wrong: OpenJDK caches start time and measures uptime monotonically. This provider does not detect the Driver Station setting the wall clock. Existing SSH stats pair kernel uptime with FPGA time, while a pulled log's `systemTime` supplies corrected wall-clock evidence; the later Flight Recorder half must establish its event clock explicitly. Every JMX entry says `"clock":"measured"` and names receipt mapped through NT4 as its timestamp basis.

**Flight Recorder clock, source review before implementation.** This design is pinned to
OpenJDK **17.0.16+8**, not a claim about the uninspected roboRIO runtime.
`RecordedEvent.getStartTime()` converts the event's start ticks to an `Instant` with the
current chunk's converter: `epoch_ns = chunk_start_epoch_ns + (event_ticks - chunk_start_ticks)
/ ticks_per_ns`. The chunk header supplies all three clock parameters; event duration is a
tick difference converted on that same scale. Neither JVM `Runtime.StartTime` nor uptime
participates in this conversion. See [RecordedEvent](https://github.com/openjdk/jdk17u/blob/jdk-17.0.16%2B8/src/jdk.jfr/share/classes/jdk/jfr/consumer/RecordedEvent.java),
[TimeConverter](https://github.com/openjdk/jdk17u/blob/jdk-17.0.16%2B8/src/jdk.jfr/share/classes/jdk/jfr/internal/consumer/TimeConverter.java)
and [ChunkHeader](https://github.com/openjdk/jdk17u/blob/jdk-17.0.16%2B8/src/jdk.jfr/share/classes/jdk/jfr/internal/consumer/ChunkHeader.java).

HotSpot pairs system UTC with ticks when it starts the next chunk. An ordinary flush updates
the header's duration, not its start anchor, so a wall-clock step inside a chunk does not
retime that chunk's events. At the next chunk a forward step can produce an epoch gap.
A backward step is subtler: `JfrChunk::nanos_now()` clamps UTC to strictly increasing values
(`last + 1 ns` until UTC catches up). Thus chunk starts do not jump backward with the OS
clock, but their advance can be far less than elapsed ticks, and converted event times can
overlap or regress across chunks. Treat either as a changed mapping; never assume one epoch
offset for the recording or claim that JFR always reports current OS wall time.
See [JfrChunk](https://github.com/openjdk/jdk17u/blob/jdk-17.0.16%2B8/src/hotspot/share/jfr/recorder/repository/jfrChunk.cpp)
and [JfrChunkWriter](https://github.com/openjdk/jdk17u/blob/jdk-17.0.16%2B8/src/hotspot/share/jfr/recorder/repository/jfrChunkWriter.cpp).

`RemoteRecordingStream` downloads the JFR bytes and parses them with `EventDirectoryStream`;
it delivers these converted `RecordedEvent` instants, without correcting a step onto the
pit clock, providing a clock-change callback, or guaranteeing global monotonic event time.
Ordered mode sorts a delivered batch by event time, not all past and future chunks together.
Its `onFlush` callback follows parsing/dispatch; it is not a remote timestamp. The download
thread can also wait one second after an empty read, and network, disk and callback backlog
add delay. **The configured flush interval is not a latency bound** and flush receipt cannot
prove an event-to-FPGA pairing within a round trip. See [RemoteRecordingStream](https://github.com/openjdk/jdk17u/blob/jdk-17.0.16%2B8/src/jdk.management.jfr/share/classes/jdk/management/jfr/RemoteRecordingStream.java),
[DownLoadThread](https://github.com/openjdk/jdk17u/blob/jdk-17.0.16%2B8/src/jdk.management.jfr/share/classes/jdk/management/jfr/DownLoadThread.java)
and [EventDirectoryStream](https://github.com/openjdk/jdk17u/blob/jdk-17.0.16%2B8/src/jdk.jfr/share/classes/jdk/jfr/internal/consumer/EventDirectoryStream.java).

The measurement plan uses **a fresh recording start through `FlightRecorderMXBean` on the
existing JMX connection**, not an arbitrary read of an old recording's start time. Create an
owned, uniquely named disk recording, bracket its `startRecording(id)` and the subsequent
`getRecordings()` read with local monotonic times and NT4 estimates, and select that exact id.
The server creates/rotates a chunk during this start; `RecordingInfo.startTime` is that
chunk-start epoch, rounded to milliseconds, not the time the attribute was read. Pair it to
FPGA time at receipt with an uncertainty of the whole bracket plus the NT4 round trip and
1 ms quantization. It measures the **JFR epoch anchor**, including any UTC clamp, rather than
an independent OS-wall-clock reading. Re-reading the same start time later creates no new
anchor. See [PlatformRecorder.start](https://github.com/openjdk/jdk17u/blob/jdk-17.0.16%2B8/src/jdk.jfr/share/classes/jdk/jfr/internal/PlatformRecorder.java)
and [RecordingInfo](https://github.com/openjdk/jdk17u/blob/jdk-17.0.16%2B8/src/jdk.management.jfr/share/classes/jdk/management/jfr/RecordingInfo.java).

Before stream code is admitted, test that bracket and its forced-rotation cost on the target
JDK. Retain downloaded chunk headers and a mapping per chunk: their tick origins/frequencies
let a measured tick-to-FPGA anchor carry across epoch changes without applying today's epoch
offset to yesterday's events. A valid anchor must be tied to the chunk created in its bracket;
another recording rotating it concurrently must be detected, not silently assigned. Test
synthetic forward and backward chunk anchors, delayed downloads, concurrent rotations and a
new NT4 session. Keep original event instants and durations; never retroactively retime
written entries. If a chunk cannot be tied to a bounded mapping, report it as unmapped and
stand the stream down rather than stamp its events with receipt time. The public event API
alone does not expose a chunk identity; access to the downloaded headers and this association
remain implementation work, gated on the shop's `jdk.jfr`/`jdk.management.jfr` module facts.
No stream or probe-recording code is added in this round.

Planned stream metadata: `source: jfr`, host, event type, JDK version, recording id,
`jvm_start_time_ms` (identity), settings identifier, and `sampled` according to the event
kind (execution/allocation sampling is not every occurrence). The timestamp basis is
`jfr_ticks_mapped_to_fpga`, with `clock: measured`, mapping id and its bound; a mapping entry
retains chunk start epoch/ticks, tick frequency, FPGA receipt and bracket duration. Each event
retains its original JFR epoch timestamp, tick timestamp, duration and receive time so delay
and conversion remain auditable. A change note reports old/new measurements and bounds,
never an inferred cause. Stream lag and dropped/unmapped counts are separate costs from the
JMX polling payload and round trip.

A JFR recording to a file on the robot (`-XX:StartFlightRecording` with a file under the log directory) is the counterpart of the robot's own log: it survives the pit server's absence, costs the network nothing during the run, and is pulled by the puller like any other log once `.jfr` is in its patterns. Reading it is `jdk.jfr.consumer.RecordingFile`, in the JDK, and importing it makes the same entries the stream would have, with the file's own timestamps and the `systemTime` mapping. That import is a later milestone, after the stream has shown which events matter.

#### 8.4 Followed files

Pulling brings the roboRIO's logs in after the fact (§10), when the robot is disabled, which is minutes or hours after the line that would have explained the moment. Some files are worth following as they are written: the robot program's console log first of all, since the capture otherwise has no console at all (DataLogManager writes followed program stdout into the robot's own `console` entry (`messages` is its explicit message API), not onto the network), and the kernel log and the system logger's file after it. The provider follows a configured list of host and file pairs in real time and writes each new line into the capture as it arrives, as a record of a string entry, so a line is in the live log within about a second of being written on the robot, and `search_strings`, the DS timeline's error counts, `can_health`'s reading of CAN failure text, and every other tool that reads text see it there without a pull.

- **Configuration**: `capture.tail: [{path, role}]` for the robot, or `[{host, user, key: "${NAME}", files: [{path, role}]}]` for another host (password also requires an environment reference). The host is the roboRIO by default, on the puller's SSH connection; it may be any host the team can reach over SSH, a vision coprocessor among them, with its own credentials (a key by preference; a password through the configuration's environment variable interpolation, never in a project file). One SSH connection per host, shared by the puller, the system stats provider, and this one; the connection manager moves from `capture/pull` to a shared place. A follow is a long-running `tail -F` on a channel of that connection, with a short sleep interval, which is the cheapest watcher a Linux host has; `-F` follows the name across a rotation. The kernel log and the journal are followed with their own tools where the image has them (`dmesg -w`, `journalctl -f`), else polled from an offset at the system stats period.
- **Entries**: `/Daemon/Tail/<host>/<role>`, a string entry, one record per line, with metadata `{"source":"tail","host":..,"path":..,"timestamp":"received"}`. The `role` is the configured name; four are known to the server and documented as its convention, so the signal resolver can use them: `program_console` (the console text role, which `search_strings` and the tools that take console text then find by convention in a capture, as they find `messages` in a robot's log), `kernel`, `syslog`, and `journal`. Anything else is a string entry like any other.
- **Timestamps**: a line is timestamped at receipt on the pit server, mapped to the robot's clock through the NT4 offset, and the metadata says so: the record's time is when the pit server heard the line, within the follow's latency, not when the robot wrote it. The line's own timestamp stays in its text, and `search_system_logs` on the pulled file (§10) gives the exact mapping where exactness matters. Lines received while no session is open (including lines received between NT4 connecting and its first announcement) are held in a bounded buffer (default 1000 lines) and written at the next session's start, mapped through the offset once it is measured, clamped at zero, with a metadata note that they preceded the session.
- **Cost**: a follow costs the robot nothing while the file is quiet. A program that prints in a tight loop is the risk, to the capture rather than the robot, so each followed file has a rate cap (default 200 lines per second); lines beyond it are dropped, counted, and the count written as one record when the burst ends, so the drop is in the record. Followed files appear in the session's cost accounting beside the topics.
- **Not a replacement for pulling.** What the provider heard is the pit server's record of the file, as the capture is of the stream; the file pulled later is the robot's. Both are kept, and the manifest says which is which. The follow may miss lines across an SSH reconnection, and a pull does not.

#### 8.5 Robot identity

Nothing in a DataLogManager log identifies the roboRIO that wrote it, and nothing the pit server sees on the network does either: the address and the hostname are the team number, which the team's competition robot and practice robot share, and the SSH host key changes whenever the roboRIO is reimaged. The one identifier that is unique to the device and survives reimaging is the roboRIO's serial number, which the HAL exposes to the robot program as `RobotController.getSerialNumber()` and which the device itself can be asked for (decision 14).

The provider reads it over the SSH connection on first contact with a robot, from the same source the HAL reads, together with the roboRIO's comments field, which teams fill with a name such as "practice bot" in the roboRIO's web dashboard. It writes both as JSON into the capture at session start, under `/Daemon/Robot/Identity` (`serial_number`, `comments`, `address`, `host_key_fingerprint`, `basis: "device"`), and the session manifest and the pull manifest carry the serial, so a capture and every file pulled over that connection are mapped to the robot before any data is read. A robot program that logs the serial itself is read the same way: AdvantageKit writes it on every boot as `/SystemStats/SerialNumber`, beside `/SystemStats/Comments`, and a DataLogManager robot does so with one line in `robotInit`, which the guide recommends as the way to make every log of a robot self-identifying for the rest of its life, wherever the file travels: log the serial number, the team number, and the comments once at startup. Where both a device reading and a logged value exist they must agree; a disagreement is reported in `list_sessions`, since it means the connection and the file are not the same robot, and the file's own value wins for the file.

The listing then shows a `robot` for every log that has one: `serial_number`, `comments` when known, and `basis` (`logged` from the file's own entry, `device` from the manifest of a connection the pit server made, `stated` when a person named the robot at import, as the explorer plan's §9 has the extension ask). A log with none has no `robot` field; for store files only, the listing adds a `robot_candidates` note from fingerprints persisted by import inspection, never by scanning a file while listing. Older manifests without those facts provide no hint. It appears only when a fingerprint (the team number from a logged entry, the entry set, the CAN device inventory from a REV log) matches one known robot and no other, naming the evidence and never promoting it. The same reading applies in the local server, not only the pit server: a logged serial is read within the listing's existing 2000-record metadata prefix wherever the file is; import inspection can find later identity.

Identity gates matching (§10): a pulled log is matched only to a session of the same serial, so a practice robot's log can never be matched to the competition robot's session however well their signals happen to line up; where either side has no serial, the match is made on data alone and the manifest says so. Identity also keys the puller's state, so a reimaged roboRIO with a new host key is the same robot with the same manifest, after the host key change is reported.

### 9. Live tools

Three tools, in a `LiveTools` module registered only when capture is enabled, and listed in the catalog under a new `Live` category. They read the latest-value table, the live log, and the session registry; they open no file. Every other tool reaches a live session through its path, as any log, and gets the live log's in-memory index and values (§6).

**`list_sessions`**: `sessions[]`, newest first, each with `path` (the capture), `started_at`, `ended_at` (null while open), `robot` (`serial_number`, `comments`, `address`, `basis`, per §8.5), `connected`, `topic_count`, `records`, `bytes`, `bytes_per_sec` (last minute, open sessions), `event`, `match` (when known), `cost[]` (the ten most expensive topics with `records` and `bytes_per_sec`), `thinned[]` and `excluded[]` from configuration, and `imports[]`: pulled logs matched to this session with the method (`by_time_overlap`, `by_correlation`) and the offset. `limits.sessions` when cut. `not_applicable` with a reason when capture is not enabled.

**`get_latest_values`**: `entries[]` (required), returns per entry `name`, `value`, `timestamp_sec` (robot clock), `age_ms` (robot now minus timestamp), `type`; unknown names are listed in `missing` and the status is `partial` when any is missing, `no_match` when all are. `not_applicable` when no session is open, with `last_session` and when it ended.

**`wait_for_change`**: `entry` (required), `timeout_ms` (default 5000, capped at 30000), returns the first value after the call began with its `timestamp_sec`, or `status: ok` with `changed: false` on timeout. One outstanding wait per entry per session; a second returns `error`.

Every result carries `inputs.session` (the capture path). Descriptions say what the values are and are not: the latest published, not measured, values; a stale `age_ms` means the topic stopped publishing, not that the robot stopped. The claim checks apply as to every tool.

### 10. Imported logs: pulling from the robot, and matching to sessions

**Pulling.** The robot writes its own logs to its storage: DataLogManager to `/home/lvuser/logs`, or to `/u/logs` when a USB drive is present; AdvantageKit to the USB drive's `/U/logs`; REVLib's status logger wherever it is configured. The puller copies those directories into the store (§11), keeping the robot's file names, which encode the time, event, and match that the listing reads: a file lands under `robots/<serial>/pulled/` while it is transferred and verified, and is moved into its session's `robot/` directory once it is matched (below). It behaves as `rsync` would, without the tool:

- **Transport**: SFTP over SSH to the roboRIO, as the `lvuser` account (no password by default; a key or a password may be configured). The host key is pinned on first contact per robot and a change is reported, since a reimaged roboRIO has a new one; a change is accepted automatically only with the default empty password. With a password or private key configured it is refused before authentication unless explicitly accepted through `capture.pull.ssh.accept_changed_host_key` or by removing the pinned fingerprint in `robot.json`; the robot's identity is its serial number (§8.5), which the reimage does not change, so a reported key change under the same serial continues the same pull manifest. The SSH client is the maintained JSch fork; its size, license and host-key policy are recorded in §17.
- **The gate**: a transfer step runs only while the robot has been disabled for at least a configured settle time (default 5 s), read from the control word the robot publishes (`/FMSInfo/FMSControlData`, the enabled bit), and while the pit server's NT4 connection is up, so the state is current. The moment the state is anything else, the step in progress finishes its current block and the transfer pauses; it resumes from where it stopped when the gate reopens. With no NT4 connection the puller does nothing: it will not guess that a robot it cannot hear is idle.
- **What to copy**: list once per pass and work through that snapshot across blocks and files; refresh after ten seconds if a pass is still running, or when it completes. Compare the remote listing (name, size, modification time) against a manifest of what has been pulled (`robots/<serial>/pull.json`: remote name, size, modification time, bytes copied, verified). A file not in the manifest is new; one whose size grew is fetched from the bytes already copied, since the logs are append-only, but only once the robot has confirmed that those bytes are the ones the puller holds (below); one whose size shrank, whose modification time went backward, or whose content does not match is a different file under the same name, and the local copy is kept under a disambiguated name while the new file is fetched from the start. The file the robot is writing now is copied like any other and grows across passes; the reload path handles the local copy's growth. When DataLogManager renames an open log once the Driver Station supplies the time and match, the old name disappears and a new name appears with the same content; the puller recognizes it by the same content check over the whole partial copy and renames the local copy rather than copying again.
- **Identity is content, never a name.** A name on the robot does not identify a file. REVLib names its log by the roboRIO's clock, which reads the same default date on every boot until the Driver Station sets it, so the same `REV_<date>_<time>.revlog` name recurs boot after boot, with the same modification time; and two boots of the same code declare the same entries in the same order, so the first tens of kilobytes of two different logs can be byte-identical. A resume that trusted the name would append one boot's log to another's and produce a file that loads with timestamps running backward in the middle. So before resuming or renaming, the puller asks the robot for the hash of exactly the bytes it already holds (`head -c <N> <file> | sha256sum` over the SSH session, which reads the entire held prefix on the robot, costing CPU and storage bandwidth while sending only the digest. The byte throttle does not pace that local work; a large hash can take minutes. Connect stays bounded at five seconds, five-second keepalives allow three missed replies, and each command has a deadline of 30 seconds plus one second per 256 KiB, rounded up) and compares it with the local copy; only a match resumes. Where command execution is unavailable, the fallback fetches the last 64 KB of the known range and compares that, where the microsecond timestamps of two boots have long since diverged.
- **Throttle**: a configured rate cap (default 1 MB/s), enforced on the reading side by pacing block reads, and one transfer at a time. The roboRIO's processor is small and SSH encryption costs it; the cap protects the robot as much as the network.
- **Verification**: after a file's size has been stable on the robot for one pass, the local copy is loaded through the normal path; a copy that loads, with its scan ending at the file's end, is marked verified in the manifest. A copy that does not is fetched again from the start, once.
- **Deletion**: never, in this version. Freeing the robot's storage is a later, opt-in action that requires a verified copy and says what it removed.
- **Reporting**: `list_sessions` gains `pulls[]` per robot: the last pass, files copied, bytes, and files waiting for the gate; the server log says when a pull starts, pauses, resumes, and completes.

**System logs.** The roboRIO keeps logs of its own, and they answer what no robot log can: the kernel reports a CAN interface going bus-off, a USB camera enumerating and vanishing, a network link flapping, a process killed for memory, and the cause of a reset; NI's daemons log the robot program's starts and stops and its console output; and a JVM that dies leaves a fatal error file. The puller copies them under the same gate, throttle, and content-identity rules as the robot's logs, in the same pass, from a configured set with these defaults, unverified until the shop inspects the NI image, and all off behind `capture.pull.system.enabled: false` (§17):

- The system logger's files under `/var/log/` (`messages` and its rotations), or the journal where the image runs systemd, read as text with `journalctl` from a cursor the manifest keeps, so each pass fetches only what is new.
- The kernel ring buffer, which is not a file: `dmesg` is run over the SSH session on each pass and its output appended to the session's `dmesg.txt`, from the first line after the last one already held, which the kernel's monotonic timestamps identify.
- NI's logs under `/var/local/natinst/log/`, the robot program's console log among them.
- The robot program's fatal error files, `hs_err_pid<pid>.log` in its working directory, which name their pid, so the session is the one whose `program/pid` (§8.2) matches.

Where each lands follows one rule: a file the robot keeps per boot or per program run belongs to that boot's session, under `robot/system/`; a file that spans boots, such as a rotated `messages`, belongs to the robot, under `robots/<serial>/system/`, and is reached from every session its time range covers. A rotation is a rename the content check recognizes, as it recognizes DataLogManager's.

System logs are kept as files, unmodified, as the robot's logs are; they are not written into the capture, since they arrive after the fact and belong to the robot's record, not the pit server's. The lines the tail provider heard as they were written (§8.4) are in the capture already, timestamped at receipt; the pulled file is the exact record. A tool reads them: `search_system_logs`, with the session's `path`, a `source` (`kernel`, `syslog`, `program`, `jvm_crash`, or all), the `pattern` and the time window `search_strings` takes, and the same line rule for severity. Each returned line carries its original timestamp and, where the mapping is known, `timestamp_sec` on the robot's clock with its basis: the kernel's seconds since boot are mapped by the `uptime_sec` samples the system stats provider pairs with FPGA time (§8.2), and wall-clock lines by `systemTime` from the session's pulled log or the measured offset (§8.3). A line whose mapping is unknown, which is every kernel line of a session the stats provider did not sample, is returned on its own clock with `timestamp_sec` null and the reason stated, never shifted by a guess; the kernel's clock starts before the FPGA's, by an interval the plan does not assume. Severity and pattern searches work either way; alignment with a tool's result needs the mapping, and the result says which lines have it.

**Matching.** Before loading candidates, compare the file's wall-clock range to each session manifest with import's generous slack: two hours, or sixteen for filename clocks without a zone. Unknown clocks, including REV's unset 1970 filename clock, cannot exclude candidates. Only nominated sessions are loaded for correlation. A pulled log of the same boot is matched to a session of the same robot (§8.5; a match across robots is never made, and a match where either side has no serial is marked as made on data alone) by the machinery REV synchronization uses: names nominate (a Driver Station entry both carry, a battery voltage, a loop count), the data decides (cross-correlation), and the offset must be near zero since both are on the FPGA clock. A match moves the pulled log into the session's directory and records it in the session's manifest (`session.json`, §11: the session's facts and every file in it with its provenance), which `list_sessions` reads and the listing shows as `session`. The pulled log is the authoritative record of its session; the capture stands in where no pulled log exists and fills the gap where the pulled log is truncated. No database: manifests are files, in keeping with the project's "nothing to operate" goal; a catalog over many seasons is a later decision, made when the files are many.

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

What it keeps is a scope, not everything: sessions of the last N days (default 14), whole events the user names, and sessions the user pins, from the extension, the local pin endpoints, or a future data browser (IDEAS 9.2), under a size cap (default 20 GB) that evicts the oldest unpinned sessions first and never a pinned one. A session that is open on the pit server is mirrored as it grows, through the resume path and the laptop's reload logic, at the mirror's sync interval (default 30 s); a session the pit server renamed or moved under a serial is followed by its id in the manifest, not its path. The mirror runs whenever the pit server answers and stands down quietly when it does not: offline, the listing still answers from the mirror and says how old it is. Deletion inside the mirror is the sync's, under the scope and the cap, and nothing else's: it is the one place in this design where a file is removed without a person asking, and only because the pit server still has it.

**Syncing between laptops.** A pit with no pit server still has two or three laptops with stores, and the logs end up split among them by whoever happened to pull the stick. Decision 17 lets them coalesce: `wpilog-mcp sync <url>` on one laptop reads the other's `GET /store/sessions`, compares the hashes in its manifests with its own, and copies what it lacks through `GET /store/files` with the transfer logic the mirror uses, verified by hash and by loading before the manifest records it. Each laptop pulls from the others in turn, in either order, and both end with the union. A session the peer knows that overlaps one of mine for the same serial is the same robot boot, so its files join my session rather than opening a second one; one that is new to me is created from the peer's manifest, whose facts were read from the same bytes by the same code, and verified by loading as an import is. A copied file keeps the provenance it had on the peer (`captured`, `pulled`, or `imported`, with the source) and gains the peer's store id and URL as where it came from, so a file imported from a stick on one laptop still says so on the other. What the merge cannot settle by content is the human part of `robot.json`: a name or a comment is taken from the peer only where mine is empty, and a disagreement is listed in the session's `conflicts` beside the identity conflicts §8.5 already records, for a person to resolve. Nothing is deleted on either side, and a store that is a mirror (decision 16) is never a sync target, since the sync owns it. A laptop that runs the capture for the day is the pit server for the day, and the others pull from it by the same command. The door is the same read-only one the mirror uses, so binding the server off loopback is the only setup, under decision 7 as today: the store door serves the configured stores and nothing else, leased directories and keys stay refused off loopback, and the puller's SSH credentials are never served. Peers are remembered by URL so the second sync is one command, and the extension offers the same as a command (§14). Discovery stays manual: mDNS would cost a dependency to save typing a port number once.

**Any server can keep a store.** The layout is not the pit server's alone: the extension offers to turn a laptop's log directory into a store and to organize the files in it the same way ([EXPLORER_PLAN.md](EXPLORER_PLAN.md) §9), so the folder a person fills by hand and the one the pit server fills read alike, and the mirror beside them too. A directory that is not a store is read as today, organized however its owner likes; the import runs in the server that owns the store, through `POST /store/import` when a daemon is running and in the `import` command itself otherwise, so a store never has two writers. A laptop pointed at a pit server's store over a shared folder reads it as any directory, since every file in it is a log in a directory.

### 12. Metrics: Prometheus and Grafana

Some of what the pit server knows is wanted on a wall, not in a conversation: is the robot on, what is the battery at, is the roboRIO's disk filling, is a capture running. Prometheus is the ordinary way to put numbers on a wall and Grafana the ordinary way to draw them, and both consume a format that costs nothing to serve. So the pit server exposes `GET /metrics` in the Prometheus text exposition format, rendered on each scrape from the latest-value table and the server's own counters, with no dependency and no state of its own.

What it carries:

- **Every numeric topic**, as one metric with the topic as a label, `nt_value{topic="/SmartDashboard/Battery Voltage"}`, so a topic keeps its exact name and a dashboard query is a label match. A boolean is 0 or 1; an array is one sample per element with an `index` label, up to a configured length (default 16, enough for the swerve module arrays and not for a vision frame); a struct is expanded by its schema into one sample per numeric field with a `field` label, through the field paths the tools use; strings are not carried, since Prometheus has no strings. Beside each, `nt_age_seconds{topic}`: how long since the topic last changed, in the robot's clock, so a dashboard shows staleness instead of a frozen number. A topic filter in configuration (`metrics.include` prefixes) narrows this where a robot publishes thousands of topics; the default is everything numeric, which Prometheus handles comfortably at a scrape per second for a few thousand series.
- **The providers' entries** (§8), which are topics in the latest-value table like any other, so the roboRIO's processor, its disk, and the JVM's heap arrive with no further work.
- **The pit server itself**: whether the NT4 connection is up and to which address, the session's topic count, records, and bytes written, the measured time offset and its round trip, the gateway's client count, the puller's bytes and files per robot and the files waiting at the gate, each provider's sample cost, and the pit server JVM's own memory and collections from the platform MBeans, under `wpilog_...` names in Prometheus's conventions (base units, the unit in the name: `wpilog_capture_bytes_total`, `wpilog_nt_time_offset_seconds`). `GET /health` stays the one-line answer; `/metrics` is the detailed one.

What it does not do. **It is a view, not the record** (decision 13). A scrape sees the latest value at the moment of the scrape, and a value that changed twice between two scrapes is seen once; Prometheus at its default 15 s, or at 1 s, is a sampled view of a 50 Hz signal. The capture remains the record of every change, and the documentation says so where a team is tempted to analyze a dashboard. Samples carry no timestamp in the exposition, since the robot's clock is not the wall clock and Prometheus drops a stale one; staleness is `nt_age_seconds`. **It runs neither Prometheus nor Grafana**, in keeping with the "nothing to operate" goal: a team that wants the wall display runs the two beside the pit server, and the standalone guide gives a compose file with Prometheus scraping the pit server and Grafana provisioned with a starter dashboard (battery, CAN, loop time, processor, disk, heap, session state), which is the whole setup. **It has no authentication**, as decision 7 says: the endpoint is on the same port as the MCP transport, and behind the same proxy if a team adds one.

A later step, when a dashboard wants the record and not its samples: the data endpoint the explorer plan specifies ([EXPLORER_PLAN.md](EXPLORER_PLAN.md) §6), which any server on the HTTP transport serves, giving an entry's values over a range, every sample or reduced to a requested number of points by the minimum, maximum, and mean per bucket, as an Arrow stream or CSV. A `json` format for Grafana's data source plugins is the addition, when someone needs full-resolution history in Grafana rather than in the explorer or a tool. Prometheus first.

### 13. Network exposure

The pit server defaults to loopback; binding it to the team's network is deliberate (`WPILOG_HTTP_BIND`, as today). HTTP serves the MCP endpoint and `GET /health` without authentication. The optional gateway follows that address on its separate port. HTTP's `Origin` check stays, as do its loopback-only control routes; uploads remain the deliberate network write surface.

The standalone guide gains a short section for teams that want more: an nginx configuration that terminates TLS and asks for a password in front of the MCP endpoint, with the pit server itself bound to loopback behind it. The gateway's NT4 port is a separate matter; a dashboard cannot present a password to it, and the protocol has no place for one, so it is exposed on the private network or not at all.

### 14. The VS Code extension

- A setting `wpilog-mcp.pitServerUrl` (order after the TBA key). It registers a second HTTP server for VS Code's agents and offers a user-scope Claude Code URL bridge named `wpilog-pit`. No project `.mcp.json` is written; the existing installed bridge handles the URL, including the Windows launcher.
- Both servers' instructions and `get_server_guide` say which server is which: the local one for files on this laptop and the mirror, the pit server for the team's sessions and for anything live. A session that is in the mirror is answered the same by either, from the same bytes, and the instructions say so, so an agent offline knows the mirror is not a lesser copy.
- **Synchronization** (§11, the mirror), transparent once turned on: `wpilog-mcp.mirror.enabled` (default off until a pit server URL is set, then on), `wpilog-mcp.mirror.folder` (default under the extension's own storage), `wpilog-mcp.mirror.days`, `wpilog-mcp.mirror.maxSizeGb`, and `wpilog-mcp.mirror.robots` to narrow to some serials. The local server does the work; the extension passes the settings and shows the state in a status bar item: synchronized, the count and size still to copy, or offline with the age of the mirror. Commands: `wpilog-mcp.pinSession` and `wpilog-mcp.unpinSession` (from the status bar's quick pick, listing the pit server's recent sessions), `wpilog-mcp.syncNow`, and `wpilog-mcp.openMirrorFolder`. The settings declare an `order` after the pit server URL, and the tests pin them as they pin the others.
- **Syncing between laptops** (§11, decision 17): a command `wpilog-mcp.syncFromLaptop` that asks for the other laptop's address (`host:port`, remembered afterwards in a quick pick), runs the local server's sync against it, and reports what was copied and what conflicted. It is the mirror's machinery pointed at a peer, so a laptop needs no pit server setting to use it, only the other laptop's server bound to the network.
- When the pit server is behind a proxy that asks for a password, the credential is entered once through a command and kept in VS Code's secret storage, passed to the local server the way the TBA key is, and never written to `.mcp.json` or a settings file. Claude's user-scope registration holds the local credential bridge's URL, with no secret in its arguments.

### 15. Milestones

Each leaves the project working and tested on its own.

1. **NT4 protocol and client** (§4), with the gateway's core as its test fixture (done: the I/O-free wire records, hand-written MessagePack subset, type mapping, and sliding-window time sync; JDK WebSocket client with ordered listeners, address failover, 4.1/4.0 negotiation, keepalives, infinite backoff, and the latest-value table; pure gateway fan-out and a loopback Java-WebSocket adapter; every generated fixture replayed with exact announcement/value/timestamp checks, schema decoding, hand-encoded frames, injected-clock retries, and planted faults. Startup, configuration, tools, capture, and the extension are unchanged. No real robot/ntcore/dashboard or native Windows run was available; the complete gateway remains milestone 8).
2. **Session writer, live log, and the store** (§5, §6, §11) (done: pure-Java WPILOG capture, clock-based session detection/resumption, policy and topic costs; HTTP configuration/start wiring and additive store manifests with immediate match facts and close-time directory rename; nonblocking coalesced manifests, bounded capture files, contained write failures, bounded shutdown and recovery of abandoned open manifests; the writer-built live index, consistent tool prefixes, a configurable hot window and mapped cold reads; every generated fixture checked by two readers and every existing log tool compared with a fresh finished-file load, plus concurrent append, eviction/resume, and planted faults. Context, identity, pulling, live tools, and the other-process rescan remain later work. The real-robot shop stress test is the user's and remains unverified).
3. **Log puller** (§10) and **robot identity** (§8.5) (done: logged identity and candidates, device context and serial placement, content-checked transfer with a fake remote, gated SFTP and store/session placement, and opt-in roboRIO transport coverage. Pulling defaults off; roboRIO 1/2 permissions, interoperability and shop stress remain unverified): the robot's logs arrive on their own, mapped to the robot by its serial; tested against a real roboRIO in the shop before it is on by default. The system-log second pass and `search_system_logs` are implemented in round 14 on configured, unverified candidates; collection remains off until explicitly enabled and the shop settles the defaults. The listing's reading of a logged serial comes first, since it needs no pit server.
4. **Import** (§11) (done: the explorer already supplied the command, inbox, inspection, grouping and duplicate recognition; generated USB batches and the capture-enabled inbox now pin those together, and the extension uploads streamed, hash-checked files through the pit server import endpoint. Automated HTTP and Node checks pass; the real VS Code picker remains manual).
5. **Live tools** (§9), and the extension's pit server setting (§14) (done: capture-only session/latest/wait tools, cached manifest facts and persisted recorder costs; HTTP fixture replay, independent values/bytes, per-session waits and injected-clock checks; the remaining proxy credential commands use SecretStorage and local leases. Real VS Code and the shop hardware checks remain manual).
6. **Store over HTTP, peer sync, and the mirror** (§11, §14) (first half done: catalog-only HTTP reads, growing prefixes and hashes; peer sync through daemon jobs or the offline store lock, content-checked resume, serial/window session union with convergent ids, provenance and human conflicts, remembered peers and recovery; generated fixtures, tool conformance and planted failures. Second half done: scoped and capped mirror with pins, growing-prefix resume, id-based moves, offline age and recorded REV alignment; local controls; extension registration, status/actions, pit Logs/follow/offline copy and remembered peer sync. Automated checks and planted faults pass; real VS Code remains the manual checklist): the store's read-only door; `wpilog-mcp sync <url>` between two laptops' stores, built first because it needs no pit server and tests with two daemons on one machine; then the mirror on the same door, with the local server's synchronization, the extension's settings, status bar, and pins. From here the laptop analyzes offline.
7. **Metrics endpoint** (§12) (done: dependency-free Prometheus text on every HTTP server, published capture/pull/time snapshots, recorded-schema field paths, bounded arrays and JVM MBeans; independent parser, fixture replay and blocked-worker checks; Compose and starter dashboard. Provider samples await their own milestones): the pit server's own counters and every numeric topic; the compose file and the starter dashboard in the standalone guide.
8. **Gateway** (§7) (done: optional capture startup on a separate port, ordered topic/property/value forwarding, session unannounces and new ids, robot-clock replies and reconnects when the reference changes, bounded per-client queues and published connection counts; real-socket slow-reader checks and an independent ntcore harness client checked against the timeline. Real dashboards and AdvantageScope against a robot remain the user's manual check).
9. **Windowed WPILOG mapping** (done: long-addressed windows of at most 1 GiB; straddling-record copies; compact ordinary-file offsets; shared cold reads; deterministic release before Windows moves; 4 KiB fixture/differential, conformance and import checks, and an opt-in generated 2.2 GB load/import/capture-rollover test). Imports, uploads, listing and captures accept files through 1 TiB.
10. **PhotonVision provider** (§8.1) and the vision tools' `camera_settings` (done: explicit hosts, v2026.3.4 export validation and MessagePack UI snapshots, receipt mapped through robot time, session stand-down with published costs/state, exact-camera/window tool context and synthetic HTTP/WebSocket/conformance checks; real coprocessor routes/version remain shop checks, §17).
11. **roboRIO system stats** (§8.2) and **followed files** (§8.4) (done: shared SSH ownership, adaptive stats with send-time clock mapping and kernel offset, bounded receipt-time tails, provider snapshots in live tools/manifests/metrics, and MINA/conformance checks; enabled by default with SSH configuration for pre-shop testing; hardware costs and NI-image commands remain shop checks, §17).
12. **JVM provider** (§8.3) (first half done: explicit `context.jvm` port/period, JDK-only polling, receipt timestamps and uptime pairing, state/cost snapshots, bounded worker ownership/backoff and in-process connector tests). Flight Recorder streaming waits for the deployed JRE module facts (§17).
13. **Session manifests and import matching** (§10, §11).
14. **Incremental rescan** (§6, secondary path) (done: copied compact indexes, declaration/metadata continuation, retryable partial tails, identity and byte-anchor checks, fresh decode caches and mapping retirement; across-growth calls still fail with an explained retry, and successful disk-backed calls name their admitted file size. Differential fixtures and the conformance sweep check the secondary path).
15. **JFR file import** (§8.3) and the Grafana query endpoint (§12), each when a need shows.

### 16. Testing

- **Harness** (step 1): `harness/run` builds a separate WPILib 2026 GradleRIO TimedRobot and runs an opt-in JUnit suite against the packaged pit server's HTTP MCP endpoint. A timeline drives headless DriverStationSim states, delayed match data and program reboots; independent expectations check every scripted topic, timestamp and value, schemas, device identity, session placement, verified near-zero-offset pulls, DataLogManager renames and the disabled gate. A test-only Apache MINA SSHD fake roboRIO provides real Ed25519 SSH/SFTP and only the puller's exact prefix-hash command. Ordinary Linux/Windows tests use it for transport, keepalive, deadline and host-key checks too. The full runner supports Linux/macOS and has a separate Linux CI job on `pit-server`; no robot data is used. Step 2 now adds the synthetic NI-like OpenSSH/JRE container under `harness/rio/`; the pinned real PhotonVision backend now completes step 2 (§17, round 23). See DEVELOPMENT.md, "The shop harness", for the remaining hardware checklist.
- **Metrics**: independent exposition grammar parser and exact fixture-function checks; scalar/boolean/array/recorded-struct expansion, filtering and missing-schema omission; recorder counts against live tools, MBean unit conversion, and scrapes while the NT4 loop and store queue are blocked. The packaged daemon pins configuration wiring; every generated replay also compares metrics against the differential reader and WPILib DynamicStruct.
- **Protocol**: the client and the gateway against each other in-process, over a loopback WebSocket, on every fixture log replayed as a robot would publish it. Message encoding is checked against hand-encoded frames taken from the protocol document.
- **Capture fidelity**: every fixture is captured through the client and writer both whole and with a small rollover bound. Differential and wpiutil readers check entries, values and timestamps across all files, checking marked schema seeds separately. Every closed file stays within its bound and has a manifest hash; the live index belongs to one file. An injected partial-write failure preserves the completed prefix.
- **Sessions**: reconnection with continuing timestamps resumes; with restarted timestamps begins a new session. A blocked store queue does not delay values or flushes, updates coalesce, unchanged facts do not trigger writes, and shutdown waits within its 30 second bound for the final manifest. Startup recovers abandoned captures, preserves active writers across processes, and reports unreadable files without hashing them; a killed process after a blocked-queue timeout is recovered by the next service start. Injected writer failures preserve the connection, record the reason, and suppress recording until a new robot clock. Match facts appear before the close-time directory rename.
- **Live log**: a session replayed through the writer answers every tool the same as a fresh load of the finished file (entries, sample counts, statistics, time range); a reader that starts mid-session sees a consistent prefix while the writer appends from another thread, checked under the stress test's concurrent calls; values past the hot window read from the file equal the values that were in memory.
- **Growing files** (§6, secondary path): the resumed scan equals a fresh scan.
- **Gateway**: a client with `all` receives every change; one without receives the latest per period; exact/prefix and topics-only subscriptions agree with the protocol; a `publish` from a client changes nothing upstream or in the capture. Real sockets check both subprotocols, property acknowledgements, an unread client's bounded queue while a second receives every value, session unannounce/reannounce and new ids, unknown-clock replies and first-sync reconnects, and robot time within the measured round trips. A blocked store cannot delay forwarding or writer flushes. Packaged startup exposes the port and separate robot/downstream metrics; the harness's independent ntcore instance records the gateway view for the same timeline oracle and HTTP checks.
- **Puller**: the gate, the listing comparison, resume offsets, the content check, the rename rule, the throttle's pacing, and the manifest are pure logic tested against a fake remote in memory: a file that grew with matching content is fetched from its old size; a file that shrank is a new file; a larger file under a seen name whose content does not match is fetched from the start and never concatenated with the old copy, which is kept, both for a REV log named by an unset clock and for two logs of the same code that share a prefix; the DataLogManager rename is recognized by content; a transfer in progress pauses within one block of the state leaving disabled and resumes at the same offset; the manifest round-trips. The SFTP client itself is covered by an opt-in test against a real roboRIO, named by a property, like the real-log suites.
- **Live tools**: the claim checks, the conformance sweep (with capture enabled on a replayed fixture), and determinism.
- **Mirror**: against a pit server's store served in-process over the HTTP transport on loopback: a session in scope appears locally with the same hashes and the same manifests; every tool's result on the mirrored file equals its result on the store's file (the conformance sweep run on both); a growing capture grows locally through resume after the prefix hash, and a prefix that stopped matching is fetched from the start; a session renamed or moved on the pit server is followed by its id; the scope's window moving on evicts the oldest unpinned session and never a pinned one, and the cap is respected; with the pit server stopped, the listing answers from the mirror with its age and no error, and the sync resumes without a duplicate when it returns; the local server refuses to import into a mirror and reports a stray in it; the extension's settings, order, commands, and status text are pinned by its Node tests.
- **Store peer sync**: two HTTP-served stores sync in both orders and again with nothing new; their hashes and same-serial overlapping session ids converge, including a peer bridging local fragments. Every generated fixture's copied bytes and log-tool answers agree, including REV association. Provenance and peer origin survive, robot text conflicts retain the local value, interrupted copies prove their prefix before resuming, failed peers retain owned partial bytes, and pending placements recover before network contact. Hash/reader mismatches and mirrors are refused; active capture flushes retain peer files. Daemon jobs exclude another sync, commands obey the cross-process lock, and remote connections cannot submit jobs. The door's catalog membership, Origin, leases, Range and current-prefix rules are exercised and planted independently of the mirror.
- **Store and import**: a fixture imported from a temporary directory lands where its content says (its robot's directory, its session by time overlap, else a session of its own, else `unassigned/`), with the original untouched; the same file imported twice is recognized by its hash and not copied again; a REV log imported after its wpilog lands in the same session and is synchronized; a file dropped in the inbox is imported and removed, and a refused one is explained in the inbox's log; a file copied by hand into a session directory is reported as unmanaged and not indexed; a session under an address is moved under the serial when a pulled log supplies it, with every path the listing showed before still answering; a store with a newer format version is refused with a message; a session directory's rename on event and match arriving. Each path is built with the path API, and the suite runs on Windows, where a pulled file being moved into its session must not be mapped at the time.
- **Followed files**: the follow logic against a fake channel in memory that emits lines, stalls, rotates the file, reconnects, and bursts: every line arrives once as a record in order with the receipt mapping; a rotation loses nothing; a burst beyond the cap is dropped with its count recorded when it ends; lines before a session are held, bounded, and written at the session's start with the clamp and the note; a replayed fixture captured with a followed console answers `search_strings` and the DS timeline's error counts as the same lines in a robot's `messages` entry do, which checks the resolver's convention for the `program_console` role.
- **System logs**: the `dmesg` append from text fixtures of two passes with overlapping output, fetching from the first new line and never duplicating one; a rotated `messages` recognized by content and not fetched again; a journal cursor round-tripped through the manifest; an `hs_err` file placed in the session whose program pid it names, and left unassigned with a note when none matches; `search_system_logs` on fixture text, with kernel lines mapped through planted `uptime_sec` samples to values worked out by hand, wall-clock lines through a planted `systemTime`, and lines without a mapping returned with `timestamp_sec` null and the reason; the severity rule shared with `search_strings`, checked on the same lines. The fixtures are text written by the tests, not copied from a robot.
- **Robot identity**: a fixture that logs a serial is listed with `robot.basis: logged`, and one that logs none has no `robot` field; a capture from a fake robot whose device reading and logged value differ is reported, with the file's value winning for the file; two sessions of two serials with byte-identical data are never matched to each other's pulled logs, and a pulled log without a serial is matched on data with the manifest saying so; a host key change under the same serial continues the pull manifest, and a new serial under the same address starts a new one; `robot_candidates` names its evidence and appears only when exactly one known robot matches.
- **System stats**: the parser against independently scripted `/proc` text and `df` output over MINA SSHD (no robot values); the busy fraction and the rates against values worked out by hand from two samples; the back-off from a planted slow round trip; a missing mount or a restarted program (a new pid) handled without a gap in the other entries.
- **JVM provider**: the test JVM's platform MBeans through an in-process loopback JMX connector; explicit collections increase cumulative counts, receipt timestamps use an injected NT4 estimate, and a mapping jump writes a note without changing earlier records. Refused-port backoff and an outside-loop deadline use injected time. Queued delivery across a session boundary is rejected; captured entries agree with the differential reader, live tools, manifests and metrics. Synthetic JVM entries join the conformance corpus. Flight Recorder streaming and missing-JFR fallback tests belong to the second half after the runtime module probe.
- **Metrics**: the exposition text checked line by line against the format (a `# TYPE` line per metric; label values escaped for quotes, backslashes, and newlines, which topic names can hold); every numeric topic of a replayed fixture present with its latest value, and no string topic; an array beyond the configured length excluded; the server counters against what the session registry says; and a scrape during capture under the stress test's concurrent calls.
- **Windows**: the capture file is open for writing while the server reads it; the tests cover that on Windows, where a mapped file cannot be replaced but can be appended to and read.
- **The shop harness**: every line of capture and pull is tested above against the project's own fixture gateway and fakes, and none of it has met ntcore's NT4 server, a real SSH server, or DataLogManager's actual files; the harness closes that gap on every push rather than on one day in the shop. Three parts, outside `./gradlew test` and opt-in like the real-log suites. (1) A scripted robot in WPILib simulation under `harness/robot/`, run headless, that follows a timeline file: Driver Station states and match info driven through `DriverStationSim` so DataLogManager performs its rename, topics that are known functions of time including struct and struct-array types so schema topics are exercised, the serial, comments, and team logged from `RobotController` (with `serialnum` in the program's environment, as on the device), and a scripted reboot that gives the capture a new clock. (2) An emulated roboRIO for the puller: Apache MINA SSHD as a test-only dependency, serving SFTP rooted at a temporary directory with the robot's logs, `/proc/<pid>/environ` and `/etc/machine-info` as files, an exec channel that accepts the exact hash command and explicitly registered provider scripts, refusing everything else, `lvuser` with an empty password, and a host key generated per run; it serves the ordinary suite too, so the SSH transport meets a real SSH implementation on Linux and Windows in every CI run. (3) A runner that starts both and a pit server from the shadow JAR with a fresh store, then a verifier that questions the server over its HTTP MCP endpoint with every expectation derived from the timeline, never read from the capture: the session under the serial with device identity, the capture's values and timestamps, the second session after the reboot, the pulled log verified and matched near zero offset, the rename followed, and the gate open only while disabled. A Linux CI job runs it on pushes, apart from the ordinary build. A synthetic container now exercises the assumed image contract (§17, round 22); the pinned real PhotonVision backend completes step 2 (§17, round 23); what no container can prove about the real device (sshd permitting an empty password, `/proc` readable by `lvuser`, `sha256sum` present, the hash's cost) stays on a short list for the shop day.
- **Replaying real logs**: With `conformanceLogDir`, milestone checks use a deterministic stratified sample; `conformanceSample=full` visits every file at zero shift for releases and recording/matching changes, retaining the eight-shift matrix on small logger/REV representatives. The selection report names runtime paths and strata under `build/reports/conformance-sample/`; no machine path enters the repository. A real log is both the input and the expected output, so the harness replays one through the pit server and compares. A replayer reads a `.wpilog` with the differential reader, announces each entry as an NT4 topic under the convention its logger used (DataLogManager's `NT:` prefix inverted, AdvantageKit's layout, struct schemas published as the protocol's schema topics before any value), and publishes the values at the file's own spacing, with the replay clock starting at the source's own time (zero shift by default), optional known shifts, and a configurable speed; it runs two ways, through the gateway's server core as an in-process fixture on every platform, and through ntcore by the harness robot's replay mode, which publishes from the file with explicit timestamps and drives the Driver Station state from the file's own entries. The checks are relational, so no value from a robot log enters the repository: the capture equals the source for every replayed entry, record for record, timestamps shifted by exactly the known shift; entry types and metadata survive; the capture's cost accounting sums to the replayed bytes; the same log placed on the emulated roboRIO is pulled and matched near zero, with additional small known shifts proving the offset is measured. The capture machine's calendar clock is injected from the file's recorded epoch or DataLogManager filename so the overlap filter still applies; without either, only pull placement is skipped with a message. A several-second shift must remain retrievable outside the replayed session with an explained refusal; two logs replayed back to back with a clock reset make two sessions and two matches, never crossed. The suite skips with a message when no log directory is given and names a failing log by path only. Beyond the checks, replay is how a team's own logs become the harness's data: real shapes (struct arrays, long holds, 1 kHz beside 50 Hz, a season's sizes), real conventions through the capture path, and the Driver Station's real sequence of states for the gate and the phases.

- **System-log pull and search:** synthetic text over MINA checks overlapping dmesg passes, boot separation, content-recognized syslog rotations, journal cursor persistence and missing-command stand-down, PID placement, shared gate/pacing and unchanged-pass command counts. A fixture session joins the schema conformance sweep; hand-computed kernel interpolation, recorded wall time, unmapped lines, severity, true totals and safe committed prefixes check `search_system_logs`.
- **System text in stores:** a counting reader checks 300 sessions over ten idle passes and invalidation on placement/session changes; fifty sessions share five indexed rotations. HTTP range/hash tests, peer sync, mirror search and growing-prefix resume use synthetic text. Missing copies name the collector; corrupt bytes cannot publish, and shared mirror bytes count once and outlive any one session using them.

### 17. Open questions

- Milestone 6 extension registration follows the explorer's current user-scope rule, replacing §14's old project-entry wording. `wpilog-pit` uses `connect --url`; only the local MCP session receives directory/key leases. Mirror configuration is runtime control, leaving home YAML intact.
- The mirror cap is decimal GB of session payload, with temporary space for manifests and a verified replacement's staging copy. Pins and copies the origin no longer holds can exceed it and are reported. Growing-prefix eviction requires a prefix-hash proof. A continuously changing prefix retries once per pass, then waits for the next interval; successful replacement reclaims private partial generations.
- The local peer picker is `GET /store/sync`, including leased writable stores and remembered URLs, excluding mirrors. It remains loopback-only; the public store door remains limited to permanent configured roots.
- Explorer follow polls once a second while visible. Inclusive timestamp tails replace their boundary to retain duplicates; console follow pages uncollapsed matches and retains the latest 500. Offline selection requires origin, session id and relative file path and labels the copy. Real VS Code interaction is still unverified; DEVELOPMENT.md keeps the checklist.

Milestone 6, second-half choices:

- Mirror pins use loopback-only `/store/mirror/pin_session` and `/unpin_session` endpoints, not assistant tools, preserving the proposed read-only assistant policy. Configuration and Sync Now use the same local control surface; the HTTP listener starts before the first pass.
- Format 1 gains additive origin identity, pins and per-session freshness. Transfer journal names start with the session ID, so directory changes do not restart copying. A pass copies a bounded catalog snapshot; open prefixes are reader-checked and remain open. Finished files additionally require the advertised hash. Recorded REV matches are reused on both copies; full matching results are retained additively, older summaries act as recorded manual offsets, and the sync cache advances to 9.
- Scope is the day window or a named whole event, narrowed by robot serial; pins override both. Size is decimal GB. Newer unpinned sessions have priority. Pins, and content the origin no longer proves it holds, are retained even if that exceeds the cap, with the reason reported. Only manifested files are evicted; no recursive directory deletion can remove a stray. Renaming a directory containing a stray is refused until the person moves the stray out.

- SSH contact history stays local to the store that made the contact; copying a robot's identity and human fields does not copy its `contacts`. A damaged configured store header is reported under `GET /store`'s `unreadable` (`path`, `reason`), alongside the readable `stores`; one damaged neighbor cannot hide the others.

Milestone 6, first-half choices:

- **Peer capture filenames:** copied captures use `peer/<sha256>/<filename>` inside the joined session. A filename can be free today but reserved for the local writer's next rollover; checking only current collisions stopped recording when that next file was opened. A small-bound test rolls both writers after the copy and preserves all hashes and generated records.
- **Route names and replay readiness:** a catalog payload can keep the filename `prefix-hash`; that operation is selected by its required `bytes` query parameter. Windows CI also timed out in the existing replay subscription wait. A controlled disconnect reproduced its stale lifetime announcement count; readiness now belongs to the current connection, with connection/topic counts on failure and unchanged wall-time guards. No NT4 production timer or fidelity expectation changed.
- **Windows bus names:** the unchanged conformance comparison caught REV bus inference treating backslashes as filename characters, so the same file under another directory appeared on another bus. Strip both path separators and use locale-independent case folding; generated path-string tests run on every platform. The sync cache advances to format 8 under the REV interpretation-change rule. No tool field or correlation tolerance changes.
- **Convergence and paths:** the smallest session id wins when same-serial windows overlap, so either sync order reaches the same ids. Closed fragments joined by a bridging interval are retained beneath `merged/`, with old-path aliases and their historical manifests. Two active capture fragments defer consolidation until closed. New sessions keep the peer id, with an id suffix in their directory name to avoid clock/name collisions; existing directories keep their names. Copying into an active session preserves peer captures and match facts through subsequent writer flushes.
- **File identity and provenance:** SHA-256 is the duplicate key across the store. Format 1 gains additive session `conflicts` and provenance `copied_from` records (`store_id`, `url`, `copied_at`). Name/comments fill only empty local fields. Peer copies keep their imported/pulled/captured basis and source; no credentials are transferred. Unassigned manifests travel too. Ordinary import verification permits a declared power-cut tail with identical bytes and its note; an undeclared truncation or a damaged file is refused. The robot puller's clean-EOF rule is unchanged.
- **Open captures:** peer sync waits for a capture's final hash and reports it as not yet copyable. The door serves its current length and exact prefix hashes now, which the mirror now uses. No incomplete prefix is labeled a verified copied file.
- **Ownership and recovery:** one sync job per local store, on its queue and under `store.lock`; `POST /store/sync` and polling require loopback connections even when the read-only door is bound to the network. Offline CLI work uses the same lock, with no fallback after a daemon request fails. Transfer journals under `.sync/peer-<id>/` retain partial and replaced bytes. Durable placement and merge receipts are finished at the next sync before network contact; a stopped peer leaves a result naming the last file and byte count. Source and target mirrors are refused.
- **Selection and pacing:** HTTP transfer adds no dependency and defaults to unlimited pacing (`rate_bytes: 0` in the job, `--rate-bytes 0` in the command). A URL's `?store=<id>` selects the peer store. The command selects the first configured store, or creates one in the first log directory, unless `--store` chooses another admitted directory. The HTTP job requires one destination or an explicit `store`. Remembered URLs are visited sequentially without deletion or automatic discovery. Real laptop-to-laptop network operation remains unverified; ordinary tests use real HTTP, child JVMs, and generated fixtures on Linux and Windows.

- **Store door selection and bounds:** format 1 gains additive `mirror` and `peers` header fields. A single store answers `GET /store` directly; multiple stores return `stores` descriptors and require `?store=<id>` on reads. Only permanent configured roots publish stores; discovery may take five seconds. Sessions use `{robot_id, path, manifest}` wrappers with relative paths and include unassigned `{path, file}` records separately so imported files without identity can travel too. `since` includes sessions ending at or after the instant. File blocks resolve their owning manifest directly, without a catalog walk. Read leases protect moves; a growing file's length is pinned for each request. HTTP uses the JDK client, with bounded response bodies and a two-minute body deadline; no dependency was added.

- **Replay selection:** one shared selector serves real-log conformance, differential and claims checks, gateway/live replay, native replay, pull and two-boot tests. The first-N path limit applies before sampling in both Gradle tasks. Representatives favor the native sampler's smallest ten-second log and complete calendar-bearing log per layout, adding size bins below 1 MiB, 1–64 MiB and at least 64 MiB, the largest input, REV siblings, an incomplete tail, calendar evidence present/absent, and one complete calendar-bearing reset pair of the same identity and layout. Logger strata use the replayer's exact header/prefix classification; each unrecognized `OTHER` file and each unreadable input stays selected as its own stratum. Directory names do not define logger strata. Input path order breaks ties; outcomes never select inputs. Missing strata are reported. Full runs cover every file at zero shift, with the signed/boundary/large-shift matrix on the smallest qualifying logger representatives and a REV companion in both modes. Files selected only for size, tail, calendar-absence or reset-pair coverage run at zero shift; this avoids eight repeated transfers of a boundary-size file while retaining the same offset-rule coverage. Run full before a release tag and after NT4 client, writer, replayer or matching changes (REV interpretation changes also bump the sync-cache format). Inspection retains only facts after closing each independent reader; no robot values or local paths are committed.
- **Replay clocks and placement:** the default shift is zero. Additional small known shifts (40, 120, 200 ms and a negative case) check measured offsets; the 250 ms automatic-placement limit is retained. Large shifts belong in correlator tests and a refusal run per logger kind, never a test-only bypass of the placement rule. The writer and store receive an injected calendar `Clock`: `systemTime` first, AdvantageKit's `/SystemStats/EpochTimeMicros` for its recorded epoch, else a dated `FRC_yyyyMMdd_HHmmss` name interpreted as UTC with that assumption recorded. A file with none is still replayed; its placement check reports unavailable calendar evidence. The existing overlap filter remains enabled. In the local set, DataLogManager and other layouts used `systemTime` when present and the dated filename otherwise; AdvantageKit used `EpochTimeMicros`. Each kind also has an explicit unavailable-clock outcome rather than borrowing the host date.
- **Metadata on replay:** NT4 has properties, not a WPILOG metadata field. Replay carries the source string unchanged in the custom `wpilog_metadata` property, and capture preserves all properties under `nt4_properties`, beside its own provenance. Patches become Set Metadata records; acknowledgements cover metadata as well as values, including a final patch with no later value. NT4 text controls have no timestamps, so original Start/Finish/Set Metadata timestamps cannot be transported; value timestamps are compared exactly. Repeated declarations of the same name and type share one topic and preserve metadata changes. The current replayer refuses a name redeclared with another type; preserving those declaration lifetimes is not implemented. That is a replayer limitation, not a claim that NT4 cannot change a topic's type after it is unpublished. Invalid UTF-8 strings, malformed typed payloads, and bytes after an incomplete record cannot be made into lossless NT4 values. Replay reports an incomplete tail and compares its readable prefix; the original incomplete file still fails the puller's clean-EOF verification after one retry. Empty or zero-filled files bearing a WPILOG suffix are rejected, never counted as successful replays. One external source also contains a string record with invalid UTF-8, confirmed independently with WPILib's raw record and a strict decoder. The file is explicitly reported as not losslessly replayable, with the invalid-record count; it is not counted as a successful capture and its bytes are never replaced or silently dropped. Native replay checks text before announcing.
- **Colliding replay names:** a file can contain both `NT:/x` and `/x`; inverting the recording prefix maps both to one NT4 topic. All entries in such a collision receive unique `/__wpilog_replay__/<ordinal>` names, with underscores added to the namespace if it already occurs. The `wpilog_entry_name` property always retains the exact original name. Unique names keep the logger convention. Reports count escaped entries; distinct streams are never silently combined. This is a replay convention, not a new robot logging convention.
- **Replay Driver Station state:** both the `DS:` DataLogManager entries and AdvantageKit's `/DriverStation/` entries drive simulation, as do the FMS control bits. WPILib's native packet notification unconditionally sets DS attachment true; replay restores the recorded attachment field afterwards. An independently computed state-transition digest checks every update, including event and match fields. Numeric match fields use their declared integer, float or double type; reading double bits as an integer had produced the wrong simulated match. Native two-boot checks allow real ping replies between injected 200 ms heartbeat ticks, so advancing the test clock does not manufacture an NT4 4.1 timeout.
- **Replay across rollover:** capture fidelity is checked across all files. The REV offset invariant compares the whole source with a test-only `LogData` view of the complete captured record set; joining bytes into one file would needlessly duplicate a boundary-size source. Per-file HTTP results remain separate, with their own input windows. Comparing each small part to the whole source had reported different alignments as mismatches even when every record agreed; a generated rollover fixture pins the number of records compared.
- **REV replay coverage:** every direct REV sibling is copied, including names the normal automatic discovery does not nominate. Its source and capture alignments use the same normal parser and correlator. An incomplete REV file is retried once and refused for placement, with that outcome reported separately; its byte-identical copied prefix can still be compared through the ordinary reader. A refusal is not reported as a verified transfer. Comparisons retain paths and measured results, releasing decoded buses from the test's sync caches after each comparison; retaining every source and rolled-file copy exhausted the 4 GiB suite heap. A generated multi-bus check counts retained decoded copies rather than relying on a heap-size-dependent failure.

Shop harness step 1 decisions:

- **Reconnect investigation remains open:** the reported once-only failed-capture reconnect assertion has not reproduced in 400 repetitions or the full local suite. The regression now records the exact server/receipt sync samples, and a controlled WebSocket pins old-socket callbacks arriving after the next connection opens. Plants that remove the callback guard, clear the failed state on connect, or ignore a reboot fail. No production continuity change is justified by that evidence alone; another occurrence needs its trace before its cause can be called fixed.
- **Second load-sensitive socket failure:** one full `./gradlew build` in `e998df1..98173d1` reported a single failure in `org.triplehelix.wpilogmcp.nt4.client.ClientTest` with 2,787 other tests passing. The method and assertion were lost when targeted runs replaced the XML. The class then passed three times alone and a second full build passed. Its cause remains unknown; no timeout or protocol rule has been changed. Harness CI through `98173d1` is green. The CI harness job now runs the ordinary suite first and uploads `build/test-results/test` with its always-retained evidence so the next occurrence preserves the assertion. The job on `9485352` passed both the ordinary suite and simulation without recapturing this failure.
- **Mirror review:** invalid recorded alignments stay as failed companions, with a manifest-naming reason in the listing and REV results; they never authorize fresh correlation. Sync cache format 10 retires prior interpretation. Every mirror write route is tested from a nonloopback connection, and a growing prefix without an agreeing journal cannot be evicted. A terminal used without VS Code should configure the same `mirror` block in home YAML for permanent folder admission; bridge `--logdir` grants temporary read access.
- **Simulation launch:** [GradleRIO 2026.2.1](https://plugins.gradle.org/plugin/edu.wpi.first.GradleRIO/2026.2.1) builds a separate Java 17 project with WPILib 2026.2.2. `prepareHarness` resolves Java libraries and extracts desktop JNI libraries; the runner launches the JAR directly with their library path, without GUI or HAL simulation extensions. Gradle caches both Java and native downloads. The [simulation clock](https://github.com/wpilibsuite/allwpilib/blob/v2026.2.2/hal/src/main/native/sim/MockHooks.cpp) is paused, reset per process and stepped by 20 ms after capture subscribes. TimedRobot still drives periodic calls. Linux/macOS launch the complete harness; real-SSH tests run on Windows too.
- **Script and oracle:** timeline microseconds control DriverStationSim and explicitly stamped telemetry. `serialnum` supplies the fallback when RobotController's simulated serial is empty; team and comments come through RoboRioSim. Nonperiodic counter resets make correlation distinguish a boot and offset; a ramp alone correlates equally well at many offsets. The independent reader and HTTP checks derive expected values from the timeline, never from captured samples. Logs stop before process exit so the disabled gate can finish the pull while NT4 is connected. This exercises [DataLogManager's actual rename](https://github.com/wpilibsuite/allwpilib/blob/v2026.2.2/wpilibj/src/main/java/edu/wpi/first/wpilibj/DataLogManager.java), including its FMS packet threshold.
- **Fake SSH:** test-only `org.apache.sshd:sshd-core:2.20.0` and `sshd-sftp:2.20.0` (transitive `sshd-common`, Apache-2.0), plus `net.i2p.crypto:eddsa:0.3.0` ([CC0-1.0](https://github.com/str4d/ed25519-java/blob/v0.3.0/LICENSE.txt)) for MINA 2.x's Ed25519 provider. [MINA 2.20.0](https://mina.apache.org/sshd-project/download_2.20.0.html) is a maintained release. A new host key per instance, rooted temporary SFTP and an exact read-only exec grammar exercise JSch without installing sshd or running a shell. None is shipped in the server. `capture.pull.ssh.port` defaults to 22; an unprivileged port lets the fixture run as an ordinary user.
- **Process exit:** after DataLogManager stops and the disabled pull has time to finish, the timeline marker halts the robot process (exit 75 for reboot, 0 for the last boot). This deliberately skips JVM/native shutdown hooks: desktop NT/DS threads still reference WPILib globals, and C++ static destruction underneath them can abort on macOS. The runner checks the marker and exit code and restarts a fresh process.
- **Port isolation:** NTCore starts on the selected numeric loopback port before RobotBase is constructed. Its later default `startServer()` is a no-op ([2026.2.2 InstanceImpl](https://github.com/wpilibsuite/allwpilib/blob/v2026.2.2/ntcore/src/main/native/cpp/InstanceImpl.cpp)); the robot therefore never briefly opens the standard dashboard ports. The runner checks NTCore's listener report.
- **Scope:** this does not establish actual sshd empty-password policy, `lvuser`'s `/proc` readability, `sha256sum` availability or hashing cost. Those four checks, plus sustained robot/radio load, remain for the shop day; pulling stays off by default. NI-image emulation and PhotonVision are step 2.

Milestone 3 decisions:

- **Hash deadline and robot cost:** connect/channel-open remain five seconds. [JSch keepalives](https://github.com/mwiede/jsch/blob/jsch-2.28.7/src/main/java/com/jcraft/jsch/Session.java) run every five seconds with three missed replies allowed, independently of command output. A hash gets 30 seconds plus one second per 256 KiB of held content (rounded up); timeout closes its channel, completion cancels the timer. This deliberately conservative allowance replaces an idle socket timeout that killed large hashes. Whole-prefix SHA-256 costs a full read and CPU work on the robot, unpaced by the network byte limit; shop measurements remain required.

- **SSH transport:** [maintained JSch 2.28.7](https://github.com/mwiede/jsch/releases/tag/jsch-2.28.7), 714,740 bytes for its JAR, no mandatory runtime dependencies. It serves exec and SFTP together, with JDK 17 Ed25519 and RSA SHA-2 support. Main code and JZlib use BSD-3-Clause; bundled jBCrypt uses ISC. Notices ship in the JAR. The fat JAR retains `Multi-Release: true` for the JDK-specific providers. Production imports are confined to `ssh/JschConnection` (moved from the pull adapter in milestone 11). First contact trusts the fingerprint. A changed key is reported before authentication and may continue automatically only when authentication sends no secret (the default empty password). A configured password or key requires `capture.pull.ssh.accept_changed_host_key: true` or removal of the pinned fingerprint in `robot.json`; otherwise the connection is refused before authentication. The subsequent device serial decides continuity. This is trust on the team's network, not a cryptographic proof that a changed key is the old device. Password or unencrypted private-key authentication is configurable; default `lvuser` and empty password. Hardware interoperability remains an opt-in shop test.
- **Pull scheduling:** pulling defaults off. NT4 connection plus the enabled bit of `/FMSInfo/FMSControlData` clear continuously for five seconds opens the gate; unknown/invalid state closes it. A separate daemon owns SSH and the synchronous local-store interface; the NT4 listener only publishes state and receives queued identity. One 64 KiB block per step, default 1,000,000 bytes/s, 250 ms paused polling, three-second idle/error retry. One recursive listing per pass, with a ten-second refresh for a long pass, prevents per-block SFTP listings. Shutdown includes asynchronous SSH close within the capture's existing deadline. Configured paths are recursive, missing USB directories are allowed, links skipped; only WPILOG and REV are pulled in this pass.
- **Session matching and placement:** a strong unique correlation must be within 250 ms of zero, allowing several publication cycles but refusing a seconds-scale clock shift. Known serials must agree before correlation. Manifest time overlap nominates candidates before any load, using import's two-hour slack or sixteen hours for filename clocks without a zone. Unknown clocks (including an unset REV 1970 filename) retain candidates; strong data evidence is still required. WPILOG names normalize `NT:` and a leading slash and nominate unique scalar numeric entries; REV uses its existing signal matcher. Captures anchor their session; already matched files do not chain offsets. Without a capture, an unmatched WPILOG can anchor a session. A missing logged serial on either side records `data_alone`; otherwise `serial_and_data`. No match creates its own session with the imported clock evidence or modification-time basis. An active capture has no final hash yet. Each pulled file records the source device serial additively for transfer ownership, even if its own logged serial places it under another robot. Store format stays 1. Growth removes the obsolete session hash and returns the file to staging, retaining path aliases. Invalid Windows basenames get a portable generated name; provenance retains the exact remote name. Changed size/mtime invalidates a partial prefix proof, and each new contact rechecks completed files even if size and mtime repeat.

- **Transfer steps:** 64 KiB blocks, one caller at a time, an injected monotonic clock, and pacing of comparison reads as well as data. A pause discards the prefix proof before resuming. The fallback checks the known range's tail and cannot prove its earlier bytes; exec uses the whole held-prefix SHA-256. Pull manifest format 1 preserves retired generations and a single verification retry. Stable size/mtime for a listing pass precedes clean-EOF verification through the ordinary readers; strict native REV verification accompanies sync cache format 6. No remote deletion API exists.

- **Device sources:** pinned to [WPILib 2026.2.2 roboRIO HAL.cpp](https://github.com/wpilibsuite/allwpilib/blob/v2026.2.2/hal/src/main/native/athena/HAL.cpp). `HAL_GetSerialNumber` reads the process environment's `serialnum`; SSH reads readable `/proc/[0-9]*/environ` files because an SSH login need not inherit the robot program's environment. Missing or conflicting serials refuse device identification. `HAL_GetComments` reads `/etc/machine-info`, the quoted `PRETTY_HOSTNAME`, C-unescaped and bounded to 64 UTF-8 bytes. Hardware permissions and these reads remain shop checks.
- **Identity placement:** manifest additions are backward compatible (format 1): address-to-serial lookup, contact key history, device evidence, and logged/device conflicts. Discovery during recording writes the context entry in the current file and queues its identity fact without waiting. Promotion joins the session close update after the writer and mapped readers release the directory, like the event rename; it never closes and reopens a file solely to promote identity. A busy reader defers the move until a later close or contact; old paths resolve through store moves. Closed address history moves on first contact; subsequent sessions use the serial immediately.
- **Candidate evidence:** `robot_candidates` requires an exact logged team number, sorted entry name/type set, or sorted REV CAN id/device-type inventory to identify exactly one known serial. Only store files participate, from `robot_fingerprint` facts saved during import inspection (an additive format-1 field); older manifests without facts supply no hint. The listing's serial/comments read stays within 2000 records. Conflicting unique hints are omitted; candidates never become identity. The file's `/SystemStats/SerialNumber` wins over device evidence, with disagreements in the manifest and server log until `list_sessions` exists.

Milestone 2 decisions:

- **Rollover and windowed mapping:** the bound includes declarations, values, schema seeds and reserved finishes. Default 1 GiB; configuration accepts 256 through 1,099,511,627,776 bytes (1 TiB). Rollover remains useful for manageable capture files, but no longer works around a 2 GiB reader limit. The byte source owns windows of at most 1 GiB, copies a crossing record, and releases every mapping before its read lease. WPILib decodes complete records without a fork of its reader. Compact int-backed indexes promote only where addresses require longs. The maximum bounds the default window table to 1024 mappings; it does not promise that decoding every value fits the heap. No format or sync-cache change is needed: stored bytes and decoded answers are unchanged. The REV iterator retains WPILib's existing 16-byte tail guard; the WPILOG scan continues to use each record's actual bound, including shorter complete final records.
- **Manifest queue and directory lifetime:** only creation waits on the store queue; all later facts are immutable snapshots coalesced to one pending update. Ordinary progress is limited to once per five seconds, while changed facts and close bypass that interval. A resumed create waits for the preceding close and removes the old verified hash before append. Cosmetic directory renames now wait until close on all platforms, because moving the directory concurrently with rollover file creation or mapping growth is unsafe even where an open-file rename is permitted. Match facts reach the listing through the manifest independently of that rename.
- **Write failures:** `end_reason` is an additive session field, with no format-version bump. A writer error keeps the NT4 connection and pauses recording until clock evidence identifies another session. The final manifest is the shutdown barrier, bounded to 30 seconds; no steady-state NT4 callback waits for it. A timeout, interruption or failure is logged for the next startup sweep.
- **Abandoned captures:** recovery is queued after HTTP is listening and runs before NT4 starts, on the store queue. A slow scan/hash cannot delay the health endpoint; the completion starts the client. Writer and recovery coordinate with persistent `.wpilog.writer.lock` sidecars (prefixed with a dot), OS file locks and an in-process claim. The separate file avoids mapped-reader channel closes releasing writer ownership on some systems; lock files are never deleted or listed as unmanaged. Process death releases ownership without trusting a stale PID. Completed files and session facts survive recovery unchanged. A partial last record is readable; structural damage or an unreadable header is left open with a recovery reason. No manifest format change is needed.
- **Self-contained rollover files:** active entries are redeclared. A retained struct schema is copied once into the new file, marked by `capture_schema_seed` in its entry metadata, at the rollover's server time, matching its entry declarations. The copied boot-time timestamp no longer stretches a rolled file's range; the listing, live inputs and manifest use that file's own first data or seed record. These schema seeds are declaration overhead for cost accounting; the fidelity check verifies their bytes/timestamps separately and counts each received frame exactly once across all files.

- **Prefix boundary and lifetime:** a call captures a global record sequence and time range, then bounds each first-touched entry by that sequence. This strengthens the per-entry rule so late entry reads cannot exceed the time range in `inputs`. Single-log results use `inputs.session_time_range`; comparisons use `inputs.session_time_ranges` by path. Active captures are pinned, finished ones enter the normal cache, and the writer keeps its last index for a possible clock-continuous resume. Data reads use atomic mapping references; short lifetime transitions guard eviction/resumption. Cold reads share the windowed byte source and its 1 TiB file bound.
- **Hot expiry:** the window follows the greatest accepted value timestamp or subsequent time-sync clock. Time sync expires idle topics too, without extending the data time range. Expiry runs on the 250 ms flush tick, rather than on each append or sync, to cap growth remaps at four per second even with a zero hot window. A complete mapped record is available before its hot value is discarded. Tests count actual mapped decodes, so retaining hot values cannot pass as a cold read. No second scan or cross-process incremental index was added.

- **Windows rename amendment:** Windows forbids moving a directory while its capture is open or mapped. Facts are queued immediately; the asynchronous-update review extends the close-time rename to all platforms to keep active paths stable. Listing facts never wait for a cosmetic name.
- **Store compatibility:** `open_capture` is an additive session field for an unfinished file. Close replaces it with the existing hashed, verified `files` record, so format version 1 and its finished-file validation remain intact. Older readers can show the growing file as unmanaged. UTC names colliding within one second get `_2`, `_3`, etc.; address characters unsafe in a path are percent-encoded. The placeholder robot basis is `address`, not `stated`: only the connection endpoint is known. No serial identity is inferred.
- **Configuration:** `capture.robot` selects team, USB, or an explicit host (optional NT4 port); `store`, `period_sec`, `exclude`, `thin`, `hot_window_sec`, and `max_file_bytes` are documented in the standalone guide. Blocks inherit as a whole. Capture requires HTTP and no idle exit: it must keep recording without tool clients. The store is added to the server's log directories.

- **Pure-Java capture writer:** WPILib's DataLog writer uses JNI, while this install deliberately has no native library. `capture/WpilogOutput` therefore writes ordinary WPILOG 1.0 from the [published format](https://github.com/wpilibsuite/allwpilib/blob/main/datalog/doc/datalog.adoc). The fixture writer remains independent. Byte examples, wpiutil's pure-Java DataLogReader, and the differential reader check the output.
- **Session clock:** the initial time-sync reply decides continuity, since a retained topic value may predate the connection by minutes. A nondecreasing server clock within five seconds of the elapsed client clock resumes; a backward clock or a larger discrepancy begins a new session. The five-second tolerance permits connection/RTT jitter. A reboot wholly inside the tolerance without an observable clock rollback cannot be distinguished by timestamps alone. This is continuity evidence, not device identity.
- **Capture accounting and policy:** per-topic bytes count complete data records, excluding declaration/finish overhead. Rates use sixty one-second receipt-time buckets and a fixed sixty-second denominator. Exclusion wins over thinning; the longest matching thinning prefix wins. A thinned entry records `period_sec` in metadata. Reports remain server-log-only until the live-tools milestone.

Milestone 1 decisions:

- **Server framing:** Java-WebSocket 1.6.0 ([upstream](https://github.com/TooTallNate/Java-WebSocket), MIT), a 140,686-byte JAR with only the already-present SLF4J runtime dependency. It is a small maintained standalone server, avoiding a full HTTP stack or a new RFC 6455 implementation. Imports are confined to `nt4/server`; its MIT notice is bundled under a unique resource path. It owns a separate socket, not the MCP HTTP server. The loopback-only adapter is used only as a fixture in this milestone.
- **Specification precedence:** the published NT4 4.1 document requires timestamp-ordered retained values, `cached: false`, deduplication of overlapping subscriptions, hidden `$` topics for the empty prefix, and 4.1 keepalives (no WebSocket pings to 4.0). These override or expand the abbreviated §4/§7 descriptions. Subprotocol fallback uses standard negotiation, offering 4.1 first and 4.0 second in one handshake. Initial RTT precedes subscription. The codec writes minimal unsigned positive integers; the specification's example uses an equally valid signed 32-bit timestamp, which the decoder also accepts.
- **Clock/retry choices:** a 30-second time-sync window, newest minimum-RTT sample winning ties; 1, 2, 4, 8, then 10-second address-sweep backoff forever, reset on connection. Client 4.1 pings run every 200 ms with a one-second pong timeout; 4.0 uses the three-second time exchanges with a ten-second response timeout. Those 4.0 timers are an implementation choice: the protocol document recommends a one-second exchange and a three-second timeout for aliveness checking. The last successful address is remembered across disconnects; old connection IDs and estimates are discarded. These constants are internal, with no new configuration surface.
- **Read-only replies:** NT4 has no write-refusal message. A publish gets an announce with its pubuid and the actual upstream type/properties when present; an absent topic gets a private per-client sink, never a shared topic. Values are ignored. Setproperties replies acknowledge the unchanged actual values for the requested keys (null when absent); they never echo a change that did not happen. One warning per connection reports ignored writes. Full metadata topics and production dashboard interoperability remain for milestone 8.
- **Limits and batching:** 16 MiB per assembled wire message, 64 nested MessagePack levels, and one million decoded objects; extension formats are outside the NT4 subset. The gateway sweeps at the minimum period of a client's value subscriptions, permitted by the protocol, deduplicating overlap and preserving arrival order. Binary output is coalesced/fragmented near the MTU. Replay exists only in tests and uses the pure-Java writer's synthetic fixture corpus; no robot data was added.

- **Replay topic names:** DataLogManager entries beginning `NT:` lose that prefix; other entries keep their names. AdvantageKit's [26.0.2 NT4Publisher](https://github.com/Mechanical-Advantage/AdvantageKit/blob/v26.0.2/akit/src/main/java/org/littletonrobotics/junction/networktables/NT4Publisher.java) adds `/AdvantageKit` to the same full keys its WPILOGWriter writes, retaining `RealOutputs`. Capture adds `NT:` as usual. Scoped schema entries keep their own topic names and additionally seed the protocol's root `/.schema/` topic, marked `wpilog_replay_schema_alias`; they precede data of the type. The WPILOG matcher normalizes the published AdvantageKit prefix when nominating pairs; data still decides. Replay does not establish a new resolver convention for every robot library.
- Whether thinning should ever be on by default for known high-rate topics, or stay a configured choice. The proposal is configured only.
- Whether sessions should be grouped by event under a robot (`sessions/<EVENT>/...`) rather than by date (§11). By date is proposed: a shop session has no event, and the event is a rename away when it arrives.
- When a catalog beyond manifest files is warranted (§10).
- Whether the puller should also copy files the server does not read, such as CTRE's `.hoot` signal logs, so that the robot's storage holds nothing the pit server lacks. The proposal is a configured list of patterns, with `.wpilog` and `.revlog` by default, and `.jfr` added when the JVM provider is configured.
- Whether the roboRIO's JRE, which WPILib builds with `jlink` from a chosen set of modules, carries `jdk.management.agent` (needed for JMX remote) and `jdk.jfr` with `jdk.management.jfr` (needed for the Flight Recorder stream). The first thing to check on a real roboRIO; if either is absent, the provider does what the present modules allow, and the question of adding them goes to WPILib.
- The JMX port. The field's allowed port ranges do not bind the pit server, which does not run there, but a team that leaves the flags in its deployed code carries them to competition; the guide proposes a port in the team-usable range (5800 to 5810), so nothing changes between the shop and the field, and says the listener is harmless there.
- Measure the enabled-by-default system-stats period and budget on roboRIO 1 and 2 at the shop; the provisional defaults are 2 s and 100 ms.
- The default follow (§8.4) is the NI program console path chosen below; kernel/journal are configured explicitly. Verify behavior on the installed image. Also whether the image's `tail` is busybox's, whose `-F` and sleep interval options differ, and whether `dmesg -w` and `journalctl -f` exist on it.
- The roboRIO's system log set (§10): whether the image's logger writes `/var/log/messages` or a systemd journal, what NI keeps under `/var/local/natinst/log/` and which file holds the program's console output, where the JVM's fatal error files land, and how far the kernel's clock runs ahead of the FPGA's; each verified on a roboRIO 1 and a roboRIO 2 and written into the defaults with the image version they were checked against.
- Verify access to the recorded HAL serial and comments sources over SSH on real roboRIO 1 and 2; the paths are decided above, their image/account permissions remain unverified.
- Whether to ask WPILib to have DataLogManager log the serial number, team number, and comments at startup, as AdvantageKit does, so no team has to add the line.
- Whether `nt_value` with a topic label or a metric name derived from each topic serves Grafana better. The label keeps names exact and the cardinality is the same; derived names read better in a query editor. The proposal is the label, with a sanitized `name` label beside it if that turns out to matter.
- Whether the mirror's scope should also follow use: a session any tool on the pit server read for this laptop's client would be mirrored without a pin. It is the most transparent rule and needs the pit server to attribute calls to clients, which the HTTP sessions allow; proposed for after the explicit scope has been lived with.
- Whether the Grafana query endpoint (§12) is worth building before the data browser (IDEAS 9.2) covers the same need inside VS Code.

#### Milestone 4: laptop uploads and import completion

The existing command and inbox remain the import pipeline. Uploads use `POST /store/import`
with one `application/octet-stream` file, exact Content-Length and source SHA-256, a store id
from the configured catalog door, and a portable basename. A hidden transfer lock protects
reception outside the store queue; the importer consumes only the server's temporary copy.
The laptop original remains in place and provenance says `upload:<filename>`. The 1 TiB file
limit is checked before reception. JSON imports naming server paths and assignments require
loopback; byte uploads and their polling work over the pit URL with the Origin gate. There is
no new authentication scheme: teams using a proxy protect the upload route first.
The extension streams files sequentially, reports the existing result statuses, and does not
repeat an uncertain POST. Retrying by user choice finds an already admitted hash.

During milestone 4 validation another real-socket timeout was preserved: `GatewaySocketTest.realClientsSeeEveryChangeOrLatestAndWritesDoNotLeak` waited five seconds for the sampled 4.0 client's publish announcement at line 65. The isolated class then passed. Its XML was saved before subsequent runs; there was no assertion of wrong values and no cause established. No timeout was widened. The ordinary-test XML artifact remains the evidence source for another CI occurrence.

The milestone 4 harness job caught an upload refused by a competing in-process store lock.
Its retained ordinary-test XML identified inbox transfer cleanup, which used the lock outside
the store queue. Cleanup now queues behind imports, at most one pending job per inbox, and
polling returns while it waits. A blocked-queue test failed on the previous implementation
and passes after serialization; no sleep or wider retry was added to hide the race.


Milestone 5 choices:

- Startup inventory publication skips an invalid `session.json`, logs its path and reason,
  and publishes none of that session's partial facts. Previously recovery skipped the bad
  file but the following strict inventory scan stopped NT4 startup. The store door and
  import catalog still require valid manifests; this exception is only for live status.
- Store filenames are checked with the native path API, preserving Windows' Unicode
  support. Unrepresentable or malformed Unicode names get an explained encoding refusal,
  never Java's path exception. Opening the first store warns once when `sun.jnu.encoding`
  is not UTF-8; services must set a UTF-8 locale before JVM startup. A dedicated Linux
  CI run unsets LANG and LC_ALL and preserves its own XML beside the normal test results.

- Live tools are registered only on capture servers. The catalog filters by that registry;
  both server locations keep their existing initialize/guide explanation. Tools read published
  recorder snapshots and cached manifests, never store files. Capture counts persist additively
  as `capture_stats` in format 1; older manifests and crash-recovered sessions have unknown
  counts. Recovery clears the last queued summary because it can predate durable records. Counts include value
  record headers, omit context/control records and copied schema seeds, span rollover files and refresh every 250 ms.
  Rates use the full preceding minute even at startup, and are null after close. Cost ties sort
  by topic name. Imports can join a session that still holds only an open capture.
- Latest values retain the authoritative type in the same immutable publication as the payload and timestamp; the announcement table can advance independently during a query. A deterministic snapshot regression pins that redeclaration race. Multiple entries remain independent latest publications, not a simultaneous sample. Latest values accept NT4 names and their `NT:` aliases. Binary/struct values stay raw signed
  byte arrays; ordinary tools decode structs. Ages use robot time, can be negative for future
  publisher timestamps, and are null without sync. Waits mean the next publication, including
  an unchanged value, and are installed atomically per topic per MCP session. Timeouts are
  `ok`/`changed:false`; unannounce, disconnect and session end cancel them with a reason.
  Registration rechecks the topic under the wait lock, and shutdown closes admission before
  stopping the client clock, so neither ordering can strand a waiter.
- Proxy Basic credentials are origin-scoped SecretStorage values, entered/cleared through
  commands and leased to the local server in memory. The mirror HTTP client never redirects
  with them. Claude's URL bridge goes through loopback `/pit-mcp` for exactly the registered
  endpoint, leaving its command and user registration secret-free. Set/clear needs Claude
  re-registration; with the window closed only the configured offline mirror remains usable.
- Windows CI on both the inbox correction and live tools caught capture creation failing its
  atomic `store.json` replacement, followed by a null-session assertion; both HTML reports were
  retained. The native cause is still unverified. Store writes now retry Windows
  `AccessDeniedException` only: six atomic-move attempts with 20/40/80/160/320 ms backoffs,
  preserving the old manifest, cleaning failed temporaries and honoring interruption. Other
  errors, including an unsupported atomic move, fail immediately. This is bounded handling of
  a repeatable failure class, not a claim to have identified the process denying replacement.
  [OpenJDK's Windows error mapping](https://github.com/openjdk/jdk17u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsException.java)
  distinguishes access denial; its [atomic move](https://github.com/openjdk/jdk17u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileCopy.java)
  has no retry. Injected move/pause tests pin the bounds without sleeping. No NT4 timeout or
  numerical tolerance changed.
- Windows also caught the credential expiry test assuming the wall clock advanced between
  registration and cleanup. The test now expires strictly past the last access and asserts
  the number removed, like the existing directory-lease test; production expiry is unchanged.
  Live session ordering compares cached instants, not ISO strings: fractional seconds can
  otherwise put a newer session behind one at the start of the same second.

- The capture-enabled inbox check now stops the real watcher before driving its injected poll
  clock. A full build caught a real watcher observation racing the test's `0`/settle-time
  observations; a planted later-clock first observation reproduced the refusal to import.
  One clock drives the test now, with no longer wait or production settle-time change.


#### Milestone 7 choices

- Prometheus 0.0.4 is written directly from its specification. Topic samples keep NaN and
  infinities, unlike JSON tool numbers; float values are widened before formatting so
  Prometheus's double parser preserves the recorded binary32 value. No exposition timestamp
  is emitted. Ages may be negative and are absent without sync. Arrays have zero-based
  `index` labels, struct leaves `field` paths, and struct arrays both; the configured limit
  applies to each array dimension. `metrics.include` never filters schema dependencies.
- Only published schemas are used, with no canonical or template fallback. Missing,
  incompatible or nonnumeric payloads have no samples. Field paths enumerate declared
  fields, excluding synthetic derived rotations while preserving an actually declared
  field of that name. Capture counts use `session_started_at` to distinguish lifetimes;
  they have the same 250 ms snapshot and byte definition as live tools.
- Pull bytes and successful verification events are process counters by serial, including
  retransfers and grown-file verifications. Gate counts cover only unfinished files known
  from the last remote listing, without a listing during scrape. JVM totals omit unsupported
  MBean values. Milestone 8 now supplies the gateway's published connected count; provider
  costs have no samples until those providers exist. Owners supply published component facts
  through the metrics interface; the renderer keeps no state between requests.
- The Compose example pins Prometheus 3.15.0 and Grafana 13.2.3. Robot dashboard topic boxes
  start empty: people choose exact names and confirm units; provider panels do not claim
  measurements from unimplemented providers. The capture, not the scrape, remains the record.
- Packaged startup exposed a configured, not-yet-created store below an existing directory
  alias being admitted lexically but validated canonically (macOS `/var` versus `/private/var`).
  Directory admission now uses the same ancestor resolver as validation, with a failing-first
  regression that also refuses siblings and a retargeted alias. It grants no extra directory.
- Review-fix CI `86998c3` preserved another `ClientTest` real-socket failure: the
  `wrongValueFamilyIsCountedWithoutLosingTheNextFrameOrConnection` test timed out waiting
  for its announcement, before sending either value. Both platform build jobs passed;
  the failed harness job's unchanged rerun passed ordinary tests and the harness. Its XML
  and job log were saved. The cause remains unverified; no timeout was widened.

Milestone 8 choices:

- `capture.gateway` is absent by default. An empty block selects 5810; port 0 disables it.
  The gateway follows the existing HTTP bind address rather than adding another bind setting.
  It starts after HTTP is listening, independently of the upstream client and puller. A busy
  port retries forever after 1, 2, 4, 8, 16, then 30 seconds, with address reuse enabled. Bind
  failures name `capture.gateway.port` and their cause once per state change. Health and
  `list_sessions` expose the port, state, cause and UTC `since`; a retry with the same cause
  preserves that time. The gateway core keeps received topics while waiting, and shutdown
  cancels retries without waiting for a never-bound listener. The socket is bound before
  Java-WebSocket takes ownership to close failed channels and avoid its repeated fatal log.
  No new WebSocket dependency or server native library
  was added; Java-WebSocket still owns framing only.
- Release review replaced the address-reuse option check with an actual rebind after a plain
  server actively closes a connection, leaving its port in TIME_WAIT. Java-WebSocket reapplies
  the option after binding, so its eventual value proved nothing about whether the initial bind
  could succeed. The test requires LISTENING before advancing the retry clock and has no OS skip.
  This macOS JDK defaults reuse to true: deleting the setter alone passed, while explicitly
  disabling it before bind failed with BindException. Windows may allow either setting; Linux
  rejects the missing pre-bind option as the review demonstrated. Listener-error injection also
  pins the one-second reset after a successful bind; an injected close failure must log its cause
  rather than the thirty-second-bound warning.
- The same ordered listener feeds recording, live waiters and gateway publications. Capture
  exclusion/thinning does not filter downstream clients. A disconnect ends the visible gateway
  session even when capture later resumes its file. Values flush before unannounce, and ids are
  never reused. Overlapping subscriptions share the minimum requested value period, permitted
  by the [NT4 subscription specification](https://github.com/wpilibsuite/allwpilib/blob/v2026.2.1/ntcore/doc/networktables4.adoc#subscription-options).
- Without upstream sync, the gateway answers with its own monotonic clock, following
  [ntcore's server reply](https://github.com/wpilibsuite/allwpilib/blob/v2026.2.1/ntcore/src/main/native/cpp/server/ServerClient4.cpp).
  On the first valid estimate of each upstream connection, downstream sockets reconnect:
  [ntcore 2026's client](https://github.com/wpilibsuite/allwpilib/blob/v2026.2.1/ntcore/src/main/native/cpp/net/ClientImpl.cpp)
  takes its first RTT reply, so changing the reference under an existing connection would leave
  its offset wrong. No topic timestamp is rewritten. The harness uses a separate ntcore instance
  in each simulated robot process and checks the received server timestamps, not local timestamps.
- Each client has at most 32 MiB or 65,536 period-pending value records and 32,768 socket fragments
  of at most 1,200 payload bytes. Both bounds disconnect and log, rather than silently losing
  `all` publications. Socket queue size is constant-time; no queue walk occurs per publication.
  The metrics count is published without the fan-out lock. Queue sizes are internal constants;
  tests inject smaller bounds and stop reading a real TCP connection.
- The gateway port has no authentication and belongs on the private network. HTTP Origin,
  loopback controls and the upload policy are unchanged. Real dashboard and AdvantageScope
  operation against a robot remains the user's manual check; simulation proves ntcore protocol
  interoperability and the scripted data path, not radio behavior or a dashboard's UI.

Native replay duration check: the stratified sample exposed the old five-minute whole-file
deadline on a large input while receipt acknowledgements were still advancing. The verifier now
bounds a lack of received-value or metadata progress to 30 seconds; file size does not determine
success. Injected-clock checks cover a healthy ten-minute replay, unchanged/rewound receipts and
repeated handshake read errors. This changes no NT4 socket timeout or matching tolerance.
The same large input exposed a second ordering limit: the publisher's bounded stop handshake
was left waiting during offline auditing and SFTP retries. Capture now acknowledges and stops
the publisher before that work; a fixture check fails if the publisher remains alive at the audit
boundary. Its handshake timeout is unchanged.

Release-preparation socket investigation:

- Both preserved failures were read before running again: `ClientTest.wrongValueFamilyIsCountedWithoutLosingTheNextFrameOrConnection`
  waited for its announcement, and `GatewaySocketTest.realClientsSeeEveryChangeOrLatestAndWritesDoNotLeak`
  waited for the 4.0 peer's publish acknowledgement. With 18 parallel CPU workers, looping both
  complete classes reproduced the first at repetition 27 (294 completed test methods). A focused
  instrumented run reproduced it at repetition 76: the peer was open with one output frame queued,
  but selector interest was only `OP_READ`. The library selector was waiting, not writing.
- [Java-WebSocket 1.6.0's server](https://github.com/TooTallNate/Java-WebSocket/blob/v1.6.0/src/main/java/org/java_websocket/server/WebSocketServer.java)
  clears write interest after draining a batch, concurrently with a sender enabling it. The
  gateway's existing 200 ms aliveness tick now rearms pending output when write interest was lost;
  the independent scripted peer uses the same adapter repair. No bytes are resent, queue checks
  are constant time, and no 4.0 ping or new dependency is added. Both real-socket variants failed
  first with that state planted and then delivered the hand-encoded frame once. The historical
  gateway timeout fits the same path, but its saved evidence did not include selector state, so
  that attribution remains an inference. Both named cases passed 400 repetitions each under the
  same CPU load after the repair, with unchanged timeouts.
- The full-class loops also exposed a distinct missing disconnect callback in
  `ClientTest.reconnectUsesNewAnnouncementsAndContinuingValuesAndResetsBackoff`, at repetition 251
  before the repair and 110 afterward. CI for the bind fix separately timed out at
  `ReplayClockResetTest.pair` waiting for the first native replay process's disconnect; its
  unchanged rerun passed. The socket-repair commit's CI reproduced that same native-disconnect
  wait again while both ordinary Linux/Windows builds passed. Reports and stacks are preserved under the release-preparation build
  reports. These are not claimed fixed by the write repair: the native server does not use this
  library. The working hypothesis is EOF notification/demand ordering in the JDK client while
  the injected aliveness clock is stationary, rather than lost values. A confirming run needs
  the live socket's input state, receive demand and callback queue captured before teardown;
  advancing or widening a timeout would hide that distinction. Neither was done.


Release-preparation editor choices:

- Explorer milestone 11 uses the development-only MIT `@vscode/test-electron` 2.5.2 line,
  which supports CI's Node 20. Linux `xvfb` runs the minimum supported VS Code (1.101.0)
  and current stable independently; the helper downloads isolated editor builds and caches
  them. The runner is Linux-only so invoking it from another desktop cannot open a GUI;
  those developers use CI. A generated WPILOG, disposable user/settings/install directories and an argv-recording
  Claude executable keep the run independent of an installed editor, account or robot log.
- Five surfaces are exercised inside the real host: activation, shared daemon startup,
  the actual Logs provider, the custom editor opening, and secret-free user-scope pit command
  arguments. This does not claim rendering, credentials, agent discovery or the remaining
  interactive mirror/organizer checklist. The ordinary Node test command is unchanged, and
  smoke code/dependencies/downloads are excluded from the VSIX. CI repeats it with five
  restored compiled-output faults; an unrelated failure or timeout does not count as a caught plant.

Release-review keepalive failures (before the tag):

- **Third load-sensitive socket failure:** Windows CI on `6dc0542`, run `37816626941`,
  first attempt: `CaptureGatewayTest.orderedCaptureFeedMirrorsTypesPropertiesValuesClockAndSessionBoundariesWithoutAcceptingWrites`
  dereferenced a missing `/signal` announcement. Its preserved XML says the fixture gateway
  dropped client `capture` with `NT4 pong timeout`; the capture had cleared its topics before
  the downstream assertion. The assertion now checks connection state and reports its last reason.
- **Fourth load-sensitive socket failure:** the same run's unchanged Windows retry:
  `ReplayPullTest.measuredShiftsPassThroughSftpAndPreserveThePlacementLimit` failed for the
  generated DataLogManager and other-layout fixtures with `Replay capture stalled`. Its XML
  twice says the fixture gateway dropped client `replay` with `NT4 pong timeout`. Replay now
  reports the disconnect before waiting for missing records or attempting pull placement.
- Both failures have the same liveness defect: heartbeats judged time since a pong processed
  on the application loop, even when that loop had sent no ping during a stall. The review's
  controlled probes reproduced healthy disconnections after 1.3 seconds; injected-clock tests
  now pin 1.5 seconds on both sides, network pong receipt/replies, and the exact one-second
  deadline of an unanswered ping. The 200 ms interval and one-second limit are unchanged.
  Periodic `channel.force` also ran on the client loop; slow Windows disk force is a plausible
  trigger, not a disk-latency measurement present in the saved XML. It now runs on a writer-owned
  daemon with one pending force, completion-time observation and loop-owned observer callbacks.
  Close/rollover await that force. Record writes stay on the loop; no evidence yet requires
  moving those writes. No protocol deadline was widened.
- JDK WebSocket receive demand is renewed on its network callback, since waiting for a listener
  also withholds automatic pong replies and incoming pong delivery. Copied work has a 32 MiB
  bound (at least 64 bytes charged per callback); exceeding it records a receive-queue reason.
  Gateway ping replies retain the existing socket-queue check, with only an overrun drop queued
  to fan-out. The real-log stratified sample is the verification scope for this timing fix;
  the full set passed on `9d7a408` and remains required again before 0.10.0.
- The first CI pass after this fix was green on Linux and Windows but exposed an obsolete
  native-pair test assumption: `ManualScheduler.receive()` waited for a queued pong callback,
  which the fix intentionally removed. That test now advances to its three-second time-sync
  exchange and waits for the actual reply before reboot, retaining the existing assertion and
  deadline. It does not treat missing application-loop pong work as a dead connection.

### Milestone 11 choices: SSH stats and followed files

- The default is on with `capture.pull.ssh` or an enabled puller; capture without SSH remains
  NT4-only. `capture.stats.enabled: false` and `capture.tail: []` turn each provider off.
  The shop measurement revisits these defaults; it no longer blocks making them testable.
- `ssh` owns JSch sessions, one per configured host; conflicting credentials are refused. Channels have separate
  owners and workers. Connection backoff is 1 s doubling to 30 s during NT4 presence; the
  disabled-only pull gate is unchanged. Robot pins retain `robot.json` authority; other-host
  first-contact fingerprints are local `ssh-hosts.json` facts, outside the catalog door and
  peer copying. Tail passwords and key paths require environment references.
- The console default is `/home/lvuser/FRC_UserProgram.log`, the path used by
  [WPILib DataLogManager](https://github.wpilib.org/allwpilib/docs/release/java/src-html/edu/wpi/first/wpilibj/DataLogManager.html).
  Its followed stdout is `console`; `messages` is the separate explicit logging API. The
  process lookup reads the quoted JAR argument in `/home/lvuser/robotCommand`, as generated by
  [GradleRIO 2026.2.1](https://github.com/wpilibsuite/GradleRIO/blob/v2026.2.1/src/main/java/edu/wpi/first/gradlerio/deploy/roborio/FRCJavaArtifact.java),
  then matches exact `/proc` arguments. Missing/ambiguous processes omit program fields;
  unsupported custom launch commands are reported, never guessed.
- The command supplies the device's tick/page units; `df -Pk` and meminfo KiB become bytes.
  CPU excludes idle/iowait and counts guest time once, per the
  [Linux proc specification](https://www.kernel.org/doc/html/latest/filesystems/proc.html).
  Program CPU uses PID plus start ticks; interval rates need a valid previous kernel sample.
  Stats replies are bounded to 64 KiB and 30 s. The send-time offset is captured before exec;
  missing estimates increment a dropped count. Unsupported stats output stands down for the session, including a clock-continuous resume, and is tried again on a new session. A slow round trip doubles the period to 30 s;
  a fitting one halves toward the configured base. Whole-robot CPU seconds are not a claim of
  CPU spent on the provider. `capture_stats.kernel_clock` keeps the uptime/FPGA pairing.
- Tails use `-n 0 -F -s 0.25`; kernel/journal prefer native follow, otherwise poll the configured
  path by inode and byte offset at the stats period. A source miss stands down until a new
  session; SSH loss resumes automatically, with possible missed lines explicitly left to later
  system-log pulling. Rate buckets are one second, capped at 200 accepted lines per file;
  history is 1000 lines and an individual line 64 KiB. Drops produce one notice after a full second without another drop, even
  when a burst spans several rate buckets or ends in silence. Buffered receipt times are mapped at session start and clamped at
  zero with a metadata note. Provider record/byte costs are per session; drop counters are
  process-lifetime facts. `capture_stats.providers` is additive, so no store format bump.
- Recorded `sampled: true` metadata keeps adaptive provider series periodic in numeric quality and data-endpoint views, rather than inferring change-only logging from changing values.
- Providers share the live index and ordinary tools, with source-labelled latest values and
  numeric metrics. The context path does not republish provider entries through the NT4 gateway.
  An external capture-loop watchdog reports one-second stalls and recovery without disconnecting
  a peer. The October 8 Windows keepalive failures are the concrete reason for the loop rule in
  CLAUDE.md and ARCHITECTURE.md. No SSH I/O is admitted to that ordered loop.
- NI-image command availability/permissions, actual rotation behavior, and roboRIO 1/2 processor,
  storage and network cost remain shop measurements. All committed proc/tail fixtures are
  synthetic. No native server dependency, version bump or tag is part of this milestone.
- The first provider CI run failed the same fresh-file comparison on Linux and Windows:
  catalog refresh could overtake the coalesced close update and its identity directory move.
  The test now joins capture's final-manifest barrier before resolving the path, rather than
  sleeping. The Windows run also exposed a missing-file exception reported as an internal error;
  a separate regression now requires an explained moved-or-removed-file result.

### Round 12 choices: bounded verification and present-time access

- Native transport proves zero-shift fidelity once per selected file; Java alone proves the
  placement shift matrix. `conformanceNative=none|sample|full` is independent of the Java
  directory selector. Ordinary tests exclude natives; sample is the harness default. Native
  batches allow 32,768 records. A separate 1 MiB estimate, including a conservative native
  message envelope, protects ntcore's own 2 MiB local publisher queue: count-only batches
  lost fixture values there before the capture socket. Blocking pipe notifications replace
  timed handshake polling; JDK directory-watch polling added seconds per barrier on this Mac.
  Control files remain progress evidence, and capture receipts acknowledge every record.
  The queue limit is from allwpilib's `net/ClientMessageQueue.h` in WPILib 2026.1.1.
- Verification follows DEVELOPMENT's policy: targeted checks while editing, one final build,
  only affected optional suites, and full real-log replay before a tag. Coverage is explicit
  and runs once on Linux CI. Forks share atomic cache claims but own fixture files; tests which
  inspect cache contents use temporary caches. No telemetry or machine-local input path is
  stored here; selections, timings and counts stay under `build/reports`.
- Stats retain one `df` per sample (free space has no proc file). PID/start ticks and `getconf`
  constants are cached per SSH connection. Fixed proc reads check process identity; a mismatch
  omits program fields and requests one discovery on the next sample. Shop CPU costs remain
  unmeasured.
- `last_seconds` uses the acquired view's robot-time estimate for an open capture, otherwise
  the last record; without an estimate it uses the prefix end. Positive finite durations only,
  incompatible with absolute start/end, intersected with scopes/windows. `compare_matches`
  resolves each log separately. Inputs distinguish the resolved window from the captured range.
- `pit://session/current` is a read-only JSON resource, always discoverable, with an explained
  `not_applicable` when capture is absent/closed. It uses published snapshots. Prompts stay
  empty and resource subscriptions are not offered. No new assistant write surface is added.


### Round 13 choices: running the pit server as a service

- Public `run <name> [--config <file>] [--managed]` reuses internal foreground startup,
  logging to stderr without a PID file. Configuration/bind failures return nonzero; clean
  transport stops return zero. Public run uses the JDK signal dispatcher for INT/TERM to
  request exit zero after shutdown hooks drain; Windows forced termination cannot be caught.
  Internal spawning retains its private flag and token.
- Supervisor ownership is explicit through `--managed` only, published in
  health and `list_sessions` even without capture. The daemon manager refuses adoption,
  replacement and stopping; `stop` probes the configured port as well as its optional PID
  record and explains which systemctl command to use. `--config` also selects stop's port.
  A hand-written systemd unit must pass the flag. Reading `INVOCATION_ID` was a mistake:
  GitHub runner jobs and terminal shells inherit it, so their ordinary daemons were wrongly
  refused. The regression starts, adopts and stops a real child with that variable set.
- The current plan had no concrete unit text. `service-unit` therefore prints three named
  files for review: the service, an unprivileged HTTP probe, and its 30-second monotonic timer.
  The layout is `/opt/wpilog-mcp`, root-owned configuration/environment under `/etc/wpilog-mcp`,
  and a `wpilog-mcp` system account with `StateDirectory=wpilog-mcp`. Stop has 90 seconds and
  mixed kill mode; restart is always with five seconds between failures and no start limit.
  `SuccessExitStatus=143` accepts a clean SIGTERM exit on JVMs without the signal handler.
  Installed program files are readable by the service account (`0644` JAR, `0755` launcher
  and program directories); reinstall repairs the old owner-only modes while preserving
  private configuration. CI checks access as the account before startup and writes the JDK's
  `JAVA_HOME` to the environment file, since systemd lacks the runner shell's toolcache PATH.
  The probe reports in the journal, without independently restarting a healthy-but-busy JVM.
  Filesystem/privilege hardening preserves JIT, and no MemoryMax duplicates heap policy.
  [systemd's execution rules](https://github.com/systemd/systemd/blob/main/man/systemd.exec.xml),
  [service lifetime](https://github.com/systemd/systemd/blob/main/man/systemd.service.xml) and
  [monotonic timers](https://github.com/systemd/systemd/blob/main/man/systemd.timer.xml) own the
  directive semantics. The guide explains installation and a private network without NTP:
  calendar placement uses the pit clock; robot time cannot repair its calendar date.
- Absent-program discovery is one scan at connection start, then one every ten samples.
  Only a known PID/start-time change requests the next-sample rescan. The missing-program
  state remains visible and its unchanged reason is logged once. Unit constants remain
  cached per connection, and each sample still invokes one df as agreed in round 12.
- CI harness work depends only on path classification; ordinary builds keep their own XML.
  Main, configuration, installer and MCP changes also select real-JAR extension checks.
  Filter-only changes exercise their Python assertions and the service/editors, without
  unrelated capture replay. Unknown before history runs everything.
  Real systemd CI verifies the printed service and timer, managed/version health, refusal of
  daemon-manager stop, and a bounded systemctl stop; an unmanaged service is a required plant.
  No native replay or real-log replay is needed for this round's service/provider changes.


#### Round 14: system-log collection and clocks

- Configuration splits kernel from journal: `kernel: dmesg|off`, `syslog` paths, and a separate
  `journal: false`. Journal true replaces syslog-file collection, never dmesg. It reads the
  whole journal with `journalctl -q --no-pager -o short-unix --show-cursor`, then
  `--after-cursor`; the first pass selects the current kernel boot. Missing/refused commands
  stand down that source for the session with the reason, without guessing a fallback file.
  Kernel messages may appear twice with different clock bases; source kernel means dmesg only.
- System pulling is independently opt-in and uses the existing SSH connection, pull worker,
  disabled settle gate and 64 KiB byte budget. Streaming commands have a 30-second per-block
  deadline, driven outside the worker; dmesg's in-memory comparison refuses rings over 16 MiB
  with a reason. Journal output is spooled/grouped on disk, not held as an unbounded reply.
  Unchanged file metadata retains its content proof within a connection; new names/growth
  still require content checks. No robot file is written or deleted.
  Closing the gate releases a streaming exec channel, then retries from its committed
  cursor; SFTP retains its byte offset. The real-SSH test exposed why: a paused full input
  pipe blocked JSch's shared network reader and even a separate channel could not open.
- Session manifests gain additive `system_logs` receipts/cursors/reasons; the store format
  stays 1. Syslog snapshots live under the robot's system directory. Journal day files live
  there in a session-id directory, separating FPGA sessions even within one kernel boot.
  Kernel and NI files stay under session robot/system. A crash without a unique observed PID
  match stays in robot/system/unassigned with its note; provider snapshots retain program_pids
  so finding a target does not scan captures. A capture flush, resume or close preserves these
  facts. A partial append beyond a committed receipt is discarded before retry.
- `search_system_logs` takes the capture path and reads local manifest members only. Kernel
  mapping interpolates nearest uptime/FPGA pairs within their range; wall text uses recorded
  systemTime and a pulled file's recorded alignment. Traditional syslog dates without year or
  zone stay unmapped. No laptop calendar-time guess substitutes for missing robot evidence.
  Unmapped lines remain visible under a time filter with null and a reason. Paging is stable by
  path then line number; totals count the complete filtered set. The guide distinguishes the
  exact pulled record from the timely tail. Reference responses remain maintainer-generated.
  Round 15 extends door/sync/mirror transfers to these separate text receipts, described below.
- The shop must return /var/local/natinst/log and /var/log listings; journalctl/dmesg/df
  availability, whether dmesg needs root and prints `[seconds]` stamps (needed for the kernel
  cursor's wrap detection), whether journalctl accepts a boot id, the console path, and the
  robot program command line.
  These are unverified defaults, with system collection and journal both off. No native replay,
  real-log replay or npm run is warranted for this round's capture/pull/tool changes; the
  generated shop harness runs once with conformanceNative=none, and Windows is CI's check.

#### Round 15: system text follows the store

- Each SSH connection retains a parsed session inventory and catalog snapshot. Manifest and
  directory metadata detect replacements, session changes and placements; no timer reparses
  the store. A 300-session counting-reader test pins one parse per unchanged manifest over ten
  idle passes and checks invalidation after placement and a new open session.
- Shared syslog/journal receipts are additive `robots/<serial>/system/index.json` entries,
  with written epoch spans when known. Search includes overlaps and unknown spans, including
  unknown session spans. Per-session receipts remain for kernel, NI and crash files. Older
  shared session receipts are honored without rewriting them; the index supplies a newer
  committed prefix of the same path. Store format remains 1.
- The first journal command uses `-b` alone, journalctl's current boot. The reply still carries
  the boot UUID for the continuity check; it need not be accepted as a journalctl argument.
  Both command availability and boot-id support remain shop facts to collect.
- `/store/sessions` carries session receipts and a robot-wide `system_logs` index list. An
  unfiltered peer sync copies the entire index, including rotations older than its sessions;
  filtered listings and mirrors select overlaps or unknown spans. System-file ranges and
  prefix hashes stop at the receipt's committed length. Text uses the transfer engine's
  content checks and verifies against the advertised SHA-256 and size, without invoking a
  WPILOG reader. Peer copies retain provenance plus the source store/URL/time; a placement
  journal recovers interrupted publication, and session IDs keep growing text at one receiving
  path across event renames. Local fragment consolidation carries its text receipts into the
  joined manifest as well as the telemetry. Interrupted jobs name the held file and byte offset.
  Mirroring keeps the origin's receipts, counts
  shared bytes once, and evicts them only when no retained session uses them and the origin
  still holds them. Missing local copies return `not_applicable` naming the collecting server.
- Synthetic stores check shared receipt counts, legacy compatibility, ranged/hash reads,
  two HTTP stores, mirror search, growing-prefix resume, hash corruption and retention. The
  round runs targeted checks, one locale-unset build and one generated harness with
  `conformanceNative=none`; it needs no native replay, real-log sample or extension test run.

### Round 17 verification choices

- Chart drawing defaults to the whole requested window; dense time series keep min/max per
  plot-pixel column. Explicit limit/offset requests a page. Each series reports the drawing
  mode/count, while its statistics still use the whole window. Renderer failures other than
  heap exhaustion omit only the image and name the failing class.
- Generated worker roots are cleared once per JVM, and conformance's store/export roots before
  each creation. Reused Gradle worker numbers no longer reuse unmanifested pulled text.
- The socket-delivery test guard is thirty seconds; it returns immediately on delivery and
  does not change the NT4 clock, ping interval or unanswered-ping deadline.
- Boundary tests use 4 KiB windows; one conformance fixture and one import repeat that path.
  The ordinary sparse-address check reads beyond 2 GiB without allocating a giant fixture.
  `largeLogTest` separately writes 2.2 GB of synthetic records and exercises actual import and
  rollover beyond 2 GiB. Windows CI plants an unreleased window on the peer-independent import
  path, retaining it against GC. A rename alone did not catch this on the Windows runner:
  its OS/JDK allowed the move. The import test also checks the JVM's mapped-buffer pool, so
  skipped cleaner calls fail even where rename is permitted; the restored code must both
  release every window and move the file.
- The Linux metrics smoke exposed a separate readiness race: Grafana had provisioned its
  dashboard, but its background updater stopped the Prometheus plugin just before the query.
  Query readiness now uses the existing bounded readiness helper; a scripted 404 then success
  and a permanently missing plugin pin both outcomes without a wall-clock wait.

### Round 18 choices: another process's append and shop facts

- Milestone 14 uses the long-addressed `LogReader` introduced with windowed mapping. A resume
  copies declaration state and compact offset arrays and maps the whole file again; it never
  mutates a prior reader's index. Incomplete final records retry from their first byte. Prior
  damage takes a fresh scan, retaining the established timestamp-damage recovery rules.
- The prefix rule is equal, known filesystem identity plus SHA-256 anchors of the complete
  header and the last complete record. These digests are saved before a rewrite can affect
  the old mapping. They do not detect an interior rewrite preserving both anchors. A full
  prefix hash would reread the indexed bytes, defeating the secondary path's purpose. Where the
  JDK exposes no file key, round 19 now uses creation time as identity;
  the two anchors remain required, including on Windows.
  Fresh decode caches prevent stale short entries; across-growth calls still discard results.
- `robot-facts <host>` and `robot-facts --server <name>` use one connection through the shared
  SSH implementation, its TOFU/change policy and size-scaled hash deadline. Named servers use
  their capture store's pins; direct hosts use `~/.wpilog-mcp/robot-facts/`. No password flag is
  accepted; named YAML can reference the environment. The only local writes are pins and a
  new dated Markdown report. Nothing on the robot is written or deleted.
- Image metadata and both console locations are candidate probes, not presumed NI facts.
  Current-boot `journalctl -b` and explicit boot-UUID selection are separate probes: one does
  not prove the other. `/proc/<pid>/environ` is tested for readability, never read. Commands
  keep bounded stdout/stderr, status and elapsed time; a permission refusal does not abort
  the remaining checklist. Known secrets/key paths and sensitive command-line options are
  redacted. The report retains the actual successful authentication method and public key
  fingerprint.
- The largest listed WPILOG/REV file nominates a hash-cost probe of min(size, 104857600) bytes.
  Its requested size always accompanies timing; a changing file can alter the available bytes,
  so this does not claim a complete-file cost. Utility output/permissions, NI paths and actual
  authentication remain unverified on hardware until this collector is run in the shop.
  Provider budgets, live tail options/rotation, radio loss, sustained load and robot timing
  still require their own exercises. DEVELOPMENT.md maps the entire shop checklist to the
  report's found/absent/refused conclusions and the remaining manual work.

### Round 19 choices: Windows identity and PhotonVision context

- Windows uses creation time when the JDK supplies no file key; growth and both byte anchors
  are still required. NTFS tunneling can reuse a deleted file's creation time for a replacement
  within a short window, so anchors remain the defense. A replacement preserving that time and
  both anchors is not proven distinct; hashing the whole prefix would lose the rescan benefit.
  Growth retirement no longer records a duplicate reload notice; the next load records it.
- The backend is pinned to [PhotonVision v2026.3.4](https://github.com/PhotonVision/photonvision/releases/tag/v2026.3.4).
  [Server.java](https://github.com/PhotonVision/photonvision/blob/v2026.3.4/photon-server/src/main/java/org/photonvision/server/Server.java)
  declares GET `/api/settings/photonvision_config.zip` and `/websocket_data`. The former is a
  ZIP containing `photon.sqlite`, not a JSON settings response. We validate/hash/discard it and
  use the latter's binary MessagePack `settings` plus `cameraSettings` document for current facts.
  No PhotonVision implementation, SQLite dependency or native component is bundled.
- [UIPhotonConfiguration](https://github.com/PhotonVision/photonvision/blob/v2026.3.4/photon-core/src/main/java/org/photonvision/common/dataflow/websocket/UIPhotonConfiguration.java)
  pins `settings.general` version/device/hardware and `settings.atfl` field layout.
  [UICameraConfiguration](https://github.com/PhotonVision/photonvision/blob/v2026.3.4/photon-core/src/main/java/org/photonvision/common/dataflow/websocket/UICameraConfiguration.java)
  pins nickname/uniqueName, currentPipelineSettings/index, videoFormatList, calibrations and
  connected/mismatch/deactivated flags. Calibration matrices and per-snapshot `meanErrors`
  come from `UICameraCalibrationCoefficients`. Pipeline type is its Java enum ordinal, not
  its different UI base index (AprilTag ordinal 5, base index 2). 3D and multi-tag fields are
  required only on pipeline types that publish them; elsewhere they are null, not guessed false.
- [VisionModule](https://github.com/PhotonVision/photonvision/blob/v2026.3.4/photon-core/src/main/java/org/photonvision/vision/processes/VisionModule.java)
  sends selective `mutatePipelineSettings` without a camera ID. A new read-only WebSocket
  causes a complete configuration broadcast; we close the prior connection before that
  handshake to avoid receiving the broadcast twice, rather than assigning the delta to an
  arbitrary camera. Other known UI telemetry messages are ignored.
  Unsupported versions/required shapes and unknown messages stand down for the session.
- These routes provide no configuration timestamp. `timestamp_basis` explicitly says receipt
  mapped through NT4 server time; the estimate is captured on the network callback, not after
  an HTTP fetch or queued write. Startup waits for an estimate. Metadata names the pinned
  release, host and export hash. The archive is not retained, and network configuration is
  not copied into capture entries. A disappeared backend leaves the last snapshot and reason.
- The worker bounds export bytes (64 MiB), expansion (256 MiB), UI messages (4 MiB), cameras (64)
  and request/initial-snapshot time (10 s). Keepalives use unanswered pings with a 5 s deadline
  and network-thread pong receipt, so a worker delay is not charged to the backend. One message
  at a time is delivered with backpressure
  through the writer's context hook. Store contention cannot delay its delivery. State and
  cost use existing manifest/live snapshots; `wpilog_provider_state` adds a labeled state gauge.
- `context.photonvision` is beside capture, off when absent/empty, default port 5800 with an
  optional explicit port for installations/tests. It inherits as a block and requires capture.
  Camera names match exactly: a generic robot-code index is never mapped to a backend nickname
  by assumption. `camera_settings` retains the last pre-window snapshot plus changes inside
  the window. No current pose tool exposes per-camera context; their robot-level comparisons
  remain unchanged. The catalog and response scenarios need no new calls.
- Shop: confirm the coprocessor's release, export route/archive shape, binary WebSocket route
  and document, camera nicknames, calibration errors/resolutions, pipeline changes and backend
  restart behavior. The fixture proves our pinned protocol handling, not a real deployment's
  routes or cost. The PhotonVision harness container remains step 2.

### Round 20 choices: refreshes and JVM polling

- The reconnect assertion waits for retry scheduling, not just the earlier disconnect callback;
  it still observes either delay so removing reset fails the one-second count assertion.
  PhotonVision refresh starts are limited to one per backend per second, with one pending
  refresh for notifications during that second or a fetch. No notification is applied to a
  guessed camera.
- `context.jvm: {port: 5809, period_sec: 1}` is explicit opt-in, inherited with the context
  block. The host is NT4's connected candidate. Port accepts 1–65535; period accepts
  0.001–3600 s and is scheduled after delivery, so calls never overlap. No JMX credentials,
  instrumentation or robot launch changes are made by the server. The team's same-port
  registry/RMI flags are in STANDALONE.md; unauthenticated JMX belongs on the private network.
- Corrected §8.3: Runtime StartTime is cached and Uptime is elapsed time in
  [OpenJDK 17](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/services/management.cpp).
  Their sum is not a measurement of later wall-clock changes. Samples instead use receipt's
  NT4 estimate, recording monotonic uptime, FPGA-minus-uptime and a bound of the complete
  JMX poll plus NT4 RTT plus 1 ms quantization. A change larger than both adjacent bounds
  records `/Daemon/JVM/ClockNote`; start time is identity metadata only. No new SSH command
  is used for clock mapping; SSH kernel-uptime pairing and pulled-log `systemTime` remain separate. The SSH stats
  command does not currently read wall time; only the separate robot-facts clock probe
  samples `date`, so that distinction is retained rather than claiming a second live wall source.
- JMX uses the JDK connector only. One I/O worker plus a five-second external watchdog
  prevents a blocked connector from occupying capture or multiplying workers. Refusals
  back off 1, 2, 4, 8, 16, then 30 seconds; a stuck RMI call must return before retry.
  NT4 presence and an open session control admission, and delivery checks its original
  session token again. Runtime facts are fetched once per connection; secret-bearing
  property arguments are redacted. Unsupported process CPU or negative counters are omitted.
- JMX `sample_bytes` means eight bytes per numeric value plus UTF-8 runtime/note JSON,
  excluding RMI/WPILOG framing; the public API provides no RMI byte counter. Recorded-byte
  cost remains the writer's actual count. Provider state and numeric samples reuse the
  existing live-tool, manifest and metrics snapshots.
- `robot-facts` locates the deployed program by its JAR and invokes `/proc/<pid>/exe
  --list-modules`, not the shell's possibly different Java. The report separately marks
  `jdk.management.agent`, `jdk.jfr`, `jdk.management.jfr` found/absent/refused. Actual image
  modules, launch compatibility, network reachability and polling cost remain shop facts;
  the Flight Recorder half is intentionally still open.

### Round 21 choices: close barriers and JVM clock evidence

- Linux run `37979048713` on `154797d`, artifact `test-report-ubuntu-latest`, records
  `ReplayCaptureTest.reconnectBeforeReplayWaitsForTheNewSubscriptionsAnnouncements`
  failing after 30.096 s: `Client callback missing`, through `ManualScheduler.until`
  at ReplayCaptureTest line 56. Stdout is empty. Stderr records the `readiness` peer
  connecting, then teardown 30 seconds later; it records no client close/error callback.
  The saved evidence therefore does **not** establish which JDK callback ran, or that
  dispatch rejected it. A local trace of the unchanged code receives `onClose(1006)`,
  dispatches with `current == this`, and `failed` clears the connected state.
- The fixture's `dropClients` future formerly acknowledged only local `closeConnection`
  calls. That is not a peer-delivery barrier. A deterministic peer withholding its close
  reply proves the old future completes before that reply. The fixture now sends a 1001
  close frame and completes after the peer's reply/EOF and ordered socket removal. It
  sends the frame directly because Java-WebSocket 1.6.0's `close()` closes server sockets
  when output drains, before a reply. ReplayCaptureTest, CaptureFailureTest and ClientTest
  drain the already-enqueued client disconnect after this barrier, rather than waiting
  for an unproven TCP-delivery event. Client protocol, timers and timeouts are unchanged.
  This closes the demonstrated fixture ordering gap; the historical missing callback's
  exact cause remains unproven by that XML and is not labeled a client-state bug.
- JMX `ClockNote` is measurements only: previous/current offsets and round-trip bounds,
  change, both JVM start identities and a reason explicitly declining to determine the cause.
  The comparison resets for a new NT4 session. A within-bound change is not a note. The bound
  test reads the first sample before another poll can replace its published round trip.
- Delivery failure deliberately stands down for the session, including a resume; the sink
  may have written partially and blindly retrying risks duplicate context. The next session
  polls again. Connection/poll failures retain their existing bounded backoff.
- §8.3 now pins the JFR design to OpenJDK 17.0.16+8 source. Chunk epochs can change; backward
  UTC is clamped, and flush receipt has no hard latency bound. A fresh recording start,
  bracketed over the existing JMX connection, is a candidate bounded anchor; reading an old
  start time is not. Chunk association, cost and stepped-clock behavior must be verified
  before streaming, after the shop module probe. This round adds no stream code or robot
  instrumentation. Shop preparation explicitly checks team launch flags, port reachability
  and `list_sessions` reporting `jvm` as `sampling`.


### Round 22: mock lifetime and the synthetic NI-like container

- Four remaining synthetic tool classes use `MockLogAdmission`; `testPutLog` pins their
  entries against pressure eviction until explicit scope cleanup. A permanently full heap
  and immediate pressure/make-room sweeps failed 63 of 78 tests before pinning; the same
  four classes and pin/cleanup checks passed afterward. File-backed logs remain evictable.
- `harness/rio/` is an Ubuntu 22.04 container with Temurin 17's jlink-built JRE and real
  OpenSSH. No NI image or robot file is copied. `lvuser` has an empty password; sshd explicitly
  permits it, uses no PAM, and serves SFTP plus real exec channels. Its process can read its
  own `/proc` environment; serialnum is supplied to the robot launcher. Runtime-generated
  Ed25519 host keys, loopback-only host port publications, no privileged mode and no host-proc
  or Docker-socket mount limit the harness to its synthetic files.
- The robotCommand's five JMX flags are the standalone guide's. The image includes
  `jdk.management.agent` and intentionally excludes both Flight Recorder modules so
  `robot-facts` proves found/absent conclusions against the actual deployed runtime.
  `sha256sum`, `df` and `tail` are real installed utilities; journalctl is absent.
  A container's dmesg permission refusal is retained, never replaced by fabricated kernel text.
- The same timeline drives both processes and the independent capture/pull oracle. The
  container also checks live provider states, provider cost receipts, `/Daemon/roboRIO/` and
  `/Daemon/Tail/` entries, pulled console text, and `/Daemon/JVM/` uptime/FPGA pairings.
  The latter must fit a single constant offset inside every reported bound. For that check,
  the HAL runs on its continuous monotonic clock; stepping at 20 ms would introduce a
  simulation artifact larger than a fast JMX round trip. Values retain their exact scripted
  timestamps, and delayed callbacks emit every due record. The existing MINA run retains
  the per-block gate audit and ntcore gateway oracle.
- Linux CI caches image layers and runs the two independent timeline tests in two workers;
  building the simulation once and overlapping their real-time phases avoids two sequential
  48-second waits. `harness/rio/run` documents the Docker entry point on Linux or a Linux VM.
  The ordinary suite has no Docker requirement. PhotonVision's real process stays open.
- This proves that the providers, collector and transfer stack work against this declared
  Linux environment. Actual NI empty-password policy, `/proc` access, console/NI paths,
  installed command versions, bracketed dmesg stamps and permissions, journal support,
  JRE modules/flags, kernel reboot, radio behavior and roboRIO 1/2 hashing/provider costs
  remain shop measurements. Container timings are desktop/runner costs, not robot costs.

- Integrating the real shell exposed a collector assumption: `which` returns 1 with empty
  output for an absent executable, not the scripted 127. The SSH fixture now uses that reply;
  its absent-tool assertion failed before the classifier correction. Permission/deadline
  refusals still take precedence.

- The first CI image build hit Docker Hub's anonymous 429 limit before executing a layer.
  The Dockerfile uses [Canonical's public Ubuntu registry](https://ubuntu.com/docs/oci-registries/oci-how-to/getting-started/)
  and the Docker Official Images Temurin mirror on ECR Public; both tags were verified before
  switching. Build caches remain scoped to this harness. The clock oracle's file regression
  also pins the independent reader's recorded length, not the spare array capacity.
  The BuildKit bootstrap uses the same upstream image tag through Google's public mirror;
  otherwise that earlier setup step still depends on Docker Hub's token endpoint.

### Round 23 choices: the pinned backend and one harness entry point

- The separate, unmodified [v2026.3.4 Linux x64 release JAR](https://github.com/PhotonVision/photonvision/releases/tag/v2026.3.4)
  is pinned by URL and SHA-256 in `harness/photonvision/release.json`; the downloaded hash agrees
  with the asset's published digest. Cache hits are verified too. Its GPL-3.0-or-later code and
  native libraries enter no shipped server or extension artifact.
- [Main.java](https://github.com/PhotonVision/photonvision/blob/v2026.3.4/photon-server/src/main/java/org/photonvision/Main.java)
  provides `--test-mode` and `--disable-networking`. The harness generates a blank JPEG at the
  file-camera path selected by that release's `TestUtils`; it uses none of the photographed
  test scenes. The default `WPI2026` AprilTag pipeline has a synthetic calibration with zero
  measured snapshots. A fresh backend publishes its own no-target NT data to the simulated
  robot, and captured settings reach `analyze_vision.camera_settings` by exact camera name.
- This opt-in backend needs the release's fixed web/NT ports 5800/5810 unused on the Linux
  runner. It runs alongside the two container boots, not as another sequential timeline.
  An audit receiver checks the ZIP/database, version and every UI message key. Unknown keys
  beside valid settings are refused as well; previously that branch bypassed the key check.
  The first real-process run caught the harness sending a string for the release's numeric
  network-mode enum. Configuring its local NT destination uses that enum and waits for the
  settings route's asynchronous web-server restart before opening the audit socket.
- `harness/run` and the compatibility `harness/rio/run` share capability selection. macOS and
  Linux without Docker explicitly skip the container/backend while retaining the MINA
  timeline. Linux x86_64 with Docker adds both. No ordinary test needs Docker or a download.
- This retires the pinned-release route/shape and file-camera integration questions, not
  deployed coprocessor version/reachability, USB cameras, measured calibration, actual setting
  changes, hardware acceleration, reboot behavior or sustained load. Those remain shop facts.
