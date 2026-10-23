# Changelog

All notable changes to wpilog-mcp will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed (breaking)

- The unreleased `health_check` now reports `scan_index_disk_cache`, with current entry and
  byte counts and process hit/miss counters; previously the released `parsed_log_disk_cache`
  block described an eager cache unused by ordinary reads. REV cache fields remain.

- Automatic mirror synchronization now uses the format-2 replica protocol. Previously
  scope and capacity could evict collected files. Legacy mirrors now require explicit format-2
  migration; old files, pins and journals survive without treating transfer verification as
  master confirmation. Acquisition scope, bounded metadata/disk admission and explicit cleanup
  replace automatic eviction. Format-2 mirrors pull finalized versions from manual or discovered
  peers and confirm authority directly with their master. Like the legacy mirror, they copy what
  the pit pulled or was given and each capture's clock record, never a capture. Older binaries refuse format 2.

- MCP log discovery now requires an explicit source when multiple stores or directories are
  configured, instead of preferring an import destination or blending roots. `list_source_scopes`
  returns bounded choices, including unavailable roots. Scoped relative reads and visualization
  API 2 references retain the selected server/store/directory; equal hashes in another copy
  cannot silently substitute for it. API 1 rejects qualified references and views. Mirror
  synchronization uses separate selected stores and never blends Explorer sources. `list_available_logs` no longer returns
  `source_sync`, `source_files`, `excluded_directories` or `preferred_store_id`; its legacy `view`
  parameter is validated but has no effect.

**Notable changes:** Claude Code registration is now at user scope, shared across projects. The extension retires its old project `.mcp.json` entries where it can safely do so; tracked and custom entries need the migration steps in the standalone guide.

- **Extension registration:** every window and agent now uses the standalone install's shared `http` server instead of per-client stdio servers. Directory, team and TBA-key changes replace a session lease without restarting it. Claude Code uses a user-scope bridge; permanent configuration belongs in `servers.yaml`, with optional project YAML for terminal directory leases. The development builds' `useStandaloneServer` and `idleExitMinutes` settings are retired; `maxHeap` still reaches the launcher through `WPILOG_MAX_HEAP`.
- **Project `.mcp.json` retirement:** on project open, the extension removes only recognized old entries from untracked or ignored files, retaining other contents. Tracked, custom and unrecognized entries are left with a note. Development builds' `vscode-default` daemon is stopped before its private settings are removed, keeping them for retry if the stop fails; previously old project entries kept launching separate servers.
- **Directory listing:** `list_available_logs.log_directories` now contains objects with `path`, `origin` (`configured` or `leased`) and nullable `team`. Clients expecting strings must use `log_directory_paths`.

### Added

- `/data/entries` reads a robot over a UTC range: `robot`, `start_utc` and `end_utc` in place of
  `path`. The server chooses the robot's files from the store's catalog, places each by its own
  measured clock, and answers as one table in UTC; by default a stretch reads the roboRIO's log
  where one exists and the pit's recording otherwise, with each entry named as each source names
  it (`names`, `capture_names`), and `source=capture` reads the recordings alone. Two files of one
  source that cover the same minutes are refused together, never merged, except the files of one
  recording, which are read in order; files that cannot be placed, and manifests the catalog could
  not read, are listed with the reason. `/data/robots` and `/data/sessions` list a store's robots
  and one robot's sessions as CSV, from the catalog alone, for a dashboard's choices. Before, a
  request read one file, so a dashboard over a robot's history needed one query for every file.
- The Compose example's Grafana gains a history dashboard, **Pit / Robot history**: a robot,
  chosen from the store's robots, and a source over Grafana's time range, a table of the robot's
  sessions whose rows link to their own range, and five panels drawn as bands from each bucket's
  minimum to its maximum with the mean. Each panel has an entry box for each source, since the
  roboRIO's own log and the pit's recording name a signal differently, and every box starts
  empty. Its first panel says what each refusal means. CI's metrics check provisions it and sends a
  panel's own query through Grafana, which the server, configured without a store, refuses.
  Before, Grafana showed the open capture and sampled latest values, never stored history.
- A robot range reads REV signals: a name that starts with `REV/`, the same in `names` and
  `capture_names`, is read from the robot's REV logs, each placed through the alignment the store
  recorded with a WPILOG of its session that the range placed, the roboRIO's log before the pit's
  recording, and decoded for the signals asked for alone. A REV log without a recorded alignment is
  listed as skipped, never correlated during a request. Before, a range refused a REV signal, and
  one was read only by its log's path.
- Every capture records its server's wall clock as `/Daemon/Clock/EpochTimeMicros`: after each
  NetworkTables time synchronization, the next value logged takes a reading at its own robot
  time, so a robot that publishes no wall clock can still be placed in UTC, live and in its
  finished file, with `utc_basis.clock: pit_clock`. Before, such a capture refused every UTC
  window with "No wall-clock entry". There are about 1,200 readings an hour; they never widen a
  file's time range, are never forwarded to gateway clients and never make a file roll over, and
  an upstream topic of the same name is not recorded.
- The Compose example's Grafana gains a recorded dashboard, **Pit / Pit recording**, beside the
  sampled one: battery, CAN utilization, loop duration, robot CPU and free disk from the open
  capture, every value drawn as a band from each bucket's minimum to its maximum with the mean,
  the last five minutes by default, refreshed every five seconds, with zoom down to single
  samples. Grafana reads the data endpoint's CSV through Infinity 4.1.1, pinned and installed
  before Grafana starts, from a provisioned data source whose base URL is the server. Infinity
  shows a refused panel's status code but not the server's reason, so the dashboard's first
  panel says what 404, 422 and 400 mean. Before, the pit wall showed only sampled latest values,
  so a voltage dip between two scrapes was lost.
- `/data/entries` takes `capture=current` in place of `path`: the capture this server is
  recording when the request arrives, so a dashboard panel follows it across a rollover or a
  new session without knowing its file. The response names the file it read; with nothing
  recording the request is refused with `404`, the reason and the last session's file. Before, a
  panel looked the open file up in the catalog and went on plotting the old file after a
  rollover until the dashboard was reloaded.
- `/data/entries` takes `start_utc` and `end_utc`, ISO 8601 instants as Grafana's time range
  gives them, to choose the window by the log's measured UTC: the window holds the samples whose
  UTC lies in it, both ends included, and implies `utc=true`. A log that cannot be placed in UTC
  is refused with `422` and the reason, not answered with an empty table, and each entry's
  `utc_basis` counts the samples before the clock's first reading, which no UTC window holds.
  Before, a window could be asked for only in the log's own seconds.
- The server builds a column file for each finished log in the background and keeps it in the
  disk cache, named by the content's SHA-256 and the format and recipe versions, and numeric
  reads take their numbers from it once it is built. A log gets one when the server reads it from its file, and when a store's
  session manifest records it, as an import, a pull, an upload and a capture's close do; a
  closed capture still served from memory is read again from its file for the build. Builds
  run one at a time on a low-priority thread, wait while the heap has less than 512 MB free or a
  store would refuse a copy for want of recording's reserve, publish nothing when the file
  changed while it was read, and trim the cache to its limit afterwards. Before, the cache's
  size limit was enforced only at startup; now it is also enforced after each build, so other
  cached files can be evicted sooner. Each file also keeps its records' byte positions, packed
  as differences, with payload sizes and indexes where they cannot be derived, in 6 to 8% of the
  file, so native reads can use it. A guard test, like the scan index's, fails when a source
  that shapes a column file changes, until the change is decided.
- `compare_sessions` compares one statistic of up to eight metrics across up to 64 finished
  sessions of one store or mirror, each session counting once, with sample-weighted pooling
  only on request and labeled so. Each session keeps its own clock and verified members; the
  result reports requested, analyzed and skipped counts that always add up, every metric's own
  contributors, and the code version, robot, event, battery and firmware evidence of each
  session, unknown and conflicting values included. A plain folder, a duplicate selection and
  a still-recording session are refused or skipped with reasons, at most two comparisons run at
  once, and every cap refuses the whole call with true counts instead of answering from part
  of it. Previously only `compare_matches` compared logs, two at a time, and a set of sessions
  meant one `get_statistics` call per log with no summary that counted each session once.

- The unreleased replica service now records each deferred capture publication's safe cause
  and failure times, and serves bounded remote status and exact pending-session lookup.
  Previously markers lost the explanation across restart and peers had no pending-session
  status route. Complete pending counts are also available as a metric.

- The unreleased lazy reader now persists bounded, versioned scan indexes in the shared
  disk cache, each proved by a whole-source hash when written; previously every evicted
  ordinary log was scanned again. Known active and growing sources bypass persistence.

- The unreleased presentation reads now unwrap declared scalar angles before exact output
  or reduction on `read_entry`, the data endpoint and recording previews, using the chart's
  existing rule. `read_entry` and the data endpoint also accept explicit angle units for
  plain numbers. Previously a 179° to −179° crossing reduced to a mean of 0° on those paths;
  it now gives 180°. Native `/read/v1` retains raw values and arithmetic means.

- The unreleased data endpoint now accepts `utc=true`, adding a nullable measured UTC
  timestamp to Arrow and CSV beside the original log timestamp, with per-entry clock and
  null-sample basis; previously it returned only log-clock timestamps.

- The unreleased replica status and metrics now warn when records or exclusions reach
  80 percent of their separate count limits; previously operators had no count warning
  before catalog publication reached the cap. Metrics use validated facts in store-owner
  order and omit unavailable facts. Pending capture-publication causes are reported separately.

- The unreleased capture service now publishes bounded recorder state and write/stall ages
  in `/health`; previously a stopped recorder was indistinguishable from an active one there.
  A stopped recorder keeps process health `ok`. Synthetic and targeted checks passed;
  real-robot validation remains outstanding.
- The unreleased store now shares physical disk reservations across stores on the same
  filesystem for import staging, uploads, replica transfers, known-length robot/peer downloads
  and additional immutable or cross-filesystem placement copies, retaining reservations until
  I/O unwinds; previously these writers could consume capture's reserve before failing.
  Unknown-length command output remains outside that admission policy.
- The unreleased health and metrics routes now expose published usable, reserved and capture
  reserve bytes with opaque filesystem labels; previously server disk space was not measured.

- Mirrors exchange paged finalized offers and immutable byte ranges through a bounded server
  protocol. Peer copies remain provisional; only the selected master can confirm or correct
  them. Closed clock companions, provenance and separate metadata bases survive transfer.
  MCP/Arrow results and API 2 logical file-version previews expose the admitted generation and
  authority; previews refresh after correction without switching stores.
- Server-owned DNS-SD discovery and explicit manual peers support up to eight independent
  mirrors. YAML `mirrors` and extension `wpilog-mcp.mirrors` require separate destinations and
  explicit control selection. Previously a single runtime mirror was replaceable by another
  configuration. Window disposal now leaves the daemon's service grant and synchronization alive.
  The bundled JmDNS provider has a synthetic macOS proof; native Linux/Windows remain unverified.

- Mirror publication now has two bounded transfer workers, immutable generations, flushed-prefix
  resume checks, durable intents/receipts and read-lease retirement. Cancelled or timed-out I/O
  remains charged until it unwinds and cannot publish afterward. Explicit local cleanup previews
  affected views, binds catalog/policy revisions, persists exclusions and preserves saved layouts.
  The old writer could overwrite/evict copies; it remains removed. Paged replica offers now
  feed this owner, and only direct-master receipts can promote or correct provisional copies. No native durability or throughput claim is made.

- Visualization API 2 and native saves now use a server-owned library for saved views and
  templates. Previously saving a layout required a writable log store or created one; ordinary
  folders and mirrors now need only durable server state. Pinned owner references, bounded
  paging, atomic retry receipts and copy-only legacy migration preserve configurations and
  originals. Working checkpoints recover after a library commit, and saved ordinary-file
  locators survive draft expiry. API 1 retains its legacy store behavior.

- The unreleased visualization preview now draws fixed/full recording history from bounded server min/max/mean buckets across every admitted member. Previously it displayed only the most recently delivered 4,096 points, losing earlier extrema in dense intervals. HTTP and MCP share original-sample counts, chronological ordering, prefix evidence and explicit work/byte refusals. Review of the unreleased reducer corrected numeric projections: absent array elements are counted gaps, enums use their stored numbers, and signed/unsigned integers are range-checked before conversion, including enum fields. Historical panels use the window's reserved budget, coalesce late-sample refreshes, and retain their buckets across follow frames and presentation edits; exact statistics remain independent.


- The read-only NT4 gateway now publishes the context collected by the server, including JVM/Linux statistics, followed text and camera settings, with their original robot-clock timestamps and metadata. Newly committed kernel, journal and system-file lines use the same server-side clock conversion as system-log search before publication; unknown event times remain explicitly unmapped. Remote recordings preserve provider provenance. Previously only robot-published topics reached gateway clients; full pulled history remains in the store.

- The unreleased recording preview now separates acquisition health from quiet signals and finalized history, through one bounded HTTP/MCP metadata read. A completed follow can resume the same anchored recording without duplicating buffers or switching to a new boot. Server-session recovery retries window registration and reattachment, rejects stale replies and preserves pause intent; previously a failed continuation left an open view stranded and Resume could falsely report “following.” Cancelled inventory opens now receive and close their admitted handle, preventing hide/reveal from consuming the window's inventory slots. Capture receipt-to-publication and host/render acknowledgement timing are now observable within their own clock domains, without claiming end-to-end latency.

- Extension: Source Overview now reads directly from its selected local or remote MCP server,
  with separate collapsed connection/scope roots and persistent owner verification. This replaces
  the unreleased automatic translation from a pit source to a laptop mirror or local capture.
  API 2 data, follows, charts and library saves retain the chosen server/scope; API 1 and local
  administration retain their local gate. Remote-only browsing needs no local daemon, Java or
  recordings. Mirror/live services now require explicit enablement.

- A REV log in a store pairs with the robot's own log, on the pit and on a laptop's mirror: when the two were each measured against one capture, the REV tools, `list_available_logs`, REV reads and session reads align them by combining the two recorded offsets, drift included, and nothing is correlated again. `sync_method` is `COMBINED`; `sync_status` adds `sync.combined` with the reference, each measurement and `max_error_ms` (the sum of their bounds), and the confidence is the lower of the two. Previously a REV log paired only with the file it was measured against, so a laptop, which holds no captures, could not place REV data on the robot log's clock, and a REV log the pit measured while its capture was still recording paired with nothing, even on the pit.
- A pull or import measured against a capture records the capture's recording, and each clock record entry names its capture's recording, so the reference is still known after the capture closes and in a copy without it. An offset recorded by 0.10.0-dev4 or earlier against a capture still recording names no recording: it is read as the session's recording only when the session shows exactly one, and otherwise the file stays unsynchronized with that reason.
- `list_available_logs` gives each REV companion its `sync_method`.
- The pit writes a clock record beside each session for every capture file once it closes: every roboRIO uptime sample the capture holds, its hash, its recording and its span. The mirror copies these records instead of the captures, and `search_system_logs` takes kernel pairs from them, so a laptop gives the pit's kernel times line for line. Previously the pairing existed only inside the capture, and a copy without it listed kernel lines without times. Existing captures get records once after the pit's next start; `search_system_logs` now takes any log of the session as its path. Review of the unreleased keeper corrected its rollover watermark: each new capture session now starts its own count, so a previous session with more files cannot delay the new session's clock records.

- A laptop's server can now record live NetworkTables from the pit server's read-only gateway into a folder of its own, so its live views and live tools read local files instead of the pit. The extension sets this from its pit settings whenever a pit URL is set (`wpilog-mcp.live.enabled`, `wpilog-mcp.live.folder`, `wpilog-mcp.live.gatewayPort`), through new loopback-only controls under `/store/live`. Previously only a server whose `servers.yaml` configured a capture recorded live data. The recording keeps the robot's timestamps; the live folder never shares the mirror's; and a capture configured in `servers.yaml` cannot be replaced or stopped this way. The live tools answer from whichever capture is current. Review of the unreleased settings flow corrected competing configuration calls: activation and later live/mirror changes now share one queue through completion, so a slow capture stop cannot leave one service using the previous pit.

- The unreleased visualization preview now saves and re-reads historical intervals from time-series drag zoom and keyboard pan/zoom controls. Previously only explicit Start/End fields could change the read interval. Inspect History keeps its axis fixed during incoming data, Resume following restores the editor's chosen rolling window, and invalid clock ranges refuse before editing. Reads keep the existing per-window budgets and explicit recording-plot trimming limit; peers keep their own follows. Review also corrected Full recording after finalization reusing the previous rolling-window lease when the saved recorded policy was already full; the explicit gesture now re-admits history.

- Visualization source operations now provide bounded histograms and scatter data for files and anchored recordings through HTTP and MCP. Previously the unreleased source API supplied only values and statistics. Both chart operations reuse the image tool’s numerical rules over original samples, preserve exact timestamp pairing and full match totals, and report independent prefix evidence, decode problems and resource refusals. The local preview now displays both panel types with exact measurement tables, explicit scatter axis order, replacement paging and independent prefix evidence. Chart reads share the bounded window analysis queue, one-Hz cadence and pause/hide/close ownership; presentation edits preserve results.

- The recording-panel picker now accepts explicit numeric struct fields and array indexes, checked through shared cross-member compatibility before a layout edit. Previously the unreleased picker could add only whole entries, and raw-panel field requirements used an incompatible value type. Inline refusals preserve the layout; cancelled or stale checks cannot apply to a different selection, and repeated fields share one numeric requirement across panel types.

- Recording sources now support timestamp-latest/cursor reads and exact bounded statistics through shared HTTP/MCP operations. Previously the unreleased preview refused these reads for recordings. One proven prefix covers all members, timestamp ties retain explicit member/ordinal provenance, and statistics reuse the existing field, angle and quality rules over original samples. Decode problems and resource limits remain explicit; access changes discard the answer. Recording panels now use these operations for latest/cursor values and statistics instead of physical-delivery values or disabled analysis. Requests coalesce within bounded window admission, results identify their independent prefix, and stale, paused or hidden work cannot replace the display.

- The visualization preview now shares one configurable decoded-buffer allowance across recorded samples, live panels and paged inventory (64 MiB by default, 16–1024 MiB after window reload). Previously the unreleased recorded preview did not charge its samples to the live/inventory owner. Downloads reserve space first, recorded reduction adapts to available headroom and repeated plot/table copies, and capabilities report effective client-window allowances. Renderer acknowledgements retain charges through replacement; hidden expiry clears table contents as well as plots. Total-heap and sustained-performance measurements remain pending. Review of the unreleased preview corrected capabilities and working-view reads returning every attached window's session ID, which another local client could present to use that window's inventory handles, attachments and quotas. Replies now name windows by opaque per-process IDs, `current_session` marks the caller's own, and ownership still follows the session.

- Local recording layouts now open a bounded panel preview through revision-bound follows; previously the unreleased overview could only inspect or store them. Plots and ordinal raw rows share completed frontiers, recorded-clock evidence, pause/resume and independent leases; values/statistics use independently identified server prefixes. The paged picker avoids full-inventory limits, presentation edits retain acquisition, and hide/close/finalization release work. Buffers, delivery acknowledgements and decode diagnostics are bounded. Remote sources and the remaining Stage 3 gates remain pending.

- Recording views can now resolve a pinned working revision through shared HTTP/MCP binding and open a follow whose bootstrap uses that exact admitted prefix. Previously the unreleased layout metadata and raw follows had no revision-checked handoff. Unresolved requirements remain explicit, duplicate selections share one query, and view follows use fixed 1 MiB queried pages. Changed binding evidence refuses continuation instead of silently retargeting; the panel preview uses that same handoff.

- Recording-backed view layouts can now be created, saved, reopened and cloned through the shared API; the unreleased metadata workflow previously refused recording sources. Admission reuses the bounded identity/grant checks, releases the prefix before persistence and retains unresolved layout intent. Recording Resume/Open enters the local panel preview.

- Recording template compatibility now uses every freshly admitted member through the shared resolver, with explicit absence, conflicts, schema/unit evidence and bounded native witness scans. Previously the unreleased visualization checker rejected recording references as single-file sources. HTTP and MCP share the result and resource refusals; recording panels consume the same binding evidence.

- Source Overview now pages recording signals with literal search, exact-type filters, true totals and fresh selected-member details. Previously this unreleased preview downloaded one full inventory and refused large recordings. Refresh preserves exact-name selection, visible expiry refreshes automatically, hidden/closed views release their handles and rows, and older servers get an update hint. Review of the unreleased overview corrected overlapping initialization orphaning an inventory owner and failed reopen attempts making known-expired rows actionable again. Unavailable declarations use an explicitly exclusive mode that retains ordinary filters for switching back. Requests, retained rows and acknowledged renderer delivery have bounded memory admission; the recording-panel picker shares this bounded discovery.


- Recording discovery can opt into a session-owned paged inventory through shared HTTP/MCP operations. Previously a full recording inventory could exceed 4 MiB even when queried follows fit. Fixed listings now expose exact totals, conflicts and unavailable rows without retaining mappings or large metadata; selected details recheck current declarations and schema dependencies. Authenticated cursors, bounded construction, session shares, expiry and typed pressure refusals keep discovery bounded. Review of the unreleased checkpoint corrected final refusals blocking dispatch workers, MCP accepting oversized raw requests after JSON normalization, and capacity shortcuts bypassing session and notification rules. Refusals now have bounded I/O ownership, inventory requests count raw bytes, mixed-batch controls keep normal dispatch, and inventory-containing MCP batches have an explicit 8 MiB aggregate reply cap. Source Overview and the recording-panel picker consume these paged operations.

- Native follows can negotiate frontier metadata for queried entries and their required schemas. Previously every prefix carried every topic descriptor, so a 1 MiB follow could refuse large recordings even when only a few signals were selected. Existing callers retain full metadata; the broker verifies the negotiated scope, retires incompatible leases and keeps its byte reservations and complete server history proof. The separate overview inventory limit is unchanged.
- Recording-source inspection now admits open captures through the follower's anchored membership and writer/hash checks; previously the unreleased visualization inspector refused them. HTTP/MCP share bounded member inventories, declaration conflicts, explicit robot-estimate/sample-clock evidence and typed read refusals. The overview labels its recording identity and clock evidence.
- Stage 3 adds acknowledged recording follows over the shared reader: fixed bootstrap prefixes, bounded pages packing entries and projections, exact retries, metadata/schema updates once per prefix and explicit identity/history refusals. Previously the native follower tracked refreshes rather than acknowledged client pages. Oversized records produce explicit ordinal gaps so other entries continue; ordinary native queries keep their size refusal unless they opt into problems. The proof also corrects cold native live-prefix decoding, which threw on malformed bytes instead of reporting the record and continuing. Native HTTP follows now have bounded leases and dedicated long polling, release acknowledged prefixes before waiting, recheck access on cached retries and retain final member hashes. Review of the unreleased routes corrected malformed JSON bodies returning source errors and parser advice: read and follow routes now return a short 400 `INVALID_REQUEST` before source work. The pre-broker follow-up corrects four unreleased limitations: finalized follows now mark their last page and retain a 410 acknowledgement instead of returning empty pages forever; 413 refusals distinguish retryable shared-byte pressure from structural limits (oversized bodies previously returned 409); writer identity persists through finalization, recovery and copies so a replacement cannot pass with matching counts; and acknowledged 256-record turns share pages across signals and projections. Review of small pages also corrected large records repeatedly losing space to a busy entry: byte deferrals now let other selections keep packing, and the first deferred selection starts the next page. The extension now has a shared follow broker with unioned selections, acknowledged immutable delivery, cancellation, exact retries, bounded admission and explicit same-recording reset; previously clients had no shared follow owner. Its real-server fixture covers restart and finalization. Review of the unreleased broker corrected a fixed node ceiling that rejected byte-bounded full pages of array values: traversal now follows the negotiated byte budget while retaining the depth limit. The unreleased client now sizes the actual UTF-8 request at subscription admission against the server's 64 KiB cap; previously repeated windows could create a request the server would refuse, and the client cap was 1 MiB. An oversized join refuses before changing its peers. The unreleased shared-reset behavior is replaced by immutable opened jobs: only pending opens coalesce, and later joins, refreshes and reveals use independent leases without resetting peers. Local refresh admission happens before detaching its previous subscription; a local capacity refusal preserves both the requesting view and its peers. Review 8 corrected ordinary appends killing a long-lived view in the unreleased follower: catalog refresh accepts open-payload growth, retries other moving dependencies once, and marks a still-unstable inventory as a retryable 409. The HTTP broker backs off on the same cursor without resetting peers; permanent history changes still stop. Automatic live editor views and the combined display budget remain the next integration work.
- Session merges retain former IDs and resolve a capture by its original provenance anchor; previously a merge could strand a saved recording reference or widen it to unrelated files. Copy helpers, capture updates, recovery, import, pull, sync receipts and mirrors preserve aliases. Recovery also preserves original capture provenance instead of replacing its start with the merged session calendar. A lost alias requires known final hashes or an explicit unavailable result.
- Reusable recorded visualizations add searchable built-in/store templates, exact-name and explicit-role compatibility with unit/type reasons, pinned revisions, RFC 8785 template export/import, original-sample statistics and bounded raw-record pages. The recorded preview previously had no reusable template or agent editing surface. Seventeen separate local MCP operations share the HTTP owner; durable request receipts, revision conflicts and negotiated control notifications connect agent edits to Explorer without carrying samples. Review of the unreleased prototype corrected deleted views occupying live capacity for 30 days: deletion retry records now have separate count/byte bounds, six-hour expiry and oldest-first eviction. Missing store templates now return an actionable `TEMPLATE_UNAVAILABLE` instead of a bare path or revision zero. The unreleased control channel now keeps pings available when optional state fails and closes its workspace after stream/call drain; previously SSE could stop heartbeats or lazily recreate an owner during shutdown. The native fixture now drives the product first-store flow, template sharing, hidden/revealed data requests and overview Resume/Open new view. Continuous live data and explicit cross-window display requests remain later stages.
- Recorded visualization preview adds an optional source overview, independent layouts with plots and original cursor-value tables, labeled store saves, recoverable drafts, and explicit Entries targeting. Previously layouts could not be reused as separate working documents or saved through the real server. Session expiry releases crashed-window attachments, and stale/hidden replies are cancelled. Review of the unreleased preview corrected all-or-nothing saved-file listings, unbounded accumulation of unused source locators, and plot refetching/blanking on presentation-only edits. Unreadable saved files are now reported beside readable views, unused locators are reclaimed, and title/layout/style edits retain current samples. Normal log opening keeps the existing editor; live following is a later stage.

- Visualization Stage 1 adds shared definition validation, bounded revisioned working-state and store metadata primitives, transport-derived local caller authority, and an isolated native-editor lifecycle prototype. Previously views had no durable owner identity or revision contract. Named servers report `server_instance_id` and `working_state` through health and the server guide; unavailable optional state warns and uses ephemeral identity with `working_state_reason`. The unreleased prototype retains conflicting backups for comparison, releases the native Save acknowledgement wait after five seconds with a warning, quarantines bad working records intact, and prunes expired drafts before quota checks. Normal log opening still uses the existing Explorer editor.

- The Entries sidebar now offers **Group by: Path / Data type**, remembered per workspace. Data-type groups show entry counts and full signal paths while retaining filtering and lazy field expansion; previously entries could only be browsed by their path hierarchy.
- `./gradlew runExtension` compiles the extension, opens it in a VS Code development window, and watches TypeScript changes. `openExtension` opens without watching; `watchExtension` watches without opening. These tasks find Node on PATH or in an existing Codex runtime, with an explicit executable override, and reuse the installer's editor lookup. Previously local UI iteration required separate compiler and editor commands.
- The Entries sidebar now filters full signal paths in an inline text field, with clear and Collapse All controls. Previously filtering opened a separate input dialog and the view had no collapse-all action; unopened groups remain searchable and matching paths open to reveal their entries.
- Optional data-store setup enrolls reviewed source folders for server-owned imports that continue while the shared server runs, even with editors closed. Previously the organizing offer performed only a one-time operation. Copy is the default; automatic Move requires a completed-file acknowledgment, and duplicates stay at their source. Revisioned machine-local policy, durable source receipts, status, pause/resume and Sync Now preserve provenance without rewriting YAML.
- The default log listing uses an opted-in store and pending/refused source versions, hiding verified originals before counts and paging; Show All Sources retains the full view. Previously copied originals and their store entries appeared together. Explicit paths and clients without an enrolled policy retain their existing behavior.

