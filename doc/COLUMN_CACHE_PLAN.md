# Column Files for Fast Reads

Status: proposed on 2026-10-21 at the maintainer's request, and its [decisions](#decisions)
answered the same day. A throwaway prototype, outside the product code, measured the design on
synthetic logs and, read-only, on three local team logs. The same day, the first part of phase 1
was built: the file format, its writer and reader, and their tests. No reader uses it yet.
[The first build](#the-first-build) says what it changed, and the maintainer
[decided](#decisions-from-the-first-build) what it asked the same day.

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
off and a file that changed while it was scanned have none. A capture that has just closed is
served from memory until it is unloaded, so its column file is built when it is next read from
its file; so are the files a mirror copies, whose sessions the store does not announce. Files
already in a store are not built ahead of a read. Until a file is ready, today's path answers.

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
  synchronization, and checks that the log names the content the request named. It publishes
  nothing if the file's attributes changed while it read, and it publishes under the final name
  only when complete. Two servers sharing the cache may both build the same content; their
  files are identical, so either one serves.
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
Thirty planted mistakes, from a gate left open to a content hash recorded before the proof
that justifies it, each failed them, as did four planted in the new guard and its baseline.

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
- **Windows.** A mapped column file cannot be deleted while it is mapped, as a mapped WPILOG
  cannot; cleanup has to wait for its readers, as it already does for logs.

## Phases

1. **Finished files.** Build column files in the background for numeric entries and the
   numeric fields of structs, and read them in the data endpoint and the statistics tools,
   behind the differential tests. The format, writer and reader are built, and the files are
   placed in the disk cache and built in the background; the readers come next.
2. **The recording.** The capture writes the open file's columns as it records, replacing the
   per-value index. This comes before the Grafana plan's stage 1, whose gate runs on it.
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
