<table>
<tr>
<td width="220" valign="top" align="center">
  <!-- The leading <br> balances the h1's top margin, so the icon sits midway between the cell's top edge and the title. -->
  <p align="center"><br><img src="vscode-extension/images/icon.png" alt="wpilog-mcp icon" width="140" align="top"></p>
  <h1 align="center">wpilog-mcp</h1>
  <p align="center">Model Context Protocol (MCP) Server for WPILib Logs</p>
</td>
<td valign="middle">

*"Why did the robot die with 30 seconds left?"* *"Why did auto work in practice but not at competition?"* wpilog-mcp gives your AI assistant the tools to analyze your robot's telemetry logs, so you can ask questions like these in plain English and get answers based on the data.

It reads WPILib `.wpilog` files and the REV `.revlog` files recorded beside them. It works with Claude, GitHub Copilot, Gemini, ChatGPT, and any other agent that speaks MCP. Built by [FRC Team 2363 Triple Helix](https://team2363.org).

**Install:** [WPILog Analyzer on the Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=TripleHelixProgramming.wpilog-analyzer), or the [standalone install](doc/STANDALONE.md) for clients outside VS Code

**Documentation:** [VS Code extension](vscode-extension/README.md) · [Standalone install](doc/STANDALONE.md) · [Tools](doc/TOOLS.md) · [Architecture](doc/ARCHITECTURE.md) · [Development](doc/DEVELOPMENT.md) · [all documents](#documentation)

</td>
</tr>
</table>

## How It Works

The [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) is an open standard by which an AI assistant such as Claude calls tools that a server provides; wpilog-mcp is such a server. Its tools open the logs, find entries, compute statistics, and report events. The reasoning is the model's.

A log viewer such as AdvantageScope shows you the data, but only if you know where to look. With wpilog-mcp you ask an engineering or strategy question; the model finds the relevant entries, proposes an explanation, and tests it against the data with further tool calls.

### What You Can Ask

Start broad; the model finds the logs and the entries. Then get as specific as the data allows:

- *"Show me our logs from the Chesapeake District event."*
- *"We lost Q42. Look at the log and help us understand what happened."*
- *"What are the scoring rules for this year's game?"*
- *"Pull our match results from The Blue Alliance and look for trends across the event."*
- *"Walk me through the power delivery during teleop in Q42. Were there any brownout concerns?"*
- *"How did our four swerve modules compare in that match? Is one of them lagging the others?"*
- *"Did the vision corrections get larger late in the match? Line up the pose jumps against the time the arm was extended."*
- *"The intake stalled twice. Find every moment its current was above 40 A while its velocity was zero, and show me the CAN bus health around each one."*
- *"Using the REV log, align the SPARK MAX currents with the battery voltage and tell me whether the climber or the drivetrain pulled the voltage down."*

> **Note:** How far the analysis goes depends on two things: the model, and what the model can see beyond the log. wpilog-mcp supplies the tools and the data; the reasoning is the model's, and a more capable model reasons more carefully.
>
> A log records names and numbers, not what they mean. With your robot project open, the model can read the code that writes each entry and learn which mechanism it belongs to, what its units are, and whether it records a measurement or a command. Match results from The Blue Alliance and the season's game rules add still more.

### An Example

**Prompt:** *"We just finished Q68 and the drivers said the robot 'stuttered' during teleop. Investigate the log and tell the pit crew exactly what to check."*

Claude checks `get_ds_timeline` for brownout events, then looks at the motor currents around that moment: `power_analysis` lists each current entry's peak with its time, and `get_statistics` or `read_entry` with `start_time` and `end_time` narrows to the moment. A call to `can_health` looks for CAN timeouts while the robot was enabled.

A possible answer: *"Battery voltage dropped below the brownout threshold at 42.5 s (BROWNOUT_START). At that moment the 'Intake/Roller' current spiked to 60 A while its velocity was zero, which suggests a mechanical jam. Check the intake for debris or a bent mounting bracket."*

## Installation

wpilog-mcp runs two ways. It is designed for Claude and tested with Claude, but any agent that speaks MCP should work: GitHub Copilot in VS Code, Gemini CLI, Cursor, and others. ChatGPT reaches MCP servers only over the network, so it needs the [HTTP transport](doc/STANDALONE.md#http-transport).

**[VS Code extension](vscode-extension/README.md):** install **[WPILog Analyzer](https://marketplace.visualstudio.com/items?itemName=TripleHelixProgramming.wpilog-analyzer)** from the Visual Studio Marketplace, either through VS Code's Extensions view or from its Marketplace page. There is nothing else to download. The extension bundles the server, finds Java, and registers the server with VS Code, where Copilot and other agents pick it up; for Claude Code, it adds the server to your robot project's `.mcp.json`, which you approve once. A particular build, such as a test build of the next version, installs from the `.vsix` file on the [releases page](https://github.com/TripleHelixProgramming/wpilog-mcp/releases); the extension's README says how.

**[Standalone install](doc/STANDALONE.md):** for MCP clients outside VS Code, such as Claude Desktop, the Claude Code CLI, Gemini CLI, or Cursor. Run the one-line installer, or clone the repository and run `./gradlew install`; set your team number and log directory in `~/.wpilog-mcp/servers.yaml`; then point your MCP client at the `wpilog-mcp` launcher.

Both take the same three settings: where your logs are (one directory or several), your team number, and a Blue Alliance API key if you want match results. The extension takes them in VS Code [settings](vscode-extension/README.md#settings) and moves the key into VS Code's secret storage, so that no settings file keeps it ([details](vscode-extension/README.md#the-blue-alliance-api-key)); the standalone server takes them in its [configuration file](doc/STANDALONE.md#configuration).

### Extension or Standalone?

Most people want the extension. The standalone install is for Claude Code or Claude Desktop without VS Code, for the HTTP transport, and for settings the extension doesn't offer: disk cache size, export directory, scan depth, and named server configurations in `servers.yaml`.

You can install both. Each keeps its own settings and its own disk cache; the extension leaves alone any project that already runs the standalone server. [Using It Alongside the Standalone Install](vscode-extension/README.md#using-it-alongside-the-standalone-install) has the details, including what to turn off if you registered the standalone server for every project.

## Guardrails

A language model tends to find an explanation that fits the data, whether or not the data can bear a firm conclusion. The server is built to resist that tendency:

- Tools return measurements, not verdicts: statistics with sample counts, timestamps, event lists. There is no "diagnose my robot" tool; the model must reason across several calls, and every step of that reasoning can be checked. The few tools that do offer a summary for the pit, a battery health score or a brownout risk, state the rule that decided it.
- The server does not guess. A tool treats an entry as the battery voltage or the robot pose only when you name it, when it follows a published logging convention, or when it is the only entry of its type; otherwise the tool lists the candidates and leaves the choice to you and the model. A word in an entry's name is no proof of what it measures. Your robot's source code settles that, and the server sends the model there to read it.
- Results say how far to trust them. Every result carries a status, so that "found nothing" is never mistaken for "nothing is wrong"; every result that rests on statistics carries a data-quality score, with a reason for each penalty.
- On connect, the server hands the model its reasoning rules: confirm that the event in the question actually happened; quote only numbers a tool returned; learn what an entry measures from the code that logs it, not from its name; test a proposed cause against a rival explanation; label an inferred cause as a hypothesis. For clients that do not pass these on, `get_server_guide` returns the same guidance.

[ARCHITECTURE.md](doc/ARCHITECTURE.md#design-principles) explains these principles and the failures that taught them; [TOOLS.md](doc/TOOLS.md#response-fields) describes the result fields that carry them.

## What It Reads

- **WPILOG files** from any logging framework: AdvantageKit, WPILib's DataLogManager, or your own. The server reads the primitive types and their arrays; it decodes struct entries with the schemas the log itself records, so WPILib's geometry types, vendor structs, and a team's own structs all decode. A log cut short, by a power loss for example, still loads up to the damage, and every result drawn from it says so. See [Data Types](doc/TOOLS.md#data-types).
- **REV logs** (`.revlog`), which REVLib 2026 and later writes for robot programs that use SPARK MAX or SPARK Flex controllers. The server finds the REV logs recorded with a wpilog and puts their timestamps on the wpilog's clock, by correlating signals that both logs record; each alignment comes with a confidence level. See [RevLog Tools](doc/TOOLS.md#revlog-tools).
- **Match results from The Blue Alliance**, with a free API key: the team's alliance, the scores, and the match times for each qualification or playoff log. See [TBA Tools](doc/TOOLS.md#tba-tools); the [extension](vscode-extension/README.md#the-blue-alliance-api-key) and [standalone](doc/STANDALONE.md#configuration) pages say where the key goes.
- **Game rules** for recent seasons, transcribed from each season's final game manual: scoring, match timing, field geometry, and robot limits. See [`get_game_info`](doc/TOOLS.md#get_game_info).

## Tools

Every tool that reads a log takes a `path` parameter. The server loads a log the first time a tool names it; it unloads a log that sits idle, or any log when memory runs short. The tools, in the categories `get_server_guide` uses:

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

`get_server_guide` describes every tool by category, along with the analysis principles. Its description tells the agent to call it first, so that the agent reaches for a built-in tool rather than writing analysis code of its own.

[TOOLS.md](doc/TOOLS.md) gives each tool's parameters and results; [TOOL_RESPONSES.md](doc/TOOL_RESPONSES.md) shows real responses from every one.

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

## Supporting Triple Helix

wpilog-mcp is developed and maintained by Triple Helix (FRC 2363). We offer it free to the FRC community, in the hope that it helps a few more teams understand their robots and fix what the logs reveal.

Triple Helix is one of three FIRST teams operating under the [Intentional Innovation Foundation](https://www.iifound.org/) (IIF), a 501(c)(3) nonprofit in Newport News, Virginia. Alongside NASA Knights (FRC 122) and Blackwater Robotics (FTC 27248), IIF runs a small Peninsula STEM Gym out of a space shared with a diesel service garage. Our vision is bigger: a Peninsula-wide facility with a full-size practice field and open hours, where any FRC, FTC, or FLL team in the region can drop in to build and scrimmage.

All of this runs on a shoestring. Donations, sponsorships, and team member contributions cover robots, travel, and the lease on the gym. If wpilog-mcp helped your team, or if you believe in the vision of a shared community makerspace, please consider [a donation to IIF](https://www.paypal.com/donate?hosted_button_id=R9BJ2TLELQR6W). Thank you so much!

## License

MIT License. See [LICENSE](LICENSE).

## Acknowledgments

- [WPILib](https://github.com/wpilibsuite/allwpilib) for the DataLog format
- [AdvantageKit](https://github.com/Mechanical-Advantage/AdvantageKit) for pioneering FRC replay logging
- [Anthropic](https://anthropic.com) for MCP and Claude
- [FRC Team 2363 Triple Helix](https://team2363.org)
