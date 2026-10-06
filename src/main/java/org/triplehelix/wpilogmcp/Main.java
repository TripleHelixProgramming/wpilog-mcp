/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.config.ConfigException;
import org.triplehelix.wpilogmcp.config.ConfigLoader;
import org.triplehelix.wpilogmcp.config.DaemonManager;
import org.triplehelix.wpilogmcp.config.InstallCommand;
import org.triplehelix.wpilogmcp.config.ServerConfig;
import org.triplehelix.wpilogmcp.game.GameKnowledgeBase;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.StdioBridge;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.store.ImportCommand;
import org.triplehelix.wpilogmcp.tba.TbaConfig;
import org.triplehelix.wpilogmcp.tools.DiscoveryTools;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * Main entry point for the wpilog-mcp server.
 *
 * <p>Supports two startup modes:
 * <ul>
 *   <li><b>Legacy CLI:</b> {@code java -jar wpilog-mcp.jar [options]}</li>
 *   <li><b>Named config:</b> {@code java -jar wpilog-mcp.jar start <name> [--config <path>]}</li>
 * </ul>
 *
 * <p>Two more verbs serve a named {@code http} server started in the background: {@code stop
 * <name>} ends it, and {@code connect <name>} (or {@code connect --url <url>}) relays a stdio
 * client to it, starting it first when it is not running, so that every client on a machine
 * shares one server (see {@link StdioBridge}).
 */
public class Main {
  private static final String VERSION = Version.VERSION;
  /** SimpleLogger's level property, read once, when the first logger in the JVM is created. */
  static final String LOG_LEVEL_PROPERTY = "org.slf4j.simpleLogger.defaultLogLevel";

  /**
   * Created on first use, not when this class loads: SimpleLogger fixes its level when the first
   * logger in the JVM is created, so {@link #main} decides the level ({@code -debug},
   * {@code WPILOG_DEBUG}, {@code debug: true} in the configuration) before anything logs.
   */
  private static Logger logger() {
    return LoggerHolder.LOGGER;
  }

  private static final class LoggerHolder {
    static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
  }

  public static void main(String[] args) {
    // Decide the log level before any logger exists (see logger()). A configuration file's
    // debug setting is applied where the file is loaded, still before the first log line.
    if (debugRequested(args, System.getenv("WPILOG_DEBUG"))) {
      enableDebugLogging();
    } else if (System.getProperty(LOG_LEVEL_PROPERTY) == null) {
      System.setProperty(LOG_LEVEL_PROPERTY, "info");
    }

    // Check for "--internal-daemon" flag (used by DaemonManager for HTTP daemon re-exec)
    if (args.length >= 2 && "--internal-daemon".equals(args[0])) {
      handleInternalDaemon(args);
      return;
    }

    // Check for "start" subcommand
    if (args.length >= 2 && "start".equals(args[0])) {
      handleStartCommand(args);
      return;
    }
    if (args.length >= 2 && "stop".equals(args[0])) {
      System.exit(runStop(args[1]));
      return;
    }
    if (args.length >= 2 && "connect".equals(args[0])) {
      System.exit(runConnect(args));
      return;
    }
    if (args.length >= 1 && "import".equals(args[0])) {
      System.exit(runImport(args));
      return;
    }
    if (args.length >= 1 && "install".equals(args[0])) {
      System.exit(runInstall(args));
      return;
    }

    // No args or CLI flags: default to "start default"
    // CLI flags (e.g., -logdir, -team) still go through handleLegacyCli for backwards compatibility
    if (args.length == 0) {
      handleStartCommand(new String[]{"start", "default"});
      return;
    }

    // CLI flag mode (backwards compatibility)
    handleLegacyCli(args);
  }

  /** Whether {@code -debug} is among the arguments or {@code WPILOG_DEBUG} is {@code true}. */
  static boolean debugRequested(String[] args, String wpilogDebug) {
    return Arrays.asList(args).contains("-debug")
        || "true".equalsIgnoreCase(wpilogDebug);
  }

