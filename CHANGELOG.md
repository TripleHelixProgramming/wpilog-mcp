# Changelog

All notable changes to wpilog-mcp will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
- **Launcher script heap auto-sizing** — The launcher script reads `maxmemory` from `servers.json` and sets JVM `-Xmx` to `maxmemory + 2GB` headroom. Falls back to `WPILOG_MAX_HEAP` env var or 4g default.
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
- **Data quality metadata (§6.5)** — New `DataQuality` record computes quality metrics from any `List<TimestampedValue>`: sample count, time span, gap count/max (adaptive 3x-median threshold), NaN/Infinity count, effective sample rate, timing jitter, and a composite quality score (0.0–1.0). `ResponseBuilder.addDataQuality()` serializes these into a `data_quality` JSON object and auto-warns when score < 0.5. Integrated into `get_statistics` as reference implementation.
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
