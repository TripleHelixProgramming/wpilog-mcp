# Recorded History in Grafana

Status: proposed on 2026-10-21 at the maintainer's request, and its [decisions](#decisions)
answered the same day. Stage 1 was [built](#stage-1-built-so-far) on 2026-10-22: the server's
reads, the gate, which they pass, and the recorded dashboard. Stage 2's
[design](#stage-2-design-history-by-robot-and-time) was answered on 2026-10-23 as proposed, and
its server reads and dashboard are [built](#stage-2-built-so-far); REV signals in a range are
still to build. Two answers differ from the proposal: a dashboard starts from a robot and a time
range, and live panels on the open capture come first.
The designs those answers need are agreed before they are built. The first stage's design is
[proposed below](#stage-1-design-the-open-capture); the maintainer answered its six questions
on 2026-10-21.

A server install already puts the robot's latest values on a wall. Every HTTP server serves
`/metrics`, and the Compose example runs Prometheus and Grafana beside it
([pit plan §12](pit-server/PIT_SERVER_PLAN.md#12-metrics-prometheus-and-grafana)). That is a
sampled view of the present. This plan adds the record: plots of a session's history at full
resolution, read from the log store, in the same Grafana.

The scope is a server install: a machine that runs the standalone server as a service, with a
store fed by capture, pulling, imports, uploads or peer sync, such as the pit server. A
student's laptop keeps the Explorer, which plots the same data from the same server. Nothing
here is installed there.

## What already exists

- The data endpoint, `/data/entries`, serves every sample of an entry over a window, or
  buckets of minimum, maximum and mean reduced to `max_points`, as Arrow or CSV
  ([STANDALONE.md](STANDALONE.md#the-data-endpoint)). The decision index keeps it as the door
  for viewers, scripts and dashboards.
- The unreleased `utc=true` option adds `timestamp_utc`, measured from the log's own wall-clock
  entry and interpolated between its readings, with the basis in the metadata. It is empty
  before the robot's clock was set. A file name, a modification time or the server's clock
  never supplies it ([hardening item 6](ARCHITECTURE_HARDENING_PLAN.md#6-no-wall-clock-time-on-the-data-routes)).
- The store catalog, `GET /read/v1/catalog`, lists robots, sessions and files: provenance
  (such as captured, pulled or imported), the event and match a session belongs to when known, and
  each file's calendar start and end with the clock they come from
  ([READ_API.md](READ_API.md#http-version-1)).
- Grafana's Infinity data source, maintained by Grafana Labs, reads CSV and JSON from a URL.
  Its requests come from Grafana's server, not a browser, and carry no `Origin` header unless
  one is configured, so they pass the endpoint's check. Path admission still applies.

## Design

### Grafana reads the CSV the endpoint already writes

Infinity's backend CSV parser skips lines that start with a chosen comment character, so the
endpoint's `#` metadata lines drop out and the header row is the first record. It reads
`timestamp_utc`, ISO 8601 with microseconds, as a time, and an empty cell as null.

The pit plan and hardening item 6 expected a new `json` format for Grafana. Infinity does not
need one, and it would take no more from JSON than from CSV: neither carries units or
metadata into a panel. One Grafana query reads one entry, since a CSV response repeats the
header for each entry. A panel's unit is set on the panel, as on the live dashboard.

### Grafana's time range chooses the window

`/data/entries` gains `start_utc` and `end_utc`, ISO 8601 instants that cannot be combined
with `start_time` or `end_time`. A panel passes Grafana's range as `${__from:date:iso}` and
`${__to:date:iso}`.

- The window holds the samples whose measured UTC lies in it, end included, as the endpoint's
  last bucket includes the window's end today. The mapping is the one the UTC column uses, so
  a sample has one time however it is asked for.
- A sample from before the robot's clock was set has no UTC and lies in no window. The
  response counts such samples with their reason, as `utc_basis` already does.
- A log with no usable wall clock is refused with the reason, not answered with an empty
  table, so the panel shows why it is blank.
- The window's ends are mapped back to log time with a margin, and the samples are then
  filtered on the forward mapping. The existing reverse mapping keeps the first reading's
  offset before that reading, where the column is empty; filtering on the forward mapping
  keeps the two consistent.
- `max_points` then buckets inside the window. Zooming in asks again with a narrower window
  and gets finer buckets, until the window holds no more samples than `max_points`. Then the
  samples themselves come back, one per row, in the same columns.

### A dashboard starts from a robot and a time range

Someone chooses a robot and a time range, and the files come from the store's catalog by the
robot's identity and the session facts, under the rules in [Across sessions](#across-sessions).
Where a pit capture and the roboRIO's own log cover the same minutes, a source picker on the
dashboard chooses one, defaulting to the roboRIO's log where one exists and to the capture
otherwise; nothing merges them. This replaces the proposal's start from one session in the
store. How the server answers a range that spans files, the picker's form, and whether a
catalog table helps find a range are designed and agreed before they are built.

The catalog's calendar times only choose files. Each comes from the clock its record names,
the pit server's clock for a capture, which can differ from the robot's measured clock by
seconds. The panels plot measured UTC.

Entry names go in text boxes, chosen exactly by the people who set up the dashboard, as on the
live dashboard. The server proposes nothing: a word in a name is not evidence of what an entry
measures.

### Provisioning

The Compose example gains a pinned Infinity release and a provisioned data source whose base
URL is the server, so a dashboard moves to another server by changing one URL. A recorded-history
dashboard sits beside the live one: the robot and source choices, then battery, loop time and
CAN utilization panels, each a band from minimum to maximum with the mean.

- The endpoint reads only files inside configured directories and stores. Grafana's requests
  carry no MCP session, so a directory leased by a VS Code window is refused once that
  window's session ends. A server install configures its store in `servers.yaml`.
- Grafana's container reaches the server as Prometheus does. On Linux the server must bind
  beyond loopback, which exposes the unauthenticated data endpoint to that network
  ([metrics setup](pit-server/STANDALONE.md#metrics-and-a-starter-dashboard)). Keep it on the
  private team network.

### Logs without a measured clock

A simulation log, or a log from a robot that never received a Driver Station time, has no UTC,
and a time-series panel shows the refusal. Grafana's Trend panel plots against any increasing
numeric column, so it can draw such a log against `timestamp_sec`, without the time picker. No
date is invented: the rejected columnar proposal already stated that a synthetic display time
must never pass for measured UTC.

## Performance

High-resolution data costs Grafana little as long as every panel asks for buckets: the
endpoint returns at most `max_points` rows per series however dense the entry is. The cost
falls on the server, where today it grows with the length of the entry rather than the
window. These measurements were taken on the maintainer's computer against team logs, with a
throwaway server and cache. Following the measurement-logs rule in the
[hardening plan's decisions](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer), the
exact figures stay out of the repository and are rounded here.

- A 1,000-point overview of a 50 Hz entry from a loaded log took 20 to 50 ms, twelve panels at
  once about 45 ms, and a 100 Hz REV signal about 20 ms. A large log's first load added a few
  seconds.
- A ten-second window cost as much as the whole log, because the endpoint decodes the entire
  entry and filters it sample by sample on every request. The shared read cursor, which finds
  the window by search and decodes only what is inside, answered the same window in 4 to 14 ms.
- The UTC column rebuilds and sorts the clock mapping on every request: about 13 ms for twenty
  minutes of one reading per 20 ms loop, about 110 ms for three hours.
- A capture still recording is read through a fixed prefix whose decoded values start empty
  for each request. A panel refreshing every few seconds decodes the session from its start
  each time, in the process that records; [stage 1's design](#stage-1-design-the-open-capture)
  measures what that costs.
- A decoded number occupies about 50 bytes of heap. The endpoint's REV path decodes every
  signal of a REV log together, about fifteen times the file's size.
- Infinity reads a whole response into memory and builds one map per row. At 1,000 rows that
  costs nothing. Every sample of a 100 Hz signal over a session, about 200,000 rows per panel,
  is where Grafana's own process would suffer. Provisioned panels always pass `max_points`.

The plan therefore includes:

1. `/data/entries` selects every window, in log time or UTC, through the shared read cursor,
   which also reads a capture's prefix. The route keeps its role and its formats.
2. The UTC mapping is built once per loaded log, and once per published prefix for a capture.
3. REV signals reach Grafana through the selective REV decoder with the store's recorded
   alignment: only the requested signal is decoded, and no synchronization runs.
4. The synthetic soak test ([hardening item 3](ARCHITECTURE_HARDENING_PLAN.md#3-one-process-records-and-analyzes))
   gains a dashboard's load, twelve panels refreshing every five seconds on the open capture,
   under its existing stall and heap bounds.

## Across sessions

A panel across sessions plots one entry from many files on one wall-clock axis: a robot's
battery across an event, or its loop time across a season. No route answers it today, so a
dashboard would need one query per file. Measured on today's readers, one event's fifteen
match logs took about 30 s on their first load, about 16 s after a restart with persisted scan
indexes, and about 0.1 s once loaded. A loaded log is unloaded after 30 idle minutes by
default, so a dashboard opened once a day would pay the slow case each time.

Whatever answers it follows these rules:

- Files are chosen by the robot's identity and the session facts in the catalog, never by
  matching names or times alone.
- Two files that cover the same minutes for one robot, such as a pit capture and the roboRIO's
  own log of one match, are kept apart and labeled by provenance. The dashboard's source picker
  chooses one, by default the roboRIO's log; nothing merges them.
- Each file's samples use that file's measured clock. A file without one is listed, not
  placed.
- Finished sessions only, as the multi-session decisions already require for statistics.
- Panels plot each session's own series. Numbers pooled across sessions belong to
  [`compare_sessions`](TOOLS.md#compare_sessions), with its per-session evidence and pooling
  rules, not to a dashboard query.

Two ways to answer it are measured before either is chosen:

- Summaries kept by the server. Each finished file gets a derived summary keyed by its
  content and the summary's recipe, as the scan index is: for each numeric entry, minimum,
  maximum, mean and count over short fixed intervals, with UTC. Wide windows read the
  summaries; a window narrow enough to need samples reads the file. No new engine or
  dependency. The [column file plan](COLUMN_CACHE_PLAN.md) keeps full-resolution
  columns beside each log instead, read by every reader, and leaves summaries for later; its
  first build overviews twelve two-hour panels in 59 ms by reading every sample.
- A columnar store on server installs only, which the columnar plan's
  [revisit](COLUMNAR_STORE_PLAN.md#revisited-for-server-installs-2026-10-21) reconsiders under
  that scope.

Both are measured on a season-sized synthetic store, the columnar store through DuckDB, against
the maintainer's target: one entry across an event's matches in under 2 s after a restart, and
across a season in under 10 s. Where a columnar store would run is decided after the
measurement.

## Stages

The maintainer put the open capture first.

1. **The open capture.** Live panels on a recording in progress: the shared read cursor reads
   the capture's published prefix, and the UTC mapping is built once per published prefix. The
   soak test with a dashboard's load must meet its bounds before live panels go in; until then
   the live dashboard stays on Prometheus.
2. **History by robot and time.** The UTC window, window selection through the shared cursor,
   the mapping built once per loaded log, the REV path, the Infinity data source, the source
   picker and the recorded-history dashboard, with the tests below. No new server dependency.
3. **Across sessions.** Summaries and a columnar store measured against the target, then the
   chosen one built.

## Stage 1 design: the open capture

Proposed on 2026-10-21 for the maintainer's review. Nothing in it is built. The maintainer
answered the [questions](#questions-for-the-maintainer) below the same day.

### What a person sees

On the pit server's Grafana, a second dashboard beside the sampled one plots the recording in
progress. Each panel is one entry: the last five minutes by default, refreshed every five
seconds, drawn as a band from minimum to maximum with the mean. Zooming in with the time picker
narrows the window until every sample shows; zooming out reaches back to the start of the open
file. When nothing is recording, each panel says so.

The sampled dashboard stays. It shows the server itself (heap, capture rate, the pull gate and
the NT4 round trip), which no recording holds, and the latest value once a second. The recorded
panels show what a scrape cannot: every value the capture wrote, so a 30 ms voltage dip between
two scrapes appears as a bucket's minimum.

### What the code does today

- Until 2026-10-21 a request for a capture still recording ended in Internal error, after
  decoding every requested entry. It now returns the prefix the writer had published, without an
  ETag.
- Every request for an open capture decodes each requested entry from the start of the file and
  then filters the window. Every appended value makes a new published prefix, so a cache keyed by
  the prefix would almost never hit: decoded values and the UTC mapping have to grow with the
  recording instead.
- A capture has measured UTC only when the robot publishes a wall-clock topic over NetworkTables.
  AdvantageKit's `SystemStats/EpochTimeMicros` is one. DataLogManager's `systemTime` is written
  to the roboRIO's own log, not to NetworkTables. The capture writes no wall-clock entry of its
  own.
- A panel can find the open file in the catalog's `open_captures`, but its path changes at a
  rollover (every 1 GiB by default, as `capture-<n>.wpilog`) and when a robot reboot starts a new
  session.
- The open file's index held about 82 bytes of heap for each recorded value, pinned until the
  file closed, and could not be unloaded when memory ran short. Since 2026-10-22 it keeps its
  records in columns outside the heap, under half a byte a value
  ([the column file plan](COLUMN_CACHE_PLAN.md#the-recording-phase-built)).
- The synthetic soak test never reads the open capture, keeps no recent values (production keeps
  600 s), and its heap bound is only "below the maximum".

These synthetic measurements were taken on the maintainer's computer with a 768 MiB test heap:

- The soak test, with its usual 100 topics at 50 Hz, imports and readers, plus twelve panels
  refreshing every five seconds over the last five minutes at 1,000 points, met all its bounds
  for ten minutes. All 3,000,000 values were recorded, the listener loop's worst delay was about
  90 ms, and a refresh took about 20 to 40 ms, 115 ms at worst. Sampled heap reached 803 of
  805 MB, and garbage collection took about 35 s of the ten minutes.
- With twelve topics at 50 Hz written straight to an open capture, the same refresh took 26 ms
  after 2.5 minutes of recording and 109 ms after two hours, and allocated 33 MB and 424 MB: the
  work grows with the session, not with the window.
- At the soak's 5,000 values per second, 82 bytes per value is about 1.5 GB of heap per hour.
  The capture writes about 15 bytes per value, so a file reaches its 1 GiB rollover with about
  6 GB of index, more than the launcher's default 4 GB heap.

### The change

1. `/data/entries` takes `capture=current` in place of `path`. The server answers from the
   capture recording at the time of each request and names the file it read in the response's
   inputs. When nothing is recording it refuses with the reason, naming the last session.
2. `start_utc` and `end_utc` from [stage 2](#stages) come forward, with the rules under
   [Grafana's time range chooses the window](#grafanas-time-range-chooses-the-window): live
   panels need Grafana's range.
3. Windows are selected through the shared read cursor, for an open capture's published prefix
   and for a loaded log alike: found by search, with only the records inside decoded, so a
   refresh costs what its window holds. The sample count comes from the index, and the sampling
   score is taken over the window, as data quality is everywhere else. Once the column plan's
   recording phase is in, the open file's columns serve these windows.
4. The UTC mapping is kept for each loaded log and, for an open capture, extended as wall-clock
   readings arrive. A live window is placed by the robot's wall-clock topic when the robot
   publishes one, and otherwise by a wall-clock entry the capture records from the pit server's
   clock through NetworkTables' time synchronization; `utc_basis` names the clock that placed
   it.
5. The soak test's gate, below, must pass before the live dashboard ships.
6. The Compose example gains a pinned Infinity, a provisioned data source whose base URL is the
   server, and the live dashboard. The CI metrics check installs the plugin and confirms that the
   data source and the dashboard provision; the live answers themselves are checked by the Java
   tests and the gate.

History by robot and time, REV signals, the source picker and panels across sessions stay in
stages 2 and 3.

### Questions for the maintainer

1. **Name the recording, not its file.** For: a panel that keeps working across rollovers and
   reboots. With `capture=current`, a panel follows the capture when the file rolls over (about
   every four hours at the soak's rate) or a reboot starts a new session. With a path looked up
   from the catalog, it keeps plotting the old file until the dashboard is reloaded. Proposed:
   `capture=current`. Answered on 2026-10-21: `capture=current`.
2. **Which clock places a live sample.** For: the time axis and the "last five minutes" window.
   Grafana chooses the window by its own clock, on the pit server. A robot's wall clock is set
   from the Driver Station laptop; if that laptop runs two minutes ahead of the pit server, a
   panel on the robot's clock lags two minutes behind real time, and a robot that publishes no
   wall-clock topic gets no live panel at all. The alternative: the capture records a wall-clock
   entry of its own, measured from the pit server's clock through NetworkTables' time
   synchronization and labeled `pit_clock` in `utc_basis`, which is the clock the catalog
   already uses for a capture's calendar times. Live windows then line up with Grafana's clock on
   the same machine, for every robot. This relaxes hardening item 6's rule that the server's
   clock never supplies UTC, for captures only and as a recorded measurement, and history in
   stage 2 then has two clocks for a capture that the panel must name. Proposed: the pit clock
   for live panels. Answered on 2026-10-21, and confirmed in words because it relaxes hardening
   item 6's rule: the robot's clock when it publishes one, and the pit server's otherwise, each
   labeled. The pit server's clock supplies UTC only for a capture whose robot publishes none.
3. **What a window does at a rollover or a reboot.** For: whether "the last five minutes" may
   span two files. Proposed: the open file only. For up to five minutes after a rollover the
   panel's left side is empty, with the reason in the metadata, and a reboot's new session starts
   a fresh panel. The alternative reads the previous file of the same session too, which is the
   first answer across files that stage 2 designs in general. Answered on 2026-10-21: the open
   file only.
4. **The open index's memory, first.** For: a recording comes first. At 82 bytes per value, a
   long session at a high value rate runs the server out of heap before its first rollover,
   whether or not Grafana is in use. Proposed: compact the open index into primitive arrays of
   timestamps and offsets, estimated at 20 to 25 bytes per value, as its own change before stage
   1. The alternatives are a smaller default rollover size, which bounds the index without
   shrinking it, or a documented limit on the recorded value rate. Answered on 2026-10-21: the
   [column file plan](COLUMN_CACHE_PLAN.md#decisions)'s recording phase, which keeps the
   open file's columns outside the heap, comes before this stage, with no smaller default
   rollover size in the meantime.
5. **The gate's bounds.** For: deciding when the live dashboard ships. The gate as written passed
   today's code after ten minutes, because the cost that grows with a session is still small
   then. Proposed: run the dashboard's load after the capture already holds two hours at the
   soak's rate, and require a listener delay of at most one second, as now; a twelve-panel
   refresh within 250 ms and 50 MB of allocation at two hours as at two minutes; and heap after a
   full collection that grows by no more than the compacted index's cost per value. Answered on
   2026-10-21: two hours of prefill at the soak's rate, then twelve panels every five seconds for
   ten minutes; the listener waits at most one second; each refresh finishes within 250 ms and
   allocates under 50 MB, at two hours as at two minutes; and the heap left after a full
   collection grows by less than 64 MB over the run.
6. **A second dashboard, or the robot panels moved.** For: what the pit wall shows. Proposed: add
   the recorded dashboard beside the sampled one. The alternative moves the five robot-value
   panels off Prometheus once the gate passes. Answered on 2026-10-21: a second dashboard beside
   the sampled one.

### Tests that must fail first

- `capture=current` on an open capture returns its prefix's rows, worked out by hand, and is
  refused with the reason when nothing records; after a rollover it names the new file.
- The window through the cursor returns the same rows as filtering the whole entry, on every
  fixture and on a capture's prefix; a planted off-by-one at a window's end fails it.
- A ten-second window on a two-hour open capture decodes only the records inside it, counted
  rather than timed.
- The UTC mapping extended reading by reading equals one built fresh from the same prefix, and
  an ambiguous clock is refused in both.
- The gate, opt-in like the soak test, fails on today's code at two hours.

## Stage 1: built so far

On 2026-10-22, after the column plan's recording phase, the server side of [the
change](#the-change):

1. **`capture=current`** answers from the capture recording when each request arrives, names
   the file it read in `inputs.log`, and follows the capture to its next file. With nothing
   recording it is refused with `404`, the reason and the last session.
2. **`start_utc` and `end_utc`** choose the window by measured UTC, ends included, imply
   `utc=true`, and are refused beside `start_time` or `end_time`. The UTC window becomes one
   window of the log's microseconds, found by search over the mapping, so the rows are exactly
   those whose `timestamp_utc` lies in it. A log that cannot be placed is refused with `422`
   and the reason; each entry's `utc_basis` counts its samples before the clock's first reading.
3. **Windows cost what they hold.** A number or a struct field is read from its column by
   search, in a capture as in a finished file. The sampling class is scored over the window, the
   sample count comes from the index, and a recording finds an entry's decode problems without
   decoding it: a number whose column holds every record has none, and a struct entry counted
   its failures as the writer decoded each record, as the second decision below chose.
4. **The UTC mapping is kept.** A loaded log builds it once; a recording extends it by the
   clock's new readings, and each request maps its own prefix exactly as a fresh read would.
   The robot's clock places a log when it has a valid reading, otherwise a recorded server
   clock, `/Daemon/Clock/EpochTimeMicros`, labeled `pit_clock`; an ambiguous robot clock is
   refused, not replaced. Every capture records that entry, as the first decision below settled.

Built tests: `capture=current` against rows worked by hand, its refusals, and a capture that
moves to its next file; hand-worked UTC windows at and beside each end, open ends, fractions of
a microsecond, offsets, buckets and refusals; a differential on every fixture with a clock in
which each UTC window holds exactly the rows a whole read places in it; a recording's mapping
against a fresh read of each prefix, through a clock set, a clock stepped an hour and an
ambiguous clock; and, counted, a ten-second UTC window on a two-hour recording that decodes at
most 40 of its 1,400 blocks. Twenty-two planted mistakes each fail them. The recorder's clock is
checked against readings worked by hand from a moving test clock, for files too small to hold a
reading beside their last value, against an upstream topic of its name, and end to end: a robot
with no clock read by a UTC window on its recording and its finished file. A recording's struct
fields are checked against a decode of the same prefix for every leaf and pooled path, through the
cases where the columns must stop, start again or step aside, with views read again after later
records and schemas, against the numbers written and against the finished file's column file;
and, counted, a ten-second UTC window of a pose's `x` on a two-hour recording decodes no pose and
at most 100 of its 4,218 blocks. Twenty-five planted mistakes each fail them.

5. **The gate passes.** An opt-in run of the soak test records a synthetic robot through the pit
   server's own capture service, two hours of the soak's 100 topics at 50 Hz and a wall clock,
   then ten minutes in real time while twelve panels ask every five seconds for the last five
   minutes by measured UTC at 1,000 points, through the real HTTP transport. On the
   maintainer's computer, the 120 refreshes at two hours took 18 ms at the median and 101 ms at
   worst, against 250 ms, and allocated 36 MB at the median and 44 MB at worst, against 50 MB; at
   two minutes they took 19 ms and 26 MB. The listener waited at most 37 ms, against a second,
   and the heap after a full collection grew 9.8 MB, against 64 MB. With the recording's column
   reads turned off, so that each refresh decodes its entries whole as before the recording
   phase, the refreshes passed at two minutes, at 38 MB, and failed at two hours, at 870 MB each,
   though they still took under 250 ms on this computer: the allocation bound is the one that
   sees a cost growing with the session.
6. **The recorded dashboard.** The Compose example's Grafana installs Infinity 4.1.1, pinned,
   before it starts, with a provisioned data source whose base URL is the server, and
   provisions **Pit / Pit recording** beside the sampled dashboard: its five robot panels, each
   a band from minimum to maximum with the mean over `capture=current`. CI's metrics check
   confirms the plugin's version, the provisioning, and that a panel's own query, sent through
   Grafana with nothing recording, comes back refused by the server.

Building the gate found three things.

- **The first run failed its allocation bound.** A refresh allocated 51 to 88 MB, about 4.5 MB a
  request, and a dashboard opened after two quiet hours would have read every new clock reading
  as an object in each of its panels at once, about 50 MB apiece. CSV rows now come from one
  buffer, UTC cells are sized without formatting them, UTC is mapped in long arithmetic with the
  exact path kept for overflow, a recording's column blocks are read into a buffer each reader
  keeps, and one request at a time extends the clock's readings, as numbers, while the others
  wait. A request takes about 2.5 MB, the same at six minutes as at two hours, and every
  response is byte for byte what it was.
- **Infinity does not pass the server's reason on.** A refused panel shows only the status code,
  so the dashboard's first panel says what 404, 422 and 400 mean; the server's text is in the
  response, which Grafana does not show.
- **The prefill is paced at ten times real time.** Streamed as fast as the recorder took values,
  about thirty times the soak's rate, the listener stalled for over a second several times and
  once lost the robot, which started a new session. At ten times it still stalled four times
  during the prefill, never during the ten minutes at the soak's rate.

### Decisions for the maintainer

1. **The recorder's clock entry.** For: live panels on a robot that publishes no wall clock
   over NetworkTables, which is any robot without AdvantageKit's `EpochTimeMicros`. Today a
   `start_utc` window on such a capture is refused with "No wall-clock entry". Built, the
   capture would record this server's clock at each NetworkTables time synchronization, every
   three seconds, and its samples would be placed to within the synchronization's one-way delay,
   a few milliseconds. Building it showed what that costs. Written at the synchronization's
   own robot time, a reading widened the capture's time range past its last value; written as
   provider context, the gateway forwarded it to its clients. Written instead at the time of the
   next value, and not forwarded, both go away, but the entry still joins every capture file, is
   declared again at each rollover and counts toward each file's reserve, and about 70 tests that
   pin a capture's exact entries or records change with it. The alternative keeps it out of the
   WPILOG: the live mapping uses the time synchronization held in memory, and a finished
   session's readings go to the store's clock record beside the capture, as the roboRIO's
   uptime pairs do; a copy of the file alone then cannot be placed in UTC for such a robot.
   Proposed: the entry, at value times and not forwarded, as the stage 1 answer described it,
   with the tests updated. Answered on 2026-10-22: the entry in each capture, as proposed; it is
   built, and the replay audit checks every reading falls on a recorded value's time.
2. **Struct fields on a recording.** For: a live panel of a struct field, such as a pose's
   `x`. Two hours of a 50 Hz pose is 360,000 records, all decoded for every refresh today,
   against the gate's 250 ms and 50 MB for twelve panels. Reading through the shared cursor
   decodes only the window, but the decode problems reported beside a series then describe the
   window's records, not the entry's as a finished file's do. Proposed: keep the entry's decode
   problems, found once per record by the first refresh past it, as the clock's readings are,
   so the metadata means the same for every log. Asked again with a third way, the maintainer's
   own question: the writer decomposes each struct record into columns as it records, as finished
   column files already hold struct fields, at about 1% more writer time for a struct-heavy robot
   and scratch disk about the size of the struct records while the file is open. Answered on
   2026-10-22: fields as columns, recorded. That reverses the recording phase's first answer, and
   the maintainer confirmed it in words the same day. It is built: the writer's work on a pose
   record went from 62 to 271 ns, and on four module states to 635 ns, against about 4.1 µs to
   write the record ([the column plan](COLUMN_CACHE_PLAN.md#struct-fields-recorded)).

## Stage 2 design: history by robot and time

Proposed on 2026-10-23, and its six questions answered the same day: as proposed. Building it
refined two points. The files of one recording are read in order, as one recording, rather than
refused as two files over the same minutes, since a same-boot reconnect now continues the
recording in its next file, which starts with the values the robot re-sends on reconnecting. A
server with several stores needs `store=<id>`, as there is no default store.

### What a person sees

A third dashboard, **Pit / Robot history**, sits beside the sampled and recorded ones. Its top
row holds a robot, chosen from the store's robots by name and serial, a source, and Grafana's
own time range. A table lists the robot's sessions in that range: when each started and ended,
its event and match, and whether it holds the roboRIO's log, the pit's recording or both.
Clicking a row sets the time range to that session. Below it, each panel plots one entry over
the whole range, as a band from each bucket's minimum to its maximum with the mean, across every
file the range covers, on one axis of measured UTC. Zooming in asks again with a narrower window,
down to single samples, as on the recorded dashboard.

### The code before stage 2

- A request to `/data/entries` reads one file: it takes one `path`, or `capture=current`.
  Nothing takes a robot, a session or a time range across files, and nothing chooses between a
  roboRIO log and a recording of the same minutes.
- The store's catalog lists robots, sessions and files as one nested JSON document with
  absolute paths, up to 16 MiB, and takes no parameters. The store door's session listing
  filters by robot and by an earliest end, but gives paths relative to the store. Neither is a
  flat table that Infinity reads directly.
- A robot is its serial number. Until a capture learns the serial, the capture is filed under
  the robot's address and moved once the serial is known; the local test store holds one robot
  under two addresses, its mDNS name and its IP address. Event, match and team are facts of a
  session, from the logged FMS entries.
- A roboRIO log pulled after a capture joins the capture's session only when cross-correlation
  places it within 250 ms; otherwise it gets a session of its own, and nothing in the store says
  that the two cover the same minutes.
- A session read through the read API joins a session's files on one log clock and merges
  them, without UTC. A dashboard keeps its sources apart, so that read does not fit.
- REV signals through `/data/entries` decode every signal of each REV log and start REV
  synchronization when the robot log loads, though a store's REV logs then take their recorded
  offsets. Only the REV read route decodes one signal at a time, and it has no window, no UTC
  and no CSV.
- The two sources name one signal differently. In the team's AdvantageKit roboRIO log the
  battery is `/SystemStats/BatteryVoltage`; in the pit's recording of the same robot it is
  `NT:/AdvantageKit/SystemStats/BatteryVoltage`, because AdvantageKit publishes every logged key
  under `/AdvantageKit` and the recorder marks NetworkTables topics with `NT:`. One name reads
  one source.

### The proposal

1. **The server chooses the files.** `/data/entries` takes `robot`, `source`, `start_utc` and
   `end_utc` in place of `path`. It reads every finished file of that robot whose provenance is
   the source's: the roboRIO's logs, pulled, imported or uploaded, or the pit's recordings. A
   file is a candidate when its catalog times overlap the window widened by two hours, the
   window the store already uses to pair a pulled log with a session; it is then placed by its
   own measured UTC and windowed exactly, as a single file is today. The response lists every
   file it read, with its span, and every candidate it skipped, with the reason: no measured
   clock, no such entry, outside the window. Two files of the source that overlap inside the
   window are refused together, with both names, not merged. At most 64 files answer one
   request, the session read's limit; more are refused with the true count.
2. **Buckets span the window.** A bucket takes the samples of the file that covers its time; a
   stretch that no file covers gives empty buckets, as a gap inside one file does today.
3. **The source picker** is a dashboard choice of roboRIO log or pit recording, roboRIO log by
   default, passed as `source`. How a panel names its entry in each source is question 2.
4. **The sessions table** reads a new CSV route listing one robot's sessions over a UTC range,
   one row per session: its id, its start and end, in measured UTC where a file is placed and in
   catalog time otherwise, labeled, its event and match, and the sources it holds. A second CSV
   route lists the robots for the robot choice. Both read the catalog, never the files' values.
5. **REV signals** in a robot range come from the REV logs the store has aligned to a file of
   the source in that range, through the recorded alignment, decoding only the requested
   signal. A REV log without a recorded alignment is listed as skipped and never correlated
   during a request.
6. **The open capture** is question 5.

### Questions for the maintainer

1. **How a range picks its files.** For: any range wider than one file. Case: Saturday 9:00 to
   12:00 holds six matches and two practice sessions; the six match logs and the two practice
   logs were pulled from the roboRIO, and the pit recorded both practice sessions. With the
   roboRIO source a panel reads eight files and plots them in one band; with the pit recording,
   two. Proposed: the proposal's rules, an overlap inside one source refused and never merged,
   and 64 files at most. What would change: a wider limit answers a whole event in one request
   but makes its first answer slower; merging an overlap would hide which file a value came from.
2. **Names that differ between sources.** For: switching a dashboard between the roboRIO log and
   the pit recording. Case: the battery panel's box holds `/SystemStats/BatteryVoltage`; switched
   to the pit recording, no file holds that name, and the panel shows the refusal until the box
   holds `NT:/AdvantageKit/SystemStats/BatteryVoltage`. Choices: (a) each panel has one box for
   each source, and the picker chooses which is read; (b) one box, and the server translates by
   AdvantageKit's published convention, `NT:/AdvantageKit/<key>` in a recording for `<key>` in
   an AdvantageKit log, only when both files carry AdvantageKit's own metadata; (c) one box,
   retyped when the source changes. Proposed: (a), since it names every entry exactly, as the
   rule that the server proposes nothing asks, and a dashboard is set up once.
3. **The default where both sources exist.** The answer of 2026-10-21 reads "defaulting to the
   roboRIO's log where one exists and to the capture otherwise". For: a range in which only some
   roboRIO logs have been pulled. Case: Thursday's practice sessions 1 to 3 were pulled and
   session 4 not yet, and the pit recorded all four. Chosen per range, the roboRIO source reads
   whenever the range holds any roboRIO log, and session 4 stays blank until it is pulled.
   Chosen per stretch, each stretch reads its roboRIO log where one exists and the recording
   otherwise, so sessions 1 to 3 come from the roboRIO and session 4 from the pit, and the
   response labels the source of each stretch; each panel then needs both names, which question
   2's (a) or (b) gives. Proposed: per stretch, labeled, if question 2 gives each panel both
   names, and per range otherwise.
4. **Whether a sessions table helps.** For: finding a range without knowing its times. Case: a
   mentor wants Friday's third practice session; the table lists Friday's sessions with their
   times, and a click sets the range. Proposed: yes, with the two CSV routes. What would change:
   without it, a person sets the range by hand, from the catalog or the Explorer.
5. **The open capture in a history range.** For: a range that ends now. Case: "last 3 hours" on
   a practice day while the robot is recording. Proposed: the pit recording source includes the
   open capture's published prefix, as `capture=current` reads it, so the range runs to the last
   flush; the roboRIO source holds finished files only, since a roboRIO log is pulled after the
   robot stops. What would change: finished files only would leave the current session to the
   recorded dashboard.
6. **REV logs without a recorded alignment.** For: REV panels. Case: a REV log placed before its
   roboRIO log was matched has no recorded alignment, and correlating it during a request costs
   seconds a file. Proposed: listed as skipped and never correlated during a request; the store
   aligns it when it places the files, as it does today. What would change: correlating on
   demand fills the panel but makes a dashboard's refresh pay for the measurement.

### Stage 2: built so far

On 2026-10-23, the server's reads and the dashboard's listings:

1. **Robot ranges.** `/data/entries` takes `robot`, `start_utc` and `end_utc` in place of `path`,
   with `names` as the roboRIO's logs name each entry and `capture_names` as the recordings do,
   paired by position, and `source=capture` for the recordings alone. The catalog nominates the
   files, each is placed by its own clock, a stretch reads the roboRIO's log where one exists and
   the recording otherwise, and the response lists the files, the stretches, the files skipped and
   why, and the manifests the catalog could not read. Buckets span the range in UTC.
2. **The listings.** `/data/robots` and `/data/sessions` answer in CSV from the catalog alone,
   each session's times labeled with their basis.
3. **The history dashboard.** **Pit / Robot history** in the Compose example: the robot from
   `/data/robots`, the source, a sessions table whose rows set the range, and the five robot
   panels, each with a box for each source's name, every box empty to start. CI's metrics check
   provisions it and sends a panel's own query through Grafana to a server with no store, which
   refuses it.

Built tests: a range over three files whose clocks the fixture sets, against rows worked out from
the fixture alone, through a roboRIO log whose catalog time is an hour off, a sample before a
clock's first reading, buckets checked against the bucket rule worked apart from the server, the
recordings alone, two overlapping roboRIO logs refused, the two files of one recording read in
order, a file with no clock skipped, an open recording, a manifest the catalog cannot read,
every refusal, the file limit, and both listings. Twelve planted mistakes were run; eleven fail
them, and the twelfth, placing a sample from before the clock's first reading, cannot change an
answer, since the window already starts at that reading. The dashboard's structure is checked as
the recorded dashboard's is, and three planted mistakes fail it. Still to build: REV signals in a
range, which a range refuses until then, and the metrics check against a fixture store whose
clocks are known, which needs CI's Docker to run.

### Tests that must fail first

- A robot range over files built in the test with known clocks: the right files read, in order,
  each sample once, both ends of the window included, a gap empty; a file without a clock listed
  and skipped; two overlapping files of one source refused with both names; the file limit
  refused with its true count. A planted off-by-one at a file's edge and a planted merge of an
  overlap must each fail them.
- The sessions and robots routes against a store built in the test, every row checked by hand.
- REV in a range: only the requested signal decoded, counted, and no synchronization started; a
  REV log without an alignment listed and skipped.
- CI's metrics check provisions the history dashboard and sends a panel's own query through
  Grafana against a fixture store whose clocks are known.

## Tests

- Window selection against hand-worked answers on a fixture whose wall-clock reading arrives
  partway: the end included, samples before the first reading left out and counted, an
  ambiguous clock refused, a window outside the log, and both window forms refused together.
- Windowed selection through the shared cursor returns the same rows as filtering the whole
  entry, on every fixture and, opt-in, on real logs. A planted off-by-one at a window's end
  must fail it.
- A ten-second window on a long synthetic entry decodes only the records inside it, checked by
  counting decoded records rather than by timing.
- The metrics-stack check in CI adds Infinity, provisions the recorded-history dashboard and
  queries it through Grafana's API against a fixture log with a known clock. Times and bucket
  values are compared with answers worked out independently of the server.

## Decisions

The maintainer answered these on 2026-10-21.

1. **Read the CSV instead of adding a `json` format.** Accepted. The pit plan named a `json`
   format as what Grafana's plugins would read. Infinity reads the CSV the endpoint already
   writes, skipping its `#` metadata lines. A JSON copy of the same rows would be another format
   to test, and Infinity would take no more from it. The pit plan's §12 now names the CSV and
   Infinity.
2. **Let Grafana's time range choose the window.** Accepted. This is what makes zooming work. A
   twenty-minute log at 1,000 points is about 1.2 s per point, so a half-second voltage dip
   shows only as one point's minimum. With the window, zooming to ten seconds gives 10 ms per
   point, finer than the robot's 20 ms loop, so every sample shows.
3. **Start a dashboard from a robot and a time range.** Chosen in place of the proposal, which
   started from one session in the store. The server chooses the files, so the rules under
   [Across sessions](#across-sessions) apply from the first stored-history stage: where a pit
   capture and the roboRIO's own log of a match overlap, a source picker chooses one, by default
   the roboRIO's log.
4. **Plot the open capture first.** Chosen in place of the proposal, which left live views to
   Prometheus until stored history was done. A live panel today would decode the whole session
   on every refresh, in the recording process; the open-capture stage removes that cost and must
   pass the soak test with a dashboard's load before live panels go in.
5. **Across sessions: measure both, against a target.** Summaries and a columnar store are
   measured on a season-sized synthetic store, the columnar store through DuckDB. The target is
   one entry across an event's matches in under 2 s after a restart, and across a season in
   under 10 s. Where a columnar store would run is decided after the measurement.
