# Recordings in Columns

Status: an idea for future work, written on 2026-10-22 at the maintainer's request and set aside
the same day: recordings stay WPILOG, and the writer buffers its writes instead, which removes
the system calls described below. Nothing here is built, and its questions stay open until the
idea is taken up.

The maintainer's reasoning: people analyze the robot's own log once it is pulled, in
AdvantageScope and the tools. A recording's value is immediate access to live data, and to data
on logs not yet transferred from the robot. So a recording need not be a WPILOG. It should be
written in the form its readers need, and converted to a WPILOG only when someone asks.

That reverses the pit plan's first decision, "Captures are `.wpilog` files... A capture is a log
like any other" ([Pit 1](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made)), and the
column plan's "the WPILOG stays the record". It keeps Pit 2: the recording stands in for the
robot's log until that log is pulled, so it still holds every change.

## What a recording costs today

A recording writes each value twice while it is open: about 15 bytes of WPILOG and about 14
bytes of columns in a scratch file, which the live reads use. After close the scratch file is
deleted, and a column file is built from the WPILOG in another pass. Writing the WPILOG takes
five system calls a value (two writes, one for the record's header and one for its value, and
three requests for the file's position): 4.0 µs a value on the recording's loop. At ten times
the soak's rate the loop waited a second or more inside those writes several times, and once,
streamed faster, lost the robot. Batched into one write per 64 KiB, the same writes take 7 ns a
value.

## The proposal

1. **A recording is a column file written as it records.** Each entry's records go into packed
   blocks, as the open capture's scratch file holds them today, but the file is the record and
   is kept. Numbers keep their values in columns. Strings, raw values, arrays, struct payloads
   and schemas keep their bytes in a payload column beside their times, so nothing a WPILOG
   holds is lost. Struct fields keep the leaf columns the writer builds today. Each block
   carries a checksum. Entry declarations, metadata changes and an entry's end are their own
   small blocks, so a file says what each entry was at each time, as a WPILOG does.
2. **Blocks reach the disk whole.** A block is written when it fills, and every block still
   filling is written at each 250 ms flush tick, then forced as today. The loop makes a few
   writes a tick instead of five system calls a value, and the bytes on disk are written once.
3. **The file is read as it is written.** The live reads use the same blocks they read now. A
   closed recording is opened by a reader of the same format, as a log every tool reads, so
   `read_entry`, the statistics tools, the data endpoint, the visualization and
   `compare_sessions` answer from it without a WPILOG. No column file is built after close: the
   recording already is one.
4. **A crash loses at most the last flush tick.** Recovery reads blocks from the start and stops
   at the first whose checksum fails, as the WPILOG scan now stops at a cut record.
5. **WPILOG on demand, never automatically.** A tool and an HTTP route write a WPILOG of a
   recording, whole or a window of it, into the export directory, for AdvantageScope and for
   anyone else's scripts. A recording's export is checked against the recording itself: every
   record, time and value read back from the WPILOG equal those read from the recording.

What does not change: robot logs, pulled, imported or uploaded, stay WPILOG and are read as
today. Captures already in a store stay WPILOG and stay readable. The NT4 gateway, the live
tools' latest values and Prometheus do not read the file.

## What has to change with it

A survey of the code found five ways capture files depend on being WPILOG.

- **A record's byte offset is its identity.** The live index keeps each record's position in
  the WPILOG, `/read/v1` reports a `record_offset` for each sample, follows check entries across
  the moment a recording closes, and column files keep positions too. A recording has no WPILOG
  offsets; a record is named by its entry and its index in that entry.
- **The file's SHA-256 is its identity across stores.** Manifests, clock records, the chains
  that align other logs to a capture, mirror and peer entries, column-file names and the
  visualization's references all name a capture by its hash. A recording keeps that: its own
  bytes have a hash. A WPILOG export is not stored, so nothing is re-based on it.
- **A capture grows by appending.** Resuming after a short reconnect appends to the same file;
  the store's HTTP door serves an open capture's prefix, with byte ranges and a hash of the
  prefix; transfers resume by prefix. A recording also only grows by appending blocks, so
  these keep working once each reads blocks rather than WPILOG records.
- **Recovery and validation scan WPILOG, and the kind `wpilog` is written into about ten
  checks**: the catalog, peer sync, mirror entries, column builds, the read catalog, the
  visualization's `.wpilog` suffix, imports. Each gains the recording's kind.
- **WPILOG's own records carry meaning.** Declarations, metadata changes, the re-declaration of
  every entry and the last schema at the start of each file after a rollover, and the first
  declaration winning: the recording keeps each, as blocks, so its readers keep the same rules.

Two uses of captures go beyond live data, and must keep working: a capture is the preferred
anchor that places a pulled robot log, an import or a REV log on one timeline, by correlating
them, and its roboRIO uptime samples become the clock record that dates kernel log lines. Both
read the capture as a log, so they read a recording through its reader.

## Questions for the maintainer

1. **What a recording holds.** For: whether a recording can stand in for the robot's log until
   that is pulled. A match's pit recording holds the robot's numbers and also its strings
   (commands, alerts), structs (poses), the context providers' JVM and SSH figures, and the
   recorder's clock. Proposed: everything a capture holds today, so nothing is learned only
   from a WPILOG. The alternative keeps only numbers and strings, smaller and simpler, and
   loses struct payloads, raw values and context except as numbers.
2. **What a crash may lose.** For: how much of a recording a server crash costs. Today a value
   is written to the operating system as it arrives, so a crash of the server loses nothing the
   operating system has; a power cut loses what was not yet forced, up to 250 ms. Proposed:
   blocks written at each flush tick, so a crash of the server loses at most the last 250 ms,
   as a power cut does today. The alternative writes each block's growth more often, which
   costs system calls again.
3. **Existing captures.** For: stores that already hold WPILOG captures. Proposed: they stay
   WPILOG and stay readable, and only new recordings are written as columns. The alternative
   converts them, which changes their hashes and every reference to them.
4. **Conversion.** For: opening a recording in AdvantageScope or a script. Proposed: a tool
   and an HTTP route that write a WPILOG of a recording, whole or a window of it, into the
   export directory, when asked, and never on their own. A recording of a two-hour session at
   the soak's rate would export as about 550 MB of WPILOG.
5. **`record_offset` on a recording.** For: native reads, which report each sample's place in
   its file. Proposed: a recording reports the record's index in its entry, under a new
   field, and `record_offset` stays for WPILOG files. Clients of `/read/v1` that read
   recordings then read the new field.
6. **Laptops.** For: a laptop that records the pit server's gateway into its own folder. That
   writes a capture the same way. Proposed: the same format there, so one reader serves both.
7. **The interim fix.** For: the stalls, before any of this is built. Answered on 2026-10-22:
   the WPILOG writer buffers its writes, and this plan waits.

## Phases

1. The format and its writer, beside the WPILOG writer, chosen by a setting, with recovery.
2. The reader: a closed recording read as a log, checked against the WPILOG of the same
   recording written alongside it, bit for bit, for every entry type.
3. The live reads on the format, the store's kind, the door, sync and the read catalog.
4. Export to WPILOG, checked against the recording.
5. New recordings default to the format; the WPILOG writer stays for robot-log tests and for
   export.

## Tests that must fail first

- A recording and the WPILOG written alongside it from the same stream give the same records,
  times, values, metadata and decode problems through every tool, on every capture fixture.
- A recording cut at every byte of its last block recovers every block before it and nothing
  after, and refuses a block whose checksum fails.
- An export read by the independent WPILOG reader equals the recording, record for record.
- The loop's writes per value, counted: a few a flush tick, not five a value.
