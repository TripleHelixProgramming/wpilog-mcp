/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/**
 * A file that is not a readable log is the caller's data, not a server fault: every tool says
 * what the file is, never "Internal error". Real robots produce such files (two of the 509 logs
 * other teams have published are one empty file and one that is all zeros).
 */
class UnreadableLogTest extends ToolTestBase {

  @TempDir Path dir;
  @TempDir Path elsewhere;
  private java.util.Set<Path> savedAllowed;

  @Override
  protected void registerTools(ToolRegistry registry) {
    WpilogTools.registerAll(registry);
  }

  @BeforeEach
  void allowTheDirectory() {
    savedAllowed = LogManager.getInstance().getAllowedDirectories();
    LogManager.getInstance().addAllowedDirectory(dir);
  }

  /** Other test classes rely on the allowed directories they set once: leave them as found. */
  @AfterEach
  void restoreAllowedDirectories() {
    var manager = LogManager.getInstance();
    manager.clearAllowedDirectories();
    savedAllowed.forEach(manager::addAllowedDirectory);
  }

  /** The error each of several tools gives for the file. */
  private String errorFor(Path file) throws Exception {
    String first = null;
    for (var tool : List.of("list_entries", "get_match_phases", "get_statistics", "can_health")) {
      var args = new JsonObject();
      args.addProperty("path", file.toString());
      if (tool.equals("get_statistics")) args.addProperty("name", "/x");
      var r = findTool(tool).execute(args).getAsJsonObject();
      assertFalse(r.get("success").getAsBoolean(), tool + ": " + r);
      assertEquals("error", r.get("status").getAsString(), tool + ": " + r);
      var error = r.get("error").getAsString();
      assertFalse(error.startsWith("Internal error"), tool + ": " + error);
      if (first == null) first = error;
      assertEquals(first, error, "every tool gives the same explanation");
    }
    return first;
  }

  @Test
  @DisplayName("an empty file: the error says it is empty")
  void emptyFile() throws Exception {
    var file = Files.createFile(dir.resolve("FRC_20250417_165906.wpilog"));
    var error = errorFor(file);
    assertTrue(error.startsWith("Invalid WPILOG file: "), error);
    assertTrue(error.contains("the file is empty, 0 bytes"), error);
  }

  @Test
  @DisplayName("a file of zeros: the error says its first bytes are zero and how large it is")
  void zeroFilledFile() throws Exception {
    var file = dir.resolve("FRC_20240301_215318_PACA_Q49.wpilog");
    Files.write(file, new byte[100_000]);
    var error = errorFor(file);
    assertTrue(error.contains("does not start with the WPILOG header"), error);
    assertTrue(error.contains("its first 4096 bytes are all zero, of 100000 bytes"), error);
  }

  @Test
  @DisplayName("another kind of file: the error shows how it starts")
  void anotherFormat() throws Exception {
    var file = dir.resolve("notes.wpilog");
    Files.writeString(file, "hello, this is not a log file");
    var error = errorFor(file);
    assertTrue(error.contains("it starts with 68 65 6c 6c 6f 2c"), error);
  }

  @Test
  @DisplayName("an unsupported version and a too-short file are named as such")
  void versionAndShortFile() throws Exception {
    // WPILib's reader accepts version 1.0 and later; a header with version 0.0 is not one
    var version = dir.resolve("version0.wpilog");
    Files.write(version, new byte[] {'W', 'P', 'I', 'L', 'O', 'G', 0x00, 0x00, 0, 0, 0, 0, 0, 0});
    assertTrue(errorFor(version).contains("version bytes 00 00"), errorFor(version));
    var tiny = dir.resolve("tiny.wpilog");
    Files.write(tiny, new byte[] {1, 2, 3});
    assertTrue(errorFor(tiny).contains("3 bytes, too short for a WPILOG header"), errorFor(tiny));
  }

  @Test
  @DisplayName("a path outside the allowed directories: Access denied, not an internal error")
  void outsideTheAllowedDirectories() throws Exception {
    var file = Files.createFile(elsewhere.resolve("FRC_20260101_000000.wpilog"));
    var error = errorFor(file);
    assertTrue(error.startsWith("Access denied: path is outside configured log directories"), error);
  }

  @Test
  @DisplayName("a missing file: File not found, not an internal error")
  void missingFile() throws Exception {
    var error = errorFor(dir.resolve("never_written.wpilog"));
    assertTrue(error.startsWith("File not found: "), error);
  }
}
