package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

/**
 * Logic-level unit tests for CoreTools using synthetic log data.
 */
class CoreToolsLogicTest {

  private List<Tool> tools;

  @BeforeEach
  void setUp() {
    tools = new ArrayList<>();
    var capturingServer = new McpServer() {
      @Override
      public void registerTool(Tool tool) {
        tools.add(tool);
        super.registerTool(tool);
      }
    };
    CoreTools.registerAll(capturingServer);
  }

  @AfterEach
  void tearDown() {
    LogManager.getInstance().unloadAllLogs();
  }

  private Tool findTool(String name) {
    return tools.stream()
        .filter(t -> t.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Tool not found: " + name));
  }

  private void setActiveLog(ParsedLog log) {
    var manager = LogManager.getInstance();
    manager.testPutLog(log.path(), log);
    manager.testSetActiveLogPath(log.path());
  }

  @Nested
  @DisplayName("list_entries Tool")
  class ListEntriesToolTests {
    @Test
    @DisplayName("lists all entries in active log")
    void listsAllEntries() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/core.wpilog")
          .addNumericEntry("/Drive/Speed", new double[]{0}, new double[]{0})
          .addNumericEntry("/Arm/Angle", new double[]{0}, new double[]{0})
          .build();
      setActiveLog(log);

      var tool = findTool("list_entries");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var entries = resultObj.getAsJsonArray("entries");
      assertEquals(2, entries.size());
    }
  }

  @Nested
  @DisplayName("read_entry Tool")
  class ReadEntryToolTests {
    @Test
    @DisplayName("reads values with pagination")
    void readsPagedValues() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/core.wpilog")
          .addNumericEntry("/Test/Data", new double[]{0, 1, 2, 3, 4}, new double[]{10, 20, 30, 40, 50})
          .build();
      setActiveLog(log);

