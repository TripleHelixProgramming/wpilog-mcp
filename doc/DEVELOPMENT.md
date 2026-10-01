# Development

## Project Structure

Production source is in `src/main/java/org/triplehelix/wpilogmcp/`, organized by responsibility:

| Package | Purpose |
|---------|---------|
| `cache/` | Persistent disk cache for revlog sync results and content fingerprinting |
| `config/` | Named server configurations, JSON parsing, daemon lifecycle |
| `game/` | Year-specific FRC game knowledge (scoring, timing, field geometry) |
| `log/` | WPILOG loading with lazy on-demand parsing, LRU caching, struct decoding |
| `mcp/` | MCP JSON-RPC 2.0 protocol: message routing, stdio/HTTP transports, sessions |
| `revlog/` | REV `.revlog` parsing (WPILOG-format and native binary) with DBC signal decoding |
| `sync/` | Cross-correlation timestamp synchronization between wpilog and revlog |
| `tba/` | The Blue Alliance API client with caching and log enrichment |
| `tools/` | All MCP tools, organized by category into module classes |

Tests mirror this structure under `src/test/java/`.

## Requirements

- **JDK 17+** (WPILib JDK recommended)
- No other dependencies (WPILib libraries bundled)

WPILib JDK locations:
- **macOS**: `~/wpilib/2026/jdk/bin/java`
- **Windows**: `C:\Users\Public\wpilib\2026\jdk\bin\java.exe`
- **Linux**: `~/wpilib/2026/jdk/bin/java`

## Building

```bash
./gradlew build          # Full build with tests
./gradlew shadowJar      # Build fat JAR only
./gradlew install        # Build and install to ~/.wpilog-mcp/
./gradlew test           # Run unit tests
./gradlew stressTest     # Run stdio + HTTP integration stress tests
./gradlew stdioStressTest  # Stdio stress test only
./gradlew httpStressTest   # HTTP stress test only
```

Stress tests use `~/riologs` and team 2363 by default. Override by adding a `stresstest` server entry to `.wpilog-mcp.yaml` in the project root, or pass `-Pconfigpath=/path/to/config.yaml`. Set `TBA_API_KEY` in your environment to include TBA integration tests. Both stress tests apply the conformance checks below to the results they produce; the real-log conformance sweep below applies them to every tool on every log.

### Robustness tests

```bash
./gradlew test                                   # includes the conformance sweep on the fixture corpus
./gradlew test -PconformanceUpdate               # rewrite the known-failures list (should stay empty)
./gradlew test --tests '*ReviewLogGoldenTest*' -PgoldenLog=/path/to/akit_26-09-30_00-10-26.wpilog
./gradlew test --tests '*RealLogConformanceTest*' -PconformanceLogDir=/path/to/riologs [-PconformanceMaxLogs=N] [-PconformanceTools=a,b]
./gradlew test --tests '*RealLogDifferentialTest*' -PconformanceLogDir=/path/to/riologs [-PconformanceMaxLogs=N]
./gradlew test --tests '*RealLogClaimsTest*' -PconformanceLogDir=/path/to/riologs
./gradlew test --tests '*ToolResponsesDoc*' -PtoolResponsesLogDir=/path/to/riologs
```

- **Fixture corpus** (`src/test/java/.../fixtures`): about 20 small logs written by a pure-Java WPILOG writer, one per logging convention (AdvantageKit match and practice, plain WPILib, swerve arrays, vision templates, custom structs, CANivore, alerts, truncated, ...), regenerated on every run.
- **Conformance sweep** (`ToolConformanceTest`): every tool on every fixture must return the result contract, no NaN, no silent success (a success whose content is only zeros, `false`, and empty lists counts as silent), `inputs` on every successful log-reading result, true totals, deterministic output (every call repeated with the entries reversed and twice shuffled gives the same result), and every output its description names (`DescriptionOutputs`). Tools whose needed parameters are optional in their schema (`profile_mechanism`, `analyze_cycles`) get argument variants that reach their real analysis. New violations fail the build; `src/test/resources/conformance/known-failures.txt` is the ratchet.
- **Real-log conformance sweep** (`RealLogConformanceTest`, opt-in): the same checks and argument variants for every log-reading tool on every `.wpilog` under a directory (up to 5 levels deep), with the entries reversed for determinism. No ratchet: any violation fails. The report of every call and its time is in `build/reports/conformance/real-logs.txt`.
- **Differential check** (`FixtureDifferentialTest`, always on; `RealLogDifferentialTest`, opt-in): the conformance sweep shows the tools keep their contract, not that a number is right. This check reads each log with `IndependentLog`, a WPILOG reader written from the format alone (no server code, no WPILib reader), and compares the time range, every entry's type and sample count, the statistics of the most-sampled numeric entries and of entries holding NaN, and the enabled windows. The server may set records aside only where that reader sees damage itself; the server's own warning excuses nothing. A file neither side can read is counted, a file only one side can read is a finding. The report is `build/reports/conformance/differential.txt`. When changing a check, plant the bug it is meant to catch and see the test fail.
- **Claims on the live service** (`RealLogClaimsTest`, opt-in): starts the server on its HTTP transport and checks documented claims against known facts of the sample logs; the report is `build/reports/conformance/claims.txt`.
- **Golden checks** (`ReviewLogGoldenTest`): values from the review log checked against numpy and Python ground truth; opt-in because the log is not in the repository.
- **Tool response reference**: `doc/TOOL_RESPONSES.md` is generated by `ToolResponsesDoc` from the calls in `src/test/resources/tool-responses/scenarios.json`; add a call there for any new tool (the capture fails when a tool has none).

## Building the VS Code Extension

```bash
./gradlew bundleExtension    # Copy server JAR into extension (for local dev)
./gradlew buildExtension     # Build, compile, and package .vsix
./gradlew installExtension   # Build, package, and install to VS Code
```

Requires [Node.js](https://nodejs.org/). For `installExtension`, close VS Code before running, then restart when done. The `.vsix` is written to `vscode-extension/wpilog-analyzer-{version}.vsix`.

## Contributing

1. **Report bugs** - Open an issue with reproduction steps
2. **Request features** - Open an issue describing your use case
3. **Submit PRs** - Fork, make changes, add tests, submit
