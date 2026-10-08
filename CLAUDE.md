# Claude Code Project Instructions

This document provides context and coding guidelines for Claude Code when working on wpilog-mcp.

## Project Overview

**wpilog-mcp** is a Java 17+ MCP server that reads WPILib robot telemetry logs (binary `.wpilog` format) and REV motor controller logs (`.revlog`), and provides analysis tools for FRC (FIRST Robotics Competition) diagnostics over JSON-RPC 2.0 on stdio and HTTP transports. The repository also holds the VS Code extension (`vscode-extension/`) that bundles the server, and the one-line installers for the standalone install.

Before working on this codebase, scan the package structure and read the key files relevant to your task. Do not rely on class names or counts mentioned in any documentation — verify against the actual code. `doc/ARCHITECTURE.md` explains the design and the reasons for it; `doc/DEVELOPMENT.md` says how to build, test, change a tool, and release. Read the architecture guide's design principles before changing a tool: most of them were learned from tools that returned plausible wrong answers, and the guide keeps the examples.

## Architecture at a Glance

The server has a layered architecture (`doc/ARCHITECTURE.md` has the code map):

1. **Transport layer** — MCP JSON-RPC 2.0 over stdio (one client, one message at a time) or HTTP (several clients, sessions, parallel requests). Message routing is transport-independent.
2. **Tool layer** — Dozens of tools in modules grouped by subject, registered at startup. Every tool runs through one base that turns bad arguments, unreadable files, exceptions, and heap exhaustion into error results and enforces the result contract on every result. Tools that read a log share a second base that adds the `path` parameter, loads the log, and records which entries the tool read. Shared services: the signal resolver, time scopes and windows, field paths into structs and arrays, data quality, the response builder, and the reasoning guidance. There is no "active log": each call stands alone.
3. **Log layer** — Logs are read lazily: one scan indexes a file, values are decoded on demand and cached, and loaded logs are unloaded when memory runs short. A loaded log follows its file: a file that changed on disk is loaded again, a result read across the change is discarded with an explained error, and each session is told once. Structs decode with the schemas the log records. The scan is built for logs that were not closed cleanly.
4. **RevLog layer** — REV log parsing with DBC-based CAN signal decoding, and synchronization to the wpilog's clock by cross-correlation, where names only nominate signal pairs and the data decides. Results are cached on disk (see Disk Cache).
5. **External integrations** — The Blue Alliance API for match data, bundled game data, named server configurations with environment variable interpolation, and a background server for the standalone install.
6. **VS Code extension** — Installs and updates the standalone server, registers its HTTP endpoint with VS Code’s agents and its bridge with Claude Code at user scope, and leases each window’s directories and secret-storage TBA key to its MCP session. The home YAML remains the user’s permanent configuration.
7. **Capture service** — One ordered NT4 client listener feeds the session writer, its live index and the optional read-only gateway. The writer applies exclusion and thinning policy, accounts for each topic's cost, and publishes a fixed prefix for readers. Session continuity follows the robot's clock; `doc/ARCHITECTURE.md` explains the ownership and `doc/STANDALONE.md` the configuration.
8. **Store** — Manifests are the record of robots, sessions, files and provenance. The inbox, import, pull, peer sync and mirror share placement and transfer rules: content proves continuity and matching, and names only nominate. The store owns its writes; only mirror synchronization evicts mirror files. See `doc/ARCHITECTURE.md` and `doc/PIT_SERVER_PLAN.md`.
9. **Live tools and metrics** — Live tools read published session facts and the latest-value table without joining capture or store work. Ordinary log tools read a consistent prefix of the live index. Metrics are a sampled view; the capture is the record. `doc/TOOLS.md` owns the result contract and `doc/STANDALONE.md` the metrics setup.
10. **Pit HTTP services** — The HTTP transport also carries registration leases, the catalog-backed store door, uploads and the credential bridge. Local controls and network access have separate admission rules; a configured store is not an arbitrary directory share. `doc/STANDALONE.md` describes the routes and exposure, and `doc/ARCHITECTURE.md` their ownership.

## Java 17 Best Practices

All code should follow JDK 17 idioms:

