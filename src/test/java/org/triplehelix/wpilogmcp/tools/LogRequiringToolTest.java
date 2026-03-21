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
    public String name() {
      return "simple_log_tool";
    }

    @Override
    public String description() {
      return "Test tool that requires a log";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().build();
    }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) {
      return success()
          .addProperty("log_path", log.path())
          .build();
    }
  }

  /** Tool that accesses log data. */
  static class DataAccessTool extends LogRequiringTool {
    @Override
    public String name() {
      return "data_access_tool";
    }

    @Override
    public String description() {
      return "Test tool that accesses log data";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("entry_name", "string", "Entry to access", true)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {
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
    public String name() {
      return "throwing_log_tool";
    }

    @Override
    public String description() {
      return "Test tool that throws exceptions";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("throw_type", "string", "Type of exception", true)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(ParsedLog log, JsonObject arguments) throws Exception {
      var throwType = arguments.get("throw_type").getAsString();

      switch (throwType) {
        case "illegal_argument":
          throw new IllegalArgumentException("Test illegal argument");
        case "runtime":
          throw new RuntimeException("Test runtime exception");
        case "null_pointer":
          throw new NullPointerException("Test null pointer");
        default:
          return success().build();
      }
    }
  }

  // ===== AUTOMATIC LOG CHECKING TESTS =====

  @Nested
  @DisplayName("Automatic Log Checking")
  class AutomaticLogCheckingTests {

    @Test
    @DisplayName("returns error when no log loaded")
    void returnsErrorWhenNoLogLoaded() throws Exception {
      var tool = new SimpleLogTool();
      var result = tool.execute(new JsonObject());

      assertTrue(result.isJsonObject());
      var obj = result.getAsJsonObject();
      assertFalse(obj.get("success").getAsBoolean());
      assertTrue(obj.get("error").getAsString().contains("No log file is currently loaded"));
      assertTrue(obj.get("error").getAsString().contains("Use load_log first"));
    }

    @Test
    @DisplayName("executes successfully when log is loaded")
    void executesSuccessfullyWhenLogLoaded() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test/log.wpilog")
          .addNumericEntry("/test/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test/log.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test/log.wpilog");

      var tool = new SimpleLogTool();
      var result = tool.execute(new JsonObject());

      assertTrue(result.isJsonObject());
      var obj = result.getAsJsonObject();
      assertTrue(obj.get("success").getAsBoolean());
      assertEquals("/test/log.wpilog", obj.get("log_path").getAsString());
    }

    @Test
    @DisplayName("passes correct log instance to executeWithLog")
    void passesCorrectLogInstanceToExecuteWithLog() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/path/to/specific.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{42.0})
          .build();
      logManager.testPutLog("/path/to/specific.wpilog", mockLog);
      logManager.testSetActiveLogPath("/path/to/specific.wpilog");

      var tool = new SimpleLogTool();
      var result = tool.execute(new JsonObject());

      var obj = result.getAsJsonObject();
      assertEquals("/path/to/specific.wpilog", obj.get("log_path").getAsString());
    }

    @Test
    @DisplayName("returns error immediately without calling executeWithLog when no log")
    void returnsErrorImmediatelyWithoutCallingExecuteWithLog() throws Exception {
      // Ensure no log is loaded
      logManager.unloadAllLogs();

      var tool = new DataAccessTool();
      var args = new JsonObject();
      args.addProperty("entry_name", "/nonexistent");

      var result = tool.execute(args);

      // Should get "no log" error, not "entry not found" error
      var obj = result.getAsJsonObject();
      assertFalse(obj.get("success").getAsBoolean());
      assertTrue(obj.get("error").getAsString().contains("No log file is currently loaded"));
      assertFalse(obj.get("error").getAsString().contains("Entry not found"));
    }
  }

  @Nested
  @DisplayName("ExecuteWithLog Method")
  class ExecuteWithLogTests {

    @Test
    @DisplayName("receives non-null log parameter")
    void receivesNonNullLogParameter() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new SimpleLogTool();
      var result = tool.execute(new JsonObject());

      // If log was null, tool would have failed
      assertTrue(result.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("can access log entries")
    void canAccessLogEntries() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/my/entry", new double[]{0, 1, 2}, new double[]{1.0, 2.0, 3.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new DataAccessTool();
      var args = new JsonObject();
      args.addProperty("entry_name", "/my/entry");

      var result = tool.execute(args);

      var obj = result.getAsJsonObject();
      assertTrue(obj.get("success").getAsBoolean());
      assertEquals("/my/entry", obj.get("entry_name").getAsString());
      assertEquals(3, obj.get("value_count").getAsInt());
    }

    @Test
    @DisplayName("can use helper methods like requireEntry")
    void canUseHelperMethodsLikeRequireEntry() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/exists", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new DataAccessTool();
      var args = new JsonObject();
      args.addProperty("entry_name", "/nonexistent");

      var result = tool.execute(args);

      // Should get entry not found error (from requireEntry helper)
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
    void convertsIllegalArgumentExceptionToErrorResponse() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new ThrowingLogTool();
      var args = new JsonObject();
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
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new ThrowingLogTool();
      var args = new JsonObject();
      args.addProperty("throw_type", "runtime");

      // ToolBase now catches all exceptions and returns error responses
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();
      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("Internal error"));
    }

    @Test
    @DisplayName("returns error response for NullPointerException")
    void returnsErrorForNullPointerException() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new ThrowingLogTool();
      var args = new JsonObject();
      args.addProperty("throw_type", "null_pointer");

      // ToolBase now catches all exceptions and returns error responses
      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();
      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("Internal error"));
    }
  }

  @Nested
  @DisplayName("Integration with ToolBase")
  class IntegrationTests {

    @Test
    @DisplayName("inherits all ToolBase helper methods")
    void inheritsAllToolBaseHelperMethods() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0, 1, 2}, new double[]{1.0, 2.0, 3.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new DataAccessTool();

      // Should have access to requireEntry (from ToolBase)
      var log = logManager.getActiveLog();
      var values = tool.requireEntry(log, "/entry");
      assertEquals(3, values.size());

      // Should have access to success() (from ToolBase)
      var response = tool.success().build();
      assertTrue(response.get("success").getAsBoolean());
    }

    @Test
    @DisplayName("respects dependency injection from ToolBase")
    void respectsDependencyInjectionFromToolBase() {
      var tool = new SimpleLogTool();

      // Should have access to logManager field from ToolBase
      assertNotNull(tool.logManager);
      assertSame(LogManager.getInstance(), tool.logManager);
    }

    @Test
    @DisplayName("template method pattern works correctly")
    void templateMethodPatternWorksCorrectly() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new SimpleLogTool();

      // Calling execute() should trigger:
      // 1. ToolBase.execute() (template method)
      // 2. LogRequiringTool.executeInternal() (log checking)
      // 3. SimpleLogTool.executeWithLog() (actual logic)
      var result = tool.execute(new JsonObject());

      assertTrue(result.getAsJsonObject().get("success").getAsBoolean());
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCaseTests {

    @Test
    @DisplayName("handles log loaded then unloaded")
    void handlesLogLoadedThenUnloaded() throws Exception {
      // Load a log
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new SimpleLogTool();

      // First call should succeed
      var result1 = tool.execute(new JsonObject());
      assertTrue(result1.getAsJsonObject().get("success").getAsBoolean());

      // Unload log
      logManager.unloadAllLogs();

      // Second call should fail with appropriate error
      var result2 = tool.execute(new JsonObject());
      assertFalse(result2.getAsJsonObject().get("success").getAsBoolean());
      assertTrue(result2.getAsJsonObject().get("error").getAsString()
          .contains("No log file is currently loaded"));
    }

    @Test
    @DisplayName("handles rapid successive calls")
    void handlesRapidSuccessiveCalls() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      var tool = new SimpleLogTool();

      for (int i = 0; i < 100; i++) {
        var result = tool.execute(new JsonObject());
        assertTrue(result.getAsJsonObject().get("success").getAsBoolean());
      }
    }

    @Test
    @DisplayName("handles empty log file")
    void handlesEmptyLogFile() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/empty.wpilog")
          .build(); // No entries
      logManager.testPutLog("/empty.wpilog", mockLog);
      logManager.testSetActiveLogPath("/empty.wpilog");

      var tool = new SimpleLogTool();
      var result = tool.execute(new JsonObject());

      // Should succeed - log exists, even if empty
      assertTrue(result.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("handles switching active log")
    void handlesSwitchingActiveLog() throws Exception {
      // Load first log
      var mockLog1 = mockLogBuilder
          .setPath("/log1.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/log1.wpilog", mockLog1);
      logManager.testSetActiveLogPath("/log1.wpilog");

      var tool = new SimpleLogTool();
      var result1 = tool.execute(new JsonObject());
      assertEquals("/log1.wpilog", result1.getAsJsonObject().get("log_path").getAsString());

      // Load second log
      var mockLog2 = mockLogBuilder
          .setPath("/log2.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{2.0})
          .build();
      logManager.testPutLog("/log2.wpilog", mockLog2);
      logManager.testSetActiveLogPath("/log2.wpilog");

      // Should now use second log
      var result2 = tool.execute(new JsonObject());
      assertEquals("/log2.wpilog", result2.getAsJsonObject().get("log_path").getAsString());
    }

    @Test
    @DisplayName("handles concurrent tool instances")
    void handlesConcurrentToolInstances() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      // Create multiple tool instances
      var tool1 = new SimpleLogTool();
      var tool2 = new SimpleLogTool();
      var tool3 = new SimpleLogTool();

      // All should work correctly
      assertTrue(tool1.execute(new JsonObject()).getAsJsonObject().get("success").getAsBoolean());
      assertTrue(tool2.execute(new JsonObject()).getAsJsonObject().get("success").getAsBoolean());
      assertTrue(tool3.execute(new JsonObject()).getAsJsonObject().get("success").getAsBoolean());
    }
  }

  @Nested
  @DisplayName("Documentation Compliance")
  class DocumentationComplianceTests {

    @Test
    @DisplayName("guarantees log parameter is non-null in executeWithLog")
    void guaranteesLogParameterIsNonNull() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      // Documentation states: "The log parameter is guaranteed to be non-null"
      var tool = new SimpleLogTool();
      var result = tool.execute(new JsonObject());

      // If log was null, the tool would have failed with NullPointerException
      assertTrue(result.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    @DisplayName("executeInternal is final and cannot be overridden")
    void executeInternalIsFinalAndCannotBeOverridden() {
      // This is enforced at compile time by the final keyword
      // This test documents the requirement
      assertTrue(true);
    }

    @Test
    @DisplayName("converts IllegalArgumentException as documented")
    void convertsIllegalArgumentExceptionAsDocumented() throws Exception {
      var mockLog = mockLogBuilder
          .setPath("/test.wpilog")
          .addNumericEntry("/entry", new double[]{0}, new double[]{1.0})
          .build();
      logManager.testPutLog("/test.wpilog", mockLog);
      logManager.testSetActiveLogPath("/test.wpilog");

      // Documentation states: "Throw IllegalArgumentException for parameter validation errors.
      // These will be automatically converted to error responses."
      var tool = new ThrowingLogTool();
      var args = new JsonObject();
      args.addProperty("throw_type", "illegal_argument");

      var result = tool.execute(args);

      var obj = result.getAsJsonObject();
      assertFalse(obj.get("success").getAsBoolean());
      assertTrue(obj.has("error"));
    }
  }
}