- The explorer's manual Organize Logs command can import copies into a user-chosen data-store folder, including a new store, and remembers it in Additional Log Directories. Previously Copy and Move both organized in the source folder and Import required an existing store; their labels now state where files go. Imports preserve the originals and reuse the server's content-based duplicate detection.
- Native read queries now project nested struct/array fields and reduce numeric windows to explicit min/max buckets through the same field and arithmetic owners as legacy tools. Previously third-party readers had to reimplement these operations or use the explorer endpoint.
- Multi-entry native reads admit separate tables together, in NDJSON or multipart Arrow, and manifested sessions can be read on a requested anchor clock with recorded alignment and source coordinates. Previously readers had to coordinate separate queries and combine files themselves; unproven clocks and overlaps are never silently merged.
- A caller-polled local follower resumes appended WPILOG prefixes, including partial records and backward timestamps, while retaining older cursors. Previously local native reads could only refuse a growing file and start over.
- Native REV queries decode only named signals and the model evidence needed for device identity; complete descriptions still share their decode with later queries. Previously reading one signal expanded every device signal and could exceed the decoded-storage budget unnecessarily.
- A standalone Java reader and additive `/read/v1` HTTP API expose bounded snapshots, native samples and schema provenance to third-party clients. Previously custom applications needed server internals or the explorer's extraction format; MCP and `/data/entries` retain their formats and admission, with the integer bucket correction described below.
- Read-only store catalogs now expose recorded files, shared system receipts, provenance and mirror facts through the public reader, and REV access applies recorded offset and drift with an explicit raw-clock fallback. Previously those manifest and alignment owners were reachable only through server workflows.

- MCP and Arrow/CSV now share read admission and record traversal with the local API, including writer-owned live prefixes. Previously the cached, live and selective paths traversed records separately; compatibility materialization retains existing diagnostics, values, ordering, reload notices and lifetimes.
- A transport-neutral local read foundation now offers explicitly owned snapshots and bounded, selective WPILOG cursors with exact timestamps, native values and scoped diagnostics. Previously every narrow query decoded a whole entry; existing MCP and Arrow consumers retain their established behavior.

- Windowed WPILOG mapping reads, imports, uploads and captures files through 1 TiB; the old single buffer refused files above 2 GiB. Ordinary-file indexes retain four-byte offsets, and all windows are released before Windows renames.

#### Capture and store

- Configured `context.photonvision` hosts now supply PhotonVision v2026.3.4 camera configuration snapshots beside NT detections; calibration, pipeline and hardware context were previously absent. Backend/version/shape failures retain the last snapshot and publish a stand-down reason in live tools, manifests and metrics.
- Pulled system text now travels through the store door, peer sync and mirrors with committed-prefix reads, hash checks and growing-file resume. Previously only telemetry files crossed that boundary; mirrored searches now use their local text, and a missing copy names the collecting server.
- A server with a `capture` section records NetworkTables into ordinary WPILOG files and serves the open capture to every log tool. Previously tools required a file already on disk. Clock continuity distinguishes a Wi-Fi interruption from a new boot; exclusion, thinning and per-topic costs make recording policy explicit. Each tool sees a fixed prefix, with a configurable hot window and older values read from the file.
- Manifest-backed stores organize WPILOG and REV files by robot and session, preserve names and provenance, deduplicate by SHA-256 and pair REV companions by data correlation. `wpilog-mcp import`, HTTP import jobs and a watched inbox bring existing folders or USB copies into that layout; previously filenames were the only organization. Moves leave readable aliases, and hand-copied strays remain unmanaged.
- Unassigned logs can be assigned through the explorer or `POST /store/assign`, retaining provenance. Inbox batches carry an explicit robot choice and explained refusal receipts; previously assignment required moving files by hand.
- Robot identity now includes logged serials and comments in plain folders as well as stores, device identity learned through SSH, and reported logged/device disagreements. Unique `robot_candidates` name fingerprint evidence without assigning a robot; previously capture paths remained tied to an address and identity was limited to imports.
- `wpilog-mcp sync [url]` copies missing file hashes between laptops through a running daemon or the offline store lock. Overlapping sessions of the same serial converge, provenance retains the source store, disagreements preserve local human fields, and peers are remembered. Previously sharing an organized store required manual copying. Interrupted transfers resume after a content check; neither side deletes the other's files.
- A local server can maintain a mirror of an origin store for offline analysis, scoped by recent days, events, robots and pinned sessions under a size cap. It copies the logs the origin pulled from robots or was given, with their system text, but not the origin's own captures, which stay at the origin while the laptop records live data itself; each mirrored session lists only what the mirror holds. Interrupted copies resume, moves follow session IDs, and listings show synchronization age. Previously offline work required manual copies; mirrors preserve recorded REV alignment and refuse imports and peer sync.

#### Pit server

- Explicit `context.jvm` configuration now polls the robot JVM's memory, cumulative collections, threads, classes and CPU through the JDK JMX connector. Previously capture lacked JVM context. Receipt timestamps and measured uptime pairing, bounded worker ownership/backoff, session-safe delivery and provider costs reach the live tools, manifests and metrics; robot launch flags remain the team's choice, and Flight Recorder streaming is still pending.
- `robot-facts` now probes the deployed program's runtime module list and distinguishes missing/refused remote-management and Flight Recorder modules. Previously the shop report could not establish those prerequisites.
- Opt-in system-log pulling collects kernel buffers, syslog rotations or the whole journal, NI logs and JVM crash files through the disabled-only SSH pull pass. Previously only telemetry files were pulled. Content receipts and durable cursors prevent repeated copies; crash files use recorded program PIDs, and unverified NI-image candidates remain off by default.
- `search_system_logs` searches manifested local companions with shared severity rules, paged totals, and written clocks mapped through measured kernel pairs or recorded systemTime. Previously assistants could search only captured text; unknown clocks remain null with reasons, and the exact pulled record is distinguished from timely tail entries.
- SSH-configured captures now record adaptive roboRIO system stats and followed console/files under `/Daemon/`, sharing one host connection with pulling. Previously these operating-system measurements and live console lines were absent. Send/receipt-time mapping, bounded buffering, reported drops and provider costs appear in live tools, manifests and metrics; both providers default on for shop testing, with explicit opt-outs.
- The read-only HTTP store door serves configured catalogs, session manifests, ranged file reads and prefix hashes. Previously stores were readable only through their filesystem. Inbox files, strays, credentials and lease-only directories remain outside the door.
- Opt-in `capture.pull` fetches robot WPILOG and REV files over SFTP only while the connected robot is disabled and settled. Content checks protect resume and DataLogManager renames, reused names retain old generations, and verified copies join sessions by serial and near-zero correlation. Previously the server had no robot-file transport. Pulling stays off by default pending the shop test and never changes or deletes robot files.
- Laptop uploads can reach a pit server's verified importer over HTTP, retaining the source, checking its hash and reporting duplicates. Previously network access could read sessions but not send a log; server-path imports and assignments remain loopback-only.

#### Live tools and metrics

- `analyze_vision` now reports recorded `camera_settings` for each exact camera identity and requested window, or says none was captured; previously detections had no captured calibration or pipeline context.
- `render_chart` returns a bounded PNG and versioned chart specification with full-window measurements and inputs, for time series, histograms, scatter and field poses. Previously an assistant could return numbers but no chart; held signals use steps, phases come from the resolver, and a missing headless toolkit leaves an explained JSON result.
- Time-scoped tools accept `last_seconds`, reporting the resolved window for each call; agents previously needed absolute robot timestamps to ask about recent data. Present-tense discovery, startup guidance and the `pit://session/current` resource expose the current capture and its identity, gateway and providers.
- `read_entry` accepts `max_points` and numeric field paths, returning exact samples when they fit or time buckets with count, minimum, maximum, mean, first and last values. Previously callers had to page through every sample to see a long entry's shape; buckets preserve single-sample spikes and explain when nonnumeric entries cannot be reduced.
- `GET /data/entries` streams entries and struct fields as Arrow or CSV, with optional buckets, input metadata, ETags, a size cap and notice of changes during a stream. Previously whole-entry export required a separate CSV file per entry. The endpoint shares MCP's path and Origin checks, and `get_server_guide` advertises it on HTTP servers.
- The data endpoint also streams `REV/<device>/<signal>` entries on the WPILOG clock, with alignment method, offset and confidence. Previously it served only WPILOG entries; unsynchronized REV signals are refused with their reason, and signals still synchronizing return a retry hint.
- `list_sessions`, `get_latest_values` and `wait_for_change` expose session costs and matched imports, current publications with robot-clock ages, and bounded per-client waits. Previously these facts required reading captures or server logs. Queries use published snapshots and closed summaries remain in manifests.
- Every HTTP server exposes Prometheus text metrics without a new dependency: numeric NT4 values and ages, bounded arrays and schema fields, capture/pull counters and JVM measurements. A Compose setup provisions a starter Grafana dashboard. Previously there was no metrics endpoint; scrapes never join the capture or store queue and remain sampled views of the recorded data.

#### Gateway

- An optional read-only NT4 gateway lets dashboards and AdvantageScope connect to the pit computer, with robot timestamps, properties, schemas and session boundaries. Previously the gateway was only a test fixture. Subscriptions support every change or periodic latest values, and bounded queues disconnect slow readers without blocking capture. Real-robot dashboard checks remain manual.

#### Extension

- Explorer's Data view now exposes Perspective filtering, sorting, grouping, pivots, quick charts and CSV export on the selected Arrow samples, appending batches in follow mode. Previously samples could only be plotted. The engine, viewer and plugins are bundled static assets with their license and local-only WebAssembly/worker permissions; no runtime npm dependency is added. VSIX size: about 4.65 MB to 8.91 MB (+4.26 MB, 92%) on the same local package build.
- **Open in Notebook** saves three reproducible cells beside the log or in the workspace and opens VS Code's notebook editor. Previously notebooks required hand-written endpoint requests; the generated file preserves the entries, window and server inputs, with no bundled Python.
- Assistant chart links now open the selected entries, window and pane through a server-validated URI handler. Previously chart results could not open an explorer selection; invalid numbers and refused paths remain explained errors.
- **Ask about this selection** sends an explicit path, entries and window with the first tool to VS Code chat, falling back to the clipboard. Previously that context had to be copied by hand.
- WPILog Explorer adds Logs and Entries trees and a read-only WPILOG editor showing the time range, entries, field paths, sample counts and truncation warnings. Previously the extension registered assistant tools but displayed no logs itself. Its HTTP session reconnects after server restarts; the webview uses the extension's client and reports server refusals.
- Explorer plots share a cursor and a timeline with logged match phases and Driver Station events. Zoom, pan, visible-window statistics and change-only steps use the server's data; large windows show min/max bands until exact samples fit. Previously viewing and navigating signals required another application.
- Explorer's console filters text by pattern, level and visible window, collapses repeats, shows alert lifetimes and moves the shared cursor when a line is selected. Previously these events were available only through tool calls.
- Explorer expands array field paths into plottable elements, with **Plot Every Element** selecting the first sixteen. Previously an array's individual values could not be selected for plotting in the viewer.
- Explorer's field view draws the logged robot pose and heading on the bundled season field. It uses the resolver's declared convention or an explicit candidate selection; previously the viewer had no spatial view of the recorded motion.
- Explorer's REV pane groups signals by bus and device and plots synchronized signals beside WPILOG entries, showing alignment method, confidence and offset. Previously REV signals required separate tool calls; a bus that cannot synchronize keeps its reason and is not plotted on an invented clock.
- WPILog Explorer can organize folders with remembered offers, copy/move and robot choices, progress and exact refusal details. Store trees show robot sessions, REV companions, Unassigned, Inbox and Unmanaged files; plain folders keep their event/date view. Previously organization required the command or API.
- The pit server setting adds a second HTTP server for agents, lists and opens its sessions, and supports uploads, mirroring, pins, Sync Now, Open Mirror Folder and Sync from Laptop. Previously the extension offered only the local viewer. Offline views use the exact mirrored files.
- A pit proxy login can live in SecretStorage and be leased to the local server for mirror reads, with a secret-free Claude bridge registration. Previously password-protected pit URLs had no supported credential path; settings, manifests and command arguments carry no secret.
- The extension can install a missing standalone server from its bundled JAR, update an older launcher and offer Install Standalone Server on demand. Previously installation was a separate step and an older server only produced a warning. New configuration uses absolute User directories and team; existing settings and newer launchers are kept.
- **Show Server Log** and **Restart Server** operate on the shared standalone server. Previously inspecting its log or restarting a daemon required terminal commands.

#### Standalone install

- `robot-facts` collects a dated Markdown report of the roboRIO's image, files, utilities, permissions, authentication and bounded hash cost over the existing SSH stack. Previously the shop checklist required manually gathering these facts; refused commands remain evidence, and secrets are redacted.
- `run <name>` exposes named foreground startup, and `service-unit <name>` prints a hardened systemd service and health timer for review. Previously service managers had to use an internal flag or CLI-only configuration. `--managed` reports supervisor ownership in health and `list_sessions`; hand-written units must pass it too. The daemon manager refuses to adopt, stop or replace those processes and names the systemctl command instead.
- `wpilog-mcp connect <name>` starts or joins the named HTTP daemon and bridges stdio clients into separate MCP sessions; `connect --url <url>` bridges to an existing endpoint. Previously command-only clients each started a server. Lost connections produce request-specific errors and a nonzero exit so the client can reconnect.
- `wpilog-mcp stop <name>` requests a graceful shutdown through a loopback-only endpoint protected by the user's daemon token, waiting for active calls before falling back to process termination. Previously stopping a background server required killing its process manually.
- `idle_exit_minutes` lets a background HTTP server exit after a configured period without sessions or requests; health probes do not keep it alive. Previously unused daemons needed an explicit stop. Automatic exit is disabled by default, and capture servers require it to stay disabled.
- `GET /health` includes the server version and process ID, so launchers can identify the process answering a port instead of relying on liveness alone.
- `wpilog-mcp install` owns the JAR and launcher layout, optional initial configuration, version selection and text/JSON result. Shell, PowerShell and Gradle installers now delegate to it instead of maintaining separate layouts. Equal/newer launchers are kept unless forced; configuration and running daemons are preserved. Windows launchers also fall back to `JAVA_HOME` after WPILib.
- Installers support an exact `--tag`, the newest `--pre-release`, and a matching extension with `--with-extension`; terminal prompts offer directories and team, then the extension for each WPILib VS Code that accepts it. `--vscode <season>` names a WPILib season and `--vscode system` a VS Code installed apart from WPILib; the JAR's `install --list-vscode` prints what the installers would offer. Explicit `--refresh` makes a backed-up fresh install after stopping its daemons, where ordinary updates stay non-disruptive. Older releases fall back only when their JAR lacks the install verb, never after a real write failure.
- Loopback-only session registration leases directories and an in-memory TBA key to the shared server, revoking access when the session ends. Previously each client needed permanent configuration. The bridge accepts project/flag leases while taking daemon settings only from the home or explicit configuration, so one project cannot choose the shared server's settings.

### Changed

- The data endpoint's UTC mapping is kept: a loaded log builds it once, and a capture still
  recording extends it by the clock's new readings, so a request no longer reads the whole clock
  entry again. On a two-hour recording, a ten-second UTC window decodes at most 40 of its 1,400
  column blocks, and a number whose column holds every record reports its decode problems
  without being decoded. `utc_basis` gains `clock`: `robot` for the robot's own clock, or
  `pit_clock` when a recorded `/Daemon/Clock/EpochTimeMicros` entry places a log whose robot
  clock has no valid reading; an ambiguous robot clock is refused, not replaced.
- The `sampling` class the data endpoint reports for each entry describes the requested window,
  as data quality does everywhere else. Before, it was scored over the whole entry, so a window
  of an irregular stretch of an otherwise periodic entry was called periodic, and every request
  on a recording scored every sample.
- A recording keeps its index in columns outside the heap. Each entry's records keep their
  publication sequence, time, byte position and payload size, and a number its value, in blocks
  of 512 records packed as a column file packs its positions and written to a scratch file in
  the disk cache that is never forced to disk. Before, the open capture's index kept an object
  on the heap for every record, pinned until the file closed: on 100 numeric topics at 50 Hz it
  kept 62 bytes a value with the default hot window, about 2 GB over two hours, and the writer's
  thread spent more on each value as the heap grew, 152 ns over the second 15 minutes. Now the
  heap keeps 0.3 bytes a value, the writer's thread spends 40 to 47 ns a value however long the
  session runs, and the file takes 14 bytes a value. Numbers are read from their columns, so
  `capture.hot_window_sec` now keeps recent strings, raw values, arrays and structs only; a
  number whose record holds anything but its type's value in its type's width, such as an
  integer sent on a double topic, is read from the WPILOG as before. When the disk cache is
  off, the recording's disk reserve is short or a block cannot be written, the blocks stay in
  the heap, packed, at 14 bytes a value, and the current capture's row in `list_sessions` says
  why in a new `index_in_heap`. The file counts toward `diskcachesize` but is never evicted; it
  is deleted once the writer has moved on to another file and the index's last reader is done,
  and by the next start after a crash.
- A data request allocates about half what it did, with every response byte for byte the same:
  CSV rows are written from one buffer without making strings, a UTC cell is sized without being
  formatted first, measured UTC is mapped in long arithmetic with the exact path kept for
  overflow, and a recording's column blocks are read into a buffer each reader keeps. A UTC
  window of 1,000 buckets on a recording went from 4.5 to 2.5 MB a request. Before, twelve live
  panels refreshing together allocated 51 to 88 MB, over the live dashboard's 50 MB bound.
- A recording's struct fields are read from columns too. The capture's writer decodes each struct
  record once, with the schemas a read of it would use, and keeps each numeric leaf, such as a
  pose's `.translation.x` or a module state's `[2].speed`, as a column beside its entry's. Before,
  every read of a field on the open capture decoded the whole entry, so a live panel of a pose
  decoded every pose of the session on each refresh, 360,000 of them after two hours at 50 Hz.
  Now a ten-second window decodes none and reads a few dozen blocks, and an entry's decode
  problems are counted as its records are written. The writer's work on a pose record goes from
  about 60 to 270 ns, and on four module states to 635 ns.
  When a struct's schema is recorded after some of its values, the writer decodes those values
  again under it, at most 4,096 records for one schema; an entry past that, a struct array record
  wider than 64 elements, a field whose kind changes and an entry recorded out of time order are
  read from the WPILOG as before. Finished column files and a recording now take a struct's
  fields by one walk; column files are unchanged, byte for byte, on every fixture and five real
  logs.
