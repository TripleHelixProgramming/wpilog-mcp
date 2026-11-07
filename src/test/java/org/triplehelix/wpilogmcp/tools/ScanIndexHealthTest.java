/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.log.LogManager;

/** A fresh process makes zero disk-lookup counters an independent fact, not a prior result. */
class ScanIndexHealthTest {
  @ParameterizedTest @ValueSource(booleans = {true, false})
  void emptyCacheReportsExactIndexFieldsAndCounts(boolean enabled) throws Exception {
    var parent = Files.createDirectories(Path.of("build", "test-disk-cache", "scan-health").toAbsolutePath());
    var work = Files.createTempDirectory(parent, "probe-");
    var cache = Files.createDirectories(work.resolve("cache"));
    var stdout = work.resolve("stdout");
    var stderr = work.resolve("stderr");
    // This file is not an index: directory totals must not accidentally include it.
    Files.writeString(cache.resolve("unrelated.txt"), "not a scan index");
    var builder = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "-Duser.home=" + work.resolve("home"), "-cp", System.getProperty("java.class.path"),
        Child.class.getName(), Boolean.toString(enabled))
        .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
    builder.environment().keySet().removeIf(name -> name.startsWith("WPILOG_") || name.equals("TBA_API_KEY"));
    builder.environment().put("WPILOG_DISK_CACHE_DIR", cache.toString());
    builder.environment().put("XDG_DATA_HOME", work.resolve("xdg").toString());
    builder.environment().put("LOCALAPPDATA", work.resolve("local-app-data").toString());
    var child = builder.start();
    child.getOutputStream().close();
    try {
      assertTrue(child.waitFor(15, TimeUnit.SECONDS), "The isolated health probe must finish");
      assertEquals(0, child.exitValue(), () -> {
        try { return Files.readString(stderr); }
        catch (Exception error) { return error.toString(); }
      });
      var result = JsonParser.parseString(Files.readString(stdout)).getAsJsonObject();
      assertEquals("ok", result.get("status").getAsString());
      assertTrue(result.has("scan_index_disk_cache"),
          "health_check must report the persistent index instead of the unused parsed-log cache");
      assertFalse(result.has("parsed_log_disk_cache"), "The replaced released field must not remain misleading");
      var index = result.getAsJsonObject("scan_index_disk_cache");
      assertEquals(Set.of("entry_count", "size_bytes", "hits", "misses", "format_version",
          "recipe_version", "enabled", "directory"), index.keySet());
      for (var field : Set.of("entry_count", "size_bytes", "hits", "misses"))
        assertEquals(0L, index.get(field).getAsLong(), field + " is zero before any log lookup");
      assertEquals(2, index.get("format_version").getAsInt());
      assertEquals(1, index.get("recipe_version").getAsInt());
      assertEquals(enabled, index.get("enabled").getAsBoolean());
      assertTrue(Files.isSameFile(cache, Path.of(index.get("directory").getAsString())));
      var sync = result.getAsJsonObject("sync_disk_cache");
      assertNotNull(sync, "The existing REV synchronization cache remains visible");
      assertTrue(sync.get("enabled").getAsBoolean());
      assertEquals(0, sync.get("cached_files").getAsInt());
      assertEquals(0, sync.get("total_size_mb").getAsLong());
    } finally {
      child.destroyForcibly();
      child.waitFor(5, TimeUnit.SECONDS);
    }
  }

  @Test void descriptionNamesTheIndexCountersAndVersions() {
    String description = new CoreTools.HealthCheckTool().description();
    for (var field : Set.of("scan_index_disk_cache", "entry_count", "size_bytes", "hits",
        "misses", "format_version", "recipe_version", "enabled", "directory", "sync_disk_cache"))
      assertTrue(description.contains(field), "health_check description must name " + field);
  }

  /** No configuration file, server, network request or recording is opened by this probe. */
  public static final class Child {
    public static void main(String[] arguments) throws Exception {
      var manager = LogManager.getInstance();
      try {
        manager.getDiskCache().setEnabled(Boolean.parseBoolean(arguments[0]));
        System.out.println(new CoreTools.HealthCheckTool().execute(new JsonObject()));
      } finally { manager.shutdown(); }
    }
  }
}
