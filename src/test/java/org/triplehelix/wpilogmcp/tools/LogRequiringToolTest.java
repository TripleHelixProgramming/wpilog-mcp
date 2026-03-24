package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;

@DisplayName("LogRequiringTool")
class LogRequiringToolTest {

  private LogManager logManager;
  private MockLogBuilder mockLogBuilder;

  @BeforeEach
  void setUp() {
    logManager = LogManager.getInstance();
    logManager.unloadAllLogs();
    mockLogBuilder = new MockLogBuilder();
  }

  @AfterEach
  void tearDown() {
    logManager.unloadAllLogs();
  }

  // ===== TEST TOOL IMPLEMENTATIONS =====

  /** Simple test tool that requires a log and returns success. */
  static class SimpleLogTool extends LogRequiringTool {
    @Override
    public String name() { return "simple_log_tool"; }

    @Override
    public String description() { return "Test tool that requires a log"; }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) {
      return success()
          .addProperty("log_path", log.path())
          .build();
    }
  }

  /** Tool that accesses log data. */
  static class DataAccessTool extends LogRequiringTool {
    @Override
    public String name() { return "data_access_tool"; }

    @Override
    public String description() { return "Test tool that accesses log data"; }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("entry_name", "string", "Entry to access", true)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var entryName = arguments.get("entry_name").getAsString();
      var values = requireEntry(log, entryName);
      return success()
          .addProperty("entry_name", entryName)
          .addProperty("value_count", values.size())
          .build();
    }
  }

  /** Tool that throws exceptions. */
  static class ThrowingLogTool extends LogRequiringTool {
    @Override
    public String name() { return "throwing_log_tool"; }

    @Override
    public String description() { return "Test tool that throws exceptions"; }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("throw_type", "string", "Type of exception", true)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var throwType = arguments.get("throw_type").getAsString();
      return switch (throwType) {
        case "illegal_argument" -> throw new IllegalArgumentException("Test illegal argument");
        case "runtime" -> throw new RuntimeException("Test runtime exception");
        case "null_pointer" -> throw new NullPointerException("Test null pointer");
        default -> success().build();
      };
    }
  }

  // ===== Helper =====

  private JsonObject argsWithPath(String path) {
    var args = new JsonObject();
    args.addProperty("path", path);
    return args;
  }

  private void putLog(ParsedLog log) {
    logManager.testPutLog(log.path(), log);
  }

  // ===== SCHEMA INJECTION TESTS =====

  @Nested
  @DisplayName("Schema Injection")
  class SchemaInjectionTests {

    @Test
    @DisplayName("inputSchema() includes injected path property")
    void inputSchemaIncludesPath() {
      var tool = new SimpleLogTool();
      var schema = tool.inputSchema();
      var props = schema.getAsJsonObject("properties");
      assertTrue(props.has("path"), "Should have injected 'path' property");
      assertEquals("string", props.getAsJsonObject("path").get("type").getAsString());
    }

    @Test
    @DisplayName("path is in the required array")
    void pathIsRequired() {
      var tool = new SimpleLogTool();
      var schema = tool.inputSchema();
      var required = schema.getAsJsonArray("required");
      boolean found = false;
      for (var r : required) {
        if ("path".equals(r.getAsString())) found = true;
      }
      assertTrue(found, "path should be in required array");
    }

    @Test
    @DisplayName("tool-specific properties are preserved alongside path")
    void toolPropertiesPreserved() {
      var tool = new DataAccessTool();
      var schema = tool.inputSchema();
      var props = schema.getAsJsonObject("properties");
      assertTrue(props.has("path"), "Should have path");
      assertTrue(props.has("entry_name"), "Should preserve tool-specific property");
    }

    @Test
    @DisplayName("toolSchema() does not include path")
    void toolSchemaDoesNotIncludePath() {
      var tool = new SimpleLogTool();
      var schema = tool.toolSchema();
      var props = schema.getAsJsonObject("properties");
      assertFalse(props.has("path"), "toolSchema should NOT include path");
    }
  }

  // ===== PATH-BASED LOG ACCESS TESTS =====

  @Nested
  @DisplayName("Path-Based Log Access")
  class PathBasedLogAccessTests {

    @Test
    @DisplayName("returns error when path argument is missing")
    void returnsErrorWhenPathMissing() throws Exception {
      var tool = new SimpleLogTool();
      var result = tool.execute(new JsonObject());

      var obj = result.getAsJsonObject();
      assertFalse(obj.get("success").getAsBoolean());
      assertTrue(obj.get("error").getAsString().contains("path"));
    }

    @Test
    @DisplayName("executes successfully with path to cached log")
    void executesWithCachedLog() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test/log.wpilog")
          .addNumericEntry("/test/entry", new double[]{0}, new double[]{1.0})
          .build();
      putLog(mockLog);

      var tool = new SimpleLogTool();
      var result = tool.execute(argsWithPath("/test/log.wpilog"));

      var obj = result.getAsJsonObject();
      assertTrue(obj.get("success").getAsBoolean());
      assertEquals("/test/log.wpilog", obj.get("log_path").getAsString());
    }

    @Test
    @DisplayName("passes correct log to executeWithLog")
    void passesCorrectLog() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/path/to/specific.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{42.0})
          .build();
      putLog(mockLog);

      var tool = new SimpleLogTool();
      var result = tool.execute(argsWithPath("/path/to/specific.wpilog"));

      assertEquals("/path/to/specific.wpilog",
          result.getAsJsonObject().get("log_path").getAsString());
    }
  }

  @Nested
  @DisplayName("ExecuteWithLog Method")
  class ExecuteWithLogTests {

    @Test
    @DisplayName("can access log entries")
    void canAccessLogEntries() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/my/entry", new double[]{0, 1, 2}, new double[]{1.0, 2.0, 3.0})
          .build();
      putLog(mockLog);

      var tool = new DataAccessTool();
      var args = argsWithPath("/test.wpilog");
      args.addProperty("entry_name", "/my/entry");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();
      assertTrue(obj.get("success").getAsBoolean());
      assertEquals("/my/entry", obj.get("entry_name").getAsString());
      assertEquals(3, obj.get("value_count").getAsInt());
    }

    @Test
    @DisplayName("can use helper methods like requireEntry")
    void canUseHelperMethods() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/exists", new double[]{0}, new double[]{1.0})
          .build();
      putLog(mockLog);

      var tool = new DataAccessTool();
      var args = argsWithPath("/test.wpilog");
      args.addProperty("entry_name", "/nonexistent");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();
      assertFalse(obj.get("success").getAsBoolean());
      assertTrue(obj.get("error").getAsString().contains("Entry not found"));
    }
  }

  @Nested
  @DisplayName("Exception Handling")
  class ExceptionHandlingTests {

    @Test
    @DisplayName("converts IllegalArgumentException to error response")
    void convertsIllegalArgument() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      putLog(mockLog);

      var tool = new ThrowingLogTool();
      var args = argsWithPath("/test.wpilog");
      args.addProperty("throw_type", "illegal_argument");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();
      assertFalse(obj.get("success").getAsBoolean());
      assertEquals("Test illegal argument", obj.get("error").getAsString());
    }

    @Test
    @DisplayName("returns error response for runtime exceptions")
    void returnsErrorForRuntimeExceptions() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      putLog(mockLog);

      var tool = new ThrowingLogTool();
      var args = argsWithPath("/test.wpilog");
      args.addProperty("throw_type", "runtime");

      var result = tool.execute(args);
      var obj = result.getAsJsonObject();
      assertFalse(obj.get("success").getAsBoolean());
      assertTrue(obj.get("error").getAsString().contains("Internal error"));
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("handles log loaded then unloaded")
    void handlesLogLoadedThenUnloaded() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      putLog(mockLog);

      var tool = new SimpleLogTool();

      // First call should succeed
      var result1 = tool.execute(argsWithPath("/test.wpilog"));
      assertTrue(result1.getAsJsonObject().get("success").getAsBoolean());

      // Unload log
      logManager.unloadAllLogs();

      // Second call should fail (log not in cache and path not on disk)
      var result2 = tool.execute(argsWithPath("/test.wpilog"));
      assertFalse(result2.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("handles rapid successive calls")
    void handlesRapidSuccessiveCalls() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      putLog(mockLog);

      var tool = new SimpleLogTool();
      for (int i = 0; i < 100; i++) {
        var result = tool.execute(argsWithPath("/test.wpilog"));
        assertTrue(result.getAsJsonObject().get("success").getAsBoolean());
      }
    }

    @Test
    @DisplayName("handles empty log file")
    void handlesEmptyLogFile() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/empty.wpilog")
          .build();
      putLog(mockLog);

      var tool = new SimpleLogTool();
      var result = tool.execute(argsWithPath("/empty.wpilog"));
      assertTrue(result.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("different paths access different logs")
    void differentPathsAccessDifferentLogs() throws Exception {
      var log1 = mockLogBuilder.setPath("/log1.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0}).build();
      var log2 = new MockLogBuilder().setPath("/log2.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{2.0}).build();
      putLog(log1);
      putLog(log2);

      var tool = new SimpleLogTool();
      var r1 = tool.execute(argsWithPath("/log1.wpilog"));
      var r2 = tool.execute(argsWithPath("/log2.wpilog"));

      assertEquals("/log1.wpilog", r1.getAsJsonObject().get("log_path").getAsString());
      assertEquals("/log2.wpilog", r2.getAsJsonObject().get("log_path").getAsString());
    }

    @Test
    @DisplayName("handles concurrent tool instances")
    void handlesConcurrentToolInstances() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      putLog(mockLog);

      var tool1 = new SimpleLogTool();
      var tool2 = new SimpleLogTool();
      var tool3 = new SimpleLogTool();

      assertTrue(tool1.execute(argsWithPath("/test.wpilog")).getAsJsonObject().get("success").getAsBoolean());
      assertTrue(tool2.execute(argsWithPath("/test.wpilog")).getAsJsonObject().get("success").getAsBoolean());
      assertTrue(tool3.execute(argsWithPath("/test.wpilog")).getAsJsonObject().get("success").getAsBoolean());
    }
  }

  @Nested
  @DisplayName("Integration with ToolBase")
  class IntegrationTests {

    @Test
    @DisplayName("respects dependency injection from ToolBase")
    void respectsDependencyInjection() {
      var tool = new SimpleLogTool();
      assertNotNull(tool.logManager);
      assertSame(LogManager.getInstance(), tool.logManager);
    }

    @Test
    @DisplayName("template method pattern works correctly")
    void templateMethodPatternWorks() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      putLog(mockLog);

      var tool = new SimpleLogTool();
      var result = tool.execute(argsWithPath("/test.wpilog"));
      assertTrue(result.getAsJsonObject().get("success").getAsBoolean());
    }
  }
}
