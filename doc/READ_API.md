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

The catalog reader accepts both format-1 stores and explicitly migrated format-2 stores.
Format-2 replica records retain their verification basis; the read-only library neither migrates
stores nor promotes legacy transfer checks into master confirmation.

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
During refresh, increasing size with the same file identity is accepted only for manifested open
capture payloads. Manifests, directories and completed payloads still require matching stamps;
shrinkage, replacement and same-size modification do not qualify. A moving inventory is scanned
at most twice. If it remains unstable, the read refuses with `FILE_CHANGED` and `retryable: true`.
This does not prove sample history: the managed reader and follow continuation retain their
writer, ordinal and finalized-hash checks. Cache reuse still checks all stamps strictly, so later
growth refreshes the observed size.

Use `openWpilog(path)` for a manifested WPILOG. `openRev(companion, anchor)` uses only recorded
offsets, each with its drift and confidence: the companion's own against that WPILOG, or, when the
companion and the WPILOG were each measured against one reference, the two combined. A reference
is a file by its hash, or a capture recording for an offset measured while the capture was still
recording; the files of one recording share the robot's clock, and a session's clock records name
the recording of each capture, so a mirror without the captures still finds the reference. A
combined alignment's method is `COMBINED`, its `combination` names the reference and each
measurement, its confidence is the lower of theirs, and its error is at most the sum of theirs.
It does not run correlation, change alignment or write a synchronization cache. `rawValues(signal)`
keeps the REV clock; `values(signal)` applies the recorded mapping. Missing or corrupt proof leaves
the companion raw-readable with an `UNSYNCHRONIZED` state and reason; a converted read is refused.
Mirrors use their recorded facts unchanged. System text remains text with its own receipts and
clock basis; it is not silently combined into telemetry entries.

`openSession(storeId, sessionId, anchorPath, Overlap.REJECT)` creates a time-ordered union on
an explicitly chosen WPILOG clock. It includes rollover files from the anchor's recording run and
files whose recorded offsets lead to the anchor's reference, by the rule `openRev` follows: a
file's recorded steps toward the anchor apply as recorded, and a file and an anchor measured
against one reference are combined into one `COMBINED` step. A recording run requires `captured`
provenance, the same recorded original start time and the same collecting store (preserved through
peer copies and mirrors). Session membership alone is insufficient: peer sync can consolidate
independently recorded sessions. An older session-level alignment that names neither a file nor a
recording applies only when the session's captures, clock records and other offsets show exactly
one recording; otherwise the file is excluded with that reason. Files without proof are listed in `excludedSources`,
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
| `POST /read/v1/follows` | Create a retained follow of one captured recording |
| `POST /read/v1/follows/<id>/next` | Acknowledge a page and read the next page, or wait for publication |
| `DELETE /read/v1/follows/<id>` | Retire the follow and cancel its wait; acquisition continues |

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
nested values at 64 levels; a limit is an explained refusal, never a truncated value. For an
oversized request, the shared body reader consumes the detection prefix (64 KiB + 1 byte) and
discards up to another 4 MiB within ten seconds, subject to any earlier route deadline, before
replying. This lets a sender finish and receive the refusal instead of losing it to a TCP reset.
If the body has not ended when that discard bound or deadline is reached, the connection closes
without a guaranteed reply; this also prevents response close from starting another blocking drain.
Arrow batches instead follow the encoded record and batch bounds above. NDJSON may split a source
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
capacity. Excess work normally returns JSON `BUSY` with HTTP 503 and `Retry-After: 1` through
two bounded refusal workers per pool. If refusal capacity is also full, the exchange closes
before response headers; no dispatch worker drains an unfinished body. A resource limit
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

## Following a server recording

The native HTTP follow is available to custom readers. The optional visualization editor still
uses its recorded workflow. No Java/Python follow-client adapter or authenticated configured
remote visualization adapter is supplied yet. A follow reads one captured recording, including
rollover members, through the recording server or a configured store containing its final files.
An open mirror cannot supply the originating writer's immutable index.

