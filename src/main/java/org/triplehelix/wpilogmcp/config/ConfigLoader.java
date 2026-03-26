package org.triplehelix.wpilogmcp.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
import org.yaml.snakeyaml.Yaml;

/**
 * Loads and parses server configuration files (YAML or JSON).
 *
 * <p>Configuration files contain a {@code servers} section with named server configurations.
 * Default values can be specified either as top-level properties on the root object (preferred)
 * or in an explicit {@code defaults} block. Environment variable references
 * ({@code ${VAR_NAME}}) in string values are interpolated at load time.
 *
 * <p>Both YAML and JSON formats are supported. YAML is preferred for new installations
 * because it supports comments. File format is detected by extension ({@code .yaml},
 * {@code .yml}, or {@code .json}).
 *
 * <p>File discovery order (first found wins):
 * <ol>
 *   <li>{@code --config <path>} CLI argument (any format)</li>
 *   <li>{@code .wpilog-mcp.yaml} in the current working directory</li>
 *   <li>{@code .wpilog-mcp.json} in the current working directory</li>
 *   <li>{@code ~/.wpilog-mcp/servers.yaml} (install directory)</li>
 *   <li>{@code ~/.wpilog-mcp/servers.json} (install directory, legacy)</li>
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

    // Parse defaults from three layers (lowest to highest priority):
    // 1. Top-level properties on the root object (excluding "servers" and "defaults")
    // 2. Explicit "defaults" block (if present)
    // This allows a clean flat config where team, logdir, etc. live at the root.
    ServerConfig defaults = parseServerBlock("_defaults", root);
    if (root.has("defaults") && root.get("defaults").isJsonObject()) {
      var explicitDefaults = parseServerBlock("_defaults", root.getAsJsonObject("defaults"));
      defaults = explicitDefaults.mergeWithDefaults(defaults);
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
   * YAML files are preferred over JSON at each location.
   */
  List<Path> configSearchPaths() {
    var paths = new ArrayList<Path>();
    String home = System.getProperty("user.home");

    // 1. Project-local override (YAML preferred, JSON fallback)
    paths.add(Path.of(".wpilog-mcp.yaml"));
    paths.add(Path.of(".wpilog-mcp.json"));

    // 2. Install directory (YAML preferred, JSON fallback)
    paths.add(Path.of(home, "." + APP_NAME, "servers.yaml"));
    paths.add(Path.of(home, "." + APP_NAME, "servers.json"));

    return paths;
  }

  /**
   * Parses a config file as JSON or YAML based on file extension.
   * Files ending in {@code .yaml} or {@code .yml} are parsed as YAML;
   * all others are parsed as JSON.
   */
  private JsonObject parseFile(Path configFile) throws ConfigException {
    var fileName = configFile.getFileName().toString().toLowerCase();
    if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
      return parseYamlFile(configFile);
    }
    return parseJsonFile(configFile);
  }

  private JsonObject parseJsonFile(Path configFile) throws ConfigException {
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

  private JsonObject parseYamlFile(Path configFile) throws ConfigException {
    try {
      var content = Files.readString(configFile);
      var yaml = new Yaml();
      Object parsed = yaml.load(content);
      if (parsed == null) {
        throw new ConfigException("Empty configuration file: " + configFile);
      }
      if (!(parsed instanceof Map<?, ?> rawMap)) {
        throw new ConfigException(
            "Invalid configuration file (expected mapping): " + configFile);
      }
      @SuppressWarnings("unchecked")
      var map = (Map<String, Object>) rawMap;
      return mapToJsonObject(map);
    } catch (IOException e) {
      throw new ConfigException("Failed to read configuration file: " + configFile + ": " + e.getMessage());
    } catch (org.yaml.snakeyaml.error.YAMLException e) {
      throw new ConfigException("Invalid YAML in configuration file: " + configFile + ": " + e.getMessage());
    }
  }

  /**
   * Converts a nested Map (from YAML parsing) to a Gson JsonObject.
   */
  @SuppressWarnings("unchecked")
  static JsonObject mapToJsonObject(Map<String, Object> map) {
    var obj = new JsonObject();
    for (var entry : map.entrySet()) {
      var key = entry.getKey();
      var value = entry.getValue();
      if (value == null) {
        obj.add(key, com.google.gson.JsonNull.INSTANCE);
      } else if (value instanceof Map<?, ?> m) {
        obj.add(key, mapToJsonObject((Map<String, Object>) m));
      } else if (value instanceof List<?> list) {
        obj.add(key, listToJsonArray(list));
      } else if (value instanceof String s) {
        obj.add(key, new JsonPrimitive(s));
      } else if (value instanceof Boolean b) {
        obj.add(key, new JsonPrimitive(b));
      } else if (value instanceof Integer i) {
        obj.add(key, new JsonPrimitive(i));
      } else if (value instanceof Long l) {
        obj.add(key, new JsonPrimitive(l));
      } else if (value instanceof Double d) {
        obj.add(key, new JsonPrimitive(d));
      } else if (value instanceof Number n) {
        obj.add(key, new JsonPrimitive(n));
      } else {
        // Coerce anything else to string
        obj.add(key, new JsonPrimitive(value.toString()));
      }
    }
    return obj;
  }

  /**
   * Converts a List (from YAML parsing) to a Gson JsonArray, recursively handling
   * nested maps, lists, and scalar types.
   */
  @SuppressWarnings("unchecked")
  static JsonArray listToJsonArray(List<?> list) {
    var arr = new JsonArray();
    for (var item : list) {
      if (item == null) {
        arr.add(com.google.gson.JsonNull.INSTANCE);
      } else if (item instanceof Map) {
        arr.add(mapToJsonObject((Map<String, Object>) item));
      } else if (item instanceof List<?> nested) {
        arr.add(listToJsonArray(nested));
      } else if (item instanceof String s) {
        arr.add(new JsonPrimitive(s));
      } else if (item instanceof Boolean b) {
        arr.add(new JsonPrimitive(b));
      } else if (item instanceof Number n) {
        arr.add(new JsonPrimitive(n));
      } else {
        arr.add(new JsonPrimitive(item.toString()));
      }
    }
    return arr;
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
    if (path.startsWith("~/") || path.startsWith("~\\") || path.equals("~")) {
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
      try {
        return obj.get(key).getAsInt();
      } catch (NumberFormatException e) {
        throw new NumberFormatException(
            "Config key '" + key + "' expected an integer, got: " + obj.get(key));
      }
    }
    return null;
  }

  private static Long getLong(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      try {
        return obj.get(key).getAsLong();
      } catch (NumberFormatException e) {
        throw new NumberFormatException(
            "Config key '" + key + "' expected a long, got: " + obj.get(key));
      }
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
