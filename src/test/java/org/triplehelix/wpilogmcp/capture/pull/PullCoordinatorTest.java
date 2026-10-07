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

  @Test void noContactUntilSettledThenPauseWithinABlockResumeAndContinueAcrossKeyChanges() throws Exception {
    var robot = remote(); var pins = new ArrayList<String>(); var identities = new ArrayList<PullCoordinator.Identity>();
    try (var pull = new PullCoordinator(CONFIG, gate, store, WALL, identities::add, worker, (address, config, pin) -> { pins.add(pin); return robot; })) {
      assertEquals(FileTransfer.Status.PAUSED, pull.step().status()); assertTrue(pins.isEmpty());
      gate.connected("127.0.0.1"); gate.control(0L); worker.advance(4_999_999);
      assertEquals(FileTransfer.Status.PAUSED, pull.step().status()); assertTrue(pins.isEmpty()); worker.advance(1);
      robot.onRead = () -> gate.control(1L);
      assertEquals(FileTransfer.Status.COPIED, pull.step().status()); assertEquals(1, robot.reads.size());
      assertEquals(65536, robot.reads.get(0).count()); assertEquals(0, robot.reads.get(0).offset());
      assertEquals(List.of(robot.device), identities.stream().map(PullCoordinator.Identity::device).toList()); assertNull(pins.get(0));
      assertEquals(FileTransfer.Status.PAUSED, pull.step().status()); assertEquals(1, robot.reads.size());
      robot.onRead = () -> {}; gate.control(0L); worker.advance(5_000_000);
      assertEquals(FileTransfer.Status.COPIED, pull.step().status()); assertEquals(65536, robot.reads.get(1).offset());
      gate.disconnected(); assertEquals(FileTransfer.Status.PAUSED, pull.step().status()); assertTrue(robot.closed);
      robot.device = FakeRobot.device("SYNTHETIC-A", "SHA256:changed"); settled();
      assertEquals(FileTransfer.Status.COPIED, pull.step().status()); assertEquals("SHA256:first", pins.get(1));
      assertEquals(131072, robot.reads.get(2).offset()); assertEquals(2, identities.size());
      worker.advance(1_000_000); assertEquals(FileTransfer.Status.VERIFIED, pull.step().status());
      assertTrue(store.pulls(robot.device, WALL).manifest().files().get(0).verified());
      gate.disconnected(); pull.step(); robot.device = FakeRobot.device("SYNTHETIC-B", "SHA256:third"); settled();
      assertEquals(FileTransfer.Status.COPIED, pull.step().status()); assertEquals(0, robot.reads.get(3).offset());
      assertEquals("SYNTHETIC-B", store.pulls(robot.device, WALL).manifest().serialNumber());
    }
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
