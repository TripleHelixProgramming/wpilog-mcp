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

## Verification policy

Checks should cost what they prove. Measure wall time before and after a speed change on the same
machine; record the command, selection, counts, failures and timing under `build/reports/`.
Do not rerun a passing check for reassurance. A failure which does not reproduce gets one retry;
report both results. Keep the Gradle daemon warm, build once, use `--tests` while working, and
never run `./gradlew clean` inside a round.

| Change or boundary | Required check |
|---|---|
| Each item while editing | Targeted `./gradlew test --tests '*RelevantTest'`; plant the bug and see that check fail. |
| End of a round, after the last change | One `env -u LANG -u LC_ALL ./gradlew build`. CI supplies Windows; report without waiting for it to start. |
| A file under `vscode-extension/` changed | `npm test` there. Run `npm ci` only when dependencies/lockfile changed or the install is absent. CI also gates Node/editor checks by these paths. |
| Capture, pull, NT4 or harness code changed | One `harness/run` after those changes; use `-PconformanceNative=none` if none of the native-triggering surfaces below changed. |
| NT4 client, gateway, capture writer, replayer or harness changed | Native replay with `-PconformanceNative=sample`, in that same harness run; zero shift only. |
| Writer output, replayer output or matching changed | Stratified Java real-log sample with `-PconformanceLogDir=/path/to/logs`. A harness pacing-only change does not trigger it. |
| Before a release tag | Full real-log directory (`-PconformanceSample=full` and `-PconformanceNative=full`), without a file limit. |
| Coverage wanted | `./gradlew jacocoTestReport`, or add `-Pcoverage` to the build/test command. CI instruments and reports once, on Linux only. |

Ordinary tests never run native replay. They use half the available processors, at least one
fork and at most four, with 768 MiB per fork (at most 3 GiB combined heap). The real-log opt-in
uses one larger fork. Each task has its own disk cache; concurrent forks share its atomic cache
claims, while tests inspecting cache contents use `@TempDir` caches. Generated corpus files live
under `build/test-fixtures/worker-<worker>` and are written once per JVM, so Windows mappings in
another fork cannot be overwritten. The conformance sweep loads a baseline once per fixture and
uses decoded immutable views for entry-order permutations, preserving raw sample counts separately
from successfully decoded values. Coverage instrumentation is absent from ordinary/targeted runs.
The harness CI job downloads the ordinary job's XML evidence instead of rerunning its suite;
the Arrow cross-check similarly consumes that build's streams.

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

**Transfer logic.** `sync.FileTransferTest` uses a remote and local store in memory and advances
an injected clock: whole-prefix hashes, the last-64-KiB fallback, growth, shrinks, rewound clocks,
common-prefix boots, REV's repeated unset-clock name, DataLogManager renames, one-block pauses,
read pacing, one retry, manifest round trips, and concurrent-call refusal. Listing counters pin one
snapshot per pass and a ten-second refresh while a file grows. `TransferVerificationTest`
copies every generated fixture byte for byte and runs the normal readers, including rejection of
partial WPILOG records and native REV headers/frames. No test waits for a robot or sleeps for time.

**SFTP, gate and placement.** `PullConfigTest`, `PullGateTest`, `SftpTransportTest`,
`PullCoordinatorTest`, `PullStoreTest`, and `CapturePullTest` cover configuration and secrets,
HAL-source reads over a fake channel, command quoting, slow hash replies with an injected deadline,
cancelled/expired timers, and host-key refusal before secret authentication. Tests cover explicit
acceptance, fingerprint reset, default empty-password contacts, and disabled settling,
block-boundary pauses, retry scheduling, and the real capture listener over numeric loopback.
Store tests hold mapped reads before moves, check hidden staging and path ownership, byte-exact
placement and hashes, verified growth/rename, serial conflicts, identical signals across serials,
`data_alone`, and the 250 ms matching bound without chaining earlier offsets. REV and WPILOG
use the shared correlation machinery. A store with twenty distant sessions and one overlapping
session checks that only the overlapping capture is loaded. `SshPackagingTest` initializes Ed25519 and RSA SHA-2 from
the actual fat JAR without optional crypto providers, and checks packaged licenses and dependency
confinement. `PullDocumentationTest` checks accepted keys and the opt-in hardware invocation.
Every ordinary fixture remains synthetic; Linux and Windows run these tests without a robot.

`SftpLoopbackTest` additionally runs the production JSch transport against `FakeRoboRio`, an
Apache MINA SSHD server with a fresh Ed25519 key and a temporary SFTP root. It checks offset
reads, exact prefix hashes, HAL identity files, keepalives while exec is silent, injected command
deadlines, and host-key refusal before sending a password. The fake-channel tests remain: a real
socket checks interoperability, while an injected channel pins schedules precisely. MINA and its
EdDSA provider are test dependencies only.

**Robot identity.** `CaptureIdentityTest`, `RobotIdentityReaderTest`, `RobotCandidatesTest`, and
`LogStoreTest` check the HAL source convention, context at start/resume, serial promotion with
mapped readers, retained old paths, key history, disagreements, and logged identity in the listing
prefix. A blocked store queue cannot delay a mid-session context, value, or flush; promotion occurs
at close. A fixture larger than the 2000-record prefix counts mapped record bytes on each fresh
listing, and unreadable same-size store files prove candidates come from persisted manifests. `/SystemStats/SerialNumber` is resolved with the same metadata roles as imports.
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
`GatewayBindTest` leaves a plain server connection in TIME_WAIT and requires the gateway to bind
that same port without a retry; reading the socket option after binding cannot prove reuse was
enabled in time. An injected listener error checks that successful binding resets the next backoff
to one second. `GatewayLifecycleTest` checks that a failed close names its cause without claiming
the shutdown deadline expired.
`GatewayKeepaliveTest` and `ClientKeepaliveTest` stall injected clocks by 1.5 seconds while
network callbacks continue: healthy peers survive, an unanswered ping expires at one second,
and ping replies do not wait for fan-out. Receive demand and its queue bound are checked separately.
These tests need no robot, native NT library, external service, or committed log. Actual ntcore and
dashboard interoperability and native Windows execution still require their respective environments.

