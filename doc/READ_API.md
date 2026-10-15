# Reading logs in another application

The shared read API is for applications that need log data without MCP analysis or an installed
server. It reads ordinary WPILOG files and manifest-backed stores, including mirrors. A local
Java reader and a remote HTTP reader use the same queries, values and explicit resource lifetime.
Arrow is an encoding of that shared data; local analysis does not encode and decode Arrow just to
read a number. The existing MCP tools and `/data/entries` keep their own established response
formats, full-entry diagnostics and units.

## The Java library

Build with `./gradlew readerJar`. The result is `build/libs/wpilog-reader-<version>.jar`, a
self-contained Java 17 library. It contains the shared reader, schema decoder, catalog models,
REV decoder, Arrow writer and remote client. Bundled WPILib, Gson, FlatBuffers, Arrow-format and
SLF4J classes are relocated below `org.triplehelix.wpilogmcp.reader.internal`, so the embedding
application's versions cannot shadow them; the WPILib BSD notice ships in both JARs. It contains no
server bootstrap, log manager, NT4 client, SSH provider or native binaries. This task builds locally;
it does not publish a Maven package or change the release assets. The Java surface is experimental:
public types returned from `log`, `log.struct`, `store`, `sync` and `revlog` are part of this
artifact's supported model, rather than requiring a server JAR. The catalog wire currently
reflects those records with snake-case fields; a Java component rename is therefore a
wire-contract change, and consumers must tolerate additive fields.
`RemoteLogAccess.catalog()` returns `CatalogDescriptor`; a remote snapshot's `descriptor()`
returns `SnapshotDescriptor`. These are immutable public models with server paths kept as strings.
Neither signature exposes Gson or another relocated implementation type.

```java
try (var access = LogAccess.local(List.of(Path.of("/data/logs")));
     var snapshot = access.open(Path.of("/data/logs/example.wpilog"));
     var cursor = snapshot.read(ReadQuery.window("/voltage", 10_000_000, 12_000_000, false))) {
  for (;;) {
    var batch = cursor.next();
    for (var sample : batch.samples()) {
      // timestampUs and recordOffset are longs; ordinal is the original per-entry record index.
      // value retains its declared integer, floating, boolean, string, array, struct or byte type.
    }
    // Keep batch.problems(): a malformed selected record is reported, never silently counted.
    if (batch.complete()) break;
  }
}
```

Import these classes from `org.triplehelix.wpilogmcp.read`. Roots must be explicit and nonempty;
paths are checked with symlinks resolved. The reader does not load home configuration, install a
server, adopt files or write a cache. `ReadOptions` sets the sort-workspace budget, maximum encoded
record size and mapping window size. [ReadLog.java](../examples/read-api/ReadLog.java) is a complete
consumer. Compile it with only the reader JAR on `javac -cp`, then run it with that JAR and the
example's directory on `java -cp` (a colon on Unix, a semicolon on Windows).

