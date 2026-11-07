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
  @Test void importPreferenceDoesNotChooseBetweenConfiguredAndLeasedSources() throws Exception {
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
      assertEquals("SOURCE_SELECTION_REQUIRED", before.get("code").getAsString());
      assertEquals(2, before.getAsJsonArray("source_scopes").size());
      service.replace(new LocalIngestion.Policy(0, true, destination.toString(), null, List.of()));
      var result = tool.execute(new JsonObject()).getAsJsonObject();
      assertEquals("SOURCE_SELECTION_REQUIRED", result.get("code").getAsString());
      assertEquals(3, result.getAsJsonArray("source_scopes").size());
      for (var scope : directory.sourceScopes().snapshot()) {
        var selected = new JsonObject(); selected.addProperty("scope_id", scope.id());
        var page = tool.execute(selected).getAsJsonObject();
        assertEquals(scope.root().equals(destination) ? 0 : 1, page.get("log_count").getAsInt());
        assertEquals(scope.id(), page.getAsJsonObject("source_scope").get("scope_id").getAsString());
        selected.addProperty("view", "all");
        assertEquals(page.get("logs"), tool.execute(selected).getAsJsonObject().get("logs"));
        assertFalse(page.has("excluded_directories")); assertFalse(page.has("preferred_store_id"));
        if (scope.root().equals(leased)) assertEquals("leased", page.getAsJsonArray("log_directories").get(0).getAsJsonObject().get("origin").getAsString());
      }
    } finally {
      service.close(); logs.stores().clearLocalIngestion(service); logs.unloadAllLogs();
      ClientLeases.getInstance().remove(lease);
      ClientLeases.getInstance().transportClosed();
      logs.clearAllowedDirectories(); old.forEach(logs::addAllowedDirectory);
      directory.setLogDirectories(oldListing.stream().map(Path::toString).toList()); directory.clearCache();
    }
  }

  @Test void selectedStoreAndOriginalDirectoryRemainSeparateAfterImport() throws Exception {
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
      assertEquals("SOURCE_SELECTION_REQUIRED", tool.execute(new JsonObject()).getAsJsonObject().get("code").getAsString());
      var selected = new JsonObject(); selected.addProperty("scope_id", service.preferredStoreId());
      var result = tool.execute(selected).getAsJsonObject();
      assertEquals(1, result.get("log_count").getAsInt());
      assertTrue(Path.of(result.getAsJsonArray("logs").get(0).getAsJsonObject().get("path").getAsString()).startsWith(destination));
      assertFalse(result.has("source_sync")); assertFalse(result.has("source_files"));
      selected.addProperty("scope_id", directory.sourceScopes().snapshot().stream().filter(scope -> scope.root().equals(source)).findFirst().orElseThrow().id());
      assertEquals(2, tool.execute(selected).getAsJsonObject().get("log_count").getAsInt(), "Originals remain in their own source");
      selected.addProperty("view", "all");
      assertEquals(2, tool.execute(selected).getAsJsonObject().get("log_count").getAsInt());
      ImportFixture.write(original, 2);
      Files.setLastModifiedTime(original, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(original).toMillis() + 1000));
      assertEquals(2, tool.execute(selected).getAsJsonObject().get("log_count").getAsInt());
      var unknown = new JsonObject(); unknown.addProperty("view", "other");
      assertEquals("error", tool.execute(unknown).getAsJsonObject().get("status").getAsString());
    } finally {
      service.close(); logs.stores().clearLocalIngestion(service); logs.unloadAllLogs();
      logs.clearAllowedDirectories(); old.forEach(logs::addAllowedDirectory);
      directory.setLogDirectories(oldListing.stream().map(Path::toString).toList()); directory.clearCache();
    }
  }
}
