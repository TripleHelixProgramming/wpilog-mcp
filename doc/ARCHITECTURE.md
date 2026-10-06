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

The server analyzes logs after they are written. It does not connect to a running robot or to NetworkTables. It keeps no database of past matches: what it knows about a robot comes from the log files. It runs no scripts: when the tools can't express an analysis, `export_csv` hands the data to the agent's own tools.

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

Every tool that reads a log takes the log's path. There is no "current log", and a session holds no state, so a call does not depend on the calls before it, and one client's calls do not change what another sees. REV log data is the exception, and a visible one. It is ready only once the background synchronization has finished, and until then the tools say so. An offset set with `set_revlog_offset` is shared the same way (see [Synchronization](#synchronization)).

### Claims are checked

A tool's description and the documentation promise only what the code does, and tests hold them to it: each tool's schema against the parameters its code reads, the documented parameters against the schema, and every output a description names against real results.

A test run without failures shows that the tools keep their contract, not that their numbers are right. So the numbers are checked against sources that share no code with the server: a second log reader written from the format specification, and values computed separately with WPILib's Python reader and NumPy. That check found defects every other test had missed, among them a healthy log reported as truncated because it held records before time zero. Recomputing the reference values also showed that two of the robustness review's own reference values were wrong. The rule for every fix is a regression test that fails without it. [DEVELOPMENT.md](DEVELOPMENT.md#testing) describes the tests.

## Technologies

| Technology | Used for |
|---|---|
| Java 17 | The server. |
| WPILib's `wpiutil` library | Reading WPILOG files and parsing struct schemas. The server does not reimplement the file format. |
| Gson | JSON for the protocol, tool arguments, and results. |
| SnakeYAML | Reading the configuration file. |
| Caffeine | The in-memory cache of loaded logs and each log's cache of decoded values. |
| MessagePack | The disk cache of REV log sync results: whole objects written once and read back, which needs a compact file format and not a database. |
| SLF4J with its simple logger | Logging, to stderr. |
| The JDK's built-in HTTP server | The HTTP transport. It is enough for local use by a few clients and adds no dependency. |
| Arrow's format library (`arrow-format`, with the Flatbuffers runtime) | The Flatbuffers schema and message headers of the Arrow IPC stream the data endpoint writes. Only the generated format classes: the arrays' layout is the server's own, on the heap. |
| The JDK's HTTP client | The Blue Alliance API. |
| Gradle with the Shadow plugin | Building one self-contained JAR. |
| JUnit 5 | Tests. |
| TypeScript and the VS Code extension API | The VS Code extension. It has no runtime npm dependencies, and its tests use Node's built-in test runner. |

The JAR has no other runtime dependencies.

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
| `mcp` | The protocol: message handling, the stdio and HTTP transports, sessions, and the tool registry |
| `tools` | The tools, grouped by subject, and what they share: the base every tool runs through, the result builder and result contract, the signal resolver, time scopes, field paths, data quality, and the guidance text |
| `log` | Finding and loading logs: the log manager, the lazy log and its scan, and the directory listing. It also finds the REV logs that belong to a wpilog and runs their synchronization. `log/struct` decodes structs from schemas, and `log/subsystems` holds the cache of loaded logs, the record decoder, path security, and an older parser that decodes a whole log at once, kept as a fallback |
| `store` | File manifests, content inspection, and one import queue per store; robot identity, session placement, provenance, duplicate detection, and unmanaged files |
| `revlog` | REV log parsing. `revlog/dbc` reads CAN database (DBC) files and decodes frames with them |
| `sync` | The synchronization algorithm, and the combined view of a wpilog with its synchronized REV logs |
| `cache` | The disk cache of sync results, the cache directory, file fingerprints, and the older disk cache of parsed logs, which is no longer used |
| `tba` | The Blue Alliance client, and adding match results to log listings |
| `game` | Bundled game data |
| `config` | Reading the configuration file, and managing a server started in the background |

Three more places: `src/main/java/edu/wpi/first/util/datalog` holds one small class placed in WPILib's own package, which gives the server access to WPILib's record-level reading. `src/main/resources` holds the built-in CAN database and the game data. `vscode-extension/src` holds the extension.

## Life of a Tool Call

Each step is explained in the sections that follow.

1. A client sends a `tools/call` request over stdio or HTTP. The transport hands it to the protocol handler, which finds the tool by name.
2. The tool runs inside a wrapper that every tool shares, which turns an exception or an out-of-memory condition into an explained result.
3. A tool that reads a log asks the log manager for the log at `path`. The path is checked against the configured log directories. A log already in memory is returned at once, once a look at the file's attributes shows it is still the file the log was read from; a file that changed is loaded again. Otherwise the file is mapped into memory and scanned once.
4. The tool finds the signals it needs through the signal resolver. A tool that takes a scope or windows turns them into time windows through the shared scope handling.
5. It reads the values it needs. An entry's values are decoded the first time any call asks for them, and then cached.
6. It computes its result and, in most tools, builds it with the result builder: the status, the inputs, what was skipped or shortened, and data quality where the result rests on statistics.
7. The base that log-reading tools share looks at the file again, and discards the result with an explained error if the file changed while the tool read it. Otherwise it adds a report of any records that failed to decode, a note when the log was not read to its end, and, to a success, the entries the tool read; a session that used the log before its file changed is told once that it was reloaded. The wrapper then enforces the result contract.
8. The protocol handler adds the execution time and returns the result as the text of the reply.

## Startup and Configuration

The server starts in one of three ways:

- From a configuration file: with no arguments, or with `start <name>`. The named server's settings come from the file. This is what the installers set up and what MCP clients run. [STANDALONE.md](STANDALONE.md#configuration) describes the file, where it is looked for, and how a server inherits the top-level settings.
- From command-line flags, with environment variables as their defaults and no configuration file. The VS Code extension starts the server for VS Code's agents this way. [STANDALONE.md](STANDALONE.md#command-line-flags) lists the flags.
- As a background HTTP server: `start <name>` for a server whose transport is HTTP starts a second process and returns once it answers. Two starts of one server never spawn two processes, and the background process gets the same heap the launcher would give it. `stop <name>` ends it, and `connect <name>` relays a stdio client to it, starting it first when it must, so that one background server can serve every client on a machine.

The `install` verb writes the standalone layout from its running JAR, with launcher and configuration templates packaged as resources. The shell installers download a JAR and delegate to it; Gradle does the same with `--force`. Versioned JARs and launchers remain available, the current launcher advances only to a newer version unless forced, and an existing YAML or legacy JSON configuration is preserved. Installation holds a file lock across the version decision and writes, and replaces complete files instead of truncating a JAR a daemon may still be reading. It never stops the daemon; `start` and `connect` retain that responsibility.

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
- `POST /stop` ends the server, when it was started in the background: from this machine only, with the token the start gave it (above).
- `GET /data/entries` serves every sample of one or more entries over a window, as an Apache Arrow IPC stream or as CSV (doc/STANDALONE.md, "The Data Endpoint"), for the extension's viewer, a script, or a dashboard, which MCP's JSON messages are the wrong shape for. It goes through the log manager's validator and the `Origin` check as the MCP endpoint does, and reads nothing it would not. The Arrow stream is written at the format level: `arrow-format` gives the Flatbuffers metadata, and the server lays out each batch's validity bitmaps, offsets, and data in byte arrays on the heap, so nothing leaves the garbage collector's care, as arrow-vector's off-heap allocator would. The tests read the streams back with a reader written from the specification, and CI reads them with pyarrow. The entries' samples are resolved, typed, classed by sampling, and flattened for CSV by the same code the tools use (`EntryData` in the tools package); the buckets are `read_entry`'s (`Buckets`).

A server started in the background may also end itself: with `idle_exit_minutes` in its configuration, it exits once that long has passed with no session open and no request to the MCP endpoint, counting from its start or from the end of its last session. A health check does not count, so a start's probe cannot keep a server alive. The flag is off unless set, so a server someone started by hand stays until `stop`; the VS Code extension sets it on the server it manages.

The stdio bridge (`connect`) is a client of this transport in the same JAR: it posts each line from its standard input to the endpoint, writes each response as one line, relays the event stream's messages the same way, and deletes its session when its input closes, so the idle clock can run. A request it cannot deliver (the server unreachable, the session gone after a restart) gets a JSON-RPC error with the request's id, and the bridge exits non-zero, since the client's remedy is to run it again.

`initialize` creates a session, a random identifier that every later request must carry. A session holds nothing but its timestamps. A sweep every five minutes removes sessions that have gone an hour without use, and an open event stream counts as use.

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

A path given to a tool is checked before the file is opened: its real path, with symbolic links resolved, must be inside a configured log directory. With no log directory configured, any path is accepted. CSV exports are written only inside the export directory, which is checked the same way.

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

One setting sizes the server: the JVM's maximum heap, `4g` by default (`WPILOG_MAX_HEAP` for the standalone install, `wpilog-mcp.maxHeap` in the extension). The launcher, a server started in the background, and the VS Code extension all pass it to the JVM.

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

In stdio mode the server handles one message at a time. In HTTP mode, requests run in parallel on a fixed pool of max(4, 2 × CPU count) threads, with up to 64 further threads for event streams. Requests are not serialized per session.

The design keeps shared mutable state small:

- Tools hold no state. One instance of each tool serves every thread, and everything a call builds is created for that call.
- Shared structures that change while the server runs are concurrent ones: concurrent maps, the Caffeine caches, volatile fields, and atomic counters. The code takes few locks: one per log path during loading, one that keeps heap-pressure checks made at the same time from each unloading a log, the one that makes starts of a background server take turns, and a few synchronized accessors.
- Check-then-act sequences are atomic. A log is loaded once under its path's lock, after a second look at the cache. An entry's values are decoded once, through the cache's per-key computation. A finished synchronization replaces its placeholder only if the placeholder is still there, so a log unloaded in the meantime stays unloaded. A background start claims its PID file with an atomic create. A version-mismatch restart replaces the old daemon's record with the starter's PID and `starting` marker while still holding the start lock used to check its version, then retains that claim through stopping and spawning. Another start therefore waits even after the old daemon's PID dies; it cannot decide on the same old record or remove the restart's claim as stale. No start lock is held while waiting for a process to exit or boot.
- Background work is limited to a single thread that runs REV log synchronizations one at a time, a timer that checks heap pressure every 5 minutes, a timer that removes expired sessions, one sweep of the disk cache at startup, and the value caches' own upkeep, which Caffeine runs on the JVM's shared pool.

On shutdown, the HTTP transport ends its event streams, waits up to 5 seconds for calls in progress, and closes its listener. Then the log manager stops its background threads and unloads the logs, so that calls in progress can finish before their logs are closed.

## Security

Three rules, each explained in its own section above:

- Files: a path given to a tool must resolve to a file inside the configured log directories, and exports go only to the export directory (see [Path security](#path-security)).
- Network: the HTTP transport listens on `127.0.0.1` by default, checks `Origin` against DNS rebinding, and has no authentication, so binding it to another address exposes the logs to anyone who can reach the port (see [HTTP](#http)).
- The Blue Alliance key is never logged or returned by a tool. The VS Code extension keeps it in VS Code's secret storage and in a configuration file in its own storage that only the user can read (see [The VS Code Extension](#the-vs-code-extension)).

## The VS Code Extension

The extension exists so that a team installs one thing. It bundles the server's JAR, runs it with the WPILib JDK when one is installed, and finds the log folder. Its [README](../vscode-extension/README.md) describes what it does; these are the reasons behind it.

- Agents find MCP servers in different ways, so the extension provides the server twice. VS Code's own agents get it from VS Code's server registry. Claude Code reads a project's `.mcp.json` instead, so the extension adds an entry there. Without that, someone who installed the extension to use it with Claude Code would find no server.
- The entry for Claude Code does nothing but start the server with a configuration file. The settings and the Blue Alliance key are in that file, in the extension's own storage, readable only by the user (by file mode on macOS and Linux, and by the user profile's protection on Windows). The server Claude Code starts runs outside VS Code and can read neither VS Code's settings nor its secret storage. The file keeps the key out of the project and spares the user environment variables. It is the same arrangement as the standalone install, where a client's entry names the launcher and the configuration file holds the settings.
- The entry points at a copy of the JAR in the extension's storage, whose path survives extension updates.
- The extension runs one server per computer, shared by VS Code's agents, Claude Code, and the explorer, with every project's log directories joined in. A draft that let the user define more servers and projects name theirs was taken out before release: the server keeps logs apart by path already, a tool reads one log per call, and one heap serves better than two halves, so a second server added only a settings model and a picker (`doc/EXPLORER_PLAN.md`, decision 5). The extension's server is its own: it reads none of the standalone install's `servers.yaml`, and its daemon is named `vscode-default` so its PID file, token, and log never collide with a standalone server's `default`. One setting makes the standalone install's `http` server the extension's instead, for a user who has that install and wants Claude Desktop and the rest on the same server: the extension then starts it with the install's own launcher, reads the port from the PID file the start writes, and writes nothing into the install, whose file decides the server's settings and whose installer decides its version (an older one is reported, not replaced, since the explorer needs what newer servers have).
- The explorer (the views and the editor that show a log without an assistant) is a client of the same server, through an MCP client of its own and the data endpoint. Its webview opens no connection: the extension host fetches, and the webview draws what it is sent, under a content security policy that allows only the extension's own scripts. Every number it shows is a tool's result or the data endpoint's samples, so it can never disagree with the assistant; the webview's own code (the Arrow reader, the plot arithmetic) runs under Node's test runner as well, where it is checked against the server's streams.
- The entry holds paths that exist on one computer only. So the extension does not write into a `.mcp.json` that git tracks, and it offers to keep the file out of git.
- User settings apply to every project, and a project's own settings override them, so Claude Code in a project gets the settings that apply to that project. No team number is assumed: the setting is empty until the user sets it.
- The extension adds no second server to a project whose `.mcp.json` already runs the standalone one. It cannot see a standalone server registered for all projects, which is why that case needs the extension's entries turned off.

## Known Limits

- A file over 2 GB is not loaded.
- On Windows, a loaded log's file cannot be replaced or deleted while it is mapped, and unloading the log does not release the mapping until the garbage collector does, so copying a newer log over a loaded one fails there until the old log has been collected ([IDEAS.md](IDEAS.md), "Logs That Change After Loading").
- Compressed logs are not read ([IDEAS.md](IDEAS.md), "Compressed Log Files").
- Protobuf entries are not decoded. A record of 100 bytes or less is returned as hex, and a longer one only as its size.
- The memory budgets are estimates, and the heap-pressure check is the backstop (see [Memory Management](#memory-management)).
- The MCP instructions do not reach every client, which is why `get_server_guide` repeats the guidance.
