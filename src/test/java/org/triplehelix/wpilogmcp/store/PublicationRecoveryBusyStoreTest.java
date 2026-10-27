/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * Publication recovery reads the store without its lock while writers keep working. A manifest
 * write's temporary file vanishing once listed, or a receipt rewritten while it was read, used to
 * fail the pass like damage would, and with it capture startup, for good.
 */
class PublicationRecoveryBusyStoreTest {
  private static final Clock WALL = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
  @TempDir Path directory;
  private final LogManager manager = LogManager.getInstance();
  private Set<Path> allowed;
  private LogStore store;

  @BeforeEach void setup() throws Exception {
    directory = directory.toRealPath();
    allowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(directory);
    store = manager.stores().store(directory.resolve("store"));
    var imported = store.importPaths(new LogStore.Request(
        List.of(ImportFixture.write(directory.resolve("seed.wpilog"), 7)), false, "RIO"), ignored -> {}).get(10, TimeUnit.SECONDS);
    assertEquals("imported", imported.files().get(0).status());
    store.refreshStatus().get(10, TimeUnit.SECONDS);
  }

  @AfterEach void cleanup() throws Exception {
    if (store != null) store.close();
    manager.unloadAllLogs();
    manager.clearAllowedDirectories();
    allowed.forEach(manager::addAllowedDirectory);
  }

  @Test void aTemporaryManifestRenamedOverItsManifestOnceListedIsSkipped() throws Exception {
    var manifest = sessionFolders().get(0).resolve("session.json");
    var temporary = Files.copy(manifest, manifest.resolveSibling(".manifest-walk.tmp"));
    var renamed = new AtomicBoolean();
    store.publicationWalkProbe(entry -> {
      // A manifest write lands here: the walk has listed its temporary file, which now replaces the manifest.
      if (entry.getFileName().equals(temporary.getFileName()) && renamed.compareAndSet(false, true))
        Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    });
    assertDoesNotThrow(() -> store.refreshStatus().get(10, TimeUnit.SECONDS));
    assertTrue(renamed.get(), "The walk listed the temporary file before it was renamed");
    assertEquals(ReplicaPublications.State.READY, store.publicationStatus().state());
  }

  @Test void anotherEntryThatVanishesOnceListedLeavesThePassIncompleteButRetryable() throws Exception {
    var moving = Files.createDirectories(store.root().resolve("moving"));
    store.publicationWalkProbe(entry -> {
      // A folder that moves may carry a marker past the walk: the pass cannot call itself complete.
      if (entry.getFileName().equals(moving.getFileName())) Files.deleteIfExists(entry);
    });
    var failure = assertThrows(ExecutionException.class, () -> store.refreshStatus().get(10, TimeUnit.SECONDS));
    var busy = StoreBusyException.in(failure);
    assertNotNull(busy, failure.getCause().toString());
    assertEquals(StoreBusyException.Condition.RECORD_CHANGING, busy.condition());
    assertDoesNotThrow(() -> store.refreshStatus().get(10, TimeUnit.SECONDS), "Once the store is still, the pass completes");
  }

  @Test void aReceiptRewrittenBetweenInspectionAndPublicationIsBusyNotDamaged() throws Exception {
    store.migrateReplicas(store.replicaHeader().id()).get(10, TimeUnit.SECONDS);
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    var captured = ReplicaPublicationFixture.completedCapture(store, WALL, 23);
    var receipt = captured.resolveSibling("session.json");
    assertTrue(Files.exists(captured.resolveSibling(ReplicaMasters.CAPTURE_PENDING)), "A full catalog leaves the publication pending");
    var rewritten = new AtomicBoolean();
    store.publicationRecoveryProbe(marker -> {
      // A writer replaces the receipt after the walk inspected it and before the locked retry rechecks it.
      if (rewritten.compareAndSet(false, true)) Files.writeString(receipt, Files.readString(receipt) + "\n");
    });
    var failure = assertThrows(ExecutionException.class, () -> store.refreshStatus().get(10, TimeUnit.SECONDS));
    var busy = StoreBusyException.in(failure);
    assertNotNull(busy, failure.getCause().toString());
    assertEquals(StoreBusyException.Condition.RECORD_CHANGING, busy.condition());
  }

  @Test void theBoundCountsSessionFoldersAndMarkersFoundElsewhereNotOtherPaths() throws Exception {
    var sessions = sessionFolders();
    var day = sessions.get(0).getParent();
    store.publicationFolderLimit(sessions.size() + 2);
    Files.createDirectories(day.resolve("extra-1"));
    Files.createDirectories(day.resolve("extra-2"));
    var notes = Files.createDirectories(store.root().resolve("notes"));
    for (int i = 0; i < 50; i++) Files.writeString(notes.resolve("note-" + i + ".txt"), "x");
    assertDoesNotThrow(() -> store.refreshStatus().get(10, TimeUnit.SECONDS), "Fifty other files cost the bound nothing");
    // An uncommitted intent is inspected and rolled back without a failure; outside a session folder it still counts.
    var marker = new JsonObject();
    marker.addProperty("path", "notes/session.json");
    marker.addProperty("sha256", "b".repeat(64));
    marker.addProperty("kind", "session");
    Files.writeString(notes.resolve(ReplicaMasters.CAPTURE_PENDING), marker.toString());
    var failure = assertThrows(ExecutionException.class, () -> store.refreshStatus().get(10, TimeUnit.SECONDS));
    assertTrue(failure.getCause().getMessage().startsWith("RESOURCE_LIMIT:"), failure.getCause().toString());
    assertNull(StoreBusyException.in(failure), "A store past its bound is not busy: trying again cannot help");
  }

  @Test void anUnsafeFailureOutranksABusyOneWhicheverComesFirst() {
    var busy = new StoreBusyException(StoreBusyException.Condition.RECORD_CHANGING, "busy");
    var later = new StoreBusyException(StoreBusyException.Condition.LOCK_HELD, "later");
    var unsafe = new IOException("Invalid capture publication intent");
    assertSame(unsafe, LogStore.worse(busy, unsafe));
    assertSame(unsafe, LogStore.worse(unsafe, busy));
    assertSame(busy, LogStore.worse(null, busy));
    assertSame(busy, LogStore.worse(busy, later), "The first of two busy conditions is kept");
    assertSame(unsafe, LogStore.worse(unsafe, new IOException("second")), "The first unsafe failure is kept");
  }

  /** robots/robot/sessions/day/session, as the import placed them. */
  private List<Path> sessionFolders() throws IOException {
    try (var paths = Files.find(store.root().resolve("robots"), 5,
        (path, attributes) -> path.getFileName().toString().equals("session.json")
            && store.root().relativize(path).getNameCount() == 6)) {
      var found = paths.map(Path::getParent).sorted().toList();
      assertFalse(found.isEmpty(), "The seed import placed a session");
      return found;
    }
  }
}
