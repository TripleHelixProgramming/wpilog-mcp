/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest;

/** The optional persisted index cannot weaken source admission or mapping ownership. */
class PersistentScanIntegrationTest {
  @TempDir Path directory;
  private Path source;
  private Path cache;
  private LogManager manager;

  @BeforeEach void setup() throws Exception {
    directory = directory.toRealPath();
    source = directory.resolve("source.wpilog");
    write(source, false);
    String taskCache = System.getenv("WPILOG_DISK_CACHE_DIR");
    assertNotNull(taskCache);
    cache = Path.of(taskCache).resolve("scan-integration-" + UUID.randomUUID());
    manager = new LogManager();
    manager.getCacheDirectory().setOverride(cache.toString());
    manager.getDiskCache().setEnabled(true);
    manager.getSyncDiskCache().setEnabled(false);
    manager.addAllowedDirectory(directory);
  }

  @AfterEach void stop() { if (manager != null) manager.shutdown(); }

  @Test void anInteriorRewriteWithRestoredAttributesCannotReuseOldDeclarations() throws Exception {
    try (var use = manager.acquireReadOnly(source.toString())) {
      assertEquals(List.of(10.0, 20.0), values(use.log(), "/Value"));
    }
    assertEquals(1, indexes());
    assertTrue(manager.unloadLog(source.toString()));
    rewriteDeclaration(source);
    long scans = LogScan.passCount();
    try (var use = manager.acquireReadOnly(source.toString())) {
      assertFalse(use.log().entries().containsKey("/Value"), "The stale index names the old declaration");
      assertEquals(List.of(10.0, 20.0), values(use.log(), "/Other"));
      assertEquals(List.of(99.0), values(use.log(), "/Tail"));
    }
    assertEquals(1, LogScan.passCount() - scans, "Changed content must run a new scan");
  }

  @Test void aWarmRememberedManifestProofCannotAuthorizeChangedBytes() throws Exception {
    var store = manager.stores().store(directory.resolve("store"));
    var result = store.importPaths(new LogStore.Request(List.of(source), false, "practice"), p -> {})
        .get(10, TimeUnit.SECONDS);
    var placed = result.files().get(0);
    assertEquals("imported", placed.status(), placed.toString());
    store.migrateReplicas(store.replicaHeader().id()).get(10, TimeUnit.SECONDS);
    var member = placed.path();
    try (var use = manager.acquireReadOnly(member.toString())) {
      assertNotNull(use.replica(), "The fixture must exercise manifested admission");
      assertEquals(List.of(10.0, 20.0), values(use.log(), "/Value"));
    }
    manager.unloadLog(member.toString());
    rewriteDeclaration(member);
    long scans = LogScan.passCount();
    var failure = assertThrows(IOException.class, () -> manager.acquireReadOnly(member.toString()));
    assertTrue(failure.getMessage().contains("SOURCE_UNAVAILABLE"), failure::getMessage);
    assertTrue(failure.getMessage().contains("manifested hash"), failure::getMessage);
    assertEquals(0, LogScan.passCount() - scans,
        "A mismatched manifested file must not fall back to scanning replacement content");
  }

  @Test void aFailedFreshManifestProofCannotFallBackToAnEagerRead() throws Exception {
    var store = manager.stores().store(directory.resolve("store"));
    var result = store.importPaths(new LogStore.Request(List.of(source), false, "practice"), p -> {})
        .get(10, TimeUnit.SECONDS);
    var placed = result.files().get(0);
    assertEquals("imported", placed.status(), placed.toString());
    store.migrateReplicas(store.replicaHeader().id()).get(10, TimeUnit.SECONDS);
    var member = placed.path();
    try (var use = manager.acquireReadOnly(member.toString())) {
      assertNotNull(use.replica(), "The fixture must exercise manifested admission");
      assertEquals(List.of(10.0, 20.0), values(use.log(), "/Value"));
    }
    assertTrue(manager.unloadLog(member.toString()));
    manager.scanObserver((phase, path) -> {
      if (phase == LazyParsedLog.OpenPhase.BEFORE_HASH) throw new IOException("synthetic proof read failure");
    });
    long scans = LogScan.passCount();
    var failure = assertThrows(IOException.class, () -> manager.acquireReadOnly(member.toString()),
        "A failed current content proof must refuse the read, not invoke the eager parser");
    assertTrue(failure.getMessage().contains("SOURCE_UNAVAILABLE"), failure::getMessage);
    assertTrue(failure.getMessage().contains("proof"), failure::getMessage);
    assertEquals(0, LogScan.passCount() - scans);
    assertTrue(LogFileAccess.awaitFree(member, Duration.ZERO));
  }

