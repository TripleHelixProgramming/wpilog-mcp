# Future Enhancement Ideas

This document captures ideas for future versions of wpilog-mcp. Items are organized by theme and roughly prioritized within each section.

---

## 1. Performance & Scalability

### 1.6 FFT-Based Cross-Correlation
**Priority:** Low
**Complexity:** High

For very long recordings (>30 minutes), use FFT for O(n log n) cross-correlation instead of the current O(n*k) sliding window.

**When to use:**
- Signal length > 100,000 samples
- Search window > 10,000 samples
- Fall back to sliding window for short signals (FFT overhead not worth it)

---

## 2. Data Quality & Accuracy

### 2.2 Multi-Log Temporal Alignment
**Priority:** Low
**Complexity:** High

Align multiple logs from the same event (e.g., practice sessions) for comparative analysis.

**Use cases:**
- Compare autonomous performance across attempts
- Track mechanism tuning improvements
- Identify intermittent issues

---

## 3. FRC Domain Features

### 3.2 Autonomous Routine Library
**Priority:** Medium
**Complexity:** Medium

Build a library of autonomous routine signatures for pattern matching.

**Features:**
- Extract routine "fingerprints" from pose trajectories
- Match logged auto to known routines
- Compare execution quality to previous runs
- Detect failed/aborted routines

### 3.3 Energy Budget Analysis
**Priority:** Medium
**Complexity:** Low

Analyze battery energy consumption patterns.

**Metrics:**
- Total energy consumed (Wh) per match
- Energy by mechanism (if per-motor current available)
- Peak power events
- Regenerative braking efficiency

### 3.4 CAN Bus Diagnostics
**Priority:** Medium
**Complexity:** Medium

Enhanced CAN bus analysis. The current `can_health` tool counts CAN errors but does not distinguish between disabled-state timeouts (normal) and enabled-state errors (problematic).

**Features:**
- Distinguish disabled-state timeouts from real errors (cross-reference with robot enable state)
- Identify noisy devices by error rate
- Detect bus-off events and recovery time
- Bandwidth utilization by device category

### 3.5 Mechanism Health Tracking
**Priority:** Low
**Complexity:** High

Track mechanism health across multiple logs.

**Indicators:**
- Motor temperature trends
- Current draw for constant loads (motor wear)
- Encoder noise (bearing wear)
- Response time degradation

---

## 4. Integration & Ecosystem

### 4.1 PathPlanner/Choreo Integration
**Priority:** High
**Complexity:** Medium

Import trajectory files and compare to actual execution.

**Features:**
- Parse `.path` and `.traj` files
- Overlay planned vs. actual paths
- Calculate following error along trajectory
- Identify problematic segments

### 4.2 AdvantageScope Integration
**Priority:** Medium
**Complexity:** Medium

Deep integration with AdvantageScope for visualization of analysis results.

#### 4.2.1 Layout File Generation

Generate AdvantageScope layout files (`.json`) that pre-configure visualizations based on analysis results.

**Layout file structure** (reverse-engineered from AdvantageScope source):
```json
{
  "version": "26.0.0",
  "hubs": [{
    "x": 0, "y": 0, "width": 1200, "height": 800,
    "state": {
      "sidebar": { "width": 300, "expanded": ["/Drive", "/Power"] },
      "tabs": {
        "selected": 0,
        "tabs": [{ "type": 1, "title": "Voltage", "controller": {...} }]
      }
    }
  }],
  "satellites": []
}
```

**Tab types:**
| Type | Visualization | Use case |
|------|---------------|----------|
| 1 | Line Graph | Voltage, current, velocity over time |
| 3 | 2D Field | Robot poses, trajectories |
| 9 | Swerve | Module states, chassis speeds |

**Implementation:**
- `open_in_advantagescope` tool generates layout JSON based on analysis context
- Example: after `analyze_power` finds brownouts, generate layout with voltage + current graphs
- Write layout to temp file, instruct user to import via `File > Import Layout`

#### 4.2.2 Application Launch

AdvantageScope supports file associations — launching with a log file works:
```bash
open -a AdvantageScope /path/to/log.wpilog  # macOS
AdvantageScope.exe C:\path\to\log.wpilog     # Windows
```

**Current limitation:** No CLI flag to load a layout file on launch. Users must manually import.

#### 4.2.3 Runtime Control API (Not Currently Available)

AdvantageScope runs an XR server on port 8170 (`/ws` WebSocket) for VR/AR headset streaming, but this is one-way broadcast only — no general control API exists.

**Desired capabilities (would require AdvantageScope feature request or PR):**
- `POST /open?file=/path/to/log.wpilog` — open a log file
- `POST /layout` — apply a layout configuration
- `POST /seek?timestamp=12345` — navigate to timestamp
- `POST /highlight?entry=/Power/Voltage&start=100&end=200` — highlight time range

**Workaround for now:** File-based handoff with user instruction:
1. Generate layout JSON to `~/.wpilog-mcp/advantagescope-layout.json`
2. Launch AdvantageScope with log file
3. Display message: "Import layout via File > Import Layout"

#### 4.2.4 Data Export Formats

