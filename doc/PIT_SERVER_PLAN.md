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
- **It is the foundation of a long-term record.** Once every session is captured and cataloged, questions across a season become possible: how a mechanism's current draw drifted over the year, whether a fix held.

### What it is not

- **Not for the field.** At competition the only team device on the robot's network is the Driver Station laptop, and the field caps the robot's bandwidth. The pit server runs in the shop, on the practice-field network, and in the pit over a tether. It does not run during a match.
- **Not a replacement for the robot's log.** The robot's own log records what the code saw at loop rate; NetworkTables carries the subset the code chose to publish. The recording stands in for a missing log and fills gaps; a pulled log remains the record of truth where one exists.
- **Not a way to control the robot.** The pit server reads. Dashboards that write to the robot, to choose an autonomous routine or tune a value, keep talking to the robot directly.

### How it works, in one picture

```
  robot (NetworkTables server)
        │  one subscription, every change
        ▼
  pit server ─────────────────────────────────────────────────────┐
    │ records each session as a .wpilog file          (the record) │
    │ re-publishes what it hears as a NetworkTables   (the gateway)│
    │   server of its own                                          │
    │ answers MCP tool calls over HTTP                (the server) │
    └──────────────────────────────────────────────────────────────┘
        ▲                                   ▲
        │ NetworkTables                     │ MCP over HTTP
  AdvantageScope, Elastic,            VS Code extension, Claude Code,
  any dashboard                       Cursor, any MCP client
```

The gateway is what lets a dashboard or a visualizer point at the pit server instead of the robot, so the robot has one client. The MCP server is what the assistants talk to; it offers every tool wpilog-mcp has today, pointed at the recordings, plus a few for live data.

### Where it stands

Much of this exists. wpilog-mcp already runs as a background HTTP server with a lock and a PID file; it already reads a file that changes on disk and shows a caller the latest contents, which is how a laptop reads a recording it did not write; it already reads event, match, and team from a log's own data; and it already aligns two recordings of the same session by their data, which is how REV logs are synchronized today. The new pieces are the NetworkTables client, the session writer, the log puller, the gateway, and a handful of live tools.

## Part II: Specification

What follows is for the developers. It follows the project's rules in `CLAUDE.md` and the design principles in [ARCHITECTURE.md](ARCHITECTURE.md): the log decides, the server does not guess, every result says what it used, and every fix comes with a test that fails without it.

### 1. Terms

- **Session**: one robot boot as the pit server saw it, from the first value received after connecting to the last before the connection dropped. The roboRIO's FPGA clock resets on boot, so a session is also one continuous timebase.
- **Capture**: the `.wpilog` file the pit server writes for a session.
- **Gateway**: the pit server's own NetworkTables server, re-publishing the robot's topics.
- **Context provider**: a module that asks a device other than the robot (a vision coprocessor) for its configuration during a session.

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

### 3. Architecture

Code map additions, following the existing package layout:

| Package | What it holds |
|---|---|
| `nt4` | The NT4 wire protocol: message records, the MessagePack value codec, type codes, the time-sync arithmetic. Shared by client and server; no I/O |
| `nt4/client` | The client: connection, subscription, reconnection, the latest-value table |
| `nt4/server` | The gateway: the WebSocket server, per-client subscriptions, announcement and value fan-out |
| `capture` | Session detection, the session writer (DataLog) and the live log it extends as it writes, topic cost accounting, exclusion and thinning policy |
| `capture/context` | Context providers: PhotonVision first |
| `capture/pull` | The log puller: the robot-state gate, the remote listing and transfer over SFTP, resume, throttling, the pull manifest |
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

### 8. Context providers: PhotonVision first

PhotonVision publishes its results to the robot's NetworkTables under `/photonvision/<camera>/`, so detections, latency, and the raw result packet are already in the capture. What is not on the wire is the configuration that gives them meaning, which lives only on the coprocessor:

- camera calibration per resolution, with its reprojection error;
- the active pipeline: type, resolution, exposure and gain, 3D mode, multi-tag, the AprilTag field layout in use;
- the software version, device type, and hardware status.

The provider connects to the coprocessor's web UI backend on port 5800, requests the settings export at session start, subscribes to the UI's WebSocket for settings changes, and writes each snapshot as a JSON entry `/Daemon/PhotonVision/<camera>/Settings` with the server timestamp of the snapshot. The UI's routes are not a published API and move between PhotonVision releases, so the provider is written against a named version, says which in its entry metadata, and a test pins the shape it expects; a mismatch is logged and the provider stands down for the session rather than guessing.

Coprocessor addresses come from configuration (`context.photonvision: [host, ...]`), or from the `/photonvision/<camera>/` topics' presence, which prompts a log line suggesting the configuration. Limelight follows the same shape later, from its HTTP API on port 5807.

