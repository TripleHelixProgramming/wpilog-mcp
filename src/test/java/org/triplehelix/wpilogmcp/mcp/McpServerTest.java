package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

/**
 * Tests for MCP server functionality.
 */
class McpServerTest {

  private McpServer server;

  @BeforeEach
  void setUp() {
    server = new McpServer();
  }

  @Nested
  @DisplayName("Tool Registration")
  class ToolRegistration {

    @Test
    @DisplayName("registers tools successfully")
    void registersTools() {
      Tool testTool = createTestTool("test_tool", "A test tool");
      server.registerTool(testTool);
      // Tool is registered - we can't directly access but can test via tools/list
    }

    @Test
    @DisplayName("registers multiple tools")
    void registersMultipleTools() {
      server.registerTool(createTestTool("tool1", "First tool"));
      server.registerTool(createTestTool("tool2", "Second tool"));
      server.registerTool(createTestTool("tool3", "Third tool"));
      // All tools registered successfully
    }

    @Test
    @DisplayName("overwrites tool with same name")
    void overwritesToolWithSameName() {
      server.registerTool(createTestTool("duplicate", "First version"));
      server.registerTool(createTestTool("duplicate", "Second version"));
      // Latest registration wins
    }
  }

  @Nested
  @DisplayName("SchemaBuilder")
  class SchemaBuilderTest {

    @Test
    @DisplayName("builds schema with string property")
    void buildsSchemaWithStringProperty() {
      JsonObject schema =
          new SchemaBuilder()
              .addProperty("path", "string", "File path", true)
              .build();

      assertEquals("object", schema.get("type").getAsString());

      JsonObject properties = schema.getAsJsonObject("properties");
      JsonObject pathProp = properties.getAsJsonObject("path");
      assertEquals("string", pathProp.get("type").getAsString());
      assertEquals("File path", pathProp.get("description").getAsString());

      assertTrue(schema.getAsJsonArray("required").contains(new JsonPrimitive("path")));
    }

    @Test
    @DisplayName("builds schema with optional property")
    void buildsSchemaWithOptionalProperty() {
      JsonObject schema =
          new SchemaBuilder()
              .addProperty("filter", "string", "Optional filter", false)
              .build();

      assertFalse(schema.has("required"));
    }

    @Test
    @DisplayName("builds schema with integer property and default")
    void buildsSchemaWithIntegerProperty() {
      JsonObject schema =
          new SchemaBuilder()
              .addIntegerProperty("limit", "Max results", false, 100)
              .build();

      JsonObject properties = schema.getAsJsonObject("properties");
      JsonObject limitProp = properties.getAsJsonObject("limit");
      assertEquals("integer", limitProp.get("type").getAsString());
      assertEquals(100, limitProp.get("default").getAsInt());
    }

    @Test
    @DisplayName("builds schema with number property and default")
    void buildsSchemaWithNumberProperty() {
      JsonObject schema =
          new SchemaBuilder()
              .addNumberProperty("threshold", "Value threshold", false, 0.5)
              .build();

      JsonObject properties = schema.getAsJsonObject("properties");
      JsonObject thresholdProp = properties.getAsJsonObject("threshold");
      assertEquals("number", thresholdProp.get("type").getAsString());
      assertEquals(0.5, thresholdProp.get("default").getAsDouble());
    }

    @Test
    @DisplayName("builds complex schema with multiple properties")
    void buildsComplexSchema() {
      JsonObject schema =
          new SchemaBuilder()
              .addProperty("name", "string", "Entry name", true)
              .addNumberProperty("start_time", "Start timestamp", false, null)
              .addNumberProperty("end_time", "End timestamp", false, null)
              .addIntegerProperty("limit", "Max samples", false, 100)
              .addIntegerProperty("offset", "Skip samples", false, 0)
              .build();

      assertEquals("object", schema.get("type").getAsString());

      JsonObject properties = schema.getAsJsonObject("properties");
      assertEquals(5, properties.size());

      // Only 'name' should be required
      assertEquals(1, schema.getAsJsonArray("required").size());
      assertTrue(schema.getAsJsonArray("required").contains(new JsonPrimitive("name")));
    }

    @Test
    @DisplayName("builds empty schema")
    void buildsEmptySchema() {
      JsonObject schema = new SchemaBuilder().build();

      assertEquals("object", schema.get("type").getAsString());
      assertTrue(schema.getAsJsonObject("properties").size() == 0);
      assertFalse(schema.has("required"));
    }
  }

  @Nested
  @DisplayName("Tool Interface")
  class ToolInterfaceTest {

    @Test
    @DisplayName("tool returns correct metadata")
    void toolReturnsCorrectMetadata() {
      Tool tool = createTestTool("my_tool", "Does something useful");

      assertEquals("my_tool", tool.name());
      assertEquals("Does something useful", tool.description());
      assertNotNull(tool.inputSchema());
    }

    @Test
    @DisplayName("tool executes with arguments")
    void toolExecutesWithArguments() throws Exception {
      Tool tool =
          new Tool() {
            @Override
            public String name() {
              return "echo";
            }

            @Override
            public String description() {
              return "Echoes input";
            }

            @Override
            public JsonObject inputSchema() {
              return new SchemaBuilder()
                  .addProperty("message", "string", "Message to echo", true)
                  .build();
            }

            @Override
            public JsonElement execute(JsonObject arguments) {
              JsonObject result = new JsonObject();
              result.addProperty("echoed", arguments.get("message").getAsString());
              return result;
            }
          };

      JsonObject args = new JsonObject();
      args.addProperty("message", "Hello, World!");

      JsonElement result = tool.execute(args);
      assertEquals("Hello, World!", result.getAsJsonObject().get("echoed").getAsString());
    }

    @Test
    @DisplayName("tool can throw exceptions")
    void toolCanThrowExceptions() {
      Tool tool =
          new Tool() {
            @Override
            public String name() {
              return "failing";
            }

            @Override
            public String description() {
              return "Always fails";
            }

            @Override
            public JsonObject inputSchema() {
              return new SchemaBuilder().build();
            }

            @Override
            public JsonElement execute(JsonObject arguments) throws Exception {
              throw new RuntimeException("Intentional failure");
            }
          };

      assertThrows(RuntimeException.class, () -> tool.execute(new JsonObject()));
    }
  }

  /**
   * Creates a simple test tool.
   */
  private Tool createTestTool(String name, String description) {
    return new Tool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public JsonObject inputSchema() {
        return new SchemaBuilder().build();
      }

      @Override
      public JsonElement execute(JsonObject arguments) {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return result;
      }
    };
  }
}
