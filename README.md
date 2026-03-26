# wpilog-mcp: Model Context Protocol (MCP) Server for WPILib Logs

**Ever wondered why your robot died with 30 seconds left? Or why auto worked in practice but not in competition?**

wpilog-mcp lets you ask those questions in plain English. Load your robot's telemetry logs and have a conversation with your data.  Built by [FRC Team 2363 Triple Helix](https://team2363.org) using WPILib's official `DataLogReader` for guaranteed format compatibility.

**See what's possible:** Check out the [example analyses](doc/) generated from real robot logs — in particular, the [VACHE Power Analysis](doc/VACHE_POWER_ANALYSIS.md) is a stellar demonstration of the system's strict insistence against over-interpretation, conducted using the most recent version.

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

- **Data quality scoring** — Each response includes a quality assessment based on sample count, data gaps, and timing regularity. When data quality is poor, the AI is explicitly told to reduce its confidence.
- **Epistemic guidance** — Tool descriptions and response metadata embed language like "suggests" and "may indicate" rather than "proves" or "confirms." The AI is reminded that a single match is never enough to draw definitive conclusions.
- **Primitive tool design** — Instead of a single "diagnose my robot" tool that returns a health score, the server provides building blocks (voltage stats, current stats, correlation coefficients). The AI must reason across multiple tool calls, making its logic transparent and auditable.

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

