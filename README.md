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

**Documentation:** [Operations and usage manual](doc/OPERATIONS.md) · [VS Code extension](vscode-extension/README.md) · [Standalone install](doc/STANDALONE.md) · [Tools](doc/TOOLS.md) · [Architecture](doc/ARCHITECTURE.md) · [Development](doc/DEVELOPMENT.md) · [all documents](#documentation)

</td>
</tr>
</table>

A standalone HTTP server can also [record NetworkTables and pull robot logs](doc/STANDALONE.md#pit-server) into a local store. Pulling is opt-in and waits for a connected, disabled robot; the open capture is available to the existing tools.

The HTTP transport also serves [Prometheus metrics and a starter Grafana dashboard](doc/STANDALONE.md#metrics-and-a-starter-dashboard). Dashboards show sampled latest values and their ages; the capture keeps the full record between scrapes.

## How It Works

The [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) is an open standard by which an AI assistant such as Claude calls tools that a server provides; wpilog-mcp is such a server. Its tools open the logs, find entries, compute statistics, and report events. The reasoning is the model's.

A log viewer such as AdvantageScope shows you the data, but only if you know where to look. With wpilog-mcp you ask an engineering or strategy question; the model finds the relevant entries, proposes an explanation, and tests it against the data with further tool calls.

A log folder can become a store, organized by robot and session: the extension [offers to organize](vscode-extension/README.md#organizing-your-logs) a folder it finds, and the standalone [import command and inbox](doc/STANDALONE.md#importing-logs) do the same from a terminal or a USB stick. Imports preserve filenames and provenance, and the listing reports files awaiting assignment or refused by the inbox.

The extension can [connect to a pit server and keep an offline mirror](vscode-extension/README.md#pit-server-and-offline-mirror): browse or follow its sessions, pin what you need, and continue analyzing the same files after leaving the network. **Sync from Laptop** combines two teammates' stores through the same HTTP door, preserving provenance and reporting conflicts.

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

wpilog-mcp is designed for Claude and tested with Claude, but any agent that speaks MCP should work: GitHub Copilot in VS Code, Gemini CLI, Cursor, and others. ChatGPT reaches MCP servers only over the network, so it needs the [HTTP transport](doc/STANDALONE.md#http-transport).

The [operations and usage manual](doc/OPERATIONS.md) walks through the setup check, the first questions, and running the server in the shop and the pit.

The shared-server and pit features described on this branch require 0.10.0-dev1 or newer.
The latest full release may be older; until this version and its installer are published,
use the [checkout install](doc/STANDALONE.md#install) from `development` and check
`wpilog-mcp -version` before following the manual.

Install **[WPILog Analyzer](https://marketplace.visualstudio.com/items?itemName=TripleHelixProgramming.wpilog-analyzer)** in VS Code, accept its server-install offer, and put your logs in `~/riologs` or set its Log Directory setting. The extension bundles the server JAR, finds Java, and starts one shared server. Copilot and other VS Code agents get its HTTP definition; Claude Code gets a user-scope bridge registration. [The extension guide](vscode-extension/README.md) covers setup and the explorer. A particular build, such as a test build of the next version, installs from the `.vsix` file on the [releases page](https://github.com/TripleHelixProgramming/wpilog-mcp/releases); the extension's README says how.

### Extension or Standalone?

Most people want the extension. It installs and updates the same standalone server used by clients outside VS Code; installing both does not create a second server or cache. The default log location needs no configuration changes. User and project directory settings in VS Code become temporary leases, visible to every connected client while the window is open.

Without VS Code, use the [standalone installer](doc/STANDALONE.md). The installer shipped with these changes offers directories, team, and the matching extension in a terminal; it keeps existing settings and uses defaults without a terminal. Point clients at the launcher's `connect http` bridge. For permanent directories or advanced server settings, edit `~/.wpilog-mcp/servers.yaml`; VS Code's Settings UI does not edit it. A Blue Alliance key is optional: the extension registers its secret-storage key in memory, while standalone users can configure the key in YAML or the environment.

For a published pre-release, pass the installer `--tag v0.10.0-dev1 --with-extension` to select
that server and its matching extension together; the [pre-release instructions](doc/STANDALONE.md#trying-a-pre-release)
cover both platforms. The tag must already exist on Releases. Upgrades use user-scope Claude Code
registration; the [migration guide](doc/STANDALONE.md#moving-from-a-project-mcpjson) covers old project entries.

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

The explorer's **Data view** uses the selected samples for filtering, pivots, quick charts
and CSV export, and appends new batches while following a session. **Open in Notebook**
saves a three-cell Python notebook with the request and its evidence. Assistants can return
`render_chart` images with an **Open in Explorer** link; **Ask about this selection** sends
the path, entries and window to VS Code chat, with a clipboard fallback. See the
[extension guide](vscode-extension/README.md#exploring-logs).

## Tools

Every tool that reads a log takes a `path` parameter. The server loads a log the first time a tool names it; it unloads a log that sits idle, or any log when memory runs short. The tools, in the categories `get_server_guide` uses:

| Category | Tools |
|----------|-------|
| **Discovery** | `get_server_guide`, `suggest_tools` |
| **Core** | `list_available_logs`, `list_loaded_logs`, `list_entries`, `read_entry`, `render_chart`, `get_entry_info`, `list_struct_types`, `resolve_signals`, `health_check` |
| **Query** | `search_entries`, `get_types`, `find_condition`, `search_strings`, `search_system_logs` |
| **Statistics** | `get_statistics`, `compare_entries`, `detect_anomalies`, `find_peaks`, `rate_of_change`, `time_correlate`, `align_entries` |
| **Robot Analysis** | `get_match_phases`, `analyze_swerve`, `power_analysis`, `can_health`, `analyze_can_bus`, `compare_matches`, `get_code_metadata`, `moi_regression` |
| **FRC Domain** | `get_ds_timeline`, `analyze_vision`, `compare_poses`, `pose_corrections`, `profile_mechanism`, `analyze_auto`, `analyze_cycles`, `analyze_replay_drift`, `analyze_loop_timing`, `predict_battery_health`, `get_game_info` |
| **TBA** | `get_tba_status`, `get_tba_match_data` |
| **RevLog** | `list_revlog_signals`, `get_revlog_data`, `sync_status`, `set_revlog_offset`, `wait_for_sync` |
| **Export** | `export_csv`, `generate_report` |
| **Live** | `list_sessions`, `get_latest_values`, `wait_for_change` |

The Live tools answer for the present on a server with capture enabled, and `not_applicable` on any other. They report session costs and current publications without opening a log; the other tools still read the live capture by path.

`get_server_guide` describes every tool by category, along with the analysis principles. Its description tells the agent to call it first, so that the agent reaches for a built-in tool rather than writing analysis code of its own.

[TOOLS.md](doc/TOOLS.md) gives each tool's parameters and results; [TOOL_RESPONSES.md](doc/TOOL_RESPONSES.md) shows real responses from every one.

## Documentation

Start with the guide for what you are doing. The reference pages are linked from each.

**Using it**

| Document | What it covers |
|----------|----------------|
| [Operations and usage manual](doc/OPERATIONS.md) | Step by step: the setup check, how a question becomes tool calls, reading an answer, recipes for the common questions, the explorer, the shared server, the pit server, the shop day, stores, maintenance, and what to do when something is wrong |
| [VS Code extension README](vscode-extension/README.md) | The extension: the explorer, organizing logs, the pit server and the offline mirror, settings, Claude Code, The Blue Alliance key, upgrading, troubleshooting |
| [STANDALONE.md](doc/STANDALONE.md) | The reference for the standalone install: the configuration file and every key, command-line flags and environment variables, MCP client setup, the HTTP routes, the pit server's configuration, metrics, Docker |
| [TOOLS.md](doc/TOOLS.md) | Every tool's parameters and results, the data types, the result fields, and how REV logs are synchronized |
| [TOOL_RESPONSES.md](doc/TOOL_RESPONSES.md) | The JSON every tool returns, captured from real logs |

**Developing it**

| Document | What it covers |
|----------|----------------|
| [ARCHITECTURE.md](doc/ARCHITECTURE.md) | The goals and design principles, and how the server reads logs, records captures, manages memory, caches results, and handles concurrent clients |
| [DEVELOPMENT.md](doc/DEVELOPMENT.md) | Building, every test suite, adding a tool, releasing, and contributing |
| [CHANGELOG.md](CHANGELOG.md) | Release notes |

**Plans and proposals**

| Document | What it covers |
|----------|----------------|
| [PIT_SERVER_PLAN.md](doc/PIT_SERVER_PLAN.md) | The pit server: the idea for everyone, the specification for developers, and the record of the decisions made while building it |
| [EXPLORER_PLAN.md](doc/EXPLORER_PLAN.md) | WPILog Explorer, the viewer in the extension: the same server's numbers, plotted, with a path from looking to asking |
| [IDEAS.md](doc/IDEAS.md) | Proposed work, and the priorities among it |

**Historical**

| Document | What it covers |
|----------|----------------|
| [ROBUSTNESS_REVIEW.md](doc/ROBUSTNESS_REVIEW.md), [ROBUSTNESS_PLAN.md](doc/ROBUSTNESS_PLAN.md) | The review of the server's accuracy on real logs and the plan that answered it, behind most of version 0.9.0 |
| [VAALE event analysis](doc/VAALE_EVENT_ANALYSIS.md), [VACHE power analysis](doc/VACHE_POWER_ANALYSIS.md) | Two example analyses of real robot logs, made with earlier versions (March 2026, before v0.3.0, and v0.8.0). The current server reports some of the same logs differently |

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