- **Streams**: Prefer streams over imperative loops unless performance is a concern (e.g., hot paths, large datasets with measurable overhead).
- **`var`**: Use `var` when it aids readability by reducing redundancy (e.g., `var entries = map.entrySet()`). Never use `var` for primitive types (`int`, `long`, `double`, etc.).
- **Records**: Use records for immutable data carriers instead of classes with boilerplate getters/equals/hashCode.
- **Enums**: Prefer enums over string or integer constants for fixed sets of values.
- **Switch expressions**: Use switch expressions (`->` syntax) over traditional switch statements. Use pattern matching where applicable.
- **Sealed classes**: Use sealed classes/interfaces when a type hierarchy has a known, fixed set of subtypes.
- **`Optional`**: Use `Optional` for return types that may have no value. Never use `Optional` as a field or parameter type.
- **Text blocks**: Use text blocks (`"""`) for multi-line string literals.

## Source Conventions

- Two-space indentation, in Java and TypeScript alike.
- Every Java file starts with the MIT license header in `gradle/license-header.txt` (generated files are exempt). `./gradlew license` checks the headers, and so do `./gradlew build` and CI.
- Javadoc explains why, not only what: the rule a class follows, the convention it reads, and the failure that motivated it, as `doc/ARCHITECTURE.md` does. A comment that dates a change names a release that exists or is being prepared, never a later one; a test enforces this against the version in `build.gradle`.
- Result fields are `snake_case`, and a number's name carries its unit where the unit is not obvious (`loop_time_ms`, `auto_duration_sec`, `mean_abs_speed_mps`).
- Commit messages: a subject that states the change in the project's terms, often prefixed by the component or tool (`Extension:`, `Release workflow:`, `power_analysis:`), and a body that says why, what was wrong before, and how the change was verified.

## FRC Domain Knowledge

Robot controllers, logging libraries, and their defaults change from season to season, so the log decides wherever it can, and anything the code has to assume is stated in the result as an assumption:

- **Brownout threshold**: the controller's own threshold when the log records it; otherwise a documented default, stated as an assumption, since the log may not say which controller wrote it. Brownouts come from the controller's logged flag, reported apart from voltage threshold crossings, each with its basis.
- **Match phases**: from the Driver Station state the log records, as segments of constant state. Nothing assumes a match: any number of enabled periods, autonomous never logged, no FMS, practice mode, or no Driver Station data at all.
- **Loop timing**: from the entry passed, else a published convention; other names are only candidates. The unit comes from the name, else from the data, with the basis reported. The slow first cycle after boot is not an overrun.
- **Swerve modules**: an array of module states is modules by index; positions such as front-left are only a stated assumption. Measured and setpoint states come from a published naming, each pair from one table.
- **CAN bus**: an error is classified by the robot's state when it happened (a disabled-state timeout is normal, an enabled-state error is not, and before any Driver Station data the state is unknown); counters are read per bus from the status the log records.
- **Logging conventions**: many libraries log a value only when it changes, so a long interval is usually a hold, not missing data.

## Mathematical Rigor

This codebase performs numerical analysis on telemetry data. Maintain high standards for floating-point correctness:

- **Statistics**: Use Bessel's correction for sample standard deviation (n-1 denominator). Handle zero variance, single data points, all-NaN arrays. Report the count of samples that were not finite.
- **Percentiles**: Correct interpolation at boundary conditions (0th, 100th percentile).
- **Derivatives**: Handle non-uniform timestamp spacing. Use appropriate smoothing windows. Compute rates, differences, and peaks within each time window, never across the gap between two.
- **Correlation**: Include sample sizes and p-values. The p-value accounts for autocorrelation through an effective sample size, so a smooth, densely sampled signal does not look overwhelmingly significant. Always caveat that correlation does not imply causation.
- **Angles**: an entry declared as an angle is unwrapped across ±180° and summarized with circular statistics.
- **Edge cases**: Always handle empty datasets, single elements, NaN/Infinity propagation, division by zero, duplicate timestamps. A value that cannot be computed is `null`, never zero and never NaN.

## LLM Epistemological Guardrails

This codebase implements strategies to prevent LLM overconfidence when interpreting telemetry data. `doc/ARCHITECTURE.md` gives each principle with the failure that taught it. When adding or modifying tools, follow these patterns:

### Measurements, Not Verdicts
Return raw statistics (mean, std, percentiles, sample sizes, p-values), timestamps, and event lists rather than pre-computed interpretations. There is no "diagnose my robot" tool. The few summaries for a pit crew in a hurry (a battery health score, a brownout risk, a CAN health level) report the facts they summarize and the rule that decided them, beside the summary (`..._basis` fields).