Export analysis results in AdvantageScope-compatible formats:
- **MCAP** — Native AdvantageScope format, includes metadata
- **Annotated wpilog** — Original log with analysis markers as new entries
- **JSON** — For web-based visualization or external tools

### 4.3 Scouting System Integration
**Priority:** Low
**Complexity:** Medium

Connect to team scouting databases.

**Features:**
- Import match schedule and alliance data
- Correlate robot performance with match outcomes
- Export performance metrics to scouting DB
- Compare to alliance partner/opponent data

---

## 5. User Experience

### 5.1 Analysis Presets
**Priority:** Medium
**Complexity:** Low

Pre-configured analysis profiles for common scenarios.

**Presets:**
- `quick_check` — Battery, CAN, loop timing (30 seconds)
- `match_debrief` — Full match analysis with phase breakdown
- `auto_tuning` — Detailed autonomous performance
- `mechanism_debug` — Deep dive on specific mechanism

### 5.2 Natural Language Queries
**Priority:** Medium
**Complexity:** Low (LLM handles this)

Improve tool descriptions and error messages for LLM interaction.

**Improvements:**
- Consistent error message format with structured error codes (e.g., `ERR_NO_LOG`, `ERR_ENTRY_NOT_FOUND`, `ERR_INSUFFICIENT_DATA`) so LLMs can handle errors programmatically
- Actionable suggestions in all errors
- Example queries in tool descriptions
- Domain-specific vocabulary in responses

### 5.3 Report Generation
**Priority:** Medium
**Complexity:** Medium

Generate human-readable reports from analysis.

**Formats:**
- Markdown summary
- HTML with embedded charts
- PDF for printing

**Content:**
- Executive summary (match outcome, key issues)
- Detailed findings by category
- Recommendations prioritized by impact

### 5.4 Batch Tool Execution
**Priority:** Medium
**Complexity:** Low

Accept a list of tool calls and return all results in one response. Reduces round-trips for common multi-step workflows like "load log, get voltage stats, get current stats, correlate."

**Design:**
- `run_workflow` tool that accepts an ordered list of `{tool, arguments}` pairs
- Results returned as an array in the same order
- Early termination on error (with partial results)
- Complements §5.1 (Analysis Presets) but is more flexible

### 5.5 Entry Name Aliasing / Normalization
**Priority:** Medium
**Complexity:** Medium

FRC teams use wildly different naming conventions: `/Robot/Drive/FrontLeft/Velocity` vs `/Swerve/Module0/DriveVelocity` vs `/SmartDashboard/FL Drive Speed`. A configurable alias map (or fuzzy matching) would make tools much more usable across teams without requiring exact entry name knowledge. The existing `findEntryByPattern` is a start, but it's case-insensitive substring matching, which is coarse.

**Approach:**
- Configurable alias file mapping semantic roles to name patterns
- Fuzzy search that ranks by edit distance and structural similarity
- "Did you mean?" suggestions with confidence scores

### 5.6 Auto-Organize Log Directory
**Priority:** Medium
**Complexity:** Medium

Provide a tool to automatically organize files in the logdir into a clean directory structure. Robots generate `.wpilog`, `.revlog`, and `.hoot` files in a flat directory (or per-session subdirectories with opaque names). An auto-organize tool would sort them into a human-readable hierarchy.

**Possible structure:**
```
logdir/
├── 2026-vache/
│   ├── q42/
│   │   ├── akit_26-03-22_14-57-55_vache_q42.wpilog
│   │   └── REV_20260322_145731.revlog
│   ├── q58/
│   │   └── ...
│   └── practice/
│       └── ...
```

**Features:**
- Group by event + match type/number (parsed from filenames and log metadata)
- Co-locate matching `.wpilog` and `.revlog` files (using time-based matching)
- Optionally move or symlink (non-destructive mode creates symlinks, destructive mode moves files)
- Dry-run mode that shows what would be organized without changing anything
- Handle files with no parseable metadata (put in an `unsorted/` directory)

---

## 6. LLM Epistemological Guardrails

Taming an LLM's natural tendency toward overconfidence is one of the biggest challenges when using them for telemetry and log analysis. These strategies guide LLMs toward statistical reasoning and probabilistic answers.

### 6.3 MCP Guided Prompts
**Priority:** Medium
**Complexity:** Medium

Expose pre-built analysis workflows via MCP's `prompts/list` capability. These guide LLMs through multi-step analyses with proper statistical reasoning.

**Example prompts:**

```json
{
  "name": "systematic_brownout_analysis",
  "description": "Guided workflow for investigating power delivery issues",
  "arguments": [
    {"name": "match_count", "description": "Number of matches to analyze (min 3 recommended)"}
  ]
}
```

**Prompt content structure:**
1. **Data gathering phase** — Load multiple matches, extract relevant entries
2. **Baseline establishment** — Calculate normal operating ranges
3. **Anomaly detection** — Identify deviations with confidence intervals
4. **Correlation analysis** — Check for relationships with other variables
5. **Conclusion synthesis** — Express findings with appropriate uncertainty

