/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
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
    @DisplayName("a huge limit is capped at 10000 samples per page, and limits says so")
    void capsLimit() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/core_cap.wpilog")
          .addNumericEntry("/Test/Data", new double[]{0, 1}, new double[]{10, 20})
          .build();
      putLogInCache(log);
      var args = new JsonObject();
      args.addProperty("path", "/test/core_cap.wpilog");
      args.addProperty("name", "/Test/Data");
      args.addProperty("limit", 5_000_000);
      var r = findTool("read_entry").execute(args).getAsJsonObject();
      assertEquals(10_000, r.getAsJsonObject("limits").getAsJsonObject("samples").get("limit")
          .getAsInt());
      assertEquals(2, r.getAsJsonArray("samples").size());
    }

    @Test
    @DisplayName("get_entry_info suggests entries whatever the case of the name asked for")
    void caseInsensitiveSuggestions() throws Exception {
      var log = new MockLogBuilder()
          .setPath("/test/core_suggest.wpilog")
          .addNumericEntry("/Drive/Velocity", new double[]{0}, new double[]{1})
          .build();
      putLogInCache(log);
      var args = new JsonObject();
      args.addProperty("path", "/test/core_suggest.wpilog");
      args.addProperty("name", "/drive/velocity");
      var r = findTool("get_entry_info").execute(args).getAsJsonObject();
      assertFalse(r.get("success").getAsBoolean());
      assertTrue(r.toString().contains("/Drive/Velocity"), r.toString());
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
    @DisplayName("without a path: the fallback schemas, each with its source and fields")
    void fallbackSchemas() throws Exception {
      var result = findTool("list_struct_types").execute(new JsonObject()).getAsJsonObject();
      assertTrue(result.get("success").getAsBoolean());
      assertTrue(result.get("note").getAsString().contains("each log's own schemas"));
      var bySource = new java.util.HashMap<String, String>();
      for (var t : result.getAsJsonArray("struct_types")) {
        var o = t.getAsJsonObject();
        bySource.put(o.get("name").getAsString(), o.get("source").getAsString());
        assertTrue(o.get("valid").getAsBoolean(), o.toString());
        assertTrue(o.has("fields") && o.has("numeric_leaf_paths") && o.has("size_bytes"));
      }
      assertEquals("wpilib", bySource.get("Pose2d"));
      assertEquals("wpilib", bySource.get("SwerveModuleState"));
      assertEquals("assumed", bySource.get("PoseObservation"));
      assertEquals("assumed", bySource.get("SwerveSample"));
    }

    @Test
    @DisplayName("with a path: the log's struct types, including ones it logs no schema for")
    void perLog() throws Exception {
      var pose = new java.util.LinkedHashMap<String, Object>();
      pose.put("translation", java.util.Map.of("x", 1.0, "y", 2.0));
      pose.put("rotation", java.util.Map.of("value", 0.0));
      var log = new MockLogBuilder()
          .setPath("/test/structs.wpilog")
          .addEntry("/.schema/struct:Widget", "structschema", java.util.List.of(
              new org.triplehelix.wpilogmcp.log.TimestampedValue(0, "double a;int16 b[3]")))
          .addEntry("/Drive/Pose", "struct:Pose2d", java.util.List.of(
              new org.triplehelix.wpilogmcp.log.TimestampedValue(0, pose)))
          .addEntry("/Thing/Gadget", "struct:Gadget", java.util.List.of())
          .build();
      putLogInCache(log);
      var args = new JsonObject();
      args.addProperty("path", "/test/structs.wpilog");
      var result = findTool("list_struct_types").execute(args).getAsJsonObject();
      assertTrue(result.get("success").getAsBoolean(), result.toString());
      var types = result.getAsJsonArray("struct_types");
      assertEquals(3, types.size(), types.toString());
      var widget = types.get(0).getAsJsonObject();
      assertEquals("Widget", widget.get("name").getAsString());
      assertEquals("logged", widget.get("source").getAsString());
      assertEquals(14, widget.get("size_bytes").getAsInt());
      assertEquals(0, widget.get("entry_count").getAsInt());
      var poseType = types.get(1).getAsJsonObject();
      assertEquals("wpilib", poseType.get("source").getAsString());
      assertEquals("/Drive/Pose", poseType.getAsJsonArray("entries").get(0).getAsString());
      var gadget = types.get(2).getAsJsonObject();
      assertEquals("missing", gadget.get("source").getAsString());
      assertFalse(gadget.get("valid").getAsBoolean());
      assertTrue(result.getAsJsonArray("warnings").get(0).getAsString()
          .contains("struct Gadget cannot be decoded"), result.toString());
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
      assertEquals("ok", resultObj.get("status").getAsString());
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

      assertTrue(resultObj.has("sync_disk_cache"), "Should include the revlog sync disk cache");
      var syncCache = resultObj.getAsJsonObject("sync_disk_cache");
      assertTrue(syncCache.has("enabled"), "Should report the sync cache's enabled status");
      assertTrue(syncCache.has("directory"), "Should report the cache directory");

      assertTrue(resultObj.has("parsed_log_disk_cache"),
          "Should include the parsed-log disk cache under a label that says it is unused");
      var parsedLogCache = resultObj.getAsJsonObject("parsed_log_disk_cache");
      assertFalse(parsedLogCache.get("used_by_load_path").getAsBoolean(),
          "The parsed-log cache has not been on the load path since 0.8.0");
      assertTrue(parsedLogCache.has("enabled"), "Should report enabled status");
      assertTrue(parsedLogCache.has("format_version"), "Should report format version");
      assertTrue(parsedLogCache.has("directory"), "Should report cache directory");
      assertEquals(org.triplehelix.wpilogmcp.cache.DiskCacheSerializer.CURRENT_FORMAT_VERSION,
          parsedLogCache.get("format_version").getAsInt());
      assertFalse(resultObj.has("disk_cache"),
          "The block that reported the unused cache as the disk cache is gone");
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

    /** Five logs: two from each of two events, one without an event; distinct mtimes. */
    void writeLogs(Path dir) throws Exception {
      String[] names = {"akit_26-03-20_10-00-00_vache_q10.wpilog",
          "akit_26-03-21_11-00-00_vache_sf2.wpilog", "akit_26-04-02_09-00-00_dcmp_q5.wpilog",
          "akit_26-04-03_09-30-00_dcmp_f1.wpilog", "bench_test.wpilog"};
      long base = java.time.Instant.parse("2026-03-01T00:00:00Z").toEpochMilli();
      for (int i = 0; i < names.length; i++) {
        var file = dir.resolve(names[i]);
        Files.write(file, new byte[] {0});
        Files.setLastModifiedTime(file,
            java.nio.file.attribute.FileTime.fromMillis(base + i * 86_400_000L * 10));
      }
      LogDirectory.getInstance().setLogDirectory(dir.toString());
    }

    JsonObject list(Object... kv) throws Exception {
      var args = new JsonObject();
      for (int i = 0; i < kv.length; i += 2) {
        if (kv[i + 1] instanceof Number n) args.addProperty((String) kv[i], n);
        else args.addProperty((String) kv[i], kv[i + 1].toString());
      }
      return findTool("list_available_logs").execute(args).getAsJsonObject();
    }

    @Test
    @DisplayName("pages with true totals, newest first, stable order")
    void paging(@TempDir Path tempDir) throws Exception {
      writeLogs(tempDir);
      var first = list("limit", 2);
      assertEquals(5, first.get("log_count").getAsInt());
      assertEquals(2, first.get("returned").getAsInt());
      assertTrue(first.get("has_more").getAsBoolean());
      assertEquals(5, first.getAsJsonObject("limits").getAsJsonObject("logs").get("total")
          .getAsInt());
      var last = list("limit", 2, "offset", 4);
      assertEquals(1, last.get("returned").getAsInt());
      assertFalse(last.get("has_more").getAsBoolean());
      var again = list("limit", 2);
      assertEquals(first.getAsJsonArray("logs"), again.getAsJsonArray("logs"));
    }

    @Test
    @DisplayName("filters by name, event, match type, and date")
    void filters(@TempDir Path tempDir) throws Exception {
      writeLogs(tempDir);
      assertEquals(1, list("name", "BENCH").get("log_count").getAsInt());
      assertEquals(2, list("event", "vache").get("log_count").getAsInt());
      var qual = list("match_type", "qm");
      assertEquals(2, qual.get("log_count").getAsInt(), qual.toString());
      for (var l : qual.getAsJsonArray("logs")) {
        assertTrue(l.getAsJsonObject().get("filename").getAsString().contains("_q"), qual.toString());
      }
      assertEquals(1, list("match_type", "sf").get("log_count").getAsInt());
      assertTrue(list("match_type", "zz").get("error").getAsString().contains("Unknown match_type"));
      var recent = list("since", "2026-04-01");
      assertTrue(recent.get("log_count").getAsInt() >= 2, recent.toString());
      for (var l : recent.getAsJsonArray("logs")) {
        assertFalse(l.getAsJsonObject().get("filename").getAsString().contains("26-03-2"),
            recent.toString());
      }
      var none = list("event", "nope");
      assertEquals("no_match", none.get("status").getAsString());
      assertEquals(5, none.get("total_logs").getAsInt());
      var bad = list("since", "yesterday");
      assertTrue(bad.get("error").getAsString().contains("since must be a date"), bad.toString());
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
