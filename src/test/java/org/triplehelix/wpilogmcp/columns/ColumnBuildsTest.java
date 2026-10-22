/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.log.IsolatedLogManagers;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.FieldPath;

/**
 * Background builds of column files: what a built file holds, what makes a build wait, and what
 * keeps a file from being published or built again.
 */
class ColumnBuildsTest {
  private static final int SAMPLES = 50;
  @TempDir Path directory;
  private Path source;
  private LogManager manager;
  private final List<ColumnBuilds> started = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    directory = directory.toRealPath();
    source = write(directory.resolve("builds.wpilog"), 0.5, SAMPLES);
    manager = IsolatedLogManagers.create(directory, "column-builds");
  }

  @AfterEach
  void stop() {
    started.forEach(ColumnBuilds::close);
    manager.shutdown();
  }

  /** {@code /v} holds {@code step * i} at {@code 1 + 0.02 i} seconds. */
  private static Path write(Path file, double step, int samples) throws IOException {
    try (var w = new FixtureWriter(file, "")) {
      for (int i = 0; i < samples; i++) w.dbl("/v", 1 + i * 0.02, i * step);
    }
    return file;
  }

  private ColumnBuilds builds(ColumnBuilds.Logs logs, LongSupplier freeHeap, BooleanSupplier recordingShort) {
    var builds = new ColumnBuilds(manager.getDiskCache(), logs, freeHeap, recordingShort, file -> false, 5);
    started.add(builds);
    return builds;
  }

  private ColumnBuilds builds(ColumnBuilds.Logs logs) {
    return builds(logs, () -> Long.MAX_VALUE, () -> false);
  }

  /** The content its scan index proved, as the server reads the log. */
  private String hash(Path file) throws IOException {
    try (var use = manager.acquireReadOnly(file.toString())) {
      return ((LazyParsedLog) use.log()).contentSha256().orElseThrow();
    }
  }

  private Path target(String hash) {
    return manager.getDiskCache().columnFilePath(hash).orElseThrow();
  }

  private List<Path> temporaries() throws IOException {
    try (var files = Files.list(manager.getCacheDirectory().getPath())) {
      return files.filter(file -> file.getFileName().toString().contains(".tmp.")).toList();
    }
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, what);
      Thread.sleep(2);
    }
  }

  private static void awaitIdle(ColumnBuilds builds) throws InterruptedException {
    await(builds::idle, "the build did not finish");
  }

  /** Counts acquisitions, and runs {@code during} when the build first looks at the log. */
  private static ColumnBuilds.Logs watched(ColumnBuilds.Logs logs, AtomicInteger acquisitions, Runnable during) {
    return path -> {
      acquisitions.incrementAndGet();
      var lease = logs.acquire(path);
      return new ColumnBuilds.Lease() {
        @Override
        public LogData log() {
          var log = lease.log();
          during.run();
          return log;
        }

        @Override
        public boolean unchanged() {
          return lease.unchanged();
        }

        @Override
        public void close() throws IOException {
          lease.close();
        }
      };
    };
  }

  @Test
  void aReadLogGetsAColumnFileOfItsOwnValues() throws Exception {
    var builds = builds(ColumnBuilds.from(manager));
    String hash;
    try (var use = manager.acquireReadOnly(source.toString())) {
      hash = ((LazyParsedLog) use.log()).contentSha256().orElseThrow();
      builds.request(source.toString(), use.log());
    }
    awaitIdle(builds);
    manager.getDiskCache().cleanup();
    try (var file = ColumnFile.open(target(hash), "sha256:" + hash)) {
      var v = file.select("/v", FieldPath.parse("")).orElseThrow();
      var samples = v.samples(v.all());
      assertEquals(SAMPLES, samples.size());
      for (int i = 0; i < SAMPLES; i++) {
        assertEquals(FixtureWriter.micros(1 + i * 0.02), samples.timeUs(i));
        assertEquals(i * 0.5, samples.number(i));
      }
    }
    assertEquals(List.of(), temporaries(), "no temporary file is left behind");
  }

  @Test
  void aLogWithoutProvenContentOrAMalformedHashAsksForNothing() throws Exception {
    var builds = builds(ColumnBuilds.from(manager));
    manager.getDiskCache().setEnabled(false);
    try (var use = manager.acquireReadOnly(source.toString())) {
      builds.request(source.toString(), use.log());
    }
    assertTrue(builds.idle(), "a log read without its content proved has no column file");
    manager.getDiskCache().setEnabled(true);
    manager.unloadLog(source.toString());
    String hash = hash(source);
    builds.request(source.toString(), "sha256:" + hash);
    builds.request(source.toString(), hash.toUpperCase());
    builds.request(null, hash);
    assertTrue(builds.idle());
  }

  /** The heap gate at exactly 512 MB free lets the build through to the recording gate. */
  @Test
  void aBuildWaitsWhileTheHeapIsShortThenWhileRecordingsReserveIs() throws Exception {
    var heap = new AtomicLong(ColumnBuilds.MIN_FREE_HEAP - 1);
    var heapChecks = new AtomicInteger();
    var reserveShort = new AtomicBoolean(true);
    var reserveChecks = new AtomicInteger();
    var acquisitions = new AtomicInteger();
    var builds = builds(watched(ColumnBuilds.from(manager), acquisitions, () -> {}),
        () -> {
          heapChecks.incrementAndGet();
          return heap.get();
        },
        () -> {
          reserveChecks.incrementAndGet();
          return reserveShort.get();
        });
    String hash = hash(source);
    builds.request(source.toString(), hash);
    await(() -> heapChecks.get() >= 3, "the build was not tried");
    assertEquals(0, acquisitions.get(), "nothing is read while the heap is short");
    assertTrue(builds.queued(hash), "the build still waits");
    heap.set(ColumnBuilds.MIN_FREE_HEAP);
    await(() -> reserveChecks.get() >= 3, "the build was not tried again");
    assertEquals(0, acquisitions.get(), "nothing is read while recording's reserve is short");
    reserveShort.set(false);
    awaitIdle(builds);
    assertEquals(1, acquisitions.get());
    assertTrue(Files.exists(target(hash)));
  }

  @Test
  void aGateThatCannotAnswerHoldsTheBuild() throws Exception {
    var answered = new AtomicBoolean();
    var checks = new AtomicInteger();
    var builds = builds(ColumnBuilds.from(manager), () -> Long.MAX_VALUE, () -> {
      checks.incrementAndGet();
      if (!answered.get()) throw new IllegalStateException("no answer");
      return false;
    });
    String hash = hash(source);
    builds.request(source.toString(), hash);
    await(() -> checks.get() >= 3, "the build was not tried");
    assertFalse(Files.exists(target(hash)));
    answered.set(true);
    awaitIdle(builds);
    assertTrue(Files.exists(target(hash)), "a failed gate is not a failed build");
  }

  @Test
  void aRequestNamingOtherContentThanTheFileHoldsBuildsNothing() throws Exception {
    String old = hash(source);
    manager.unloadLog(source.toString());
    write(source, 0.25, SAMPLES + 1);
    var builds = builds(ColumnBuilds.from(manager));
    builds.request(source.toString(), old);
    awaitIdle(builds);
    assertFalse(Files.exists(target(old)), "a file is named only by the content it was built from");
    assertEquals(List.of(), temporaries());
  }

  @Test
  void aFileThatChangesDuringItsBuildIsNotPublished() throws Exception {
    String hash = hash(source);
    var once = new AtomicBoolean();
    var builds = builds(watched(ColumnBuilds.from(manager), new AtomicInteger(), () -> {
      if (once.getAndSet(true)) return;
      try {
        Files.setLastModifiedTime(source, FileTime.fromMillis(Files.getLastModifiedTime(source).toMillis() + 5_000));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }));
    builds.request(source.toString(), hash);
    awaitIdle(builds);
    assertFalse(Files.exists(target(hash)), "bytes read across a change are not published");
    assertEquals(List.of(), temporaries());
    builds.request(source.toString(), hash);
    awaitIdle(builds);
    assertTrue(Files.exists(target(hash)), "the same content read again, unchanged, is built");
  }

  @Test
  void aPathThatCannotBeReadDoesNotStopTheSameContentAtAnother() throws Exception {
    String hash = hash(source);
    var builds = builds(ColumnBuilds.from(manager));
    builds.request(directory.resolve("moved-away.wpilog").toString(), hash);
    awaitIdle(builds);
    assertFalse(Files.exists(target(hash)));
    builds.request(source.toString(), hash);
    awaitIdle(builds);
    assertTrue(Files.exists(target(hash)), "the content is built where it can be read");
  }

  @Test
  void oneBuildServesEveryRequestForTheSameContent() throws Exception {
    var acquisitions = new AtomicInteger();
    var heap = new AtomicLong(0);
    var builds = builds(watched(ColumnBuilds.from(manager), acquisitions, () -> {}), heap::get, () -> false);
    String hash = hash(source);
    for (int i = 0; i < 5; i++) builds.request(source.toString(), hash);
    heap.set(Long.MAX_VALUE);
    awaitIdle(builds);
    builds.request(source.toString(), hash);
    awaitIdle(builds);
    assertTrue(Files.exists(target(hash)));
    assertEquals(1, acquisitions.get(), "requests while one waits, and after its file exists, read nothing");
  }

  @Test
  void aBuildTrimsTheCacheToItsLimit() throws Exception {
    var cacheDirectory = manager.getCacheDirectory().getPath();
    var old = Files.write(cacheDirectory.resolve("cc".repeat(16) + "-sync.msgpack"), new byte[1 << 20]);
    Files.setLastModifiedTime(old, FileTime.fromMillis(1_000));
    manager.getDiskCache().setMaxTotalSizeMb(1);
    var builds = builds(ColumnBuilds.from(manager));
    String hash = hash(source);
    builds.request(source.toString(), hash);
    awaitIdle(builds);
    assertFalse(Files.exists(old), "the oldest file goes once the new one puts the cache over its limit");
    assertTrue(Files.exists(target(hash)));
  }

  @Test
  void aFileTheLimitHasNoRoomForIsNotBuiltAgain() throws Exception {
    manager.getDiskCache().setMaxTotalSizeMb(0);
    var acquisitions = new AtomicInteger();
    var builds = builds(watched(ColumnBuilds.from(manager), acquisitions, () -> {}));
    String hash = hash(source);
    builds.request(source.toString(), hash);
    awaitIdle(builds);
    assertFalse(Files.exists(target(hash)), "trimmed as soon as it was written");
    builds.request(source.toString(), hash);
    awaitIdle(builds);
    assertEquals(1, acquisitions.get(), "not built again in this process");
  }

  @Test
  void buildsRunOnOneLowPriorityDaemonThread() throws Exception {
    var thread = new AtomicReference<Thread>();
    var logs = ColumnBuilds.from(manager);
    var builds = builds(path -> {
      thread.set(Thread.currentThread());
      return logs.acquire(path);
    });
    String hash = hash(source);
    builds.request(source.toString(), hash);
    awaitIdle(builds);
    assertTrue(Files.exists(target(hash)));
    assertEquals("column-builds", thread.get().getName());
    assertTrue(thread.get().isDaemon());
    assertEquals(Thread.MIN_PRIORITY, thread.get().getPriority());
  }

  @Test
  void closingDropsWaitingBuildsAndRefusesNewOnes() throws Exception {
    var heapChecks = new AtomicInteger();
    var acquisitions = new AtomicInteger();
    var builds = builds(watched(ColumnBuilds.from(manager), acquisitions, () -> {}), () -> {
      heapChecks.incrementAndGet();
      return 0;
    }, () -> false);
    String hash = hash(source);
    builds.request(source.toString(), hash);
    await(() -> heapChecks.get() >= 1, "the build was not tried");
    builds.close();
    assertTrue(builds.idle(), "a waiting build is dropped");
    int checks = heapChecks.get();
    Thread.sleep(50);
    assertEquals(checks, heapChecks.get(), "a dropped build is not tried again");
    builds.request(source.toString(), hash);
    assertTrue(builds.idle(), "a request after close is refused");
    assertEquals(0, acquisitions.get());
  }
}
