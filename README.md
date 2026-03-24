# wpilog-mcp: Model Context Protocol (MCP) Server for WPILib Logs

**Ever wondered why your robot died with 30 seconds left? Or why auto worked in practice but not in competition?**

wpilog-mcp lets you ask those questions in plain English. Load your robot's telemetry logs and have a conversation with your data.

### What is MCP?

The **Model Context Protocol (MCP)** is an open standard that enables AI assistants (like Claude) to securely access your local data and tools. 

This project provides an **MCP Server**. Once configured, it acts as a "bridge" that gives your AI assistant the specific tools needed to read WPILOG files, analyze swerve performance, detect brownouts, and even pull match results from The Blue Alliance—all through a natural conversation.

---

## The Power of AI Reasoning

Unlike traditional log viewers (like AdvantageScope) which require you to know exactly what to look for, **wpilog-mcp** allows you to ask high-level engineering and strategic questions. The AI doesn't just "query" data; it **hypothesizes, investigates, and synthesizes**.

### 1. The Autonomous "Post-Match Pit Boss"
**Prompt:** *"We just finished Q68 and the drivers said the robot 'stuttered' during teleop. Investigate the log and tell the pit crew exactly what to check."*

*   **The AI's Reasoning:** Claude will load the log, scan the `get_ds_timeline` for brownout events, use `power_analysis` to find which motor controller had the highest current spike at that exact timestamp, and check `can_health` for timeouts.
*   **The Result:** *"I found a BROWNOUT_START at 42.5s. During this time, the 'Intake/Roller' current spiked to 60A while velocity was zero, suggesting a mechanical jam. Check the intake for debris or a bent mounting bracket."*

### 2. Strategic "Cycle Time" Optimization
**Prompt:** *"Compare our cycle times in Q74 vs Q68. Why were we slower in the second half of Q68?"*

*   **The AI's Reasoning:** Claude will pull match results from TBA to see the scores, use `analyze_cycles` to calculate state-based efficiency, and correlate "dead time" with robot position data.
*   **The Result:** *"Your scoring cycles in Q74 averaged 8.2s. In Q68, they slowed to 12.5s after the 60-second mark. I noticed that during those slower cycles, the robot was taking a much longer path around the 'Stage' obstacle—check if your autonomous path-finding or driver path was blocked."*

### 3. Control Theory "Tuning Audit"
**Prompt:** *"Look at our swerve drive performance in the last match. Is our steering PID too aggressive? Look for oscillation."*

*   **The AI's Reasoning:** Claude will use `analyze_swerve` to identify the modules, call `get_statistics` on the steering error, and run `find_peaks` to look for high-frequency oscillations in the `AppliedVolts`.
*   **The Result:** *"The Back-Left module is showing a 0.15s oscillation period in steering position while the robot is at a standstill. This suggests your P gain is slightly too high or your D gain is insufficient for the new modules."*

---

