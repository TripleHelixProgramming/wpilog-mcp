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
import org.triplehelix.wpilogmcp.config.ClientLeases;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.CoreTools;

class LocalIngestionListingTest {
  @TempDir Path temp;
  @Test void preferredViewNamesConfiguredAndLeasedDirectoriesItDoesNotSearch() throws Exception {
    temp = temp.toRealPath();
    var logs = LogManager.getInstance(); var directory = LogDirectory.getInstance();
    var old = logs.getConfiguredDirectories(); var oldListing = directory.getLogDirectories();
    var configured = Files.createDirectory(temp.resolve("configured"));
    var leased = Files.createDirectory(temp.resolve("leased"));
    var destination = Files.createDirectory(temp.resolve("store"));
    var configuredLog = ImportFixture.write(configured.resolve("configured.wpilog"), 1);
    var leasedLog = ImportFixture.write(leased.resolve("leased.wpilog"), 2);
    logs.addAllowedDirectory(temp); directory.setLogDirectories(List.of(configured.toString()));
    var lease = "listing-excluded-" + java.util.UUID.randomUUID();
    ClientLeases.getInstance().transportOpened();
    ClientLeases.getInstance().replaceDirectories(lease, List.of(new ClientLeases.Directory(leased, null)));
    var service = logs.stores().configureLocalIngestion(temp.resolve("policy.json"));
    try {
      var tools = new ToolRegistry(); CoreTools.registerAll(tools);
      var tool = tools.getTool("list_available_logs");
      var before = tool.execute(new JsonObject()).getAsJsonObject();
      assertEquals(2, before.get("log_count").getAsInt());
      assertFalse(before.has("excluded_directories"), "No preferred store excludes no directories");
      assertFalse(before.has("hint"));
      service.replace(new LocalIngestion.Policy(0, true, destination.toString(), null, List.of()));
      var result = tool.execute(new JsonObject()).getAsJsonObject();
      assertEquals(0, result.get("log_count").getAsInt());
      assertTrue(result.has("excluded_directories"),
          "The preferred-store view must name directories that it did not search");
      var excluded = result.getAsJsonArray("excluded_directories");
      assertEquals(2, excluded.size());
      assertEquals(configured.toString(), excluded.get(0).getAsJsonObject().get("path").getAsString());
      assertEquals("configured", excluded.get(0).getAsJsonObject().get("origin").getAsString());
      assertEquals(leased.toString(), excluded.get(1).getAsJsonObject().get("path").getAsString());
      assertEquals("leased", excluded.get(1).getAsJsonObject().get("origin").getAsString());
      assertEquals(2, result.getAsJsonObject("limits").getAsJsonObject("excluded_directories").get("total").getAsInt());
      assertTrue(result.get("hint").getAsString().contains("view: all"));
      var all = new JsonObject(); all.addProperty("view", "all");
      var allResult = tool.execute(all).getAsJsonObject();
      assertEquals(2, allResult.get("log_count").getAsInt());
      var paths = allResult.getAsJsonArray("logs").asList().stream()
          .map(value -> value.getAsJsonObject().get("path").getAsString()).toList();
      assertTrue(paths.contains(configuredLog.toString()));
      assertTrue(paths.contains(leasedLog.toString()));
      assertFalse(allResult.has("excluded_directories"));
      assertFalse(allResult.getAsJsonObject("limits").has("excluded_directories"));
      assertFalse(allResult.has("hint"));
    } finally {
      service.close(); logs.stores().clearLocalIngestion(service); logs.unloadAllLogs();
      ClientLeases.getInstance().remove(lease);
      ClientLeases.getInstance().transportClosed();
      logs.clearAllowedDirectories(); old.forEach(logs::addAllowedDirectory);
      directory.setLogDirectories(oldListing.stream().map(Path::toString).toList()); directory.clearCache();
    }
  }

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
      assertFalse(result.has("excluded_directories"), "No notice when every directory is searched");
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