The vision tools then read the settings entry of the session they analyze and report calibration error and pipeline mode as stated context, under their own field (`camera_settings`), never as inference.

### 9. Live tools

Three tools, in a `LiveTools` module registered only when capture is enabled, and listed in the catalog under a new `Live` category. They read the latest-value table, the live log, and the session registry; they open no file. Every other tool reaches a live session through its path, as any log, and gets the live log's in-memory index and values (§6).

**`list_sessions`**: `sessions[]`, newest first, each with `path` (the capture), `started_at`, `ended_at` (null while open), `robot` (address), `connected`, `topic_count`, `records`, `bytes`, `bytes_per_sec` (last minute, open sessions), `event`, `match` (when known), `cost[]` (the ten most expensive topics with `records` and `bytes_per_sec`), `thinned[]` and `excluded[]` from configuration, and `imports[]`: pulled logs matched to this session with the method (`by_time_overlap`, `by_correlation`) and the offset. `limits.sessions` when cut. `not_applicable` with a reason when capture is not enabled.

**`get_latest_values`**: `entries[]` (required), returns per entry `name`, `value`, `timestamp_sec` (robot clock), `age_ms` (robot now minus timestamp), `type`; unknown names are listed in `missing` and the status is `partial` when any is missing, `no_match` when all are. `not_applicable` when no session is open, with `last_session` and when it ended.

**`wait_for_change`**: `entry` (required), `timeout_ms` (default 5000, capped at 30000), returns the first value after the call began with its `timestamp_sec`, or `status: ok` with `changed: false` on timeout. One outstanding wait per entry per session; a second returns `error`.

Every result carries `inputs.session` (the capture path). Descriptions say what the values are and are not: the latest published, not measured, values; a stale `age_ms` means the topic stopped publishing, not that the robot stopped. The claim checks apply as to every tool.

### 10. Imported logs: pulling from the robot, and matching to sessions

**Pulling.** The robot writes its own logs to its storage: DataLogManager to `/home/lvuser/logs`, or to `/u/logs` when a USB drive is present; AdvantageKit to the USB drive's `/U/logs`; REVLib's status logger wherever it is configured. The puller copies those directories to the pit server's log directory, under `pulled/<robot>/`, keeping the robot's file names, which encode the time, event, and match that the listing reads. It behaves as `rsync` would, without the tool:

- **Transport**: SFTP over SSH to the roboRIO, as the `lvuser` account (no password by default; a key or a password may be configured). The host key is pinned on first contact per robot and a change is reported, since a reimaged roboRIO has a new one. The SSH client is a library dependency, the smallest maintained one that serves; the choice is an open question (§15).
- **The gate**: a transfer step runs only while the robot has been disabled for at least a configured settle time (default 5 s), read from the control word the robot publishes (`/FMSInfo/FMSControlData`, the enabled bit), and while the pit server's NT4 connection is up, so the state is current. The moment the state is anything else, the step in progress finishes its current block and the transfer pauses; it resumes from where it stopped when the gate reopens. With no NT4 connection the puller does nothing: it will not guess that a robot it cannot hear is idle.
- **What to copy**: the remote listing (name, size, modification time) against a manifest of what has been pulled (`pulled/<robot>/.pull-manifest.json`: remote name, size, modification time, bytes copied, verified). A file not in the manifest is new; one whose size grew is fetched from the bytes already copied, since the logs are append-only; one whose size shrank or whose modification time went backward is treated as a new file under the same name, kept beside the old copy. The file the robot is writing now is copied like any other and grows across passes; the reload path handles the local copy's growth. When DataLogManager renames an open log once the Driver Station supplies the time and match, the old name disappears and a new name appears with the same prefix bytes; the puller recognizes the prefix (the first 64 KB match) and renames the local copy rather than copying again.
- **Throttle**: a configured rate cap (default 1 MB/s), enforced on the reading side by pacing block reads, and one transfer at a time. The roboRIO's processor is small and SSH encryption costs it; the cap protects the robot as much as the network.
- **Verification**: after a file's size has been stable on the robot for one pass, the local copy is loaded through the normal path; a copy that loads, with its scan ending at the file's end, is marked verified in the manifest. A copy that does not is fetched again from the start, once.
- **Deletion**: never, in this version. Freeing the robot's storage is a later, opt-in action that requires a verified copy and says what it removed.
- **Reporting**: `list_sessions` gains `pulls[]` per robot: the last pass, files copied, bytes, and files waiting for the gate; the server log says when a pull starts, pauses, resumes, and completes.

