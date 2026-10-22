/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.pull.FakeRobot;
import org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.sync.PullManifest;

/** The durable resume prefix and its immutable publication are two physical allocations. */
class SystemPullPlacementTest {
  @TempDir Path temporary;
  private static final long RESERVE = 100;
  private static final String REMOTE = "/var/log/program.log";
  private static final byte[] PAYLOAD = "0123456789abcdef\n".getBytes(StandardCharsets.UTF_8);

  @Test void secondCopyIsRefusedBeforeCreatingPayloadWhenItWouldSpendTheCaptureReserve()
      throws Exception {
    try (var fixture = new Fixture()) {
      assertEquals(17, PAYLOAD.length, "Sixteen ASCII characters and one LF occupy seventeen bytes");
      try (var incoming = fixture.local.reserve(REMOTE, PAYLOAD.length)) {
        fixture.free.set(133); // 100 reserve + 17 held incoming + 17 requested - 1.
        var failure = assertThrows(IOException.class, () -> fixture.local.verified(fixture.entry),
            "The immutable copy needs its own reservation before it writes a payload byte");
        var refused = assertInstanceOf(DiskSpaceBudgets.Refused.class, failure);
        assertEquals("CAPTURE_DISK_RESERVE", refused.code());
        assertEquals(133, refused.snapshot().freeBytes());
        assertEquals(17, refused.snapshot().reservedBytes());
        assertEquals(100, refused.snapshot().captureReserveBytes());
        assertEquals(17, refused.requestedBytes());
        assertTrue(fixture.local.state().files().isEmpty());
        try (var files = Files.walk(fixture.root)) {
          assertEquals(0, files.filter(path -> path.getFileName().toString().equals("program.log")).count(),
              "Refused admission must not create an immutable destination payload");
        }
        assertArrayEquals(PAYLOAD, Files.readAllBytes(fixture.prefix()),
            "The independently verified resume prefix remains available for a later attempt");
      }
      assertEquals(0, fixture.store.diskBudget().snapshot().reservedBytes());
    }
  }

  @Test void changedSelectedSessionIsRefusedRatherThanPublishingIntoItsReplacement() throws Exception {
    try (var fixture = new Fixture()) {
      var manifest = fixture.capture.resolveSibling("session.json");
      var changed = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
      changed.addProperty("id", "replacement");
      Files.writeString(manifest, changed.toString());
      var failure = assertThrows(IOException.class, () -> fixture.local.verified(fixture.entry),
          "The session selected by beginPass must still own the placement");
      assertTrue(failure.getMessage().contains("session"), failure.getMessage());
      assertTrue(fixture.local.state().files().isEmpty());
      assertArrayEquals(PAYLOAD, Files.readAllBytes(fixture.prefix()));
    }
  }

