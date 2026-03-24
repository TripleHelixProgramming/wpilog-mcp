package org.triplehelix.wpilogmcp.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and parses server configuration files.
 *
 * <p>Configuration files are JSON with a top-level {@code defaults} section (optional)
 * and a {@code servers} section containing named server configurations. Environment
 * variable references ({@code ${VAR_NAME}}) in string values are interpolated at load time.
 *
 * <p>File discovery order (first found wins):
 * <ol>
 *   <li>{@code --config <path>} CLI argument</li>
 *   <li>{@code .wpilog-mcp.json} in the current working directory (project-local override)</li>
 *   <li>{@code ~/.wpilog-mcp/servers.json} (install directory — primary location)</li>
 * </ol>
 *
 * @since 0.8.0
 */
public class ConfigLoader {
  private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
  private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
  private static final String APP_NAME = "wpilog-mcp";

  private final Function<String, String> envResolver;

  /** Creates a ConfigLoader using system environment variables. */
  public ConfigLoader() {
    this(System::getenv);
  }

  /**
   * Creates a ConfigLoader with a custom environment variable resolver (for testing).
   *
   * @param envResolver Function that maps env var names to values (null if unset)
   */
  public ConfigLoader(Function<String, String> envResolver) {
    this.envResolver = envResolver;
  }

  /**
   * Loads a named server configuration.
   *
   * <p>Discovers the config file, parses it, merges defaults, interpolates
   * environment variables, and expands tilde in path values.
   *
   * @param configName The server configuration name
   * @param explicitPath Optional explicit config file path (from --config flag), or null
   * @return The resolved ServerConfig
   * @throws ConfigException if the config file is not found, unparseable, or the name is missing
   */
  public ServerConfig load(String configName, Path explicitPath) throws ConfigException {
    var configFile = discoverConfigFile(explicitPath);
    var root = parseFile(configFile);

    // Parse defaults section
    ServerConfig defaults = null;
    if (root.has("defaults") && root.get("defaults").isJsonObject()) {
      defaults = parseServerBlock("_defaults", root.getAsJsonObject("defaults"));
    }

    // Parse servers section
    if (!root.has("servers") || !root.get("servers").isJsonObject()) {
      throw new ConfigException("Configuration file has no 'servers' section: " + configFile);
    }
    var servers = root.getAsJsonObject("servers");

    if (!servers.has(configName)) {
      var available = new ArrayList<>(servers.keySet());
      throw new ConfigException(
          "Unknown server configuration '" + configName + "'. Available: " + available);
    }

    var serverBlock = servers.getAsJsonObject(configName);
    var config = parseServerBlock(configName, serverBlock).mergeWithDefaults(defaults);

    // Validate
    validate(config);

    logger.info("Loaded configuration '{}' from {}", configName, configFile);
    return config;
  }

  /**
   * Lists all available server configuration names from the config file.
   *
   * @param explicitPath Optional explicit config file path, or null
   * @return List of server names
   * @throws ConfigException if the config file is not found or unparseable
   */
  public List<String> listConfigs(Path explicitPath) throws ConfigException {
    var configFile = discoverConfigFile(explicitPath);
    var root = parseFile(configFile);

    if (!root.has("servers") || !root.get("servers").isJsonObject()) {
      return List.of();
    }
    return new ArrayList<>(root.getAsJsonObject("servers").keySet());
  }

  /**
   * Discovers the configuration file path.
   *
   * @param explicitPath An explicit path from --config, or null for auto-discovery
   * @return The path to the config file
   * @throws ConfigException if no config file is found
   */
  Path discoverConfigFile(Path explicitPath) throws ConfigException {
    if (explicitPath != null) {
      if (Files.isRegularFile(explicitPath)) {
        return explicitPath;
      }
      throw new ConfigException("Configuration file not found: " + explicitPath);
    }

    var searchPaths = configSearchPaths();
    for (var path : searchPaths) {
      if (Files.isRegularFile(path)) {
        logger.debug("Found configuration file: {}", path);
        return path;
      }
    }

    throw new ConfigException(
        "No configuration file found. Searched: " + searchPaths
            + ". Create a config file or use --config <path>.");
  }

  /**
   * Returns the list of paths to search for config files, in priority order.
   */
  List<Path> configSearchPaths() {
    var paths = new ArrayList<Path>();
    String home = System.getProperty("user.home");

    // 1. Project-local override
    paths.add(Path.of(".wpilog-mcp.json"));

    // 2. Install directory (primary location — co-located with versions, bin, cache)
    paths.add(Path.of(home, "." + APP_NAME, "servers.json"));

    return paths;
  }

  private JsonObject parseFile(Path configFile) throws ConfigException {
    try {
      var content = Files.readString(configFile);
      var element = JsonParser.parseString(content);
      if (!element.isJsonObject()) {
        throw new ConfigException(
            "Invalid configuration file (expected JSON object): " + configFile);
      }
      return element.getAsJsonObject();
    } catch (IOException e) {
      throw new ConfigException("Failed to read configuration file: " + configFile + ": " + e.getMessage());
    } catch (com.google.gson.JsonSyntaxException e) {
      throw new ConfigException("Invalid JSON in configuration file: " + configFile + ": " + e.getMessage());
    }
  }

  private ServerConfig parseServerBlock(String name, JsonObject block) {
    return new ServerConfig(
        name,
        expandPath(interpolate(getString(block, "logdir"))),
        getInteger(block, "team"),
        interpolate(getString(block, "tba_key")),
        getString(block, "transport"),
        getInteger(block, "port"),
        expandPath(interpolate(getString(block, "diskcachedir"))),
        getLong(block, "diskcachesize"),
        getBoolean(block, "diskcachedisable"),
        getBoolean(block, "debug"),
        expandPath(interpolate(getString(block, "exportdir"))),
        getInteger(block, "scandepth")
    );
  }

  private void validate(ServerConfig config) throws ConfigException {
    var transport = config.effectiveTransport();
    if (!"stdio".equals(transport) && !"http".equals(transport)) {
      throw new ConfigException(
          "Invalid transport '" + transport + "' in configuration '" + config.name()
              + "'. Must be 'stdio' or 'http'.");
    }

    int port = config.effectivePort();
    if (port < 1 || port > 65535) {
      throw new ConfigException(
          "Invalid port " + port + " in configuration '" + config.name()
              + "'. Must be between 1 and 65535.");
    }

  }

  /**
   * Interpolates {@code ${VAR_NAME}} references in a string value.
   */
  String interpolate(String value) {
    if (value == null || !value.contains("${")) return value;
    var matcher = ENV_VAR_PATTERN.matcher(value);
    var sb = new StringBuilder();
    while (matcher.find()) {
      var envName = matcher.group(1);
      var envValue = envResolver.apply(envName);
      if (envValue != null) {
        matcher.appendReplacement(sb, Matcher.quoteReplacement(envValue));
      } else {
        logger.warn("Environment variable {} is not set", envName);
        matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
      }
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  /**
   * Expands a leading {@code ~} to the user's home directory.
   */
  static String expandPath(String path) {
    if (path == null) return null;
    if (path.startsWith("~/") || path.equals("~")) {
      return System.getProperty("user.home") + path.substring(1);
    }
    return path;
  }

  private static String getString(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsString();
    }
    return null;
  }

  private static Integer getInteger(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsInt();
    }
    return null;
  }

  private static Long getLong(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsLong();
    }
    return null;
  }

  private static Boolean getBoolean(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsBoolean();
    }
    return null;
  }
}
