# Architecture

This guide explains what wpilog-mcp is for, the principles its design follows, and the reasons for them. It then shows how the code carries those principles out: how it reads logs, manages memory, caches results, synchronizes REV logs, and serves several clients at once.

Other documents cover what this one leaves out. [TOOLS.md](TOOLS.md) describes every tool and the fields of a result. [STANDALONE.md](STANDALONE.md) and the [extension's README](../vscode-extension/README.md) cover installation and configuration. [DEVELOPMENT.md](DEVELOPMENT.md) covers building, testing, and adding a tool.

The code is described here by what each part does, not by class names, which change. The [code map](#code-map) says which package holds each part. Where this document and the code disagree, the code is right; please fix the document.

## Goals

wpilog-mcp exists so that an FRC team can ask an AI agent an engineering question about its robot, and get an answer that rests on the robot's own logs. The question may be why the robot browned out in a match, or whether a swerve module is slipping. The people asking are students and mentors, often in the pit between matches with little time, and afterwards with more.

That purpose sets the goals:

- Give no false or guessed answer. Every response must rest on facts that can be checked in the log. A wrong number is worse than no number, because the agent builds an explanation on it and the team acts on that explanation at a competition.
- Work on any team's log. The review that shaped the current design put it this way: "An agent should be able to analyze a log correctly without ever seeing the code that produced it" ([ROBUSTNESS_REVIEW.md](ROBUSTNESS_REVIEW.md)). Logs differ by framework (AdvantageKit, WPILib's DataLogManager, CTRE's swerve template, PathPlanner), by team, and by situation: a practice session is not an FMS match. So the server assumes nothing about a team's names. What a team's entry measures is another matter. The log does not record it, so the server does not supply it; the robot's source code does, and the server sends the agent there.
- Lead the model toward sound reasoning. Language models find an explanation that fits the data even when the data can't support it. The server should make the scientific method the easy path: check the premise, test a rival explanation, and claim no more certainty than the evidence allows.
- Be easy to run. The server is one JAR that needs only Java 17 (the WPILib JDK is enough), with no database and no service to operate. The VS Code extension bundles it and finds Java and the logs by itself.
- Handle real logs: hundreds of megabytes, cut off when the robot lost power, holding a team's own struct types.
- Be efficient and correct: quick enough for a conversation on a team's laptop, and able to serve several clients at once without one client's call disturbing another's.

The server analyzes recorded logs. It keeps no database of past matches: what it knows about a robot comes from the log files. It runs no scripts: when the tools can't express an analysis, `export_csv` hands the data to the agent's own tools.

## Design Principles

Most of these principles were learned from failures. A review of version 0.8.2 against a real practice log found tools that returned plausible wrong answers, and empty results that an agent could not tell from "no problem found". [ROBUSTNESS_REVIEW.md](ROBUSTNESS_REVIEW.md) lists what it found, and [ROBUSTNESS_PLAN.md](ROBUSTNESS_PLAN.md) records what was changed. The examples below come from that work.

### Tools return measurements, and the model does the reasoning

Tools return statistics with their sample counts, timestamps, event lists, and correlations with p-values. There is no "diagnose my robot" tool. A verdict hides the evidence it rests on. With building blocks, the model has to combine facts across several calls, and a person can check each step.

Version 0.8.2's battery tool answered "URGENT: Replace battery immediately" from two voltage dips shorter than 100 ms. The brownouts were real, but their cause was a hypothesis, and the tool stated it as a conclusion.

A few tools do give a summary for a pit crew in a hurry: a battery health score, a loop-timing score, a CAN health level, a brownout risk. That is a deliberate trade-off. Each reports the facts it summarizes, and the rule that decided the summary, beside it.

### The server does not guess

A tool that needs a particular signal, such as the battery voltage, the robot pose, or the autonomous chooser, uses an entry on three grounds only. The caller named it, it follows a convention a logging library publishes, or it is the only entry of the right type. An entry that merely has a suggestive name is listed as a candidate for the agent or the user to confirm, and is not used. Most choices can be overridden with a parameter; an entry named that way but missing or of the wrong type is an error, not a reason to fall back on something else.

A word in a name is not evidence, and neither is the shape of the data. The swerve tool takes the measured and setpoint module states from the names the swerve libraries and templates publish, each pair from one table. The mechanism tool analyzes only the entries passed to it, and uses a mechanism's name to list candidates. The vision tool reads what the vision template and the vision libraries publish under their own names. An entry that only has the content or the name of vision data is listed as a candidate and is not decoded. In each case the result names the entries it used and how each was chosen.

What a candidate holds is settled where the entry is logged, in the robot's source code. That code says which mechanism an entry belongs to, its units, and whether it is measured or commanded. The server's guidance tells the agent to read it and, when the code is not at hand, to call any mapping taken from a name an assumption.

The rule holds beyond entry names. When REV logs are synchronized, names only nominate pairs of signals to compare, and the data decides which pairs are used. When a log records loop time under none of the known conventions, the loop-timing tool says so; it does not derive loop time from whatever periodic signal it can find.

A guess is right on the logs a tool was written against and wrong elsewhere, and the wrong answer looks just like a right one. Version 0.8.2 took the first entry with "voltage" in its name as the battery. On one log that was the roboRIO's 5 V rail, and the tool reported a high brownout risk with 46,242 samples below the threshold. The same version's search for CAN errors looked for "can" anywhere in a message, which also matches "scan" and "cancel". The REV log matcher of the time treated every AdvantageKit output entry as a motor's output, because each such path (`/RealOutputs/...`) contains the word "output".

The tools that still judged by a name or by content after that review failed the same way on real logs. A planned autonomous trajectory is a struct array of timestamps and poses, as a camera's observations are. The vision tool reported it as a camera in 86 of Team 2363's 88 logs, with a latency of minutes, and decoding its millions of samples took one call on a 688 MB log past 3 GB of heap.

In logs other teams publish, a gyro's struct has yaw and pitch fields and was reported as a camera target. The mechanism tool took a camera's `targetYaw` for a setpoint. The swerve tool, given two target arrays beside the measured states, compared the measured states with whichever was declared first.

### The log decides

What a tool needs to know about a log comes from the log itself:

- Struct values are decoded with the schemas the log records. A team's own structs decode, and so does a team's edited copy of a template struct, which a layout built into the server would get wrong.
- The timeline comes from the Driver Station data in the log. Nothing assumes a match: a log may hold any number of enabled periods, autonomous may never be logged, and FMS may not be attached.
- Thresholds come from the log when it records them, such as the roboRIO's brownout voltage. The season comes from the log's own clock when it has one, and otherwise from the build date it records, a year in its file name, or the current year; the result says which.
- Every number in the log can be analyzed. The statistics and query tools read a struct field or an array element as they read a numeric entry.

On the practice log the review used, version 0.8.2 reported a single enabled period from 40 to 840 seconds where the log holds four, and assumed a brownout threshold of 6.8 V where the log records 6.75 V.

Game rules are the one thing a log cannot supply. They are bundled with the server, and `get_game_info` names the game manual revision its data came from and says that it is not from the log.

### Every result says what it is and what it used

A result has a status: `ok`, `partial`, `not_applicable`, `no_match`, or `error`. An analysis that finds nothing to analyze does not return an empty success. It says that it does not apply, or that it found no match; what it looked for; and how to point the tool at the right data, because finding nothing is not evidence that nothing is wrong.

An empty success is the most damaging failure for an agent, because it looks like an answer. Version 0.8.2's vision tool returned `success: true` with an empty list for a window in which the robot's pose moved 60 cm while the robot sat disabled. A tool that only reads or lists, such as `read_entry` or `search_strings`, can still answer with a count of zero, which is a plain statement of what the log holds.

A result also names the entries, fields, and time windows it was computed from, the sections it could not produce, and the true total of a list it cut short.

Where the honest answer is "unknown", the result says so. A value that can't be computed is `null`, not zero and not `NaN`. A REV log whose synchronization failed yields no data, rather than data on the wrong clock. A Blue Alliance outage is reported as an outage, not as a match without results. A bad argument, such as a start time after the end time or an entry that does not exist, is an error that says what is wrong.

### Nothing is selected or left out silently

A cap or a priority applied to a list is a judgment the model cannot see. A list of the first hundred console messages can leave out the only errors that matter. So for console text the server reports exact counts and a summary of the distinct messages with the count of each, and offers the complete list with search and paging (`search_strings`). Emphasis comes from the counts, not from a selection the server made.

The same goes for what the server could not read. Records that failed to decode are reported by every tool that read them, the statistics tools count the values that were not finite numbers, and every result computed from a log that was not read to its end says so. Most robot logs end inside their last record, because the robot is switched off while logging. That alone is a note in the result's metadata and not a warning, since a warning on nearly every result teaches a model to ignore warnings. A log that lost more than its last record gets a warning.

### Confidence is calibrated to the evidence

Results that rest on statistics carry a data quality score with a reason for every penalty. It is computed on the samples in the requested time scope, with non-finite values counted against it. Before the score is computed, the sampling is classified: AdvantageKit and NetworkTables log a value when it changes, so a long interval in such a signal is usually a held value, not missing data. The result describes it as a hold, and irregular timing counts against a signal only when the signal is periodic. Long holds still lower the score, because statistics weigh samples, not time, and so under-represent a value held for a long time.

The same lesson applies to the quality score: in version 0.8.2 nearly every result scored 0.50, "low", including a loop-time signal with 59,217 samples at 47.8 Hz.

Quality bounds statistics, not observations. A logged brownout flag or an error line is a fact and needs no statistical caveat. So a tool that reports only logged events, such as the Driver Station timeline, carries no quality score, and where a result holds both events and statistics, its low-quality warning says that it applies to the statistics only. The server teaches the model three tiers. A discrete event is a fact. A mean or a correlation is an inference bounded by its confidence level. A cause outside the telemetry, such as wiring or wear, is a hypothesis that needs a physical check.

A confidence level never claims more than its evidence shows. REV log synchronization reports high confidence only when at least two signal pairs agree to within a few milliseconds.

### The server coaches the model's reasoning

Guidance reaches the model in three places:

- the descriptions of the analysis tools, which say what their numbers do and don't show;
- results that rest on statistics, which carry the confidence level, interpretation guidance in hedged language, suggested follow-up calls, and a reminder that one match may not generalize;
- server-level rules sent when the client connects.

The server-level rules are concrete. They are phrased in terms of this server's outputs: confirm with `get_ds_timeline` that the event in the question happened before explaining it, and quote only numbers a tool returned. The most important rules come first, and the whole text fits the 2 KB that Claude Code keeps.

Rules that cost effort, such as testing a proposed cause against a rival explanation, apply only to "why" questions, so that a lookup gets a direct answer; a pit crew has little time. One rule is about meaning: an entry's name does not prove what it measures, so the agent reads the robot's source code where the entry is logged, or calls the mapping an assumption. No rule forbids the only way around a limit: the rule against computing statistics by hand names `export_csv` as the way out when no tool can read the data.

Some clients drop these rules, so `get_server_guide` returns the full method as a tool result, and its description asks the agent to call it first. [TOOLS.md](TOOLS.md#server-instructions) describes both.

### Each call stands alone

Every tool that reads a log takes the log's path. There is no "current log", so analysis does not depend on an implicit selection left by an earlier call. Session leases control shared file access, not which log a tool selects. REV log data is the exception, and a visible one. It is ready only once the background synchronization has finished, and until then the tools say so. An offset set with `set_revlog_offset` is shared the same way (see [Synchronization](#synchronization)).

### Claims are checked

A tool's description and the documentation promise only what the code does, and tests hold them to it. Each tool's schema is checked against the parameters its code reads, the documented parameters against the schema, and every output a description names against real results.

A test run without failures shows that the tools keep their contract, not that their numbers are right. So the numbers are checked against sources that share no code with the server: a second log reader written from the format specification, and values computed separately with WPILib's Python reader and NumPy. That check found defects every other test had missed, among them a healthy log reported as truncated because it held records before time zero. Recomputing the reference values also showed that two of the robustness review's own reference values were wrong.

The rule for every fix is a regression test that fails without it. [DEVELOPMENT.md](DEVELOPMENT.md#testing) describes the tests.

## Technologies

| Technology | Used for |
|---|---|
| Java 17 | The server. |
| WPILib's `wpiutil` library | Reading WPILOG files and parsing struct schemas. |
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
| TypeScript and the VS Code extension API | The VS Code extension has no runtime npm dependencies. Pure logic uses Node tests; the separate development-only Electron smoke drives real VS Code on the oldest supported and current stable releases. |

The table covers the file-analysis and shared-server dependencies.

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
| `mcp` | JSON-RPC, transports, sessions, loopback directory/key registration, HTTP data/store/metrics endpoints and import/sync jobs, and the tool registry |
| `data` | The Apache Arrow IPC stream writer the data endpoint uses: Flatbuffers metadata from `arrow-format`, and each column's buffers laid out in byte arrays on the heap |
| `tools` | The tools, grouped by subject, and what they share: the base every tool runs through, the result builder and result contract, the signal resolver, time scopes, field paths, data quality, and the guidance text |
| `log` | Finding and loading logs: the log manager, the lazy log and its scan, and the directory listing. It also finds the REV logs that belong to a wpilog and runs their synchronization. `log/struct` decodes structs from schemas, and `log/subsystems` holds the cache of loaded logs, the record decoder, path security, and an older parser that decodes a whole log at once, kept as a fallback |
| `store` | File manifests, content inspection, one mutation queue per store, robot identity, session placement, provenance, duplicate detection, and unmanaged files |
| `revlog` | REV log parsing. `revlog/dbc` reads CAN database (DBC) files and decodes frames with them |
| `sync` | Signal synchronization and combined logs; transport-independent content-checked transfers and verification |
| `cache` | The disk cache of sync results, the cache directory, file fingerprints, and the older disk cache of parsed logs, which is no longer used |
| `tba` | The Blue Alliance client, and adding match results to log listings |
| `game` | Bundled game data |
| `config` | File configuration, session leases, daemon lifecycle, and the shared install/refresh implementation |

Three more places: `src/main/java/edu/wpi/first/util/datalog` holds one small class placed in WPILib's own package, which gives the server access to WPILib's record-level reading. `src/main/resources` holds the built-in CAN database and the game data. `vscode-extension/src` holds the extension.

## Life of a Tool Call

Each step is explained in the sections that follow.

1. A client sends a `tools/call` request over stdio or HTTP. The transport hands it to the protocol handler, which finds the tool by name.
2. The tool runs inside a wrapper that every tool shares, which turns an exception or an out-of-memory condition into an explained result.
3. A tool that reads a log asks the log manager for the log at `path`. The path is checked against permanent directories and live session leases. A log already in memory is returned at once, once a look at the file's attributes shows it is still the file the log was read from; a file that changed is loaded again. Otherwise the file is mapped into memory and scanned once.
4. The tool finds the signals it needs through the signal resolver. A tool that takes a scope or windows turns them into time windows through the shared scope handling.
5. It reads the values it needs. An entry's values are decoded the first time any call asks for them, and then cached.
6. It computes its result and, in most tools, builds it with the result builder: the status, the inputs, what was skipped or shortened, and data quality where the result rests on statistics.
7. The base that log-reading tools share looks at the file again, and discards the result with an explained error if the file changed while the tool read it. Otherwise it adds a report of any records that failed to decode, a note when the log was not read to its end, and, to a success, the entries the tool read. A session that used the log before its file changed is told once that it was reloaded. The wrapper then enforces the result contract.
8. The protocol handler adds the execution time and returns the result as the text of the reply.

#### Charts

`render_chart` uses the same signal resolver, windows, sampling classification and field paths as
the numeric tools. Its versioned specification owns every drawing choice. `ChartImage` only renders
that evidence with headless JDK imaging, and the explorer uses uPlot and its field view. Full-window
statistics stay independent of drawing pagination. `ResponseBuilder` attaches MCP content once: the
tool base enforces the JSON contract and log inputs before the transport places image blocks beside
that JSON. Missing imaging support skips the image with a reason, preserving the measurements.

## Startup and Configuration

The server starts in one of three ways:

- From a configuration file: with no arguments, or with `start <name>`. The named server's settings come from the file. This is what the installers set up and what MCP clients run. [STANDALONE.md](STANDALONE.md#configuration) describes the file, where it is looked for, and how a server inherits the top-level settings.
- From command-line flags, with environment variables as their defaults and no configuration file. [STANDALONE.md](STANDALONE.md#command-line-flags) lists the flags.
- As a background HTTP server: `start <name>` for a server whose transport is HTTP starts a second process and returns once it answers. Two starts of one server never spawn two processes, and the background process gets the same heap the launcher would give it. `stop <name>` ends it, and `connect <name>` relays a stdio client to it, starting it first when it must, so that one background server can serve every client on a machine.

#### Installing

The `install` verb writes the standalone layout from its running JAR, with launcher and configuration templates packaged as resources. The shell installers download a JAR and delegate to it; Gradle does the same with `--force`. Versioned JARs and launchers remain available, the current launcher advances only to a newer version unless forced, and an existing YAML or legacy JSON configuration is preserved.

Installation preflights the complete layout against the canonical install root through the shared security validator, and rechecks destinations when reading or writing them. The validator follows existing symlinks before normalizing parent components, matching the filesystem’s interpretation of `link/..`. Outside symlink targets are refused before any layout write. The intentional current-launcher link is allowed only to an in-root target, and a missing in-root target still counts as older.

Installation opens its lock without following links, holds it across the version decision and writes, and replaces complete files instead of truncating a JAR a daemon may still be reading. Ordinary updates leave daemon replacement to `start` and `connect`. Explicit `--refresh` instead stops recorded daemons, marks the install and start guards before closing their handles for Windows, renames the whole layout to a recovery backup, and copies only the settings into a fresh install. `--with-extension --vsix` delegates to one platform-aware VS Code lookup; Gradle and release scripts use it, while extension updates never do. The scripts ask about the extension only after `install --list-vscode` names an editor, so a person is never offered an install the JAR would refuse. The lookup lists every season's WPILib folder, as the JDK search does: 0.10.0-dev3 looked only at the calendar year's `vscode/bin/code`, then at `code` on PATH, so it found no editor in a Mac app bundle and none anywhere each January until the new season's WPILib was installed. It reads each editor's version from the editor's own `package.json`, without starting it, and never offers one older than the extension's `engines.vscode`, which the build copies into the version class. A VS Code installed apart from WPILib is used only when named: a team's robot code is built in WPILib's, and the two keep separate extensions. The editor is chosen before anything is written, so an install that asked for the extension either gets it or changes nothing.

#### Background servers

A background start reads what the server says of itself, not only that something answered. `GET /health` carries the server's version, configuration name and process ID. A start that finds a recorded server of another version stops it and starts its own version, so an upgrade never leaves an old JAR serving. One that finds something on the port that does not answer as this server reports it and starts nothing. Without a PID file, adoption requires the same configuration name as well as the same version: otherwise a second named configuration could silently join the wrong server. An older server without a name keeps the prior adoption rules with a warning naming its version.

A server is stopped by a request over loopback that carries a token. The start wrote the token to a file beside the PID file, readable by the user alone, and gave it to the server in its environment, never on its command line. So a process that can read the file may stop the server, and no other. A server too old to have the endpoint is ended as a process.

While a server is being stopped its record says so, and no start takes it for running or claims the file before the port is free. A stopping record left behind by a stopper that died is taken for a running one again after a grace period.

A background start is guarded against races. Under a lock that holds across threads and processes, a start decides whether a server is running, clears a stale PID file, and claims the file. The file then records the spawned process as booting until it answers. Another start, or a status check, made in that interval waits for the process rather than mistaking its record for one left by a dead process whose ID was reused.

A start reads and replaces the file only while it holds the lock, because Windows refuses to replace a file that is open in another program. A write refused because some other program has the file open (a virus scanner, someone displaying it) is retried for half a second. A server that answers is reported as started even if its record could not be updated; the next start settles the record.

#### Settings and logging

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
- `GET /data/entries` serves every sample of one or more entries over a window, as an Apache Arrow IPC stream or as CSV (doc/STANDALONE.md, "The Data Endpoint"), for the extension's viewer, a script, or a dashboard, which MCP's JSON messages are the wrong shape for.
- `GET /store` and its robots, sessions, file and prefix-hash routes expose only the configured stores' catalogs. They do not inherit client directory leases.

With `idle_exit_minutes` configured, a background server exits after that interval without a session or recent MCP request, provided no HTTP import/sync job or inbox import is active. Health probes do not count as use. The default is zero, regardless of who starts the daemon; the extension does not change the file’s idle policy.

The stdio bridge (`connect`) is a client of this transport in the same JAR. It posts each line from its standard input to the endpoint, writes each response as one line, and relays the event stream's messages the same way. It deletes its session when its input closes, so the idle clock can run. A request it cannot deliver (the server unreachable, the session gone after a restart) gets a JSON-RPC error with the request's id, and the bridge exits non-zero, since the client's remedy is to run it again.

`initialize` creates a session, a random identifier that every later request must carry. A session tracks activity and owns any directory/key registrations; deleting or expiring it revokes both. A sweep every five minutes removes sessions that have gone an hour without use, and an open event stream counts as use.

The server listens on `127.0.0.1` unless told otherwise, and rejects a request to the MCP endpoint whose `Origin` header names a host other than the local machine or an allowed one, which protects against DNS rebinding. There is no authentication: anyone who can reach the port can use the server.

#### The data endpoint

`GET /data/entries` goes through the log manager's validator and the `Origin` check as the MCP endpoint does, and reads nothing it would not. The Arrow stream is written at the format level: `arrow-format` gives the Flatbuffers metadata, and the server lays out each batch's validity bitmaps, offsets, and data in byte arrays on the heap. So nothing leaves the garbage collector's care, as arrow-vector's off-heap allocator would. The tests read the streams back with a reader written from the specification, and CI reads them with pyarrow. The entries' samples are resolved, typed, classed by sampling, and flattened for CSV by the same code the tools use (`EntryData` in the tools package); the buckets are `read_entry`'s (`Buckets`).

### Message handling

Both transports share one message handler. It answers `initialize` with the server's capabilities (tools and the current-session resource) and its instructions for the model, lists the tools, and dispatches tool calls. A message without an `id` is a notification and gets no reply. A malformed request gets a JSON-RPC error, with the request's `id` when it has one, and an unknown tool name gets an error that suggests tools with similar names. A tool's JSON result travels as a text block; chart replies may also carry an image block.

## Tool Framework

Tools are grouped in modules by subject (core, query, statistics, robot analysis, FRC domain, poses, export, The Blue Alliance, REV logs, discovery), and each module registers its tools at startup. The categories an agent sees in `get_server_guide` come from a separate catalog, not from this grouping.

Every tool runs through one base, so the same things happen for every call:

- A bad argument, or a file that is not a readable log (missing, a directory, not readable by the server's user, empty, in another format, or too large), becomes an error result whose message says what is wrong.
- Any other exception becomes an "Internal error" result. The tests treat an internal error as a bug.
- Stack exhaustion during a tool call or result normalization becomes an explained error. The message handler also bounds raw tools, schema construction and response wrapping with a JSON-RPC error; the next stdio request is still served. Neither boundary tries to serialize the overflowing input or its full stack.
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

It reads the two forms the logging frameworks write: WPILib's (`FRC_20260321_162956_VACHE_Q10.wpilog`) and AdvantageKit's (`akit_26-03-21_16-29-56_vache_q10.wpilog`). A match type and its number are taken together, from the records or from the name. A name with an event and no match is listed as exactly that, an event without a match: the Driver Station reports an event name off the field too. A file someone renamed yields only the time in its name, if it still carries one: the name says what the person wrote, not what the robot recorded, so no event or match is read from it.

A log that records no team gets the configured team number. The listing remembers all this until the file's modification time changes. A time in a file name is read as UTC, the roboRIO's zone, except in a name ending in `_sim`, which a computer wrote in its local time.

### The log store

A directory with `store.json` is a store. Its format version and creation time describe the layout; `robots/<id>/robot.json` describes a robot, and `sessions/<yyyy-MM-dd>/<HHmmss>Z[_<EVENT>_<MATCH>]/session.json` beneath that robot lists its files under `robot/`. Relative paths in manifests use `/` on every platform. Unassigned files have `unassigned/<sha256 prefix>/import.json`, with payloads beneath `robot/` as in sessions. Legacy payloads beside the import manifest remain readable and migrate on the next import under the store lock, preserving provenance and move notices.

Manifests keep the store inspectable with ordinary file tools, without a database or another service to operate. Only manifested files belong to sessions: a file copied in by hand appears under `unmanaged`, even inside a session directory. A newer format is refused, and an older one requires an explicit migration.

#### Persistent local ingestion

The named-server flag `experimental_data_store` defaults to false. Without it, startup does
not load persistent ingestion policy or publish its grants, preferred store or background
worker. Local ingestion controls explain that the feature is disabled without reading the
policy, and the extension hides its offer and commands. Disabling the flag does not delete the
policy or stored files; a restart with the flag enabled restores the saved policy. Ordinary
configured roots, manual imports and store reads remain independent of this feature.

The named shared server owns `LocalIngestion`, not an editor window. Its policy is machine-local,
keyed by canonical configuration path and name, and atomically replaced under a separate file
lock with optimistic revisions. A live loopback session admits explicit setup; persistent grants
then outlive that lease and are restored before discovery. Grants belong to the policy owner,
with monotonic revisions so an older import completion cannot resurrect a removed source.
Pausing keeps store reads available and stops future batches. Enabled ingestion inhibits idle
exit, and shutdown drains admitted work before revoking its grants.

The inbox and enrolled folders share the `FileStability` owner. Discovery only nominates files;
`LogStore.importPrepared` rechecks their stamps and destination identity under its existing queue
and cross-process lock before running the existing importer. Copy/move, SHA-256 deduplication,
classification and correlation have no extension-side implementation. Copy is the default;
automatic Move needs the person's explicit completed-file handoff acknowledgment. Stability is
not proof that a writer closed its file. Growing copies produce later immutable versions.

Bounded receipts under `.local-ingestion/` record source path/version, result, destination, hash
and time, including duplicate origins without replacing a payload's first provenance. Receipts
are metadata, excluded from unmanaged files and peer payload transfers. Unchanged receipts avoid
rehashing or correlation after idle scans and restart. They hide sources only while the verified
manifested destination remains available. The default listing uses this policy before counts or
paging; an explicit all-sources view and explicit path reads remain available.

Unassigned REV reconsideration is a bounded store operation, at most 32 candidates per queue
turn. Session-owned WPILOG content hashes define its candidate generation; durable local attempt
receipts avoid repeating unsuccessful correlation on every idle scan. A newly arrived or assigned
WPILOG triggers reconsideration using the original assignment path, all eligible anchors, the
250 ms gate and original provenance. An ambiguity stays unassigned. This changes arrival-order
handling, not synchronization arithmetic or the disk-cache format.

#### Imports

The extension's Import choice selects a separate destination and always submits a copy request
to the same importer as in-place Copy and Move. The chosen root is saved in the user's additional
log directories, then its MCP session's serialized lease update completes before the HTTP job
is submitted. Cancelling the destination or robot picker submits no job or settings change;
the extension does not implement its own copying, matching or duplicate detection.

The log manager owns one `StoreRegistry`, which provides one `LogStore` object and daemon import queue for each real store path. Its `importPaths` future and progress callback are independent of HTTP and the extension. Imports classify by the WPILOG header or native REV record header (REV can also use a WPILOG container), hash the complete file, and read through the lazy decoder before placing anything. The shared signal resolver supplies the identity and Driver Station conventions. A new session's start comes from the logged wall clock, else the filename convention, else modification time minus the log's duration; the manifest records that basis.

#### Session placement

For a known robot, calendar ranges nominate existing sessions before any correlation load, with the
puller's two-hour slack (sixteen for filename clocks); unknown clocks retain candidates. A known
serial also nominates its retained alias directories, without merging or moving them. A capture,
including an open capture, anchors its session. Without a capture, an already data-matched WPILOG
can anchor it.

Joining an anchored session requires strong correlation to exactly one session and an offset within
250 ms of zero. Recorded anchor offsets also count against that bound, so chains cannot accumulate
the allowance. The evidence names the anchor's hash and the measured relative offset, and the anchor
session keeps its calendar. A failed or ambiguous proof creates a separate session with the reason,
never a calendar fallback.

Only when no nominated session has an anchor does exact calendar overlap retain its former role. New
such receipts say `placement_method: by_time_overlap` with a reason and no correlation or measured
offset; legacy receipts remain valid without that additive field. Existing files are never
reclassified or moved by a later import. Store format remains 1.

REV companions likewise require unique strong correlation within 250 ms of zero through the existing
synchronizer. Their manifest retains offset, drift, confidence and the matching WPILOG hash;
ambiguous or unmatched REV files wait unassigned. This admission rule does not constrain ordinary
analysis of loose REV logs on other clocks or rewrite existing store associations. Sync cache format
11 accompanied this import rule and invalidated the interpretations cached before it.

#### One writer

There must never be two store writers. `POST /store/import` submits to that registry and exposes an in-memory job for polling. The command finds the named daemon through its PID file and health check and posts to it. It imports in its own process only when no daemon is running, and never falls back after an HTTP failure.

Every import, including an inbox import, takes a nonblocking `FileChannel` lock on `store.lock` before inspection and holds it through placement and manifests. A daemon started during an offline import therefore reports the held lock instead of writing beside it or waiting indefinitely. The lock file stays in place after release: deleting it could give two processes different inodes to lock. Shutdown stops polling and drains imports before closing shared log readers.

The HTTP write surface requires configured directories for both the store and its sources, resolves symlinks with the shared security validator, and uses the MCP Origin gate. Outside sources are local copies or moves into the user's inbox; the endpoint cannot move arbitrary files on the server. Jobs retain their last progress and full result, with at most 100 jobs per transport. Completed jobs are evicted first; if all 100 are active, admission returns 503. Jobs disappear at restart.

Store locks must be regular paths, never symbolic links, even to targets inside the store. The registry checks this before HTTP admission and the queued import opens `store.lock` with `NOFOLLOW_LINKS`. The shared containment validator treats dangling links as existing path components whose resolution must fail, so it cannot authorize a new outside file through a missing target.

#### The inbox

The inbox uses a daemon polling thread every three seconds over known inboxes. Store discovery walks configured directories at watcher startup and at most once a minute thereafter; listing scans also pass along the stores they discover. With no configured store it does no imports. Size and modification time must match across two looks at least three seconds apart, and are checked again after waiting in the store queue.

Files move through the existing importer, and successes and refusals get JSON-lines receipts in `inbox/imported.log`. Unchanged refusals are not retried until restart. Duplicate content remains in the inbox with its stored destination explained, as the importer leaves duplicate sources intact. The listing reports inbox paths, sizes, and waiting/importing/refused state separately from unmanaged files.

The command stages in `inbox/.transfer-<id>/`, with an external transfer lock acquired before directory creation, then publishes `inbox/batch-<id>/` by atomic rename. Hidden entries are never walked for imports or listed. The next poll removes an unlocked abandoned transfer and writes a receipt; a slow active copy retains its lock. A batch sidecar `batch.json` carries `stated_robot`, and its stamp participates in settling and retry. A malformed sidecar refuses the batch; loose files have no stated identity.

#### Pairs and assignment

Reading before placement lets an import pair files still beside one another. Copies are loaded again and hashed before the pair's manifest is atomically replaced; failed ordinary Copy imports remain unmanaged and failed renamed moves are restored when possible. No existing log is overwritten or deleted.

Move uses an atomic rename on one filesystem. On different filesystems, or if atomic rename is
unsupported, it creates a new copy, forces it to disk, verifies its inspected hash, commits the
manifest, and only then deletes the unchanged source. A failed verification removes only that
Move's new copy; the original survives. Failure to remove the source after commit is a successful
import with an explanatory reason, never a rollback of the stored file. The reader reservation
covers the entire operation. Upload staging uses this same path, normally the rename case.

Explicit `POST /store/assign` moves manifested unassigned files into robot sessions through the same queue and lock, commits the destination manifest before removing the old import manifest, and preserves the first import's provenance and moved-path notices. Assignment cannot silently adopt an unmanaged or already assigned file. Identical content already held is reported with its path and its source is left in place.

Moving a payload listed by another store is refused before inspection or placement, with that store named; copies and unmanaged sources remain allowed. Other stores’ manifests are read once per source store in a batch, so the refusal does not add a catalog walk per file.

Filename collisions and payloads named `import.json`, `session.json`, `robot.json`, `store.json`, or `batch.json` use a hash subdirectory while preserving the filename; separate sessions starting within one second use a numbered suffix. Imports preserve original paths and filenames, with moved-path notices for seven days. A stated robot is renamed when a serial is learned. When that serial already has a directory, both manifests name the serial and the import reports the two directories for a later explicit merge.

#### Nominating sessions for a REV file

An import reads the catalog once and updates its in-memory facts after each successful manifest write, rather than walking the store for every file. Before correlating a REV file, it nominates the stated robot's sessions near the REV file's recorded wall clock, else its filename time. The window extends two hours on either side of the REV range; a filename estimate gets another fourteen hours because its zone is not recorded. With no REV clock it considers all of that robot's wpilogs. These estimates never establish a match or widen a session: the signal data decides, and the wpilog supplies the session's time range.

#### Readers and moves

Import, listing, and shared lazy readers own every window through `ScopedLogReader`. It invokes the cleaner on all windows before releasing the read lease, including failed constructions; a failed cleanup leaves the lease held so an import cannot race a live mapping. They cannot rely on GC to permit renames on Windows.

The manager's `acquire` returns a use that tools, streams, and background synchronization hold until they finish reading; eviction stops caching and unmaps after the last use closes. An import reserves its source paths against new reads, then calls `release` to evict them and wait up to three seconds for existing readers. A call that still holds a file gets an explained refusal; after it finishes the import can be retried without restarting. The reservation lasts through the rename, including robot-directory promotion, and no I/O or waiting runs under the file-access monitor.

### Path security

A path given to a tool is checked before the file is opened: its real path, with symbolic links resolved, must be inside a configured log directory. The server validator's dynamic set is the union of permanent configuration and live session leases (`ClientLeases`); listings, store discovery, inbox polling, and import validation see the same leases. A server with no configured directory and no lease admits nothing, and cached files receive the same validation as new reads, so a lease that ends takes its files with it. Discovery resolves the configured root before walking, without following links below it: a linked root is usable, while child links cannot introduce cycles or broaden a lease. Ordinary listings retain the configured spelling; store members retain their canonical catalog paths and the real-directory prefix filter. CSV exports are written only inside the export directory, which is checked the same way.

### Shared read API

The `read` package owns record selection and bounded batches for local callers, MCP tools and
Arrow/CSV extraction. `LogAccess.local` requires explicit roots and owns its snapshots; it reads
no home configuration, creates no store, starts no daemon and writes no cache. The server's
`ManagedLogAccess` uses the existing `LogManager` admission, leases, catalog aliases and cached
index instead. It does not map or scan the same file again. Native opens suppress automatic REV
synchronization without changing the manager's global setting; a subsequent legacy acquisition
still starts synchronization for a natively loaded version. `StoreReadCatalog` reads existing
manifests and durable aliases without starting a writer; `RecordedRevRead` applies only the
recorded alignment. The additive `/read/v1` routes and `RemoteLogAccess` expose retained snapshots
and the same queries to third-party clients. `readerJar` packages the pure reader and catalog
owners separately from the server. [READ_API.md](READ_API.md) defines the public lifecycle, wire
formats, limits and examples; existing REV tool and endpoint behavior keeps its original owner.

Both adapters feed `SampleCursors` through a borrowed `RecordSeries`. Local snapshots, cached
WPILOGs and writer-owned live prefixes share this traversal; sorting permutations and cursor
lifetime guards each have one owner. `LegacyMaterializer` preserves whole-entry decode counts,
first-error wording, cached values, unknown-type previews and stable double-second ordering for
existing tools and Arrow/CSV. The selection engine admits a contiguous range to the backend, whose
tight loop delivers decoded values to the sink. Whole-entry reads use one range, without per-record
query dispatch or a second sample/batch representation. File and live backends own their storage
access; both clients share the decoding and failure policy. Narrow cursors still read headers
before copying or decoding payloads. In particular, adjacent large integer timestamps can round to the
same double; the legacy view preserves their file order, while native cursors retain exact
microsecond ordering. The compatibility view is the original `LogData` object, because reload
and REV synchronization checks rely on its identity. Domain rules and existing encodings stay
above this boundary, and narrow legacy queries still validate the whole entry.

`LogScan` owns indexing and corruption rules for both paths. Indexing telemetry and locating a
window read record headers without copying payloads across mapping boundaries. Each entry keeps
its compact file-order offsets plus constant-size monotonicity facts. Ordered entries use binary
search. Only an unordered entry requested in time order needs a primitive ordinal permutation,
ordered by integer microseconds and original ordinal. Its temporary timestamps and permutation
cost twelve bytes per record during sorting and retain four afterward. Their shared per-access
budget defaults to 64 MiB; exceeding it is an explained refusal, with file-order reading still
available. Snapshots of the same loaded file version share the permutation; live views share only
when they belong to the same writer and publication boundary. The final snapshot's last cursor
releases it, so a later prefix cannot inherit a shorter sort and a closed peer cannot release
another peer's memory charge. Local snapshots use canonical path and file attributes for identity.

`StoreReadCatalog` retains its inventory and canonical membership map until a manifest, inspected
directory or payload changes identity, size or modification time. It checks those dependencies
without reading payloads; expiry of a moved-path notice also refreshes the listing. An external
rewrite preserving all attributes remains outside this check, as for loaded logs. Concurrent
refreshes share one future; filesystem work and waiting happen outside the ownership monitor.
The refresh owner scans at most twice when dependencies move. Its post-scan validation accepts
same-identity growth only for manifested open capture payloads; manifests, directories and closed
payloads stay strict. Cache reuse remains strict for every dependency, including open payloads,
so later growth refreshes observed sizes. Shrinkage, same-size modification and replacement cannot
pass as growth. Two unstable scans return retryable `FILE_CHANGED`, not a history verdict; the
managed reader and ordinal engine still own writer/ordinal/hash continuity. A missing member
while building the canonical map also restarts the bounded scan rather than publishing it.
The HTTP endpoint retains the catalog while its admitted root set agrees, retiring an old set
after its final request. REV descriptions and queries share bounded immutable decodes there,
with leases charging active reads and alignment kept per request. Native REV queries decode only
exactly selected signals and the first model value needed for a SPARK identity; describe still
reads all signals, and a complete decode can serve a selection. The decoder's input and
decoded-storage limits are documented in [READ_API.md](READ_API.md#stores-and-recorded-rev-alignment);
legacy synchronization keeps its existing decoder limits and results. Native REV retention uses
primitive timestamp/value chunks, charged before allocation, without the former four-million-value
ceiling. Store-reader tolerance is explicit and separate from writer inventory: invalid historical
sessions may be skipped by either, but invalid robot/control manifests still stop writer inventory.

A query selects one entry and explicit disjoint windows, with inclusive or exclusive right ends.
`read.FieldPath` owns projection for native and legacy callers; `NumericBucket` owns arithmetic
for their reductions. Native buckets use exact integer boundaries while legacy tools retain their
double-second results. `TimeWindows` shares containment and binary-boundary traversal without
rounding one clock representation into the other. Multi-entry reads admit their cursors together
and return separate tables rather than inventing a join. A manifest-backed session union applies
recorded clock transforms to an explicit anchor, rejects unexplained overlaps by default, and
keeps source coordinates. Capture rollover needs no fitted offset: `CaptureWriter` keeps the same
session object through rollover and continuing reconnects, and creates a new one after a backward
clock or a reconnect discrepancy beyond five seconds. `CaptureStore` records the writer's original
start instant on every file. A union checks that provenance and the collecting store, preserved
through peer copies and mirror origins. Peer sync can consolidate manifests using calendar
information, so a shared session ID alone never proves a shared clock. An external editor forging
those provenance facts is outside this recorded-evidence rule.

Server sessions borrow `ManagedLogAccess` for every member, reusing its cached index and live
publication boundary. A later append does not invalidate that writer-owned prefix. When a live
member shares an entry with another member, `KEEP_ALL` is required: stale manifest timestamps
cannot prove those recordings are disjoint. A standalone
catalog cannot freeze an external writer and excludes manifested open captures with a reason;
choosing one as the anchor is refused. Read the session from its recording server or follow files
individually. The merge shares an 8 MiB lookahead bound across at most 64 files. A catalog also
charges at most 128 MiB of retained session address indexes, until each snapshot's last cursor
closes. Admissions use a nonblocking single-owner guard: at most one uncharged cold index is being
constructed, and competing opens get `BUSY`. File IO and closes remain outside monitors. This is
a retained-index bound, not a total heap quota, and one cold index can exceed it before refusal.
Unproven files are excluded with reasons, and identical hashes are not read twice.

Session windows are mapped backwards through the recorded alignment chain before the shared
cursor's binary search. Exact offset subtraction preserves large integer timestamps. Drift
inversion rounds outwards, allowing for each floating operation and integer rounding in the
forward mapping; final inclusion still uses that original forward mapping. Overlapping inverse
margins are merged to avoid duplicate reads. Extreme coefficients fall back to a complete read
when a conservative inverse cannot be established.
Batches visit at most 4,096 records and target at most 4 MiB of encoded payload. File-order records
outside a window still count against the work bound. A larger record is delivered alone, up to an
explicit 64 MiB default limit; controls and recorded schemas are checked before allocation too.
These are encoded-byte and sort-workspace bounds, not a total heap quota: mappings, offsets and
expanded decoded values have their own cost. Integer timestamps, long byte positions and native
values survive without numeric coercion. Unknown types retain their complete bytes in this API;
legacy tools keep their historical preview. Structs use the shared decoder and report schema basis.

A snapshot publishes the file boundary it indexed. Its open and each batch check file attributes
before and after reading; a changed file discards the operation and requires reopening. This also
applies to a growing file read from disk unless the caller explicitly uses `LogFollower`.
That caller-polled owner reuses `LogScan.resume` after the identity/header/last-record anchors
agree, retries partial tails, and reads appended ordinals without assuming timestamp order.
Each refresh remaps a fixed prefix; an admitted older cursor keeps its original mapping.
Replacement or changed anchors select a fresh scan, never an append to an unrelated file. A mapping pins a lifetime, not an immutable copy; edits
that preserve the filesystem identity, size and modification time remain undetectable, as in the
existing manager. Closing an owner rejects new reads, while admitted cursors retain every window
until they close. The process-wide move lease is released only after unmapping, including on
Windows; scanning, sorting and unmapping happen outside the lifetime monitor.

Native cursors report decode problems only for selected records. Full validation is an explicit
all-record cursor. Existing MCP and data endpoint reads retain their whole-entry diagnostics;
selective reading must not silently remove an existing warning.

A managed live snapshot retains the writer's published sequence, entry lengths and robot-time
scope end. Later appends cannot change that view; hot wire values and mapped cold records expose
the same source values without rescan. Its file boundary is null, so appends do not invent an ETag
or invalidate the fixed prefix. Closed files still use the existing reload and stream-change
checks at their original response boundaries. Owner shutdown retires native snapshots after their
last cursor; a compatibility tool's acquisition stays retained until the caller closes it,
even if shutdown evicts the cached log meanwhile.

The HTTP reader retains a bounded set of expiring snapshots so multiple queries see one live
prefix, not a freshly opened file on each request. Active requests postpone idle expiry; the
idle clock starts again when they finish. DELETE retires admission but lets active cursors finish.
Bounded streaming workers and an IO-progress deadline contain slow or stopped readers. NDJSON preserves integer coordinates and floating-point bits with explicit
tags; Arrow shares one type mapping and the existing IPC writer. Both require a terminal status,
so a disconnected or invalidated response is never a completed query. Store paths are checked
against manifest membership after canonicalization, including paths reached through symlinks.

The library's manifest types are the store's existing record owners, not a parallel format.
Transfer-journal inventory and metadata interfaces are pure shared owners, keeping store writers,
server singletons and network providers out of the reader artifact. Isolated consumer compilation,
execution and bytecode dependency checks enforce this boundary. Recorded REV remains an eager
adapter with double-second timestamps and a charged immutable decode; it neither claims
constant-space input streaming nor recomputes an offset. Raw and converted clocks are explicit.

### Loading

The log manager returns a log already in memory, or loads it. Loading takes a lock for that path, so two calls for the same log load it once, while different logs load in parallel. `MappedLogBytes` owns read-only windows of at most 1 GiB, addressed with long offsets, up to a stated 1 TiB file limit. `LogReader` validates and frames each record once, passing only its payload view to WPILib's unchanged `DataLogRecord` value and control decoders. Previously the long-addressed reader framed a complete record, then an upstream reader framed it again and allocated more buffer views. A payload contained in one window is a view; only a straddling payload is copied. The window size is injectable for tests, and buffer-backed differential reads retain WPILib's original framing.

For another process's append, `LogScan.resume(previous, reader, path)` copies the old compact
offset index, entry declarations (including ID aliases and metadata) and time range, then reads
from the first unindexed byte. An incomplete final record leaves that byte as the resume point;
an ordinary mid-flush tail is truncation, not damage. Each resume maps the whole file anew and
gets a new decoded-value cache, so a previously decoded entry cannot stay shorter than its index.
The old mapping retires after its last use; a result spanning growth is still discarded with
the existing explained error. Successful disk-backed calls report the admitted snapshot's
`inputs.file_size_bytes`.

Resuming requires growth and equal filesystem identities, plus unchanged bytes of the complete
WPILOG header (extra header included) and the last complete record before the old resume point. The
scan saves SHA-256 digests of those two anchors, not views of an old mapping that a rewrite could
change. Anchor reads use a bounded buffer, and their cost depends on the header and last record
sizes.

An interior rewrite preserving both anchors is not detected. This is a cheap append check, not a
full-prefix integrity proof: hashing every indexed byte would cost the scan we are avoiding.
Replacements, shrinkage, damaged prior scans, failed anchors and unknown identities all load afresh.

A filesystem key is the preferred identity. Where the JDK returns no key, including the usual
Windows provider, the file's creation time is the identity instead. NTFS can give a recreated file
the deleted file's creation time within a short window (file system tunneling); the saved header and
last-record anchors defend against that reuse. A replacement preserving both creation time and
anchors can still escape this cheap check. The checkpoint lives in the existing bounded log cache,
not a second collection that grows with every path ever read.

A loaded log follows its file. The manager keeps the file's size, modification time, and identity (the inode, where the file system has one) as they were just before the log was read, and compares them with the file on every call. A file that changed is loaded again, and a file that is gone is an error.

The comparison is made again after each call, and a result read across a change is discarded with an error that says what changed. The result may hold old data (a file renamed into place keeps serving its old bytes through the mapping) or mix old and new (a file overwritten in place has its old record offsets applied to new bytes). A change the attributes do not show can still make a read of the mapping fault, which the JVM reports as an `InternalError`. The tool base turns that into the same explained error and unloads the log; before, it escaped every catch and ended a stdio server.

Each session is told once, on its next result from the log, that the log was reloaded, since results it holds from earlier calls came from the old file. A session that first used the log after the change is not told, having nothing stale.

The REV log tools look again for the REV logs that belong to a wpilog, at most every two seconds, and synchronize again when the candidates or their files changed, keeping an offset set by hand for a file that did not. These rules came from logs copied off a robot while it was still writing them, and copied again once they had grown.

Loading does not decode the log. Mapped windows live outside the Java heap, and a single pass records each entry (name, type, and metadata, in announcement order) and each data record's long byte address. `RecordOffsets` keeps an int array until that entry first exceeds the signed-int range, then promotes that array to longs. Ordinary files therefore still need four offset bytes per record: widening the API must not double every team's index.

Records are read one by one through WPILib's record-level access, because WPILib's own iterator silently skips a final record shorter than 16 bytes. If the scan fails in a way the rules below do not cover, an older parser is tried, which decodes the whole log into memory at once.

The scan is built for logs that were not closed cleanly:

- A record that runs past the end of the file ends the scan at its first byte, without counting that partial record as damage. An undeclared entry or a parser failure is damage. The last few records before an unreadable tail are dropped if their timestamps jump by more than 60 seconds, since a torn write can look like a valid record; dropped records also count as damage and require a fresh scan.
- A record more than a day ahead of the latest timestamp is ignored wherever it appears.
- Negative timestamps are kept. WPILib's DataLogManager writes records before time zero.
- An entry declared twice with the same type is one entry with all its records. A name declared again with a different type is ignored, with a warning in the server's log.
- A file that is not a WPILOG at all (empty, all zeros, another format) gets an error that says what the file is.

The scan tells a log that only ends inside its last record, the ordinary end of a robot's log, from one that lost more: records dropped or ignored, garbage, a header that runs past the file. Results on the first carry a note, and on the second a warning.

These rules came from real logs. Three of the 88 logs the scan was first run against ended in garbage records, with timestamps as large as 6 × 10¹² seconds, which had stretched their time ranges.

### Decoding

Values are decoded the first time a tool asks for an entry, all of that entry's records at once, and kept in the log's value cache. Concurrent requests for the same entry decode it once. Records that fail to decode are counted and reported, and the entry's other values are still returned.

Structs are decoded from the schemas the log records, using WPILib's schema parser. That covers any struct, including nested structs, enums, bit-fields, and a team's or a vendor's own types. For a struct whose schema the log does not record, WPILib's geometry and kinematics schemas are used, and a few types from common team templates are decoded with an assumed layout and marked as assumed. Each struct type's decoding plan is compiled on first use and cached. [TOOLS.md](TOOLS.md#data-types) shows what decoded values look like.

## Foreground and supervisor ownership

A named foreground HTTP `run` may use port zero: the listener chooses its own available port
and reports the bound address. Probing a free port and releasing it before a child binds is a
race; it caused the foreground ownership test to fail under a parallel build. Daemon loading
still rejects zero, since adoption, stop and bridges discover the server by its configured port.

`run <name>` shares the internal daemon's configuration/startup path but never claims a PID
file or daemonizes. Only `--managed` publishes supervisor ownership through the registry to
health; a hand-written unit must pass it too. The inherited `INVOCATION_ID`
does not establish ownership: using it also marked GitHub runner jobs and terminal-launched
daemons as managed, breaking adoption and stop. The daemon manager probes this fact before adoption,
replacement or stop, including a configured port with no PID record. A stale PID claim grants
no authority over a managed server. Only the internal daemon accepts its spawn's stop token.

`config.ServiceUnit` prints templates from `resources/service/`; it installs nothing. A system
account owns the state directory, systemd owns restarts and bounded signal shutdown, and the
separate unprivileged health timer reports failures. The templates preserve JIT and leave JVM
heap sizing to the existing launcher rather than imposing a second memory limit. STANDALONE.md
owns installation, hardening exceptions and the calendar-clock caveat.

Installed JARs are `0644`, launchers and program directories `0755` on POSIX, set before
atomic publication and repaired on reinstall. Owner-only temporary files previously left a
root-installed launcher unreadable to the service account. Configuration and existing parent
directories retain their permissions. CI checks access as that account before starting the
unit and supplies the runner's JDK through `JAVA_HOME` in its environment file.

## Memory Management

The daemon’s maximum heap comes from the launcher’s `WPILOG_MAX_HEAP`, `4g` by default; a background server inherits it from the JVM that starts it. The extension sets that variable from its `wpilog-mcp.maxHeap` setting in the launcher’s environment when it starts or restarts the daemon (and runs the installer with the same heap), so the size never appears in a process list. A daemon another client started keeps its own heap until it is restarted.

A loaded log costs:

- The mapped file, outside the Java heap. The operating system pages it in as it is read and can drop those pages again at any time.
- 4 bytes per record for offsets in an ordinary file; 8 for an entry whose addresses extend past 2 GiB, plus the entry table. Arrays are compacted after scanning; offsets are never individually boxed. A crossing record temporarily costs its record size on the heap.
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

The disk cache saves the expensive part of loading a log that has REV logs: parsing them and correlating their signals. Its key covers what a result depends on: a fingerprint of each file, both file names (the time in a REV log's name feeds the alignment), and a hash of the CAN database used to decode the frames.

The key does not cover the code. So the cache has a format version that is raised with any change to parsing, decoding, or synchronization, and entries of another version are deleted. Before that rule, results computed by older, wrong code were served indefinitely. The server's own version is not part of the key: a release changes the server for many reasons, and rebuilding the cache with each one would make every user wait for synchronizations that come out the same.

Other choices in the disk cache:

- The fingerprint is a SHA-256 over the file's size and three 64 KB samples (its start, middle, and end), not the whole file. That is a trade of certainty for speed: a change confined to another part of a file would go unnoticed.
- A sync cache file above 64 MiB is refused before its contents are opened and removed by the startup sweep. The read is also bounded by the measured size plus one byte, so growth after the size check cannot bypass the cap. This bounds optional encoded cache input, not source-log size or total decoded heap; an oversized entry is recomputed from the logs. The written format stays unchanged.
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

In stdio mode the server handles one message at a time. HTTP dispatch uses four core threads
and grows to 32 with direct handoff and no waiting queue. Data and native-read routes hand off
to 16 streaming workers; store and import routes have four independent workers. Excess transfers
normally receive JSON `BUSY` and 503 with a retry hint. Each streaming pool has two final-refusal
workers with direct handoff and the same I/O watchdog; MCP execution overload uses the ordinary
pool's refusal workers. When refusal capacity is exhausted, the exchange is closed before
response headers. This avoids the JDK's unread-body drain on dispatch workers; a client may
receive a disconnect instead of JSON. Stalled streams and refusals cannot occupy health or
registration's dispatch workers. A progress watchdog interrupts the owning
SocketChannel worker after ten seconds without IO progress, including request uploads. Output
progress is checked in at most 64 KiB units. The watchdog never flushes or closes an exchange
itself, since closing a chunked stream can block behind the same peer. If all 32 dispatch
workers are occupied by other handlers, a further exchange is still rejected by the JDK dispatcher,
which closes the connection without an HTTP response: the client sees a reset, not a 503. This
is distinct from MCP execution admission, which can return 503. MCP calls use a separate pool capped at
min(32, max(4, 2 × CPU count)), with up to 64 further threads for event streams. Requests are
not serialized per session.

`RequestBodies` owns size-refusal draining for JSON routes. It retains only the route's limit
plus one byte, then discards at most another 4 MiB through an 8 KiB buffer before the endpoint
formats its existing refusal. Previously the JDK could close with unread bytes in flight,
resetting the socket and losing the response. Draining has a ten-second deadline in addition
to the route's existing earlier deadline. A daemon watchdog interrupts the owning reader; it
does no socket I/O and cannot interrupt a later request after the drain finishes. If EOF has not arrived at the
discard bound or deadline, the exchange closes before response headers, avoiding the JDK's
additional blocking drain on response close. A complete refusal is then not guaranteed. Binary uploads remain with
their streaming owner.

Directory/key registration is serialized only with that session's removal, through the session map's per-key computation; validation and filesystem I/O happen beforehand. DELETE, expiry, and transport shutdown discard the session's permissions together, so a late registration cannot resurrect them. Keys remain in memory, with the most recent live registration taking precedence over the file. Registration is HTTP-only, behind the Origin check and refused on any non-loopback bind: a model's tool call cannot grant itself access.

The design keeps shared mutable state small:

- Tools hold no state. One instance of each tool serves every thread, and everything a call builds is created for that call.
- Shared structures that change while the server runs are concurrent ones: concurrent maps, the Caffeine caches, volatile fields, and atomic counters. The code takes few locks. One guards each log path during loading, and one keeps heap-pressure checks made at the same time from each unloading a log. One makes starts of a background server take turns, and the store/import and install guards are described above.
- Check-then-act sequences are atomic. A log is loaded once under its path's lock, after a second look at the cache. An entry's values are decoded once, through the cache's per-key computation. A finished synchronization replaces its placeholder only if the placeholder is still there, so a log unloaded in the meantime stays unloaded.
- A background start claims its PID file with an atomic create. A version-mismatch restart replaces the old daemon's record with the starter's PID and `starting` marker while still holding the start lock used to check its version, then retains that claim through stopping and spawning. Another start therefore waits even after the old daemon's PID dies; it cannot decide on the same old record or remove the restart's claim as stale. No start lock is held while waiting for a process to exit or boot.
- Background work is limited to a single thread that runs REV log synchronizations one at a time, a timer that checks heap pressure every 5 minutes, a timer that removes expired sessions, one sweep of the disk cache at startup, and the known-store inbox poller. The value caches' own upkeep is Caffeine's, which runs it on the JVM's shared pool.

On shutdown, the HTTP transport ends its event streams, waits up to 5 seconds for calls in progress, and closes its listener. Then the log manager stops its background threads and unloads the logs, so that calls in progress can finish before their logs are closed.

The NT4 gateway charges queued work per peer and isolates a peer that exceeds its allowance.
A short stall that fits the budget leaves views connected. If an upstream publication is refused,
one reserved cleanup disconnects every view when fan-out resumes, even below the five-second
stall bound: a missed change-only value must not leave a connected dashboard silently stale.
The global stall fallback still requires five seconds without progress, measured at admission
outside the loop. Recording waits for neither path. The budgets and ownership are described in
[the recording architecture](pit-server/ARCHITECTURE.md#gateway-work-limits).

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

The explorer shares this server through `mcpClient.ts` and `dataClient.ts`. Its webviews only draw results under their content security policies and open no network connection to the log server. Entries uses a sidebar webview so its inline filter can query full entry names, including unopened branches; it reuses the existing entry tree and field-information provider. The same pure tree builder groups by path or exact logged type; the provider owns the workspace grouping preference and applies it together with any pending filter. Each refresh invalidates the sidebar's node handles, so a late reply or click cannot act on the previous log or query. Pure models handle tree grouping, organizing decisions, and requests; `explorer.ts` and `extension.ts` hold the VS Code glue. Node tests cover those models, both platform path/command rules, fake transports, and a real JAR; real VS Code checks are listed in [DEVELOPMENT.md](DEVELOPMENT.md#extension-tests).

The extension's activation export exposes readiness and a read-only view of its actual Logs
provider, so the real-editor smoke can inspect the same tree users see without a second model
or a test command. It does not expose the extension context, SecretStorage, or server manager.
The smoke owns a disposable standalone install and an argv-recording Claude executable;
ordinary Node tests and packaged extension code do not load its Electron runner.

### Visualization contracts and recorded preview

The recorded preview registers an optional source overview and independent working-view documents beside
the existing default editor. The Java visualization contract resource is the authority for source references
and declarative configuration. Strict raw-text parsing rejects duplicate keys, non-finite
numbers, lone surrogates, unknown fields, future versions, and excessive depth/size before state
admission. The internal object-taking validators cannot recover duplicate keys discarded by an
earlier parser. MCP adapters carry configuration/envelope definitions as JSON text until this
validator runs; the HTTP adapter validates raw JSON before object conversion.
No source connection, credential, sample buffer, or filesystem locator is part of a durable source
reference. Binding and telemetry still belong to the existing resolver and reader.

A named configuration owns a persistent server UUID in its independent visualization state
folder. If opening that optional state fails, startup warns and uses an ephemeral identity;
health and the guide report the reason. Working-state operations use their own bounded,
single-worker queue (64 pending jobs),
atomic replacement, and exclusive cross-process state lock. They never join recording or import
work. Limits are 256 non-deleted working records, 256 MiB aggregate live metadata, and 1 MiB per configuration;
quota exhaustion retains existing work, and every budget check first removes expired records.
Deleting a view releases both live quotas. Its retry tombstone has a separate six-hour lifetime,
256-record cap and 8 MiB byte cap; pressure evicts the oldest tombstones, never live views.
Recovery applies those bounds too and persists any shortening of the unreleased prototype's
30-day tombstone deadline to at most six hours from recovery. Restart cannot extend that deadline.
Deleted views do not pin source locators. Expiry or eviction ends their retry guarantee.
A malformed or unknown working record moves intact to `quarantine/<uuid>/` under the state
directory with a warning; valid records still load. Quarantine is outside the active-state quota
and is retained for manual inspection. A failed quarantine move retains the original and fails
recovery. Detached records carry a 30-day recovery expiry; attachments currently last until
explicit detach, session expiry/removal or restart. The removal callback coalesces session IDs
and only enqueues cleanup; it performs no I/O under the session map lock. Admitted jobs drain
coalesced releases even when the queue is full. An attachment rechecks session liveness on the
state queue, so expiry while queued cannot resurrect a dead client. A late old-session release
cannot detach its replacement. Shutdown stops admission,
allows queued operations to drain, and waits at most two seconds; a daemon cleanup task retains
the state lock until that drain completes. `VisualizationWorkspace`, owned once by the registry,
is the application boundary for HTTP and the named MCP adapters. `RecordedSources` retains a
reference-counted catalog across requests; root changes retire it after readers release it.
Construction and disposal run outside admission locks. Bindings delegate to `SignalResolver`
and numeric field validation; statistics delegate to `get_statistics`, raw pages to the reader.
No browser duplicates those deductions.

`TemplateDefinition` validates the portable envelope and uses the Apache-licensed JCS library
for RFC 8785 binary64 formatting/property order. SHA-256 covers the envelope without its digest.
`TemplateMetadata` owns store template revisions and portable import proof. Metadata/receipts
commit together; a complete bounded registry is required before import conflicts are decided.
Template records are limited to 1 MiB/64 receipts per map, catalog reads to 1,024 files/8 MiB.
The store reader reports a removed template as `TEMPLATE_UNAVAILABLE`; updates read the existing
record under the store lock instead of treating a missing file as revision zero. Existing views
keep their copied configuration and pinned provenance even when that template disappears.
Working mutation receipts share their atomic record, up to 4 MiB/4,096 receipts. Save/clone
reserve the original intent before work on another queue; completion retains its original answer.
Save records the reserved checkpoint without discarding a later working edit. Configurations
are copied into each view; updates to a template never rewrite them.

`ControlNotifications` publishes small immutable invalidations into negotiated per-session
rings (256 events/1 MiB). It never performs socket I/O on the state queue. SSE workers and a
direct-stdio daemon perform delivery, with serialized stdio writes; epoch/sequence replay
requests resynchronization when history is gone. Explorer's control channel is independent of
sample HTTP requests, and its ordered document controller applies incoming revisions with CAS.
An SSE worker borrows the endpoint's existing notification owner; it never invokes the lazy
workspace factory. If optional visualization initialization failed, ordinary SSE pings continue.
HTTP shutdown ends the stream workers and drains admitted calls before closing that owner, so
a stream or tool cannot recreate a workspace during the normal shutdown drain.
Hidden views receive definitions without data work; a changed query or reveal cancels/replaces
owned reads. Explicit cross-client display and continuous telemetry remain later stages.

Saved-view writes use the store's existing queue, cross-process lock, containment checks, mirror
refusal, and atomic writer. Revision comparison happens under that lock. Saved listings validate
records independently and report unreadable shared files without moving them. Their record/byte
budgets include failed reads and return an explained partial listing; opening by saved UUID reads
only that file. Visualization metadata
is excluded from unmanaged-file discovery; older readers can still list it as stray metadata.

Caller authority is a separate immutable request value. Stdio explicitly grants trusted-local
authority. HTTP derives it from the actual loopback listener, loopback peer, and Origin for each
single or batch call. An omitted context is untrusted. The handler clears authority and session
state in `finally`; queued mutations receive the captured value rather than reading a later
thread-local. No new acquisition connection or telemetry pipeline is introduced.

Recorded sources reuse the catalog's manifested origin/hash and the shared security/reader owners.
Managed content is checked with the existing store hash owner before decoding; successful proofs
are cached for up to 256 file boundaries for ten idle minutes. A changed boundary or server restart
requires another proof, so modified bytes cannot silently retain an old SourceRef. Catalog facts
can still appear before this work. Unmanaged locators persist separately from SourceRef and include file identity, size and times;
changing that fingerprint invalidates the view. An in-place rewrite preserving every fingerprint
attribute is not detected for an unmanaged file; the fingerprint is not a content proof.
Locators with no unexpired working view are reclaimed at restart and under quota pressure;
expiry also releases its unreferenced locator. Recent identifications remain available between
identifying a source and creating a draft. The 1,024-entry/1 MiB bounds are checked after reclamation,
and reopening the unchanged file restores its deterministic ID. Overview facts precede the index request. Duration,
entry counts and completeness reuse the existing index, without decoding values. Cursor tables
stream original samples through `RecordedValue`: greatest timestamp at or before the cursor,
then greatest file ordinal for equal timestamps, with true problem counts and bounded details.
Plots reuse the existing Arrow endpoint, decoder and uPlot; they never derive table statistics
from buckets. No second log cache or acquisition client is introduced.

Recording inspection uses the workspace's catalog lease and the same admission owner as ordinal
follow. Both paths share the conversion from the wire reference to the recording identity.
An open manifested capture is identified by
origin, session and original provenance anchor; its member prefixes retain the follower's
writer-identity, hash, same-recording, rename and grant checks. Inspection closes every prefix
before returning. It neither installs a continuing lease nor resolves a recording to one path.
The default full inventory retains each member's native descriptor and a name summary; conflicting
types or metadata are reported without selecting a member. The summary is not a field/schema
binding decision. Both retained member descriptors and the final inventory have a 4 MiB encoded
cap; each descriptor also retains the native reader's allocation preflight. This is separate
from the later display-memory budget. Clock facts use the fixed view's shared NT4 estimate,
or the indexed last sample if no finite estimate exists. No telemetry is decoded for
counts or bounds, and no receipt/wall time enters the sample axis. A stateless inspection cannot
hold a previous display edge; that policy still belongs to the upcoming live display controller.
The Source Overview can inspect a recording, while live view creation, template binding and
the single-file analysis routes remain unavailable. Capabilities distinguish inspection from
the still-false `live` editor capability.
The HTTP and MCP adapters share one typed native-reader refusal mapping,
including temporary inventory changes versus permanent recording-history changes. One workspace
source owner shares successful content verification between recording inspection and recorded-file
admission. It remembers at most 256 canonical path/file-boundary/manifest-hash tuples, expiring
after ten idle minutes; close clears them. No payload, mapping or permission is retained by
this memory. A miss hashes the file, checking its boundary before and after; a changed path,
size, modification time, file identity or expected hash needs a new proof. Failures are never
remembered, and the opened native snapshot still has to match the verified boundary. Every
admission retains current catalog and path/grant checks. Edits preserving all observed file
attributes remain outside this proof, as with the existing loaded-log boundary checks.
Concurrent cold misses may duplicate hashing; no I/O holds the metadata lock or a cache
computation lock, and a hash finishing after close cannot repopulate the memory. Followers use
the same verification policy with memory owned by each lease. Paged inventory open/details reuse the
workspace owner. Repeated full inspection must not
become per-frame polling in place of the long-lived broker.

Paged recording discovery has one workspace owner for HTTP and MCP. Opening copies compact
name/type/member/count facts and metadata digest tokens from one admitted prefix, then releases
the prefix before publishing an unguessable session-bound handle. Pages filter and order this
fixed snapshot; cursors authenticate the epoch, inventory, filter, limit and position. No
sample data, raw metadata, schema graph or mapping remains in the inventory. The summary uses
the same clock-fact owner as full inspection. Fresh selected details use native schema closure
and the follower's member-continuity policy, comparing old declaration tokens before returning
current counts. New members or stale selected declarations require refresh, and compatibility
is never deduced from a page or a conflict.

Accounting includes builds and retired inventories still used by readers. Short metadata locks
claim or reclaim slots; admission, grant I/O, native close and future completion happen outside
them. A removed session marks its in-flight admission invalid without waiting for disk I/O.
The two no-queue build workers reserve metadata before starting; a ten-second response deadline
cancels publication but retains the reservation until a blocked read actually unwinds. Four
separate HTTP/MCP inventory workers leave control requests available. Response reservations
remain charged through serialization and socket writing, including MCP batches. Transport admission
carries the original UTF-8 byte count into normal dispatch, so parsing cannot erase evidence of
an oversized request. Refusal decisions follow session and basic request validation; notification
suppression and mixed-batch controls retain their normal owner. Each inventory operation in a
batch shares its whole exchange's 64 KiB input limit. Individual inventory replies remain bounded
to 1 MiB; an inventory-containing batch counts all encoded replies and array framing against a
separate 8 MiB ceiling. Overflow stops the remaining calls and reports a non-retryable refusal
that warns earlier calls may have executed. Handles expire
after 60 idle seconds or five absolute minutes; expiry and close bypass data request slots.
Current grants are checked on every page and retry. Ordinary listing never reopens payloads;
only a missing pathname triggers catalog alias resolution. Details reopen through native
admission. The [standalone reference](STANDALONE.md#recorded-visualization-preview) owns the
limits and refusal contract.

The recording overview borrows the window's MCP session and grants. It explicitly negotiates
paged support before sending a bounded open request; handle requests never migrate silently
to a replacement session. One initialization promise per visible generation owns catalog
identification and controller publication. Repeated ready messages join it; hide/close cancels
it and invalidates late identity, catalog and renderer replies. A source and its single inventory
owner are assigned together before awaiting renderer delivery, so initialization cannot overwrite
an admitted owner or fall back to full recording inspection. One serial request chain then owns
each overview. Generation checks reject
late filter, detail and refresh replies; the renderer returns a delivery acknowledgement before
another page copy is sent. Pending paints coalesce to the latest state. A five-second renderer
stall stops inventory acquisition, retaining the outstanding copy's reservation until receipt or
editor close. Hidden overviews discard the webview context, close their handle and release row
buffers; reveal recreates the renderer and opens a fresh snapshot. No hidden heartbeat exists.

At most two list pages, cursor breadcrumbs and one selected detail are retained per overview.
Selection uses the exact name in the anchored source. Refresh admits the replacement and its
first page before retiring the old handle, then resolves that selection again and reads fresh
details. An off-page selection uses a literal prefix of at most 256 codepoints and matches the full name exactly, one page at a time,
under the same cancellation and byte accounting, with a 1,024-page traversal ceiling and a
refine-and-reselect remedy. Visible absolute expiry and idle-expiry responses use the same
refresh path. Failed admission retains readable old rows with their timestamp and reason;
expired rows are labeled and disabled, and Retry backs off from one to thirty seconds. Known
idle/session/declaration invalidation survives a failed reopen independently of the absolute
deadline; only an admitted replacement page clears it. Deciding whether to queue one automatic
reopen does not determine whether an invalid snapshot is marked expired.

Unavailable declarations are an exclusive traversal of bounded previews. The renderer disables
ordinary name/type filters while keeping their values, and the request adapter omits those
filters in this mode. It never merges two inventories or searches shortened names locally.

Encoded inventory requests reserve twice their 1 MiB reply cap from the same 64 MiB acquisition
budget owner used by follow jobs. The window separately accounts retained inventory JSON,
host/webview copies and outstanding renderer delivery against 64 MiB, using conservative object
and string estimates rather than claiming an exact JavaScript heap bound. Admission refuses
before retaining or delivering excess rows. This inspection checkpoint does not connect live
panels. The planned configurable combined recorded/live display budget and its performance
gate remain part of panel integration; existing recorded plots retain their previous limits.

Follow metadata can be explicitly limited to the queried entries. This projects the wire
inventory before encoding, with only the selected struct schemas and their transitive
relationships supplied by the native schema owner. Member identities, writer/final-hash proof,
index admission and the server's checks on every entry's retained history remain complete.
The lease and each new frontier acknowledge the scope; the client refuses a missing or
inconsistent acknowledgement instead of silently accepting full metadata under a small page
budget. Existing callers retain complete descriptors. Default full inspection retains its
separate 4 MiB limit; the overview uses bounded inventory pages instead. Follow projection
does not reduce catalog scanning/indexing costs.

Each working UUID owns its controller, panel, data generation and cancellation. Configuration
revisions always reach the renderer, but the data key contains only source identity, requirements,
series use and recorded range. Title, geometry, style and checkpoint edits preserve current buffers
and in-flight answers. Query changes, hide and disposal abort obsolete requests. A reply is accepted
only for its exact UUID/query generation; another view's resources remain live. Entries derives its
target from the current native tab and selected panel and serializes additions with that view's
other gestures. Hidden legacy editors cannot regain Entries through a late listing reply.
Bundled GridStack owns layout presentation; server configuration remains authoritative. The
preview requests at most 4,096 plot points and 32 series per view, sequentially, with a 1 MiB
encoded-response cap and no host response cache. Each renderer retains only its current query's
series and values across presentation edits. The configurable aggregate decoded budget and shared
live display integration remain Stage 3; this preview makes no one-hour live or 1 GiB performance claim.

The first Stage 3 gate adds `read/server/OrdinalFollow`, a continuation engine over
the existing `ReadSnapshot` and `SampleCursors`, now exposed through native HTTP follow leases.
The source adapter supplies independently owned, immutable member prefixes of one proven
recording; it retains responsibility for catalog admission, session aliases and membership.
The engine fixes bootstrap P0 before the first request. It reads the requested historical
windows, then consumes physical ordinals after P0, including late samples outside those windows.
The first page of each prefix carries all member/entry counts and metadata/schema descriptions;
later pages name that `prefix_id` without repeating the description. Retries retain the original
bytes, including metadata until that page is acknowledged. Admission bounds the whole metadata
envelope, and permits a metadata-only first page when values need the next page's space.
Clients must retain the frontier; lost client state requires explicit rebootstrap.

Pages pack batches across entries and members, visiting at most 4,096 records across the entire
query, with a 4 MiB encoded ceiling; entry completion is distinct from frontier completion.
The engine rotates across member/entry/field selections in turns of at most 256 visited records.
The pending page owns its next scheduling position; only acknowledgement commits it, including
when byte pressure defers a record. Per-query batch limits remain page-wide. It uses one native
cursor per turn and bounded one-record reads to assign problem
ordinals and decide fit. A record fitting a fresh data page but not the current page is deferred
without progress; its lookahead counts as work. That selection is skipped for the rest of the
page while the others keep packing. The first deferred selection gets the next page's first
turn, committed only by acknowledgement. This avoids repeatedly denying a large record space
behind a chatty selection at small page budgets. Fixed frame overhead and each record's encoded
wrapper are charged conservatively before merging, then the final encoding enforces the ceiling.
A record above the payload cap or conservative wire expansion budget becomes a `RESOURCE_LIMIT`
problem carrying its ordinal, physical offset and payload size. This explicit data gap advances
only on acknowledgement; it is not a delivered sample. The shared native reader owns header-only
size admission through `ReadQuery.recordLimit`; ordinary queries still fail on oversized records,
and opting into problems cannot raise a snapshot's cap. Session queries preserve that policy.
Cold native decode failures become diagnostics at their physical offsets, while legacy
materialization keeps its prior exception policy.

An opaque cursor is scoped to a lease/epoch and its retained member generations, catalog
revision and per-entry progress. The engine keeps one exact encoded retry page and commits its
progress only when the next request acknowledges that page's terminal cursor. Callers must
finish parsing the whole response before using that cursor. Response handles independently
retain read-only bytes, so retiring a follow cannot release another admitted response's budget.
A shared encoded-byte budget charges pending responses and serialization reservations; this
is separate from native index/decoder memory and the planned client decoded-data budget.
Closing admission does not wait on a decoder or writer: an admitted bounded batch releases its
prefix on completion. No source acquisition, decoding or socket work belongs on the recorder
loop. Replaced members, shrinking history and changed recording identities require explicit
rebootstrap; finalization drains its fixed final counts and never opens a later recording.
`CaptureRecording` owns the original provenance/first-collector rule used by both session unions
and `StoreReadCatalog.recording`. Session aliases survive every store reconstruction, merge receipt
and mirror copy; an ambiguous anchor or lost alias without known final hashes refuses. Recovery
preserves the original open-capture provenance even after a merged session's calendar expands.
`CatalogRecordingSource` admits only these members, verifies final hashes at each changed file
boundary and rechecks that boundary after snapshot admission. `CaptureWriter` creates one writer
incarnation per file and supplies it to the live index and immutable store-update snapshots.
`provenance.writer_identity` survives close, recovery, relocation, peer copies and mirrors;
a short reconnect keeps it, while rollover and new writers create another. The source checks
open metadata against its live index; the engine owns continuity checks across prefixes, including
finalization after replacement between polls. Legacy missing finalization proof requires explicit
rebootstrap. Initially finalized legacy files still bootstrap from verified hashes.

`FollowEndpoint` owns 32 temporary leases, 60-second idle expiry and a shared 64 MiB encoded budget.
Its transport gives next calls 16 dedicated workers, independent of creation and DELETE. Waits
last at most 25 seconds; one next per lease may run. An acknowledged frontier releases mappings
and retains only identity/type/count proof before waiting, so idle follows do not reserve files
against store moves. Follower workers compare small immutable `LiveLog` tokens containing only
writer identity and revision; a token cannot retain the released index. Publication increments
the revision in the writer's existing state replacement. The recording loop has no follower
callbacks, waits, decoding or network work. Store publication
adds one atomic token increment after a durable write. Waiters inspect tokens at most 25 ms apart
and recheck external file/catalog changes within five seconds; an unchanged check never opens a
native prefix. Admission/grants are checked again even for an encoded retry. Active requests keep
their lease alive; DELETE and shutdown cancel waits, and disconnected idle sockets are reclaimed
after the bounded wait and idle expiry. Final member hashes travel with frontier metadata.
The lease count does not reserve encoded bytes: a page build reserves twice its page limit and
retained retries share the same budget. Transient exhaustion returns 413 with `retryable: true`
and permits retrying the same cursor after other pages release their bytes; structural request,
metadata, frame and index limits return `retryable: false`. Request-body JSON parsing is classified at
the shared read endpoint before source work; malformed bodies return 400 with a short diagnostic.
Every page carries `ended`, true only on the page completing the finalized frontier. That page
remains retryable until acknowledged. Acknowledgement releases prefixes, proofs and encoded data;
the bounded lease retains only the terminal cursor outcome and admission context until DELETE
or expiry. Repeating that cursor returns 410 `FOLLOW_ENDED`, without opening a prefix or waiting.
The client stops polling and DELETEs after consuming the terminal page.
The extension's window-owned acquisition broker is the next tested boundary. A window supplies
one broker per server/credential context. Same-turn subscriptions with an identical anchored
SourceRef and bootstrap windows can share a pending open: entry/field requirements are unioned
and identical selections deduplicated. If the union exceeds 256 selections or the 64 KiB request
cap, a subscription that fits alone gets a separate pending job, subject to admission limits.
Different bootstrap windows always remain separate; the broker never invents a time projection.

Opening freezes the query set before callbacks, credential lookup or a network await. A later
join, explicit refresh or reveal after releasing a subscription gets its own lease, leaving
peers' cursor, samples and data generation uninterrupted. Removing a consumer retains the
opened query superset, including through server expiry/replacement. Removing the last aborts
next and DELETEs the lease. A refresh reserves and attaches its replacement before detaching
the old subscription; local admission failure leaves the requesting view and its peers unchanged.
This requires spare job/byte capacity even when the old subscription has no peers. Reset
numbers belong to each subscription and increase across refreshes; stale callbacks from the
old binding cannot deliver or acknowledge work for its replacement. No history cache or
bootstrap handoff is involved. Panels will request 1 MiB pages; the protocol maximum and
adapter default remain 4 MiB. The real-server fixture exercises both page sizes; live panels
are not wired yet.

`FollowClient` borrows the existing origin-scoped credential provider. It never follows redirects
or accepts credentials embedded in URLs. The adapter and subscription admission share the exact
UTF-8 open-request encoder, capped at the server's 64 KiB including repeated query windows.
Admission sizes the individual request and any pending union before reserving or changing a
job, so a refused join leaves its peers alone. Encoding stops at the first overflowing query;
next-request bodies have the same cap, and oversized requests never consult credentials or
reach the network. A permanent remote refusal after refresh admission stops the requesting view
without resetting its peers; typed transient refusals back off on that job.
Responses are byte-capped before JSON parsing; tagged
integers, floating-point bits, timestamps, ordinals and diagnosed gaps remain in the server's native
representation. `followProtocol` validates identity, selection, work bounds and metadata presence
before any delivery, then freezes the shared frame. A page carries each consumer's captured view
UUID, generation, configuration revision and query identity. A stale consumer receives no callback.
No client decoder, timestamp deduplication, statistical reduction or robot connection is added.

There is at most one request and one borrowed page per job. All current consumers must finish
before the next cursor acknowledges the page. A failed consumer, or one exceeding five seconds,
is detached with an explicit error; another consumer can continue. Consumers must release borrowed
frames when their callback resolves and clear their own buffers on reset. Retries use the submitted
cursor, with exponential jittered backoff (250 ms base, 10 s ceiling). A 204 is only a wait timeout;
409 and 413 retry only when `retryable` is true. A still-moving catalog produces 409
`FILE_CHANGED` with that flag, including on create; permanent changed-history failures keep
`HISTORY_UNAVAILABLE` and `retryable: false`. Every native-read 409 carries the flag. Older
servers' missing 409 flags mean false; malformed flags are protocol errors. Closed/expired leases
explicitly reset the same anchored source and windows. History and access refusals stop with their code. A terminal page is consumed,
then DELETEd without another poll. Cleanup has a two-second deadline; lost DELETEs fall back to the
server's 60-second idle expiry. Cancellation also interrupts a stalled credential lookup.

Admission limits each broker to 32 jobs (including separate leases for the same source/windows)
and 256 consumers, with at most 256 unioned entry/field selections per job. Its 64 MiB encoded
reservation covers two page limits per active job: eight jobs at the adapter's 4 MiB default,
or 32 at the approved 1 MiB panel size. A retiring job retains its slot and reservation until
DELETE finishes or reaches the adapter's two-second deadline. Ended/error jobs release bytes
but keep a job slot until their subscriptions close. Refresh replaces a consumer within the
256-consumer cap; it still needs job/byte headroom. The server's 16 long-poll workers remain
a separate limit; busy replies back off without reopening or resetting peers.
There is no encoded history cache or per-consumer frame copy. Parsed frames limit traversal to
the negotiated page byte budget in nodes, counting primitives, and 96 levels of nesting. Every JSON
node requires at least one encoded byte, so the traversal bound admits every byte-bounded page
within that depth, including full pages of tagged arrays. These are acquisition bounds, **not** an aggregate JavaScript heap or display
budget. Configured remote visualization routing, live editor/clock states, the decoded-buffer
setting and accounting for recorded buffers/webview copies, and measured display/resource workloads
remain Stage 3 work. The broker is exercised through the real server fixture and is not yet wired
into the recorded editor.


## Known Limits

- A WPILOG file over 1 TiB is not loaded; single records still have the upstream decoder's int-sized buffer bound.
- On Windows, a mapped file cannot be moved or replaced. Managed imports reserve file access and release mappings after the last reader before moving a source; an external copy cannot bypass an active reader.
- Compressed logs are not read ([IDEAS.md](IDEAS.md), "Compressed Log Files").
- Protobuf entries are not decoded. A record of 100 bytes or less is returned as hex, and a longer one only as its size.
- The memory budgets are estimates, and the heap-pressure check is the backstop (see [Memory Management](#memory-management)).
- The MCP instructions do not reach every client, which is why `get_server_guide` repeats the guidance.

## Review hardening for 0.10.0-dev1

Raw record offsets retain file order. Decoded entry values have a stable chronological view,
so binary-search scopes include late records without changing equal-timestamp ordering. An
incomplete final record does not invalidate its preceding complete sparse sample. Struct decode
plans have a maximum dependency depth of 64, including reused subplans; deeper schemas give an
explained decode refusal instead of overflowing the thread stack.

The lag-one autocorrelation estimate is the sum of within-window centered neighbor products
divided by the centered sum of squares of all samples. Excluded gaps contribute no pair. Unlike
rescaling by the number of surviving pairs, this estimator stays between -1 and 1 when windows
have unequal lengths. Derivatives weight adjacent slopes by the opposite interval length, so
uneven timestamps place the derivative at the middle sample. Voltage crossings end at each
scope boundary; they never span a disabled interval excluded from the request.

The autocorrelation estimator is biased toward zero for short windows, so the effective sample
size can be slightly inflated; it is an approximation, not an exact independent-sample count.

MCP HTTP bodies are limited to 4 MiB and ten seconds. A separate pool admits at most
`min(32, max(4, processors * 2))` MCP requests with no waiting queue; overload uses the bounded
503/disconnect refusal path described above.
HTTP dispatch uses direct handoff to four-to-32 workers, without a waiting queue. Slow MCP
bodies, tool calls and data/store streams do not occupy those workers; streaming admission has
its own pools and progress deadlines as described above.

Store JSON publication uses the same 4 MiB limit as reading. An oversized update is refused
before replacing the last readable manifest. Move aliases follow a bounded chain with cycle
detection, so successive store moves keep earlier paths usable. Sync-cache counts are checked
against the remaining serialized bytes before allocation, in addition to the version and CRC.
The cache format is 12 to invalidate the former drift explanation's incorrect ms/hour unit.