Create with a durable, unqualified schema-1 `source_ref` and 1–256 distinct `(entry, field)` queries:

```json
{"source_ref":{"kind":"session","origin_store_id":"11111111-1111-4111-8111-111111111111","session_id":"22222222-2222-4222-8222-222222222222","anchor_member":{"original_name":"capture.wpilog","recording_started_at":"2026-01-01T00:00:00Z"}},"queries":[{"entry":"NT:/voltage","order":"file"}]}
```

Use actual IDs and original capture provenance from `/read/v1/catalog`; those UUIDs are examples.
The anchor must identify exactly one member. Membership uses captured provenance, original
recording start, and first collecting store, not robot names or calendar overlap. Session manifests
carry `merged_session_ids`; old IDs select the anchor's recording rather than all merged files.
Copies, sync/merge receipts and mirrors retain aliases. An older writer may drop them. Optional
`finalized_members: [{"content_sha256":"..."}]` on the reference can recover only those proven
members; missing evidence returns `SOURCE_UNAVAILABLE` or `HISTORY_UNAVAILABLE`. It never selects
the robot's current session. Multiple configured stores claiming the same origin require choosing
one serving location rather than guessing between them.

Raw creation rejects a schema-2 qualified `source_ref` or a top-level `scope_id` with 400
`INVALID_REQUEST`, before reserving a lease or opening a source. It cannot validate a serving
server/scope envelope and never unwraps one silently. For a qualified source, create a working
view through visualization API 2 and use its `view_binding` as described below.

A 201 response gives `id`, `server_epoch`, `cursor`, `source_ref`, `idle_timeout_sec: 60.0`,
`max_wait_ms: 25000` and `api_version`. POST `{"cursor":"...","wait_ms":25000}` to
`/read/v1/follows/<id>/next`. The page has `server_epoch`, `follow_id`, `cursor`, `next_cursor`,
`prefix_id`, `bootstrap`, `frontier_complete`, `ended` and `batches`. A `frontier` appears once per prefix:
`recording_id`, `catalog_revision`, `finalized`, and `members`, each with `member_id`, `generation`,
nullable `content_sha256`, nullable `writer_identity` and the native snapshot descriptor. The catalog revision is monotonic
within the follow, not a persistent store revision. Retain the frontier until the next one arrives.
Each batch identifies its `member_id`, `entry`, nullable `field`, `next_ordinal`, and native `batch`.
Its completion flag concerns that projection/member; `frontier_complete` concerns the whole page
plan. A late declaration can publish a new frontier without samples.

By default, the frontier includes every entry and every known struct schema in each member.
That metadata must fit one page, even if the queries select only a few signals. Optional
`frontier_scope: "queries"` on create projects the descriptor to the queried entry names and
their counts, plus the selected struct schemas and transitive dependencies supplied by the
native schema owner. Multiple field projections of one entry share one metadata declaration.
Missing selected entries are absent until declared; a later prefix reports them. Schema source,
text, validity and diagnostics are preserved; unrelated logged/bundled schemas are omitted.
Schema metadata does not add schema-entry samples or queries. All member identities, boundaries,
writer identities, final hashes and recording-level diagnostics remain present, and internal
history checks still cover every entry, including those omitted from the wire.

Create accepts only `all` (the default) or `queries` and echoes `frontier_scope` in its 201
response. Queried frontiers also carry `frontier_scope: "queries"`; an absent field denotes
legacy full metadata. A client requesting projection must check the create echo before polling
and each subsequent frontier before use. The extension adapter retires a created lease after a
missing, malformed or conflicting scope acknowledgement, with bounded cleanup and an explicit
error. An old server may ignore the request field, so the request alone is not negotiation.
The adapter retains 4 MiB/full-metadata defaults for existing callers; recording panels
explicitly choose 1 MiB/queries. Scope bytes count toward the 64 KiB request cap before broker
admission. Response, depth and traversal limits remain unchanged.