  @Test void aPrefixThatGrewBeyondTheVerifiedEntryCannotBecomeTheImmutableSnapshot() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.local.append(fixture.entry.localName(), PAYLOAD.length, new byte[] {'x'});
      var failure = assertThrows(IOException.class, () -> fixture.local.verified(fixture.entry),
          "A publication must reject bytes outside its verified seventeen-byte prefix");
      assertTrue(failure.getMessage().contains("changed")
          || failure.getMessage().contains("size"), failure.getMessage());
      assertTrue(fixture.local.state().files().isEmpty());
      assertEquals(18, Files.size(fixture.prefix()), "The newer durable prefix must not be deleted");
    }
  }

  @Test void aStalledImmutableCopyDoesNotOccupyCaptureAdmission() throws Exception {
    try (var fixture = new Fixture()) {
      var copying = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      fixture.local.copier(new LogStore.Mover() {
        @Override LogStore.CopyAttributes copy(Path from, Path to, long bytes) throws IOException {
          copying.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Copy barrier expired");
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(failure);
          }
          return super.copy(from, to, bytes);
        }
      });
      var executor = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "synthetic-system-copy"); thread.setDaemon(true); return thread;
      });
      var loop = new ManualScheduler();
      var placement = fixture.store.captures(SystemSessionFixture.WALL);
      var writer = writer(loop, placement);
      try {
        var transfer = executor.submit(() -> fixture.local.verified(fixture.entry));
        assertTrue(copying.await(5, TimeUnit.SECONDS));
        assertEquals(17, fixture.store.diskBudget().snapshot().reservedBytes(),
            "The immutable copy reserves its seventeen bytes before the copier can block");
        var topic = new Announce("/copy-admission", 1, "int", null, new JsonObject());
        writer.connected(java.net.URI.create("ws://localhost:0/nt/synthetic"), "networktables.first.wpi.edu");
        writer.timeSync(1_000_000, 0);
        writer.announce(topic);
        for (int i = 0; i < 3; i++)
          writer.value(topic, new ValueFrame(1, 1_000_100 + i * 20_000, 2, 17L + i), i * 20_000);
        long previous = System.nanoTime(), until = previous + TimeUnit.MILLISECONDS.toNanos(350);
        while (previous < until) {
          Thread.sleep(2);
          long now = System.nanoTime();
          loop.advance(Math.max(1, (now - previous) / 1_000));
          previous = now;
        }
        assertNull(writer.admissionFailure(),
            "Immutable copying must not consume the writer's 200 ms admission deadline");
        assertTrue(writer.ready(), "Capture must admit while the copy remains stalled");
        release.countDown();
        assertEquals(fixture.entry.localName(), transfer.get(5, TimeUnit.SECONDS));
        writer.disconnected(); loop.drain(); placement.completion().get(5, TimeUnit.SECONDS);
        var values = new ArrayList<Long>();
        var times = new ArrayList<Long>();
        for (var record : org.triplehelix.wpilogmcp.fixtures.CaptureRecords.robot(writer.session().path())) {
          times.add(record.getTimestamp()); values.add(record.getInteger());
        }
        assertEquals(List.of(17L, 18L, 19L), values);
        assertEquals(List.of(1_000_100L, 1_020_100L, 1_040_100L), times);
        assertEquals(0, fixture.store.diskBudget().snapshot().reservedBytes());
      } finally {
        release.countDown(); executor.shutdownNow();
        writer.shutdown(); loop.drain(); placement.completion().get(5, TimeUnit.SECONDS); loop.close();
      }
    }
  }

  @Test void changedSourceDuringCopyRefusesPublicationAndRemovesOnlyItsDisposableStage() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.local.copier(new LogStore.Mover() {
        @Override LogStore.CopyAttributes copy(Path from, Path to, long bytes) throws IOException {
          var attributes = super.copy(from, to, bytes);
          Files.write(from, new byte[] {'x'}, java.nio.file.StandardOpenOption.APPEND);
          return attributes;
        }
      });
      var failure = assertThrows(IOException.class, () -> fixture.local.verified(fixture.entry));
      assertTrue(failure.getMessage().contains("changed"), failure.getMessage());
      assertEquals(18, Files.size(fixture.prefix()));
      assertTrue(fixture.local.state().files().isEmpty());
      assertNoTemporaryPayload(fixture);
      assertEquals(0, fixture.store.diskBudget().snapshot().reservedBytes());
    }
  }

  @Test void changedSessionDuringCopyIsRejectedByThePublicationRecheck() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.local.copier(new LogStore.Mover() {
        @Override LogStore.CopyAttributes copy(Path from, Path to, long bytes) throws IOException {
          var attributes = super.copy(from, to, bytes);
          var manifest = fixture.capture.resolveSibling("session.json");
          var changed = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
          changed.addProperty("id", "replacement");
          Files.writeString(manifest, changed.toString());
          return attributes;
        }
      });
      var failure = assertThrows(IOException.class, () -> fixture.local.verified(fixture.entry));
      assertTrue(failure.getMessage().contains("session"), failure.getMessage());
      assertArrayEquals(PAYLOAD, Files.readAllBytes(fixture.prefix()));
      assertTrue(fixture.local.state().files().isEmpty());
      assertNoTemporaryPayload(fixture);
      assertEquals(0, fixture.store.diskBudget().snapshot().reservedBytes());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"source", "stage"})
  void sourceOrStageChangedAfterCopyWhilePublicationIsQueuedIsRefused(String changed) throws Exception {
    try (var fixture = new Fixture()) {
      var blocked = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var barrier = new AtomicReference<CompletableFuture<Void>>();
      var worker = new AtomicReference<Thread>();
      var stage = new AtomicReference<Path>();
      fixture.local.copier(new LogStore.Mover() {
        @Override LogStore.CopyAttributes copy(Path from, Path to, long bytes) throws IOException {
          var attributes = super.copy(from, to, bytes);
          stage.set(to);
          barrier.set(fixture.store.<Void>captureAsync(io -> {
            blocked.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Publication barrier expired"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
            return null;
          }));
          try { if (!blocked.await(5, TimeUnit.SECONDS)) throw new IOException("Publication barrier did not start"); }
          catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
          return attributes;
        }
      });
      var executor = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "synthetic-system-publication"); thread.setDaemon(true); return thread;
      });
      try {
        var transfer = executor.submit(() -> {
          worker.set(Thread.currentThread());
          return fixture.local.verified(fixture.entry);
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        awaitPublicationWaiter(worker);
        var altered = changed.equals("source") ? fixture.prefix() : stage.get();
        Files.write(altered, new byte[] {'x'}, java.nio.file.StandardOpenOption.APPEND);
        assertEquals(18, Files.size(altered), "The queued mutation adds one byte to the verified seventeen");
        release.countDown();
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> transfer.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("changed"), failure.getCause().getMessage());
        barrier.get().get(5, TimeUnit.SECONDS);
        assertEquals(changed.equals("source") ? 18 : 17, Files.size(fixture.prefix()));
        assertTrue(fixture.local.state().files().isEmpty());
        assertNoTemporaryPayload(fixture);
        assertEquals(0, fixture.store.diskBudget().snapshot().reservedBytes());
      } finally {
        release.countDown(); executor.shutdownNow();
      }
    }
  }

  private static void awaitPublicationWaiter(AtomicReference<Thread> worker) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var thread = worker.get();
      if (thread != null && thread.getState() == Thread.State.WAITING
          && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
              frame.getClassName().equals(CopyLeaseGroup.class.getName()) && frame.getMethodName().equals("await"))) return;
      Thread.sleep(1);
    }
    fail("The completed copy must wait for final publication behind the owner barrier");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"after_copy", "after_rename"})
  void nextPlacementRecoversAProcessesDisposableCopyWithoutDeletingItsResumePrefix(String phase) throws Exception {
    try (var fixture = new Fixture()) {
      var ready = temporary.resolve("copied.txt");
      var output = temporary.resolve("copy-process.log");
      var process = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
          "-cp", System.getProperty("java.class.path"), AbandonedCopy.class.getName(),
          fixture.root.toString(), ready.toString(), phase).redirectErrorStream(true).redirectOutput(output.toFile()).start();
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(ready), () -> "Child never copied its stage: " + readOutput(output));
        var receipt = JsonParser.parseString(Files.readString(ready)).getAsJsonObject();
        var payload = Path.of(receipt.get("payload").getAsString());
        var abandonedStage = Path.of(receipt.get("stage").getAsString());
        assertTrue(payload.startsWith(fixture.root));
        assertTrue(abandonedStage.startsWith(fixture.root));
        assertArrayEquals(PAYLOAD, Files.readAllBytes(payload));
        assertTrue(Files.exists(abandonedStage.resolve(".owner.lock")));
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertArrayEquals(PAYLOAD, Files.readAllBytes(fixture.prefix()));
        assertEquals(fixture.entry.localName(), fixture.local.verified(fixture.entry));
        assertFalse(Files.exists(abandonedStage), "The next placement recovers only the abandoned disposable stage");
        assertArrayEquals(PAYLOAD, Files.readAllBytes(fixture.prefix()));
        var receipts = fixture.local.state().files();
        assertEquals(1, receipts.size(), "The retry publishes exactly one immutable snapshot receipt");
        assertArrayEquals(PAYLOAD, Files.readAllBytes(fixture.capture.getParent().resolve(receipts.get(0).path())));
        try (var paths = Files.walk(fixture.root)) {
          assertEquals(0, paths.filter(path -> path.getFileName().toString().equals(".owner.lock")).count());
        }
        assertEquals(0, fixture.store.diskBudget().snapshot().reservedBytes());
      } finally {
        process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private static String readOutput(Path path) {
    try { return Files.readString(path); }
    catch (IOException failure) { return failure.toString(); }
  }

  /** A killed process releases its OS lock without running the stage's ordinary retirement. */
  public static final class AbandonedCopy {
    public static void main(String[] args) throws Exception {
      var root = Path.of(args[0]);
      var ready = Path.of(args[1]);
      String phase = args[2];
      var security = new SecurityValidator(); security.addAllowedDirectory(root);
      try (var space = new DiskSpaceBudgets(filesystem -> 1_000_000);
           var registry = new StoreRegistry(security, LogManager.getInstance(), StoreCatalog::discover, space)) {
        var store = registry.store(root);
        store.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(100, 0, 0));
        var local = store.systemPulls(FakeRobot.device(SystemSessionFixture.SERIAL, "SHA256:fixture"),
            SystemSessionFixture.WALL);
        if (!local.beginPass()) throw new IOException("Missing synthetic capture");
        local.sources(Map.of(REMOTE, "program"));
        local.copier(new LogStore.Mover() {
          private Path stage;
          @Override LogStore.CopyAttributes copy(Path from, Path to, long bytes) throws IOException {
            var attributes = super.copy(from, to, bytes);
            stage = to.getParent();
            if (phase.equals("after_copy")) copied(to);
            return attributes;
          }
          @Override void move(Path from, Path to, java.nio.file.CopyOption... options) throws IOException {
            super.move(from, to, options);
            if (phase.equals("after_rename")) copied(to);
          }
          private void copied(Path payload) throws IOException {
            var receipt = new JsonObject();
            receipt.addProperty("payload", payload.toString());
            receipt.addProperty("stage", stage.toString());
            var pending = ready.resolveSibling(ready.getFileName() + ".pending");
            Files.writeString(pending, receipt.toString());
            Files.move(pending, ready, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            try { Thread.sleep(TimeUnit.MINUTES.toMillis(1)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
          }
        });
        local.verified(local.manifest().files().get(0));
      }
    }
  }

  private static void assertNoTemporaryPayload(Fixture fixture) throws IOException {
    try (var paths = Files.walk(fixture.root)) {
      assertEquals(0, paths.filter(path -> Files.isRegularFile(path)
          && (path.getFileName().toString().equals("program.log")
          || path.getFileName().toString().equals(".owner.lock"))).count(),
          "A refused copy leaves neither a destination payload nor an owned placement stage");
    }
  }

  /** The package-private constructor is the existing 200 ms admission seam, not a new clock. */
  private static CaptureWriter writer(ManualScheduler loop, CaptureStore placement) throws Exception {
    var constructor = CaptureWriter.class.getDeclaredConstructor(Clock.class, ClientScheduler.class,
        CapturePolicy.class, CaptureWriter.Observer.class, long.class, CaptureWriter.OutputFactory.class,
        CaptureWriter.AdmissionLimits.class);
    constructor.setAccessible(true);
    return constructor.newInstance(SystemSessionFixture.WALL, loop, CapturePolicy.ALL, placement,
        CaptureWriter.DEFAULT_MAX_FILE_BYTES, (CaptureWriter.OutputFactory) WpilogOutput::new,
        new CaptureWriter.AdmissionLimits(1 << 20, 1_000, 200_000, 256, 2_000));
  }

  private final class Fixture implements AutoCloseable {
    final Path root, capture;
    final AtomicLong free = new AtomicLong(1_000_000);
    final DiskSpaceBudgets space;
    final StoreRegistry registry;
    final LogStore store;
    final SystemPullStore local;
    final PullManifest.Entry entry;

    Fixture() throws Exception {
      root = Files.createTempDirectory(temporary, "store-").toRealPath();
      capture = SystemSessionFixture.create(root, "first", true, 42);
      var security = new SecurityValidator();
      security.addAllowedDirectory(temporary);
      space = new DiskSpaceBudgets(filesystem -> free.get());
      registry = new StoreRegistry(security, LogManager.getInstance(), StoreCatalog::discover, space);
      store = registry.store(root);
      store.diskBudget().configureCapture(new DiskSpaceBudgets.Policy(RESERVE, 0, 0));
      local = store.systemPulls(FakeRobot.device(SystemSessionFixture.SERIAL, "SHA256:fixture"),
          SystemSessionFixture.WALL);
      assertTrue(local.beginPass());
      local.sources(Map.of(REMOTE, "program"));
      local.manifest();
      String name = local.create(REMOTE);
      local.append(name, 0, PAYLOAD);
      entry = new PullManifest.Entry(REMOTE, PAYLOAD.length, 0, PAYLOAD.length, false, name, 0, null);
      local.save(new PullManifest(1, SystemSessionFixture.SERIAL, List.of(entry), List.of()));
    }

    Path prefix() { return root.resolve(entry.localName()); }

    @Override public void close() {
      registry.close();
      space.close();
    }
  }
}
