# Decision index

This index locates decisions; their linked records own the rationale, qualifications and
implementation status. Standing means the decision still applies, not that every planned
checkpoint is implemented. Superseded records stay visible so an old requirement is not
mistaken for current policy. The source and mirror decisions replace conflicting routing,
visualization-library and mirror policies in the older plans.

A plain date is recorded in the decision's source. Where an older record has no decision
date, `(indexed)` explicitly dates this index entry instead; it does not invent when the
owner decided. The 2026-10-18 source decisions are dated by the visualization plan's explicit
supersession record. Unresolved choices are not standing decisions and remain in their
owning plan.

The rows cover the numbered pit and Explorer decisions, each source/mirror agreement, the
routing decisions they replace, the columnar rejection and the hardening rules. The pit
plan's round-by-round implementation and verification narrative remains in its decision
record; test results and historical defect reports are not new architectural decisions.

| Date | Status | Decision | Record |
|---|---|---|---|
| 2026-10-20 (indexed) | standing | Pit 1: Captures are `.wpilog` files | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 2: Every change is captured | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 3: The NT4 client and server are pure Java | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 4: The gateway is read-only | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 5: The gateway mirrors the robot's clock | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 6: Live data has one source: the pit server | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 7: The pit server trusts its network | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 8: Context is written into the capture | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 9: The writer builds the index | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 10: The robot's logs are pulled only while it is disabled, throttled, and never deleted from the robot | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 11: A sampled series says so | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 12: The robot program is instrumented only by its owners | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 13: Prometheus and Grafana are views, not the record | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 14: A robot is its serial number | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 15: The pit server owns its store | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | superseded | Pit 16: mirror as an evictable scoped cache | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Pit 17: Laptops without a pit server sync by pulling from each other | [Record](pit-server/PIT_SERVER_PLAN.md#2-decisions-already-made) |
| 2026-10-20 (indexed) | standing | Explorer 1: One extension | [Record](EXPLORER_PLAN.md#1-decisions) |
| 2026-10-20 (indexed) | standing | Explorer 2: The webview never touches the network | [Record](EXPLORER_PLAN.md#1-decisions) |
| 2026-10-20 (indexed) | standing | Explorer 3: Every number on screen comes from a tool | [Record](EXPLORER_PLAN.md#1-decisions) |
| 2026-10-20 (indexed) | standing | Explorer 4: One configuration | [Record](EXPLORER_PLAN.md#1-decisions) |
| 2026-10-20 (indexed) | standing | Explorer 5: one shared local daemon for local clients | [Record](EXPLORER_PLAN.md#1-decisions) |
| 2026-10-20 (indexed) | superseded | Explorer 5: no source or server picker; direct remote choices now have separate connections | [Record](EXPLORER_PLAN.md#1-decisions) |
| 2026-10-20 (indexed) | standing | Explorer 6: A bridge, for starting on demand and for clients that take only a command | [Record](EXPLORER_PLAN.md#1-decisions) |
| 2026-10-20 (indexed) | standing | Explorer 7: Bulk data has its own door, in Arrow's format | [Record](EXPLORER_PLAN.md#1-decisions) |
| 2026-10-20 (indexed) | standing | Explorer 8: The webview's libraries are bundled as static assets, and chosen for taking Arrow | [Record](EXPLORER_PLAN.md#1-decisions) |
| 2026-10-20 (indexed) | standing | Explorer 9: Opening a log opens the explorer | [Record](EXPLORER_PLAN.md#1-decisions) |
| 2026-10-20 (indexed) | standing | Explorer 10: The log folder can be a store, by the person's choice | [Record](EXPLORER_PLAN.md#1-decisions) |
| 2026-10-18 | standing | Source browser: One Logs tree, with a separate root for each configured source. An unused collapsed source does not connect automatically. Views from separate sources can remain open together. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Remote operation: Metadata, analysis, data and live follows go directly to the selected owning server. Remote visualization needs no local server, mirror or laptop capture. Local visualization does not depend on the pit. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Source selection: There is no default store. With multiple configured choices and no explicit selection, the MCP service instructs the agent to ask the user. Availability, recency and matching filenames do not choose a store. An already explicit source or view remains selected for its operations. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Source kinds: Managed stores, mirrors and ordinary directory trees are separate sources. Browsing an unorganized directory does not require importing it or creating a store. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Manual and agent operations: Both use the same validation, binding, analysis, revisions and persistence services. Ordinary remote visualization covers reading, analysis, working views and permitted visualization metadata; log-library changes and administration are separate operations. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Saved definitions: Views and templates live in a library on the connected server, outside recording stores. They can be saved while reading a mirror or ordinary directory. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Mirror ownership: Ordinary import, capture, inbox and agent workflows do not write mirror contents. Peer admission, replacement and explicit cleanup all run through the mirror engine. Manually placed files are reported as unmanaged, not adopted as replica content. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Download scope: Users choose an explicit scope, optionally everything. Robot, event and date criteria select new downloads. Changing or aging out of that scope does not delete collected recordings. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Growing recordings: Copy each finalized file when available, including completed rollover members while the next member is growing. Active recording bytes are read from the explicitly selected remote source. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Master deletion: Retain local copies when the master deletes or stops listing them. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Capacity and cleanup: At the storage limit, preserve existing files and pause new downloads. Resume when space is available or the budget increases. Cleanup is explicit. No age-based or oldest-first eviction is implied. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Provisional recordings: Finalized peer files can be browsed, plotted and analyzed immediately, with provisional status visible in Explorer and MCP results. Master disagreement can replace provisional content after the correct copy is safely available. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Verified recordings: A verified file is never overwritten. A matching master confirmation changes metadata without rewriting correct bytes. A hash advertised by a peer alone is insufficient confirmation. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Conflicting peers: Keep the first accepted provisional candidate for a claimed file identity and version. Refuse later conflicting candidates and report the conflict. Filename equality alone does not establish that identity. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Peer discovery: Automatically discover peers for the same master store; also support manually configured addresses. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Peer redistribution: Share both verified and provisional finalized files, preserving provenance and status. Additional copies or transfer hops never promote provisional content. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Upstream submission: Mirrors do not submit recordings to the master. Unrecognized peer files can remain provisional until the master acquires them independently. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Agent display: Open in the originating eligible Explorer window when identified, otherwise the sole eligible window. Ask when several qualify; return an opening link when none does. Report display only after acknowledgement. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Copying layouts: Copy views/templates explicitly into the destination server's library. Adapting a view to a mirror requires an explicit destination source. Definition changes do not synchronize automatically. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-18 | standing | Deferred decisions: Authentication mechanisms, accounts and detailed permission policy remain deferred. No account or personal-ownership guarantee is inferred from an MCP session. | [Record](SOURCE_AND_MIRROR_PLAN.md#agreed-behavior) |
| 2026-10-17 | superseded | Every visualization reads only the laptop server; direct remote ownership replaces this requirement | [Record](VISUALIZATION_PLAN.md#consolidated-data-path-owner-decision-2026-10-17) |
| 2026-10-17 | superseded | Mirrors exclude finalized pit captures; finalized files are now eligible regardless of recorder | [Record](VISUALIZATION_PLAN.md#consolidated-data-path-owner-decision-2026-10-17) |
| 2026-10-17 | standing | Gateway access assumes the private team network | [Record](VISUALIZATION_PLAN.md#consolidated-data-path-owner-decision-2026-10-17) |
| 2026-10-17 | standing | Measured clock facts travel with the session | [Record](VISUALIZATION_PLAN.md#clock-facts-travel-with-the-session-approved-2026-10-17) |
| 2026-10-19 | rejected | An embedded DuckDB/Parquet analytics cache and a new Grafana query door without a measured need | [Record](COLUMNAR_STORE_PLAN.md#why-it-was-rejected) |
| 2026-10-20 (indexed) | standing | A recording comes first | [Record](ARCHITECTURE.md#a-recording-comes-first) |
| 2026-10-20 (indexed) | standing | Single-thread owners take short turns, measured from outside | [Record](ARCHITECTURE.md#single-thread-owners-take-short-turns-measured-from-outside) |
| 2026-10-20 (indexed) | standing | Cost follows the change | [Record](ARCHITECTURE.md#cost-follows-the-change) |
| 2026-10-20 (indexed) | standing | No native code runs in the process that records | [Record](ARCHITECTURE.md#no-native-code-runs-in-the-process-that-records) |
| 2026-10-20 (indexed) | standing | Derived data is keyed by its inputs and its recipe | [Record](ARCHITECTURE.md#derived-data-is-keyed-by-its-inputs-and-its-recipe) |
| 2026-10-20 (indexed) | standing | Capture has a disk reserve | [Record](ARCHITECTURE.md#capture-has-a-disk-reserve) |
| 2026-10-20 (indexed) | standing | Store-queue turns have a one-second reported budget; never interrupt a turn | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 (indexed) | standing | One disk budget per filesystem: 2 GiB reserve, 256 MiB floor and 512 MiB resume margin, all configurable | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 (indexed) | standing | A stopped capture is reported by health while process status remains ok | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 (indexed) | standing | The synthetic soak is 100 topics at 50 Hz for ten minutes, with no listener stall over one second | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 (indexed) | standing | Restart after three failed probes ten seconds apart | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 (indexed) | standing | Exact integer-microsecond bucket membership and time order before 0.10.0 | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 (indexed) | standing | Keep /data/entries for viewers, scripts and dashboards, and /read/v1 for exact typed reads | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 (indexed) | standing | Use an index row per decision with a date, status and record link | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 (indexed) | standing | The pit Explorer portion is named EXPLORER_REMOTE_SESSIONS.md | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Server log output never makes a thread wait: about 1 MiB of buffer with its own writer; lines that do not fit are dropped, counted and reported | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Sync from Laptop and the other copies that ran on the store worker move outside it, with space reserved first | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | UTC on /data/entries interpolates between wall-clock readings and is null before the first | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | read_entry, /data/entries and the preview unwrap declared angles; /read/v1 stays raw | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Exact samples when they fit, chart windows over their data and charts' dropping of non-finite values stay as named route differences | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Build a persistent scan index keyed by content, without waiting for a real-log measurement | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | A pending capture publication records its cause, which catalog status and mirrors show | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Multi-session analysis is a new tool; compare_matches stays the two-file comparison | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Ambiguous duplicate/backward wall-clock mappings are refused in both UTC presentation and system-log mapping | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | System-log wall-clock inversion is exact until its final conversion to double | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Remote replica status gains bounded pending-session lookup without changing immutable file identities | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Capture startup continues after verified publication-only failure; unsafe or corrupt recovery remains fatal | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Registering a folder that holds no store neither takes nor creates its store lock | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | The one real-log exception: the persistent scan index is timed on three local team logs, with results kept local | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Multi-session analysis compares finished sessions in stores and mirrors only | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | A multi-session metric is one statistic of one number: mean, std_dev, min, max, median, p5 or p95; angles get a circular mean and spread | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Multi-session work is capped before it starts, at most two comparisons run at once, and no new work service is added | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | A multi-session call reads at most 1 GiB of logs; median and percentiles keep at most 65,536 values per session and metric | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | A session's anchor defaults to its only WPILOG file; a session with several requires a choice | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
| 2026-10-20 | standing | Multi-session analysis chooses its source by scope_id as other tools do and keeps its designed duplicate, failure, pooling, confounder, unit and reply rules | [Record](ARCHITECTURE_HARDENING_PLAN.md#decisions-for-the-maintainer) |
