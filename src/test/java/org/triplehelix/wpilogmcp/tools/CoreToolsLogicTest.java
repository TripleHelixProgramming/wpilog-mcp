package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * Logic-level unit tests for CoreTools using synthetic log data.
 */
class CoreToolsLogicTest extends ToolTestBase {

  @Override
  protected void registerTools(ToolRegistry registry) {
    CoreTools.registerAll(registry);
    FrcDomainTools.registerAll(registry);
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
      putLogInCache(log);

      var tool = findTool("list_entries");
      var args = new JsonObject();
      args.addProperty("path", "/test/core.wpilog");
      var result = tool.execute(args);
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
      putLogInCache(log);

      var tool = findTool("read_entry");
      var args = new JsonObject();
      args.addProperty("path", "/test/core.wpilog");
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

    @Test
    @DisplayName("returns error for negative offset")
    void returnsErrorForNegativeOffset() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/core.wpilog")
          .addNumericEntry("/Test/Data", new double[]{0, 1, 2}, new double[]{10, 20, 30})
          .build();
      putLogInCache(log);

      var tool = findTool("read_entry");
      var args = new JsonObject();
      args.addProperty("path", "/test/core.wpilog");
      args.addProperty("name", "/Test/Data");
      args.addProperty("offset", -1);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("offset"));
    }

    @Test
    @DisplayName("returns error for zero limit")
    void returnsErrorForZeroLimit() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/core.wpilog")
          .addNumericEntry("/Test/Data", new double[]{0, 1, 2}, new double[]{10, 20, 30})
          .build();
      putLogInCache(log);

      var tool = findTool("read_entry");
      var args = new JsonObject();
      args.addProperty("path", "/test/core.wpilog");
      args.addProperty("name", "/Test/Data");
      args.addProperty("limit", 0);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("limit"));
    }

    @Test
    @DisplayName("returns error for negative limit")
    void returnsErrorForNegativeLimit() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/core.wpilog")
          .addNumericEntry("/Test/Data", new double[]{0, 1, 2}, new double[]{10, 20, 30})
          .build();
      putLogInCache(log);

      var tool = findTool("read_entry");
      var args = new JsonObject();
      args.addProperty("path", "/test/core.wpilog");
      args.addProperty("name", "/Test/Data");
      args.addProperty("limit", -5);

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("limit"));
    }

    @Test
    @DisplayName("returns error for non-existent entry name")
    void returnsErrorForNonExistentEntry() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/core.wpilog")
          .addNumericEntry("/Test/Data", new double[]{0, 1, 2}, new double[]{10, 20, 30})
          .build();
      putLogInCache(log);

      var tool = findTool("read_entry");
      var args = new JsonObject();
      args.addProperty("path", "/test/core.wpilog");
      args.addProperty("name", "/NonExistent/Entry");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.get("error").getAsString().contains("not found"),
          "Error should indicate entry was not found");
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
      assertTrue(resultObj.has("jvm_heap_used_mb"), "Should report cache memory estimate");
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
      putLogInCache(log);

      var tool = findTool("health_check");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.has("jvm_heap_used_mb"), "Should report cache memory estimate");
      double cacheMemory = resultObj.get("jvm_heap_used_mb").getAsDouble();
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

  @Nested
  @DisplayName("list_available_logs Tool")
  class ListAvailableLogsTests {

    private Path savedLogDirectory;

    @BeforeEach
    void saveLogDirectoryState() {
      savedLogDirectory = LogDirectory.getInstance().getLogDirectory();
    }

    @AfterEach
    void restoreLogDirectoryState() {
      if (savedLogDirectory != null) {
        LogDirectory.getInstance().setLogDirectory(savedLogDirectory.toString());
      } else {
        LogDirectory.getInstance().setLogDirectory(null);
      }
      LogDirectory.getInstance().clearCache();
    }

    @Test
    @DisplayName("returns error when log directory is not configured")
    void returnsErrorWhenNotConfigured() throws Exception {
      LogDirectory.getInstance().setLogDirectory(null);

      var tool = findTool("list_available_logs");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("error"), "Should have error field");
      assertTrue(resultObj.get("error").getAsString().contains("not configured"),
          "Error should mention directory not configured");
      assertTrue(resultObj.has("hint"), "Should have hint field");
    }

    @Test
    @DisplayName("returns error when log directory does not exist")
    void returnsErrorWhenDirectoryDoesNotExist() throws Exception {
      LogDirectory.getInstance().setLogDirectory("/nonexistent/path/that/does/not/exist");

      var tool = findTool("list_available_logs");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertFalse(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("error"), "Should have error field");
    }

    @Test
    @DisplayName("returns empty list when directory has no wpilog files")
    void returnsEmptyListWhenNoLogs(@TempDir Path tempDir) throws Exception {
      // Create a non-wpilog file so directory is not empty
      Files.createFile(tempDir.resolve("readme.txt"));
      LogDirectory.getInstance().setLogDirectory(tempDir.toString());

      var tool = findTool("list_available_logs");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(0, resultObj.get("log_count").getAsInt());
      var logs = resultObj.getAsJsonArray("logs");
      assertEquals(0, logs.size());
    }

    @Test
    @DisplayName("returns logs when directory has wpilog files")
    void returnsLogsWhenFilesExist(@TempDir Path tempDir) throws Exception {
      // Create dummy .wpilog files (content doesn't matter for listing)
      Files.write(tempDir.resolve("test1.wpilog"), new byte[]{0});
      Files.write(tempDir.resolve("test2.wpilog"), new byte[]{0});
      LogDirectory.getInstance().setLogDirectory(tempDir.toString());

      var tool = findTool("list_available_logs");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(2, resultObj.get("log_count").getAsInt());
      var logs = resultObj.getAsJsonArray("logs");
      assertEquals(2, logs.size());

      // Verify each log entry has required fields
      for (var logElement : logs) {
        var logObj = logElement.getAsJsonObject();
        assertTrue(logObj.has("friendly_name"), "Each log should have friendly_name");
        assertTrue(logObj.has("path"), "Each log should have path");
        assertTrue(logObj.has("filename"), "Each log should have filename");
        assertTrue(logObj.has("size_bytes"), "Each log should have size_bytes");
        assertTrue(logObj.has("last_modified"), "Each log should have last_modified");
      }
    }

    @Test
    @DisplayName("includes metadata_cache and log_directory in response")
    void includesMetadataInResponse(@TempDir Path tempDir) throws Exception {
      Files.write(tempDir.resolve("data.wpilog"), new byte[]{0});
      LogDirectory.getInstance().setLogDirectory(tempDir.toString());

      var tool = findTool("list_available_logs");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertTrue(resultObj.has("log_directory"), "Should include log_directory");
      assertTrue(resultObj.has("metadata_cache"), "Should include metadata_cache");
      var cache = resultObj.getAsJsonObject("metadata_cache");
      assertTrue(cache.has("size"), "Cache should have size stat");
      assertTrue(cache.has("hits"), "Cache should have hits stat");
      assertTrue(cache.has("misses"), "Cache should have misses stat");
    }

    @Test
    @DisplayName("ignores non-wpilog files in directory")
    void ignoresNonWpilogFiles(@TempDir Path tempDir) throws Exception {
      Files.write(tempDir.resolve("valid.wpilog"), new byte[]{0});
      Files.write(tempDir.resolve("notes.txt"), new byte[]{0});
      Files.write(tempDir.resolve("data.csv"), new byte[]{0});
      Files.write(tempDir.resolve("other.revlog"), new byte[]{0});
      LogDirectory.getInstance().setLogDirectory(tempDir.toString());

      var tool = findTool("list_available_logs");
      var result = tool.execute(new JsonObject());
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      assertEquals(1, resultObj.get("log_count").getAsInt());
      var logs = resultObj.getAsJsonArray("logs");
      assertEquals("valid.wpilog", logs.get(0).getAsJsonObject().get("filename").getAsString());
    }
  }

  @Nested
  @DisplayName("get_entry_info Tool")
  class GetEntryInfoToolTests {

    @Test
    @DisplayName("single value produces exactly 1 sample")
    void testGetEntryInfoSingleValue() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/entry_info.wpilog")
          .addNumericEntry("/Test/Single", new double[]{0.0}, new double[]{42.0})
          .build();
      putLogInCache(log);

      var tool = findTool("get_entry_info");
      var args = new JsonObject();
      args.addProperty("path", "/test/entry_info.wpilog");
      args.addProperty("name", "/Test/Single");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var samples = resultObj.getAsJsonArray("sample_values");
      assertEquals(1, samples.size(),
          "Single value entry should have exactly 1 sample");
    }

    @Test
    @DisplayName("two values produces exactly 2 samples")
    void testGetEntryInfoTwoValues() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/entry_info.wpilog")
          .addNumericEntry("/Test/Two", new double[]{0.0, 1.0}, new double[]{10.0, 20.0})
          .build();
      putLogInCache(log);

      var tool = findTool("get_entry_info");
      var args = new JsonObject();
      args.addProperty("path", "/test/entry_info.wpilog");
      args.addProperty("name", "/Test/Two");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var samples = resultObj.getAsJsonArray("sample_values");
      assertEquals(2, samples.size(),
          "Two value entry should have exactly 2 samples (first and last are the same as middle collapses via distinct)");
    }

    @Test
    @DisplayName("many values produces exactly 3 samples (first, middle, last)")
    void testGetEntryInfoManyValues() throws Exception {
      var timestamps = new double[100];
      var values = new double[100];
      for (int i = 0; i < 100; i++) {
        timestamps[i] = i * 0.1;
        values[i] = i * 2.0;
      }
      var log = new MockLogBuilder()
          .setPath("/test/entry_info.wpilog")
          .addNumericEntry("/Test/Many", timestamps, values)
          .build();
      putLogInCache(log);

      var tool = findTool("get_entry_info");
      var args = new JsonObject();
      args.addProperty("path", "/test/entry_info.wpilog");
      args.addProperty("name", "/Test/Many");

      var result = tool.execute(args);
      var resultObj = result.getAsJsonObject();

      assertTrue(resultObj.get("success").getAsBoolean());
      var samples = resultObj.getAsJsonArray("sample_values");
      assertEquals(3, samples.size(),
          "100 value entry should have exactly 3 samples (first, middle, last)");
    }
  }
}
