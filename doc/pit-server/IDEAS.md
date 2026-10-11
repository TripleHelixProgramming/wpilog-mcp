# Pit server: Ideas

> **Experimental:** the pit server has not had real-world testing. These documents are
> retained for development and controlled shop evaluation, not as a recommendation for team use.
> Automated tests and simulation do not establish reliability on a real robot.

## 9. The Pit Server

### 9.1 Live Capture, Gateway, and the Long-Term Record
Priority: High. Complexity: High.

Milestones 1 through 8 are implemented: capture and its live index, identity/pulling, import,
live tools, store sync/mirroring, metrics and the optional read-only NT4 gateway. The shop harness
now checks a separate ntcore client's gateway observations against its timeline. Real dashboards,
AdvantageScope and roboRIO shop behavior remain manual checks.
[PIT_SERVER_PLAN.md](PIT_SERVER_PLAN.md#15-milestones) records the remaining work.

A daemon in the shop and the pit that listens to the robot and keeps what it hears. It:

- subscribes once to the robot's NetworkTables and records every change of every topic as a `.wpilog` capture per robot boot;
- re-publishes the stream as a read-only NetworkTables gateway, so the robot has one client;
- pulls the robot's own log files and the roboRIO's system logs whenever the robot sits disabled;
- follows configured files, such as the program's console, into the capture as they are written;
- answers the existing tools and a few live ones over the HTTP MCP transport, on the team's private network;
- records vision coprocessor settings, the roboRIO's system stats, and the robot program's garbage collection and profile beside the data;
- serves its latest values for Prometheus and Grafana;
- maps every session and pulled file to a robot by the roboRIO's serial number;
- keeps all of it in a store it owns, by robot and session, that files enter only by capture, pull, or import.

The extension keeps a synchronized mirror of the sessions a laptop wants, so analysis continues
offline from the same files. The process that writes a capture is the process that answers
questions about it, so an open session is served from the index and values the writer builds as
it writes, never by reading the file back. [PIT_SERVER_PLAN.md](PIT_SERVER_PLAN.md) is the
proposal and the specification, with milestones.

The store's HTTP door and laptop-to-laptop peer sync now exist (milestone 6, first half):
catalog reads, Range and prefix hashes, content-checked resume, overlapping same-serial session
union, provenance, human conflicts and remembered peers. Both daemon jobs and the offline
command own the local store lock. The second half now adds the local server’s scoped mirror,
pins/cap, growing capture prefixes, recorded alignment, offline age and id-based moves; the
extension registers the pit server, follows remote logs, selects mirrored copies offline, and
offers mirror and remembered peer-sync controls. Real VS Code verification remains the manual
checklist in DEVELOPMENT.md.

### 9.4 Shop Harness

Step 1 is implemented: a scripted WPILib 2026 headless robot, real SSH/SFTP from a synthetic
roboRIO, and a packaged pit server checked over HTTP MCP. Timelines pin capture fidelity,
identity, boots, match renames, pulling and the disabled gate. It runs separately from the
ordinary suite; see [DEVELOPMENT.md](DEVELOPMENT.md#the-shop-harness).

Step 2 is implemented: the synthetic NI-like OpenSSH/JRE container exercises real stats/tail
commands, system-file pulls, robot-facts and bounded JVM uptime pairing. The SHA-256-pinned
PhotonVision v2026.3.4 Linux release exercises its real private HTTP/WebSocket routes, a
generated file camera and captured settings through `analyze_vision`. One runner selects
available backends and reports skips; macOS still runs the MINA timeline.

These declared environments establish no actual NI permissions, coprocessor calibration,
installed release, radio behavior or robot-side cost. Those remain the shop test, as does
compatibility with another PhotonVision release.

Real-log replay extends step 1: the independent reader feeds the loopback gateway on every
platform, and the separate robot can publish the same file through native ntcore. Generated
fixtures exercise both paths in CI. A local `conformanceLogDir` enables stratified record
comparison by default, with `conformanceSample=full` for releases and for changes to the
recording or matching path.

One shared selector covers logger kinds, file sizes and the largest file, REV companions,
damaged tails, calendar evidence and a two-boot pair; the runtime report explains every choice.
Both modes check metadata, cost accounting, signed offsets, pull-placement refusals, clock
resets, and REV companion comparisons. Reports stay under `build/`; logs and telemetry stay
outside git.

The source robot clock is preserved, while an injected capture calendar clock keeps the overlap
filter meaningful. A log ending mid-record remains unverified by the puller; replay compares
its complete records and reports that limitation. See the replay commands in the development
guide.

## Implementation record

The pit import milestone is complete: existing command and inbox grouping now have bulk USB
and capture-enabled coverage, and the extension can upload verified copies to the pit store.
The server accepts only file bytes at its network write surface; server-path operations remain
local. Capture-only session/latest/wait tools now exist, with recorder costs and matched imports.
The remaining §14 proxy login uses SecretStorage and local credential leases; its interactive
credential flows and hardware validation remain on the manual checklist.

The metrics milestone is complete: `/metrics` serves latest numeric topics with ages,
recorded struct fields, capture/pull counters and JVM MBeans. A Compose setup and starter
Grafana dashboard live in `doc/pit-server/metrics`. The optional gateway now serves dashboards through
the pit computer and reports its client count.
The capture remains the full record behind the sampled dashboard.

The roboRIO stats and followed-file providers are implemented on one shared SSH connection.
They are enabled with SSH configuration for shop testing, with cost reports and bounded tails;
measure the defaults and NI-image command support before recommending them to teams.
PhotonVision configuration capture and opt-in JMX polling are implemented. JVM entries use
NT4 receipt timestamps with monotonic uptime pairing, and `robot-facts` probes the deployed
runtime modules. Flight Recorder streaming waits for those shop facts and measured overhead.

System-log collection and `search_system_logs` now work on configured synthetic-tested sources:
dmesg, syslog rotations or the whole journal, NI files and JVM crash files. The mechanism
shares SSH, the pull gate, byte pacing and manifest placement; timestamp mapping requires
measured pairs. Collection remains opt-in with unverified image candidates until the shop
supplies the directory listings, command availability, permissions and program path.

System text now follows telemetry through the catalog-backed door, peer sync and mirrors, with
committed-prefix reads and hash verification. Shared syslog receipts live once in the robot's
index and are selected by written span; a mirror's offline search reads its own copy. The shop
still needs to confirm dmesg's bracketed seconds and journalctl's boot-id support.

## Shop evidence

The read-only `robot-facts` command now collects the NI-image shop checklist into a dated local
report; radio, load, rotation behavior and provider budgets still require the hardware
exercise.

## Integration verification record

Explorer verification: milestone 11 now drives real VS Code in CI at the oldest supported
version and current stable for activation, the shared server, fixture Logs listing, editor
opening and pit command arguments. Rendered plots, interactive credential/organizer/mirror
flows and agent discovery remain the manual/future automated coverage in DEVELOPMENT.md.

Round 12 adds the assistant's relative `last_seconds` scope and discoverable
`pit://session/current` resource; the open flag distinguishes a current capture from its
recorded siblings. Replay/verification cost now follows DEVELOPMENT's policy: Java owns clock
placement, native owns transport fidelity, and only release preparation runs the whole corpus.
