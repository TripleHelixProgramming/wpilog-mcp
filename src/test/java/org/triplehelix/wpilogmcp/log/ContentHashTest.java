/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureWriter;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

/**
 * A log names the content its scan index proved, which column files are keyed by: the SHA-256
 * of the file's bytes, whether the index was written by this read or reused by a later one, and
 * nothing when the disk cache is off or the file changed while it was scanned.
 */
class ContentHashTest {
  @TempDir Path directory;
  private Path source;
  private LogManager manager;

  @BeforeEach
  void setup() throws Exception {
    directory = directory.toRealPath();
    source = directory.resolve("content.wpilog");
    try (var w = new FixtureWriter(source, "")) {
      for (int i = 0; i < 50; i++) w.dbl("/v", 1 + i * 0.02, i * 0.5);
    }
    manager = IsolatedLogManagers.create(directory, "content-hash");
  }

  @AfterEach
  void stop() {
    if (manager != null) manager.shutdown();
  }

  private String sha256() throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
  }

  @Test
  void aLogNamesTheContentItsScanIndexProved() throws Exception {
    try (var use = manager.acquire(source.toString())) {
      assertEquals(sha256(), ((LazyParsedLog) use.log()).contentSha256().orElseThrow(), "the scan that wrote the index");
    }
    manager.unloadLog(source.toString());
    try (var use = manager.acquire(source.toString())) {
      assertEquals(sha256(), ((LazyParsedLog) use.log()).contentSha256().orElseThrow(), "a later read that reused it");
    }
  }

  /**
   * A file rewritten in place while it is scanned, with its size and time restored, keeps every
   * attribute but not its content. The proof taken after the scan finds the change, so the log
   * names no content and nothing is built from bytes the name would not describe.
   */
  @Test
  void aLogRewrittenWhileItWasScannedNamesNoContent() throws Exception {
    var once = new AtomicBoolean();
    manager.scanObserver((phase, path) -> {
      if (phase == LazyParsedLog.OpenPhase.AFTER_SCAN && !once.getAndSet(true)) rewriteSampleInPlace(source);
    });
    try (var use = manager.acquire(source.toString())) {
      assertTrue(once.get(), "the file was rewritten during the scan");
      assertTrue(((LazyParsedLog) use.log()).contentSha256().isEmpty());
    }
  }

  /** Changes the sample 12.5 to 99, keeping the file's size and modification time. */
  private static void rewriteSampleInPlace(Path file) throws IOException {
    var before = FileSnapshot.of(file);
    byte[] bytes = Files.readAllBytes(file);
    byte[] from = WpilogWriter.encodeDouble(12.5);
    byte[] to = WpilogWriter.encodeDouble(99);
    int found = -1;
    for (int i = 0; i <= bytes.length - from.length; i++) {
      if (Arrays.equals(from, Arrays.copyOfRange(bytes, i, i + from.length))) {
        assertEquals(-1, found, "the sample occurs once");
        found = i;
      }
    }
    assertTrue(found > 0, "the sample is in the file");
    System.arraycopy(to, 0, bytes, found, to.length);
    Files.write(file, bytes);
    Files.setLastModifiedTime(file, before.modified());
    assertTrue(before.sameAs(FileSnapshot.of(file)), "the change is hidden from the file's attributes");
  }

  @Test
  void aLogReadWithTheDiskCacheOffNamesNoContent() throws Exception {
    manager.getDiskCache().setEnabled(false);
    try (var use = manager.acquire(source.toString())) {
      assertTrue(((LazyParsedLog) use.log()).contentSha256().isEmpty());
    }
  }
}
