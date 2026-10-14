# Pit server: Development

> **Experimental:** the pit server has limited controlled-shop evidence. These documents are
> retained for development and controlled shop evaluation, not as a recommendation for team use.
> Automated tests and simulation do not establish reliability on a real robot.

**Transfer logic.** `sync.FileTransferTest` uses a remote and local store in memory and advances
an injected clock: whole-prefix hashes, the last-64-KiB fallback, growth, shrinks, rewound clocks,
common-prefix boots, REV's repeated unset-clock name, DataLogManager renames, one-block pauses,
read pacing, one retry, manifest round trips, and concurrent-call refusal. Listing counters pin one
snapshot per pass and a ten-second refresh while a file grows. `TransferVerificationTest`
copies every generated fixture byte for byte and requires the normal readers to reach clean EOF,
including rejection of partial WPILOG records and native REV headers/frames. Those deterministic
checks advance an injected clock. `FileTransferBatchTest` also checks durable batch ordering,
crash-tail repair, timer wakeups and bounded byte-rate credit. `SftpTransferThroughputTest` uses a
real loopback SSH server and an ordered TCP relay delayed 15 ms in each direction to measure
30 ms RTT, persistent-handle use and wire read-ahead. It uses wall time deliberately; no physical
robot is required.

**SFTP, gate and placement.** `PullConfigTest`, `PullGateTest`, `SftpTransportTest`,
`PullCoordinatorTest`, `PullStoreTest`, and `CapturePullTest` cover configuration and secrets,
HAL-source reads over a fake channel, command quoting, slow hash replies with an injected deadline,
cancelled/expired timers, and host-key refusal before secret authentication. Tests cover explicit
acceptance, fingerprint reset, default empty-password contacts, and disabled settling,
block-boundary pauses, retry scheduling, and the real capture listener over numeric loopback.

Store tests hold mapped reads before moves, check hidden staging and path ownership, byte-exact
placement and hashes, verified growth/rename, serial conflicts, identical signals across serials,
`data_alone`, and the 250 ms matching bound without chaining earlier offsets. REV and WPILOG use the
shared correlation machinery. A store with twenty distant sessions and one overlapping session
checks that only the overlapping capture is loaded.

`SshPackagingTest` initializes Ed25519 and RSA SHA-2 from the actual fat JAR without optional crypto
providers, and checks packaged licenses and dependency confinement. `PullDocumentationTest` checks
accepted keys and the opt-in hardware invocation. Every ordinary fixture remains synthetic; Linux
and Windows run these tests without a robot.

`SftpLoopbackTest` additionally runs the production JSch transport against `FakeRoboRio`, an
Apache MINA SSHD server with a fresh Ed25519 key and a temporary SFTP root. It checks offset
reads, exact prefix hashes, HAL identity files, keepalives while exec is silent, injected command
deadlines, and host-key refusal before sending a password. The fake-channel tests remain: a real
socket checks interoperability, while an injected channel pins schedules precisely. MINA and its
EdDSA provider are test dependencies only.

**System logs.** `SystemPullTest`, `KernelLinesTest`, `CaptureSystemPullTest` and
`SearchSystemLogsTest` use synthetic text and paired clocks. MINA serves SFTP and scripted exec
snapshots; tests cover block pacing, the disabled gate, rotation content reuse, journal cursor
restart and stand-down, kernel overlap/reboot, PID placement, and writer manifest updates. Tool
checks work out interpolation by hand, share severity cases with `search_strings`, pin
committed-prefix/path safety and true paging totals, and check discovery.

`SystemInventoryTest` counts parses in a 300-session store over ten idle passes, then changes
placements and the open session; its measured per-pass time is written under `build/reports/`.
`SystemIndexTest` checks fifty sessions/five shared rotations, written-span selection, legacy
receipts and the first journal command. `SystemTextTransferTest` checks catalog/range/hash reads,
peer and mirror copies, corrupt-byte refusal, growing-prefix resume, missing-copy guidance and
mirror capacity/eviction.

