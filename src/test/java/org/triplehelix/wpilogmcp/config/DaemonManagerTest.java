package org.triplehelix.wpilogmcp.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("DaemonManager")
class DaemonManagerTest {

  @TempDir
  Path tempDir;

  private DaemonManager createManager() {
    return new DaemonManager(tempDir);
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
      manager.writePidFile("test", 999999999L, 2363);

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
