/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;

/** A matching filename or an offline neighbor is never the user's source choice. */
class SourceSelectionTest {
  @TempDir Path temp;
  final LogManager logs = LogManager.getInstance();
  final LogDirectory directory = LogDirectory.getInstance();
  Set<Path> oldAllowed;
  List<Path> oldDirectories;

  // The fixture folder's grant lasts for the JVM: take it before remember(), so restore() keeps it.
  @BeforeAll static void grantFixtureFolder() { FixtureLogs.defaultDirectory(); }

  @BeforeEach void remember() {
    oldAllowed = logs.getConfiguredDirectories(); oldDirectories = directory.getLogDirectories();
  }
  @AfterEach void restore() throws Exception {
    logs.addAllowedDirectory(temp);
    logs.release(temp); logs.clearAllowedDirectories(); oldAllowed.forEach(logs::addAllowedDirectory);
    directory.setLogDirectories(oldDirectories.stream().map(Path::toString).toList());
  }
  @Test void listingRequiresChoiceBeforeFilteringEvenWhenOtherRootIsUnavailable() throws Exception {
    var present = Files.createDirectory(temp.resolve("present")).toRealPath();
    var missing = temp.toRealPath().resolve("offline");
    try (var writer = new WpilogWriter(present.resolve("only-match.wpilog"), "")) {
      int entry = writer.start("counter", "int64", "", 0);
      writer.append(entry, 1_000_000, WpilogWriter.encodeInt64(42));
    }
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(present); logs.addAllowedDirectory(missing);
    directory.setLogDirectories(List.of(present.toString(), missing.toString()));
    var request = new JsonObject(); request.addProperty("name", "only-match");
    var result = new CoreTools.ListAvailableLogsTool().execute(request).getAsJsonObject();
    assertEquals("error", result.get("status").getAsString(), result.toString());
    assertEquals("SOURCE_SELECTION_REQUIRED", result.get("code").getAsString(), result.toString());
    assertEquals(2, result.getAsJsonArray("source_scopes").size());
    assertTrue(result.get("hint").getAsString().contains("ask the user"));
    assertFalse(result.has("logs"), "No file search precedes explicit selection");
  }

  @Test void onlyAPathWithNoRootIsRelativeToASource() throws Exception {
    // Fixtures sit under the build folder, on the working directory's drive: the drive a Windows path
    // such as \logs\match.wpilog names. JUnit's temporary folder can be on another drive.
    var fixture = FixtureLogs.generateAll(FixtureLogs.defaultDirectory()).stream()
        .filter(f -> f.id().equals("akit_match")).findFirst().orElseThrow().path().toRealPath();
    var other = Files.createDirectory(temp.resolve("other")).toRealPath();
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(fixture.getParent()); logs.addAllowedDirectory(other);
    directory.setLogDirectories(List.of(fixture.getParent().toString(), other.toString()));
    var byName = entries(fixture.getFileName().toString());
    assertEquals("SOURCE_SELECTION_REQUIRED", byName.get("code").getAsString(), byName.toString());
    // On Windows this drops the drive letter and keeps the root; elsewhere it is the whole path.
    var rooted = fixture.toString().substring(fixture.getRoot().toString().length() - 1);
    assertEquals(fixture, Path.of(rooted).toAbsolutePath(), "the fixture is on the working directory's drive");
    var full = entries(fixture.toString());
    var fromRoot = entries(rooted);
    assertEquals("ok", fromRoot.get("status").getAsString(), fromRoot.toString());
    assertEquals(full.get("entries"), fromRoot.get("entries"));
  }

  private static JsonObject entries(String path) throws Exception {
    var request = new JsonObject(); request.addProperty("path", path);
    return new CoreTools.ListEntriesTool().execute(request).getAsJsonObject();
  }

