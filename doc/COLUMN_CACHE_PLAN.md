# Column Companions for Fast Reads

Status: proposed on 2026-10-21 at the maintainer's request, and its [decisions](#decisions)
answered the same day. Nothing in the server is built. A throwaway prototype, outside the
product code, measured the design on synthetic logs and, read-only, on three local team logs.

The log store is now asked for reads a database would serve: Grafana panels at any zoom, live
panels on the recording in progress, the Explorer, and comparisons across a season. Each of
those reads pays a cost for every sample it touches, and some pay for samples they never
return. This plan keeps the WPILOG as the record and adds, beside each log, a derived file
that holds its numeric entries as columns, with summaries for overviews, read by
memory-mapping as the log itself is. There is no engine and no new dependency: it is a file
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

### What a companion holds

One companion for each WPILOG file:

- For each numeric entry (doubles, floats, integers and booleans), two columns: timestamps in
  integer microseconds, and values as 64-bit integers for integer entries and 64-bit floating
  point otherwise. Nothing passes through a floating-point projection unless the entry is
  floating point. Samples are in time order; equal timestamps keep their order in the file, and
  an entry logged out of order is sorted when the companion is built.
- Beside each column, summaries: minimum, maximum, sum and count of the finite values for
  every block of 256 samples, and again for every 16 blocks, with counts of the non-finite
  values. An integer column's minimum and maximum are kept as integers.
- The log's wall-clock readings and the UTC mapping built from them, once.
- A directory: each entry's name, type and sample count, and where its columns and summaries
  start; and the source file's identity and the recipe version that built it.

Each numeric field inside a struct is a column of its own, named by its field path as the
tools name it: a pose's x as `Entry.x`, a swerve module state's speed as `Entry[0].speed`.
Strings and raw values keep today's path, and elements of plain numeric arrays come later.

### Where it lives and how it is trusted

Companions live in the disk cache beside the scan indexes and REV synchronizations, under the
same size limit and cleanup, and follow the same rules:

- A companion is keyed by the source file's content and by everything that shapes it: the
  decoder and format versions, the schemas and the clock revision. A change to how values are
  decoded raises the recipe version, as the scan index's does.
- Every companion carries a checksum and its format version. A corrupt or crafted one is
  rejected and built again; reading one never crashes the server or exhausts its heap.
- A companion gives the same answers as reading the WPILOG. Differential tests compare every
  sample on every fixture and, opt-in, on real logs; a planted change to a value must fail them.
- A missing or rejected companion falls back only to the same admitted file, never to another
  copy that happens to match, and a read never switches between the two midway.

### When it is built

In the background. After an import, a pull or a capture closes, and for any finished file that
is read before it has one, a bounded worker of low priority builds the companion. Until it is
ready, today's path answers. The worker never runs on the store's worker or the recording's
threads, and it pauses while the capture's disk reserve or the heap runs short: a recording
comes first.

### How it is read

The companion is memory-mapped, so its columns live in the operating system's file cache, not
in the Java heap, and no sample becomes an object. A window is found by binary search on the
timestamps. An overview's buckets come from whole blocks of the summaries plus the partial
blocks at the bucket edges, so a 1,000-point overview of three hours costs about what one of
three minutes does. Raw samples in a window are copied straight into the reply.

The data endpoint, the read API's numeric reads, the statistics tools and `compare_sessions`
read through it. Bucket membership follows the endpoint's integer-microsecond rule exactly, and
the reported sample counts, sampling and decode problems stay what they are today.

### While recording

The capture appends the open file's columns as it writes the WPILOG, to a companion that is
never forced to disk and is built again from the WPILOG after a crash. Only the current block
of each entry stays in the heap, so the open index no longer grows the heap with the session.
A reader takes the columns up to the published prefix, as it takes the WPILOG's records today.

### What does not change

The WPILOG stays the record, and nothing is written into it. The scan index stays, and the
companion is built from the log it indexes. No native code runs in the process that records,
and callers still choose named queries with typed arguments; they never send SQL, paths into
the cache or table names.

## Measurements

A throwaway prototype in a scratch copy of the code built companions from the server's own
reader for a synthetic season: 128 ten-minute match logs, each with 16 numeric entries at 50 Hz
(61 million values, 920 MB of WPILOG), and one two-hour log with 12 entries. Every timestamp and
value of two entries on two of the logs matched today's decoded values. The measurements were
taken on the maintainer's computer with the server's default 4 GB heap, with the files already
in the operating system's cache.

