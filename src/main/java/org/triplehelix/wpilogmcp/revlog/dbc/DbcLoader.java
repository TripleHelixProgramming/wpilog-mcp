package org.triplehelix.wpilogmcp.revlog.dbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads DBC definitions using a hybrid approach with fallback resolution.
 *
 * <p>DBC files define how to decode CAN signals from REV motor controllers.
 * This loader supports multiple sources with priority-based fallback:
 *
 * <ol>
 *   <li><b>Command-line argument:</b> Explicit path passed to load()</li>
 *   <li><b>Config directory:</b> ~/.config/wpilog-mcp/rev_spark.dbc (Linux/Mac)
 *       or %APPDATA%/wpilog-mcp/rev_spark.dbc (Windows)</li>
 *   <li><b>Environment variable:</b> WPILOG_REV_DBC=/path/to/custom.dbc</li>
 *   <li><b>Embedded resource:</b> Built-in definitions (default)</li>
 * </ol>
 *
 * <p>This hybrid approach allows the tool to work out-of-the-box while supporting
 * updates to signal definitions without requiring a rebuild.
 *
 * @since 0.5.0
 */
public class DbcLoader {
  private static final Logger logger = LoggerFactory.getLogger(DbcLoader.class);

  /** Path to the embedded DBC resource. */
  private static final String EMBEDDED_RESOURCE = "/dbc/rev_spark.dbc";

  /** Name of the environment variable for DBC override. */
  private static final String ENV_VAR_NAME = "WPILOG_REV_DBC";

  /** Name of the DBC file in the config directory. */
  private static final String CONFIG_FILE_NAME = "rev_spark.dbc";

  private final DbcParser parser;

  /**
   * Creates a new DbcLoader.
   */
  public DbcLoader() {
    this.parser = new DbcParser();
  }

  /**
   * Loads DBC definitions with hybrid resolution.
   *
   * <p>Resolution order:
   * <ol>
   *   <li>Command-line override (if provided and exists)</li>
   *   <li>Config directory file (if exists)</li>
   *   <li>Environment variable (if set and file exists)</li>
   *   <li>Embedded resource (always available)</li>
   * </ol>
   *
   * @param commandLineOverride Optional path from command-line argument, or null
   * @return The loaded DBC database
   * @throws IOException if no DBC definitions could be loaded
   */
  public DbcDatabase load(String commandLineOverride) throws IOException {
    // 1. Command-line override
    if (commandLineOverride != null && !commandLineOverride.isBlank()) {
      Path path = Path.of(commandLineOverride);
      if (Files.exists(path)) {
        logger.info("Loading DBC from command-line: {}", path);
        return loadFromFile(path);
      }
      logger.warn("DBC file not found at command-line path: {}, falling back", path);
    }

    // 2. Config directory
    Path configDbc = getConfigDir().resolve(CONFIG_FILE_NAME);
    if (Files.exists(configDbc)) {
      logger.info("Loading DBC from config directory: {}", configDbc);
      return loadFromFile(configDbc);
    }

    // 3. Environment variable
    String envPath = System.getenv(ENV_VAR_NAME);
    if (envPath != null && !envPath.isBlank()) {
      Path path = Path.of(envPath);
      if (Files.exists(path)) {
        logger.info("Loading DBC from {}: {}", ENV_VAR_NAME, path);
        return loadFromFile(path);
      }
      logger.warn("DBC file not found at {} path: {}, falling back", ENV_VAR_NAME, path);
    }

    // 4. Embedded resource (default)
    logger.debug("Using embedded REV signal definitions");
    return loadEmbedded();
  }

  /**
   * Loads DBC definitions from the embedded resource only.
   *
   * @return The loaded DBC database
   * @throws IOException if the embedded resource cannot be loaded
   */
  public DbcDatabase loadEmbedded() throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(EMBEDDED_RESOURCE)) {
      if (stream == null) {
        throw new IOException("Embedded DBC resource not found: " + EMBEDDED_RESOURCE);
      }
      String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      return parser.parse(content);
    }
  }

  /**
   * Loads DBC definitions from a specific file.
   *
   * @param path The file path
   * @return The loaded DBC database
   * @throws IOException if the file cannot be read
   */
  public DbcDatabase loadFromFile(Path path) throws IOException {
    String content = Files.readString(path, StandardCharsets.UTF_8);
    return parser.parse(content);
  }

  /**
   * Loads DBC definitions from a string.
   *
   * @param content The DBC content
   * @return The loaded DBC database
   */
  public DbcDatabase loadFromString(String content) {
    return parser.parse(content);
  }

  /**
   * Gets the platform-specific config directory.
   *
   * <p>Returns:
   * <ul>
   *   <li>macOS: ~/Library/Application Support/wpilog-mcp</li>
   *   <li>Windows: %APPDATA%/wpilog-mcp</li>
   *   <li>Linux: ~/.config/wpilog-mcp</li>
   * </ul>
   *
   * @return The config directory path
   */
  public static Path getConfigDir() {
    String os = System.getProperty("os.name", "").toLowerCase();
    String home = System.getProperty("user.home");

    if (os.contains("mac")) {
      return Path.of(home, "Library", "Application Support", "wpilog-mcp");
    } else if (os.contains("win")) {
      String appData = System.getenv("APPDATA");
      if (appData != null && !appData.isBlank()) {
        return Path.of(appData, "wpilog-mcp");
      }
      return Path.of(home, "AppData", "Roaming", "wpilog-mcp");
    } else {
      // Linux and other Unix-like systems
      return Path.of(home, ".config", "wpilog-mcp");
    }
  }

  /**
   * Gets information about where DBC definitions would be loaded from.
   *
   * <p>Useful for diagnostics and user feedback.
   *
   * @param commandLineOverride Optional command-line override path
   * @return Description of the DBC source that would be used
   */
  public String getSourceDescription(String commandLineOverride) {
    if (commandLineOverride != null && !commandLineOverride.isBlank()) {
      Path path = Path.of(commandLineOverride);
      if (Files.exists(path)) {
        return "Command-line: " + path;
      }
    }

    Path configDbc = getConfigDir().resolve(CONFIG_FILE_NAME);
    if (Files.exists(configDbc)) {
      return "Config directory: " + configDbc;
    }

    String envPath = System.getenv(ENV_VAR_NAME);
    if (envPath != null && !envPath.isBlank() && Files.exists(Path.of(envPath))) {
      return "Environment variable (" + ENV_VAR_NAME + "): " + envPath;
    }

    return "Embedded resource (built-in)";
  }
}
