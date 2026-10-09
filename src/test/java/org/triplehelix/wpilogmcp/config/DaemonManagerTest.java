/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.IntPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.Version;
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
      // The config path is passed as the platform renders it (backslashes on Windows)
      var config = Path.of("/c/servers.yaml");
      var command = DaemonManager.daemonCommand("java", "4g", null, "/x/wpilog-mcp.jar",
          "team", config);
      assertEquals(List.of("java", "-Xmx4g", "-jar", "/x/wpilog-mcp.jar", "--internal-daemon",
          "team", "--config", config.toString()), command);
      var withLevel = DaemonManager.daemonCommand("java", "2g", "debug", "a.jar", "n", null);
      assertEquals(List.of("java", "-Xmx2g",
          "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug", "-jar", "a.jar",
          "--internal-daemon", "n"), withLevel);
    }
  }

  // ==================== Concurrent starts ====================

  /**
   * Stands in for the daemon process: counts the launches, and starts a server on the port only
   * after the test releases {@code finishBoot}, as a real daemon answers only once its JVM is up. The "process" is
   * this JVM, which is alive throughout.
   */
  private static final class FakeLauncher implements DaemonManager.Launcher {
    final AtomicInteger launches = new AtomicInteger();
    final List<HttpTransport> servers = new CopyOnWriteArrayList<>();
    /** The stop token each launch was given in its environment. */
    final List<String> tokens = new CopyOnWriteArrayList<>();
    final int port;
    final CountDownLatch finishBoot;

    FakeLauncher(int port, boolean held) {
      this.port = port;
      this.finishBoot = new CountDownLatch(held ? 1 : 0);
    }

    @Override
    public DaemonManager.Launched launch(List<String> command,
        java.util.Map<String, String> environment, java.io.File logFile) {
      launches.incrementAndGet();
      tokens.add(environment.get(DaemonManager.STOP_TOKEN_ENV));
      var boot = new Thread(() -> {
        try {
          assertTrue(finishBoot.await(10, TimeUnit.SECONDS), "Test did not release daemon boot");
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
    org.triplehelix.wpilogmcp.harness.HarnessHttp.await("daemon condition", 10, condition::getAsBoolean);
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
      var launcher = new FakeLauncher(port, true);
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
        manager.onJoiningStart(launcher.finishBoot::countDown);
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
        var launcher = new FakeLauncher(port, false);
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
      var launcher = new FakeLauncher(port, true);
      var manager = new DaemonManager(tempDir, Duration.ofSeconds(8), launcher);
      var pool = Executors.newSingleThreadExecutor();
      try {
        Future<Boolean> start = pool.submit(() -> manager.spawnDaemon("test", port, null));
        await(() -> launcher.launches.get() == 1 && Files.exists(manager.pidFilePath("test"))
            && !pidLines(manager).contains(DaemonManager.STARTING_MARKER));
        assertEquals(List.of(Long.toString(OWN_PID), Integer.toString(port),
            DaemonManager.BOOTING_MARKER), pidLines(manager));
        launcher.finishBoot.countDown();
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

  // ==================== A PID file open elsewhere ====================

  /**
   * A file system that refuses writes to the PID file, as Windows does while the file is open in
   * another program, then makes them. The first {@code createRefusals} creates are refused, and
   * each replace {@code refuseReplace} accepts, asked with its number from 1.
   */
  private static final class BusyFiles implements DaemonManager.PidFileWriter {
    final AtomicInteger creates = new AtomicInteger();
    final AtomicInteger replaces = new AtomicInteger();
    /** For each replace, whether the thread making it held the start lock. */
    final List<Boolean> lockHeld = new CopyOnWriteArrayList<>();
    private final int createRefusals;
    private final IntPredicate refuseReplace;
    private final BiFunction<Path, Path, IOException> refusal;
    volatile DaemonManager manager;

    BusyFiles(int createRefusals, IntPredicate refuseReplace) {
      this(createRefusals, refuseReplace,
          (temp, pidFile) -> new AccessDeniedException(temp.toString(), pidFile.toString(), null));
    }

    BusyFiles(int createRefusals, IntPredicate refuseReplace,
        BiFunction<Path, Path, IOException> refusal) {
      this.createRefusals = createRefusals;
      this.refuseReplace = refuseReplace;
      this.refusal = refusal;
    }

    @Override
    public void create(Path pidFile, String content) throws IOException {
      if (creates.incrementAndGet() <= createRefusals) {
        throw new AccessDeniedException(pidFile.toString());
      }
      DaemonManager.FILE_SYSTEM.create(pidFile, content);
    }

    @Override
    public void replace(Path temp, Path pidFile) throws IOException {
      if (manager != null) lockHeld.add(manager.holdsStartLock("test"));
      if (refuseReplace.test(replaces.incrementAndGet())) {
        throw refusal.apply(temp, pidFile);
      }
      DaemonManager.FILE_SYSTEM.replace(temp, pidFile);
    }
  }

  @Nested
  @DisplayName("a PID file that is open in another program (Windows refuses to replace it)")
  class BusyFileTests {

    private final DaemonManager.Launcher noLauncher = (command, environment, logFile) -> {
      throw new IOException("no server is launched in this test");
    };

    private DaemonManager managerOn(BusyFiles files, DaemonManager.Launcher launcher) {
      var manager = new DaemonManager(tempDir, Duration.ofSeconds(8), launcher, files);
      files.manager = manager;
      return manager;
    }

    private List<String> booting(int port) {
      return List.of(Long.toString(OWN_PID), Integer.toString(port),
          DaemonManager.BOOTING_MARKER);
    }

    private long temporaryFiles() throws IOException {
      try (var listing = Files.list(tempDir)) {
        return listing.filter(p -> p.getFileName().toString().endsWith(".pid.tmp")).count();
      }
    }

    @Test
    @DisplayName("a refused replace is tried again until the file is free")
    void replaceIsTriedAgain() throws IOException {
      var files = new BusyFiles(0, n -> n <= 3);
      var manager = managerOn(files, noLauncher);
      manager.writePidFile("test", 12345, 2363);
      assertEquals(4, files.replaces.get());
      assertEquals(record(12345, 2363), pidFileLines(manager, "test"));
      assertEquals(0, temporaryFiles());
    }

    @Test
    @DisplayName("a refused claim is tried again; a file that exists is an answer, not a refusal")
    void claimIsTriedAgain() throws IOException {
      var files = new BusyFiles(2, n -> false);
      var manager = managerOn(files, noLauncher);
      assertTrue(manager.claimPidFile("test", 2363));
      assertEquals(3, files.creates.get());
      assertEquals(claim(OWN_PID, 2363), pidFileLines(manager, "test"));

      assertFalse(manager.claimPidFile("test", 2363), "already claimed");
      assertEquals(4, files.creates.get(), "asked once");
    }

    @Test
    @DisplayName("a file that stays busy fails the write after its tries, and nothing is left")
    void givesUp() throws IOException {
      createManager().writePidFile("test", 111, 2363);
      var files = new BusyFiles(0, n -> true);
      var manager = managerOn(files, noLauncher);
      assertThrows(AccessDeniedException.class, () -> manager.writePidFile("test", 222, 2363));
      assertEquals(DaemonManager.FILE_BUSY_ATTEMPTS, files.replaces.get());
      assertEquals(record(111, 2363), pidFileLines(manager, "test"), "the old record is intact");
      assertEquals(0, temporaryFiles());
    }

    @Test
    @DisplayName("a sharing violation is tried again; a failure of another kind is not")
    void whichFailuresAreTriedAgain() throws IOException {
      // Windows reports a file open elsewhere as access denied or as a sharing violation
      var sharing = new BusyFiles(0, n -> n <= 2,
          (temp, pidFile) -> new FileSystemException(temp.toString(), pidFile.toString(),
              "The process cannot access the file because it is being used by another process"));
      managerOn(sharing, noLauncher).writePidFile("test", 12345, 2363);
      assertEquals(3, sharing.replaces.get());

      var missing = new BusyFiles(0, n -> true,
          (temp, pidFile) -> new NoSuchFileException(pidFile.toString()));
      var manager = managerOn(missing, noLauncher);
      assertThrows(NoSuchFileException.class, () -> manager.writePidFile("test", 1, 2363));
      assertEquals(1, missing.replaces.get(), "not a busy file: not tried again");
    }

    @Test
    @DisplayName("a start whose record is refused at first still starts its server")
    void startSurvivesABusyFile() throws Exception {
      // What failed on Windows: another start had the record open when this one replaced it,
      // and this start stopped the server it had just launched and reported failure
      int port = freePort();
      var launcher = new FakeLauncher(port, false);
      var files = new BusyFiles(0, n -> n <= 2);
      var manager = managerOn(files, launcher);
      try {
        assertTrue(manager.spawnDaemon("test", port, null));
        assertEquals(1, launcher.launches.get());
        assertEquals(record(OWN_PID, port), pidFileLines(manager, "test"));
      } finally {
        launcher.stop();
      }
    }

    @Test
    @DisplayName("a start replaces the record only while it holds the start lock")
    void recordsUnderTheLock() throws Exception {
      // Starts read the record under the lock; replacing it outside the lock is what collided
      int port = freePort();
      var launcher = new FakeLauncher(port, false);
      var files = new BusyFiles(0, n -> false);
      var manager = managerOn(files, launcher);
      try {
        assertTrue(manager.spawnDaemon("test", port, null));
        assertEquals(List.of(true, true), files.lockHeld,
            "the booting record, then the plain one");
      } finally {
        launcher.stop();
      }
    }

    @Test
    @DisplayName("a server that is up is not reported failed because its record cannot be updated")
    void runningServerIsNotReportedFailed() throws Exception {
      // The booting record is written; every replace after it is refused
      int port = freePort();
      var launcher = new FakeLauncher(port, false);
      var files = new BusyFiles(0, n -> n >= 2);
      var manager = managerOn(files, launcher);
      try {
        assertTrue(manager.spawnDaemon("test", port, null), "the server answers");
        assertEquals(booting(port), pidFileLines(manager, "test"));

        // A later start, the file free again, finds it running and settles the record
        var later = new DaemonManager(tempDir, Duration.ofSeconds(8), launcher);
        assertTrue(later.spawnDaemon("test", port, null));
        assertEquals(1, launcher.launches.get(), "no second server");
        assertEquals(record(OWN_PID, port), pidFileLines(later, "test"));
      } finally {
        launcher.stop();
      }
    }

    @Test
    @DisplayName("the record of a running server is kept when it cannot be updated")
    void runningServersRecordIsKept() throws Exception {
      // It used to be deleted as unreadable, and the next start spawned a second server
      var server = startServer();
      try {
        int port = server.getPort();
        createManager().writePidFile("test", OWN_PID, port, DaemonManager.BOOTING_MARKER);
        var manager = managerOn(new BusyFiles(0, n -> true), noLauncher);
        assertTrue(manager.isAlreadyRunning("test", port));
        assertEquals(booting(port), pidFileLines(manager, "test"));
      } finally {
        server.stop();
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

  // ==================== Restart, stop, and strangers ====================

  /**
   * Processes by ID as a test says they are: a fake daemon's ID is alive until it is ended; any
   * other ID is asked of the system, so this JVM's ID is alive and the dead ID is dead.
   */
  private static final class FakeProcesses implements DaemonManager.Processes {
    final java.util.Map<Long, Boolean> alive = new java.util.concurrent.ConcurrentHashMap<>();
    final List<Long> destroyed = new CopyOnWriteArrayList<>();
    final List<Long> forced = new CopyOnWriteArrayList<>();
    /** Whether a plain destroy ends the process, as a signal does for a JVM. */
    volatile boolean destroyWorks = true;
    /** Whether even a forced termination can end the process. */
    volatile boolean forceWorks = true;
    /** Runs when a process is ended, so the fake daemon it stands for can close its port. */
    volatile Runnable onEnd = () -> {};

    @Override
    public boolean isAlive(long pid) {
      var known = alive.get(pid);
      return known != null ? known : DaemonManager.SYSTEM_PROCESSES.isAlive(pid);
    }

    @Override
    public boolean destroy(long pid) {
      destroyed.add(pid);
      if (destroyWorks) end(pid);
      return true;
    }

    @Override
    public boolean destroyForcibly(long pid) {
      forced.add(pid);
      if (forceWorks) {
        end(pid);
      }
      return true;
    }

    void end(long pid) {
      alive.put(pid, false);
      onEnd.run();
    }
  }

  /**
   * A daemon as a start sees it from outside: answers {@code /health} with the version it is
   * told (none for a daemon older than 0.9.2), and {@code /stop} with the token, as the real
   * transport does, or 404 for a daemon too old to have the endpoint. It is "process"
   * {@link #pid} in a {@link FakeProcesses}, and ending that process closes its port.
   */
  private static final class FakeDaemon implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    final long pid;
    final AtomicInteger stopRequests = new AtomicInteger();
    final List<String> tokensPresented = new CopyOnWriteArrayList<>();
    /** Whether an accepted stop request ends the process, as it does in a real daemon. */
    volatile boolean exitsOnStop = true;
    volatile boolean managed;

    FakeDaemon(int port, long pid, String version, boolean hasStop, String token,
        FakeProcesses processes) throws IOException {
      this.pid = pid;
      processes.alive.put(pid, true);
      server = com.sun.net.httpserver.HttpServer.create(
          new java.net.InetSocketAddress("127.0.0.1", port), 0);
      processes.onEnd = () -> server.stop(0);
      server.createContext("/health", exchange -> {
        var body = "{\"status\":\"ok\",\"sessions\":0,\"pid\":" + pid
            + (version == null ? "" : ",\"version\":\"" + version + "\"") + ",\"managed\":" + managed + "}";
        reply(exchange, 200, body);
      });
      server.createContext("/stop", exchange -> {
        if (!hasStop) {
          reply(exchange, 404, "{\"error\":\"no such endpoint\"}");
          return;
        }
        stopRequests.incrementAndGet();
        var presented = exchange.getRequestHeaders().getFirst(HttpTransport.STOP_TOKEN_HEADER);
        tokensPresented.add(presented);
        if (!token.equals(presented)) {
          reply(exchange, 403, "{\"error\":\"wrong token\"}");
          return;
        }
        reply(exchange, 200, "{\"status\":\"stopping\"}");
        if (exitsOnStop) {
          new Thread(() -> processes.end(pid)).start();
        }
      });
      server.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status,
        String body) throws IOException {
      var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  /** A program that is not wpilog-mcp, on a port: it answers HTTP, but not as the server. */
  private static com.sun.net.httpserver.HttpServer stranger(int port) throws IOException {
    var server = com.sun.net.httpserver.HttpServer.create(
        new java.net.InetSocketAddress("127.0.0.1", port), 0);
    server.createContext("/", exchange -> {
      var bytes = "not here".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(404, bytes.length);
      try (var os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    server.start();
    return server;
  }

  @Nested
  @DisplayName("the version in /health: restart, stop, and strangers on the port")
  class RestartAndStopTests {
    private static final long FAKE_PID = 424242L;
    private final FakeProcesses processes = new FakeProcesses();

    private DaemonManager managerWith(DaemonManager.Launcher launcher, Duration timeout) {
      return new DaemonManager(tempDir, timeout, launcher, DaemonManager.FILE_SYSTEM, processes,
          org.triplehelix.wpilogmcp.Version.VERSION);
    }

    private final DaemonManager.Launcher noLauncher = (command, environment, logFile) -> {
      throw new IOException("no server is launched in this test");
    };

    @Test void managedServersAreNeverAdoptedStoppedOrReplaced() throws Exception {
      int port = freePort(); var launcher = new FakeLauncher(port, false);
      var manager = managerWith(launcher, Duration.ofMillis(300));
      for (String version : List.of(Version.VERSION, "0.1.0")) {
        try (var daemon = new FakeDaemon(port, FAKE_PID, version, true, "tok", processes)) {
          daemon.managed = true;
          assertFalse(manager.spawnDaemon("pit", port, null), "A managed server is not a start-owned daemon");
          assertFalse(Files.exists(manager.pidFilePath("pit")), "Do not adopt a managed server");
          assertFalse(manager.stopDaemon("pit", port), "A managed service has no PID file");
          manager.writePidFile("pit", FAKE_PID, port); // Even a stale claim grants no authority.
          assertFalse(manager.stopDaemon("pit"), "Use systemctl, never the stop token or a signal");
          assertFalse(manager.spawnDaemon("pit", port, null), "A newer JAR must not replace a managed server");
          assertEquals(0, daemon.stopRequests.get()); assertTrue(processes.destroyed.isEmpty());
          assertEquals(0, launcher.launches.get());
          Files.deleteIfExists(manager.pidFilePath("pit"));
        }
      }
    }

    @Test
    @DisplayName("the probe tells who holds a port: nobody, this server, or a stranger")
    void probeTellsWhoHoldsThePort() throws Exception {
      var manager = createManager();
      assertEquals(DaemonManager.Holder.NOBODY, manager.probe(freePort()).holder());

      var server = startServer();
      try {
        var probe = manager.probe(server.getPort());
        assertEquals(DaemonManager.Holder.THIS_SERVER, probe.holder());
        assertEquals(org.triplehelix.wpilogmcp.Version.VERSION, probe.version());
        assertEquals(OWN_PID, probe.pid());
      } finally {
        server.stop();
      }

      int port = freePort();
      var other = stranger(port);
      try {
        assertEquals(DaemonManager.Holder.STRANGER, manager.probe(port).holder());
        assertFalse(manager.healthCheck(port), "a stranger is not a healthy daemon");
      } finally {
        other.stop(0);
      }

      // Something that accepts the connection and never speaks HTTP
      try (var silent = new ServerSocket(0)) {
        assertEquals(DaemonManager.Holder.STRANGER, manager.probe(silent.getLocalPort()).holder());
      }
    }

    @Test
    @DisplayName("a daemon of this version is left running; one of another version is restarted")
    void restartsADaemonOfAnotherVersion() throws Exception {
      int port = freePort();
      var launcher = new FakeLauncher(port, false);
      var manager = managerWith(launcher, Duration.ofSeconds(8));
      manager.writeToken("test", "old-token");
      manager.writePidFile("test", FAKE_PID, port);

      // Same version: nothing to do
      try (var same = new FakeDaemon(port, FAKE_PID, org.triplehelix.wpilogmcp.Version.VERSION,
          true, "old-token", processes)) {
        assertTrue(manager.spawnDaemon("test", port, null));
        assertEquals(0, launcher.launches.get(), "a daemon of this version was restarted");
        assertEquals(0, same.stopRequests.get());
        assertEquals(record(FAKE_PID, port), pidFileLines(manager, "test"));
      }

      // Another version: stopped with the token from the file, and started again
      try (var old = new FakeDaemon(port, FAKE_PID, "0.1.0", true, "old-token", processes)) {
        assertTrue(manager.spawnDaemon("test", port, null));
        assertEquals(1, old.stopRequests.get(), "the old daemon was not asked to stop");
        assertEquals(List.of("old-token"), old.tokensPresented);
        assertEquals(1, launcher.launches.get(), "a new daemon was not started");
        assertEquals(record(OWN_PID, port), pidFileLines(manager, "test"));
        var token = manager.readToken("test");
        assertNotNull(token);
        assertNotEquals("old-token", token, "the new daemon gets a token of its own");
        assertEquals(List.of(token), launcher.tokens, "the daemon's environment holds its token");
      } finally {
        launcher.stop();
      }
    }

    @Test
    @DisplayName("a daemon from before /health carried a version is restarted, as a process")
    void restartsAnOlderDaemonAsAProcess() throws Exception {
      // It reports no version and has no /stop endpoint; its token file never existed
      int port = freePort();
      var launcher = new FakeLauncher(port, false);
      var manager = managerWith(launcher, Duration.ofSeconds(8));
      manager.writePidFile("test", FAKE_PID, port);
      try (var old = new FakeDaemon(port, FAKE_PID, null, false, "none", processes)) {
        assertTrue(manager.spawnDaemon("test", port, null));
        assertEquals(List.of(FAKE_PID), processes.destroyed, "it was not ended as a process");
        assertEquals(1, launcher.launches.get());
        assertEquals(record(OWN_PID, port), pidFileLines(manager, "test"));
      } finally {
        launcher.stop();
      }
    }

    @Test
    @DisplayName("two starts at once during a restart leave one daemon of this version")
    void twoStartsDuringARestart() throws Exception {
      for (int round = 0; round < 4; round++) {
        var runDir = Files.createDirectories(tempDir.resolve("round" + round));
        int port = freePort();
        var launcher = new FakeLauncher(port, false);
        var rounds = new FakeProcesses();
        var manager = new DaemonManager(runDir, Duration.ofSeconds(8), launcher,
            DaemonManager.FILE_SYSTEM, rounds, org.triplehelix.wpilogmcp.Version.VERSION);
        manager.writeToken("test", "tok");
        manager.writePidFile("test", FAKE_PID, port);
        var pool = Executors.newFixedThreadPool(2);
        try (var old = new FakeDaemon(port, FAKE_PID, "0.1.0", true, "tok", rounds)) {
          var go = new CountDownLatch(1);
          Future<Boolean> first = pool.submit(() -> {
            go.await();
            return manager.spawnDaemon("test", port, null);
          });
          Future<Boolean> second = pool.submit(() -> {
            go.await();
            return manager.spawnDaemon("test", port, null);
          });
          go.countDown();
          assertTrue(first.get(20, TimeUnit.SECONDS), "round " + round);
          assertTrue(second.get(20, TimeUnit.SECONDS), "round " + round);
          assertEquals(1, launcher.launches.get(), "round " + round + ": daemons started");
          assertEquals(1, old.stopRequests.get(), "round " + round + ": stop requests");
          assertEquals(record(OWN_PID, port), Files.readAllLines(manager.pidFilePath("test")));
        } finally {
          pool.shutdownNow();
          launcher.stop();
        }
      }
    }

    @Test
    @DisplayName("a restart keeps its claim after the old daemon exits and before the new spawn")
    void startAfterOldDaemonExitsJoinsTheRestart() throws Exception {
      int port = freePort();
      var launcher = new FakeLauncher(port, false);
      var restartWaitingForExit = new CountDownLatch(1);
      var continueRestart = new CountDownLatch(1);
      var secondCheckedRecord = new CountDownLatch(1);
      var allowLaunch = new CountDownLatch(1);
      var restartingThread = new AtomicReference<Thread>();
      var secondThread = new AtomicReference<Thread>();
      var managerRef = new AtomicReference<DaemonManager>();
      var secondClaimed = new AtomicBoolean();
      var controlledProcesses = new DaemonManager.Processes() {
        @Override
        public boolean isAlive(long pid) {
          if (pid == FAKE_PID && Thread.currentThread() == restartingThread.get()
              && !managerRef.get().holdsStartLock("test")) {
            // Pause outside the start lock, after /stop and before the restart cleans up.
            restartWaitingForExit.countDown();
            awaitLatch(continueRestart);
          }
          return processes.isAlive(pid);
        }

        @Override
        public boolean destroy(long pid) {
          return processes.destroy(pid);
        }

        @Override
        public boolean destroyForcibly(long pid) {
          return processes.destroyForcibly(pid);
        }
      };
      var files = new DaemonManager.PidFileWriter() {
        @Override
        public void create(Path pidFile, String content) throws IOException {
          boolean second = Thread.currentThread() == secondThread.get();
          try {
            DaemonManager.FILE_SYSTEM.create(pidFile, content);
            if (second) {
              secondClaimed.set(true);
            }
          } finally {
            if (second) {
              secondCheckedRecord.countDown();
            }
          }
        }

        @Override
        public void replace(Path temp, Path pidFile) throws IOException {
          DaemonManager.FILE_SYSTEM.replace(temp, pidFile);
        }
      };
      var manager = new DaemonManager(tempDir, Duration.ofSeconds(8),
          (command, environment, logFile) -> {
            // Neither caller can boot before the second has inspected the dead daemon's record.
            awaitLatch(allowLaunch);
            return launcher.launch(command, environment, logFile);
          }, files, controlledProcesses, Version.VERSION);
      managerRef.set(manager);
      manager.writeToken("test", "tok");
      manager.writePidFile("test", FAKE_PID, port);
      var pool = Executors.newFixedThreadPool(2);
      try (var old = new FakeDaemon(port, FAKE_PID, "0.1.0", true, "tok", processes)) {
        old.exitsOnStop = false;
        Future<Boolean> first = pool.submit(() -> {
          restartingThread.set(Thread.currentThread());
          return manager.spawnDaemon("test", port, null);
        });
        awaitLatch(restartWaitingForExit);
        var reserved = pidFileLines(manager, "test");
        processes.end(FAKE_PID); // Close the old listener before letting the second start look.
        Future<Boolean> second = pool.submit(() -> {
          secondThread.set(Thread.currentThread());
          return manager.spawnDaemon("test", port, null);
        });
        awaitLatch(secondCheckedRecord);
        continueRestart.countDown();
        allowLaunch.countDown();

        assertTrue(first.get(20, TimeUnit.SECONDS));
        assertTrue(second.get(20, TimeUnit.SECONDS));
        assertAll(
            () -> assertEquals(claim(OWN_PID, port), reserved,
                "the restart must belong to its live starter before the old daemon exits"),
            () -> assertFalse(secondClaimed.get(),
                "the second start removed the stopping record and claimed an in-flight restart"),
            () -> assertEquals(1, launcher.launches.get()));
      } finally {
        continueRestart.countDown();
        allowLaunch.countDown();
        pool.shutdownNow();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        launcher.stop();
      }
    }

    /** Latches choose the interleaving; the deadline only bounds a broken test's wait. */
    private void awaitLatch(CountDownLatch latch) {
      try {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "restart interleaving was not reached");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("restart interleaving interrupted", e);
      }
    }

    @Test
    @DisplayName("a restart that cannot stop the old daemon restores its stopping record")
    void failedRestartRestoresTheOldRecord() throws Exception {
      int port = freePort();
      var manager = managerWith(noLauncher, Duration.ofMillis(400));
      manager.writeToken("test", "tok");
      manager.writePidFile("test", FAKE_PID, port);
      processes.forceWorks = false;
      try (var old = new FakeDaemon(port, FAKE_PID, "0.1.0", true, "tok", processes)) {
        old.exitsOnStop = false;
        assertFalse(manager.spawnDaemon("test", port, null));
        assertEquals(List.of(Long.toString(FAKE_PID), Integer.toString(port),
            DaemonManager.STOPPING_MARKER), pidFileLines(manager, "test"),
            "a failed restart must not leave a live starter's claim blocking later starts");
      }
    }

    @Test
    @DisplayName("a start waits for a daemon of its own version, not for the one being replaced")
    void waitsForItsOwnVersion() throws Exception {
      // Another start claimed the file; the old daemon still answers while it is stopped
      int port = freePort();
      var manager = managerWith(noLauncher, Duration.ofMillis(400));
      assertTrue(manager.claimPidFile("test", port));
      try (var old = new FakeDaemon(port, FAKE_PID, "0.1.0", true, "tok", processes)) {
        assertFalse(manager.spawnDaemon("test", port, null),
            "the old daemon answering was taken for the new one");
      }
    }

    @Test
    @DisplayName("a port held by another program is reported, and nothing is started")
    void portHeldByAStranger() throws Exception {
      int port = freePort();
      var launcher = new FakeLauncher(port, false);
      var manager = managerWith(launcher, Duration.ofSeconds(8));
      var other = stranger(port);
      try {
        assertFalse(manager.spawnDaemon("test", port, null));
        assertEquals(0, launcher.launches.get(), "a daemon was spawned onto a held port");
        assertFalse(Files.exists(manager.pidFilePath("test")), "the claim must be released");
        assertFalse(Files.exists(manager.tokenPath("test")));
      } finally {
        other.stop(0);
      }
    }

    @Test
    @DisplayName("a daemon of this version on the port with no record is recorded, not started")
    void recordsADaemonWithoutARecord() throws Exception {
      var server = startServer();
      try {
        int port = server.getPort();
        var launcher = new FakeLauncher(port, false);
        var manager = managerWith(launcher, Duration.ofSeconds(8));
        assertTrue(manager.spawnDaemon("test", port, null));
        assertEquals(0, launcher.launches.get());
        assertEquals(record(OWN_PID, port), pidFileLines(manager, "test"));
      } finally {
        server.stop();
      }
    }

    @Test
    @DisplayName("stop asks the daemon to stop with the token, waits, and removes its files")
    void stopEndsTheDaemon() throws Exception {
      int port = freePort();
      var manager = managerWith(noLauncher, Duration.ofSeconds(8));
      manager.writeToken("test", "tok");
      manager.writePidFile("test", FAKE_PID, port);
      try (var daemon = new FakeDaemon(port, FAKE_PID, org.triplehelix.wpilogmcp.Version.VERSION,
          true, "tok", processes)) {
        assertTrue(manager.stopDaemon("test"));
        assertEquals(List.of("tok"), daemon.tokensPresented);
        assertTrue(processes.destroyed.isEmpty(), "it stopped on request; nothing to end");
        assertFalse(processes.isAlive(FAKE_PID));
        assertFalse(Files.exists(manager.pidFilePath("test")));
        assertFalse(Files.exists(manager.tokenPath("test")));
      }
    }

    @Test
    @DisplayName("stop when nothing runs succeeds and leaves no files")
    void stopWhenNothingRuns() throws Exception {
      var manager = managerWith(noLauncher, Duration.ofSeconds(8));
      manager.writeToken("test", "tok");
      assertTrue(manager.stopDaemon("test"));
      assertFalse(Files.exists(manager.tokenPath("test")), "a stale token file is removed");

      manager.writePidFile("test", DEAD_PID, 2363);
      assertTrue(manager.stopDaemon("test"));
      assertFalse(Files.exists(manager.pidFilePath("test")), "a stale record is removed");
    }

    @Test
    @DisplayName("a daemon that refuses the token, or has no /stop, is ended as a process")
    void stopFallsBackToTheProcess() throws Exception {
      int port = freePort();
      var manager = managerWith(noLauncher, Duration.ofSeconds(8));
      manager.writeToken("test", "wrong");
      manager.writePidFile("test", FAKE_PID, port);
      try (var daemon = new FakeDaemon(port, FAKE_PID, org.triplehelix.wpilogmcp.Version.VERSION,
          true, "right", processes)) {
        assertTrue(manager.stopDaemon("test"));
        assertEquals(1, daemon.stopRequests.get());
        assertEquals(List.of(FAKE_PID), processes.destroyed);
        assertFalse(Files.exists(manager.pidFilePath("test")));
      }
    }

    @Test
    @DisplayName("a daemon that accepts the stop but does not exit is ended without asking")
    void stopEndsAStuckDaemon() throws Exception {
      int port = freePort();
      var manager = managerWith(noLauncher, Duration.ofMillis(400));
      manager.writeToken("test", "tok");
      manager.writePidFile("test", FAKE_PID, port);
      processes.destroyWorks = false;
      try (var daemon = new FakeDaemon(port, FAKE_PID, org.triplehelix.wpilogmcp.Version.VERSION,
          true, "tok", processes)) {
        daemon.exitsOnStop = false;
        assertTrue(manager.stopDaemon("test"));
        assertEquals(1, daemon.stopRequests.get());
        assertEquals(List.of(FAKE_PID), processes.forced, "it was not ended forcibly");
        assertFalse(Files.exists(manager.pidFilePath("test")));
      }
    }

    @Test
    @DisplayName("a daemon being stopped is not running, until its stopper has been gone too long")
    void stoppingRecord() throws Exception {
      var server = startServer();
      try {
        int port = server.getPort();
        var manager = createManager();
        manager.writePidFile("test", OWN_PID, port, DaemonManager.STOPPING_MARKER);
        var stopping = List.of(Long.toString(OWN_PID), Integer.toString(port),
            DaemonManager.STOPPING_MARKER);

        assertFalse(manager.isAlreadyRunning("test", port), "a daemon on its way out is not running");
        assertEquals(stopping, pidFileLines(manager, "test"), "and its record is kept");
        assertFalse(manager.claimPidFile("test", port), "and the file cannot be claimed");

        // The stopper died long ago and the daemon still answers: it is running after all
        Files.setLastModifiedTime(manager.pidFilePath("test"),
            FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));
        assertTrue(manager.isAlreadyRunning("test", port));
        assertEquals(record(OWN_PID, port), pidFileLines(manager, "test"));
      } finally {
        server.stop();
      }
    }

    @Test
    @DisplayName("the token file is readable by its owner alone where permissions exist")
    void tokenFileIsPrivate() throws Exception {
      var manager = createManager();
      manager.writeToken("test", "tok");
      assertEquals("tok", manager.readToken("test"));
      var path = manager.tokenPath("test");
      org.junit.jupiter.api.Assumptions.assumeTrue(
          Files.getFileStore(path).supportsFileAttributeView("posix"),
          "no POSIX permissions on this file system");
      assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
          Files.getPosixFilePermissions(path)));
    }
  }
}
