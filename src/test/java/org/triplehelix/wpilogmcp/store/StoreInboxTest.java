/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    return tools.getTool("list_available_logs").execute(new JsonObject()).getAsJsonObject();
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

  @Test void realDaemonPollerDiscoversANestedStore() throws Exception {
    var file = ImportFixture.write(root.resolve("inbox").resolve("drop.wpilog"), 6);
    manager.stores().startWatching();
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    while (Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(30);
    store.awaitImports();
    assertFalse(Files.exists(file), "Polling watcher did not consume the stable drop");
    assertEquals(1, receipts().size());
    assertTrue(Thread.getAllStackTraces().keySet().stream()
        .anyMatch(thread -> thread.getName().equals("store-inbox") && thread.isDaemon()));
  }

  @Test void fileLockCoversInspectionVerificationAndReceiptsAndIsReleasedOnCompletion() throws Exception {
    var input = ImportFixture.write(temp.resolve("lock-duration.wpilog"), 10);
    var phases = new java.util.ArrayList<String>();
    var held = new java.util.ArrayList<Boolean>();
    store.importPrepared(new LogStore.Request(List.of(input), false, "practice"), p -> {
      phases.add(p.phase());
      held.add(lockHeld());
    }, r -> r, result -> {
      phases.add("receipt");
      held.add(lockHeld());
    }).get(10, TimeUnit.SECONDS);
    assertTrue(phases.containsAll(List.of("starting", "inspecting", "verifying", "complete", "receipt")), phases.toString());
    assertTrue(held.stream().allMatch(Boolean::booleanValue), held.toString());
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

  private boolean lockHeld() {
    try (var channel = java.nio.channels.FileChannel.open(root.resolve("store.lock"), StandardOpenOption.WRITE)) {
      try (var lock = channel.tryLock()) { return lock == null; }
      catch (java.nio.channels.OverlappingFileLockException e) { return true; }
    } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
  }
}