Get a free API key at [thebluealliance.com/account](https://www.thebluealliance.com/account).

When configured, the server enriches match logs with TBA data:
- **Match times** - Corrects midnight timestamps from FMS
- **Scores** - Your alliance's score and opponent's score
- **Win/Loss** - Whether your team won the match
- **Alliance** - Which alliance (red/blue) your team was on

Team number is extracted from each log file's metadata (DriverStation/FMS data), with the configured `team` value as a fallback.

This data is automatically added to `list_available_logs` output for logs that have event/match/team metadata.

## REV Log Integration

wpilog-mcp correlates `.revlog` files (generated by WPILib robot programs using REV hardware) with your WPILOG data, giving you access to high-resolution motor controller telemetry with synchronized timestamps.

**How it works:**
1. Revlog files are discovered automatically via time-based matching — they can be in the same directory, sibling directories, or anywhere within the configured log directory tree (up to the configured scan depth (default 5))
2. Reference the wpilog in any tool call — matching revlogs are discovered and synchronized automatically on first access
3. Use `sync_status` to verify synchronization confidence before relying on timestamps
4. Sync results are cached to disk — reloading the same wpilog+revlog pair skips both parsing and correlation

**Synchronization:** Timestamps are aligned using a two-phase approach:
1. **Coarse alignment** from `systemTime` entries and revlog filename timestamps (seconds-level)
2. **Fine alignment** via Pearson cross-correlation of matching signals like motor output duty cycle (millisecond-level)

For long recordings (>15 minutes), linear clock drift between the FPGA clock and the monotonic clock is estimated and compensated automatically.

The system reports confidence levels (HIGH/MEDIUM/LOW/FAILED) based on correlation strength, number of agreeing signal pairs, and inter-pair consistency. Typical accuracy at HIGH confidence is ±1–5 ms.

**If automatic sync fails**, use `set_revlog_offset` to manually provide a known offset.

**Limitations:**
- REV logs use `CLOCK_MONOTONIC` while WPILOGs use FPGA time — offset varies per boot
- Correlation requires overlapping signal variation (flat/disabled data degrades quality)
- Short logs or steady-state data may produce lower confidence synchronization

**Available data:**
- Applied output (duty cycle), velocity, position
- Bus voltage, output current, temperature
- Faults and sticky faults

See [TOOLS.md](doc/TOOLS.md#revlog-tools) for detailed tool documentation and a technical explanation of the synchronization algorithm.

## Available Tools

wpilog-mcp provides 45 tools organized into categories. All log-requiring tools take a `path` parameter — the server auto-loads logs on first reference and auto-evicts idle logs.

| Category | Tools |
|----------|-------|
| **Discovery** | `get_server_guide`, `suggest_tools` |
| **Core** | `list_available_logs`, `list_loaded_logs`, `list_entries`, `read_entry`, `get_entry_info`, `list_struct_types`, `health_check` |
| **Query** | `search_entries`, `get_types`, `find_condition`, `search_strings` |
| **Statistics** | `get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate` |
| **Robot Analysis** | `get_match_phases`, `analyze_swerve`, `power_analysis`, `can_health`, `compare_matches`, `get_code_metadata`, `moi_regression` |
| **FRC Domain** | `get_ds_timeline`, `analyze_vision`, `profile_mechanism`, `analyze_auto`, `analyze_cycles`, `analyze_replay_drift`, `analyze_loop_timing`, `predict_battery_health`, `get_game_info`, `analyze_can_bus` |
| **TBA** | `get_tba_status`, `get_tba_match_data` |
| **RevLog** | `list_revlog_signals`, `get_revlog_data`, `sync_status`, `set_revlog_offset`, `wait_for_sync` |
| **Export** | `export_csv`, `generate_report` |

**Start here:** Call `get_server_guide` first to understand what analysis capabilities are available. This prevents writing custom analysis code when a built-in tool already exists.

**Bundled game data:** 2024 Crescendo, 2025 Reefscape, 2026 REBUILT. The `get_game_info` tool returns scoring zones, match timing, and field geometry for these seasons.

## Supported Data Types

### Primitive Types
`boolean`, `int64`, `float`, `double`, `string`, `raw`, `json`, and arrays of each

### WPILib Geometry Types

| Type | Decoded Fields |
|------|----------------|
| `Pose2d` | `x`, `y`, `rotation_rad`, `rotation_deg` |
| `Pose3d` | `x`, `y`, `z`, `qw`, `qx`, `qy`, `qz` |
| `Translation2d/3d` | `x`, `y`, (`z`) |
| `Rotation2d` | `radians`, `degrees` |
| `Rotation3d` | `qw`, `qx`, `qy`, `qz` |
| `Transform2d/3d` | Same as Pose |
| `Twist2d/3d` | `dx`, `dy`, (`dz`), `dtheta`/(`rx`, `ry`, `rz`) |

### WPILib Kinematics Types

| Type | Decoded Fields |
|------|----------------|
| `ChassisSpeeds` | `vx_mps`, `vy_mps`, `omega_radps` |
| `SwerveModuleState` | `speed_mps`, `angle_rad`, `angle_deg` |
| `SwerveModulePosition` | `distance_m`, `angle_rad`, `angle_deg` |

### Vision & Autonomous Types

| Type | Decoded Fields |
|------|----------------|
| `TargetObservation` | `yaw_rad`, `yaw_deg`, `pitch_rad`, `pitch_deg`, `skew_rad`, `skew_deg`, `area`, `confidence`, `objectID` |
| `PoseObservation` | `timestamp`, `pose_x`, `pose_y`, `pose_z`, `pose_qw`, `pose_qx`, `pose_qy`, `pose_qz`, `ambiguity`, `tagCount`, `averageTagDistance`, `type` |
| `SwerveSample` | `timestamp`, `x`, `y`, `heading`, `heading_deg`, `vx`, `vy`, `omega`, `ax`, `ay`, `alpha`, `moduleForcesX[4]`, `moduleForcesY[4]` |

*Note: `PoseObservation.type` is decoded as an enum string: `MEGATAG_1`, `MEGATAG_2`, or `PHOTONVISION`.*

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
- [VAALE Event Analysis](doc/VAALE_EVENT_ANALYSIS.md) - Comprehensive event analysis from real robot logs
- [VACHE Power Analysis](doc/VACHE_POWER_ANALYSIS.md) - In-depth power & voltage analysis showcasing epistemic guardrails
- [WPILib DataLog Docs](https://docs.wpilib.org/en/stable/docs/software/telemetry/datalog.html)
- [MCP Protocol](https://modelcontextprotocol.io/)
