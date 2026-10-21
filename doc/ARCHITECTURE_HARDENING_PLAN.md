# Architecture hardening plan

Status: items 1 to 6 are accepted after the third run's phase A full build on 2026-10-20 (4,896
passed, none failed, 87 skipped); item 7 is being built in that run's phase B
([Third run](#third-run)). The rest of this paragraph is the record before the third run.
The first run paused at the maintainer's request after Phase 0 and item 1's import work.
Item 1's bounded logging decision now has an implementation and targeted verification.
Item 2's shared budget, capture floor and initial copy paths passed targeted verification.
Its remaining sync and pull copy ownership is implemented in the second run; targeted
recovery checks and all 39 behavioral plants pass. Full-build acceptance remains open. Item 8's principles,
artifact guard, decision index and rename pass targeted checks and behavioral plants.
Item 3's full synthetic soak, public health/restart targeted checks and 19 behavioral plants
passed. Item 6's optional UTC column, shared ambiguity refusal and exact valid inverse are
implemented; targeted checks and behavioral plants are complete, with full-build acceptance open. Item 4's integer
correction passes targeted checks and plants. Its settled angle presentation and full five-route
differential matrix are implemented; targeted checks and all 23 second-run behavioral plants pass,
with exact restoration and full-build acceptance open.
Item 5's discovery, catalog warnings and cache-version guard pass targeted checks and
plants, with synthetic before/after measurements. Scan persistence is implemented in the
second run: targeted checks and all 31 behavioral plants pass with exact restoration. Durable
publication-failure reporting has passed targeted verification and all 42 behavioral plants.
Item 7 now has a detailed proposed design and a new synthetic measurement; implementation
stops for approval of that design. The maintainer settled every one of
those original stops the same day ([Decisions for the maintainer](#decisions-for-the-maintainer)),
and subsequently settled the newly measured inverse-precision question in favor of exact
wall-clock inversion with only the final conversion to double rounded. The newly identified
item 5b protocol gap is also settled: add bounded remote replica status with an optional
session lookup. Capture startup may continue after publication-only failures once intact
local receipts are verified; corrupt or unsafe recovery stays fatal. The implementation preserves that narrow startup distinction, verified through actual CaptureService startup.
The second run has implemented and targeted-tested the logging, copy-ownership, item 6
clock, item 4 presentation, scan-index and publication-status decisions.
The second run's initial full build failed and its integration corrections pass targeted checks.
The approved second full build still failed on one daemon-restart timeout; the single isolated
retry passed. Extension, broker, native-sample and native-library checks passed. A passing
full build remains outstanding. Item 7 is a design approval stop.
The first run's end-of-run checks ran once each: the full build failed
on four outdated test expectations, which were corrected and pass in targeted runs, and the
other checks passed ([End-of-run verification](#end-of-run-verification)). No second full build
was run.
The weakness sections describe the reviewed starting point; implementation
notes record differences found in the code. Later items retain their measurement and decision gates.

The review of the [columnar analytics cache](COLUMNAR_STORE_PLAN.md) rejected that proposal
because it named no problem it would solve. The review still found real weaknesses in the
current design: an ordinary import can stop a recording, nothing notices a disk filling, and
several costs grow with the size of the store instead of the size of the change. This plan
takes them one at a time. Each item gives the weakness and its evidence, the change, the tests
that must fail on today's code, and the documents the change must keep true.

One rule sets the order, and item 8 adds it to the design principles: a recording comes first.
A slow analysis can be run again; a match that was not recorded is gone.

Code is cited by class and method rather than by line, since lines move. Each open choice is in
[Decisions for the maintainer](#decisions-for-the-maintainer) with a proposed answer, and an
implementation stops at a decision that has not been made.

## Summary

| Item | Weakness | Change | Phase |
|---|---|---|---|
| [1](#1-capture-waits-behind-imports) | Capture shares one store queue and one cross-process lock with imports. A long import, or an import in another process, can stop a recording for the rest of the robot's boot, and the `import` command does not see a managed server. | Stage imports outside the queue, run capture turns first, time every turn, retry a held lock within the admission deadline, and send the command's imports to a managed server. | 1 — import work and bounded logging implemented; targeted checks and behavioral plants complete; accepted by the third run's phase A full build |
| [2](#2-nothing-measures-free-disk-space) | Nothing measures free disk space. Every writer finds a full disk by failing. | One free-space budget per filesystem, a reserve only capture may use, a clean low-disk close and resume, and free space in `/health` and the metrics. | 1 — copy ownership implemented; targeted checks and 39 behavioral plants passed; accepted by the third run's phase A full build |
| [8](#8-the-rules-are-not-written-down) | The rules this plan follows were not design principles, native dependencies were unchecked, and decision locations were ambiguous. | Six principles, a build-time native-library guard, a checked decision index, and the pit Explorer plan rename. | 2 — targeted checks, plants and the native-library guard passed |
| [3](#3-one-process-records-and-analyzes) | One process records and analyzes, `/health` cannot see a stalled capture, and nothing restarts a hung server. | A soak test, capture progress in `/health`, and a restart after repeated failed probes. | 3 — soak, targeted checks, plants and harness passed; real systemd unverified; accepted by the third run's phase A full build |
| [6](#6-no-wall-clock-time-on-the-data-routes) | The data routes carry only the log's clock. | An opt-in UTC timestamp from the log's own wall-clock readings, with its basis. | 4 — UTC, ambiguity handling and exact inverse targeted checks complete; 37 of 38 plant attempts caught, all restored; accepted by the third run's phase A full build |
| [4](#4-three-routes-serve-reduced-samples) | Three routes serve reduced samples, with shared arithmetic but different bucket membership, admission, formats and unwritten roles. | A differential test, one membership rule, and a written role for each route. | 4 — integer correction, angle presentation and five-route matrix targeted checks complete; all 23 second-run plants caught and restored; accepted by the third run's phase A full build |
| [5](#5-costs-grow-with-the-whole-store) | Discovery, scanning and replica catalogs cost in proportion to everything the server can see. | Measure; then persist scan indexes by content, discover from manifests, and warn before the catalog cap. | 5 — synthetic measurement, discovery, capacity warning and guard targeted checks/plants complete; scan index targeted checks and 31 plants complete; publication causes targeted complete with 42 caught/restored plants; accepted by the third run's phase A full build, the scan index's setting awaiting the maintainer |
| [7](#7-questions-across-many-sessions-have-no-contract) | Questions across many sessions have no contract. | Write the contract, then measure, then build. | 5 — detailed design and synthetic measurement complete; design approved with amendments after the second run; the new tool is not yet built |

The order is 1, 2, 8, 3, 6, 4, 5, 7. Phases 1 through 3 have the dispositions above.
Phase 4 has implemented item 6's settled forward mapping and subsequent exact-inverse
decision, and item 4's integer correction, angle presentation and full differential matrix,
with targeted checks complete; the third run's phase A full build accepted them. Phase 5 retains its
decision gates and has measured the same synthetic
store before and after discovery changes.

`/data/entries` and `/read/v1` are new in 0.10.0. If 0.10.0 is to be released before phase 4,
settle item 4's boundary and route decisions first: after a release, changing either is a
breaking change.

## 1. Capture waits behind imports

### What is wrong

`LogStore` owns one single-thread executor, the store queue (its thread is `store-import`), and
every store mutation runs on it while holding the cross-process `store.lock`.

- `importPaths` and `importPrepared` reach `enqueue`, which runs a whole request in one turn.
  Each file is hashed (`StoreFiles.hash`), a duplicate's stored copy is hashed again,
  `ImportInspection.read` inspects each file and decodes REV logs, and placement hashes and
  inspects again as it copies or moves. The turn lasts as long as all of that takes for every
  file in the request, which for a laptop's season of logs can be longer than 30 seconds. This
  has not been measured; the first test below stalls an import on purpose.
- An upload's body is received outside the queue (`receiveUpload`), but `importUpload` then
  imports the staged file through `importPaths`, in one turn.
- A recording is admitted through `CaptureStore.createAsync`, which records its session and the
  robot's identity through `LogStore.recordingAsync`, on the same queue behind whatever is
  waiting. Rolling over to the next file writes directly and does not wait for a turn.

`CaptureWriter` gives a new recording 30 seconds to be admitted and holds at most 65,536 events
or 16 MiB meanwhile (`ADMISSION_LIMITS`). Past either limit, `failAdmission` stops recording
until the robot's clock changes, which in practice means until the robot reboots.
[Startup and the store](pit-server/ARCHITECTURE.md#startup-and-the-store) documents that
outcome for a store that cannot take a recording. Nothing documents that an ordinary import can
cause it, and [Placement](pit-server/ARCHITECTURE.md#placement) states the rule it breaks: queue
wait and filesystem latency have no general wall-clock bound, "so recording admission must not
join those operations". The NT4 listener does not join them. The admission does.

The cross-process lock fails sooner. `StoreLock.acquire` calls `tryLock` and refuses at once
with "Store lock is held by another import". A command-line `import` sends its request to a
running server only when that server has a PID record. A server the launcher started has one; a
managed server under systemd never does, because `DaemonManager.spawnDaemon` refuses to adopt
one. Run beside a managed pit server as the service's user, the way the systemd guide runs
`robot-facts`, the command prints "No running daemon; importing in this process under
store.lock" and holds the lock for its whole request. `CaptureWriter.completeAdmission` treats
the refusal like any other error: a recording that starts during that import stops at the first
attempt.

No test covers either case. `CaptureAdmissionQueueTest.initialAnnounceAndValuesDoNotJoinARealImport`
stalls an import's inspection for 750 ms from its progress callback and proves that the NT4
listener does not wait for it. It does not prove that a recording survives an import longer
than the admission deadline, no test holds `store.lock` from another channel during admission,
and no test runs the `import` command beside a managed server.

Pulls already work the way imports should. Decoding, the inspection hash and correlation run on
the pull worker, outside the queue and the lock. The queued turn that follows rechecks file
identity, size and modification time and the matching candidates, and does not repeat payload
scans, hashes or correlation under the lock; moves drain readers within a three-second budget
([Placement](pit-server/ARCHITECTURE.md#placement)).

### The change

1. **Imports stage outside the queue.** A staging worker hashes and inspects each file outside
   the queue and the lock, under a read lease as pulls do, and copies it into staging when the
   import copies. A short turn for each file then rechecks what staging relied on (size,
   modification time and the manifest's duplicates) and renames or records it. A file that
   changed after staging is staged again or refused with the reason, never placed on facts that
   no longer hold. What imports guarantee today stays: the inbox's stability rule; a REV log
   assigned only with a robot and session time or a unique correlated wpilog; a mirror's refusal
   of imports and assignments; and a file imported by moving (an inbox file, or an upload's
   staged body) moved only after its turn's recheck.
2. **Capture goes first.** One owner thread drains a capture lane before the import lane, so
   store mutations keep a single order. A capture still waits for the turn in progress, which
   change 1 makes short.
3. **Every turn is timed from outside the queue**, as `LoopWatchdog` times the NT4 loop and for
   the reason [the loop rule](pit-server/ARCHITECTURE.md#relative-scopes-and-discoverable-live-state)
   gives: a check scheduled on a stalled loop cannot see the stall. A turn over its budget
   (proposed: one second, the watchdog's `STALL_US`) is logged once with its kind and duration
   and counted in a metric. The budget is a stated expectation that tests check; it does not
   interrupt a turn.
4. **Capture retries a held lock** with backoff until its admission deadline. Imports keep
   failing at once: a person is waiting and can try again.
5. **Shutdown stays bounded.** Transport shutdown waits only for short turns. The staging worker
   stops at shutdown, and what becomes of a file it staged but did not place (removed at the next
   start, or kept for retry as a pull keeps a staged file) is stated and tested.
6. **The command finds a managed server.** Before importing in its own process, `import` probes
   the configured port as `DaemonManager.probe` does, and sends its request to a server of that
   name that answers, managed or not. It imports in its own process only when no server answers.

Later, under a design of its own: write a capture first into its own directory inside the
store, and move it into its session at close, so that starting a recording needs no queue turn
at all. Resume and rollover are the hard parts, and
[Open captures and renames](pit-server/ARCHITECTURE.md#open-captures-and-renames) already defers
cosmetic moves to close.

### Tests that must fail first

`CaptureWriter` has a package-private constructor that takes `AdmissionLimits`, so a test in the
`capture` package can set a short deadline; `LogStore.importPaths` and `LogStore.captures` are
public.

- With a 200 ms admission deadline, stall an import's inspection past the deadline from its
  progress callback, start a capture and send values. The capture is admitted, its file holds
  every value sent, and the import completes. Today recording stops with "Capture admission
  exceeded 0.2 seconds".
- Hold `store.lock` from a second `FileChannel` for less than the deadline, then release it. The
  capture is admitted after the release. Today it stops at once with "Store lock is held by
  another import".
- Queue a capture while an import of many files is staging. The capture's turn starts within the
  turn budget, as the turn timer measures it.
- Change a file between its staging and its turn. It is not placed on the stale facts.
- With a managed server answering on the configured port and no PID record, `import` sends its
  request to that server. Today it prints "No running daemon; importing in this process under
  store.lock".

Plant the absence of each change (hashing back inside the turn, no capture lane, no retry, no
probe) and see the matching test fail.

### Documents

[Startup and the store](pit-server/ARCHITECTURE.md#startup-and-the-store),
[Placement](pit-server/ARCHITECTURE.md#placement), [Concurrency](ARCHITECTURE.md#concurrency),
the `import` command's "With the daemon running" and "Without a daemon" paragraphs in
[STANDALONE.md](STANDALONE.md), the import and capture steps in [OPERATIONS.md](OPERATIONS.md),
the new metric in
[Metrics and a starter dashboard](pit-server/STANDALONE.md#metrics-and-a-starter-dashboard), and
`CHANGELOG.md`. Store import and capture are new in 0.10.0, so the entry takes the changelog's
form for unreleased work ("The unreleased … now …").

### Implementation disposition — 2026-10-20

The six import changes have implementations, and their targeted tests and behavioral plants pass.
The second run also implements the bounded logging decision below. The 500-file
synthetic measurement imported every file: total caller time increased from 995.357 ms to
7,637.042 ms; the new independent observer measured a longest store turn of 22.452 ms and
zero over-budget turns. The baseline had no independent turn counter. Shorter owner turns do
not imply faster whole-batch imports; this measurement makes no season-sized throughput claim.
The full synthetic soak passed under item 3.

The first run's isolated regression test reproduced that `StoreQueue` ends its action timer before calling
the log sink from the owner: while the callback was held, the next capture failed to complete.
This delay did not extend the reported action duration. The outside observer could also block
in that sink. The counter and maximum describe timed actions, not a bound on the whole
owner cycle. An arbitrarily blocked sink, one retained notice for every over-budget turn,
bounded memory and uninterrupted capture cannot all be guaranteed without a policy for losing
or coalescing notices, or refusing further work. The maintainer chose one on 2026-10-20: all server log output goes through a bounded in-memory
buffer written by its own thread, and lines that do not fit are dropped, counted and reported.
The second run implements this with one 1,048,576-byte ring and one daemon output owner.
Incomplete, queued and in-flight bytes share the allowance; overflow discards whole lines.
One ordered loss notice gives line and byte counts, and a process metric retains lost lines.
Installation precedes startup logging; existing MCP stdout and direct short-lived CLI errors
keep their owners. Cleanup precedes a drain bounded to two seconds. Fresh-JVM regressions
hold actual stderr and check both store capture turns and later listener values through
CaptureWriter's conditional close report. That report requires a pending force past its
reporting deadline; an ordinary early close need not log. The maintainer approved using that
existing path. The earlier successful soak did not exercise a blocked logging sink.

Code inspection refined three details. The off-owner pull preparation example is `PullStore`;
`MainImportTest` already covered offline CLI imports, but not a managed server without a PID
record. Also, `awaitImports` must retain its existing full-completion meaning for CLI callers
and tests; transport shutdown uses the new bounded `awaitMutations` barrier. WPILOG files and
proven REV companions still publish as one atomic group. Specific non-loopback bindings refuse
path import before writing, because a failed loopback probe cannot establish that the server
is absent; the refusal gives the supported byte-upload or bind-configuration remedy.

## 2. Nothing measures free disk space

### What is wrong

No server code asks how much space is free. `src/main/java` never calls `getUsableSpace`,
`getFreeSpace` or `getUnallocatedSpace`, and its `getFileStore` calls ask only which file
attributes a filesystem supports and whether two paths share one. Every writer finds a full
disk by failing:

- **Capture.** A write failure closes the session with its `end_reason` and suppresses recording
  until a new robot clock
  ([Sessions and capture files](pit-server/STANDALONE.md#sessions-and-capture-files)). A disk
  that fills during a match loses the rest of that boot.
- **Imports, uploads, pulls and mirror transfers** fail when a write fails, after their copies
  have used space that a recording needed.
- **Mirrors.** `MirrorCapacity` reserves bytes against the mirror's configured limit, not against
  the disk. Its status reports logical reservations, "not JVM heap, filesystem blocks or free
  disk space"
  ([Replica generation publication owner](ARCHITECTURE.md#replica-generation-publication-owner)).
- **Metrics.** They count capture, pull and provider bytes and JVM memory. The only free-space
  readings the server takes are the robot's, through its SSH statistics
  ([Relative scopes and discoverable live state](pit-server/ARCHITECTURE.md#relative-scopes-and-discoverable-live-state)).

### The change

1. **One budget per filesystem.** Free space belongs to a filesystem, not to a store: two stores
   on one disk share it. A budget for each filesystem that holds a store (`Files.getFileStore`)
   starts from measured usable space and subtracts outstanding reservations. Every writer that
   copies a file reserves its size before writing: an import copy, an upload (its length is
   known before its body), a pull (the robot's listing gives the size) and a mirror transfer,
   beside its `MirrorCapacity` reservation.
2. **A reserve for capture.** A writer other than capture refuses up front, naming the free
   space, the reserve and its own size, when its reservation would cut into a configured capture
   reserve. Only capture may use the reserve.
3. **A floor.** Below a hard floor, capture closes its file cleanly with a low-disk `end_reason`
   and keeps the NT4 connection. It opens a new file when free space is back above the floor by
   a stated margin, and the manifest records the gap. Unlike a write failure, running low is not
   a reason to wait for a new robot clock.
4. **Visible.** `/health` and the metrics report free bytes, reserved bytes and the reserve for
   each store's filesystem, so a person or a dashboard sees the floor coming.

### Tests that must fail first

Free space is read through an injectable source, so tests set it. `CaptureWriter` already takes
an `OutputFactory` that fails writes without filling a test disk.

- An import copy whose size would cut into the reserve is refused before any byte is copied, and
  the refusal names the three numbers. Today the copy starts.
- At the floor, capture closes with the low-disk reason. When the injected free space rises, a
  new file opens, and the two files together hold every value sent outside the gap. Today
  nothing reacts to the reading.
- Two stores on one filesystem share one budget: a reservation for one reduces what the other
  may use.
- `/health` and the metrics carry the new fields, checked against the injected numbers.

### Documents

[Sessions and capture files](pit-server/STANDALONE.md#sessions-and-capture-files) for the
low-disk end reason and resume; the configuration references in [STANDALONE.md](STANDALONE.md)
and [pit-server/STANDALONE.md](pit-server/STANDALONE.md) for the reserve and floor; the metric
list in [Metrics and a starter dashboard](pit-server/STANDALONE.md#metrics-and-a-starter-dashboard);
what a person sees when the disk runs low in [OPERATIONS.md](OPERATIONS.md); and `CHANGELOG.md`.

### Implementation disposition — 2026-10-20

The shared filesystem owner, import/upload/replica admission, known-length pull staging,
clean capture pause/resume, byte configuration and published health/metrics are implemented.
Tests use injected byte counts, actual copying adapters and blocked I/O; reservations remain
charged after a response stops waiting, until the admitted write unwinds. Growing local sources
cannot copy beyond the size admitted from their inspected snapshot.

The first run stopped at copy paths whose transaction ownership contradicted that model:
`StoreSync` and `SystemPeerSync` ran under the store owner and cross-process lock, system-text
publication made additional copies there, and pull placement could copy across filesystems.
The maintainer settled their ownership on 2026-10-20. The second run implements that decision:
`LogStore.sync` uses its daemon staging executor, and the permanent `.sync/.owner.lock`
serializes the journal across processes without holding `store.lock` during downloads,
pacing, inspection or hashing. Short publication turns recheck store identity/role, selected
session, duplicates and prepared bytes. `SystemPeerSync`, `SystemPullStore` and cross-filesystem
`PullStore` placement also prepare and reserve their additional destination copies off the
owner, then atomically rename and record them in a turn. Every reservation stays owned until
the I/O or admitted publication using it has unwound.

The maintainer clarified recovery during this run: preserve journal-owned incomplete downloads
and system-text prefixes for proved resume, and recover at the next sync before contacting
the peer. Cleanup removes abandoned owned placement copies. After a cross-filesystem telemetry
copy is committed, destination and source proof also permit removal of its original staging
bytes, including recovery from an already committed receipt; a changed original remains with
an explained refusal. Resumed bytes still require peer prefix proof.
Peer placement and session-merge receipts retain their recovery role. Robot pull placement
has no such receipt and keeps its existing alias/retry contract. Copied originals are removed
only after commit and revalidation. New telemetry and system-text receipts retain their
selected identities across process death; legacy receipts remain readable. Targeted recovery,
200 ms capture-admission, reservation-lifetime and documentation checks pass, and all 39
behavioral plants were caught and restored. The third run's phase A full build accepted it. Unknown-length command
output remains outside the copy-admission change.

Every registered store begins with a standing 2 GiB reserve. Its first explicit capture policy
replaces that default; multiple configured policies retain their maximum until registry restart.
The filesystem takes the maximum across its stores. Accounting is process-local and conservative,
not a filesystem quota or a bound on other processes. Capture retains its prior state when
space is unknown; known space at or below the floor pauses it, and only space strictly above
the floor plus margin resumes it.

## 3. One process records and analyzes

### What is wrong

The pit server records and answers analysis in one JVM. Much already keeps the two apart: the
NT4 listener writes each capture itself and never waits for manifest work, forcing to disk runs
on its own thread, logs unload under memory pressure, and read routes use bounded workers.
Nothing measures whether that is enough, and nothing notices when it is not.

- `LiveReplayTest.concurrentLiveCallsDuringReplay`, run by the stress tasks, replays each
  fixture into a capture while live tools are called. No test records at a sustained rate for
  minutes while imports and heavy reads run, and none bounds how long the listener loop stalls
  meanwhile.
- `/health` reports `status`, `managed`, `sessions`, `version`, `name`, `pid`, the server's
  identity and the gateway's state. Its `status` is always `ok`, including while a capture has
  stopped or is waiting for admission.
- The printed systemd unit restarts the server when it exits (`Restart=always`). The health
  probe runs every 30 seconds but has no `OnFailure=`, so a server that runs but no longer
  answers is never restarted ([Running under systemd](pit-server/STANDALONE.md#running-under-systemd)).

### The change

1. **A soak test**, opt-in like the stress tests: record a synthetic NT4 stream at a stated rate
   (100 topics at 50 Hz, the rate the gateway's item bound is sized for) for ten minutes,
   while a large import runs and clients make heavy reads through
   MCP and `/read/v1`. It asserts no admission failure, every value recorded (counted and
   checked against the generator), no listener-loop stall over `LoopWatchdog`'s one-second
   threshold, periodic forces within a stated bound, store-queue turns within item 1's budget,
   and heap under its maximum. It records the measured numbers.
2. **Capture progress in `/health`**: a `capture` object beside `gateway`, as the gateway's
   state is published there, with each capture's state (waiting for admission, recording or
   stopped, with the reason), the age of its last write, and the longest listener-loop stall
   and store-queue turn since the last probe. `/health` stays a short answer and its `status`
   stays `ok` while the process answers, so a slow import never causes a restart.
3. **A restart after repeated failures**: the probe tries three times, ten seconds apart (its
   start timeout raised to fit), and `OnFailure=` restarts the server only when all three fail.
   A restart ends a recording, so it is the last resort for a process that no longer answers.
   `WatchdogSec=` was considered: its `sd_notify` messages need a Unix datagram socket, which
   Java 17 cannot open, and a watchdog fed from inside the process can go on being fed while the
   HTTP side hangs. The probe tests what a client sees.
4. **A separate recorder process only if the soak test shows that one process cannot meet its
   bounds.** It would cost a protocol between the two processes, a second supervised unit and a
   second place for configuration, and the evidence should demand that first.

### Tests that must fail first

- `/health` carries the `capture` object with the states above. Today it does not.
- `ServiceUnitTest`: the printed probe retries and its `OnFailure=` names a restart unit. Today
  it has neither.
- The soak test is a measurement, not a regression test. Run it on today's code first and record
  the baseline; with an import running, it should show item 1's failure.

### Documents

[Running as a service](STANDALONE.md#running-as-a-service),
[Running under systemd](pit-server/STANDALONE.md#running-under-systemd), the `/health`
description in [OPERATIONS.md](OPERATIONS.md), the new suite in [DEVELOPMENT.md](DEVELOPMENT.md),
and `CHANGELOG.md`.

### Implementation disposition — 2026-10-20

The ten-minute synthetic soak passed. Public capture progress and the printed restart units
passed targeted checks and 19 behavioral plants, each restored to its original hash. Four
existing-API regressions and seven new-API scaffold tests failed first; five further tests
caught interval publication races and a force-only close incorrectly resetting write age.
The combined integration check passed 38 cases, and the final interval/age check passed 12.
Real systemd execution remains outstanding.

| Measurement | Before: shorter 120-second baseline | After: full 600-second soak |
|---|---:|---:|
| Generated / received values | 600,000 / 600,000 | 3,000,000 / 3,000,000 |
| Recorded and independently checked values | 0 | 3,000,000 |
| Longest listener-loop stall | 4.615 ms | 182.155 ms |
| Longest store action / over-budget count | Not instrumented | 32.173 ms / 0 |
| Sampled heap maximum / configured maximum | 373,165,024 / 805,306,368 bytes | 756,624,240 / 805,306,368 bytes |
| Completed forces | 0 | 2,384 |
| Longest force / completion gap | Unavailable | 22.634 ms / 409.010 ms |
| 128-file import caller time | 35.267 s | 36.945 s |
| MCP calls / exact-read calls | 32,385 / 1,641 | 115,544 / 6,270 |

The baseline exhausted capture's admission buffer while an import inspection callback held
for 35 seconds; no generated values reached a recording. In the full run the same import
stall did not block admission. Every one of the 100 topics held exactly 30,000 generator values,
and exact reads returned 313,500,000 samples without failure. Both listener and timed store
actions stayed below one second; force duration and completion gaps stayed below the stated
two-second bound. The full JUnit invocation took 603.368 seconds.

These are synthetic measurements on one machine, not a throughput or deployment guarantee.
The harness uses the real NT4 listener, writer, store and HTTP routes; separate tests cover
the capture-service lifecycle and disk monitor. Heap is sampled JVM heap, not total process
memory or an exact peak; the store measurement preceded item 1's logging fix and did not hold stderr. No real
robot, NT4 replay, Linux systemd restart or Windows run is represented by these numbers.

## 4. Three routes serve reduced samples

### Implementation disposition, 2026-10-20

The separable integer-membership and ordering correction is implemented; targeted checks and
eleven behavioral plants passed. An initial projection plant changed the wildcard branch while
the fixture selected one array element, so that run passed and is recorded as a missed plant.
The corrected non-wildcard plant failed the independent expected counts and was restored. Four failing-first tests reproduced a one-microsecond sample
entering the preceding bucket, reversed adjacent timestamps collapsing to the same double,
the resulting eager/lazy system-time synchronization difference, and a still-readable format 12
cache. Original coordinates now survive numeric projection and legacy materialization; the
synchronization cache is format 13. A separate mixed signed-zero comparator test also failed first.
Route roles retain Explorer decision 7: presentation through `/data/entries`, exact typed clients
through `/read/v1`, and preview-specific visualization routes.

The required full build ran once: 4,551 passed, four failed and 86 skipped in 116.899 seconds.
Its failures exposed stale checks: the fixture differential oracle still assigned buckets in
double seconds, two legacy-reader tests still required file order for distinct microseconds
that share a double, and a transfer claim test pinned cache format 12. A separate WPILOG-byte
parser recomputed every one of the differential test's 45 reported discrepancies with integer
arithmetic; all reported server values matched that independent result. For example,
33,160,000 us belongs to bin 7 of the 6,000,000–200,000,000 us window with fifty bins, while
the old double quotient was 6.999999999999999 and assigned bin 6.

Six test files were corrected: the independent reader now retains raw microseconds, its bucket
oracle uses independent rational arithmetic and integer order, its prefix carrier copies those
coordinates, the two legacy tests assert the agreed integer order, and the format pin is 13.
Other statistics and Driver Station checks retain their prior semantics. The targeted correction
run passed all 51 tests in 7.969 seconds. Three additional plants restored double binning,
discarded legacy timestamp provenance and restored cache format 12; they failed the matching
checks and were restored to their exact hashes. These bring item 4 to eleven caught plants,
with the earlier wildcard-branch miss preserved. No second full build was run, so the targeted
corrections do not constitute a passing full-build result.

The first run stopped full fixture/window/budget equivalence because its starting premise omitted four existing
contracts: sparse legacy reads bypass reduction; charts use occupied prepared-part extents;
charts filter nonfinite values; and charts unwrap declared angles. For example, `[1,3]` at one
instant with a two-point budget is two exact legacy rows but one native bucket with mean 2.
For samples `[2,3,4,5]` inside a requested `[0,10]` window, two raw-read buckets have counts 3/1
and means 3/5, while chart buckets over occupied `[2,5]` have counts 2/2 and means 2.5/4.5.
A one-bucket `[1,NaN,3]` chart counts 2 rather than 3; an angle `[179,-179]` is unwrapped to
`[179,181]` for chart mean 180, versus raw mean 0. Those preparation policies remain unchanged.
The two boundary/order fixtures establish the independent correction, not a full-corpus
cross-route equivalence sweep. The maintainer settled the contracts on 2026-10-20: the
presentation routes unwrap declared angles as charts do, `/read/v1` stays raw, and the other
three differences stay as the differential test's named exceptions.

The second run implements that decision with one shared angle-unwrapping owner. Declared
scalar angle presentation applies before exact output and reduction; explicit units are available
on read_entry and the data endpoint. Preview unwraps after the common-clock member merge,
retaining one bounded state across input batches. Native reads remain raw. The synthetic
five-route matrix checks thirteen fixture/window shapes at three point budgets, including
integer boundaries, adjacent timestamps sharing one double, nested angle fields, nonfinite
samples and empty intervals. Its four permitted differences are explicit and every available
statistic is checked against independent literals and integer inequalities. The old-code run
had 35 passing controls and 41 expected failures in 16.860 seconds. Implementation checks
passed 84 cases in 23.045 seconds, with the one expected missing-angle documentation claim
then corrected. Existing-path integration passed 200 cases in 10.027 seconds, with two
expected missing-coverage claims then corrected. The final coverage, conformance and
documentation run passed all 14 cases in 14.472 seconds. All 23 second-run behavioral plants
were caught and restored to identical hashes. These targeted results complete item 4's
second-run checkpoint; the third run's phase A full build accepted it.

### What is wrong

Three HTTP routes and two tools return samples reduced to a number of points:

| Route | Reduction | Bucket membership | Formats |
|---|---|---|---|
| `GET /data/entries`, and the `read_entry` and `render_chart` tools | `tools/Buckets` | `floor((t - start) / bucket_sec)` in double-precision seconds, over the first to the last sample unless a window is given | Arrow, CSV |
| `/read/v1` snapshot queries | `read/ReducedCursor` | `floor((t - start) * points / span)` in exact integer microseconds, over explicit windows with inclusive or exclusive ends | NDJSON, Arrow |
| The visualization preview (`/visualizations/v1` and `v2`) | `visualization/SourceReduction`, through `SampleCursors.reduce` to `ReducedCursor` | As `/read/v1` | JSON |

The arithmetic is shared: `NumericBucket` computes every bucket's count, minimum, maximum,
mean, first and last value. Membership is not, and
[Shared read API](ARCHITECTURE.md#shared-read-api) says so on purpose: "Native buckets use exact
integer boundaries while legacy tools retain their double-second results", and the legacy view
keeps adjacent timestamps that round to the same double in file order, where native cursors
order them by microsecond. The legacy path keeps its results so that existing answers do not
change. Three things follow:

- **Two answers.** A sample exactly on a bucket boundary can fall into the earlier bucket when
  its offset is divided in double-precision seconds, and adjacent large timestamps can change a
  bucket's `first` and `last`. A plot in Explorer, which reads `/data/entries`, and the same
  window read through the Java library can disagree at bucket edges. No test states where the
  two paths may differ, so a third difference would pass unnoticed.
- **Admission and side effects.** `/read/v1` reads a store only through a configured store
  catalog, where a session-leased directory is enough for `/data/entries`. Its snapshot opens
  start no REV discovery, correlation or disk-cache writes; the acquisition behind
  `/data/entries` still starts them ([HTTP version 1](READ_API.md#http-version-1)).
- **Roles.** The Explorer plan's decision 7 makes `/data/entries` bulk data's own door: "The
  viewer, an agent's script, and a dashboard read the same door"
  ([decisions](EXPLORER_PLAN.md#1-decisions)). The read API is "for applications that need log
  data without MCP analysis or an installed server", and says `/data/entries` keeps its own
  formats. Neither document says which door a script or a dashboard should use, so each new
  format or client is a question of where to build it, and the rejected plan proposed a fourth
  route, `/analytics/v1/query`.

### The change

1. **A differential test** that states where the paths may differ. For every fixture and a set
   of windows and point budgets, every path puts every sample in the same bucket and reports
   the same count, minimum, maximum, mean, first and last, except in the documented cases.
   Before writing the exceptions, find a boundary sample that the double-second division
   misplaces and a pair of adjacent timestamps that round to one double, so the documented cases
   are shown, not assumed.
2. **One membership rule, if the maintainer agrees**: exact integer microseconds and microsecond
   order everywhere. `/data/entries` is new in 0.10.0 and has no released clients, so changing
   it now costs nothing. The `read_entry` and `render_chart` buckets are released, so changing
   them is a `### Changed` entry that moves samples at bucket edges. The proposal changes all
   three before 0.10.0 is released, and the differential test's exceptions become empty.
3. **A written role for each route** (proposed: keep decision 7). `/data/entries` stays the door
   for viewers, agents' scripts and dashboards, and new presentation formats, such as the Grafana
   `json` format and item 6's UTC timestamps, are added there. `/read/v1` is the exact, typed
   protocol for the Java library and other applications: snapshots, sessions, follows and typed
   values. The visualization routes stay the preview's own protocol. If the maintainer instead
   makes `/read/v1` the one door, decision 7 is superseded in writing, and `/read/v1` must first
   admit what `/data/entries` admits today, stores in session-leased directories included.

### Tests that must fail first

- With one membership rule: the boundary sample and the adjacent pair, which today land
  differently on `/data/entries` and the tools than on `/read/v1`.
- Without it: the differential test with its exceptions is a guard. Plant a third difference
  (for example, the legacy window's end made exclusive) and see it fail.
- If roles are enforced (for example, a presentation format refused on `/read/v1`), a test for
  each refusal.

### Documents

[Shared read API](ARCHITECTURE.md#shared-read-api), [The Data Endpoint](STANDALONE.md#the-data-endpoint),
[HTTP version 1](READ_API.md#http-version-1), the bucket wording for `read_entry` and
`render_chart` in [TOOLS.md](TOOLS.md), [the Explorer plan's decisions](EXPLORER_PLAN.md#1-decisions)
if decision 7 changes, the extension's README if Explorer's plotted numbers move, and
`CHANGELOG.md`.

## 5. Costs grow with the whole store

### What is wrong

Several costs grow with everything the server can see, not with what changed:

- **Discovery.** Each request that names a source scope, or gives a relative path without one,
  walks every configured root once to find stores
  ([Explicit source selection](ARCHITECTURE.md#explicit-source-selection)). After the fix of
  2026-10-19 it is one walk per request rather than four. With a warm filesystem cache it
  measured about 8 ms beside 2,000 unrelated files and 56 ms beside 20,000. A dashboard whose
  panels name a scope pays it once per panel on every refresh.
- **Scanning.** Opening a log scans it once to index it, and nothing keeps the index, so a
  question across a season scans every file again on every run. The older disk cache of parsed
  logs (`cache/DiskCache`) is still constructed, configured, cleaned at startup and reported by
  `health_check` as `parsed_log_disk_cache`, but nothing reads or writes it
  ([Caching](ARCHITECTURE.md#caching)).
- **Replica catalogs.** A catalog holds at most 4,096 replica records and 4,096 exclusions, in at
  most 128 parts and 32 MiB
  ([Replica generation publication owner](ARCHITECTURE.md#replica-generation-publication-owner)).
  A store that records several robots for a few seasons can approach that. When publication
  fails, recording continues (`recordingAsync` records the failure), but what a mirror then sees
  has not been tested.
- **The synchronization cache's version.** REV synchronization results are cached by content
  and checked against `SyncCacheSerializer.CURRENT_FORMAT_VERSION`. Raising it when REV parsing,
  decoding or synchronization changes is a rule nothing checks.

### The change

Measure first: discovery per request, open and scan time per file, and catalog size, on a
season-sized store, with item 7's workload. The source plan's M9 scale gates
([implementation checkpoints](SOURCE_AND_MIRROR_PLAN.md#implementation-checkpoints)) already call
for season-sized measurement; use the same store.

1. **Persist scan indexes, if scanning dominates.** Key each file's index by content: the hash a
   store manifest already records, or, for a loose file, its size, modification time and file
   key, verified on load, together with the index format version. An index whose file changed is
   never used. The persistent index replaces `cache/DiskCache`, takes over `diskcachedir` and
   `diskcachesize`, and `health_check` reports it in place of `parsed_log_disk_cache`. If
   scanning does not dominate, remove `cache/DiskCache` alone.
2. **Discover from what is known.** Store manifests name their files. A cached listing of each
   directory is rechecked by the directory's modification time, and a directory modified within
   the timestamp resolution of its last listing is listed again. A listing older than a stated
   age (proposed: one minute) is refreshed regardless, for network filesystems that report
   times late. The guarantees of the 2026-10-19 fix stay:
   `SourceScopesTest.aNewNestedStoreImmediatelyChangesTheChoiceSet`, containment before and after
   aliases, a vanished store never identified again as a plain directory, and no hash
   substitution.
3. **Replica catalogs.** First a test of what happens at the cap: publication refused with a
   reason, recording continuing, and mirror status saying why. Then a warning in status and the
   metrics at 80 percent of either cap. Partition catalogs by robot or season only if real
   stores approach the cap.
4. **A guard for the synchronization cache's version.** A test records a digest of the sources
   that shape synchronization results, with the format version it was taken at. Changing those
   sources fails the test until the developer raises the version or records that cached results
   are unaffected.

### Tests that must fail first

- Discovery: a counting filesystem probe shows directory reads growing with the tree today.
  After the change, a request after no change reads no listings, and a new nested store still
  appears at the next request.
- Index: a second open of an unchanged file does not scan it again, and an index for a changed
  file is not used.
- Replica catalog: the behavior at the cap, and the warning before it.
- Synchronization cache: plant a change to a REV source and see the guard fail.

### Documents

[Caching](ARCHITECTURE.md#caching), [Explicit source selection](ARCHITECTURE.md#explicit-source-selection),
[Replica generation publication owner](ARCHITECTURE.md#replica-generation-publication-owner), the
`diskcachedir` description in [STANDALONE.md](STANDALONE.md), `health_check` in
[TOOLS.md](TOOLS.md), and `CHANGELOG.md`.

### Item 5 implementation disposition — 2026-10-20

The before/after measurement used only a synthetic store: 50 sessions with 100 numeric topics at 50 Hz,
1,000 through 5,900 samples per topic, and 20,000 unrelated small text files in 100 folders.
It contains 17,250,250 data records and 254,771,450 payload bytes. Five discovery warm-ups
preceded twenty timed requests against the same prepared store on the same machine.

| Operation | Before | After |
|---|---:|---:|
| Discovery, mean per request | 69.880 ms | 1.954 ms |
| Discovery, median per request | 68.845 ms | 1.960 ms |
| Directory-listing opens per request | 103 | 0 |
| Traversals per request | 1 | 1 |
| Direct-reader open, mean per file | 0.487 ms | 0.494 ms |
| Index scan, mean per file | 18.099 ms | 19.756 ms |
| Shared session admission, mean per file | 12.513 ms | 12.061 ms |
| First catalog read | 62.531 ms | 66.308 ms |
| Rechecked catalog read | 1.254 ms | 1.342 ms |

These are separately measured operations, not additive stages of one request. OS cache state
was not controlled. The directory-open counter measures enumeration, not the metadata probes
that remain. The store header stayed at 327 bytes and replica metadata at 136,897 bytes for
fifty records. These synthetic numbers support neither a production performance claim nor a
decision about scan dominance.

Change 1 stops at that measurement: no real logs were supplied, so the agreed rule forbids
deciding whether scanning dominates from these numbers alone. The maintainer then decided on
2026-10-20 to build the persistent index without that measurement. The second run implements
it under the existing `DiskCache` cleanup/capacity owner alongside REV results; the two
configuration settings remain active. `health_check` replaces `parsed_log_disk_cache` with
`scan_index_disk_cache` (entries, bytes, hits, misses and recipe/format versions). The released
field change is documented as breaking. An eligible open verifies current whole-file content
under a read lease before using an index; known growing captures are excluded. Invalid or
unsupported indexes fall back to a scan. Targeted checks and all 31 behavioral plants passed,
with exact restoration; the third run's phase A full build accepted it.

The same 50-file synthetic workload measured mean first admission at 21.172 ms before and
44.348 ms after, and second admission after eviction at 14.906 ms before and 12.578 ms after.
First admission now includes content proof and index writing. Actual owner instrumentation
recorded 50 scans in each old-code admission pass, then 50 initial scans and 50 restored
indexes with zero scans in the new second pass. The 50 indexes total 69,311,150 bytes.
Separately measured direct scans averaged 13.342 ms before and 16.933 ms after; full-file
hashes averaged 2.768 ms and 3.392 ms. These are warm-cache synthetic observations on one
macOS machine, with uncontrolled OS cache state, not a production speed claim or additive
request stages.

Change 2 now reuses rechecked child-directory listings. A new store header in a known directory
still changes the choice set on the next request. Directory metadata is rechecked; recent
timestamps (two seconds), clock rollback, changed facts or a one-minute listing age cause a new
enumeration. Delayed metadata on a newly created subtree's parent can delay that subtree until
the maximum-age refresh. Selected-file ancestry checks still refuse changed ownership. Stable
listing I/O is avoided, but metadata work remains proportional to known directories. Cache and
pending traversal each account approximately 32 MiB; wide trees fall back to streaming discovery.
Those are accounting bounds, not measured heap. Iterator I/O failures close and discard partial
listings and preserve healthy neighboring sources. Counting, invalidation, containment and
failure-isolation checks and behavioral plants passed.

Change 3 adds count warnings to replica status and metrics at 3,277 records or exclusions
(80 percent of each 4,096 limit). The owner publishes immutable capacity facts, including
replica turns with ordinary observers suppressed; stale off-owner notifications cannot replace
them. Invalid or unavailable initial facts are omitted. Targeted discovery, warning and publication
checks passed, with six discovery plants and ten capacity plants caught and restored. An initial
bounded-wait plant did not recreate a queue join and passed; the corrected real-join plant failed.
Capacity refusals classify all five encoded-metadata limits and the count-limit preflight so an
import removes its owned, uncommitted stage without deleting its source or committed bytes.
Existing tests already proved bounded
catalog refusal and capture continuation, so those controls were not claimed as new failing-first
tests. The second run now adds durable, sanitized causes and first/latest failure times to
per-session pending markers, retaining old-reader compatibility. A bounded store-owner
snapshot is rebuilt off the queue at registration/startup and updated in store order on live
failures, retries and successful publication. Incomplete recovery preserves known entries
without inventing a total; status and metrics never start a walk or join the store queue.

The source audit found that capacity status was local and unpublished files had no replica
identity. The maintainer approved bounded `POST /replicas/v1/status` with required `store_id`
and optional `session_id`, keeping immutable `/file` rules unchanged. It now exposes true
totals, safe causes and an explained, retryable `PUBLICATION_PENDING` refusal. Handshake-pinned
roots and fresh access/header/owner/session checks prevent stale replies or copy substitution.
Replies are shortened by actual escaped UTF-8 bytes as well as the requested row limit.

The audit also found that actual CaptureService startup treated publication failure as fatal,
unlike the old manual-writer control. The approved implementation tolerates only the typed
publication-only failure after a complete recovery of safe local receipts. Corrupt, unsafe,
missing or incomplete recovery still fails startup. New targeted tests caught and corrected
initialization races, lost nondurable failure time, fractional timestamp ordering and false
completeness after failed header/receipt inspection. All 42 owner, startup, metric, HTTP and traversal-bound mutation plants were caught and
exactly restored, including an independently counted actual directory tree. The final restored-source owner, documentation and compatibility-guard check passed all 72
cases, with no failures or skips. See the second-run end-gate disposition below.

The third run made registration's publication step lock-free in a folder that holds no store:
it records that there is no store, and still reports a store this process knew about that has
vanished, without creating `store.lock`. A folder that holds a store keeps the locked step and
its refusal of a symbolic-link lock. A new test registers an empty folder and finds it still
empty. The symbolic-link admission test, which the second run's build skipped once, now fails
rather than skips when its link cannot be made.

Change 4 adds the synchronization-cache source guard with a reviewed 47-source baseline at
format 13, previous format 12. Item 4 changed the format because exact WPILOG timestamp order
changes synchronization inputs. The new test first failed for the missing baseline and passed
after that review. Three plants caught changed REV input bytes, an inadequate explanation and
a claimed format bump without a version increase; all source hashes were restored. The guard
requires a compatibility decision but cannot itself prove the decision or its dependency list.

## 6. No wall-clock time on the data routes

### What is wrong

The data routes carry the log's own clock. `/data/entries` returns the log's time, in
microseconds in Arrow's `timestamp` column and in seconds in CSV's `timestamp_sec`
([The Data Endpoint](STANDALONE.md#the-data-endpoint)); `/read/v1` returns `timestamp_us` on the
file's clock or a session's chosen anchor clock; the visualization routes do the same. The
`server_epoch` that follows and the visualization routes return is a random identifier for the
server's run, not a time. A client that plots against wall time, such as
a Grafana time-series panel, a spreadsheet joined to scouting notes, or a comparison with a
video, has to build the mapping itself, and each client may build a different one or invent one
from a file name.

The server already reads that mapping. `log/WallClock` pairs log times with epoch microseconds
and judges which readings are plausible, for the system-log clocks, the REV log tools, file
naming, the log manager, directory listings, the REV synchronizer and import inspection. None of
the data routes uses it.

### The change

1. An opt-in UTC timestamp on `/data/entries`, the dashboard door of item 4, computed in the
   shared read layer from the log's own `WallClock` readings by the mapping the existing callers
   use, so the server never gives two answers for when a sample happened. The agreed scope
   excludes `/read/v1` from this change.
2. The basis travels with the data: which entry supplied the readings, how many there were, the
   offset used and the largest disagreement between readings.
3. Before the robot's clock was set, the UTC timestamp is null, with the reason. It is never
   made up from a file name, a modification time or the server's clock.
4. Arrow gains a nullable UTC column beside `timestamp`, and CSV an ISO 8601 column beside
   `timestamp_sec`; the existing columns stay. Where JSON carries the value (`/read/v1`'s
   NDJSON, or the later `json` format), epoch microseconds are decimal strings, as the read
   API's other integer timestamps are.

This is the prerequisite for the Grafana `json` format that the
[pit server plan](pit-server/PIT_SERVER_PLAN.md#12-metrics-prometheus-and-grafana) leaves for
later.

### Tests that must fail first

- A fixture whose wall-clock reading arrives partway: samples before it carry null and the
  reason, and samples after it carry the reading's epoch plus their offset from the reading,
  checked against values worked out by hand. Today the option does not exist.
- Two readings that disagree: the basis reports the disagreement.

### Documents

[The Data Endpoint](STANDALONE.md#the-data-endpoint), [HTTP version 1](READ_API.md#http-version-1)
for its explicit absence of UTC, [Shared read API](ARCHITECTURE.md#shared-read-api),
[`search_system_logs`](TOOLS.md#search_system_logs) for ambiguity refusal, and `CHANGELOG.md`.

### Implementation disposition — 2026-10-20

The first run stopped before implementation because its mapping premise was false.
`WallClock` selects valid readings; it does not define a single mapping.
`LogManager.estimateWallClockRange` and
`ImportInspection` use the first reading with a constant offset. `SystemLogClocks.interpolate`
and `LogSynchronizer.interpolateFpgaTime` interpolate between readings and extrapolate beyond
them.

For synthetic readings at FPGA 10 s and 20 s with UTC epochs 12 s apart, FPGA 15 s maps to
the first epoch plus 5 s under the first-reading rule, or plus 6 s under interpolation.
Both interpolation implementations were invoked in an isolated probe; the constant-offset
comparison was calculated independently from its source formula. The synchronizer also
extrapolates before the first reading, which conflicts with the requested null-before-clock
rule. No UTC column or route option was added in that run. The maintainer settled the mapping
on 2026-10-20: interpolate between readings, keep the last reading's offset after them, and
give null with the reason before the first.

The second run implements an exact-coordinate `UtcTimeMapping` and optional `utc=true` on
`/data/entries`. Arrow and CSV add nullable `timestamp_utc` beside their existing timestamps.
The per-series `utc_basis` reports the selected entry, retained reading count, first/last
offsets, largest consecutive offset change, rounding, null-coordinate count and reasons.
The final epoch rounds once to nearest microsecond, ties to even; offsets in JSON are exact
decimal strings. Reduced rows map their exact bucket starts, and CSV array expansion does
not multiply the logical null count. `/read/v1`, the first-offset estimates and REV alignment
remain unchanged.

A reproduced ambiguity in the released inverse required another explicit decision. The
maintainer approved rejecting conflicting duplicate coordinates and backward mappings in
both UTC presentation and `SystemLogClocks`; identical repeated pairs remain valid evidence.
That shared validation is implemented, preserving session bounds, endpoint offsets and
kernel mapping.

The targeted integration passed 138 tests, and the documentation check passed 11. All 27
behavioral plants were caught and restored with matching hashes. The initial implementation
check exposed extended-year CSV size accounting and the additive `WallClock` source guard;
both corrections passed the integration check. See the second-run end-gate disposition below.
A separate valid inverse probe measured about 0.501089 microseconds of round-trip difference
against the proposed 0.5-microsecond bound: the exact UTC rounding and the existing
double-second inverse each contribute. This triggered the work order's stop. The maintainer
subsequently approved exact wall-clock inversion with only the final conversion to double
rounded, preserving reading selection, session bounds, endpoint extrapolation and kernel
mapping. This updated decision supersedes the earlier answer allowing a bounded numerical
allowance for the legacy inverse. The exact inverse is now implemented in the shared mapper:
recognized timestamps and raw anchors remain exact; derived clock anchors preserve their
existing binary-double value; rational interpolation rounds only the final seconds result
to a binary double, ties to even. The accepted grammar and finite range remain unchanged.
Within one mapping segment, forward/inverse comparison allows only half a UTC microsecond
from forward quantization plus the final robot-seconds rounding error converted to UTC
microseconds by the mapping slope, without an arbitrary numerical allowance.

The exact-inverse integration passed 120 tests in 13.350 s; two additional rounding-branch
tests passed in 10.830 s. The final documentation check passed 11 tests in 5.900 s. Across
item 6, 37 of 38 behavioral plant attempts were caught and all 38 restored with matching
hashes. The first raw-anchor rounding plant escaped because its final double masked the
intermediate loss; an independently calculated odd-microsecond witness caught the same
plant. The miss remains part of the record. Targeted implementation, regression, plant and
documentation work is complete; the third run's phase A full build accepted it.

## 7. Questions across many sessions have no contract

### What is wrong

`compare_matches` compares one entry's statistics in two logs (`path` and `compare_path`), each
on its own clock. The reasoning guidance tells the model that one log is one sample, that
`compare_matches` shows two matches differ but not why, to use `get_statistics` on each log
when there are more than two, and to check `get_code_metadata` for a change of code between
them. The ideas guide proposes [multi-log alignment](IDEAS.md#22-multi-log-temporal-alignment)
and [mechanism health across many logs](IDEAS.md#35-mechanism-health-tracking). Nothing says
what a result across fifty sessions must report, so the first implementation would decide by
accident: pooling samples from different matches as one population, hiding a code change
between events, or leaving sessions out without saying so.

### The change

1. **Write the contract in [TOOLS.md](TOOLS.md) before any code.**
   - The session is the unit. A result has one row per session, with its inputs, scope,
     `data_quality`, robot, and git SHA where the log records one.
   - Statistics across sessions are statistics of the per-session values, and their sample
     size is the number of sessions. Samples are pooled only when the caller asks, and a pooled
     result says so.
   - `confounders` lists what differs between sessions besides the question: code version,
     robot and event, and battery and firmware where logged.
   - Sessions that could not be analyzed are in `skipped` with reasons, and a list cut by a
     limit reports the true total, as [the result contract](TOOLS.md#response-fields) requires.
2. **Measure.** Compute the contract's per-session values over about 50 real logs with the shared
   read API (the real-log suites are opt-in and their logs stay outside the repository), and
   record where the time goes. If scanning dominates, item 5's persistent index comes next.
3. **Build** through [Changing or Adding a Tool](DEVELOPMENT.md#changing-or-adding-a-tool).
   Whether that is a new tool or `compare_matches` extended to many logs is a maintainer
   decision.

### Tests that must fail first

Once the tool exists: the mean across sessions equals the mean of the per-session means worked
out independently, not the pooled mean (fixtures with different sample counts make the two
differ); the sample size counts sessions; a git SHA difference appears in `confounders`; and a
session that cannot be read is in `skipped`.

### Documents

[TOOLS.md](TOOLS.md) first; then the reasoning guidance, so that it names the tool; the ideas
guide's items 2.2 and 3.5; the tool-response scenarios and the README's tool table, as the tool
checklist requires; and `CHANGELOG.md`.

### Item 7 implementation disposition — 2026-10-20

The [proposed contract](TOOLS.md#proposed-multi-session-analysis-contract) now states source
selection, per-session evidence, equally weighted metric contributors, opt-in pooling,
confounders and missing facts, true totals and explicit work bounds. It is marked as planned,
not an available operation. The maintainer chose a new tool on 2026-10-20, leaving
`compare_matches` unchanged; numeric admission limits, duplicate-selection handling,
whole-request versus per-session failures and paging are settled in its design before
implementation. No schema, registration,
reasoning guidance or generated response reference changed.

With no real logs supplied, the shared read API measured item 5's fifty synthetic sessions.
Session `s`, numbered from zero, has `1000 + 100s` selected samples of `s + (i mod 5)`.
The independent mean for that session is `s + 2`; the equally weighted session mean is
26.5, with fifty contributing sessions. The pooled control has 172,500 samples and mean
`5612500 / 172500`, or 32.53623188405797. Before and after runs checked each selected sample,
timestamp, count and per-session mean, then both aggregate answers. Synthetic code, robot and
event facts were read separately; this harness does not implement the proposed confounder
report or its future refusal/cancellation contract.

After the discovery change, mean per-session shared admission took 12.061 ms, selected-entry
read/check 1.716 ms, data-quality calculation 0.286 ms and metadata read 0.119 ms. The before
values were 12.513, 1.734, 0.314 and 0.130 ms respectively. These separate passes locate costs
in the fixture, with uncontrolled OS caches; they are not a real-season throughput gate.
The first measurement attempt used exact equality for a floating-point quality score and
failed. Its private harness was corrected to an independently derived error tolerance; that
setup failure was preserved and was not counted as a caught implementation regression.

The second-run design proposes `compare_sessions` with one explicit source, finalized
catalog sessions, duplicate refusal, equal session weights, opt-in pooling and complete
per-session dispositions. It specifies admission, cancellation and response bounds and
identifies a missing reusable analysis-worker owner; adding that owner and the first-version
selection restrictions require approval. No tool, schema, registration or worker was built.
After the run, the maintainer approved the design with the amendments recorded in
[Decisions for the maintainer](#decisions-for-the-maintainer): no new work owner, a 1 GiB size
cap, a default anchor for a session with one WPILOG file, the 5th percentile and the existing
statistic names, and source selection as other tools choose it. The tool is not yet built.

The second-run synthetic pass again checked all 172,500 selected samples and the independently
derived aggregate values above. Mean shared admission was 24.595 ms per session, selected
read/check 2.678 ms, quality 0.424 ms and metadata 0.262 ms. The complete standalone measurement
ran in 4.848 seconds, with compilation measured separately. Its proposed maximum response model was 433,356
encoded bytes for 64 sessions, eight metrics and 16 windows; long evidence strings raised it
to 1,613,004 bytes. Count bounds therefore cannot replace the proposed 1 MiB encoded-response
admission. These measurements inform a design, not a demonstrated maximum-capacity guarantee.

## 8. The rules are not written down

Disposition: implementation, targeted checks and behavioral plants are complete, and the
native-library guard passed at the end of the run.
The architecture guide states six principles, including item 2's incomplete writer coverage.
The build guard inspects application runtime artifacts and the distribution, and the decision
index checks its links. The experimental portion is `pit-server/EXPLORER_REMOTE_SESSIONS.md`;
bare source and extension citations still refer to the main `EXPLORER_PLAN.md`. Native
libraries used only by test oracles remain separate.

### What is wrong

The [design principles](ARCHITECTURE.md#design-principles) are nine rules about what a result
may claim, how the server reasons and how its claims are checked. None says what the server
must protect or what it may cost:

- **A recording comes first.** No guide says that a recording outranks an import, an analysis or
  a synchronization when they compete, and item 1 shows that the code does not yet act on it.
- **A single-thread owner's turns are short and timed from outside.** The pit architecture
  states this for loops ([the loop rule](pit-server/ARCHITECTURE.md#relative-scopes-and-discoverable-live-state)),
  and the project instructions repeat it, but the main design does not, and the store queue
  breaks it.
- **Cost follows the change, not the store.** Item 5.
- **No native code runs in the server.** Its application dependencies are pure Java, while
  `MappedLogBytes` already uses read-only mappings outside the Java heap
  ([Memory Management](ARCHITECTURE.md#memory-management)). The nearest statement of a
  rule is a property of the data endpoint, which lays out Arrow itself so that "nothing leaves
  the garbage collector's care" ([The data endpoint](ARCHITECTURE.md#the-data-endpoint)).
  Nothing checks it, and the rejected plan would have put a native database engine in the
  process that records.
- **Derived data is keyed by its content and its recipe.** The REV synchronization cache follows
  this, and the rejected plan's [Worth keeping](COLUMNAR_STORE_PLAN.md#worth-keeping) states it,
  but it is not a principle.
- **Capture has a disk reserve.** Item 2.

Decisions are recorded in each plan: the pit plan's
[decision record](pit-server/PIT_SERVER_PLAN.md#part-iii-decision-record), the Explorer plan's
[decisions](EXPLORER_PLAN.md#1-decisions), the source plan's
[agreed behavior](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior), and the rejected columnar plan.
With no index, a proposal can pass over an earlier decision unnoticed: the two data doors of
item 4 came from the Explorer and visualization plans, and the rejected plan proposed a third.

Two plans formerly shared the name `EXPLORER_PLAN.md`: [the Explorer plan](EXPLORER_PLAN.md)
and [its experimental pit-server portion](pit-server/EXPLORER_REMOTE_SESSIONS.md). Bare source
and extension citations were checked against their decision and section numbers: they refer
to the main Explorer plan, which retains its name.

### The change

1. **Add to the design principles**, each with the failure that taught it, as the existing
   principles have: a recording comes first; a single-thread owner's turns are short and timed
   from outside; cost follows the change; no native code runs in the process that records;
   derived data is keyed by its content and recipe; and capture has a disk reserve.
2. **A guard against native libraries**, run by `./gradlew build`: a check that fails when the
   server's runtime classpath or its `-all` JAR contains a `.so`, `.dylib`, `.jnilib` or `.dll`
   file. Adding one then takes a deliberate change to the check and to the principle.
3. **An index of decisions**, `doc/DECISIONS.md`: one line per decision, with its date, its
   status (standing, superseded or rejected) and a link to its record. A claim test checks that
   every link resolves to a file and a heading.
4. **Rename the pit-server portion** to a name of its own (proposed:
   `doc/pit-server/EXPLORER_REMOTE_SESSIONS.md`, after its title), and update its links and
   `DocumentationLayoutTest`, which requires the experimental guides by name.

### Tests that must fail first

- The native-library guard: plant a native library on the runtime classpath or in the `-all`
  JAR and see the check fail; remove the plant and see it pass.
- The decision index: plant a broken anchor and see the claim test fail.

The rest of item 8 is documentation.

### Documents

[ARCHITECTURE.md](ARCHITECTURE.md), the new `doc/DECISIONS.md` and its row in the README's
document map, the renamed plan and its inbound links, the new check in
[DEVELOPMENT.md](DEVELOPMENT.md), and `CHANGELOG.md`.

### Implementation disposition — 2026-10-20

The six principles, build guard and checked decision index are implemented. The pit Explorer
portion was renamed without changing its bytes; source and extension citations of the bare
name refer to the main Explorer plan. The index checks all 68 dated/status rows and their
heading links. A broken anchor was caught, as were synthetic `.DLL`, `.so`, `.jnilib` and
`.dylib` markers across runtime directories, runtime archives and the distribution alone.
Every plant was restored. The guard checks packaged artifacts; it does not prove arbitrary
Java code cannot load a separately installed native library. The end-of-run full build failed
on item 4's outdated tests before reaching the guard; the guard then passed when run on its own.

## Decisions for the maintainer

The maintainer accepted these decisions for the 2026-10-20 implementation run. Rows marked
"Settled 2026-10-20" answer choices where a run stopped: the first run's answers were made after
it, and later rows say whether they came during the second run or after it. The item
dispositions above distinguish implemented decisions, pending verification and item 7's
approved design, which is not yet built. A disagreement with the code stops the affected part
for review.

| Decision | Agreed disposition | Applies to |
|---|---|---|
| Store-queue turn budget | One second, logged and counted, never enforced by interruption | Item 1 |
| Log output that stops accepting writes | Settled 2026-10-20: all server log output goes through an in-memory buffer of about 1 MiB written by its own thread; lines that do not fit are dropped and counted, and one line reports the loss when output resumes | Item 1 |
| Capture reserve, floor and resume margin | 2 GiB reserve, 256 MiB floor, resume 512 MiB above the floor, configurable | Item 2 |
| Copies that ran on the store worker | Settled 2026-10-20: Sync from Laptop, the second copy of pulled system text and cross-filesystem pull moves copy outside the store worker, as imports do, with space reserved before the first byte and a short job that rechecks and places each file | Item 2 |
| Whether capture is first written to its own directory and moved at close | A separate design after phase 1 | Item 1, later |
| Whether a stopped capture fails the health probe | No: report it; item 1 removes the common cause | Item 3 |
| Soak test rate, duration and bounds | 100 topics at 50 Hz for ten minutes; no loop stall over one second | Item 3 |
| Restart threshold | Three failed tries, ten seconds apart | Item 3 |
| A separate recorder process | Out of scope; stop item 3 and report if the soak fails | Item 3 |
| Bucket membership on `/data/entries` and the tools | Exact integer microseconds and microsecond order, before 0.10.0 is released | Item 4 |
| Angles in reduced data | Settled 2026-10-20: `read_entry`, `/data/entries` and the preview unwrap declared angles before reducing, as `render_chart` does; `read_entry` and `/data/entries` gain an `angle` parameter for plain numbers; `/read/v1` stays raw | Item 4 |
| Other route differences | Settled 2026-10-20: exact samples when they fit, a chart's window over its own data and a chart's dropping of non-finite values stay as documented differences, named as the differential test's only exceptions | Item 4 |
| Roles of the data routes | Keep Explorer decision 7: `/data/entries` for viewers, scripts and dashboards; `/read/v1` for exact typed reads | Items 4 and 6 |
| Wall-clock mapping | Settled 2026-10-20, replacing "the mapping existing `WallClock` callers use", since they use two: interpolate between readings, keep the last reading's offset after them, and give null with the reason before the first, with the basis reported. The REV-matching window and an import's start keep the first reading's offset as documented estimates; REV alignment keeps its internal backward estimate | Item 6 |
| Ambiguous wall-clock readings | Settled 2026-10-20 during implementation: reject conflicting duplicate coordinates and backward mappings with an explicit reason in both UTC presentation and `SystemLogClocks`; identical repeated pairs are redundant. This authorizes the corresponding change to released system-log mapping behavior | Item 6 |
| Valid wall-clock inverse precision | Settled 2026-10-20, updated to supersede the earlier numerical-allowance answer: use exact wall-clock inversion and allow only rounding of the final conversion to double. Preserve reading selection, session bounds, endpoint extrapolation and kernel mapping | Item 6 |
| UTC on `/read/v1` | No; only `/data/entries` is in scope | Item 6 |
| Measurement logs | No real logs supplied; use a synthetic season and label it. Settled 2026-10-20 after the second run, as the one exception: time the persistent scan index on three local team logs, of about 38 MB, about 280 MB and the largest, with a throwaway cache and no running server. The results stay in ignored local evidence, and nothing from the logs enters the repository | Items 5 and 7 |
| Persistent scan index | Settled 2026-10-20, replacing the rule that a real-log measurement decides first: build it now, keyed by content and checked before use. The parsed-log disk cache keeps its REV synchronization cleanup and size limit. After the real-log timing above, the maintainer decides whether it stays on, is off by default or gets a larger size limit | Item 5 |
| Pending capture publications | Settled 2026-10-20: each session's pending marker records its failure's code, message and time; catalog status lists pending publications with causes and true totals, and a mirror asking for such a session is told why | Item 5 |
| Remote pending-publication status | Settled 2026-10-20 during the second-run source audit: add bounded `POST /replicas/v1/status` with required `store_id` and optional `session_id`; a selected pending session gets an explained, retryable `PUBLICATION_PENDING` refusal with its recorded cause. Keep immutable `/file` identity rules unchanged | Item 5b |
| Capture startup with pending publications | Settled 2026-10-20 during the second-run source audit: continue CaptureService startup after publication-only failures once intact local receipts are verified; corrupt or unsafe recovery remains fatal | Item 5b |
| Registering a folder that holds no store | Settled 2026-10-20 after the second run: the publication step that runs when a store is registered neither takes nor creates `store.lock` in a folder with no store. The symlinked-lock test fails, rather than skips, when its link cannot be made because a file is already there | Item 5b |
| Multi-session contract and its tool | Settled 2026-10-20: a new tool built to the contract; `compare_matches` stays the two-file comparison. The second-run design was approved after that run, with the amendments in the next five rows; the tool is not yet built | Item 7 |
| Multi-session sources | Settled 2026-10-20 after the second run: finished sessions in stores and mirrors only. A plain folder is refused, with the remedy to import it or use `compare_matches` for two files; a session still recording is skipped as not finished. Comparing live recordings is a later, separate feature | Item 7 |
| Multi-session metrics | Settled 2026-10-20 after the second run: each metric is one statistic of one number, either a named entry or the battery voltage, total current, full loop time or user loop time role, optionally one struct field or fixed array index. The statistics are `mean`, `std_dev`, `min`, `max`, `median`, `p5` and `p95`, named as `get_statistics` names them; angles get only a circular mean and spread. The summary across sessions applies the same set to the per-session values, each session counting once | Item 7 |
| Multi-session work and limits | Settled 2026-10-20 after the second run: no new work service, whole-call time limit or cancellation. Work is capped before it starts, at most two comparisons run at once, and a third is refused. A call reads at most 1 GiB of log files, with the record-visit cap doubled to 8,388,608 to match; median and percentiles keep at most 65,536 values per session and metric, and the other statistics are exempt. The other limits stay as designed: 64 sessions, 8 metrics, 16 windows per session, 256 files and one complete reply of at most 1 MiB. Server-wide [time limits and cancellation](IDEAS.md#time-limits-and-cancellation-for-long-tool-calls) are a follow-up | Item 7 |
| Multi-session anchors | Settled 2026-10-20 after the second run: a session's anchor defaults to its only WPILOG file, and the result reports that basis. A session with several requires a choice, and the refusal lists each session's choices | Item 7 |
| Multi-session source selection and remaining rules | Settled 2026-10-20 after the second run: the source is chosen by `scope_id` as other tools choose it, without the design's required server identity; a [scope ID from another server](IDEAS.md#scope-ids-from-another-server) is a server-wide follow-up. Duplicate refusal, per-session failures, opt-in pooling, confounder evidence, unit rules and the complete reply stay as designed | Item 7 |
| Daemon start test after the second run | Settled 2026-10-20 after the second run: the test's fake daemon closes its port before it reports its process dead, with a regression test that forces the old gap. A start that joined a failed start still waits three start timeouts; that [follow-up](IDEAS.md#a-joined-start-waits-out-a-failed-start) is unchanged in this run | End-of-run verification |
| Decision index format | One line per decision: date, status, link | Item 8 |
| Name for the pit-server Explorer portion | `doc/pit-server/EXPLORER_REMOTE_SESSIONS.md` | Item 8 |

## How each item is accepted

The rules in [DEVELOPMENT.md](DEVELOPMENT.md#verification-policy) apply to every item: a test that
fails on today's code before the change is trusted; numbers checked against answers worked out
independently of the server; each check planted with the bug it guards against; one
`./gradlew build` passing at the end; `npm test` where a file under `vscode-extension/` changed,
and one `harness/run` where capture, pull or NT4 code changed, as the policy's table says;
`./gradlew followBrokerTest` where capture or the follow routes changed, since it runs the real
`CaptureWriter` and HTTP transport; and the documents named in the item kept true. Paths in tests
are built with the path API the code uses, since CI runs on Windows too.

## End-of-run verification

### First run

Each first-run closing check ran once, on 2026-10-20:

| Check | Result |
|---|---|
| `./gradlew build` | Failed: 4,551 passed, 4 failed, 86 skipped, in 116.899 s |
| The four corrected tests and their adjacent consumers | 51 passed, in a targeted run |
| `./gradlew verifyRuntimeNativeLibraries` | Passed; the failed build stopped before this guard |
| `npm test` in `vscode-extension` | 892 passed, 5 skipped |
| `./gradlew followBrokerTest` | 5 passed |
| `harness/run -PconformanceNative=sample` | 33 passed, 3 skipped, none failed, in 77.399 s |

The build's four failures were test expectations older than item 4's correction: the
differential oracle's double-second bucket membership, two legacy-reader tests that required
file order for distinct microseconds sharing one double, and a pin on synchronization-cache
format 12. Calculations from the raw WPILOG bytes, independent of the server, confirmed the
integer answers the server gave; [item 4](#4-three-routes-serve-reduced-samples) gives the
example. Six test files were corrected and no production code changed. No second full build was
run, so the corrected tree has passing targeted checks, not a passing full build.

The harness ran on macOS, which skips its Docker roboRIO and real PhotonVision backends. Of 137
behavioral plants, 134 were caught. The three misses stay on record beside the corrected plants
that caught the same faults, and every planted file was restored to its original bytes.

Not run: Linux under Xvfb, Windows, real VS Code 1.101.0 or current releases, a real Linux
systemd restart, an import by the service's user beside a managed server, a real robot and real
logs. Acceptance also waits on the parts the run stopped at, which the maintainer has since
settled: item 2's remaining copy ownership now passes targeted checks and behavioral plants;
item 6's UTC column, ambiguity handling and settled exact inverse are implemented, with
targeted checks and behavioral plants complete; its full-build acceptance remains open.
Item 4's angle presentation and five-route matrix have completed targeted verification;
all 23 second-run plants were caught and restored. Item 5's scan index has targeted
verification and 31 caught, exactly restored plants. Publication-failure reporting, remote pending-session lookup and narrowly classified startup
continuation have completed targeted verification and all 42 behavioral plants, with exact restoration.
Item 7 has a detailed design and synthetic measurement; implementation stops for approval.

### Second run

The closing checks on 2026-10-20 used synthetic data and isolated test services:

| Check | Passed | Failed | Skipped | Seconds |
|---|---:|---:|---:|---:|
| Initial `env -u LANG -u LC_ALL ./gradlew build` | 4,878 | 6 | 87 | 153.313 |
| Approved second full build, same command | 4,883 | 1 | 88 | 167.240 |
| Single isolated daemon-restart retry | 1 | 0 | 0 | 3.104 |
| `npm --prefix vscode-extension test` | 892 | 0 | 5 | 59.896 |
| `./gradlew followBrokerTest` | 5 | 0 | 0 | 16.090 |
| `harness/run -PconformanceNative=sample` | 33 | 0 | 3 | 90.311 |

The initial failures exposed two omitted standalone reader dependencies, unclassified
result-field tokens in the documentation check, and a historical test fixture bypassing
its store queue. Those corrections passed targeted checks, including 84 fixture/integration
cases. Five final integration plants reproduced the corresponding failures and were restored
exactly. One separate plant attempt was blocked by Gradle-cache permissions before tests;
it is setup evidence, not a regression catch.

The approved second full build resolved those failures but timed out in the existing
concurrent daemon-restart test. Its single isolated retry passed, together with the
native-library guard that the failed build had not reached. The daemon manager and its test
were unchanged from the incoming tree. Logs show a starter refusing a transiently occupied
port while the joining caller exceeded its test deadline; the precise probe failure was
not recorded. No daemon behavior or test was changed for this failure. The full-build gate
remains failed. After the run, the maintainer chose to fix the test's fake daemon, which
reports its process dead before its port closes, and authorized one full build after the two
fixes and another after item 7 is built.

One additional symlink-lock case was skipped in the second build; its saved XML omitted the
reason. The fixture catches any I/O error when creating the link, so this is a coverage gap,
not proof that symlinks were unavailable. The test's other three cases created their links in
the same build, so links were available. The likely cause is the new publication step at store
registration, which creates an ordinary `store.lock` in a folder with no store before the test
makes its link; the decision table records the fix. The five extension skips ran under the broker task.
The macOS harness explicitly omitted Docker roboRIO and real PhotonVision. Linux, Windows,
real editors, real systemd, real robots and real logs remain unverified.

Across the second run, 200 plant attempts were exactly restored: 196 caught, three earlier
behavioral misses followed by stronger catches, and one setup-only failure. These are
mutation attempts, not unique test counts. Item 7 ended the run as a detailed design and
synthetic measurement only; the maintainer approved the design afterwards, with amendments,
and the tool is not yet built. The run is not accepted while the failed full-build gate
remains.

### Third run

Phase A closed items 1 to 6 on 2026-10-20 with synthetic data, apart from one authorized local
timing:

| Check | Passed | Failed | Skipped | Seconds |
|---|---:|---:|---:|---:|
| Registration lock, failing first on the old code | 13 | 5 | 0 | 14.106 |
| Registration lock with the adjacent store suites | 138 | 0 | 0 | 11.873 |
| Daemon restart regression, failing first on the old order | 0 | 1 | 0 | 7.568 |
| `DaemonManagerTest` with the corrected fixture | 57 | 0 | 0 | 19.646 |
| Documentation and claim checks | 21 | 0 | 0 | 6.387 |
| `env -u LANG -u LC_ALL ./gradlew build` | 4,896 | 0 | 87 | 157.631 |

Registration's publication step no longer takes or creates `store.lock` in a folder that holds
no store. Its failing-first run reproduced the second build's skip as a failure: the lock that
registration created was already there when the test made its link. The daemon tests' fake
daemon now closes its port, and checks that nothing accepts a connection, before it reports its
process gone. A latch-held regression reproduces the old order, in which the restart's probe
found the old daemon still answering and started nothing. `DaemonManager` is unchanged. Four
behavioral plants were caught and restored exactly: the registration lock restored, publication
turns left unlocked for a real store, the fake's old order, and a port left listening.

The full build includes the license check and the native-library guard. Against the second
run's approved build, the only change in skipped tests is the symbolic-link lock case, which now
runs. The verification policy did not require `npm test`, `followBrokerTest` or the native
harness for these changes: no extension, capture, pull, NT4, harness, reader or broker code
changed. Items 1 to 6 are accepted.

The persistent scan index was timed on three local team logs, read-only, with a throwaway cache
and no server; the results stay in local, ignored evidence. The estimate for a multi-session
call at its 1 GiB cap stayed below the work order's 15-second threshold, so phase B went ahead.
Whether the index stays on, is off by default or gets a larger size limit is the maintainer's
decision.

## Out of scope

- An embedded database or any native engine in the server process: the columnar plan's
  [rejection](COLUMNAR_STORE_PLAN.md#why-it-was-rejected) holds.
- A separate recorder process; a failed soak stops item 3 for a separate design.
- Changes to the manifest format. A new `end_reason` value is not one; partitioned replica
  catalogs would be, and need a plan of their own.
- Analysis features beyond item 7's contract.