| Question | Today's path | Companions |
|---|---|---|
| One entry from each of an event's 16 logs, 1,000 points each, after a restart | 213 ms (730 ms on first load) | 2 ms |
| The same across the season's 128 logs | 1.45 s (3.8 s on first load) | 13 ms |
| Overview of one entry over the two-hour log, already decoded | 2.3 ms | 1.8 ms |
| Ten seconds of one entry from the two-hour log | 6.3 ms | under 0.1 ms |
| Twelve panels, the last five minutes of the two-hour log, entries not yet decoded | 237 ms, 723 MB allocated | 2 ms, about 2 KB allocated |
| Heap for the open file's index, per recorded value | 82 bytes | 16.6 bytes in memory; none when mapped |

An open capture pays a cost of the twelve-panel kind on every refresh, since its decoded values
are never kept: the Grafana plan measured 109 ms and 424 MB for twelve integer entries after
two hours of recording. Building the companions took 97 ns per value from loaded logs, and they
took 16.1 bytes per value on disk, against the WPILOG's 15.0.

These logs carry 16 entries; real logs carry hundreds. Today's path pays for the whole file
when it loads, and the real-log timing in the
[Grafana plan](GRAFANA_PLAN.md#across-sessions) found one event's fifteen matches taking about
16 s after a restart. A companion pays only for the entry asked for. The prototype had no
checksum, no integer summaries, no UTC column and no out-of-order entries, and a cold disk adds
the time to read each entry's columns, about half a megabyte per ten-minute log.

The same prototype was then timed, read-only, on three local team logs of about 38 MB, 280 MB
and 2 GiB, the three the scan index was timed on; the exact figures stay in ignored local
evidence, and the logs' hashes, sizes and modification times were unchanged afterwards. Each
log has 200 to 270 numeric entries among 340 to 460, holding 0.7 to 1.7 million values, and
every one of those values matched today's decode. Their companions took 11 to 27 MB, 16 bytes
per value: about 1% to 30% of the log, since most of a log's bytes are structs, arrays and
strings, which the prototype left out. After a restart, today's path took 0.1 to 0.9 s to
answer an overview of an entry, most of it spent proving and loading the log's scan index; a
companion answered in under a millisecond, opening included. Building one took 0.2 to 0.3 s.

## Costs and risks

- **Disk.** About 16 bytes for each numeric value, roughly the size of the log itself, before
  any compression; storing timestamps as differences would bring it near 10 bytes. The disk
  cache's limit, 8,192 MB by default, then holds companions for about eight times the
  prototype's season, and a server install can raise it.
- **Build.** One pass for each file, in the background: 0.1 to 0.3 microseconds for each numeric
  value, and 0.2 to 0.3 s for each of the three team logs, before struct fields.
- **A second read path.** Every reader that moves to companions must give today's answers,
  including angles, bucket edges, decode problems and entries logged out of order. The
  differential tests carry that weight.
- **Windows.** A mapped companion cannot be deleted while it is mapped, as a mapped WPILOG
  cannot; cleanup has to wait for its readers, as it already does for logs.

## Phases

1. **Finished files.** Build companions in the background for numeric entries and the
   numeric fields of structs, and read them in the data endpoint and the statistics tools,
   behind the differential tests.
2. **The recording.** The capture writes the open file's columns as it records, replacing the
   per-value index. This comes before the Grafana plan's stage 1, whose gate runs on it.
3. **Across sessions.** The Grafana plan's stage 3 reads companions across files. Its
   server-kept summaries are the companions' summaries, and the columnar store's revisit
   measures this against its targets: one entry across an event in under 2 s after a
   restart, and across a season in under 10 s.
4. **Plain numeric arrays**, element by element, as derived columns.

## Decisions

The maintainer answered these on 2026-10-21.

1. **The first phase covers numeric entries and struct fields.** Chosen in place of the
   proposal, which left struct fields for a later phase. Numeric entries cover battery, loop
   time, CAN and most motor signals, and AdvantageKit's poses and module states are structs, so
   both reach the readers at once.
2. **Companions are built in the background.** Chosen in place of the proposal, which built them
   in the scan's pass. Loading a log costs what it does today, and the first answer from a file
   without a companion comes from today's path.
3. **Companions share the disk cache's limit.** As proposed: the scan indexes, REV results and
   companions share one limit, which a server install can raise.
4. **The recording phase comes before Grafana's stage 1.** As proposed: the open file's columns
   replace the per-value index before live panels are built. The proposed stopgap, a smaller
   default rollover size until then, was declined: the default stays 1 GiB.
5. **Time companions on three local team logs.** As proposed, read-only, with the exact results
   kept in ignored local evidence, as the scan index's timing was under the hardening plan's
   real-log rule. The rounded results are under [Measurements](#measurements).
