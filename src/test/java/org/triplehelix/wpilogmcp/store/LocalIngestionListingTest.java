/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.CoreTools;

class LocalIngestionListingTest {
  @TempDir Path temp;
  @Test void defaultShowsTheStoreAndPendingVersionsWhileAllKeepsSources() throws Exception {
    temp = temp.toRealPath();
    var logs = LogManager.getInstance(); var directory = LogDirectory.getInstance();
    var old = logs.getConfiguredDirectories(); var oldListing = directory.getLogDirectories();
    var source = Files.createDirectory(temp.resolve("source")); var destination = Files.createDirectory(temp.resolve("store"));
    var original = ImportFixture.write(source.resolve("original.wpilog"), 1);
    Files.copy(original, source.resolve("copy.wpilog"));
    logs.addAllowedDirectory(temp); directory.setLogDirectories(List.of(source.toString()));
    var service = logs.stores().configureLocalIngestion(temp.resolve("policy.json"));
    try {
      var tools = new ToolRegistry(); CoreTools.registerAll(tools);
      var tool = tools.getTool("list_available_logs");
      assertEquals(2, tool.execute(new JsonObject()).getAsJsonObject().get("log_count").getAsInt());
      service.replace(new LocalIngestion.Policy(0, true, destination.toString(), null,
          List.of(new LocalIngestion.Source(source.toString(), "copy", true, false, "practice"))));
      service.poll(0); service.currentWork().get(30, TimeUnit.SECONDS); service.poll(TimeUnit.SECONDS.toNanos(3)); service.currentWork().get(30, TimeUnit.SECONDS);
      var result = tool.execute(new JsonObject()).getAsJsonObject();
      assertEquals(1, result.get("log_count").getAsInt(), "Confirmed source duplicates are hidden before counting and paging");
      assertTrue(Path.of(result.getAsJsonArray("logs").get(0).getAsJsonObject().get("path").getAsString()).startsWith(destination));
      assertTrue(result.has("source_sync")); assertTrue(result.has("source_files"));
      var all = new JsonObject(); all.addProperty("view", "all");
      assertEquals(3, tool.execute(all).getAsJsonObject().get("log_count").getAsInt());
      ImportFixture.write(original, 2);
      Files.setLastModifiedTime(original, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(original).toMillis() + 1000));
      assertEquals(2, tool.execute(new JsonObject()).getAsJsonObject().get("log_count").getAsInt(), "New source version stays visible until imported");
      var unknown = new JsonObject(); unknown.addProperty("view", "other");
      assertEquals("error", tool.execute(unknown).getAsJsonObject().get("status").getAsString());
    } finally {
      service.close(); logs.stores().clearLocalIngestion(service); logs.unloadAllLogs();
      logs.clearAllowedDirectories(); old.forEach(logs::addAllowedDirectory);
      directory.setLogDirectories(oldListing.stream().map(Path::toString).toList()); directory.clearCache();
    }
  }
}
