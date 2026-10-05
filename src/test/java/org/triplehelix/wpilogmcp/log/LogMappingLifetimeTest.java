/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.fixtures.WpilogWriter.encodeDouble;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.subsystems.LogParser;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreRegistry;
import org.triplehelix.wpilogmcp.sync.LogSynchronizer;
import org.triplehelix.wpilogmcp.sync.SyncResult;
import org.triplehelix.wpilogmcp.tools.LogRequiringTool;
import org.triplehelix.wpilogmcp.tools.ToolDependencies;

/** Imports must release real mappings, while an evicted log still serves every existing holder. */
class LogMappingLifetimeTest {
  @TempDir Path temp;
  LogManager manager;
  StoreRegistry stores;
  Path source;
  Path store;

  @BeforeEach
  void setup() throws Exception {
    temp = temp.toRealPath();
    source = temp.resolve("read-then-move.wpilog");
    store = temp.resolve("store");
    try (var writer = new WpilogWriter(source, "mapping lifetime fixture")) {
      int value = writer.start("/value", "double", "", 0);
      writer.append(value, 1_000_000, encodeDouble(12));
      writer.append(value, 3_000_000, encodeDouble(18));
    }
    manager = new LogManager();
    manager.addAllowedDirectory(temp);
    stores = new StoreRegistry(manager.testGetSecurityValidator(), manager);
  }

  @AfterEach
  void cleanup() {
    stores.close();
    manager.shutdown();
  }

  private JsonObject arguments(Path path) {
    var args = new JsonObject();
    args.addProperty("path", path.toString());
    return args;
  }

  private LogStore.Outcome move() throws Exception {
    return stores.store(store).importPaths(new LogStore.Request(List.of(source), true, "practice"), p -> {})
        .get(15, TimeUnit.SECONDS).files().get(0);
  }

  @Test
  void eagerFallbackAlsoRetainsItsFileForTheCall() throws Exception {
    manager.testPutLog(source.toString(), new LogParser().parse(source));
    try (var use = manager.acquire(source.toString())) {
      assertFalse(manager.release(source).released());
      assertEquals(List.of(12.0, 18.0), use.log().values().get("/value").stream()
          .map(TimestampedValue::value).toList());
    }
    assertTrue(manager.release(source).released());
    try (var free = LogFileAccess.move(List.of(source))) {
      Files.move(source, temp.resolve("eager-freed.wpilog"));
    }
  }

  @Test
  void loadedLogReadByToolCanMoveAndReload() throws Exception {
    manager.loadLog(source.toString());
    assertThrows(IOException.class, () -> LogFileAccess.move(List.of(source)));
    var result = new ReadingTool(manager, null, null).execute(arguments(source)).getAsJsonObject();
    assertEquals(30.0, result.get("sum").getAsDouble());
    var moved = move();
    assertEquals("imported", moved.status(), moved.toString());
    assertFalse(Files.exists(source));
    assertTrue(moved.path().startsWith(store.resolve("robots").resolve("practice")));
    try (var use = manager.acquire(moved.path().toString())) {
      assertEquals(List.of(12.0, 18.0), use.log().values().get("/value").stream()
          .map(TimestampedValue::value).toList());
    }
    manager.unloadLog(moved.path().toString());
    try (var free = LogFileAccess.move(List.of(moved.path()))) {
      // A real rename proves this on Windows; the access claim proves it without GC on macOS.
      Files.move(moved.path(), temp.resolve("after-eviction.wpilog"));
    }
  }

  @Test
  void heldToolRefusesMoveWithReasonThenReleasesForRetry() throws Exception {
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var call = executor.submit(() -> new ReadingTool(manager, entered, proceed).execute(arguments(source)));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var refused = move();
      assertEquals("refused", refused.status());
      assertTrue(refused.reason().contains("in-flight"), refused.reason());
      assertTrue(refused.reason().contains("3 seconds"), refused.reason());
      assertTrue(Files.exists(source));
      assertFalse(call.isDone());
      proceed.countDown();
      assertEquals(30.0, call.get(5, TimeUnit.SECONDS).getAsJsonObject().get("sum").getAsDouble());
      assertEquals("imported", move().status());
    } finally {
      proceed.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void backgroundSyncHoldsItsMappingUntilItActuallyStops() throws Exception {
    var entered = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var synchronizer = new LogSynchronizer() {
      @Override
      public SyncResult synchronize(LogData wpilog, ParsedRevLog revlog) {
        entered.countDown();
        try {
          if (!proceed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture sync timed out");
          var result = super.synchronize(wpilog, revlog);
          finished.countDown();
          return result;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
    };
    var path = FixtureLogs.writeRevlogPair(temp, "2026-pair.wpilog", ZoneOffset.UTC, "systemTime");
    var own = new LogManager(synchronizer);
    own.addAllowedDirectory(temp);
    own.getSyncDiskCache().setEnabled(false);
    try {
      own.loadLog(path.toString());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertFalse(own.release(path).released(), "cancelling the future must not unmap a running sync");
      proceed.countDown();
      assertTrue(own.awaitSyncExecutorIdle(5_000));
      assertEquals(0, finished.getCount(), "the real correlation must finish reading after eviction");
      assertTrue(own.release(path).released());
      try (var free = LogFileAccess.move(List.of(path))) {
        Files.move(path, temp.resolve("synced-then-moved.wpilog"));
      }
    } finally {
      proceed.countDown();
      own.shutdown();
    }
  }

  @Test
  void evictionKeepsEveryHolderAndLastCloseFinishesRelease() throws Exception {
    var first = manager.acquire(source.toString());
    var second = manager.acquire(source.toString());
    var executor = Executors.newSingleThreadExecutor();
    try (var reservation = LogFileAccess.reserveMove(List.of(source))) {
      assertTrue(manager.unloadLog(source.toString()));
      assertEquals(12.0, first.log().values().get("/value").get(0).value());
      first.close();
      first.close();
      assertEquals(18.0, second.log().values().get("/value").get(1).value());
      assertThrows(LogFileException.class, () -> manager.acquire(source.toString()));
      var waiting = new CountDownLatch(1);
      var released = executor.submit(() -> {
        waiting.countDown();
        return manager.release(source);
      });
      assertTrue(waiting.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> released.get(100, TimeUnit.MILLISECONDS));
      second.close();
      assertTrue(released.get(2, TimeUnit.SECONDS).released());
      Files.move(source, temp.resolve("freed.wpilog"));
    } finally {
      first.close();
      second.close();
      executor.shutdownNow();
    }
  }

  private static final class ReadingTool extends LogRequiringTool {
    private final CountDownLatch entered;
    private final CountDownLatch proceed;

    ReadingTool(LogManager manager, CountDownLatch entered, CountDownLatch proceed) {
      super(new ToolDependencies(manager, null, null, null));
      this.entered = entered;
      this.proceed = proceed;
    }

    @Override
    public String name() {
      return "mapping_fixture";
    }

    @Override
    public String description() {
      return "Read a synthetic fixture while an import may evict its mapping.";
    }

    @Override
    protected JsonObject toolSchema() {
      return new JsonObject();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      if (entered != null) {
        entered.countDown();
        if (!proceed.await(12, TimeUnit.SECONDS)) throw new IOException("Fixture call timed out");
      }
      var result = new JsonObject();
      result.addProperty("sum", log.values().get("/value").stream()
          .mapToDouble(v -> ((Number) v.value()).doubleValue()).sum());
      return result;
    }
  }
}
