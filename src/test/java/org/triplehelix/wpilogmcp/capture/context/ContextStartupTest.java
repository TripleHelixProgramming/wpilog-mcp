/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.config.ProviderConfig;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.ssh.SharedSsh;
import org.triplehelix.wpilogmcp.ssh.SshConnection;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreRegistry;

class ContextStartupTest {
  @TempDir Path temp;

  @Test void connectedSshIsWaitingForIdentityEvenBeforeItsWorkerRuns() throws Exception {
    try (var rig = new Rig(new ScriptedSsh("STARTUP"))) {
      rig.connect();
      rig.loop.advance(100_000);
      assertEquals("waiting", rig.status().state(), "A connected SSH session must not retain the old offline state");
      assertEquals("Reading the device identity", rig.status().reason());
    }
  }

  @Test void aBlockedIdentityReadPublishesItsActualActivity() throws Exception {
    var ssh = new ScriptedSsh("BLOCKED-IDENTITY"); ssh.blockIdentity = true;
    try (var rig = new Rig(ssh)) {
      rig.connect(); var sample = rig.sampleAsync();
      assertTrue(ssh.identityEntered.await(5, TimeUnit.SECONDS)); rig.loop.drain();
      assertEquals("waiting", rig.status().state());
      assertEquals("Reading the device identity", rig.status().reason());
      ssh.releaseIdentity.countDown(); sample.get(5, TimeUnit.SECONDS);
    }
  }

  @Test void programDiscoveryHasItsOwnWaitingReason() throws Exception {
    var ssh = new ScriptedSsh("BLOCKED-DISCOVERY"); ssh.blockDiscovery = true;
    try (var rig = new Rig(ssh)) {
      rig.connect(); var sample = rig.sampleAsync();
      assertTrue(ssh.discoveryEntered.await(5, TimeUnit.SECONDS)); rig.loop.drain();
      assertEquals("waiting", rig.status().state());
      assertEquals("Discovering the robot program", rig.status().reason());
      ssh.releaseDiscovery.countDown(); sample.get(5, TimeUnit.SECONDS); rig.loop.drain();
      assertEquals("waiting_for_sync", rig.status().state());
      assertEquals("No NT4 clock estimate; sample dropped", rig.status().reason());
    }
  }

  @Test void firstSampleHasItsOwnWaitingReasonAfterDiscovery() throws Exception {
    var ssh = new ScriptedSsh("BLOCKED-SAMPLE"); ssh.blockSample = true;
    try (var rig = new Rig(ssh)) {
      rig.connect(); var sample = rig.sampleAsync();
      assertTrue(ssh.sampleEntered.await(5, TimeUnit.SECONDS)); rig.loop.drain();
      assertEquals("waiting", rig.status().state());
      assertEquals("Reading robot statistics", rig.status().reason());
      ssh.releaseSample.countDown(); sample.get(5, TimeUnit.SECONDS);
    }
  }

  @Test void contextAndBorrowedPullsReadIdentityOncePerConnection() throws Exception {
    var first = new ScriptedSsh("FIRST-DEVICE"); var next = new ScriptedSsh("NEXT-DEVICE");
    try (var rig = new Rig(first, next)) {
      rig.connect(); rig.worker.advance(250_000); rig.loop.drain();
      assertEquals("FIRST-DEVICE", rig.pullIdentity().serialNumber());
      assertEquals("FIRST-DEVICE", rig.pullIdentity().serialNumber());
      assertEquals(1, first.identityScans.get(), "Context and pulls must share the connection's identity scan");
      rig.reconnect(); rig.worker.advance(250_000); rig.loop.drain();
      assertEquals("NEXT-DEVICE", rig.pullIdentity().serialNumber());
      assertEquals(1, next.identityScans.get(), "A reconnect must obtain fresh device evidence once");
      assertEquals(1, first.identityScans.get());
    }
  }

  @Test void simultaneousContextAndPullIdentityReadsShareAnInflightResult() throws Exception {
    var ssh = new ScriptedSsh("CONCURRENT"); ssh.blockIdentity = true;
    try (var rig = new Rig(ssh)) {
      rig.connect(); var sample = rig.sampleAsync();
      assertTrue(ssh.identityEntered.await(5, TimeUnit.SECONDS));
      var pull = rig.threads.submit(rig::pullIdentity);
      assertTrue(ssh.secondChannel.await(5, TimeUnit.SECONDS));
      assertFalse(pull.isDone(), "The pull waits for the shared identity result on its own worker");
      ssh.releaseIdentity.countDown();
      assertEquals("CONCURRENT", pull.get(5, TimeUnit.SECONDS).serialNumber()); sample.get(5, TimeUnit.SECONDS);
      assertEquals(1, ssh.identityScans.get(), "Concurrent consumers must not both scan /proc");
    }
  }

