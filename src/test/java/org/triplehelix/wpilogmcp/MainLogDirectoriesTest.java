/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;

/** Several log directories from -logdir, WPILOG_DIR, and the configuration file. */
@DisplayName("Main log directories")
class MainLogDirectoriesTest {

  @TempDir
  Path tempDir;

  @Nested
  @DisplayName("splitPathList")
  class SplitPathList {

    @Test
    @DisplayName("splits on the separator, in order")
    void splits() {
      assertEquals(List.of("/a", "/b/c"), Main.splitPathList("/a:/b/c", ":"));
      assertEquals(List.of("C:\\logs", "D:\\usb"), Main.splitPathList("C:\\logs;D:\\usb", ";"));
    }

    @Test
    @DisplayName("one directory, null, and empty")
    void edges() {
      assertEquals(List.of("/a"), Main.splitPathList("/a", ":"));
      assertEquals(List.of(), Main.splitPathList(null, ":"));
      assertEquals(List.of(), Main.splitPathList("", ":"));
    }

    @Test
    @DisplayName("trims entries and drops blank ones")
    void blanks() {
      assertEquals(List.of("/a", "/b"), Main.splitPathList(" /a ::  : /b:", ":"));
      assertEquals(List.of(), Main.splitPathList(" : ", ":"));
    }

    @Test
    @DisplayName("uses the platform's path separator by default")
    void platformSeparator() {
      var sep = java.io.File.pathSeparator;
      assertEquals(List.of("/a", "/b"), Main.splitPathList("/a" + sep + "/b"));
    }
  }

  @Nested
  @DisplayName("configureLogDirectories")
  class Configure {

    private List<Path> savedDirectories;
    private Set<Path> savedAllowed;

    @BeforeEach
    void save() {
      savedDirectories = LogDirectory.getInstance().getLogDirectories();
      savedAllowed = LogManager.getInstance().getAllowedDirectories();
    }

    @AfterEach
    void restore() {
      LogDirectory.getInstance().setLogDirectories(
          savedDirectories.stream().map(Path::toString).toList());
      var logManager = LogManager.getInstance();
      logManager.clearAllowedDirectories();
      savedAllowed.forEach(logManager::addAllowedDirectory);
    }

    @Test
    @DisplayName("lists from, and allows loading from, every directory, existing or not")
    void listsAndAllowsEach() throws Exception {
      var a = Files.createDirectories(tempDir.resolve("a"));
      var later = tempDir.resolve("usb-not-mounted");
      LogManager.getInstance().clearAllowedDirectories();

      Main.configureLogDirectories(List.of(a.toString(), later.toString()));

      assertEquals(List.of(a, later), LogDirectory.getInstance().getLogDirectories());
      var allowed = LogManager.getInstance().getAllowedDirectories();
      assertTrue(allowed.contains(a.toRealPath()), allowed.toString());
      assertTrue(allowed.contains(later.getParent().toRealPath().resolve(later.getFileName())), allowed.toString());
    }
  }

  @Nested
  @DisplayName("in a fresh JVM")
  class FreshJvm {

    @Test
    @DisplayName("-logdir repeated configures each directory")
    void repeatedFlag() throws Exception {
      var a = Files.createDirectories(tempDir.resolve("a"));
      var b = Files.createDirectories(tempDir.resolve("b"));
      var output = MainProcess.run(tempDir, List.of("-logdir", a.toString(), "-logdir",
          b.toString(), "-diskcachedisable"), Map.of());
      assertTrue(output.contains("Configuring log directory: " + a), output);
      assertTrue(output.contains("Configuring log directory: " + b), output);
    }

    @Test
    @DisplayName("WPILOG_DIR holds several directories, separated as in PATH")
    void environmentList() throws Exception {
      var a = Files.createDirectories(tempDir.resolve("a"));
      var b = Files.createDirectories(tempDir.resolve("b"));
      var output = MainProcess.run(tempDir, List.of("-diskcachedisable"),
          Map.of("WPILOG_DIR", a + java.io.File.pathSeparator + b));
      assertTrue(output.contains("Configuring log directory: " + a), output);
      assertTrue(output.contains("Configuring log directory: " + b), output);
    }

    @Test
    @DisplayName("-logdir replaces WPILOG_DIR's directories rather than adding to them")
    void flagReplacesEnvironment() throws Exception {
      var fromEnv = Files.createDirectories(tempDir.resolve("env"));
      var fromFlag = Files.createDirectories(tempDir.resolve("flag"));
      var output = MainProcess.run(tempDir, List.of("-logdir", fromFlag.toString(),
          "-diskcachedisable"), Map.of("WPILOG_DIR", fromEnv.toString()));
      assertTrue(output.contains("Configuring log directory: " + fromFlag), output);
      assertFalse(output.contains("Configuring log directory: " + fromEnv), output);
    }

    @Test
    @DisplayName("a directory that does not exist is configured with a warning")
    void missingWarned() throws Exception {
      var missing = tempDir.resolve("usb");
      var output = MainProcess.run(tempDir, List.of("-logdir", missing.toString(),
          "-diskcachedisable"), Map.of());
      assertTrue(output.contains("Log directory " + missing + " does not exist"), output);
    }

    @Test
    @DisplayName("logdir as a list in the configuration file configures each directory")
    void configList() throws Exception {
      var a = Files.createDirectories(tempDir.resolve("a"));
      var b = Files.createDirectories(tempDir.resolve("b"));
      var config = tempDir.resolve("servers.yaml");
      Files.writeString(config, """
          diskcachedisable: true
          servers:
            both:
              transport: stdio
              logdir:
                - "%s"
                - "%s"
          """.formatted(a.toString().replace("\\", "\\\\"), b.toString().replace("\\", "\\\\")));
      var output = MainProcess.run(tempDir, List.of("start", "both", "--config",
          config.toString()), Map.of());
      assertTrue(output.contains("Configuring log directory: " + a), output);
      assertTrue(output.contains("Configuring log directory: " + b), output);
    }
  }
}