Projection runs before descriptor allocation/encoding budgets, so unselected metadata does not
consume that wire budget. Selected metadata and dependencies must still fit the negotiated
page; oversized metadata returns non-retryable `RESOURCE_LIMIT`. This is not a paginated full
signal inventory and does not change the overview's separate 4 MiB cap, the native index/member
limits or catalog scan costs. A larger page budget applies to the entire immutable lease, not
only its initial page. The broker reserves twice that size per active job.

Bootstrap fixes its prefix before creation returns and applies query windows to history. Later
pages consume new physical ordinals even when their timestamps equal or precede old timestamps
or lie outside the bootstrap window. Queries require `order: "file"`, initial ordinal zero and no
reduction. Projection uses the shared native decoder. `max_records` and `max_page_bytes` optionally
lower the defaults of 4,096 visited records and 4 MiB per page. Malformed/oversized records carry
explicit ordinal problems and advance as diagnosed gaps; they are not samples. A metadata-only
page can precede the samples when the full frontier occupies its space. Wire preflight reserves
six bytes per UTF-16 code unit in a string plus fixed framing, so a string can become a gap at
roughly one sixth of the page budget, even when its recorded payload is smaller than that budget.
Other framing can lower this threshold; it is a conservative bound, not an exact JSON size test.
The initial schedule uses lexicographic original-filename order (`capture-10` before `capture-2`),
then query order. It rotates across `(member, entry, field)` selections, visiting at most 256
records per turn, including filtered or malformed records. The next turn survives page boundaries
only when that page is acknowledged; retries keep exactly the same order. A byte-deferred record
retains its ordinal and its selection is skipped for the rest of that page, while other selections
keep filling the remaining space. The first deferred selection starts the next page after
acknowledgement, so it gets a fresh byte budget rather than repeatedly losing its space to a
chatty selection. Its lookahead is charged once on the page where it was deferred.
Per-query batch record/payload limits
still apply across the whole page. Member names are identities, not chronological order.

Use `next_cursor` only after consuming the entire page. Retrying the preceding `cursor` returns
the exact same bytes; acknowledgement retires them. A 204 means no publication before the wait
deadline: keep the submitted cursor and wait again. An acknowledged frontier releases its native
prefix before waiting; unchanged polls do not reopen it. Finalization supplies member hashes,
drains the last ordinals, and never attaches the follow to another recording. Once its finalized
frontier is complete, its last page has `ended: true` (all other pages have `false`). Consume that
page before acknowledging its `next_cursor`. The terminal data page remains byte-identically
retryable until then. Its acknowledgement releases native data and encoded bytes and returns
HTTP 410 `FOLLOW_ENDED` immediately, regardless of `wait_ms`. The lightweight lease retains that
same acknowledgement result until DELETE or idle expiry, so a lost acknowledgement response is
safe to repeat. Stop polling and DELETE; neither an empty batch nor a 204 means the follow ended.

Reduced counts and changed known final hashes refuse continuity. Capture provenance persists a
`writer_identity` per file, shared with the live index, retained through short reconnects and
finalization, and preserved by recovery, store copies and mirrors. A rollover or new writer gets
a new identity. Continuation compares that identity even if replacement and finalization happen
between polls. Legacy finalized metadata without this proof requires explicit rebootstrap at
the live-to-finalized transition; an initially finalized file can bootstrap from its verified
hash. The hash verifies final bytes and the writer identity proves continuity; neither substitutes
for the other. Successful final hashes use bounded verification memory: a follow owns its
memory, while visualization source admissions share their workspace's. Both remember at most
256 path/boundary/expected-hash tuples for ten idle minutes, retaining no mappings or payloads.
Boundary or expected-hash changes force verification; current path/catalog checks and native
snapshot-boundary checks still run. This does not detect edits that preserve all observed
filesystem attributes. Concurrent cold misses may duplicate hash I/O without a shared I/O lock.
A stale/foreign cursor is `INVALID_REQUEST`; an expired, deleted or restarted lease is HTTP 410
`CURSOR_EXPIRED`. An already admitted wait cancelled by DELETE or shutdown ends with HTTP 410
`CLOSED`. Rebootstrap the same source explicitly after losing a cursor or the retained client
frontier, and surface unavailable history rather than silently retargeting. Malformed JSON bodies
on creation or next return HTTP 400 `INVALID_REQUEST` with `Invalid read JSON`; they do not
acknowledge a page or expose parser-library advice.

