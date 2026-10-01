<table>
<tr>
<td width="220" valign="top" align="center">
  <img src="vscode-extension/images/icon.png" alt="wpilog-mcp icon" width="140"><br>
  <h1>wpilog-mcp</h1>
  Model Context Protocol (MCP) Server for WPILib Logs
</td>
<td valign="top">

Why did the robot die with 30 seconds left? Why did auto work in practice but not at competition? wpilog-mcp lets an AI assistant read your robot's telemetry logs, so you can ask questions like these in plain English and get answers based on the data.

It reads WPILib `.wpilog` files with WPILib's own log reader, and REV `.revlog` files recorded alongside them. It was built by [FRC Team 2363 Triple Helix](https://team2363.org).

**Documentation:** [VS Code extension](vscode-extension/README.md) · [Standalone install](doc/STANDALONE.md) · [Tools](doc/TOOLS.md) · [Architecture](doc/ARCHITECTURE.md) · [Development](doc/DEVELOPMENT.md) · [all documents](#documentation)

</td>
</tr>
</table>

## Example Questions

```
Please show me our logs from the Chesapeake District event
```

```
We lost Q42. Can you look at the log and help us understand what happened?
```

```
Please walk me through the power delivery during teleop. Were there any brownout concerns?
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

How much the analysis finds depends on the AI model you use. wpilog-mcp provides the tools and the data; the model does the reasoning.

## Installation

There are two ways to run wpilog-mcp. The server is designed for and tested with Claude, but should work with any MCP client.

**[VS Code extension](vscode-extension/README.md):** install **WPILog Analyzer** from the `.vsix` file on the [releases page](https://github.com/TripleHelixProgramming/wpilog-mcp/releases/latest) (it is not on the Marketplace). The extension finds Java, starts the server, and registers it with VS Code, where Copilot and other agents find it. For Claude Code, it adds the server to your robot project's `.mcp.json`, and you approve it once. With the robot project open, the agent can also read your code and connect log entries to the subsystems that write them.

**[Standalone install](doc/STANDALONE.md):** for MCP clients outside VS Code, such as Claude Desktop, the Claude Code CLI, or Gemini. Run the one-line installer (or clone the repository and run `./gradlew install`), set your team number and log directory in `~/.wpilog-mcp/servers.yaml`, and point your MCP client at the `wpilog-mcp` launcher.

Both take the same settings: where your logs are (one directory or several), your team number, and a Blue Alliance API key if you want match results. The extension takes the first two in VS Code [settings](vscode-extension/README.md#settings) and the key through a [command](vscode-extension/README.md#the-blue-alliance-api-key). The standalone server takes all three in its [configuration file](doc/STANDALONE.md#configuration).

### Extension or Standalone?

Most people want the extension. The standalone install is for Claude Code or Claude Desktop without VS Code, for the HTTP transport, and for settings the extension doesn't offer: disk cache size, export directory, scan depth, and named server configurations in `servers.yaml`.

You can install both. Each keeps its own settings and its own disk cache, and the extension leaves alone a project that already runs the standalone server. [Using It Alongside the Standalone Install](vscode-extension/README.md#using-it-alongside-the-standalone-install) has the details, including what to turn off if you registered the standalone server for all your projects.

## How It Works

The [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) is an open standard that lets an AI assistant such as Claude call tools that a server provides. wpilog-mcp is such a server. Its tools read WPILOG files and describe what the data in them means.

A log viewer such as AdvantageScope shows you the data, but you have to know what to look for. With wpilog-mcp you can ask an engineering or strategy question, and the model finds the relevant entries and tests its explanations against them with further tool calls.

### Guardrails

Language models tend to find an explanation that fits the data, even when the data can't support a firm conclusion. The server is built to work against this:

- Tools return measurements, not verdicts: statistics with sample counts, timestamps, and event lists. There is no "diagnose my robot" tool, so the model has to reason across several calls, and each step can be checked. The few tools that add a summary for the pit, such as a battery health score or a brownout risk, state the rule that decided it.
- The server does not guess. A tool treats a log entry as the battery voltage or the robot pose only when you name it, when it follows a known logging convention, or when it is the only entry of its type. Otherwise the tool lists the candidates and leaves the choice to you and the model. A word in an entry's name is not taken as proof of what it measures. Your robot's source code settles that, and the server tells the model to read it.
- Results say how far to trust them. Every result has a status, so "found nothing" is never mistaken for "nothing is wrong", and results that rest on statistics carry a data-quality score with a reason for every penalty.
- On connect, the server gives the model reasoning rules: check that the event in the question actually happened, quote only numbers a tool returned, learn what an entry measures from the code that logs it and not from its name, test a proposed cause against a rival explanation, and label inferred causes as hypotheses. For clients that don't pass these on, `get_server_guide` returns the same guidance.

[ARCHITECTURE.md](doc/ARCHITECTURE.md#design-principles) explains these principles and the reasons for them, and [TOOLS.md](doc/TOOLS.md#response-fields) describes the result fields that carry them.

### An Example

**Prompt:** *"We just finished Q68 and the drivers said the robot 'stuttered' during teleop. Investigate the log and tell the pit crew exactly what to check."*

Claude checks `get_ds_timeline` for brownout events, looks at motor currents around that moment (`power_analysis` lists each current entry's peak with its time, and `get_statistics` or `read_entry` with `start_time` and `end_time` narrows to the moment), and checks `can_health` for CAN timeouts while enabled.

A possible answer: *"Battery voltage dropped below the brownout threshold at 42.5 s (BROWNOUT_START). At that moment the 'Intake/Roller' current spiked to 60 A while its velocity was zero, which suggests a mechanical jam. Check the intake for debris or a bent mounting bracket."*

## What It Reads

- **WPILOG files** from any logging framework: AdvantageKit, WPILib's DataLogManager, or your own. The server reads the primitive types and their arrays, and decodes struct entries with the schemas the log itself records, so WPILib's geometry types, vendor structs, and a team's own structs all decode. A log cut short (by a power loss, for example) still loads, up to the damage, and every result on it says so. See [Data Types](doc/TOOLS.md#data-types).
- **REV logs** (`.revlog`), which REVLib 2026 and later writes in robot programs that use SPARK MAX or SPARK Flex controllers. The server finds the REV logs recorded with a wpilog and puts their timestamps on the wpilog's clock by correlating signals that both logs record. Each alignment gets a confidence level. See [RevLog Tools](doc/TOOLS.md#revlog-tools).
- **Match results from The Blue Alliance**, with a free API key: the team's alliance, the scores, and the match times for each qualification or playoff log. See [TBA Tools](doc/TOOLS.md#tba-tools); the [extension](vscode-extension/README.md#the-blue-alliance-api-key) and [standalone](doc/STANDALONE.md#configuration) pages say where the key goes.
- **Game rules** for recent seasons, transcribed from each season's final game manual: scoring, match timing, field geometry, and robot limits. See [`get_game_info`](doc/TOOLS.md#get_game_info).

## Tools

Every tool that reads a log takes a `path` parameter: the server loads a log the first time a tool names it, and unloads logs that sit idle or when memory runs short. The tools, in the categories `get_server_guide` uses:

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

`get_server_guide` describes every tool by category, along with the analysis principles. Its description tells the agent to call it first, so that the agent uses a built-in tool rather than writing its own analysis code.

[TOOLS.md](doc/TOOLS.md) has each tool's parameters and results, and [TOOL_RESPONSES.md](doc/TOOL_RESPONSES.md) shows real responses from every tool.

## Documentation

| Document | What it covers |
|----------|----------------|
| [VS Code extension README](vscode-extension/README.md) | Installing and using the extension: settings, Claude Code, The Blue Alliance key, upgrading, troubleshooting |
| [STANDALONE.md](doc/STANDALONE.md) | The standalone install: configuration file, command-line flags, MCP client setup, the HTTP transport, Docker, troubleshooting |
| [TOOLS.md](doc/TOOLS.md) | Every tool's parameters and results, the data types, the result fields, and how REV logs are synchronized |
| [TOOL_RESPONSES.md](doc/TOOL_RESPONSES.md) | The JSON every tool returns, captured from real logs |
| [ARCHITECTURE.md](doc/ARCHITECTURE.md) | The goals and design principles, and how the server reads logs, manages memory, caches results, and handles concurrent clients |
| [DEVELOPMENT.md](doc/DEVELOPMENT.md) | Building, testing, adding a tool, releasing, and contributing |
| [CHANGELOG.md](CHANGELOG.md) | Release notes |
| [IDEAS.md](doc/IDEAS.md) | Planned and proposed work |
| [ROBUSTNESS_REVIEW.md](doc/ROBUSTNESS_REVIEW.md), [ROBUSTNESS_PLAN.md](doc/ROBUSTNESS_PLAN.md) | The review of the server's accuracy on real logs and the plan that answered it, behind most of version 0.9.0 (historical) |
| [VAALE event analysis](doc/VAALE_EVENT_ANALYSIS.md), [VACHE power analysis](doc/VACHE_POWER_ANALYSIS.md) | Two example analyses of real robot logs, made with earlier versions (March 2026, before v0.3.0, and v0.8.0). They are historical: the current server reports some of the same logs differently |

Elsewhere: the [WPILib DataLog documentation](https://docs.wpilib.org/en/stable/docs/software/telemetry/datalog.html) and the [Model Context Protocol](https://modelcontextprotocol.io/).

## License

MIT License. See [LICENSE](LICENSE).

## Acknowledgments

- [WPILib](https://github.com/wpilibsuite/allwpilib) for the DataLog format
- [AdvantageKit](https://github.com/Mechanical-Advantage/AdvantageKit) for pioneering FRC replay logging
- [Anthropic](https://anthropic.com) for MCP and Claude
- [FRC Team 2363 Triple Helix](https://team2363.org)
