# Pit server: Architecture

> **Experimental:** the pit server has limited controlled-shop evidence. These documents are
> retained for development and controlled shop evaluation, not as a recommendation for team use.
> Automated tests and simulation do not establish reliability on a real robot.

## File transfer

The [source and mirror implementation plan](../SOURCE_AND_MIRROR_PLAN.md) specifies the
next mirror protocol, verification states and separation of transfer I/O from publication.
M4 supplies explicit format-2 migration and preservation admission. M5 supplies immutable
generations, recovery, local cleanup and paged peer reconciliation; discovery is server-owned. Ordinary union transfers retain
their separate owner described below.

`sync.FileTransfer` advances at most one 64 KiB block per step, with an injected monotonic clock and
gate. It performs no sleeps and owns no socket or thread. A read-only transport interface serves
SFTP and the HTTP store door. A local interface supplies append, archive, rename, verification,
placement, and atomic manifest writes. One caller owns a step, and a concurrent call is refused. One
remote listing serves a whole pass across files and blocks. A long-running pass refreshes its
listing after ten seconds. Verification requires a stable size and modification time across distinct
listings.

SFTP data reads retain one handle per active file, closed on pause, error, file completion or
listing refresh. At most two SFTP packets are outstanding; the delivered packet and read-ahead
remain within the gate's 64 KiB bound. Range-only transports retain the same interface. Pacing
charges actual data and fallback-comparison bytes against a bounded 64 KiB allowance and the
configured byte rate; idle time cannot accumulate an unbounded burst.

The robot pull store batches local bytes up to 1 MiB, with a checkpoint due after one second,
and also checkpoints on file completion, pause and an idle close. Pacing schedules a wake by
that deadline; an in-flight remote or filesystem operation can delay completion. One queue admission appends the
batch, forces staged data, then atomically replaces the progress manifest. Other transfer stores
retain their existing per-block checkpoints. Closing during an active remote read aborts the
transport; only uncheckpointed memory can be lost. A process crash may leave more staged bytes
than the manifest counts, but never durable progress ahead of those bytes.

#### Resuming a copy

`pull.json` remembers each remote name, size, modification time, copied count, verification state,
and local path, plus retired generations. Growth resumes only after a hash of exactly the held
prefix matches. Without command execution, the fallback compares the last 64 KiB of that range,
which is weaker evidence than a whole-prefix hash. Shrinkage, rewound time, or mismatched content
archives the old copy and starts a new file. A disappearing name and a new name with unique matching
content can rename the held copy. Names alone never establish continuity. An owned, unverified
staging file longer than its saved count is truncated to that count, after rechecking the current
manifest. The held prefix still needs the ordinary content proof; verified files and short staged
files cannot use this crash-tail repair.

#### Verification

The gate pauses between blocks, and resuming rechecks the held prefix. Read pacing includes fallback
comparison bytes. After a stable listing pass, the ordinary readers must reach a clean EOF before
placement; a recoverable truncated log is not verified. One failed verification permits one complete
refetch. The REV parser has a strict verification entry point beside its ordinary recovery behavior,
and sync cache format 6 invalidates older reader/synchronization results. There is no remote
deletion operation.

#### The pull worker

`ssh.SharedSsh` owns each host connection. `capture/pull.PullCoordinator` borrows its SFTP channel
and runs on a separate daemon. It reads the ordered capture listener's `/FMSInfo/FMSControlData`
enabled bit: only a continuously disabled robot, settled for five seconds (the default) with NT4
still connected, permits work. Unknown state and each new connection close the gate. Polling,
retries and pacing use a scheduler, and neither SSH nor transfer storage runs on the NT4 loop.
Shutdown includes the transport in the capture's existing deadline.

#### SSH

The maintained JSch fork is one dependency with no required crypto provider on Java 17. Its
versioned Ed25519 classes require the packaged JAR's `Multi-Release` manifest flag, which is checked
by initializing the packaged providers in an isolated classloader. Host fingerprints are kept per
serial and address. First contact is trusted. A changed key is reported before authentication:
empty-password authentication may continue, but a configured password or key requires explicit
`capture.pull.ssh.accept_changed_host_key: true` or removal of the pinned fingerprint in
`robot.json`. The subsequent serial reading selects the pull manifest. A changed key alone does not
identify a device.

Connect and channel-open timeouts stay at five seconds. Five-second SSH keepalives (three missed
replies allowed) keep a live connection available while a hash has no output. Each hash command has
a separate deadline: 30 seconds plus one second per 256 KiB, rounded up. Expiry closes its channel,
and a completed command cancels its timer. Hashing reads the entire held prefix on the robot and
costs CPU and storage bandwidth; the transfer byte cap does not pace that local work.

#### Placement

`store.PullStore` uses the existing queue, path validation, move reservations and reader release.
Partial files stay in `robots/<serial>/pulled/` and do not appear as logs. Verified files move into
session `robot/` directories with hash, size and remote provenance. Confirmed growth returns a file
to staging and removes its obsolete session hash before append. Moves keep durable tool-path
aliases. A file's logged serial wins over the SSH device reading, with a recorded conflict, and
matching never crosses known serials. Addresses select a connection, not a robot's lifetime
identity.

Strict verification and inspection share one prepared input for each completed pull. Native REV
is decoded once; a WPILOG keeps one retained view until matching finishes. Decoding, inspection's
content hash and calendar-filtered correlation run on the pull worker outside the store queue and
cross-process lock. Local prefix hashing also uses a retained read lease outside that queue.
All pulled-file moves reserve and drain source readers on the pull worker; the reader-drain budget
is three seconds, and exhaustion leaves the staged file for retry.

Same-filesystem placement uses an atomic rename. A cross-filesystem move reserves the additional
destination bytes and copies, hashes and forces an owned destination stage on the pull worker.
The final turn rechecks the source and prepared copy before renaming and recording the result.
The original is removed only after commit and a fresh source snapshot/hash check; a changed
original stays with an explained cleanup refusal. Placement retains its existing aliases and
retry behavior, without adding a robot-pull placement receipt.

Short queued operations snapshot ownership and manifests, then recheck file identity/size/mtime,
transfer progress and the complete matching candidate set before moving bytes and writing
manifests. They do not repeat payload scans, hashes or correlation under the lock. A capture may
extend the fixed prefix already used; a replacement, changed calendar nomination/identity/anchor
or newly ambiguous candidate requires another off-queue decision. Candidate manifests are capped
individually at 4 MiB; revalidation still visits the managed candidate set. Queue wait and filesystem
latency have no general wall-clock bound, so recording admission must not join those operations.

Local and inbox imports use the same separation: their staging worker holds source read leases
while it hashes, inspects, correlates and verifies copies. Publication rechecks those facts
under `store.lock`. A changed source is refused. One mutation owner selects capture admission
before waiting import turns; an import's payload inspection cannot occupy that owner.
The one-second mutation-action budget is observed from a separate daemon and counted once per
over-budget action, without interruption. It cannot preempt an operating-system operation.
The reporter admits its line to the process's bounded stderr buffer after the action timer
ends. A separate daemon drains that buffer, so a blocked journal cannot hold either the store
owner or its outside observer. The 1 MiB allowance includes incomplete, queued and in-flight
bytes. Overflow discards whole lines and counts their bytes; one ordered loss line reports
the gap when output resumes. Capture's periodic cost and error logging use the same owner.
Shutdown drains after capture and reader cleanup, with at most a two-second output wait;
a hard crash can lose queued logs. The WPILOG remains the recording.

