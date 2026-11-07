/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class FileNameEncodingTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
  @Test void openingTwoStoresWarnsOnlyOnceInAFreshJvm() throws Exception {
    var output = directory.resolve("warning.txt");
    var process = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "-cp", System.getProperty("java.class.path"), WarningProcess.class.getName(), directory.toString())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try { assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); }
    finally { process.destroyForcibly(); }
    String messages = java.nio.file.Files.readString(output);
    assertEquals(0, process.exitValue(), messages);
    assertEquals(1, messages.lines().filter(line -> line.contains("non-ASCII file names")).count(), messages);
  }
  public static class WarningProcess {
    public static void main(String[] args) {
      // Changes warning input only; the native filesystem encoding stays fixed at JVM startup.
      System.setProperty("sun.jnu.encoding", "US-ASCII");
      var root = java.nio.file.Path.of(args[0]);
      var security = new org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator();
      try (var first = new LogStore(root.resolve("first"), security);
          var second = new LogStore(root.resolve("second"), security)) {}
    }
  }
  @Test void nativeEncodingRefusalNamesTheEncodingAndRemedyWithoutJavaText() {
    var error = assertThrows(IllegalArgumentException.class, () -> FileNameEncoding.check(
        "uploaded café.wpilog", "US-ASCII", name -> { throw new InvalidPathException(name, "raw Java detail"); }));
    assertTrue(error.getMessage().contains("US-ASCII"), error.getMessage());
    assertTrue(error.getMessage().contains("start the server with a UTF-8 locale"), error.getMessage());
    assertFalse(error.getMessage().contains("raw Java detail"), error.getMessage());
    assertFalse(error instanceof InvalidPathException);
  }
  @Test void storeComponentAlsoExplainsMalformedUnicode() {
    var error = assertThrows(IllegalArgumentException.class, () -> StoreFiles.component("bad-\ud800.wpilog"));
    assertTrue(error.getMessage().contains(System.getProperty("sun.jnu.encoding")), error.getMessage());
    assertTrue(error.getMessage().contains("start the server with a UTF-8 locale"), error.getMessage());
  }
  @Test void warningIsOnceForStoresAndSilentForUtf8IncludingItsAliases() {
    var once = new AtomicBoolean(); var lines = new ArrayList<String>();
    FileNameEncoding.warnIfNeeded("UTF-8", once, lines::add);
    FileNameEncoding.warnIfNeeded("UTF8", once, lines::add);
    assertTrue(lines.isEmpty()); assertFalse(once.get());
    FileNameEncoding.warnIfNeeded("US-ASCII", once, lines::add);
    FileNameEncoding.warnIfNeeded("US-ASCII", once, lines::add);
    assertEquals(1, lines.size());
    assertTrue(lines.get(0).contains("US-ASCII")); assertTrue(lines.get(0).contains("non-ASCII file names"));
    assertTrue(lines.get(0).contains("start the server with a UTF-8 locale"));
  }
}
