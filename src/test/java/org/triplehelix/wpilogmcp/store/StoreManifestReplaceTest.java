/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** A denied rename must preserve the previous complete manifest; injected pacing never sleeps. */
class StoreManifestReplaceTest {
  @TempDir Path directory;
  @Test void oversizedReplacementIsRefusedBeforePublishingAnUnreadableManifest() throws Exception {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    var io = new StoreFiles(directory, security);
    var path = directory.resolve("store.json");
    io.write(path, Map.of("value", "original"));
    var before = Files.readString(path);
    var failure = assertThrows(IOException.class,
        () -> io.write(path, Map.of("value", "x".repeat(4 * 1024 * 1024))));
    assertTrue(failure.getMessage().contains("too large"));
    assertEquals(before, Files.readString(path));
    assertEquals("original", io.read(path, com.google.gson.JsonObject.class).get("value").getAsString());
    noTemporaryFiles();
  }
  final List<Long> delays = new ArrayList<>();
  final AtomicInteger attempts = new AtomicInteger(), published = new AtomicInteger();
  Path target() throws IOException {
    var path = directory.resolve("store.json"); Files.writeString(path, "old complete manifest"); return path;
  }
  StoreFiles io(StoreFiles.Mover move, StoreFiles.Pause pause, boolean windows) throws IOException {
    var security = new SecurityValidator(); security.addAllowedDirectory(directory);
    return new StoreFiles(directory, security, (p, value) -> published.incrementAndGet(), move, pause, windows);
  }
  void noTemporaryFiles() throws IOException {
    try (var files = Files.list(directory)) { assertEquals(0, files.filter(p -> p.getFileName().toString().startsWith(".manifest-")).count()); }
  }
  @Test void transientWindowsDenialsRetryTheSameCompleteBytesAndPublishOnce() throws Exception {
    var path = target(); var temporaries = new java.util.HashSet<Path>();
    var io = io((from, to) -> {
      temporaries.add(from); assertEquals("old complete manifest", Files.readString(to));
      assertEquals("new", StoreJson.JSON.fromJson(Files.readString(from), com.google.gson.JsonObject.class).get("value").getAsString());
      if (attempts.incrementAndGet() < 3) throw new AccessDeniedException(from.toString(), to.toString(), null);
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }, delays::add, true);
    io.write(path, Map.of("value", "new"));
    assertEquals(3, attempts.get()); assertEquals(List.of(20L, 40L), delays);
    assertEquals(1, temporaries.size()); assertEquals(1, published.get()); noTemporaryFiles();
    assertEquals("new", StoreJson.JSON.fromJson(Files.readString(path), com.google.gson.JsonObject.class).get("value").getAsString());
  }
  @Test void persistentDenialHasSixAttemptsAndLeavesThePreviousManifest() throws Exception {
    var path = target(); var denied = new AccessDeniedException(path.toString());
    var io = io((from, to) -> { attempts.incrementAndGet(); throw denied; }, delays::add, true);
    assertSame(denied, assertThrows(AccessDeniedException.class, () -> io.write(path, Map.of("value", "new"))));
    assertEquals(6, attempts.get()); assertEquals(List.of(20L, 40L, 80L, 160L, 320L), delays);
    assertEquals("old complete manifest", Files.readString(path)); assertEquals(0, published.get()); noTemporaryFiles();
  }
  @Test void otherFilesystemsAndUnsupportedAtomicMovesAreNotRetried() throws Exception {
    var path = target();
    for (boolean windows : List.of(false, true)) {
      attempts.set(0); delays.clear();
      IOException failure = windows ? new AtomicMoveNotSupportedException("from", "to", "synthetic unsupported atomic move") : new AccessDeniedException(path.toString());
      var io = io((from, to) -> { attempts.incrementAndGet(); throw failure; }, delays::add, windows);
      assertSame(failure, assertThrows(IOException.class, () -> io.write(path, Map.of("value", "new"))));
      assertEquals(1, attempts.get()); assertTrue(delays.isEmpty()); assertEquals(0, published.get());
      assertEquals("old complete manifest", Files.readString(path)); noTemporaryFiles();
    }
  }
  @Test void interruptedBackoffPreservesTheManifestAndTheInterrupt() throws Exception {
    var path = target();
    var io = io((from, to) -> { attempts.incrementAndGet(); throw new AccessDeniedException(path.toString()); },
        ignored -> { throw new InterruptedException("synthetic stop"); }, true);
    try {
      assertThrows(java.io.InterruptedIOException.class, () -> io.write(path, Map.of("value", "new")));
      assertTrue(Thread.currentThread().isInterrupted()); assertEquals(1, attempts.get());
      assertEquals("old complete manifest", Files.readString(path)); assertEquals(0, published.get()); noTemporaryFiles();
    } finally { Thread.interrupted(); }
  }
}