A snapshot exposes declarations, metadata, sample counts, schema texts with their logged, WPILib
or assumed basis, file boundary and truncation facts. Entry summary times retain the existing
reader's double seconds; samples carry exact signed integer microseconds. A query names one entry
and zero or more sorted, disjoint windows. No windows means all records. Right ends are exclusive
unless `endInclusive` is true. `TIME` sorts by exact timestamps with original ordinal as the tie
break; `FILE` preserves record order. Native opaque values keep all bytes. Structs use the schemas
and derived-field conventions described in [ARCHITECTURE.md](ARCHITECTURE.md#decoding). Unsigned
64-bit struct fields use `BigInteger`, including low values, so one field keeps one exact type;
unsigned enums use `log.struct.UnsignedEnumValue`. Legacy tool decoding remains unchanged.

Each batch visits at most 4,096 records and targets at most 4 MiB of encoded payload. File-order
records outside a requested window still count as work. One larger record may occupy a batch on
its own, up to the explicit record limit (64 MiB by default). These are work and encoded-byte
bounds, not a total heap quota: decoded structs and arrays can use more memory. An unordered
entry's time-order index uses a bounded shared workspace; use file order when that workspace
would exceed the configured budget. `validate(entry)` explicitly scans every record of that
entry; narrow native queries report only failures among the records they select.

`withRecordLimit(bytes, ReadQuery.LimitAction.PROBLEM)` explicitly lowers the per-record payload
ceiling and returns `RESOURCE_LIMIT` problems at physical offsets instead of failing the cursor.
Oversized records count as visited and their header sizes count toward payload bytes, without
decoding their values. They are explicit gaps, not samples. `ERROR` retains the normal refusal;
omitting the policy keeps the snapshot's limit and normal refusal. A query cannot raise the
snapshot's cap. Projection, reduction, HTTP and session reads preserve the policy.

Always close cursors, snapshots and the access object, preferably with try-with-resources.
Closing a snapshot prevents new cursors but lets admitted cursors finish before its mappings are
released. Closing a cursor cancels it. Use each cursor from one reader thread. A changed closed file fails with `FILE_CHANGED`; discard
that operation and reopen. Attribute checks cannot detect a rewrite that preserves the file's
identity, size and modification time. A server-owned live snapshot instead fixes the published
prefix; subsequent appends do not extend it. Reopen to follow new server data; local append-following is described below.

## Projection, reduction and several entries

`ReadQuery.project("translation.x")` selects a nested struct field; `[2]` selects an array
member and `[*].speed` selects a field from every member. This is the same `FieldPath` grammar
used by MCP and the legacy data endpoint, with at most 64 steps. A missing field or array member
is null. Projection changes the value, not its timestamp, offset or original ordinal.

`reduce(maxPoints)` requests numeric min/max/mean buckets, with 1..4,096 buckets per window.
It requires time order and a scalar numeric value, optionally selected by `project`. Each bucket
reports `start_us`, `end_us`, `count`, `finite_count`, `min`, `max`, `mean`, `first` and `last`.
Non-finite values count as observations but not finite measurements; an unavailable or overflowed
measurement is null. The membership rule is `floor((timestamp-start)*maxPoints/(end-start))`,
capped at the final bucket; integer boundaries round up. The inclusive final timestamp belongs
to the last bucket. Empty buckets are omitted, and separate windows never share a bucket.
With no windows the entry's first and last timestamps define the range. Reduction preserves
input accounting and diagnostics even when a batch produces no output. A bucket's timestamp is
its start; offset, ordinal and optional source coordinates identify its first contributing sample.
Those coordinates do not assert that the other contributing samples came from the same file.

`snapshot.readAll(List.of(queryA, queryB))` admits up to 64 explicitly named queries together.
It yields independent tables in caller order, with `queryIndex`, `entry` and each `SampleBatch`;
there is no implicit timestamp join, interpolation or resampling. Close the multi-entry cursor
as one resource. Closing the snapshot after admission does not interrupt those reads.

## Following a local growing file

`LogAccess.local(...).follow(path)` creates a caller-polled `LogFollower`; it starts no thread.
`refresh()` returns INITIAL, UNCHANGED, APPENDED or REPLACED with the current borrowed snapshot,
its `scannedFrom`, `resumePoint` and previous entry counts. The follower owns that snapshot;
close the follower when finished. An APPENDED update's `readNew(entry)` reads new original
ordinals in file order, including duplicate or backward timestamps. It does not mistake an old
maximum timestamp for the append boundary. A held cursor retains its old fixed prefix.

Growth resumes only with the same filesystem identity and unchanged header and last-complete-record
anchors. A partial final record is retried from its beginning on the next refresh. Replacement or
an anchor change starts a fresh scan; a rewrite preserving identity and both anchors is outside
this inexpensive check. The follower maps the new file and copies the index rather than scanning
the old prefix again. Ordinary snapshots retain their stricter file-change behavior. Remote
following uses a new server snapshot; the local follower is not a network subscription.

## Stores and recorded REV alignment

`read.catalog.StoreReadCatalog` takes explicit store roots. `snapshot()` reads existing manifests
and returns robots, sessions, completed and open telemetry files, provenance, mirror origin and
shared system-log index receipts. Invalid manifests are named in `problems`; readable inventory
remains available. Strays, inbox files and control manifests never become readable telemetry just
because they have a familiar filename. Store/session IDs and recorded file paths come from the
manifest; durable moved-path aliases resolve to current catalog members.

An access owner reuses that inventory while the identities, modification times and sizes of its
manifest, directory and payload dependencies agree. Directory changes discover new manifests;
atomic replacements and growing files invalidate the view. Reads still validate the requested
path. This avoids reparsing every session or resolving every payload path on each open. As with
loaded logs, an external rewrite preserving all those attributes is outside this cheap check.

Use `openWpilog(path)` for a manifested WPILOG. `openRev(companion, anchor)` uses only the companion's
recorded association with that WPILOG and its recorded offset, drift and confidence. It does not
run correlation, change alignment or write a synchronization cache. `rawValues(signal)` keeps the
REV clock; `values(signal)` applies the recorded mapping. Missing or corrupt proof leaves the
companion raw-readable with an `UNSYNCHRONIZED` state and reason; a converted read is refused.
Mirrors use their recorded facts unchanged. System text remains text with its own receipts and
clock basis; it is not silently combined into telemetry entries.

`openSession(storeId, sessionId, anchorPath, Overlap.REJECT)` creates a time-ordered union on
an explicitly chosen WPILOG clock. It includes rollover files from the anchor's recording run and
files with a recorded alignment chain to that clock. A recording run requires `captured` provenance,
the same recorded original start time and the same collecting store (preserved through peer copies
and mirrors). Session membership alone is insufficient: peer sync can consolidate independently
recorded sessions. An older session-level alignment without an anchor hash applies only when the
manifest has one captured recording clock. Files without proof are listed in `excludedSources`,
not given a zero offset. Identical SHA-256 files are included once. Overlapping recordings of an entry are
refused unless `KEEP_ALL` explicitly keeps both, and conflicting types, metadata or schemas are
refused. Each raw sample retains its source path and original timestamp beside its mapped time.
This uses manifest facts, performs no new matching and changes no file. Corrupt alignment is an
error naming the manifest. A union admits at most 64 files, with an 8 MiB shared encoded-payload
lookahead allowance; read files individually if it is exceeded. It requires time order, has no
single `first_ordinal`, and requires explicit anchor-clock windows for reduction. Narrow windows
seek on each source clock with conservative rounding margins, then filter exact mapped timestamps;
they do not decode the whole entry. Extreme drift coefficients that cannot be safely inverted
fall back to a complete read.

On the server, session members borrow the same managed cached indexes and writer-owned prefixes
as single-file snapshots. `live` is true if any included member is a live prefix. Appends leave an
existing prefix readable; reopen the session to see later samples. If an entry exists in both a
live member and another member, `KEEP_ALL` is required: the manifest can lag the live prefix and
cannot prove disjoint spans. A standalone catalog excludes
manifested open captures by path with a reason; an open anchor is refused. Use the recording
server for that session, or `LogFollower` for an individual growing file.

A catalog limits retained session indexes to 128 MiB of address fields in total, in addition to the
64-file limit. The `StoreReadCatalog(ReadOptions, LogAccess, long)` overload configures this budget
and optionally borrows an access owner without closing it. Charges remain until the session and
its last admitted cursor close, even after catalog retirement. One session admission runs at a
time; another gets retryable `BUSY`. One cold member index may be constructed before its size is
known and refused if it exceeds the remaining budget. This bounds retained indexes, not all heap
usage: decoded values, live hot data, schemas and temporary scans have separate costs.

REV shares the existing decoder, with selective retention for native signal queries. Close each `RecordedRevRead`, preferably with
try-with-resources: concurrent readers of one file version share an immutable decode, while
each read applies its current manifest alignment. One cold parse runs at a time per catalog;
a different cold selection returns retryable `BUSY` while it runs. The owner retains at most eight idle/active
versions under a 512 MiB accounting budget. Active reads remain charged; idle entries may be
evicted. Admission limits input to 64 MiB and reserves memory before decoding. Native REV values
retain pairs of primitive doubles in immutable 64-sample chunks, instead of a record and boxed value per
sample. Charges cover each chunk and signal, two input buffers and 1 MiB of decoder metadata;
there is no fixed four-million-sample cap. The 512 MiB budget still bounds decoded storage and
includes active reads; a sufficiently large file or crowded cache can still be refused. This is
an allocation estimate, not a JVM-wide heap quota. `openRev` also accepts a set of exact signal
keys. It decodes only those signals, plus the first model value needed to establish a SPARK's
published device identity; another device or model cannot satisfy the selection. An HTTP signal
query requests this selective path. Describe still decodes all signals, and a retained complete
decode can serve a later selection without reparsing. Selection saves decoded storage; it does
not remove the 64 MiB input bound or make the compressed input a seekable signal index.
The native input read remains bounded if the file grows after admission. A resource refusal
is explained, never a partial success. Its timestamps remain the decoder's double seconds;
the API does not invent exact REV record offsets or lossless integer timestamps.

On a named server with the [experimental data-store feature enabled](STANDALONE.md#keeping-a-data-store-up-to-date)
and an opted-in data store, the HTTP catalog adds `preferred_store_id`.
That preference describes the shared server's browsing default; it does not rewrite explicit
paths, hide catalog members or change a standalone library caller's configured roots.

## HTTP version 1

Every HTTP server exposes `/read/v1` under the same Origin and path admission as its existing
read routes. The transport is unauthenticated: keep it on loopback or a private trusted network.
Native snapshot opens do not start REV discovery, correlation or disk-cache writes; legacy MCP
and `/data/entries` acquisition still does, including after a native read cached the file.
Store reads require a configured store catalog; session-leased loose directories alone do not
admit a store, unlike the legacy data route. These routes allocate temporary reader resources;
they cannot import, move, assign, change alignment or otherwise mutate a store. No MCP session or tools call is required.

| Route | Purpose |
| --- | --- |
| `GET /read/v1/catalog` | Manifest inventory, stable IDs, provenance, mirror and shared-text facts, per-manifest problems |
| `POST /read/v1/snapshots` | Open one fixed WPILOG prefix; body has `path`, or `store_id`, `session_id` and `file_path`; use `anchor_path` instead of `file_path` for a session union, with `overlap` as `reject` (default) or `keep_all` |
| `GET /read/v1/snapshots/<id>` | Read the retained descriptor |
| `POST /read/v1/snapshots/<id>/query` | Stream one query or a `queries` array in typed NDJSON (default) or Arrow |
| `DELETE /read/v1/snapshots/<id>` | Release the snapshot; admitted reads may finish |
| `POST /read/v1/rev/describe` | Discover a companion's signals and recorded alignment |
| `POST /read/v1/rev/query` | Stream one REV signal on its explicitly selected clock |

The descriptor includes `api_version`, `id`, `path`, `live`, `boundary`, `entries`, `schemas`,
`sample_counts`, `index_bytes`, `truncated`, `damaged`, `truncation_message` and
`idle_timeout_sec`; a store member also carries its manifest facts. A union adds `sources` and
`excluded_sources`. `live` explicitly means a writer-owned prefix: a null boundary alone does not
mean a live recording. Up to 32 snapshots are
retained per transport, with 60 seconds of idle lifetime. An expired ID returns 410 and must be
reopened. An admitted request is active use: it prevents idle expiry, and the idle clock restarts
when the request finishes, including a failed or disconnected stream. Explicit DELETE prevents
new requests while already admitted requests finish. Close promptly instead of relying on expiry.
Streaming uses the separate, bounded workers and progress deadline described below. The Java
client also bounds each frame read with a deadline. Paths are server-owned strings: a client
on a different operating system must not reinterpret a Windows path as a local file path.

A query body is explicit about its units:

```json
{"entry":"/voltage","order":"time","windows":[{"start_us":"10000000","end_us":"12000000","end_inclusive":false}],"batch_records":4096,"batch_bytes":4194304,"format":"arrow"}
```

Optional query fields are `field` (the projection path), `reduction: {"max_points": N}` and
`first_ordinal` (a per-file entry ordinal, useful for append-only reading). Optional
`record_limit: {"max_bytes": N, "on_limit": "problem"}` selects the explicit gap policy above;
`N` is a positive integer and `on_limit` is `problem` or `error`. For several entries,
send `{"queries":[queryA,queryB],"format":"ndjson"}`. Each NDJSON batch then names `query_index`,
`entry` and `entry_complete`; `complete` means the final query is complete. Multi-query Arrow is
`multipart/mixed`, one independent IPC stream per query with an `X-Query-Index` header. Every part
needs its own successful terminal status, and the complete response needs its final MIME boundary.
A failed part stops the response rather than labeling later rows with the failed query's schema.

Integer timestamp coordinates and byte positions in JSON are decimal strings, so JavaScript
cannot round values above 2^53. NDJSON sample values have explicit tags: `i64` and `u64` as decimal text,
`f32` and `f64` as hexadecimal IEEE bit patterns, `bool`, `string`, base64 `bytes`, `enum` with
its numeric value and nullable label (`enum_u64` for unsigned 64-bit enums), and typed arrays,
lists and named struct fields. Negative
zero and non-finite floating values survive this native interface. They are not converted to
MCP's JSON null policy. The request body is bounded at 64 KiB, JSON/NDJSON frames at 16 MiB and
nested values at 64 levels; a limit is an explained refusal, never a truncated value. Arrow
batches instead follow the encoded record and batch bounds above. NDJSON may split a source
batch into smaller frames to fit that budget. Samples retain `payload_bytes` from their record
headers; visited-record and payload-byte totals are preserved exactly across the frames.
Diagnostics and filtered-record accounting may occupy a final empty frame. A single value that
cannot fit alone is refused; use Arrow for that value. A complete batch still needs the stream's
terminal proof.

Arrow uses the existing IPC writer with four columns: `timestamp_us`, `record_offset` and
`ordinal` as signed int64, then `value` with its declared type and schema. Floats use Arrow
float64, as the existing writer does; unsigned 64-bit struct fields use Arrow uint64. Use typed
NDJSON when the original float32 representation
matters. The schema metadata carries only `api_version`, `snapshot_id` and `entry`; fetch the retained
descriptor once instead of receiving all declarations in every stream. Projection declares its
selected type; reduction declares the bucket fields. Session unions add `source_path` and
`source_timestamp_us`. A missing struct schema returns `SCHEMA_UNAVAILABLE` with an NDJSON
alternative for inspecting the decode diagnostics. Batch metadata reports decode
problems, visited records and payload bytes. Even a first batch with no selected values has a
complete struct shape, including enums and derived rotation fields.

A stream is provisional until its explicit terminal status arrives. NDJSON ends with a `kind:
"end"` record and `status: "ok"` or `"error"`; Arrow ends with an empty batch carrying `status`
before the normal IPC end marker. Missing terminal proof or an error means discard the operation,
including earlier batches. Decode problems within a completed query remain attached to their
batches and are different from an invalidated or interrupted operation. A JSON error before
streaming carries `status`, `code` and `message`; after headers are sent the terminal frame
carries the reason.

`read.http.RemoteLogAccess` implements `LogAccess` for a server origin. `open(String)` keeps the
server's path verbatim; the returned snapshot's `serverPath()` and descriptor do too. Its
`Path` overload is a convenience for matching platforms. `open(storeId, sessionId, filePath)`
uses catalog identity; `openSession(...)` selects a proven union, and `catalog()` returns the
typed inventory. It checks stream completion,
has bounded response reads and timeouts, follows no redirects, and accepts no URL credentials.
The JDK HTTP client is sufficient; no Arrow reader dependency is needed for this Java adapter.

REV requests name `companion_path` and `anchor_path`; query requests also name `signal` and
`clock` (`raw` or `fpga`). Describe returns the signal names, units, state, reason and recorded
alignment; an unsynchronized association has null offset and drift, never an implied zero. Queries start with that association and stream at most 4,096 values per frame, with
timestamps explicitly in double seconds and the same mandatory terminal proof. The Java
`read.http.RemoteRevRead.describe(...)` and `open(..., signal, clock)` methods expose these
operations; `next()` returns a batch and `complete()` establishes successful completion.
HTTP requests retain the catalog and decoded REV owner while the configured roots agree;
describing a companion then reading several signals does not parse it again. Neither endpoint
recalculates alignment. Telemetry streaming has 16 workers separate from health/registration.
Store transfer and import routes have another four workers, so readers cannot consume all upload
capacity. Excess work returns JSON `BUSY` with HTTP 503 and `Retry-After: 1`. A resource limit
instead returns 413; it is not a suggestion to repeat the identical oversized request. The Java
client recognizes a proxy's plain-text 503 or 429 as `BUSY` too. Ten seconds without progress on an IO operation ends the transfer
and releases its read. Writes are split into at most 64 KiB progress units. A missing terminal
proof still means the result is incomplete.

[read_arrow.py](../examples/read-api/read_arrow.py) shows a non-Java consumer using Python's
standard HTTP client and PyArrow. It checks the terminal proof before accepting the row count
and retains each batch's decode problems, spooling large diagnostic output to a temporary file.
For a data frame, accumulate or persist batches under your application's own memory budget and
commit the result only after that same check.

## Compatibility and validation

The shared cursor owns selection and decoding; the legacy materializer preserves existing MCP
and `/data/entries` values, ordering, warnings, statistics, pagination and encodings. No analysis
moves into Arrow. The new API's exact coordinates, scoped diagnostics and explicit lifecycle are
additive; they do not silently alter a legacy tool's contract.

The [frozen-build comparison](DEVELOPMENT.md#comparing-frozen-server-builds) runs unchanged calls
against the original and candidate JARs and compares MCP results and complete Arrow/CSV/image
artifacts. Independent fixture readers check native samples separately. Mapping, live-prefix,
cancellation, remote completion, manifest and external-JAR tests cover cases a static comparison
cannot prove. Passing those checks establishes the tested scope; it cannot prove every possible
input and interleaving equivalent.

## Scope of this interface

Projection, numeric reduction, multi-entry queries, proven session unions, selective REV decoding
and local append-following are implemented. Exact and legacy time windows share the membership
and binary-boundary primitives; legacy tools retain their established double-second policies.
The interface does not infer signals, join entries by guessed timestamps, recalculate REV alignment
or perform MCP analyses. Publishing the reader as a Maven artifact or adding a release asset is a
separate release decision; building the local JAR does neither.
