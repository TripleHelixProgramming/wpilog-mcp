/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

/**
 * Readers take no lock, so a listing can run while a manifest is being replaced: written to a
 * temporary file beside it, then moved over it. The temporary file can vanish between the walk
 * listing it and reading it, and that is not a failure of the store.
 */
class StoreListingRaceTest {
  @TempDir Path temp;
  final LogManager manager = LogManager.getInstance(); Set<Path> allowed;
  @BeforeEach void setup() throws Exception { temp = temp.toRealPath(); allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp); }
  @AfterEach void cleanup() { manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }

  @Test void theWalkFindsRegularFilesAndLinksToThemAsBefore() throws Exception {
    var root = Files.createDirectories(temp.resolve("walk"));
    var file = Files.writeString(Files.createDirectories(root.resolve("a")).resolve("file.txt"), "x");
    var link = root.resolve("link.txt"); var dangling = root.resolve("dangling.txt");
    try { Files.createSymbolicLink(link, file); Files.createSymbolicLink(dangling, root.resolve("absent.txt")); }
    catch (UnsupportedOperationException | java.io.IOException noLinks) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "this file system cannot make links: " + noLinks.getMessage());
    }
    // Files.isRegularFile follows a link, so the walk counts one to a file and not one to nothing.
    assertEquals(java.util.List.of(file, link), StoreCatalog.regularFiles(root));
  }

  @Test void aListingDuringManifestReplacementsNeverFails() throws Exception {
    var root = temp.resolve("store");
    var capture = SystemSessionFixture.create(root, "boot", false, 42);
    var manifest = capture.resolveSibling("session.json");
    var security = manager.testGetSecurityValidator(); var io = new StoreFiles(root, security);
    var session = StoreJson.JSON.fromJson(Files.readString(manifest), Session.class);
    var stop = new AtomicBoolean(); var writerFailure = new AtomicReference<Throwable>();
    var writer = new Thread(() -> {
      try { while (!stop.get()) io.write(manifest, session); }
      catch (Throwable failure) { writerFailure.set(failure); }
    }, "manifest-writer");
    writer.start();
    int listings = 0;
    try {
      long deadline = System.nanoTime() + 4_000_000_000L;
      while (System.nanoTime() < deadline) {
        var snapshot = StoreCatalog.read(root, security);
        assertEquals(1, snapshot.sessions().size());
        assertTrue(snapshot.unmanaged().isEmpty(), () -> "a manifest being replaced is not a stray: " + snapshot.unmanaged());
        listings++;
      }
    } finally { stop.set(true); writer.join(10_000); }
    assertNull(writerFailure.get());
    assertTrue(listings > 10, listings + " listings");
  }
}
