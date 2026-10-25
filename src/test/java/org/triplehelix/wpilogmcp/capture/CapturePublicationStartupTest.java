/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.ReplicaPublicationFixture;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest;

/** Actual service startup must distinguish deferred catalog publication from unsafe recovery. */
class CapturePublicationStartupTest {
  @TempDir Path directory;
  private static final Clock WALL = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
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
    store.migrateReplicas(store.replicaHeader().id()).get(10, TimeUnit.SECONDS);
  }

  @AfterEach void cleanup() throws Exception {
    if (store != null) store.close();
    manager.unloadAllLogs();
    manager.clearAllowedDirectories();
    allowed.forEach(manager::addAllowedDirectory);
  }

  @Test void fullCatalogWithIntactCompletedReceiptDoesNotFailActualServiceStartup() throws Exception {
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    var captured = ReplicaPublicationFixture.completedCapture(store, WALL, 23);
    var receipt = StoreJson.JSON.fromJson(Files.readString(captured.resolveSibling("session.json")), StoreManifest.Session.class);
    assertNull(receipt.openCapture());
    assertEquals(1, receipt.files().size());
    assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(captured))),
        receipt.files().get(0).sha256(), "The pending receipt proves the local bytes already committed");
    assertTrue(Files.exists(captured.resolveSibling(".replica-publication.json")));
    try (var use = manager.acquireReadOnly(captured.toString())) {
      assertEquals(List.of(23L), use.log().values().get("NT:/Value").stream().map(value -> value.value()).toList());
    }
    withService(service -> {
      assertDoesNotThrow(() -> service.start().get(5, TimeUnit.SECONDS),
          "Verified local capture receipts must permit startup while a full replica catalog remains pending");
      assertNotEquals("startup_failed", service.live().captureProgress().reason());
      assertEquals(4096, store.replicaHeader().replicas().files().size());
      assertTrue(Files.exists(captured.resolveSibling(".replica-publication.json")),
          "Startup must not conceal the deferred publication by deleting its intent");
    });
  }

  @ParameterizedTest @ValueSource(strings = {"invalid-kind", "unsafe-path"})
  void unsafePublicationIntentStillFailsActualServiceStartup(String failure) throws Exception {
    var folder = Files.createDirectories(store.root().resolve("synthetic-pending"));
    var marker = new JsonObject();
    marker.addProperty("path", failure.equals("unsafe-path") ? "../outside/session.json" : "synthetic-pending/session.json");
    marker.addProperty("sha256", "a".repeat(64));
    marker.addProperty("kind", failure.equals("invalid-kind") ? "other" : "session");
    Files.writeString(folder.resolve(".replica-publication.json"), marker.toString());
    withService(service -> {
      assertThrows(ExecutionException.class, () -> service.start().get(5, TimeUnit.SECONDS),
          "Unsafe publication recovery must not be swallowed as deferred publication");
      assertEquals("startup_failed", service.live().captureProgress().reason());
      assertTrue(Files.exists(folder.resolve(".replica-publication.json")));
    });
  }

  @Test void hashProvenReceiptWithAnEscapingPayloadPathStillFailsActualServiceStartup() throws Exception {
    ReplicaPublicationFixture.fillCatalog(store, manager, 4096);
    var captured = ReplicaPublicationFixture.completedCapture(store, WALL, 23);
    var receiptPath = captured.resolveSibling("session.json");
    var receipt = JsonParser.parseString(Files.readString(receiptPath)).getAsJsonObject();
    receipt.getAsJsonArray("files").get(0).getAsJsonObject()
        .addProperty("path", "../../../../../outside.wpilog");
    Files.writeString(receiptPath, receipt.toString());
    var markerPath = captured.resolveSibling(".replica-publication.json");
    var marker = JsonParser.parseString(Files.readString(markerPath)).getAsJsonObject();
    marker.addProperty("sha256", HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(receiptPath))));
    Files.writeString(markerPath, marker.toString());
    withService(service -> {
      assertThrows(ExecutionException.class, () -> service.start().get(5, TimeUnit.SECONDS),
          "Matching receipt bytes do not prove its referenced payload paths are safe");
      assertEquals("startup_failed", service.live().captureProgress().reason());
      assertTrue(Files.exists(markerPath), "Unsafe recovery must preserve its evidence");
    });
  }

  @Test void uncommittedPublicationIntentRetainsItsExistingRollbackBehavior() throws Exception {
    var folder = Files.createDirectories(store.root().resolve("uncommitted"));
    var marker = new JsonObject();
    marker.addProperty("path", "uncommitted/session.json");
    marker.addProperty("sha256", "b".repeat(64));
    marker.addProperty("kind", "session");
    var path = folder.resolve(".replica-publication.json");
    Files.writeString(path, marker.toString());
    withService(service -> {
      assertDoesNotThrow(() -> service.start().get(5, TimeUnit.SECONDS));
      assertFalse(Files.exists(path), "An intent without its committed receipt remains a rolled-back attempt");
      assertNotEquals("startup_failed", service.live().captureProgress().reason());
    });
  }

  @Test void aStoreLockHeldWhileRecoveryRunsDelaysCaptureUntilItIsReleased() throws Exception {
    // Held by this process, the lock refuses the store's own turns as another process's lock does.
    try (var channel = FileChannel.open(store.root().resolve("store.lock"), StandardOpenOption.WRITE)) {
      var passes = new AtomicInteger();
      var held = new AtomicReference<FileLock>();
      ReplicaPublicationFixture.onPublicationStart(store, () -> { if (passes.incrementAndGet() == 1) held.set(channel.lock()); });
      withService(service -> {
        var started = service.start();
        awaitReason(service, "waiting_for_store");
        assertEquals("lock_held", service.live().captureProgress().cause());
        assertFalse(started.isDone(), "A busy store delays capture instead of stopping it");
        held.get().release();
        assertDoesNotThrow(() -> started.get(10, TimeUnit.SECONDS));
        assertEquals("not_started", service.live().captureProgress().reason(), "Capture starts as if it had never waited");
        assertNull(service.live().captureProgress().cause());
        assertTrue(passes.get() >= 2, "Recovery ran again once the lock was free");
      });
    }
  }

  @Test void stoppingWhileCaptureWaitsForItsStoreEndsTheWaitAtOnce() throws Exception {
    try (var channel = FileChannel.open(store.root().resolve("store.lock"), StandardOpenOption.WRITE)) {
      var held = new AtomicReference<FileLock>();
      ReplicaPublicationFixture.onPublicationStart(store, () -> { if (held.get() == null) held.set(channel.lock()); });
      var started = new AtomicReference<CompletableFuture<Void>>();
      try {
        withService(service -> {
          service.firstStoreRetry(Duration.ofSeconds(60));
          started.set(service.start());
          awaitReason(service, "waiting_for_store");
        });
        // The service is closed: its startup is over now, not when the next try was due.
        assertDoesNotThrow(() -> started.get().get(2, TimeUnit.SECONDS));
      } finally {
        if (held.get() != null) held.get().release();
      }
    }
  }

  @Test void aBusyStoreIsTriedAgainAfterOneSecondDoublingToThirtySeconds() {
    var delays = new java.util.ArrayList<Long>();
    for (var delay = CaptureService.FIRST_STORE_RETRY; delays.size() < 7; delay = CaptureService.nextStoreRetry(delay))
      delays.add(delay.toSeconds());
    assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L), delays);
  }

  /** Capture's published status reaches {@code reason}; the deadline only guards against a hang. */
  private static void awaitReason(CaptureService service, String reason) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!reason.equals(service.live().captureProgress().reason()) && System.nanoTime() < deadline) Thread.sleep(5);
    assertEquals(reason, service.live().captureProgress().reason());
  }

  @FunctionalInterface private interface Check { void run(CaptureService service) throws Exception; }

  /** No listener task runs until close has made the queued NT4 connect a no-op. */
  private void withService(Check check) throws Exception {
    var loop = new HeldLoop();
    try (var socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress("127.0.0.1", 0));
      var config = new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", socket.getLocalPort(), "synthetic-startup")),
          store.root(), .02, CapturePolicy.ALL, 0);
      var service = new CaptureService(config, manager, WALL, loop, WpilogOutput::new, Duration.ofSeconds(2));
      try { check.run(service); }
      finally {
        var closer = new Thread(service::close, "synthetic-capture-close");
        closer.setDaemon(true);
        loop.closer = closer;
        closer.start();
        assertTrue(loop.closeTasks.await(5, TimeUnit.SECONDS), "Capture close must enqueue writer and client cleanup");
        loop.drain();
        closer.join(5000);
        assertFalse(closer.isAlive(), "Capture shutdown must finish after ordered cleanup");
        loop.drain();
        store.awaitImports();
      }
    }
  }

  private static final class HeldLoop implements ClientScheduler {
    private final LinkedBlockingQueue<Runnable> ready = new LinkedBlockingQueue<>();
    private final CountDownLatch closeTasks = new CountDownLatch(2);
    private volatile Thread closer;
    @Override public long nowUs() { return 0; }
    @Override public void execute(Runnable action) {
      ready.add(action);
      if (Thread.currentThread() == closer) closeTasks.countDown();
    }
    @Override public void schedule(Runnable action, long delayUs) { }
    @Override public void close() { }
    void drain() { Runnable action; while ((action = ready.poll()) != null) action.run(); }
  }
}
