/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

@DisplayName("DaemonManager")
class DaemonManagerTest {

  /** A PID no process has: above the PID limit of Linux (2^22) and macOS (99998). */
  private static final long DEAD_PID = 999999999L;
  /** This JVM's PID: a process that is alive for the whole test. */
  private static final long OWN_PID = ProcessHandle.current().pid();

  @TempDir
  Path tempDir;

  private DaemonManager createManager() {
    return new DaemonManager(tempDir);
  }

  /** A manager that stops waiting for a daemon after 300 ms. */
  private DaemonManager createImpatientManager() {
    return new DaemonManager(tempDir, Duration.ofMillis(300));
  }

  /** A real server whose /health answers, on a free port; the caller stops it. */
  private static HttpTransport startServer() throws IOException {
    var transport = new HttpTransport(new ToolRegistry(), 0);
    transport.start();
    return transport;
  }

  /** A port nothing listens on. */
  private static int freePort() throws IOException {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private List<String> pidFileLines(DaemonManager manager, String name) throws IOException {
    return Files.readAllLines(manager.pidFilePath(name));
  }

  private static List<String> record(long pid, int port) {
    return List.of(Long.toString(pid), Integer.toString(port));
  }

  private static List<String> claim(long pid, int port) {
    return List.of(Long.toString(pid), Integer.toString(port), DaemonManager.STARTING_MARKER);
  }

  // ==================== PID File Management ====================

  @Nested
  @DisplayName("PID file management")
  class PidFileTests {

    @Test
    @DisplayName("writes and reads PID file")
    void writesAndReadsPidFile() throws IOException {
      var manager = createManager();
      manager.writePidFile("test", 12345, 2363);

      var pidFile = manager.pidFilePath("test");
      assertTrue(Files.exists(pidFile));

      var lines = Files.readAllLines(pidFile);
      assertEquals(2, lines.size());
      assertEquals("12345", lines.get(0));
      assertEquals("2363", lines.get(1));
    }

    @Test
    @DisplayName("deletes PID file")
    void deletesPidFile() throws IOException {
      var manager = createManager();
      manager.writePidFile("test", 12345, 2363);
      assertTrue(Files.exists(manager.pidFilePath("test")));

      manager.deletePidFile("test");
      assertFalse(Files.exists(manager.pidFilePath("test")));
    }

    @Test
    @DisplayName("delete is idempotent for non-existent file")
    void deleteIdempotent() {
      var manager = createManager();
      assertDoesNotThrow(() -> manager.deletePidFile("nonexistent"));
    }

    @Test
    @DisplayName("PID file path includes server name")
    void pidFilePathIncludesName() {
      var manager = createManager();
      var path = manager.pidFilePath("competition");
      assertTrue(path.getFileName().toString().equals("competition.pid"));
    }

    @Test
    @DisplayName("claims the PID file once, with this process and the starting marker")
    void claimsOnce() throws IOException {
      var manager = createManager();
      assertTrue(manager.claimPidFile("test", 2363));
      assertEquals(claim(OWN_PID, 2363), pidFileLines(manager, "test"));

      assertFalse(manager.claimPidFile("test", 2363), "A second claim must fail");
      assertFalse(manager.claimPidFile("test", 9999), "A claim for another port must fail too");
      assertEquals(claim(OWN_PID, 2363), pidFileLines(manager, "test"), "The claim is untouched");
    }

    @Test
    @DisplayName("recording the daemon replaces the claim and leaves no temporary file")
    void recordReplacesClaim() throws IOException {
      var manager = createManager();
      assertTrue(manager.claimPidFile("test", 2363));
      manager.writePidFile("test", 12345, 2363);

      assertEquals(record(12345, 2363), pidFileLines(manager, "test"));
      try (Stream<Path> files = Files.list(tempDir)) {
        assertEquals(List.of(manager.pidFilePath("test")), files.toList());
      }
    }
  }

  // ==================== Already Running Detection ====================

  @Nested
  @DisplayName("Already running detection")
  class AlreadyRunningTests {

    @Test
    @DisplayName("returns false when no PID file exists")
    void falseWhenNoPidFile() {
      var manager = createManager();
      assertFalse(manager.isAlreadyRunning("test", 2363));
    }

    @Test
    @DisplayName("removes stale PID file when process is dead")
    void removesStaleFile() throws IOException {
      var manager = createManager();
      // Write a PID for a process that almost certainly doesn't exist
      manager.writePidFile("test", DEAD_PID, 2363);

      assertFalse(manager.isAlreadyRunning("test", 2363));
      assertFalse(Files.exists(manager.pidFilePath("test")),
          "Stale PID file should be removed");
    }

    @Test
    @DisplayName("removes malformed PID file")
    void removesMalformedFile() throws IOException {
      var manager = createManager();
      Files.writeString(manager.pidFilePath("test"), "not a number\n");

      assertFalse(manager.isAlreadyRunning("test", 2363));
      assertFalse(Files.exists(manager.pidFilePath("test")));
    }

    @Test
    @DisplayName("removes PID file with missing port line")
    void removesSingleLinePidFile() throws IOException {
      var manager = createManager();
      Files.writeString(manager.pidFilePath("test"), "12345\n");

      assertFalse(manager.isAlreadyRunning("test", 2363));
      assertFalse(Files.exists(manager.pidFilePath("test")));
    }

    @Test
    @DisplayName("true for a healthy daemon on the expected port")
    void trueForHealthyDaemonOnPort() throws IOException {
      var server = startServer();
      try {
        var manager = createManager();
        manager.writePidFile("test", OWN_PID, server.getPort());

        assertTrue(manager.isAlreadyRunning("test", server.getPort()));
        assertEquals(record(OWN_PID, server.getPort()), pidFileLines(manager, "test"),
            "The record must be kept");
      } finally {
        server.stop();
      }
    }

    @Test
    @DisplayName("false for a healthy daemon on another port, whose record is kept")
    void falseForDaemonOnAnotherPort() throws IOException {
      var server = startServer();
      try {
        var manager = createManager();
        manager.writePidFile("test", OWN_PID, server.getPort());

        assertFalse(manager.isAlreadyRunning("test", server.getPort() + 1),
            "A daemon on another port is not the one asked for");
        assertEquals(record(OWN_PID, server.getPort()), pidFileLines(manager, "test"),
            "The running daemon's record must be kept");
      } finally {
        server.stop();
      }
    }

    @Test
    @DisplayName("false while a start by a live process is in progress, whose claim is kept")
    void falseWhileStartInProgress() throws IOException {
      var manager = createManager();
      assertTrue(manager.claimPidFile("test", 2363));

      assertFalse(manager.isAlreadyRunning("test", 2363));
      assertEquals(claim(OWN_PID, 2363), pidFileLines(manager, "test"));
    }

    @Test
    @DisplayName("removes the claim of a start whose process died")
    void removesDeadStartersClaim() throws IOException {
      var manager = createManager();
      Files.writeString(manager.pidFilePath("test"),
          DEAD_PID + "\n2363\n" + DaemonManager.STARTING_MARKER + "\n");

      assertFalse(manager.isAlreadyRunning("test", 2363));
      assertFalse(Files.exists(manager.pidFilePath("test")));
    }

    @Test
    @DisplayName("removes the record of a live process that does not answer (PID reused)")
    void removesRecordOfReusedPid() throws IOException {
      var manager = createManager();
      int port = freePort();
      manager.writePidFile("test", OWN_PID, port);

      assertFalse(manager.isAlreadyRunning("test", port));
      assertFalse(Files.exists(manager.pidFilePath("test")));
    }
  }

  // ==================== Spawning ====================

  @Nested
  @DisplayName("spawnDaemon")
  class SpawnTests {

    @Test
    @DisplayName("returns true for a daemon already running on the port, without spawning")
    void alreadyRunning() throws IOException {
      var server = startServer();
      try {
        var manager = createManager();
        manager.writePidFile("test", OWN_PID, server.getPort());

        assertTrue(manager.spawnDaemon("test", server.getPort(), null));
        assertEquals(record(OWN_PID, server.getPort()), pidFileLines(manager, "test"));
      } finally {
        server.stop();
      }
    }

    @Test
    @DisplayName("refuses to start on a new port while the daemon runs on the old one")
    void refusesWhileRunningOnAnotherPort() throws IOException {
      var server = startServer();
      try {
        var manager = createImpatientManager();
        manager.writePidFile("test", OWN_PID, server.getPort());

        assertFalse(manager.spawnDaemon("test", server.getPort() + 1, null));
        assertEquals(record(OWN_PID, server.getPort()), pidFileLines(manager, "test"),
            "The old daemon's record must be kept");
      } finally {
        server.stop();
      }
    }

    @Test
    @DisplayName("waits for a start in progress and gives up when its server never answers")
    void waitsForStartInProgressThenGivesUp() throws IOException {
      var manager = createImpatientManager();
      int port = freePort();
      // As if another start had claimed the file
      assertTrue(manager.claimPidFile("test", port));

      assertFalse(manager.spawnDaemon("test", port, null));
      assertEquals(claim(OWN_PID, port), pidFileLines(manager, "test"),
          "Another start's claim must not be touched");
    }

    @Test
    @DisplayName("joins a start in progress once its server answers")
    void joinsStartInProgress() throws IOException {
      var server = startServer();
      try {
        var manager = createImpatientManager();
        assertTrue(manager.claimPidFile("test", server.getPort()));

        assertTrue(manager.spawnDaemon("test", server.getPort(), null));
      } finally {
        server.stop();
      }
    }
  }

  // ==================== Daemon Command ====================

  @Nested
  @DisplayName("daemon heap and command")
  class DaemonCommandTests {

    @Test
    @DisplayName("without WPILOG_MAX_HEAP or -Xmx the daemon gets the launcher's 4g, not the "
        + "JVM default")
    void defaultsToLauncherHeap() {
      assertEquals("4g", DaemonManager.daemonMaxHeap(null, List.of()));
      assertEquals("4g", DaemonManager.daemonMaxHeap("  ", List.of("-Dfoo=bar")));
    }

    @Test
    @DisplayName("WPILOG_MAX_HEAP wins, trimmed")
    void environmentWins() {
      assertEquals("8g", DaemonManager.daemonMaxHeap(" 8g ", List.of("-Xmx2g")));
    }

    @Test
    @DisplayName("otherwise the heap this JVM was started with; the last -Xmx is the one in force")
    void inheritsOwnHeap() {
      assertEquals("2g", DaemonManager.daemonMaxHeap(null, List.of("-Xmx2g")));
      assertEquals("6g", DaemonManager.daemonMaxHeap(null,
          List.of("-Xmx2g", "-Dx=y", "-Xmx6g")));
      assertEquals("4g", DaemonManager.daemonMaxHeap(null, List.of("-Xmx")),
          "an empty -Xmx is not a size");
    }

    @Test
    @DisplayName("the command always sets the heap before -jar and passes the config")
    void commandShape() {
      var command = DaemonManager.daemonCommand("java", "4g", null, "/x/wpilog-mcp.jar",
          "team", Path.of("/c/servers.yaml"));
      assertEquals(List.of("java", "-Xmx4g", "-jar", "/x/wpilog-mcp.jar", "--internal-daemon",
          "team", "--config", "/c/servers.yaml"), command);
      var withLevel = DaemonManager.daemonCommand("java", "2g", "debug", "a.jar", "n", null);
      assertEquals(List.of("java", "-Xmx2g",
          "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug", "-jar", "a.jar",
          "--internal-daemon", "n"), withLevel);
    }
  }

  // ==================== Concurrent starts ====================

  /**
   * Stands in for the daemon process: counts the launches, and starts a server on the port only
   * after {@code bootMillis}, as a real daemon answers only once its JVM is up. The "process" is
   * this JVM, which is alive throughout.
   */
  private static final class FakeLauncher implements DaemonManager.Launcher {
    final AtomicInteger launches = new AtomicInteger();
    final List<HttpTransport> servers = new CopyOnWriteArrayList<>();
    final int port;
    final long bootMillis;

    FakeLauncher(int port, long bootMillis) {
      this.port = port;
      this.bootMillis = bootMillis;
    }

    @Override
    public DaemonManager.Launched launch(List<String> command, java.io.File logFile) {
      launches.incrementAndGet();
      var boot = new Thread(() -> {
        try {
          Thread.sleep(bootMillis);
          var server = new HttpTransport(new ToolRegistry(), port);
          server.start();
          servers.add(server);
        } catch (IOException | InterruptedException e) {
          // a second "daemon" cannot bind the port, as a real one could not
        }
      });
      boot.setDaemon(true);
      boot.start();
      return new DaemonManager.Launched() {
        @Override
        public long pid() {
          return OWN_PID;
        }

        @Override
        public boolean isAlive() {
          return true;
        }

        @Override
        public void destroy() {}
      };
    }

    void stop() {
      servers.forEach(HttpTransport::stop);
    }
  }

  private static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, "condition not reached in 10 s");
      Thread.sleep(5);
    }
  }

  @Nested
  @DisplayName("concurrent starts")
  class ConcurrentStartTests {

    @Test
    @DisplayName("a start made while another start's server is booting joins it")
    void startDuringBoot() throws Exception {
      // The first start has spawned its server and recorded it; the server does not answer yet.
      // A second start used to take that record for a reused PID, delete it, and spawn again.
      int port = freePort();
      var launcher = new FakeLauncher(port, 700);
      var manager = new DaemonManager(tempDir, Duration.ofSeconds(8), launcher);
      var pool = Executors.newFixedThreadPool(2);
      try {
        Future<Boolean> first = pool.submit(() -> manager.spawnDaemon("test", port, null));
        await(() -> {
          try {
            var lines = pidFileLines(manager, "test");
            return launcher.launches.get() == 1
                && (lines.size() < 3 || !DaemonManager.STARTING_MARKER.equals(lines.get(2)));
          } catch (IOException e) {
            return false;
          }
        });
        Future<Boolean> second = pool.submit(() -> manager.spawnDaemon("test", port, null));

        assertTrue(first.get(15, TimeUnit.SECONDS));
        assertTrue(second.get(15, TimeUnit.SECONDS));
        assertEquals(1, launcher.launches.get(), "the second start spawned another server");
        assertEquals(record(OWN_PID, port), pidFileLines(manager, "test"));
      } finally {
        pool.shutdownNow();
        launcher.stop();
      }
    }

    @Test
    @DisplayName("many starts at once, over a stale record, spawn one server")
    void manyStartsAtOnce() throws Exception {
      // Each start used to read the stale record, delete it, and claim the file: a start could
      // delete the claim another had just made, and both spawned
      for (int round = 0; round < 6; round++) {
        var runDir = Files.createDirectories(tempDir.resolve("round" + round));
        int port = freePort();
        var launcher = new FakeLauncher(port, 150);
        var manager = new DaemonManager(runDir, Duration.ofSeconds(8), launcher);
        manager.writePidFile("test", DEAD_PID, port);
        int starts = 12;
        var pool = Executors.newFixedThreadPool(starts);
        try {
          var go = new CountDownLatch(1);
          var results = new ArrayList<Future<Boolean>>();
          for (int i = 0; i < starts; i++) {
            results.add(pool.submit(() -> {
              go.await();
              return manager.spawnDaemon("test", port, null);
            }));
          }
          go.countDown();
          for (var result : results) {
            assertTrue(result.get(20, TimeUnit.SECONDS), "round " + round);
          }
          assertEquals(1, launcher.launches.get(), "round " + round + ": servers spawned");
          assertEquals(record(OWN_PID, port), pidFileLines(manager, "test"), "round " + round);
        } finally {
          pool.shutdownNow();
          launcher.stop();
        }
      }
    }

    @Test
    @DisplayName("the record of a server still booting is kept, and settles once it answers")
    void bootingRecord() throws Exception {
      // What a status check, or a second start, finds while a server is coming up
      var manager = createManager();
      int port = freePort();
      manager.writePidFile("test", OWN_PID, port, DaemonManager.BOOTING_MARKER);
      var booting = List.of(Long.toString(OWN_PID), Integer.toString(port),
          DaemonManager.BOOTING_MARKER);

      assertFalse(manager.isAlreadyRunning("test", port));
      assertEquals(booting, pidFileLines(manager, "test"), "it used to be removed as a reused PID");

      var server = new HttpTransport(new ToolRegistry(), port);
      server.start();
      try {
        assertTrue(manager.isAlreadyRunning("test", port));
        assertEquals(record(OWN_PID, port), pidFileLines(manager, "test"),
            "a server that answers is no longer booting");
      } finally {
        server.stop();
      }
    }

    @Test
    @DisplayName("a booting record whose server never answered is removed after the grace period")
    void bootingRecordExpires() throws Exception {
      var manager = createImpatientManager();
      int port = freePort();
      manager.writePidFile("test", OWN_PID, port, DaemonManager.BOOTING_MARKER);
      Files.setLastModifiedTime(manager.pidFilePath("test"),
          FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));

      assertFalse(manager.isAlreadyRunning("test", port));
      assertFalse(Files.exists(manager.pidFilePath("test")));
    }

    @Test
    @DisplayName("a start records its server as booting until it answers")
    void startRecordsBooting() throws Exception {
      int port = freePort();
      var launcher = new FakeLauncher(port, 600);
      var manager = new DaemonManager(tempDir, Duration.ofSeconds(8), launcher);
      var pool = Executors.newSingleThreadExecutor();
      try {
        Future<Boolean> start = pool.submit(() -> manager.spawnDaemon("test", port, null));
        await(() -> launcher.launches.get() == 1 && Files.exists(manager.pidFilePath("test"))
            && !pidLines(manager).contains(DaemonManager.STARTING_MARKER));
        assertEquals(List.of(Long.toString(OWN_PID), Integer.toString(port),
            DaemonManager.BOOTING_MARKER), pidLines(manager));
        assertTrue(start.get(15, TimeUnit.SECONDS));
        assertEquals(record(OWN_PID, port), pidLines(manager));
      } finally {
        pool.shutdownNow();
        launcher.stop();
      }
    }

    private List<String> pidLines(DaemonManager manager) {
      try {
        return pidFileLines(manager, "test");
      } catch (IOException e) {
        return List.of();
      }
    }
  }

  // ==================== Health Check ====================

  @Nested
  @DisplayName("Health check")
  class HealthCheckTests {

    @Test
    @DisplayName("returns false for port with no server")
    void falseForNoServer() {
      var manager = createManager();
      // Port 1 is almost certainly not running an HTTP server
      assertFalse(manager.healthCheck(1));
    }
  }
}