### The Server Does Not Guess
A tool uses an entry for a role (battery voltage, robot pose, swerve states, loop time, the auto chooser, ...) only when the caller named it, it follows a convention a logging or vendor library publishes, or it is the only entry of the right type. Anything else that looks right is listed as a candidate and not used. A word in a name is not evidence, and neither is the shape of the data. Every tool asks the shared signal resolver, so no two tools disagree about which entry is the battery voltage, and `resolve_signals` shows the agent the same choices. An entry named explicitly that is missing or of the wrong type is an error, not a reason to fall back on something else.

### The Result Contract
Every result has a `status`: `ok`, `partial` (what was skipped is in `skipped`), `not_applicable`, `no_match` (with `looked_for` and a `hint`), or `error`. A tool that finds nothing to analyze never returns an empty success. Successful results that read a log name their `inputs`; a list cut by a limit reports `limits` with the true total; a result from a log not read to its end says so. The contract is enforced in the tool base for every result; see `doc/TOOLS.md` ("Response Fields").

### Data Quality and Directives
Results that rest on statistics carry `data_quality`, scored on the samples in the requested scope, with the sampling classified first (periodic, change-only, event) and a reason for every penalty (`data_quality.reasons`). Analysis directives turn it into a confidence level (high/medium/low/insufficient), guidance in epistemic language ("suggests", "may indicate", "consistent with"), follow-up suggestions, and a single-match warning. Tools that report only logged events (the DS timeline, match phases) carry no quality score: a logged event is a fact, and quality bounds statistics, not observations.

### Confidence Calibration
- Don't claim "high confidence" on few finite samples or when long intervals cover much of the time span; the thresholds live in the data-quality code
- Long intervals in a change-only series are holds, not gaps; irregular timing counts against periodic signals only
- Edge cases (single data points, high jitter, many NaNs) require reduced confidence
- A warning on every result teaches a model to ignore warnings: the ordinary truncated last record is a note, not a warning
- Always warn that single-match analysis may not generalize

### Tool Description Guidance
Embed interpretation guidance in tool descriptions ("Trojan horse" pattern). Name every output the tool returns and say what the numbers do and don't show; a test fails when a description names an output no result contains. Include sample size considerations, correlation vs causation caveats, single-match limitations, and appropriate uncertainty language.

### Server-Level Guidance
General reasoning guidance (scientific method, confabulation traps) lives in one class and is delivered two ways: as the MCP `instructions` field on `initialize` (clients such as Claude Code put it in the system prompt; it must fit a size limit a test checks, most important rules first, ASCII only) and as `analysis_principles` in the `get_server_guide` result (reaches every client). Keep rules concrete and checkable, scope heavy rules to causal questions so they don't fire on lookups, and never phrase `confidence_level` as a blanket ceiling — it is driven by sample counts, holds, and timing (with `data_quality.reasons`), and reads "low" on sparse change-only signals whose events are perfectly clear, so it bounds statistics, not directly observed events. One rule sends the agent to the robot's source code for what a log cannot say: which mechanism an entry belongs to, its units, and whether it is measured or commanded. A test verifies every tool name the guidance mentions exists and that the text fits its size limit.

## Tool Architecture

When adding or changing a tool, follow the checklist in `doc/DEVELOPMENT.md` ("Changing or Adding a Tool"); it is the authority. In short:

- Tools that read a log extend the log-reading base, which adds the `path` parameter and loads the log. Override the method that receives the loaded log. Tools that don't read a log extend the general base, which the log-reading base itself extends.
- Find inputs through the shared signal resolver, take time ranges through the shared scope and window handling, read numbers inside structs and arrays through field paths, build results with the shared response builder, and attach data quality where the result rests on statistics.
- Register the tool in the module with related tools, and add it to the catalog that `get_server_guide` and `suggest_tools` use.
- The schema must declare every parameter the code reads, and the description must name every output: tests compare both with the code and with real results.

## Testing Standards

This codebase must be rock solid. `./gradlew test` needs nothing outside the repository, and CI runs it on Linux and Windows. Three rules apply to every change:

- A fix comes with a regression test that fails without the fix. Run the test against the old code and watch it fail before you trust it.
- A test of a number checks the number, against an answer worked out independently of the server. That a call succeeded proves nothing about what it returned.
- When you change a check, plant the bug it is meant to catch and see the check fail.

What the suite holds, and what a change must keep passing (`doc/DEVELOPMENT.md` describes each suite):

- **Edge cases**: Empty inputs, single elements, boundary values, null/missing data, NaN/Infinity, zero values, negative values, duplicates, malformed inputs, bad arguments, missing DriverStation data.
- **Mathematical edge cases**: Zero variance, single data points, all-NaN arrays, percentiles at 0/100, regression with collinear data, correlation with constant signals.
- **Error paths**: Exception-throwing code paths must be tested. An "Internal error" result is a bug.
- **Fixtures**: small logs, one per logging convention, written at test time by a pure-Java WPILOG writer with values that are simple functions of time, so the right answer to any statistic is known exactly. None is committed.
- **Conformance sweep**: every tool is called from its schema on every fixture; results must keep the result contract and be deterministic under reordered entries. Its known-failures list is a ratchet that should stay empty.
- **Differential check**: a second WPILOG reader, written from the format specification and sharing no code with the server, checks what the server read, and domain answers are recomputed from the raw records of the entries each tool says it used.
- **Claim checks**: the schemas, the documentation, the tool catalog, the descriptions, the reasoning guidance, the versions, and the build and CI wiring are checked against the code.
- **Stress tests and real-log suites**: opt-in, on real logs outside the repository. When adding a tool, add it to the stress tests and give it argument variants if its real analysis needs parameters its schema leaves optional.
- **Extension tests**: `npm test` in `vscode-extension` runs the extension's pure logic with Node's test runner; CI runs it on Linux and Windows.
- **Windows**: CI runs everything on Windows too. Build an expected path with the same path API the code uses, never from a hand-written string; compare paths as the file system does; and don't assume a checked-out file has LF line endings.
- **Logs**: no robot log belongs in the repository, and neither does a value taken from another team's log, even one number in a test, unless the log's license allows it and its notice comes with it.
- **The disk cache**: every test task uses its own cache under `build/`, emptied first, never the user's; `-PtestCacheDir` names a folder to keep between runs instead.

## Version Management

There is a single source of truth for version numbers, auto-generated by the build. Update it in the build file when releasing new versions. The VS Code extension carries the same version: `./gradlew syncExtensionVersion` writes it into the extension's package files, and a test fails when they disagree. `doc/TOOL_RESPONSES.md` carries the version too and is regenerated for a release. A development build has a suffix (such as `-dev2`), is published as a pre-release, and skips the changelog and response-reference steps. Releases are immutable: a published release's files and tag cannot change, and a deleted release's tag name cannot be reused, so a release that went wrong gets a new version, never a moved tag. `doc/DEVELOPMENT.md` ("Releasing") has the steps.

## Disk Cache

REV log synchronizations are cached on disk, keyed by the two files and the CAN database, not by the code. Raise the cache's format version with any change to REV parsing, decoding, or synchronization: an entry of another version is ignored when read and deleted by a sweep at startup, so the fix reaches every user's existing cache. The server's version is not part of the key, since a release changes the server for many reasons. A result read from the cache must give the same answers as a fresh one; the in-process stress test compares them. The cache is input the server did not necessarily write: a corrupt or crafted file is rejected (every file carries a checksum and a format version), and reading one must never crash the server or exhaust the heap.

## Security

- Path traversal prevention with symlink resolution is enforced for all file access. When handling file paths, always validate through the security validator; a path must resolve to a file inside the configured log directories. CSV exports are restricted to a configured export directory.
- The HTTP transport listens on `127.0.0.1` by default, checks `Origin` against DNS rebinding, and has no authentication; binding it elsewhere exposes the logs to anyone who can reach the port.
- Directory/key/credential registration, the credential bridge, peer-sync jobs and mirror controls are loopback-only, even when the read endpoints serve the network. The upload route is the deliberate network write surface: validate its destination through the store, and put proxy authentication on that path first. `doc/STANDALONE.md` owns the route policy.
- The optional NT4 gateway follows the HTTP bind on a separate port with no authentication; keep it on the private network. Client writes never reach the robot, and bounded send queues disconnect slow subscribers without blocking capture.
- The Blue Alliance key is never logged and never returned by a tool. Nothing this project writes or launches puts it on a command line (process lists are visible to other users) or into a project file: the extension keeps it in VS Code’s secret storage and registers it in memory with the shared server for the life of its session. The standalone server still accepts a `-tba-key` flag, and `doc/STANDALONE.md` says why the configuration file or the environment variable is better.
- Dependencies: Dependabot covers the extension's npm packages and, through the dependency-submission job in CI, the server's Gradle dependencies, build plugins included. An alert on a build plugin or a packaging tool is about the build, not the server JAR or the `.vsix`; say which in the changelog.