  @Test void failedEligibilityCannotBypassFreshProofOfAManifestedMember() throws Exception {
    var store = manager.stores().store(directory.resolve("store"));
    var result = store.importPaths(new LogStore.Request(List.of(source), false, "practice"), p -> {})
        .get(10, TimeUnit.SECONDS);
    var placed = result.files().get(0);
    assertEquals("imported", placed.status(), placed.toString());
    store.migrateReplicas(store.replicaHeader().id()).get(10, TimeUnit.SECONDS);
    var member = placed.path();
    try (var use = manager.acquireReadOnly(member.toString())) {
      assertNotNull(use.replica(), "The fixture must exercise manifested admission");
      assertEquals(List.of(10.0, 20.0), values(use.log(), "/Value"));
    }
    assertTrue(manager.unloadLog(member.toString()));
    rewriteDeclaration(member);
    Path session = null;
    for (var ancestor = member.getParent(); ancestor != null && ancestor.startsWith(directory.resolve("store"));
        ancestor = ancestor.getParent()) {
      var receipt = ancestor.resolve("session.json");
      if (Files.isRegularFile(receipt)) { session = receipt; break; }
    }
    assertNotNull(session, "The selected member must have an ancestor session receipt");
    Files.writeString(session, "{ invalid synthetic session receipt");
    long scans = LogScan.passCount();
    var failure = assertThrows(IOException.class, () -> manager.acquireReadOnly(member.toString()),
        "An eligibility read failure must not eagerly parse changed content under a remembered manifest proof");
    assertTrue(failure.getMessage().contains("SOURCE_UNAVAILABLE"), failure::getMessage);
    assertEquals(0, LogScan.passCount() - scans);
    assertTrue(manager.getAllLoadedLogs().isEmpty());
    assertTrue(LogFileAccess.awaitFree(member, Duration.ZERO));
  }

  @ParameterizedTest
  @EnumSource(value = LazyParsedLog.OpenPhase.class, names = {"AFTER_SCAN", "AFTER_INDEX_ENCODING"})
  void aFileThatGrowsAfterItsScanPublishesNoIndex(LazyParsedLog.OpenPhase changeAt) throws Exception {
    var extended = directory.resolve("extended.wpilog");
    write(extended, true);
    byte[] original = Files.readAllBytes(source);
    byte[] after = Files.readAllBytes(extended);
    assertArrayEquals(original, Arrays.copyOf(after, original.length));
    byte[] addition = Arrays.copyOfRange(after, original.length, after.length);
    manager.scanObserver((phase, path) -> {
      if (phase == changeAt) Files.write(path, addition, StandardOpenOption.APPEND);
    });
    try (var use = manager.acquireReadOnly(source.toString())) {
      assertEquals(2, use.log().sampleCount("/Value"), "The admitted scan retains its original prefix");
    }
    assertEquals(0, indexes(), "A size change during construction must prevent persistence");
  }

  @Test void aCatalogOpenCaptureWithoutALocalWriterIsNeverPersisted() throws Exception {
    String when = "2026-01-01T00:00:00Z";
    var store = Files.createDirectories(directory.resolve("external-store"));
    var robot = Files.createDirectories(store.resolve("robots").resolve("synthetic"));
    var session = Files.createDirectories(robot.resolve("sessions").resolve("2026-01-01")
        .resolve("11111111-1111-4111-8111-111111111111"));
    var capture = session.resolve("capture.wpilog");
    write(capture, false);
    Files.writeString(store.resolve("store.json"), StoreJson.JSON.toJson(new StoreManifest.Header(
        1, when, "22222222-2222-4222-8222-222222222222", List.of())));
    Files.writeString(robot.resolve("robot.json"), StoreJson.JSON.toJson(new StoreManifest.Robot(
        "synthetic", "synthetic", null, null, "logged")));
    var open = new StoreManifest.OpenCapture("capture.wpilog", new StoreManifest.Provenance(
        "captured", null, "capture.wpilog", when, false), Files.size(capture), 1, 3);
    Files.writeString(session.resolve("session.json"), StoreJson.JSON.toJson(new StoreManifest.Session(
        "11111111-1111-4111-8111-111111111111", when, null, "pit_clock", null, null, null,
        null, List.of(), open)));
    assertNull(manager.capturePublication(capture), "The open capture is owned by another process");
    try (var use = manager.acquireReadOnly(capture.toString())) {
      assertEquals(List.of(10.0, 20.0), values(use.log(), "/Value"));
    }
    assertEquals(0, indexes(), "A catalog open_capture is growing even without a local LiveLog");
  }

