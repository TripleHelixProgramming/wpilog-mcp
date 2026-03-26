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

Stress tests use `~/riologs` and team 2363 by default. Override by adding a `stresstest` server entry to `.wpilog-mcp.yaml` in the project root. Set `TBA_API_KEY` in your environment to include TBA integration tests.

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