  @Test void aDisconnectedReadCannotSupplyIdentityToTheNextConnectionOrDelayItsStart() throws Exception {
    var first = new ScriptedSsh("STALE-DEVICE"); first.blockIdentity = true;
    var next = new ScriptedSsh("FRESH-DEVICE");
    try (var rig = new Rig(first, next)) {
      rig.connect(); var stale = rig.threads.submit(rig::pullIdentity);
      assertTrue(first.identityEntered.await(5, TimeUnit.SECONDS));
      assertTimeoutPreemptively(Duration.ofSeconds(1), rig::reconnect,
          "Identity I/O must not hold a lock needed by connection lifecycle work");
      assertEquals("FRESH-DEVICE", rig.pullIdentity().serialNumber());
      first.releaseIdentity.countDown();
      var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> stale.get(5, TimeUnit.SECONDS));
      assertInstanceOf(IOException.class, failure.getCause());
      assertTrue(failure.getCause().getMessage().contains("connection changed"), failure.getCause().getMessage());
      assertEquals("FRESH-DEVICE", rig.pullIdentity().serialNumber());
      assertEquals(1, first.identityScans.get()); assertEquals(1, next.identityScans.get());
    }
  }

  @Test void aLateContextIdentityDoesNotDiscoverOrPublishForTheDisconnectedRobot() throws Exception {
    var first = new ScriptedSsh("STALE-CONTEXT"); first.blockIdentity = true;
    var next = new ScriptedSsh("CURRENT-CONTEXT");
    try (var rig = new Rig(first, next)) {
      rig.connect(); var sample = rig.sampleAsync();
      assertTrue(first.identityEntered.await(5, TimeUnit.SECONDS));
      rig.reconnect(); rig.loop.drain();
      assertEquals("waiting", rig.status().state());
      assertEquals("Reading the device identity", rig.status().reason());
      first.releaseIdentity.countDown(); sample.get(5, TimeUnit.SECONDS); rig.store.awaitImports();
      assertEquals(0, first.discoveryCalls.get(), "A stale identity result must stop the old setup sequence");
      assertFalse(java.nio.file.Files.exists(rig.store.root().resolve("robots").resolve("STALE-CONTEXT")));
      rig.worker.advance(250_000); rig.loop.drain();
      assertEquals(1, next.identityScans.get());
      assertEquals("CURRENT-CONTEXT", rig.pullIdentity().serialNumber());
    }
  }

  @Test void aLateStatsFailureCannotStandDownAReplacementSshConnection() throws Exception {
    var first = new ScriptedSsh("FAILED-REPLY"); first.blockSample = true; first.sampleText = "unsupported old reply";
    var next = new ScriptedSsh("REPLACEMENT");
    try (var rig = new Rig(first, next)) {
      rig.connect(); var sample = rig.sampleAsync();
      assertTrue(first.sampleEntered.await(5, TimeUnit.SECONDS));
      first.connected = false; rig.connections.advance(250_000); // SSH reconnects while NT4 stays connected.
      first.releaseSample.countDown(); sample.get(5, TimeUnit.SECONDS);
      rig.worker.advance(250_000); rig.loop.drain();
      assertEquals("waiting_for_sync", rig.status().state(), "An old SSH reply must not latch a new connection's stand-down");
      assertEquals(1, next.discoveryCalls.get());
      assertEquals("REPLACEMENT", rig.pullIdentity().serialNumber());
    }
  }

  @Test void unavailableIdentityIsSharedUntilReconnectInsteadOfRescanningOnEveryPull() throws Exception {
    var ssh = new ScriptedSsh(null);
    try (var rig = new Rig(ssh)) {
      rig.connect(); rig.worker.advance(250_000);
      for (int i = 0; i < 2; i++) {
        assertTrue(assertThrows(IOException.class, rig::pullIdentity).getMessage().contains("No readable robot serialnum"));
      }
      assertEquals(1, ssh.identityScans.get());
    }
  }

  private final class Rig implements AutoCloseable {
    final ManualScheduler loop = new ManualScheduler(), worker = new ManualScheduler(), connections = new ManualScheduler();
    final ExecutorService threads = Executors.newFixedThreadPool(3);
    final List<ScriptedSsh> contacts;
    final PullConfig pull = new PullConfig(true, List.of(), 0, 1_000_000, PullConfig.DISABLED.ssh());
    final LiveCapture live = new LiveCapture(temp, loop);
    final StoreRegistry stores;
    final LogStore store;
    final CaptureWriter writer;
    final ContextProviders providers;
    Rig(ScriptedSsh... contacts) throws Exception {
      this.contacts = List.of(contacts);
      var security = new SecurityValidator(); security.addAllowedDirectory(temp);
      stores = new StoreRegistry(security); store = stores.store(temp.resolve("store"));
      var wall = Clock.systemUTC();
      writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, (host, start) -> temp.resolve("capture.wpilog"));
      var count = new AtomicInteger();
      var pool = new SharedSsh(host -> CompletableFuture.completedFuture(null),
          (host, config, pin) -> this.contacts.get(count.getAndIncrement()), host -> connections);
      var config = new CaptureConfig(List.of(RobotAddress.uri("synthetic", 5810, "startup")), temp, .01,
          CapturePolicy.ALL, 0, 1 << 20, pull, 0,
          new ProviderConfig(true, new ProviderConfig.Stats(true, 2_000_000, 100_000), List.of()));
      providers = new ContextProviders(config, writer, live, loop, store, wall, pool, worker);
      providers.start(); worker.drain(); loop.drain();
    }
    void connect() { providers.connected("synthetic"); connections.drain(); }
    void reconnect() {
      providers.disconnected(); connections.advance(250_000);
      providers.connected("synthetic"); connections.advance(250_000);
    }
    Future<?> sampleAsync() { return threads.submit(() -> worker.advance(250_000)); }
    ProviderStatus status() { return live.providers().stream().filter(p -> p.name().equals("roboRIO")).findFirst().orElseThrow(); }
    DeviceIdentity pullIdentity() throws IOException {
      try (var remote = providers.pull("synthetic", pull, null)) { return remote.identity(); }
    }
    @Override public void close() throws Exception {
      contacts.forEach(c -> { c.releaseIdentity.countDown(); c.releaseDiscovery.countDown(); c.releaseSample.countDown(); });
      threads.shutdown(); assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
      var closing = providers.closeAsync(); connections.advance(250_000); assertTrue(closing.isDone());
      writer.close(); stores.close();
    }
  }

  private static final class ScriptedSsh implements SshConnection {
    final String serial;
    final AtomicInteger identityScans = new AtomicInteger(), channels = new AtomicInteger(), discoveryCalls = new AtomicInteger();
    final CountDownLatch identityEntered = new CountDownLatch(1), releaseIdentity = new CountDownLatch(1);
    final CountDownLatch discoveryEntered = new CountDownLatch(1), releaseDiscovery = new CountDownLatch(1);
    final CountDownLatch sampleEntered = new CountDownLatch(1), releaseSample = new CountDownLatch(1);
    final CountDownLatch secondChannel = new CountDownLatch(1);
    volatile boolean connected = true, blockIdentity, blockDiscovery, blockSample;
    String sampleText = ProcFixture.sample(0);
    ScriptedSsh(String serial) { this.serial = serial; }
    @Override public String fingerprint() { return "SHA256:synthetic-startup"; }
    @Override public boolean connected() { return connected; }
    @Override public SshConnection.Files files() {
      if (channels.incrementAndGet() == 2) secondChannel.countDown();
      return new SshConnection.Files() {
        @Override public List<Item> list(String directory) throws IOException {
          if (!directory.equals("/proc")) return List.of();
          identityScans.incrementAndGet(); identityEntered.countDown();
          if (blockIdentity) await(releaseIdentity);
          return List.of(new Item("42", 0, 0, true, false, false));
        }
        @Override public byte[] read(String path, long offset, int count) {
          return (path.equals("/etc/machine-info") ? "PRETTY_HOSTNAME=\"synthetic\"\n"
              : serial == null ? "USER=lvuser\0" : "serialnum=" + serial + "\0").getBytes(StandardCharsets.UTF_8);
        }
        @Override public void close() {}
      };
    }
    @Override public Optional<String> exec(String command, long timeoutMs, int maxBytes) throws IOException {
      if (command.equals(StatsCommand.lookup(true)) || command.equals(StatsCommand.lookup(false))) {
        discoveryCalls.incrementAndGet(); discoveryEntered.countDown(); if (blockDiscovery) await(releaseDiscovery);
        return Optional.of(ProcFixture.discovery(0));
      }
      sampleEntered.countDown(); if (blockSample) await(releaseSample);
      return Optional.of(sampleText);
    }
    @Override public Command follow(String command) { throw new AssertionError("No tail in this fixture"); }
    @Override public void close() { connected = false; }
    private static void await(CountDownLatch latch) throws IOException {
      try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("Scripted activity not released"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted scripted activity", e); }
    }
  }
}
