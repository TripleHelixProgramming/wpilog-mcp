/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.store.LogStore;

/** Closing a transport cannot join payload work owned by the shared store registry. */
class HttpImportShutdownTest {
  @TempDir Path directory;

  @Test void transportCloseDrainsOnlyPublicationTurns() throws Exception {
    var logs = LogManager.getInstance();
    var previous = logs.getAllowedDirectories();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closer = Executors.newSingleThreadExecutor(task -> {
      var thread = new Thread(task, "http-import-close-fixture");
      thread.setDaemon(true);
      return thread;
    });
    HttpTransport server = null;
    CompletableFuture<Void> stopped = null;
    CompletableFuture<LogStore.Result> imported = null;
    try {
      logs.addAllowedDirectory(directory);
      var source = ImportFixture.write(directory.resolve("source.wpilog"), 37);
      var store = logs.stores().store(directory.resolve("store"));
      server = new HttpTransport(new ToolRegistry(), 0);
      server.start();
      imported = store.importPaths(new LogStore.Request(List.of(source), false, "SYNTHETIC"),
          progress -> {
            if (!progress.phase().equals("inspecting")) return;
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
          });
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      stopped = CompletableFuture.runAsync(server::stop, closer);
      try {
        stopped.get(1500, TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException timeout) {
        fail("Transport close must not wait for a blocked shared import's payload inspection");
      }
      assertFalse(imported.isDone(),
          "Closing one transport must not cancel an import owned by the shared registry");
    } finally {
      release.countDown();
      if (imported != null) {
        var result = imported.get(10, TimeUnit.SECONDS);
        assertEquals("imported", result.files().get(0).status(), result.toString());
      }
      if (stopped != null) stopped.get(10, TimeUnit.SECONDS);
      else if (server != null) server.stop();
      closer.shutdownNow();
      logs.clearAllowedDirectories();
      previous.forEach(logs::addAllowedDirectory);
    }
  }
}