#### Session matching

Before loading candidates, manifest session ranges nominate overlapping sessions, using the same
clock slack as import: two hours, or sixteen for filename clocks without a zone. Unknown clocks,
including REV's unset 1970 filename clock, cannot exclude a candidate. Names nominate shared numeric
entries (including DataLogManager's `NT:` prefix and the `/AdvantageKit` table added by
AdvantageKit's NT publisher) or REV signal pairs, and the existing correlation machinery decides.
Flat, ambiguous or weak data is insufficient.

A strong match must have an offset within 250 ms of zero and identify one session. Capture files
anchor their session, and already matched members cannot chain small offsets into a larger clock
shift. A session without a capture may use an unmatched WPILOG as its anchor. A missing logged
serial on either side records `data_alone`, even when device identity already limits the candidate
robot.

When no match is unique, the file gets a new session, dated by its clock evidence or its
modification time, and the manifest records which. Its file manifest and listing retain
`matching_reason`, distinguishing insufficient data, clock/serial disagreement, excessive offset,
and ambiguous sessions. An open capture has no completed hash to invent, so a match against it
records the capture's recording instead: the files of one recording share the robot's clock, and
the recording still names the reference after the capture closes and in a copy without it. A new contact checks held
content even when a reused file has identical size and mtime, and a changed listing invalidates an
in-progress proof.

## NT4 foundation

The NT4 layer was first exercised as an unwired fixture. The configured capture service now uses its
client and optional gateway. The client and a loopback gateway exercise each other on every
generated fixture to pin the delivery order the capture writer depends on. The gateway's core takes
messages and explicit times and returns deliveries, and performs no I/O under its short state lock.
The adapter sends those deliveries on its own daemon loop. The client has a separate daemon loop, so
every announcement, removal, property update, and value reaches its listener in order on one thread.
The capture service feeds the writer, live tools and gateway from that listener.

#### Keepalive

Both 4.1 endpoints ping every 200 ms and expire the oldest unanswered ping after one second. A local
loop that has not sent a ping cannot blame the peer for its own stall. Pong receipt is stamped on
the network callback, and gateway pong replies bypass fan-out while retaining the send-queue bound.
The JDK client renews receive demand there too (automatic pong replies consume that demand). Copied
listener work is bounded to 32 MiB, charging at least 64 bytes per callback. An overrun closes with
an explained reason instead of growing the application queue indefinitely.

#### The gateway

The gateway has its own configured port and the HTTP bind address. Recorder policy does not filter
its feed. A robot disconnect flushes pending values before unannouncing every upstream topic, and
new announcements allocate new gateway ids. Client publications are private acknowledgement sinks,
never a second route to the robot or capture. Properties acknowledgements report unchanged facts.

Completed provider writes also enter the gateway's bounded queue. Their declared type, value and
already-established robot timestamp have one owner; forwarding never estimates their time again.
One queued publication orders the declaration, a replacement metadata envelope and its value.
The versioned `wpilog_context` property lets a downstream recorder retain canonical context names
and provenance. A name alone supplies no provenance. Upstream declarations take precedence over
local context on a collision. Disconnect clears both kinds of topic; rollover does not replay
provider history to existing subscribers.

Pulled text notifications occur only after the byte receipt and cursor commit, outside the store
queue and cross-process lock. On the pull worker, the same clock reader used by system-log search
maps the new committed lines. Kernel/journal cursors identify deltas; a changed immutable file
snapshot compares the latest receipt for that remote path, so rotation to a shorter file does not
select an older, longer version. It reuses only a byte-proven prefix, ending at its last complete line. The worker
reads one bounded line at a time and hands off at most 64 lines or about 256 KiB of encoded text
per batch. A single line can exceed that batch target but cannot exceed 64 KiB of characters.
The capture loop preserves those mapped timestamps and yields between records after two
milliseconds. The worker's delivery wait has a five-second deadline and a cancellation flag;
a late callback cannot write after timeout, close or a change of recording. No text read or clock
read runs on the NT4 loop, gateway fan-out loop or store queue. Per-update limits are 65,536 lines
and 16 MiB of characters. Failure retains the original receipt and disconnects gateway views;
history recovery stays a store read, not an unbounded network replay.

Mapped JSON text uses `/Daemon/System/<source>` and its event timestamp. Unmapped lines use a
separate `/unmapped` topic with a null event time and an explicit reason; only that topic uses
receipt time, captured before file reads or capture-loop delay. Mapping uses the evidence available
at publication and is not retroactively changed. File-prefix proof holds read leases on both files,
so a concurrent move cannot race the comparison or strand an open handle.
Closed or unassigned crash-file sessions do not publish into the active recording.

The listener's bind lifecycle is independent of capture startup. A busy port retries with 1-second
exponential backoff capped at 30 seconds, and topics remain in the gateway core while waiting. The
adapter binds a reusable socket before handing it to Java-WebSocket, so failed attempts close their
channel and do not emit the library's repeated fatal-startup log. Health and live sessions read a
published state, cause and state-change time without waiting.

The fan-out thread coalesces sends at the minimum requested value period per client, deduplicating
overlapping subscriptions as NT4 permits. Period-pending values are bounded by 32 MiB and
65,536 records per client. Java-WebSocket's nonblocking selector drains the socket queues. Every
text and binary message is fragmented near the MTU, with at most 32,768 queued fragments per client.
The adapter checks the queue's constant-time size before each send and drops an overrun connection
immediately, logging its reason. Neither a socket write nor a store operation runs on the NT4 loop.
The connected count is a volatile publication for metrics, not a call into the fan-out lock.

The 200 ms aliveness tick also restores lost socket write interest. Java-WebSocket 1.6.0 can clear
`OP_WRITE` after a concurrent sender sets it, leaving an open 4.0 connection with queued bytes and
no next message to wake it. A constant-time queue check rearms the selector. It does not resend
bytes or add protocol pings. The independent scripted wire fixture needs the same RFC 6455 adapter
repair. A regression plants that exact selector state on real sockets.

#### Time synchronization

Time-sync replies apply the client's measured robot offset to its monotonic clock. Without an
estimate they use that local clock, following ntcore's server reply. The first synchronized sample
after each upstream connection resets downstream connections: ntcore 2026 uses its first reply,
so retaining a connection across a clock change would retain the wrong offset. The native harness
probe verifies its received timestamps through a separate NetworkTableInstance. A real dashboard
or AdvantageScope session remains a manual hardware check.

Clients reach the gateway as they reach ntcore, which is more than RFC 6455. AdvantageScope
requests `GET /` before opening its WebSocket and connects only after a 2xx answer; Java-WebSocket
answers any request that is not an upgrade with a 404, so AdvantageScope polled without ever
connecting. The adapter's connections answer that one request with ntcore's page and headers
and hand every other request to the library whole. After a 4.1 handshake AdvantageScope also opens
a timing-only connection (`rtt.networktables.first.wpi.edu`) and sends clock requests only there.
The gateway answers them with the main connection's clock, pings the connection like a 4.1 peer,
and keeps it out of the core and the client count, since it belongs to a dashboard already counted.

#### MessagePack

The MessagePack subset is written from the format specification, like the Arrow data writer, and
checked against hand-encoded bytes, including every integer width, floating-point bits, variable
length headers, and concatenated NT4 messages. It is separate from the existing MessagePack disk
cache library. Untrusted lengths, nesting, and message size are bounded, and unsupported extension
formats are rejected. The announced type string survives unchanged: a binary code chooses a decoder,
not a schema or an interpretation. Struct schemas are ordinary binary topics.

#### The client

The client uses Java 17's WebSocket to avoid another client dependency. Java-WebSocket 1.6.0
supplies only the server framing (140,686-byte JAR, MIT, with SLF4J already present), and its
license is retained under `META-INF/licenses/`. NT4.1 is preferred, with NT4.0 negotiated on the
same handshake. Only 4.1 connections receive WebSocket pings.

Initial time synchronization precedes subscription, and later measurements run every three seconds.
The clock estimate uses the newest minimum-RTT measurement in the last 30 seconds. Failed address
sweeps retry after 1, 2, 4, 8, then 10 seconds indefinitely. Each sweep tries the last successful
address first, then the others in configured order. Until a connection succeeds, sweeps start with
the first configured address.

The lossless listener sees every received value, including an older timestamp. Following WPILib's
protocol, the latest table keeps the greatest timestamp (ties replace), honors `cached: false`, and
removes values on unannounce or disconnect. The gateway keeps the same retained-value rule for a new
subscriber, while an `all` subscription receives every publication in arrival order. Native ntcore
interoperability is checked by the harness. Real dashboard interoperability remains the user's
manual check.

## Capture writer

The capture writer is the NT4 client's listener, so announcement, value, and finish records have one
writer and one order. Its pure-Java WPILOG output follows WPILib's file specification because the
native DataLog writer cannot run in the Java-only install. The independent fixture writer, the
differential reader, and wpiutil's reader check its bytes. Storage and live-index observers receive
complete writes and their byte offsets, and context providers can join this loop later.

#### Entry metadata

Announcement properties are preserved under `nt4_properties` in the entry metadata, separate from
the recorder's `source`, `robot`, and thinning policy. Property patches merge with null meaning
deletion and write WPILOG Set Metadata records. The writer then publishes the changed descriptor to
the live index; calls already in progress keep their earlier metadata snapshot. A fresh file scan
applies the same control records, and changes also seed the next rollover declaration.

NT4 does not timestamp its text controls, so these controls use the measured server clock at
receipt. Closed-file accounting snapshots count received data records, including their WPILOG
headers, and exclude declarations, control changes, identity context and schema seeds.

#### Startup and the store

An optional `capture` configuration starts this listener beside the HTTP server. Missing robots do
not delay HTTP startup. Shutdown drains tool calls, closes the capture, then retires log readers.
The capture store uses the existing store queue, path validation, move reservations, and manifests.
Creation runs asynchronously on the store queue.
Capture admission uses that queue's priority lane. If another process holds `store.lock`,
capture retries with 5–50 ms backoff outside the owner until its admission deadline; imports
still refuse contention immediately. A retry checks the deadline before entering publication;
if an admitted filesystem operation finishes late, the writer retires its result through the
existing abandoned-admission path.
The NT4 listener never joins admission. It retains events in arrival order while waiting, preserving
original robot, receipt and wall-clock times. The backlog is bounded to 65,536 events and 16 MiB of
accounted storage, including encoded/text payloads and per-event allowances, with a 30-second admission deadline. This is an
accounting limit, not an exact JVM heap measurement. Overflow or deadline expiry stops recording for
that boot with an immediate server-log reason and count of unrecorded pending events; a late path
is retired with an explained manifest end_reason, not opened as a healthy empty recording. A new
robot clock permits another admission attempt, after an older cancelled placement retires safely;
that wait uses the same bounded event policy. A disconnect already in the backlog closes the
finite recording at its original time after admission succeeds.

Backlog replay yields after 256 events or two milliseconds checked between events. A single
filesystem write/flush can exceed that slice; it is not a hard operating-system I/O deadline.
Provider context delivery waits for admission and backlog replay to finish; tails retain their
existing bounded pre-session buffers. Later manifest updates use immutable snapshots and coalesce
into one pending task: changed facts queue immediately, ordinary progress at most every five
seconds. Shutdown waits at most 30 seconds for the writer, pending admission and last close
snapshot, including its hashes. Unlike a normal buffered disconnect, explicit server shutdown
abandons a still-pending admission with an error/count and an eventual manifest end reason.
A timeout is logged for the next startup sweep.

#### Open captures and renames

An additive `open_capture` field represents a growing file without inventing a hash or weakening the
finished-file checks. At close it becomes a normal `files` member. Event and match facts are queued
when they change. Cosmetic renames wait for close and reader release on every platform: otherwise an
asynchronous directory move can race a rollover open or a mapping growth. Address directories carry
robot basis `address`, since an endpoint is not a stated robot identity.

Each file's `provenance.writer_identity` comes from the capture writer and is shared with the
live index. It survives finalization, recovery and copies; each new file, whether a rollover, a
same-boot reconnect or a new session, gets a fresh identity. Closed-file facts and pending manifest updates capture it
immutably, so a later rollover cannot overwrite an earlier file's proof. Publication uses the
existing metadata handoff and adds no per-value store work. Native follows compare the proof
through finalization; a legacy missing proof requires an explicit new bootstrap.

A device serial learned during recording is written as context in the current file. Its manifest
update is asynchronous; promotion waits for session close and reader release, like the event rename.
New sessions already use the known serial. A reader that prevents the move leaves the directory in
place until a later close or contact. Store moves keep old paths usable by tools, with the same
security and file-change checks as the destination.

#### Robot identity

Robot identity uses the resolver's metadata roles for `/SystemStats/SerialNumber` and
`/SystemStats/Comments`, including their `NT:` forms, wherever a log lives. Listing reads only the
first 2000 records; import inspection can find later identity. A log's own logged serial wins over a
connection's device serial, and disagreements are recorded in the manifest and server log.
`capture/context` reads the HAL's sources, records the device evidence as the first JSON context
entry at the start of every file, and the store remembers addresses and SSH key history by
serial. An address or host key can change; neither replaces the serial.

`robot_candidates` is only a hint: an exact logged team, sorted entry name/type set, or REV CAN
id/type inventory must match one known serial. Only store files participate, from fingerprints saved
by import inspection in the additive `robot_fingerprint` manifest field. Older manifests without it
supply no hint. Listing never opens a log to compute candidate evidence. Contradictory unique hints
are omitted. No candidate changes placement.

#### Recovery

The service queues recovery after HTTP is listening, then starts NT4 only when the sweep completes.
Completed local receipts can remain unpublished when the replica catalog is full. The store
classifies that outcome as `PublicationPendingException` only after a complete, safe receipt
sweep. Capture startup tolerates that specific publication outcome while preserving its
pending status; ordinary I/O errors, malformed publication intents and unsafe paths still
fail startup. The tolerance applies to publication refresh after capture recovery, not to
capture recovery itself, and never classifies a failure from its message text. A well-formed
intent whose proposed and previous hashes match no committed receipt retains the existing
uncommitted-attempt rollback behavior.
Scanning and hashing an abandoned capture cannot delay the daemon health endpoint. Each
`open_capture` is claimed with the same persistent sidecar lease as the writer, and a live writer is
skipped. The OS lock covers other processes, and the local claim avoids opening a second channel to
a held lock. The lease is separate from the data: closing mapped readers must not release a writer's
ownership. A dead process loses its lease.

Public capture progress is published separately from the live source's detailed health:
`CaptureProgress` contains only finite state/reason values, completed-write age and scalar
stall maxima. It exposes neither source paths nor raw errors. The HTTP probe reads published
facts without waiting for recovery, NT4 or the store owner. Listener and store interval
observations preserve completion racing a probe and leave cumulative metrics intact; every
health caller shares the interval boundary. A force-only close does not reset the last
record-write age, and stopped capture leaves process health `ok`. Store maxima retain the
action-duration scope above.

Recovery scans a readable file before hashing it, preserves an incomplete-tail flag and previously
closed files, and uses file modification time for `ended_at`, with `end_reason` set to
`server stopped while recording`. Failed reads leave the file open with a manifest/log reason.
Neither successful recovery nor a failed read changes the capture bytes. The sidecar inode stays in
place for later writers and is catalog metadata.

#### Sessions and rollover

Session continuity uses time-sync replies, rather than old retained topic timestamps. A continuing
clock continues the session in its next file, as a rollover does, because a closed file is never
written again: the store has hashed it, and on a format-2 store published that hash, which its own
reads are checked against. Appending to it made every later read of it fail and left its
publication pending. The next file starts with whatever the robot sends on reconnecting, including
its topics' last values at their original times, which can lie within the closed file's span. A
reset or a discrepancy beyond five seconds starts a new session. Flushes and five-minute topic cost reports run on the same injectable event
loop. Exclusion and thinning are explicit policy, and thinned entries record their period.

Each capture file is bounded by `capture.max_file_bytes` (default 1 GiB), including reserved
finishes. Rollover closes every active entry and starts the next numbered file in the same session
with fresh entry ids and a new live index. Retained schema definitions seed the new file, explicitly
marked in entry metadata and timestamped at the rollover's server time, so a file can decode its own
structs without inheriting the boot-time schema's range. Tools continue to read one file per call.

The writer records its own wall clock, `/Daemon/Clock/EpochTimeMicros`, so a capture whose robot
publishes no clock can still be placed in UTC. A time synchronization only makes a reading
pending; the next value written takes it at its own robot time, carried from the server's clock
when the reply arrived. Read at the synchronization's own robot time, a reading after the robot's
last value would widen the file's time range. Readings stay at least a second apart, so the
synchronizations' jitter never makes the clock run backwards. The entry is not provider context: it
is never forwarded to gateway clients, it is declared in a file only at its first reading, and a
reading that does not fit is left for the next file rather than rolling this one over.

A writer IOException closes recording with its reason and keeps the client connected. Only another
robot clock permits recording again. Record writes roll back an incomplete tail when possible, and
failed rollback forbids appending finishes. Session `end_reason` preserves the reason without
changing the store format version.

## Robot facts

`robot-facts` is an explicit operator command, separate from the continuously running providers. It
borrows the same SSH implementation and host-key policy for one connection, opens bounded exec
channels for a fixed read-only command list, and uses SFTP only to nominate the largest log for a
bounded hash-cost probe. Exit status, stdout and stderr remain separate, including permission
refusals. The pure report builder classifies evidence, not the image's assumed capabilities, and
unavailable commands do not abort the report. Known credentials and sensitive command-line options
are redacted at its output boundary. Named configurations use their store pins, and direct hosts use
a local pin store. No remote environment contents are collected.

## Live log

The capture writer already knows each declaration, complete record's offset, value, and timestamp.
`CaptureIndex` passes those facts to `LiveLog`; rescanning a growing file would duplicate that work
and repeatedly invalidate tool calls. Entries retain announcement order and WPILOG ids. Repeated
names and types, forward clock jumps, struct schemas, and decode problems follow the finished
reader's rules, so eviction and a normal file load preserve answers.

Each entry's records are columns outside the heap: each record's publication sequence, time,
byte position and payload size, and a number's value. The writer appends to the entry's tail, a
few primitive arrays, and writes each full tail of 512 records as one packed block to a scratch
file in the disk cache that is never forced to disk; a reader reads a block with one positional
read, so no mapping is released under it. When the disk cache is off, the recording's disk
reserve is short, or a block cannot be written, blocks stay in the heap, packed, and
`list_sessions` says why in `index_in_heap`. The writer publishes complete records, then a global
boundary containing the record sequence and data time range. A call captures
that boundary and takes each entry's length on first access, capped at the boundary. Its values,
entry table, and schemas therefore come from one consistent prefix. `inputs.session_time_range`
names that prefix in seconds, and `compare_matches` supplies a range per live input path. Data reads
take no writer lock. Short lifetime transitions and the existing file leases protect retirement.

A struct entry's numeric fields are columns too. The writer decodes each struct record once, with
the schemas a call reading that record decodes it with, and takes its numbers by the same walk a
finished file's column file uses: a column for each path, such as `.translation.x` or
`[2].angle.value`, holding each sample's record and value in packed blocks beside the entry's. A
field is then read by search, as a number is, at any zoom, and the entry's decode problems are
counted as its records are written rather than by decoding the entry on every call. When a
schema the struct depends on is first recorded, or another takes its place, a call from that
record on decodes every record with it, so the writer decodes the entry's records so far again
into new columns, which calls before that record never read. One recorded schema costs the
writer's loop at most 4,096 records decoded again, a few milliseconds, taken entry by entry in
the order they were declared; an entry past that budget is read from the WPILOG from that record
on. Schemas usually arrive before their values or within a few records of them, so the budget is
rarely touched. The WPILOG also answers for a struct array record wider than columns take, a
leaf whose kind changed, a pooled path once some leaf had no column, and an entry recorded out of
time order.

The default ten-minute hot window keeps the client's decoded values of strings, raw values, arrays
and structs, with array/struct conversion when a tool requests a whole value; a struct field read
from its columns needs neither. A number keeps none: it is
read from its column when its record holds it in its type's width, as every record the writer
encodes does, and any other record is read from the WPILOG like a string, so a malformed record
is still reported where it is. Expiry follows server time sync as well as new data, so idle topics age
out too.

The writer keeps its records in a 256 KiB buffer on the listener loop and writes them to the file
at once: at each 250 ms flush tick, when the buffer fills, and at close and rollover. Written as
they came, each value cost two writes and three requests for the file's position, about 4 µs, and
at ten times the soak's rate the loop waited in the kernel for over a second at a time; written at
once, they cost about 20 ns a value. A process crash loses what the buffer holds, at most one tick's records. After
the tick's write, the tick hands disk force to the writer's daemon thread, with only one force
outstanding. Its completion time and index/manifest notification are published back on the ordered
listener loop; close and rollover wait before closing the channel or replacing its file. The size
the store publishes for the open file counts only what the file holds, so a reader of the growing
file finds every byte it is told of.

A write of the buffer that fails leaves the file where its last complete write ended, and the
buffer's records are lost, although the live index already holds their values. The session ends as
a failed write, with no finish record after the loss, since a lost start would leave a finish naming
an entry the file lacks, and the index is let go once its last reader has finished, so the next
call reads the file.

Expiry follows completed flushes, at most four growth remaps per second even with a zero hot window.
A hot value stays until its record is in the file, and before discarding it the writer first covers
its complete record with a read-only mapping.
Older values use their offsets, and replacing a mapping waits for its last atomic reader reference
before unmapping it. The write channel remains open beside the mapping on Windows.

The manager pins active captures outside its evictable cache and supplies a fresh prefix per call,
without file-change checks or after-call discard. At close the same index enters the ordinary cache;
eviction releases its mappings after in-flight uses. A later load uses `LazyParsedLog`. Each file
has its own index, which is never written again once its file closes; its columns' file is deleted
once the writer has moved on to another file and the index's last reader has finished, and the
startup sweep removes the files an earlier process left.
Cold reads use the same long-addressed windows as finished logs, including records past 2 GiB.
Another process reading a growing capture uses the incremental scan described under Loading;
it has no access to this writer-owned live index.

#### The store door

Store listings use manifests without the stray walk, and a file block resolves only its owning manifest, so a transfer does not walk a season's catalog every 64 KiB. File reads hold a read lease, pin one length, and stream through a bounded buffer. The JDK HTTP client implements `sync.RemoteFiles` on the other end, and the same prefix proofs and transfer journal serve SFTP and HTTP. SFTP requires a clean EOF; peer sync applies the ordinary import inspection so an already imported power-cut tail keeps its declared note. Mirror ownership and remembered peer URLs are additive header facts, preserved by capture, import and identity updates.

#### Laptop uploads

Laptop uploads are byte streams into an HTTP-owned hidden inbox transfer. A sidecar lock prevents
the watcher from taking a slow upload, and abandoned transfers use its existing recovery. Cleanup
joins the same store queue, coalesced to one pending job. It never takes the store lock on the
watcher thread and cannot race an admitted import. Reception and source-hash verification happen
outside the store queue with a bounded buffer. `LogStore.importUpload` then uses the same
inspection, grouping and manifest pipeline as a local import. The network route selects a configured
store id and one filename, never a server filesystem path. Server-path imports and assignment stay
local to the machine.

#### Peer synchronization

Peer synchronization reads the catalog door with the JDK HTTP client and uses `FileTransfer` unchanged. `StoreSync` supplies the local placement policy: verify the advertised size and SHA-256, run the import inspection, then admit the file. Network pacing defaults to unlimited and can be capped per job. Open captures have no final hash and wait until closed; the door itself still serves their growing prefixes for the mirror. Mirrors refuse peer sync in either direction.

Overlapping windows with the same serial describe one boot. Taking the smallest session id makes both transfer orders converge without inventing another id. If a peer bridges multiple local fragments, closed directories move below one session's `merged/` directory, retaining their bytes and historical manifests. The catalog exposes the merged manifest once and old file paths remain aliases. Multiple still-open writers wait until closed before consolidation.

A capture flush replaces only its own files and retains peer files and match facts. Peer captures go under `peer/<sha256>/`, because a currently unused `capture-N.wpilog` name still belongs to the local writer's future rollover. The original file provenance stays intact, with an append-only `copied_from` history. Local nonempty robot names/comments win, and disagreements go to session conflicts. None of these additive fields changes format 1.

A sync's daemon staging worker owns its journal, downloads, pacing, hashes, inspection and
reader drains. The permanent `.sync/.owner.lock` excludes another sync process while releasing
`store.lock` between short publication turns. The loopback-only `POST /store/sync` job and the
offline command share that protocol; a second sync to the same store is refused. The command
never bypasses a failing running daemon. Each publication rechecks the store's identity and
role, selected session, duplicate inventory and prepared bytes before its receipt, atomic
rename and manifest change. Peer waits and payload copies do not occupy the mutation owner.

Each download reserves its remaining declared bytes before payload I/O. Cross-filesystem
placement and immutable system-text copies reserve their additional allocation on the actual
destination filesystem, then copy, verify and force off the owner. Transfers close on every
exit; response cancellation cannot retire a journal owner or reservation before its I/O
unwinds. Progress reporting, rate limits and job states remain attached to the same sync.

`store.json` remembers peer URLs. `.sync/peer-<store-id>/pull.json` owns incomplete bytes and
superseded attempts without exposing them through the door. Atomic placement and session-merge
receipts bridge filesystem moves and manifest replacements; the next sync recovers pending
receipts before network contact, even with the peer offline. Journal-owned incomplete downloads
and system-text prefixes remain available for proved resume; abandoned owned placement copies
can be removed. A completed cross-filesystem telemetry placement removes its original staging
bytes only after destination and source proof. Recovery performs that cleanup even for a
committed receipt, retaining a changed original with an explained refusal. An occupied
unmanifested path is kept as a stray, never adopted or overwritten. Results name copied bytes,
existing hashes, conflicts, refusals and the offset of an interrupted file.

#### Mirrors

`MirrorSync` now stops before network or payload work. Format-1 mirrors report
`migration_required`; format-2 mirrors recover journals, then use the versioned replica
protocol for peer offers and direct-master reconciliation. The old automatic scope/cap eviction engine exists only in test fixtures for
historical migration and read compatibility. Existing sessions, captures, clock records,
system-text prefixes, offsets, partial journals and unmanaged files remain unchanged.

Format 2 adds a bounded replica catalog to the atomic store header. Master files have UUID
file/version identities and size/hash/finalization records. A migrated mirror retains
`legacy_unconfirmed` entries: its old transfer-verified boolean cannot supply master identity
or authoritative confirmation. Legacy pins become acquisition inclusions. Read compatibility
accepts formats 1 and 2; new ordinary stores still use format 1 until explicitly migrated.
Older binaries reject format 2. The backup and preparation record are retained under `.replicas/`.

Migration hashes outside the store queue, retaining file leases and filesystem boundaries.
Two server-wide preparation slots, one per store and no waiting queue, remain charged through
publication or cancellation unwind. A short store turn rechecks identity and boundaries before
publishing the marker; future completions and mutation observers run after the store lock closes.
A format-2 retry retains its established UUIDs. The metadata and path-count limits refuse an
oversized migration without truncating old facts. Native power-loss durability is not yet proven.

Scope is an acquisition predicate. Robot filters combine with event OR date evidence, and
explicit `all` includes unknown dates. A capture is never acquired, closed or active; its clock
record is. Capacity counts held, partial, metadata and reserved logical bytes,
with metadata headroom inside the cap. Replacement must fit beside the incumbent and pins
cannot bypass admission. Cleanup previews identify exact entries, exclusions and affected views. M5 executes revision-bound
cleanup with durable receipts and read-lease retirement through the common publication owner. No admission decision deletes
existing data, even after master deletion or clock advance. See
[replica publication ownership](../ARCHITECTURE.md#replica-generation-publication-owner) for the
worker, journal, cancellation and logical-accounting boundaries.

A clock record holds the facts a closed capture has for placing other sources on robot time:
today, the roboRIO uptime samples that alone link kernel log times to robot time. It is derived
from the capture's bytes, so any store holding the capture can write it again, and it names the
capture's hash, so a stale record shows. The store writes records off its queue, because reading
a large capture takes seconds, and takes the queue only to check that the capture is unchanged
before publishing the record in the session manifest; a capture reopened meanwhile gets none
until it closes again. The rollover watermark belongs to the capture session, not the store
observer: a new session starts at zero even when the preceding session closed many files.
Only successful manifest writes advance it; reading and publishing clock records still run
asynchronously off the capture loop. Records are kept whole, not thinned, so a mirror's searches match the
pit's line for line. Peer sync copies no records. A server that records into a store writes them for
every capture the store holds, peer copies included, at its start; a store nothing records into has
none, and its own searches read the captures.

`MirrorService` publishes status snapshots; HTTP polling does not scan the mirror. Its timer never
stops: a tick that finds the transfer owner busy with another job starts nothing, leaves the status as
it was, and the next tick retries. What the service is doing and the state it reports are one value, so
a client that reads a completed state can always start the next job. Local controls
configure an admitted folder, report the gate, migrate explicitly, page migrated file records and
preview cleanup. Requests and replies are bounded to 64 KiB and 1 MiB. Imports and inboxes still
refuse mirrors. Stored offsets remain the source of read alignment; migration never re-correlates
logs. No mirror read asks the master to answer a tool.

## PhotonVision context in the capture

`context.photonvision` names explicit backends. The provider uses the JDK HTTP/WebSocket client
on its own worker; no backend I/O joins the NT4 loop or the store queue. PhotonVision v2026.3.4's
settings export is a ZIP containing SQLite. The archive is bounded, validated, hashed and
released; its live `UIPhotonConfiguration` stream supplies structured current settings without
adding a SQL/native dependency. The reader keeps only the requested camera facts, not network
credentials or the whole export. Unknown versions, required-field mismatches, outages and limits
stand down for the session, with state/reason and costs published through the existing provider
snapshot. A new session may try again.

WebSocket messages use the existing bounded MessagePack decoder. The next message is requested only
after the preceding snapshot is written on the ordered capture loop, so copied work stays bounded.
Per-message and archive byte limits, a camera-count bound and request deadlines give that
backpressure a stated outcome: stand down, retain the last recorded configuration. A snapshot's
timestamp is the robot time estimate captured at network receipt, before either worker queue; this
backend has no timestamp of its own.

Selective pipeline notifications omit camera identity in the pinned release. A replacement read-only
socket obtains a complete snapshot. The old socket is closed before that handshake because the
backend broadcasts full state to existing clients too. The provider never applies a delta to a
guessed camera or sends a settings mutation. Refresh starts are separated by at least one second per
backend. Notifications during that interval or a refresh remain one pending refresh, and completion
checks the generation counter so a change during delivery cannot be lost.

`analyze_vision` attaches `camera_settings` by exact camera name and records the settings entry
in inputs. The last snapshot before a requested window is held, and changes inside it retain
their receipt timestamps. Nothing captured after the window's end describes that window.
Calibration errors and pipeline modes are stated configuration, not quality inferred from
observations. Missing, differently named or unreadable settings report `none_captured` with a
reason. The pose tools currently report robot-level comparisons, not per-camera context.

## JVM context in the capture

`context.jvm` enables a JDK `JMXConnectorFactory` client for the connected robot address and
explicit port. The robot team's GradleRIO launch owns the listener; no agent, native code,
extra library or SSH command is installed. `JvmSample` reads the platform MBeans, keeping
counts cumulative and converting milliseconds/nanoseconds to seconds. Runtime identity,
version and arguments are read once per connection; sensitive property values are redacted.

`JvmProvider` keeps one I/O operation outstanding, on one daemon worker. A separate scheduler
judges its five-second deadline, publishes state and schedules refused connections with 1–30 s
exponential backoff. RMI is allowed to ignore interruption: a timed-out call retains its worker
until it actually returns, with a stand-down reason and no replacement workers. Cleanup uses
that worker too; capture, scrapes, tools, the store queue and shutdown never join it. Session
identity is checked at completion and again when the ordered capture loop admits delivery.
This prevents a sample taken in one boot from appearing in the next after a backlog.

Receipt captures the NT4 estimate on the I/O thread, before either queue. Every numeric sample
includes JVM uptime, FPGA-minus-uptime offset and the JMX-poll-plus-NT4-round-trip bound (plus 1 ms
uptime quantization). Changes larger than both adjacent bounds produce a note; timestamps already
written remain fixed. Missing NT4 synchronization drops and counts a sample.

JVM start time is identity metadata only: OpenJDK's cached start time plus monotonic uptime does
**not** follow later wall-clock changes. No JVM startup-anchored wall timestamp follows the
Driver Station correction, and this provider does not detect it. SSH stats pair kernel uptime with
FPGA time; corrected wall-clock evidence comes from pulled-log `systemTime`. The separate
`robot-facts` clock probe also samples uptime beside `date` for the shop. Flight Recorder's future
mapping must use its actual event clock; its streaming half awaits the runtime module probe.

`ClockNote` carries both offsets, both bounds and both JVM start identities without diagnosing the
cause. A sink failure latches stand-down for that session, including across a same-boot
reconnect; a new session resets it. This avoids retrying a delivery whose partial write is unknown.
[PIT_SERVER_PLAN.md §8.3](PIT_SERVER_PLAN.md#83-the-robot-programs-jvm) records the JDK 17
chunk-clock evidence and the measurement still required before JFR streaming.

`ContextProviders` writes these results through the same context hook and live index as the
other providers. Published provider snapshots carry state, period, round trip and cost without
requiring readers to join a worker. Sample bytes mean typed value payload bytes, excluding
RMI/WPILOG framing, since the public JMX API exposes no wire-byte counter. Record bytes are the
writer's actual cost. No state is added to metrics or live tools.

## SSH context in the capture

The providers need one authenticated connection per host, not one per feature. `ssh.SharedSsh`
owns connection attempts and backoff on a host worker. The puller owns a borrowed SFTP channel,
stats own one exec channel per sample, and each follower owns a long-lived channel and reader.
NT4 presence admits them all; only file pulling has the disabled-state gate. First contact
records identity even with pulling disabled. Pin writes and identity promotion use the store
queue, while provider readers never wait for it.

`SharedSsh.Host.identity` owns one device-identity attempt per authenticated connection.
Context and borrowed pulls share its in-flight or completed result, including an unavailable
identity. Atomic admission picks one reader, which performs SFTP on its requesting worker;
contenders wait on their own workers. No I/O runs under a lock, and connection lifecycle work
does not wait for the read. Reconnect creates a fresh attempt and a returned identity is
checked against the current connection. Provider phase snapshots bind state and reason to
that SSH connection and NT4 episode. A newly connected host reports its identity wait before
the sampling worker starts, and old replies cannot overwrite its replacement's state.

`ProcStats` parses explicit Linux ticks, pages and KiB; no unit comes from the pit computer. Busy
CPU excludes idle/iowait and does not count guest time twice. Rates need two monotonic kernel
samples, and program CPU needs the same PID and start tick. Missing-program discovery retries every
ten samples; only a previously known process changing requests an immediate next-sample lookup. The
persistent reason is logged on state change, not on each sample.

`StatsProvider` captures the NT4 offset and monotonic send time before exec, so an estimate arriving
with the reply cannot retroactively timestamp the request. Round-trip cost adapts the period from
its base up to 30 seconds and back down. The kernel/FPGA pairing and provider costs are additive
manifest fields, with no store format change. No robot CPU cost is attributed to this shell command.
Numeric quality and data-endpoint views respect `sampled: true` metadata even when adaptive periods
are irregular.

`ContextProviders` hands bounded results to the writer's ordered context hook. It declares
`/Daemon/` entries, retains metadata changes and rollover declarations, and feeds the same live
index as topics. The latest-value map adds their source; HTTP tools and metrics read published
facts without joining provider workers. Tails accept 200 lines per second per file, bound
pending history to 1000 lines and individual lines to 64 KiB, and record losses. Buffered
pre-session receipt times use the next measured offset, clamped at zero with a metadata note.
Missing sources stand down for that session; SSH loss resets channels without inventing text.

## Pulled system text

`capture/pull.SystemPullPass` is the second phase of the existing pull worker, using its shared SSH
contact, disabled/connected gate and byte budget. SFTP files reuse `FileTransfer`; exec snapshots
spool one block at a time with an external read deadline. Unchanged passes retain content proofs
instead of hashing the same files on the robot every few seconds. A closed gate closes the exec
channel: a full JSch input pipe otherwise blocks its shared network reader and prevents even another
channel from opening. The next snapshot starts from the committed cursor, and SFTP keeps its held
prefix. A real-SSH regression caught this by opening a hash channel beside a paused ring-buffer
reply.

`store.SystemPullStore` prepares immutable snapshots on the pull worker, retaining the source
lease while it reserves the second allocation, copies, hashes and forces a private destination
stage. A short store turn rechecks source, selected session, text receipts and destination,
then atomically renames the copy and records it. The journal-owned prefix remains available
for proved resume and rotations. Kernel/journal append lengths and cursors still commit
together, and an interrupted append is truncated to that receipt before retry; those
unknown-length command appends are outside this copy-admission change. Kernel and journal
clocks remain different even when their messages happen to agree.

`store.SystemPullStore` owns a parsed inventory and catalog snapshot for its connection.
File/directory metadata detects manifest replacements, placements and session changes; an idle pass
neither parses history again nor uses a timer to decide that it is stale. A changed manifest alone
is reparsed.

`SystemLogState` is an additive session-manifest field, separate from telemetry files. Source,
remote path, pass time, size and hash explain each copy. Shared syslog files belong to the robot:
`SystemLogIndex` records them once in `robots/<serial>/system/index.json`, with written calendar
spans, instead of updating every historical session. Search selects overlaps or unknown spans, while
honoring legacy per-session shared receipts. The index supplies the newer committed prefix when both
describe the same path. Per-session kernel and NI files move with their directory. Provider
snapshots retain observed program PIDs so crash placement reads manifest facts, not every capture.

`tools.SearchSystemLogsTool` reads receipts without joining the store queue or contacting SSH, uses
the ordinary move lease and a fixed byte prefix, and names every text/clock input. `SystemLogClocks`
interpolates measured uptime pairs and reads recorded wall clocks; missing evidence produces null
and a reason. Severity comes from the same classifier as `search_strings`. The pulled file is exact
text; tail entries are a timely receipt-time copy.

The catalog-backed door exports session receipts and the shared index, with the same range,
prefix-hash and path admission as telemetry. `SystemPeerSync` uses `FileTransfer` for held-prefix
proof and pacing; text verifies by the receipt's hash and size instead of a telemetry decoder.
A separate reservation covers its immutable destination copy while the journal retains the
downloaded prefix. Hashing, copying and forcing run off the store owner; the publication turn
rechecks source and metadata facts before its atomic rename and receipt update.
A durable placement receipt recovers a copy interrupted before manifest publication. Mirror
sync uses the same checks and keeps origin receipts, counts shared bytes once toward capacity,
and releases them only after their last retained session and proof the origin still has them.
Consolidation carries each fragment's text receipts alongside its telemetry under the owned
`merged/<id>/robot/system/` structure; old capture paths still find their original manifest.
Search never fetches missing companions: it reports `not_applicable` with the collecting server.

### Pit server clients in the extension

The local and pit MCP clients have separate sessions. Only the local client registers the
window's directory and key leases. Remote editor URIs retain the server URL and exact remote
path, plus session identity for offline fallback; a remote Windows path is never interpreted
as a laptop path. The mirror settings and user actions go to loopback controls on the local
server. The peer picker reads remembered URLs from the store, so different windows share one
history. Neither action rewrites the home configuration.

Follow polls the current log once per second while its editor is visible. Numeric tails replace
the inclusive timestamp boundary, preserving duplicate timestamps without duplicating a poll.
Typed arrays are bounded by the plot budget and retain a preceding hold sample. Console follow
pages a fixed time range without collapsing repeats, retains the latest 500 matches, and
advances only after the final page. Offline fallback requires origin, session id and relative
file path and labels the copy; it never chooses by a similar basename.

### Live publication tools

`tools/LiveTools` is registered on every server and answers `not_applicable` without capture. `capture/LiveCapture` publishes immutable
recorder status and an in-memory catalog of session and robot manifests. Store writers notify
it on their queue; startup and completed imports refresh the manifest inventory. A tool neither
opens a file nor joins that queue, so an import cannot delay a latest-value query or recording.
`capture/CaptureStats` is an additive format-1 manifest field, retaining value counts and costs
for closed sessions; older manifests report unknown counts. An open session is a matching
candidate even before its first file has a final hash.

The NT4 client's concurrent latest-value table keeps each value, timestamp and authoritative type
together. A concurrent unannounce and redeclaration cannot relabel an already-read value with the
next topic's type. Multiple requested entries are independent latest publications, not a
simultaneous robot sample. Ages use its measured robot offset and monotonic clock. Waiters are keyed
by topic and MCP session, claimed once by the ordered listener, then completed outside the small
registry lock. The injected scheduler supplies deadlines. Disconnect, unannounce and capture end
cancel waiters, and duplicate waits return an explained error. The ordinary log tools retain their
consistent-prefix read of the writer-built index.

Proxy credentials follow `config/ClientLeases`: a SecretStorage value belongs to one local
MCP session and one HTTP origin. `sync/HttpRemoteFiles` uses it for store reads without
redirects. `mcp/PitMcpEndpoint` forwards only the exact leased MCP endpoint for Claude's URL
bridge, keeping secrets out of registration commands and files. Registration and forwarding
are loopback-only; neither is an assistant tool. The extension's `pitCredential.ts` holds
origin selection and bridge URL logic; VS Code input and secret storage stay in the glue.

## Metrics are a sampled view

`mcp/MetricsEndpoint` renders Prometheus 0.0.4 from `LiveCapture` publications and platform
MBeans. It owns no history or counters. The recorder publishes costs at flush; the pull
worker publishes copy/verification progress; time sync publishes an immutable window so
reads cannot join the NT4 writer's monitor. The HTTP thread never queues work on either
capture or store, reads files, or contacts the robot. Ordinary HTTP servers expose JVM and
inactive-component state even without capture. The gateway supplies its published client count
through the component snapshot interface; provider costs remain absent until providers exist.

Topic types travel atomically with latest values. Only published struct schemas enter the
metrics decoder, including nested dependencies: a dashboard cannot state the assumptions
of a fallback schema beside every number. Shared field paths name declared numeric leaves,
with bounded arrays; enum fields carry their stored number and booleans 0/1. No sample has
an exposition timestamp: the robot clock is not calendar time. Ages report staleness, and
capture remains the record of changes a scrape missed. The independent parser and replay
oracle compare every numeric topic with source records, using WPILib's DynamicStruct rather
than the server's compiled decoder for structs. The Compose example stays outside the server.

### Relative scopes and discoverable live state

`LogRequiringTool` resolves `last_seconds` once on the acquired log view, before delegating to
any tool. `LiveLog.View` captures the NT4 estimate at acquisition; closed views use the last
record. The window and the recorded session prefix are distinct facts in `inputs`. The
multiple-log comparator applies the same resolver separately to each log. No scrape, tool or
resource joins the NT4 loop to read its clock. `pit://session/current` exposes the live tools'
published identity, file, gateway and provider facts through ordinary MCP resource discovery.

The SSH stats worker discovers the deployed JAR's PID/start ticks and tick/page constants once
per connection. `ProcStats.Configuration` retains resolved real directories alongside those
units. `StatsCommand` owns both live and diagnostic command construction, deduplicates the
physical directories before df, and supplies the same steady/proc/df parts to robot-facts.
Its sample reads the fixed proc files and checks the start ticks, rediscovering
only after the process disappears or changes. Steady samples use shell builtins plus one `df`
for all filesystems; a changed filesystem set omits free-space values with a reason instead of
attaching a row to the wrong path.

A single-thread loop serializes like a lock. Its blocking work is a budget: keep it only where
ordering or backpressure requires it, with a stated outcome. Capture record writes still order
records; periodic `channel.force` runs on the writer's worker, and close/rollover are durability
barriers covered by capture's 30-second shutdown bound. SSH connect/channel opens have five-second
deadlines; stats exec has a 30-second bound, on its own worker. No SSH request runs on NT4.
`LoopWatchdog` posts at most one probe and measures it from a separate thread, logging a stall
of at least a second once, then recovery. It neither disconnects a peer nor changes timeouts.

The failure behind this rule was the pair of Windows keepalive drops of October 8 recorded in the
pit plan's decision record (Milestone 11 choices): delayed application loops judged healthy peers
dead, with capture fsync a likely source of the delay. A loop must never charge its own backlog to a
peer. Pong receipt and replies stay on network threads; only a sent, unanswered ping can expire. A
check scheduled on the stalled loop cannot detect that loop's stall while it is happening.

## Code map

| Package | Responsibility |
|---|---|
| `nt4` | NT4 control/value records, the spec-written MessagePack subset, type mapping, and time-sync arithmetic; no network or file I/O |
| `nt4/client` | JDK WebSocket connection and fallback, ordered listeners, subscription, retry/keepalive timers, and concurrent latest values |
| `nt4/server` | Pure subscription/announcement/value fan-out and a bounded WebSocket adapter, used by configured capture and the loopback robot fixture |
| `ssh` | One authenticated connection per host, pinned keys, bounded exec channels and independent SFTP/follow channels; reconnect only during NT4 presence |
| `capture/pull` | Disabled-state gate, borrowed SFTP/exec channels, telemetry and system-text transfer passes outside the NT4 loop; the operator's read-only `robot-facts` command and pure evidence report |
| `capture/context` | HAL device identity, parsed Linux stats, adaptive sample budget, bounded followed-file buffers, pinned PhotonVision configuration, JDK JMX sampling and published provider state |
| `capture` | Pure-Java WPILOG output and writer ownership leases, session continuity and policy/cost accounting; the live index and `LiveCapture`/`CaptureStats` publication snapshots for `tools/LiveTools` and metrics |

## Gateway work limits

The gateway checks aggregate receive
bytes and fragment counts before accepting each continuation, including an unfinished message.
Fan-out admission reserves at most 32 MiB and 65,536 tasks before copying payloads. Shared upstream
work is charged to every view; each peer's private requests divide the remaining capacity fairly.
A peer that exceeds its own allowance is disconnected without spending another peer's allowance.
The item bound admits a second of 100 topics at 50 Hz (5,000 records), unlike the former 4,096 cap.
A global stall fallback requires five seconds without fan-out progress; that monotonic bound is
a constructor parameter. Admission observes the stall outside the loop. A refused upstream
publication separately reserves one coalesced cleanup, outside the full work budget, to disconnect
every view when the loop resumes. This also applies below five seconds: a change-only topic may
never publish again, so keeping a connected view after losing its update would hide stale data.
Short stalls that fit the budget drop nothing. One reserved cleanup per refused peer, or one
global cleanup, releases the view; capture never waits and recording is unaffected.

## Integration dependencies

| Dependency | Why it is used |
|---|---|
| The JDK's WebSocket client | The NT4 capture client. |
| Maintained JSch (BSD-3-Clause, with ISC jBCrypt) | SSH connection and channels in `ssh`; JDK 17 supplies Ed25519 and RSA SHA-2. |
| Java-WebSocket (MIT) | RFC 6455 framing for the NT4 gateway and its loopback fixture; confined to `nt4/server`. |
| Apache MINA SSHD and EdDSA (test only) | A synthetic roboRIO's SSH/SFTP server on loopback, including actual host-key negotiation and exec channels. |

## Simulation runtime

The separate `harness/robot` GradleRIO project runs a headless WPILib simulation with its own
desktop natives. It is outside the server build and ships in no install. The opt-in shop harness
connects it, a temporary SSH device and the packaged server, and checks HTTP MCP results against
a timeline; [DEVELOPMENT.md](DEVELOPMENT.md#the-shop-harness) gives its scope and hardware limits.
