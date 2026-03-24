package org.triplehelix.wpilogmcp;

import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.config.ConfigException;
import org.triplehelix.wpilogmcp.config.ConfigLoader;
import org.triplehelix.wpilogmcp.config.DaemonManager;
import org.triplehelix.wpilogmcp.config.ServerConfig;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tba.TbaConfig;
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
 */
public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);
  private static final String VERSION = Version.VERSION;

  public static void main(String[] args) {
    // Set default log level if not specified
    if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
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

    // No args or CLI flags: default to "start default"
    // CLI flags (e.g., -logdir, -team) still go through handleLegacyCli for backwards compatibility
    if (args.length == 0) {
      handleStartCommand(new String[]{"start", "default"});
      return;
    }

    // CLI flag mode (backwards compatibility)
    handleLegacyCli(args);
  }

  // ==================== Named Config Mode ====================

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
      var loader = new ConfigLoader();
      var config = loader.load(configName, configPath);

      if (config.isHttp()) {
        // HTTP transport: spawn as daemon
        var daemon = new DaemonManager();
        int port = config.effectivePort();

        if (daemon.isAlreadyRunning(configName, port)) {
          System.exit(0);
        }

        if (daemon.spawnDaemon(configName, port, configPath)) {
          System.exit(0);
        } else {
          logger.error("Failed to start server '{}'", configName);
          System.exit(1);
        }
      } else {
        // Stdio transport: run in foreground
        logger.info("Starting wpilog-mcp server (config: {})...", configName);
        applyConfig(config);
        initializeAndRun(false, 2363);
      }
    } catch (ConfigException e) {
      logger.error("{}", e.getMessage());
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
      logger.info("Starting wpilog-mcp daemon (config: {})...", configName);
      var loader = new ConfigLoader();
      var config = loader.load(configName, configPath);
      applyConfig(config);
      initializeAndRun(config.isHttp(), config.effectivePort());
    } catch (ConfigException e) {
      logger.error("{}", e.getMessage());
      System.exit(1);
    }
  }

  /**
   * Applies a ServerConfig to the singleton subsystems.
   */
  public static void applyConfig(ServerConfig config) {
    var logManager = LogManager.getInstance();
    var tbaConfig = TbaConfig.getInstance();

    // Debug mode
    if (Boolean.TRUE.equals(config.debug())) {
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
      logger.info("Debug logging enabled");
    }

    // Log directory
    if (config.logdir() != null && !config.logdir().isEmpty()) {
      logger.info("Configuring log directory: {}", config.logdir());
      LogDirectory.getInstance().setLogDirectory(config.logdir());
      logManager.addAllowedDirectory(config.logdir());
    }

    // Team number
    if (config.team() != null) {
      LogDirectory.getInstance().setDefaultTeamNumber(config.team());
      logger.debug("Default team number: {}", config.team());
    }

    // TBA API key
    if (config.tbaKey() != null && !config.tbaKey().isEmpty()) {
      tbaConfig.setApiKey(config.tbaKey());
      logger.debug("TBA API key set from configuration");
    }

    // Cache settings
    if (config.diskcachedir() != null && !config.diskcachedir().isEmpty()) {
      logManager.getCacheDirectory().setOverride(config.diskcachedir());
      logger.debug("Disk cache directory: {}", config.diskcachedir());
    }
    if (config.diskcachesize() != null) {
      logManager.getDiskCache().setMaxTotalSizeMb(config.diskcachesize());
      logger.debug("Disk cache size limit: {} MB", config.diskcachesize());
    }
    if (Boolean.TRUE.equals(config.diskcachedisable())) {
      logManager.getDiskCache().setEnabled(false);
      logManager.getSyncDiskCache().setEnabled(false);
      logger.info("Disk cache disabled");
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

  // ==================== Legacy CLI Mode ====================

  private static void handleLegacyCli(String[] args) {
    logger.info("Starting wpilog-mcp server...");

    var tbaConfig = TbaConfig.getInstance();
    var logManager = LogManager.getInstance();

    // Read environment variable defaults (CLI flags override these)
    var logDir = System.getenv("WPILOG_DIR");
    boolean httpMode = "true".equalsIgnoreCase(System.getenv("WPILOG_HTTP"));
    int httpPort = parseEnvInt("WPILOG_HTTP_PORT", 2363);
    boolean debugMode = "true".equalsIgnoreCase(System.getenv("WPILOG_DEBUG"));

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
    if (debugMode) {
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
    }

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
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        logger.info("Debug logging enabled");
      } else if (arg.equals("-logdir")) {
        if (i + 1 < args.length) {
          logDir = args[++i];
          logger.debug("Log directory set from command line: {}", logDir);
        } else {
          logger.error("Error: -logdir requires a path argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-tba-key")) {
        if (i + 1 < args.length) {
          var key = args[++i];
          tbaConfig.setApiKey(key);
          logger.debug("TBA API key provided via command line");
        } else {
          logger.error("Error: -tba-key requires an API key argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-team")) {
        if (i + 1 < args.length) {
          try {
            int team = Integer.parseInt(args[++i]);
            LogDirectory.getInstance().setDefaultTeamNumber(team);
            logger.debug("Default team number set from command line: {}", team);
          } catch (NumberFormatException e) {
            logger.error("Error: -team requires a numeric team number");
            printUsage();
            System.exit(1);
          }
        } else {
          logger.error("Error: -team requires a team number argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-diskcachedir")) {
        if (i + 1 < args.length) {
          var dir = args[++i];
          LogManager.getInstance().getCacheDirectory().setOverride(dir);
          logger.debug("Disk cache directory set from command line: {}", dir);
        } else {
          logger.error("Error: -diskcachedir requires a path argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-diskcachesize")) {
        if (i + 1 < args.length) {
          try {
            long sizeMb = Long.parseLong(args[++i]);
            if (sizeMb < 1) {
              logger.error("Error: -diskcachesize must be at least 1 MB");
              printUsage();
              System.exit(1);
            }
            LogManager.getInstance().getDiskCache().setMaxTotalSizeMb(sizeMb);
            logger.debug("Disk cache size limit set from command line: {} MB", sizeMb);
          } catch (NumberFormatException e) {
            logger.error("Error: -diskcachesize requires a numeric value (MB)");
            printUsage();
            System.exit(1);
          }
        } else {
          logger.error("Error: -diskcachesize requires a number argument (MB)");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-diskcachedisable")) {
        LogManager.getInstance().getDiskCache().setEnabled(false);
        LogManager.getInstance().getSyncDiskCache().setEnabled(false);
        logger.info("Disk cache disabled");
      } else if (arg.equals("-exportdir")) {
        if (i + 1 < args.length) {
          ExportTools.setExportDirectory(args[++i]);
        } else {
          logger.error("Error: -exportdir requires a path argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-scandepth")) {
        if (i + 1 < args.length) {
          try {
            int depth = Integer.parseInt(args[++i]);
            LogDirectory.getInstance().setScanDepth(depth);
          } catch (NumberFormatException e) {
            logger.error("Error: -scandepth requires a numeric value");
            printUsage();
            System.exit(1);
          }
        } else {
          logger.error("Error: -scandepth requires a number argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("--http")) {
        httpMode = true;
        logger.info("HTTP transport enabled");
      } else if (arg.equals("--port")) {
        if (i + 1 < args.length) {
          try {
            httpPort = Integer.parseInt(args[++i]);
            if (httpPort < 1 || httpPort > 65535) {
              logger.error("Error: --port must be between 1 and 65535");
              printUsage();
              System.exit(1);
            }
            logger.debug("HTTP port set from command line: {}", httpPort);
          } catch (NumberFormatException e) {
            logger.error("Error: --port requires a numeric value");
            printUsage();
            System.exit(1);
          }
        } else {
          logger.error("Error: --port requires a port number argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.startsWith("-")) {
        logger.error("Unknown option: {}", arg);
        printUsage();
        System.exit(1);
      }
    }

    // Configure log directory
    if (logDir != null && !logDir.isEmpty()) {
      logger.info("Configuring log directory: {}", logDir);
      LogDirectory.getInstance().setLogDirectory(logDir);
      LogManager.getInstance().addAllowedDirectory(logDir);
    } else {
      logger.warn("No log directory configured. Use -logdir or WPILOG_DIR env var.");
    }

    // Configure default team number from environment if not set via command line
    if (LogDirectory.getInstance().getDefaultTeamNumber() == null) {
      var envTeam = System.getenv("WPILOG_TEAM");
      if (envTeam != null && !envTeam.isEmpty()) {
        try {
          int team = Integer.parseInt(envTeam);
          LogDirectory.getInstance().setDefaultTeamNumber(team);
          logger.info("Default team number set from WPILOG_TEAM env var: {}", team);
        } catch (NumberFormatException e) {
          logger.warn("Invalid WPILOG_TEAM environment variable: {}", envTeam);
        }
      }
    }

    // Ensure we have the latest environment variables before applying
    tbaConfig.refreshFromEnvironment();

    // Initialize TBA client with configuration
    tbaConfig.applyToClient();
    if (tbaConfig.isConfigured()) {
      logger.info("TBA enrichment enabled");
    } else {
      logger.info("TBA enrichment disabled (no API key found in env or args)");
    }

    initializeAndRun(httpMode, httpPort);
  }

  // ==================== Shared Startup ====================

  /**
   * Initializes subsystems and starts the server. Called by both CLI and config modes.
   */
  private static void initializeAndRun(boolean httpMode, int httpPort) {
    var logManager = LogManager.getInstance();
    var tbaConfig = TbaConfig.getInstance();

    // Ensure TBA is applied (config mode sets apiKey but doesn't call applyToClient)
    tbaConfig.applyToClient();
    if (tbaConfig.isConfigured()) {
      logger.info("TBA enrichment enabled");
    } else {
      logger.info("TBA enrichment disabled (no API key found)");
    }

    // Run disk cache cleanup in background (non-blocking)
    if (logManager.getDiskCache().isEnabled()) {
      new Thread(() -> logManager.getDiskCache().cleanup(), "cache-cleanup").start();
    }

    // Verify bundled game data is accessible
    var currentGame = org.triplehelix.wpilogmcp.game.GameKnowledgeBase.getInstance().getCurrentGame();
    if (currentGame != null) {
      logger.info("Game data loaded: {} {}", currentGame.season(), currentGame.gameName());
    } else {
      logger.warn("No bundled game data for current season");
    }

    // Create tool registry and register all tools
    var toolRegistry = new ToolRegistry();
    WpilogTools.registerAll(toolRegistry);
    logger.debug("Registered all MCP tools");

    if (httpMode) {
      var httpTransport = new HttpTransport(toolRegistry, httpPort);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        logger.info("Shutdown signal received");
        httpTransport.stop();
        logManager.getDiskCache().shutdown();
      }, "shutdown-hook"));
      try {
        httpTransport.start();
        Thread.currentThread().join();
      } catch (IOException e) {
        logger.error("Fatal HTTP server error: {}", e.getMessage(), e);
        System.exit(1);
      } catch (InterruptedException e) {
        logger.info("Server interrupted, shutting down");
        httpTransport.stop();
      }
    } else {
      var finalLogManager = logManager;
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        logger.debug("Stdio shutdown: flushing disk cache");
        finalLogManager.getDiskCache().shutdown();
      }, "stdio-shutdown-hook"));
      var server = new McpServer(toolRegistry);
      try {
        server.run();
      } catch (IOException e) {
        logger.error("Fatal server error: {}", e.getMessage(), e);
        System.exit(1);
      }
    }
  }

  // ==================== Usage ====================

  private static void printUsage() {
    logger.info("Usage: wpilog-mcp [options]");
    logger.info("       wpilog-mcp start <config-name> [--config <path>]");
    logger.info("");
    logger.info("With no arguments, starts the \"default\" server configuration.");
    logger.info("");
    logger.info("Commands:");
    logger.info("  start <name>        Start a named server from servers.json");
    logger.info("  --config <path>     Explicit config file path (default: auto-discover)");
    logger.info("");
    logger.info("Options:");
    logger.info("  -logdir <path>    Set default directory for log files");
    logger.info("  -team <number>    Default team number for logs missing metadata");
    logger.info("  -tba-key <key>    The Blue Alliance API key for match data");
    logger.info("  -diskcachedir <path> Set directory for persistent disk cache");
    logger.info("  -diskcachesize <mb>  Max disk cache size in MB (default: 8192)");
    logger.info("  -diskcachedisable    Disable persistent disk cache");
    logger.info("  -exportdir <path> Set directory for CSV exports (default: {tmpdir}/wpilog-export/)");
    logger.info("  -scandepth <n>   Max directory depth for log file scanning (default: 5)");
    logger.info("  --http            Use HTTP transport instead of stdio");
    logger.info("  --port <port>     HTTP port (default: 2363, requires --http)");
    logger.info("  -debug            Enable debug logging");
    logger.info("  -version, -v      Show version information");
    logger.info("  -help, -h         Show this help message");
    logger.info("");
    logger.info("Environment variables (CLI flags override these):");
    logger.info("  WPILOG_DIR             Default directory for log files");
    logger.info("  WPILOG_TEAM            Default team number for logs missing metadata");
    logger.info("  TBA_API_KEY            The Blue Alliance API key");
    logger.info("  WPILOG_DISK_CACHE_DIR     Directory for persistent disk cache");
    logger.info("  WPILOG_DISK_CACHE_SIZE    Max disk cache size in MB (default: 8192)");
    logger.info("  WPILOG_DISK_CACHE_DISABLE Set to 'true' to disable persistent disk cache");
    logger.info("  WPILOG_EXPORT_DIR      Directory for CSV exports (default: {tmpdir}/wpilog-export/)");
    logger.info("  WPILOG_SCAN_DEPTH      Max directory depth for scanning (default: 5)");
    logger.info("  WPILOG_HTTP            Set to 'true' to use HTTP transport");
    logger.info("  WPILOG_HTTP_PORT       HTTP port (default: 2363)");
    logger.info("  WPILOG_DEBUG           Set to 'true' to enable debug logging");
    logger.info("  WPILOG_MAX_HEAP        Max JVM heap size (default: 4g, used by run-mcp.sh/bat)");
    logger.info("");
    logger.info("Memory management is automatic — the server adapts to available JVM heap.");
    logger.info("To increase capacity, set WPILOG_MAX_HEAP in the MCP env block (e.g., 8g).");
  }

  private static int parseEnvInt(String name, int defaultValue) {
    var value = System.getenv(name);
    if (value == null || value.isEmpty()) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      logger.warn("Invalid {} environment variable '{}', using default {}", name, value, defaultValue);
      return defaultValue;
    }
  }

  private static void applyEnvInt(String name, java.util.function.IntConsumer setter) {
    var value = System.getenv(name);
    if (value == null || value.isEmpty()) return;
    try {
      int parsed = Integer.parseInt(value);
      if (parsed > 0) {
        setter.accept(parsed);
        logger.debug("{} set from environment: {}", name, parsed);
      }
    } catch (NumberFormatException e) {
      logger.warn("Invalid {} environment variable: {}", name, value);
    }
  }

  private static void applyEnvLong(String name, java.util.function.LongConsumer setter) {
    var value = System.getenv(name);
    if (value == null || value.isEmpty()) return;
    try {
      long parsed = Long.parseLong(value);
      if (parsed > 0) {
        setter.accept(parsed);
        logger.debug("{} set from environment: {}", name, parsed);
      }
    } catch (NumberFormatException e) {
      logger.warn("Invalid {} environment variable: {}", name, value);
    }
  }
}