Built by [FRC Team 2363 Triple Helix](https://team2363.org) using WPILib's official `DataLogReader` for guaranteed format compatibility.

**See what's possible:** [Example Analysis Report](EXAMPLE.md) - A complete match analysis generated from real robot logs.

## Quick Start

### 1. Build and Install

Built for JDK 17 binary compatibility. It is recommended to use the [WPILib JDK](https://docs.wpilib.org/en/stable/docs/zero-to-robot/step-2/wpilib-setup.html), which the build tool attempts to locate automatically.

```bash
git clone https://github.com/TripleHelixProgramming/wpilog-mcp.git
cd wpilog-mcp
./gradlew install
```

This installs to `~/.wpilog-mcp/`:
```
~/.wpilog-mcp/
├── jars/wpilog-mcp-{version}.jar   # versioned JAR
├── bin/
│   ├── wpilog-mcp-{version}.sh     # versioned launcher
│   └── wpilog-mcp                  # symlink to current version
└── servers.json                     # server configurations
```

Add to your shell profile (`~/.zshrc` or `~/.bashrc`):
```bash
export PATH="${HOME}/.wpilog-mcp/bin:$PATH"
```

### 2. Configure

Edit `~/.wpilog-mcp/servers.json` (created by the installer with defaults):

```json
{
  "defaults": {
    "team": 2363,
    "maxlogs": 10,
    "maxmemory": 2048
  },
  "servers": {
    "default": {
      "logdir": "~/wpilib/logs",
      "transport": "stdio"
    },
    "http": {
      "logdir": "~/wpilib/logs",
      "transport": "http",
      "port": 2363
    }
  }
}
```

The `defaults` section is inherited by all server configs. Per-server values override defaults. Environment variable references (`${TBA_API_KEY}`) and tilde paths (`~/...`) are expanded automatically.

Then configure your MCP client to use the installed command:

| Environment | Config File |
|-------------|-------------|
| **VS Code extension** | `.mcp.json` in your project folder |
| **Claude Code CLI** | `~/.claude/settings.json` |
| **Claude Desktop** | See [Claude Desktop](#claude-desktop) section |

**MCP client configuration:**
```json
{
  "mcpServers": {
    "wpilog": {
      "command": "wpilog-mcp"
    }
  }
}
```

With no arguments, the launcher starts the `"default"` server configuration from `servers.json`. It automatically locates the WPILib JDK and sets JVM heap to 4 GB (override with `WPILOG_MAX_HEAP` in `servers.json` env or shell profile).

All configuration — log directory, team number, TBA key, cache settings — lives in `servers.json`. The TBA key is optional; without it, the server works normally but without match score enrichment.

### 3. Use

Restart Claude Code. Then just ask:

```
What robot logs are available?
```

```
Analyze the qualification match 42 log and give me a summary
```

```
When did battery voltage drop below 11 volts?
```

```
Compare the commanded wheel speeds to actual wheel speeds
```

That's it! See [Usage Examples](#usage-examples) for more.

---

## Table of Contents

- [Quick Start](#quick-start)
- [Usage Examples](#usage-examples)
- [Configuration Options](#configuration-options)
- [Available Tools](#available-tools)
- [Supported Data Types](#supported-data-types)
- [Troubleshooting](#troubleshooting)
- [Development](#development)
- [Contributing](#contributing)

## Usage Examples

### Browsing Logs

```
What robot logs are available?
```

The server parses filenames like `2024vadc_qm42.wpilog` into friendly names like "VADC Qualification 42".

### Basic Analysis

```
Give me a summary of the robot log
```

```
Show me all entries related to the drivetrain
```

```
What Pose2d entries are available?
```

### Robot Position

```
Show me where the robot was at the start and end of the match
```

```
Get statistics on the robot's X position - how much did it vary?
```

### Threshold Events

```
When did battery voltage drop below 11 volts?
```

```
Find all times when robot speed exceeded 4 meters per second
```

### Console Output

```
Search for any CAN errors in the console output
```

```
Find all vision-related messages in the logs
```

### Swerve Analysis

```
Show me the swerve module states for the front left module
```

```
Compare commanded wheel speeds to actual wheel speeds
```

### AdvantageKit Replay

```
Compare /RealOutputs/Drive/Pose with /ReplayOutputs/Drive/Pose
```

### Multi-Log Analysis

```
Compare battery statistics between the practice log and the match log
```

### REV Log Analysis

```
What REV signals are available for this match?
```

```
Show me the motor temperature and current for SparkMax_1 during teleop
```

```
Compare the commanded output from the wpilog with the actual applied output from the revlog
```

## Configuration

### Server Configuration (`servers.json`)

Running `wpilog-mcp` with no arguments loads the `"default"` configuration from `servers.json`. Use `start <name>` to select a different configuration:

```bash
wpilog-mcp                          # starts the "default" config
wpilog-mcp start http               # starts the "http" config
```

Config file search order:
1. `--config <path>` (explicit override)
2. `.wpilog-mcp.json` (project-local)
3. `~/.wpilog-mcp/servers.json` (install directory)

**Config fields:**

| Field | Description | Default |
|-------|-------------|---------|
| `logdir` | Directory containing log files (scans subdirectories) | — |
| `team` | Default team number | — |
| `tba_key` | The Blue Alliance API key (supports `${TBA_API_KEY}`) | — |
| `transport` | `"stdio"` or `"http"` | `"stdio"` |
| `port` | HTTP port | `2363` |
| `maxlogs` | Max logs in memory cache | `20` |
| `maxmemory` | Max memory (MB) for log cache (alternative to `maxlogs`) | — |
| `diskcachedir` | Directory for persistent disk cache | OS default |
| `diskcachesize` | Max disk cache size (MB) | `8192` |
| `diskcachedisable` | Disable persistent disk cache | `false` |
| `exportdir` | Directory for CSV exports | `{tmpdir}/wpilog-export/` |
| `scandepth` | Max directory depth for log/revlog file scanning | `5` |
| `debug` | Enable debug logging | `false` |

String values support `${ENV_VAR}` interpolation and `~/` tilde expansion.

### Command-Line Overrides

Any config field can also be passed as a CLI flag, which takes precedence over `servers.json` and environment variables:

```bash
wpilog-mcp -logdir /media/usb/logs -debug
wpilog-mcp start http --port 9000
```

| Flag | Env Variable |
|------|-------------|
| `-logdir <path>` | `WPILOG_DIR` |
| `-team <number>` | `WPILOG_TEAM` |
| `-tba-key <key>` | `TBA_API_KEY` |
| `-maxlogs <n>` | `WPILOG_MAX_LOGS` |
| `-maxmemory <mb>` | `WPILOG_MAX_MEMORY` |
| `-diskcachedir <path>` | `WPILOG_DISK_CACHE_DIR` |
| `-diskcachesize <mb>` | `WPILOG_DISK_CACHE_SIZE` |
| `-diskcachedisable` | `WPILOG_DISK_CACHE_DISABLE` |
| `-exportdir <path>` | `WPILOG_EXPORT_DIR` |
| `-scandepth <n>` | `WPILOG_SCAN_DEPTH` |
| `--http` | `WPILOG_HTTP` |
| `--port <port>` | `WPILOG_HTTP_PORT` |
| `-debug` | `WPILOG_DEBUG` |

The launcher script sets `WPILOG_MAX_HEAP` (default `4g`) for JVM heap size. Large log files (hundreds of MB) may need `8g` or more.

**Precedence:** CLI flags > environment variables > `servers.json` per-server values > `servers.json` defaults.

**In-memory cache:** Logs are auto-loaded on first reference and auto-evicted after 30 minutes of inactivity. Use `maxlogs` for a count-based limit (default 20) or `maxmemory` for a memory-based limit. The least recently used log is evicted when the limit is reached.

**Disk cache:** Parsed wpilog files and revlog sync results are cached to disk to avoid expensive reparsing on server restart. Enabled by default. Set `diskcachedisable` to turn off.

### The Blue Alliance Integration

Get a free API key at [thebluealliance.com/account](https://www.thebluealliance.com/account).

When configured, the server enriches match logs with TBA data:
- **Match times** - Corrects midnight timestamps from FMS
- **Scores** - Your alliance's score and opponent's score
- **Win/Loss** - Whether your team won the match
- **Alliance** - Which alliance (red/blue) your team was on

Team number is extracted from each log file's metadata (DriverStation/FMS data), with the configured `team` value as a fallback.

This data is automatically added to `list_available_logs` output for logs that have event/match/team metadata.

### REV Log Integration

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

See [TOOLS.md](TOOLS.md#revlog-tools) for detailed tool documentation and a technical explanation of the synchronization algorithm.

### Claude Desktop

Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "wpilog": {
      "command": "wpilog-mcp"
    }
  }
}
```

### Other MCP Clients

This server supports **stdio transport** (default, JSON-RPC over stdin/stdout) and **HTTP Streamable transport** (multi-client).

**Stdio:**
```json
{
  "command": "wpilog-mcp",
  "transport": "stdio"
}
```

**HTTP** (runs as a background daemon):
```bash
wpilog-mcp start http
```

See [MCP Protocol](https://modelcontextprotocol.io/) for client implementations.

## Available Tools

wpilog-mcp provides 45 tools organized into categories. All log-requiring tools take a `path` parameter — the server auto-loads logs on first reference and auto-evicts idle logs.

| Category | Tools |
|----------|-------|
| **Discovery** | `get_server_guide`, `suggest_tools` |
| **Core** | `list_entries`, `read_entry`, `get_entry_info`, `search_entries`, `get_statistics`, `compare_entries`, `get_types`, `list_struct_types`, `health_check`, `get_game_info` |
| **Log Browser** | `list_available_logs`, `list_loaded_logs` |
| **Search** | `find_condition`, `search_strings` |
| **Analysis** | `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `get_match_phases` |
| **FRC-Specific** | `analyze_swerve`, `power_analysis`, `can_health`, `compare_matches`, `get_code_metadata`, `moi_regression` |
| **FRC Domain Analysis** | `get_ds_timeline`, `analyze_vision`, `profile_mechanism`, `analyze_auto`, `analyze_cycles`, `analyze_replay_drift`, `analyze_loop_timing`, `analyze_can_bus`, `predict_battery_health` |
| **TBA Integration** | `get_tba_status`, `get_tba_match_data` |
| **RevLog Integration** | `list_revlog_signals`, `get_revlog_data`, `sync_status`, `set_revlog_offset`, `wait_for_sync` |
| **Export** | `export_csv`, `generate_report` |

**Start here:** Call `get_server_guide` first to understand what analysis capabilities are available. This prevents writing custom analysis code when a built-in tool already exists.

### Key Features

- **Execution Time Tracking**: All tool responses include `_execution_time_ms` field for performance monitoring
- **Intelligent Error Handling**: Clear error messages with specific error codes and "Did You Mean?" suggestions for misspelled tool names
- **Memory Monitoring**: Real-time heap usage tracking with estimation accuracy validation via `health_check` tool
- **Improved Statistical Analysis**: IQR calculation uses proper linear percentile interpolation for accurate outlier detection
- **Enhanced Mechanism Analysis**: Stall detection, settling time, and overshoot calculations for control system tuning
- **Vision System Monitoring**: Pose jump detection to identify unreliable vision estimates
- **Loop Timing Analysis**: Detect and diagnose real-time performance issues
- **CAN Bus Health**: Monitor bus utilization and error rates
- **REV Log Integration**: Correlate high-resolution motor controller data from `.revlog` files with FPGA-timestamped telemetry

For complete tool documentation with parameters and examples, see [TOOLS.md](TOOLS.md).

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

### MCP Server Shows "Failed"

1. **Check Java version** - Requires JDK 17 binary compatibility (JDK 21 is also fine):
   ```bash
   /path/to/wpilib/2026/jdk/bin/java -version
   # Should show: openjdk version "17.x.x" or "21.x.x"
   ```

2. **Test manually**:
   ```bash
   echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | \
     wpilog-mcp
   ```

3. **Check config files**:
   - Server config: `~/.wpilog-mcp/servers.json`
   - VS Code extension: `.mcp.json` in your project folder
   - Claude Code CLI: `~/.claude/settings.json`
   - Claude Desktop: `~/Library/Application Support/Claude/claude_desktop_config.json`

4. **Restart completely** - Quit and relaunch (not just reload)

### Log Files Show as Corrupted

Truncated logs (from robot power loss) are handled gracefully - the server recovers as much data as possible and marks the log as truncated.

### Server Times Out

Usually means wrong Java version or incorrect path. Verify the JAR exists at the configured path.

## Requirements

- **JDK 17+** (WPILib 2026 JDK recommended)
- No other dependencies (WPILib libraries bundled)

WPILib JDK locations:
- **macOS**: `~/wpilib/2026/jdk/bin/java`
- **Windows**: `C:\Users\Public\wpilib\2026\jdk\bin\java.exe`
- **Linux**: `~/wpilib/2026/jdk/bin/java`

## Development

### Project Structure

```
wpilog-mcp/
├── src/main/java/org/triplehelix/wpilogmcp/
│   ├── Main.java                   # Entry point (stdio + HTTP modes)
│   ├── cache/
│   │   ├── DiskCache.java          # Persistent wpilog cache (MessagePack)
│   │   ├── SyncDiskCache.java      # Persistent revlog sync cache
│   │   ├── ContentFingerprint.java # SHA-256 content fingerprinting
│   │   └── ...                     # Serializers, metadata, directory
│   ├── config/
│   │   ├── ConfigLoader.java       # Named server config (JSON)
│   │   ├── DaemonManager.java      # HTTP daemon lifecycle
│   │   └── ServerConfig.java       # Config record
│   ├── game/
│   │   └── GameKnowledgeBase.java  # FRC game data (2024-2026)
│   ├── log/
│   │   ├── LogManager.java         # WPILOG parsing, caching, revlog sync
│   │   ├── LogDirectory.java       # Log/revlog file discovery
│   │   └── subsystems/             # Parser, cache, struct decoders
│   ├── mcp/
│   │   ├── McpMessageHandler.java  # Transport-independent router
│   │   ├── McpServer.java          # Stdio transport
│   │   ├── HttpTransport.java      # HTTP Streamable transport
│   │   ├── SessionManager.java     # Session lifecycle
│   │   └── ToolRegistry.java       # Tool registration
│   ├── revlog/
│   │   ├── RevLogParser.java       # REV log file parsing
│   │   └── dbc/                    # DBC-based CAN signal decoding
│   ├── sync/
│   │   ├── LogSynchronizer.java    # Cross-correlation timestamp alignment
│   │   ├── SynchronizedLogs.java   # Unified wpilog+revlog access
│   │   └── SyncResult.java         # Synchronization confidence metrics
│   ├── tba/
│   │   ├── TbaClient.java          # TBA API client with caching
│   │   └── TbaEnrichment.java      # Log enrichment with TBA data
│   └── tools/
│       ├── WpilogTools.java        # Tool registration
│       ├── CoreTools.java          # Core tools (8)
│       ├── QueryTools.java         # Search & query tools (4)
│       ├── StatisticsTools.java    # Statistical analysis tools (6)
│       ├── RobotAnalysisTools.java # FRC analysis tools (7)
│       ├── FrcDomainTools.java     # Advanced FRC tools (9)
│       ├── ExportTools.java        # CSV/report export (2)
│       ├── TbaTools.java           # TBA integration (2)
│       ├── RevLogTools.java        # REV log integration (5)
│       ├── DiscoveryTools.java     # LLM discoverability (2)
│       └── ToolUtils.java          # Shared utilities
├── src/test/java/                  # 1,170+ test cases
├── build.gradle                    # Build configuration
├── README.md
└── TOOLS.md                        # Complete tool reference
```

### Building

```bash
./gradlew build          # Full build with tests
./gradlew shadowJar      # Build fat JAR only
./gradlew install        # Build and install to ~/.wpilog-mcp/
./gradlew test           # Run unit tests
./gradlew stressTest     # Run stdio + HTTP integration stress tests
./gradlew stdioStressTest  # Stdio stress test only
./gradlew httpStressTest   # HTTP stress test only
```

Stress tests require a `"stresstest"` config in `servers.json` with a valid `logdir`.

## Contributing

1. **Report bugs** - Open an issue with reproduction steps
2. **Request features** - Open an issue describing your use case
3. **Submit PRs** - Fork, make changes, add tests, submit

## License

MIT License - see [LICENSE](LICENSE)

## Acknowledgments

- [WPILib](https://github.com/wpilibsuite/allwpilib) for the DataLog format
- [AdvantageKit](https://github.com/Mechanical-Advantage/AdvantageKit) for pioneering FRC replay logging
- [Anthropic](https://anthropic.com) for MCP and Claude
- [FRC Team 2363 Triple Helix](https://team2363.org)

## See Also

- [TOOLS.md](TOOLS.md) - Complete tool reference
- [EXAMPLE.md](EXAMPLE.md) - Sample analysis report showing tool capabilities
- [WPILib DataLog Docs](https://docs.wpilib.org/en/stable/docs/software/telemetry/datalog.html)
- [MCP Protocol](https://modelcontextprotocol.io/)