Every native-read HTTP 409 includes a boolean `retryable`. For follows, `FILE_CHANGED` with
`retryable: true` means a catalog refresh remained unstable after two scans. Back off and retry
the same create request, or the same submitted next cursor on its existing lease; do not reset
buffers or rebootstrap. A preceding page may already be acknowledged, but the failed page has
not advanced the cursor. Ordinary open-capture appends do not cause this refusal.
`HISTORY_UNAVAILABLE` (changed writer or unprovable continuity), `SOURCE_UNAVAILABLE` and other
permanent 409 refusals have `retryable: false`; they need the stated remedy. Do not retry every
409 or infer retryability from its message. The extension treats a missing 409 flag from an older
server as false, and a present non-boolean flag as an invalid response.

Each transport admits at most 32 follows and shares a 64 MiB encoded-page/serialization budget.
Lease admission does not reserve a maximal page. Building a page temporarily reserves twice its
page limit (8 MiB at the default), and retained unacknowledged pages also consume the shared
budget, so a next call can return HTTP 413 `RESOURCE_LIMIT` even below the lease cap. Every 413
has a boolean `retryable`: `true` for shared encoded-budget pressure, `false` for structural
request, metadata, frame or index limits. For transient pressure, release other retained pages,
back off, then retry the same submitted cursor without rebootstrap. The preceding
page may already be acknowledged, but no part of the unsuccessful next page is acknowledged.
Other 413 refusals, such as metadata that cannot fit a page, need the stated limit/query remedy;
blind retries cannot fix those. Clients must use `retryable`, not classify message text.
Native indexes are owned by the shared reader; follow admission also caps the sum of member index
bytes at 128 MiB and the recording at 64 members. Only one next call per lease may run. Sixteen
dedicated workers bound long polls; saturation uses the bounded 503 `BUSY`/disconnect path
above, and creation/DELETE have
separate ordinary-read capacity. Active requests postpone idle expiry until they finish.
DELETE cancels a publication wait and retires admission; an admitted bounded decode releases on
completion. Clients should DELETE on last-consumer cancellation. A disconnected socket with no
I/O is detected at the next write, bounded by the 25-second wait; its abandoned lease then expires
after 60 idle seconds. Shutdown cancels waits before draining HTTP. Same-Origin and current path
admission apply even to cached retries. No follow operation writes a store or waits on the recorder
loop. Capture publication checks use its immutable state token; other writers' filesystem changes
are checked within five seconds while idle. These are resource bounds, not measured throughput or
display-latency claims.

## Scope of this interface

Projection, numeric reduction, multi-entry queries, proven session unions, selective REV decoding
and local append-following and native HTTP recording follows are implemented. Exact and legacy time windows share the membership
and binary-boundary primitives; legacy tools retain their established double-second policies.
The interface does not infer signals, join entries by guessed timestamps, recalculate REV alignment
or perform MCP analyses. Publishing the reader as a Maven artifact or adding a release asset is a
separate release decision; building the local JAR does neither.


### Following a recording view

A trusted local reader with an active `Mcp-Session-Id` may instead POST this body to
`/read/v1/follows`:

```json
{"view_binding":{"view_id":"33333333-3333-4333-8333-333333333333","expected_revision":1}}
```

Use an existing working recording view, not the example UUID. `view_binding` is exclusive:
source, queries, windows and page limits come from the pinned configuration and cannot be
supplied alongside it. This form always uses 4,096 visited records, 1 MiB pages and
`frontier_scope: "queries"`; it shares the raw route's 32 leases, 64 MiB encoded budget,
long-poll workers, timeout, terminal acknowledgement and DELETE behavior. Raw source/query
requests remain schema-1-only and do not require a visualization session. A view created with
visualization API 2 retains its qualified server and scope through admission and every access
recheck, including cached page retries. A missing selected mirror never falls back to its master.

The server resolves all requirements from one admitted prefix and hands that same prefix to
bootstrap. It checks the expected revision before and after admission and before publishing
its lease. A revision change observed at any check returns `REVISION_CONFLICT`; the last check
is the admission point, so edits after it do not invalidate the tagged old revision. Missing/deleted metadata returns
`VIEW_UNAVAILABLE`. Up to 32 distinct resolved entry/field selections are admitted; duplicates
share a query, and missing/conflicting requirements remain explicit in the result. No resolved
selection returns `NO_RESOLVED_BINDINGS`; missing clock evidence for a following interval
returns `CLOCK_UNAVAILABLE`. Both are 409 refusals; inspect or rebind the view rather than
retrying its unchanged request in a tight loop. Temporary compatibility pressure is retryable
413 and preserves a submitted continuation cursor. Source/member limits remain non-retryable.

The 201 response adds `max_page_bytes` and `view_binding`: `api_version`, `view_id`, `revision`,
`source_ref`, `compatibility`, `clock`, `time_policy`, `queries`, `follow_ready`, `reason`,
`frontier_scope`, and `max_page_bytes`. The whole response fits 1 MiB; excessive binding
metadata refuses. For a live source the initial interval uses `time.live`; a newly opened
finalized source uses `time.recorded`. A fixed interval is converted to inclusive integer
microseconds without clamping. Following uses the admitted clock's right edge and `window_sec`,
never wall or receipt time. Empty clock evidence does not mean full history. As on raw follows,
bootstrap filters that interval and deltas retain all subsequent physical ordinals.

After admission, edits do not mutate this follow. Keep the returned view/revision tag with every
client delivery and replace stale subscriptions. Each subsequent prefix checks the pinned
requirements; changed selected names, metadata, types, units or transitive schemas return 409
`BINDING_CHANGED`. New declarations do not silently add queries. A new binding is required;
other leases continue. Source grants and the opening session are checked even on cached retries.
A session expiry closes admission to that lease; DELETE remains available. If creation loses
its response, the existing 60-second idle expiry bounds cleanup.

For detached evidence only, use `POST /visualizations/v2/views/<view-id>/bindings` with
`expected_revision`, or `visualization_view_bind` with `view_id`, `expected_revision` and
`api_version: 2`. The local compatibility route uses `v1` and `api_version: 1` for legacy
sources. Those do not open a follow. A controller can create a follow directly and avoid a
redundant compatibility check. The editor's recording panels remain gated.

View-bound follow frontiers additionally include `clock`, using the same recorded estimate,
last-sample, right-edge and waiting fields as the binding plan. It describes each newly admitted
prefix, including finalization; continuation pages reuse that prefix's frontier. Raw follows
retain their existing wire shape and do not acquire this optional clock field. Clients must not
substitute receipt time for the reported clock evidence.

API 2 remote view follows name `api_version: 2` inside `view_binding`, beside `view_id`
and `expected_revision`. The pinned view retains its complete qualified source. Unknown
versions refuse; explicit API 1 refuses a qualified view. Existing local bindings that omit
this version retain their pre-API-2 behavior for both source forms. Omitting the version does
not grant a remote caller access to the local compatibility route.