  @Test void explicitListingsAndRelativeReadsRemainInTheirSelectedDirectory() throws Exception {
    var first = Files.createDirectory(temp.resolve("one")).toRealPath();
    var second = Files.createDirectory(temp.resolve("two")).toRealPath();
    for (var root : List.of(first, second)) try (var writer = new WpilogWriter(root.resolve("same.wpilog"), "")) {
      int entry = writer.start("counter", "int64", "", 0);
      writer.append(entry, 1_000_000, WpilogWriter.encodeInt64(root.equals(first) ? 12 : 34));
      writer.append(entry, 2_000_000, WpilogWriter.encodeInt64(root.equals(first) ? 16 : 38));
    }
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(first); logs.addAllowedDirectory(second);
    directory.setLogDirectories(List.of(second.toString(), first.toString()));
    var registry = new org.triplehelix.wpilogmcp.mcp.ToolRegistry(); CoreTools.registerAll(registry);
    var choicePage = registry.getTool("list_source_scopes").execute(new JsonObject()).getAsJsonObject();
    assertEquals("ok", choicePage.get("status").getAsString(), choicePage.toString());
    assertEquals(2, choicePage.get("total").getAsInt());
    var scopes = directory.sourceScopes().snapshot();
    for (var selected : scopes) {
      var request = new JsonObject(); request.addProperty("scope_id", selected.id());
      var listed = new CoreTools.ListAvailableLogsTool().execute(request).getAsJsonObject();
      assertEquals(1, listed.get("log_count").getAsInt(), listed.toString());
      assertEquals(selected.root().resolve("same.wpilog").toString(), listed.getAsJsonArray("logs").get(0).getAsJsonObject().get("path").getAsString());
      request.addProperty("path", "same.wpilog"); request.addProperty("name", "counter");
      var stats = new StatisticsTools.GetStatisticsTool().execute(request).getAsJsonObject();
      assertEquals("ok", stats.get("status").getAsString(), stats.toString());
      assertEquals(selected.root().equals(first) ? 14.0 : 36.0, stats.get("mean").getAsDouble());
      assertEquals(2.8284271247461903, stats.get("std_dev").getAsDouble(), 1e-12);
      request.addProperty("path", (selected.root().equals(first) ? second : first).resolve("same.wpilog").toString());
      assertEquals("SOURCE_UNAVAILABLE", new StatisticsTools.GetStatisticsTool().execute(request).getAsJsonObject().get("code").getAsString());
    }
    var relative = new JsonObject(); relative.addProperty("path", "same.wpilog"); relative.addProperty("name", "counter");
    assertEquals("SOURCE_SELECTION_REQUIRED", new StatisticsTools.GetStatisticsTool().execute(relative).getAsJsonObject().get("code").getAsString());
    try (var read = logs.readAccess().acquire(first.resolve("same.wpilog").toString())) {
      assertEquals(2, read.sampleCount("counter"), "An absolute file path is an explicit source-qualified selection");
    }
  }