      var tool = findTool("read_entry");
      var args = new JsonObject();
      args.addProperty("name", "/Test/Data");
      args.addProperty("limit", 2);
      args.addProperty("offset", 1);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var samples = resultObj.getAsJsonArray("samples");
      assertEquals(2, samples.size());
      assertEquals(20.0, samples.get(0).getAsJsonObject().get("value").getAsDouble(), 0.001);
      assertEquals(30.0, samples.get(1).getAsJsonObject().get("value").getAsDouble(), 0.001);
    }
  }

  @Nested
  @DisplayName("list_struct_types Tool")
  class ListStructTypesToolTests {
    @Test
    @DisplayName("returns all struct type categories")
    void returnsStructTypeCategories() throws Exception {
      var tool = findTool("list_struct_types");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("struct_types"), "Should have struct_types field");

      var structTypes = resultObj.getAsJsonObject("struct_types");
      assertTrue(structTypes.has("geometry"), "Should have geometry types");
      assertTrue(structTypes.has("kinematics"), "Should have kinematics types");
      assertTrue(structTypes.has("vision"), "Should have vision types");

      // Check that geometry includes expected types
      var geometry = structTypes.getAsJsonArray("geometry");
      assertTrue(geometry.size() > 0, "Geometry should have struct types");

      // Check that kinematics includes expected types
      var kinematics = structTypes.getAsJsonArray("kinematics");
      assertTrue(kinematics.size() > 0, "Kinematics should have struct types");
    }

    @Test
    @DisplayName("includes standard WPILib struct types")
    void includesStandardWpilibStructs() throws Exception {
      var tool = findTool("list_struct_types");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      var structTypes = resultObj.getAsJsonObject("struct_types");
      var geometry = structTypes.getAsJsonArray("geometry");
      boolean hasPose2d = false;
      for (var element : geometry) {
        if (element.getAsString().equals("Pose2d")) {
          hasPose2d = true;
          break;
        }
      }
      assertTrue(hasPose2d, "Should include Pose2d in geometry types");
    }
  }

  @Nested
  @DisplayName("health_check Tool")
  class HealthCheckToolTests {
    @Test
    @DisplayName("returns system status with version")
    void returnsSystemStatus() throws Exception {
      var tool = findTool("health_check");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals("OK", resultObj.get("status").getAsString());
      assertTrue(resultObj.has("server_version"), "Should report server version");
      assertEquals(org.triplehelix.wpilogmcp.Version.VERSION,
          resultObj.get("server_version").getAsString());
      assertTrue(resultObj.has("loaded_logs"), "Should report loaded logs count");
      assertTrue(resultObj.has("tba_available"), "Should report TBA availability");
      assertTrue(resultObj.has("cache_memory_mb"), "Should report cache memory estimate");
    }

    @Test
    @DisplayName("includes JVM memory information")
    void includesJvmMemoryInfo() throws Exception {
      var tool = findTool("health_check");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("jvm_memory"), "Should include JVM memory info");
      var jvmMemory = resultObj.getAsJsonObject("jvm_memory");
      assertTrue(jvmMemory.has("used_mb"), "Should report used memory");
      assertTrue(jvmMemory.has("free_mb"), "Should report free memory");
      assertTrue(jvmMemory.has("total_mb"), "Should report total memory");
      assertTrue(jvmMemory.has("max_mb"), "Should report max memory");
    }

    @Test
    @DisplayName("includes disk cache status")
    void includesDiskCacheStatus() throws Exception {
      var tool = findTool("health_check");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("disk_cache"), "Should include disk cache info");
      var diskCache = resultObj.getAsJsonObject("disk_cache");
      assertTrue(diskCache.has("enabled"), "Should report enabled status");
      assertTrue(diskCache.has("format_version"), "Should report format version");
      assertTrue(diskCache.has("directory"), "Should report cache directory");
      assertEquals(org.triplehelix.wpilogmcp.cache.DiskCacheSerializer.CURRENT_FORMAT_VERSION,
          diskCache.get("format_version").getAsInt());
    }

    @Test
    @DisplayName("includes revlog sync status")
    void includesRevlogSyncStatus() throws Exception {
      var tool = findTool("health_check");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("revlog_sync_in_progress"),
          "Should report revlog sync status");
    }

    @Test
    @DisplayName("reports cache memory estimate when logs are loaded")
    void reportsCacheMemoryEstimate() throws Exception {
      // Load a log first
      var log = new MockLogBuilder()
          .setPath("/test/health.wpilog")
          .addNumericEntry("/Test/Data", new double[]{0, 1, 2}, new double[]{1, 2, 3})
          .build();
      setActiveLog(log);

      var tool = findTool("health_check");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("cache_memory_mb"), "Should report cache memory estimate");
      double cacheMemory = resultObj.get("cache_memory_mb").getAsDouble();
      assertTrue(cacheMemory >= 0, "Cache memory should be non-negative");
    }
  }

  @Nested
  @DisplayName("get_game_info Tool")
  class GetGameInfoToolTests {

    @Test
    @DisplayName("returns 2026 REBUILT game data")
    void returns2026Data() throws Exception {
      var tool = findTool("get_game_info");
      var args = new JsonObject();
      args.addProperty("season", 2026);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(2026, resultObj.get("season").getAsInt());
      assertEquals("REBUILT", resultObj.get("game_name").getAsString());
      assertTrue(resultObj.has("match_timing"));
      assertTrue(resultObj.has("scoring"));
      assertTrue(resultObj.has("field_geometry"));
      assertTrue(resultObj.has("game_pieces"));
      assertTrue(resultObj.has("analysis_hints"));
    }

    @Test
    @DisplayName("returns match timing details")
    void returnsMatchTiming() throws Exception {
      var tool = findTool("get_game_info");
      var args = new JsonObject();
      args.addProperty("season", 2026);

      var result = tool.execute(args);
      var timing = result.getAsJsonObject().getAsJsonObject("match_timing");

      assertEquals(20, timing.get("auto_duration_sec").getAsInt());
      assertEquals(140, timing.get("teleop_duration_sec").getAsInt());
      assertEquals(160, timing.get("total_duration_sec").getAsInt());
      assertEquals(30, timing.get("endgame_duration_sec").getAsInt());
    }

    @Test
    @DisplayName("returns error for unknown season")
    void returnsErrorForUnknownSeason() throws Exception {
      var tool = findTool("get_game_info");
      var args = new JsonObject();
      args.addProperty("season", 1999);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("1999"));
      assertTrue(resultObj.has("available_seasons"));
    }

    @Test
    @DisplayName("defaults to current season when no season specified")
    void defaultsToCurrentSeason() throws Exception {
      var tool = findTool("get_game_info");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      // Current year is 2026, which is bundled
      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(2026, resultObj.get("season").getAsInt());
    }
  }
}
