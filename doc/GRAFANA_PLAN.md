# Recorded History in Grafana

Status: proposed on 2026-10-21 at the maintainer's request, and its [decisions](#decisions)
answered the same day. Nothing in it is implemented. Two answers differ from the proposal: a
dashboard starts from a robot and a time range, and live panels on the open capture come first.
The designs those answers need are agreed before they are built.

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
  for each request. A panel refreshing every few seconds would decode the session from its
  start each time, in the process that records. This was not measured.
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