  @Test void unreadableRootDoesNotBlockSelectedHealthyDirectory() throws Exception {
    var healthy = Files.createDirectory(temp.resolve("healthy")).toRealPath();
    var denied = Files.createDirectory(temp.resolve("denied")).toRealPath();
    Assumptions.assumeTrue(Files.getFileStore(denied).supportsFileAttributeView("posix"), "POSIX permissions required");
    try (var writer = new WpilogWriter(healthy.resolve("synthetic.wpilog"), "")) {
      int entry = writer.start("counter", "int64", "", 0);
      writer.append(entry, 1_000_000, WpilogWriter.encodeInt64(7));
    }
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(healthy); logs.addAllowedDirectory(denied);
    directory.setLogDirectories(List.of(healthy.toString(), denied.toString()));
    var initial = directory.sourceScopes().snapshot();
    String chosen = initial.stream().filter(s -> s.root().equals(healthy)).findFirst().orElseThrow().id();
    String unavailable = initial.stream().filter(s -> s.root().equals(denied)).findFirst().orElseThrow().id();
    var permissions = Files.getPosixFilePermissions(denied);
    try {
      Files.setPosixFilePermissions(denied, Set.of());
      Assumptions.assumeFalse(Files.isReadable(denied), "Process can bypass directory permissions");
      var choices = new CoreTools.ListSourceScopesTool(new org.triplehelix.wpilogmcp.mcp.ToolRegistry())
          .execute(new JsonObject()).getAsJsonObject();
      assertEquals("ok", choices.get("status").getAsString(), choices.toString()); assertEquals(2, choices.get("total").getAsInt());
      var failed = directory.sourceScopes().snapshot().stream().filter(s -> s.id().equals(unavailable)).findFirst().orElseThrow();
      assertEquals("directory", failed.kind()); assertFalse(failed.available()); assertNotNull(failed.reason());
      var unselected = new JsonObject(); unselected.addProperty("name", "synthetic");
      var refused = new CoreTools.ListAvailableLogsTool().execute(unselected).getAsJsonObject();
      assertEquals("SOURCE_SELECTION_REQUIRED", refused.get("code").getAsString(), refused.toString());
      var selected = new JsonObject(); selected.addProperty("scope_id", chosen);
      var listing = new CoreTools.ListAvailableLogsTool().execute(selected).getAsJsonObject();
      assertEquals("ok", listing.get("status").getAsString(), listing.toString()); assertEquals(1, listing.get("log_count").getAsInt());
      assertEquals(healthy.resolve("synthetic.wpilog").toString(), listing.getAsJsonArray("logs").get(0).getAsJsonObject().get("path").getAsString());
      selected.addProperty("path", "synthetic.wpilog"); selected.addProperty("name", "counter");
      var stats = new StatisticsTools.GetStatisticsTool().execute(selected).getAsJsonObject();
      assertEquals("ok", stats.get("status").getAsString(), stats.toString());
      assertEquals(7, stats.get("mean").getAsDouble()); assertEquals(1, stats.get("count").getAsInt());
      selected = new JsonObject(); selected.addProperty("scope_id", unavailable);
      var bad = new CoreTools.ListAvailableLogsTool().execute(selected).getAsJsonObject();
      assertEquals("SOURCE_UNAVAILABLE", bad.get("code").getAsString(), bad.toString());
    } finally { Files.setPosixFilePermissions(denied, permissions); }
    assertTrue(directory.sourceScopes().select(unavailable).available());
  }

  @Test void aStoreBelowALinkedRootRetainsDirectoryTeamMetadata() throws Exception {
    var real = Files.createDirectory(temp.resolve("real")).toRealPath();
    var linked = temp.resolve("linked");
    try { Files.createSymbolicLink(linked, real); }
    catch (java.io.IOException | UnsupportedOperationException unavailable) {
      Assumptions.assumeTrue(false, "Symbolic links unavailable: " + unavailable); return;
    }
    var store = Files.createDirectory(real.resolve("store")); String id = java.util.UUID.randomUUID().toString();
    Files.writeString(store.resolve("store.json"), org.triplehelix.wpilogmcp.store.StoreJson.JSON.toJson(
        new org.triplehelix.wpilogmcp.store.StoreManifest.Header(1, "2026-01-01T00:00:00Z", id, List.of())));
    logs.clearAllowedDirectories(); logs.addAllowedDirectory(real);
    directory.setLogDirectory(linked.toString()); var oldTeam = directory.getDefaultTeamNumber();
    try {
      directory.setDefaultTeamNumber(37);
      var request = new JsonObject(); request.addProperty("scope_id", id);
      var result = new CoreTools.ListAvailableLogsTool().execute(request).getAsJsonObject();
      assertTrue(result.has("log_directories"), result.toString());
      var origin = result.getAsJsonArray("log_directories").get(0).getAsJsonObject();
      assertEquals(store.toString(), origin.get("path").getAsString());
      assertEquals("configured", origin.get("origin").getAsString()); assertEquals(37, origin.get("team").getAsInt());
    } finally { directory.setDefaultTeamNumber(oldTeam); }
  }
}
