package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry for MCP tools.
 *
 * <p>Holds the tool definitions and provides lookup. Shared by both stdio and HTTP transports.
 */
public class ToolRegistry {
  private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

  private final Map<String, Tool> tools = new ConcurrentHashMap<>();

  public void registerTool(Tool tool) {
    logger.debug("Registering tool: {}", tool.name());
    tools.put(tool.name(), tool);
  }

  public Tool getTool(String name) {
    return tools.get(name);
  }

  public Collection<String> getToolNames() {
    return tools.keySet();
  }

  public int size() {
    return tools.size();
  }

  public JsonArray toJsonArray() {
    return tools.values().stream()
        .map(tool -> {
          var toolObj = new JsonObject();
          toolObj.addProperty("name", tool.name());
          toolObj.addProperty("description", tool.description());
          toolObj.add("inputSchema", tool.inputSchema());
          return toolObj;
        })
        .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
  }

  /**
   * A tool that can be invoked via MCP.
   */
  public interface Tool {
    String name();

    String description();

    JsonObject inputSchema();

    JsonElement execute(JsonObject arguments) throws Exception;
  }

  /**
   * Fluent builder for JSON Schema objects used by tool input schemas.
   */
  public static class SchemaBuilder {
    private final JsonObject schema = new JsonObject();
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    public SchemaBuilder() {
      schema.addProperty("type", "object");
      schema.add("properties", properties);
    }

    public SchemaBuilder addProperty(
        String name, String type, String description, boolean isRequired) {
      var prop = new JsonObject();
      prop.addProperty("type", type);
      prop.addProperty("description", description);
      properties.add(name, prop);
      if (isRequired) required.add(name);
      return this;
    }

    public SchemaBuilder addIntegerProperty(
        String name, String description, boolean isRequired, Integer defaultValue) {
      var prop = new JsonObject();
      prop.addProperty("type", "integer");
      prop.addProperty("description", description);
      if (defaultValue != null) prop.addProperty("default", defaultValue);
      properties.add(name, prop);
      if (isRequired) required.add(name);
      return this;
    }

    public SchemaBuilder addNumberProperty(
        String name, String description, boolean isRequired, Double defaultValue) {
      var prop = new JsonObject();
      prop.addProperty("type", "number");
      prop.addProperty("description", description);
      if (defaultValue != null) prop.addProperty("default", defaultValue);
      properties.add(name, prop);
      if (isRequired) required.add(name);
      return this;
    }

    public JsonObject build() {
      if (required.size() > 0) schema.add("required", required);
      return schema;
    }
  }
}
