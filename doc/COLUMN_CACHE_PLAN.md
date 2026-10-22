# Column Files for Fast Reads

Status: proposed on 2026-10-21 at the maintainer's request, and its [decisions](#decisions)
answered the same day. A throwaway prototype, outside the product code, measured the design on
synthetic logs and, read-only, on three local team logs. The same day, the first part of phase 1
was built: the file format, its writer and reader, and their tests; then the files were placed
in the disk cache, built in the background and given each record's facts. On 2026-10-22 the
readers moved onto them: [reading through column files](#reading-through-column-files) says
which reads do and which still read the log. [The recording phase's
design](#the-recording-phase-design) is agreed, with its four questions answered, and [was
built](#the-recording-phase-built) the same day. [The first build](#the-first-build) says what it
changed, and the maintainer [decided](#decisions-from-the-first-build) what it asked the same day.

The log store is now asked for reads a database would serve: Grafana panels at any zoom, live
panels on the recording in progress, the Explorer, and comparisons across a season. Each of
those reads pays a cost for every sample it touches, and some pay for samples they never
return. This plan keeps the WPILOG as the record and adds, beside each log, a derived file
that holds its numeric entries and struct fields as columns, read by memory-mapping as the log
itself is. There is no engine and no new dependency: it is a file
layout the server owns, in the disk cache it already keeps.

## Where the cost is today

- A decoded number occupies about 50 bytes of heap, and the decoded values of an entry are
  cached as such objects.
- The open file of a recording keeps an index of about 82 bytes of heap for every recorded
  value, pinned until the file closes. At 5,000 values a second that is about 1.5 GB an hour,
  and a file reaches its 1 GiB rollover with about 6 GB, more than the server's default 4 GB
  heap ([Grafana plan, stage 1](GRAFANA_PLAN.md#stage-1-design-the-open-capture)).
- The data endpoint and the older tools decode a whole entry before they filter a window or
  bucket an overview, so ten seconds of a long log cost as much as all of it.
- The UTC mapping is rebuilt and sorted for every request, and a REV log decodes every signal
  together.
- The WPILOG itself is compact: about 15 bytes for each numeric value.

## The design

### What a column file holds

One column file for each WPILOG file:

- For each numeric entry (doubles, floats, integers and booleans), two columns: timestamps in
  integer microseconds, and values as 64-bit integers for integer entries and 64-bit floating
  point otherwise. Nothing passes through a floating-point projection unless the entry is
  floating point. Samples are in time order; equal timestamps keep their order in the file, and
  an entry logged out of order is sorted when the column file is built.
- No summaries, for now. An overview reads every sample of its window with the data
  endpoint's own arithmetic, so its answers are the endpoint's to the last bit. A later format
  version can add summaries if reads across a season need them.
- Each record's facts as native reads report them: where it begins in the log, its payload's
  size, and its index among the entry's records, packed into 6 to 8% of the file (see
  [Record facts for native reads](#record-facts-for-native-reads)).
- The log's wall-clock readings and the UTC mapping built from them, once.
- A directory: each entry's name, type and sample count, and where its columns start; and the
  source file's identity and the recipe version that built it.

Each numeric field inside a struct is a column of its own, named by its field path as the
tools name it: a pose's x as `Entry.translation.x`, a swerve module state's speed as
`Entry[0].speed`.
Strings and raw values keep today's path, and elements of plain numeric arrays come later.

### Where it lives and how it is trusted

Column files live in the disk cache beside the scan indexes and REV synchronizations, under the
same size limit and cleanup, and follow the same rules:

- A column file is named by the SHA-256 of the source file's content, which its scan index
  proved, and by its format and recipe versions. The schemas are part of the content, and a
  file holds only values on the log's own clock, so nothing else enters the name. A change to
  how values are decoded, stored or read raises the recipe version, as the scan index's does,
  and a test fails until the change is decided.
- Every column file carries a checksum and its format version. A corrupt or crafted one is
  rejected and built again; reading one never crashes the server or exhausts its heap.
- A column file gives the same answers as reading the WPILOG. Differential tests compare every
  sample on every fixture and, opt-in, on real logs; a planted change to a value must fail them.
- A missing or rejected column file falls back only to the same admitted file, never to another
  copy that happens to match, and a read never switches between the two midway.

### When it is built

In the background, one file at a time. The server asks for a log's column file when it reads
the log from its file, and when a store writes a session manifest, as an import, a pull, an
upload and a capture's close each do. Only a log whose content its scan index proved can have
one, since the file is named by that content: a growing file, a file read with the disk cache
off and a file that changed while it was scanned have none. A capture that has just closed
goes on being served from memory until it is unloaded, and that copy names no proven content,
so its build reads the file itself, privately, while the copy in memory serves reads. The files
a mirror copies are built when first read, since the store does not announce their sessions,
and files already in a store are not built ahead of a read. Until a file is ready, today's path
answers.

Asking touches only memory, since a store asks on its own thread. Builds run on one daemon
thread at the lowest priority, never on the store's or the recording's threads. A build waits
while the heap has less than 512 MB free, since one entry's columns may take 200 MB of it, so a
server whose heap is 512 MB or smaller builds none; it also waits while a store's filesystem is
down to recording's reserve or its free space is unknown, which is when the store would refuse
a copy: a recording comes first.

### How it is read

The column file is memory-mapped, so its columns live in the operating system's file cache, not
in the Java heap, and no sample becomes an object. A window is found by binary search on the
timestamps. An overview reads every sample in its window, at about 3 ns a sample. Raw samples
in a window are copied straight into the reply.

The data endpoint, the read API's numeric reads, the statistics tools and `compare_sessions`
read through it. Bucket membership follows the endpoint's integer-microsecond rule exactly, and
the reported sample counts, sampling and decode problems stay what they are today.

### While recording

The capture appends the open file's columns as it writes the WPILOG, to a column file that is
never forced to disk and is built again from the WPILOG after a crash. Only the current block
of each entry stays in the heap, so the open index no longer grows the heap with the session.
A reader takes the columns up to the published prefix, as it takes the WPILOG's records today.

### What does not change

The WPILOG stays the record, and nothing is written into it. The scan index stays, and the
column file is built from the log it indexes. No native code runs in the process that records,
and callers still choose named queries with typed arguments; they never send SQL, paths into
the cache or table names.

## Measurements

A throwaway prototype in a scratch copy of the code built column files from the server's own
reader for a synthetic season: 128 ten-minute match logs, each with 16 numeric entries at 50 Hz
(61 million values, 920 MB of WPILOG), and one two-hour log with 12 entries. Every timestamp and
value of two entries on two of the logs matched today's decoded values. The measurements were
taken on the maintainer's computer with the server's default 4 GB heap, with the files already
in the operating system's cache.

| Question | Today's path | Column files |
|---|---|---|
| One entry from each of an event's 16 logs, 1,000 points each, after a restart | 213 ms (730 ms on first load) | 2 ms |
| The same across the season's 128 logs | 1.45 s (3.8 s on first load) | 13 ms |
| Overview of one entry over the two-hour log, already decoded | 2.3 ms | 1.8 ms |
| Ten seconds of one entry from the two-hour log | 6.3 ms | under 0.1 ms |
| Twelve panels, the last five minutes of the two-hour log, entries not yet decoded | 237 ms, 723 MB allocated | 2 ms, about 2 KB allocated |
| Heap for the open file's index, per recorded value | 82 bytes | 16.6 bytes in memory; none when mapped |

An open capture pays a cost of the twelve-panel kind on every refresh, since its decoded values
are never kept: the Grafana plan measured 109 ms and 424 MB for twelve integer entries after
two hours of recording. Building the column files took 97 ns per value from loaded logs, and
they took 16.1 bytes per value on disk, against the WPILOG's 15.0.

These logs carry 16 entries; real logs carry hundreds. Today's path pays for the whole file
when it loads, and the real-log timing in the
[Grafana plan](GRAFANA_PLAN.md#across-sessions) found one event's fifteen matches taking about
16 s after a restart. A column file pays only for the entry asked for. The prototype had no
checksum, no integer summaries, no UTC column and no out-of-order entries, and a cold disk adds
the time to read each entry's columns, about half a megabyte per ten-minute log.

The same prototype was then timed, read-only, on three local team logs of about 38 MB, 280 MB
and 2 GiB, the three the scan index was timed on; the exact figures stay in ignored local
evidence, and the logs' hashes, sizes and modification times were unchanged afterwards. Each
log has 200 to 270 numeric entries among 340 to 460, holding 0.7 to 1.7 million values, and
every one of those values matched today's decode. Their column files took 11 to 27 MB, 16 bytes
per value: about 1% to 30% of the log, since most of a log's bytes are structs, arrays and
strings, which the prototype left out. After a restart, today's path took 0.1 to 0.9 s to
answer an overview of an entry, most of it spent proving and loading the log's scan index; a
column file answered in under a millisecond, opening included. Building one took 0.2 to 0.3 s.

## The first build

The format, the writer and the reader are built and tested, and nothing reads through them yet.
Building them changed five things in the design above.

- **No summaries yet.** The data endpoint's bucket mean is a running sum over the bucket's
  values in time order, and a mean summed block by block differs from it in the last bits. An
  overview therefore reads every sample of its window, with the endpoint's own arithmetic, and
  computes a sample's bucket only when the sample reaches the next bucket's first microsecond.
  That costs about 3 ns a sample: twelve panels of two hours at 250 Hz, 21.6 million samples,
  took 59 ms, inside the Grafana gate's 250 ms.
- **What a file leaves to the log.** A struct array whose records hold more than 64 elements
  is not held, which the scan's record sizes show before anything is decoded. Past 64
  elements inside a struct, or where a field's decoded type changes between records (a uint64
  field decodes as an integer below 2^63 and as a double above), the entry is held without
  those values, and a read that pools elements with `[*]` takes the log's path. So does an
  entry with more than about 16.8 million samples across its fields.
- **How a build reads the log.** A build decodes each entry once, record by record, through
  the same decode and the same sort into time order as the tools, but outside the log's cache
  of decoded values. The first build went through that cache and ran out of a 4 GB heap on the
  2 GiB team log, nearly all of whose bytes are one struct array entry of about 32 KB a
  record. Now a build holds only the entry it is writing.
- **Checks on reading.** The header, directory and footer are checked when a file opens, and
  each section's checksum the first time a read uses it, so a read pays only for the columns it
  asks for. A read that would need a damaged section takes the log's path instead, and the file
  is read no more.
- **One owner closes a file.** A file is mapped through the same windows as a log, and
  releasing a mapping while another thread reads it crashes the process: a test that closed a
  file while its readers ran did. Readers will hold the file as they hold a log, and the file is
  closed only after its last reader.

The tests compare every sample of every column on every fixture with the tools' own reads, and
a dozen windows of each with their overviews at five sizes; a log written for the edge cases
checks values worked out by hand, and damaged, crafted, truncated and concurrently read files
are checked as well. Eighteen planted
mistakes, from a float read as a double to a skipped bucket boundary, each failed them.

On the three team logs, read-only, every one of 23 million samples over about 2,500 fields and
pooled paths matched the tools' reads. The files took 46 to 146 MB, from about a fifteenth of
the log to a third more than it, and 0.3 to 1.2 s to build. Twelve panels after a restart took
0.1 to 1 s on today's path and 2 to 6 ms from the files, opening included; ten seconds of each
of twelve fields took 6 to 34 ms on today's path, already decoded, and under 0.3 ms from the
files.

## Decisions from the first build

The maintainer answered these on 2026-10-21, each as proposed.

1. **No summaries for now.** Summaries would read about one value in 256, but their means would
   differ from the data endpoint's in the last bits, so the endpoint's answers would change.
   Reading every sample, twelve two-hour panels at 250 Hz take 59 ms. A later format version
   adds summaries if reads across a season need them.
2. **What a build leaves to the log stays as built.** A build's heap holds 8 to 12 bytes a
   sample of the entry it is writing. For finished files, a struct array of more than 64
   elements a record and an entry of more than about 16.8 million samples are left to the log:
   the 2 GiB team log's array entry is, and so would be a two-hour recording of four module
   states at 250 Hz. The recording phase writes columns as it records, so long recordings do not
   depend on these limits.
3. **The name.** "Companion" already names the REV log paired with a WPILOG, as in the read
   API's `companion_path`, so these are column files in the code and the documents.

## Placing and building files

The second step places column files in the disk cache and builds them in the background, as
described above; nothing reads them yet. Building it settled five details.

- **The name is the content.** A file is named `v<format>-r<recipe>-<sha256>-columns.col`, by
  the SHA-256 its scan index proved, which for a store's file is the manifest's. Every copy of
  the same bytes shares one file, and a file of another format or recipe is never read or
  replaced. The startup sweep removes a file whose name or header names another format or
  recipe; damage inside a section is left to the reader that needs it, in the next step.
- **Eviction after each build.** The cache is trimmed to its limit after each build, the oldest
  files first, REV results, scan indexes and column files alike, keeping any file a reader
  holds. Before, the limit was enforced only when the server started. A file the limit has no
  room for is not built again until the server restarts.
- **What a build trusts.** A build reads the log as a tool does, read-only, so it starts no REV
  synchronization, and checks that the log names the content the request named. A request may
  name a path the store has since moved, as a session renamed for its match at close is, and
  the read follows the store's record of the move. A build publishes nothing if the file it read
  changed while it read, and it publishes under the final name only when complete. Two servers
  sharing the cache may both build the same content; their files are identical, so either one
  serves.
- **What a failure stops.** A build that fails on the content is not tried again in the process.
  A path that cannot be read, such as a capture folder renamed after it closed, does not count
  against the content, which may still be read at another path. At shutdown a build in progress
  is interrupted and its file abandoned.
- **Only new files.** Building every stored file when the server starts would, for a store
  larger than the cache's limit, evict files as fast as it built them.

Tests check the values a built file holds against the fixture's own, each gate at its boundary
(exactly 512 MB free, and a store with exactly its reserve free), a file changed while it was
built, a request naming content its file no longer holds, the trim after a build and the files
it keeps, both ways a build is asked for, one through a real import into a store, and a
session listing a path no file system accepts, which must not fail the store's publication.
Recorded captures check the close: a capture held in memory and one renamed for its match are
each built from the file, with the values recorded, and a capture still recording is refused.
Thirty-five planted mistakes, from a gate left open to a content hash recorded before the
proof that justifies it, each failed them, as did four planted in the new guard and its
baseline.

## Record facts for native reads

Native reads report three facts with every sample about its record: where it begins in the log,
its payload's size, and its index among the entry's records in file order. A program uses them
to find or check the record behind a sample, to read only what a growing file appended, and to
count bytes against its budgets. Format 2 keeps them for every record a file holds:

- **Byte positions, packed.** Each block of 4,096 records keeps its first position whole and
  the rest as the difference from the record before, in as few bytes as the difference needs.
  An entry's records lie a few hundred bytes to a few kilobytes apart, so a difference takes
  two or three bytes.
- **Payload sizes, once when they agree.** A number or a plain struct has one size, which the
  directory keeps once; a struct array's sizes vary and are packed like positions.
- **Indexes only where needed.** A record's index is its position in time order unless the
  entry was logged out of order or a record failed to decode; only then are indexes kept.

Measured on the three team logs, the facts add 5.6 to 7.6% to their files, against 21 to 22%
with whole positions and 39 to 42% with every fact whole. On the 2 GiB log's file, reading
positions forward took 4.5 ns a record against 1.8 ns for whole eight-byte values, and finding
a window's first record from its block took 6.4 µs. A packed section is checked the first time
a read uses it: its checksum over exactly its bytes, then every value in order, each block's
differences ending where the next block's begin, so damage reads as damage and a crafted
section with a right checksum is still refused. A file of the first format is refused by its
version, removed at startup, and built again when its log is next read.

### Decision on the record facts

The maintainer chose this layout on 2026-10-21 from four measured choices: every fact whole,
whole positions with derived indexes and sizes, these packed positions, or native reads left on
the log's path. The read cost was to be measured before relying on it, with whole positions as
the fallback; it was, and packed positions stay.

## Reading through column files

The third step moved the readers onto the files. Numbers reached the tools two ways before: a
resolver decoded an entry into one object per sample for `read_entry`, `render_chart`, the data
endpoint and the statistics tools, and a record cursor decoded records for native reads, the
visualization preview and `compare_sessions`. Both now take a held path's numbers from the file
once it is built.

- **One series, two sources.** The resolver returns a path's samples as numbers by position,
  read from the file or from the log's decode, and every numeric step works on that series
  whichever source it came from: windows, angle unwrapping, buckets, data quality, summary
  statistics, interpolation, lags and exact pages. A read from a file makes no object per
  sample. `read_entry`, `render_chart`, the data endpoint, `get_statistics`, `compare_entries`,
  `compare_matches`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`,
  `align_entries`, `find_condition` and the visualization's charts read this way. A scalar
  entry's values keep the decode's types, and repeated values are told apart by the bits the
  decoded values' own comparison uses, so an integer past 2^53 is not mistaken for its
  neighbor.
- **Native reads.** A native read of one numeric leaf comes from the file's columns and record
  facts through the same record selection as the log's records, so windows, file and time
  order, batches, byte budgets, record limits and reductions behave as before. A file serves it
  only where its answer is the log's to the bit: every record of the entry decoded and holds
  the leaf, and the leaf is neither an enum, whose label the file does not keep, nor a uint64
  field, which native reads keep unsigned while the file holds the tools' integers. One record
  decoded the native way shows which a field is. A read in file order, or from a first record
  index, also needs every record's index to be its position, which an entry logged out of order
  breaks. Whole structs, pooled `[*]` reads and plain arrays read the log.
- **Holding files.** A loaded log opens its content's file the first time a read needs it and
  closes it when its own mapping closes, after its last reader, so a file is never unmapped
  under a read. The cache's trim keeps the files loaded logs hold. A file found damaged is read
  no more, removed and built again, and its readers read the log meanwhile.
- **What still reads the log.** Strings, raw values, plain numeric arrays and REV signals;
  `export_csv`; the domain tools, which read whole records, among them the power, CAN, swerve,
  vision, pose and match tools; and any log whose file is not built yet. The reader library
  reading files on its own machine never opens the server's cache, so it reads the log; its
  reads through a server use the server's files.

The resolver gives every held and pooled path of every fixture and of a log of edge cases the
decode's samples to the bit, value bits included, and the data endpoint's series keep the decode's types
and decode problems. The five presentation routes answer their 39 cases from the file as from
the log, against the same oracle. The conformance sweep calls every tool on every fixture again
with a column file and requires the same result. Native reads match the log's batch by batch,
every sample's record facts and the problems included, in sixteen ways on a log of edge cases
and on every fixture, and a test names the reads a file must serve and those it must leave to
the log. Thirty-four planted mistakes, from a float read as a double to a record's index
reported as its position, each failed them; thirteen first passed, and each now has a test of
its own, among them a decode problem lost when a file answers, an integer past 2^53 taken for
its neighbor, an infinite sample counted as a value, and a rate's last point.

Measured on the three team logs, read-only, through the tools themselves: twelve whole-log
overviews by `read_entry` at 1,000 points took 10 to 33 ms from the files against 24 to 87 ms on
the log's path with the values already decoded, and allocated 33 to 36 MB against 68 to 197 MB;
ten seconds of each took 1.5 to 3.3 ms against 11 to 52 ms. After a restart, loading the log's
index took 35 to 547 ms either way, and the twelve reads after it 10 to 55 ms from the files
against 45 to 223 ms with the decode. Native reductions of the twelve took 13 to 66 ms against
65 to 301 ms. `get_statistics` gained least, 28 to 124 ms against 42 to 173 ms, since it sorts
every sample for its percentiles. Reading the same samples from the files alone took 0.8 to
3.4 ms in the first build's measurement, so most of an overview's time is now the tools' own
work around the read.

### Decisions on the read path

The maintainer decided these on 2026-10-21.

1. **One code path.** The MCP tools and the data endpoint compute windows, buckets and
   statistics with the same code, so a number is computed one way wherever it is asked for,
   rather than the tools keeping their own beside the endpoint's.
2. **One summary statistics implementation.** Every tool that reports summary statistics uses
   one implementation, `compare_sessions` included, rather than a streaming one of its own.
3. **The deep switch.** Every numeric reader works on the series directly, in place of reading a
   file and turning its samples back into one object per sample, which would have saved the
   decode but kept the per-sample work.

## The recording phase: design

Proposed on 2026-10-22 for the maintainer's review, and its
[questions](#questions-for-the-maintainer) answered the same day, each as proposed.

### What the open file costs today

The capture's index keeps one object on the heap for every record it writes, with the record's
position, size and time, and, for ten minutes by default, the value itself as the robot sent it
(`capture.hot_window_sec`), so that a recent record can be read without mapping the file again.
The index stays until the file closes, and the open log cannot be unloaded when memory runs
short. Every read of the open file decodes each entry it asks for from the first record.

A throwaway prototype, outside the product code, measured today's index against the design
below, on 100 numeric topics at 50 Hz, the soak's 5,000 values a second, for 30 minutes (9
million values), with the files already in the operating system's cache:

| Question | Today's index | Columns, packed |
|---|---|---|
| Heap kept per value | 52 bytes, 62 with the ten-minute hot window | 0.24 bytes |
| Heap after two hours at the soak's rate | about 1.9 GB, 2.0 GB with the hot window | about 3 MB |
| Writer's thread, per value | 60 to 75 ns | 31 ns |
| Disk per value | none | 13 bytes |
| Twelve panels, the last five minutes at 1,000 points, after 30 minutes | 122 to 153 ms | 2.1 ms |

Writing the record to the WPILOG takes about 4.1 µs a value, so the index is a small part of
the writer's work either way. The Grafana plan's figure was 82 bytes a value; these synthetic
topics are all numbers. Unpacked blocks took 28 bytes a value on disk, 27 ns on the writer's
thread and 0.35 ms for the twelve panels. The tail's heap is its entries' arrays, 1.4 MB for these
100 topics, and 16 bytes for each block written.

### The design

1. **What the open file's columns hold** (question 1). For every entry, each record's time,
   byte position and payload size: what native reads, follows and windows need, for strings and
   structs too. For every numeric entry, a double, float, integer or boolean, the value as well.
   Records keep the order the writer wrote them; a read in time order sorts an entry logged out
   of order, as it does today.
2. **Blocks, and the tail** (question 2). The writer's thread appends each record's facts and
   value to its entry's tail, a few primitive arrays of at most 512 records, and writes a full
   tail as one block. A block packs times and positions as differences from the record before,
   keeps a payload size once when the block's sizes agree, and keeps values whole. A tail grows
   from a few records as its entry does, so an entry logged once a minute holds a few bytes, not
   a block.
3. **Where the file lives.** A scratch file in the disk cache's directory, named by the capture's
   identity, written as blocks fill and never forced to disk. The operating system keeps it in
   its cache and writes it out as memory requires, so neither the heap nor the process's memory
   grows with the session. The file counts against the cache's limit and is never trimmed while
   its capture is open. It is deleted when the capture's index retires, after its last reader,
   and by the startup sweep after a crash; it is never read after a restart. The WPILOG stays
   the record, and the finished file's column file is built from it after close, as now.
4. **What a reader sees.** A view's prefix stays fixed as today: each entry's records up to the
   publication boundary. A block stays readable in the heap until the next flush tick maps the
   file past it, so the file is mapped again at most four times a second, as the WPILOG's
   mapping is today; blocks below the mapping are read from it.
5. **The hot window keeps only what has no column** (question 4). A number is read from its
   column, so a numeric record keeps no object. Strings, raw values, structs and arrays keep
   their recent wire values for the hot window, since their bytes are read from the WPILOG's
   mapping.
6. **The same readers as a finished file.** The open log answers numeric reads from its columns
   through the series the readers already take from finished files, so `read_entry`, the data
   endpoint, the statistics tools and Grafana's live windows on a recording cost what their
   window holds. Native reads and follows take each record's facts from the columns, and its
   value from a column or from the WPILOG as before.
7. **When the disk cannot take the file** (question 3). When the disk cache is off, a block
   cannot be written, or the cache's filesystem is down to the recording's reserve, the blocks
   stay in the heap, packed: 13 bytes a value, a quarter of today's index. The capture's status
   says so.

At close, the open log goes on serving its prefix from the columns until it is unloaded, while
the finished file's column file is built in the background as now. A capture resumed after a
gap in the same process keeps its columns; a rollover starts a new file with new columns.

### Questions for the maintainer

1. **Struct fields while recording.** For: whether a live panel of a pose or a module state reads
   a column, as a number does, or decodes the records in its window. Decoding a pose and taking
   its four numbers costs 151 ns on the writer's thread, and four module states with eight
   numbers 284 ns, against about 4.1 µs to write the record: 4 to 7% more work on each struct
   record, on the thread a recording comes first on. Decoding a window instead costs a reader
   about 2.3 ms for the last five minutes of a 50 Hz pose, 15,000 records, so twelve such panels
   take about 30 ms of the gate's 250. A struct value that arrives before its schema would also
   have no columns. Proposed: struct fields decode their window; the writer's thread takes only
   numbers it already holds.
2. **Packed or whole blocks.** For: the open file's size on disk. Whole values take 28 bytes a
   value: two hours at the soak's rate is about 1 GB, and a file at its 1 GiB rollover, about 70
   million values, brings 2 GB. Packed, 13 bytes: about 470 MB for two hours and 0.9 GB at
   rollover, for 4 ns more a value on the writer's thread and 2.1 ms against 0.35 ms for twelve
   panels. Proposed: packed, as format 2 packs a finished file's positions.
3. **When the disk cannot take the file.** For: live reads of a recording whose cache disk is
   full, or whose server runs with the disk cache off. Proposed: the blocks stay in the heap,
   packed, at 13 bytes a value: two hours at the soak's rate is about 470 MB, against 1.9 GB for
   today's index. The alternative stops indexing the recording until it closes: the WPILOG is
   still written, but its open file answers no reads.
4. **Numbers leave the hot window.** For: the memory the last ten minutes hold. At the soak's
   rate the hot window keeps 3 million values as objects, about 30 bytes each on top of the
   index, about 90 MB. With the numbers in columns, a numeric record keeps none, and
   `capture.hot_window_sec` then holds recent strings, raw values and structs only. Proposed:
   yes, with the setting's documentation changed to say so.

The maintainer answered all four on 2026-10-22: as proposed. Struct fields decode their window,
blocks are packed, blocks stay in the heap when the disk cannot take them, and numbers leave the
hot window. The first was reversed the same day, for live struct panels at any zoom: the writer
records struct fields as columns ([Grafana plan](GRAFANA_PLAN.md#decisions-for-the-maintainer)).

### Tests that must fail first

- At sampled publication boundaries of a recorded capture, every view's records, facts and
  values from the columns equal those a fresh scan of the same WPILOG prefix gives, for every
  entry type, bit for bit.
- The heap after a full collection does not grow with the session: after ten million recorded
  values it is within 16 MB of where it started, counted rather than timed.
- A ten-second window on a two-hour capture reads only the blocks that hold it, counted.
- A leftover open file is removed at startup and never read; a block that cannot be written
  moves the capture's columns to the heap, and every read before and after gives the same
  answers.
- Grafana's gate, opt-in like the soak test, passes after the change and fails on today's code
  at two hours.

## The recording phase: built

Built on 2026-10-22 to the design above. Building it settled three points. The first changed
the design, and the maintainer kept the change on review the same day:

- **A block is read with a positional read, not through a mapping.** A block is written once and
  never changes, so a reader reads it from the scratch file with one positional read. No mapping
  then has to be retired under its readers, which the WPILOG's mapping needs reference counts
  for, since unmapping a buffer a thread still reads crashes the server; a block is readable as
  soon as it is written, not after the next flush tick; and the file can be deleted, on Windows
  too, as soon as its channel closes. The cost is a system call for each block a reader decodes:
  twelve five-minute panels take 6.9 ms from the file, against 2.2 ms with the blocks in the
  heap, well within the 250 ms of Grafana's gate.
- **When the file is deleted.** The design said the file goes when the index retires. An index
  retires when it is evicted from memory, and the writer may resume it after a gap, so the file
  is deleted only once the writer has moved on to another file and the index's last reader is
  done. The startup sweep removes the files a stopped or crashed server left.
- **Which records a column holds.** A number's column holds a record's value when the record's
  payload has its type's width and the writer's frame carried the type's own value, as every
  record the writer encodes for its entry's type does. Any other record, such as an integer a
  client sent on a double topic or a malformed payload, keeps its wire value in the hot window
  and is read from the WPILOG after, as before. While an entry has such a record, its numeric
  reads come from the decode, so they keep its answers and decode problems. The list of such
  records doubles as it fills, since a client that sends integers on a double topic makes every
  record of the session one of them.

Measured on the prototype's load, 100 numeric topics at 50 Hz for 30 minutes (9 million
values). One benchmark, compiled against the index before this change and after it, appends
with made-up record positions and times the loop whole; the panels come from the probe that
measured the design:

| Question | Before | Blocks in the scratch file | Blocks in the heap |
|---|---|---|---|
| Heap kept per value | 62 bytes | 0.3 bytes | 14 bytes |
| Writer's thread, per value | 46 ns, and 83 ns more to expire hot values | 47 ns | 40 ns |
| Writer's thread over the second 15 minutes | 152 ns, rising as the heap grows | 40 to 44 ns | 36 to 39 ns |
| Disk per value | none | 14 bytes | none |
| Twelve panels, the last five minutes | 122 to 153 ms | 6.9 ms | 2.2 ms |

The design's figures for the writer's thread were timed call by call between WPILOG writes,
which adds a timer call and the write's cache misses to each append; the benchmark times the
loop whole, so its figures are lower for both indexes.

The tests the design names are built, except Grafana's gate, which comes with the Grafana
plan's stage 1, whose panels it measures. [The pit server's capture
tests](pit-server/DEVELOPMENT.md#capture-tests) describe them; nineteen planted mistakes each
fail them. `list_sessions` reports the open capture's `index_in_heap`, null while the file takes
its blocks.

## Costs and risks

- **Disk.** About 16 bytes for each numeric value, roughly the size of the log itself, before
  any compression; storing timestamps as differences would bring it near 10 bytes. The disk
  cache's limit, 8,192 MB by default, then holds column files for about eight times the
  prototype's season, and a server install can raise it. Struct fields add more: with them,
  one team log's file took a third more than the log itself.
- **Build.** One pass for each file, in the background: 0.1 to 0.15 microseconds for each value
  held, and 0.3 to 1.2 s for each of the three team logs, struct fields included.
- **A second read path.** Every reader that moves to column files must give today's answers,
  including angles, bucket edges, decode problems and entries logged out of order. The
  differential tests carry that weight.
- **Renames at close.** A build holds its file while it reads it, as a tool call does. When a
  session closes with its match facts, the store renames its folder after waiting up to 3 s for
  the folder's readers; a build still reading a file there, such as a robot log pulled into the
  session moments before, leaves the folder under its old name, with the match facts in its
  manifest all the same.
- **Windows.** A mapped column file cannot be deleted while it is mapped, as a mapped WPILOG
  cannot; cleanup has to wait for its readers, as it already does for logs.

## Phases

1. **Finished files.** Build column files in the background for numeric entries and the
   numeric fields of structs, and read them in the data endpoint and the statistics tools,
   behind the differential tests. Built: the format, writer and reader; the files placed in the
   disk cache and built in the background; and the readers moved onto them.
2. **The recording.** The capture writes the open file's columns as it records, replacing the
   per-value index. This comes before the Grafana plan's stage 1, whose gate runs on it. Its
   [design](#the-recording-phase-design) is agreed, and it was
   [built](#the-recording-phase-built) on 2026-10-22.
3. **Across sessions.** The Grafana plan's stage 3 reads column files across sessions, and the
   columnar store's revisit measures this against its targets: one entry across an event in
   under 2 s after a restart, and across a season in under 10 s. If reading every sample misses
   them, a later format version adds summaries.
4. **Plain numeric arrays**, element by element, as derived columns.

## Decisions

The maintainer answered these on 2026-10-21.

1. **The first phase covers numeric entries and struct fields.** Chosen in place of the
   proposal, which left struct fields for a later phase. Numeric entries cover battery, loop
   time, CAN and most motor signals, and AdvantageKit's poses and module states are structs, so
   both reach the readers at once.
2. **Column files are built in the background.** Chosen in place of the proposal, which built
   them in the scan's pass. Loading a log costs what it does today, and the first answer from a
   file without a column file comes from today's path.
3. **Column files share the disk cache's limit.** As proposed: the scan indexes, REV results and
   column files share one limit, which a server install can raise.
4. **The recording phase comes before Grafana's stage 1.** As proposed: the open file's columns
   replace the per-value index before live panels are built. The proposed stopgap, a smaller
   default rollover size until then, was declined: the default stays 1 GiB.
5. **Time column files on three local team logs.** As proposed, read-only, with the exact
   results kept in ignored local evidence, as the scan index's timing was under the hardening
   plan's real-log rule. The rounded results are under [Measurements](#measurements).