  /**
   * Turns on debug logging. Effective only before the first logger is created, so every start
   * path calls it before its first log line.
   */
  static void enableDebugLogging() {
    System.setProperty(LOG_LEVEL_PROPERTY, "debug");
  }

  // ==================== Named Config Mode ====================

  /**
   * Loads a named configuration, applying its debug setting before the first log line (see
   * {@link #logger()}), then logs where it came from and any warnings.
   */
  private static ServerConfig loadConfig(String configName, Path configPath)
      throws ConfigException {
    var loaded = new ConfigLoader().loadDetailed(configName, configPath);
    if (Boolean.TRUE.equals(loaded.config().debug())) {
      enableDebugLogging();
    }
    loaded.warnings().forEach(warning -> logger().warn("{}", warning));
    logger().info("Loaded configuration '{}' from {}", configName, loaded.file());
    return loaded.config();
  }

  private static void handleStartCommand(String[] args) {
    var configName = args[1];
    Path configPath = null;

    // Parse optional --config flag
    for (int i = 2; i < args.length; i++) {
      if ("--config".equals(args[i]) && i + 1 < args.length) {
        configPath = Path.of(args[++i]);
      }
    }

    try {
      var config = loadConfig(configName, configPath);

      if (config.isHttp()) {
        // HTTP transport: spawn as daemon, or find the one already running on the port
        var daemon = new DaemonManager();
        if (daemon.spawnDaemon(configName, config.effectivePort(), configPath)) {
          System.exit(0);
        }
        logger().error("Failed to start server '{}'", configName);
        System.exit(1);
      } else {
        // Stdio transport: run in foreground
        logger().info("Starting wpilog-mcp server (config: {})...", configName);
        applyConfig(config);
        initializeAndRun(false, 2363, null, null, null, null, null);
      }
    } catch (ConfigException e) {
      logger().error("{}", e.getMessage());
      System.exit(1);
    }
  }

  /**
   * Internal entry point for the daemon child process.
   * Invoked with: {@code --internal-daemon <name> [--config <path>]}
   */
  private static void handleInternalDaemon(String[] args) {
    var configName = args[1];
    Path configPath = null;

    for (int i = 2; i < args.length; i++) {
      if ("--config".equals(args[i]) && i + 1 < args.length) {
        configPath = Path.of(args[++i]);
      }
    }

    try {
      var config = loadConfig(configName, configPath);
      logger().info("Starting wpilog-mcp daemon (config: {})...", configName);
      applyConfig(config);
      var daemonBind = System.getenv("WPILOG_HTTP_BIND");
      var daemonPath = System.getenv("WPILOG_HTTP_PATH");
      var daemonOrigins = parseAllowedOrigins(System.getenv("WPILOG_HTTP_ALLOWED_ORIGINS"));
      // The stop token comes from the start that spawned this daemon, in the environment
      var stopToken = System.getenv(DaemonManager.STOP_TOKEN_ENV);
      initializeAndRun(config.isHttp(), config.effectivePort(), daemonBind, daemonPath,
          daemonOrigins, stopToken, config.idleExit().orElse(null));
    } catch (ConfigException e) {
      logger().error("{}", e.getMessage());
      System.exit(1);
    }
  }

  /**
   * {@code stop <name>}: ends the named server started in the background.
   *
   * @return The exit code
   */
  static int runStop(String configName) {
    return new DaemonManager().stopDaemon(configName) ? 0 : 1;
  }

  static int runImport(String[] args) {
    try {
      var options = ImportCommand.parse(args);
      return ImportCommand.run(options,
          loadConfig(options.server(), options.config()), new DaemonManager(), System.out);
    } catch (IllegalArgumentException e) {
      logger().error("{}", e.getMessage());
      return 2;
    } catch (Exception e) {
      var cause = e instanceof ExecutionException ? e.getCause() : e;
      logger().error("Import failed: {}", cause.getMessage());
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return 1;
    }
  }