## Concurrency

This server handles concurrent access from multiple MCP clients (especially in HTTP mode, where requests run in parallel) and uses background threads, for REV log synchronization among other things. When writing or modifying code:

- Tools hold no state. One instance of each tool serves every thread, and everything a call builds is created for that call.
- Assume any public method on shared state (caches, registries, managers) may be called concurrently.
- Prefer Caffeine caches, `ConcurrentHashMap`, volatile fields, and atomics over manual locking, and keep the locks few.
- When manual locking is necessary, hold locks for the shortest time possible and never perform I/O or blocking operations while holding a lock.
- A single-thread loop serializes like a lock. Blocking work on it has a stated budget and outcome, and stays only where it supplies ordering or backpressure. Never charge the loop's own delay to a peer; detect loop stalls from outside the loop. See the Windows keepalive failure in `doc/ARCHITECTURE.md`.
- Serialize store mutations on the store queue and under its cross-process lock. Recording and snapshot readers must not wait behind imports: coalesce asynchronous manifest updates, and keep shutdown waits bounded. See `doc/ARCHITECTURE.md` for the ownership boundaries.
- The live-value wait lock only orders registration, removal and shutdown admission. Claim a waiter under that lock and complete its future outside it; completion can run another caller's code. Never join the NT4 loop or store queue while holding it.
- Watch for TOCTOU bugs: check-then-act sequences on shared state must be atomic (look again under the lock, compute through the cache's per-key computation, replace a placeholder only if it is still there, claim a file with an atomic create).
- Background executors use daemon threads so they don't prevent JVM shutdown, and shutdown lets calls in progress finish before their logs are closed.

## VS Code Extension

- TypeScript against the VS Code API, with no runtime npm dependencies; the development dependencies exist to compile and package it and are not in the `.vsix`.
- Logic that needs no VS Code API lives in its own modules as pure functions, with tests on Node's test runner; `extension.ts` holds the VS Code glue.
- Settings declare an `order`, so the Settings editor lists them sensibly; tests pin the order, the declarations, and that every command a description links to exists.
- The TBA key setting is a write-only field: a key entered there is moved into secret storage and cleared. Nothing writes the key into `.mcp.json` or a settings file.
- A change to how the extension behaves in VS Code is checked by driving a real VS Code (the oldest supported version and the current one) when the tests cannot reach it.

## Documentation

When modifying tools or features, keep all documentation in sync:
- Tool descriptions in Java code (shown to LLM agents via MCP; tested against real output)
- `README.md` for the overview and the choice between the extension and the standalone install
- `doc/STANDALONE.md` for standalone installation, configuration (`servers.yaml`, CLI flags, environment variables), and client setup
- `vscode-extension/README.md` for the VS Code extension's settings and behavior
- `doc/TOOLS.md` for human reference on tool parameters and behavior (its parameter lists are tested against the schemas)
- `doc/ARCHITECTURE.md` for the design: goals, principles, log loading, memory, caching, concurrency
- `doc/DEVELOPMENT.md` for building, every test suite, the tool checklist, and releasing
- `doc/TOOL_RESPONSES.md` is generated from a scenarios file under `src/test/resources/`; every new tool needs a call there (see `doc/DEVELOPMENT.md`)
- `CHANGELOG.md` for release notes, in Keep a Changelog form: new work goes under `[Unreleased]` in `### Added`, `### Changed` (or `### Changed (breaking)`), `### Fixed`, `### Security`, `### Testing`, or `### Documentation`, one entry per change, saying what was wrong before as well as what is new
- `doc/IDEAS.md` for planned/proposed work (update or remove items as they are completed)

Keep this file to rules and pointers: a detail that changes with development, a new release, or a new season belongs in the document that owns it.
