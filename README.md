<table>
<tr>
<td width="220" valign="top" align="center">
  <img src="vscode-extension/images/icon.png" alt="wpilog-mcp icon" width="140"><br>
  <h1>wpilog-mcp</h1>
  Model Context Protocol (MCP) Server for WPILib Logs
</td>
<td valign="top">

**Ever wondered why your robot died with 30 seconds left? Or why auto worked in practice but not in competition?**

wpilog-mcp lets you ask those questions in plain English. Load your robot's telemetry logs and have a conversation with your data.  Built by [FRC Team 2363 Triple Helix](https://team2363.org) using WPILib's official `DataLogReader` for guaranteed format compatibility.

**See what's possible:** Check out the [example analyses](doc/) generated from real robot logs — in particular, the [VACHE Power Analysis](doc/VACHE_POWER_ANALYSIS.md) is a stellar demonstration of the system's strict insistence against over-interpretation, conducted using the most recent version.

</td>
</tr>
</table>

## Table of Contents

- [Installation](#installation)
- [AI Semantic Processing](#ai-semantic-processing)
- [The Blue Alliance Integration](#the-blue-alliance-integration)
- [REV Log Integration](#rev-log-integration)
- [Available Tools](#available-tools)
- [Supported Data Types](#supported-data-types)
- [Troubleshooting](#troubleshooting)

---

## Installation

There are two ways to run wpilog-mcp, depending on which AI client you use. The server is designed for and tested with Claude, but should work with any MCP-capable client.

**[VS Code Extension](vscode-extension/README.md)** — Install the **WPILog Analyzer** extension and it handles everything: Java detection, server startup, and MCP registration. This is the easiest path if you use VS Code with Claude Code, Copilot, or any other MCP-compatible agent. No manual configuration needed. When used with your robot project open, the AI can cross-reference log data with your source code for richer, team-specific analysis.

**[Standalone Install](doc/STANDALONE.md)** — For MCP clients outside VS Code: Claude Desktop, Claude Code CLI, Gemini, or other MCP-compatible tools. You run `./gradlew install`, configure `servers.yaml`, and point your MCP client at the `wpilog-mcp` launcher.  The server will add its capabilities to those your tool already possesses.

Both can be installed at the same time. They run as independent server instances with separate configuration — the extension uses VS Code settings while the standalone install uses `~/.wpilog-mcp/servers.yaml`. Changes to one do not affect the other.

### Then Just Ask

```
Please show me our logs from the Chesapeake District event
```

```
We lost Q42 — can you look at the log and help us understand what happened?
```

```
Please walk me through the power delivery during teleop — were there any brownout concerns?
```

```
How did our four swerve modules compare in that match?
```

```
What are the scoring rules for this year's game?
```

```
Can you pull our match results from The Blue Alliance and look for trends across the event?
```

> **Note:** The depth and quality of analysis depends on the AI model you use. wpilog-mcp provides the tools and data — the model provides the reasoning. More capable models will produce more insightful analysis.

---

## AI Semantic Processing

The **Model Context Protocol (MCP)** is an open standard that enables AI assistants (like Claude) to learn about and use tools that provide access to specialized knowledge.

This project provides an **MCP Server**, which acts as a Rosetta stone for decoding the meaning of data in WPILOG files.

Unlike traditional log viewers (like AdvantageScope) which require you to know exactly what to look for, **wpilog-mcp** allows you to ask high-level engineering and strategic questions. The AI doesn't just "query" data; it **hypothesizes, investigates, and synthesizes**.

The server provides the tools — the quality of analysis depends on the AI model's ability to use them well. The examples below reflect what's possible with a highly capable model like Claude.

### Honest Analysis, Not Just Answers

AI models have a natural tendency to find explanations that fit the data — even when the data doesn't support a strong conclusion. wpilog-mcp is designed to work against this bias. Every tool returns **accurate, raw data** (statistics, timestamps, sample counts, p-values) rather than pre-digested conclusions. Built-in guardrails steer the AI toward honest, qualified analysis:

- **Data quality scoring** — Results that rest on statistics carry a quality assessment (`data_quality`) based on sample count, data gaps, and timing regularity, with a reason for every penalty; when data quality is poor, the AI is explicitly told to reduce its confidence. Tools that report discrete events or counts (`find_condition`, `can_health`, `analyze_can_bus`, `analyze_auto`, ...) carry none — an observed event needs no statistic. [`doc/TOOLS.md`](doc/TOOLS.md#data_quality) lists which tools carry it.
- **Epistemic guidance** — Tool descriptions and response metadata embed language like "suggests" and "may indicate" rather than "proves" or "confirms." The AI is reminded that a single match is never enough to draw definitive conclusions.
- **Primitive tool design** — Instead of a single "diagnose my robot" tool that returns a health score, the server provides building blocks (voltage stats, current stats, correlation coefficients). The AI must reason across multiple tool calls, making its logic transparent and auditable.
- **Server-level reasoning guidance** — On connect, the server sends MCP `instructions` that clients such as Claude Code and VS Code place in the model's system prompt: verify that the event in the question actually happened, never name an entry or quote a number that no tool returned, test a proposed cause against a rival hypothesis, and state observed events plainly while labeling inferred causes as hypotheses. `get_server_guide` returns the full `analysis_principles` — the method, confidence calibration, and a catalogue of confabulation traps — for clients that don't forward instructions.

The goal: when you ask "why did we lose Q68?", you get analysis grounded in what the data actually shows — with appropriate caveats about what it doesn't.

### Example Scenarios

#### 1. The Autonomous "Post-Match Pit Boss"
**Prompt:** *"We just finished Q68 and the drivers said the robot 'stuttered' during teleop. Investigate the log and tell the pit crew exactly what to check."*

*   **The AI's Reasoning:** Claude will load the log, scan the `get_ds_timeline` for brownout events, use `power_analysis` to find which motor controller had the highest current spike at that exact timestamp, and check `can_health` for timeouts.
*   **The Result:** *"I found a BROWNOUT_START at 42.5s. During this time, the 'Intake/Roller' current spiked to 60A while velocity was zero, suggesting a mechanical jam. Check the intake for debris or a bent mounting bracket."*

#### 2. Strategic "Cycle Time" Optimization
**Prompt:** *"Compare our cycle times in Q74 vs Q68. Why were we slower in the second half of Q68?"*

*   **The AI's Reasoning:** Claude will pull match results from TBA to see the scores, use `analyze_cycles` to calculate state-based efficiency, and correlate "dead time" with robot position data.
*   **The Result:** *"Your scoring cycles in Q74 averaged 8.2s. In Q68, they slowed to 12.5s after the 60-second mark. I noticed that during those slower cycles, the robot was taking a much longer path around the 'Stage' obstacle—check if your autonomous path-finding or driver path was blocked."*

#### 3. Control Theory "Tuning Audit"
**Prompt:** *"Look at our swerve drive performance in the last match. Is our steering PID too aggressive? Look for oscillation."*

*   **The AI's Reasoning:** Claude will use `analyze_swerve` to identify the modules, call `get_statistics` on the steering error, and run `find_peaks` to look for high-frequency oscillations in the `AppliedVolts`.
*   **The Result:** *"The Back-Left module is showing a 0.15s oscillation period in steering position while the robot is at a standstill. This suggests your P gain is slightly too high or your D gain is insufficient for the new modules."*

## The Blue Alliance Integration

Get a free API key at [thebluealliance.com/account](https://www.thebluealliance.com/account). In VS Code, run **WPILog Analyzer: Set The Blue Alliance API Key** (the key is kept in VS Code's secret storage); standalone, set `TBA_API_KEY` (see [doc/STANDALONE.md](doc/STANDALONE.md)).

When configured, the server enriches match logs with TBA data:
- **Match times** - Corrects midnight timestamps from FMS
- **Scores** - Your alliance's score and opponent's score
- **Win/Loss** - Whether your team won the match
- **Alliance** - Which alliance (red/blue) your team was on

Team number is extracted from each log file's metadata (DriverStation/FMS data), with the configured `team` value as a fallback.

This data is automatically added to `list_available_logs` output for logs that have event/match/team metadata.

## REV Log Integration

wpilog-mcp correlates `.revlog` files (written by REVLib 2026 and later in robot programs using REV SPARK MAX/Flex controllers) with your WPILOG data, giving you access to high-resolution motor controller telemetry with synchronized timestamps.

**How it works:**
1. Revlog files are discovered automatically via time-based matching — they can be in the same directory, sibling directories, or anywhere within the configured log directory that holds the wpilog (up to the configured scan depth (default 5)). With several log directories configured, only the one holding the wpilog is searched, so another team's REV logs from the same event are never matched to yours
2. Reference the wpilog in any tool call — matching revlogs are discovered and synchronized automatically on first access
3. Use `sync_status` to verify synchronization confidence before relying on timestamps
4. Sync results are cached to disk — reloading the same wpilog+revlog pair skips both parsing and correlation

**Synchronization:** Timestamps are aligned using a two-phase approach:
1. **Coarse alignment** from the wpilog's wall clock (`systemTime` or AdvantageKit's `EpochTimeMicros`) and the revlog's filename time, read in the zone the wpilog's own filename shows (seconds-level, and off by as much as the roboRIO's clock was when the file was named)
2. **Fine alignment** via Pearson cross-correlation of signals both logs record — a SPARK's applied output, velocity, current, or bus voltage against the robot code's own entries; names only nominate candidate pairs, and correlation chooses among them (millisecond-level)

For long recordings (>15 minutes), linear clock drift between the FPGA clock and the monotonic clock is estimated and compensated automatically.

The system reports confidence levels (HIGH/MEDIUM/LOW/FAILED) based on correlation strength, number of agreeing signal pairs, and inter-pair consistency; a level never claims more agreement than the pairs show (HIGH means two or more pairs within 5 ms), and `sync_status` gives the pairs' offset range.

**If automatic sync fails**, use `set_revlog_offset` to manually provide a known offset.

**Limitations:**
- REV logs timestamp frames on the recording device's clock while WPILOGs use FPGA time; the offset is measured, never assumed
- Correlation requires overlapping signal variation (flat/disabled data degrades quality)
- Short logs or steady-state data may produce lower confidence synchronization

**Available data** (decoded per REV's published SPARK frame specification, firmware 25+):
- Applied output (duty cycle), bus voltage, output current, motor temperature, limit switches, inversion
- Each fault and warning, and its sticky version
- Velocity and position (RPM and rotations unless a conversion factor is configured), and analog, alternate/external, and duty-cycle encoder data when logged
- Closed-loop setpoint, I accumulator, and MAXMotion setpoints when logged

See [TOOLS.md](doc/TOOLS.md#revlog-tools) for detailed tool documentation and a technical explanation of the synchronization algorithm.

## Available Tools

wpilog-mcp provides 49 tools organized into categories. All log-requiring tools take a `path` parameter — the server auto-loads logs on first reference and auto-evicts idle logs.

| Category | Tools |
|----------|-------|
| **Discovery** | `get_server_guide`, `suggest_tools` |
| **Core** | `list_available_logs`, `list_loaded_logs`, `list_entries`, `read_entry`, `get_entry_info`, `list_struct_types`, `resolve_signals`, `health_check` |
| **Query** | `search_entries`, `get_types`, `find_condition`, `search_strings` |
| **Statistics** | `get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `align_entries` |
| **Robot Analysis** | `get_match_phases`, `analyze_swerve`, `power_analysis`, `can_health`, `analyze_can_bus`, `compare_matches`, `get_code_metadata`, `moi_regression` |
| **FRC Domain** | `get_ds_timeline`, `analyze_vision`, `compare_poses`, `pose_corrections`, `profile_mechanism`, `analyze_auto`, `analyze_cycles`, `analyze_replay_drift`, `analyze_loop_timing`, `predict_battery_health`, `get_game_info` |
| **TBA** | `get_tba_status`, `get_tba_match_data` |
| **RevLog** | `list_revlog_signals`, `get_revlog_data`, `sync_status`, `set_revlog_offset`, `wait_for_sync` |
| **Export** | `export_csv`, `generate_report` |

**Start here:** Call `get_server_guide` first to understand what analysis capabilities are available. This prevents writing custom analysis code when a built-in tool already exists.

**Bundled game data:** 2024 CRESCENDO, 2025 REEFSCAPE, 2026 REBUILT, transcribed from each season's final game manual. The `get_game_info` tool returns scoring values, ranking-point thresholds by event tier, match timing, field geometry, and robot limits for these seasons, labelled with the manual revision they came from (`manual_version`) and `source: bundled knowledge base, not the log`.

## Supported Data Types

### Primitive Types
`boolean`, `int64`, `float`, `double`, `string`, `raw`, `json`, and arrays of each

### Structs

Struct entries (`struct:Name` and `struct:Name[]`) are decoded from the schema each log records for its struct types (`/.schema/struct:Name`), so any struct decodes — WPILib geometry and kinematics, vendor structs, and a team's own, including nested structs, fixed-size arrays, enums, and bit-fields. A team that edits a template struct (adding a field to `PoseObservation`, say) gets its own layout decoded, not the template's.

Decoded values keep the schema's field names and nesting:

| Schema | Decoded value |
|--------|---------------|
| `Pose2d` | `{"translation": {"x", "y"}, "rotation": {"value", "_derived": {"degrees"}}}` |
| `Pose3d` | `{"translation": {"x", "y", "z"}, "rotation": {"q": {"w", "x", "y", "z"}, "_derived": {"roll", "pitch", "yaw", "roll_deg", "pitch_deg", "yaw_deg"}}}` |
| `SwerveModuleState` | `{"speed", "angle": {"value", "_derived": {"degrees"}}}` |
| enum field, e.g. `PoseObservation.type` | `{"value": 2, "label": "PHOTONVISION"}` |

Numeric tools (`get_statistics`, `find_condition`, `compare_entries`, and the rest) read struct fields and array elements by path — `/RealOutputs/Drive/Pose.translation.x`, `/PowerDistribution/ChannelCurrent[3]`, `/Vision/Camera0/PoseObservations[0].tagCount` — and unwrap known angles; `get_entry_info` lists an entry's numeric fields.

`_derived` values are computed from WPILib's `Rotation2d` and `Rotation3d` (only when the log's schema for them is WPILib's). When a log records no schema for a struct type, WPILib's own schema is used for WPILib types, and a template layout for AdvantageKit vision's `PoseObservation` and `TargetObservation` and Choreo's `SwerveSample`; `list_struct_types` and `get_entry_info` say which source each type used. A record whose size does not fit its schema is not decoded, and tools that read the entry say how many records failed and why.

## Troubleshooting

- **VS Code extension issues** — See the [extension README](vscode-extension/README.md#troubleshooting)
- **Standalone install issues** — See [doc/STANDALONE.md](doc/STANDALONE.md#troubleshooting)
- **Log files show as corrupted** — Truncated logs (from robot power loss) are handled gracefully. The server recovers as much data as possible and marks the log as truncated.
- **Out of memory with large logs** — Increase heap size. Extension: set `wpilog-mcp.maxHeap` to `8g`. Standalone: set `WPILOG_MAX_HEAP=8g`.

## License

MIT License - see [LICENSE](LICENSE)

## Acknowledgments

- [WPILib](https://github.com/wpilibsuite/allwpilib) for the DataLog format
- [AdvantageKit](https://github.com/Mechanical-Advantage/AdvantageKit) for pioneering FRC replay logging
- [Anthropic](https://anthropic.com) for MCP and Claude
- [FRC Team 2363 Triple Helix](https://team2363.org)

## See Also

- [VS Code Extension README](vscode-extension/README.md) - Extension settings, upgrading, uninstalling, troubleshooting
- [STANDALONE.md](doc/STANDALONE.md) - Standalone install, configuration, and Docker
- [DEVELOPMENT.md](doc/DEVELOPMENT.md) - Building from source, project structure, contributing
- [TOOLS.md](doc/TOOLS.md) - Complete tool reference
- [TOOL_RESPONSES.md](doc/TOOL_RESPONSES.md) - Captured JSON responses for every tool, including the LLM guidance fields
- [VAALE Event Analysis](doc/VAALE_EVENT_ANALYSIS.md) - Comprehensive event analysis from real robot logs
- [VACHE Power Analysis](doc/VACHE_POWER_ANALYSIS.md) - In-depth power & voltage analysis showcasing epistemic guardrails
- [WPILib DataLog Docs](https://docs.wpilib.org/en/stable/docs/software/telemetry/datalog.html)
- [MCP Protocol](https://modelcontextprotocol.io/)
