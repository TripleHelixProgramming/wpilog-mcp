/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

/**
 * An in-memory peer reaches the real sync adapter without opening a socket. A held second read
 * proves that capture and competing imports progress while an actual payload remains unfinished.
 */
public final class StoreSyncHardeningFixture implements AutoCloseable {
  public static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
  public static final long RESERVE_BYTES = 100;
  private static final String PEER_URL = "http://synthetic.invalid";
  private final LogManager manager = LogManager.getInstance();
  private final Set<Path> previous = manager.getAllowedDirectories();
  private final SecurityValidator security = new SecurityValidator();
  private final AtomicLong free = new AtomicLong(10_000_000_000L);
  private final DiskSpaceBudgets space = new DiskSpaceBudgets(filesystem -> free.get());
  private final StoreRegistry stores;
  private final LogStore extraTarget;
  private final CountDownLatch reading = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final AtomicBoolean held = new AtomicBoolean();
  private final AtomicInteger payloadReads = new AtomicInteger();
  private final AtomicLong deliveredBytes = new AtomicLong();
  private final AtomicLong provedPrefixBytes = new AtomicLong(-1);
  private final AtomicInteger opened = new AtomicInteger();
  private final List<Long> proofs = new java.util.concurrent.CopyOnWriteArrayList<>();
  private final List<PayloadRead> reads = new java.util.concurrent.CopyOnWriteArrayList<>();
  record PayloadRead(long offset, long provedPrefixBytes) {}
  private Runnable beforeOpen = () -> {};
  private final StoreSync.Peer peer;
  private final byte[] bytes;
  private CompletableFuture<StoreSync.Result> syncing;
  public final LogStore target;
  public final Path source;
  public final String sha256;

  public StoreSyncHardeningFixture(Path directory, boolean holdSecondRead) throws Exception {
    this(directory, holdSecondRead, null, false);
  }

  StoreSyncHardeningFixture(Path directory, boolean holdSecondRead, LogStore.Mover mover) throws Exception {
    this(directory, holdSecondRead, mover, false);
  }

  /** Reopen only our synthetic fixture; a process-death test must keep its journal and origin. */
  static StoreSyncHardeningFixture reopen(Path directory) throws Exception {
    return new StoreSyncHardeningFixture(directory, false, null, true);
  }