All of these stores and text files are generated. The conformance sweep includes a generated store
session with system companions. No image path or robot text is a fixture; the actual NI file set
remains the shop checklist in [STANDALONE.md](STANDALONE.md#pulled-system-logs-opt-in). The ordinary
build needs no SSH host.

**Robot identity.** `CaptureIdentityTest`, `RobotIdentityReaderTest`, `RobotCandidatesTest`, and
`LogStoreTest` check the HAL source convention, context at start/resume, serial promotion with
mapped readers, retained old paths, key history, disagreements, and logged identity in the listing
prefix. A blocked store queue cannot delay a mid-session context, value, or flush; promotion occurs
at close. A fixture larger than the 2000-record prefix counts mapped record bytes on each fresh
listing, and unreadable same-size store files prove candidates come from persisted manifests.
`/SystemStats/SerialNumber` is resolved with the same metadata roles as imports. `robot_candidates`
checks use synthetic exact fingerprints, ambiguity, contradictory hints, and CAN inventories. No
identity or value comes from a robot log.

**NT4 protocol and gateway.** `./gradlew test --tests '*.nt4.*'` checks the pure codec against
hand-encoded JSON/MessagePack frames from the NT4 and MessagePack specifications, both directions of
the type table, malformed input, and the time-sync arithmetic. The pure gateway tests drive explicit
times for overlapping subscriptions, batching, cached values, exact/prefix matching, `topicsonly`,
removals, and read-only acknowledgements.

Real JDK WebSocket clients connect to the loopback gateway on numeric `127.0.0.1` and ephemeral
ports. Every generated fixture is replayed in timestamp order; every announcement, unannouncement,
timestamp, and raw payload is compared with the source records, including struct schema topics and
intentionally malformed struct payloads. The replay helper also accepts a pace and an injected
pacer.

The client tests inject the event loop and clock to observe infinite 1–10 second backoff,
reconnection, and time-sync/keepalive timers without sleeping. A separate scripted peer checks
fragmentation, the 4.0 fallback, unknown IDs, malformed messages, and listener order. A planted
server clock is checked within the measured RTT.

`GatewayBindTest` leaves a plain server connection in TIME_WAIT and requires the gateway to bind
that same port without a retry; reading the socket option after binding cannot prove reuse was
enabled in time. An injected listener error checks that successful binding resets the next backoff
to one second. `GatewayLifecycleTest` checks that a failed close names its cause without claiming
the shutdown deadline expired. `GatewayKeepaliveTest` and `ClientKeepaliveTest` stall injected
clocks by 1.5 seconds while network callbacks continue: healthy peers survive, an unanswered ping
expires at one second, and ping replies do not wait for fan-out. Receive demand and its queue bound
are checked separately.

These tests need no robot, native NT library, external service, or committed log. Actual ntcore and
dashboard interoperability and native Windows execution still require their respective environments.

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
fixtures.

Run it on roboRIO 1 and 2 to verify proc-environment permissions, comments, empty-password access,
the installed OpenSSH algorithms, and the available hash command. Then exercise the actual pit
server through disabled/enabled transitions, Wi-Fi loss, reboot, log growth/rename and a reimage;
measure CPU/network cost at the configured rate. This shop stress test remains the user's and
unverified here. Pulling stays off by default until those checks pass. System-log pulling is a
separate opt-in; its candidate paths remain unverified until the shop visit.

Start with `wpilog-mcp robot-facts --server pit --out rio-facts.md`, or the direct-host form in
[STANDALONE.md](STANDALONE.md#robot-facts-for-the-shop). It uses the actual SSH authentication,
pins and read-only probes to collect the image facts; it never reads environment contents or
modifies the robot. Keep the resulting report outside the repository. Its conclusion keys map
the checklist as follows; **absent** on an exercise means not measured, not a missing capability.

| Shop checklist fact | Collector evidence / remaining exercise |
|---|---|
| NI version, image and kernel | `uname`, `image:*` (candidate metadata paths) |
| `/var/local/natinst/log`, `/var/log`, home and robot/USB log contents | `list:*` for all six directories |
| Installed journalctl, dmesg, df, tail and sha256sum; versions | `which:*`, `journal-version`, `tail-version` |
| Journal exists, selects the current boot and accepts a boot UUID | `journal-boot`, `journal-uuid`, `boot-id` |
| dmesg permission and `[seconds]` stamps for cursor wrap detection | `dmesg`, `dmesg-seconds-stamps` |
| Deployed JRE remote management and Flight Recorder modules | `jre-modules`, `module:jdk.management.agent`, `module:jdk.jfr`, `module:jdk.management.jfr`; `java --list-modules` through `/proc/<pid>/exe`, never a PATH-based guess |
| JMX launch enabled by the team | Inspect the robot's `build.gradle` GradleRIO `jvmArgs`: same registry/RMI port, reachable hostname, authentication/SSL choice as documented in STANDALONE; the pit server must not add flags. |
| JMX port reachable from the pit computer | Exercise the configured port from the pit network; a successful SSH or NT4 connection does not prove JMX reachability. |
| JVM provider actually sampling | With an open capture, `list_sessions.providers[]` has `name: jvm`, `state: sampling`; inspect its round trip, sample bytes and recorded `/Daemon/JVM/` entries. |
| Console path and deployed JAR's actual command line | `console`, `console-ni`, `program` |
| `/proc/<pid>/environ` readability by the selected account | `proc-environ-readable`; contents are never printed |
| Empty-password access / actual authentication method, host-key fingerprint | `ssh-authentication` and report header |
| `df -Pk` line shape, uptime beside wall clock | `df`, `df-line-shape`, `clocks` |
| Hash utility and robot-side CPU/disk cost | `which:sha256sum`, `hash` elapsed time with its requested bytes, at most 100 MiB; CPU attribution and whole-file cost remain shop measurements |
| Wi-Fi loss, load, timing, enabled/disabled gate, reboot, growth/rename and reimage | `wifi-loss-load-and-robot-timing`: run the actual server; a snapshot cannot exercise these |
| Provider defaults, proc field semantics/permissions and sustained CPU/disk budget on roboRIO 1 and 2 | `provider-cpu-and-disk-budget`: run providers and measure; the snapshot is only initial evidence |
| `tail -F -s`, `dmesg -w`, `journalctl -f`, reconnect and rotation | `tail-follow-options-and-rotation`: exercise live followers after inspecting utility versions |
| PhotonVision export ZIP, SQLite member, binary UI messages and version | Proven against the pinned v2026.3.4 release JAR by the Linux harness; not a real coprocessor. |
| PhotonVision provider state, captured file-camera settings and `analyze_vision.camera_settings` | Proven against that release with generated pixels, through the packaged pit server, manifests and metrics; no physical camera or measured calibration. |
| Deployed PhotonVision release, network reachability, camera calibration and actual settings changes | Remains a shop check on the team's coprocessor; another release is not covered by the pin. |

`JvmProviderTest` starts the test JVM's real JMX connector on one ephemeral loopback port, provokes
collections, and checks receipt timestamps and uptime mapping against fake NT4 time. The first
sample's bound is checked before the next poll; an uptime-tracking clock, a small within-bound
change and a six-second move pin when a measurement-only note is emitted. A failed sink stands down
across a resume and recovers only in the next session. Injected scheduling checks backoff and the
external deadline.

`JvmDeliveryTest` checks a reply queued across a boot. `JvmCaptureTest` checks
capture/live-tool/manifest/metrics agreement and differential reads. The generated `jvm_context`
fixture adds independently known counters to the conformance and differential sweeps. These tests
establish no roboRIO modules or budget.

`RobotFactsTest` scripts NI-like and sparse replies over MINA SSHD, including stderr/status,
bounded output, injected command deadlines, the hash ceiling, actual authentication and CLI
redaction. An independent command allowlist and pure report/conclusion checks catch writes,
invented utility presence, missing hash size and leaked synthetic credentials. No NI commands
execute on the test host.

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
CI has a separate Linux job on pushes to `development`, sharing Gradle's download cache.

Each run owns fresh ports, a home directory, a disk cache, synthetic device files, and a store under
`build/shop-harness/`. `FakeRoboRio` serves `/home/lvuser/logs`, `/proc/42/environ`, and
`/etc/machine-info`; `/u/logs` and `/U/logs` are absent. Its exec channel accepts only the puller's
quoted `head -c N -- <path> | sha256sum` command and computes that prefix in Java, without a shell.

The packaged server starts from the shadow JAR with capture, pull and the gateway enabled. No robot
is used, and no generated log belongs in the repository. The harness caps pulling at 64 KiB/s so the
timeline exercises transfers on both sides of an enable transition; the production default remains 1
MB/s.

`ShopHarnessTest` initializes an HTTP MCP session and asks the running server for its listing and
entry values. `HarnessExpectations` derives its answers from the timeline, independently of the
robot and capture writer; the differential reader checks every scripted record's bytes and timestamp
too. The checks cover serial/device identity, one session per boot, scalar/raw/struct and
struct-array topics with schemas, verified pulls matched near zero offset, DataLogManager's rename,
event/match manifests, and SFTP reads gated by disabled state. Saved manifests, HTTP results,
server/robot output, and the SFTP read audit explain failures. CI uploads this evidence.

Both backends compare verified pull receipts with the final remote log inventory, saved as
`remote-log-names.json`. DataLogManager can rename a listed pre-match file before its first SFTP
open; its saved zero-byte attempt cannot supply a content proof for rename recovery. The oracle
permits that leftover only when the remote name has vanished, its owned staged file is empty,
and it has no retry or failure. Partial, failed, duplicate and still-current unfinished pulls
remain failures. `HarnessPullManifestTest` forces this race and checks the refusal cases without
depending on thread timing; `RemoteLogInventoryTest` checks both backend listings.

Platform regressions run in the ordinary test suite: `ProviderReasonDescriptionTest` checks
the provider paragraph with both LF and CRLF, including its blank-line boundary.

The simulated robot also owns a separate ntcore client instance connected only to the gateway. It
subscribes to every scripted topic and schema, records the received server timestamps in a separate
observation log, and waits for gateway announcements before the runner releases the timeline. The
same independent oracle and HTTP calls check that log, including every struct record, in each boot.
This is native ntcore interoperability, not a real dashboard UI test.

The CI build jobs retain their ordinary test XML, including assertion messages; the separate harness
job retains its own XML and process artifacts. Neither repeats the other suite.

The ordinary gateway checks are `GatewayCoreTest`, `GatewaySocketTest`, `GatewayBackpressureTest`,
`CaptureGatewayTest`, `GatewayBindTest`, `GatewayLifecycleTest`, and `GatewayConfigTest`; run them
with `./gradlew test --tests '*Gateway*Test'`. Bind retries use an injected clock: a held port
leaves capture and pull running, health and sessions agree on the waiting state, and releasing it
restores downstream service. A nonloopback-interface check pins the service's HTTP bind choice
(skipped only if no such interface exists). Other sockets use literal loopback addresses on Linux
and Windows.

These tests also check subscription periods/options, truthful write acknowledgements, queue bounds
with a real unread TCP peer, robot-clock round trips and absent-clock reconnects, session
boundaries, and forwarding/flushes during a blocked store operation.

`SocketWriteDemandTest` plants a queued RFC 6455 frame with read-only selector interest, on both the
gateway and the independent scripted peer. Each must deliver it once without another publication or
a 4.1 ping. This pins the Java-WebSocket write-demand race reproduced by looping the socket classes
beside CPU workers, rather than increasing their wall-clock guards. `MetricsDaemonTest` checks the
port and connected-client counter in the packaged process and one log warning per writing client.

To add a timeline, copy `harness/timelines/reboot-match.json`. Times ending in `_us` use the robot's
microsecond clock, reset on every boot. Keep `period_us: 20000`; samples cover
`[sample_start_us, sample_end_us)` on that grid, up to 10,000 samples per topic in the verifier's
HTTP page. Each boot lists ordered phases (`disabled`, `teleop`, `autonomous`), match information
and its delivery time, a sine frequency, counter-reset indices, an ending time, and whether the exit
is a reboot.

- Use distinct match numbers to identify the expected sessions, and keep counter-reset indices
  ordered. The counter resets break the correlation ambiguity of a monotonic ramp; names only
  nominate synchronization candidates.
- Give DataLogManager at least five seconds after FMS attachment to rename, and leave disabled time
  for a throttled pull before the boot ends.

The log stops one second after the last scripted sample while NT4 stays connected for the pull. At
the end marker the process halts with code 75 for a reboot or 0 for the final boot; it skips native
shutdown hooks whose global destructors can race desktop NT/DS threads. The runner checks the marker
and code before starting another process. The HAL clock waits until capture subscribes, then
advances in 20 ms steps paced in real time. `serialnum` is set in the robot's environment;
simulation's empty HAL serial falls back to it.

This checks the programs and wire transports, not the NI image or radio. The shop day must still
check that the actual sshd permits an empty password, `lvuser` can read the robot process's
`/proc` environment, `sha256sum` is installed, and whole-prefix hashing has acceptable CPU/disk
cost on roboRIO 1 and 2. Wi-Fi loss, sustained load, and robot timing remain hardware checks.
The synthetic NI-like container below adds real Linux utilities and a JRE, not evidence about
an actual NI image. The pinned PhotonVision release runs in the Linux harness too; deployed hardware remains a separate check.

#### The container harness (step 2)

On Linux with JDK 17 and Docker Engine available to your user, run:

```bash
harness/run -PconformanceNative=sample
```

A Linux VM with Docker works too. `harness/run` is the shared entry point; `harness/rio/run`
delegates to it. On macOS, or Linux without a reachable Docker daemon, it prints that the container
and real PhotonVision are skipped and still runs the MINA timeline. An explicit CI
`HARNESS_RIO_IMAGE` requires Docker instead of silently skipping the check. No container, image,
privileged process, or package is installed by the ordinary tests.

The first container build downloads Ubuntu 22.04, Temurin 17 and Ubuntu's OpenSSH/coreutils
packages. The official images are read from ECR Public (Canonical's Ubuntu and Docker Official
Images' Temurin mirror), avoiding Docker Hub anonymous-pull limits on shared CI addresses. It reuses
the robot JAR and desktop JNI libraries built once by GradleRIO, and uses no file from an NI image.
The Dockerfile's allowlist excludes the store, logs, credentials and repository history from the
build context. The image is local; nothing is pushed to a registry. Its runtime host key is
generated per container.

`harness/rio/` supplies real sshd with `lvuser`'s empty password permitted, an accessible
`/proc`, `/home/lvuser/robotCommand`, and `/var/local/natinst/log/FRC_UserProgram.log`.
`sha256sum` is installed; `journalctl` is absent. A jlink-built real JRE contains
`jdk.management.agent`, deliberately omitting `jdk.jfr` and `jdk.management.jfr` so the
facts collector must report both found and absent modules. The command uses the five documented
JMX flags. All published SSH, NT4 and JMX ports bind to host loopback; the container is never
privileged, and the Docker socket and host `/proc` are never mounted into it.

`RioHarnessTest` runs the same two-boot timeline and packaged pit server, enabling pull, system
logs, stats, the console tail and `context.jvm`. It reuses the independent record-by-record
capture/pull oracle and checks device identity, DataLogManager rename and matching, provider states
and recorded costs, console transitions in both the timely tail and pulled exact file, the shipped
`robot-facts` command, and the JVM uptime pairing. Every sample's offset must equal its receipt
timestamp minus JVM uptime; a single offset must fit the intersection of all reported uncertainty
bounds. No tolerance is invented for the container.

Its HAL clock runs continuously; the ordinary harness's 20 ms stepped clock would introduce
artificial jitter into this test. Scripted telemetry retains explicit timeline timestamps, with
every due record emitted after a late callback. The MINA harness still checks gateway delivery and
the disabled gate's per-block audit; the container does not pretend OpenSSH supplies that audit. The
independent facts command uses the explicit loopback host and mapped SSH port, so its host pins
belong to its own temporary facts store rather than contending with the recording store.

CI's existing metrics-stack smoke preloads the same documented versions from Prometheus's
Quay registry and Google's Docker Hub cache, then retains the Compose tags; it does not change
the tested stack when shared Docker Hub quotas are exhausted.

CI builds the image with cached Docker layers, then runs both independent timeline processes in two
Gradle workers so their two 24-second boots overlap instead of doubling the job's wait. Native
fixture replay remains governed by the existing change filter; no real-log directory is configured
in CI. `build/shop-harness/rio-*/` retains the timeline, configuration, provider snapshots,
collector reports, server/console/sshd logs, manifests, tool results and `timing.json`; the JUnit
XML records each test's time. Docker cleanup runs even after an assertion fails.

The container proves interoperability with these declared assumptions, including real shell command
execution and JMX. It does not prove actual roboRIO authentication, permissions, module
availability, NI utility versions, reboot semantics of the kernel, radio behavior or roboRIO
CPU/disk cost. Those remain the shop checklist.

#### The real PhotonVision backend

On Linux x86_64 with Docker, the same runner starts the unmodified **v2026.3.4 Linux x64
release JAR** alongside the container timeline, against the same packaged pit server.
`harness/photonvision/release.json` pins its URL and SHA-256 (also matched against the release
asset's published digest). The 115,072,557-byte download stays under `build/harness-downloads/`;
CI caches it with `actions/cache`, keyed by that manifest. Fresh downloads and cache hits are
hashed before execution. PhotonVision is GPL-3.0-or-later, runs as a separate test process and
is bundled in neither the server JAR nor the extension.

The release offers `--test-mode`. Its `WPI2026` file camera reads a JPEG at a fixed test-resource
path: the harness writes a new blank 640 by 480 image there, never an upstream scene or a
coprocessor export. `--disable-networking` prevents device network changes; Java runs headless. Only
its fresh settings are configured to publish NT4 to the simulated robot. The release fixes HTTP at
5800 and its NT client uses 5810, so this opt-in Linux test requires those ports unused; ordinary
tests continue to bind ephemeral ports. The backend, generated configuration and image are isolated
under `build/shop-harness/rio-*/photonvision/` and terminated with the timeline.

A pin bump must re-verify `PhotonBackend.restarted()`'s two log strings,
`Web server going down for restart` and `Listening on http://localhost:5800/`, and the fixed ports
5800 and 5810.

A separate WebSocket observer checks every received top-level key against the provider's known
contract, binary MessagePack full state and the exact version. The HTTP export must contain a
SQLite-header `photon.sqlite`. Both robot boots must reach `following` in `list_sessions`, the
manifest and `wpilog_provider_state`; actual file-camera NT publications let `analyze_vision`
read the recorded `/Daemon/PhotonVision/WPI2026/Settings`. The generated blank image exercises
configuration and no-target traffic, not detection quality or measured camera calibration.
Snapshots, observed message keys, export, process log and startup time stay in the test artifacts.
Changed versions, ZIPs without the database and unknown keys (including beside valid settings)
are refused with a session-long `stand_down` reason, not guessed or retried.

#### PhotonVision provider checks and shop facts

`PhotonVisionProviderTest` uses a synthetic backend serving HTTP export and WebSocket messages
on one loopback port, with no downloaded configuration. `PhotonCaptureTest` checks both cameras'
records, timestamps, live tools, metrics and manifest while store work is blocked; the ordinary
vision fixture carries settings for exactly one camera, and the conformance/differential sweep
reads it. `ContextConfigTest` pins explicit hosts and inherited configuration. `IncrementalScanTest`
requires the resumed offset on every platform: Windows CI must exercise creation-time identity,
not permit a fallback fresh scan. Plants cover ignored identity, local-fetch timestamps,
guessed missing fields, ignored selective changes and settings attributed to another camera.

The pinned-release route/version and file-camera checks above are retired from the shop
checklist as software-contract questions. At the shop, confirm the deployed coprocessor runs
**PhotonVision v2026.3.4**, is reachable from the pit computer, and records its actual camera
nicknames, calibration resolutions/reprojection errors, a pipeline/exposure change (including
a selective notification), and the stand-down reason after backend restart.
The private routes may change in another release: do not silently relax the pinned shape.
The roboRIO facts collector checks SSH/NI-image facts; it does not replace these coprocessor checks.

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

Without the directory property the real-log tests skip with a message. CI exercises fixture replay
in the ordinary Linux/Windows build and native fixture replay in the Linux harness job; CI has no
real-log directory.

Sampling is the milestone default. It chooses the smallest log spanning at least ten seconds per
logger kind, or the smallest if that kind has only short logs, plus a complete calendar-bearing
representative where available. It also covers each size class (below 1 MiB, 1 to below 64 MiB, and
64 MiB or larger), the largest file, a REV companion, an incomplete tail, and calendar evidence
present and absent. It adds one complete calendar-bearing pair of the same identity and layout whose
robot clock resets. Representatives can cover several strata and are deduplicated.

Logger strata use the replayer's exact classification from its header and recorded prefixes. Each
file it cannot classify (`OTHER`) is its own stratum and stays selected, as does each unreadable
input whose refusal needs checking. Ties use normalized path order; no random seed or correlation
result enters selection. Inventory uses the independent reader and is shared within the test JVM;
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

`gateway-<directory>.jsonl`, `gateway-pull-<directory>.jsonl`, `ntcore-<directory>.jsonl` and the
two-log `*-pair-<directory>.json` reports under `build/reports/replay/` record paths, logger kinds,
entry/record/byte counts, escaped-name counts, mismatch categories, measured offsets and wall time.
Capture record bytes include their actual WPILOG headers, whose widths can differ from the source;
`accounted_bytes` and `accounted_records` include schema aliases and must equal the writer's
per-topic totals. Rollover schema seeds are counted separately and identity context is outside topic
accounting.

Every REV file beside the source goes onto the fake roboRIO, including those normal filename
nomination leaves out; the same bus must retain its synchronization outcome and its measured offset
plus the replay shift. Failed inputs keep their scratch captures for diagnosis. For rollover, the
source-wide REV comparison uses a test-only view of all captured parts, without creating a file
above the reader's size limit. Each part's HTTP synchronization result is checked separately and
reported: its shorter input window can legitimately produce another alignment.

REV comparisons retain each measured result and release the decoded bus before comparing the next
one. `retained_revlogs_peak` reports the observed count in the source/capture sync caches; the
multi-bus fixture pins it to one. Retaining every decoded source and capture copy exhausted the 4
GiB replay heap on a large rolled input. No assertion or report copies telemetry values into source
control.

The robot also accepts `--replay <file> <control-directory> <nt4-port> <shift-us> [speed]`. Speed
defaults to 1 (source pacing); 0 uses receive acknowledgements for the fastest lossless replay. The
JUnit runner owns this handshake, including metadata acknowledgements, so TCP queue acceptance is
never mistaken for capture completion.

The native publisher allows up to 32,768 records rather than 1,024. The largest generated-fixture
window accounts for 3,460,352 bytes under the conservative copied-work estimate, below the client's
32 MiB bound. ntcore also has its own **2 MiB local publisher queue**; a separate 1 MiB byte budget
(including a conservative native message envelope) drains it before it can drop values. This bound
can end a batch before the record limit.

Both sides use blocking pipe notifications and receipt conditions, keeping control files as progress
evidence. JDK directory watchers poll on some platforms; replacing the 50/100 ms application polls
with those events alone slowed replay on a Mac and exposed ntcore's smaller queue. Native zero-shift
reports include per-file wall time.

The native verifier fails after 30 seconds without receipt progress, including metadata receipts,
instead of imposing a total duration on a whole file. The former five-minute deadline stopped a
healthy large replay mid-stream; injected-clock checks pin continued progress and stalled receipts.
The publisher's stop handshake finishes before offline auditing and SFTP verification, so a large
transfer cannot expire that handshake after all records have already arrived.

The native verifier also checks a digest of every Driver Station state transition, independently
decoded from the source. HAL's notification forces DS attachment true; replay restores the recorded
attachment field after notifying so a recorded disconnection remains a disconnection.

### Stress tests

The general stress-test instructions remain in the [development guide](../DEVELOPMENT.md#stress-tests).

### Capture tests

`./gradlew test --tests '*.capture.*' --tests '*CaptureFidelityTest'` checks WPILOG bytes against
hand-encoded format examples, all NT4 payload families, clock continuity and reboot detection,
exclusion/thinning metadata, flushes, and minute-bounded cost accounting with injected clocks.

`CaptureFlushTest` holds a disk force behind a latch while the next listener task writes a record;
completion publishes the injected clock time, and close/rollover wait for the outstanding force.
Tests of cold values and manifests wait for this completion rather than assuming a timer tick
finished the disk operation. `ReplayCaptureTest` reports a disconnect immediately with its cause,
before a missing-record wait can obscure it as a later pull-placement failure.

Every generated fixture is replayed through the loopback gateway and JDK client into a capture; the
independent reader checks every entry, payload, and timestamp, and wpiutil checks the result too.
The bounded-file pass verifies the same received frames across every rollover, with marked schema
seeds checked separately at the rollover server time, plus every file's byte bound. Store and tool
checks pin each rolled file's minimum timestamp, including `inputs.session_time_range`.

This replay uses NT4 4.0 with an injected client clock so tiny forced files and tool calls do not
turn byte fidelity into an aliveness timing test; the NT4 suite independently tests 4.1 keepalives.
Replay allows 60 seconds of wall time for socket delivery and forcing the small rollover files,
which exceeds the normal helper's ten-second deadline on Windows; that deadline never advances the
injected clock or changes the fidelity assertions. Replay walks complete record boundaries because
wpiutil's iterator can omit a short final record. The client separately checks that a wrong
MessagePack family is counted and dropped without losing the next frame or disconnecting.

CI runs on `development` as well as `main`, including the Windows job.

`CaptureConfigTest`, `CaptureStoreTest`, and `CaptureStartTest` cover configuration keys and their
documentation, UTC placement, open/closed manifests, hashes, resumption and name collisions, and
queued match facts preceding the close-time directory rename. A blocked store queue leaves values
and flushes running, retains one pending update, and writes a complete final manifest. That queue
check counts output flush calls and keeps its values hot: its deadlock guard does not impose a
disk-force/remapping throughput requirement on Windows CI. `LiveLogTest` separately exercises real
flushes, hot-window expiry, mapping growth and cold reads on both platforms. Injected clocks pin the
five-second progress cadence and immediate changed facts.

`CaptureShutdownTest` proves service shutdown waits for that manifest outside the NT4 loop.
`CaptureRecoveryTest` plants open manifests with complete, incomplete-tail, unreadable and
damaged-header fixtures; checks hashes, ranges, modification-time endings, preserved facts and prior
files; and proves recovery waits on the store queue. A blocked sweep leaves the real HTTP health
endpoint responsive and starts no NT4 work until recovery completes. It verifies same-process and
cross-process ownership, reader mapping alongside a writer, alias and symlink guards, and cleanup
after an output fails to open.

A child JVM blocks the store queue, checks the 30 second default and injects a zero deadline into
the production wait, then exits without draining the pending manifest; the next service start must
finish it. No test sleeps to advance a clock.

`CaptureFailureTest` injects a disk failure over loopback and checks the reason in the manifest/log,
connection survival, a suppressed same-clock reconnect, and a resumed recording on a new clock. Its
trace pins the server/receipt times at each reconnect, and a controlled WebSocket delivers stale
callbacks after the next connection opens to check listener order without socket timing. Writer
tests also plant a partial payload write and check rollback to the completed prefix, force/create
failures, and a bound unable to hold one record.

The packaged `start` command runs in an isolated home: HTTP works with the robot absent, then a
loopback fixture robot connects and its values survive daemon shutdown. No test waits for an
injected clock.

`IncrementalScanTest` grows a pure-Java fixture across complete and partial record boundaries,
checks changed anchors and identity, late declarations/metadata, cached-value invalidation,
held mapping uses and a tool's across-growth refusal followed by the new file-size input.
`IncrementalDifferentialTest` compares the largest generated fixture's resumed index and every
record with a fresh scan and the independent reader. It records warmed fresh/resume scan medians
(five alternating measurements after two warmups, the same already-mapped file) in
`build/reports/round18/rescan-cost.txt`; mapping and filesystem cache costs are excluded equally.
Where a filesystem returns no file key, creation time identifies the file; unchanged anchors and
growth permit resume on Windows too. A recreated file with a reused creation time relies on the
header and last-record anchors, as the architecture guide explains.

`LiveCaptureConformanceTest` replays every fixture through the gateway/client/writer, then runs the
conformance suite's argument variants for every log-reading tool on the open session and on a fresh
load of its finished file. It compares complete results, excluding only execution timing and the
documented live-prefix range/disk file-size input, after a properties patch on every entry, and also
runs the independent capture-fidelity checks. Metadata updates reach new calls while an acquired
view keeps the metadata it saw, as well as its value boundary.

`LiveLogTest` checks fixed prefixes during a concurrent append, input ranges (including role-based
inputs), array growth, all hot/cold value families and schemas, and actual mapped-read counts
(including a plant that leaves every hot object intact). It also checks four-per-second expiry with
a zero hot window, mapping retirement with a held reader, resume after eviction, and rename after
release. The store rename tests now keep a live mapping beside the writer too. These run in the
normal Linux and Windows suite.

A sparse WPILOG beyond the mapping limit is refused by import without moving or placing its bytes;
loading and the plain-directory listing give the same reason. The shop stress test with a real robot
remains a manual check owned by the user.

### The store HTTP door

`StoreDoorTest` runs the actual HTTP transport against fixture-backed stores: catalog descriptors,
filters, Range and prefix hashes, the HTTP remote, open-capture growth, and unassigned payloads. It
checks traversal, strays, inbox and control-file refusals; lease-only stores are not published. One
server bound to all interfaces serves the door while refusing both registration routes and untrusted
Origins. Plants remove each boundary, leak a synthetic credential into a manifest, and report the
coalesced size instead of the current file length. Catalog payloads named `prefix-hash` are also
exercised, separately from the hash operation's required query field.

`ManualSchedulerTest` pins the native replay harness race where clock advancement already drained
the reply a subsequent wait expected. `ReplayCaptureTest` drops a socket before replay and proves
that subscription readiness counts the current connection's announcements. Lifetime counts could
overshoot forever after a reconnect; timeout diagnostics now include connection and topic counts,
without telemetry.

`StoreSyncTest` uses two stores on real in-process HTTP transports. Both transfer orders and a third
round must converge on the same session ids and hash union. It checks overlapping fragments, serial
separation, provenance and human conflicts, content-proved resume, peer disappearance, placement
recovery before contacting an offline peer, hash and reader refusals, mirror refusal in both
directions, and queued-job exclusion. An active writer must retain a peer's capture and match facts
on its next flush.

The network-bind test permits loopback job submission and refuses a connection through a nonloopback
interface (only that interface check skips if none exists). With small file bounds, both writers
roll over after syncing; all peer hashes and all 402 generated value records survive, so a future
local filename cannot collide with a peer capture. `MainSyncTest` runs child JVMs offline and
against a real daemon, checking remembered peers, cross-process locking, exit codes and that daemon
failures never fall back to an offline writer.

`StoreSyncConformanceTest` imports every generated fixture, syncs over HTTP, and compares every log
tool and schema-derived argument variant on the source-store file and its copied file. It includes
the REV companion, verifies identical bytes, and normalizes only execution time and the known file
paths. Counts are written to `build/reports/conformance/store-sync.txt`. This comparison exposed
directory prefixes leaking into inferred REV bus names on Windows. `SynchronizedLogsTest` now tests
Windows drive/UNC and Unix path strings on every platform, including underscores in parent
directories, numbered fallback buses and locale-independent names.

Plants change ids, overlap/serial rules, hash deduplication, provenance, conflict handling, resume
proofs, verification, recovery, move aliases, mirror/HTTP admission and a copied log's tool answer.
These tests need no robot, real log, external server or new dependency and run in the ordinary Linux
and Windows builds. Run them with
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

### Pit import coverage

`StoreUploadEndpointTest` sends generated bytes over loopback and a nonloopback interface, checks
placement and duplicates, rejects traversal, Origin violations, mirrors, unknown store ids, wrong
hashes and unreadable content, and keeps server-path operations local. `StoreInboxTest` pauses an
HTTP upload during inbox polling, checks queued cleanup behind a blocked store, ownership and size
limits, and imports while capture is enabled with an absent robot. `MainImportTest` sends a
generated USB directory through the actual command: two serials, two boots each, two logs per boot,
duplicate copies and a second import. No robot values enter these fixtures.

The extension's `upload.test.ts` checks streamed bytes and hash, store selection, polling, refusal
reporting and uncertain POST behavior. Manually check Upload Logs to Pit Server on both supported VS
Code versions, including the picker, several files, a duplicate, a refusal and a network
interruption; the editor smoke does not exercise the upload picker.

### Live tool checks

`LiveToolsTest` crosses HTTP MCP with the production capture service, a loopback gateway and
injected robot/calendar clocks. It pins ages, first-publication and per-session waits, exact record
bytes, thinning/exclusion, costs and session limits, closed summaries, missing topics, bad
arguments, shutdown/unannounce registration ordering, and import visibility while the store queue is
blocked. Session ordering includes mixed whole and fractional seconds. Description outputs are
checked against these actual results.

`StoreManifestReplaceTest` injects Windows access denials and a pause recorder: it pins complete
atomic replacement, six attempts, error selection, interruption, temporary cleanup and publication
only after success, without sleeps. `LiveReplayTest` replays every generated fixture, compares
current values and timestamps with the independent reader, counts WPILOG record bytes, and repeats
calls for determinism. `LiveValueSnapshotTest` supplies snapshots on opposite sides of a topic
redeclaration and pins the value's original authoritative type; the client's hand-encoded-frame test
checks that the type is stored with the value.

`LiveCaptureConformanceTest` continues to compare every ordinary log tool on the live prefix and
finished file. The only silent-empty exception is `wait_for_change`'s specified successful
`changed:false` timeout; empty analyses still fail.

Run `./gradlew test --tests '*Live*Test' --tests '*ClaimChecksTest'`. The existing
`-PconformanceLogDir=/path/to/logs` also feeds the live replay's relational checks, with path-only
failures and reports under `build/reports/live-tools/`; no real values are written into tests or
docs. Both stress entry points add concurrent HTTP live queries during fixture replay. Recovery
checks plant stale summaries and require unknown counts after both successful and failed file
recovery. The responses scenarios include all three capture-only tools; the ordinary reference
server answers that capture is not enabled.

`PitCredentialTest` uses synthetic credentials and real HTTP to check lease precedence,
removal/expiry, exact endpoint forwarding, origin-scoped store authorization, and network/Origin
refusals. Node tests cover SecretStorage key selection, headers on MCP/data/upload/polling,
registration and secret-free Claude arguments. The real-editor smoke checks the command without a
proxy credential. On the oldest supported and current releases, manually exercise Set/Clear Pit
Proxy Credential, a proxy login from agents/Logs/plots/uploads/mirror, Claude re-registration, lease
expiry after closing the window, and offline mirror access through `servers.yaml`.

### SSH context provider checks

```bash
./gradlew test --tests '*StatsProviderTest' --tests '*TailProviderTest' --tests '*SharedSshTest' \
  --tests '*ProviderCaptureTest' --tests '*ContextProviderStateTest' --tests '*ProviderConfigTest' --tests '*LoopWatchdogTest'
```

This suite uses only synthetic `/proc` text and generated logs. MINA SSHD serves exact scripted
commands, never a general shell. The checks cover independent expected CPU fractions, kernel
tick/page/KiB conversions, network rates, send-time stamping and missing-sync drops; injected
period/backoff clocks; append, rotation, split UTF-8, missing files and SSH reconnect; and line caps
and bounded buffering. The full provider path shares one SSH authentication with device identity,
records while the robot is enabled, and compares HTTP/live/fresh-file results through the
conformance and differential readers.

Resolver, metadata, manifest, metrics and configuration claims are checked. The outside-loop
watchdog is tested with two independent manual clocks. These tests run in ordinary Linux and Windows
CI, with no robot, native SSH executable or locale dependency.

Before a shop deployment, measure the two-second/100 ms defaults on roboRIO 1 and 2; check
`/proc` and `df` output and permissions, the deployed JAR lookup, console file, tail options,
`dmesg -w`/`journalctl -f`, rotations and resource cost. Scripted MINA replies prove the parser,
transport and capture path, not the shell utilities or permissions on an NI image. A stratified
real-log replay is sufficient for this provider addition; the full set runs before the next tag.

## Provider command cost

- `StatsProviderTest` counts real MINA exec commands with no program: 31 samples require four
  discoveries, without repeated changed-process notices.

## Extension integration checklist

Use the oldest supported and current VS Code. The numbering follows the original combined
checklist; local editor checks remain in the [main guide](../DEVELOPMENT.md#extension-tests).

8. Set/clear the pit URL and verify the second VS Code MCP definition, the user-scope Claude offer and copied command, and that no project registration or remote directory/key lease is written. Try the oldest supported and current VS Code.
9. Configure mirror scope/cap/events/serials, then pin/unpin, Sync Now and Open Mirror Folder. Observe remaining counts, synchronized state, offline age and a verification refusal. Confirm a leased mirror remains readable and the health endpoint answers during sync.
10. Open a pit log with a remote Windows path and one with spaces/Unicode. Follow plotted series and console across new values, duplicate timestamps, pause, hidden editor, and disconnect. Open the exact mirrored copy offline and verify the copy label. Rename a session at the origin and confirm the listing follows it.
11. Sync from another laptop twice: destination choice, host:port entry, remembered quick pick, progress and copied/present/conflict/refusal output. Mirrors must not appear as destinations or organizer offers.

## Additional integration checks

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
