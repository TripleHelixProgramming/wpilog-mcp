# Architecture

This document explains what wpilog-mcp is for, the principles its design follows, the reasons for them, and how the code carries those principles out: how it reads logs, manages memory, caches results, synchronizes REV logs, and serves several clients at once.

Other documents cover what this one leaves out. [TOOLS.md](TOOLS.md) describes every tool and the fields of a result. [STANDALONE.md](STANDALONE.md) and the [extension's README](../vscode-extension/README.md) cover installation and configuration. [DEVELOPMENT.md](DEVELOPMENT.md) covers building, testing, and adding a tool.

The code is described here by what each part does, not by class names, which change. The [code map](#code-map) says which package holds each part. Where this document and the code disagree, the code is right; please fix the document.

## Goals

wpilog-mcp exists so that an FRC team can ask an AI agent an engineering question about its robot, such as why the robot browned out in a match or whether a swerve module is slipping, and get an answer that rests on the robot's own logs. The people asking are students and mentors, often in the pit between matches with little time, and afterwards with more.

That purpose sets the goals:

- Give no false or guessed answer. Every response must rest on facts that can be checked in the log. A wrong number is worse than no number, because the agent builds an explanation on it and the team acts on that explanation at a competition.
- Work on any team's log. The review that shaped the current design put it this way: "An agent should be able to analyze a log correctly without ever seeing the code that produced it" ([ROBUSTNESS_REVIEW.md](ROBUSTNESS_REVIEW.md)). Logs differ by framework (AdvantageKit, WPILib's DataLogManager, CTRE's swerve template, PathPlanner), by team, and by situation: a practice session is not an FMS match. So the server assumes nothing about a team's names. What a team's entry measures is another matter. The log does not record it, so the server does not supply it; the robot's source code does, and the server sends the agent there.
- Lead the model toward sound reasoning. Language models find an explanation that fits the data even when the data can't support it. The server should make the scientific method the easy path: check the premise, test a rival explanation, and claim no more certainty than the evidence allows.
- Be easy to run. The server is one JAR that needs only Java 17 (the WPILib JDK is enough), with no database and no service to operate. The VS Code extension bundles it and finds Java and the logs by itself.
- Handle real logs: hundreds of megabytes, cut off when the robot lost power, holding a team's own struct types.
- Be efficient and correct: quick enough for a conversation on a team's laptop, and able to serve several clients at once without one client's call disturbing another's.

The server analyzes recorded logs and, when capture is configured, the fixed prefix of a running robot's NetworkTables capture. It keeps no database of past matches: what it knows about a robot comes from the log files. It runs no scripts: when the tools can't express an analysis, `export_csv` hands the data to the agent's own tools.

## Design Principles

Most of these principles were learned from failures. A review of version 0.8.2 against a real practice log found tools that returned plausible wrong answers, and empty results that an agent could not tell from "no problem found". [ROBUSTNESS_REVIEW.md](ROBUSTNESS_REVIEW.md) lists what it found, and [ROBUSTNESS_PLAN.md](ROBUSTNESS_PLAN.md) records what was changed. The examples below come from that work.

### Tools return measurements, and the model does the reasoning

Tools return statistics with their sample counts, timestamps, event lists, and correlations with p-values. There is no "diagnose my robot" tool. A verdict hides the evidence it rests on. With building blocks, the model has to combine facts across several calls, and a person can check each step.

Version 0.8.2's battery tool answered "URGENT: Replace battery immediately" from two voltage dips shorter than 100 ms. The brownouts were real, but their cause was a hypothesis, and the tool stated it as a conclusion.

A few tools do give a summary for a pit crew in a hurry: a battery health score, a loop-timing score, a CAN health level, a brownout risk. That is a deliberate trade-off. Each reports the facts it summarizes, and the rule that decided the summary, beside it.

### The server does not guess

When a tool needs a particular signal, such as the battery voltage, the robot pose, or the autonomous chooser, it uses an entry only when the caller named it, when it follows a convention a logging library publishes, or when it is the only entry of the right type. An entry that merely has a suggestive name is listed as a candidate for the agent or the user to confirm, and is not used. Most choices can be overridden with a parameter; an entry named that way but missing or of the wrong type is an error, not a reason to fall back on something else.

A word in a name is not evidence, and neither is the shape of the data. The swerve tool takes the measured and setpoint module states from the names the swerve libraries and templates publish, each pair from one table. The mechanism tool analyzes only the entries passed to it, and uses a mechanism's name to list candidates. The vision tool reads what the vision template and the vision libraries publish under their own names. An entry that only has the content or the name of vision data is listed as a candidate and is not decoded. In each case the result names the entries it used and how each was chosen.

What a candidate holds is settled where the entry is logged, in the robot's source code. That code says which mechanism an entry belongs to, its units, and whether it is measured or commanded. The server's guidance tells the agent to read it and, when the code is not at hand, to call any mapping taken from a name an assumption.

The rule holds beyond entry names. When REV logs are synchronized, names only nominate pairs of signals to compare, and the data decides which pairs are used. When a log records loop time under none of the known conventions, the loop-timing tool says so; it does not derive loop time from whatever periodic signal it can find.

A guess is right on the logs a tool was written against and wrong elsewhere, and the wrong answer looks just like a right one. Version 0.8.2 took the first entry with "voltage" in its name as the battery. On one log that was the roboRIO's 5 V rail, and the tool reported a high brownout risk with 46,242 samples below the threshold. The same version's search for CAN errors looked for "can" anywhere in a message, which also matches "scan" and "cancel". The REV log matcher of the time treated every AdvantageKit output entry as a motor's output, because each such path (`/RealOutputs/...`) contains the word "output".

The tools that still judged by a name or by content after that review failed the same way on real logs. A planned autonomous trajectory is a struct array of timestamps and poses, as a camera's observations are. The vision tool reported it as a camera in 86 of Team 2363's 88 logs, with a latency of minutes, and decoding its millions of samples took one call on a 688 MB log past 3 GB of heap. In logs other teams publish, a gyro's struct has yaw and pitch fields and was reported as a camera target. The mechanism tool took a camera's `targetYaw` for a setpoint. The swerve tool, given two target arrays beside the measured states, compared the measured states with whichever was declared first.

### The log decides

What a tool needs to know about a log comes from the log itself:

- Struct values are decoded with the schemas the log records. A team's own structs decode, and so does a team's edited copy of a template struct, which a layout built into the server would get wrong.
- The timeline comes from the Driver Station data in the log. Nothing assumes a match: a log may hold any number of enabled periods, autonomous may never be logged, and FMS may not be attached.
- Thresholds come from the log when it records them, such as the roboRIO's brownout voltage. The season comes from the log's own clock when it has one, and otherwise from the build date it records, a year in its file name, or the current year; the result says which.
- Every number in the log can be analyzed. The statistics and query tools read a struct field or an array element as they read a numeric entry.

On the practice log the review used, version 0.8.2 reported a single enabled period from 40 to 840 seconds where the log holds four, and assumed a brownout threshold of 6.8 V where the log records 6.75 V.

Game rules are the one thing a log cannot supply. They are bundled with the server, and `get_game_info` names the game manual revision its data came from and says that it is not from the log.

### Every result says what it is and what it used

A result has a status: `ok`, `partial`, `not_applicable`, `no_match`, or `error`. An analysis that finds nothing to analyze does not return an empty success. It says that it does not apply, or that it found no match; what it looked for; and how to point the tool at the right data, because finding nothing is not evidence that nothing is wrong. An empty success is the most damaging failure for an agent, because it looks like an answer: version 0.8.2's vision tool returned `success: true` with an empty list for a window in which the robot's pose moved 60 cm while the robot sat disabled. A tool that only reads or lists, such as `read_entry` or `search_strings`, can still answer with a count of zero, which is a plain statement of what the log holds.

A result also names the entries, fields, and time windows it was computed from, the sections it could not produce, and the true total of a list it cut short.

Where the honest answer is "unknown", the result says so. A value that can't be computed is `null`, not zero and not `NaN`. A REV log whose synchronization failed yields no data, rather than data on the wrong clock. A Blue Alliance outage is reported as an outage, not as a match without results. A bad argument, such as a start time after the end time or an entry that does not exist, is an error that says what is wrong.

### Nothing is selected or left out silently

A cap or a priority applied to a list is a judgment the model cannot see. A list of the first hundred console messages can leave out the only errors that matter. So for console text the server reports exact counts and a summary of the distinct messages with the count of each, and offers the complete list with search and paging (`search_strings`). Emphasis comes from the counts, not from a selection the server made.

The same goes for what the server could not read. Records that failed to decode are reported by every tool that read them, the statistics tools count the values that were not finite numbers, and every result computed from a log that was not read to its end says so. Most robot logs end inside their last record, because the robot is switched off while logging. That alone is a note in the result's metadata and not a warning, since a warning on nearly every result teaches a model to ignore warnings. A log that lost more than its last record gets a warning.

### Confidence is calibrated to the evidence

Results that rest on statistics carry a data quality score with a reason for every penalty. It is computed on the samples in the requested time scope, with non-finite values counted against it. Before the score is computed, the sampling is classified: AdvantageKit and NetworkTables log a value when it changes, so a long interval in such a signal is usually a held value, not missing data. The result describes it as a hold, and irregular timing counts against a signal only when the signal is periodic. Long holds still lower the score, because statistics weigh samples, not time, and so under-represent a value held for a long time.

The same lesson applies to the quality score: in version 0.8.2 nearly every result scored 0.50, "low", including a loop-time signal with 59,217 samples at 47.8 Hz.

Quality bounds statistics, not observations. A logged brownout flag or an error line is a fact and needs no statistical caveat. So a tool that reports only logged events, such as the Driver Station timeline, carries no quality score, and where a result holds both events and statistics, its low-quality warning says that it applies to the statistics only. The server teaches the model three tiers: a discrete event is a fact, a mean or a correlation is an inference bounded by its confidence level, and a cause outside the telemetry, such as wiring or wear, is a hypothesis that needs a physical check.

A confidence level never claims more than its evidence shows. REV log synchronization reports high confidence only when at least two signal pairs agree to within a few milliseconds.

### The server coaches the model's reasoning

Guidance reaches the model in three places: in the descriptions of the analysis tools (what their numbers do and don't show), in results that rest on statistics (the confidence level, interpretation guidance in hedged language, suggested follow-up calls, and a reminder that one match may not generalize), and in server-level rules sent when the client connects.

The server-level rules are concrete. They are phrased in terms of this server's outputs: confirm with `get_ds_timeline` that the event in the question happened before explaining it, and quote only numbers a tool returned. The most important rules come first, and the whole text fits the 2 KB that Claude Code keeps. Rules that cost effort, such as testing a proposed cause against a rival explanation, apply only to "why" questions, so that a lookup gets a direct answer; a pit crew has little time. One rule is about meaning: an entry's name does not prove what it measures, so the agent reads the robot's source code where the entry is logged, or calls the mapping an assumption. No rule forbids the only way around a limit: the rule against computing statistics by hand names `export_csv` as the way out when no tool can read the data.

Some clients drop these rules, so `get_server_guide` returns the full method as a tool result, and its description asks the agent to call it first. [TOOLS.md](TOOLS.md#server-instructions) describes both.

### Each call stands alone

Every tool that reads a log takes the log's path. There is no "current log", so analysis does not depend on an implicit selection left by an earlier call. Session leases control shared file access, not which log a tool selects. REV log data is the exception, and a visible one. It is ready only once the background synchronization has finished, and until then the tools say so. An offset set with `set_revlog_offset` is shared the same way (see [Synchronization](#synchronization)).

### Claims are checked

A tool's description and the documentation promise only what the code does, and tests hold them to it: each tool's schema against the parameters its code reads, the documented parameters against the schema, and every output a description names against real results.

A test run without failures shows that the tools keep their contract, not that their numbers are right. So the numbers are checked against sources that share no code with the server: a second log reader written from the format specification, and values computed separately with WPILib's Python reader and NumPy. That check found defects every other test had missed, among them a healthy log reported as truncated because it held records before time zero. Recomputing the reference values also showed that two of the robustness review's own reference values were wrong. The rule for every fix is a regression test that fails without it. [DEVELOPMENT.md](DEVELOPMENT.md#testing) describes the tests.

## Technologies

| Technology | Used for |
|---|---|
| Java 17 | The server. |
| WPILib's `wpiutil` library | Reading WPILOG files and parsing struct schemas. The pure-Java capture writer emits the documented file format. |
| Gson | JSON for the protocol, tool arguments, and results. |
| SnakeYAML | Reading the configuration file. |
| Caffeine | The in-memory cache of loaded logs and each log's cache of decoded values. |
| MessagePack | The disk cache of REV log sync results: whole objects written once and read back, which needs a compact file format and not a database. |
| SLF4J with its simple logger | Logging, to stderr. |
| The JDK's built-in HTTP server | The HTTP transport. It is enough for local use by a few clients and adds no dependency. |
| Arrow's format library (`arrow-format`, with the Flatbuffers runtime) | The Flatbuffers schema and message headers of the Arrow IPC stream the data endpoint writes. Only the generated format classes: the arrays' layout is the server's own, on the heap. |
| The JDK's HTTP client | The Blue Alliance API. |
| The JDK's WebSocket client | The NT4 capture client. |
| Maintained JSch (BSD-3-Clause, with ISC jBCrypt) | SSH exec and SFTP in `capture/pull`; JDK 17 supplies Ed25519 and RSA SHA-2. |
| Java-WebSocket (MIT) | RFC 6455 framing for the NT4 gateway fixture; confined to `nt4/server`. |
| Gradle with the Shadow plugin | Building one self-contained JAR. |
| JUnit 5 | Tests. |
| Apache MINA SSHD and EdDSA (test only) | A synthetic roboRIO's SSH/SFTP server on loopback, including actual host-key negotiation and exec channels. |
| TypeScript and the VS Code extension API | The VS Code extension. It has no runtime npm dependencies, and its tests use Node's built-in test runner. |

The JAR has no other runtime dependencies.

The separate `harness/robot` GradleRIO project runs a headless WPILib simulation with its own
desktop natives. It is outside the server build and ships in no install. The opt-in shop harness
connects it, a temporary SSH device and the packaged server, and checks HTTP MCP results against
a timeline; [DEVELOPMENT.md](DEVELOPMENT.md#the-shop-harness) gives its scope and hardware limits.

## Code Map

```
  MCP client (Claude Code, VS Code, Claude Desktop, ...)
        |  JSON-RPC 2.0 over stdio, or over HTTP
  +-----v-------------------------------------------------------------+
  | Transport       stdio server, or HTTP server with sessions        |
  | Protocol        message handler: initialize, list tools, call     |
  | Tools           registry of tools, on a shared base and services  |
  | Logs            log manager, lazy logs, directory listing,        |
  |                 path security                                     |
  | Integrations    REV log parser and synchronizer, The Blue         |
  |                 Alliance client, game data                        |
  +-------------------------------------------------------------------+
```

The server's code is under `src/main/java/org/triplehelix/wpilogmcp/`:

| Package | What it holds |
|---|---|
| (the root) | Startup: reading arguments and configuration, and wiring the parts together |
| `mcp` | JSON-RPC, transports, sessions, loopback directory/key registration, HTTP data/import endpoints, and the tool registry |
| `tools` | The tools, grouped by subject, and what they share: the base every tool runs through, the result builder and result contract, the signal resolver, time scopes, field paths, data quality, and the guidance text |
| `log` | Finding and loading logs: the log manager, the lazy log and its scan, the writer-built live log, and the directory listing. It also finds the REV logs that belong to a wpilog and runs their synchronization. `log/struct` decodes structs from schemas, and `log/subsystems` holds the cache of loaded logs, the record decoder, path security, and an older parser that decodes a whole log at once, kept as a fallback |
| `store` | File manifests, content inspection, and one import queue per store, and abandoned-capture recovery; robot identity, session placement, provenance, duplicate detection, and unmanaged files |
| `revlog` | REV log parsing. `revlog/dbc` reads CAN database (DBC) files and decodes frames with them |
| `sync` | Signal synchronization and combined logs; transport-independent content-checked transfers, pacing, pull manifests, and verification |
| `cache` | The disk cache of sync results, the cache directory, file fingerprints, and the older disk cache of parsed logs, which is no longer used |
| `tba` | The Blue Alliance client, and adding match results to log listings |
| `game` | Bundled game data |
| `config` | File configuration, session leases, daemon lifecycle, and the shared install/refresh implementation |
| `nt4` | NT4 control/value records, the spec-written MessagePack subset, type mapping, and time-sync arithmetic; no network or file I/O |
| `nt4/client` | JDK WebSocket connection and fallback, ordered listeners, subscription, retry/keepalive timers, and concurrent latest values |
| `nt4/server` | Pure subscription/announcement/value fan-out and the loopback WebSocket adapter; a robot fixture first |
| `capture/pull` | Disabled-state gate, SSH/SFTP adapter, and the daemon coordinating transfer and device identity outside the NT4 loop |
| `capture/context` | Device identity from the HAL sources, with source provenance and capture context |
| `capture` | Pure-Java WPILOG output and writer ownership leases, session clock continuity, ordered recording, topic policy/cost accounting, and the service/index observer connecting the NT4 listener to the store and log manager |

Three more places: `src/main/java/edu/wpi/first/util/datalog` holds one small class placed in WPILib's own package, which gives the server access to WPILib's record-level reading. `src/main/resources` holds the built-in CAN database and the game data. `vscode-extension/src` holds the extension.

## File transfer

`sync.FileTransfer` advances at most one 64 KiB block per step, with an injected monotonic clock
and gate. It performs no sleeps and owns no socket or thread. A read-only transport interface serves
SFTP now and the mirror later; a local interface supplies append, archive, rename, verification,
placement, and atomic manifest writes. One caller owns a step; a concurrent call is refused.
One remote listing serves a whole pass across files and blocks. A long-running pass refreshes after
ten seconds; verification requires stable size and modification time across distinct listings.

`pull.json` remembers each remote name, size, modification time, copied count, verification state,
and local path, plus retired generations. Growth resumes only after a hash of exactly the held
prefix matches. Without command execution, the fallback compares the last 64 KiB of that range;
this is weaker evidence than a whole-prefix hash. Shrinkage, rewound time, or mismatched content
archives the old copy and starts a new file. A disappearing name and a new name with unique matching
content can rename the held copy. Names alone never establish continuity.

The gate pauses between blocks; resuming rechecks the held prefix. Read pacing includes fallback
comparison bytes. After a stable listing pass, the ordinary readers must reach a clean EOF before
placement; a recoverable truncated log is not verified. One failed verification permits one complete
refetch. The REV parser has a strict verification entry point beside its ordinary recovery behavior;
sync cache format 6 invalidates older reader/synchronization results. There is no remote deletion operation.

`capture/pull.PullCoordinator` owns the SFTP connection and runs on a separate daemon. It reads
the ordered capture listener's `/FMSInfo/FMSControlData` enabled bit: only a continuously disabled
robot, settled for five seconds with NT4 still connected, permits work. Unknown state and each new
connection close the gate. Polling, retries and pacing use a scheduler; neither SSH nor transfer
storage runs on the NT4 loop. Shutdown includes the transport in the capture's existing deadline.

The maintained JSch fork is one dependency with no required crypto provider on Java 17. Its
versioned Ed25519 classes require the packaged JAR's `Multi-Release` manifest flag, checked by
initializing the actual packaged providers in an isolated classloader. Host fingerprints are kept
per serial and address. First contact is trusted. A changed key is reported before authentication:
empty-password authentication may continue, but a configured password or key requires explicit
`capture.pull.ssh.accept_changed_host_key: true` or removal of the pinned fingerprint in `robot.json`.
The subsequent serial reading selects the pull manifest. A changed key alone does not identify a device.

Connect and channel-open timeouts stay at five seconds. Five-second SSH keepalives (three missed
replies allowed) keep a live connection available while a hash has no output. Each hash command has
a separate deadline: 30 seconds plus one second per 256 KiB, rounded up. Expiry closes its channel,
and a completed command cancels its timer. Hashing reads the entire held prefix on the robot and
costs CPU and storage bandwidth; the transfer byte cap does not pace that local work.

`store.PullStore` uses the existing queue, path validation, move reservations and reader release.
Partial files stay in `robots/<serial>/pulled/` and do not appear as logs. Verified files move into
session `robot/` directories with hash, size and remote provenance. Confirmed growth returns a file
to staging and removes its obsolete session hash before append. Moves keep durable tool-path aliases.
A file's logged serial wins over the SSH device reading, with a recorded conflict; matching never
crosses known serials. Addresses select a connection, not a robot's lifetime identity.

Before loading candidates, manifest session ranges nominate overlapping sessions, using the same
clock slack as import: two hours, or sixteen for filename clocks without a zone. Unknown clocks,
including REV's unset 1970 filename clock, cannot exclude a candidate.
Names nominate shared numeric entries (including DataLogManager's `NT:` prefix) or REV signal
pairs; the existing correlation machinery decides. Flat, ambiguous or weak data is insufficient.
A strong match must have an offset within 250 ms of zero and identify one session. Capture files
anchor their session; already matched members cannot chain small offsets into a larger clock shift.
A session without a capture may use an unmatched WPILOG as its anchor. A missing logged serial on
either side records `data_alone`, even when device identity already limits the candidate robot.
No unique match creates a new session, using the file's clock evidence or modification time with
that basis. An open capture has no completed hash to invent. A new contact checks held content even
when a reused file has identical size and mtime; a changed listing invalidates an in-progress proof.

## NT4 foundation

The NT4 layer was first exercised as an unwired fixture; the configured capture service now uses
its client. The client and a loopback gateway exercise each other on every generated fixture to
pin the delivery order the capture writer depends on. The gateway's core
takes messages and explicit times and returns deliveries; it performs no I/O under its short state
lock. The adapter sends those deliveries on its own daemon loop. The client has a separate daemon
loop, so every announcement, removal, property update, and value reaches its listener in order on
one thread. The capture writer implements that listener.

The MessagePack subset is written from the format specification, like the Arrow data writer, and
checked against hand-encoded bytes, including every integer width, floating-point bits, variable
length headers, and concatenated NT4 messages. It is separate from the existing MessagePack disk
cache library. Untrusted lengths, nesting, and message size are bounded; unsupported extension
formats are rejected. The announced type string survives unchanged: a binary code chooses a decoder,
not a schema or an interpretation. Struct schemas are ordinary binary topics.

The client uses Java 17's WebSocket to avoid another client dependency. Java-WebSocket 1.6.0 supplies
only the server framing (140,686-byte JAR, MIT, with SLF4J already present); its license is retained
under `META-INF/licenses/`. NT4.1 is preferred, with NT4.0 negotiated on the same handshake. Only 4.1
connections receive WebSocket pings. Initial time synchronization precedes subscription; later
measurements run every three seconds. The clock estimate uses the newest minimum-RTT measurement
in the last 30 seconds. Failed address sweeps retry after 1, 2, 4, 8, then 10 seconds indefinitely.
Each sweep tries the last successful address first, then the others in configured order; until a
connection succeeds, sweeps start with the first configured address.

The lossless listener sees every received value, including an older timestamp. In accordance with
WPILib's protocol, the latest table keeps the greatest timestamp (ties replace), honors `cached: false`,
and removes values on unannounce or disconnect. The gateway deduplicates overlapping
subscriptions, batches at the client's minimum requested period, and honors `all`, `topicsonly`,
and exact/prefix matching. Downstream publications are private acknowledgement sinks: values never
enter the upstream table, and property replies state the unchanged properties. Full gateway
integration, metadata topics, and real dashboard interoperability remain the later gateway milestone.

## Capture writer

The capture writer is the NT4 client's listener, so announcement, value, and finish records have
one writer and one order. Its pure-Java WPILOG output follows WPILib's file specification because
the native DataLog writer cannot run in the Java-only install. The independent fixture writer,
the differential reader, and wpiutil's reader check its bytes. Storage and live-index observers
receive complete writes and their byte offsets; context providers can join this loop later.
An optional `capture` configuration starts this listener beside the HTTP server. Missing robots
do not delay HTTP startup; shutdown drains tool calls, closes the capture, then retires log readers.
The capture store uses the existing store queue, path validation, move reservations, and manifests.
Creation is synchronous. Later updates use immutable snapshots and coalesce into one pending task;
changed facts queue immediately, ordinary progress at most every five seconds. Imports cannot
stall the NT4 event loop. Shutdown waits at most 30 seconds for the writer and last close snapshot, including its hashes. A timeout is logged for the next startup sweep.
An additive `open_capture` field represents a growing file without inventing a hash or weakening
the finished-file checks. At close it becomes a normal `files` member. Event and match facts are
queued when they change. Cosmetic renames wait for close and reader release on every platform:
otherwise an asynchronous directory move can race a rollover open or a mapping growth. Address directories carry robot basis `address`, since an endpoint is not a stated robot identity.
A device serial learned during recording is written as context in the current file. Its manifest
update is asynchronous; promotion waits for session close and reader release, like the event rename.
New sessions already use the known serial. A reader that prevents the move leaves the directory in
place until a later close or contact. Store moves keep old paths usable by tools,
with the same security and file-change checks as the destination.

Robot identity uses the resolver's metadata roles for `/SystemStats/SerialNumber` and
`/SystemStats/Comments`, including their `NT:` forms, wherever a log lives. Listing reads only
the first 2000 records; import inspection can find later identity. Its own logged serial
wins over a connection's device serial; disagreements are recorded in the manifest and server log.
`capture/context` reads the HAL's sources, records the device evidence as the first JSON context
entry at file start and again on resume, and the store remembers addresses and SSH key history by
serial. An address or host key can change; neither replaces the serial. `robot_candidates` is only
a hint: an exact logged team, sorted entry name/type set, or REV CAN id/type inventory must match
one known serial. Only store files participate, from fingerprints saved by import inspection in
the additive `robot_fingerprint` manifest field. Older manifests without it supply no hint. Listing
never opens a log to compute candidate evidence. Contradictory unique hints are omitted. No candidate
changes placement.

The service queues recovery after HTTP is listening, then starts NT4 only when the sweep completes.
Scanning and hashing an abandoned capture cannot delay the daemon health endpoint. Each `open_capture`
is claimed with the same persistent sidecar lease as the writer; a live writer is skipped. The
OS lock covers other processes and the local claim avoids opening a second channel to a held
lock. The lease is separate from the data: closing mapped readers must not release a writer's
ownership. A dead process loses its lease. Recovery scans a readable file before hashing it,
preserves an incomplete-tail flag and previously closed files, and uses file modification time
for `ended_at`, with `end_reason` set to `server stopped while recording`. Failed reads leave the
file open with a manifest/log reason. Neither successful recovery nor a failed read changes the
capture bytes. The sidecar inode stays in place for later writers and is catalog metadata.

Session continuity uses time-sync replies, rather than old retained topic timestamps. A continuing
clock resumes the closed file with fresh entry ids; a reset or a discrepancy beyond five seconds
starts a new session. Flushes and five-minute topic cost reports run on the same injectable event
loop. Exclusion and thinning are explicit policy, and thinned entries record their period.
Each capture file is bounded by `capture.max_file_bytes` (default 1 GiB), including reserved finishes.
Rollover closes every active entry and starts the next numbered file in the same session with fresh
entry ids and a new live index. Retained schema definitions seed the new file, explicitly marked in
entry metadata and timestamped at the rollover's server time, so a file can decode its own structs without inheriting the boot-time schema's range. Tools continue to read one file per call.
A writer IOException closes recording with its reason and keeps the client connected. Only another
robot clock permits recording again. Record writes roll back an incomplete tail when possible;
failed rollback forbids appending finishes. Session `end_reason` preserves the reason without changing
the store format version.

## Live log

The capture writer already knows each declaration, complete record's offset, value, and timestamp.
`CaptureIndex` passes those facts to `LiveLog`; rescanning a growing file would duplicate that work
and repeatedly invalidate tool calls. Entries retain announcement order and WPILOG ids. Repeated
names and types, forward clock jumps, struct schemas, and decode problems follow the finished
reader's rules, so eviction and a normal file load preserve answers.

Each entry has a chunked append-only array and a volatile length. The writer publishes complete
slots, then a global boundary containing the record sequence and data time range. A call captures
that boundary and takes each entry's length on first access, capped at the boundary. Its values,
entry table, and schemas therefore come from one consistent prefix. `inputs.session_time_range`
names that prefix in seconds; `compare_matches` supplies a range per live input path. Data reads
take no writer lock. Short lifetime transitions and the existing file leases protect retirement.

The default ten-minute hot window keeps the client's decoded values, with array/struct conversion
when a tool requests them. Expiry follows server time sync as well as new data, so idle topics age
out too. Expiry is batched on the 250 ms flush tick, at most four growth remaps per second even
with a zero hot window. Before discarding a hot value, the writer ensures a read-only mapping covers its complete
record. Older values use their offsets; replacing a mapping waits for its last atomic reader
reference before unmapping it. The write channel remains open beside the mapping on Windows.

The manager pins active captures outside its evictable cache and supplies a fresh prefix per call,
without file-change checks or after-call discard. At close the same index enters the ordinary cache;
eviction releases its mappings after in-flight uses. A later load uses `LazyParsedLog`. The writer
keeps the last session's index available for clock-continuous resumption and remaps it if evicted.
The mapped-file size limit remains the existing reader's 2 GB limit. No other-process incremental
rescan is implemented here.

## Life of a Tool Call

Each step is explained in the sections that follow.

1. A client sends a `tools/call` request over stdio or HTTP. The transport hands it to the protocol handler, which finds the tool by name.
2. The tool runs inside a wrapper that every tool shares, which turns an exception or an out-of-memory condition into an explained result.
3. A tool that reads a log asks the log manager for the log at `path`. The path is checked against permanent directories and live session leases. A log already in memory is returned at once, once a look at the file's attributes shows it is still the file the log was read from; a file that changed is loaded again. Otherwise the file is mapped into memory and scanned once.
4. The tool finds the signals it needs through the signal resolver. A tool that takes a scope or windows turns them into time windows through the shared scope handling.
5. It reads the values it needs. An entry's values are decoded the first time any call asks for them, and then cached.
6. It computes its result and, in most tools, builds it with the result builder: the status, the inputs, what was skipped or shortened, and data quality where the result rests on statistics.
7. The base that log-reading tools share looks at the file again, and discards the result with an explained error if the file changed while the tool read it. Otherwise it adds a report of any records that failed to decode, a note when the log was not read to its end, and, to a success, the entries the tool read; a session that used the log before its file changed is told once that it was reloaded. The wrapper then enforces the result contract.
8. The protocol handler adds the execution time and returns the result as the text of the reply.

## Startup and Configuration

The server starts in one of three ways:

- From a configuration file: with no arguments, or with `start <name>`. The named server's settings come from the file. This is what the installers set up and what MCP clients run. [STANDALONE.md](STANDALONE.md#configuration) describes the file, where it is looked for, and how a server inherits the top-level settings.
- From command-line flags, with environment variables as their defaults and no configuration file. [STANDALONE.md](STANDALONE.md#command-line-flags) lists the flags.
- As a background HTTP server: `start <name>` for a server whose transport is HTTP starts a second process and returns once it answers. Two starts of one server never spawn two processes, and the background process gets the same heap the launcher would give it. `stop <name>` ends it, and `connect <name>` relays a stdio client to it, starting it first when it must, so that one background server can serve every client on a machine.

The `install` verb writes the standalone layout from its running JAR, with launcher and configuration templates packaged as resources. The shell installers download a JAR and delegate to it; Gradle does the same with `--force`. Versioned JARs and launchers remain available, the current launcher advances only to a newer version unless forced, and an existing YAML or legacy JSON configuration is preserved. Installation preflights the complete layout against the canonical install root through the shared security validator, and rechecks destinations when reading or writing them. The validator follows existing symlinks before normalizing parent components, matching the filesystem’s interpretation of `link/..`. Outside symlink targets are refused before any layout write; the intentional current-launcher link is allowed only to an in-root target (a missing in-root target still counts as older). Installation opens its lock without following links, holds it across the version decision and writes, and replaces complete files instead of truncating a JAR a daemon may still be reading. Ordinary updates leave daemon replacement to `start` and `connect`. Explicit `--refresh` instead stops recorded daemons, marks the install and start guards before closing their handles for Windows, renames the whole layout to a recovery backup, and copies only the settings into a fresh install. `--with-extension --vsix` delegates to one platform-aware VS Code lookup; Gradle and release scripts use it, while extension updates never do.

A background start reads what the server says of itself, not only that something answered. `GET /health` carries the server's version and process ID: a start that finds a server of another version stops it and starts its own version, so an upgrade never leaves an old JAR serving; one that finds something on the port that does not answer as this server reports it and starts nothing; and one that finds a server of its own version answering with no PID file records it. A server is stopped by a request over loopback that carries a token the start wrote to a file beside the PID file, readable by the user alone, and gave the server in its environment, never on its command line; so a process that can read the file may stop the server, and no other. A server too old to have the endpoint is ended as a process. While a server is being stopped its record says so, and no start takes it for running or claims the file before the port is free; a stopping record left behind by a stopper that died is taken for a running one again after a grace period.

A background start is guarded against races. Under a lock that holds across threads and processes, a start decides whether a server is running, clears a stale PID file, and claims the file. The file then records the spawned process as booting until it answers, so another start, or a status check, made in that interval waits for the process rather than mistaking its record for one left by a dead process whose ID was reused. A start reads and replaces the file only while it holds the lock, because Windows refuses to replace a file that is open in another program. A write refused because some other program has the file open (a virus scanner, someone displaying it) is retried for half a second. A server that answers is reported as started even if its record could not be updated; the next start settles the record.

Started from a file, the server takes its log directories, team number, Blue Alliance key, transport, and cache settings from the file, so an MCP client needs no environment variables to run it. A few things have no field in the file and come from the environment: the heap size, and the HTTP bind address, path, and allowed origins.

All logging goes to stderr. In stdio mode stdout carries the protocol, so the server points its own standard output at stderr, and no library can write into the protocol stream. The log level is decided before the first logger is created, because the logging library reads it only once.

Applying a configuration hands the Blue Alliance key to the Blue Alliance client. The server then sweeps the disk cache, loads the current season's game data, registers every tool, and starts the transport.

## Transports and Protocol

### Stdio

The stdio transport reads one JSON-RPC message per line from stdin and writes each reply as one line, in UTF-8 on every platform. It handles one message at a time on the main thread, and the server exits when stdin closes. It is synchronous on purpose: an MCP client on stdio sends a request and waits for the reply, so handling requests concurrently would add shared-state hazards and help no client.

### HTTP

The HTTP transport serves the MCP Streamable HTTP shape on one endpoint (`/mcp` by default):

- `POST` carries a request, a notification, or a batch. Replies are JSON.
- `GET` opens an event stream. The server sends no messages of its own, so the stream carries only a keep-alive every 15 seconds.
- `DELETE` ends a session.
- `GET /health` answers as soon as the server is up, with the version and the process ID. `start` uses it to tell whether a background server is running, and which.
- `POST /directories` replaces a session’s directory/team lease, `DELETE /directories` ends it early, and `POST /tba-key` replaces its in-memory key. These routes share the Origin gate and refuse any non-loopback bind: only a person’s client, never a tool, can grant access.
- `POST /stop` ends the server, when it was started in the background: from this machine only, with the token the start gave it (above).
- `GET /data/entries` serves every sample of one or more entries over a window, as an Apache Arrow IPC stream or as CSV (doc/STANDALONE.md, "The Data Endpoint"), for the extension's viewer, a script, or a dashboard, which MCP's JSON messages are the wrong shape for. It goes through the log manager's validator and the `Origin` check as the MCP endpoint does, and reads nothing it would not. The Arrow stream is written at the format level: `arrow-format` gives the Flatbuffers metadata, and the server lays out each batch's validity bitmaps, offsets, and data in byte arrays on the heap, so nothing leaves the garbage collector's care, as arrow-vector's off-heap allocator would. The tests read the streams back with a reader written from the specification, and CI reads them with pyarrow. The entries' samples are resolved, typed, classed by sampling, and flattened for CSV by the same code the tools use (`EntryData` in the tools package); the buckets are `read_entry`'s (`Buckets`).

With `idle_exit_minutes` configured, a background server exits after that interval without a session or recent MCP request, provided no HTTP import job or inbox import is active. Health probes do not count as use. The default is zero, regardless of who starts the daemon; the extension does not change the file’s idle policy.

The stdio bridge (`connect`) is a client of this transport in the same JAR: it posts each line from its standard input to the endpoint, writes each response as one line, relays the event stream's messages the same way, and deletes its session when its input closes, so the idle clock can run. A request it cannot deliver (the server unreachable, the session gone after a restart) gets a JSON-RPC error with the request's id, and the bridge exits non-zero, since the client's remedy is to run it again.

`initialize` creates a session, a random identifier that every later request must carry. A session tracks activity and owns any directory/key registrations; deleting or expiring it revokes both. A sweep every five minutes removes sessions that have gone an hour without use, and an open event stream counts as use.

The server listens on `127.0.0.1` unless told otherwise, and rejects a request to the MCP endpoint whose `Origin` header names a host other than the local machine or an allowed one, which protects against DNS rebinding. There is no authentication: anyone who can reach the port can use the server.

### Message handling

Both transports share one message handler. It answers `initialize` with the server's capabilities (tools only) and its instructions for the model, lists the tools, and dispatches tool calls. A message without an `id` is a notification and gets no reply. A malformed request gets a JSON-RPC error, with the request's `id` when it has one, and an unknown tool name gets an error that suggests tools with similar names. A tool's result travels as the text of the call's reply.

## Tool Framework

Tools are grouped in modules by subject (core, query, statistics, robot analysis, FRC domain, poses, export, The Blue Alliance, REV logs, discovery), and each module registers its tools at startup. The categories an agent sees in `get_server_guide` come from a separate catalog, not from this grouping.

Every tool runs through one base, so the same things happen for every call:

- A bad argument, or a file that is not a readable log (missing, a directory, not readable by the server's user, empty, in another format, or too large), becomes an error result whose message says what is wrong.
- Any other exception becomes an "Internal error" result. The tests treat an internal error as a bug.
- Running out of memory becomes an error result that names the heap size and the remedies (a narrower time window, or a larger heap), and the server keeps serving.
- The result contract is enforced on every result, whatever built it. `success` and `status` come first, a missing reason is filled in, and a number that is NaN or infinite, which is not valid JSON, becomes `null` and is listed in the result's metadata.

Tools that read a log share a second base (except two tools, one taking an optional path and one taking two logs, which load their logs themselves). It adds the required `path` parameter to the tool's schema and loads the log. It also hands the tool a view of the log that records which entries the tool actually reads, so that a successful result can report its inputs even when the tool does not list them itself.

Most tools build their results with a shared result builder. The rest assemble theirs by hand, which is why the contract is enforced in one place for all of them. [TOOLS.md](TOOLS.md#response-fields) describes the fields. The tools also share these services:

- The signal resolver maps each role a tool may need to entries, by the rule above: an entry passed explicitly, then a known convention, then the only entry of the right type, and otherwise candidates that are listed but not used. The roles include the Driver Station state, battery voltage, the brownout flag and threshold, loop time, the robot pose, a vision pose, swerve module states, chassis speeds, gyro yaw, vision observations and targets, CAN buses, console text, and alerts. Because every tool asks the same resolver, two tools never disagree about which entry is the battery voltage, and `resolve_signals` shows the agent the same choices. [TOOLS.md](TOOLS.md#resolve_signals) lists the conventions.
- Scope handling, for the tools that take a scope, turns a named scope (`enabled`, `auto`, `teleop`, one enabled segment), explicit windows, or a start and end time into a list of time windows. The phases come from the same timeline `get_match_phases` reports, so every tool agrees on when the robot was in autonomous. Rates, differences, and peaks are computed within each window, not across the gap between two.
- Field paths let the statistics and query tools read a number inside a struct or an array as they read a numeric entry.
- Data quality classifies a series as periodic, logged on change, or event-driven, and scores it. Analysis directives turn the score into a confidence level and guidance for the model. [TOOLS.md](TOOLS.md#data_quality) gives the scoring.
- The reasoning guidance for the model is kept in one place and delivered both as the MCP instructions and through `get_server_guide`.

## Reading Logs

### Finding logs

The directory listing covers every configured log directory, to a depth of 5 by default. A file reachable through two overlapping directories is listed once, and a directory that cannot be read is reported in the result instead of failing the listing.

For each log, the listing reads the event, the match, and the team from the entries that carry them by convention (AdvantageKit's Driver Station and system tables, and NetworkTables' FMS table) among roughly the first 2,000 records. Robot code starts logging before the Driver Station connects, so those records usually hold no event or match yet, and the listing takes what they leave unset from the file name.

It reads the two forms the logging frameworks write: WPILib's (`FRC_20260321_162956_VACHE_Q10.wpilog`) and AdvantageKit's (`akit_26-03-21_16-29-56_vache_q10.wpilog`). A match type and its number are taken together, from the records or from the name, and a name with an event and no match is listed as exactly that, an event without a match: the Driver Station reports an event name off the field too. A file someone renamed yields only the time in its name, if it still carries one: the name says what the person wrote, not what the robot recorded, so no event or match is read from it.

A log that records no team gets the configured team number. The listing remembers all this until the file's modification time changes. A time in a file name is read as UTC, the roboRIO's zone, except in a name ending in `_sim`, which a computer wrote in its local time.

### The log store

A directory with `store.json` is a store. Its format version and creation time describe the layout; `robots/<id>/robot.json` describes a robot, and `sessions/<yyyy-MM-dd>/<HHmmss>Z[_<EVENT>_<MATCH>]/session.json` beneath that robot lists its files under `robot/`. Relative paths in manifests use `/` on every platform. Unassigned files have `unassigned/<sha256 prefix>/import.json`, with payloads beneath `robot/` as in sessions. Legacy payloads beside the import manifest remain readable and migrate on the next import under the store lock, preserving provenance and move notices. Manifests keep the store inspectable with ordinary file tools, without a database or another service to operate. Only manifested files belong to sessions: a file copied in by hand appears under `unmanaged`, even inside a session directory. A newer format is refused, and an older one requires an explicit migration.

The log manager owns one `StoreRegistry`, which provides one `LogStore` object and daemon import queue for each real store path. Its `importPaths` future and progress callback are independent of HTTP and the extension. Imports classify by the WPILOG header or native REV record header (REV can also use a WPILOG container), hash the complete file, and read through the lazy decoder before placing anything. The shared signal resolver supplies the identity and Driver Station conventions. The session's start comes from the logged wall clock, else the filename convention, else modification time minus the log's duration; the manifest records that basis. A known robot's overlapping wall-clock session receives the log. REV files require a unique successful correlation through the existing synchronizer, and the manifest keeps the offset, clock drift, confidence, and matching wpilog hash. Clock alignment alone cannot identify a robot; ambiguous and unmatched REV files wait unassigned.

There must never be two store writers. `POST /store/import` submits to that registry and exposes an in-memory job for polling; the command finds the named daemon through its PID file and health check and posts to it. It imports in its own process only when no daemon is running, and never falls back after an HTTP failure. Every import, including an inbox import, takes a nonblocking `FileChannel` lock on `store.lock` before inspection and holds it through placement and manifests. A daemon started during an offline import therefore reports the held lock instead of writing beside it or waiting indefinitely. The lock file stays in place after release: deleting it could give two processes different inodes to lock. Shutdown stops polling and drains imports before closing shared log readers.

The HTTP write surface requires configured directories for both the store and its sources, resolves symlinks with the shared security validator, and uses the MCP Origin gate. Outside sources are local copies or moves into the user's inbox; the endpoint cannot move arbitrary files on the server. Jobs retain their last progress and full result, with at most 100 jobs per transport. Completed jobs are evicted first; if all 100 are active, admission returns 503. Jobs disappear at restart.

Store locks must be regular paths, never symbolic links, even to targets inside the store. The registry checks this before HTTP admission and the queued import opens `store.lock` with `NOFOLLOW_LINKS`. The shared containment validator treats dangling links as existing path components whose resolution must fail, so it cannot authorize a new outside file through a missing target.

The inbox uses a daemon polling thread every three seconds over known inboxes. Store discovery walks configured directories at watcher startup and at most once a minute thereafter; listing scans also pass along the stores they discover. With no configured store it does no imports. Size and modification time must match across two looks at least three seconds apart, and are checked again after waiting in the store queue. Files move through the existing importer; successes and refusals get JSON-lines receipts in `inbox/imported.log`. Unchanged refusals are not retried until restart, and duplicate content remains in the inbox with its stored destination explained, as the importer leaves duplicate sources intact. The listing reports inbox paths, sizes, and waiting/importing/refused state separately from unmanaged files. The command stages in `inbox/.transfer-<id>/`, with an external transfer lock acquired before directory creation, then publishes `inbox/batch-<id>/` by atomic rename. Hidden entries are never walked for imports or listed. The next poll removes an unlocked abandoned transfer and writes a receipt; a slow active copy retains its lock. A batch sidecar `batch.json` carries `stated_robot`, and its stamp participates in settling and retry. A malformed sidecar refuses the batch; loose files have no stated identity.

Reading before placement lets an import pair files still beside one another. Copies are loaded again and hashed before the pair's manifest is atomically replaced; failed copies remain unmanaged and failed moves are restored when possible. No existing log is overwritten or deleted. Explicit `POST /store/assign` moves manifested unassigned files into robot sessions through the same queue and lock, commits the destination manifest before removing the old import manifest, and preserves the first import's provenance and moved-path notices. Assignment cannot silently adopt an unmanaged or already assigned file. Identical content already held is reported with its path and its source is left in place. Moving a payload listed by another store is refused before inspection or placement, with that store named; copies and unmanaged sources remain allowed. Other stores’ manifests are read once per source store in a batch, so the refusal does not add a catalog walk per file. Filename collisions and payloads named `import.json`, `session.json`, `robot.json`, `store.json`, or `batch.json` use a hash subdirectory while preserving the filename; separate sessions starting within one second use a numbered suffix. Imports preserve original paths and filenames, with moved-path notices for seven days. A stated robot is renamed when a serial is learned; when that serial already has a directory, both manifests name the serial and the import reports the two directories for a later explicit merge.

An import reads the catalog once and updates its in-memory facts after each successful manifest write, rather than walking the store for every file. Before correlating a REV file, it nominates the stated robot's sessions near the REV file's recorded wall clock, else its filename time. The window extends two hours on either side of the REV range; a filename estimate gets another fourteen hours because its zone is not recorded. With no REV clock it considers all of that robot's wpilogs. These estimates never establish a match or widen a session: the signal data decides, and the wpilog supplies the session's time range.

Import, listing, and shared lazy readers own their mappings through the same deterministic close mechanism. They cannot rely on GC to permit renames on Windows. The manager's `acquire` returns a use that tools, streams, and background synchronization hold until they finish reading; eviction stops caching and unmaps after the last use closes. An import reserves its source paths against new reads, then calls `release` to evict them and wait up to three seconds for existing readers. A call that still holds a file gets an explained refusal; after it finishes the import can be retried without restarting. The reservation lasts through the rename, including robot-directory promotion, and no I/O or waiting runs under the file-access monitor.

### Path security

A path given to a tool is checked before the file is opened: its real path, with symbolic links resolved, must be inside a configured log directory. The server validator's dynamic set is the union of permanent configuration and live session leases (`ClientLeases`); listings, store discovery, inbox polling, and import validation see the same leases. A server with no configured directory and no lease admits nothing, and cached files receive the same validation as new reads, so a lease that ends takes its files with it. A listing resolves file symlinks before reading metadata, so a link cannot broaden a lease. CSV exports are written only inside the export directory, which is checked the same way.

### Loading

The log manager returns a log already in memory, or loads it. Loading takes a lock for that path, so two calls for the same log load it once, while different logs load in parallel. A file over 2 GB is refused, because WPILib's reader maps a file into a single buffer.

A loaded log follows its file. The manager keeps the file's size, modification time, and identity (the inode, where the file system has one) as they were just before the log was read, and compares them with the file on every call: a file that changed is loaded again, and a file that is gone is an error. The comparison is made again after each call, and a result read across a change is discarded with an error that says what changed, because the result may hold old data (a file renamed into place keeps serving its old bytes through the mapping) or mix old and new (a file overwritten in place has its old record offsets applied to new bytes). A change the attributes do not show can still make a read of the mapping fault, which the JVM reports as an `InternalError`; the tool base turns that into the same explained error and unloads the log, where before it escaped every catch and ended a stdio server. Each session is told once, on its next result from the log, that the log was reloaded, since results it holds from earlier calls came from the old file; a session that first used the log after the change is not told, having nothing stale. The REV log tools look again for the REV logs that belong to a wpilog, at most every two seconds, and synchronize again when the candidates or their files changed, keeping an offset set by hand for a file that did not. These rules came from logs copied off a robot while it was still writing them, and copied again once they had grown.

Loading does not decode the log. WPILib's reader maps the file into memory outside the Java heap, and a single pass over it records each entry (name, type, and metadata, in the order the robot program declared them) and, for each data record, only its byte offset: 4 bytes per record. Records are read one by one through WPILib's record-level access, because WPILib's own iterator silently skips a final record shorter than 16 bytes. If the scan fails in a way the rules below do not cover, an older parser is tried, which decodes the whole log into memory at once.

The scan is built for logs that were not closed cleanly:

- A record that runs past the end of the file, names an entry that was never declared, or makes WPILib's parser fail ends the scan, and the log counts as damaged. The last few records before the damage are dropped if their timestamps jump by more than 60 seconds, since a torn write can look like a valid record.
- A record more than a day ahead of the latest timestamp is ignored wherever it appears.
- Negative timestamps are kept. WPILib's DataLogManager writes records before time zero.
- An entry declared twice with the same type is one entry with all its records. A name declared again with a different type is ignored, with a warning in the server's log.
- A file that is not a WPILOG at all (empty, all zeros, another format) gets an error that says what the file is.

The scan tells a log that only ends inside its last record, the ordinary end of a robot's log, from one that lost more: records dropped or ignored, garbage, a header that runs past the file. Results on the first carry a note, and on the second a warning.

These rules came from real logs. Three of the 88 logs the scan was first run against ended in garbage records, with timestamps as large as 6 × 10¹² seconds, which had stretched their time ranges.

### Decoding

Values are decoded the first time a tool asks for an entry, all of that entry's records at once, and kept in the log's value cache. Concurrent requests for the same entry decode it once. Records that fail to decode are counted and reported, and the entry's other values are still returned.

Structs are decoded from the schemas the log records, using WPILib's schema parser. That covers any struct, including nested structs, enums, bit-fields, and a team's or a vendor's own types. For a struct whose schema the log does not record, WPILib's geometry and kinematics schemas are used, and a few types from common team templates are decoded with an assumed layout and marked as assumed. Each struct type's decoding plan is compiled on first use and cached. [TOOLS.md](TOOLS.md#data-types) shows what decoded values look like.

## Memory Management

The daemon’s maximum heap comes from the launcher’s `WPILOG_MAX_HEAP`, `4g` by default; a background server inherits it from the JVM that starts it. The extension sets that variable from its `wpilog-mcp.maxHeap` setting in the launcher’s environment when it starts or restarts the daemon (and runs the installer with the same heap), so the size never appears in a process list; a daemon another client started keeps its own heap until it is restarted.

A loaded log costs:

- The mapped file, outside the Java heap. The operating system pages it in as it is read and can drop those pages again at any time.
- 4 bytes per record for the offsets, plus the entry table.
- Decoded values, up to the log's budget. Each entry in a log's value cache is weighed by an estimate of its decoded size. The budget is 60% of the maximum heap divided by the number of logs already in memory when the log is loaded, and at least 128 MB. When it is full, the cache drops the entries used least recently and least often, and they are decoded again when asked for.

Loaded logs are kept in a cache:

- A log not used for 30 minutes is unloaded.
- When less than 15% of the maximum heap is free after a garbage collection, the least recently used log is unloaded and the heap collected again, until the measure recovers. The collection matters: the heap's used figure counts garbage, and what an unloaded log held is garbage until it is collected, so a measure taken without one sees no change and goes on to unload every log. The check runs on every load and every 5 minutes, and forces no collection when the heap is not under pressure.
- Before a log larger than the free heap is loaded, other logs are unloaded in the same way, until that much is free.

The value budgets are estimates, and they are not rebalanced as more logs load, so together they can exceed the heap. The heap-pressure check is what keeps the total within it. A log unloaded while a call is still reading it stays usable for that call: its value cache is emptied, later reads decode without caching, and the mapping is released when the last acquired use closes. The log-reading tool base retains through result annotations, the data endpoint through the stream, and background synchronization through its actual completion, even if its future was cancelled. A bare cache view carries no such lifetime guarantee; code that reads values acquires a use.

If a call still runs out of memory, it returns an error that suggests a narrower time window or a larger heap, and the server keeps serving other calls.

## Caching

| What is cached | Where | How long |
|---|---|---|
| Loaded logs (the scan of each) | Memory | 30 minutes idle, or until unloaded under heap pressure |
| Each log's decoded values | Memory | Within the log's budget; the entries used least are dropped first |
| Struct decoding plans | Memory | The life of the loaded log (the life of the server, for logs that record no schemas) |
| Listing metadata (event, match, team) | Memory | Until the file's modification time changes |
| REV log sync results | Memory | Until the wpilog is unloaded |
| REV signals shifted onto the wpilog's clock | Memory | Until the wpilog is unloaded |
| REV log sync results | Disk | Until the cache's format version changes, or the cache is trimmed to its size limit |
| The Blue Alliance responses | Memory | 24 hours; "not found" for 5 minutes; failures are not cached; 200 entries of each kind |
| Game data | Memory | The life of the server |

The disk cache saves the expensive part of loading a log that has REV logs: parsing them and correlating their signals. Its key covers what a result depends on: a fingerprint of each file, both file names (the time in a REV log's name feeds the alignment), and a hash of the CAN database used to decode the frames. The key does not cover the code. So the cache has a format version that is raised with any change to parsing, decoding, or synchronization, and entries of another version are deleted. Before that rule, results computed by older, wrong code were served indefinitely. The server's own version is not part of the key: a release changes the server for many reasons, and rebuilding the cache with each one would make every user wait for synchronizations that come out the same.

Other choices in the disk cache:

- The fingerprint is a SHA-256 over the file's size and three 64 KB samples (its start, middle, and end), not the whole file. That is a trade of certainty for speed: a change confined to another part of a file would go unnoticed.
- Each file ends with a CRC-32, which catches accidental corruption. A file that fails it is deleted and its result recomputed.
- Files are written under a temporary name and moved into place, so a reader never sees half a file.
- A result read back must give the same answers as the one computed, down to the order of devices and signals, which is the order the REV log first shows them. The in-process stress test compares the two.
- At startup, unless the disk cache is disabled, files of an old format, temporary files more than an hour old, and the oldest files beyond the size limit are removed.

[STANDALONE.md](STANDALONE.md#config-fields) says where the cache directory is and how to change it or its size limit. The VS Code extension's servers use a cache directory of their own, so the extension and a standalone install never delete each other's files when their versions differ.

An older disk cache of parsed logs is still configured and cleaned, but nothing reads or writes it now that logs are scanned lazily from memory-mapped files.

## REV Logs and Synchronization

### Parsing

The REV log parser reads both forms a REV log takes: a WPILOG file whose entries hold CAN frames, and REV's own binary format. The frames are decoded with a CAN database. The built-in one follows REV's published SPARK frame specification, and a file supplied by the user can replace it. SPARK controllers are labeled by the model each reports. An earlier decoder applied a frame layout that did not match current firmware, and read a real log as bus voltages of 0.65 V and speeds of 6.7 × 10⁸ rpm. The built-in database was then checked against the same controllers' readings in the robot's own log.

### Matching REV logs to a wpilog

When a wpilog loads, the server looks for REV logs recorded at the same time, by the times in their file names or, failing that, their modification times. It looks only in the configured log directory that holds the wpilog and in the wpilog's own folder. Another configured directory may hold another robot's logs from the same event, and those must never be synchronized onto this one.

A wpilog whose recorded wall clock cannot be trusted gets no REV logs. Until the Driver Station sets it, a roboRIO's clock reads a default date that every boot shares, so a wpilog carrying that date would match REV logs from any other boot that carries it. A wpilog that records no wall clock is matched by the time in its file name, or failing that by its modification time.

### Synchronization

The roboRIO and a REV device keep separate clocks, so each REV log has to be shifted onto the wpilog's timeline. The offset is measured, never assumed: a coarse estimate from the file name and the wpilog's wall clock, then cross-correlation of signals that both logs record, such as a motor's applied output. [TOOLS.md](TOOLS.md#how-timestamp-synchronization-works) describes the algorithm and its confidence levels. The choices behind it:

- Names nominate candidate pairs, and the data decides. Each candidate is ranked by how well it correlates, and only the best are used.
- Pairs that agree form the answer. A pair that correlates at a contradictory offset is set aside and named, not averaged in.
- A REV log that does not overlap the wpilog once it is placed is not attached, and a few seconds of overlap is not enough to correlate on.
- With no usable pair, the result falls back to the coarse estimate at low confidence, or fails and says so. The coarse estimate is not trusted further than that: on one real log it was 15 seconds off, where correlation measured an offset of about 12 ms.
- The correlation is computed directly, lag by lag. The work is bounded (a few pairs, a capped number of samples, a fixed range of lags) and runs once per log, so a faster transform-based method has not been worth its complexity.

Synchronization runs in the background, one REV log at a time, so loading a wpilog returns before its REV logs are ready. `wait_for_sync` waits for them. If the wpilog is unloaded before a synchronization finishes, the result is discarded and the log is not brought back into memory. An offset set with `set_revlog_offset` replaces the measured one for every client until the log is unloaded, and is not saved to disk.

## The Blue Alliance and Game Data

The Blue Alliance client calls the v3 API with 10-second timeouts and caches the events and matches it fetches. An outage or a rejected key is reported as such, never as missing match data: a log listing says that enrichment was unavailable and why, and stops calling for the rest of that page.

Enrichment adds a log's match result to the listing: the alliance, the scores, and the match times. Finding the right match for a playoff log takes inference. Since 2023, playoffs have been double elimination, and the Driver Station's "Elimination N" is bracket match N. A finals log is matched to the team's playoff match nearest the log's time, and a playoff log from before 2023 by the order the team played. Every result says which method found its match, so an inferred match is never presented as a direct one.

The key is never logged or returned by a tool.

Game data is one file per season, bundled in the JAR. The current season's is loaded at startup, and the others on first use. It holds match timing, scoring, field geometry, and robot limits, transcribed from that season's final game manual, which it cites.

## Concurrency

In stdio mode the server handles one message at a time. In HTTP mode, requests run in parallel on a fixed pool of max(4, 2 × CPU count) threads, with up to 64 further threads for event streams. Requests are not serialized per session. Directory/key registration is serialized only with that session's removal, through the session map's per-key computation; validation and filesystem I/O happen beforehand. DELETE, expiry, and transport shutdown discard the session's permissions together, so a late registration cannot resurrect them. Keys remain in memory, with the most recent live registration taking precedence over the file. Registration is HTTP-only, behind the Origin check and refused on any non-loopback bind: a model's tool call cannot grant itself access.

The design keeps shared mutable state small:

- Tools hold no state. One instance of each tool serves every thread, and everything a call builds is created for that call.
- Shared structures that change while the server runs are concurrent ones: concurrent maps, the Caffeine caches, volatile fields, and atomic counters. The code takes few locks: one per log path during loading, one that keeps heap-pressure checks made at the same time from each unloading a log, the one that makes starts of a background server take turns, and the store/import and install guards described above.
- Check-then-act sequences are atomic. A log is loaded once under its path's lock, after a second look at the cache. An entry's values are decoded once, through the cache's per-key computation. A finished synchronization replaces its placeholder only if the placeholder is still there, so a log unloaded in the meantime stays unloaded. A background start claims its PID file with an atomic create. A version-mismatch restart replaces the old daemon's record with the starter's PID and `starting` marker while still holding the start lock used to check its version, then retains that claim through stopping and spawning. Another start therefore waits even after the old daemon's PID dies; it cannot decide on the same old record or remove the restart's claim as stale. No start lock is held while waiting for a process to exit or boot.
- Background work is limited to a single thread that runs REV log synchronizations one at a time, a timer that checks heap pressure every 5 minutes, a timer that removes expired sessions, one sweep of the disk cache at startup, the known-store inbox poller, and the value caches' own upkeep, which Caffeine runs on the JVM's shared pool.

On shutdown, the HTTP transport ends its event streams, waits up to 5 seconds for calls in progress, and closes its listener. Then the log manager stops its background threads and unloads the logs, so that calls in progress can finish before their logs are closed.

## Security

Three rules, each explained in its own section above:

- Files: a path given to a tool must resolve to a file inside the configured log directories, and exports go only to the export directory (see [Path security](#path-security)).
- Network: the HTTP transport listens on `127.0.0.1` by default, checks `Origin` against DNS rebinding, and has no authentication, so binding it to another address exposes the logs to anyone who can reach the port (see [HTTP](#http)).
- The Blue Alliance key is never logged or returned by a tool. The VS Code extension keeps it in secret storage and registers it in the server’s memory for the session, without writing a server or project file (see [The VS Code Extension](#the-vs-code-extension)).

## The VS Code Extension

The extension installs and updates the standalone layout from its bundled JAR and starts that install’s `http` server with the home configuration explicitly. This avoids separate heaps, caches, and private configuration files for the viewer and agents. `servers.yaml` belongs to the user; the Settings UI does not edit it. The extension’s Java/heap settings run the installer, while the launcher chooses the daemon’s Java and heap.

`directoryLease.ts` resolves User roots and open projects’ directories with a team per path. The extension’s own MCP session registers them and its secret-storage key before VS Code’s agents receive the HTTP definition. Registrations are visible to every session. A window updates its lease on settings, key, and folder changes; the client serializes updates, keeps the session alive, re-registers after restart, and deletes it on disposal. No daemon restart is needed for a lease change.

`claudeRegistration.ts` builds one user-scope `connect http` bridge command, through `cmd /c` on Windows. The bridge loads server configuration only from the home or explicit file, so the first project cannot choose the shared daemon’s port. Project YAML contributes only top-level directories and team. The extension offers that file once, with an optional gitignore addition, and never rewrites it. No key or directory goes in Claude Code’s registration command.

`legacyMigration.ts` recognizes the old `connect vscode-default` entry or `connect http --config` pointing inside extension storage. It removes only the named entry from untracked or ignored `.mcp.json`; other contents, tracked files, custom entries, and symlinks are left alone with a note. After the old daemon stops, private `servers/` and `projects/` files are removed once. Failed stops preserve the files for a later activation.

The explorer shares this server through `mcpClient.ts` and `dataClient.ts`. The webview only draws results under its content security policy and opens no network connection. Pure models handle tree grouping, organizing decisions, and requests; `explorer.ts` and `extension.ts` hold the VS Code glue. Node tests cover those models, both platform path/command rules, fake transports, and a real JAR; real VS Code checks are listed in [DEVELOPMENT.md](DEVELOPMENT.md#extension-tests).

## Known Limits

- A file over 2 GB is not loaded.
- On Windows, a mapped file cannot be moved or replaced. Managed imports reserve file access and release mappings after the last reader before moving a source; an external copy cannot bypass an active reader.
- Compressed logs are not read ([IDEAS.md](IDEAS.md), "Compressed Log Files").
- Protobuf entries are not decoded. A record of 100 bytes or less is returned as hex, and a longer one only as its size.
- The memory budgets are estimates, and the heap-pressure check is the backstop (see [Memory Management](#memory-management)).
- The MCP instructions do not reach every client, which is why `get_server_guide` repeats the guidance.
