# Standalone Install (without VS Code)

For MCP clients outside VS Code — Claude Desktop, Claude Code CLI, Gemini, or other MCP-compatible tools. The server is designed for and tested with Claude. It will work with any MCP-compatible client, but the depth and quality of analysis depends on the model's reasoning capabilities.

## Requirements

- **JDK 17+** (WPILib JDK recommended)
- No other dependencies (WPILib libraries bundled)

WPILib JDK locations:
- **macOS**: `~/wpilib/2026/jdk/bin/java`
- **Windows**: `C:\Users\Public\wpilib\2026\jdk\bin\java.exe`
- **Linux**: `~/wpilib/2026/jdk/bin/java`

## Install

```bash
git clone https://github.com/TripleHelixProgramming/wpilog-mcp.git
cd wpilog-mcp
./gradlew install
```

This installs to `~/.wpilog-mcp/`:
```
~/.wpilog-mcp/
├── jars/wpilog-mcp-{version}.jar        # versioned JAR
├── bin/
│   ├── wpilog-mcp-{version}[.bat]       # versioned launcher
│   └── wpilog-mcp[.bat]                 # symlink (Unix) or copy (Windows)
└── servers.yaml                          # server configurations
```

Add to your shell profile (`~/.zshrc` or `~/.bashrc`):
```bash
export PATH="${HOME}/.wpilog-mcp/bin:$PATH"
```

## Configuration

Edit `~/.wpilog-mcp/servers.yaml` (created by the installer with defaults):

```yaml
# Your FRC team number (used for TBA match lookups)
team: 2363

# Directory containing .wpilog files downloaded from the roboRIO
logdir: ~/riologs

# The Blue Alliance API key (get one at https://www.thebluealliance.com/account)
# tba_key: your-key-here

servers:

  # Default: stdio transport for MCP clients (Claude Desktop, etc.)
  default:
    transport: stdio

  # HTTP transport for browser-based or multi-client access
  http:
    transport: http
    port: 2363
```

Top-level settings (like `team` and `logdir`) are inherited by all server configs. Per-server values override defaults. Environment variable references (`${TBA_API_KEY}`) and tilde paths (`~/...`) are expanded automatically.

### Config Fields

| Field | Description | Default |
|-------|-------------|---------|
| `logdir` | Directory containing log files (scans subdirectories) | — |
| `team` | Default team number | — |
| `tba_key` | The Blue Alliance API key (supports `${TBA_API_KEY}`) | — |
| `transport` | `"stdio"` or `"http"` | `"stdio"` |
| `port` | HTTP port | `2363` |
| `diskcachedir` | Directory for persistent disk cache | OS default |
| `diskcachesize` | Max disk cache size (MB) | `8192` |
| `diskcachedisable` | Disable persistent disk cache | `false` |
| `exportdir` | Directory for CSV exports | `{tmpdir}/wpilog-export/` |
| `scandepth` | Max directory depth for log/revlog file scanning | `5` |
| `debug` | Enable debug logging | `false` |

String values support `${ENV_VAR}` interpolation and `~/` tilde expansion.

### Named Server Configurations

Running `wpilog-mcp` with no arguments loads the `"default"` configuration. Use `start <name>` to select a different one:

```bash
wpilog-mcp                          # starts the "default" config
wpilog-mcp start http               # starts the "http" config
```

Config file search order:
1. `--config <path>` (explicit override)
2. `.wpilog-mcp.yaml` (project-local)
3. `.wpilog-mcp.json` (project-local)
4. `~/.wpilog-mcp/servers.yaml` (install directory)
5. `~/.wpilog-mcp/servers.json` (install directory, legacy)

### Command-Line Overrides

Any config field can also be passed as a CLI flag:

```bash
wpilog-mcp -logdir /media/usb/logs -debug
wpilog-mcp start http --port 9000
```

