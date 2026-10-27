/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.ChildJvms;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

/** A fork observes actual scan messages without depending on a new cache-only test API. */
class PersistentScanOwnerTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "reuse persisted index with new manager = {0}")
  @ValueSource(booleans = {false, true})
  void aSecondOpenHydratesTheIndexWithoutAnotherScan(boolean newManager) throws Exception {
    String configured = System.getenv("WPILOG_DISK_CACHE_DIR");
    assertNotNull(configured, "The Gradle test task must supply its isolated cache directory");
    var cache = Path.of(configured).resolve("scan-owner-" + UUID.randomUUID()).toAbsolutePath();
    Files.createDirectories(cache);
    var output = directory.resolve("scan.log");
    var process = new ProcessBuilder(ChildJvms.JAVA, ChildJvms.HEADLESS,
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
        "-Dorg.slf4j.simpleLogger.log.org.triplehelix.wpilogmcp.log.LazyParsedLog=info",
        "-cp", System.getProperty("java.class.path"), OpenProcess.class.getName(),
        directory.toString(), cache.toString(), Boolean.toString(newManager))
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Persistent scan probe timed out");
    } finally {
      process.destroyForcibly();
    }
    String transcript = Files.readString(output);
    assertEquals(0, process.exitValue(), transcript);
    assertTrue(transcript.contains("CHECKED both independent answers"), transcript);
    long scans = transcript.lines().filter(line -> line.contains("Scanned synthetic-index.wpilog:")).count();
    long indexes;
    try (var files = Files.list(cache)) {
      indexes = files.filter(path -> path.getFileName().toString().endsWith("-scan.idx")).count();
    }
    long actualIndexes = indexes;
    assertAll(
        () -> assertEquals(1, scans, "An evicted second open must not scan again; transcript:\n" + transcript),
        () -> assertEquals(1, actualIndexes, "A completed scan must publish one real persistent index"));
  }

  public static class OpenProcess {
    public static void main(String[] args) throws Exception {
      var root = Path.of(args[0]).toRealPath();
      var cache = Path.of(args[1]);
      var file = root.resolve("synthetic-index.wpilog");
      try (var writer = new WpilogWriter(file, "persistent scan owner fixture")) {
        int entry = writer.start("/Value", "double", "{\"unit\":\"synthetic\"}", 0);
        writer.append(entry, 2_000_000, WpilogWriter.encodeDouble(20));
        writer.append(entry, 1_000_000, WpilogWriter.encodeDouble(10));
        writer.append(entry, 1_000_000, WpilogWriter.encodeDouble(11));
        writer.append(entry, 3_000_000, WpilogWriter.encodeDouble(30));
      }
      var manager = manager(root, cache);
      try {
        checkAnswer(manager, file);
        assertTrue(manager.unloadLog(file.toString()), "First mapping must leave the loaded cache");
        if (Boolean.parseBoolean(args[2])) {
          manager.shutdown();
          manager = manager(root, cache);
        }
        checkAnswer(manager, file);
        System.out.println("CHECKED both independent answers");
      } finally {
        manager.shutdown();
      }
    }

    private static LogManager manager(Path root, Path cache) {
      var manager = new LogManager();
      manager.getCacheDirectory().setOverride(cache.toString());
      manager.getDiskCache().setEnabled(true);
      manager.getSyncDiskCache().setEnabled(false);
      manager.addAllowedDirectory(root);
      return manager;
    }

    private static void checkAnswer(LogManager manager, Path file) throws Exception {
      try (var use = manager.acquireReadOnly(file.toString())) {
        var log = use.log();
        assertEquals(List.of("/Value"), List.copyOf(log.entries().keySet()));
        assertEquals(new EntryInfo(1, "/Value", "double", "{\"unit\":\"synthetic\"}"),
            log.entries().get("/Value"));
        var values = log.values().get("/Value");
        assertEquals(List.of(1_000_000L, 1_000_000L, 2_000_000L, 3_000_000L),
            values.stream().map(TimestampedValue::timestampUs).toList());
        assertEquals(List.of(10.0, 11.0, 20.0, 30.0),
            values.stream().map(TimestampedValue::value).toList());
        assertEquals(4, log.sampleCount("/Value"));
        assertEquals(1.0, log.minTimestamp());
        assertEquals(3.0, log.maxTimestamp());
        assertFalse(log.truncated());
        assertFalse(log.damaged());
      }
    }
  }
}
