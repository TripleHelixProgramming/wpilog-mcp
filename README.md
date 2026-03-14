# wpilog-mcp: Model Context Protocol (MCP) Server for WPILib Logs

**Ever wondered why your robot died with 30 seconds left? Or why auto worked in practice but not in competition?**

wpilog-mcp lets you ask those questions in plain English. Load your robot's telemetry logs and have a conversation with your data.

### What is MCP?

The **Model Context Protocol (MCP)** is an open standard that enables AI assistants (like Claude) to securely read and analyze your local data. 

This project provides an **MCP Server**. Once configured, it acts as a "bridge" that gives your AI assistant the specific tools needed to read WPILOG files, analyze swerve performance, detect brownouts, and even pull match results from The Blue Alliance—all through a natural conversation.

Built by [FRC Team 2363 Triple Helix](https://www.triplehelixrobotics.com/) using WPILib's official `DataLogReader` for guaranteed format compatibility.

**See what's possible:** [Example Analysis Report](EXAMPLE.md) - A complete match analysis generated from real robot logs.

## Quick Start

### 1. Build

Built for JDK 17 binary compatibility. It is recommended to use the [WPILib JDK](https://docs.wpilib.org/en/stable/docs/zero-to-robot/step-2/wpilib-setup.html), which the build tool attempts to locate automatically.

```bash
git clone https://github.com/TripleHelixProgramming/wpilog-mcp.git
cd wpilog-mcp
./gradlew shadowJar
```

### 2. Configure

Configuration location depends on how you're running Claude Code:

| Environment | Config File |
|-------------|-------------|
| **VS Code extension** | `.mcp.json` in your project folder |
| **Claude Code CLI** | `~/.claude/settings.json` |
| **Claude Desktop** | See [Claude Desktop](#claude-desktop) section |

**Example configuration (macOS/Linux):**
```json
{
  "mcpServers": {
    "wpilog": {
      "command": "/Users/yourname/wpilib/2026/jdk/bin/java",
      "args": [
        "-jar", "/path/to/wpilog-mcp-0.1.0-all.jar",
        "-logdir", "/path/to/your/logs",
        "-team", "2363"
      ],
      "env": {
        "TBA_API_KEY": "your_tba_api_key"
      }
    }
  }
}
```

**Example configuration (Windows):**
```json
{
  "mcpServers": {
    "wpilog": {
      "command": "C:\\Users\\Public\\wpilib\\2026\\jdk\\bin\\java.exe",
      "args": [
        "-jar", "C:\\path\\to\\wpilog-mcp-0.1.0-all.jar",
        "-logdir", "C:\\path\\to\\your\\logs",
        "-team", "2363"
      ],
      "env": {
        "TBA_API_KEY": "your_tba_api_key"
      }
    }
  }
}
```

*Note: TBA key is optional. Without it, the server works normally but without match enrichment. Team number is extracted from log metadata when available, with `-team` as a fallback.*

### 3. Use

Restart Claude Code. Then just ask:

```
What robot logs are available?
```

```
Load the qualification match 42 log and give me a summary
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
Load the robot log and give me a summary
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
Load both the practice and match logs, then compare battery statistics
```

### Memory Management

```
Unload all logs to free up memory
```

## Configuration Options

### Command Line

| Option | Description |
|--------|-------------|
| `-logdir <path>` | Directory containing `.wpilog` files |
| `-team <number>` | Default team number for logs missing metadata |
| `-tba-key <key>` | The Blue Alliance API key for match data enrichment |
| `-maxlogs <n>` | Max number of logs to cache (default: 20) |
| `-maxmemory <mb>` | Max memory (MB) for log cache (alternative to `-maxlogs`) |
| `-debug` | Enable debug logging |
| `-help` | Show usage information |

**Environment variables:**
- `WPILOG_DIR` - Alternative to `-logdir` (command line takes precedence)
- `WPILOG_TEAM` - Alternative to `-team`
- `TBA_API_KEY` - Alternative to `-tba-key`

**Cache limits:** The server caches parsed logs in memory for fast access. By default, up to 20 logs are kept. Use `-maxlogs` to change this limit, or `-maxmemory` to set a memory-based limit instead (only used if `-maxlogs` is not set). The least recently used log is evicted when the limit is reached.

### The Blue Alliance Integration

Get a free API key at [thebluealliance.com/account](https://www.thebluealliance.com/account).

When configured, the server enriches match logs with TBA data:
- **Match times** - Corrects midnight timestamps from FMS
- **Scores** - Your alliance's score and opponent's score
- **Win/Loss** - Whether your team won the match
- **Alliance** - Which alliance (red/blue) your team was on

Team number is extracted from each log file's metadata (DriverStation/FMS data), with the configured `-team` value as a fallback.

This data is automatically added to `list_available_logs` output for logs that have event/match/team metadata.

### Claude Desktop

Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "wpilog": {
      "command": "/path/to/java",
      "args": ["-jar", "/path/to/wpilog-mcp-0.1.0-all.jar", "-logdir", "/path/to/logs"]
    }
  }
}
```

### Other MCP Clients

This server uses **stdio transport** (JSON-RPC over stdin/stdout). Generic config:

```json
{
  "command": "/path/to/java",
  "args": ["-jar", "/path/to/wpilog-mcp-0.1.0-all.jar"],
  "transport": "stdio"
}
```

See [MCP Protocol](https://modelcontextprotocol.io/) for client implementations.

## Available Tools

wpilog-mcp provides 34 tools organized into categories:

| Category | Tools |
|----------|-------|
| **Core** | `load_log`, `list_entries`, `read_entry`, `get_entry_info`, `search_entries`, `get_statistics`, `compare_entries`, `get_types` |
| **Log Browser** | `list_available_logs` |
| **Multi-Log** | `list_loaded_logs`, `set_active_log`, `unload_log`, `unload_all_logs` |
| **Search** | `find_condition`, `search_strings` |
| **Analysis** | `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `get_match_phases` |
| **FRC-Specific** | `analyze_swerve`, `power_analysis`, `can_health`, `compare_matches`, `get_code_metadata` |
| **FRC Domain Analysis** | `get_ds_timeline`, `analyze_vision`, `profile_mechanism`, `analyze_auto`, `analyze_cycles`, `analyze_replay_drift` |
| **TBA Integration** | `get_tba_status` |
| **Export** | `export_csv`, `generate_report` |

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
     java -jar wpilog-mcp-0.1.0-all.jar
   ```

3. **Check config location**:
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
│   ├── Main.java                 # Entry point
│   ├── log/
│   │   ├── LogManager.java       # WPILOG parsing & struct decoding
│   │   └── LogDirectory.java     # Log file discovery
│   ├── mcp/
│   │   ├── McpServer.java        # MCP protocol handling
│   │   └── JsonRpc.java          # JSON-RPC utilities
│   ├── tba/
│   │   ├── TbaClient.java        # TBA API client with caching
│   │   ├── TbaConfig.java        # TBA configuration management
│   │   └── TbaEnrichment.java    # Log enrichment with TBA data
│   └── tools/
│       ├── WpilogTools.java      # Tool registration
│       ├── CoreTools.java        # Log management tools (9)
│       ├── QueryTools.java       # Search & query tools (4)
│       ├── StatisticsTools.java  # Statistical analysis tools (6)
│       ├── RobotAnalysisTools.java # FRC analysis tools (6)
│       ├── FrcDomainTools.java   # Advanced FRC tools (6)
│       ├── ExportTools.java      # CSV/report export (2)
│       ├── TbaTools.java         # TBA integration (1)
│       └── ToolUtils.java        # Shared utilities
├── src/test/java/                # Test suite
├── build.gradle                  # Build configuration
├── README.md
└── TOOLS.md                      # Complete tool reference
```

### Building

```bash
./gradlew build          # Full build with tests
./gradlew shadowJar      # Build fat JAR only
./gradlew test           # Run tests
```

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
- [FRC Team 2363 Triple Helix](https://www.triplehelixrobotics.com/)

## See Also

- [TOOLS.md](TOOLS.md) - Complete tool reference
- [EXAMPLE.md](EXAMPLE.md) - Sample analysis report showing tool capabilities
- [WPILib DataLog Docs](https://docs.wpilib.org/en/stable/docs/software/telemetry/datalog.html)
- [MCP Protocol](https://modelcontextprotocol.io/)
