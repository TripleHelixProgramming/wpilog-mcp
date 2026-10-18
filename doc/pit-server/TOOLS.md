# Pit server: Tools

> **Experimental:** the pit server has limited controlled-shop evidence. These documents are
> retained for development and controlled shop evaluation, not as a recommendation for team use.
> Automated tests and simulation do not establish reliability on a real robot.

For fresh data, use `list_sessions`, `get_latest_values` and `wait_for_change`. The current
capture is the file `list_available_logs` marks `open`.

The MCP resource `pit://session/current` is discoverable without a tool call. It returns
session identity/file/start/connection, gateway status and providers, or `not_applicable`
with a reason when no session is open. It uses published snapshots and supports reads, not
resource subscriptions; prompts remain empty.

Every tool with a time scope accepts `last_seconds`: a positive finite N, ending at the open
capture's estimated robot time fixed for that call, or a closed log's last record. Without an
estimate, the captured prefix's end is the anchor. It cannot be combined with `start_time` or
`end_time`; scope/windows intersect it.

In the result, `inputs.last_seconds` records N and `inputs.window` the resolved absolute
start/end. `compare_matches` resolves independently for each log and reports `inputs.windows`
keyed by path. `inputs.session_time_range` still reports the captured prefix's range, not an
assertion that fresh values were published throughout that window.

## Tool reference

The complete parameters and results live in [Live tools](../TOOLS.md#live-tools) and
[`search_system_logs`](../TOOLS.md#search_system_logs). They are registered on every server.

## Sampled metrics beside the tools

`GET /metrics` is an HTTP view, not an MCP tool. It serves current numeric NT4 values,
robot-clock ages, recorder and pull counters, and server JVM measurements in Prometheus
format. Struct fields use the same paths as the analysis tools, but only published schemas;
strings are omitted. The [standalone guide](STANDALONE.md#metrics-and-a-starter-dashboard)
lists the fields, configuration and starter Grafana dashboard. A scrape can miss changes:
the capture is the record, and tools reading it analyze a fixed prefix of that record.

## Listing captured and pulled files

With `capture.pull` enabled, partial transfers stay out of the log listing; verified copies
appear under their session with device identity unless the file logs its own serial. A
logged/device disagreement is kept in the session manifest and server log. Moved paths remain
usable by tools after the seven-day listing notice. Windowed mapping supports WPILOG files
through 1 TiB. A file beyond that bound remains in `logs` with an explained `read_error`.

## Additional stored context

A store robot can also carry `contacts` (`address`, `host_key_fingerprint`, `seen_at`)
documenting SSH key history.

## Inputs on an open capture

An open capture adds `inputs.session_time_range` (`start_sec`, `end_sec`): the complete fixed
prefix of that capture file available to the call, independent of its requested analysis scope.
A rolled session has several files; each call and its range describe one file. `compare_matches`
instead adds `inputs.session_time_ranges`, keyed by each live log's path. Later calls can see a
longer prefix.

## Daemon context conventions

The capture's `/Daemon/` convention records context alongside NT4 entries without an `NT:`
prefix. `/Daemon/roboRIO/` holds sampled operating-system measurements with units in their
names; entry metadata gives the SSH host and period.

`/Daemon/Tail/<host>/<role>` holds received text. The resolver recognizes `program_console`,
`kernel`, `syslog`, and `journal` as followed-file roles, including `program_console` in the
console-text role; unknown roles remain ordinary string entries. `search_strings`, timeline
error counts and CAN text analysis read them through the same text path as `messages` and
`console` in robot logs.

Tail timestamps are receipt times, not timestamps parsed from the line; pre-session buffering
is stated in metadata. A drop notice is recorder text, not a robot message. Pulled companions
use `search_system_logs`: the tail is the timely copy, the pulled file the exact record.

## Mirrored listing facts

For a mirrored log, `session` also reports `origin` (the origin URL), `complete`, `growing`,
`last_sync` and `age_sec`. The last successful synchronization stays visible offline.
`complete` means every file the mirrored session lists was verified; `growing` is true only
for a copy of an open capture that 0.10.0-dev1 to dev4 made, which the mirror does not refresh.
A mirror does not hold the pit's captures, so a capture is read on the pit server.
The `stores` summary marks `mirror` and includes its origin, pins and freshness. A mirror refuses
imports and leaves unmanaged files untouched; pinning is a local HTTP UI control, not a tool.

## Recorded robot identity

Plain-directory logs also carry `robot` when `/SystemStats/SerialNumber` and
`/SystemStats/Comments` (or their `NT:` forms) supply it within the first 2000 records. Import
inspection can read later identity. This logged identity wins over the store's device identity.

Store rows without a serial can carry `robot_candidates`: each has `serial_number` and
`evidence` (`kind`, `value`). Kinds are `logged_team_number`, `entry_set`, and
`rev_can_inventory`; the latter two values are hashes of exact sorted fingerprints, persisted by
import inspection in the manifest. Listing never scans a file for candidate evidence. Plain
files and older store manifests without fingerprints have no candidates. Each kind must match
exactly one known serial; conflicting hints are omitted. This never assigns the file.
Unassigned REV rows can carry the same candidates.
