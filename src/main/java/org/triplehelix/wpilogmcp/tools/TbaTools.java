package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;
import org.triplehelix.wpilogmcp.tba.TbaClient;

/**
 * The Blue Alliance integration tools.
 *
 * <p>Tools included:
 * <ul>
 *   <li>{@code get_tba_status} - Get TBA API integration status</li>
 * </ul>
 */
public final class TbaTools {

  private TbaTools() {}

  /**
   * Registers all TBA tools with the MCP server.
   */
  public static void registerAll(McpServer server) {
    server.registerTool(new GetTbaStatusTool());
  }

  static class GetTbaStatusTool implements Tool {
    @Override
    public String name() {
      return "get_tba_status";
    }

    @Override
    public String description() {
      return "Get The Blue Alliance API integration status, including configuration and cache statistics.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var client = TbaClient.getInstance();

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("available", client.isAvailable());

      if (client.isAvailable()) {
        result.addProperty("status", "configured");

        var cache = new JsonObject();
        for (var entry : client.getCacheStats().entrySet()) {
          cache.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("cache", cache);

        result.addProperty("hint",
            "TBA data will be included in list_available_logs for logs with team number in metadata");
      } else {
        result.addProperty("status", "not_configured");
        result.addProperty("hint",
            "Set TBA_API_KEY environment variable or use -tba-key argument. "
                + "Get a free API key at https://www.thebluealliance.com/account");
      }

      return result;
    }
  }
}
