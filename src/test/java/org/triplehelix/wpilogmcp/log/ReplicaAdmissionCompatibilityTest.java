/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Replica checks augment established admission; they cannot silently replace the legacy cache
 * policy. A fresh JVM excludes the process-wide lease state left by transport tests. */
class ReplicaAdmissionCompatibilityTest {
  @TempDir Path temp;
  @Test void aLegacyCachedNonStoreLogKeepsItsAlreadyAdmittedReadPolicy() throws Exception {
    var output = temp.resolve("legacy-cache.txt");
    var builder = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "-cp", System.getProperty("java.class.path"), LegacyProcess.class.getName(), temp.toRealPath().toString());
    builder.environment().put("WPILOG_DISK_CACHE_DIR", temp.resolve("cache").toString());
    var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try { assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); }
    finally { process.destroyForcibly(); }
    String messages = java.nio.file.Files.readString(output);
    assertEquals(0, process.exitValue(), messages);
    assertTrue(messages.lines().anyMatch("admitted value=7"::equals), messages);
  }

  public static class LegacyProcess {
    public static void main(String[] args) throws Exception {
      var root = Path.of(args[0]);
      var manager = new LogManager();
      try {
        manager.addAllowedDirectory(root.resolve("configured"));
        String path = root.resolve("outside-cached.wpilog").toString();
        var log = new ParsedLog(path, Map.of("value", new EntryInfo(1, "value", "int64", "")),
            Map.of("value", List.of(new TimestampedValue(0, 7L))), 0, 0);
        manager.testPutLog(path, log);
        try (var read = manager.acquireReadOnly(path)) {
          assertSame(log, read.log(), "the replica lookup must preserve prior cached-path admission");
          assertEquals(7L, read.log().values().get("value").get(0).value());
          System.out.println("admitted value=7");
        }
      } finally { manager.shutdown(); }
    }
  }
}