**Matching.** A pulled log of the same boot is matched to a session by the machinery REV synchronization uses: names nominate (a Driver Station entry both carry, a battery voltage, a loop count), the data decides (cross-correlation), and the offset must be near zero since both are on the FPGA clock. A match is recorded in a manifest beside the capture (`<capture>.session.json`: the session's facts and its imports) that `list_sessions` reads and the listing shows as `session`. The pulled log is the authoritative record of its session; the capture stands in where no pulled log exists and fills the gap where the pulled log is truncated. No database: manifests are files, in keeping with the project's "nothing to operate" goal; a catalog over many seasons is a later decision, made when the files are many.

### 11. Network exposure

The pit server binds to an address on the team's network (`WPILOG_HTTP_BIND`, as today) and serves the MCP endpoint, the gateway, and `GET /health` without authentication. The `Origin` check stays: it protects a browser on the network from being used against the server by a web page, and costs non-browser clients nothing.

The standalone guide gains a short section for teams that want more: an nginx configuration that terminates TLS and asks for a password in front of the MCP endpoint, with the pit server itself bound to loopback behind it. The gateway's NT4 port is a separate matter; a dashboard cannot present a password to it, and the protocol has no place for one, so it is exposed on the private network or not at all.

### 12. The VS Code extension

- A setting `wpilog-mcp.pitServerUrl` (order after the TBA key). With it set, the extension registers the pit server with VS Code's MCP registry as an HTTP server, beside the local one, and adds an HTTP entry for it to a robot project's `.mcp.json` for Claude Code, under the same rules as the local entry. The entry holds a URL and nothing secret, so a team may commit it: every teammate's Claude Code then finds the pit server from the project.
- Both servers' instructions and `get_server_guide` say which server is which: the local one for files on this laptop, the pit server for the team's sessions and for anything live.

### 13. Milestones

Each leaves the project working and tested on its own.

1. **NT4 protocol and client** (§4), with the gateway's core as its test fixture.
2. **Session writer and live log** (§5, §6): captures appear in the log directory, and every existing tool works on an open session from memory and on a finished one from its file. Stress test on a real robot in the shop.
3. **Log puller** (§10): the robot's logs arrive on their own; tested against a real roboRIO in the shop before it is on by default.
4. **Live tools** (§9), and the extension's pit server setting (§12).
5. **Gateway** (§7) complete: dashboards and AdvantageScope pointed at the pit server.
6. **PhotonVision provider** (§8) and the vision tools' `camera_settings`.
7. **Session manifests and import matching** (§10).
8. **Incremental rescan** (§6, secondary path): a growing capture read by another process.

### 14. Testing

- **Protocol**: the client and the gateway against each other in-process, over a loopback WebSocket, on every fixture log replayed as a robot would publish it. Message encoding is checked against hand-encoded frames taken from the protocol document.
- **Capture fidelity**: a replayed fixture captured through the client and the writer must pass the differential reader against the fixture it came from: same entries, same values, same timestamps.
- **Sessions**: reconnection with continuing timestamps resumes; with restarted timestamps begins a new session; the capture is renamed when event and match arrive.
- **Live log**: a session replayed through the writer answers every tool the same as a fresh load of the finished file (entries, sample counts, statistics, time range); a reader that starts mid-session sees a consistent prefix while the writer appends from another thread, checked under the stress test's concurrent calls; values past the hot window read from the file equal the values that were in memory.
- **Growing files** (§6, secondary path): the resumed scan equals a fresh scan.
- **Gateway**: a client with `all` receives every change; one without receives the latest per period; a `publish` from a client changes nothing upstream; the time-sync answer is robot time within the measured offset's error.
- **Puller**: the gate, the listing comparison, resume offsets, the rename-by-prefix rule, the throttle's pacing, and the manifest are pure logic tested against a fake remote in memory: a file that grew is fetched from its old size; a file that shrank is a new file; a transfer in progress pauses within one block of the state leaving disabled and resumes at the same offset; the manifest round-trips. The SFTP client itself is covered by an opt-in test against a real roboRIO, named by a property, like the real-log suites.
- **Live tools**: the claim checks, the conformance sweep (with capture enabled on a replayed fixture), and determinism.
- **Windows**: the capture file is open for writing while the server reads it; the tests cover that on Windows, where a mapped file cannot be replaced but can be appended to and read.

### 15. Open questions

- The AdvantageKit topic layout versus its log layout (§5): verify on a real capture before adding resolver conventions.
- Whether thinning should ever be on by default for known high-rate topics, or stay a configured choice. The proposal is configured only.
- Whether a capture's `_cap` marker should instead be a directory convention. The marker keeps the listing's name parsing unchanged.
- When a catalog beyond manifest files is warranted (§10).
- The SSH library for the puller (§10): the maintained JSch fork is small; Apache MINA SSHD is large but has a test server. The choice weighs the standalone install's size against testability.
- Whether the puller should also copy files the server does not read, such as CTRE's `.hoot` signal logs, so that the robot's storage holds nothing the pit server lacks. The proposal is a configured list of patterns, with `.wpilog` and `.revlog` by default.