**Tool tests.** Each tool's behavior and its edge cases (empty and single-sample entries, NaN and infinite values, duplicate timestamps, missing Driver Station data, and bad arguments), on the fixtures and on small logs that the tests build in memory.

**Conformance sweep.** Every tool is called with arguments built from its schema, on every fixture if it reads a log. Each result must follow the [result contract](TOOLS.md#result-contract-success-status-and-related-fields): no internal error, no NaN, no silent success (a success whose content is only zeros, `false`, and empty lists counts as silent), `inputs` on every successful result that read a log, true totals for shortened lists, a note on every result computed from a log that was not read to its end, and every output the tool's description names.

The output must also be deterministic: each call on a log is repeated with the log's entries reversed and twice shuffled, and must give the same result (the REV log tools are exempt, because they depend on the synchronization done when the log was loaded). Tools whose needed parameters are optional in their schema get argument variants that reach their real analysis. `src/test/resources/conformance/known-failures.txt` is a ratchet: a violation not listed there fails the build, and so does a listed one that no longer occurs. It should stay empty. The report of every call is `build/reports/conformance/report.txt`.

**Differential check.** The conformance sweep shows that the tools keep their contract, not that a number is right. This check reads each fixture with a second WPILOG reader, written from the format specification alone and sharing no code with the server or with WPILib's reader, and compares what the two read: the time range, every entry's type and sample count, the statistics of the most-sampled numeric entries and of entries holding NaN, and the enabled windows. It also recomputes domain answers from the raw records of the entries each tool says it used, by the rules [TOOLS.md](TOOLS.md) gives: the loop-time statistics of `analyze_loop_timing`, the roboRIO brownouts in `power_analysis` and `get_ds_timeline`, the per-bus figures of `analyze_can_bus`, the module speeds of `analyze_swerve`, and the mode of each enabled segment in `get_match_phases`. No expected value is stored, so the same checks run on any log. The server may set records aside only where the second reader sees damage itself.

**Claim checks.** The documentation and the tool descriptions are checked against the code. Each tool's schema is compared with the parameters its code reads, the parameters in [TOOLS.md](TOOLS.md) with the schemas, and the tool table in the README and the catalog in `get_server_guide` with the registered tools. TOOLS.md must hold every tool, under the server's category for it. TOOL_RESPONSES.md is generated from logs a contributor may not have, so it may lack a tool that was just added; it may not misplace a tool or hold one the server does not have, and the scenarios file it is generated from must have a call for every tool. The reasoning guidance sent to agents may name only tools that exist and must fit its size limit.

**Process and installer tests.** Fresh JVMs verify startup configuration and logging. `InstallCommandTest`, `CodeInstallerTest`, `InstallRefreshTest`, and `InstallerTest` use temporary installs, fake release downloads, and fake VS Code CLIs. They check containment and layout on both platforms’ path rules, generated launchers, version ordering, exact JAR copies, JSON isolated from JVM stderr, preserved settings, refresh guards and stopping a real daemon, release selection, prompts, one matching VSIX installation, and the legacy fallback. PowerShell checks run when PowerShell is available; native Windows execution remains CI’s job.

Directory lease tests drive the real transport: session expiry/deletion/replacement, cached-read revocation, independent clients, origin/team listings, key precedence and captured logs, loopback/Origin refusals, symlinks, imports, and the actual inbox poller. A packaged bridge runs in a temporary home and project to prove home-only server configuration, project/flag leases, relative paths, URL connections, repeated initialization, and cleanup at EOF. The fake TBA service sees only synthetic keys. Mutation checks cover these boundaries; no live API or user's home is used.

**Version checks.** The extension's version must equal the project version, and no comment in the source may date a change to a release later than the current one. `ChangelogTest` requires one section per release and Unreleased first, so a duplicated published heading cannot hide pending changes.

**Build file check.** The stress test tasks, which nothing else runs, must build the test classes first and fail the build when a test fails. The CI workflow must run the license check, which neither `test` nor `shadowJar` includes.

CI runs `./gradlew test shadowJar license` on Linux and Windows, and builds and tests the extension. `license` checks that every Java file carries the license header in `gradle/license-header.txt`; `./gradlew licenseFormat` adds a missing one. On a push to `main` it also submits the Gradle dependencies to GitHub's dependency graph: GitHub does not read `build.gradle`, and without the submission Dependabot alerts cover only the extension's npm packages, not the libraries in the server JAR.

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
check, real-log claims and every Java gateway, pull, pair and live replay. Native selection is independently controlled by `-PconformanceNative=none|sample|full` (default `sample` for `shopHarness`). Reports under
`build/reports/conformance-sample/` name the selected paths, their strata and unavailable strata.
`-PconformanceMaxLogs=N` still restricts the input to the first N paths in sorted order, before
sampling, in both ordinary and harness tasks. A limited run may therefore lack a stratum or a
two-boot pair; its report says so. `-PconformanceTools` limits only the tool sweep, and
`-PsimLogDir` is described under the claims check below. See "The shop harness" for the selector's
rules and the sample/full verification policy.

- Real-log conformance sweep: the fixture sweep's checks (all but the comparison of each description with its outputs) and argument variants, for every tool that reads a log, on each selected `.wpilog` recursively under the directory, with the entries reversed for determinism. There is no ratchet, so any violation fails. The report, with the time of every call, is `build/reports/conformance/real-logs.txt`.
- Real-log differential check: the second reader against the server on each selected log, with the domain answers above. A file neither can read is counted, and a file only one can read is a finding. The report is `build/reports/conformance/differential.txt`.
- Real-log replay: every complete record through the gateway, JDK client, writer and HTTP tools,
  compared against the differential reader's original bytes, including type, metadata history,
  timestamps and topic accounting. Per-file JSONL reports are under `build/reports/replay/`.
- Claims on the live service: the server is started on its HTTP transport and called as a client would call it, and each answer is compared with a fact established independently of the tool under test. The facts are about Team 2363's sample logs, and about simulated logs whose `MANIFEST.md` was written by a separate reader; `-PsimLogDir` names the directory holding those. With other logs, without that directory, or when a named log is outside the selected sample, a claim whose precondition isn't met is reported as not verifiable, never as passed. The report is `build/reports/conformance/claims.txt`.
- Golden checks: values from a practice log of Team 2363 (`-PgoldenLog`) and from a 2026 championship elimination match that Team 4065 published under the MIT license (`-PgoldenMatchLog`, its `akit_cmptx_e4_sample.wpilog`), computed separately with WPILib's Python log reader and NumPy. Each property switches on its own set.

With `-PconformanceLogDir`, the tests run with a 4 GB heap (the launcher's default) instead of the usual test heap; `-PconformanceHeap=8g` changes it.

Use your own team's logs. Logs that another team has deliberately published can be used for testing on your own computer, but keep them there: publishing a log is not permission to redistribute it, and no log belongs in this repository. Nor does a value taken from another team's log: a number copied into a test is a copy too. The exception is a log whose license allows the copy, with the license's notice kept beside the values, as the golden checks do for Team 4065's MIT-licensed match.

### roboRIO SFTP shop test

Keep the robot disabled on a trusted shop network and generate a synthetic robot test log in one
of the configured default log directories. The hardware-only `SftpRobotTest` is skipped unless
`pullRobot` is explicitly supplied:

```bash
./gradlew test --tests '*SftpRobotTest' -PpullRobot=172.22.11.2
```

An explicit host or team IP can replace the USB address. `-PpullUser=lvuser` selects an account;
`WPILOG_PULL_PASSWORD` or `WPILOG_PULL_KEY` supplies authentication without command-line secrets.
The test reads device serial/comments, negotiates SFTP, lists logs, checks an offset read and a
SHA-256 of exactly the bytes read. It changes nothing on the robot and saves no robot data as
fixtures. Run it on roboRIO 1 and 2 to verify proc-environment permissions, comments, empty-password
access, the installed OpenSSH algorithms, and the available hash command. Then exercise the actual
pit server through disabled/enabled transitions, Wi-Fi loss, reboot, log growth/rename and a reimage;
measure CPU/network cost at the configured rate. This shop stress test remains the user's and
unverified here. Pulling stays off by default until those checks pass. System-log pulling is later work.

The filename regression also runs as `env -u LANG -u LC_ALL ./gradlew --no-daemon filenameLocaleTest`.
Linux CI checks explained refusals on its native non-UTF-8 encoding; macOS can remain UTF-8
without these variables. Reports are separate from the main suite. The upload test uses the
platform path API as its independent representability check.

### Metrics checks

`MetricsProjectionTest` checks every exposition sample with hand-computed scalar, array,
struct, clock, counter, provider and JVM answers. `PrometheusText` is an independent parser
that checks grammar, escaping, family grouping, unique labels, types and absent sample
timestamps. `LiveMetricsTest` uses real NT4 and HTTP, checks known functions and live-tool
cost agreement, and scrapes while both NT4 and store work are blocked. `MetricsDaemonTest`
executes the packaged JAR to pin YAML scope and capture wiring. `MetricsConfigTest` validates
keys and defaults; pull and protocol tests pin publication counters and nonblocking clock reads.

The fixture and opt-in real-log `LiveReplayTest` also scrape `/metrics`, comparing scalar and
array values with the differential reader and structs with WPILib's DynamicStruct. The
`metrics_samples` count appears beside entries/records/calls under `build/reports/live-tools`;
real-directory failures identify only a path. No robot values enter test sources or reports.

Linux CI also runs `python3 ci/check_metrics.py` after building the shadow JAR. It starts
that server and the documented Compose stack, runs Prometheus's own `promtool` on the
configuration and exposition, queries a real scrape, and verifies Grafana's provisioned
dashboard, datasource and all starter queries. Evidence is uploaded from
`build/metrics-smoke`. This optional Docker check is outside `./gradlew test`.

### The shop harness

Run `harness/run` on Linux or macOS with JDK 17 and a network connection for the first build.
An optional argument selects a timeline, for example
`harness/run harness/timelines/reboot-match.json`. Windows runs the ordinary real-SSH tests;
the complete simulation runner in step 1 supports Linux and macOS only.

The script builds the separate GradleRIO project in `harness/robot` once, then runs the opt-in
JUnit task `shopHarness` (tag `shop-harness`). Neither the robot build nor the simulation runs in
`./gradlew test` or `./gradlew build`. GradleRIO 2026.2.1 resolves WPILib 2026.2.2 Java artifacts and
the host's desktop JNI libraries into Gradle's cache; it downloads no roboRIO image or simulation
GUI. The runner launches the robot JAR with those libraries, without HAL simulation extensions.
CI has a separate Linux job on pushes to `pit-server`, sharing Gradle's download cache.

Each run owns fresh ports, a home directory, a disk cache, synthetic device files, and a store
under `build/shop-harness/`. `FakeRoboRio` serves `/home/lvuser/logs`, `/proc/42/environ`, and
`/etc/machine-info`; `/u/logs` and `/U/logs` are absent. Its exec channel accepts only the puller's
quoted `head -c N -- <path> | sha256sum` command and computes that prefix in Java, without a shell.
The packaged server starts from the shadow JAR with capture, pull and the gateway enabled. No robot is used,
and no generated log belongs in the repository.
The harness caps pulling at 64 KiB/s so the timeline exercises transfers on both sides of an
enable transition; the production default remains 1 MB/s.

`ShopHarnessTest` initializes an HTTP MCP session and asks the running server for its listing
and entry values. `HarnessExpectations` derives its answers from the timeline, independently of
the robot and capture writer; the differential reader checks every scripted record's bytes and
timestamp too. The checks cover serial/device identity, one session per boot, scalar/raw/struct
and struct-array topics with schemas, verified pulls matched near zero offset, DataLogManager's
rename, event/match manifests, and SFTP reads gated by disabled state. Saved manifests, HTTP
results, server/robot output, and the SFTP read audit explain failures. CI uploads this evidence.
The simulated robot also owns a separate ntcore client instance connected only to the gateway.
It subscribes to every scripted topic and schema, records the received server timestamps in a
separate observation log, and waits for gateway announcements before the runner releases the
timeline. The same independent oracle and HTTP calls check that log, including every struct
record, in each boot. This is native ntcore interoperability, not a real dashboard UI test.
The CI harness job first runs the ordinary suite and retains `build/test-results/test`, including
assertion messages, so later targeted runs cannot erase a socket failure. The local runner remains
the opt-in harness; `./gradlew build` runs the ordinary suite separately.

The ordinary gateway checks are `GatewayCoreTest`, `GatewaySocketTest`,
`GatewayBackpressureTest`, `CaptureGatewayTest`, `GatewayBindTest`, `GatewayLifecycleTest`, and
`GatewayConfigTest`; run them with `./gradlew test --tests '*Gateway*Test'`. Bind retries use an
injected clock: a held port leaves capture and pull running, health and sessions agree on the
waiting state, and releasing it restores downstream service. A nonloopback-interface check
pins the service's HTTP bind choice (skipped only if no such interface exists). Other sockets
use literal loopback addresses on Linux and Windows.

`SocketWriteDemandTest` plants a queued RFC 6455 frame with read-only selector interest, on
both the gateway and the independent scripted peer. Each must deliver it once without another
publication or a 4.1 ping. This pins the Java-WebSocket write-demand race reproduced by looping
the socket classes beside CPU workers, rather than increasing their wall-clock guards.
They check subscription periods/options, truthful write acknowledgements, queue bounds with a
real unread TCP peer, robot-clock round trips and absent-clock reconnects, session boundaries,
and forwarding/flushes during a blocked store operation. `MetricsDaemonTest` checks the port and
connected-client counter in the packaged process and one log warning per writing client.

To add a timeline, copy `harness/timelines/reboot-match.json`. Times ending in `_us` use the
robot's microsecond clock, reset on every boot. Keep `period_us: 20000`; samples cover
`[sample_start_us, sample_end_us)` on that grid, up to 10,000 samples per topic in the verifier's
HTTP page. Each boot lists ordered phases (`disabled`,
`teleop`, `autonomous`), match information and its delivery time, a sine frequency, counter-reset
indices, an ending time, and whether the exit is a reboot. Use distinct match numbers to identify
the expected sessions, and keep counter-reset indices ordered. The counter resets break the correlation ambiguity of a monotonic ramp;
names only nominate synchronization candidates. Give DataLogManager at least five seconds after
FMS attachment to rename, and leave disabled time for a throttled pull before the boot ends.
The log stops one second after the last scripted sample while NT4 stays connected for the pull.
At the end marker the process halts with code 75 for a reboot or 0 for the final boot; it skips
native shutdown hooks whose global destructors can race desktop NT/DS threads. The runner checks
the marker and code before starting another process.
The HAL clock waits until capture subscribes, then advances in 20 ms steps paced in real time.
`serialnum` is set in the robot's environment; simulation's empty HAL serial falls back to it.

This checks the programs and wire transports, not the NI image or radio. The shop day must still
check that the actual sshd permits an empty password, `lvuser` can read the robot process's
`/proc` environment, `sha256sum` is installed, and whole-prefix hashing has acceptable CPU/disk
cost on roboRIO 1 and 2. Wi-Fi loss, sustained load, and robot timing remain hardware checks.
An NI-image container and PhotonVision belong to harness step 2.

#### Replaying a directory of logs

The ordinary suite's `LogReplayTest` uses every generated fixture, including schemas, empty
entries, metadata changes and colliding names. `ReplayPullTest` adds real SSH/SFTP and measured
positive and negative offsets, with 240 ms accepted and 260 ms refused by the unchanged 250 ms
placement rule. `ReplayClockResetTest` keeps one client and writer through two boots, advancing
an injected scheduler through sync and reconnect, and checks that the two pulls never cross.
`NtcoreReplayPairTest` repeats that check with two native server processes; the client and store
remain alive. Real two-log checks choose complete files of one robot with calendar evidence and
a backward robot clock, without using a correlation result to select them.

For your own directory:

```bash
# Stratified sample through the gateway; no native simulation needed, including on Windows
./gradlew test --tests '*RealLogReplayTest' --tests '*RealReplayPullTest' --tests '*RealReplayPairTest' -PconformanceLogDir=/path/to/logs

# Build the robot once; timeline, Java fixture matrix, native fixtures and real-log native sample
harness/run -PconformanceNative=sample -PconformanceLogDir=/path/to/logs

# Native only, when required and the robot is already built (one zero-shift replay per selected file)
./gradlew shopHarness --tests '*RealNtcoreReplayTest' -PconformanceNative=sample -PconformanceLogDir=/path/to/logs
```

Without the directory property the real-log tests skip with a message. CI exercises fixture
replay in the ordinary Linux/Windows build and native fixture replay in the Linux harness job;
CI has no real-log directory. Sampling is the milestone default. It chooses the smallest log
spanning at least ten seconds per logger kind, or the smallest if that kind has only short logs,
plus a complete calendar-bearing representative where available. It also covers each size class
(below 1 MiB, 1 to below 64 MiB, and 64 MiB or larger), the largest file, a REV companion, an
incomplete tail, calendar evidence present and absent, and one complete calendar-bearing pair
of the same identity and layout whose robot clock resets. Representatives can cover several
strata and are deduplicated. Logger strata use the replayer's exact classification from its
header and recorded prefixes. Each file it cannot classify (`OTHER`) is its own stratum and
stays selected, as does each unreadable input whose refusal needs checking. Ties use normalized
path order; no random seed or correlation result enters selection. Inventory uses the independent reader and is shared within the test JVM;
readers close before replay. Reports contain paths, categories and counts, never telemetry.

Use the sample when the verification policy above calls for real-log checks. Full mode is for
before a release tag, not every milestone: use `-PconformanceSample=full` for Java and
`-PconformanceNative=full` for native. The properties are independent; `none` skips native
classes without skipping the timeline or Java fixture matrix. `conformanceMaxLogs` retains its
file-count limit; omit it for release verification.

Every selected file runs at zero shift. Only Java runs the eight-shift matrix on the smallest
qualifying logger representatives and a REV companion: negative offsets, small positive offsets,
240 ms accepted, 260 ms refused, and several-second refusal. Native proves publisher/transport
fidelity, not the same placement arithmetic again, and does not repeat the SFTP pull checks.
The generated two-boot native check still proves ntcore reset/reconnect interoperability; real
native boot pairs are additional full-mode checks. Selection reports name paths and strata,
with native `shifts_us: [0]`. Ordinary `ReplayPullTest` keeps four propositions per layout:
zero/identity, -120 ms/sign, +240 ms/inside boundary, +260 ms/outside boundary; `ReplayMatrixTest`
runs all eight through Java under the `shop-harness` tag. The boundary is 250 **milliseconds**.

The default robot clock uses source timestamps unchanged. Runs with 40, 120 and 200 ms shifts,
a negative shift, the placement boundary and a several-second refusal test distinguish measured
offsets from assumed zero. The capture writer and store receive the same injected calendar
clock, from recorded epoch time or a dated DataLogManager filename. Without either, capture
still runs and pull placement reports why it was skipped. An incomplete source is retried once
and refused by the normal pull verifier; the readable prefix is still replayed and checked. Invalid
UTF-8 cannot be transported as an unchanged NT4 string: the directory report records that refusal
and an independently checked invalid-record count, separately from successful captures.

`gateway-<directory>.jsonl`, `gateway-pull-<directory>.jsonl`, `ntcore-<directory>.jsonl` and
the two-log `*-pair-<directory>.json` reports under `build/reports/replay/` record
paths, logger kinds, entry/record/byte counts, escaped-name counts, mismatch categories, measured
offsets and wall time. Capture record bytes include their actual WPILOG headers, whose widths
can differ from the source; `accounted_bytes` and `accounted_records` include schema aliases and
must equal the writer's per-topic totals. Rollover schema seeds are counted separately and identity
context is outside topic accounting. Every REV file beside the source goes onto the fake roboRIO,
including those normal filename nomination leaves out; the same bus must retain its synchronization outcome and
its measured offset plus the replay shift. Failed inputs keep their scratch captures for diagnosis.
For rollover, the source-wide REV comparison uses a test-only view of all captured parts, without
creating a file above the reader's size limit. Each part's HTTP synchronization result is checked
separately and reported: its shorter input window can legitimately produce another alignment.
REV comparisons retain each measured result and release the decoded bus before comparing the
next one. `retained_revlogs_peak` reports the observed count in the source/capture sync caches;
the multi-bus fixture pins it to one. Retaining every decoded source and capture copy exhausted
the 4 GiB replay heap on a large rolled input.
No assertion or report copies telemetry values into source control.

The robot also accepts `--replay <file> <control-directory> <nt4-port> <shift-us> [speed]`.
Speed defaults to 1 (source pacing); 0 uses receive acknowledgements for the fastest lossless
replay. The JUnit runner owns this handshake, including metadata acknowledgements, so TCP queue
acceptance is never mistaken for capture completion. The native publisher allows up to
32,768 records rather than 1,024: the largest generated-fixture window accounts for 3,460,352
bytes under the conservative copied-work estimate, below the client's 32 MiB bound. ntcore
also has its own **2 MiB local publisher queue**; a separate 1 MiB byte budget (including a
conservative native message envelope) drains it before it can drop values. This bound can
end a batch before the record limit. Both sides use blocking pipe notifications and receipt
conditions, keeping control files as progress evidence. JDK directory watchers poll on some
platforms; replacing the 50/100 ms application polls with those events alone slowed this Mac
and exposed ntcore's smaller queue. Native zero-shift reports include per-file wall time.
The native verifier fails after 30 seconds without receipt progress, including metadata receipts,
instead of imposing a total duration on a whole file. The former five-minute deadline stopped a
healthy large replay mid-stream; injected-clock checks pin continued progress and stalled receipts.
The publisher's stop handshake finishes before offline auditing and SFTP verification, so a large
transfer cannot expire that handshake after all records have already arrived.
The native verifier also checks a digest of every Driver Station state transition, independently
decoded from the source. HAL's notification forces DS attachment true; replay restores the
recorded attachment field after notifying so a recorded disconnection remains a disconnection.

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
`CaptureFlushTest` holds a disk force behind a latch while the next listener task writes a record;
completion publishes the injected clock time, and close/rollover wait for the outstanding force.
Tests of cold values and manifests wait for this completion rather than assuming a timer tick
finished the disk operation. `ReplayCaptureTest` reports a disconnect immediately with its cause,
before a missing-record wait can obscure it as a later pull-placement failure.
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
That queue check counts output flush calls and keeps its values hot: its deadlock guard does
not impose a disk-force/remapping throughput requirement on Windows CI. `LiveLogTest` separately
exercises real flushes, hot-window expiry, mapping growth and cold reads on both platforms.
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
same-clock reconnect, and a resumed recording on a new clock. Its trace pins the server/receipt
times at each reconnect, and a controlled WebSocket delivers stale callbacks after the next
connection opens to check listener order without socket timing. Writer tests also plant a partial payload
write and check rollback to the completed prefix, force/create failures, and a bound unable to hold one record. The packaged
`start` command runs in an isolated home: HTTP works with the robot absent, then a loopback
fixture robot connects and its values survive daemon shutdown. No test waits for an injected clock.

`LiveCaptureConformanceTest` replays every fixture through the gateway/client/writer, then runs
the conformance suite's argument variants for every log-reading tool on the open session and on
a fresh load of its finished file. It compares complete results, excluding only execution timing
and the documented live-prefix range, after a properties patch on every entry, and also runs the
independent capture-fidelity checks. Metadata updates reach new calls while an acquired view keeps
the metadata it saw, as well as its value boundary.
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

#### The real editor smoke

```bash
./gradlew bundleExtension extensionSmokeFixture
cd vscode-extension
npm ci
VSCODE_VERSION=1.101.0 xvfb-run -a npm run test:smoke
VSCODE_VERSION=stable xvfb-run -a npm run test:smoke
```

These commands run on Linux with Xvfb installed. From macOS or Windows, use the two
`editor-smoke` CI jobs; the runner refuses to open an editor on those desktops.
CI runs both versions in separate Linux jobs. `@vscode/test-electron` 2.5.2 is a development
only dependency (MIT); that line supports CI's Node 20. It downloads a separate VS Code,
cached in `.vscode-test`, without using the installed editor. `npm test` is unchanged.
The smoke generates its log with the pure-Java fixture writer, seeds a standalone install
in a temporary home under `build/extension-smoke`, and lets the extension start its server.
It checks activation, the shared daemon's health/version, the actual Logs provider's leased
fixture listing, the custom editor tab, and the real pit command's user-scope arguments.
A test `claude` executable records argv without registering anything. The runner stops its
own daemon, retains logs/results under `build/extension-smoke`, and never changes the user's
install or account. Test code, downloaded editors and dependencies are excluded from the VSIX.
CI then runs `test:smoke:plants`: five faults in disposable compiled output remove activation,
startup, listing or editor opening, or add a synthetic secret argument. Each must fail at its
own assertion after the earlier checks passed; a timeout or unrelated crash is not accepted.
The script restores every compiled file and runs only in Linux CI.

This retires the real-editor caveat for those five surfaces. It does not inspect rendered
plots, exercise credential dialogs, contact the Claude service, or prove agent discovery.
The older milestones were checked without a real editor; these interactions still need the
oldest supported and current versions by hand:

1. Install from a fresh home: one offer, Not now lasting until activation, installer progress/PATH notice, and successful server startup. Verify only absolute User directories/team seed a new file and no key does.
2. Update an older launcher with its YAML preserved; keep a newer hand install. Test the on-demand install command and a development suffix.
3. Ask Copilot for `list_available_logs`: origins/teams are visible; opening and closing project windows adds and removes their directories. Settings/key/folder changes do not restart the daemon.
4. Use `get_match_info` with the secret-storage key and confirm no configuration, project file, command, or output contains it. Clear the key and check fallback behavior.
5. Register Claude Code at user scope, including the copy-command fallback. Accept/decline the project YAML offer and gitignore choice; run the bridge in a terminal with VS Code closed.
6. Open legacy projects: retire only recognized untracked/ignored entries, preserve other entries and tracked/custom files with a note, stop `vscode-default` once, and remove its private settings. Check a failed stop can retry next activation.
7. Exercise the explorer: log/entry trees and origins, plotting and statistics, console cursor, field pose, REV alignment, decode warnings, and read refusals. For organizing, check once-per-activation offers, Never/on-demand override, folder and robot picks, move/copy, progress/refusal output, inbox states, assignment, and refresh. The detailed milestone 6 checklist is in [EXPLORER_PLAN.md](EXPLORER_PLAN.md#11-milestones).

8. Set/clear the pit URL and verify the second VS Code MCP definition, the user-scope Claude offer and copied command, and that no project registration or remote directory/key lease is written. Try the oldest supported and current VS Code.
9. Configure mirror scope/cap/events/serials, then pin/unpin, Sync Now and Open Mirror Folder. Observe remaining counts, synchronized state, offline age and a verification refusal. Confirm a leased mirror remains readable and the health endpoint answers during sync.
10. Open a pit log with a remote Windows path and one with spaces/Unicode. Follow plotted series and console across new values, duplicate timestamps, pause, hidden editor, and disconnect. Open the exact mirrored copy offline and verify the copy label. Rename a session at the origin and confirm the listing follows it.
11. Sync from another laptop twice: destination choice, host:port entry, remembered quick pick, progress and copied/present/conflict/refusal output. Mirrors must not appear as destinations or organizer offers.

Native Windows launchers/CLI integration and the interactive installer should also be checked on Windows; Node tests of Windows path/argument rules do not replace that. Installer tests use temporary homes and fake releases/CLIs, never the user's install.

### The store HTTP door

`StoreDoorTest` runs the actual HTTP transport against fixture-backed stores: catalog descriptors,
filters, Range and prefix hashes, the HTTP remote, open-capture growth, and unassigned payloads.
It checks traversal, strays, inbox and control-file refusals; lease-only stores are not published.
One server bound to all interfaces serves the door while refusing both registration routes and
untrusted Origins. Plants remove each boundary, leak a synthetic credential into a manifest, and
report the coalesced size instead of the current file length. `ManualSchedulerTest` pins the native
replay harness race where clock advancement already drained the reply a subsequent wait expected.
Catalog payloads named `prefix-hash` are also exercised, separately from the hash operation's
required query field. `ReplayCaptureTest` drops a socket before replay and proves that subscription
readiness counts the current connection's announcements. Lifetime counts could overshoot forever
after a reconnect; timeout diagnostics now include connection and topic counts, without telemetry.

`StoreSyncTest` uses two stores on real in-process HTTP transports. Both transfer orders and a
third round must converge on the same session ids and hash union. It checks overlapping fragments,
serial separation, provenance and human conflicts, content-proved resume, peer disappearance,
placement recovery before contacting an offline peer, hash and reader refusals, mirror refusal in
both directions, and queued-job exclusion. An active writer must retain a peer's capture and match
facts on its next flush. The network-bind test permits loopback job submission and refuses a
connection through a nonloopback interface (only that interface check skips if none exists).
With small file bounds, both writers roll over after syncing; all peer hashes and all 402
generated value records survive, so a future local filename cannot collide with a peer capture.
`MainSyncTest` runs child JVMs offline and against a real daemon, checking remembered peers,
cross-process locking, exit codes and that daemon failures never fall back to an offline writer.

`StoreSyncConformanceTest` imports every generated fixture, syncs over HTTP, and compares every
log tool and schema-derived argument variant on the source-store file and its copied file.
It includes the REV companion, verifies identical bytes, and normalizes only execution time and
the known file paths. Counts are written to `build/reports/conformance/store-sync.txt`.
This comparison exposed directory prefixes leaking into inferred REV bus names on Windows.
`SynchronizedLogsTest` now tests Windows drive/UNC and Unix path strings on every platform,
including underscores in parent directories, numbered fallback buses and locale-independent names.
Plants change ids, overlap/serial rules, hash deduplication, provenance, conflict handling, resume
proofs, verification, recovery, move aliases, mirror/HTTP admission and a copied log's tool answer.
These tests need no robot, real log, external server or new dependency and run in the ordinary
Linux and Windows builds. Run them with
`./gradlew test --tests '*StoreSyncTest' --tests '*MainSyncTest' --tests '*StoreSyncConformanceTest'`.

### Mirrors

`MirrorSyncTest` serves a generated origin store on the real loopback HTTP transport. It checks
scope, whole events, serial filters, exact manifests and bytes, resume offsets and prefix proofs,
replacement, local reloads, ID-based moves, pins, cap/window eviction, offline retention and
interrupted transfers. `MirrorEndpointTest` exercises configuration, status, pin/unpin, Origin
and path refusals, offline listing age, and health while an origin is blocked. `MirrorConfigTest`
pins defaults and key-specific validation. `StoreRecordedAlignmentTest` plants a recorded offset
that differs from fresh correlation and proves the correlator is never called.

The `StoreSyncConformanceTest` sweep now runs both peer sync and mirror copies on every generated
fixture, including REV, and compares every log tool's results; its reports are
`build/reports/conformance/store-sync.txt` and `build/reports/conformance/mirror.txt`.
No robot log or robot value is needed. Linux and Windows CI run these in the ordinary suite.

### The data endpoint's streams

`DataEndpointTest` drives `GET /data/entries` on the real transport over the fixture corpus and reads every Arrow stream back with `ArrowSpecReader`, a reader in the test sources written from the Arrow IPC specification that shares no code with the server's writer. Every stream and the CSV of the same request are left under `build/arrow-samples/`, and CI's `arrow-crosscheck` job reads those with pyarrow, the reference implementation, and compares them to the CSV (`ci/check_arrow.py`), so a misreading the writer and the test reader could share does not pass. Run it by hand with `pip install pyarrow && python ci/check_arrow.py build/arrow-samples` after the Java test. Python is not needed for `./gradlew test`.

### Pit import coverage

`StoreUploadEndpointTest` sends generated bytes over loopback and a nonloopback interface,
checks placement and duplicates, rejects traversal, Origin violations, mirrors, unknown store
ids, wrong hashes and unreadable content, and keeps server-path operations local.
`StoreInboxTest` pauses an HTTP upload during inbox polling, checks queued cleanup behind a blocked store, ownership and size limits,
and imports while capture is enabled with an absent robot. `MainImportTest` sends a generated
USB directory through the actual command: two serials, two boots each, two logs per boot,
duplicate copies and a second import. No robot values enter these fixtures.
The extension's `upload.test.ts` checks streamed bytes and hash, store selection, polling,
refusal reporting and uncertain POST behavior. Manually check Upload Logs to Pit Server on
both supported VS Code versions, including the picker, several files, a duplicate, a refusal
and a network interruption; the editor smoke does not exercise the upload picker.

### Live tool checks

`LiveToolsTest` crosses HTTP MCP with the production capture service, a loopback gateway and
injected robot/calendar clocks. It pins ages, first-publication and per-session waits, exact
record bytes, thinning/exclusion, costs and session limits, closed summaries, missing topics,
bad arguments, shutdown/unannounce registration ordering, and import visibility while the store
queue is blocked. Session ordering includes mixed whole and fractional seconds. Description
outputs are checked against these actual results. `StoreManifestReplaceTest` injects Windows
access denials and a pause recorder: it pins complete atomic replacement, six attempts, error
selection, interruption, temporary cleanup and publication only after success, without sleeps. `LiveReplayTest` replays every generated fixture,
compares current values and timestamps with the independent reader, counts WPILOG record bytes,
and repeats calls for determinism. `LiveValueSnapshotTest` supplies snapshots on opposite sides of a topic redeclaration and pins the value's original authoritative type; the client's hand-encoded-frame test checks that the type is stored with the value. `LiveCaptureConformanceTest` continues to compare every
ordinary log tool on the live prefix and finished file. The only silent-empty exception is
`wait_for_change`'s specified successful `changed:false` timeout; empty analyses still fail.

Run `./gradlew test --tests '*Live*Test' --tests '*ClaimChecksTest'`. The existing
`-PconformanceLogDir=/path/to/logs` also feeds the live replay's relational checks, with path-only
failures and reports under `build/reports/live-tools/`; no real values are written into tests
or docs. Both stress entry points add concurrent HTTP live queries during fixture replay.
Recovery checks plant stale summaries and require unknown counts after both successful and
failed file recovery. The responses scenarios include all three capture-only tools; the ordinary reference server
answers that capture is not enabled.

`PitCredentialTest` uses synthetic credentials and real HTTP to check lease precedence,
removal/expiry, exact endpoint forwarding, origin-scoped store authorization, and network/Origin
refusals. Node tests cover SecretStorage key selection, headers on MCP/data/upload/polling,
registration and secret-free Claude arguments. The real-editor smoke checks the command without a
proxy credential. On the oldest supported and current releases, manually exercise Set/Clear Pit Proxy Credential, a proxy login from
agents/Logs/plots/uploads/mirror, Claude re-registration, lease expiry after closing the window,
and offline mirror access through `servers.yaml`.

### SSH context provider checks

```bash
./gradlew test --tests '*StatsProviderTest' --tests '*TailProviderTest' --tests '*SharedSshTest' \
  --tests '*ProviderCaptureTest' --tests '*ContextProviderStateTest' --tests '*ProviderConfigTest' --tests '*LoopWatchdogTest'
```

This suite
uses only synthetic `/proc` text and generated logs. MINA SSHD serves exact scripted commands,
never a general shell: independent expected CPU fractions, kernel tick/page/KiB conversions,
network rates, send-time stamping and missing-sync drops; injected period/backoff clocks;
append, rotation, split UTF-8, missing files and SSH reconnect; line caps and bounded buffering.
The full provider path shares one SSH authentication with device identity, records while the
robot is enabled, and compares HTTP/live/fresh-file results through the conformance and
differential readers. Resolver, metadata, manifest, metrics and configuration claims are checked.
The outside-loop watchdog is tested with two independent manual clocks. These tests run in
ordinary Linux and Windows CI, with no robot, native SSH executable or locale dependency.

Before a shop deployment, measure the two-second/100 ms defaults on roboRIO 1 and 2; check
`/proc` and `df` output and permissions, the deployed JAR lookup, console file, tail options,
`dmesg -w`/`journalctl -f`, rotations and resource cost. Scripted MINA replies prove the parser,
transport and capture path, not the shell utilities or permissions on an NI image. A stratified
real-log replay is sufficient for this provider addition; the full set runs before the next tag.

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

Before tagging, run the Java real-log replay commands above with `-PconformanceSample=full` and native with `-PconformanceNative=full`, with no file limit. A sampled milestone run is not the release check.

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

The pit/mirror extension tests (`pitServer`, `storeClient`, `pitTransport`, `follow`, and the
manifest/Claude/organizer checks) run under `npm test` on both CI platforms. Node HTTP servers
check controls and job polling, refusals and deadlines; the real JAR check includes leased
store discovery. VM tests exercise the webview's actual plot/console handlers and independently
check incremental boundary, pagination and memory limits. Plants remove the URL definition,
identity checks, prefix boundary, pin/cap defaults, endpoint routes and polling safeguards.
These tests do not replace the real VS Code checklist above.

`RelativeTimeTest` and the HTTP `LiveToolsTest` check last-seconds scopes on closed and open logs,
including separate endpoints for two-log comparisons, invalid/ambiguous bounds and a moving
robot clock. `LiveLogTest` pins the clock for an already acquired call. The schema-driven sweep
includes a recent-window variant. `McpMessageHandlerTest` and the HTTP fixture check current-session
resource discovery/read, unknown URI refusal, absent capture and empty prompts; discovery tests
pin each present-tense keyword. Stats MINA tests count initial/PID-restart/SSH-reconnect discovery,
assert scan-free steady commands and one combined `df`, and reject a reused PID until reidentified.
