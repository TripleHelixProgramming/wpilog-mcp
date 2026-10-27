/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogFileAccess;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.CoreTools;

class StoreInboxTest {
  @TempDir Path temp;
  Path root;
  LogStore store;
  Set<Path> previousAllowed;
  List<Path> previousDirs;
  final LogManager manager = LogManager.getInstance();
  static final long LOOK = StoreInbox.SETTLE_TIME.toNanos();

  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath();
    root = temp.resolve("nested").resolve("store");
    previousAllowed = manager.getAllowedDirectories();
    previousDirs = LogDirectory.getInstance().getLogDirectories();
    manager.clearAllowedDirectories();
    manager.addAllowedDirectory(temp);
    LogDirectory.getInstance().setLogDirectory(temp.toString());
    store = manager.stores().store(root);
    store.importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
  }

  @AfterEach void cleanup() {
    manager.stores().stopWatching();
    manager.stores().awaitImports();
    manager.clearAllowedDirectories();
    previousAllowed.forEach(manager::addAllowedDirectory);
    LogDirectory.getInstance().setLogDirectories(previousDirs.stream().map(Path::toString).toList());
  }

  private JsonObject listing() throws Exception {
    var tools = new ToolRegistry();
    CoreTools.registerAll(tools);
    var request = new JsonObject();
    request.addProperty("scope_id", LogDirectory.getInstance().sourceScopes().snapshot().stream()
        .filter(scope -> scope.root().equals(root)).findFirst().orElseThrow().id());
    return tools.getTool("list_available_logs").execute(request).getAsJsonObject();
  }

  private JsonObject inboxEntry() throws Exception {
    var result = listing();
    assertEquals("ok", result.get("status").getAsString(), result.toString());
    assertTrue(result.getAsJsonArray("unmanaged").isEmpty(), result.toString());
    assertEquals(1, result.getAsJsonArray("inbox").size(), result.toString());
    return result.getAsJsonArray("inbox").get(0).getAsJsonObject();
  }

  private List<String> receipts() throws Exception {
    return Files.readAllLines(root.resolve("inbox").resolve("imported.log"));
  }

  @Test void abandonedTransferCleanupWaitsInTheStoreQueueWithoutBlockingPolling() throws Exception {
    var abandoned = Files.createDirectories(root.resolve("inbox/.transfer-abandoned"));
    Files.writeString(abandoned.resolve("partial"), "synthetic incomplete bytes");
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var busy = store.captureAsync(io -> {
      entered.countDown();
      try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("test barrier"); }
      catch (InterruptedException e) { throw new java.io.IOException(e); }
      return null;
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var polled = java.util.concurrent.CompletableFuture.runAsync(() -> {
        try { store.inbox().poll(0); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
      });
      polled.get(5, TimeUnit.SECONDS); // A busy store must not stall the registry's other inboxes.
      assertTrue(Files.exists(abandoned));
    } finally { release.countDown(); busy.get(10, TimeUnit.SECONDS); }
    store.awaitImports();
    assertFalse(Files.exists(abandoned), "Cleanup must be queued behind the active store operation, not skipped by a competing lock");
    assertTrue(receipts().get(0).contains("Removed incomplete inbox transfer"));
  }

  @Test void httpOwnedUploadsSurviveInboxSweepsAndAnInterruptedUploadLeavesNothing() throws Exception {
    var source = ImportFixture.write(temp.resolve("upload.wpilog"), 3);
    byte[] bytes = Files.readAllBytes(source);
    String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var input = new java.io.ByteArrayInputStream(bytes) {
      boolean first = true;
      @Override public synchronized int read(byte[] b, int offset, int count) {
        if (first) { first = false; entered.countDown(); try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
          catch (InterruptedException e) { throw new AssertionError(e); } }
        return super.read(b, offset, count);
      }
    };
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    var receiving = executor.submit(() -> store.receiveUpload("uploaded.wpilog", bytes.length, sha, input));
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      store.inbox().poll(0); store.inbox().poll(LOOK); store.awaitImports();
      assertTrue(StoreCatalog.readManaged(root, manager.testGetSecurityValidator()).files().isEmpty());
      release.countDown();
      var upload = receiving.get(10, TimeUnit.SECONDS);
      assertArrayEquals(bytes, Files.readAllBytes(upload.path()));
      store.importUpload(upload, null, p -> {}).get();
      assertEquals(1, StoreCatalog.readManaged(root, manager.testGetSecurityValidator()).files().size());
      assertThrows(java.io.IOException.class, () -> store.receiveUpload("broken.wpilog", bytes.length + 1, sha, new java.io.ByteArrayInputStream(bytes)));
      assertThrows(java.io.IOException.class, () -> store.receiveUpload("long.wpilog", bytes.length - 1, sha, new java.io.ByteArrayInputStream(bytes)));
      assertThrows(IllegalArgumentException.class, () -> store.receiveUpload("huge.wpilog", 1L + org.triplehelix.wpilogmcp.log.MappedLogBytes.MAX_FILE_BYTES, sha, input));
      try (var files = Files.walk(root.resolve("inbox"))) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
    } finally { release.countDown(); executor.shutdownNow(); }
  }

  @Test void anUploadAboveTwoGiBReachesStreamingLengthValidation() throws Exception {
    String hash = "0".repeat(64);
    StoreFolders.release(root); // a store with an 8 GiB budget of its own owns the folder for this block
    try (var space = new DiskSpaceBudgets(filesystem -> 8L << 30);
        var selected = new LogStore(root, manager.testGetSecurityValidator(), manager,
            StoreCatalog::read, new LogStore.Mover(), space.store(root))) {
      var error = assertThrows(java.io.IOException.class, () -> selected.receiveUpload("long.wpilog", 1L << 31,
          hash, new java.io.ByteArrayInputStream(new byte[0])));
      assertEquals("Upload ended before its declared Content-Length", error.getMessage());
    }
    try (var files = Files.walk(root.resolve("inbox"))) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
  }

  @Test void captureEnabledWithAnAbsentRobotStillImportsItsInbox() throws Exception {
    var config = new org.triplehelix.wpilogmcp.config.CaptureConfig(
        List.of(java.net.URI.create("ws://127.0.0.1:1/nt/synthetic")), root, .01,
        org.triplehelix.wpilogmcp.capture.CapturePolicy.ALL, 0);
    var server = new org.triplehelix.wpilogmcp.mcp.HttpTransport(new ToolRegistry(), 0, "127.0.0.1", null, null);
    server.setStoreDirectories(Set.of(root)); server.start();
    // This test advances the poll clock itself. A real watcher observing first would mix
    // System.nanoTime with 0/LOOK and keep the injected clock before its first observation.
    manager.stores().stopWatching();
    try (var capture = new org.triplehelix.wpilogmcp.capture.CaptureService(config, manager)) {
      capture.start().get(10, TimeUnit.SECONDS);
      var source = ImportFixture.write(root.resolve("inbox/dropped.wpilog"), 4, "SYNTHETIC-INBOX", 1_767_225_600_000_000L);
      byte[] bytes = Files.readAllBytes(source);
      store.inbox().poll(0); store.inbox().poll(LOOK); store.awaitImports();
      var catalog = StoreCatalog.readManaged(root, manager.testGetSecurityValidator());
      assertEquals(1, catalog.files().size()); assertEquals("SYNTHETIC-INBOX", catalog.files().get(0).robot().serialNumber());
      assertArrayEquals(bytes, Files.readAllBytes(catalog.files().get(0).path())); assertFalse(Files.exists(source));
      assertEquals("imported", JsonParser.parseString(receipts().get(0)).getAsJsonObject().get("status").getAsString());
    } finally { server.stop(); }
  }

  @Test void twoStableLooksRequireBothSizeAndMtimeAndMoveVerifiedBytesOnce() throws Exception {
    var file = ImportFixture.write(root.resolve("inbox").resolve("growing.data"), 1);
    store.inbox().poll(0);
    assertEquals("waiting", inboxEntry().get("state").getAsString());
    assertEquals(file.toString(), inboxEntry().get("path").getAsString());
    assertEquals(Files.size(file), inboxEntry().get("size").getAsLong());
    var modified = Files.getLastModifiedTime(file);
    Files.write(file, new byte[] {0}, StandardOpenOption.APPEND);
    Files.setLastModifiedTime(file, modified); // Size alone changes.
    store.inbox().poll(LOOK);
    store.awaitImports();
    assertTrue(Files.exists(file));
    assertEquals("waiting", inboxEntry().get("state").getAsString());
    Files.setLastModifiedTime(file, FileTime.fromMillis(modified.toMillis() + 1000)); // Mtime alone changes.
    store.inbox().poll(2 * LOOK);
    store.awaitImports();
    assertTrue(Files.exists(file));
    store.inbox().poll(3 * LOOK - 1);
    store.awaitImports();
    assertTrue(Files.exists(file), "Two immediate looks are not a stability interval");
    byte[] bytes = Files.readAllBytes(file);
    store.inbox().poll(3 * LOOK);
    store.awaitImports();
    assertFalse(Files.exists(file));
    assertTrue(listing().getAsJsonArray("inbox").isEmpty());
    var receipt = JsonParser.parseString(receipts().get(0)).getAsJsonObject();
    assertEquals("unassigned", receipt.get("status").getAsString());
    assertEquals(file.toString(), receipt.get("original_path").getAsString());
    var destination = Path.of(receipt.get("path").getAsString());
    assertTrue(destination.startsWith(root.resolve("unassigned")));
    assertArrayEquals(bytes, Files.readAllBytes(destination));
    store.inbox().poll(4 * LOOK);
    store.awaitImports();
    assertEquals(1, receipts().size());
  }

  @Test void listingShowsImportingWhileTheSharedQueueIsHeld() throws Exception {
    var file = ImportFixture.write(root.resolve("inbox").resolve("queued.wpilog"), 2);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var first = store.importPaths(new LogStore.Request(List.of(), false, null), p -> {
      if (!p.phase().equals("starting")) return;
      entered.countDown();
      try { release.await(10, TimeUnit.SECONDS); }
      catch (InterruptedException e) { throw new IllegalStateException(e); }
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      store.inbox().poll(0);
      store.inbox().poll(LOOK);
      assertEquals("importing", inboxEntry().get("state").getAsString());
      assertTrue(Files.exists(file));
      assertFalse(Files.exists(root.resolve("inbox").resolve("imported.log")));
      store.inbox().poll(2 * LOOK); // The queued file must not be queued again.
    } finally { release.countDown(); }
    first.get(10, TimeUnit.SECONDS);
    store.awaitImports();
    assertFalse(Files.exists(file));
    assertEquals(1, receipts().size());
  }

  @Test void aFileTheStoreLockHoldsBackIsDeferredThenImportedAfterItsWait() throws Exception {
    var file = ImportFixture.write(root.resolve("inbox").resolve("busy.wpilog"), 4);
    long first = LOOK + StoreInbox.FIRST_DEFERRAL.toNanos(), second = first + 2 * StoreInbox.FIRST_DEFERRAL.toNanos();
    // Held by this process, the lock refuses the inbox's import as another process's lock does.
    try (var channel = FileChannel.open(root.resolve("store.lock"), StandardOpenOption.WRITE); var lock = channel.lock()) {
      store.inbox().poll(0);
      store.inbox().poll(LOOK); store.awaitImports();
      var entry = inboxEntry();
      assertEquals("waiting", entry.get("state").getAsString(), "A deferred file waits, with its reason; no new state appears");
      assertTrue(entry.get("reason").getAsString().contains("Store lock is held"), entry.toString());
      assertEquals("deferred", JsonParser.parseString(receipts().get(0)).getAsJsonObject().get("status").getAsString());
      store.inbox().poll(first - 1); store.awaitImports();
      assertEquals(1, receipts().size(), "Not tried again before five seconds");
      store.inbox().poll(first); store.awaitImports();
      assertEquals(2, receipts().size(), "Tried again at five seconds, and deferred again");
      store.inbox().poll(second - 1); store.awaitImports();
      assertEquals(2, receipts().size(), "The second wait is twice as long");
    }
    store.inbox().poll(second); store.awaitImports();
    assertFalse(Files.exists(file), "Moved into the store once the lock was free");
    // The fixture names no robot, so the store keeps it unassigned: stored, not refused.
    assertEquals("unassigned", JsonParser.parseString(receipts().get(2)).getAsJsonObject().get("status").getAsString());
  }

  @Test void aDeferredFileWaitsFiveSecondsThenTwiceAsLongUpToAMinute() {
    var delays = new java.util.ArrayList<Long>();
    for (var delay = StoreInbox.nextDeferral(null); delays.size() < 6; delay = StoreInbox.nextDeferral(delay)) delays.add(delay.toSeconds());
    assertEquals(List.of(5L, 10L, 20L, 40L, 60L, 60L), delays);
  }

  @Test void onlyAConditionThatPassesOnItsOwnIsDeferred() throws Exception {
    assertTrue(store.deferred(new StoreBusyException(StoreBusyException.Condition.LOCK_HELD, "held")));
    assertTrue(store.deferred(new java.util.concurrent.CompletionException(new LogFileAccess.InUse("Another import is moving: x"))));
    try (var moving = LogFileAccess.reserveMove(List.of(root))) {
      var read = assertThrows(LogFileAccess.BeingMoved.class, () -> LogFileAccess.read(root.resolve("store.json")).close());
      assertTrue(store.deferred(read), "A read that meets another import's move can succeed once the move ends");
    }
    try (var owner = new DiskSpaceBudgets(filesystem -> 2L << 30)) {
      var reserve = assertThrows(DiskSpaceBudgets.Refused.class, () -> owner.store(root).reserveCopy(1));
      assertEquals("CAPTURE_DISK_RESERVE", reserve.code());
      assertTrue(store.deferred(reserve), "The space recording keeps free can come back");
    }
    assertFalse(store.deferred(new IOException("File has no WPILOG or REV record header")));
    assertFalse(store.deferred(new IOException("Store lock is held by another import (store.lock); retry when it finishes")),
        "A reason's words are not its type");
  }

  @Test void refusalRemainsVisibleWithOneReceiptUntilTheFileChanges() throws Exception {
    var file = Files.writeString(root.resolve("inbox").resolve("notes.txt"), "PRIVATE CONTENT");
    store.inbox().poll(0);
    store.inbox().poll(LOOK);
    store.awaitImports();
    assertEquals("PRIVATE CONTENT", Files.readString(file));
    var entry = inboxEntry();
    assertEquals("refused", entry.get("state").getAsString());
    assertTrue(entry.get("reason").getAsString().contains("no WPILOG or REV record header"));
    var receipt = JsonParser.parseString(receipts().get(0)).getAsJsonObject();
    assertEquals("refused", receipt.get("status").getAsString());
    assertEquals(entry.get("reason"), receipt.get("reason"));
    assertFalse(receipts().get(0).contains("PRIVATE CONTENT"));
    store.inbox().poll(2 * LOOK);
    store.awaitImports();
    assertEquals(1, receipts().size());
    ImportFixture.write(file, 3);
    store.inbox().poll(3 * LOOK);
    assertEquals("waiting", inboxEntry().get("state").getAsString());
    store.inbox().poll(4 * LOOK);
    store.awaitImports();
    assertFalse(Files.exists(file));
    assertEquals(2, receipts().size());
  }

  @Test void growthWhileQueuedReturnsToWaitingBeforeInspection() throws Exception {
    var file = ImportFixture.write(root.resolve("inbox").resolve("resumed.wpilog"), 9);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    store.importPaths(new LogStore.Request(List.of(), false, null), p -> {
      if (!p.phase().equals("starting")) return;
      entered.countDown();
      try { release.await(10, TimeUnit.SECONDS); }
      catch (InterruptedException e) { throw new IllegalStateException(e); }
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      store.inbox().poll(0);
      store.inbox().poll(LOOK);
      Files.write(file, new byte[] {0}, StandardOpenOption.APPEND);
    } finally { release.countDown(); }
    store.awaitImports();
    assertTrue(Files.exists(file));
    assertEquals("waiting", inboxEntry().get("state").getAsString());
    assertFalse(Files.exists(root.resolve("inbox").resolve("imported.log")));
    store.inbox().poll(System.nanoTime() + LOOK);
    store.awaitImports();
    assertFalse(Files.exists(file));
  }

  @Test void duplicatesStayWithTheirDestinationAndAreNotRetried() throws Exception {
    var file = ImportFixture.write(root.resolve("inbox").resolve("duplicate.wpilog"), 4);
    var imported = store.importPaths(new LogStore.Request(List.of(file), false, "practice"), p -> {}).get();
    store.inbox().poll(0);
    store.inbox().poll(LOOK);
    store.awaitImports();
    assertTrue(Files.exists(file));
    assertEquals("refused", inboxEntry().get("state").getAsString());
    assertTrue(inboxEntry().get("reason").getAsString().contains(imported.files().get(0).path().toString()));
    assertEquals("present", JsonParser.parseString(receipts().get(0)).getAsJsonObject().get("status").getAsString());
    store.inbox().poll(2 * LOOK);
    store.awaitImports();
    assertEquals(1, receipts().size());
  }

  @Test void symlinksAreRefusedWithoutMovingTheirTargets() throws Exception {
    var target = ImportFixture.write(temp.resolve("original.wpilog"), 5);
    var link = root.resolve("inbox").resolve("link.wpilog");
    try { Files.createSymbolicLink(link, target); }
    catch (UnsupportedOperationException | java.io.IOException e) {
      org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable: " + e.getMessage());
    }
    store.inbox().poll(0);
    store.inbox().poll(LOOK);
    store.awaitImports();
    assertTrue(Files.exists(target));
    assertTrue(Files.isSymbolicLink(link));
    assertEquals("refused", inboxEntry().get("state").getAsString());
    assertTrue(inboxEntry().get("reason").getAsString().contains("symbolic links"));
  }

  @Test void daemonPollerDiscoversANestedStore() throws Exception {
    var file = ImportFixture.write(root.resolve("inbox").resolve("drop.wpilog"), 6);
    try (var poller = new StorePollerProbe(manager.stores())) {
      manager.stores().startWatching();
      try { poller.advance(); poller.advance(); store.awaitImports(); }
      finally { manager.stores().stopWatching(); }
      assertFalse(Files.exists(file), "Scheduled polling must consume the stable drop"); assertEquals(1, receipts().size());
    }
    var executor = StoreRegistry.newWatcher();
    try { assertTrue(executor.submit(() -> Thread.currentThread().isDaemon() && Thread.currentThread().getName().equals("store-inbox")).get(5, TimeUnit.SECONDS)); }
    finally { executor.shutdownNow(); }
  }

  @Test void publishingOneGroupDoesNotInvalidateTheRemainingInboxBatch() throws Exception {
    var batch = Files.createDirectories(root.resolve("inbox").resolve("multiple-groups"));
    Files.writeString(batch.resolve("batch.json"), "{\"stated_robot\":\"practice\"}");
    var first = ImportFixture.write(batch.resolve("first.wpilog"), 31);
    var second = ImportFixture.write(batch.resolve("second.wpilog"), 32);
    byte[] firstBytes = Files.readAllBytes(first);
    byte[] secondBytes = Files.readAllBytes(second);
    store.inbox().poll(0);
    store.inbox().poll(LOOK);
    store.awaitImports();

    var files = StoreCatalog.readManaged(root, manager.testGetSecurityValidator()).files();
    assertEquals(2, files.size(), "Every unchanged source in the published inbox batch must be imported");
    assertFalse(Files.exists(first));
    assertFalse(Files.exists(second));
    assertEquals(2, receipts().size());
    assertTrue(files.stream().anyMatch(file -> {
      try { return java.util.Arrays.equals(firstBytes, Files.readAllBytes(file.path())); }
      catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }));
    assertTrue(files.stream().anyMatch(file -> {
      try { return java.util.Arrays.equals(secondBytes, Files.readAllBytes(file.path())); }
      catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }));
  }

  @Test void payloadPreparationReleasesTheFileLockWhileReceiptPublicationKeepsIt() throws Exception {
    var input = ImportFixture.write(temp.resolve("lock-duration.wpilog"), 10);
    var phases = new java.util.ArrayList<String>();
    var held = new java.util.ArrayList<Boolean>();
    store.importPrepared(new LogStore.Request(List.of(input), false, "practice"), p -> {
      phases.add(p.phase());
      held.add(lockHeld());
    }, request -> {
      phases.add("admission");
      held.add(lockHeld());
      return request;
    }, result -> {
      phases.add("receipt");
      held.add(lockHeld());
    }).get(10, TimeUnit.SECONDS);
    assertTrue(phases.containsAll(List.of("starting", "inspecting", "verifying", "complete", "receipt")), phases.toString());
    for (int index = 0; index < phases.size(); index++)
      assertEquals(Set.of("admission", "receipt").contains(phases.get(index)), held.get(index),
          "Only bounded admission and receipt publication hold store.lock: " + phases.get(index));
    assertFalse(lockHeld(), "Future completion releases the lock for another process");
  }

  @Test void receiptSymlinkCannotAppendToAnotherStoreFile() throws Exception {
    var target = Files.writeString(root.resolve("keep.txt"), "original bytes");
    var receipt = root.resolve("inbox").resolve("imported.log");
    try { Files.createSymbolicLink(receipt, target); }
    catch (UnsupportedOperationException | java.io.IOException e) {
      org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable: " + e.getMessage());
    }
    var refused = Files.writeString(root.resolve("inbox").resolve("notes.txt"), "not a log");
    store.inbox().poll(0);
    store.inbox().poll(LOOK);
    store.awaitImports();
    assertEquals("original bytes", Files.readString(target));
    assertTrue(Files.exists(refused));
    assertTrue(store.inbox().listing().get(0).reason().contains("Could not write inbox/imported.log"));
  }

  @Test void idleExitAlsoWaitsForInboxImportsWithoutAnHttpJob() throws Exception {
    var file = ImportFixture.write(root.resolve("inbox").resolve("idle.wpilog"), 21);
    var exited = new CountDownLatch(1);
    var transport = new org.triplehelix.wpilogmcp.mcp.HttpTransport(new ToolRegistry(), 0);
    var idle = new org.triplehelix.wpilogmcp.mcp.HttpIdleProbe(transport);
    transport.setIdleExit(Duration.ofMillis(200), exited::countDown);
    try {
      try (var reader = org.triplehelix.wpilogmcp.log.LogFileAccess.read(file)) {
        store.inbox().poll(0);
        store.inbox().poll(LOOK);
        transport.start();
        assertEquals(0, transport.sessionCount());
        idle.advance(Duration.ofMillis(700));
        assertEquals(1, exited.getCount(), "Inbox work keeps the daemon alive past idle");
      }
      store.awaitImports();
      assertFalse(Files.exists(file));
      idle.advance(Duration.ofMillis(700)); assertTrue(exited.await(5, TimeUnit.SECONDS), "The idle exit runs on its own thread");
    } finally {
      transport.stop();
    }
  }

  @Test void batchIdentityIsListedAndAppliedButALooseDropHasNone() throws Exception {
    var batch = root.resolve("inbox").resolve("batch-fixture");
    var named = ImportFixture.write(batch.resolve("named.wpilog"), 22);
    Files.writeString(batch.resolve("batch.json"), "{\"stated_robot\":\"practice\"}");
    var loose = ImportFixture.write(root.resolve("inbox").resolve("loose.wpilog"), 23);
    var entries = listing().getAsJsonArray("inbox");
    assertEquals(2, entries.size(), "The sidecar is metadata, not a log");
    for (var value : entries) {
      var entry = value.getAsJsonObject();
      if (Path.of(entry.get("path").getAsString()).equals(named)) {
        assertEquals("practice", entry.get("stated_robot").getAsString());
      } else {
        assertTrue(entry.get("stated_robot").isJsonNull());
      }
    }
    store.inbox().poll(0);
    store.inbox().poll(LOOK);
    store.awaitImports();
    assertFalse(Files.exists(named));
    assertFalse(Files.exists(loose));
    for (var receipt : receipts()) {
      var entry = JsonParser.parseString(receipt).getAsJsonObject();
      var destination = Path.of(entry.get("path").getAsString());
      assertTrue(destination.startsWith(entry.get("original_path").getAsString().equals(named.toString())
          ? root.resolve("robots").resolve("practice") : root.resolve("unassigned")), receipt);
    }
    assertEquals(2, receipts().size());
  }

  @Test void malformedBatchRefusesEveryFileOnceAndChangingTheSidecarRetries() throws Exception {
    var batch = root.resolve("inbox").resolve("batch-bad");
    var file = ImportFixture.write(batch.resolve("one.wpilog"), 24);
    var sidecar = Files.writeString(batch.resolve("batch.json"), "{not json PRIVATE}");
    store.inbox().poll(0);
    store.inbox().poll(LOOK);
    store.awaitImports();
    assertTrue(Files.exists(file));
    assertEquals("refused", inboxEntry().get("state").getAsString());
    assertTrue(inboxEntry().get("reason").getAsString().contains("Invalid inbox batch.json"));
    assertTrue(receipts().get(0).contains("Invalid inbox batch.json"));
    assertFalse(receipts().get(0).contains("PRIVATE"));
    store.inbox().poll(2 * LOOK);
    store.awaitImports();
    assertEquals(1, receipts().size());
    Files.writeString(sidecar, "{\"stated_robot\":\"fixed\"}");
    store.inbox().poll(3 * LOOK);
    store.inbox().poll(4 * LOOK);
    store.awaitImports();
    assertFalse(Files.exists(file));
    assertEquals(2, receipts().size());
  }

  @Test void failedTransferIsHiddenRemovedOnNextPollAndReceiptedButAnActiveOneIsKept() throws Exception {
    var source = ImportFixture.write(temp.resolve("usb").resolve("outside.wpilog"), 25);
    var security = new org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator();
    security.addAllowedDirectory(temp);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var transfer = java.util.concurrent.CompletableFuture.runAsync(() -> {
      assertThrows(java.io.IOException.class, () -> InboxTransfer.stage(root, List.of(source), false,
          "practice", security, System.out, (from, to) -> {
            Files.copy(from, to);
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { throw new java.io.IOException(e); }
            throw new java.io.IOException("planted copy failure");
          }));
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      store.inbox().poll(0); store.awaitImports();
      assertTrue(listing().getAsJsonArray("unmanaged").isEmpty());
      assertTrue(listing().getAsJsonArray("inbox").isEmpty());
      assertFalse(Files.exists(root.resolve("inbox").resolve("imported.log")));
      try (var children = Files.list(root.resolve("inbox"))) {
        assertEquals(1, children.filter(Files::isDirectory).count(), "The active copy remains");
      }
    } finally {
      release.countDown();
    }
    transfer.get(10, TimeUnit.SECONDS);
    store.inbox().poll(LOOK); store.awaitImports();
    assertTrue(Files.exists(source));
    try (var children = Files.list(root.resolve("inbox"))) {
      assertEquals(List.of("imported.log"), children.map(p -> p.getFileName().toString()).toList());
    }
    assertTrue(receipts().get(0).contains("Removed incomplete inbox transfer"));
    assertTrue(listing().getAsJsonArray("unmanaged").isEmpty());
    assertTrue(listing().getAsJsonArray("inbox").isEmpty());
    store.inbox().poll(2 * LOOK);
    assertEquals(1, receipts().size());
  }

  @Test void hiddenInboxEntriesAreNotWalkedOrListed() throws Exception {
    ImportFixture.write(root.resolve("inbox").resolve(".hidden").resolve("one.wpilog"), 26);
    ImportFixture.write(root.resolve("inbox").resolve(".two.wpilog"), 27);
    store.inbox().poll(0);
    store.inbox().poll(LOOK);
    store.awaitImports();
    assertTrue(listing().getAsJsonArray("inbox").isEmpty());
    assertFalse(Files.exists(root.resolve("inbox").resolve("imported.log")));
  }

  @Test void listingDiscoversANewStoreBeforeTheNextMinuteWalk() throws Exception {
    long now = System.nanoTime();
    manager.stores().poll(now);
    var otherRoot = temp.resolve("another-store");
    var security = new org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator();
    security.addAllowedDirectory(temp);
    try (var other = new StoreRegistry(security)) {
      other.store(otherRoot).importPaths(new LogStore.Request(List.of(), false, null), p -> {}).get();
    }
    var file = ImportFixture.write(otherRoot.resolve("inbox").resolve("new.wpilog"), 31);
    LogDirectory.getInstance().scanLogs(); // No tool-level registry lookup should be needed.
    manager.stores().poll(now + LOOK);
    manager.stores().poll(now + 2 * LOOK);
    manager.stores().awaitImports();
    assertFalse(Files.exists(file), "The listing's discovery is watched immediately");
  }

  @Test void robotNamesMatchThePortableAsciiRuleUsedByClients() {
    for (var valid : List.of("practice", "Robot_2-A.b", "123")) assertEquals(valid, StoreFiles.robotName(valid));
    for (var invalid : List.of("", "two words", "robot/child", "café")) {
      assertEquals("Robot names use only letters, digits, dots, hyphens, and underscores",
          assertThrows(IllegalArgumentException.class, () -> StoreFiles.robotName(invalid)).getMessage());
    }
    for (var invalid : List.of(".", "..", "tail.", "CON", "lpt9.txt")) {
      assertEquals("Not a portable robot or file name: " + invalid,
          assertThrows(IllegalArgumentException.class, () -> StoreFiles.robotName(invalid)).getMessage());
    }
  }

  private boolean lockHeld() {
    try (var channel = java.nio.channels.FileChannel.open(root.resolve("store.lock"), StandardOpenOption.WRITE)) {
      try (var lock = channel.tryLock()) { return lock == null; }
      catch (java.nio.channels.OverlappingFileLockException e) { return true; }
    } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
  }
}
