/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.store.StoreManifest.Provenance;
import org.triplehelix.wpilogmcp.store.StoreManifest.Robot;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

/** The immutable receipt is a second allocation; the durable prefix must retain its own owner. */
class SystemPeerSyncHardeningTest {
  @TempDir Path directory;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
  private static final String ROBOT = "SYNTHETIC-SYSTEM";
  private static final String REMOTE = "robots/" + ROBOT + "/system/messages.txt";
  private static final String URL = "http://synthetic.invalid";
  private static final byte[] TEXT = "2026-10-20T12:00:00Z synthetic system line\n"
      .getBytes(StandardCharsets.UTF_8);
  private final AtomicLong free = new AtomicLong(10_000_000_000L);
  private final DiskSpaceBudgets budget = new DiskSpaceBudgets(filesystem -> free.get());
  private final AtomicInteger reads = new AtomicInteger();
  private final CountDownLatch reading = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final LogManager manager = LogManager.getInstance();
  private Set<Path> previous;
  private SecurityValidator security;
  private StoreRegistry registry;
  private LogStore store;

  @BeforeEach void setup() throws Exception {
    directory = directory.toRealPath();
    previous = manager.getAllowedDirectories();
    manager.addAllowedDirectory(directory);
    security = new SecurityValidator();
    security.addAllowedDirectory(directory);
    registry = new StoreRegistry(security, manager, StoreCatalog::discover, budget);
    store = registry.store(directory.resolve("store"));
    store.capture(io -> null);
    store.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(100, 20, 30));
  }

  @AfterEach void close() {
    release.countDown();
    if (registry != null) registry.close();
    manager.clearAllowedDirectories();
    previous.forEach(manager::addAllowedDirectory);
  }

  @Test void theImmutableCopyCannotSpendTheReservationHeldByItsResumePrefix() throws Exception {
    long freeBytes = 100 + 2L * TEXT.length - 1;
    free.set(freeBytes);
    var result = store.sync(URL, 0, progress -> {}, CLOCK, address -> peer(false))
        .get(10, TimeUnit.SECONDS);
    assertEquals(List.of(), result.filesCopied(),
        "A downloaded prefix must not make its second immutable allocation without a reservation");
    assertEquals(List.of(), result.stopped(), result.toString());
    assertEquals(1, result.refusals().size(), result.toString());
    String reason = result.refusals().get(0).reason();
    assertTrue(reason.contains("free_bytes=" + freeBytes), reason);
    assertTrue(reason.contains("reserved_bytes=" + TEXT.length), reason);
    assertTrue(reason.contains("capture_reserve_bytes=100"), reason);
    assertTrue(reason.contains("requested_bytes=" + TEXT.length), reason);
    assertEquals(1, reads.get(), "The first allocation was admitted and completely downloaded");
    assertFalse(Files.exists(store.root().resolve(REMOTE)), "No immutable receipt bytes were placed");
    assertEquals(List.of(), SystemLogIndex.read(new StoreFiles(store.root(), security),
        store.root().resolve("robots").resolve(ROBOT)).files());
    try (var paths = Files.walk(store.root().resolve(".sync"))) {
      var prefixes = paths.filter(path -> path.getFileName().toString().endsWith(".part")).toList();
      assertEquals(1, prefixes.size(), "The verified resumable prefix remains journal-owned");
      assertArrayEquals(TEXT, Files.readAllBytes(prefixes.get(0)));
    }
    assertEquals(0, budget.snapshots().stream().mapToLong(DiskSpaceBudgets.Snapshot::reservedBytes).sum(),
        "The completed refusal releases reservations after all I/O unwinds");
  }

  @Test void aProgressCallbackFailureReleasesTheFinishedReadReservation() throws Exception {
    var callbacks = new AtomicInteger();
    var result = store.sync(URL, 0, progress -> {
      if (!progress.phase().equals("system_copied")) return;
      callbacks.incrementAndGet();
      assertEquals(REMOTE, progress.remotePath());
      assertEquals(TEXT.length, progress.bytesCopied());
      assertEquals(TEXT.length, progress.sizeBytes());
      assertEquals(TEXT.length, reservedBytes(),
          "The completed read still owns its prefix reservation when progress is delivered");
      throw new IllegalStateException("Synthetic progress callback stopped");
    }, CLOCK, address -> peer(false)).get(10, TimeUnit.SECONDS);
    assertEquals(1, callbacks.get(), "The failure must occur after a real completed payload read");
    assertEquals(1, reads.get());
    assertEquals(List.of(), result.filesCopied());
    assertEquals(List.of(), result.refusals(), result.toString());
    assertEquals(1, result.stopped().size(), result.toString());
    var stopped = result.stopped().get(0);
    assertEquals(REMOTE, stopped.remotePath());
    assertEquals(TEXT.length, stopped.bytesCopied());
    assertEquals("Synthetic progress callback stopped", stopped.reason());
    assertEquals(0, reservedBytes(),
        "The outer transfer owner must close when a callback throws outside step()");
    assertFalse(Files.exists(store.root().resolve(REMOTE)));
    try (var paths = Files.walk(store.root().resolve(".sync"))) {
      var prefixes = paths.filter(path -> path.getFileName().toString().endsWith(".part")).toList();
      assertEquals(1, prefixes.size(), "An observer failure must retain the resumable prefix");
      assertArrayEquals(TEXT, Files.readAllBytes(prefixes.get(0)));
    }
  }

  @Test void aStalledImmutableCopyLeavesCaptureAdmissionAndValuesIntact() throws Exception {
    var copying = new CountDownLatch(1);
    var allowCopy = new CountDownLatch(1);
    var copies = new AtomicInteger();
    var mover = new LogStore.Mover() {
      @Override LogStore.CopyAttributes copy(Path from, Path to, long bytes) throws IOException {
        copies.incrementAndGet();
        copying.countDown();
        try {
          if (!allowCopy.await(10, TimeUnit.SECONDS)) throw new IOException("Synthetic copy was not released");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
        return super.copy(from, to, bytes);
      }
    };
    var space = store.diskBudget();
    store.awaitImports();
    store.close();
    try (var copyingStore = new LogStore(store.root(), security, manager,
        StoreCatalog::read, mover, space)) {
      var syncing = copyingStore.sync(URL, 0, progress -> {}, CLOCK, address -> peer(false));
      var loop = new ManualScheduler();
      var placement = copyingStore.captures(CLOCK);
      var writer = writer(loop, placement);
      try {
        assertTrue(copying.await(5, TimeUnit.SECONDS),
            "System peer placement must use the injected immutable copier");
        assertEquals(2L * TEXT.length, reservedBytes(),
            "The held immutable copy and its downloaded prefix each reserve their own bytes");
        var topic = new Announce("/system-copy-admission", 1, "int", null, new JsonObject());
        writer.connected(java.net.URI.create("ws://127.0.0.1:0/nt/synthetic"),
            "networktables.first.wpi.edu");
        writer.timeSync(1_000_000, 0);
        writer.announce(topic);
        for (int i = 0; i < 3; i++)
          writer.value(topic, new ValueFrame(1, 1_000_100 + i * 20_000, 2, 17L + i), i * 20_000);
        long previousNanos = System.nanoTime();
        long until = previousNanos + TimeUnit.MILLISECONDS.toNanos(350);
        while (previousNanos < until) {
          Thread.sleep(2);
          long now = System.nanoTime();
          loop.advance(Math.max(1, (now - previousNanos) / 1_000));
          previousNanos = now;
        }
        assertNull(writer.admissionFailure(),
            "The immutable system copy must not consume capture's 200 ms admission deadline");
        assertTrue(writer.ready(), "Capture must be admitted while the immutable copy remains held");
        assertFalse(syncing.isDone());
        allowCopy.countDown();
        var result = syncing.get(10, TimeUnit.SECONDS);
        assertEquals(List.of(), result.stopped(), result.toString());
        assertEquals(List.of(), result.refusals(), result.toString());
        assertEquals(1, result.filesCopied().size());
        assertEquals(hash(TEXT), result.filesCopied().get(0).sha256());
        assertArrayEquals(TEXT, Files.readAllBytes(result.filesCopied().get(0).path()));
        assertEquals(1, copies.get());
        writer.disconnected();
        loop.drain();
        placement.completion().get(10, TimeUnit.SECONDS);
        var values = new ArrayList<Long>();
        var times = new ArrayList<Long>();
        for (var record : org.triplehelix.wpilogmcp.fixtures.CaptureRecords.robot(writer.session().path())) {
          values.add(record.getInteger());
          times.add(record.getTimestamp());
        }
        assertEquals(List.of(17L, 18L, 19L), values);
        assertEquals(List.of(1_000_100L, 1_020_100L, 1_040_100L), times);
        assertEquals(0, reservedBytes());
      } finally {
        allowCopy.countDown();
        syncing.get(10, TimeUnit.SECONDS);
        writer.shutdown();
        loop.drain();
        placement.completion().get(10, TimeUnit.SECONDS);
        loop.close();
      }
    } finally {
      allowCopy.countDown();
    }
  }

  @Test void anIndexChangeDuringDownloadCannotOverwriteTheNewerPublishedReceipt() throws Exception {
    var syncing = store.sync(URL, 0, progress -> {}, CLOCK, address -> peer(true));
    try {
      assertTrue(reading.await(10, TimeUnit.SECONDS), "The text payload must be held in the fake peer");
      byte[] newer = "newer local bytes\n".getBytes(StandardCharsets.UTF_8);
      var mutation = store.captureAsync(io -> {
        var destination = io.resolve(store.root(), REMOTE);
        Files.createDirectories(destination.getParent());
        Files.write(destination, newer);
        SystemLogIndex.put(io, store.root().resolve("robots").resolve(ROBOT),
            new SystemLogIndex.Entry(file(newer), null));
        return null;
      });
      assertDoesNotThrow(() -> mutation.get(2, TimeUnit.SECONDS),
          "A short publication turn cannot wait behind the peer's payload read");
      release.countDown();
      var result = syncing.get(10, TimeUnit.SECONDS);
      assertEquals(List.of(), result.filesCopied(), "Changed index facts must prevent stale placement");
      assertEquals(List.of(), result.stopped(), result.toString());
      assertTrue(result.refusals().stream().anyMatch(refusal ->
          refusal.reason().toLowerCase(java.util.Locale.ROOT).contains("changed")), result.toString());
      assertArrayEquals(newer, Files.readAllBytes(store.root().resolve(REMOTE)));
      var entries = SystemLogIndex.read(new StoreFiles(store.root(), security),
          store.root().resolve("robots").resolve(ROBOT)).files();
      assertEquals(1, entries.size());
      assertEquals(hash(newer), entries.get(0).file().sha256());
    } finally {
      release.countDown();
      syncing.get(10, TimeUnit.SECONDS);
    }
  }

  private StoreSync.Peer peer(boolean hold) throws IOException {
    var remote = new RemoteFiles() {
      @Override public List<File> list() { return List.of(); }
      @Override public byte[] read(String name, long offset, int count) throws IOException {
        assertEquals(REMOTE, name);
        reads.incrementAndGet();
        if (hold) {
          reading.countDown();
          try {
            if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("Synthetic text was not released");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
          }
        }
        return Arrays.copyOfRange(TEXT, Math.toIntExact(offset),
            Math.toIntExact(Math.min(TEXT.length, offset + count)));
      }
      @Override public Optional<String> prefixHash(String name, long length) {
        return Optional.of(hash(Arrays.copyOf(TEXT, Math.toIntExact(length))));
      }
    };
    var robot = new Robot(ROBOT, ROBOT, null, null, "stated");
    var sessions = new StoreDoor.Sessions(List.of(), List.of(),
        List.of(new StoreDoor.SharedSystem(ROBOT, List.of(new SystemLogIndex.Entry(file(TEXT), null)))));
    return new StoreSync.Peer(URL, new StoreDoor.Description(
        "11111111-1111-4111-8111-111111111111", StoreManifest.FORMAT_VERSION, "synthetic", false),
        List.of(robot), sessions, remote);
  }

  private static SystemLogState.File file(byte[] bytes) {
    return new SystemLogState.File(SystemLogState.Location.STORE, REMOTE, "syslog", "text",
        hash(bytes), bytes.length,
        new Provenance("pulled", "/var/log/messages", "messages", CLOCK.instant().toString(), false, ROBOT), null);
  }

  private long reservedBytes() {
    return budget.snapshots().stream().mapToLong(DiskSpaceBudgets.Snapshot::reservedBytes).sum();
  }

  /** Exercise the existing admission deadline without making the recorder constructor public. */
  private static CaptureWriter writer(ManualScheduler loop, CaptureStore placement) throws Exception {
    var constructor = CaptureWriter.class.getDeclaredConstructor(Clock.class, ClientScheduler.class,
        CapturePolicy.class, CaptureWriter.Observer.class, long.class, CaptureWriter.OutputFactory.class,
        CaptureWriter.AdmissionLimits.class);
    constructor.setAccessible(true);
    return constructor.newInstance(CLOCK, loop, CapturePolicy.ALL, placement,
        CaptureWriter.DEFAULT_MAX_FILE_BYTES, (CaptureWriter.OutputFactory) WpilogOutput::new,
        new CaptureWriter.AdmissionLimits(1 << 20, 1_000, 200_000, 256, 2_000));
  }

  private static String hash(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }
}