| Flag | Env Variable |
|------|-------------|
| `-logdir <path>` | `WPILOG_DIR` |
| `-team <number>` | `WPILOG_TEAM` |
| `-tba-key <key>` | `TBA_API_KEY` |
| `-diskcachedir <path>` | `WPILOG_DISK_CACHE_DIR` |
| `-diskcachesize <mb>` | `WPILOG_DISK_CACHE_SIZE` |
| `-diskcachedisable` | `WPILOG_DISK_CACHE_DISABLE` |
| `-exportdir <path>` | `WPILOG_EXPORT_DIR` |
| `-scandepth <n>` | `WPILOG_SCAN_DEPTH` |
| `--http` | `WPILOG_HTTP` |
| `--port <port>` | `WPILOG_HTTP_PORT` |
| — | `WPILOG_HTTP_BIND` |
| — | `WPILOG_HTTP_PATH` |
| — | `WPILOG_HTTP_ALLOWED_ORIGINS` |
| `-debug` | `WPILOG_DEBUG` |

**Precedence:** CLI flags > environment variables > config file per-server values > config file defaults.

The launcher script sets `WPILOG_MAX_HEAP` (default `4g`) for JVM heap size. Large log files (hundreds of MB) may need `8g` or more.

## MCP Client Setup

### Claude Code CLI

Add to `~/.claude/settings.json`:
```json
{
  "mcpServers": {
    "wpilog": {
      "command": "wpilog-mcp"
    }
  }
}
```

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

### HTTP Transport

For browser-based or multi-client access:
```bash
wpilog-mcp start http
```

See [MCP Protocol](https://modelcontextprotocol.io/) for other client implementations.

## Upgrading

Pull the latest code and re-run the installer:
```bash
cd wpilog-mcp
git pull
./gradlew install
```

The installer overwrites the JAR and launcher but preserves your `servers.yaml` configuration.

## Uninstalling

Remove the install directory and the PATH entry:
```bash
rm -rf ~/.wpilog-mcp
```

Then remove the `export PATH=...` line from your shell profile.

## Troubleshooting

- **Server shows "Failed"** — Test manually:
  ```bash
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | wpilog-mcp
  ```
- **Check config files**:
  - Server config: `~/.wpilog-mcp/servers.yaml`
  - Claude Code CLI: `~/.claude/settings.json`
  - Claude Desktop: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Server times out** — Usually wrong Java version or incorrect path. Verify with:
  ```bash
  java -version   # Should show 17.x.x or 21.x.x
  ```
- **Out of memory with large logs** — Set `WPILOG_MAX_HEAP=8g` in your environment.
- **Log files show as corrupted** — Truncated logs (from robot power loss) are handled gracefully. The server recovers as much data as possible and marks the log as truncated.

## Containerization

You can run wpilog-mcp in a Docker container for team-shared or cloud-hosted deployments. The following `Dockerfile` uses a multi-stage build to keep the runtime image small.

*Thanks to [Godmar Back](https://github.com/godmar) for contributing this setup.*

**`Dockerfile`:**
```dockerfile
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy Gradle wrapper and config first for dependency caching
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

# Copy source and build fat JAR
COPY src/ src/
RUN ./gradlew --no-daemon shadowJar -x test && \
    cp build/libs/wpilog-mcp-*-all.jar build/libs/wpilog-mcp.jar

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/build/libs/wpilog-mcp.jar ./wpilog-mcp.jar

RUN mkdir -p /logs

ENV WPILOG_DIR=/logs
ENV TBA_API_KEY=""
ENV WPILOG_TEAM=""
ENV WPILOG_HTTP=true
ENV WPILOG_HTTP_PORT=8000
ENV WPILOG_HTTP_BIND=0.0.0.0
ENV WPILOG_HTTP_PATH=/wpilogmcp

EXPOSE 8000

ENTRYPOINT ["java", "-Xmx4g", "-jar", "/app/wpilog-mcp.jar", "--http", "--port", "8000"]
```

**Build and run:**
```bash
docker build -t wpilog-mcp .
docker run -p 8000:8000 \
  -v /path/to/your/logs:/logs \
  -e TBA_API_KEY=your_key_here \
  -e WPILOG_TEAM=2363 \
  wpilog-mcp
```

The server will be available at `http://localhost:8000/wpilogmcp`. Mount your log directory to `/logs` and pass configuration via environment variables.