  @Test void oneFlightOwnsTheScanAndEveryCallerRetainsItsOwnUse() throws Exception {
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var hashes = new AtomicInteger();
    manager.scanObserver((phase, path) -> {
      if (phase == LazyParsedLog.OpenPhase.BEFORE_HASH) {
        hashes.incrementAndGet(); entered.countDown(); await(proceed);
      }
    });
    var threads = Executors.newFixedThreadPool(2, task -> { var thread = new Thread(task); thread.setDaemon(true); return thread; });
    long scans = LogScan.passCount();
    try {
      var first = threads.submit(() -> manager.acquireReadOnly(source.toString()));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var secondStarted = new CountDownLatch(1);
      var waitingThread = new AtomicReference<Thread>();
      var second = threads.submit(() -> {
        waitingThread.set(Thread.currentThread());
        secondStarted.countDown();
        return manager.acquireReadOnly(source.toString());
      });
      assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
      awaitLoadingWaiter(waitingThread.get());
      assertFalse(second.isDone());
      proceed.countDown();
      try (var a = first.get(5, TimeUnit.SECONDS); var b = second.get(5, TimeUnit.SECONDS)) {
        assertNotSame(a, b);
        assertSame(a.log(), b.log());
        manager.unloadLog(source.toString());
        a.close();
        assertEquals(List.of(10.0, 20.0), values(b.log(), "/Value"));
        assertFalse(LogFileAccess.awaitFree(source, Duration.ZERO));
      }
      assertTrue(LogFileAccess.awaitFree(source, Duration.ZERO));
      assertEquals(1, hashes.get());
      assertEquals(1, LogScan.passCount() - scans);
    } finally { proceed.countDown(); threads.shutdownNow(); }
  }

  @Test void aHeldContentProofIsARealReaderAndCannotPublishAcrossAMoveReservation() throws Exception {
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    manager.scanObserver((phase, path) -> {
      if (phase == LazyParsedLog.OpenPhase.BEFORE_HASH) { entered.countDown(); await(proceed); }
    });
    var thread = Executors.newSingleThreadExecutor(task -> { var worker = new Thread(task); worker.setDaemon(true); return worker; });
    try {
      var loading = thread.submit(() -> manager.acquireReadOnly(source.toString()));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      try (var reservation = LogFileAccess.reserveMove(List.of(source))) {
        assertFalse(manager.release(source, Duration.ZERO).released(), "Hashing must already own the reader lease");
        proceed.countDown();
        var failed = assertThrows(ExecutionException.class, () -> loading.get(5, TimeUnit.SECONDS));
        assertTrue(failed.getCause().getMessage().contains("being moved"), failed::toString);
        assertTrue(manager.getAllLoadedLogs().isEmpty());
        assertTrue(LogFileAccess.awaitFree(source, Duration.ZERO));
      }
    } finally { proceed.countDown(); thread.shutdownNow(); }
  }

  @Test void activeCaptureReadsDoNotJoinAnotherFilesHeldIndexProof() throws Exception {
    var livePath = directory.resolve("live.wpilog");
    var live = new LiveLog(livePath, 1_000_000);
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    manager.scanObserver((phase, path) -> {
      if (phase == LazyParsedLog.OpenPhase.BEFORE_HASH) { entered.countDown(); await(proceed); }
    });
    var threads = Executors.newFixedThreadPool(2, task -> { var thread = new Thread(task); thread.setDaemon(true); return thread; });
    try (var output = new WpilogOutput(livePath)) {
      int id = output.start("/Live", "double", "", 0);
      var entry = new EntryInfo(id, "/Live", "double", "");
      live.announce(entry);
      var written = output.append(id, 20_000, WpilogWriter.encodeDouble(7));
      live.append(entry, new ValueFrame(id, 20_000, 1, 7.0), written);
      manager.beginCapture(live);
      var loading = threads.submit(() -> manager.acquireReadOnly(source.toString()));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var capture = threads.submit(() -> manager.acquireReadOnly(livePath.toString()));
      try (var use = capture.get(1, TimeUnit.SECONDS)) {
        assertEquals(List.of(7.0), values(use.log(), "/Live"));
      }
      assertEquals(0, indexes(), "Neither a held ordinary proof nor an active capture publishes an index");
      proceed.countDown();
      try (var use = loading.get(5, TimeUnit.SECONDS)) {
        assertEquals(List.of(10.0, 20.0), values(use.log(), "/Value"));
      }
      assertEquals(1, indexes(), "Only the completed ordinary file is persisted");
    } finally { proceed.countDown(); threads.shutdownNow(); live.finish(); live.close(); }
  }

