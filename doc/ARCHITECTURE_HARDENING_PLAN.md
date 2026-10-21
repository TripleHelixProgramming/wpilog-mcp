# Architecture hardening plan

Status: proposed on 2026-10-19. Nothing in it is implemented. Items 1 and 2 protect recordings
and come first; the others wait for the measurements and maintainer decisions they name.

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
| [1](#1-capture-waits-behind-imports) | Capture shares one store queue and one cross-process lock with imports. A long import, or an import in another process, can stop a recording for the rest of the robot's boot, and the `import` command does not see a managed server. | Stage imports outside the queue, run capture turns first, time every turn, retry a held lock within the admission deadline, and send the command's imports to a managed server. | 1 |
| [2](#2-nothing-measures-free-disk-space) | Nothing measures free disk space. Every writer finds a full disk by failing. | One free-space budget per filesystem, a reserve only capture may use, a clean low-disk close and resume, and free space in `/health` and the metrics. | 1 |
| [8](#8-the-rules-are-not-written-down) | The rules this plan follows are not design principles, nothing checks that the server runs no native code, decisions have no index, and two plans share a file name. | New principles, a guard against native libraries, a decision index, and a rename. | 2 |
| [3](#3-one-process-records-and-analyzes) | One process records and analyzes, `/health` cannot see a stalled capture, and nothing restarts a hung server. | A soak test, capture progress in `/health`, and a restart after repeated failed probes. | 3 |
| [6](#6-no-wall-clock-time-on-the-data-routes) | The data routes carry only the log's clock. | An opt-in UTC timestamp from the log's own wall-clock readings, with its basis. | 4 |
| [4](#4-three-routes-serve-reduced-samples) | Three routes serve reduced samples, with shared arithmetic but different bucket membership, admission, formats and unwritten roles. | A differential test, one membership rule, and a written role for each route. | 4 |
| [5](#5-costs-grow-with-the-whole-store) | Discovery, scanning and replica catalogs cost in proportion to everything the server can see. | Measure; then persist scan indexes by content, discover from manifests, and warn before the catalog cap. | 5 |
| [7](#7-questions-across-many-sessions-have-no-contract) | Questions across many sessions have no contract. | Write the contract, then measure, then build. | 5 |

The order is 1, 2, 8, 3, 6, 4, 5, 7. Phase 1 can start now. Phase 2 is documentation and one
guard. Phase 3 needs the soak test's numbers. Phases 4 and 5 need decisions and measurements.

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
5. **Shutdown stays bounded.** `awaitImports` then waits only for short turns. The staging worker
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
   (proposed: 100 topics at 50 Hz, the rate the gateway's item bound is sized for) for a stated
   time (proposed: ten minutes), while a large import runs and clients make heavy reads through
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

## 4. Three routes serve reduced samples

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
   use, so the server never gives two answers for when a sample happened. `/read/v1` takes the
   same option if its clients need it.
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
if `/read/v1` takes the option, [Shared read API](ARCHITECTURE.md#shared-read-api), and
`CHANGELOG.md`.

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

## 8. The rules are not written down

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
- **No native code runs in the server.** Its dependencies are pure Java, and its only memory
  outside the heap is read-only mappings of log files, which the operating system can drop at
  any time ([Memory Management](ARCHITECTURE.md#memory-management)). The nearest statement of a
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

Two plans are named `EXPLORER_PLAN.md`: [the Explorer plan](EXPLORER_PLAN.md) and
[its experimental pit-server portion](pit-server/EXPLORER_PLAN.md), and source comments cite
`EXPLORER_PLAN.md` by bare name.

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

## Decisions for the maintainer

Each has a proposed answer. An implementation uses none of them until the maintainer agrees.

| Decision | Proposed | Blocks |
|---|---|---|
| Store-queue turn budget | One second, logged and counted, never enforced by interruption | Item 1 |
| Capture reserve, floor and resume margin | 2 GiB reserve, 256 MiB floor, resume 512 MiB above the floor, configurable | Item 2 |
| Whether capture is first written to its own directory and moved at close | A separate design after phase 1 | Item 1, later |
| Whether a stopped capture fails the health probe | No: report it; item 1 removes the common cause | Item 3 |
| Soak test rate, duration and bounds | 100 topics at 50 Hz for ten minutes; no loop stall over one second | Item 3 |
| Restart threshold | Three failed tries, ten seconds apart | Item 3 |
| A separate recorder process | Only if the soak test fails its bounds | Item 3 |
| Bucket membership on `/data/entries` and the tools | Exact integer microseconds and microsecond order, before 0.10.0 is released | Item 4 |
| Roles of the data routes | Keep Explorer decision 7: `/data/entries` for viewers, scripts and dashboards; `/read/v1` for exact typed reads | Items 4 and 6 |
| Wall-clock mapping | The mapping existing `WallClock` callers use, with its basis reported | Item 6 |
| Multi-session contract and its tool | The contract in item 7; a new tool or `compare_matches` extended, chosen after the contract | Item 7 |
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

## Out of scope

- An embedded database or any native engine in the server process: the columnar plan's
  [rejection](COLUMNAR_STORE_PLAN.md#why-it-was-rejected) holds.
- A separate recorder process, unless item 3's soak test fails its bounds.
- Changes to the manifest format. A new `end_reason` value is not one; partitioned replica
  catalogs would be, and need a plan of their own.
- Analysis features beyond item 7's contract.
