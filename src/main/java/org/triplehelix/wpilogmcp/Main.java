package org.triplehelix.wpilogmcp;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.tba.TbaConfig;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * Main entry point for the wpilog-mcp server.
 *
 * <p>Starts an MCP server that provides tools for analyzing WPILOG files.
 */
public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);
  private static final String VERSION = "0.3.0";

  public static void main(String[] args) {
    // Set default log level if not specified
    if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
    }
    
    logger.info("Starting wpilog-mcp server...");

    var tbaConfig = TbaConfig.getInstance();
    var logDir = System.getenv("WPILOG_DIR");

    // Parse command line arguments
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
      } else if (arg.equals("-maxlogs")) {
        if (i + 1 < args.length) {
          try {
            int maxLogs = Integer.parseInt(args[++i]);
            if (maxLogs < 1) {
              logger.error("Error: -maxlogs must be at least 1");
              printUsage();
              System.exit(1);
            }
            LogManager.getInstance().setMaxLoadedLogs(maxLogs);
            logger.debug("Max loaded logs set from command line: {}", maxLogs);
          } catch (NumberFormatException e) {
            logger.error("Error: -maxlogs requires a numeric value");
            printUsage();
            System.exit(1);
          }
        } else {
          logger.error("Error: -maxlogs requires a number argument");
          printUsage();
          System.exit(1);
        }
      } else if (arg.equals("-maxmemory")) {
        if (i + 1 < args.length) {
          try {
            long maxMemory = Long.parseLong(args[++i]);
            if (maxMemory < 1) {
              logger.error("Error: -maxmemory must be at least 1 MB");
              printUsage();
              System.exit(1);
            }
            LogManager.getInstance().setMaxMemoryMb(maxMemory);
            logger.debug("Max memory for log cache set from command line: {} MB", maxMemory);
          } catch (NumberFormatException e) {
            logger.error("Error: -maxmemory requires a numeric value (MB)");
            printUsage();
            System.exit(1);
          }
        } else {
          logger.error("Error: -maxmemory requires a number argument (MB)");
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
      // Also configure as allowed directory for path security
      // This prevents path traversal attacks by restricting file access
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

    // Create and configure server
    var server = new McpServer();

    // Register all WPILOG tools
    WpilogTools.registerAll(server);
    logger.debug("Registered all MCP tools");

    try {
      server.run();
    } catch (IOException e) {
      logger.error("Fatal server error: {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  private static void printUsage() {
    logger.info("Usage: java -jar wpilog-mcp.jar [options]");
    logger.info("");
    logger.info("Options:");
    logger.info("  -logdir <path>    Set default directory for log files");
    logger.info("  -team <number>    Default team number for logs missing metadata");
    logger.info("  -tba-key <key>    The Blue Alliance API key for match data");
    logger.info("  -maxlogs <n>      Max number of logs to cache (default: 20)");
    logger.info("  -maxmemory <mb>   Max memory (MB) for log cache (alternative to -maxlogs)");
    logger.info("  -debug            Enable debug logging");
    logger.info("  -version, -v      Show version information");
    logger.info("  -help, -h         Show this help message");
    logger.info("");
    logger.info("Environment variables:");
    logger.info("  WPILOG_DIR        Default directory for log files");
    logger.info("  WPILOG_TEAM       Default team number for logs missing metadata");
    logger.info("  TBA_API_KEY       The Blue Alliance API key");
    logger.info("");
    logger.info("Cache limits:");
    logger.info("  -maxlogs sets a fixed number of logs to keep in memory.");
    logger.info("  -maxmemory sets a memory limit (only used if -maxlogs is not set).");
    logger.info("  If neither is set, defaults to 20 logs.");
  }
}