  private StoreSyncHardeningFixture(Path directory, boolean holdSecondRead, LogStore.Mover mover,
      boolean reopen) throws Exception {
    directory = directory.toRealPath();
    manager.addAllowedDirectory(directory);
    security.addAllowedDirectory(directory);
    stores = new StoreRegistry(security, manager, StoreCatalog::discover, space);
    var configuredTarget = stores.store(directory.resolve("target"));
    extraTarget = mover == null ? null : new LogStore(configuredTarget.root(), security, manager,
        StoreCatalog::read, mover, configuredTarget.diskBudget());
    target = extraTarget == null ? configuredTarget : extraTarget;
    var origin = stores.store(directory.resolve("origin"));
    for (var store : List.of(target, origin)) {
      store.capture(io -> null);
      store.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(RESERVE_BYTES, 20, 30));
    }
    source = directory.resolve("synthetic-peer.wpilog");
    if (!reopen) try (var writer = new WpilogWriter(source, "synthetic sync ownership")) {
      int clock = writer.start("systemTime", "int64", "", 0);
      int serial = writer.start("/SystemStats/SerialNumber", "string", "", 0);
      int value = writer.start("/Value", "double", "", 0);
      writer.append(clock, 0, WpilogWriter.encodeInt64(1_767_225_600_000_000L));
      writer.append(serial, 0, WpilogWriter.encodeString("SYNTHETIC-SYNC"));
      for (int i = 0; i < 12_000; i++) {
        writer.append(value, 20_000L * i, WpilogWriter.encodeDouble(3.0 * i + 7));
      }
    }
    bytes = Files.readAllBytes(source);
    assertTrue(bytes.length > 131_072, "The fixture must require several 65,536-byte payload blocks");
    sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    if (!reopen) {
      var imported = origin.importPaths(new LogStore.Request(List.of(source), false, null), progress -> {})
          .get(10, TimeUnit.SECONDS);
      assertEquals("imported", imported.files().get(0).status(), imported.toString());
    } else {
      var existing = StoreCatalog.readManaged(origin.root(), security).files();
      assertEquals(1, existing.size(), "Only the original synthetic log belongs to this fixture");
      assertEquals(sha256, existing.get(0).file().sha256());
      assertArrayEquals(bytes, Files.readAllBytes(existing.get(0).path()));
    }
    var door = new StoreDoor(() -> Set.of(origin.root()));
    var selected = door.select(null);
    var sessions = StoreDoor.sessions(selected, null, null, null);
    assertEquals(1, sessions.sessions().size());
    var exported = sessions.sessions().get(0);
    var file = exported.manifest().files().get(0);
    assertEquals(sha256, file.sha256(), "The remote catalog must describe independently hashed bytes");
    String remoteName = exported.path() + "/" + file.path();
    var remote = new RemoteFiles() {
      @Override public List<File> list() {
        return List.of(new File(remoteName, bytes.length, CLOCK.millis()));
      }
      @Override public byte[] read(String name, long offset, int count) throws IOException {
        if (!remoteName.equals(name)) throw new IOException("Unknown synthetic payload");
        payloadReads.incrementAndGet();
        reads.add(new PayloadRead(offset, provedPrefixBytes.get()));
        if (holdSecondRead && offset >= 65_536 && held.compareAndSet(false, true)) {
          reading.countDown();
          try {
            if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("Synthetic payload was not released");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Synthetic payload interrupted", interrupted);
          }
        }
        int end = Math.toIntExact(Math.min(bytes.length, offset + count));
        byte[] result = Arrays.copyOfRange(bytes, Math.toIntExact(offset), end);
        deliveredBytes.addAndGet(result.length);
        return result;
      }
      @Override public Optional<String> prefixHash(String name, long length) throws IOException {
        if (!remoteName.equals(name) || length < 0 || length > bytes.length) {
          throw new IOException("Invalid synthetic prefix");
        }
        try {
          String proof = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
              .digest(Arrays.copyOf(bytes, Math.toIntExact(length))));
          provedPrefixBytes.set(length); proofs.add(length);
          return Optional.of(proof);
        } catch (java.security.NoSuchAlgorithmException impossible) {
          throw new AssertionError(impossible);
        }
      }
    };
    peer = new StoreSync.Peer(PEER_URL, selected.description(),
        StoreCatalog.readManaged(origin.root(), security).robots().stream()
            .map(StoreCatalog.RobotDirectory::robot).toList(), sessions, remote);
  }

  public CompletableFuture<StoreSync.Result> sync() {
    syncing = anotherSync();
    return syncing;
  }

  public CompletableFuture<StoreSync.Result> anotherSync() {
    return anotherSync((boundary, staged, destination, manifest) -> {});
  }

  CompletableFuture<StoreSync.Result> sync(StoreSync.Probe probe) {
    syncing = anotherSync(probe);
    return syncing;
  }

  CompletableFuture<StoreSync.Result> anotherSync(StoreSync.Probe probe) {
    return target.sync(PEER_URL, 0, progress -> {}, CLOCK, url -> {
      beforeOpen.run(); opened.incrementAndGet(); return peer;
    }, probe);
  }

  void beforeOpen(Runnable callback) { beforeOpen = callback; }
  int peerOpens() { return opened.get(); }
  long deliveredBytes() { return deliveredBytes.get(); }
  List<Long> prefixProofs() { return List.copyOf(proofs); }
  List<PayloadRead> readTrace() { return List.copyOf(reads); }

  public void awaitPayload() throws InterruptedException {
    assertTrue(reading.await(10, TimeUnit.SECONDS), "Sync must reach the second real payload read");
    assertEquals(65_536, deliveredBytes.get(), "Exactly the first block has been delivered");
  }

  public void releasePayload() { release.countDown(); }
  public long sizeBytes() { return bytes.length; }
  public int payloadReads() { return payloadReads.get(); }
  public void freeBytes(long value) { free.set(value); }
  public long reservedBytes() {
    return stores.diskSnapshots().stream().mapToLong(DiskSpaceBudgets.Snapshot::reservedBytes).sum();
  }
  public StoreCatalog.Snapshot targetCatalog() throws IOException {
    return StoreCatalog.readManaged(target.root(), security);
  }
  /** A second owner represents another process, with a separate staging lane and the same lock. */
  public LogStore competingOwner() throws IOException {
    return new LogStore(target.root(), security, manager, StoreCatalog::read,
        new LogStore.Mover(), target.diskBudget());
  }

  @Override public void close() throws Exception {
    releasePayload();
    try {
      if (syncing != null && !syncing.isCancelled()) syncing.get(10, TimeUnit.SECONDS);
      else target.awaitImports();
    } finally {
      if (extraTarget != null) extraTarget.close();
      stores.close();
      manager.clearAllowedDirectories();
      previous.forEach(manager::addAllowedDirectory);
    }
  }
}