  /** Installation keeps stdout usable by the extension's --json reader, even on failure. */
  static int runInstall(String[] args) {
    try {
      var options = InstallCommand.parse(args);
      var summary = InstallCommand.install(options);
      System.out.println(options.json() ? summary.json() : summary.text());
      return 0;
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      return 2;
    } catch (IOException e) {
      System.err.println("Install failed: " + e.getMessage());
      return 1;
    }
  }

  /**
   * {@code connect <name> [--config <path>]} or {@code connect --url <url>}: relays standard
   * input and output to an HTTP server, as {@link StdioBridge} does. With a name, the server is
   * started first if it is not running, exactly as {@code start} would; with a URL, nothing is
   * started.
   *
   * @return The exit code: 0 when the client closed its end, 1 when the server could not be
   *     started or reached, 2 for a bad command line
   */
  static int runConnect(String[] args) {
    String url = null;
    String configName = null;
    Path configPath = null;
    var paths = new ArrayList<String>();
    Integer team = null;
    try {
      for (int i = 1; i < args.length; i++) {
        switch (args[i]) {
          case "--url" -> url = args[++i];
          case "--config" -> configPath = Path.of(args[++i]);
          case "--logdir" -> paths.add(args[++i]);
          case "--team" -> {
            team = Integer.valueOf(args[++i]);
            if (team <= 0) throw new IllegalArgumentException("team must be positive");
          }
          default -> {
            if (configName != null || args[i].startsWith("-")) throw new IllegalArgumentException();
            configName = args[i];
          }
        }
      }
      if ((url == null) == (configName == null)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      logger().error("Usage: wpilog-mcp connect <name> [--config <path>] | connect --url <url> "
          + "[--logdir <dir>]... [--team <n>]");
      return 2;
    }

    try {
      var loader = new ConfigLoader();
      var workingDirectory = Path.of("").toAbsolutePath();
      var project = loader.projectLease(workingDirectory);
      paths.addAll(project.paths());
      var registration = new JsonObject();
      var resolved = new JsonArray();
      paths.stream().map(path -> workingDirectory.resolve(path).toString()).distinct()
          .forEach(resolved::add);
      registration.add("paths", resolved);
      registration.addProperty("team", team == null ? project.team() : team);
      registration.addProperty("project_servers_ignored", project.serversIgnored());
      URI endpoint;
      if (url != null) {
        endpoint = StdioBridge.endpointFor(url);
      } else {
        var loaded = loader.loadHomeDetailed(configName, configPath);
        var config = loaded.config();
        if (Boolean.TRUE.equals(config.debug())) enableDebugLogging();
        loaded.warnings().forEach(warning -> logger().warn("{}", warning));
        logger().info("Loaded configuration '{}' from {}", configName, loaded.file());
        if (!config.isHttp()) {
          logger().error("Server '{}' uses the stdio transport; connect needs an http server. "
              + "Set transport: http in its configuration, or run it directly.", configName);
          return 1;
        }
        // Passing the chosen file matters: the spawned JVM inherits this project's cwd too.
        if (!new DaemonManager().spawnDaemon(configName, config.effectivePort(),
            loaded.file().toAbsolutePath())) {
          logger().error("Server '{}' could not be started", configName);
          return 1;
        }
        var path = System.getenv("WPILOG_HTTP_PATH");
        endpoint = URI.create("http://127.0.0.1:" + config.effectivePort()
            + (path == null || path.isEmpty() ? "/mcp" : path));
      }
      return connectTo(endpoint, System.in, paths.isEmpty() && !project.serversIgnored()
          ? null : registration);
    } catch (ConfigException | IllegalArgumentException e) {
      logger().error("{}", e.getMessage());
      return 1;
    }
  }

  /**
   * Runs a bridge from {@code input} to the endpoint on this process's standard output, which is
   * taken for the protocol: the JVM's {@code System.out} is sent to standard error first, as the
   * stdio server does, so nothing else can print on it.
   */
  static int connectTo(URI endpoint, InputStream input) {
    return connectTo(endpoint, input, null);
  }

  private static int connectTo(URI endpoint, InputStream input, JsonObject registration) {
    var protocolOut = System.out;
    System.setOut(System.err);
    logger().info("Connecting standard input and output to {}", endpoint);
    return new StdioBridge(endpoint, input, protocolOut, registration).run();
  }

  /**
   * Applies a ServerConfig to the singleton subsystems.
   */
  public static void applyConfig(ServerConfig config) {
    var logManager = LogManager.getInstance();
    var tbaConfig = TbaConfig.getInstance();

    // Debug mode (the start paths enabled it before their first log line; see logger())
    if (Boolean.TRUE.equals(config.debug())) {
      enableDebugLogging();
      logger().info("Debug logging enabled");
    }

    // Log directories
    if (config.logdirs() != null && !config.logdirs().isEmpty()) {
      configureLogDirectories(config.logdirs());
    }

    // Team number
    if (config.team() != null) {
      LogDirectory.getInstance().setDefaultTeamNumber(config.team());
      logger().debug("Default team number: {}", config.team());
    }

    // TBA API key: from the configuration, else the environment, and on to the client. Setting
    // it without applying it left whoever applies a configuration without starting the server
    // (the stress tests) with TBA "not configured".
    if (config.tbaKey() != null && !config.tbaKey().isEmpty()) {
      tbaConfig.setApiKey(config.tbaKey());
      logger().debug("TBA API key set from configuration");
    }
    tbaConfig.applyToClient();

    // Cache settings
    if (config.diskcachedir() != null && !config.diskcachedir().isEmpty()) {
      logManager.getCacheDirectory().setOverride(config.diskcachedir());
      logger().debug("Disk cache directory: {}", config.diskcachedir());
    }
    if (config.diskcachesize() != null) {
      logManager.getDiskCache().setMaxTotalSizeMb(config.diskcachesize());
      logger().debug("Disk cache size limit: {} MB", config.diskcachesize());
    }
    if (Boolean.TRUE.equals(config.diskcachedisable())) {
      logManager.getDiskCache().setEnabled(false);
      logManager.getSyncDiskCache().setEnabled(false);
      logger().info("Disk cache disabled");
    }

    // Export directory
    if (config.exportdir() != null && !config.exportdir().isEmpty()) {
      ExportTools.setExportDirectory(config.exportdir());
    }

    // Directory scan depth
    if (config.scandepth() != null) {
      LogDirectory.getInstance().setScanDepth(config.scandepth());
    }
  }

  /**
   * Sets the directories logs are listed from and may be loaded from, warning about any that does
   * not exist (yet: a drive may be mounted later).
   */
  static void configureLogDirectories(List<String> dirs) {
    var logDirectory = LogDirectory.getInstance();
    logDirectory.setLogDirectories(dirs);
    var logManager = LogManager.getInstance();
    for (var dir : logDirectory.getLogDirectories()) {
      logger().info("Configuring log directory: {}", dir);
      logManager.addAllowedDirectory(dir);
      if (!Files.isDirectory(dir)) {
        logger().warn("Log directory {} does not exist; its logs are listed once it does", dir);
      }
    }
  }

  // ==================== Legacy CLI Mode ====================

  private static void handleLegacyCli(String[] args) {
    logger().info("Starting wpilog-mcp server...");

    var tbaConfig = TbaConfig.getInstance();
    var logManager = LogManager.getInstance();

    // Read environment variable defaults (CLI flags override these)
    var logDirs = new ArrayList<>(splitPathList(System.getenv("WPILOG_DIR")));
    boolean logDirsFromCli = false;
    boolean httpMode = "true".equalsIgnoreCase(System.getenv("WPILOG_HTTP"));
    int httpPort = parseEnvInt("WPILOG_HTTP_PORT", 2363);
    String httpBind = System.getenv("WPILOG_HTTP_BIND");
    String httpPath = System.getenv("WPILOG_HTTP_PATH");
    var allowedOrigins = parseAllowedOrigins(System.getenv("WPILOG_HTTP_ALLOWED_ORIGINS"));

    applyEnvLong("WPILOG_DISK_CACHE_SIZE", v -> logManager.getDiskCache().setMaxTotalSizeMb(v));
    if ("true".equalsIgnoreCase(System.getenv("WPILOG_DISK_CACHE_DISABLE"))) {
      logManager.getDiskCache().setEnabled(false);
      logManager.getSyncDiskCache().setEnabled(false);
    }
    var envExportDir = System.getenv("WPILOG_EXPORT_DIR");
    if (envExportDir != null && !envExportDir.isEmpty()) {
      ExportTools.setExportDirectory(envExportDir);
    }
    applyEnvInt("WPILOG_SCAN_DEPTH", v -> LogDirectory.getInstance().setScanDepth(v));

    // Parse command line arguments (override env vars)
    for (int i = 0; i < args.length; i++) {
      var arg = args[i];
      if (arg.equals("-help") || arg.equals("-h")) {
        printUsage();
        System.exit(0);
      } else if (arg.equals("-version") || arg.equals("-v") || arg.equals("--version")) {
        System.out.println("wpilog-mcp version " + VERSION);
        System.exit(0);
      } else if (arg.equals("-debug")) {
        // Enabled by main() before the first logger was created
        logger().info("Debug logging enabled");
      } else if (arg.equals("-logdir")) {
        if (i + 1 < args.length) {
          if (!logDirsFromCli) {
            // The first -logdir replaces WPILOG_DIR's directories; each further one adds one
            logDirs.clear();
            logDirsFromCli = true;
          }
          logDirs.add(args[++i]);
          logger().debug("Log directory added from command line: {}", args[i]);
        } else {
          logger().error("Error: -logdir requires a path argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-tba-key")) {
        if (i + 1 < args.length) {
          var key = args[++i];
          tbaConfig.setApiKey(key);
          logger().debug("TBA API key provided via command line");
        } else {
          logger().error("Error: -tba-key requires an API key argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-team")) {
        if (i + 1 < args.length) {
          try {
            int team = Integer.parseInt(args[++i]);
            LogDirectory.getInstance().setDefaultTeamNumber(team);
            logger().debug("Default team number set from command line: {}", team);
          } catch (NumberFormatException e) {
            logger().error("Error: -team requires a numeric team number");
            printUsage();
            System.exit(1);
          }
        } else {
          logger().error("Error: -team requires a team number argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-diskcachedir")) {
        if (i + 1 < args.length) {
          var dir = args[++i];
          LogManager.getInstance().getCacheDirectory().setOverride(dir);
          logger().debug("Disk cache directory set from command line: {}", dir);
        } else {
          logger().error("Error: -diskcachedir requires a path argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-diskcachesize")) {
        if (i + 1 < args.length) {
          try {
            long sizeMb = Long.parseLong(args[++i]);
            if (sizeMb < 1) {
              logger().error("Error: -diskcachesize must be at least 1 MB");
              printUsage();
              System.exit(1);
            }
            LogManager.getInstance().getDiskCache().setMaxTotalSizeMb(sizeMb);
            logger().debug("Disk cache size limit set from command line: {} MB", sizeMb);
          } catch (NumberFormatException e) {
            logger().error("Error: -diskcachesize requires a numeric value (MB)");
            printUsage();
            System.exit(1);
          }
        } else {
          logger().error("Error: -diskcachesize requires a number argument (MB)");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-diskcachedisable")) {
        LogManager.getInstance().getDiskCache().setEnabled(false);
        LogManager.getInstance().getSyncDiskCache().setEnabled(false);
        logger().info("Disk cache disabled");
      } else if (arg.equals("-exportdir")) {
        if (i + 1 < args.length) {
          ExportTools.setExportDirectory(args[++i]);
        } else {
          logger().error("Error: -exportdir requires a path argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-scandepth")) {
        if (i + 1 < args.length) {
          try {
            int depth = Integer.parseInt(args[++i]);
            LogDirectory.getInstance().setScanDepth(depth);
          } catch (NumberFormatException e) {
            logger().error("Error: -scandepth requires a numeric value");
            printUsage();
            System.exit(1);
          }
        } else {
          logger().error("Error: -scandepth requires a number argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("--http")) {
        httpMode = true;
        logger().info("HTTP transport enabled");
      } else if (arg.equals("--port")) {
        if (i + 1 < args.length) {
          try {
            httpPort = Integer.parseInt(args[++i]);
            if (httpPort < 1 || httpPort > 65535) {
              logger().error("Error: --port must be between 1 and 65535");
              printUsage();
              System.exit(1);
            }
            logger().debug("HTTP port set from command line: {}", httpPort);
          } catch (NumberFormatException e) {
            logger().error("Error: --port requires a numeric value");
            printUsage();
            System.exit(1);
          }
        } else {
          logger().error("Error: --port requires a port number argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.startsWith("-")) {
        logger().error("Unknown option: {}", arg);
        printUsage();
        System.exit(1);
      }
    }

    // Configure log directories
    if (!logDirs.isEmpty()) {
      configureLogDirectories(logDirs);
    } else {
      logger().warn("No log directory configured. Use -logdir or WPILOG_DIR env var.");
    }

    // Configure default team number from environment if not set via command line
    if (LogDirectory.getInstance().getDefaultTeamNumber() == null) {
      var envTeam = System.getenv("WPILOG_TEAM");
      if (envTeam != null && !envTeam.isEmpty()) {
        try {
          int team = Integer.parseInt(envTeam);
          LogDirectory.getInstance().setDefaultTeamNumber(team);
          logger().info("Default team number set from WPILOG_TEAM env var: {}", team);
        } catch (NumberFormatException e) {
          logger().warn("Invalid WPILOG_TEAM environment variable: {}", envTeam);
        }
      }
    }

    // The TBA key is -tba-key when given, else TBA_API_KEY: initializeAndRun applies it, filling
    // it from the environment only when no key was set.
    initializeAndRun(httpMode, httpPort, httpBind, httpPath, allowedOrigins, null, null);
  }

  // ==================== Shared Startup ====================

  /**
   * Initializes subsystems and starts the server. Called by both CLI and config modes.
   *
   * @param stopToken The token {@code POST /stop} must carry, or null when the server cannot be
   *     stopped over HTTP (a server not spawned by {@code start})
   * @param idleExit How long with no session and no request before the server exits, or null
   *     for never
   */
  private static void initializeAndRun(boolean httpMode, int httpPort,
      String httpBind, String httpPath, Set<String> allowedOrigins,
      String stopToken, Duration idleExit) {
    var logManager = LogManager.getInstance();
    var tbaConfig = TbaConfig.getInstance();

    // The legacy command line sets the key without applying it
    tbaConfig.applyToClient();
    if (tbaConfig.isConfigured()) {
      logger().info("TBA enrichment enabled");
    } else {
      logger().info("TBA enrichment disabled (no API key found)");
    }

    // Run disk cache cleanup in background (non-blocking)
    if (logManager.getDiskCache().isEnabled()) {
      new Thread(() -> logManager.getDiskCache().cleanup(), "cache-cleanup").start();
    }

    // Verify bundled game data is accessible
    var currentGame = GameKnowledgeBase.getInstance().getCurrentGame();
    if (currentGame != null) {
      logger().info("Game data loaded: {} {}", currentGame.season(), currentGame.gameName());
    } else {
      logger().warn("No bundled game data for current season");
    }

    // Create tool registry and register all tools
    var toolRegistry = new ToolRegistry();
    WpilogTools.registerAll(toolRegistry);
    logger().debug("Registered all MCP tools");

    if (httpMode) {
      var httpTransport = new HttpTransport(toolRegistry, httpPort, httpBind, allowedOrigins, httpPath);
      // A stop request and the idle exit end the server as a signal would: the transport
      // finishes the calls in flight, then the shutdown hook closes the logs
      Runnable exit = () -> {
        httpTransport.stop();
        System.exit(0);
      };
      if (stopToken != null && !stopToken.isBlank()) {
        httpTransport.setStop(stopToken, exit);
      }
      if (idleExit != null) {
        httpTransport.setIdleExit(idleExit, exit);
      }
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        logger().info("Shutdown signal received");
        // Order matters: drain in-flight HTTP requests first, then shut down LogManager
        // so that in-flight tool calls don't encounter closed logs.
        httpTransport.stop();
        logManager.shutdown();
      }, "shutdown-hook"));
      try {
        httpTransport.start();
        // The guide tells an agent with shell access where to get every sample of an entry
        DiscoveryTools.setDataEndpoint(
            httpTransport.dataEndpointUrl());
        Thread.currentThread().join();
      } catch (IOException e) {
        logger().error("Fatal HTTP server error: {}", e.getMessage(), e);
        System.exit(1);
      } catch (InterruptedException e) {
        logger().info("Server interrupted, shutting down");
        httpTransport.stop();
      }
    } else {
      var finalLogManager = logManager;
      logManager.stores().startWatching();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        logger().debug("Stdio shutdown: shutting down LogManager");
        finalLogManager.shutdown();
      }, "stdio-shutdown-hook"));
      var server = new McpServer(toolRegistry);
      try {
        server.run();
      } catch (IOException e) {
        logger().error("Fatal server error: {}", e.getMessage(), e);
        System.exit(1);
      }
    }
  }

  // ==================== Usage ====================

  private static void printUsage() {
    logger().info("Usage: wpilog-mcp [options]");
    logger().info("       wpilog-mcp start <config-name> [--config <path>]");
    logger().info("       wpilog-mcp stop <config-name>");
    logger().info("       wpilog-mcp connect <config-name> [--config <path>] [--logdir <dir>]... [--team <n>]");
    logger().info("       wpilog-mcp connect --url <url> [--logdir <dir>]... [--team <n>]");
    logger().info("       {}", ImportCommand.USAGE);
    logger().info("");
    logger().info("With no arguments, starts the \"default\" server configuration.");
    logger().info("");
    logger().info("Commands:");
    logger().info("  start <name>        Start a named server from servers.yaml");
    logger().info("  stop <name>         Stop a named http server started in the background");
    logger().info("  connect <name>      Relay stdin/stdout to a named http server, starting it if needed");
    logger().info("  connect --url <url> Relay stdin/stdout to an MCP server at a URL");
    logger().info("  import <path>...   Import logs into the configured store, using its daemon when running");
    logger().info("  install           Install this JAR: [--install-dir <dir>] [--logdir <dir>]... [--team <n>] [--force] [--refresh] [--with-extension --vsix <file>] [--json]");
    logger().info("  --config <path>     Explicit config file path (default: auto-discover)");
    logger().info("");
    logger().info("Options:");
    logger().info("  -logdir <path>    Directory of log files (repeat for several)");
    logger().info("  -team <number>    Default team number for logs missing metadata");
    logger().info("  -tba-key <key>    The Blue Alliance API key for match data");
    logger().info("  -diskcachedir <path> Set directory for persistent disk cache");
    logger().info("  -diskcachesize <mb>  Max disk cache size in MB (default: 8192)");
    logger().info("  -diskcachedisable    Disable persistent disk cache");
    logger().info("  -exportdir <path> Set directory for CSV exports (default: {tmpdir}/wpilog-export/)");
    logger().info("  -scandepth <n>   Max directory depth for log file scanning (default: 5)");
    logger().info("  --http            Use HTTP transport instead of stdio");
    logger().info("  --port <port>     HTTP port (default: 2363, requires --http)");
    logger().info("  -debug            Enable debug logging");
    logger().info("  -version, -v      Show version information");
    logger().info("  -help, -h         Show this help message");
    logger().info("");
    logger().info("Environment variables (CLI flags override these):");
    logger().info("  WPILOG_DIR             Directories of log files, separated by '{}'",
        File.pathSeparator);
    logger().info("  WPILOG_TEAM            Default team number for logs missing metadata");
    logger().info("  TBA_API_KEY            The Blue Alliance API key");
    logger().info("  WPILOG_DISK_CACHE_DIR     Directory for persistent disk cache");
    logger().info("  WPILOG_DISK_CACHE_SIZE    Max disk cache size in MB (default: 8192)");
    logger().info("  WPILOG_DISK_CACHE_DISABLE Set to 'true' to disable persistent disk cache");
    logger().info("  WPILOG_EXPORT_DIR      Directory for CSV exports (default: {tmpdir}/wpilog-export/)");
    logger().info("  WPILOG_SCAN_DEPTH      Max directory depth for scanning (default: 5)");
    logger().info("  WPILOG_HTTP            Set to 'true' to use HTTP transport");
    logger().info("  WPILOG_HTTP_PORT       HTTP port (default: 2363)");
    logger().info("  WPILOG_HTTP_BIND       HTTP bind address (default: 127.0.0.1, use 0.0.0.0 for containers)");
    logger().info("  WPILOG_HTTP_PATH       HTTP endpoint path (default: /mcp)");
    logger().info("  WPILOG_HTTP_ALLOWED_ORIGINS  Comma-separated hostnames for Origin validation");
    logger().info("  WPILOG_DEBUG           Set to 'true' to enable debug logging");
    logger().info("  WPILOG_MAX_HEAP        Max JVM heap size (default: 4g), read by the wpilog-mcp "
        + "launcher and by servers started in the background with 'start'");
    logger().info("");
    logger().info("Memory management is automatic — the server adapts to available JVM heap.");
    logger().info("To increase capacity, set WPILOG_MAX_HEAP in the MCP env block (e.g., 8g).");
  }

  /**
   * The directories in a path list such as {@code WPILOG_DIR}, separated as in {@code PATH}: by
   * {@code :}, or {@code ;} on Windows. Entries are trimmed and blank ones dropped.
   */
  static List<String> splitPathList(String value) {
    return splitPathList(value, File.pathSeparator);
  }

  /** As {@link #splitPathList(String)}, with the separator given (for testing). */
  static List<String> splitPathList(String value, String separator) {
    if (value == null) return List.of();
    return Arrays.stream(value.split(Pattern.quote(separator)))
        .map(String::strip)
        .filter(entry -> !entry.isEmpty())
        .toList();
  }

  private static Set<String> parseAllowedOrigins(String value) {
    if (value == null || value.isEmpty()) return Set.of();
    var origins = new HashSet<String>();
    for (var part : value.split(",")) {
      var trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        origins.add(trimmed);
      }
    }
    return Set.copyOf(origins);
  }

  private static int parseEnvInt(String name, int defaultValue) {
    var value = System.getenv(name);
    if (value == null || value.isEmpty()) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      logger().warn("Invalid {} environment variable '{}', using default {}", name, value, defaultValue);
      return defaultValue;
    }
  }

  private static void applyEnvInt(String name, IntConsumer setter) {
    var value = System.getenv(name);
    if (value == null || value.isEmpty()) return;
    try {
      int parsed = Integer.parseInt(value);
      if (parsed > 0) {
        setter.accept(parsed);
        logger().debug("{} set from environment: {}", name, parsed);
      }
    } catch (NumberFormatException e) {
      logger().warn("Invalid {} environment variable: {}", name, value);
    }
  }

  private static void applyEnvLong(String name, LongConsumer setter) {
    var value = System.getenv(name);
    if (value == null || value.isEmpty()) return;
    try {
      long parsed = Long.parseLong(value);
      if (parsed > 0) {
        setter.accept(parsed);
        logger().debug("{} set from environment: {}", name, parsed);
      }
    } catch (NumberFormatException e) {
      logger().warn("Invalid {} environment variable: {}", name, value);
    }
  }
}
