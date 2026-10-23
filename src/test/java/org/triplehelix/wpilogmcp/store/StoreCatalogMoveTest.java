/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;
import org.triplehelix.wpilogmcp.store.StoreManifest.LogFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.Provenance;
import org.triplehelix.wpilogmcp.store.StoreManifest.Robot;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

/**
 * A catalog read takes no lock, so the store can rename a session's folder while the read walks
 * the store, as it does once the session's match is known. The read must neither fail on the
 * folder it listed, which made list_available_logs refuse a whole store in the shop harness, nor
 * report a session at the path it had before. Each test renames a folder at a chosen point inside
 * the read, through the read's own hooks.
 */
class StoreCatalogMoveTest {
  @TempDir Path directory;
  private static final String WHEN = "2026-01-01T00:00:00Z";
  private Path root, day;
  private SecurityValidator security;

  @BeforeEach void store() throws IOException {
    root = directory.toRealPath().resolve("store");
    day = root.resolve("robots/RIO/sessions/2026-01-01");
    Files.createDirectories(day);
    Files.writeString(root.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, WHEN, "store", List.of())));
    Files.writeString(root.resolve("robots/RIO/robot.json"), StoreJson.JSON.toJson(new Robot("RIO", "RIO-1", "", "", "device")));
    for (var id : List.of("a", "b")) session(day.resolve(id), id);
    security = new SecurityValidator();
    security.addAllowedDirectory(directory.toRealPath());
  }

  private static void session(Path folder, String id) throws IOException {
    Files.createDirectories(folder);
    var log = Files.write(folder.resolve(id + ".wpilog"), new byte[] {1, 2, 3});
    var file = new LogFile(log.getFileName().toString(), "0".repeat(64), Files.size(log), "wpilog",
        new Provenance("pulled", null, log.getFileName().toString(), WHEN, false), true, 1, 2, WHEN, WHEN,
        "filename", false, null);
    Files.writeString(folder.resolve("session.json"), StoreJson.JSON.toJson(
        new Session(id, WHEN, WHEN, "filename", null, null, null, null, List.of(file))));
  }

  private static List<Path> sessions(StoreCatalog.Snapshot snapshot) {
    return snapshot.sessions().stream().map(StoreCatalog.SessionDirectory::path).toList();
  }

  /** The session reader the read calls for each manifest, renaming a folder once when it reaches {@code at}. */
  private StoreCatalog.SessionReader renaming(String at, String folder, String to, AtomicInteger reads) {
    var renamed = new AtomicBoolean();
    return (io, manifest) -> {
      if (manifest.getParent().getFileName().toString().equals(at)) {
        reads.incrementAndGet();
        if (renamed.compareAndSet(false, true)) Files.move(day.resolve(folder), day.resolve(to));
      }
      return io.read(manifest, Session.class);
    };
  }

  @Test void aSessionRenamedWhileTheReadIsOnAnotherIsFoundUnderItsNewName() throws Exception {
    var reads = new AtomicInteger();
    // b was listed with a; the store renames it while the read is on a.
    var snapshot = StoreCatalog.readInventory(root, security, null, renaming("a", "b", "b_EVENT_Q2", reads));
    assertEquals(List.of(day.resolve("a"), day.resolve("b_EVENT_Q2")), sessions(snapshot));
    assertEquals(2, reads.get(), "The read started over once");
    assertTrue(snapshot.files().stream().anyMatch(f -> f.path().equals(day.resolve("b_EVENT_Q2/b.wpilog"))));
  }

  @Test void aSessionRenamedAfterItWasReadIsNotReportedAtItsOldPath() throws Exception {
    var reads = new AtomicInteger();
    // a was read whole; the store renames it while the read is on b, so a's paths would be stale.
    var snapshot = StoreCatalog.readInventory(root, security, null, renaming("b", "a", "a_EVENT_Q1", reads));
    assertEquals(List.of(day.resolve("a_EVENT_Q1"), day.resolve("b")), sessions(snapshot));
    assertTrue(snapshot.files().stream().noneMatch(f -> f.path().startsWith(day.resolve("a"))),
        "No file at the path the session had before");
  }

  @Test void theTolerantReadListsNoProblemForARename() throws Exception {
    var problems = new ArrayList<String>();
    var renamed = new AtomicBoolean();
    StoreCatalog.PathObserver observer = path -> {
      if (path.equals(day.resolve("a").resolve("session.json")) && renamed.compareAndSet(false, true))
        Files.move(day.resolve("b"), day.resolve("b_EVENT_Q2"));
    };
    var snapshot = StoreCatalog.readTolerantInventory(root, security, (path, why) -> problems.add(path + ": " + why), observer);
    assertEquals(List.of(), problems);
    assertEquals(List.of(day.resolve("a"), day.resolve("b_EVENT_Q2")), sessions(snapshot));
  }

  @Test void theTolerantReadListsNoProblemForASessionRenamedAsItsManifestIsRead() throws Exception {
    var problems = new ArrayList<String>();
    var renamed = new AtomicBoolean();
    // The store renames b just as the read opens b's own manifest.
    StoreCatalog.PathObserver observer = path -> {
      if (path.equals(day.resolve("b").resolve("session.json")) && renamed.compareAndSet(false, true))
        Files.move(day.resolve("b"), day.resolve("b_EVENT_Q2"));
    };
    var snapshot = StoreCatalog.readTolerantInventory(root, security, (path, why) -> problems.add(path + ": " + why), observer);
    assertEquals(List.of(), problems);
    assertEquals(List.of(day.resolve("a"), day.resolve("b_EVENT_Q2")), sessions(snapshot));
  }

  /** A reader for a store renaming b each time the read reaches a: it never stops moving. */
  private StoreCatalog.SessionReader alwaysRenaming(AtomicInteger attempts) {
    return (io, manifest) -> {
      if (manifest.getParent().getFileName().toString().equals("a")) {
        attempts.incrementAndGet();
        var current = Files.list(day).filter(p -> p.getFileName().toString().startsWith("b")).findFirst().orElseThrow();
        Files.move(current, day.resolve("b" + attempts.get()));
      }
      return io.read(manifest, Session.class);
    };
  }

  @Test void aFolderStillMovingOnTheThirdReadIsNamedNotPassedBy() throws Exception {
    var attempts = new AtomicInteger();
    var failure = assertThrows(IOException.class, () -> StoreCatalog.readInventory(root, security, null, alwaysRenaming(attempts)));
    assertEquals(3, attempts.get());
    assertTrue(failure.getMessage().startsWith("Moved while the catalog was read: " + day.resolve("b2")), failure.getMessage());
    // A tolerant read keeps every other session and lists the moving one with its problems.
    var problems = new ArrayList<String>();
    attempts.set(0);
    var snapshot = StoreCatalog.readInventory(root, security, (path, why) -> problems.add(path + ": " + why), alwaysRenaming(attempts));
    assertEquals(List.of(day.resolve("a")), sessions(snapshot));
    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("Moved while the catalog was read"), problems.toString());
  }
}
