# Recorded History in Grafana

Status: proposed on 2026-10-21 at the maintainer's request, and its [decisions](#decisions)
answered the same day. Nothing in it is implemented. Two answers differ from the proposal: a
dashboard starts from a robot and a time range, and live panels on the open capture come first.
The designs those answers need are agreed before they are built. The first stage's design is
[proposed below](#stage-1-design-the-open-capture), with six questions for the maintainer.

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
  dependency.
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

Proposed on 2026-10-21 for the maintainer's review. Nothing in it is built, and the
[questions](#questions-for-the-maintainer) below decide parts of it.

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
- The open file's index holds about 82 bytes of heap for each recorded value and is pinned until
  the file closes; it cannot be unloaded when memory runs short.
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
   score is taken over the window, as data quality is everywhere else.
4. The UTC mapping is kept for each loaded log and, for an open capture, extended as wall-clock
   readings arrive.
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
   `capture=current`.
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
   for live panels.
3. **What a window does at a rollover or a reboot.** For: whether "the last five minutes" may
   span two files. Proposed: the open file only. For up to five minutes after a rollover the
   panel's left side is empty, with the reason in the metadata, and a reboot's new session starts
   a fresh panel. The alternative reads the previous file of the same session too, which is the
   first answer across files that stage 2 designs in general.
4. **The open index's memory, first.** For: a recording comes first. At 82 bytes per value, a
   long session at a high value rate runs the server out of heap before its first rollover,
   whether or not Grafana is in use. Proposed: compact the open index into primitive arrays of
   timestamps and offsets, estimated at 20 to 25 bytes per value, as its own change before stage
   1. The alternatives are a smaller default rollover size, which bounds the index without
   shrinking it, or a documented limit on the recorded value rate.
5. **The gate's bounds.** For: deciding when the live dashboard ships. The gate as written passed
   today's code after ten minutes, because the cost that grows with a session is still small
   then. Proposed: run the dashboard's load after the capture already holds two hours at the
   soak's rate, and require a listener delay of at most one second, as now; a twelve-panel
   refresh within 250 ms and 50 MB of allocation at two hours as at two minutes; and heap after a
   full collection that grows by no more than the compacted index's cost per value.
6. **A second dashboard, or the robot panels moved.** For: what the pit wall shows. Proposed: add
   the recorded dashboard beside the sampled one. The alternative moves the five robot-value
   panels off Prometheus once the gate passes.

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