  @Test void aFailedProofReleasesItsFlightAndReaderForTheNextRequest() throws Exception {
    manager.scanObserver((phase, path) -> {
      if (phase == LazyParsedLog.OpenPhase.BEFORE_HASH) throw new LogFileException("synthetic proof failure");
    });
    assertThrows(LogFileException.class, () -> manager.acquireReadOnly(source.toString()));
    assertTrue(LogFileAccess.awaitFree(source, Duration.ZERO));
    manager.scanObserver((phase, path) -> {});
    try (var use = manager.acquireReadOnly(source.toString())) {
      assertEquals(List.of(10.0, 20.0), values(use.log(), "/Value"));
    }
    assertEquals(1, indexes());
  }

  @Test void anInterruptedWaiterDoesNotCancelOrReleaseAnotherRequestsClaim() throws Exception {
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    manager.scanObserver((phase, path) -> {
      if (phase == LazyParsedLog.OpenPhase.BEFORE_HASH) { entered.countDown(); await(proceed); }
    });
    var threads = Executors.newFixedThreadPool(2, task -> { var thread = new Thread(task); thread.setDaemon(true); return thread; });
    try {
      var leader = threads.submit(() -> manager.acquireReadOnly(source.toString()));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var waiter = threads.submit(() -> {
        Thread.currentThread().interrupt();
        try {
          var failure = assertThrows(IOException.class, () -> manager.acquireReadOnly(source.toString()));
          assertTrue(failure.getMessage().contains("Interrupted while waiting"), failure::getMessage);
          assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
      });
      waiter.get(5, TimeUnit.SECONDS);
      assertFalse(leader.isDone());
      assertFalse(LogFileAccess.awaitFree(source, Duration.ZERO));
      proceed.countDown();
      try (var use = leader.get(5, TimeUnit.SECONDS)) {
        assertEquals(List.of(10.0, 20.0), values(use.log(), "/Value"));
      }
      assertEquals(1, indexes());
    } finally { proceed.countDown(); threads.shutdownNow(); }
  }

  @ParameterizedTest
  @EnumSource(value = LazyParsedLog.OpenPhase.class, names = {"BEFORE_HASH", "AFTER_INDEX_ENCODING"})
  void shutdownDoesNotWaitForAHeldProofOrLetItPublishAfterwards(LazyParsedLog.OpenPhase holdAt) throws Exception {
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    manager.scanObserver((phase, path) -> {
      if (phase == holdAt) { entered.countDown(); await(proceed); }
    });
    var threads = Executors.newFixedThreadPool(2, task -> { var thread = new Thread(task); thread.setDaemon(true); return thread; });
    try {
      var loading = threads.submit(() -> manager.acquireReadOnly(source.toString()));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      threads.submit(manager::shutdown).get(5, TimeUnit.SECONDS);
      assertFalse(loading.isDone(), "Shutdown must not release or interrupt another request's proof");
      assertFalse(LogFileAccess.awaitFree(source, Duration.ZERO));
      proceed.countDown();
      var failure = assertThrows(ExecutionException.class, () -> loading.get(5, TimeUnit.SECONDS));
      assertTrue(failure.getCause().getMessage().contains("closed"), failure::toString);
      assertTrue(manager.getAllLoadedLogs().isEmpty());
      assertEquals(0, indexes(), "A proof completed after shutdown cannot publish an index");
      assertTrue(LogFileAccess.awaitFree(source, Duration.ZERO));
    } finally { proceed.countDown(); threads.shutdownNow(); }
  }

  @ParameterizedTest
  @EnumSource(value = LazyParsedLog.OpenPhase.class, names = {"BEFORE_HASH", "AFTER_INDEX_ENCODING"})
  void aCapturePublishedDuringAProofWinsWithoutWaitingForTheOrdinaryLoad(LazyParsedLog.OpenPhase holdAt) throws Exception {
    var path = directory.resolve("late-capture.wpilog");
    var live = new LiveLog(path, 1_000_000);
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    manager.scanObserver((phase, requested) -> {
      if (phase == holdAt) { entered.countDown(); await(proceed); }
    });
    var threads = Executors.newFixedThreadPool(2, task -> { var thread = new Thread(task); thread.setDaemon(true); return thread; });
    try (var output = new WpilogOutput(path)) {
      int id = output.start("/Live", "double", "", 0);
      var entry = new EntryInfo(id, "/Live", "double", "");
      live.announce(entry);
      var written = output.append(id, 20_000, WpilogWriter.encodeDouble(7));
      live.append(entry, new ValueFrame(id, 20_000, 1, 7.0), written);
      var loading = threads.submit(() -> manager.acquireReadOnly(path.toString()));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      manager.beginCapture(live);
      var capture = threads.submit(() -> manager.acquireReadOnly(path.toString()));
      try (var use = capture.get(1, TimeUnit.SECONDS)) {
        assertFalse(use.log() instanceof LazyParsedLog);
        assertEquals(List.of(7.0), values(use.log(), "/Live"));
      }
      proceed.countDown();
      try (var use = loading.get(5, TimeUnit.SECONDS)) {
        assertFalse(use.log() instanceof LazyParsedLog, "The completed ordinary candidate must be retired");
        assertEquals(List.of(7.0), values(use.log(), "/Live"));
      }
      assertEquals(0, indexes());
    } finally { proceed.countDown(); threads.shutdownNow(); live.finish(); live.close(); }
  }

  @Test void aNewSynchronousReadAfterShutdownMayRestoreItsOwnIndex() throws Exception {
    try (var use = manager.acquireReadOnly(source.toString())) {
      assertEquals(List.of(10.0, 20.0), values(use.log(), "/Value"));
    }
    assertEquals(1, indexes());
    manager.shutdown();
    long scans = LogScan.passCount();
    try (var use = manager.acquireReadOnly(source.toString())) {
      assertEquals(List.of(10.0, 20.0), values(use.log(), "/Value"));
    }
    assertEquals(0, LogScan.passCount() - scans,
        "Shutdown fences earlier work, not a new explicit read using a valid persisted index");
  }

  private long indexes() throws IOException {
    if (!Files.isDirectory(cache)) return 0;
    try (var files = Files.list(cache)) {
      return files.filter(path -> path.getFileName().toString().endsWith("-scan.idx")).count();
    }
  }

  private static List<Object> values(LogData log, String name) {
    return log.values().get(name).stream().map(TimestampedValue::value).toList();
  }

  private static void await(CountDownLatch latch) throws IOException {
    try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("Synthetic proof latch timed out"); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
  }

  private static void awaitLoadingWaiter(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    do {
      var frames = Arrays.asList(thread.getStackTrace());
      boolean future = frames.stream().anyMatch(frame -> frame.getClassName().equals("java.util.concurrent.CompletableFuture")
          && frame.getMethodName().equals("get"));
      boolean owner = frames.stream().anyMatch(frame -> frame.getClassName().equals(LogManager.class.getName())
          && frame.getMethodName().equals("acquireAttempt"));
      if (future && owner) return;
      java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    } while (System.nanoTime() < deadline);
    fail("The second request must await the existing owner, not hash or scan independently");
  }

  private static void write(Path path, boolean extra) throws IOException {
    try (var writer = new WpilogWriter(path, "persistent scan integration")) {
      int value = writer.start("/Value", "double", "", 0);
      int tail = writer.start("/Tail", "double", "", 0);
      writer.append(value, 1_000_000, WpilogWriter.encodeDouble(10));
      writer.append(value, 2_000_000, WpilogWriter.encodeDouble(20));
      writer.append(tail, 3_000_000, WpilogWriter.encodeDouble(99));
      if (extra) writer.append(value, 4_000_000, WpilogWriter.encodeDouble(30));
    }
  }

  private static void rewriteDeclaration(Path file) throws IOException {
    var before = FileSnapshot.of(file);
    byte[] bytes = Files.readAllBytes(file);
    byte[] from = "/Value".getBytes(StandardCharsets.UTF_8);
    byte[] to = "/Other".getBytes(StandardCharsets.UTF_8);
    int found = -1;
    for (int i = 0; i <= bytes.length - from.length; i++) {
      if (Arrays.equals(from, Arrays.copyOfRange(bytes, i, i + from.length))) {
        assertEquals(-1, found, "The declaration witness must occur once"); found = i;
      }
    }
    assertTrue(found > 12, "Change an interior declaration, outside header and final-record anchors");
    System.arraycopy(to, 0, bytes, found, to.length);
    Files.write(file, bytes);
    Files.setLastModifiedTime(file, before.modified());
    assertTrue(before.sameAs(FileSnapshot.of(file)), "The new proof must detect a change hidden from attributes");
  }
}
