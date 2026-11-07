/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.store.StoreRegistry;
import org.triplehelix.wpilogmcp.tools.CoreTools;

/** Follow only the explicitly configured root, not a link encountered during discovery. */
class LinkedLogDirectoryTest {
  @TempDir Path temp;
  final LogDirectory directory = LogDirectory.getInstance();
  final LogManager manager = LogManager.getInstance();
  List<Path> previousDirectories;
  Set<Path> previousAllowed;

  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath();
    previousDirectories = directory.getLogDirectories();
    previousAllowed = manager.getAllowedDirectories();
  }

  @AfterEach void restore() {
    manager.clearAllLogs();
    manager.clearAllowedDirectories();
    previousAllowed.forEach(manager::addAllowedDirectory);
    directory.setLogDirectories(previousDirectories.stream().map(Path::toString).toList());
    directory.clearCache();
  }

  @Test void linkedRootListsLoadablePathsAndRevCompanionsWithTheConfiguredSpelling() throws Exception {
    var real = Files.createDirectory(temp.resolve("real logs"));
    ImportFixture.write(real.resolve("nested").resolve("test.wpilog"), 42);
    var rev = Files.write(real.resolve("nested").resolve("REV_20260101_000000.revlog"), new byte[0]);
    var link = link("linked logs", real);
    configure(link);

    var registry = new ToolRegistry();
    CoreTools.registerAll(registry);
    var listing = registry.getTool("list_available_logs").execute(new JsonObject()).getAsJsonObject();
    assertEquals("ok", listing.get("status").getAsString(), listing.toString());
    var logs = listing.getAsJsonArray("logs");
    assertEquals(1, logs.size(), "A configured symlink root must list its WPILOG");
    var listed = Path.of(logs.get(0).getAsJsonObject().get("path").getAsString());
    assertEquals(link.resolve("nested").resolve("test.wpilog"), listed);
    var args = new JsonObject();
    args.addProperty("path", listed.toString());
    args.addProperty("name", "/Value");
    var read = registry.getTool("read_entry").execute(args).getAsJsonObject();
    assertEquals("ok", read.get("status").getAsString(), read.toString());
    var samples = read.getAsJsonArray("samples");
    assertEquals(2, samples.size());
    assertEquals(42, samples.get(0).getAsJsonObject().get("value").getAsDouble());
    assertEquals(43, samples.get(1).getAsJsonObject().get("value").getAsDouble());
    var expectedRev = link.resolve(real.relativize(rev));
    assertEquals(List.of(expectedRev), directory.listRevLogFiles().stream().map(LogDirectory.RevLogFileInfo::path).toList());
    assertEquals(List.of(expectedRev), directory.listRevLogFilesInDirectory(link).stream().map(LogDirectory.RevLogFileInfo::path).toList());
  }

  @Test void childLinksAreNotFollowedEvenWhenTheirTargetsAreOtherwiseAllowed() throws Exception {
    var real = Files.createDirectory(temp.resolve("real"));
    var outside = Files.createDirectory(temp.resolve("outside"));
    ImportFixture.write(real.resolve("own.wpilog"), 1);
    var other = ImportFixture.write(outside.resolve("other.wpilog"), 2);
    linkAt(real.resolve("escape"), outside);
    linkAt(real.resolve("cycle"), real);
    linkAt(real.resolve("alias.wpilog"), other);
    var root = link("root", real);
    configure(root);
    manager.addAllowedDirectory(outside);
    assertEquals(List.of(root.resolve("own.wpilog").toString()),
        directory.listAvailableLogs().stream().map(LogDirectory.LogFileInfo::path).toList(),
        "Discovery must not follow child links or cycles");
  }

  @Test void linkedStoreAndLinkedSubdirectoryKeepCatalogMembershipAndContainment() throws Exception {
    var store = Files.createDirectory(temp.resolve("store"));
    var source = ImportFixture.sameClockRevPair(temp.resolve("input"), "pair");
    var security = new SecurityValidator();
    security.addAllowedDirectory(temp);
    try (var stores = new StoreRegistry(security)) {
      stores.store(store).importPaths(new LogStore.Request(List.of(source.getParent()), false, "fixture"), p -> {})
          .get(30, TimeUnit.SECONDS);
      var catalog = StoreCatalog.read(store, security);
      var wpilog = catalog.files().stream().filter(f -> f.file().kind().equals("wpilog")).findFirst().orElseThrow();
      var rev = catalog.files().stream().filter(f -> f.file().kind().equals("revlog")).findFirst().orElseThrow();
      var root = link("store link", store);
      configure(root);
      assertEquals(List.of(wpilog.path().toString()),
          directory.listAvailableLogs().stream().map(LogDirectory.LogFileInfo::path).toList());
      assertEquals(List.of(rev.path()),
          directory.listRevLogFiles().stream().map(LogDirectory.RevLogFileInfo::path).toList());
      var registry = new ToolRegistry();
      CoreTools.registerAll(registry);
      var args = new JsonObject();
      args.addProperty("path", wpilog.path().toString());
      args.addProperty("name", "systemTime");
      var read = registry.getTool("read_entry").execute(args).getAsJsonObject();
      assertEquals("ok", read.get("status").getAsString(), read.toString());

      var payloads = link("payload link", rev.path().getParent());
      Files.write(payloads.resolve("unmanifested.revlog"), new byte[0]);
      assertEquals(List.of(rev.path()),
          directory.listRevLogFilesInDirectory(payloads).stream().map(LogDirectory.RevLogFileInfo::path).toList(),
          "A linked subtree uses the already authorized store, not loose-file discovery");
      var elsewhere = link("other subtree", Files.createDirectory(store.resolve("elsewhere")));
      assertTrue(directory.listRevLogFilesInDirectory(elsewhere).isEmpty(), "Keep the realDir prefix filter");
    }
  }

  private void configure(Path root) {
    directory.setLogDirectory(root.toString());
    manager.clearAllowedDirectories();
    manager.addAllowedDirectory(root);
  }

  private Path link(String name, Path target) throws IOException {
    return linkAt(temp.resolve(name), target);
  }

  private Path linkAt(Path link, Path target) throws IOException {
    try { return Files.createSymbolicLink(link, target); }
    catch (IOException | UnsupportedOperationException | SecurityException e) {
      assumeTrue(false, "Platform refused symbolic-link creation: " + e);
      throw new AssertionError(e);
    }
  }
}
