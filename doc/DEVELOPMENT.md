# Development

How to build, test, and change wpilog-mcp. [ARCHITECTURE.md](ARCHITECTURE.md) explains how the code is organized and why it is designed the way it is; read its [design principles](ARCHITECTURE.md#design-principles) before changing a tool.

## Requirements

- JDK 17 or newer; the WPILib JDK is recommended (see [STANDALONE.md](STANDALONE.md#requirements) for where WPILib puts it). `./gradlew` uses `JAVA_HOME`, or `java` on your `PATH`.
- [Node.js](https://nodejs.org/) 20, the version CI uses, with `npm` on your `PATH`: needed only to build or test the VS Code extension.

## Building

```bash
./gradlew build          # Full build with tests
./gradlew shadowJar      # Build the self-contained JAR only
./gradlew install        # Build and install to ~/.wpilog-mcp/
```

The JAR is `build/libs/wpilog-mcp-{version}-all.jar`. `install` runs the fat JAR's `install --force` verb to set up the [standalone install](STANDALONE.md#install) from your build. It preserves an existing YAML or legacy JSON configuration and makes this development build current. Use `-PinstallDir=/path/to/scratch` for an isolated install.

### The VS Code extension

```bash
./gradlew bundleExtension    # Copy the server JAR into the extension (for local development)
./gradlew buildExtension     # Build, compile, and package the .vsix
./gradlew installExtension   # Build, package, and install into VS Code
```

`installExtension` delegates to the JAR’s `install --force --with-extension --vsix <file>` verb, installing the standalone layout and the matching extension. VS Code lookup lives in that verb: the current year’s WPILib VS Code, then `code` on PATH. `-PinstallDir=/path/to/scratch` overrides the server’s install directory. Close VS Code before running the task, and restart VS Code when the task is done. The `.vsix` is written to `vscode-extension/wpilog-analyzer-{version}.vsix`.

The extension's version is the project version in `build.gradle`. Every extension task runs `./gradlew syncExtensionVersion`, which writes it into `vscode-extension/package.json` and `package-lock.json`. A development version such as `0.9.0-dev` installs over the previous release, and the release that follows it installs over the development version.

## Testing

```bash
./gradlew test
```

This runs every test that needs nothing outside the repository. Three rules apply to every change:

- A fix comes with a regression test that fails without the fix. Run the test against the old code and watch it fail before you trust it.
- A test of a number checks the number, against an answer worked out independently of the server. That a call succeeded proves nothing about what it returned.
- When you change a check, plant the bug it is meant to catch and see the check fail.

The tests run on Windows in CI too. Build an expected path with the same path API the code uses, never from a hand-written string; compare paths as the file system does; and don't assume a checked-out file has LF line endings.

### What `./gradlew test` covers

Tests are in `src/test/java`, in the same packages as the code they test, plus a few test-only packages: `fixtures`, `conformance`, `golden`, `docs`, and `integration`.

**Fixture logs.** Small logs, one per logging convention (AdvantageKit match and practice logs, plain WPILib, swerve module states as an array and per module, vision templates, entries that only look like vision data, a team's own structs, a CANivore, alerts, a truncated log, and more). They are written at test time by a small WPILOG writer in pure Java, and their values are simple functions of time, so the right answer to any statistic is known exactly. None is committed.

**Store fixtures.** `store.LogStoreTest` writes synthetic WPILOG and REV files in temporary directories and drives the Java import queue and the real listing tool. It checks verified copies and moves, original provenance, duplicate hashes, REV correlation and ambiguity, serial promotion without merging, clock bases and overlap, unmanaged files, format refusal, queued callers, path containment, and the lifetime of readers and moved-path notices. No robot logs or external services are needed. `StoreImportEndpointTest` drives the HTTP jobs and refusals on the real transport; `StoreInboxTest` checks stability, receipts, and listing states; `MainImportTest` drives child JVMs and a real daemon, including a cross-process file-lock refusal.

The store checks also distinguish a new named robot with a logged serial from a later serial promotion, keep non-overlapping sessions separate, count catalog reads per batch, and observe which REV candidates reach correlation. `log.LogMappingLifetimeTest` reads fixtures through tools and the manager before moving them, holds calls and background synchronization across eviction, and checks that the final holder releases the mapping so a real rename succeeds on Windows too.

**Robot identity.** `CaptureIdentityTest`, `RobotIdentityReaderTest`, `RobotCandidatesTest`, and
`LogStoreTest` check the HAL source convention, context at start/resume, serial promotion with
mapped readers, retained old paths, key history, disagreements, and late logged identity outside a
store. `/SystemStats/SerialNumber` is resolved with the same metadata roles as imports.
`robot_candidates` checks use synthetic exact fingerprints, ambiguity, contradictory hints, and
CAN inventories. No identity or value comes from a robot log.

**NT4 protocol and gateway.** `./gradlew test --tests '*.nt4.*'` checks the pure codec against
hand-encoded JSON/MessagePack frames from the NT4 and MessagePack specifications, both directions
of the type table, malformed input, and the time-sync arithmetic. The pure gateway tests drive
explicit times for overlapping subscriptions, batching, cached values, exact/prefix matching,
`topicsonly`, removals, and read-only acknowledgements. Real JDK WebSocket clients connect to the
loopback gateway on numeric `127.0.0.1` and ephemeral ports. Every generated fixture is replayed in
timestamp order; every announcement, unannouncement, timestamp, and raw payload is compared with
the source records, including struct schema topics and intentionally malformed struct payloads.
The replay helper also accepts a pace and an injected pacer. The client tests inject the event loop
and clock to observe infinite 1–10 second backoff, reconnection, and time-sync/keepalive timers
without sleeping. A separate scripted peer checks fragmentation, the 4.0 fallback, unknown IDs,
malformed messages, and listener order. A planted server clock is checked within the measured RTT.
These tests need no robot, native NT library, external service, or committed log. Actual ntcore and
dashboard interoperability and native Windows execution still require their respective environments.

**Tool tests.** Each tool's behavior and its edge cases (empty and single-sample entries, NaN and infinite values, duplicate timestamps, missing Driver Station data, and bad arguments), on the fixtures and on small logs that the tests build in memory.

**Conformance sweep.** Every tool is called with arguments built from its schema, on every fixture if it reads a log. Each result must follow the [result contract](TOOLS.md#result-contract-success-status-and-related-fields): no internal error, no NaN, no silent success (a success whose content is only zeros, `false`, and empty lists counts as silent), `inputs` on every successful result that read a log, true totals for shortened lists, a note on every result computed from a log that was not read to its end, and every output the tool's description names.

The output must also be deterministic: each call on a log is repeated with the log's entries reversed and twice shuffled, and must give the same result (the REV log tools are exempt, because they depend on the synchronization done when the log was loaded). Tools whose needed parameters are optional in their schema get argument variants that reach their real analysis. `src/test/resources/conformance/known-failures.txt` is a ratchet: a violation not listed there fails the build, and so does a listed one that no longer occurs. It should stay empty. The report of every call is `build/reports/conformance/report.txt`.

**Differential check.** The conformance sweep shows that the tools keep their contract, not that a number is right. This check reads each fixture with a second WPILOG reader, written from the format specification alone and sharing no code with the server or with WPILib's reader, and compares what the two read: the time range, every entry's type and sample count, the statistics of the most-sampled numeric entries and of entries holding NaN, and the enabled windows. It also recomputes domain answers from the raw records of the entries each tool says it used, by the rules [TOOLS.md](TOOLS.md) gives: the loop-time statistics of `analyze_loop_timing`, the roboRIO brownouts in `power_analysis` and `get_ds_timeline`, the per-bus figures of `analyze_can_bus`, the module speeds of `analyze_swerve`, and the mode of each enabled segment in `get_match_phases`. No expected value is stored, so the same checks run on any log. The server may set records aside only where the second reader sees damage itself.

**Claim checks.** The documentation and the tool descriptions are checked against the code. Each tool's schema is compared with the parameters its code reads, the parameters in [TOOLS.md](TOOLS.md) with the schemas, and the tool table in the README and the catalog in `get_server_guide` with the registered tools. TOOLS.md must hold every tool, under the server's category for it. TOOL_RESPONSES.md is generated from logs a contributor may not have, so it may lack a tool that was just added; it may not misplace a tool or hold one the server does not have, and the scenarios file it is generated from must have a call for every tool. The reasoning guidance sent to agents may name only tools that exist and must fit its size limit.

**Process and installer tests.** Fresh JVMs verify startup configuration and logging. `InstallCommandTest`, `CodeInstallerTest`, `InstallRefreshTest`, and `InstallerTest` use temporary installs, fake release downloads, and fake VS Code CLIs. They check containment and layout on both platforms’ path rules, generated launchers, version ordering, exact JAR copies, JSON isolated from JVM stderr, preserved settings, refresh guards and stopping a real daemon, release selection, prompts, one matching VSIX installation, and the legacy fallback. PowerShell checks run when PowerShell is available; native Windows execution remains CI’s job.

Directory lease tests drive the real transport: session expiry/deletion/replacement, cached-read revocation, independent clients, origin/team listings, key precedence and captured logs, loopback/Origin refusals, symlinks, imports, and the actual inbox poller. A packaged bridge runs in a temporary home and project to prove home-only server configuration, project/flag leases, relative paths, URL connections, repeated initialization, and cleanup at EOF. The fake TBA service sees only synthetic keys. Mutation checks cover these boundaries; no live API or user's home is used.

**Version checks.** The extension's version must equal the project version, and no comment in the source may date a change to a release later than the current one.

**Build file check.** The stress test tasks, which nothing else runs, must build the test classes first and fail the build when a test fails. The CI workflow must run the license check, which neither `test` nor `shadowJar` includes.

CI runs `./gradlew test shadowJar license` on Linux and Windows, and builds and tests the extension. `license` checks that every Java file carries the license header in `gradle/license-header.txt`; `./gradlew licenseFormat` adds a missing one. On a push to `main` it also submits the Gradle dependencies to GitHub's dependency graph: GitHub does not read `build.gradle`, and without the submission Dependabot alerts cover only the extension's npm packages, not the libraries in the server JAR.

### Tests on real logs

These are opt-in, because the logs are not in the repository. Each is selected by its test package and switched on by a property:

```bash
# The real-log suites, on a directory of logs
./gradlew test --tests '*.conformance.*' -PconformanceLogDir=/path/to/logs [-PsimLogDir=/path/to/sim-logs] [-PconformanceMaxLogs=N] [-PconformanceTools=a,b]

# Golden values from known logs
./gradlew test --tests '*.golden.*' -PgoldenLog=/path/to/akit_26-09-30_00-10-26.wpilog -PgoldenMatchLog=/path/to/akit_cmptx_e4_sample.wpilog
```

`-PconformanceMaxLogs` limits a run to the first N logs, `-PconformanceTools` to the tools named, and `-PsimLogDir` is described under the claims check below.

- Real-log conformance sweep: the fixture sweep's checks (all but the comparison of each description with its outputs) and argument variants, for every tool that reads a log, on every `.wpilog` under the directory (up to 6 levels deep), with the entries reversed for determinism. There is no ratchet, so any violation fails. The report, with the time of every call, is `build/reports/conformance/real-logs.txt`.
- Real-log differential check: the second reader against the server on every log, with the domain answers above. A file neither can read is counted, and a file only one can read is a finding. The report is `build/reports/conformance/differential.txt`.
- Claims on the live service: the server is started on its HTTP transport and called as a client would call it, and each answer is compared with a fact established independently of the tool under test. The facts are about Team 2363's sample logs, and about simulated logs whose `MANIFEST.md` was written by a separate reader; `-PsimLogDir` names the directory holding those. With other logs, or without that directory, a claim whose precondition isn't met is reported as not verifiable, never as passed. The report is `build/reports/conformance/claims.txt`.
- Golden checks: values from a practice log of Team 2363 (`-PgoldenLog`) and from a 2026 championship elimination match that Team 4065 published under the MIT license (`-PgoldenMatchLog`, its `akit_cmptx_e4_sample.wpilog`), computed separately with WPILib's Python log reader and NumPy. Each property switches on its own set.

With `-PconformanceLogDir`, the tests run with a 4 GB heap (the launcher's default) instead of the usual test heap; `-PconformanceHeap=8g` changes it.

Use your own team's logs. Logs that another team has deliberately published can be used for testing on your own computer, but keep them there: publishing a log is not permission to redistribute it, and no log belongs in this repository. Nor does a value taken from another team's log: a number copied into a test is a copy too. The exception is a log whose license allows the copy, with the license's notice kept beside the values, as the golden checks do for Team 4065's MIT-licensed match.

### Stress tests

```bash
./gradlew stressTest       # Both stress tests
./gradlew stdioStressTest  # In-process only
./gradlew httpStressTest   # Over the HTTP transport only
```

The stress tests also need real logs, but they are Gradle tasks of their own rather than properties on `test`. The in-process test loads every log in the directory, runs the tools group by group on the first one, loads and evicts logs to stress the caches, and calls tools from several threads at once. It runs the REV log tools on the first log that has a REV log, and there checks the disk cache: it synchronizes the log with the cache off, then twice with it on, the second time from the cache, and the REV log tools must give the same answers each time. The HTTP test drives the HTTP transport as several clients would: sessions, concurrent calls, batches, and protocol edge cases, with its REV log calls on that same log. Both hold the results of their sequential calls to the conformance checks. A failing test fails the build. When the log directory does not exist, every test is skipped.

They take their settings from a server named `stresstest`. It is looked for in the file given with `-Pconfigpath=/path/to/config.yaml`, or else in the first of these files that defines one: `.wpilog-mcp.yaml` or `.wpilog-mcp.json` in the project root, then `~/.wpilog-mcp/servers.yaml` or `servers.json` (the file the installers write defines one). With no such server, they use `~/riologs` and team 2363. A TBA key in those settings (or in `TBA_API_KEY`) is used, so the TBA tools then call the live API. The heap is `WPILOG_MAX_HEAP`, or `4g`. The disk cache is never the one in those settings, but the tests' own (below).

### Capture tests

`./gradlew test --tests '*.capture.*' --tests '*CaptureFidelityTest'` checks WPILOG bytes against
hand-encoded format examples, all NT4 payload families, clock continuity and reboot detection,
exclusion/thinning metadata, flushes, and minute-bounded cost accounting with injected clocks.
Every generated fixture is replayed through the loopback gateway and JDK client into a capture;
the independent reader checks every entry, payload, and timestamp, and wpiutil checks the result
too. The bounded-file pass verifies the same received frames across every rollover, with marked
schema seeds checked separately at the rollover server time, plus every file's byte bound. Store and tool checks pin each rolled file's minimum timestamp, including `inputs.session_time_range`. This replay uses NT4 4.0 with an
injected client clock so tiny forced files and tool calls do not turn byte fidelity into an aliveness
timing test; the NT4 suite independently tests 4.1 keepalives. Replay allows 60 seconds of wall
time for socket delivery and forcing the small rollover files, which exceeds the normal helper's
ten-second deadline on Windows; that deadline never advances the injected clock or changes the
fidelity assertions. Replay walks complete record boundaries because wpiutil's iterator can omit a short final
record. The client separately checks that a wrong MessagePack family is counted and dropped
without losing the next frame or disconnecting. CI runs on `pit-server` as well as `main`, including
the Windows job.

`CaptureConfigTest`, `CaptureStoreTest`, and `CaptureStartTest` cover configuration keys and their
documentation, UTC placement, open/closed manifests, hashes, resumption and name collisions,
and queued match facts preceding the close-time directory rename. A blocked store queue leaves
values and flushes running, retains one pending update, and writes a complete final manifest.
Injected clocks pin the five-second progress cadence and immediate changed facts. `CaptureShutdownTest`
proves service shutdown waits for that manifest outside the NT4 loop. `CaptureRecoveryTest`
plants open manifests with complete, incomplete-tail, unreadable and damaged-header fixtures;
checks hashes, ranges, modification-time endings, preserved facts and prior files; and proves
recovery waits on the store queue. A blocked sweep leaves the real HTTP health endpoint responsive
and starts no NT4 work until recovery completes. It verifies same-process and cross-process ownership,
reader mapping alongside a writer, alias and symlink guards, and cleanup after an output fails
to open. A child JVM blocks the store queue, checks the 30 second default and injects a zero deadline into the production wait, then exits
without draining the pending manifest; the next service start must finish it. No test sleeps to
advance a clock. `CaptureFailureTest` injects a
disk failure over loopback and checks the reason in the manifest/log, connection survival, a suppressed
same-clock reconnect, and a resumed recording on a new clock. Writer tests also plant a partial payload
write and check rollback to the completed prefix, force/create failures, and a bound unable to hold one record. The packaged
`start` command runs in an isolated home: HTTP works with the robot absent, then a loopback
fixture robot connects and its values survive daemon shutdown. No test waits for an injected clock.

`LiveCaptureConformanceTest` replays every fixture through the gateway/client/writer, then runs
the conformance suite's argument variants for every log-reading tool on the open session and on
a fresh load of its finished file. It compares complete results, excluding only execution timing
and the documented live-prefix range, and also runs the independent capture-fidelity checks.
`LiveLogTest` checks fixed prefixes during a concurrent append, input ranges (including role-based
inputs), array growth, all hot/cold value families and schemas, actual mapped-read counts (including
a plant that leaves every hot object intact), four-per-second expiry with a zero hot window, mapping
retirement with a held reader, resume after eviction, and rename after release. The store rename
tests now keep a live mapping beside the writer too. These run in the normal Linux and Windows
suite. A sparse WPILOG beyond the mapping limit is refused by import without moving or placing
its bytes; loading and the plain-directory listing give the same reason. The shop stress test with a real robot remains a manual check owned by the user.

### The disk cache in tests

Every test task, the stress tests included, uses its own disk cache folder, `build/test-disk-cache`, and empties it before the run. So a run starts with nothing cached, synchronizes every REV log itself, and never reads or writes your own cache. `-PtestCacheDir=/path/to/folder` uses that folder instead and keeps what is in it: a second run then starts from the results the first one saved. Pointed at a copy of a cache that an older version wrote, a run shows how this version treats that version's results. Use a copy, because the tests write to the folder.

### Extension tests

```bash
cd vscode-extension
npm ci
npm test
```

`npm test` compiles TypeScript and runs every `*.test.js` through `src/test/runTests.ts` on Node 20+. The suites cover:

- Directory resolution and per-path teams; lease-refresh decisions; standalone layout, install arguments/summaries and version decisions; user-scope Claude commands on both platforms; project YAML offers/content; and conservative migration of recorded legacy entries. Migration keeps tracked/custom files and waits for a successful old-daemon stop before removing private settings.
- MCP sessions and registrations against a fake transport, including reconnect, disposal, overlapping updates, refusal words, and secret redaction. The real-JAR client test proves a third session sees leased directories and key availability, then loses them when the holder closes. It uses a scratch home and no live TBA requests. That test skips only when no JAR or Java is available; CI builds the JAR first.
- Shared-server preparation and startup against fake launches with real HTTP health and temporary PID/configuration files: one start for concurrent consumers, declined installation, failed starts, missing PID records, and ordered manual restart. Settings change leases rather than rewriting configuration or restarting the daemon.
- The explorer's tree models (plain/store/mixed listings, directory origins, structs and arrays), organizing decisions and quick picks, MCP/data/import request handling, pagination, refusals and retry limits, content security policy, and manifest declarations/order/command links.
- The webview's Arrow reader against Java-produced streams and CSVs, including enums and decode problems; plot arithmetic on known arrays; a decode-warning chip through the real handler with a small test DOM; and console, field, and REV models. Arrow fixtures skip when the Java build has not produced them.

New checks must fail on planted production faults. In particular, queue checks use latches and immediate exchanges to force ordering; a passing test that never exercised the competing update proves little.

A real VS Code was not available for milestones 6–7. Check the oldest supported and current versions by hand:

1. Install from a fresh home: one offer, Not now lasting until activation, installer progress/PATH notice, and successful server startup. Verify only absolute User directories/team seed a new file and no key does.
2. Update an older launcher with its YAML preserved; keep a newer hand install. Test the on-demand install command and a development suffix.
3. Ask Copilot for `list_available_logs`: origins/teams are visible; opening and closing project windows adds and removes their directories. Settings/key/folder changes do not restart the daemon.
4. Use `get_match_info` with the secret-storage key and confirm no configuration, project file, command, or output contains it. Clear the key and check fallback behavior.
5. Register Claude Code at user scope, including the copy-command fallback. Accept/decline the project YAML offer and gitignore choice; run the bridge in a terminal with VS Code closed.
6. Open legacy projects: retire only recognized untracked/ignored entries, preserve other entries and tracked/custom files with a note, stop `vscode-default` once, and remove its private settings. Check a failed stop can retry next activation.
7. Exercise the explorer: log/entry trees and origins, plotting and statistics, console cursor, field pose, REV alignment, decode warnings, and read refusals. For organizing, check once-per-activation offers, Never/on-demand override, folder and robot picks, move/copy, progress/refusal output, inbox states, assignment, and refresh. The detailed milestone 6 checklist is in [EXPLORER_PLAN.md](EXPLORER_PLAN.md#11-milestones).

Native Windows launchers/CLI integration and the interactive installer should also be checked on Windows; Node tests of Windows path/argument rules do not replace that. Installer tests use temporary homes and fake releases/CLIs, never the user's install.

### The data endpoint's streams

`DataEndpointTest` drives `GET /data/entries` on the real transport over the fixture corpus and reads every Arrow stream back with `ArrowSpecReader`, a reader in the test sources written from the Arrow IPC specification that shares no code with the server's writer. Every stream and the CSV of the same request are left under `build/arrow-samples/`, and CI's `arrow-crosscheck` job reads those with pyarrow, the reference implementation, and compares them to the CSV (`ci/check_arrow.py`), so a misreading the writer and the test reader could share does not pass. Run it by hand with `pip install pyarrow && python ci/check_arrow.py build/arrow-samples` after the Java test. Python is not needed for `./gradlew test`.

## Changing or Adding a Tool

A tool is more than its code: agents read its description and schema, and several tests hold them to what the code does. When you add a tool or change what one takes or returns:

1. Follow the design principles. Return measurements with their sample counts, not conclusions. Find inputs through the shared signal resolver, never by a word in a name or by content alone: an entry is used when it is passed, follows a published convention, or is the only one of its type, and anything else that looks right is listed as a candidate. Say what was used, what was skipped, and what was cut short. A tool that cannot apply returns `not_applicable` or `no_match` with what it looked for, never an empty success.
2. Use the shared pieces. A tool that reads a log extends the log-reading base, which adds the `path` parameter and loads the log. Build the result with the shared response builder, take time ranges through the shared scope and window handling, and attach data quality where the result rests on statistics.
3. Write the description for an agent. Name every output, and say what the numbers do and don't show. A test fails when a description names an output that no result contains.
4. Register it and list it. Register the tool in the module with related tools, and add it to the catalog that `get_server_guide` and `suggest_tools` use.
5. Test it. Add tests for the tool's behavior and the edge cases above, on a fixture or on a log built in the test. The conformance sweep calls a new tool automatically. If the tool has a required parameter that the sweep has no argument rule for, the sweep fails and says so, and the rule has to be added. Give the tool argument variants if its real analysis needs parameters that its schema leaves optional. Add the tool to the stress tests.
6. Document it. Add a section to [TOOLS.md](TOOLS.md) (its parameter list must match the schema), an entry in the README's tool table, a call in `src/test/resources/tool-responses/scenarios.json`, and a line in [CHANGELOG.md](../CHANGELOG.md). If the change completes something in [IDEAS.md](IDEAS.md), update or remove that item.
7. Regenerate the response reference. [TOOL_RESPONSES.md](TOOL_RESPONSES.md) is generated by running the calls in the scenarios file against real logs:
   ```bash
   ./gradlew test --tests '*.docs.*' -PtoolResponsesLogDir=/path/to/logs
   ```
   The scenarios file names the three logs it reads, relative to that directory. They are Team 2363's and are not in the repository. Without them you cannot regenerate the file, and you do not have to: the checks accept a TOOL_RESPONSES.md that does not hold a new tool yet, as long as the scenarios file has a call for it (the capture fails when a tool has none). Say so in your pull request, and a maintainer will regenerate it.

## Releasing

The installer branch must merge together with the release carrying the `install` verb, since the installers on `main` download the latest release; older releases fall back to their own tagged installer.

1. Set `version` in `build.gradle` (e.g. `0.9.0`) and run `./gradlew syncExtensionVersion`; a test fails until the extension's files match.
2. Regenerate [TOOL_RESPONSES.md](TOOL_RESPONSES.md), whose first lines carry the version (step 7 of [Changing or Adding a Tool](#changing-or-adding-a-tool)).
3. Move the `[Unreleased]` entries in [CHANGELOG.md](../CHANGELOG.md) under the new version.
4. Commit, then tag `v0.9.0` with an annotated tag (`git tag -a v0.9.0 -F notes.md`: the file's first line is the title, the rest becomes the release notes, and the workflow appends the list of merged pull requests) and push the tag.

The release workflow (`.github/workflows/release.yml`) builds the server JAR and the `.vsix` under that version and attaches both to a GitHub release. It stops if the tag and `build.gradle` disagree. A release without a suffix is also published to the Visual Studio Marketplace; see [Publishing to the Marketplace](#publishing-to-the-marketplace).

A tag with a suffix, such as `v0.9.0-dev`, is published as a pre-release. For a pre-release, skip steps 2 and 3 and tag the version `build.gradle` carries. The repository's releases are immutable: a published release's files and tag cannot be changed, and a deleted release's tag name cannot be used again, so a release that went wrong gets a new version (`0.9.0-dev2` for a pre-release, the next patch version for a release), never a moved tag.

## Publishing to the Marketplace

The extension is published under the `TripleHelixProgramming` publisher. Publishing needs a Personal Access Token from an Azure DevOps organization, created with **All accessible organizations** and the **Marketplace (Manage)** scope. The publisher's management page is [marketplace.visualstudio.com/manage](https://marketplace.visualstudio.com/manage). The release workflow publishes every release without a suffix when the token is in the `VSCE_PAT` repository secret; when the secret is missing or the token has expired, the workflow says so, and the `.vsix` from the GitHub release can be uploaded on the management page instead. The Marketplace does not accept a version with a suffix, and never accepts a version twice, so a release that must be redone gets a new patch version. `package.json`'s `publisher` must stay the publisher's ID: VS Code identifies the extension as `TripleHelixProgramming.wpilog-analyzer`, and changing it would make the Marketplace listing a different extension.

## Contributing

- Report bugs: open an issue with steps to reproduce, and the tool call and result if a tool gave a wrong answer.
- Request features: open an issue describing your use case.
- Submit pull requests: fork, make your changes, add tests, and open a PR.