- Numeric reads take their numbers from a log's column file once one is built: `read_entry`,
  `render_chart`, the data endpoint, `get_statistics`, `compare_entries`, `compare_matches`,
  `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `align_entries`,
  `find_condition`, the visualization's charts, and native reads through `/read/v1`, the preview
  and `compare_sessions`. Each works on a path's numbers by position, read from the file or from
  the log's decode alike, so the answers are the same either way. Before, every read decoded the
  log's records into one object per sample, even where a file was built. On three team logs,
  twelve whole-log overviews by `read_entry` took 10 to 33 ms from the files against 24 to 87 ms
  with the values already decoded, and ten seconds of each 1.5 to 3.3 ms against 11 to 52 ms. A
  native read comes from a file only for a numeric leaf every record of its entry holds, and not
  for an enum or uint64 field or, in file order or from a first record index, an entry logged out
  of order. Whole structs, pooled `[*]` reads, strings, arrays, REV signals, `export_csv` and the
  domain tools still read the log, and so does the reader library on its own machine, which
  never opens the server's cache.

- Every tool that reports summary statistics now computes them with one implementation:
  `get_statistics`, `compare_matches`, `compare_sessions`, `compare_poses`, `align_entries` and
  `analyze_vision`. A single value's standard deviation is now null, where `get_statistics`,
  `compare_matches`, `compare_poses` and `align_entries` reported 0, which read as no spread;
  `compare_sessions` already gave null with the reason. The deviation is computed in one pass on
  each value less the first, with compensated sums, so it streams as `compare_sessions` needs and
  stays exact where values sit far from zero relative to their spread. `compare_sessions`'
  plain one-pass deviation lost digits there: on the fixtures it was off by up to 4,177 units in
  the last place. Across the fixtures' 237 numeric series, `get_statistics`' means are unchanged
  and its deviations change by at most one unit in the last place, except that series of equal
  values now read exactly 0 instead of a remainder such as 1.8e-15.

- The unreleased source-scope discovery now reuses rechecked directory listings;
  previously every scoped request reopened each loose-tree directory listing. New store
  headers in known directories remain visible at the next request; delayed parent metadata
  is bounded by a one-minute listing refresh. Metadata traversal still scales with known
  directories.

- The unreleased reduction update now uses exact logged microseconds for `read_entry`,
  `render_chart` and `/data/entries` bucket membership and first/last order; previously
  double-second division could put a boundary sample in the earlier bucket, and distinct
  large timestamps could retain reversed file order after rounding to one double. Chart
  preparation and sparse exact-sample behavior remain unchanged.

- The unreleased capture writer now closes cleanly at its disk floor and starts a new file
  after usable space recovers, retaining the NT4 connection and recording the gap in session
  manifests; previously a full disk was discovered by a write failure and recording waited
  for a new robot clock. Exact reserve, floor and resume-margin byte settings default to
  2 GiB, 256 MiB and 512 MiB.

- Continuous folder imports and Set Up Data Store are now experimental and disabled by default behind the named-server `experimental_data_store` flag. Previously the extension exposed setup and loaded saved policies without that gate. With it off, the offer and commands are hidden, and the server loads no ingestion policy or directory grants and starts no synchronization; existing policies and store files are preserved for a later opt-in. Manual imports and organizing remain available.

- Power crossings and battery summaries name an unrecovered window boundary `open_at_scope_end`; the former `open_at_log_end` could be true before the log ended. The old `open_at_log_end` and battery-health `open_at_end` names stay as same-value aliases.

- Explorer source removes the empty placeholder left by a retired entry-cache clear. Runtime behavior and caching are unchanged.
- Daemon ownership decisions and empty power-scope explanations use documented nullable data internally, keeping `Optional` for return values. Previously those two field/parameter uses contradicted the source conventions; their behavior is unchanged.
- Voltage crossings still open at a scope boundary extend to that window end, or the log end for whole-log analysis, and carry `open_at_log_end` in bounded interval details. Dev2 silently extended the power-summary durations beyond the last voltage sample; the tools now state that basis, and battery-health intervals use the same rule.
- Gateway work admission charges each peer separately and raises the item budget from 4,096 to 65,536. Short stalls that fit the budget keep views connected; a refused upstream publication disconnects every view when fan-out resumes, including below the five-second global stall bound. Previously one peer disconnected every dashboard, and the initial per-peer fix could leave a missed change-only value silently stale. Capture remains independent of the gateway.
- Growing WPILOGs read by another process resume a copied index after file-identity and header/last-record checks; previously every append rescanned all records. Partial final records are retried, decoded caches refresh, and successful disk-backed tool calls name the file size they read. Unknown identities or changed anchors still load afresh.
- Harness replay allows 32,768 records per batch, bounds bytes for ntcore’s smaller publisher queue and wakes on pipe/receipt events; the former 1,024-record handshake dominated fast replay. Native replay now checks zero-shift transport fidelity, with `conformanceNative=none|sample|full`; Java keeps the placement matrix.
- SSH stats cache PID/start time and platform constants per connection, rediscovering only after process changes. Previously every sample scanned process command lines and fetched unchanged constants; steady samples use fixed proc reads and one combined `df`.
- `start <name>` and `connect <name>` replace a running daemon of another version and keep one of the current version. Previously an upgrade left the old JAR serving until the user stopped it; concurrent upgrade starts now share one replacement daemon instead of overwriting each other's claims.
- `start` verifies that a process answering its port is wpilog-mcp, adopts a matching server missing its PID file, and reports an unrelated listener without starting another process. Previously any HTTP response counted as the daemon and a busy port could spawn a server that immediately failed to bind.
- The mirror no longer copies the pit's captures; a laptop records live data from the pit's gateway itself. In 0.10.0-dev1 through dev4 the mirror copied every file of a session, captures included, and refreshed an open capture every pass, 30 seconds apart by default. Captures those versions copied stay unrefreshed until their session ages out. A mirrored session now lists only the files the mirror holds, and the cap counts only those.
- The unreleased scan index now reuses a finished log's index without reading the file when
  its size, modification time, file identity and creation time are unchanged and the file
  system can vouch for them; when only the modification time moved, or it cannot vouch (no file
  identity, whole-second times), a CRC32C of the content decides before a new scan. Previously
  every reopen hashed the whole file with SHA-256, which made reopening a large log from its
  index slower than scanning it. As the maintainer decided, a file rewritten in place with all
  four attributes restored keeps its old index. A store file's check against its manifest at
  admission is unchanged. The index format moves to 2; older indexes are removed at startup.
- The recorder writes its records at once. It keeps them in a 256 KiB buffer and writes them to
  the file at each 250 ms flush tick, when the buffer fills, and at close and rollover. Before, it
  wrote each record as it came, with two writes and three requests for the file's position for
  every value, about 4 µs. At ten times the soak's rate, 50,000 values a second, the listener
  waited in the kernel for over a second four times in twelve minutes, and at thirty times it
  lost the robot; written at once, a value costs about 20 ns. A process crash now loses at most the
  last tick's records, where before the file held every record the recorder had taken.
  The size the store publishes for the open capture counts only the bytes in the file. A write of
  the buffer that fails ends the session as a failed write, with no finish record naming an entry
  whose start was lost, and lets the recording's index go, so no call answers from records the file
  lost. The store also decides each entry's metadata role once, not for every value.
- A robot that reconnects on the same boot now continues its capture session in the session's
  next file, `capture-2.wpilog` and on, as a rollover does, instead of appending to the file it
  closed. The store had already hashed that file, and a store with a format-2 replica catalog had
  published the hash, so every read of the grown file was refused for a hash mismatch, the live
  dashboard's included, and its publication stayed pending as an authority conflict through every
  recovery pass. A closed capture file is now never written again. The next file starts with what
  the robot sends on reconnecting, including its topics' last values at their original times.
- `capture=current` reads the closed files of the session recording now that its window
  reaches, a rollover's or a same-boot reconnect's, as well as the open file, as one recording on
  the robot clock those files share. In UTC a time is placed by the clock of the file that was
  recording then, so a value a reconnect's file repeats at its original time keeps the time the
  closed file gave it. The response lists the files read and any closed file that could not be
  read, and a UTC window is refused with `422` only when no file of the session can be placed.
  Before, a live panel read the open file alone, so after a radio blip it lost everything before
  the reconnect until its window moved past it.
- A robot range loads its logs without starting REV synchronization, as the closed files of the
  recording `capture=current` reads do. Before, a range's first read of each log started the
  background synchronization a tool's read starts, which decodes every signal of each REV log
  recorded with it, for a request that named none.
- A robot range places the files of one recording as `capture=current` places a session's: a
  time by the clock of the file that was recording then. Before, each file was placed by its own
  clock, so the value a reconnect's file repeats had a second time, a synchronization from the
  first, and the history and live dashboards disagreed about it. A recording is now one capture
  start on its first collecting store, not a session: the files of a session were spared the
  overlap refusal, so a peer's recording of the same boot, which peer sync files in the same
  session, was merged in rather than refused.

### Fixed

- A read of a store's catalog that meets a folder the store renamed or removed meanwhile, as it
  renames a session's folder once the session's match is known, reads the store again, up to
  three times, and then names the folder still moving rather than passing it by. Before,
  `list_available_logs` could refuse a whole store ("No configured log directory could be read")
  or leave the closing session out of its answer; the shop harness hit it in one run of three on a
  Linux workstation.
- The Compose example's Prometheus and Grafana start on a host with SELinux enforcing, such as
  Fedora or RHEL: each file the stack mounts from the repository carries `z`, so Docker relabels
  it for containers, and does nothing where SELinux is off. Before, Prometheus could not read its
  configuration there and stopped, and Grafana could not read its provisioning.
- The first data request after a quiet stretch of a recording reads the clock's new readings from
  its column as numbers, and requests that arrive together wait for one of them to read them
  rather than each reading them again. Before, the first request after two quiet hours allocated
  52 MB for its clock, and each panel of a dashboard asking at once did the same; now the first
  allocates under 10 MB, most of it the readings the recording keeps.
- The unreleased data endpoint now serves a capture that is still recording, as the prefix its
  writer had published, without an ETag. It compared file snapshots unconditionally, and a
  growing recording has none, so every such request ended in Internal error after decoding
  each requested entry in full.

- The unreleased disk-space budget now measures a filesystem through a registered store folder
  that still exists. It measured through the folder of the first store registered on the disk,
  so deleting that folder made every later copy there, such as an import, an upload or a robot
  pull, refuse as `DISK_SPACE_UNKNOWN`, and health report the disk's free space as unknown,
  until the server restarted. A live capture kept recording: an unknown reading never pauses it.

- The unreleased NT4 gateway now accepts AdvantageScope. AdvantageScope 26 requests `GET /` on
  the NT4 port and opens its WebSocket only after a 2xx answer; the gateway answered 404, so
  AdvantageScope polled every 350 ms and never connected. After an NT4.1 handshake it also opens
  a timing-only connection (`rtt.networktables.first.wpi.edu`) for its clock requests, which the
  gateway refused, leaving it without the robot's clock. The gateway now answers `GET /` with
  ntcore's page and headers, and accepts the timing connection: it answers clock requests there,
  keeps the connection alive like a 4.1 connection, and leaves it out of `wpilog_gateway_clients`.

- The unreleased capture service now continues startup after verified local receipts encounter
  publication-only failures; previously a full replica catalog could stop recording after
  restart. Unsafe or corrupt recovery still fails startup.

- `search_system_logs` now preserves recognized written timestamps and clock anchors through
  exact rational wall-clock inversion, rounding only the final robot seconds to a binary
  double. Previously epoch-sized floating-point arithmetic lost fractional precision, and
  distinct large robot-clock anchors could collapse to one double and leave a valid time
  unplaced. Timestamp grammar, clock selection, session bounds, endpoint extrapolation and
  kernel mapping are unchanged.

- `search_system_logs` now leaves ambiguous wall-clock mappings unplaced with an explicit
  reason; previously conflicting duplicate clock coordinates could select a plausible but
  unsupported robot time.

- The unreleased peer synchronization now downloads, paces and verifies on a staging worker,
  with a separate persistent journal lock and short store publication turns. Previously a slow
  peer held `store.lock` and the mutation owner for the whole sync, preventing capture admission.
  Publication rechecks selected-session, duplicate, store-role and byte proofs; the next sync
  recovers receipts before peer contact while preserving incomplete-download and system-text
  resume prefixes. A completed cross-filesystem telemetry copy's original staging bytes are
  removed only after destination and source proof, including recovery of a committed receipt.
- The unreleased peer and robot-pull copy paths now reserve their additional destination
  allocations before payload I/O, and prepare immutable system-text or cross-filesystem copies
  outside the store worker. Previously those copies bypassed the capture reserve or could run
  on the owner through a filesystem move. Prepared copies are rechecked before atomic placement;
  changed originals remain after a committed copy instead of being deleted during cleanup.
- Server log output now uses a bounded 1 MiB buffer and a separate daemon, with whole-line
  loss counts and an ordered loss notice. Previously a blocked stderr could hold logging
  threads, including recording and the store owner. Normal shutdown drains after server
  cleanup for at most two seconds; a blocked sink or crash can still lose queued lines.
- The unreleased WPILOG reader now rejects synchronization-cache format 12 when adopting exact
  sample order; previously a cached REV alignment could survive a change to the ordering of its
  WPILOG clock inputs. Format 13 recomputes those answers from original microseconds.

- The unreleased printed systemd units now request a named-server restart after three bounded
  HTTP failures ten seconds apart; previously an alive process that stopped answering was only
  reported. An explicitly stopped service stays stopped. Printed-unit checks passed;
  Linux-host retry exhaustion and restart validation remain outstanding.
- The unreleased store importer now stages inspection, correlation and verified copies outside
  the store queue and lock, then publishes rechecked groups in short turns. Capture turns take
  priority, an independent watchdog counts actions exceeding one second, and shutdown
  retains active staging resources until I/O finishes. Previously a long import could exhaust
  capture admission and stop recording for the rest of a robot boot. Its completion report now
  uses the shared bounded log buffer, so blocked stderr cannot hold the owner after the action.
- The unreleased capture admission now retries a held `store.lock` with bounded backoff until
  its admission deadline; previously transient contention from another importer stopped it at
  the first attempt.
- The unreleased `import` command now probes the configured endpoint and checks the server name,
  including managed servers without a PID record. Previously it could import in its own process
  beside systemd. An answering stranger or a specific non-loopback bind now refuses with a
  remedy instead of permitting an unsafe offline fallback.

- Replica catalogs now publish bounded external parts behind a small atomic header. Previously
  ordinary metadata could fill the 4 MiB header at roughly 1,800 files, half-commit imports and
  stop later captures. Imports preflight limits; capture retains pending publication receipts
  and continues, including after restart.
- Mirror reconciliation completes bounded enumeration before payload transfers and renews
  expired sessions without switching suppliers. Previously slow first-page transfers could
  prevent later pages from ever being acquired.
- Unchanged verified replicas retain their confirmation, catalog revision and per-file receipt
  history; current byte checks reuse bounded hash proofs. Previously every pass rewrote and
  rehashed each held file.
- Replica downloads and metadata now use their own admission budgets; four slow downloads
  previously exhausted recording-inventory and visualization-library response reservations.
- Runtime mirror configuration now starts server-owned discovery after local startup, with a
  single provider owner under concurrent calls.
- Replica refusals retain supplier codes, remedies and retryability; ordinary filesystem errors
  no longer expose private paths or turn Windows drive letters into protocol codes.
- Canonical store membership retains replica admission through path aliases, and bounded parsed
  catalog reuse avoids repeatedly decoding the entire header during a read.
- Analysis of a master-disproved replica now warns with held and master hashes and sizes.
  The API 2 and mirror references now describe accepted file-version references and the current
  lack of new system-text prefix replication.

- `time_correlate` no longer treats real tiny signals as constant or assigns correlations to
  large constants whose computed mean rounds away from the value. Constancy uses a four-ulp
  range rule, and scaled centered products prevent underflow or overflow in correlation,
  lag search and autocorrelation-adjusted significance. Since v0.9.1, large finite signals
  could report zero correlation and p = 1, or lose autocorrelation and effective sample size.

- Visualization: a just-finalized capture can open as recorded content while its closed writer
  index remains cached. Previously admission treated the index's type as proof that the writer
  was still active and refused until eviction. Active prefixes still refuse the single-file route.

- Recovering a lost visualization save response now reconciles the original receipt with the
  current working view. Previously, retrying after a newer edit could restore the older layout
  and revision, falsely clear the editor's dirty state, and make the next edit conflict. Recovery
  retains the original request UUID, preserves newer edits and their native Undo/Redo, and keeps
  the retry intent if reading the current state fails.

- Source selection: raw `/read/v1/follows` now rejects qualified references before admission; previously it discarded the selected server and scope and could read another copy. Qualified recording views retain their owner through view-bound follows and cached retries.
- Source discovery now reports an unreadable configured root as unavailable while allowing explicit reads from healthy sources. Previously one directory permission failure aborted source enumeration and unrelated selected listings with an internal error; the failed choice now remains visible instead of becoming an implicit default.
- Power analysis now assumes the roboRIO 1 output-disable threshold of 6.3 V when neither an override nor a recorded setting is available, and states that the controller is unknown. Previously it mislabeled the 6.8 V rail-drop stage as that threshold and said the roboRIO 2 default was 6.3 V; its configurable default is 6.75 V. Recorded settings and logged brownout flags still take precedence over assumptions.
- Lag correlation now uses the same centered covariance and variance as the main correlation calculation. Previously large constant signal offsets could erase the measured variance, distort the coefficient or falsely report that no lag had enough overlapping samples.
- The unreleased recording preview now returns explained empty results for relative statistics, histogram, scatter and reduction requests before the first sample, then refreshes when data arrives. Previously absent measured bounds generated invalid times and left live analysis blocked until Retry. Active chart requests also treat an explicit null `last_seconds` like an omitted argument, preserving the fixed interval and reported inputs instead of returning `JsonNull`.

- Statistics: a valid numeric selection with no finite samples in its time scope returned an invalid-request error. It now returns `no_match`, a zero sample count, the scope and quality evidence, and a hint, without fabricated statistics. The unreleased live statistics preview stays eligible for refresh when a later prefix has samples.

- Extension: closing or reloading a window did not wait for its server sessions to end, so the ending could be cut off and the server kept the window's views attached, and its directory lease registered, for the hour a session lives; a reopened window was refused its own views. The window now waits up to three seconds for its sessions to end, after refusing new requests.
- A visualization operation that failed with an Error, such as stack exhaustion, never answered its caller, and the HTTP request waiting on it held its worker for good. Every failure now answers the caller, and the queue goes on with the next operation.
- Building the visualization workspace answered its waiting callers only when it failed with an exception. After an Error, such as stack exhaustion, the unfinished build stayed registered, and every later request that needed the workspace waited on it for good, a visualization tool call or a window starting its session among them. The failure now answers every caller waiting on the build, and the next request builds the workspace again.
- A JVM or PhotonVision provider's write on the capture loop answered the provider only when it failed with an exception. After an Error, the JVM provider stayed busy and stopped sampling for the rest of the capture session without saying why, and the PhotonVision provider neither stood down nor read its next settings message. Both providers now share one write path that answers every failure, and the provider stands down for the session with the reason.
- A view whose window closed without ending its session stayed attached to that window until the session expired an hour later. Another window asking for the view now takes it once the owner's session has gone two minutes without a request, the 30-second ping included, and without an open control stream; a window that is hung but still connected keeps its views. Asking does not count as the owner's activity, and the owner, if it returns, no longer owns the view.
- `search_system_logs` counted a REV log's own clock, and a pulled log's own clock without its recorded offset, toward the session's robot time, so a system-log line outside the session could get a time and then drop out of the default window. A REV log no longer counts, each log is placed by its recorded offset, and a log whose offset is invalid places nothing. The search also read a capture whose clock record and the robot's log already supplied every pair, and listed it in `inputs.clock_files`; it now reads only what contributes.
- `render_chart`'s histogram of values spanning more than a double holds (from -1e308 to 1e308) returned null bin edges and put every value in the first bin. Its edges are finite now, and edges that cannot be told apart, as a unit-wide bin around 1e308 cannot, are refused with the reason, by the rule the visualization histogram already used.
- Peer sync merging two local fragments of one session dropped the moved fragment's clock records from the merged session and left their files listed as unmanaged. The records are written again for the capture's new path, and an archived fragment's records count as store files.
- The mirror control accepted the folder of a capture configured in `servers.yaml` while that store was still empty, and would have made it a mirror. Turning the mirror off during a configuration reported it disabled while the configuration then started it, and the mirror and live-source controls checked each other's folders without one guard, so two changes at once could both take one folder. All three are refused now, with 409 for a change in progress.
- Case conversions followed the machine's locale: in Turkish, a name filter for "imu" missed `/IMU`, `end_reason` read "dısabled", TBA event keys were misspelled and the check for Windows failed. Every conversion uses the root locale.
- A loaded log's decoded-value budget was fixed when it loaded and counted only the logs before it, so the first two logs each claimed 60% of the heap, and an unload freed nothing for the others. Every loaded log's budget now follows the number loaded.
- A store log's REV log could be correlated afresh when the store's manifest changed between finding the log's REV companions and reading their recorded offsets. Every companion now gets its recorded alignment, or stays unsynchronized when the manifest no longer pairs it.
- A store listing no longer fails, or names a file as unmanaged, because the store was being written at that moment: its walk could meet a manifest being replaced, or a file placed after its manifest was read, and the directory walk itself failed when a manifest's temporary file vanished between being listed and being read. A write in progress and a file that vanished during the walk are no longer strays or failures; a temporary file a crash left behind is still a stray.
- The Explorer's plots and data tables, and the preview's plots, refuse an Arrow series that ends without its end-of-stream marker. In 0.10.0-dev1 through dev4 a series that stopped at a batch boundary still parsed, and was drawn or tabled as if complete.

- Pit log links now survive VS Code's own parsing. In 0.10.0-dev1 through dev4 the Explorer read a pit file's link from its printed form, in which VS Code encodes the query's `=` and `&`, so its pit editor could not find the link's server or path ("Remote log link is missing its server or path."). Readers now take the query VS Code keeps, and the Logs tree encodes each value twice, so a path's own `&`, `=`, `+` or `%` stays apart from the separators. Links VS Code saved for open editors still open when their paths contain none of those characters.

- The unreleased visualization client refuses a reply over its byte budget even when the whole reply arrives in the chunk that crosses the budget. Previously that reply still ended normally and resolved with the text collected before the cut, which then failed as a JSON parse error instead of the byte-budget refusal.

- A freshly configured mirror that has not yet learned its origin's store ID no longer makes the unreleased recorded-source lookups fail with an internal error; until it learns that ID it matches no reference, and lookups of other stores' files proceed. A mirror test hit the failure intermittently, depending on when the store first saw the new folder.

- Temporary recording-compatibility and other native-read refusals in the unreleased visualization API now include the same one-second retry delay as inventory pressure. Previously HTTP and MCP returned a retryable flag without timing advice; permanent refusals still omit it.

- The unreleased visualization preview shares bounded finalized-file hash verification across recording inspections and recorded-file admissions. Previously each fresh recording inspection rehashed every finalized rollover member. Changed file boundaries or manifest hashes still require verification, and current access checks remain mandatory.

- HTTP JSON size refusals now drain a bounded excess body before replying. Previously closing while a client was still sending could reset the socket and lose the explanation, including MCP and native read requests. Draining discards at most another 4 MiB within ten seconds or the route's earlier deadline; senders still transmitting at that bound or deadline are disconnected before response headers, without a guaranteed reply.

- `robot-facts --server` now explains configured-store access refusals and gives platform-appropriate service-account and direct-address guidance; previously an ordinary account saw only a path. On POSIX file systems it refuses a differently owned or unverifiable store before creating locks or replacing owner-only host-key manifests, so a privileged invocation cannot strand the service's local pins. Windows admission follows actual filesystem permissions; writable group-owned stores are no longer refused by comparing their owner with the current user.

- Capture now replaces the thousand-line INFO topic-cost bursts every five minutes with one summary of cumulative topic, record, byte, dropped and thinned totals and the five largest recorded-byte contributors. Per-topic detail remains available at DEBUG and through `list_sessions`.

- Robot pull pause messages now state the gate's reason and report changes without repeating an unchanged pause. Previously pauses printed `null` and expected power-offs appeared as warnings; a transfer interrupted after NT4 disconnects now logs its I/O retry at INFO. Known local filesystem failures, unexplained I/O failures and other exceptions retain WARN. Remote-handle close failures are distinguished from local checkpoint failures, which retain their cause if both operations fail.

- Pulled telemetry verification, inspection and matching now share one strict decode; parsing, correlation, prefix hashing and reader draining run outside the store queue, with placement evidence rechecked under it. Previously native REV files were decoded three times while holding the queue, and a reconnecting robot's first announcement waited behind imports. Capture admission is now asynchronous with a bounded ordered event buffer, preserving receipt and calendar times and explicitly reporting overflow or timeout.

- Robot pulls retain one SFTP handle across data blocks and checkpoint staged bytes and progress together, at 1 MiB or a one-second checkpoint deadline. Previously each 64 KiB block reopened the remote file and separately queued its append and manifest write. Durable progress never leads the staged data; an uncounted staged tail after a crash is truncated to the saved offset instead of forcing a copy from the beginning.

- Robot pulls resolve configured roots with SFTP realpath and walk each directory once. Previously `/u/logs` and `/U/logs` could transfer the same USB logs twice; existing alias manifests now resume their verified and partial files, including paced fallback proofs that previously could wait indefinitely. The synthetic roboRIO uses both aliases and checks one transfer per log.

- SSH stats now resolve filesystem aliases once per connection and sample each real directory once; USB aliases previously repeated df work. A budget-driven slowdown now states its round trip and budget instead of a null reason, and robot-facts times the actual steady command, its proc reads and each df path so the shop can locate the cost before changing the 100 ms default.

- SSH providers replace the misleading disconnected status during startup with the current identity, program-discovery or statistics-read step. Context and pulls now share one device-identity attempt per SSH connection, and replies from an old connection cannot supply identity or stand down its replacement.

- Failed SSH followers retain a bounded stderr prefix and the command exit status in their stand-down reason. Previously stderr was discarded, so a failed console command appeared only as an unavailable source.

- Console following uses an integer one-second interval accepted by BusyBox and GNU tail, and reports that interval. The former fractional interval made roboRIO BusyBox tail exit immediately. Robot facts now identify each provider utility separately, and the synthetic roboRIO uses BusyBox tail.

- Store Move imports across filesystems now force and verify the copied payload and commit its manifest before deleting the original; previously Java could delete the source before verification and restore corrupted bytes after a bad copy. A failed source deletion retains the successful import and reports the remaining original.
- Explorer shows configured and leased sources under connection and scope roots. Folder synchronization now reads local-ingestion status and receipts under the local connection; the removed listing fields had hidden the group. The ineffective Toggle All Source Logs command is removed.

- A REV file imported before its WPILOG is now reconsidered when the store gains eligible WPILOG data. Previously it remained unassigned until a manual assignment. The existing unique strong correlation and 250 ms gate still decide placement; bounded attempts retain original provenance and avoid repeated idle work.

- Configured symbolic-link log roots now list WPILOG and REV files, with loadable paths and no traversal of child links. In 0.10.0-dev3 a linked root returned an empty listing; linked store subtrees now retain catalog membership and resolved-path filtering too.
- Claude Code registration now recognizes and remembers an identical user-scope bridge in each editor or profile without showing the expected duplicate-add error; in 0.10.0-dev3 a second editor warned that registration failed, for both `wpilog-analyzer` and `wpilog-pit`. Conflicts leave the entry unchanged and give manual instructions. Real failures and conflicts retain the add command's stdout and stderr, while lookup output stays private; each registration keeps its own success and failure wording and a failure offers its registration command.
- Containment validators log their directory changes at DEBUG, while server startup reports each configured root once. In 0.10.0-dev3 internal validators added INFO lines to installation, library use and individual read requests, and startup announced directories more than once.

- Session overlap refusals distinguish closed recordings from potentially overlapping live prefixes; previously even an entirely closed session was described as live.
- Named foreground HTTP `run` accepts `port: 0` and reports its actual bound port; previously its startup test had to release a probed port before the child bound, allowing a competing process to claim it. Daemon commands still require a fixed port, and test cleanup no longer hides a startup failure behind a shutdown assertion.
- Native session unions recognize the store's `captured` provenance and include rollover files on their recorded clock; previously the mismatched kind excluded them. Original start time and collecting-store identity distinguish a recording from independently captured files merged into the same session. An excluded duplicate no longer hides a later source with recorded alignment.
- HTTP session reads reuse managed indexes and fixed live prefixes, with a shared retained-index budget; previously they scanned every member separately and refused reads after an open capture grew. Standalone catalogs now name open captures they cannot freeze instead of admitting an unstable session. Live members sharing an entry require explicit overlap admission because their manifests may lag the recording.
- Windowed session queries seek into each source through conservative inverse clock bounds; previously even a small window decoded every record of every member. Exact forward filtering and source coordinates remain unchanged.

- Public Java catalog and descriptor methods return typed models without relocated Gson in their signatures; previously consumers had to import an implementation package to use these APIs.
- Store transfers and uploads have independent streaming capacity, and overload returns retryable JSON `BUSY`; previously slow telemetry readers could occupy every upload worker and the remote client described a plain-text 503 as invalid JSON.
- Native read diagnostics preserve null-message failures, explain unavailable Arrow schemas, distinguish lost disk boundaries from live recordings, and report absent REV alignment as null. Previously these edges lost their reason or implied facts that were not measured; the Python example now retains batch decode problems too.
- Arrow query schemas identify their retained snapshot instead of repeating its full descriptor, file-order window lookup uses the shared binary boundary rules, and remote REV calls reuse HTTP resources. Previously large catalogs inflated every stream, many windows repeated linear searches and each REV call created another client and timer.
- On Windows, the search for a VS Code installed apart from WPILib reads a quoted PATH entry as the folder inside the quotes, and passes over an entry no file system can name. In 0.10.0-dev3 such an entry made every `--with-extension` install fail as a usage error naming an illegal character, even when WPILib's editor came first; now only `--vscode system` reads PATH.

- The installers find WPILib's VS Code in every season's folder without PATH, including the app bundle on a Mac, and offer the extension only to an editor whose version it accepts: one gets a yes-or-no question, several a menu with the newest season first and the default, and none no question at all. 0.10.0-dev3 looked only at the calendar year's `vscode/bin/code`, then at `code` on PATH: it found no WPILib editor on a Mac, or last season's each January; it would install into an editor too old for the extension or into a VS Code installed apart from WPILib; and it asked about the extension before looking, so a yes with no editor installed the server and then failed. An explicit `--with-extension` now fails before anything is written when no editor accepts the extension, and another VS Code is used only with `--vscode system`.

- Native NDJSON queries split wide decoded batches to fit the wire budget, preserving all samples and exact byte/work totals. Previously default queries for ordinary wide arrays returned 413 even though local reads and Arrow succeeded.
- Tolerant discovery is confined to read-only store catalogs. Previously its new handling of invalid robot, pull, import and system-index manifests also weakened the existing inventories used by store writers.
- Native snapshot opens no longer start REV correlation or write its cache. Previously they inherited that side effect from legacy tool admission; later legacy acquisition of the same cached file still starts its normal synchronization.
- Active read requests prevent snapshot idle expiry, with the idle clock restarting at completion. Previously a slow stream could finish successfully but lose its fixed-prefix lease before the next query.
- Native REV reads retain bounded primitive chunks instead of millions of boxed samples. Previously a four-million-sample ceiling refused long practice logs that fit within the same 512 MiB budget.
- The standalone reader relocates bundled dependencies and includes WPILib's redistribution notice. Previously it could shadow an application's WPILib or JSON classes, exposed a split package, and omitted the notice.
- Shared read requests reuse manifest inventories, immutable REV decodes and chronological permutations for the same file version. Previously each request rebuilt the catalog and REV values, and separate snapshots repeated the same sort; file changes still invalidate the shared work.
- HTTP data, native-read and store streams now use bounded workers separate from health and registration, with a ten-second IO-progress deadline. Previously 32 peers that stopped reading exhausted dispatch indefinitely and made health fail; excess streams now receive 503 and stalled reads release their resources.
- Native REV reads now bound input size, decoded expansion and retained reads before allocating their eager decode. Previously simultaneous queries could each retain an unbounded expansion of the same file.
- Shared-reader materialization admits whole ranges to the storage backend and frames each record once, retaining WPILib's value decoders. The refactor had added per-record query dispatch; duplicate framing and buffer views also made mixed-entry and mapped live reads slower despite faster indexing.
- Sync cache files above 64 MiB are refused before reading and removed at startup; previously a crafted large file could allocate its whole payload despite bounded record counts. Reads also stop at the measured size if a file grows; format version 12 is unchanged.

- Stack exhaustion in a tool, result normalization or request dispatch now returns an explained error and leaves the server answering the next request; previously it could end a stdio session.

- A type-only chassis-speed choice now states in `pose_corrections.odometry.basis` that it may be a setpoint. Previously the result named the type fallback without making that consequence explicit; selection itself is unchanged.
- Release publication uses the annotated tag message's first line as the release title and the rest as its body. Previously the workflow discarded that title and GitHub displayed the tag name instead.
- Both installers refuse a release tag without its leading `v`, naming the offending tag before downloading an asset. Dev2 silently accepted that malformed tag and could fail later with a misleading asset error.
- `export_csv` publishes a complete UTF-8 file from a temporary file in the validated directory, removing the temporary file on failure. Previously a failed write could leave a partial target, and the UTF-8 encoding was not stated.
- Loop timing uses the robot's original `LoggedRobot/FullCycleMS` and `UserCodeMS`, including `/RealOutputs`, without ambiguity from `/ReplayOutputs/LoggedRobot`. The replay-host timings remain candidates for an explicit choice; restoring those paths at equal rank had allowed declaration order to select the host's timing instead of the robot's.
- AdvantageKit replay measured chassis speeds resolve by `/ReplayOutputs/SwerveChassisSpeeds/Measured` when no original convention is present, with the replay basis stated. Dev2 had left this path as a candidate; its restoration initially tied `/RealOutputs` and could choose replay by declaration order. Original robot values now win without that ambiguity.
- AdvantageKit replay setpoint chassis speeds resolve by `/ReplayOutputs/SwerveChassisSpeeds/Setpoints` when no original convention is present, with the replay basis stated. Dev2 had left this path as a candidate; its restoration initially tied `/RealOutputs` and could choose replay by declaration order. Original robot values now win without that ambiguity.
- YAGSL measured chassis speeds resolve by the published exact path `/SmartDashboard/swerve/advantagescope/measuredChassisSpeeds` again. These logs resolved before dev2, which narrowed the conventions and left this path only as a candidate.
- YAGSL desired chassis speeds resolve by the published exact path `/SmartDashboard/swerve/advantagescope/desiredChassisSpeeds` again. These logs resolved before dev2, which narrowed the conventions and left this path only as a candidate.
- HTTP dispatch starts additional workers when its four core workers are busy, up to its existing limit of 32; previously four stalled data streams or uploads could queue health checks and delay daemon adoption or the service timer despite unused worker capacity.
- Sparse complete samples survive an incomplete final record, and decoded values are stably ordered by timestamp; previously a torn tail could discard a valid sample and an out-of-order record could disappear from scoped statistics.
- Struct decoding refuses schemas deeper than 64 dependencies with an explained error, and synchronization-cache counts are bounded before allocation; crafted inputs previously could overflow the stack or request a VM-sized array.
- Calendar, total-current, loop-time and chassis-speed roles require their published paths or a unique eligible type; unrelated leaves and suggestive words previously could select the wrong signal.
- Enabled-window endpoints remain half-open and voltage crossings stop at each scope boundary; previously a disable sample or excluded gap could enter the result.
- Correlation uses a bounded within-window autocorrelation estimate, lag search caps its size before narrowing, and derivatives account for unequal intervals; previously results could contain negative effective counts, overflow a search allocation, or report a slope at the wrong time.
- Drift explanations convert ns/s to ms/hour correctly and cached explanations are invalidated; previously the displayed rate was 1,000 times too large. Interrupted synchronization waits report cancellation instead of completion.
- CSV export propagates write failures and checks ancestor containment before creating directories; previously a failed write could report success or a refused path could create an outside directory.
- Daemon PID claims are checked against health identity, and an old shutdown preserves a replacement daemon's token; previously stale ownership could stop the wrong process or remove its stop credential. Cache maintenance no longer retains a closed stdio JVM.
- HTTP MCP requests have body-size, body-time and admission bounds, and gateway receive fragments and pending fan-out work are bounded before queuing; previously unfinished or queued input could retain unbounded resources and stalled requests could occupy health handlers.
- Repeated mirror renames retain every prior alias, and oversized store manifests are refused before publication; previously older paths broke or the store could write JSON its reader refused.
- Installer assets are selected by the version's exact published names, and pull requests to development run CI; previously multiple matching assets depended on JSON formatting and development pull requests had no build.
- Explorer entry descriptions use the requesting editor and refresh with its listing; late HTTP 304 replies cannot resurrect evicted cache entries. Previously another editor's metadata or an uncharged stale response could be returned.

- New installs pin the guide link in `servers.yaml` to the installed version's tag; previously it pointed at `main`, which could describe a different version. Existing user configurations are left unchanged, including during refresh.
- Extension upgrade messages now link to the guide at the extension's release tag, including pre-releases, and the gateway guide link is pinned by release packaging; previously both could describe `main` instead of the installed version. Unversioned local builds keep the `main` fallback.
- Named starts and connections refuse a port owned by another configuration, naming its owner and the ways out; previously `start pit` could silently adopt `http` without capture. Health now reports the configuration name, while older nameless servers retain adoption with a versioned warning.
- The shell installer selects the version, JAR and extension from their own JSON fields even when a release response is one line; previously greedy URL extraction could install the source ZIP as a JAR or select a release title as the version.
- Imports into a known robot's anchored sessions now require unique strong data correlation within 250 ms of zero; calendar overlap alone could previously select another boot. The manifest records the proof, or explicitly retains calendar-only placement when no anchor exists, and peer copies preserve it. Existing placements stay unchanged.
- Imported REV companions now apply the puller's 250 ms same-boot gate; previously a strong correlation at a seconds-scale offset could attach another boot's file. Refused companions remain unassigned and readable.
- PhotonVision now refuses unknown message keys beside otherwise valid settings; previously those keys bypassed the stand-down check.
- `robot-facts` now reports an absent executable when `which` returns status 1 with empty output; previously that real shell response was mislabeled as a refusal, while the scripted missing-tool reply used status 127.

- JVM clock notes now report both measurements, uncertainty bounds and start identities without claiming a cause; previously they suggested a restart or skew.
- A failed sample delivery remains stood down across a resume of the same session, instead of retrying a possibly partial write.
- PhotonVision full-state refreshes now coalesce notifications and start at most once per backend per second; previously every selective change could open another socket and fetch a full snapshot.
- Growing files on Windows now resume with creation time as the identity fallback plus saved byte anchors; a null file key previously forced every growth to scan afresh. Growth retirement no longer issues a duplicate reload notification.
- The standalone guide again caps `capture.pull.rate_bytes` at the parser's 2,147,483,647 bytes/second; the windowed-mapping change had incorrectly copied the 1 TiB file-size bound into that row. Capture-table range claims now exercise the configuration parser at their boundaries.
- `render_chart` draws the full window by default, retaining pixel-column extremes in dense time series; previously a default image silently showed only the first 1,000 samples. Explicit limit/offset still pages the drawing and summaries always cover the whole window.
- Chart rendering errors, including missing-font `InternalError`, skip only the image with the error class; previously some toolkit failures lost the complete JSON result.
- Conformance fixtures clear their owned directories before creation; reused Gradle worker numbers previously left unmanifested system text that broke a later build.
- System pull passes reuse a connection's inventory until manifest or directory facts change, and shared syslog receipts live once in the robot's index with written-span selection. Previously each pass reparsed every session and each rotation rewrote historical manifests. Legacy receipts remain readable; the journal's first pass uses `-b` alone instead of relying on boot-UUID argument support.
- **Service ownership:** only `--managed` marks a server as supervisor-owned. Previously an inherited `INVOCATION_ID` also marked ordinary daemons launched by CI runners or terminal shells, preventing start, adoption and stop. Printed units accept exit status 143 as a clean SIGTERM stop on JVMs without the signal handler.
- **Service installation:** POSIX installs create readable JARs (`0644`), readable/executable launchers and traversable program directories (`0755`), repairing their modes on reinstall while preserving configuration permissions. Previously owner-only temporary-file modes survived publication and prevented the service account from reading a root-installed launcher or JAR.
- SSH stats no longer call a never-found program "changed" or scan `/proc` every sample. Missing-program discovery retries every ten samples while other stats continue; an identified process changing still triggers the next-sample lookup.
- Server instructions again state that absent data is not absent problems, a named cause still needs a rival, whole-log statistics mix disabled time and boot, and one log is one sample. Those cautions had been shortened away when live-data guidance was added; all guidance still fits the client limit.
- `find_condition` explains a window with neither true intervals nor newly evaluated samples as `no_match`; a recent window after a false change-only sample previously returned an empty success.
- Calls whose file disappears during opening or reading now explain that it moved or was removed and point to `list_available_logs`; previously this race could return an unexplained internal error during capture identity promotion.
- **NT4 liveness:** keepalives now expire only an unanswered ping, pong receipt and replies run off the application loops, and periodic capture fsync runs on its own thread. Previously a one-second stall of either loop could drop a healthy connection and split a session; close and rollover still wait for pending disk forces.
- **Socket delivery:** a queued gateway reply no longer waits indefinitely when Java-WebSocket loses its write notification. The existing aliveness tick restores selector interest without resending data or adding 4.0 pings. A CPU-loaded run reproduced the fixture client's announcement stall; both server adapters now pass a planted lost-notification regression with unchanged timeouts.
- **Gateway startup:** a busy port no longer stops capture and pulling or produces a misleading shutdown-timeout warning. Address reuse and perpetual backoff from 1 to 30 seconds let it recover; health and live sessions show waiting/listening state, cause and time, and only state changes are logged.
- **Capture durability:** abandoned recordings are recovered with hash, size, scanned range, file-time ending and reason. Recovery runs after HTTP opens, shutdown is bounded, unreadable files remain explained, and a corrupt historical manifest is skipped and logged during live inventory. Previously an open manifest could remain forever or prevent capture from starting after restart.
- **Capture size and write failures:** recordings roll to numbered files before the mapping limit, with declarations and rollover-time schema copies in each. An I/O failure ends recording with its reason while keeping NT4 connected until a new robot clock. Previously large files or disk failures could cause repeated reconnects without recording.
- **Capture queue independence:** manifest snapshots coalesce, unchanged facts do not write, and progress writes run at most every five seconds. Hot-value expiry runs on the 250 ms flush tick. Event renames and serial promotion wait until session close, retaining aliases for matched imports. Previously imports, mapping work and directory moves could stall NT4 or invalidate paths.
- **Capture metadata:** topic properties and their updates survive in the file and live/fresh indexes, separate from recorder provenance. A held latest value retains its authoritative type across redeclaration, and `NT:systemTime` retains its calendar role; previously these could be lost or paired with stale metadata.
- **Capture discovery:** session ordering uses calendar instants instead of ISO-string order; imports can join an overlapping open capture, and open captures have no invented final hash. Matched WPILOG files are no longer classified as REV companions. Address placeholders say `address`, not `stated`.
- **Reconnects:** the NT4 client tries its last successful address first, avoiding a repeated failed mDNS lookup after every robot reboot.
- **Store paths:** a configured store under a directory alias can start before it exists, and Windows manifest replacement retries transient access denials while keeping atomic writes and the previous complete manifest. Previously alias validation or one access denial could stop capture creation; persistent failures still explain their cause.
- **Filename encoding:** uploads explain an unrepresentable filename, name the native encoding and recommend a UTF-8 locale. Store startup warns once when that locale is missing; previously it returned Java path errors.
- **Import safety:** imports refuse moves of another store's managed payload, symlinked lock files and dangling links. Unassigned payloads live under `robot/`, with control-name collisions disambiguated and old layouts migrated with provenance. Previously these cases could break another catalog or overwrite a manifest or outside file.
- **Import concurrency:** moves evict mappings and wait for active readers, so an already-viewed log can move without a restart. Inbox cleanup joins the store queue; polling reuses known stores, and hidden locked staging hides partial copies. Previously mappings blocked Windows moves and inbox work could race an admitted import.
- **Catalog performance:** listings again read only the 2,000-record metadata prefix; robot candidates use import-recorded fingerprints. Import batches read their catalog once and nominate correlation candidates by robot/time before loading. Previously first listings and batches could repeatedly scan whole seasons of unrelated logs.
- **Peer sync:** SSH contacts stay with the store that made them, unreadable store headers are reported individually, and peer captures occupy hash directories within joined sessions. Previously contacts leaked across stores, one header broke the whole door, or a peer filename could block the local writer's next rollover.
- **Store downloads:** a manifested file named `prefix-hash` is downloadable; only the `bytes` query selects the hash operation. Previously the filename was mistaken for the endpoint.
- **Pull efficiency:** one remote listing serves a transfer pass, refreshed after ten seconds, and length-scaled SSH hash deadlines use keepalives. Session time overlap filters candidates before correlation. Previously every block caused another recursive listing, large hashes repeatedly timed out, and matching loaded unrelated sessions on the store queue.
- **Pull matching:** AdvantageKit's `/AdvantageKit` publication prefix is recognized, and refused placement retains a reason in the manifest and listing. Previously valid names could fail nomination and refusals lost their explanation.
- **SSH host keys:** a changed key refuses password/key authentication until explicitly accepted; empty-password contacts may continue. Previously a changed host could receive configured credentials before its identity was accepted.
- **REV alignment:** corrupt recorded evidence names its manifest and leaves the companion unsynchronized, instead of returning Internal error. Bus-name inference handles Windows paths and locale-independent case. Older sync caches are invalidated so corrected decisions reach existing users.
- **Data streams:** Arrow and CSV disclose undecodable records, the explorer displays the warning, unknown enum numbers keep a null/empty label, and REV ETags include file and alignment changes. Previously partial plots looked complete, unknown enums could abort a stream, or the viewer reused stale timestamps.
- **Server installation:** destination containment checks refuse escaped symlinks, and installer JSON checks keep JVM stderr banners separate; previously installation could escape its destination or a JVM banner could be mistaken for invalid installer output.
- **Health checks:** `health_check` recognizes an in-memory registered TBA key; previously it could report a missing key despite a client's active registration.
- **File-size refusal:** WPILOGs beyond the supported mapping bound remain untouched on import and appear with the same explained refusal in plain-directory listings, instead of being omitted or partially placed. Windowed mapping raises that bound to 1 TiB.
- **Windows paths without a drive:** with several log folders configured, a path such as `\logs\match.wpilog` now reads that file on the current drive, as any Windows program would. Before, it was refused as relative ("A relative file path requires an explicit source scope"), although a path with a root cannot be relative to one of the folders; on Windows, 408 tests that name mock logs this way failed. Nothing changes on macOS or Linux, where every such path is absolute.

### Security

- **Proxied requests are no longer local:** a request carrying `Forwarded`, `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Real-IP` or `Via` is refused by every loopback-only route and is untrusted for MCP, even when it arrives over loopback. Previously a reverse proxy on the same machine, such as the pit guide's nginx example, made everyone who passed its login a local caller: they could lease any directory to their session, register a pit endpoint and forward requests through `/pit-mcp`, import by server path, drive store sync, mirror and local-ingestion controls, and use the visualization routes; `/stop` was left guarded only by its token. The documented nginx block now sets `X-Forwarded-For`; an existing proxy needs that line, because one that adds none of these headers still cannot be told from a local client.
- CI now defaults to `contents: read`; only dependency-submission jobs can write the dependency graph. Previously build jobs inherited the repository token defaults. This restriction concerns the build, not permissions in the server or extension.

### Testing

- A same-boot reconnect after a published close is checked on a format-2 store: the published
  file keeps its bytes and stays readable while the next file records, both files are published,
  and nothing is left pending; on the previous build it fails at the reconnect's file. Four planted
  mistakes each fail it.
- A format-2 mirror is checked to copy an import and a capture's clock record byte for byte and
  never the capture, and the peer test of a growing offer now offers an import, since a capture is
  skipped before that check. A planted mistake that copies captures again fails it.
- `capture=current` over a session of two files is checked against rows worked out from fixture
  files whose clocks the tests set: the value a reconnect repeats placed by the closed file's
  clock, buckets across both files placed by the clock recording at each bucket's start, a window
  in seconds, a peer's capture of the same boot left out, a file the manifest still names open, an
  unreadable file listed, a file without a clock counted unplaced, an angle unwrapped once across
  the reconnect, and the file limit. Sixteen planted mistakes each fail them.
- REV signals in a robot range are checked on a session whose recording's clock reads half a
  second later than its roboRIO log's, so placing a REV log through either gives a different
  answer: through the roboRIO log by default and through the recording alone with
  `source=capture`, each row worked out from the fixture; one signal decoded, counted, and no
  synchronization started; a REV log without an alignment, or without the signal, listed; two REV
  logs over one signal refused and two buses' logs both answering. Ten planted mistakes each fail
  them.
- One recording's files in a range are checked with a reconnect's file whose clock reads a quarter
  second later, so the two placement rules give different times; two recordings that peer sync
  filed in one session, of two boots, each placed by its own clock; and a peer's recording of the
  same boot refused. Five planted mistakes each fail them, the previous rule and the previous
  exemption among them.
- CI's metrics check reads the history dashboard on a store of logs whose clocks the check sets:
  the server restarts on a roboRIO log and a recording of the same minutes, written by the check
  from the format's specification, and the dashboard's robot choice, sessions table and a panel's
  rows and buckets, each through Grafana and Infinity, are checked against rows worked out from
  the store alone. Before, the check ran against a server without a store, so it saw only the
  history panels' refusal. Two planted server mistakes and a planted dashboard mistake each fail
  it.
- A catalog read is checked with a session folder renamed at chosen points inside the read,
  through the read's own hooks: while another session is read, after it was read, as its own
  manifest opens, and on every attempt. Each test fails on the previous code, and six planted
  mistakes each fail them.
- The recorder's buffer is checked against an output that writes each record as it comes, byte
  for byte and offset for offset, with buffers smaller than one record and larger than the whole
  file, through a write that fails halfway, a record larger than the buffer, a resumed file and a
  close whose last write fails. With a slow disk's force held open, a value stays in the index
  until its record is in the file, and the size the store publishes never counts the buffer; a
  write that fails at a tick or at close leaves no index answering for the records the file lost,
  and no finish record naming an entry whose start was lost. The server's recorder is checked over
  a real NT4 connection to write its records at the flush tick, and the store to decide a match
  number's role again when its entry comes back with another type. Twenty planted mistakes each
  fail them. The tests' capture record helper now reads to the end of a file: WPILib's iterator
  ends a for-each loop once fewer than 16 bytes remain, so it skipped an integer value at the end
  of a file without finish records.
- The live dashboard's gate also reports the listener's delays while its prefill streams at ten
  times the soak's rate. With the buffer, the longest was 112 ms in one run and 7 ms in another,
  and none reached a second; writing each record as it came, the same prefill had waited over a
  second four times in twelve minutes. Of those two runs, on the maintainer's computer under a
  load average of 8.5, the first failed one bound, one of its five refreshes at two minutes taking
  260 ms against 250 ms while the others took 16 ms, and the second passed every bound.
- The live dashboard's gate, an opt-in run of the soak test: the pit server's capture service
  records a synthetic robot for two hours, then for ten minutes in real time while twelve panels
  refresh every five seconds through the real HTTP transport by measured UTC. On the maintainer's
  computer, refreshes at two hours took 18 ms at the median and 101 ms at worst against a 250 ms
  bound, allocated 36 MB at the median and 44 MB at worst against 50 MB, the listener waited 37 ms
  at most against a second, and the heap after a full collection grew 9.8 MB against 64 MB. With
  the recording's column reads turned off, as before the recording phase, refreshes pass at two
  minutes and allocate about 870 MB each at two hours, so the gate fails. CI's metrics check
  installs the pinned Infinity, provisions the recorded dashboard, and sends a panel's own query
  through Grafana, which the server refuses for want of a capture.
- The CSV rows the data endpoint writes are pinned character for character against hand-worked
  text, with empty cells for a bucket of no finite sample and for a time before the clock's first
  reading, and the long arithmetic that maps each row's UTC against the exact path on random
  clocks, ties on half a microsecond and products past a long included. Every fixture's data
  responses were compared byte for byte with the previous build. Six planted mistakes each fail
  them.
- A recording's struct fields are checked against a decode of the same prefix, for every leaf and
  pooled path at sampled boundaries, through malformed records, arrays of changing length, values
  before their schema, nested schemas recorded late, an array wider than columns take, unsigned
  fields crossing 2^63, a schema whose records exceed the writer's budget, and time running
  backwards, with views read again after later records and schemas; against the numbers written,
  a scan of a copy, and the finished file's column file. Counted, a ten-second UTC window of a
  pose's `x` on a two-hour recording decodes no pose from the WPILOG. Twenty-five planted
  mistakes each fail them.
- The recorder's clock is checked against readings worked out by hand from a moving test clock,
  in files too small to hold a reading beside their last value, against an upstream topic of its
  name, and end to end through a UTC window on a recording and on its finished file; the replay
  audit checks every reading of every replayed capture. Tests that pin a capture's exact entries,
  records or bytes now count the robot's records apart from the recorder's clock, and the
  extension's real-server check of a recording's entries expects the clock beside the robot's.
- UTC windows are checked against rows worked out by hand, at and beside each end, with open
  ends, fractions of a microsecond, offsets, buckets and refusals, and by a differential on every
  fixture with a clock in which each window holds exactly the rows a whole read places in it.
  `capture=current` is checked across two recordings and a closed one; a recording's UTC mapping
  against a fresh read of each prefix, through a clock set, a clock stepped an hour and an
  ambiguous clock; and, counted, a ten-second window on a two-hour recording. The clock rule
  that keeps valid readings is checked against its previous form on every prefix of random
  clocks. Twenty-two planted mistakes each fail them. The data endpoint's ETag test now moves a
  copy's modification time, not the shared match fixture's: a later test class that Gradle ran in
  the same JVM was told the fixture had been reloaded and failed.
- The open capture's columns are read back from a file and from the heap in every order, by a
  reader that starts at the latest record too, and compared at sampled publication boundaries
  with an independent read of the same WPILOG prefix: every record's position, time, size and
  value, and a number's samples in time order, with the hot window on and off and with entries
  logged out of order, off-type and malformed. Counted rather than timed, ten million recorded
  numbers leave the heap within 16 MB of where it started, a ten-second window on two hours of
  an entry decodes at most 40 of its 703 blocks, and the list of off-type records is copied a
  few times a session, not once a record. Further tests pin a leftover file that is never read,
  a file deleted under its writer, a short reserve and a failed write, the startup sweep and
  eviction, the file's lifetime across retirement and discard, and `index_in_heap`. Nineteen
  planted mistakes each fail them.
- With a column file built, the conformance sweep calls every tool on every fixture again and
  requires the same result; the five presentation routes answer their 39 cases against the same
  oracle; the signal resolver and the data endpoint's series give every held path the decode's
  samples, value types, value bits and decode problems; and native reads match the log's batch by
  batch, record facts and problems included, in sixteen ways on an edge log and on every fixture.
  A test pins which reads a file serves and which it leaves to the log, and another that a held
  file closes with its log's mapping. Thirty-four planted mistakes each fail them. New tests also
  pin a rate's first and last points, infinite samples in `find_condition` and
  `detect_anomalies`, an angle held as logged in `align_entries`, when `compare_matches`'
  extremes occurred, and the default lag step; previously a mistake in any of these passed every
  test.

- Every reduction now fills its buckets through one shared rule, which computes a sample's bucket
  only when the sample leaves the current one; it is checked against computing every sample's
  bucket afresh, in and out of time order, and a native reduced bucket's first contributing
  sample, which the read API documents, is now tested. Previously `read_entry`, charts and the
  data endpoint shared one bucket loop and native reads repeated it, and no test checked which
  sample a reduced bucket named. Answers are unchanged.

- The daemon, installer and offline import tests take the ports they name from a slice below every
  system's ephemeral range, one slice to each Gradle fork, each port checked free and handed out
  once. Before, each test asked the system for a free port, closed it, and then had a daemon bind
  it or expected it to stay empty, so a test in another fork could be handed the same port in
  between, and full builds failed finding the port taken.

- The store copy admission tests now grant their own folder and restore the log manager's grants
  afterwards. Previously the upload test relied on an empty grant list, which admits every path,
  so its import was refused ("outside configured log directories") whenever a test that uses the
  shared fixture folder ran before it in the same JVM; adding a test class to the store package
  moved it behind one. Run after such a test in one JVM, it failed before the change and passes
  after it.
- The capture writer's publication timing test now grants its own folder and restores the log
  manager's grants afterwards. Previously it relied on an empty grant list, which admits every
  path, so it failed whenever a test that uses the shared fixture folder ran before it in the
  same JVM; adding test classes moved it behind one.

- Scratch under `build/` now lasts until the next run of the same suite: a passing replay deletes
  its run folder, a failed one keeps it for diagnosis, and each test task empties the folders its
  suites write before it starts. Previously the pull, pair and native replays never deleted a run,
  the gateway replay left its fake roboRIO beside each run, fixture folders piled up with every
  ordinary test run because worker numbers keep rising for the life of the Gradle daemon, the
  native editor runners kept every run, and reports named after temporary folders were never
  replaced; one checkout's `build/` passed 500 GiB. A claim check now fails when a test, a native
  editor runner or the build script names a folder under `build/` that nothing empties.

- The unreleased synchronization-cache checks now require a dated compatibility decision
  when reviewed parsing, decoding, clock or synchronization inputs change; previously the
  cache-format rule depended only on manual review. Source changes, missing explanations
  and false format bumps are checked separately.

- The unreleased capture verification now includes an opt-in ten-minute synthetic
  recording/import/HTTP soak with exact sample checks and stated listener, store-action, force
  and sampled-heap bounds; previously the shorter live replay checks did not measure sustained
  shared-process pressure. The full synthetic run recorded and checked all 3,000,000 values.
- The unreleased build now refuses packaged native libraries in the application runtime
  classpath and distribution, while keeping native test oracles separate; previously no build
  check enforced this boundary for the recording process.
- The unreleased decision index now has a claim check for dates, statuses and local heading
  links; previously no index exposed superseded decisions or detected broken record links.
- The daemon tests' fake daemon now closes its port, and checks that nothing accepts a
  connection, before it reports its process gone, as a real exit does. Previously a restart
  could see the old process dead while its port still answered, refuse to launch, and time out
  the concurrent restart test; a latch-held regression reproduces the old order.
- The store-lock symbolic-link admission test now probes link support once in an empty
  directory and fails when its own link cannot be made. Previously any I/O error turned it into
  a skip, including a lock file that already existed.
- The test task now declares the documents, workflows, installers and build files that the
  claim and documentation tests read as its inputs. Previously a repeated test command after an
  edit to only those files was answered "up to date" and reported success without running them.
- Every relative link in the committed guides is now checked for its file and, into a Markdown
  document, its heading. Previously only the operations manuals and the decision index were
  checked, so two links to a renamed visualization-plan heading and a line-number anchor into
  the standalone guide broke unnoticed; they now point at their headings. The visualization plan
  names an uncommitted review note instead of linking it.
- The gateway bind-address test now holds the loopback endpoint at its port while the gateway
  starts and releases it just before probing that nothing listens there. Previously another
  test process could listen on that loopback port and answer the probe, failing the test
  intermittently; a test-planted listener reproduces that failure on the old test.

- Server-library regressions cover quota accounting, migration, source revocation, interrupted
  checkpoints and shared HTTP/MCP pressure. The extension's real-server client test now pins
  and checks the current project version; filename sorting previously selected an older JAR
  when several builds were present, hiding stale expectations about combined source listings.

- Explicit-source regressions cover ambiguous discovery, equal-content master/mirror copies,
  selected directory reads, grant revocation on cached inventory pages, shared hash reuse,
  qualified-view recovery and API-version refusal. Isolated mutation checks exercise those
  boundaries without changing the reviewed working tree.

- The conformance sweep checks the page limits nested in results (a chart series' points, a session's costs), not only those at the top; a paged inventory's caps, also named `limits`, are told apart by their shape. A source check fails on a case conversion that names no locale. The timeline's brownout events are built from the crossings `power_analysis` and `predict_battery_health` count, one hysteresis rule instead of two, and pinned by events worked out by hand.
- Native visualization lifecycle checks now wait for renderer acknowledgement and assert the Undo/Redo callback and server state; the earlier fixture raced editor switches and could report intermittent Undo timeouts. Callback traces and repeated switches make the target observable.

- The provider-reason documentation check now reads paragraphs by lines and exercises both LF and CRLF. Previously regex backtracking treated one Windows line ending as a blank paragraph and hid the documented sampling-budget explanation.
- The pulled-file replacement regression now mutates after cached readers are released and asserts the exact changed-after-verification refusal, retained stage and unchanged progress. Previously Windows refused the fixture's truncation of a mapped file before the intended placement guard could run.
- The shop harness now distinguishes verified downloads from a vanished DataLogManager name whose first open failed before any bytes were copied. Previously that harmless empty attempt could fail the exact manifest count after both robot logs had verified; the shared check still rejects duplicate, partial, failed, nonempty or still-current unfinished transfers and compares completed pulls with the final remote inventory.
- Session reads now pass through a real loopback recorder, rollover, peer sync and mirror, with every timestamp and value checked independently. Earlier session tests assembled their manifests directly and did not cover that complete path.
- Shared-reader regressions cover projected and reduced values, multi-entry completion, session merge clocks and bounds, selective REV retention, append following and a standalone consumer's public types. Failing-first checks and restored fault plants guard the new behavior, alongside frozen legacy responses and independent real-file comparisons.
- Editor lookup tests build the Linux, Windows and Mac app-bundle layouts on every platform with fixed seasons, and drive both installer scripts through a three-season menu with one season too old, a named season, `--vscode system`, a home without WPILib and a refused explicit install that leaves nothing behind. Previously the one fake editor sat in the calendar year's folder, the only place 0.10.0-dev3 looked. The extension's `engines.vscode` reaches the JAR through the generated version class, and a test compares the two.

- Frozen-build read comparisons now exercise identical generated inputs and requests through MCP and Arrow/CSV, preserving exact numbers, warnings, ordering and metadata; previously conformance compared entry orders only within one implementation. The comparator's fault plants check that changed answers and bytes cannot pass as equivalent.
- The PhotonVision harness proves a fresh settings save and completed web-server restart even when that restart closes the POST response before its headers arrive. Previously the expected restart could fail setup with HTTP EOF; missing save evidence now reports the backend log tail.
- A real data-endpoint regression holds four response streams on a latch and checks health before releasing them; previously admission tests covered stalled MCP bodies but missed starvation by other HTTP handlers.
- The operations manual's tool names, CLI verbs, YAML examples, provider names and states, metrics and relative links are checked against the code and document headings; previously no test read the manual, so stale or misspelled claims could pass the build.
- Legacy unassigned-layout migration now checks the entire file receipt apart from its changed path; previously matching reasons, placement methods and robot fingerprints could be lost without failing the migration test.
- The PhotonVision stand-down check now pins a late snapshot notification and an abrupt backend close with no outstanding message demand, advancing its manual keepalive clock to detect the latter. Previously a stale notice could satisfy the malformed-message wait early, while a blocking wait froze the only reliable detection of a vanished backend and failed under load.
- Generated import checks now distinguish overlapping boots, open and recorded anchors, calendar-only fallback and both sides of the REV matching boundary, with planted failures. They also pin a capture's logged serial over its stated directory, placement receipts through identity promotion, and the live listing's calendar-only method and reason; those branches previously lacked regression checks. Store and mirror conformance retain same-clock companions; the real-log replay sample checks the matching path against independently read source files.
- PhotonVision contract checks now assert the synchronous stand-down state after a repeated session call; previously the export-count assertion could pass before an erroneous retry's worker ran.
- The shop harness now runs the SHA-256-pinned PhotonVision v2026.3.4 backend with a generated file camera against the packaged pit server; previously only synthetic backend replies exercised its routes and captured settings. Both runner commands now select available backends and keep the MINA timeline on macOS with explicit skip messages.
- CI preloads the documented metrics images at their unchanged versions from public registries before running Compose; repeated Docker Hub throttling and token timeouts had stopped the smoke check before it tested the stack.

- A synthetic NI-like Docker harness now runs the simulation through real OpenSSH, Linux utilities and a JMX-enabled JRE, checking provider entries, uptime bounds, pulled system text and the shipped facts collector. Previously scripted SSH replies could not prove those command/runtime integrations. The existing timeline oracle runs alongside it; actual NI image permissions and robot cost remain shop checks.

- Mock-log tool tests now use explicit admission scopes and pressure-pinned cache entries; previously a cache pressure sweep could unload their nonexistent files and fail unrelated tool assertions. An always-full heap plant reproduces the failures and checks scope cleanup.

- JVM tests now pin the NT4 contribution to the clock bound, suppress notes for within-bound changes, and exercise a failing delivery and recovery in a new session; those cases previously lacked direct assertions.
- Fixture disconnects now wait for peer closure and ordered removal; previously their future only acknowledged an abrupt local close, leaving reconnect checks dependent on later TCP delivery. A held close reply pins the barrier without longer deadlines.
- The reconnect backoff test now waits for retry scheduling after the disconnect callback; under load it previously counted delays too early. Removing the reset still fails its count assertion.
- In-process JMX/GC tests and synthetic JVM fixtures check receipt clocks, cumulative counters, jump notes, refusal backoff, session admission and live/manifest/metrics/differential agreement. Runtime module probes are checked against complete and sparse synthetic SSH images; previously these paths were untested.
- Windows resume assertions now require the resumed offset on every platform. Synthetic PhotonVision HTTP/WebSocket tests pin version, route shapes, clock mapping, change delivery, stand-down, metrics and exact-camera tool context, with planted faults; previously these provider paths had no coverage.
- Synthetic tool tests explicitly admit their mock log directories and restore the prior roots afterwards. Previously they depended on the cached-path exception, so a worker retaining lease admission could reject the same mock inputs under a different fork/class order.
- Incremental scans are checked against fresh scans and independent records, with changed-prefix, partial-tail, late-entry and stale-cache plants. Scripted SSH image reports check read-only commands, absent/refused evidence, hash sizes and credential redaction; no robot files or text are fixtures.
- The metrics-stack check waits for its Grafana datasource query within the existing readiness bound; previously a plugin update just after dashboard provisioning could produce a transient 404 and fail CI.
- Chart regressions pin unequal-time scatter pairs, every encoded link entry, clipped phases and data-view null cells. Socket-delivery test guards allow thirty seconds under load without changing protocol deadlines.
- Small mapping windows exercise every fixture record against the independent reader, plus conformance, cold values and import moves. Windows CI proves the leaked-mapping plant fails; the opt-in `largeLogTest` exercises a generated 2.2 GB file and rollover beyond 2 GiB.
- Peer system-text copying now faces an advertised-hash mismatch with same-length bytes on the peer path, checking refused placement, absent receipts and retry state. Previously a size-only verifier passed because corruption tests reached only the mirror verifier.
- Chart checks decode PNGs, compare window summaries with an independent reader, and plant size, missing-series, scope, hold-style and guessed-phase faults. Adapter/notebook/URI plants cover replaced follow rows, omitted inputs and bypassed server admission; the two real-editor CI jobs also check Perspective row counts and an appended batch. Each smoke run owns its appendable fixture, and the runner requires all six checks; the original five-check gate incorrectly rejected the successful new data-view check. Previously these chart, grid and notebook surfaces had no coverage.
- Synthetic stores now count inventory reads across 300 sessions, check five rotations shared by fifty sessions, and exercise system-text ranges, hashes, peer/mirror copies, missing-copy guidance and growing-prefix resume. Corrupt mirrored bytes and omitted shared-file capacity are refused by regressions; previously transfer tests covered telemetry alone.
- Synthetic system-log fixtures exercise real SSH gate/pacing, rotations, cursor restart and command refusal, PID placement, committed-prefix reads and independent clock mapping. Previously these paths had no tests; the tool joins schema conformance, claims and opt-in stress calls without requiring a robot's files.
- The systemd check tests launcher/JAR access as the service user before startup, reports modes on refusal, and supplies the hosted JDK through `JAVA_HOME`; previously it waited for health while the process could not start. The unmanaged plant removes only the flag. Real-child ownership and POSIX-mode regressions cover both failures, and Main/installer changes select both editor smoke jobs without unrelated capture replay.
- A separate Linux systemd job exercises the printed service and timer, managed/version health, the stop refusal and bounded shutdown, with an unmanaged-service plant and uploaded journals. Previously no test ran the pit server under its supervisor.
- The shop harness runs beside ordinary builds again and no longer downloads their already-uploaded XML. MCP transport changes also select the extension's real-JAR checks; an unreachable before-commit runs all checks instead of failing the workflow. Native replay is selected only for its affected surfaces.
- Ordinary tests use bounded parallel forks and private fixture paths, explicit clocks/conditions instead of sleeps, and shared conformance views instead of rescanning for each tool. Coverage is opt-in and Linux CI runs it once; harness/Arrow jobs reuse ordinary build artifacts. DEVELOPMENT records which optional checks a change warrants, replacing repeated full-suite and native shift-matrix work.
- The default-cache check now tests the OS path without creating it, instead of mistaking Gradle's cache override for the default; previously it depended on the checkout directory containing the application name.
- Synthetic MINA SSH scripts now check stats units, rates and clock mapping, adaptive budgets, tail limits, missing sources, rotations and reconnects, with provider conformance/differential checks. Previously SSH tests covered identity and file transfer only. An outside-loop watchdog test pins stall reporting independently of the stalled loop.
- Injected-clock regressions cover 1.5-second gateway/listener/disk stalls, the unchanged one-second unanswered-ping deadline, network-thread pong replies, bounded client receive work, and close/rollover force barriers. Capture and replay failures now name disconnects instead of missing topics or stalled placement; previously these paths obscured the two preserved Windows failures.
- Release headings are checked for uniqueness, preventing unreleased work from being hidden under a duplicated published version.
- Gateway bind checks now reuse a port left in server-side TIME_WAIT; the earlier option getter could pass when reuse was enabled only after binding. Listener failures also pin the reset backoff, and shutdown failures must report their cause without claiming that the deadline expired.
- Real-log checks now share a deterministic stratified sample by default, with runtime path/coverage reports and `conformanceSample=full` for release checks. Previously gateway and live replay read the full season on every milestone while native replay used a separate logger-only sample; all suites now honor the file limit, and full runs retain the clock-shift matrix on small logger/REV representatives. Native replay now bounds missing receipt progress instead of stopping a healthy large file at five minutes. Publisher shutdown precedes offline verification, so slow transfers cannot expire its control handshake.
- **Real VS Code:** a separate Linux CI smoke runs the oldest supported editor and current stable, checking activation, shared-server startup, a generated log in the Logs tree, custom editor opening, and secret-free pit registration arguments. These paths were previously covered only outside the editor; plot rendering and interactive flows retain their manual checks. The test runner is development-only and excluded from the VSIX.
- The headless WPILib 2026 shop harness drives scripted boots and match phases through real NT4, SSH/SFTP, HTTP MCP and a downstream ntcore gateway client. Previously this complete path required a robot. Ordinary Linux/Windows tests use test-only Apache MINA SSHD; NI-image permissions and performance still require the shop test.
- Replay checks every captured record through the independent WPILOG reader and native ntcore, with opt-in season directories through `conformanceLogDir` and count-only reports under `build/`. Generated fixtures run on every push. Replay readiness is per connection, already-drained callbacks are accepted, and decoded REV companions are released after comparison; previously these verifier issues could hang or exhaust the heap.
- Blocked-store checks separate queue independence from real disk throughput, and failure/reconnect tests preserve callback traces and ordinary-test XML. Previously transient full-suite socket failures lost their evidence; unresolved occurrences remain documented without widened timeouts.

### Documentation

- `doc/GRAFANA_PLAN.md` records what stage 1 has built and the maintainer's two answers: every
  capture records its server's clock as an entry, now built, and struct fields on a recording
  become columns as the writer records them, which reverses the recording phase's first answer,
  which the maintainer confirmed in words, and which is now built. `doc/COLUMN_CACHE_PLAN.md`
  records how, and what it costs the writer. `doc/ARCHITECTURE_HARDENING_PLAN.md` item 6 notes the
  maintainer's relaxation for captures and what the data endpoint reads today.
- `doc/COLUMN_CACHE_PLAN.md` proposes the recording phase's design: the open capture's columns
  written as it records, to a scratch file in the disk cache that is never forced to disk, so
  the open file's index no longer grows the heap. A throwaway prototype on synthetic values at
  the soak's rate measured 0.24 bytes of heap kept per value against 52 for today's index, 31 ns
  a value on the writer's thread against 60 to 75, 13 bytes a value on disk, and twelve
  five-minute panels in 2.1 ms against 122 to 153 ms after 30 minutes. The maintainer answered
  its four questions as proposed: struct fields decode their window while recording, blocks are
  packed, blocks stay in the heap when the disk cannot take them, and numbers leave the hot
  window. Previously the plan described the phase in one paragraph, with nothing measured.

- `doc/COLUMN_CACHE_PLAN.md` proposes column files: each log's numeric entries kept as
  columns beside it in the disk cache, read by memory-mapping with no engine. A throwaway prototype on synthetic logs answered one entry across a season's
  128 logs in about 13 ms against 1.45 s for today's path after a restart, and needed 16.6
  bytes of heap per value for an open file's columns against today's 82. The maintainer's
  decisions are recorded: struct fields in the first phase, building in the background, the
  disk cache's shared limit, and the recording phase before Grafana's open-capture stage, with
  no smaller rollover size in the meantime. On three local team logs, read-only, every numeric
  value matched today's decode, and a column file answered an overview in under a millisecond
  after a restart, against 0.1 to 0.9 s. Previously the plans weighed only summaries and an
  engine-backed columnar store. The plan also records the first build of the format, its writer
  and its reader: an overview reads every sample, since block
  summaries would change the endpoint's means in their last bits; a struct array of more than
  64 elements is left to the log; and a build decodes each entry outside the cache of decoded
  values, after the first build ran a 4 GB heap out of memory on a 2 GiB team log. The
  maintainer kept both: no summaries for now, and the build's limits as built.

- `doc/GRAFANA_PLAN.md` proposes the design of its first stage, live panels on the open capture,
  with synthetic measurements and six questions for the maintainer. Previously the plan noted
  that a live panel's cost on a recording in progress was not measured. The measurements show a
  refresh's work growing with the session, and the open file's index holding about 82 bytes of
  heap per recorded value until the file closes. The maintainer answered the six questions:
  panels name the recording with `capture=current`; a live window reads only the open file and is
  placed by the robot's wall clock when the robot publishes one, and by the pit server's
  otherwise; the gate runs on two hours of prefill with a 250 ms and 50 MB bound per refresh; and
  the recorded panels get a dashboard of their own.

- The hardening, source and mirror, pit server and ideas plans now state what has been built.
  Previously the hardening plan still called the multi-session tool unbuilt after
  `compare_sessions` landed and named a Grafana `json` format that the Grafana decisions
  dropped, the source and mirror plan placed M5–M7 in the working tree after their commit, the
  ideas page said nothing in the hardening plan was implemented and that M3 onward was pending,
  and the pit plan's round 24 audit called the store migration unbuilt after M4 added it.

- `doc/GRAFANA_PLAN.md` plans plotting stored sessions at full resolution in the Grafana that
  shows the live metrics, with the maintainer's decisions: Grafana's Infinity data source reads
  the data endpoint's CSV with measured UTC, Grafana's time range chooses the window, a
  dashboard starts from a robot and a time range with a source picker for overlapping
  recordings, and live panels on the open capture come first. It records rounded local
  measurements and the costs that grow with a log rather than with the view. Previously the
  pit plan deferred history in Grafana to an unspecified `json` format until a need showed.
  `doc/COLUMNAR_STORE_PLAN.md` now rereads each reason for its rejection under a
  server-install-only scope; summaries and a DuckDB-queried columnar store are measured against
  2 s for an event and 10 s for a season before either is chosen, and the rejection stands
  until then.
- The hardening plan and the decision index record the maintainer's choice for the persistent
  scan index: it stays, and its reuse trusts the file's attributes (see Changed). Previously its
  setting was open after the real-log timing, which found index opens slower than plain scans
  for large logs because each hashes the whole file.

- The unreleased multi-session analysis proposal now defines per-session evidence,
  equally weighted summaries, explicit pooling, confounders and bounded-work decisions;
  previously no contract prevented a future implementation from silently pooling or
  omitting sessions. `compare_sessions` implements it, and its `TOOLS.md` section now holds
  the contract.

- The unreleased architecture guide now states six recording and resource principles with
  the failures that motivated them, and `doc/DECISIONS.md` locates standing, superseded and
  rejected choices; previously those rules were scattered through plans and reviews. The
  disk-reserve principle records copying-writer coverage and the remaining unknown-length
  command-output gap.
- The unreleased experimental Explorer plan is now named
  `doc/pit-server/EXPLORER_REMOTE_SESSIONS.md`; previously it shared `EXPLORER_PLAN.md`
  with the main viewer plan, making bare citations ambiguous.

- Record the agreed source and mirror design and its implementation checkpoints: direct remote Explorer access, explicit source selection, separate server visualization libraries, and mirror preservation with provisional peer exchange. Mark the conflicting older routing and retention policies as superseded design; runtime behavior is unchanged.

- The ideas guide now records the remaining review hardening by subject: move history and durability, socket admission and receipt timing, client lifecycle and response bounds, Windows token permissions, CSV policy, CI pins and tool dependency ownership. These open decisions previously lived only in the review.

- The publication rule now says commits stay local, one per change, until the maintainer says push; the former instruction to commit locally could read as standing permission to make commits. Specific consent for pushes, tags, version bumps and merges to main is still required.

- The HTTP pool guidance now says that exhausting all 32 dispatch workers closes the next connection without a response. Previously the separate MCP 503 limit could be read as a promise for dispatch overload too.
- Require explicit maintainer consent for each push, tag, version bump and merge to main; the source rules and release checklist previously read as if publication were routine.
- Describe NetworkTables recording, live tools, metrics and the store as shared-server capabilities, with the untested integration guides under `doc/pit-server/`. Previously capture, store and gateway were presented as one product beside the features they overlap; the main tool reference again documents every registered tool.
- The architecture guide now states the short-window autocorrelation estimator's bias toward zero and resulting effective-count inflation; previously its boundedness was documented without that approximation's limit.
- Gateway work-admission exhaustion is documented as disconnecting all connected views; the previous wording incorrectly limited it to affected views.
- Pre-release install examples now use the installers from `development` until the full release; previously they fetched `main`'s installers, which predate `--tag` and silently installed the latest full release. The release checklist records when to return those URLs to `main`.
- Working-branch CI, dependency submission and checkout instructions now name `development`; previously they named the retired branch. The release checklist names the temporary checkout qualifications to remove before a full release.
- The operations walkthrough now states its version and PATH prerequisites, the confidence field's location, archive and registration checks, the shared HTTP port, and provider states before the first session; previously following the examples could select an older release, report a missing archive, or leave the laptop server running when capture was expected.
- A new operations and usage manual, `doc/OPERATIONS.md`, gives the precise steps to use the tools and to run the shared server, the pit server, the shop day and the stores, with what to expect at each step; previously that knowledge was spread across the reference pages. The README now maps the documents by what the reader is doing, and the standalone guide is the reference the manual links into.
- The guides were rewritten for readability: long paragraphs were split, enumerations became lists, filler was removed, and claims were checked against the code; previously several pages carried specification prose of several hundred words per paragraph.
- The pit plan now marks the implemented session-manifest and matching work and audits its remaining import-correlation, source-selection and format-migration gaps; previously milestone 13 had no completion note. The backend guide names the restart strings and fixed ports that a PhotonVision pin bump must re-verify.
- The JFR plan now distinguishes chunk clock anchors from flush delivery time and specifies the measurement needed before streaming; the shop checklist explicitly checks team-owned JMX launch flags, reachability and sampling state.
- Guides describe the shared server, session leases, user-scope registration, safe legacy migration, capture/store/live layers and their concurrency and network rules. Previously the architecture overview and setup instructions still described private per-project servers. Detailed configuration, testing and remaining manual checks stay in their owning guides.
- `doc/PIT_SERVER_PLAN.md` and `doc/EXPLORER_PLAN.md` record the implemented pit and viewer layers, their decisions and the work still proposed, linked from `doc/IDEAS.md`. These workflows previously had no team-facing overview or shared development specification.
- `doc/COLUMNAR_STORE_PLAN.md` records the proposed DuckDB and Parquet analytics cache and why it was rejected: it named no measured problem, Grafana's path already runs through the data endpoint, and a native engine would share the process that records. The weaknesses that review found in the current design are addressed by `doc/ARCHITECTURE_HARDENING_PLAN.md`: an import can stop a recording, nothing measures free disk space, `/health` cannot see a stalled capture, two read paths bucket samples differently, several costs grow with the whole store, and the rules behind these choices were not written down. Previously the risk an import poses to a recording and the missing disk measurement were undocumented. Hardening item 1 and the safe copy/capture subset of item 2 have targeted checks and mutation tests. Item 8 adds the principles, native-artifact guard, decision index and plan rename; its targeted checks and behavioral plants pass. Item 3's full synthetic soak, public health/restart targeted checks and behavioral plants pass. Item 4 corrects integer-microsecond membership and order. Item 5 adds rechecked listing reuse, catalog count warnings and a synchronization-cache compatibility guard, with synthetic before/after measurements. Item 7 records the planned multi-session contract and synthetic measurements. The run stopped at choices about blocked logging, legacy copies, item 6's wall-clock mapping, route differences, the scan index, publication-failure reporting and the multi-session tool; the plan records the maintainer's decision on each. The second run implements bounded server logging and item 2's remaining copy ownership, with targeted checks and behavioral plants complete. Item 6 adds the optional UTC column, shared ambiguity handling and exact wall-clock inversion, with targeted checks complete and 37 of 38 plant attempts caught, all restored. Item 4 now has angle presentation and the five-route differential matrix, with targeted verification complete and all 23 second-run behavioral plants caught and restored. Item 5a now persists verified scan indexes, with targeted checks, all 31 behavioral plants caught and restored, and synthetic before/after measurements. The maintainer also approved bounded remote replica status, pending-session lookup and continuation of capture startup after verified publication-only failures; that implementation has passed targeted owner/transport verification and all 42 behavioral plants, with exact restoration. Item 7 has a detailed design and synthetic cost/response measurements; the maintainer approved it with amendments, and the third run built it as `compare_sessions`. The first run's full build failed on four outdated test expectations; corrected targeted checks and its extension, broker, native-sample and native-library checks passed. The second run's full build failed with six failures from missing standalone-reader dependencies, documentation field classifications and a historical fixture bypassing the store queue. Those corrections pass targeted checks and their five behavioral plants were caught and restored. The approved second full build resolved those failures but timed out in the existing daemon-restart concurrency test; its single isolated retry and the native-library guard passed. The second-run extension, broker and macOS native-sample checks passed. The third run's phase A full build accepted items 1 to 6. Its phase B full build failed on two of that phase's own test defects, since corrected, and one intermittent gateway test, so item 7 awaits a further full build. The columnar proposal remains rejected.

## [0.9.1] - 2026-10-04

This release puts the corrected extension README on the Marketplace listing, whose Overview still said the extension was not there: the 0.9.0 package was built before the README changed, and the Marketplace shows the README inside the package. It also reorganizes the README, edits every document, and makes a loaded log follow its file.

### Added
- The VS Code extension is on the Visual Studio Marketplace; it was only a `.vsix` on the releases page. When the `VSCE_PAT` secret holds a Marketplace token, the release workflow publishes every release without a version suffix there (the Marketplace refuses suffixed versions, so test builds stay on GitHub); without the token, the workflow says how to add one. The package carries the license file; the manifest links the issue tracker and the README.

### Fixed
- A loaded log follows its file. The server keeps each loaded log's file size, modification time, and identity from just before the log was read, and compares them with the file on every call: a file that changed on disk is loaded again, and a result read while the file changed is discarded with an error that says what changed, because it may hold old data or mix old and new bytes. A read of a file truncated under its memory mapping is the same explained error, and the log is unloaded. Each session is told once, in `_metadata.log_reloaded` and a warning, when a log it used was reloaded. Before, a loaded log answered from its first load for as long as it stayed in memory, so a log copied off the robot again once it had grown kept its old contents; a file overwritten in place had the old record offsets applied to the new bytes, which hid the new records or decoded garbage; and the read of a file truncated while loaded threw an `InternalError` that nothing caught, which in stdio mode ended the server.
- The REV log tools look again for the REV logs that belong to a wpilog, at most every two seconds, and synchronize again when a REV log appeared, grew, or went: a REV log copied off the robot after the wpilog is found without reloading the wpilog, and an offset set with `set_revlog_offset` is kept for a REV log whose file did not change. Before, REV logs were found only when the wpilog was loaded.

### Testing
- CI checks that every Java file carries the license header (`./gradlew license`); the check used to run only locally, as part of `./gradlew build`. A build-file test fails if the workflow stops running it.

### Documentation
- The READMEs now send readers to the Marketplace to install the extension; the `.vsix` on the releases page remains the way to a particular build. The README's header links the Marketplace listing.
- `doc/STANDALONE.md` says why the TBA key belongs in the configuration file or the environment rather than on the command line, where the process list shows it.
- The README is reorganized. The example questions follow "How It Works" instead of repeating the header's questions a paragraph later; the installation section says once that most people want the extension; the agents the server works with are named (Claude, GitHub Copilot, Gemini, ChatGPT over the HTTP transport, and others); and a new section on supporting Triple Helix links the Intentional Innovation Foundation's donation page. The prose was edited throughout.
- The other documents got the same editorial pass: pronoun references and wrong words fixed; chained clauses broken up; the standalone guide's `-debug` flag back in its table, the HTTP transport and containerization sections placed as sections of their own, and the command-line notes gathered under the flags; the extension README's requirements moved ahead of installation and its upgrading section covering the Marketplace; the tools reference's REV log reference material, troubleshooting, and workflow gathered in the section's introduction, with one "When to use it" heading for every tool that has one; the development guide's test suites as paragraphs, its extension build beside the server build, and its releasing step split from what the workflow does; the ideas file's stale references and duplicated hand-off removed.
- `CLAUDE.md`, the guidance for AI agents working on the code, is brought up to date. It now covers the result contract, the no-guessing rule, the testing rules, the source conventions, the disk cache, and the VS Code extension; season-specific detail is left to the documents that own it.
- `doc/DEVELOPMENT.md` says how to keep tests passing on Windows, where CI runs them too, and that no value from another team's log goes into the repository unless the log's license allows it; it said neither.

## [0.9.0] - 2026-10-02

Most of this release is robustness work from [doc/ROBUSTNESS_REVIEW.md](doc/ROBUSTNESS_REVIEW.md), following [doc/ROBUSTNESS_PLAN.md](doc/ROBUSTNESS_PLAN.md): tools take their behavior from the log itself and never report success when they found nothing to analyze. It also adds several log directories and changes how the VS Code extension sets up Claude Code.

### Changed (breaking)
- VS Code extension: `wpilog-mcp.teamNumber` is empty until you set it. It defaulted to 2363, so a log that records no team number was matched with team 2363's TBA data; a team that relied on that default sets it once.
- VS Code extension: the `wpilog-analyzer` entry for Claude Code is added to `.mcp.json` only in WPILib robot projects and in folders that already have one, instead of in every workspace. The **Enable For Claude Code** setting (`wpilog-mcp.enableForClaudeCode`, on by default) turns this off, and **WPILog Analyzer: Add to Claude Code in This Folder** adds the entry to any other folder. The extension never writes into a `.mcp.json` that git tracks (it offers to add the file to `.gitignore`), leaves alone one that already runs the standalone install, and keeps the file's other servers.
- VS Code extension: the `.mcp.json` entry only starts the server, from a copy of the JAR in the extension's global storage, so an extension update no longer breaks it. The project's settings and the TBA key go into a configuration file there, one per project, that only you can read. Every server the extension starts keeps its disk cache in the extension's storage, apart from the standalone install's.
- Every result has a `status`: `ok`, `partial` (what was skipped is listed in `skipped`), `not_applicable`, `no_match` (with `looked_for` and a `hint`), or `error`. `success` is true only for `ok` and `partial`, so a tool that found nothing no longer reports success. A list cut by a limit reports `limits.<key>` with `total`, `returned`, and `limit`.
- Values that cannot be computed are `null`, not `NaN` or `Infinity` (which are not valid JSON), and are named in `_metadata.non_finite_fields`.
- `health_check` no longer returns `status: "OK"`, and its `disk_cache` block is replaced by `sync_disk_cache` and `parsed_log_disk_cache` (`used_by_load_path: false`). `get_tba_status` reports `configuration` (`configured` or `not_configured`) instead of `status`.
- Entries are listed in the order the robot program declared them, and a name started twice with the same type is one entry with all its records.
- The server no longer guesses what an entry is from its name or from the shape of its data. A role (battery voltage, total current, loop time, robot pose, auto chooser, path poses, swerve module states, vision entries, gyro yaw) resolves only to an entry you pass, an entry published under a well-known AdvantageKit, WPILib, CTRE, PathPlanner, YAGSL, Limelight, or PhotonVision name, or the only entry of its type. Anything else that looks right is listed as a candidate in `candidates`, `skipped`, or `no_match`, with the parameter to pass. New parameters to pass them: `voltage_entry` (`power_analysis`, `get_ds_timeline`, `predict_battery_health`, `generate_report`), `total_current_entry` (`predict_battery_health`), `chooser_entry`, `path_setpoint_entry`, and `path_actual_entry` (`analyze_auto`), and `vision_entries` (`analyze_vision`).
- Structs are decoded with the schema each log records (`/.schema/struct:<Name>`), so any struct decodes, including a team's own, a vendor's, nested structs, enums, and bit-fields. WPILib's schemas and three templates (`PoseObservation`, `TargetObservation`, `SwerveSample`) are used only for types the log has no schema for, and results say which applied (`source`).
- Struct output uses the schema's field names, with no aliases for the old keys: a `Pose2d` is `{"translation": {"x", "y"}, "rotation": {"value", "_derived": {"degrees"}}}` instead of `{x, y, rotation_rad, rotation_deg}`, and an enum field is `{"value", "label"}` instead of a bare label. Update any client code that reads struct values.
- Records that cannot be decoded are reported in a warning and in `_metadata.decode_problems`, and `read_entry` on an entry with no decodable records is an error that says why.
- `list_struct_types` with `path` describes the struct types in that log (source, size, fields, `numeric_leaf_paths`, and the entries using each); without `path`, the fallback schemas. It used to return a fixed list of 16 names.
- `get_entry_info` describes a struct entry's schema, fields, and `numeric_leaf_paths`, reports `decode_problem` and `non_empty_sample_count`, and takes its sample values from non-empty values.
- The numeric tools (`get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `find_condition`) read struct fields and array elements, given as a path after the entry name (`/RealOutputs/Drive/Pose.translation.x`, `/PowerDistribution/ChannelCurrent[3]`) or in `field`; `get_statistics` pools all elements with `[*]`. A struct or array entry without a path is an error that lists its numeric fields. Known angles (`Rotation2d`, `Rotation3d`, `SwerveSample` heading) are unwrapped, and `get_statistics` adds circular statistics.
- The same tools take `scope` (`enabled`, `disabled`, `auto`, `teleop`, `test`, or `segment:<i>`) and `windows` (a list of `{start, end}`, such as `find_condition`'s `intervals`), combined with `start_time` and `end_time`. Derivatives, peaks, and angle unwrapping stay within each window.
- `angle` (`radians` or `degrees`) declares a plain number, such as a gyro yaw, to be an angle: it is unwrapped across ±180° and summarized with circular statistics. The numeric tools, `compare_matches`, and `align_entries` accept it.
- `time_correlate`'s p-value accounts for autocorrelation, using an effective sample size from the two series' lag-1 autocorrelations (`effective_sample_size`, `lag1_autocorrelation`, `p_value_basis`), so smooth 50 Hz signals no longer look overwhelmingly significant.
- `data_quality` is calibrated. It classifies the `sampling` (`periodic`, `change_only`, or `event`), weighs long intervals by the share of time they cover, bases the sample-size penalty on finite values, and lists every penalty in `reasons`. Nearly every real result used to score 0.50 ("low"); long intervals in a change-only series (AdvantageKit logs values when they change) are now read as holds.
- `search_strings`, `get_ds_timeline`, `can_health`, and `generate_report` read `string[]` entries such as WPILib alerts and the string values of `json` entries, not only `string` entries. An alert is one event from when it appears until it clears (`end_sec` and `duration_sec`, or `ALERT_RAISED` with `cleared_at` in the timeline), and every match gives its `source` (`string`, `alert`, or `json`).
- `get_match_phases` returns `segments` (each interval of constant state, with `mode` and `end_reason`), `matches`, and `season`, instead of one span from the first enable to the last disable; `phases` describes the first match. A log without DriverStation data returns `no_match`, and a log whose robot was never enabled says so.
- `get_ds_timeline` builds its enable and mode events from the same timeline as `get_match_phases`, so a log with both `DS:` and `/DriverStation/` entries no longer gets doubled events, and `TELEOP_START` appears at every teleop enable.
- `analyze_auto` lists every autonomous period (`auto_periods`) and reads the selected routine from `chooser_entry`, or from the only chooser whose key contains "auto" (a WPILib SendableChooser, or AdvantageKit's `/NetworkInputs/SmartDashboard/<key>`). A log with no autonomous period returns `not_applicable`.
- `analyze_can_bus` reads CAN counters by field name, per bus: WPILib's `/SystemStats/CANBus` (bus `rio`) and CTRE CANivore status. `bus_name` now selects a bus; TEC and REC are reported as levels, with excursions to 128 or above; bus-off and TX-full counts are reported as increases, and utilization in percent.
- `can_health` matches "CAN" as a whole word, classifies each error line as enabled or disabled from the DriverStation timeline (`state_unknown` before any DriverStation data), and also reads the bus counters. `assessment_basis` says what decided `health_assessment`.
- `analyze_swerve` reads `SwerveModuleState[]` arrays as `module[0..N-1]`, reports speeds as magnitudes (`mean_abs_speed_mps`), pairs setpoints by index, and reports each module's speed tracking and steer error. The measured and setpoint entries come from a published naming, each pair from one table (AdvantageKit `SwerveStates/Measured` with `SetpointsOptimized` or `Setpoints`, CTRE `DriveState/ModuleStates` with `ModuleTargets`, YAGSL `currentStates` with `desiredStates`), or from the only module-state entry in the log, with a warning that the log does not say what it holds. Entries under other names, and one entry per module, are candidates (`no_match` with `needs_confirmation`) until passed as `measured_entry` and `setpoint_entry`; `measured_basis` and `setpoint_basis` say how each was chosen. New parameters: `measured_entry`, `setpoint_entry`, `scope`, `start_time`, and `end_time`. `swerve_entries` and `module_analysis` are removed.
- `analyze_replay_drift` compares arrays and structs element by element within `relative_tolerance` (every array entry used to be "divergent") and reports the first divergence and largest difference of each entry. A log without `/ReplayOutputs/` returns `not_applicable` instead of "0 divergences".
- `power_analysis`, `predict_battery_health`, `get_ds_timeline`, and `generate_report` use the roboRIO's logged `BrownoutVoltage` as the brownout threshold, else 6.8 V as a stated assumption (`brownout_threshold_basis`); a `brownout_threshold` argument still overrides it. `power_analysis` and `generate_report` report the roboRIO's logged brownouts (`rio_brownouts`).
- `power_analysis` (new `scope` parameter) and `generate_report` analyze enabled time when the log records it, and `generate_report`'s `avg_voltage_whole_log` is now `avg_voltage`. `brownout_risk` follows one stated rule (`brownout_risk_basis`): HIGH only from the roboRIO's logged brownout flag, or from threshold crossings when no flag is logged.
- `predict_battery_health` reports evidence, not advice: brownouts from the roboRIO's flag when it is logged, voltage statistics over enabled time (`scope`), and a `load_line` fitted against total current, with `observations` in place of the "Replace battery" recommendations. Its heuristic `health_score` remains, with `health_score_basis`, and a log without a voltage entry returns `no_match`.
- `generate_report` shares the voltage entry and brownout threshold with `power_analysis`, classifies console text as `get_ds_timeline` does, and adds the DriverStation timeline, the three largest current peaks, and the top error messages.
- `get_code_metadata` matches keys by leaf name, names each value's `sources`, and returns `no_match` when there is no metadata.
- `analyze_vision` reads what the AdvantageKit vision template and the vision libraries publish under their own names: `PoseObservation[]` streams (`/Vision/Camera<N>/PoseObservations`), `TargetObservation` entries, the template's pose arrays (`Vision/Summary/RobotPosesAccepted` and the like), Limelight's `tv`, and PhotonVision's `hasTarget`. For each camera it reports the observation rate, tag counts, ambiguity, latency, and residuals against the robot pose. Entries that only look like vision data are listed in `candidates` by kind, with `needs_confirmation`, and are not decoded; `vision_entries` passes the ones you have confirmed. `latency_candidates` lists entries named latency beside a stream, which `get_statistics` reads. `vision_prefix` limits only the vision entries (it used to hide the robot pose too), and the robot pose comes from `pose_entry` or the resolver.
- `profile_mechanism` analyzes only the entries passed for its roles (`setpoint_entry`, `measurement_entry`, `velocity_entry`, `current_entry`, `temperature_entry`). `mechanism_name` no longer chooses entries: it lists, per role, the entries whose names suggest it (`candidates`, with `needs_confirmation`), because a name does not establish that an entry is a mechanism's setpoint, or what its units are. Following error uses the setpoint in force, overshoot is a percent of the step, stalls in reverse are found, and `stall_velocity_threshold` and a temperature profile are new.
- `detect_anomalies` counts every anomaly (`anomaly_count` used to equal `limit`), reports its IQR `bounds`, takes `sort` (`time` or `severity`), and implements `spike_threshold` (jumps between samples, in the entry's units), with `spike_interval_sec` for the time between spikes.
- `find_condition` returns `intervals` (with `end_reason`), `total_true_sec`, and `fraction_of_window`, accepts boolean entries, and combines conditions on several entries with `conditions: {all: [...]}` or `{any: [...]}`. New operators: `ne`, `abs_lt`, `abs_lte`, `abs_gt`, and `abs_gte`.
- `find_peaks` reports `maxima_count` and `minima_count`. The numeric tools read booleans as 1/0, and an entry of the wrong type is an error naming its type.
- `compare_entries` rejects non-numeric entries, reports `samples_compared`, and makes entries that never overlap in time an error.
- `compare_matches` resolves `scope` in each log's own timeline, reports percentiles, `std_dev`, `sample_count`, and `data_quality` for each log, and gives `differences` (second minus first) without a significance test. An extreme in a log's first 5 s is flagged as a likely boot transient, and a log without the signal makes the result `partial`.
- `analyze_loop_timing` reads the entry passed as `entry`, else AdvantageKit's `LoggedRobot/FullCycleMS` (with `UserCodeMS` alongside), else loop periods derived from `/Timestamp`; other loop-time names are only candidates. The unit's basis is reported, the slow boot cycle is excluded, and `scope`, median, p90, and `percent_over_threshold` are new. A log without loop timing returns `no_match`.
- `export_csv` resolves a bare or relative `output_path` inside the export directory (`output_path` is optional) and returns the absolute path written. Values are flattened into columns: struct fields as dot paths, arrays one row per element with `index`, enums as `field` and `field.label`. New `inline` mode returns up to `max_rows` rows in the response.
- `list_available_logs` pages (`offset`, `limit`, default 50) and filters (`name`, `event`, `match_type`, `since`) its results and reports `log_count`, `total_logs`, and `has_more`; only the listed page is enriched from TBA. `log_directory` is replaced by `log_directories`. The event, match, and team come from the entries that carry them by convention (`EventName`, `MatchType`, and `MatchNumber` in the `DriverStation` and `FMSInfo` tables, and `SystemStats/TeamNumber`) and from the file name, now in WPILib's form (`FRC_20260321_162956_VACHE_Q10.wpilog`) as well as AdvantageKit's. A log named for an event and no match is listed with no match type; it used to be called a practice match.
- `get_tba_match_data` returns `no_match` for a match or event that does not exist, and an error, not "not found", for a rejected key or a network failure. `match_type` accepts TBA's codes (`qm`, `q`, `qf`, `sf`, `f`), B teams work, and `score_breakdown` passes through every `...Points` subtotal.
- Revlog sync reads the wall clock from WPILib's `systemTime` or AdvantageKit's `EpochTimeMicros`, using only readings taken after the roboRIO's clock was set; a log whose clock was never set is not matched to REV logs by time. A REV log's file-name time is read in the zone the wpilog's own file name shows (`revlog_filename_zone`), else UTC. A REV log from another session is not attached, and a correlation needs at least 10 s of overlap.
- The revlog tools return `not_applicable` when no `.revlog` was found for the wpilog. `get_revlog_data` computes `include_stats` over every sample in range and reports `limits.data`, and `wait_for_sync` caps `timeout_ms` at 120 s.
- `search_entries` and `get_types` return `no_match` when nothing matches.
- `analyze_cycles` reports truncation in `limits.cycles` and `limits.dead_time_periods`, replacing `cycles_truncated`, `total_cycles`, `dead_time_periods_truncated`, and `total_dead_time_periods`, and returns `no_match` when `cycle_start_state` never occurs.

### Added
- Several log directories. `logdir` in the server configuration takes a list, `-logdir` can be repeated, and `WPILOG_DIR` holds several directories separated as in `PATH` (`:`, or `;` on Windows); `-logdir` replaces the directories in `WPILOG_DIR`. `list_available_logs` lists them together, and a directory that cannot be read makes the result `partial`, named in `skipped`. REV logs are matched to a wpilog only within the configured directory that holds it and the wpilog's own folder, so another team's REV log from the same event is never synchronized with yours.
- VS Code extension: when it adds the server to a project's `.mcp.json`, it says, once per project, that a Claude Code session already running there must restart to find it (Claude Code reads `.mcp.json` only when a session starts), and how: **Reload Window**, offered when the Claude Code extension for VS Code is installed, or `claude --continue` in a terminal. **Don't Show Again** turns the notice off.
- VS Code extension: the `wpilog-mcp.additionalLogDirectories` setting. Log directories and the team number can be set in User settings for every project and overridden in a project's own settings, and a relative path is a folder inside the project. The Settings editor lists the settings in a fixed order (the log directory, the additional directories, the team number, the TBA API key, Claude Code, then Java and the heap) instead of alphabetically, which put the additional directories before the log directory.
- `resolve_signals` shows which entry plays each of 25 roles in a log (DriverStation state, battery voltage, brownout flag, loop time, robot pose, swerve module states, vision, CAN buses, alerts, and more), with the `match` tier, the basis, ranked candidates, `needs_confirmation`, and the tools that use it (`used_by`). The tools resolve their entries the same way.
- `align_entries` samples 1 to 8 numeric signals at common times (every record of one entry, or timestamps stored inside it with `time_field`), with `previous`, `linear`, or `nearest` interpolation. With two signals and `difference: true` it adds `difference_statistics`.
- `time_correlate` and `compare_entries` take `max_lag_sec` (and `lag_step_sec`) and report the best lag in `lag_search`: for `time_correlate` the lag with the strongest correlation in either direction, with its sign, and for `compare_entries` the lag with the lowest RMSE.
- `pose_corrections` measures how far a pose moved beyond what odometry or the integrated chassis speeds predict: the residual distribution, the corrections above a threshold, and their cadence (`correction_interval_sec`). It shows how big and how frequent vision corrections are.
- `compare_poses` reports the difference between two pose streams: distance, heading difference, and components in field coordinates or along and across the reference's heading (a path-following error).
- Successful results from log-reading tools say what they used: `inputs` names the entries read.
- `list_loaded_logs` reports each log's entry count and duration and, in `cache`, the number of loaded logs and the heap in use.
- MCP `instructions` in the `initialize` response, which Claude Code and VS Code put in the model's system prompt: nine rules on answering from tool results, reading `no_match` as "not found", learning what an entry measures from the robot source code that logs it and not from its name, verifying the premise, separating facts from inferences and hypotheses, testing a rival explanation, scoping statistics, and generalizing only across matches. `get_server_guide` returns the long form as `analysis_principles` (including confabulation traps and the tool call that avoids each) for clients that drop `instructions`, and asks Claude Code to keep its description loaded (`anthropic/alwaysLoad`).
- The server sends the agent to the robot's source code for what a log cannot say: which mechanism an entry belongs to, its units, and whether it is measured or commanded. The rule is in the MCP instructions, in `get_server_guide` (`critical_guidance.source_code_tip`, the first step of the method, and a trap in `analysis_principles`), in `resolve_signals`, and in every reason a tool gives for listing a candidate and not using it.
- WPILib DataLogManager logs: `DS:enabled` and `DS:autonomous` are read as well as AdvantageKit's `/DriverStation/...` entries, so match phases, scopes, and the enabled state of CAN errors work without AdvantageKit.
- `get_ds_timeline` reports the roboRIO's logged brownout flag as `RIO_BROWNOUT_START` and `RIO_BROWNOUT_END` events (`basis: "rio_flag"`), apart from voltage-threshold crossings (`basis: "voltage_threshold"`), and names the voltage entry it scanned (`brownout_voltage_entry`).
- `get_ds_timeline` counts console errors and warnings exactly (`text_event_counts`) and groups distinct messages in `text_event_summary` (count, `variants`, first and last time; up to 200 groups, with the true total), instead of listing some of them.
- `search_strings` lists all of a log's text in time order, with `pattern` (a substring, or `regex: true`), `level`, `start_time` and `end_time`, `offset` and `limit` paging with `total_matches` and `has_more`, and `collapse_repeats`. A regular expression gets one second per text value, so a catastrophic pattern returns an error instead of hanging the server. The old tool stopped silently at 50 matches.
- `power_analysis` reports `channel_analysis` for every current entry (signed peak and its time, minimum, maximum, average), expanding per-channel arrays such as `/PowerDistribution/ChannelCurrent`, capped by `channel_limit` (default 30). Current entries are recognized by a naming rule, so names like `CurrentLimit` are not taken for currents.

### Fixed
- The release workflow attaches the JAR and the `.vsix` before the release is published, and takes the release notes from the annotated tag's message. The repository's releases are immutable, and a pre-release used to be published first, so `v0.9.0-dev` was published with no files; its tag name cannot be reused, so the test build is `v0.9.0-dev2`.
- VS Code extension: the extension's version comes from `build.gradle` (`./gradlew syncExtensionVersion`, which every extension task and the release workflow run). Its `package.json` had stayed at 0.8.2, so a release would have looked already installed; a release now stops when its tag does not match `build.gradle`.
- VS Code extension: the TBA API key is kept in VS Code's secret storage instead of in plaintext settings and `.mcp.json`, where a commit could publish it. It is entered in the **Tba Api Key** setting, which the extension clears a few seconds later, once the key is in secret storage (a key still being typed is not moved, and Settings Sync never uploads the setting), or with **WPILog Analyzer: Set The Blue Alliance API Key**; **Clear The Blue Alliance API Key** removes it. A key in a project's own settings never replaces a stored key, since it may be a teammate's, and the extension tells the user to revoke it if the file was shared. On upgrade, a key found in settings is moved there, and a key an earlier version wrote into `.mcp.json` is removed with a note to revoke it if the file was shared.
- VS Code extension: requires VS Code 1.101 or later (`engines.vscode` is `^1.101.0`). On 1.100 it installed but failed on activation.
- VS Code extension: relative links and images in the packaged README work.
- VS Code extension: on Windows, a log folder named twice, such as `logs` inside the project and `C:/robot/logs`, or with a trailing slash or in another case, is passed to the server once; it was passed twice, and its logs were listed twice.
- VS Code extension: a project's configuration file, which holds the TBA key, is written only once the project's `.mcp.json` can take the entry, and **Clear The Blue Alliance API Key** removes the key from every configuration file in the extension's storage. A `.mcp.json` that was not valid JSON left a file with the key behind, which Clear did not reach.
- Revlog sync on recordings longer than 600 s no longer reports an offset off by up to tens of seconds at a correlation near 1.0. The sub-sample refinement now moves toward the correlation peak, and a clock-drift estimate beyond 1000 ppm is rejected and reported.
- Revlog sync pairs signals by data: names only nominate candidates by leaf name (applied output, velocity, current, bus voltage), correlation ranks them, and pairs that disagree are set aside instead of averaged. High confidence needs at least two pairs whose offsets have a standard deviation of 5 ms or less.
- With no correlated pair and no wall clock, revlog sync fails and says so instead of reporting an offset of 0. File-name estimates say they can be off by seconds.
- REV logs decode as REV specifies: the built-in DBC follows REV's SPARK frame specification (spark-frames 2.1.0, firmware 25 and later), so bus voltage, velocity, and applied output are no longer garbage. DBC files may declare float signals (`SIG_VALTYPE_`) and extended-frame IDs with bit 31 set.
- SPARK devices are labeled by the model they report (`SparkMax_<id>`, `SparkFlex_<id>`, or `Spark_<id>`, from a decoded `SparkModel` signal); every SPARK used to be labeled SPARK MAX. A REV log named `REV_..._<bus>.revlog` is reported under that bus, and file-name times in log listings are read as UTC, the roboRIO's zone (local time for `_sim` logs).
- The revlog sync disk cache key includes both file names and the DBC's hash, and its format version changes with any change to parsing, decoding, or sync, so results computed by older code are discarded.
- `get_revlog_data` and `list_revlog_signals` say how their timestamps were aligned (`sync_method`, `timestamps_aligned`, `offset_seconds`, `sync_confidence`), and a REV log whose sync failed returns `not_applicable` instead of its own clock. `set_revlog_offset` without `offset_ms` is an error; it was an offset of zero.
- A revlog sync that finishes after its log was unloaded no longer keeps the log in memory, a log loads even when the sync cannot start, and the revlog signal cache is safe for concurrent HTTP requests.
- TBA data for playoff logs is attached to the right match: since 2023 "Elimination N" is bracket match N (`sfNm1`), and a finals log is the team's playoff match nearest the log's time. `list_available_logs`'s `tba` object and `get_tba_match_data` report `match_key` and `lookup_method`.
- A TBA outage or a rejected key is no longer read as missing match data: `list_available_logs` reports `tba_enrichment: {available, reason}`, and `get_tba_status` checks the key (`key_check`). A `tba_key: ${VAR}` whose variable is unset counts as not configured, and log file names with the match types `qm` and `f` are enriched.
- Damaged log tails are not read as data. A log cut off mid-write can end in bytes that parse as records with absurd timestamps; the parsers stop at the damage, drop the records right before it that jump more than 60 s, and ignore any record more than a day ahead. A garbage Start record or header no longer fails the load. Negative timestamps are kept, since WPILib DataLogManager logs hold real records before time zero.
- A file that is not a readable log (empty, all zeros, another format, missing, a directory, without read permission, or outside the allowed directories) gets an error that says what the file is, instead of an "Internal error". A log over 2 GB gets an error that says so.
- Every result computed from a log that was not read to its end says so, in `_metadata.log_truncation` (`comparisons[].log_truncation` in `compare_matches`); only `list_entries` and `generate_report` used to. Most robot logs end inside their last record, so that alone is a note; a log that lost more gets a warning too.
- `analyze_vision` no longer reports a planned trajectory as a camera. A `SwerveSample[]` trajectory holds a timestamp and a pose, as a camera's observations do; where the robot logs one every loop it appeared as a camera named for its table, with a latency of minutes, and decoding its millions of samples took one call on a 688 MB log past 3 GB of heap. A gyro's struct with yaw and pitch fields is no longer a target stream, and a `Pose3d[]` outside the vision tables, such as a mechanism's component poses, is no longer a pose set.
- `analyze_swerve` pairs measured states with the setpoints of their own table. In a replay log, the replayed states could be compared with the real setpoints.
- `time_correlate` with `scope` or `windows` computes its lag-1 autocorrelations and sample rates within the windows. Pairs of samples on either side of the time between two windows lowered the autocorrelation, which overstated the effective sample size, and the sample-rate warning counted that time.
- `analyze_vision` and `analyze_cycles` score `data_quality` on the samples between `start_time` and `end_time`, not on the whole entry.
- `find_peaks` rejects a `type` other than `max`, `min`, or `both`, and `resolve_signals` rejects a `roles` that is not an array of role names. Either used to be read as the default.
- Heap pressure unloads only as many logs as needed. Free heap was measured without a garbage collection, so unloading a log changed nothing in the measure and every loaded log was unloaded, whenever the heap looked more than 85% full, even when it was full of garbage.
- The log listing no longer takes a team's own entries for the event, the match, or the team (any entry whose name contained `eventname`, `matchtype`, `matchnumber`, `stationnumber`, or `teamnumber` was read, whatever it was), reads the match type from its integer entry, and ignores a match number logged while the match type is None.
- Both parsers report malformed records in `decode_problem`, naming the record size and declared type.
- Scanning a log takes less memory: 4 bytes per record for offsets instead of about 22.
- A log evicted from the cache while a call was reading it no longer reads as empty.
- A call that exhausts the heap returns an error naming the heap size and the remedies (a time window or scope, `WPILOG_MAX_HEAP`), and the server keeps serving.
- `read_entry` returns NaN and infinite samples as the strings `NaN`, `Infinity`, and `-Infinity` instead of failing.
- `sample_context` in `server_analysis_directives` counts finite samples ("Based on 25 finite samples of 300 (275 NaN or infinite)").
- `data_quality` is scored on every sample in scope, per window. Some tools scored it after dropping NaN samples, scored the whole entry instead of the scope analyzed, or counted the time between windows as a gap. `predict_battery_health` reports `risk_level_basis` and `analyze_loop_timing` reports `health_score_basis`.
- `get_ds_timeline`, like `get_match_phases`, no longer carries `data_quality` or directives, which marked exact events as low confidence; the low-quality warning now applies to statistics only. `find_condition` and `analyze_can_bus` gain `data_quality`.
- `rate_of_change` returns `avg_rate: null` with `no_match` when no pair of samples can be differentiated, and `time_correlate` gives `p_value: null` beside a null correlation.
- No more empty successes: `can_health` and `get_ds_timeline` return `not_applicable` on a log with none of their inputs, and `list_entries`, `align_entries`, `export_csv` (which wrote a header-only file), `list_struct_types`, and `list_revlog_signals` return `no_match` when nothing matches.
- `find_condition` reports `samples_evaluated` and `find_peaks` reports `samples_analyzed`, so zero findings can be read against the samples searched.
- Every capped list reports `limits` with the true total, including `power_analysis` `channel_analysis`, `generate_report` `errors.top_messages`, and `predict_battery_health` `brownout_details`. `read_entry` caps a page at 10,000 samples.
- `analyze_swerve` takes its odometry and vision poses from the `robot_pose` and new `vision_pose` roles (or `odometry_entry` and `vision_entry`, where a missing entry or one that is not a pose is an error) and reports `odometry_basis` and `vision_basis`; it used to pick the first pose named like odometry. A number in a file name is read as the season only when it is a plausible year, not a team number.
- `analyze_cycles` in `start_to_start` mode starts a cycle on a change into `cycle_start_state`, so a state logged every loop no longer yields a stream of 20 ms cycles. An unfinished idle period ends at `end_time`.
- `moi_regression` validates `kt`, `gear_ratio`, `motor_count`, and `wheel_radius` and reports an undefined R² as null. `get_server_guide` rejects an unknown category, and `suggest_tools` returns `no_match` when nothing fits.
- The last record of a log is read; a final record shorter than 16 bytes (a boolean, a small number) was dropped.
- Bundled game data was checked against the final game manuals and corrected for 2024 CRESCENDO, 2025 REEFSCAPE (field size, ranking points; the CORAL RP is `coral_rp`, was `reef_rp`), and 2026 REBUILT. `get_game_info` returns `source`, `manual_version`, `manual_url`, `basis`, and `robot_constraints`.
- Tool descriptions match behavior: `can_health`, `power_analysis`, `analyze_auto`, `analyze_can_bus`, `get_statistics`, `generate_report`, and `export_csv`.
- Argument checks: a non-positive `limit` (`find_peaks`, `rate_of_change`, `detect_anomalies`, `find_condition`, `analyze_cycles`, `analyze_replay_drift`, `get_revlog_data`), a negative `moi_regression` `smooth_window`, a missing `get_tba_match_data` `year` or `match_number`, and a `start_time` after `end_time` are errors that say what is wrong, not internal errors or empty successes.
- Schemas declare every parameter the code reads: `angle` on `find_condition` and `compare_matches`, `windows` on `compare_matches`, and `start_time` and `end_time` on `power_analysis`.
- HTTP shutdown ends open SSE streams and no longer waits out a fixed five seconds.
- The stdio transport uses UTF-8 on every platform. On Windows with JDK 17 it mangled non-ASCII text.
- Malformed JSON-RPC requests get JSON-RPC errors (-32600 or -32602) with the request's id, and a request without an id is a notification that gets no reply.
- `start` reports a daemon running on another port (after `port` changed in `servers.yaml`) with its PID and refuses until it is stopped. Two starts no longer both spawn a daemon, whether they race over a stale PID file or the second arrives while the first one's server is still booting, when its record used to be taken for a reused process ID and deleted. On Windows, a start no longer fails, stopping the server it has just launched, when another start or another program has the PID file open at the moment the file is replaced.
- A server started in the background with `start` gets the launcher's heap: `WPILOG_MAX_HEAP`, else the `-Xmx` it was started with, else 4g. Without `WPILOG_MAX_HEAP` it used to get the JVM's default, a fraction of physical memory.
- The one-line installers (`install.sh`, `install.ps1`) install a release the way `./gradlew install` installs a build: a launcher per version and `wpilog-mcp` pointing at the newest, so an upgrade no longer rewrites an older version's launcher. They refuse a release tag that is not a version. `install.ps1` writes `servers.yaml` as written (PowerShell had expanded `${TBA_API_KEY}` in it to nothing) and works in Windows PowerShell 5.1.
- No installer writes a team number into a new `servers.yaml`: the one-line installers wrote `team: 0` and `./gradlew install` wrote `team: 2363`. A team number of 0 or less, from any source, is ignored with a warning.
- The `servers.yaml` the installers write has one place for the TBA key, the commented-out top-level `tba_key`, which says `TBA_API_KEY` works too. The `stresstest` server had its own `tba_key: ${TBA_API_KEY}`, which looked like the place for the key: a key put there reached the stress tests and no other server.
- The `measureMemory` Gradle task is gone; it passed a flag the server never had.
- `-debug`, `WPILOG_DEBUG=true`, and `debug: true` in `servers.yaml` turn on debug logging; they had no effect.
- `-tba-key` takes precedence over `TBA_API_KEY`, as the usage text says.
- A REV log synchronization read from the disk cache lists devices and signals in the order the REV log shows them, as a fresh one does. It listed them in no fixed order, so `list_revlog_signals` answered in a different order after the server restarted.
- `power_analysis` returns `no_match`, naming the scope, when there is nothing to measure in it: the scope holds no time (`enabled` on a log where the robot was never enabled, or a `start_time`/`end_time` outside the data), or no finite voltage or current sample falls in it and no brownout flag is logged. It returned a success with every section skipped. When amperage entries exist but have no finite samples in the scope, `power_analysis` and `generate_report` say so; they reported that the log had none.

### Security
- msgpack-core is 0.9.12 (was 0.9.8), which fixes CVE-2026-21452 (GHSA-cw39-r4h6-8j3x, high): a payload header could declare up to 2 GB that the library allocated before reading any of it. The server reads such payloads from its parsed-log disk cache, which is not on the load path, and only from its own checksummed files, so the exposure was small; a crafted cache file of a few dozen bytes made the reader fail with an `OutOfMemoryError` instead of rejecting the file. A test now checks that such a file is rejected while allocating almost nothing.
- The VS Code extension's development dependencies, which are used to build and package it and are not part of the `.vsix`, are updated within their existing version ranges, clearing all 42 Dependabot alerts (21 high, 16 medium, 5 low). All of them came through `@vscode/vsce`: `undici`, `fast-uri`, `js-yaml`, `brace-expansion`, `markdown-it`, `linkify-it`, `form-data`, `tmp`, `qs`, `uuid`, and `lodash`.
- CI submits the server's Gradle dependencies to GitHub's dependency graph on every push to `main`. GitHub does not read `build.gradle`, so Dependabot never saw the libraries in the server JAR, msgpack-core among them; it now alerts on them as it does on the extension's npm packages.

### Testing
- Tests check the server against an independent WPILOG reader, compare each tool's schema with the parameters its code reads and its description with real output, and cover the revlog and TBA success paths. `-PconformanceLogDir=<dir>` runs the independent-reader check, a conformance sweep, and a check of documented claims on a directory of real logs.
- VS Code extension: `npm test` runs the tests on Node 21 and later, where it failed because `node --test` no longer accepts a directory, and CI runs them on Linux and Windows (it only compiled the extension, so the failure went unseen).
- The stress tests use the configured TBA key (they ran every TBA call as "not configured"), build the test classes before running, and fail the build when a test fails.
- A tool can be added without the logs `doc/TOOL_RESPONSES.md` is generated from: the checks accept a generated file that does not hold the tool yet, and require a call for it in the scenarios file.
- The stress tests use their own disk cache, emptied before each run, instead of the user's: they read REV log synchronizations that other code had saved, and saved theirs there. `-PtestCacheDir=<folder>` gives every test task a folder that is kept between runs. The in-process stress test checks that a synchronization read from the disk cache gives the same answers as one computed with the cache off, and both stress tests run the REV log tools on a log that has a REV log; they ran them on a log without one. They list every log, page by page; they read only the first 500.
- The real-log conformance sweep searches six folder levels below the log directory, as the differential check does; it searched five. The claims check reads the simulated logs and their manifest from `-PsimLogDir`.
- The differential check recomputes domain answers from the raw records of the entries each tool names: loop-time statistics, roboRIO brownouts, CAN bus figures, swerve module speeds, and the modes of enabled segments, on the fixtures and on real logs. A second golden set (`-PgoldenMatchLog`) checks a 2026 championship elimination match that another team published under the MIT license.

### Documentation
- `doc/STANDALONE.md` registers the server with Claude Code correctly: `claude mcp add --scope user wpilog -- ~/.wpilog-mcp/bin/wpilog-mcp`, or a project `.mcp.json` written with `${HOME}`. It used to say `~/.claude/settings.json`, which Claude Code does not read for MCP servers.
- `README.md` is an overview with an index of the documentation: what the server does, which install to choose, and where each topic is covered. The reference material it carried moved to the documents that own it: data types and the REV log details to `doc/TOOLS.md`, and the rules for running the extension beside the standalone install to the extension's README, which also shows its icon. Its example questions run from broad to technical, in quoted italics, and the note after them says that the depth of the analysis depends on the model and on the context it can reach beyond the log, above all the robot code that writes the entries. Links to releases go to the releases page, where pre-releases are listed, instead of to the latest release, and `doc/STANDALONE.md` says the installers skip pre-releases.
- The icon in the header of `README.md` and of the extension's README sits midway between the top of its cell and the title (it sat high), and the icon, title, and tagline are each centered by their own `align="center"`, so a viewer whose stylesheet left-aligns table cells no longer shows the icon flush left. The intro text beside the icon is vertically centered in the row, so it no longer clusters at the top with a wide margin below when the icon column is the taller one.
- `doc/TOOLS.md` and `README.md` were matched to the current code, including the examples for `compare_matches`, `can_health`, `get_ds_timeline`, and `power_analysis`.
- `doc/TOOL_RESPONSES.md` is generated from real logs by a checked-in harness, with at least one call for every tool, grouped as `get_server_guide` and `doc/TOOLS.md` group the tools.
- `doc/DEVELOPMENT.md` covers building, every test suite and how to run it, a checklist for adding or changing a tool, and releasing.
- `doc/STANDALONE.md` documents the one-line installers.
- `doc/ARCHITECTURE.md` is new. It gives the project's goals, its design principles and the failures that led to them, a map of the code, and how the server reads logs, manages memory, caches results, synchronizes REV logs, and handles concurrent clients.

## [0.8.2] - 2026-03-26

### Added
- **YAML configuration support** — `ConfigLoader` now parses `servers.yaml` with 3-layer defaults merging (top-level defaults, per-server overrides, environment variable interpolation).
- **VS Code extension** — `wpilog-analyzer` extension with `McpServerDefinitionProvider` registration and `.mcp.json` generation for Claude Code compatibility. Auto-detects WPILib IDE JDK, log directory, and bundled JAR. Extension icon added.
- **VS Code extension `.mcp.json` generation** — Extension writes `.mcp.json` on activation with all settings (log directory, team number, TBA key) so Claude Code can discover the server.
- **Standalone installer scripts** — `install.ps1` (Windows) and `install.sh` (macOS/Linux) for one-line installation outside VS Code.
- **CI/CD workflows** — GitHub Actions for build, test, and release automation.
- **`buildExtension` Gradle task** — Builds the server JAR, compiles TypeScript, and packages the `.vsix` without installing. Complements `bundleExtension` (JAR only) and `installExtension` (build + install).
- **Stress test default configuration** — Stress tests now synthesize defaults (`~/riologs`, team 2363, TBA from `TBA_API_KEY` env var) when no config file is present. No `servers.yaml` entry required.

### Fixed
- **`AnalyzeSwerveTool.poseDistance` always returned zero** — Odometry drift analysis now handles flat struct layout (`{x, y}`) from Pose decoders, not just nested `{translation: {x, y}}`.
- **MoI R² computation used inconsistent torque formula** — Residual loop now uses the same torque sign convention as the OLS fit.
- **`time_correlate` included NaN/Infinity values** — Filter now requires `Double.isFinite()`. Uses worst-of-two quality scores.
- **`FrcDomainTools.extractTranslation` only handled nested layout** — Now supports both nested `{translation: {x, y}}` and flat `{x, y}` from struct decoders.
- **CSV export struct column mismatch** — Explicit field ordering for Pose2d, Pose3d, SwerveModuleState matches header row. Generic structs use deterministic alphabetical ordering.
- **2024 Crescendo auto amp scoring** — Corrected from 5 to 2 points per game manual.
- **TBA quarterfinal match key** — Added explicit `qf` comp level handling.
- **HTTP SSE response committed before executor check** — Moved `sendResponseHeaders(200)` inside the executor task so `RejectedExecutionException` returns 503.
- **LogManager shutdown lifecycle** — `shutdownNow()` + `awaitTermination()` prevents resource leaks. Disk cache shutdown waits for sync executor.
- **Cross-correlation center lag guard** — Returns `FAILED` when center lag exceeds array bounds, preventing `ArrayIndexOutOfBoundsException`.
- **`DataQuality` gap detection** — Changed from count-based to duration-based gap ratio for confidence level calculation.
- **DS timeline linear scan** — Replaced with binary search (`findFirstIndexAtOrAfter`) for teleop deferred emit.
- **Vision quality fallback** — Falls back to pose entry quality when no target entries found.
- **Overshoot detection absolute minimum** — Added `Math.01` minimum threshold to prevent false positives near zero setpoints.
- **Median calculation for even-length arrays** — Now averages two middle elements.
- **Battery voltage off-by-one** — Changed `voltageValues.size() - 10` to `voltageValues.size() - 1`.
- **`TbaClient.apiKey` not volatile** — Added `volatile` for safe publication to HTTP handler threads.
- **`health_check` misleading field name** — Renamed `cache_memory_mb` to `jvm_heap_used_mb` to accurately reflect that it measures total JVM heap, not cache-specific memory.
- **Main.java help text** — Changed stale `servers.json` reference to `servers.yaml`.
- **CHANGELOG gap threshold** — Corrected "3x-median" to "5x-median" to match code.
- **Launcher scripts hardcoded WPILib year** — Now dynamically scan for latest installed year.
- **Unix launcher missing JAVA_HOME fallback** — Added between WPILib scan and bare `java`.
- **CI extension compile restricted to Ubuntu** — Removed `if: matrix.os == 'ubuntu-latest'` from Node.js/extension steps.
- **`ExportTools` missing parameter validation** — `export_csv` now uses `getRequiredString()` for the `name` parameter.
- **Correlation guidance language** — Softened wording for moderate correlations.
- **CHANGELOG `WPILOG_BIND`** — Corrected to `WPILOG_HTTP_BIND`.

### Changed
- **Documentation restructured** — README slimmed to focus on installation and features. Detailed docs split into `vscode-extension/README.md` (extension settings, upgrading, uninstalling), `doc/STANDALONE.md` (standalone install, configuration, Docker), and `doc/DEVELOPMENT.md` (building, project structure, contributing).
- **Installation section leads README** — VS Code extension and standalone install presented as two equal paths with links to dedicated docs. Notes that both can coexist independently.
- **AI model capability caveats** — Added notes across documentation that analysis quality depends on the AI model used.
- **Default team number** — Changed from 0 to 2363 in generated config templates, extension defaults, and stress test fallbacks.
- **TOOLS.md confidence levels** — Fixed "moderate" to "medium", added missing "insufficient" level, corrected `servers.json` to `servers.yaml`.
- **`analyze_can_bus` categorization** — Moved from Robot Analysis to FRC Domain in README tool table to match code module.
- **Stress test output** — SLF4J logging reduced from `debug` to `warn`, JUnit output uses compact `tree` mode, stdout/stderr forwarded to console.

## [0.8.1] - 2026-03-24

### Added
- **Configurable HTTP bind address** — New `bind` config field / `-bind` CLI flag / `WPILOG_HTTP_BIND` env var controls which network interface the HTTP transport listens on.
- **Configurable endpoint path** — New `endpoint` config field allows customizing the HTTP endpoint path.
- **Origin allowlist** — New `origins` config field restricts CORS access to a configurable list of allowed origins.

## [0.8.0] - 2026-03-24

### Added
- **Lazy on-demand log parsing** — Log files are now memory-mapped and scanned in a single pass without decoding values. Entry metadata and lightweight `DataLogRecord` references are stashed per entry. Values are decoded on demand when tools access specific entries, and cached in a Caffeine weight-based LRU cache. This dramatically reduces memory usage and eliminates "file too large" errors for most files.
- **Caffeine dependency** — `com.github.ben-manes.caffeine:caffeine:3.1.8` for per-entry value caching with weight-based eviction.
- **`LogData` interface** — Common interface for `ParsedLog` (eager) and `LazyParsedLog` (lazy). All tools now accept `LogData` instead of `ParsedLog`.
- **`EntryDecoder`** — Extracted value decoding logic from `LogParser` into a standalone utility, shared by both eager parsing and lazy on-demand decoding.
- **Aggressive log eviction** — When loading a large file, the server now evicts cached logs to free memory rather than failing immediately.
- **Heap-pressure-based cache eviction** — In-memory log cache now evicts automatically when free heap drops below 15% of max. No configuration needed — users control capacity via `WPILOG_MAX_HEAP` environment variable (default 4g).

### Changed (Breaking)
- **Tool signatures** — `executeWithLog(ParsedLog log, ...)` changed to `executeWithLog(LogData log, ...)` across all 35 tool subclasses. `ToolBase` helper methods (`requireEntry`, `findEntryByPattern`, etc.) updated similarly.
- **`LogCache` rewritten with Caffeine** — The home-brewed `LinkedHashMap` + `ReentrantReadWriteLock` log cache is replaced by Caffeine with `expireAfterAccess` for idle eviction, synchronous removal listener for `LazyParsedLog.close()`, and `policy().expireAfterAccess().oldest()` for LRU eviction under heap pressure. Thread safety is handled by Caffeine internally.
- **`LogCache`, `LogManager`, sync classes** — All internal APIs updated from `ParsedLog` to `LogData`.
- **Wpilog disk cache bypassed** — `DiskCache` for wpilog files is no longer used in the load path (lazy loading from memory-mapped files is fast enough). `SyncDiskCache` for revlog sync results is still active.

### Fixed
- **Correlation near-zero denominator** — `time_correlate` now uses magnitude check (`< 1e-20`) instead of exact-zero check for variance, preventing silent clamping to ±1.0 on near-constant signals.
- **OLS singularity threshold** — `moi_regression` adds an absolute floor to the determinant check, preventing numerically unstable solutions on tiny datasets.
- **System.gc() in eviction loop** — Moved from per-iteration to a single call after the eviction loop completes, reducing GC pause latency during large file loads.
- **IPv6 loopback CORS** — `HttpTransport.isAllowedOrigin()` now accepts `[::1]` in addition to `localhost` and `127.0.0.1`.
- **Swerve angle extraction** — `analyze_swerve` now falls back to `radians` field when extracting angles from nested Rotation2d maps, in addition to `value`.
- **Percentile bounds validation** — `ToolUtils.percentile()` now throws `IllegalArgumentException` for values outside [0.0, 1.0].
- **Stale concurrency warnings** — Removed "NOT SAFE FOR CONCURRENT USE" warnings from `get_server_guide` tool output, TOOLS.md, and test suite. The server is thread-safe (Caffeine caches, ConcurrentHashMap, per-path load locking). Replaced with accurate `architecture` section describing thread safety and transport options.
- **Stale `health_check` documentation** — TOOLS.md now documents the actual response format (`jvm_memory`, `cache_memory_mb`, `disk_cache`) instead of the removed `memory_stats`/`estimatedMemoryMb`/`estimationAccuracy` fields.
- **Dead code cleanup** — Removed unused `LogManager` methods (`getCacheStats`, `getMemoryStats`, `setAutoSyncEnabled`, `isAutoSyncEnabled`, `testGetLoadedLogCount`), orphaned Javadoc, and duplicate comment blocks.

### Removed
- **`maxlogs` and `maxmemory` configuration options** — Removed from `servers.json`, CLI flags (`-maxlogs`, `-maxmemory`), and environment variables (`WPILOG_MAX_LOGS`, `WPILOG_MAX_MEMORY`). Memory management is now fully automatic via heap-pressure-based eviction. Users who need more cache capacity should increase `WPILOG_MAX_HEAP`.
- **`MemoryEstimator`** — Removed. Memory-based eviction is now driven by JVM heap pressure, not per-log estimation.
- **`LogIndex`** — Replaced by `LazyParsedLog` which builds its index during construction.
- **File-size-to-heap check** — The 8x multiplier guard is replaced by aggressive eviction + lazy loading.

## [0.7.2] - 2026-03-24

### Added
- **REV native binary format support** — `RevLogParser` now auto-detects and parses REV's proprietary `.revlog` binary format (in addition to WPILOG-format revlogs). Variable-length record parsing per the `REVrobotics/node-revlog-converter` specification. CAN ID translation maps native format IDs to DBC-compatible arbitration IDs. Device type detection for SPARK MAX, Servo Hub, and MAXSpline Encoder. Composite device keying prevents collisions when different device types share the same CAN ID.
- **Sync disk cache** — Caches parsed revlog data and cross-correlation sync results to disk (`SyncDiskCache`, `SyncCacheSerializer`). Reloading the same wpilog+revlog pair skips both parsing and correlation. Cache keyed by combined content fingerprints of both files.
- **Time-based revlog discovery** — Revlog files are now discovered via time overlap matching across the entire log directory tree (configurable scan depth, default 5), not just flat same-directory scanning. Supports multiple timestamp sources: systemTime entries, filename timestamps, and file modification time fallback. Files in sibling directories with unrelated names are correctly matched.
- **Configurable directory scan depth** — New `scandepth` config field / `-scandepth` CLI flag / `WPILOG_SCAN_DEPTH` env var controls how deep the server scans for log and revlog files. Default changed from 3 to 5.
- **`/health` HTTP endpoint** — Dedicated health check endpoint that returns immediately, replacing SSE-based health checks that consumed thread pool threads.
- **SSE thread pool separation** — SSE streams now run on a dedicated `CachedThreadPool` instead of the main request handler pool, preventing thread pool starvation.
- **Stdio shutdown hook** — Stdio mode now registers a shutdown hook for clean `DiskCache` termination.
- **Export directory configuration** — New `-exportdir` CLI flag / `WPILOG_EXPORT_DIR` env var / `"exportdir"` config field restricts CSV exports to a single configured directory. Default: `{tmpdir}/wpilog-export/`. Replaces the previous three-tier whitelist (log dir, temp dir, log parent dir).
- **TBA event code validation** — When a match lookup fails, the tool now validates the event code against TBA and searches for similar events by name/city/code. Provides "Did you mean?" suggestions when the event code doesn't match any TBA event.
- **No-args default startup** — Running `wpilog-mcp` with no arguments now starts the `"default"` server configuration from `servers.json`.
- **Launcher script heap auto-sizing** — The launcher script uses `WPILOG_MAX_HEAP` env var (default 4g) for JVM `-Xmx`.
- **`get_revlog_data` guardrails** — Now includes `DataQuality` and `AnalysisDirectives` when `include_stats` is true, consistent with other analysis tools.

### Fixed
- **P-value computation** — Cornish-Fisher expansion now uses higher-order correction terms (A&S 26.7.5) for improved accuracy at df=13–30. Reordered n<15 NaN guard before |r|≥1.0 check so `computePValue(1.0, 5)` correctly returns NaN instead of 0.0.
- **CSV export escaping** — String values containing commas, quotes, or newlines are now properly escaped per RFC 4180.
- **`compare_matches` crash** — No longer crashes on same-path input (`Map.of` duplicate key). Uses `LinkedHashMap` for deterministic output order.
- **Rate-of-change non-finite guard** — Both central-difference and windowed branches now filter non-finite derivatives.
- **Per-path load lock race** — Lock entries are no longer removed after use, preventing a race where concurrent threads could parse the same file on different lock objects.
- **DiskCache cleanup over-counting** — Stale files deleted during cleanup are no longer counted toward total size.
- **ContentFingerprint filename length** — Cache filenames now use 32 hex chars (128 bits) instead of 16 for better collision resistance.
- **CAN bus timestamp assumption** — `analyze_can_bus` uses `continue` instead of `break` for non-monotonic timestamps.
- **Battery report sentinel values** — `generate_report` no longer reports `Double.MAX_VALUE` when battery entry has no numeric data.
- **`SessionManager.cleanupExpired` TOCTOU** — Uses atomic `removeIf` instead of collect-then-remove.
- **`getValueAtTimeZoh` performance** — Now uses O(log n) binary search instead of O(n) linear scan.
- **SSE CORS headers** — All HTTP endpoints (including SSE and error responses) now include CORS headers.
- **PID file atomicity** — Uses `CREATE_NEW` for atomic creation plus `isAlreadyRunning` pre-check before spawning daemons.
- **`SearchEntriesTool` null params** — Added `isJsonNull()` checks for optional parameters.
- **`list_entries` case sensitivity** — Pattern filter is now case-insensitive, matching `search_entries` behavior.
- **Overshoot near-zero threshold** — Uses `Math.abs(lastSetpoint) > 0.001` instead of exact zero comparison.
- **Odometry drift scan performance** — Replaced O(n*m) linear scan with binary search via `getValueAtTimeZoh`.
- **SyncCacheSerializer null round-trip** — Path and explanation fields now correctly preserve null values.
- **SyncDiskCache write deduplication** — Added in-process `writesInProgress` guard matching `DiskCache` pattern.
- **`find_peaks` NaN/Infinity** — Now filters non-finite values before peak detection, preventing missed peaks adjacent to NaN and spurious Infinity peaks.
- **`getActualPoseAtTime` performance** — Replaced O(n) linear scan with O(log n) binary search via `getValueAtTimeZoh`.
- **PID file race** — `writePidFile` now lets `FileAlreadyExistsException` propagate; `spawnDaemon` catches it and destroys the duplicate process.
- **Export symlink protection** — `isPathAllowed` now resolves the full path via `toRealPath` for existing files and rejects symlink filenames via `Files.isSymbolicLink` for new files.
- **RevLogTools stale references** — Removed references to deleted `load_log` tool, stale "active wpilog" concept, and outdated "same directory" guidance from `wait_for_sync`, `list_revlog_signals`, and `set_revlog_offset` descriptions.

### Changed
- **`list_struct_types`** — Discovery catalog entry corrected to `requiresLog: false`.
- **`get_server_guide`** — Tool count dynamically computed from catalog size. Stale "active log" references removed.
- **`estimateSeasonYear`** — Regex pattern compiled once as static field.
- **Disk cache config naming** — Renamed for consistency: `-cachedir` → `-diskcachedir`, `-nocache` → `-diskcachedisable`, `WPILOG_CACHE_DIR` → `WPILOG_DISK_CACHE_DIR`, `WPILOG_NO_CACHE` → `WPILOG_DISK_CACHE_DISABLE`. JSON config keys: `cachedir` → `diskcachedir`, `nocache` → `diskcachedisable`.
- **Install layout** — `versions/{version}/wpilog-mcp.jar` replaced with `jars/wpilog-mcp-{version}.jar`. Versioned JARs in a flat `jars/` directory with versioned launcher scripts referencing them.
- **JDK 17 idioms** — Adopted `instanceof` pattern matching (eliminated manual casts in 9 locations across 5 files), converted `ToolDependencies` from class to record.
- **Stress test configuration** — Stress tests now load from `"stresstest"` named config in `servers.json` instead of scanning MCP client configs. Three Gradle tasks: `stressTest` (both), `stdioStressTest`, `httpStressTest`.

### Documentation
- TOOLS.md updated with `path` parameter for all log-requiring tools
- TOOLS.md `compare_matches` parameters updated (requires `path`, `compare_path`, `name`)
- TOOLS.md parameter lists corrected to match actual `toolSchema()` definitions (removed phantom parameters from `analyze_can_bus`, `profile_mechanism`, `analyze_auto`, `analyze_replay_drift`, `analyze_loop_timing`)
- README rewritten: installation via `./gradlew install`, `servers.json` configuration, no-args default startup, CLI overrides, removed legacy CLI references
- WpilogTools and RevLogTools Javadoc updated with current tool sets

## [0.7.0] - 2026-03-23

### Added
- **HTTP Streamable transport** — Multi-client MCP server via `--http` flag using `com.sun.net.httpserver.HttpServer`. Session management with idle timeout and periodic cleanup. SSE keep-alive for server-initiated messages.
- **Modular MCP architecture** — Transport-independent `McpMessageHandler` router, `SessionManager`, `SessionContext` (ThreadLocal) for per-request session isolation. `ToolRegistry` shared across transports.
- **Game data files** — Bundled JSON game data for 2024 Crescendo, 2025 Reefscape, and 2026 REBUILT seasons.

### Changed (Breaking)
- **Path-per-call architecture** — All log-requiring tools now take a required `path` parameter instead of operating on a shared "active log." Each tool call is self-contained. The server auto-loads logs on first reference and auto-evicts idle logs after 30 minutes of inactivity. This eliminates the need for explicit log lifecycle management.
- **Removed 4 lifecycle tools** — `load_log`, `set_active_log`, `unload_log`, and `unload_all_logs` have been removed. Log loading is now implicit when any tool references a path. Cache management is automatic.
- **`compare_matches` parameters** — Now takes `path` and `compare_path` parameters to identify the two logs to compare, instead of iterating over loaded logs.
- **`list_entries` enhanced** — Now returns log metadata (time range, truncation status) that was previously only available via the removed `load_log` tool.
- **Session isolation improved** — With no shared "active log" state, concurrent sessions in HTTP mode are fully isolated by design.
- **Tool count**: 49 → 45 (removed 4 lifecycle tools)

### Fixed
- **`export_csv` primitive array bug** — `double[]`, `int64[]`, `float[]`, `boolean[]`, and `string[]` entries now export as indexed rows with actual values instead of Java object reference strings (`[D@...`).
- **`GameKnowledgeBase.getGame()` NPE** — No longer throws `NullPointerException` for unsupported seasons; returns null as documented.
- **P-value computation** — Completed the Abramowitz & Stegun 26.7.4 Cornish-Fisher expansion (correction term using `b` was computed but never applied). Full-precision normalCdf coefficients.
- **`get_code_metadata` crash** — Now handles entries with empty values gracefully instead of throwing NPE/IndexOutOfBoundsException.
- **Replay drift comparison** — `analyze_replay_drift` now compares by timestamp alignment (1ms tolerance) instead of array index, preventing false divergences when sample counts differ.
- **`isPathAllowed` symlink bypass** — Now resolves symlinks via `toRealPath()` to prevent symlink-based path escape in `export_csv`.
- **HttpTransport thread pool** — Changed from unbounded `newCachedThreadPool` to bounded `newFixedThreadPool` to prevent thread exhaustion.
- **HttpTransport batch session enforcement** — Batch POST without session header now correctly rejects non-initialize requests.
- **`loadLocks` race condition** — Per-path locks are no longer removed after use, preventing a narrow race where concurrent threads could synchronize on different lock objects.
- **Brownout end-of-log** — `detectVoltageEvents` now emits an event for sustained brownouts at end of log.
- **Recovery analysis sample limit** — Removed the 10,000-sample hard cap that missed events at >50Hz logging rates.
- **Season year detection** — `analyze_auto` and `get_match_phases` now estimate the season year from the log filename instead of using the system clock, fixing wrong auto duration fallbacks for prior-season logs.
- **Drift rate metric** — Renamed `drift_rate_m_per_sec` to `max_error_per_total_time` to accurately describe the metric.
- **`computeMedianOffset` overflow** — Overflow-safe median computation for epoch-scale microsecond offset pairs.
- **Vacuous test assertion** — Fixed conditional assertion in `ToolUtilsTest.lowQualityAddsWarning`.

### Changed
- **Percentile implementation** — Deduplicated from `StatisticsTools` and `FrcDomainTools` into a single canonical `ToolUtils.percentile()` method.
- **Dead code removed** — Removed unused `calculateRmseZoh` method from `ToolUtils`.
- **`DataQuality.confidenceLevel()`** — Now returns 4 levels (`high/medium/low/insufficient`) instead of 3, with "insufficient" for quality ≤ 0.2. Terminology aligned with CLAUDE.md.
- **`get_match_phases` guardrails** — Added `GUIDANCE_UNIVERSAL`, `GUIDANCE_MATCH_ANALYSIS`, `DataQuality`, and `AnalysisDirectives` to the tool description and response.
- **`generate_report` guardrails** — Added `DataQuality` and `AnalysisDirectives` when battery voltage data is available.
- **Discovery categories** — Added `list_struct_types` and `health_check` to the core category listing.

## [0.6.1] - 2026-03-23

### Added

#### Discovery Tools for LLM Agent Discoverability
- **`get_server_guide` tool** — Comprehensive overview of all server capabilities organized by category. Returns structured JSON with tool descriptions, usage examples, anti-patterns to avoid, common workflows, and critical guidance. Includes a `limitations` section warning about concurrency constraints. Call this first when starting a new analysis session.
- **`suggest_tools` tool** — Recommendation engine that suggests relevant tools for a natural language task description. Uses keyword matching and semantic understanding to recommend tools with relevance scores, anti-patterns, and suggested workflows.
- **`get_tba_match_data` tool** — Direct access to The Blue Alliance match data. Query specific match scores, win/loss status, detailed score breakdowns (autonomous points, teleop points, etc.), and alliance compositions. Use this instead of guessing match outcomes from telemetry.

#### Enhanced Tool Descriptions ("Trojan Horse" Pattern)
- **`list_available_logs`**: Now prominently mentions TBA enrichment and match score availability
- **`get_tba_status`**: Enhanced to explain TBA capabilities and direct users to get_tba_match_data
- **`get_statistics`**: Emphasizes "NEVER compute statistics manually—always use this tool"
- **`get_match_phases`**: Emphasizes "NEVER manually parse timestamps—always use this tool"
- **`time_correlate`**: Emphasizes "NEVER compute correlation manually—always use this tool"

#### Concurrency Warning and Workarounds
- **Server limitations documented** — The `get_server_guide` tool now includes a `limitations` section explicitly warning that the server is NOT SAFE FOR CONCURRENT USE. Clients must execute tool calls sequentially. The server maintains shared state (active log, log cache) that would conflict under concurrent access.
- **Multi-instance workaround** — Documented that running multiple *separate* server instances pointing to the same log directory IS safe. The disk cache uses file locking and atomic operations to prevent corruption.
- **LLM sub-agent warning** — Added explicit warning about LLM frameworks (Claude Code, AutoGPT, LangGraph, etc.) that may spawn sub-agents to parallelize work. Users must explicitly instruct agents to operate sequentially when analyzing multiple logs.

### Changed
- **Tool count**: 46 → 49 (added get_server_guide, suggest_tools, get_tba_match_data)

### Testing
- Added `DiscoveryToolsTest` with 19 tests covering get_server_guide and suggest_tools functionality
- Extended `TbaToolsLogicTest` with 5 tests for get_tba_match_data schema, description, and behavior
- All 965 tests passing

## [0.6.0] - 2026-03-21

### Added

#### Persistent Disk Cache
- **MessagePack-based parse cache** — Parsed logs are cached to disk as MessagePack binary files, avoiding expensive reparsing on server restart. Cache files are stored in the OS-appropriate application data directory (macOS: `~/Library/Application Support/wpilog-mcp/cache/`, Linux: `~/.local/share/wpilog-mcp/cache/`, Windows: `%LOCALAPPDATA%/wpilog-mcp/cache/`). Override with `-diskcachedir <path>` or `WPILOG_DISK_CACHE_DIR` env var. Disable with `-diskcachedisable`.
- **Content fingerprinting** — Cache identity is based on file content (SHA-256 of first 64 KB + last 64 KB + file size), not file path. Identical files in different directories share a single cache entry. No collisions between different files with the same name.
- **Version-aware invalidation** — Cache files store a format version number. Format changes automatically invalidate stale cache files. Fast mtime+size validation avoids recomputing fingerprints when files haven't changed.
- **Concurrent safety** — Writes use atomic rename (temp file → `Files.move` with `ATOMIC_MOVE`). Advisory file locks prevent duplicate writes from parallel server instances. Reads are lock-free.
- **Automatic cleanup** — Expired cache files (default: >30 days) and oversized caches (default: >2 GB) are cleaned up on startup. Orphaned temp files from crashed writes are removed.
- **Background save** — Cache writes happen asynchronously on a daemon thread, never blocking MCP responses.

#### Comprehensive Swerve Analysis (§3.1)
- **Wheel slip detection** — `analyze_swerve` now discovers setpoint/measured SwerveModuleState entry pairs by naming convention and computes per-module slip (|actual - commanded|), reporting max slip, average slip, slip event count, and slip rate.
- **Module synchronization analysis** — Compares steering angles across all measured modules at each timestamp. Reports desync event count, max angle deviation (rad and deg), and identifies the worst-performing module.
- **Odometry drift measurement** — Auto-discovers odometry and vision Pose2d/3d entries and computes pose error over time, reporting average error, max error, and drift rate in m/s. Supports explicit entry name override via `odometry_entry` and `vision_entry` parameters.
- **New parameters**: `slip_threshold` (m/s, default 0.5), `sync_threshold_rad` (default 0.1), `odometry_entry`, `vision_entry`.
- **Graceful degradation** — Each analysis section only appears if the required entries are found. Basic per-module speed stats are always reported.

#### Data Quality Scoring Propagation (§2.1)
- **All 15 analytical tools** now include `data_quality` and `server_analysis_directives` in their responses. Previously only `get_statistics` had these fields.
- **Tools using ResponseBuilder**: `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `analyze_vision`, `profile_mechanism`, `predict_battery_health` — integrated via `.addDataQuality(quality).addDirectives(directives)`.
- **Tools using raw JsonObject**: `power_analysis`, `moi_regression`, `analyze_swerve` — integrated via new `ToolUtils.appendQualityToResult()` helper that merges with existing warnings arrays.
- **Tool-specific guidance**: Each tool adds contextual followup suggestions (e.g., "Use detect_anomalies to check for outliers" from `get_statistics`, "Use predict_battery_health for comprehensive assessment" from `power_analysis`).

#### Test Data Library (§7.2)
- **MockLogBuilder factory methods** for common test scenarios: `createCleanMatchLog()` (160s with DS, voltage, velocity, loop time), `createBrownoutMatchLog()` (voltage drops to 5.5V), `createSwerveModuleLog()` (4 modules with intentional slip/sync issues + odometry drift), `createLowQualityLog()` (gaps, NaN, low sample count), `createVisionLog()` (target flicker, pose jumps).
- **New builder helpers**: `addBooleanEntry()`, `addPeriodicEntry()` (generates from a function), `addStructEntry()` (for Map-typed values), `makePose2d()` (creates Pose2d struct maps).

#### Year-Specific Game Knowledge Base
- **`get_game_info` tool** — New tool that returns year-specific FRC game information (match timing, scoring values, field geometry, game pieces, analysis hints). Defaults to the current season. Enables LLMs to interpret log data in the context of the actual game.
- **2026 REBUILT game data** — Bundled JSON resource (`games/2026-rebuilt.json`) with complete game data sourced from the official game manual (TU17): 20s auto, 2:20 teleop with hub shift mechanics, 30s endgame, tower climbing (L1/L2/L3), FUEL scoring, ranking point thresholds (ENERGIZED 100, SUPERCHARGED 360, TRAVERSAL 50), field geometry, and analysis hints.
- **`GameKnowledgeBase`** — Singleton that loads game data from bundled resources or user-provided JSON files. Cached per season. Extensible format (format_version field) for future seasons.
- **`GameData`** — Typed accessor class over raw JSON with convenience methods for match timing, field dimensions, scoring, and analysis hints.

#### Background RevLog Processing
- **Async revlog synchronization** — `autoSyncRevLogs` now runs on a background daemon thread via `CompletableFuture`, no longer blocking the initial log load. A placeholder `SynchronizedLogs` (with 0 revlogs) is placed in the sync cache immediately so tools can detect the pending state.
- **`wait_for_sync` tool** — New tool that blocks until background synchronization completes (default timeout: 30s). Returns immediately if sync is already done or no revlogs are present.
- **`sync_in_progress` status field** — `sync_status` and `list_revlog_signals` now include a `sync_in_progress` boolean and contextual warnings when sync is still running.
- **Cancellation on eviction** — `clearAllLogs()` cancels any in-progress sync futures to avoid orphaned background work.

#### LLM Epistemological Guardrails
- **Trojan Horse tool descriptions (§6.1)** — All 20 analytical tools now embed interpretation guidance in their MCP `description()` strings. Five guidance constants in `ToolUtils` (`GUIDANCE_UNIVERSAL`, `GUIDANCE_STATISTICAL`, `GUIDANCE_POWER`, `GUIDANCE_MECHANISM`, `GUIDANCE_MATCH_ANALYSIS`) provide consistent, category-appropriate caveats about single-match limitations, sample size uncertainty, correlation-vs-causation, and alternative explanations. Informational tools (`list_entries`, `read_entry`, etc.) are unchanged.
- **Data quality metadata (§6.5)** — New `DataQuality` record computes quality metrics from any `List<TimestampedValue>`: sample count, time span, gap count/max (adaptive 5x-median threshold), NaN/Infinity count, effective sample rate, timing jitter, and a composite quality score (0.0–1.0). `ResponseBuilder.addDataQuality()` serializes these into a `data_quality` JSON object and auto-warns when score < 0.5. Integrated into `get_statistics` as reference implementation.
- **Output contextual framing (§6.2)** — New `AnalysisDirectives` class generates `server_analysis_directives` in tool responses. `fromQuality(DataQuality)` factory auto-generates guidance from detected issues (low sample count, gaps, NaN, short time span). Builder methods `addGuidance()`, `addFollowup()`, and `addSingleMatchCaveat()` allow tool-specific enrichment. `ResponseBuilder.addDirectives()` serializes into `confidence_level`, `sample_context`, `interpretation_guidance[]`, and `suggested_followup[]` fields.

### Fixed

#### Comprehensive code review remediation

**Critical & Major Fixes:**
- **Critical: Match phase timing completely rewritten** — `get_match_phases` no longer hardcodes phase durations (was using wrong values: 135s total instead of 150s). Now derives all phases from actual DriverStation mode transitions in the log, making it correct for any FRC game year. If DS data is absent, returns a warning instead of guessing.
- **Major: Cache eviction loop** — `LogCache.evictIfNeeded()` now loops until cache is within both count and memory limits, instead of evicting only a single entry per call. Prevents unbounded cache growth.
- **Major: Pre-parse eviction** — `LogManager.loadLog()` now evicts before parsing the new log, reducing peak memory usage and preventing OOM when the cache is full.
- **Major: `compare_matches` race condition** — No longer mutates the active log in a loop. Instead accesses logs directly from cache, eliminating a race condition under concurrent MCP requests.
- **Major: O(n) interpolation → O(log n)** — `getValueAtTimeLinear` now uses binary search instead of linear scan. Affects `compare_entries`, `time_correlate`, `moi_regression`, and all tools using signal interpolation.
- **Major: MoI gradient division by zero** — Numerical gradient now guards against zero dt (duplicate timestamps) by returning NaN, which is filtered by the existing isFinite check in the OLS loop.

**Minor Fixes:**
- **Rate of change average denominator** — `rate_of_change` now counts only valid (non-zero-dt) samples for the average divisor, preventing dilution from duplicate timestamps.
- **OLS determinant threshold** — `moi_regression` uses a relative threshold for singularity detection, working correctly for mechanisms with small angular velocities.
- **Symlink resolution in path validation** — `SecurityValidator` now resolves symlinks via `toRealPath()`, preventing symlink-based path traversal bypasses.
- **syncCache memory leak on eviction** — Added eviction callback from `LogCache` that cleans up corresponding `syncCache` entries when logs are evicted.
- **Brownout threshold corrected** — Default changed from 7.0V to 6.8V (actual roboRIO 1 threshold). Documentation updated for roboRIO 2 (6.3V).
- **Loop timing unit detection** — Added explicit `unit` parameter ("ms", "s", "auto"). Auto-detect uses median value instead of fragile per-sample heuristic.
- **NaN/Infinity filtering in numeric extraction** — `extractNumericData` now filters non-finite values, preventing silent corruption of statistics.
- **Initial maxTimestamp sentinel** — Changed from `Double.MIN_VALUE` (smallest positive) to `Double.NEGATIVE_INFINITY` for correctness.
- **Comprehensive exception handling** — `ToolBase.execute()` now catches all exceptions and returns error responses instead of propagating raw exceptions.
- **Memory estimation sampling** — `MemoryEstimator` now samples first, middle, and last values per entry, using the maximum to avoid underestimates for variable-size entries.

#### Prior code review follow-up fixes
- **LogManager syncRevLog TOCTOU race** — `syncRevLog` now uses `syncCache.compute()` for atomic read-modify-write on the sync cache, preventing concurrent sync requests from dropping revlog data
- **FindPeaksTool misleading parameter name** — Renamed `prominence` parameter to `min_height_diff` and output field to `height_diff`, since the calculation measures local height difference from neighbors, not true topographic prominence
- **getValueAtTimeLinear extrapolation** — `getValueAtTimeLinear` now returns `null` for timestamps outside the series range instead of holding the last value (ZOH extrapolation), preventing `compare_entries` and `time_correlate` from comparing against stale boundary values
- **LogSynchronizer timezone assumption** — Added configurable `filenameTimezone` parameter to `LogSynchronizer` constructor. The coarse offset estimation now uses this instead of always assuming `ZoneId.systemDefault()`, fixing incorrect sync when the MCP server runs in a different timezone than the PC that captured the REV log

#### Thread Safety & Correctness (code review findings)
- **Critical: LogCache read-under-write bug** — `get()` now uses `writeLock()` instead of `readLock()` for access-ordered LinkedHashMap, preventing `ConcurrentModificationException` or infinite loops during parallel MCP requests
- **LogManager TOCTOU race** — New atomic `LogCache.setActiveIfPresent()` method prevents race between `containsKey` check and `setActiveLogPath` in `setActiveLog()`
- **Anomaly detection NaN/Infinity corruption** — `detect_anomalies` tool now filters `NaN` and `Infinity` values before IQR computation, preventing silent corruption of Q1/Q3 percentiles
- **DbcSignal unsigned 64-bit overflow** — CAN signals that are unsigned and exactly 64 bits now correctly decode values with the MSB set as large positive doubles instead of negative
- **R² for no-intercept regression** — `moi_regression` tool now uses uncentered R² (`1 - SS_res / Σy²`) instead of centered R², which is mathematically invalid for the interceptless model `τ = Jα + Bω`
- **Time correlation sample rate warning** — `time_correlate` tool now warns when input signals have >10x sample rate mismatch, which can bias Pearson correlation via interpolation smoothing
- **TbaClient unbounded cache growth** — TBA API caches now evict expired entries and enforce a maximum of 200 entries per cache map, preventing unbounded memory growth in long-running servers
- **LogSynchronizer configurable parameters** — Sync constants (sample rate, search window, thresholds) are now configurable via constructor instead of hardcoded, enabling tuning for non-standard log formats
- **MoiRegression null current corruption** — `moi_regression` now skips samples where current or voltage interpolation returns null (e.g., when the current log starts later than velocity), instead of silently inserting 0.0 which corrupted the OLS fit
- **Removed System.gc() from hot paths** — Removed explicit `System.gc()` calls from `LogCache.evictLeastRecentlyUsed()` (which held the write lock) and `LogManager.loadLog()` memory estimation, eliminating unnecessary Stop-The-World GC pauses
- **LogCache volatile config fields** — `maxLoadedLogs` and `maxMemoryMb` in `LogCache` are now `volatile` to ensure cross-thread visibility when set during configuration

### Testing

#### New tests (disk cache + code review + guardrails)
- `ContentFingerprintTest` — 5 tests: same content/different paths, different content, stability, large files, filename format
- `DiskCacheSerializerTest` — 10 tests: round-trip for all value types (double, boolean, string, int64, struct/Map), multiple entries, truncation info, format version rejection, corrupt file handling, metadata-only read
- `DiskCacheTest` — 6 tests: save/load round-trip, cache miss, invalidation on modification, content-based sharing across paths, disabled cache, cleanup
- `DataQualityTest` — 12 tests: empty/null/single sample handling, gap detection (uniform vs interrupted data), NaN counting and scoring impact, quality score bounds, JSON serialization with conditional field omission
- `GetMatchPhasesToolTests` — 3 tests: DS-derived phases, missing DS data warning, non-standard game year durations
- `MoiRegressionToolTests.handlesDuplicateTimestamps` — verifies no NaN/Infinity from zero-dt gradient
- `RateOfChangeToolTests.avgRateDenominatorCountsOnlyValidSamples` — verifies correct average with duplicate timestamps
- `LogCacheTest.evictsMultipleEntriesUntilWithinCountLimit` — verifies eviction loop removes multiple entries
- `LogCacheTest.evictionCallbackIsInvokedOnEviction` — verifies syncCache cleanup callback
- `GetStatisticsToolTests.includesDataQualityAndDirectives` — verifies `data_quality` and `server_analysis_directives` in response

#### Prior tests
- Added new `RobotAnalysisToolsLogicTest` with 3 tests for `moi_regression`: missing current skip, missing voltage skip, and complete data regression
- Added 2 LogCache regression tests: eviction timing (no System.gc() in lock) and volatile field verification
- Added FindPeaksTool tests for `height_diff` output field and `min_height_diff` filtering
- Added `compare_entries` tests for overlapping/non-overlapping time ranges (no-extrapolation behavior)
- Added LogSynchronizer test for configurable timezone parameter
- **Test count**: 686 → 732

## [0.5.0] - 2026-03-20

### Added

#### REV Log (.revlog) Integration
- **RevLog parser** with DBC-based CAN signal decoding for SPARK MAX/Flex motor controllers
- **Two-phase timestamp synchronization**: coarse alignment from systemTime + fine alignment via Pearson cross-correlation
- **Clock drift compensation**: for recordings >15 minutes, estimates and corrects linear drift between FPGA and monotonic clocks
- **High-variance window search**: automatically finds the most active portion of long signals, solving the "2 minutes disabled at start" problem common in FRC matches
- **Auto-sync on load**: revlog files in the same directory as a wpilog are discovered and synchronized automatically
- **Multiple revlog support**: handles multi-bus robots (Rio + CANivore) with per-bus sync results
- **DBC hybrid loading**: embedded defaults with override chain (CLI → config dir → env var → embedded)

#### New Tools (4)
- **`list_revlog_signals`**: List available REV signals with sync status, confidence, and device metadata
- **`get_revlog_data`**: Query REV signal data with FPGA-synchronized timestamps, time filtering, and statistics
- **`sync_status`**: Detailed synchronization diagnostics including method, confidence, offset, signal pairs, and drift rate
- **`set_revlog_offset`**: Manually override automatic synchronization when it fails or produces incorrect results

#### Robustness Improvements
- **Binary parsing hardening**: malformed record recovery, negative timestamp rejection, truncated CAN frame handling, corrupt record counting/logging
- **Thread safety**: `autoSyncEnabled` is now volatile; sync cache uses `ConcurrentHashMap`
- **Centralized offset transformation**: `SynchronizedLogs` delegates to `SyncResult.toFpgaTime()` for drift-aware timestamp conversion

### Changed
- **`SyncResult`** record extended with `driftRateNanosPerSec` and `referenceTimeSec` fields for clock drift compensation
- **`isFlat` threshold** changed from `1e-10` to `1e-6` — more realistic for motor signals while still rejecting truly flat data
- **Resample limit** increased from 10,000 (100s) to 60,000 (10 min) samples to cover full FRC matches
- **Tool count**: 43 → 44

### Documentation
- **TOOLS.md**: Added comprehensive technical explanation of synchronization algorithm (two-phase alignment, cross-correlation math, drift compensation, confidence scoring)
- **README.md**: Updated REV Log Integration section with synchronization details and manual override instructions
- **CHANGELOG.md**: Added v0.5.0 release notes

### Testing
- New edge case tests: single-sample signals, zero-duration signals, long disabled periods, drift compensation math, user-provided offsets
- Updated tool count assertions for new `set_revlog_offset` tool
- Stress test updated to exercise all RevLog tools

## [0.4.1] - 2026-03-19

### Changed

#### Architecture: Tool Infrastructure Modernization
- **Created ToolBase Abstract Class**: Centralized common tool functionality eliminating 20-30% boilerplate across all tools:
  - Helper methods: `requireActiveLog()`, `requireEntry()` with "did you mean?" suggestions, `filterTimeRange()`, `inTimeRange()`, `extractNumericData()`, `findEntryByPattern()`
  - Template method pattern with automatic `IllegalArgumentException` → error response conversion
  - Fluent response builders: `success()` and `error()` for standardized responses
- **Created LogRequiringTool Specialized Base**: Abstract class for the 90% of tools requiring an active log
  - Guarantees non-null log parameter to `executeWithLog()`
  - Automatic "no log loaded" error responses
  - Eliminated ~40 duplicate log acquisition checks across codebase
- **Migrated 18 Tools** to new infrastructure:
  - **StatisticsTools** (6 tools): `get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`
  - **QueryTools** (4 tools): `search_entries`, `get_types`, `find_condition`, `search_strings`
  - **FrcDomainTools** (8 tools): `get_ds_timeline`, `analyze_vision`, `profile_mechanism`, `analyze_auto`, `analyze_cycles`, `analyze_replay_drift`, `analyze_loop_timing`, `analyze_can_bus`
- **Benefits**:
  - Reduced duplicate code: ~40 log checks, ~20 entry retrievals, ~8 helper method duplicates eliminated
  - Improved error messages: Automatic suggestions for misspelled entry names
  - Better testability: LogRequiringTool enables easier testing with guaranteed non-null parameters
  - Consistent error handling: All IllegalArgumentExceptions automatically converted to proper error responses

### Added

#### Comprehensive Edge Case Testing (67 new tests)
- **ToolDependenciesTest** (20 tests): Dependency injection container verification
  - Factory method (`fromSingletons()`) behavior
  - Constructor with explicit/null dependencies
  - Getter consistency and immutability
  - Concurrent access patterns
- **LogRequiringToolTest** (21 tests): Automatic log checking infrastructure
  - Template method pattern verification
  - Error handling when no log loaded
  - Non-null log parameter guarantee
  - Exception propagation and conversion
- **MigratedToolsEdgeCaseTest** (26 tests): Boundary conditions for all migrated tools
  - Statistical edge cases: single data point, two points (Bessel's correction), 10,000 points
  - Numeric extremes: `Double.MAX_VALUE`, `Double.MIN_VALUE`, zero variance
  - Empty datasets and special characters in entry names
  - Concurrent tool execution (100 iterations)
  - Log switching and state transitions

### Testing
- **Test Suite Growth**: 417 → 478 tests (15% increase)
- **Pass Rate**: 100% (478/478 tests passing)
- **Coverage**: Maintained >80% code coverage
- **Edge Cases**: Comprehensive boundary condition testing ensures robustness

## [0.4.0] - 2026-03-19

### Changed

#### Architecture: LogManager Subsystem Extraction
- **Refactored LogManager** from 1438-line monolith into facade pattern with 6 specialized subsystems:
  - `LogCache`: LRU cache with thread-safe operations and eviction logic
  - `LogParser`: WPILOG file parsing delegating to struct decoder registry
  - `StructDecoderRegistry`: Extensible registry pattern replacing 500-line switch statement with Map-based decoder lookup
  - `SecurityValidator`: Path validation logic with traversal attack prevention
  - `MemoryEstimator`: Memory usage estimation for cache eviction decisions
  - `BinaryReader`: Binary reading utilities for struct decoding
- **Created 16 struct decoder classes** implementing `StructDecoder` interface for WPILib types:
  - Geometry: `Pose2dDecoder`, `Pose3dDecoder`, `Translation2dDecoder`, `Translation3dDecoder`, `Rotation2dDecoder`, `Rotation3dDecoder`, `Transform2dDecoder`, `Transform3dDecoder`, `Twist2dDecoder`, `Twist3dDecoder`
  - Kinematics: `ChassisSpeedsDecoder`, `SwerveModuleStateDecoder`, `SwerveModulePositionDecoder`, `DifferentialDriveWheelSpeedsDecoder`, `MecanumDriveWheelSpeedsDecoder`
  - Vision: `TargetObservationDecoder`, `PoseObservationDecoder`
  - Autonomous: `SwerveSampleDecoder`
- **Benefits**: Improved maintainability, extensibility for custom struct types, clearer separation of concerns, easier testing
- **Backward Compatibility**: All public APIs preserved - zero breaking changes

### Fixed
- **`list_loaded_logs`**: Now properly iterates cache and returns LoadedLogInfo records with memory estimates and active status
- **Tool Documentation**: Updated 6 tool descriptions to explicitly document expected "no data found" messages:
  - `analyze_swerve`: Documents "no swerve modules detected" message
  - `power_analysis`: Documents "no battery data found" message
  - `can_health`: Documents "no CAN data found" message
  - `get_code_metadata`: Documents "no code metadata found" message
  - `analyze_auto`: Documents "no auto period detected" message
  - `analyze_can_bus`: Documents "no CAN bus data found" message

### Added
- **Memory Monitoring**: New `getMemoryStats()` method in LogManager providing comprehensive heap statistics:
  - Estimated memory usage from cache (MB)
  - Actual JVM heap usage (used, max, free, utilization percentage)
  - Estimation accuracy ratio comparing heuristics to actual usage
  - Available via `health_check` tool for real-time monitoring
- **LogCache Iteration**: Added `getAllEntries()` method to LogCache for thread-safe cache enumeration
- **`health_check` tool enhancement**: Now includes estimation accuracy metric showing how well memory heuristics match actual heap usage

## [0.3.0] - 2026-03-19

### Added

#### New Tools (4)
- **`list_struct_types`**: Lists all supported WPILib struct types organized by category (geometry, kinematics, vision, autonomous)
- **`health_check`**: System health monitoring with JVM memory usage, cache memory estimate, loaded logs count, and TBA availability
- **`analyze_loop_timing`**: Real-time performance analysis detecting loop overruns > 20ms with jitter analysis and health assessment
- **`analyze_can_bus`**: CAN bus health monitoring with utilization analysis, TX/RX error tracking, and actionable recommendations

#### Enhanced Tools (4)
- **`profile_mechanism`**: Added stall detection (velocity < 0.01, current > threshold), settling time calculation, and overshoot percentage
- **`analyze_vision`**: Added pose jump detection to identify unreliable vision estimates that can cause odometry drift
- **`analyze_auto`**: Added path following RMSE calculation with max error tracking and typical value guidelines
- **`analyze_cycles`**: Added dead time analysis to identify idle periods between cycles with percentage of teleop

#### Core Improvements
- **Execution Time Tracking**: All tool responses now include `_execution_time_ms` field for performance monitoring
- **Intelligent Error Handling**:
  - Error classification with specific codes: `invalid_parameter` (IllegalArgumentException), `io_error` (IOException), `memory_error` (OutOfMemoryError)
  - "Did You Mean?" suggestions for misspelled tool names using Levenshtein distance algorithm
- **Logging**: Added structured logging for tool execution (success/failure) and error events
- **toLowerCase() Optimization**: Cached toLowerCase() results in hot loops for better performance

### Fixed
- **IQR Calculation**: Implemented proper linear percentile interpolation for accurate outlier detection (previously used simple array indexing)
- **Memory Estimation**: Improved accuracy by properly calculating struct array sizes and handling all data types
- **Incomplete Tool Implementations**:
  - `profile_mechanism`: Now fully implements stall detection, settling time, and overshoot calculations
  - `analyze_vision`: Now includes pose jump detection with configurable thresholds
  - `analyze_auto`: Now calculates path following RMSE between desired and actual poses
  - `analyze_cycles`: Now tracks dead time (idle periods) with configurable idle state

### Changed
- **Test Suite**: Added 22 new comprehensive unit tests covering all enhanced functionality
  - StatisticsTools: Percentile interpolation and IQR accuracy tests
  - FrcDomainTools: Stall detection, settling time, overshoot, pose jumps, path following, cycle analysis, loop timing, CAN bus tests
  - CoreTools: New tool tests for `list_struct_types` and `health_check`
  - McpServer: Error classification and "Did You Mean?" suggestion tests
- **Documentation**: Comprehensive updates to README.md and TOOLS.md with detailed usage examples, health assessment criteria, and troubleshooting guides
- **Tool Count**: Increased from 35 to 39 tools

### Performance
- Average tool execution time: 489ms (stress test with real robot logs)
- Concurrent operations: 1000 ops/sec throughput verified
- Cache eviction: LRU policy working correctly with configurable limits

## [0.2.1] - 2026-03-15

### Added
- Struct decoders for vision types (`TargetObservation`, `PoseObservation`)
- Struct decoders for autonomous types (`SwerveSample`)
- Expanded struct type support in LogManager

### Changed
- Enhanced README with additional usage examples
- Updated documentation for struct type decoding

### Fixed
- Team 2363 Triple Helix website link in README

## [0.2.0] - 2026-03-10

### Added
- **`moi_regression` tool**: Mechanism moment of inertia estimation from voltage/velocity/acceleration data
- Support for mechanism characterization and control system identification

### Changed
- Improved mechanism analysis capabilities

## [0.1.0] - 2026-03-01

### Added
- Initial release of wpilog-mcp MCP server
- 35 tools across 8 categories:
  - Core tools for log loading and entry reading
  - Multi-log management
  - Search and query tools
  - Statistical analysis (detect anomalies, find peaks, rate of change, correlation)
  - FRC-specific analysis (swerve, power, CAN health)
  - FRC domain tools (DS timeline, vision, mechanism profiling, auto, cycles, replay drift)
  - The Blue Alliance integration
  - Export tools (CSV, report generation)
- WPILib struct type decoding:
  - Geometry types (Pose2d/3d, Translation2d/3d, Rotation2d/3d, Transform2d/3d, Twist2d/3d)
  - Kinematics types (ChassisSpeeds, SwerveModuleState, SwerveModulePosition)
- LRU cache with configurable limits (by count or memory)
- TBA enrichment for match logs (scores, alliances, win/loss)
- Comprehensive test suite with unit and integration tests
- MCP protocol support via JSON-RPC over stdio

### Documentation
- Complete tool reference (TOOLS.md)
- Usage examples (EXAMPLE.md)
- Configuration guide for VS Code, Claude Code CLI, and Claude Desktop

[0.9.1]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.9.0...v0.9.1
[0.8.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.7.2...v0.8.0
[0.7.2]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.7.0...v0.7.2
[0.7.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.6.1...v0.7.0
[0.6.1]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/releases/tag/v0.1.0
