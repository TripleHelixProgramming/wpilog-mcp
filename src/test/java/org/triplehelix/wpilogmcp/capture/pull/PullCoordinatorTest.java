/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.store.*;
import org.triplehelix.wpilogmcp.sync.FileTransfer;

class PullCoordinatorTest {
  @TempDir Path temp;
  StoreRegistry registry; LogStore store; Set<Path> allowed;
  final ManualScheduler worker = new ManualScheduler(); final PullGate gate = new PullGate(worker::nowUs, 5_000_000);
  static final Clock WALL = Clock.fixed(Instant.parse("2026-03-07T14:22:33Z"), ZoneOffset.UTC);
  static final PullConfig CONFIG = new PullConfig(true, PullConfig.DISABLED.directories(), 5_000_000, 1_000_000, PullConfig.DISABLED.ssh());
  @BeforeEach void setup() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    var security = new SecurityValidator(); security.addAllowedDirectory(temp); registry = new StoreRegistry(security); store = registry.store(temp.resolve("store"));
  }
  @AfterEach void cleanup() throws Exception {
    registry.close(); var manager = LogManager.getInstance(); manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
  }
  FakeRobot remote() throws Exception {
    var path = temp.resolve("synthetic.wpilog");
    try (var writer = new WpilogWriter(path, "synthetic coordinator")) {
      int id = writer.start("/raw", "raw", "", 0); writer.append(id, 1_000_000, new byte[150_000]);
    }
    var robot = new FakeRobot(); robot.files.put("/u/logs/fixture.wpilog", Files.readAllBytes(path)); return robot;
  }
  void settled() { gate.connected("127.0.0.1"); gate.control(0L); worker.advance(5_000_000); }

  @FunctionalInterface interface LoggedAction { void run() throws Exception; }
  private List<String> logLines(LoggedAction action) throws Exception {
    var original = System.err; var bytes = new java.io.ByteArrayOutputStream();
    try (var output = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8)) {
      System.setErr(output); action.run();
    } finally { System.setErr(original); }
    var lines = bytes.toString(java.nio.charset.StandardCharsets.UTF_8).lines().filter(line -> line.contains("Robot pull")).toList();
    lines.forEach(line -> System.out.println("S8 captured: " + line));
    return lines;
  }

  @Test void pausedLinesNameChangingGateReasonsWithoutRepeatingTheSameState() throws Exception {
    var lines = logLines(() -> {
      try (var pull = new PullCoordinator(CONFIG, gate, store, WALL, ignored -> {}, worker,
          (address, config, pin) -> { throw new AssertionError("Closed gates must not connect"); })) {
        pull.step(); pull.step();
        gate.connected("127.0.0.1"); pull.step(); pull.step();
        gate.control(0L); pull.step(); pull.step();
        gate.control(1L); pull.step();
      }
    });
    assertEquals(4, lines.size(), lines.toString());
    assertTrue(lines.get(0).contains("PAUSED: NT4 is disconnected"), lines.toString());
    assertTrue(lines.get(1).contains("PAUSED: Robot is enabled or its enabled state is unknown"), lines.toString());
    assertTrue(lines.get(2).contains("PAUSED: Waiting for disabled-state settling"), lines.toString());
    assertTrue(lines.get(3).contains("PAUSED: Robot is enabled or its enabled state is unknown"), lines.toString());
    assertTrue(lines.stream().allMatch(line -> line.contains("INFO") && !line.contains("null")), lines.toString());
  }

  @Test void stoppedPullNamesShutdownInsteadOfAnAbsentRemoteFile() throws Exception {
    settled();
    var lines = logLines(() -> {
      var pull = new PullCoordinator(CONFIG, gate, store, WALL, ignored -> {}, worker,
          (address, config, pin) -> { throw new AssertionError("A stopped pull must not connect"); });
      pull.closeAsync().get(2, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals("Pull coordinator is stopping", pull.step().detail());
    });
    assertTrue(lines.get(0).contains("PAUSED: Pull coordinator is stopping"));
  }

  @Test void staleEpisodeNamesTheConnectionChangeEvenWhenTheNewGateIsOpen() throws Exception {
    var robot = remote(); settled();
    var lines = logLines(() -> {
      try (var pull = new PullCoordinator(CONFIG, gate, store, WALL, ignored -> {}, worker, (address, config, pin) -> {
        gate.connected(address); gate.control(0L); worker.elapse(5_000_000); return robot;
      })) {
        assertEquals("Connection changed; waiting for a current pull session", pull.step().detail());
      }
    });
    assertTrue(robot.closed); assertTrue(gate.open());
    assertTrue(lines.get(0).contains("PAUSED: Connection changed; waiting for a current pull session"));
  }

  @Test void anIoFailureAfterTheGateDisconnectsIsInfoAndNamesTheGateReason() throws Exception {
    settled();
    var lines = logLines(() -> {
      try (var pull = new PullCoordinator(CONFIG, gate, store, WALL, ignored -> {}, worker, (address, config, pin) -> {
        gate.disconnected(); throw new IOException("Pipe closed");
      })) { pull.start(); worker.drain(); }
    });
    assertEquals(1, lines.size(), lines.toString());
    assertTrue(lines.get(0).contains("INFO"), lines.toString());
    assertTrue(lines.get(0).contains("NT4 is disconnected") && lines.get(0).contains("Pipe closed"), lines.toString());
    assertFalse(lines.get(0).contains("WARN"), lines.toString());
  }

  @Test void powerOffDuringTheSecondBlockProducesAnInfoRetry() throws Exception {
    var robot = remote(); settled();
    RobotRemote interrupted = new RobotRemote() {
      @Override public org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity() { return robot.identity(); }
      @Override public List<File> list() { return robot.list(); }
      @Override public byte[] read(String name, long offset, int count) throws IOException {
        if (offset == 0) return robot.read(name, offset, count);
        gate.disconnected(); throw new IOException("Pipe closed");
      }
      @Override public java.util.Optional<String> prefixHash(String name, long length) throws IOException { return robot.prefixHash(name, length); }
      @Override public void close() { robot.close(); }
    };
    var lines = logLines(() -> {
      try (var pull = new PullCoordinator(CONFIG, gate, store, WALL, ignored -> {}, worker, (address, config, pin) -> interrupted)) {
        pull.start(); worker.drain(); worker.advance(100_000);
      }
    });
    assertEquals(1, robot.reads.size()); assertTrue(robot.closed);
    var retry = lines.stream().filter(line -> line.contains("Pipe closed")).toList();
    assertEquals(1, retry.size(), lines.toString());
    assertTrue(retry.get(0).contains("INFO") && retry.get(0).contains("NT4 is disconnected"), lines.toString());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"open", "enabled", "settling", "unexpected"})
  void unexpectedFailuresStillWarnWhenTheGateDoesNotExplainADisconnect(String state) throws Exception {
    settled();
    var lines = logLines(() -> {
      try (var pull = new PullCoordinator(CONFIG, gate, store, WALL, ignored -> {}, worker, (address, config, pin) -> {
        if (state.equals("enabled")) gate.control(1L);
        if (state.equals("settling")) { gate.control(1L); gate.control(0L); }
        if (state.equals("unexpected")) { gate.disconnected(); throw new IllegalStateException("synthetic implementation failure"); }
        throw new IOException("synthetic I/O failure");
      })) { pull.start(); worker.drain(); }
    });
    assertEquals(1, lines.size(), lines.toString());
    assertTrue(lines.get(0).contains("WARN") && lines.get(0).contains("Robot pull will retry"), lines.toString());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void localFilesystemFailuresStillWarnWhenNtDisconnects(boolean wrapped) throws Exception {
    settled();
    var lines = logLines(() -> {
      try (var pull = new PullCoordinator(CONFIG, gate, store, WALL, ignored -> {}, worker, (address, config, pin) -> {
        gate.disconnected();
        var denied = new java.nio.file.AccessDeniedException("synthetic staged file");
        throw wrapped ? new IOException("Checkpoint failed", denied) : denied;
      })) { pull.start(); worker.drain(); }
    });
    assertEquals(1, lines.size(), lines.toString());
    assertTrue(lines.get(0).contains("WARN"), lines.toString());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"disconnected", "connected", "local-failure"})
  void persistentHandleCloseLogsItsActualFailurePhase(String state) throws Exception {
    var robot = remote(); settled(); var closes = new AtomicInteger(); var opens = new AtomicInteger();
    RobotRemote persistent = new RobotRemote() {
      @Override public org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity() { return robot.identity(); }
      @Override public List<File> list() { return robot.list(); }
      @Override public byte[] read(String name, long offset, int count) throws IOException { return robot.read(name, offset, count); }
      @Override public java.util.Optional<String> prefixHash(String name, long length) throws IOException { return robot.prefixHash(name, length); }
      @Override public java.util.Optional<java.io.InputStream> open(String name, long offset) {
        opens.incrementAndGet(); var bytes = robot.files.get(name);
        return java.util.Optional.of(new java.io.ByteArrayInputStream(bytes, (int) offset, bytes.length - (int) offset) {
          @Override public void close() throws IOException { closes.incrementAndGet(); throw new IOException("synthetic remote pipe closed"); }
        });
      }
      @Override public void close() { robot.close(); }
    };
    var lines = logLines(() -> {
      var pull = new PullCoordinator(CONFIG, gate, store, WALL, ignored -> {}, worker, (address, config, pin) -> persistent);
      try {
        assertEquals(FileTransfer.Status.COPIED, pull.step().status());
        var local = store.pulls(robot.device, WALL); var held = local.manifest().files().get(0);
        assertEquals(0, held.bytesCopied(), "close must checkpoint the buffered first block");
        if (state.equals("local-failure")) {
          var staged = store.root().resolve(held.localName()); Files.delete(staged); Files.createDirectory(staged);
        }
        if (state.equals("connected")) pull.closeAsync().get(2, java.util.concurrent.TimeUnit.SECONDS);
        else { gate.disconnected(); assertEquals(FileTransfer.Status.PAUSED, pull.step().status()); }
        assertEquals(state.equals("local-failure") ? 0 : 65536, local.manifest().files().get(0).bytesCopied());
      } finally { pull.closeAsync().get(2, java.util.concurrent.TimeUnit.SECONDS); }
    });
    assertEquals(1, opens.get()); assertEquals(1, closes.get()); assertTrue(robot.closed);
    var failure = lines.stream().filter(line -> line.contains("during close")).toList();
    assertEquals(1, failure.size(), lines.toString()); String message = failure.get(0);
    if (state.equals("local-failure")) {
      assertTrue(message.contains("WARN") && message.contains("checkpoint failed"), message);
      assertFalse(message.contains("synthetic remote pipe closed"), "remote close must not replace the local failure: " + message);
    } else {
      assertTrue(message.contains("remote handle") && message.contains("synthetic remote pipe closed"), message);
      assertFalse(message.contains("checkpoint failed"), "successful checkpoint must not be reported as failed: " + message);
      assertTrue(message.contains(state.equals("disconnected") ? "INFO" : "WARN"), message);
      if (state.equals("disconnected")) assertTrue(message.contains("NT4 is disconnected"), message);
    }
  }

  @Test void noContactUntilSettledThenPauseWithinABlockResumeAndContinueAcrossKeyChanges() throws Exception {
    var robot = remote(); var pins = new ArrayList<String>(); var identities = new ArrayList<PullCoordinator.Identity>();
    try (var pull = new PullCoordinator(CONFIG, gate, store, WALL, identities::add, worker, (address, config, pin) -> { pins.add(pin); return robot; })) {
      assertEquals(FileTransfer.Status.PAUSED, pull.step().status()); assertTrue(pins.isEmpty());
      gate.connected("127.0.0.1"); gate.control(0L); worker.advance(4_999_999);
      assertEquals(FileTransfer.Status.PAUSED, pull.step().status()); assertTrue(pins.isEmpty()); worker.advance(1);
      robot.onRead = () -> gate.control(1L);
      assertEquals(FileTransfer.Status.COPIED, pull.step().status()); assertEquals(1, robot.reads.size());
      assertEquals(new PullCoordinator.Progress(65536, 0, 1, 1), pull.progress().get("SYNTHETIC-A"));
      assertEquals(65536, robot.reads.get(0).count()); assertEquals(0, robot.reads.get(0).offset());
      assertEquals(List.of(robot.device), identities.stream().map(PullCoordinator.Identity::device).toList()); assertNull(pins.get(0));
      assertEquals(FileTransfer.Status.PAUSED, pull.step().status()); assertEquals(1, robot.reads.size());
      robot.onRead = () -> {}; gate.control(0L); worker.advance(5_000_000);
      assertEquals(FileTransfer.Status.COPIED, pull.step().status()); assertEquals(65536, robot.reads.get(1).offset());
      gate.disconnected(); assertEquals(FileTransfer.Status.PAUSED, pull.step().status()); assertTrue(robot.closed);
      robot.device = FakeRobot.device("SYNTHETIC-A", "SHA256:changed"); settled();
      assertEquals(FileTransfer.Status.COPIED, pull.step().status()); assertEquals("SHA256:first", pins.get(1));
      assertEquals(131072, robot.reads.get(2).offset()); assertEquals(2, identities.size());
      worker.advance(1_000_000); assertEquals(FileTransfer.Status.WAITING, pull.step().status());
      assertEquals(FileTransfer.Status.VERIFIED, pull.step().status()); assertEquals(3, robot.listings);
      assertEquals(new PullCoordinator.Progress(robot.files.values().iterator().next().length, 1, 0, 0),
          pull.progress().get("SYNTHETIC-A"));
      assertTrue(store.pulls(robot.device, WALL).manifest().files().get(0).verified());
      gate.disconnected(); pull.step(); robot.device = FakeRobot.device("SYNTHETIC-B", "SHA256:third"); settled();
      assertEquals(FileTransfer.Status.COPIED, pull.step().status()); assertEquals(0, robot.reads.get(3).offset());
      assertEquals(1, pull.progress().get("SYNTHETIC-A").files());
      assertEquals(new PullCoordinator.Progress(65536, 0, 1, 0), pull.progress().get("SYNTHETIC-B"));
      assertEquals("SYNTHETIC-B", store.pulls(robot.device, WALL).manifest().serialNumber());
    }
  }

  @Test void theScheduledWorkerCheckpointsAfterOneSecondEvenAtOneBytePerSecond() throws Exception {
    var robot = remote(); settled();
    var config = new PullConfig(true, CONFIG.directories(), CONFIG.settleUs(), 1, CONFIG.ssh());
    var pull = new PullCoordinator(config, gate, store, WALL, ignored -> {}, worker, (address, settings, pin) -> robot);
    try {
      pull.start(); worker.drain(); assertEquals(1, robot.reads.size());
      var local = store.pulls(robot.device, WALL);
      assertEquals(0, local.manifest().files().get(0).bytesCopied());
      worker.advance(999_999); assertEquals(0, local.manifest().files().get(0).bytesCopied());
      worker.advance(1); assertEquals(65536, local.manifest().files().get(0).bytesCopied());
      assertEquals(1, robot.reads.size(), "checkpoint timer must not admit another remote block");
    } finally { pull.closeAsync().get(10, java.util.concurrent.TimeUnit.SECONDS); }
  }

  @Test void staleContactCannotPublishIdentityAndErrorsRetryOnInjectedTime() throws Exception {
    var robot = remote(); var calls = new AtomicInteger(); var identities = new ArrayList<PullCoordinator.Identity>(); settled();
    try (var pull = new PullCoordinator(CONFIG, gate, store, WALL, identities::add, worker, (address, config, pin) -> {
      if (calls.incrementAndGet() == 1) throw new IOException("synthetic unavailable robot");
      gate.disconnected(); return robot;
    })) {
      pull.start(); worker.drain(); assertEquals(1, calls.get()); worker.advance(2_999_999); assertEquals(1, calls.get());
      worker.advance(1); assertEquals(2, calls.get()); assertTrue(robot.closed); assertTrue(identities.isEmpty()); assertTrue(robot.reads.isEmpty());
      assertFalse(Files.exists(store.root().resolve("robots").resolve("SYNTHETIC-A")));
    }
  }

  @Test void reentrantFirstContactIsRefusedBeforeOpeningASecondTransport() throws Exception {
    var robot = remote(); var reference = new AtomicReference<PullCoordinator>(); var once = new AtomicBoolean(); settled();
    try (var pull = new PullCoordinator(CONFIG, gate, store, WALL, ignored -> {}, worker, (address, config, pin) -> {
      if (once.compareAndSet(false, true)) assertThrows(IllegalStateException.class, () -> reference.get().step());
      return robot;
    })) {
      reference.set(pull); assertEquals(FileTransfer.Status.COPIED, pull.step().status()); assertEquals(1, robot.reads.size());
    }
  }

  @Test void slowTransportCloseDoesNotBlockTheCaller() throws Exception {
    var release = new java.util.concurrent.CountDownLatch(1); settled();
    RobotRemote slow = new RobotRemote() {
      @Override public org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity() { return FakeRobot.device("SYNTHETIC-A", "SHA256:first"); }
      @Override public List<File> list() { return List.of(); }
      @Override public byte[] read(String name, long offset, int count) { throw new AssertionError(); }
      @Override public java.util.Optional<String> prefixHash(String name, long length) { throw new AssertionError(); }
      @Override public void close() { try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    };
    var pull = new PullCoordinator(CONFIG, gate, store, WALL, ignored -> {}, worker, (address, config, pin) -> slow);
    try { pull.step(); assertTimeoutPreemptively(Duration.ofSeconds(1), pull::close); }
    finally { release.countDown(); pull.close(); }
  }
}
