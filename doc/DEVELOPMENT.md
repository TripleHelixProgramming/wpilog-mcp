# Development

How to build, test, and change wpilog-mcp. [ARCHITECTURE.md](ARCHITECTURE.md) explains how the code is organized and why it is designed the way it is. Read its [design principles](ARCHITECTURE.md#design-principles) before changing a tool.

## Requirements

- JDK 17 or newer; the WPILib JDK is recommended (see [STANDALONE.md](STANDALONE.md#requirements) for where WPILib puts it). `./gradlew` uses `JAVA_HOME`, or `java` on your `PATH`.
- [Node.js](https://nodejs.org/) 20, the version CI uses, with `npm` on your `PATH`: needed only to build or test the VS Code extension. Once its dependencies are installed, the extension development tasks need only Node; they can also use an existing Codex runtime as described below.

## Building

```bash
./gradlew build          # Full build with tests
./gradlew shadowJar      # Build the self-contained server JAR only
./gradlew readerJar      # Build the standalone read-only Java library
./gradlew install        # Build and install to ~/.wpilog-mcp/
```

The JAR is `build/libs/wpilog-mcp-{version}-all.jar`. `install` runs the fat JAR's `install --force` verb to set up the [standalone install](STANDALONE.md#install) from your build. It preserves an existing YAML or legacy JSON configuration and makes this development build current. Use `-PinstallDir=/path/to/scratch` for an isolated install.

### The VS Code extension

```bash
./gradlew bundleExtension    # Copy the server JAR into the extension (for local development)
./gradlew runExtension       # Open VS Code with the local extension and keep watching TypeScript
./gradlew openExtension      # Compile and open the development window, without a watcher
./gradlew compileExtension   # Compile TypeScript once
./gradlew watchExtension     # Watch TypeScript only; does not open a window (Ctrl+C to stop)
./gradlew buildExtension     # Build, compile, and package the .vsix
./gradlew installExtension   # Build, package, and install into VS Code
```

`installExtension` delegates to the JAR’s `install --force --with-extension --vsix <file>` verb, installing the standalone layout and the matching extension. VS Code lookup lives in that verb: the newest WPILib season whose VS Code the extension accepts, found without PATH ([STANDALONE.md](STANDALONE.md#the-matching-extension) has the folders). `-Pvscode=2025` names a season, and `-Pvscode=system` a VS Code installed apart from WPILib. `-PinstallDir=/path/to/scratch` overrides the server’s install directory. Close VS Code before running the task, and restart VS Code when the task is done. The `.vsix` is written to `vscode-extension/wpilog-analyzer-{version}.vsix`.

The extension's version is the project version in `build.gradle`. Every extension task runs `./gradlew syncExtensionVersion`, which writes it into `vscode-extension/package.json` and `package-lock.json`. A development version such as `0.9.0-dev` installs over the previous release, and the release that follows it installs over the development version.

#### Trying UI changes without installing an extension

From the repository root, run `./gradlew runExtension`. It compiles the extension, prepares its
bundled server JAR, opens an **Extension Development Host** window, and keeps compiling when
TypeScript files change. A compilation error prevents launch. The extension is loaded directly
from this checkout; no VSIX packaging or extension installation is involved.

The default editor is system VS Code, including the normal macOS app location even when `code`
is not on `PATH`. The task reuses the installer's editor lookup and compatibility checks. Use
`-Pvscode=2026` to select a WPILib season instead, or `-PextensionWorkspace=/path/to/robot/project`
to open a particular project in the development window:

```bash
./gradlew runExtension -Pvscode=2026 -PextensionWorkspace=/path/to/robot/project
```

Open a robot project or log in the new window. After an edit, wait for compilation to finish,
then run **Developer: Reload Window** there. JavaScript and CSS under `media/` are read directly
from the checkout and need only the reload. Stop the watcher with **Ctrl+C**; the VS Code window
stays open. To open the window without leaving a watcher running, use `./gradlew openExtension`.
Stop an existing watcher before starting another Gradle command. `watchExtension` alone only compiles and watches; it does
not open VS Code. This checkout has no F5 launch configuration.

These tasks use `node` on `PATH`, then an existing Node runtime under
`~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/`. To choose another runtime,
pass `-PnodeExecutable=/absolute/path/to/node`. An invalid explicit path is an error; it does
not fall back to another runtime. No Node runtime is downloaded. A fresh checkout still needs
`npm ci` in `vscode-extension/` to install its development dependencies once.

The development host uses the normal shared standalone server through the extension's usual
activation. For Java server changes, use `./gradlew install` and restart the shared server.
Rebuilding the bundled JAR alone does not replace an installed server at the same version.

## Verification policy

Checks should cost what they prove:

- Measure wall time before and after a speed change on the same machine. Record the command,
  selection, counts, failures and timing under `build/reports/`.
- Do not rerun a passing check for reassurance. A failure which does not reproduce gets one retry;
  report both results.
- Keep the Gradle daemon warm, build once, and use `--tests` while working. Never run
  `./gradlew clean` inside a round.

| Change or boundary | Required check |
|---|---|
| Each item while editing | Targeted `./gradlew test --tests '*RelevantTest'`; plant the bug and see that check fail. |
| End of a round, after the last change | One `env -u LANG -u LC_ALL ./gradlew build`. CI supplies Windows; report without waiting for it to start. |
| A file under `vscode-extension/` changed | `npm test` there. Run `npm ci` only when dependencies/lockfile changed or the install is absent. CI also runs Node/editor checks for changes to Main, `config/`, installers and `mcp/`, since its real-JAR tests exercise daemon ownership, installation and leases. The broker fixture also runs for reader, capture writer/index, its Java fixture and build wiring changes. |
| Capture, pull, NT4 or harness code changed | One `harness/run` after those changes; use `-PconformanceNative=none` if none of the native-triggering surfaces below changed. |
| NT4 client, gateway, capture writer, replayer or harness changed | Native replay with `-PconformanceNative=sample`, in that same harness run; zero shift only. |
| Writer output, replayer output or matching changed | Stratified Java real-log sample with `-PconformanceLogDir=/path/to/logs`. A harness pacing-only change does not trigger it. |
| Before a release tag | Full real-log directory (`-PconformanceSample=full` and `-PconformanceNative=full`), without a file limit. |
| Coverage wanted | `./gradlew jacocoTestReport`, or add `-Pcoverage` to the build/test command. CI instruments and reports once, on Linux only. |

Ordinary tests never run native replay. They use half the available processors, at least one fork
and at most four, with 768 MiB per fork (at most 3 GiB combined heap). The real-log opt-in uses one
larger fork. Coverage instrumentation is absent from ordinary and targeted runs.

Each task has its own disk cache. Concurrent forks share its atomic cache claims, while tests that
inspect cache contents use `@TempDir` caches. Generated corpus files live under
`build/test-fixtures/worker-<worker>`, whose root is cleared before its first use in each JVM, and
are written once per JVM. Every separately created store/export fixture root is cleared before
creation too: reused worker numbers must not inherit another build's manifests. Windows mappings in
another fork cannot be overwritten.

The conformance sweep loads a baseline once per fixture and uses decoded immutable views for the
entry-order permutations, keeping raw sample counts apart from successfully decoded values.

Synthetic tool tests use `MockLogAdmission` to admit their cached paths explicitly, pin their
`testPutLog` entries against heap-pressure eviction until scope cleanup unloads them, and restore
the prior configured roots. They must work while lease admission is active: relying on the legacy
cached-path exception made Linux CI's class/fork order change the answers. A real HTTP transport
with an empty directory lease pins this state in `ToolAdmissionTest`.

In CI, the harness job needs only path classification and runs beside the builds, with its own
evidence. Ordinary build jobs upload their own XML; no duplicate download or test run is needed.
Native selection follows the affected surfaces above. The Arrow cross-check consumes the ordinary
build's streams. An unreachable before-commit runs every job rather than failing classification or
skipping checks.

## Testing

```bash
./gradlew test
```

This runs every test that needs nothing outside the repository. Three rules apply to every change:

- A fix comes with a regression test that fails without the fix. Run the test against the old code and watch it fail before you trust it.
- A test of a number checks the number, against an answer worked out independently of the server. That a call succeeded proves nothing about what it returned.
- When you change a check, plant the bug it is meant to catch and see the check fail.

The tests run on Windows in CI too. Build an expected path with the same path API the code uses, never from a hand-written string; compare paths as the file system does; and don't assume a checked-out file has LF line endings.

### The systemd service check

- `MainRunTest` starts isolated foreground children and checks stderr, exit status, ownership,
  health and live tools. It also starts, adopts and stops a real daemon inheriting `INVOCATION_ID`.
- `InstallCommandTest` checks POSIX program modes and repair without exposing private settings.
- `DaemonManagerTest` uses fake health endpoints to prove managed servers are never adopted, stopped
  or replaced.
- `ServiceUnitTest` checks the printed unit's hardening, escaping and write-free behavior.

The independent Linux `systemd` CI job is filtered to Main, configuration, installers, service
templates and the check itself. The job:

1. Builds only the shadow JAR, creates the service account and installs the printed units.
2. Checks launcher/JAR access as the service user, with modes in any refusal, and supplies the
   runner's JDK through `JAVA_HOME`.
3. Starts the service and timer, checks managed/version health and the `stop` refusal, runs the
   probe, and stops through systemd within 90 seconds.
4. Repeats with only `--managed` removed and requires that the same verifier reject the plant.

Journals, printed units and assertion results are uploaded even on failure. `ci/check_service.py`
refuses to install on a developer machine: it requires root and a disposable GitHub Actions runner.
The local Python check `python3 -m unittest ci.test_changes ci.test_check_service` proves filter
fallbacks and the verifier's refusal predicates without systemd. macOS and Windows do not run
systemd locally; their ordinary Java tests still cover the foreground command and ownership.

### What `./gradlew test` covers

Tests are in `src/test/java`, in the same packages as the code they test, plus a few test-only packages: `fixtures`, `conformance`, `golden`, `docs`, and `integration`.

**Fixture logs.** Small logs, one per logging convention (AdvantageKit match and practice logs, plain WPILib, swerve module states as an array and per module, vision templates, entries that only look like vision data, a team's own structs, a CANivore, alerts, a truncated log, and more). They are written at test time by a small WPILOG writer in pure Java, and their values are simple functions of time, so the right answer to any statistic is known exactly. None is committed.

**Store fixtures.** `store.LogStoreTest` writes synthetic WPILOG and REV files in temporary directories and drives the Java import queue and the real listing tool. It checks verified copies and moves, original provenance, duplicate hashes, REV correlation and ambiguity, serial promotion without merging, clock bases and overlap, unmanaged files, format refusal, queued callers, path containment, and the lifetime of readers and moved-path notices. No robot logs or external services are needed.

Legacy unassigned-layout migration compares the complete file receipt before and after copying,
normalizing only its path. Matching, its reason, the placement method, the robot fingerprint and
provenance must all survive; a planted null matching reason proves the check.

`StoreImportEndpointTest` drives the HTTP jobs and refusals on the real transport. `StoreInboxTest` checks stability, receipts, and listing states. `MainImportTest` drives child JVMs and a real daemon, including a cross-process file-lock refusal.

The store checks also distinguish a new named robot with a logged serial from a later serial promotion, keep non-overlapping sessions separate, count catalog reads per batch, and observe which REV candidates reach correlation. `log.LogMappingLifetimeTest` reads fixtures through tools and the manager before moving them, holds calls and background synchronization across eviction, and checks that the final holder releases the mapping so a real rename succeeds on Windows too.

**Tool tests.** Each tool's behavior and its edge cases (empty and single-sample entries, NaN and infinite values, duplicate timestamps, missing Driver Station data, and bad arguments), on the fixtures and on small logs that the tests build in memory.

**Conformance sweep.** Every tool is called with arguments built from its schema, on every fixture if it reads a log. Each result must follow the [result contract](TOOLS.md#result-contract-success-status-and-related-fields):

- no internal error and no NaN;
- no silent success: a success whose content is only zeros, `false`, and empty lists counts as silent;
- `inputs` on every successful result that read a log;
- true totals for shortened lists;
- a note on every result computed from a log that was not read to its end;
- every output the tool's description names.

The output must also be deterministic: each call on a log is repeated with the log's entries reversed and twice shuffled, and must give the same result. The REV log tools are exempt, because they depend on the synchronization done when the log was loaded. Tools whose needed parameters are optional in their schema get argument variants that reach their real analysis. `src/test/resources/conformance/known-failures.txt` is a ratchet: a violation not listed there fails the build, and so does a listed one that no longer occurs. It should stay empty. The report of every call is `build/reports/conformance/report.txt`.

`RenderChartTest` checks requested PNG dimensions, full-window summaries against independent
fixture arithmetic and `get_statistics`, histogram bins, step holds, resolver-only phases,
exact scatter alignment, field geometry, pagination and unavailable imaging. `ChartDifferentialTest`
recomputes summaries from the independent reader using the entries/windows reported in inputs.
The schema-driven conformance variants cover every kind and decode every returned PNG. Node
checks cover the pure data-table adapter, notebook cells, URI admission and selection prompts;
CI's editor smoke also checks actual Perspective rows before and after append. Tool-only
conformance edits do not select the robot harness; replay/capture conformance edits still do.

**Differential check.** The conformance sweep shows that the tools keep their contract, not that a number is right. This check reads each fixture with a second WPILOG reader, written from the format specification alone and sharing no code with the server or with WPILib's reader. It compares what the two read: the time range, every entry's type and sample count, the statistics of the most-sampled numeric entries and of entries holding NaN, and the enabled windows.

It also recomputes domain answers from the raw records of the entries each tool says it used, by the rules [TOOLS.md](TOOLS.md) gives:

- the loop-time statistics of `analyze_loop_timing`;
- the roboRIO brownouts in `power_analysis` and `get_ds_timeline`;
- the per-bus figures of `analyze_can_bus`;
- the module speeds of `analyze_swerve`;
- the mode of each enabled segment in `get_match_phases`.

No expected value is stored, so the same checks run on any log. The server may set records aside only where the second reader sees damage itself.

**Claim checks.** The documentation and the tool descriptions are checked against the code, including the experimental references under `doc/pit-server/`. Each tool's schema is compared with the parameters its code reads, the parameters in [TOOLS.md](TOOLS.md) with the schemas, and the tool table in the README and the catalog in `get_server_guide` with the registered tools. The main tool reference must hold every registered tool, under the server's category for it.

`OperationsClaimTest` checks the manual's backticked tool names, CLI verbs, YAML examples,
provider names and states, emitted metrics, and relative links to document headings. Unknown
bare identifiers must be classified as prose or result fields so a misspelled tool cannot
silently escape the registry check. The checks do not repeat the manual's installation walk.

TOOL_RESPONSES.md is generated from logs a contributor may not have, so it may lack a tool that was just added. It may not misplace a tool or hold one the server does not have, and the scenarios file it is generated from must have a call for every tool. The reasoning guidance sent to agents may name only tools that exist and must fit its size limit.

**Process and installer tests.** Fresh JVMs verify startup configuration and logging. `InstallCommandTest`, `CodeInstallerTest`, `InstallRefreshTest`, and `InstallerTest` use temporary installs, fake release downloads, and fake VS Code CLIs. They check containment and layout on both platforms’ path rules, generated launchers, version ordering, exact JAR copies, and JSON isolated from JVM stderr. They also check preserved settings, refresh guards and stopping a real daemon, release selection, prompts, one matching VSIX installation, and the legacy fallback. PowerShell checks run when PowerShell is available; native Windows execution remains CI’s job.

Directory lease tests drive the real transport: session expiry/deletion/replacement, cached-read revocation, independent clients, origin/team listings, key precedence and captured logs, loopback/Origin refusals, symlinks, imports, and the actual inbox poller. A packaged bridge runs in a temporary home and project to prove home-only server configuration, project/flag leases, relative paths, URL connections, repeated initialization, and cleanup at EOF. The fake TBA service sees only synthetic keys. Mutation checks cover these boundaries; no live API or user's home is used.

**Version checks.** The extension's version must equal the project version, and no comment in the source may date a change to a release later than the current one. `ChangelogTest` requires one section per release and Unreleased first, so a duplicated published heading cannot hide pending changes.

**Build file check.** The stress test tasks, which nothing else runs, must build the test classes first and fail the build when a test fails. The CI workflow must run the license check, which neither `test` nor `shadowJar` includes.

CI runs `./gradlew build license` on Linux and Windows (Linux adds `-Pcoverage`), and builds and tests the extension. `license` checks that every Java file carries the license header in `gradle/license-header.txt`; `./gradlew licenseFormat` adds a missing one. On a push to `main` or `development` it also submits the Gradle dependencies to GitHub's dependency graph: GitHub does not read `build.gradle`, and without the submission Dependabot alerts cover only the extension's npm packages, not the libraries in the server JAR.

### Tests on real logs

These are opt-in, because the logs are not in the repository. Each is selected by its test package and switched on by a property:

```bash
# The real-log suites, on a directory of logs
./gradlew test --tests '*.conformance.*' -PconformanceLogDir=/path/to/logs [-PsimLogDir=/path/to/sim-logs] [-PconformanceSample=full] [-PconformanceMaxLogs=N] [-PconformanceTools=a,b]

# Golden values from known logs
./gradlew test --tests '*.golden.*' -PgoldenLog=/path/to/akit_26-09-30_00-10-26.wpilog -PgoldenMatchLog=/path/to/akit_cmptx_e4_sample.wpilog
```

`-PconformanceLogDir` enables a deterministic stratified sample by default; use
`-PconformanceSample=full` for every file. The same selector serves the tool sweep, differential
check, real-log claims and every Java gateway, pull, pair and live replay. Native selection is
independently controlled by `-PconformanceNative=none|sample|full` (default `sample` for
`shopHarness`). Reports under `build/reports/conformance-sample/` name the selected paths, their
strata and unavailable strata.

`-PconformanceMaxLogs=N` still restricts the input to the first N paths in sorted order, before
sampling, in both ordinary and harness tasks. A limited run may therefore lack a stratum or a
two-boot pair; its report says so. `-PconformanceTools` limits only the tool sweep, and
`-PsimLogDir` is described under the claims check below. For a targeted Java replay sample:

```bash
./gradlew test --tests '*RealLogReplayTest' --tests '*RealReplayPullTest' --tests '*RealReplayPairTest' -PconformanceLogDir=/path/to/logs
```

See [Releasing](#releasing) for the full pre-tag commands and the [integration testing guide](pit-server/DEVELOPMENT.md#replaying-a-directory-of-logs)
for the selector's strata and replay details.

- Real-log conformance sweep: the fixture sweep's checks (all but the comparison of each description with its outputs) and argument variants, for every tool that reads a log, on each selected `.wpilog` recursively under the directory, with the entries reversed for determinism. There is no ratchet, so any violation fails. The report, with the time of every call, is `build/reports/conformance/real-logs.txt`.
- Real-log differential check: the second reader against the server on each selected log, with the domain answers above. A file neither can read is counted, and a file only one can read is a finding. The report is `build/reports/conformance/differential.txt`.
- Real-log replay: every complete record through the gateway, JDK client, writer and HTTP tools,
  compared against the differential reader's original bytes, including type, metadata history,
  timestamps and topic accounting. Per-file JSONL reports are under `build/reports/replay/`.
- Claims on the live service: the server is started on its HTTP transport and called as a client would call it, and each answer is compared with a fact established independently of the tool under test. The facts are about Team 2363's sample logs, and about simulated logs whose `MANIFEST.md` was written by a separate reader; `-PsimLogDir` names the directory holding those. With other logs, without that directory, or when a named log is outside the selected sample, a claim whose precondition isn't met is reported as not verifiable, never as passed. The report is `build/reports/conformance/claims.txt`.
- Golden checks: values from a practice log of Team 2363 (`-PgoldenLog`) and from a 2026 championship elimination match that Team 4065 published under the MIT license (`-PgoldenMatchLog`, its `akit_cmptx_e4_sample.wpilog`), computed separately with WPILib's Python log reader and NumPy. Each property switches on its own set.

With `-PconformanceLogDir`, the tests run with a 4 GB heap (the launcher's default) instead of the usual test heap; `-PconformanceHeap=8g` changes it.

Use your own team's logs. Logs that another team has deliberately published can be used for testing on your own computer, but keep them there: publishing a log is not permission to redistribute it, and no log belongs in this repository. Nor does a value taken from another team's log: a number copied into a test is a copy too. The exception is a log whose license allows the copy, with the license's notice kept beside the values, as the golden checks do for Team 4065's MIT-licensed match.

### Stress tests

```bash
./gradlew stressTest       # Both stress tests
./gradlew stdioStressTest  # In-process only
./gradlew httpStressTest   # Over the HTTP transport only
```

The stress tests also need real logs, but they are Gradle tasks of their own rather than properties on `test`. The in-process test loads every log in the directory, runs the tools group by group on the first one, loads and evicts logs to stress the caches, and calls tools from several threads at once. It runs the REV log tools on the first log that has a REV log, and there checks the disk cache. It synchronizes the log with the cache off, then twice with it on, the second time from the cache; the REV log tools must give the same answers each time.

The HTTP test drives the HTTP transport as several clients would: sessions, concurrent calls, batches, and protocol edge cases, with its REV log calls on that same log. Both hold the results of their sequential calls to the conformance checks. A failing test fails the build. When the log directory does not exist, every test is skipped.

They take their settings from a server named `stresstest`, looked for in:

1. the file given with `-Pconfigpath=/path/to/config.yaml`, or else
2. the first of these files that defines one: `.wpilog-mcp.yaml` or `.wpilog-mcp.json` in the project root, then `~/.wpilog-mcp/servers.yaml` or `servers.json` (the file the installers write defines one).

With no such server, they use `~/riologs` and team 2363. A TBA key in those settings (or in `TBA_API_KEY`) is used, so the TBA tools then call the live API. The heap is `WPILOG_MAX_HEAP`, or `4g`. The disk cache is never the one in those settings, but the tests' own (below).

### Windowed mapping

`WindowedMappingTest` exercises exact boundary endings, crossing headers and truncated tails,
long random-access offsets in a sparse file and cold live reads beyond 2 GiB. The 4 KiB fixture
pass compares every record's timestamp and payload with the independent reader; one fixture runs
through the schema-driven conformance sweep and one through a real move import at that window
size. Windows CI runs a deliberately leaked-window mutation before its ordinary build and
requires the import check to fail. It observes the JVM's actual mapped-buffer count as well
as the move: a Windows/JDK combination that permits renaming a mapped file must still catch
the leak. The restored source then runs in the full build.

`./gradlew largeLogTest` is opt-in, beside the stress tasks. It writes a 2.2 GB synthetic fixture,
reads its last entry, imports it, and records enough capture data to roll a file past 2 GiB.
Allow about 5 GB of temporary disk space; nothing comes from a robot. It is excluded from
`test` and `shopHarness`. `IndexMemoryTest` reports the largest generated fixture's compact
index bytes under `build/reports/round17/`; ordinary files must retain four bytes per offset.
The report separates exact array payloads from per-entry container overhead (the latter stated
for HotSpot with compressed references and eight-byte object alignment).

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
- The Entries sidebar's inline-filter command, full-path filtering before branches are opened, lazy field expansion, stale asynchronous replies, keyboard navigation and Collapse All behavior.
- The webview's Arrow reader against Java-produced streams and CSVs, including enums and decode problems; plot arithmetic on known arrays; a decode-warning chip through the real handler with a small test DOM; and console, field, and REV models. Arrow fixtures skip when the Java build has not produced them.

New checks must fail on planted production faults. In particular, queue checks use latches and immediate exchanges to force ordering; a passing test that never exercised the competing update proves little.

#### The real editor smoke

```bash
./gradlew bundleExtension extensionSmokeFixture
cd vscode-extension
npm ci
VSCODE_VERSION=1.101.0 xvfb-run -a npm run test:smoke
VSCODE_VERSION=stable xvfb-run -a npm run test:smoke
```

These commands run on Linux with Xvfb installed. From macOS or Windows, use the two `editor-smoke`
CI jobs; the runner refuses to open an editor on those desktops. CI runs both versions in separate
Linux jobs. `@vscode/test-electron` 2.5.2 is a development only dependency (MIT); that line supports
CI's Node 20. It downloads a separate VS Code, cached in `.vscode-test`, without using the installed
editor. `npm test` is unchanged.

The smoke generates its log once with the pure-Java fixture writer, copies it and its append record
into each run's temporary folder, seeds a standalone install in a temporary home under
`build/extension-smoke`, and lets the extension start its server. It checks activation, the shared
daemon's health/version, the actual Logs provider's leased fixture listing, the custom editor tab,
Perspective's actual row count (two fixture records, then three after an appended record), and the
real pit command's user-scope arguments. A test `claude` executable records argv without registering
anything.

The runner stops its own daemon, retains logs/results under `build/extension-smoke`, and never
changes the user's install or account. Test code, downloaded editors and dependencies are excluded
from the VSIX.

CI then runs `test:smoke:plants`: six faults in disposable compiled output remove activation,
startup, listing or editor opening, replace an appended table batch, or add a synthetic secret
argument. Each must fail at its own assertion after the earlier checks passed; a timeout or
unrelated crash is not accepted. The script restores every compiled file and runs only in Linux CI.

This retires the real-editor caveat for those six surfaces. It does not inspect rendered
plots, exercise credential dialogs, contact the Claude service, or prove agent discovery.
The older milestones were checked without a real editor; these interactions still need the
oldest supported and current versions by hand:

1. Install from a fresh home: one offer, Not now lasting until activation, installer progress/PATH notice, and successful server startup. Verify only absolute User directories/team seed a new file and no key does.
2. Update an older launcher with its YAML preserved; keep a newer hand install. Test the on-demand install command and a development suffix.
3. Ask Copilot for `list_available_logs`: origins/teams are visible; opening and closing project windows adds and removes their directories. Settings/key/folder changes do not restart the daemon.
4. Use `get_tba_match_data` with the secret-storage key and confirm no configuration, project file, command, or output contains it. Clear the key and check fallback behavior.
5. Register Claude Code at user scope, including the copy-command fallback. Accept/decline the project YAML offer and gitignore choice; run the bridge in a terminal with VS Code closed.
6. Open legacy projects: retire only recognized untracked/ignored entries, preserve other entries and tracked/custom files with a note, stop `vscode-default` once, and remove its private settings. Check a failed stop can retry next activation.
7. Exercise the explorer: log/entry trees and origins, plotting and statistics, console cursor, field pose, REV alignment, decode warnings, and read refusals. For organizing, check once-per-activation offers, Never/on-demand override, folder and robot picks, import into a chosen store (including cancellation), in-place copy/move, progress/refusal output, inbox states, assignment, and refresh. The detailed milestone 6 checklist is in [EXPLORER_PLAN.md](EXPLORER_PLAN.md#11-milestones).

8. Exercise Perspective filters, grouping/pivots, quick charts and CSV export. Open a notebook beside a writable log and with workspace fallback; run it with a chosen Python kernel. Open chart links (including refused paths and histogram/scatter/field panes), use Ask about this selection in each pane, and check both chat and clipboard routes. These interactive flows remain manual.

Native Windows launchers/CLI integration and the interactive installer should also be checked on Windows; Node tests of Windows path/argument rules do not replace that. Installer tests use temporary homes and fake releases/CLIs, never the user's install.

### Visualization Stage 1 lifecycle prototype

`npm run test:visualization` in `vscode-extension` compiles and runs an isolated fixture extension
in real VS Code; set `VSCODE_VERSION` to an explicit cached/test release (for example `1.101.0`).
The runner uses a new profile, workspace, shared-data directory where supported, and synthetic backend under `build/visualization-stage1`;
it does not activate the product extension, start the installed server, or access robot logs.
On Linux, run it under Xvfb. On macOS it uses the cached editor and opens a disposable window.
It launches normally rather than using VS Code's in-memory test workbench; its second launch
checks actual dirty-document restoration and reconciliation of an offline edit. Set
`WPILOG_VISUALIZATION_DIALOGS=1` for the interactive Close check, then click **Cancel** in the
fixture's dirty-document dialog. The unattended run does not claim that check. The fixture
serializes editor switches through a webview acknowledgement, then requires the intended editor
to stay active for 200 ms. Lost focus retries reveal within one ten-second deadline; native
Undo/Redo commands are never retried. Deterministic Node tests inject focus loss at and after
acknowledgement, plus deadline exhaustion. A native cleanup regression starts with B active,
reveals A before Revert and checks B is unchanged. Each native Undo/Redo must invoke exactly one
callback and persist the expected configuration. It records callback and focus traces in `initial-native-events.json` (and the
restore equivalent); it never retries a failed Undo to obtain a pass. Native lifecycle failures are gates,
not optional passes; evidence must distinguish them from pure controller tests.

Focused server tests are `*Visualization*Test` and `*CallerContextTest`; the ordinary Node suite
includes visualization controller and recovery-journal tests. The Java contract resource owns
configuration validation. The fixture backend is a synthetic lifecycle test double, not a second
production persistence service. Native Save As stays unavailable with a Save view… hint until
its picker, destination alias, and replacement-tab gates are established. Stage 1 remains an isolated lifecycle regression; Stage 2a registers the optional product preview described below.

### Visualization ordinal-follow proof

The first Stage 3 gate proves the engine independently of HTTP and editor integration. Run
`./gradlew cleanTest test --tests '*OrdinalFollowTest' --tests '*SampleCursorsTest' --tests '*SessionReadTest' --tests '*LiveReadEngineTest' --tests '*ManagedLogAccessTest' --tests '*VisualizationSseLifecycleTest'`.
`OrdinalFollowTest` compares real writer-owned prefixes against `IndependentLog`, the separate
WPILOG-format reader. It checks physical offsets, ordinals, timestamps, values and malformed
records across bootstrap, equal/backward timestamps, concurrent appends and retries. Other
cases cover metadata/schema-only publications, late entries, caller-proven rollover members,
finalization, identity/history refusals, byte budgets and close during an admitted batch while
another reader and writer continue. Native cold-decode and SSE shutdown regressions have
failing-first evidence; the new continuation contract is checked with planted regressions.
The first-gate review refinements also have failing-first checks: native payload and wire
expansion gaps, a frontier sent once per prefix, metadata-only pages, full-envelope admission,
64 entry/member batches in one page, byte-boundary deferral and globally bounded work.
`SampleCursorsTest` pins the explicit size policy and its default refusal; `SessionReadTest`
checks that the policy survives the real HTTP/session adapter. Plants cover these boundaries.
The server integration adds `FollowEndpointTest`, `FollowLeaseTest`, `SessionAliasesTest`, and
catalog/sync/mirror/recovery cases. Run `./gradlew test --tests '*Follow*Test' --tests '*SessionAliasesTest' --tests '*StoreReadCatalogTest' --tests '*StoreSyncTest' --tests '*MirrorSyncTest' --tests '*CaptureRecoveryTest'`.
The real HTTP test drives a writer-owned capture through bootstrap/retry, backward appends,
metadata-only publication, finalization and live writer replacement. Its malformed-body regression
checks create, next and snapshot routes for a short 400 `INVALID_REQUEST`, including invalid UTF-8,
duplicate fields and truncated JSON, while preserving exact retained-page retries. Lease tests bound saturation,
idle expiry, cancellation and retained bytes; an acknowledged frontier releases its native prefix
before waiting. Store tests prove alias preservation, anchored membership, mirror copying and the
safe outcome after older writers drop aliases. A syntax-tree guard checks every session copy.
The pre-broker contract tests cover multi-page final drains, exact terminal retries and repeated
410 acknowledgements, terminal data release/expiry, typed structural/transient 413 refusals,
and resumption after a budget refusal that already acknowledged the prior page. Fairness cases
cover quiet members, distinct projections, malformed records, tiny pages and byte deferral;
the independent reader still checks exact values and physical ordinals. A 64 KiB page fixture
puts 5,000 numeric records beside ten 40,000-byte raw records: the first deferred selection
opens the second page, other selections fill remaining space, and each deferred lookahead is
read only once per page. A second large selection and a capped numeric query check that the
first deferral keeps priority; all values, timestamps, ordinals and exact retries are checked
through the completed prefix.
Writer-incarnation
tests drive real capture open/close/resume/rollover, cold replacement followed by finalization,
legacy finalization refusal, recovery, peer copying and mirroring. A provenance-copy syntax-tree
guard covers both telemetry and system-text copy constructors. These checks also require
`*CaptureStoreTest`; capture changes run the available shop harness with native replay selection.
These suites do not establish authenticated remote visualization, an editor live workflow,
native Linux/Windows execution or the declared performance workloads.

### Visualization follow broker checkpoint

Run `./gradlew followBrokerTest` after installing the extension dependencies. It compiles the
extension and test classes, then runs `liveBrokerReal.test.ts` against a child JVM containing the
real `CaptureWriter`, index and `HttpTransport`. `-PnodeExecutable=/path/to/node` uses the same
lookup as `compileExtension`. The task supplies the JDK and test classpath; ordinary `npm test`
explicitly skips the two integration cases when that classpath is absent. CI runs the task on both
Linux and Windows when extension/broker dependencies change. No editor, robot or user store is used.

The driver checks exact tagged values, signed zero, physical ordinals and duplicate/backward
timestamps; appends during a blocked bootstrap, byte-identical lost-response replay, metadata-only
publication, an oversized raw-record gap, server restart and a final hash/drain. It also follows
4,096 records of 19-element double arrays at the default 4 MiB page size, independently checking
all 77,824 floating-point bit patterns. That full server page exceeds the unreleased broker's old
262,144-node ceiling. The protocol regression admits the same-sized synthetic page and checks
the byte-derived traversal bound, negotiated-budget propagation and the separate depth limit.
A second real-server case uses the approved 1 MiB panel page size. It checks that later joins,
refresh, subscription release/reveal and wider windows preserve peers' leases, resets and exact
ordinal deltas; joining during server replacement and after finalization still drains the same
recording and retires each lease exactly once. This tests acquisition, not editor visibility.
Pure Node checks cover same-turn union/refcounts, sealing before callbacks/awaits, overflow
splitting, immutable query supersets through expiry, refresh admission before detachment,
monotonic reset numbers, cancelled borrowed pages and retirement reservations. They exercise
32 matching jobs at 1 MiB within 64 MiB, and refresh at the 256-consumer cap, alongside deadlines,
generation rejection, terminal/expiry states and exact retry cursors. HTTP tests cover cancellation (including credential lookup),
response limits, proxy authentication/refusals and origin confinement. Client-cap regressions
check the 64 KiB UTF-8 boundary, repeated windows and synchronous admission without changing
an existing peer. This checkpoint does not
exercise a live editor or establish the combined decoded display budget or performance gate.

### Visualization recorded preview

Run `./gradlew shadowJar extensionSmokeFixture`, then `npm run test:recorded-visualization`
in `vscode-extension`, with an explicit `VSCODE_VERSION=1.101.0` or current stable version.
The runner starts the built JAR as a foreground child with generated logs, a private home and
configuration, then launches an isolated normal editor profile. It never uses the installed
service or robot logs. It exercises the actual overview/working providers, bundled GridStack
and uPlot under CSP, Arrow and typed cursor replies, panel controls via Enter, A/A/B isolation,
and transport counts proving title/resize edits issue no new Arrow or cursor request,
Entries routing, first-store metadata save and a second Java/editor launch for draft/saved
recovery. Stage 2b also drives searchable templates, exact compatibility, template update and
export/import, independently checked statistics/raw rows, MCP revision delivery/conflict,
request-counted hidden/reveal behavior and overview Resume/Open new view. Results, renderer
diagnostics and screenshots stay under `build/visualization-stage2b`. The narrow DevTools
connection belongs only to this disposable editor. Supplied dialogs select the test store/file;
the native fixture runs the product empty-import and store-ID first-save path. Cancellation is
also covered in the Node fixture. Template digest checks use RFC 8785 vectors and an independent
SHA-256 over the expected canonical string. The canonicalization dependency's upstream license
and copyright notice ship under `META-INF/licenses/JCS-LICENSE.txt`. Native lifecycle regression remains
`npm run test:visualization`. Run both on the oldest supported/current editor; Linux requires
Xvfb, and actual Linux/Windows native results must not be inferred from Mac or mocked tests.

Focused Java tests include `TemplateDefinitionTest`, `TemplateMetadataTest`,
`VisualizationBindingsTest`, `ControlNotificationsTest`, `RecordedSourcesTest`,
`VisualizationEndpointTest`, `VisualizationServiceTest`, `VisualizationTombstoneTest`,
`VisualizationMetadataTest` and `CallerContextTest`. Node tests cover optional registration,
layout purity, Entries generations, lease/save ordering, aborts and response bounds. The focused
review checks also cover per-record saved-file failures, direct open beyond a listing budget,
locator reclamation, and host/renderer buffer retention across presentation revisions. Plants
must catch owner/revision bypass, session cleanup/race failures, stale replies and budget
removal. Template CAS/import receipts, role/type/unit refusal, JCS vectors, raw ordinal pages,
notification audience/replay/overflow and every named MCP success path have focused checks.
Deleted-view tests cross the live cap, exercise independent retry count/byte pressure, expiry,
legacy recovery and locator release. Removed-template cases drive get/apply/export/update
through real HTTP and MCP, plus deletion while an update waits on the store queue. Use
`cleanTest` before a counted rerun; an up-to-date build executes no test cases.
The frozen reader-equivalence corpus explicitly records these machine-local visualization
operations as exclusions; `VisualizationConformance` supplies their isolated successful
state transitions instead of comparing machine-specific IDs across frozen builds.
Plant their corresponding failures before trusting a changed check.

### The data endpoint's streams

`DataEndpointTest` drives `GET /data/entries` on the real transport over the fixture corpus and reads every Arrow stream back with `ArrowSpecReader`, a reader in the test sources written from the Arrow IPC specification that shares no code with the server's writer. Every stream and the CSV of the same request are left under `build/arrow-samples/`. CI's `arrow-crosscheck` job reads those with pyarrow, the reference implementation, and compares them to the CSV (`ci/check_arrow.py`), so a misreading the writer and the test reader could share does not pass. Run it by hand with `pip install pyarrow && python ci/check_arrow.py build/arrow-samples` after the Java test. Python is not needed for `./gradlew test`.

The same check reads `native/native_unsigned.arrow`, written by `NativeUnsignedReadTest`,
when the native sample directory is present. It compares the unsigned Arrow schema and exact
integer boundary values with independent Python integers, including values above 2^53 and
the signed 64-bit boundary. Native samples stay separate from the legacy CSV comparison,
whose unsigned struct conversion is unchanged. Run the checker with `TZ=UTC` on a workstation:
its legacy timestamp comparison uses Python's local interpretation of timezone-free datetimes,
and the Linux CI runner already uses UTC.

### Shared reader checks

`LocalLogAccessTest`, `LocalReadBoundaryTest`, `LocalReadConcurrencyTest` and `HeaderIndexTest`
exercise selective decoding, explicit window ends, large integer timestamps and values, original
ordinals, encoded-byte/workspace limits, close races and deterministic mapping release. The header
checks use a counting byte source to reject whole-payload reads during indexing and window lookup.
`LocalReadDifferentialTest` reads every generated fixture through 4 KiB mappings, checking raw
record positions, timestamps, primitive values and struct fields against the independent reader
and WPILib's dynamic struct decoder. Legacy enrichment and decode diagnostics are compared
separately. No real logs, Python, native robot runtime or HTTP service are needed for these checks.

`SampleCursorsTest` and `GuardedSampleCursorTest` pin shared traversal, whole-entry legacy
materialization, exact diagnostic wording, large double-timestamp ties and cancellation during
an active batch. The work checks require one backend admission for a complete entry, one framing
pass per record and payload-only buffer views. Performance comparisons measure indexing and decoding
separately, using mixed scalar/array/string entries, structs, incremental reloads and hot/cold live
prefixes; a faster scan must not hide a slower read. Keep the original and candidate JARs frozen,
alternate their run order, record allocation as well as time, and check values independently.
`ManagedLogAccessTest` checks shared cache identity, path admission, shutdown
and eviction lifetimes, live publication prefixes, and a source guard against direct manager
acquisition in tools or encoders. `LiveReadEngineTest` checks fixed-prefix order facts and address
accounting, hot/cold opaque values and the live decoder's exception policy. The existing reload,
mapping-lifetime and data-endpoint checks retain their response-level assertions.

`StoreReadCatalogTest` covers manifest-only admission, mirrors, provenance, invalid manifests,
move aliases, shared system receipts and independently calculated REV offsets/drift.
It also checks reuse and invalidation after new manifests, atomic replacements and payload loss,
and session unions against hand-calculated clocks, overlap refusals, excluded unproven sources and
provenance. It also checks captured rollover admission, exclusion of other recording runs, peer
round trips, retained index budgets and concurrent catalog retirement. `SessionReadTest` drives
the real HTTP transport over a writer-owned live prefix while new samples arrive.
`SessionTransferReadTest` connects the actual recorder to a loopback NT4 fixture, holds a session
prefix through growth and rollover, then reads the closed session after peer sync and mirroring
over HTTP. Timestamps and values are checked against the generating function on every path;
the manifests are written by the production recorder and transfer code. This proves those
software paths, not real-robot networking or filesystem behavior on an untested platform.
`SessionClockTest` checks inverse-window margins, clock chains and integer edges above 2^53;
a narrow session query asserts its visited-record count as well as hand-calculated values.
`SessionCursorTest` checks the aggregate lookahead bound. `QueryOperationsTest` pins
projection, exact bucket boundaries, non-finite counts and shared arithmetic; the legacy bucket and
window tests retain their independently computed expectations. `LogFollowerTest` covers partial
tails, incremental scan positions, backward timestamps and a held older prefix.
`RevReadCacheTest` checks concurrent decode sharing, active memory charges, replacement identity,
and input/decoded expansion bounds, plus selective decoding for exact device/signal keys.
`RevLogParserNativeFormatTest` checks that selection in the other REV container format. `LocalLogAccessTest` pins shared sort charges across snapshots.
`HttpAdmissionTest` includes 32 real clients that stop reading; `StreamingRequestsTest` proves
the progress watchdog releases a blocked socket without the client disconnecting. These tests
use bounded generated batches, so heap exhaustion cannot accidentally free a worker and disguise
the starvation being tested.
`HttpRefusalTest` sends an oversized body twenty times to each JSON body-reader route with a raw
socket client that cannot hide a lost reply by retrying. It also tests chunked MCP/follow input;
every attempt must receive the full refusal. `RequestBodiesTest` checks the four-MiB discard
bound, deadline cleanup and real sockets that stop during draining or exactly at the discard
ceiling; neither may strand a worker in request or response close. The follow endpoint
suite separately proves that a size refusal leaves the retained retry page intact.
`ReadEndpointTest`, `ReadWireTest`, `RemoteLogAccessTest` and `RevReadEndpointTest` cover the
versioned read routes, exact typed values, projected/reduced Arrow shapes, multi-entry NDJSON
and multipart Arrow, bounded leases, cancellation, completion proof and input/path admission.
They also check retryable overload, schema errors and null-message failures. Read and store
streaming admission are tested independently; the Python example test plants a decode problem
and requires it to survive the terminal-status check. `StructSchemaDescriptionsTest` keeps schema provenance intact
without trusting remote byte sizes. `ReadArrowTypesTest` pins the shared encoding owner.
`ReadLibraryTest` compiles and runs a consumer with only `readerJar` on its classpath, including a
mirror and recorded REV data, typed remote catalog/descriptor signatures, and checks that no home
or cache is written. `ReadLibraryLinkageTest`
checks bytecode dependencies so an unexercised error path cannot depend on an omitted server class.
The public API and runnable examples are in [READ_API.md](READ_API.md).

Run the frozen-build comparison below whenever shared scanning or decoding changes. The same-build
conformance oracle alone cannot prove compatibility with the previous implementation. Both MCP and
Arrow/CSV now use the shared admission and traversal; their compatibility materializer deliberately
keeps the legacy whole-entry diagnostics and value representation.

### Data store enrollment checks

`LocalIngestionTest` advances an injected clock through discovery, settlement, queued rechecks,
copy/move, duplicates, restart receipts, source limits and unavailable paths. `RevReconsiderationTest`
checks REV-first arrival, ambiguity, the same-boot gate, bounded work and preservation of original
provenance. `LocalIngestionEndpointTest` drives the real loopback policy, session, Origin and
revision gates; `LocalIngestionListingTest` checks default/all views before counts and paging.
`ConfigLoaderTest` and `MainRunTest` check that the named-server experimental flag defaults off
and gates loading saved policy, grants and synchronization. Extension pure tests cover command
visibility and direct-execution gating, setup cancellation, temporary lease restoration, source
defaults, Move acknowledgment and reporting. Real editor checks exercise the glue on supported
versions.

Opting out must preserve the frozen read comparison. Placement changes additionally require the
stratified Java replay; this feature does not change NT4, capture, the replayer or native transport.
Measure idle discovery and restart on a synthetic store: retained receipts must cause no repeated
payload hashing or correlation, and the timing report names its file/session count.

### Comparing frozen server builds

The shared read API migration starts with a behavior baseline, before changing the reader.
The ordinary conformance sweep compares entry orders within one implementation. The frozen-build
check instead runs the same requests against two packaged servers through HTTP MCP and the data
endpoint. It requires zero unexplained differences; a passing comparison is evidence for the
listed cases, not proof about every possible input or thread interleaving.

Create the corpus once from the baseline checkout, outside the repository. Keep its absolute
location, files, modification times and request manifest unchanged for both captures. Requests
are generated from the baseline's schemas and entries, never regenerated from the candidate.
The generator and capture commands refuse an existing output directory. Select the baseline
JAR from `build/libs` explicitly; that folder can also hold earlier versions.

```bash
evidence=$(mktemp -d)
git rev-parse HEAD > "$evidence/baseline-commit.txt"
./gradlew shadowJar readBehaviorCorpus -PreadBehaviorDir="$evidence/corpus"
cp /path/to/baseline-all.jar "$evidence/baseline.jar"
python3 ci/capture_read_behavior.py --jar "$evidence/baseline.jar" \
  --corpus "$evidence/corpus" --output "$evidence/baseline"
```

After the candidate is built, pass its JAR explicitly and use the baseline capture's `port`
from `manifest.json`. The endpoint URL is part of the public response and must be identical.
Do not copy the corpus to a second directory: paths and file identity affect responses and ETags.

```bash
python3 ci/capture_read_behavior.py --jar /path/to/candidate.jar \
  --corpus "$evidence/corpus" --output "$evidence/candidate" --port <baseline-port>
python3 ci/compare_read_behavior.py "$evidence/baseline" "$evidence/candidate" \
  --report "$evidence/comparison.json"
python3 -m unittest ci.test_compare_read_behavior ci.test_capture_read_behavior
```

The comparison preserves numeric tokens exactly, including integers above 2^53 and negative zero,
array order, null versus missing fields, warnings, paths and complete result metadata. MCP JSON text
blocks are compared structurally, and Arrow, CSV and image data retain their bytes. Object member
order is immaterial. Only the top-level tool execution duration is replaced with numeric zero;
its presence and numeric type remain checked. HTTP Date and the random MCP session identifier are
run-specific; MCP Content-Length is omitted because the duration changes the response length.
The capture manifest lists these rules. Raw responses are retained alongside compared results.
Inputs' hashes and modification times are checked before and after each capture. The corpus hash
is compared too. Missing cases, changed requests, malformed captures and incomplete runs fail.

Each child has an isolated home and cache, no TBA credentials, and a loopback-only listener.
Export artifacts are collected only for the request that writes them, and owned temporary exports
are removed on exit. Startup health and initial REV synchronization are barriers, with their raw
responses retained; their timing is not an equality assertion. Timings for every measured request,
the JDK and JAR hash are recorded under `run`, separately from behavior.

The corpus covers convention fixtures, a frozen manifest with system text, successful REV reads,
typed and projected Arrow/CSV, multiple entries, nonempty windows and refusals. A malformed record
outside a requested window pins existing whole-entry diagnostics. Existing consumers must retain
those diagnostics when selective decoding arrives; scoped diagnostics will be a new API option.
Runtime heap/cache observations, external TBA results and alignment writes are explicitly excluded.
Live growth, cancellation, mapping lifetimes, concurrent access, mirrors and third-party adapters
remain separate migration gates, with independent-reader and numerical checks retained. Do not
call a static corpus comparison complete validation of those behaviors or silently fix a baseline
defect during the refactor. Keep stage results separately reviewable; proceed through multiple
stages only when the maintainer has authorized doing so. Neither passing checks nor a completed
migration authorize publication.

## Changing or Adding a Tool

A tool is more than its code: agents read its description and schema, and several tests hold them to what the code does. When you add a tool or change what one takes or returns:

1. Follow the design principles. Return measurements with their sample counts, not conclusions. Find inputs through the shared signal resolver, never by a word in a name or by content alone. An entry is used when it is passed, follows a published convention, or is the only one of its type; anything else that looks right is listed as a candidate. Say what was used, what was skipped, and what was cut short. A tool that cannot apply returns `not_applicable` or `no_match` with what it looked for, never an empty success.
2. Use the shared pieces. A tool that reads a log extends the log-reading base, which adds the `path` parameter and loads the log. Build the result with the shared response builder, take time ranges through the shared scope and window handling, and attach data quality where the result rests on statistics.
3. Write the description for an agent. Name every output, and say what the numbers do and don't show. A test fails when a description names an output that no result contains.
4. Register it and list it. Register the tool in the module with related tools, and add it to the catalog that `get_server_guide` and `suggest_tools` use.
5. Test it. Add tests for the tool's behavior and the edge cases above, on a fixture or on a log built in the test. The conformance sweep calls a new tool automatically. If the tool has a required parameter that the sweep has no argument rule for, the sweep fails and says so, and the rule has to be added. Give the tool argument variants if its real analysis needs parameters that its schema leaves optional. Add the tool to the stress tests.
6. Document it. Add a section to [TOOLS.md](TOOLS.md) (its parameter list must match the schema) and an entry in the README's tool table, a call in `src/test/resources/tool-responses/scenarios.json`, and a line in [CHANGELOG.md](../CHANGELOG.md). If the change completes something in [IDEAS.md](IDEAS.md), update or remove that item.
7. Regenerate the response reference. [TOOL_RESPONSES.md](TOOL_RESPONSES.md) is generated by running the calls in the scenarios file against real logs:
   ```bash
   ./gradlew test --tests '*.docs.*' -PtoolResponsesLogDir=/path/to/logs
   ```
   The scenarios file names the three logs it reads, relative to that directory. They are Team 2363's and are not in the repository. Without them you cannot regenerate the file, and you do not have to: the checks accept a TOOL_RESPONSES.md that does not hold a new tool yet, as long as the scenarios file has a call for it (the capture fails when a tool has none). Say so in your pull request, and a maintainer will regenerate it.

## Releasing

The installer branch must merge together with the release carrying the `install` verb, since the installers on `main` download the latest release; older releases fall back to their own tagged installer.

Before a release tag, run both commands on the team's log directory, with no file limit:

```bash
./gradlew test --tests '*.conformance.*' -PconformanceLogDir=/path/to/logs -PconformanceSample=full
harness/run -PconformanceNative=full -PconformanceLogDir=/path/to/logs
```

Both must pass before tagging. A sampled milestone run is not the release check.

Before tagging a full release, remove the temporary version and checkout qualifications in
`README.md` (Installation), `vscode-extension/README.md` (the `--tag` paragraph under Install),
`doc/OPERATIONS.md` (opening note and 6.1 Install), and `doc/STANDALONE.md` (opening note and
Install). Search those four files case-insensitively for
"0.10.0-dev5", "Until this version" and "For these changes before release". The qualifications
apply only while `main` is older than this branch; keep the general checkout instructions and
JDK advice. The `--tag v0.10.0-dev5 --with-extension` examples in `README.md` (Installation) and
`doc/STANDALONE.md` (Install) also match the version search and stay: they show how to install a
published pre-release, not a temporary qualification. The two installer URLs in
`doc/STANDALONE.md` (Trying a pre-release) return from `development` to `main` at the release.

1. Set `version` in `build.gradle` (e.g. `0.9.0`) and run `./gradlew syncExtensionVersion`; a test fails until the extension's files match.
2. Regenerate [TOOL_RESPONSES.md](TOOL_RESPONSES.md), whose first lines carry the version (step 7 of [Changing or Adding a Tool](#changing-or-adding-a-tool)).
3. Move the `[Unreleased]` entries in [CHANGELOG.md](../CHANGELOG.md) under the new version.
4. Commits stay local, one per change, until the maintainer says push. This is a public repository: never push, tag, bump the version, or merge to main without the maintainer's explicit consent in words for that specific action. A go-ahead for a task is not consent for the push that ends it. Only with that consent, tag `v0.9.0` with an annotated tag (`git tag -a v0.9.0 -F notes.md`: the file's first line is the title, the rest becomes the release notes, and the workflow appends the list of merged pull requests) and push the tag.

The release workflow (`.github/workflows/release.yml`) builds the server JAR and the `.vsix` under that version and attaches both to a GitHub release. It stops if the tag and `build.gradle` disagree. A release without a suffix is also published to the Visual Studio Marketplace; see [Publishing to the Marketplace](#publishing-to-the-marketplace).

A tag with a suffix, such as `v0.9.0-dev`, is published as a pre-release. For a pre-release, skip steps 2 and 3 and tag the version `build.gradle` carries. The repository's releases are immutable: a published release's files and tag cannot be changed, and a deleted release's tag name cannot be used again. So a release that went wrong gets a new version (`0.9.0-dev2` for a pre-release, the next patch version for a release), never a moved tag.

## Publishing to the Marketplace

The extension is published under the `TripleHelixProgramming` publisher. Publishing needs a Personal Access Token from an Azure DevOps organization, created with **All accessible organizations** and the **Marketplace (Manage)** scope. The publisher's management page is [marketplace.visualstudio.com/manage](https://marketplace.visualstudio.com/manage).

The release workflow publishes every release without a suffix when the token is in the `VSCE_PAT` repository secret. When the secret is missing or the token has expired, the workflow says so, and the `.vsix` from the GitHub release can be uploaded on the management page instead. The Marketplace does not accept a version with a suffix, and never accepts a version twice, so a release that must be redone gets a new patch version.

`package.json`'s `publisher` must stay the publisher's ID: VS Code identifies the extension as `TripleHelixProgramming.wpilog-analyzer`, and changing it would make the Marketplace listing a different extension.

## Contributing

- Report bugs: open an issue with steps to reproduce, and the tool call and result if a tool gave a wrong answer.
- Request features: open an issue describing your use case.
- Submit pull requests: fork, make your changes, add tests, and open a PR.