**Benefits:**
- Forces multi-match analysis before conclusions
- Embeds statistical best practices in workflow
- Prevents jumping to conclusions from single data points

### 6.4 Primitive Tool Design
**Priority:** Medium
**Complexity:** Medium

Design tools that require chain-of-thought reasoning rather than providing monolithic "answers." Force LLMs to combine primitives.

**Anti-pattern (monolithic):**
```
analyze_robot_health → "Your robot has power issues (confidence: 87%)"
```

**Better pattern (primitives):**
```
get_voltage_statistics → {mean: 11.2, std: 0.8, min: 6.1, n: 4500}
get_current_statistics → {mean: 45.2, std: 12.3, max: 120.5, n: 4500}
correlate_entries → {correlation: 0.73, p_value: 0.002}
```

**The LLM must then reason:**
- "Voltage std of 0.8V suggests moderate instability"
- "Current peaks at 120A correlate with voltage drops (r=0.73)"
- "With p=0.002, this correlation is likely real, not random"
- "However, this is only one match—pattern may not persist"

**Guidelines:**
- Return raw statistics, not interpretations
- Include sample sizes and p-values where applicable
- Provide building blocks, not conclusions
- Let the LLM synthesize across multiple tool calls

**Note:** The codebase largely follows this pattern already (`get_statistics`, `rate_of_change`, `time_correlate` are primitives). However, `predict_battery_health` returns a pre-computed health score with a risk level, which is the monolithic anti-pattern. Consider whether the health score belongs in the tool output or whether the tool should return voltage stats, recovery times, and brownout events as raw data. The tension is real: teams want quick answers during competition, but monolithic scores mask uncertainty.

### 6.6 Comparative Framing
**Priority:** Medium
**Complexity:** Low

When possible, include comparative baselines to contextualize findings.

**Example:**
```json
{
  "brownout_count": 3,
  "context": {
    "typical_range": "0-2 per match for well-tuned robots",
    "concerning_threshold": ">5 per match suggests investigation",
    "your_percentile": "Higher than ~70% of logged matches"
  }
}
```

**Implementation:**
- Build baseline statistics from historical data
- Express findings relative to norms
- Avoid absolute judgments ("bad", "good")

### 6.7 Uncertainty Propagation
**Priority:** Low
**Complexity:** High

For derived calculations, propagate uncertainty through the computation chain.

**Example (moment of inertia estimation):**
```json
{
  "moment_of_inertia_kg_m2": 2.34,
  "uncertainty": {
    "standard_error": 0.18,
    "confidence_interval_95": [1.99, 2.69],
    "r_squared": 0.89,
    "degrees_of_freedom": 47,
    "sources_of_error": [
      "Motor constant calibration (±5%)",
      "Friction model approximation",
      "Sensor noise in angular velocity"
    ]
  }
}
```

**Benefits:**
- LLMs can report uncertainty bounds, not point estimates
- Identifies which inputs most affect accuracy
- Enables proper "I don't know" responses

---

## 7. Developer Experience

### 7.1 Plugin Architecture
**Priority:** Medium
**Complexity:** High

Allow teams to add custom analysis tools.

**Plugin types:**
- Custom struct decoders (already supported)
- Custom analysis tools
- Custom data sources (e.g., team-specific CAN devices)

**Distribution:**
- JAR-based plugins
- Plugin manifest with dependencies
- Version compatibility checking

### 7.2 Comprehensive Test Data
**Priority:** High
**Complexity:** Low

Build a library of reference log files for testing.

**Categories:**
- Clean match (no issues)
- Brownout event
- CAN bus failure
- Truncated log
- High-frequency struct data
- Multi-revlog scenario

---

## 8. Operational

### 8.1 Configuration Management
*Completed in v0.8.2 — YAML config with named servers, defaults merging, env var interpolation.*

### 8.3 Graceful Degradation
*Partially completed in v0.8.0 — heap-pressure-based eviction.*

Remaining work:
- Disable memory-intensive tools when heap is low
- Warn when approaching limits
- Streaming mode for very large logs

---

## Implementation Priority Matrix

| ID | Feature | Impact | Effort | Priority |
|----|---------|--------|--------|----------|
| 4.1 | PathPlanner integration | High | Medium | **P2** |
| 4.2 | AdvantageScope integration | Medium | Medium | **P2** |
| 5.1 | Analysis presets | Medium | Low | **P2** |
| 5.4 | Batch tool execution | Medium | Low | **P2** |
| 5.5 | Entry name aliasing | Medium | Medium | **P2** |
| 6.3 | MCP guided prompts | Medium | Medium | **P2** |
| 6.4 | Primitive tool design | Medium | Medium | **P2** |
| 3.4 | CAN bus diagnostics | Medium | Medium | **P3** |
| 3.3 | Energy budget analysis | Medium | Low | **P3** |
| 3.2 | Autonomous routine library | Medium | Medium | **P3** |

---

## Notes

- All features should maintain backwards compatibility with existing tool interfaces
- Performance improvements should include before/after benchmarks
- New tools should follow the existing `ToolBase` / `LogRequiringTool` patterns
- Cache format changes require migration path or clear invalidation
