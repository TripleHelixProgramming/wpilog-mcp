# Standalone Install (without VS Code)

The standalone install runs wpilog-mcp for MCP clients outside VS Code: Claude Code, Claude Desktop, Gemini, or any other MCP client. If you work in VS Code, the extension is usually the better choice; see [Extension or Standalone?](../README.md#extension-or-standalone).

The server is designed for and tested with Claude. Other MCP clients work too, but the depth and quality of the analysis depend on the model.

## Requirements

- JDK 17 or newer (the WPILib JDK is recommended)
- Nothing else: the WPILib libraries are bundled in the JAR

The launcher uses the newest WPILib JDK it finds, then `JAVA_HOME` (macOS and Linux), then `java` on your `PATH`. Building with `./gradlew` uses `JAVA_HOME`, or `java` on your `PATH`. WPILib puts its JDK here:
- macOS and Linux: `~/wpilib/2026/jdk/bin/java`
- Windows: `C:\Users\Public\wpilib\2026\jdk\bin\java.exe`

## Install

To install the latest release, run the installer for your system. The installers skip pre-releases; to try one from the [releases page](https://github.com/TripleHelixProgramming/wpilog-mcp/releases), build the code instead (below), which is what a pre-release is made from.

macOS and Linux:
```bash
curl -fsSL https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.sh | sh
```

Windows (PowerShell):
```powershell
irm https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.ps1 | iex
```

To build the current code instead, clone the repository and run the Gradle installer:
```bash
git clone https://github.com/TripleHelixProgramming/wpilog-mcp.git
cd wpilog-mcp
./gradlew install
```

Either way, the server is installed in `~/.wpilog-mcp/`:
```
~/.wpilog-mcp/
├── jars/wpilog-mcp-{version}.jar        # one JAR per installed version
├── bin/
│   ├── wpilog-mcp-{version}[.bat]       # that version's launcher
│   └── wpilog-mcp[.bat]                 # the current version: a symlink (macOS, Linux) or a copy (Windows)
└── servers.yaml                         # server configurations
```

Starting an HTTP server with `start` adds `run/` (its process ID, and a lock file that makes starts take turns) and `logs/` (its log).

MCP clients start the launcher by its full path, so you need it on your `PATH` only to run `wpilog-mcp` in a terminal. On macOS and Linux, add this to your shell profile (`~/.zshrc` or `~/.bashrc`):
```bash
export PATH="${HOME}/.wpilog-mcp/bin:$PATH"
```
On Windows, the installer prints the folder to add to your `Path`.

## Configuration

Edit `~/.wpilog-mcp/servers.yaml`. The installer creates it and never overwrites it. Set your team number there: the installer leaves it out, because it can't know it.

```yaml
# Your FRC team number, for The Blue Alliance match data when a log does not record one.
# Remove the # and put in your number:
# team: 1234

# Directory containing .wpilog files downloaded from the roboRIO
logdir: ~/riologs
# Or several directories, listed together (for example an archive drive, or logs another
# team published):
# logdir:
#   - ~/riologs
#   - /Volumes/LOGS/archive

# The Blue Alliance API key (get one at https://www.thebluealliance.com/account).
# Remove the # and put in your key, or set the TBA_API_KEY environment variable instead:
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

The installer's file also has a `stresstest` server, which only the project's stress tests use. It has no settings of its own, so it takes the ones at the top, the TBA key among them. Put the key at the top level, not under one server: a key under `stresstest` reaches no other server.

Top-level settings such as `team` and `logdir` apply to every server, and a server's own value overrides the top-level one.

### Config Fields

| Field | Description | Default |
|-------|-------------|---------|
| `logdir` | Directory of log files (subdirectories are searched too), or a list of directories | None; tools then accept a log at any path |
| `team` | Your team number, for logs that do not record one | None |
| `tba_key` | The Blue Alliance API key | `TBA_API_KEY` from the environment |
| `transport` | `stdio` or `http` | `stdio` |
| `port` | HTTP port | `2363` |
| `diskcachedir` | Directory for the persistent disk cache | `WPILOG_DISK_CACHE_DIR` from the environment, else the system's default (below) |
| `diskcachesize` | Maximum disk cache size, in MB | `8192` |
| `diskcachedisable` | Turn off the persistent disk cache | `false` |
| `exportdir` | Directory for CSV exports | `wpilog-export` in the system's temporary directory |
| `scandepth` | How many directory levels to search for `.wpilog` and `.revlog` files | `5` |
| `debug` | Debug logging | `false` |

`logdir`, `tba_key`, `diskcachedir`, and `exportdir` can include environment variables, written `${NAME}`. A variable that is not set is left as written, and the server warns about it at startup; in `tba_key` the key then counts as not set.

In `logdir`, `diskcachedir`, and `exportdir`, a path starting with `~/` is in your home folder on macOS, Linux, and Windows alike. Forward slashes work in paths on every system, so prefer them (`~/riologs`, `/Volumes/LOGS/archive`, `D:/frc-logs`).

The default disk cache directory is `~/Library/Application Support/wpilog-mcp/cache` on macOS, `wpilog-mcp/cache` under `%LOCALAPPDATA%` on Windows, and `wpilog-mcp/cache` under `$XDG_DATA_HOME` (or `~/.local/share`) on Linux.

### Several Log Directories

`logdir` takes one directory or a list. `list_available_logs` lists the logs of every directory together, newest first, and names them in `log_directories`; a log reached from two of them (one inside the other, or the same directory under two names) is listed once. Tools can load logs from any of the directories, and from nowhere else.

- A directory that cannot be read (a drive not mounted, no permission) does not stop the others: the listing is `partial` and names it in `skipped` with the reason. The server warns at startup about a directory that does not exist yet.
- REV logs are matched to a wpilog only within the configured directory that holds it (and the wpilog's own folder). The other directories are not searched, so a REV log another team recorded at the same event, which would match by time, is never synchronized with your log.
- A server's own `logdir` replaces the top-level list rather than adding to it.
- On the command line, repeat `-logdir`; in `WPILOG_DIR`, separate directories as in `PATH` (`:`, or `;` on Windows). `-logdir` replaces `WPILOG_DIR`'s directories. The server expands `~` only in the configuration file. In a flag or an environment variable, your shell expands it or nothing does: macOS and Linux shells do, but Windows Command Prompt and MCP client configurations do not, so there give the full path.
- `scandepth` counts from each directory.

### Named Server Configurations

With no arguments, `wpilog-mcp` starts the `default` server. `start <name>` starts another:

```bash
wpilog-mcp                                   # the "default" server
wpilog-mcp start http                        # the "http" server
wpilog-mcp start default --config ~/frc/wpilog.yaml
```

The server reads the first configuration file it finds:
1. The file given with `--config <path>`
2. `.wpilog-mcp.yaml` in the current directory
3. `.wpilog-mcp.json` in the current directory
4. `~/.wpilog-mcp/servers.yaml`
5. `~/.wpilog-mcp/servers.json` (older installs)

A JSON file uses the same keys as the YAML one.

After `start <name>`, the server reads only `--config` and `-debug` from the command line and ignores other flags. Of the environment variables in [Command-Line Flags](#command-line-flags), it reads only these:
- `TBA_API_KEY`, when the file sets no `tba_key`
- `WPILOG_DISK_CACHE_DIR`, when the file sets no `diskcachedir`
- `WPILOG_DEBUG`
- `WPILOG_HTTP_BIND`, `WPILOG_HTTP_PATH`, and `WPILOG_HTTP_ALLOWED_ORIGINS`, for an `http` server (see [HTTP Transport](#http-transport))

### Command-Line Flags

Started with flags instead of `start <name>`, the server reads no configuration file. It takes its settings from the flags and from environment variables, and a flag overrides its variable:

```bash
wpilog-mcp -logdir /media/usb/logs -debug
wpilog-mcp -logdir ~/riologs -logdir /media/usb/archive
wpilog-mcp -logdir ~/riologs --http --port 9000
```

| Flag | Environment variable | Config field |
|------|----------------------|--------------|
| `-logdir <path>` (repeatable) | `WPILOG_DIR` (several separated by `:`, or `;` on Windows) | `logdir` |
| `-team <number>` | `WPILOG_TEAM` | `team` |
| `-tba-key <key>` | `TBA_API_KEY` | `tba_key` |
| `-diskcachedir <path>` | `WPILOG_DISK_CACHE_DIR` | `diskcachedir` |
| `-diskcachesize <mb>` | `WPILOG_DISK_CACHE_SIZE` | `diskcachesize` |
| `-diskcachedisable` | `WPILOG_DISK_CACHE_DISABLE=true` | `diskcachedisable` |
| `-exportdir <path>` | `WPILOG_EXPORT_DIR` | `exportdir` |
| `-scandepth <n>` | `WPILOG_SCAN_DEPTH` | `scandepth` |
| `--http` | `WPILOG_HTTP=true` | `transport: http` |
| `--port <port>` | `WPILOG_HTTP_PORT` | `port` |
| `-debug` | `WPILOG_DEBUG=true` | `debug` |

`wpilog-mcp -debug` on its own is flag mode too, so it reads no configuration file. To debug a configured server, run `wpilog-mcp start default -debug` or set `debug: true`.

Three environment variables, with no flag or config field, set up the HTTP transport whether the server was started with flags or with `start`:
- `WPILOG_HTTP_BIND`: the address to listen on (default `127.0.0.1`; `0.0.0.0` accepts connections from other machines)
- `WPILOG_HTTP_PATH`: the endpoint path (default `/mcp`)
- `WPILOG_HTTP_ALLOWED_ORIGINS`: comma-separated host names to accept in a request's `Origin` header, besides `localhost`, `127.0.0.1`, and `[::1]`

`-help` prints every flag and variable, and `-version` prints the version.

The launcher sets the JVM's maximum heap from `WPILOG_MAX_HEAP` (default `4g`), and a server started in the background with `start` gets the same heap. Logs of several hundred MB may need `8g` or more.

## MCP Client Setup

### Claude Code

Register the server once, for all your projects:
```bash
claude mcp add --scope user wpilog -- ~/.wpilog-mcp/bin/wpilog-mcp
```
On Windows (Command Prompt): `claude mcp add --scope user wpilog -- cmd /c %USERPROFILE%\.wpilog-mcp\bin\wpilog-mcp.bat`

This stores the server in your own Claude Code configuration (`~/.claude.json`), not in any project. The launcher's path stays the same when you upgrade, so the registration keeps working. Run `/mcp` in Claude Code to check that `wpilog` is connected.

To add the server to a single project instead, put it in a `.mcp.json` at the project root:
```json
{
  "mcpServers": {
    "wpilog": {
      "command": "${HOME}/.wpilog-mcp/bin/wpilog-mcp"
    }
  }
}
```
Claude Code expands `${HOME}` in `.mcp.json` but not `~`. On Windows, use `"command": "cmd", "args": ["/c", "${USERPROFILE}\\.wpilog-mcp\\bin\\wpilog-mcp.bat"]`. Claude Code asks you to approve a server from `.mcp.json` the first time it sees it.

Written this way, the entry holds nothing specific to your computer. On macOS and Linux it works for any teammate who has the standalone install, so committing it is fine; a teammate without the install is asked to approve a server that then fails to start. If only you use it, add `.mcp.json` to `.gitignore` instead.

The server reads everything else from `~/.wpilog-mcp/servers.yaml`, including the TBA key (`tba_key`), so Claude Code needs no environment variables. If you also use the VS Code extension, see [Using It Alongside the Standalone Install](../vscode-extension/README.md#using-it-alongside-the-standalone-install): with a registration for all projects like the one above, the extension's own entries for Claude Code have to be turned off, or Claude Code starts both servers.

### Claude Desktop

Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows). Give the launcher's full path, because Claude Desktop does not search your shell's `PATH`:
```json
{
  "mcpServers": {
    "wpilog": {
      "command": "/Users/you/.wpilog-mcp/bin/wpilog-mcp"
    }
  }
}
```
On Windows, use `C:\\Users\\you\\.wpilog-mcp\\bin\\wpilog-mcp.bat`.

### HTTP Transport

For browser-based or multi-client access, start the `http` server from `servers.yaml`:
```bash
wpilog-mcp start http
```

The server starts in the background, and the command returns once it answers. Clients connect to `http://127.0.0.1:2363/mcp`: the port is the server's `port`, and `WPILOG_HTTP_PATH` changes the path. The server writes its log to `~/.wpilog-mcp/logs/http.log` and its process ID to the first line of `~/.wpilog-mcp/run/http.pid`. Running `start http` while it is up reports the running server rather than starting another. To stop it, end that process (`kill <pid>` on macOS and Linux).

The server listens only on `127.0.0.1` unless `WPILOG_HTTP_BIND` says otherwise (see [Command-Line Flags](#command-line-flags)); set it before `start`. `GET /health` answers as soon as the server is up. The HTTP transport has no authentication, so anyone who can reach the port can use the server.

Started with flags, `wpilog-mcp --http` runs the HTTP server in the foreground instead, as the Docker image below does.

See [modelcontextprotocol.io](https://modelcontextprotocol.io/) for other MCP clients.

## Upgrading

Run the installer again. The one-line installer gets the latest release; for the Gradle installer, pull the latest code first:
```bash
cd wpilog-mcp
git pull
./gradlew install
```

Either installer adds the new version's JAR and launcher, points `wpilog-mcp` at them, and leaves `servers.yaml` alone. Older versions stay in `jars/` and `bin/` until you delete them. MCP clients run the new version the next time they start the server. A running HTTP server keeps the old version until you stop it and start it again.

## Uninstalling

Stop any running HTTP server, then delete the install directory (on Windows, the `.wpilog-mcp` folder in your user folder):
```bash
rm -rf ~/.wpilog-mcp
```

Then take the `bin` folder off your `PATH` (the `export PATH=...` line in your shell profile) and remove the server from your MCP client's configuration. The disk cache is kept elsewhere (see [Config Fields](#config-fields)); delete it too to free the space.

## Troubleshooting

- **Server shows "Failed"**: run it by hand and read what it prints. A working server answers with a line of JSON; errors such as a missing or invalid configuration file are logged to standard error.
  ```bash
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | wpilog-mcp
  ```
- **Check the configuration files**:
  - Server: `~/.wpilog-mcp/servers.yaml`, or a `.wpilog-mcp.yaml` in the directory the server starts in
  - Claude Code: `~/.claude.json` (user scope) or the project's `.mcp.json`
  - Claude Desktop: `~/Library/Application Support/Claude/claude_desktop_config.json`
  - HTTP server log: `~/.wpilog-mcp/logs/<name>.log`
- **Server times out**: usually the Java version or a wrong path. The launcher needs Java 17 or newer and picks the newest WPILib JDK, then `JAVA_HOME` (macOS and Linux), then `java` on your `PATH`. Check the one it would use:
  ```bash
  ~/wpilib/2026/jdk/bin/java -version   # or: java -version
  ```
- **Out of memory with large logs**: set `WPILOG_MAX_HEAP=8g` in the environment the server starts in. For an MCP client, that is the `env` of the server's entry, since the client starts the launcher.
- **Log reported as truncated**: a log cut short (by a power loss on the robot, for example) still loads. The server reads every complete record and reports the log as `truncated`.

## Containerization

You can run wpilog-mcp in a Docker container for a team-shared or cloud-hosted server. This `Dockerfile` uses a multi-stage build to keep the runtime image small.

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

The server is then at `http://localhost:8000/wpilogmcp`. Mount your log directory at `/logs` and pass the rest of the configuration as environment variables (see [Command-Line Flags](#command-line-flags)): the container starts the server with flags, so it reads no `servers.yaml`. The heap is set by `-Xmx4g` in `ENTRYPOINT`, since the image does not use the launcher. A browser-based client sends an `Origin` header; if it names a host other than `localhost`, `127.0.0.1`, or `[::1]`, add that host name to `WPILOG_HTTP_ALLOWED_ORIGINS`.
