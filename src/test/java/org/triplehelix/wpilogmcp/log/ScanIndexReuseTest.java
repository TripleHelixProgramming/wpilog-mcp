/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

/**
 * A finished file's index is reused by how much its attributes still vouch for it. Unchanged
 * size, modification time, file identity and creation time reuse it without reading the file;
 * a moved modification time, or attributes the file system cannot vouch for (no file identity,
 * whole-second times), are checked by the content's CRC32C first; anything else is proved by
 * SHA-256 and scanned again. Expectations follow what this file system reports, so the same
 * tests hold where Java exposes no file identity.
 */
class ScanIndexReuseTest {
  @TempDir Path directory;
  private Path source;
  private LogManager manager;
  private final Map<String, Integer> phases = new ConcurrentHashMap<>();

  @BeforeEach void setup() throws Exception {
    directory = directory.toRealPath();
    source = directory.resolve("reuse.wpilog");
    write(source, false);
    String taskCache = System.getenv("WPILOG_DISK_CACHE_DIR");
    assertNotNull(taskCache, "The Gradle test task must supply its isolated cache directory");
    manager = new LogManager();
    manager.getCacheDirectory().setOverride(Path.of(taskCache).resolve("scan-reuse-" + UUID.randomUUID()).toString());
    manager.getDiskCache().setEnabled(true);
    manager.getSyncDiskCache().setEnabled(false);
    manager.addAllowedDirectory(directory);
    manager.scanObserver((phase, path) -> phases.merge(phase.name(), 1, Integer::sum));
  }

  @AfterEach void stop() { if (manager != null) manager.shutdown(); }

  @Test void anUnchangedFileReopensFromItsIndexByItsAttributes() throws Exception {
    indexAndUnload();
    long scans = reopen("/Value");
    assertEquals(0, scans, "An unchanged file reuses its index");
    assertEquals(0, phases.getOrDefault("BEFORE_HASH", 0), "An unchanged file needs no SHA-256 proof");
    assertEquals(vouches(source) ? 0 : 1, phases.getOrDefault("BEFORE_CHECKSUM", 0),
        "Only attributes the file system cannot vouch for need the checksum");
  }

  @Test void aTouchedFileIsCheckedOnceByItsChecksumAndThenTrusted() throws Exception {
    indexAndUnload();
    var before = FileSnapshot.of(source);
    Files.setLastModifiedTime(source, FileTime.from(before.modified().toInstant().plusMillis(1_500)));
    long scans = reopen("/Value");
    assertEquals(0, scans, "Unchanged content keeps its index after a moved modification time");
    assertEquals(1, phases.getOrDefault("BEFORE_CHECKSUM", 0), "A moved time is checked by checksum");
    assertEquals(0, phases.getOrDefault("BEFORE_HASH", 0), "A matching checksum needs no SHA-256 proof");
    assertTrue(manager.unloadLog(source.toString()));
    phases.clear();
    scans = reopen("/Value");
    assertEquals(0, scans);
    assertEquals(vouches(source) ? 0 : 1, phases.getOrDefault("BEFORE_CHECKSUM", 0),
        "After the checksum matched, the index records the new time and the next open trusts it");
  }

  @Test void aTouchedFileWhoseBirthAndModificationTimesMatchedStillFindsItsIndex() throws Exception {
    // A creation time equal to the modification time counts as unreported; after a touch the two
    // differ. The key must not depend on that, or the touched file would lose its index.
    var created = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes.class).creationTime();
    Files.setLastModifiedTime(source, created);
    indexAndUnload();
    Files.setLastModifiedTime(source, FileTime.from(created.toInstant().plusMillis(1_500)));
    long scans = reopen("/Value");
    assertEquals(0, scans, "A touched file keeps its index whatever its creation time reports");
    assertEquals(0, phases.getOrDefault("BEFORE_HASH", 0), "A matching checksum needs no SHA-256 proof");
  }

  @Test void aRewriteThatMovesTheModificationTimeIsScannedAgain() throws Exception {
    indexAndUnload();
    var before = FileSnapshot.of(source);
    rewriteDeclaration(source);
    Files.setLastModifiedTime(source, FileTime.from(before.modified().toInstant().plusMillis(2_500)));
    long scans = reopen("/Other");
    assertEquals(1, scans, "Changed content runs a new scan");
    assertEquals(1, phases.getOrDefault("BEFORE_HASH", 0), "A failed checksum falls through to the full proof");
  }

  @Test void aRewriteWithEveryAttributeRestoredIsTrustedWhereTheFileSystemVouches() throws Exception {
    indexAndUnload();
    var before = FileSnapshot.of(source);
    rewriteDeclaration(source);
    Files.setLastModifiedTime(source, before.modified());
    assertTrue(before.sameAs(FileSnapshot.of(source)), "The rewrite must hide from the attributes");
    if (vouches(source)) {
      // The accepted trade-off: identical attributes are trusted, so the old declarations remain.
      long scans = reopen("/Value");
      assertEquals(0, scans);
      assertEquals(0, phases.getOrDefault("BEFORE_HASH", 0) + phases.getOrDefault("BEFORE_CHECKSUM", 0),
          "Trusted attributes read nothing of the file before reuse");
    } else {
      long scans = reopen("/Other");
      assertEquals(1, scans, "Attributes that cannot vouch are checked, and the change is found");
    }
  }

  @Test void aWholeSecondModificationTimeAlwaysNeedsTheChecksum() throws Exception {
    Files.setLastModifiedTime(source, FileTime.from(Instant.ofEpochSecond(1_700_000_000L)));
    indexAndUnload();
    long scans = reopen("/Value");
    assertEquals(0, scans);
    assertEquals(1, phases.getOrDefault("BEFORE_CHECKSUM", 0),
        "A time without a fraction of a second could hide a same-second rewrite");
    assertEquals(0, phases.getOrDefault("BEFORE_HASH", 0));
  }

  @Test void aChangedSizeIsScannedAgain() throws Exception {
    indexAndUnload();
    var extended = directory.resolve("extended.wpilog");
    write(extended, true);
    byte[] original = Files.readAllBytes(source);
    byte[] longer = Files.readAllBytes(extended);
    assertArrayEquals(original, Arrays.copyOf(longer, original.length));
    Files.write(source, Arrays.copyOfRange(longer, original.length, longer.length), StandardOpenOption.APPEND);
    long scans = reopen("/Value", List.of(10.0, 20.0, 30.0));
    assertEquals(1, scans, "A different size is a different file to the index");
    assertEquals(0, phases.getOrDefault("BEFORE_CHECKSUM", 0), "Only a moved time is worth a checksum");
  }

  @Test void theTrustRuleWeighsEachAttribute() {
    var modified = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 500));
    var created = FileTime.from(Instant.ofEpochSecond(1_699_000_000L, 7));
    var file = new FileSnapshot(100, modified, "(dev=1,ino=2)", created);
    var recorded = LazyParsedLog.recorded(42, file);
    assertEquals(LazyParsedLog.IndexTrust.ATTRIBUTES, LazyParsedLog.trust(recorded, file));
    var moved = new FileSnapshot(100, FileTime.from(Instant.ofEpochSecond(1_700_000_001L, 500)), "(dev=1,ino=2)", created);
    assertEquals(LazyParsedLog.IndexTrust.CHECKSUM, LazyParsedLog.trust(recorded, moved), "Only the time moved");
    var keyless = new FileSnapshot(100, modified, null, created);
    assertEquals(LazyParsedLog.IndexTrust.CHECKSUM, LazyParsedLog.trust(LazyParsedLog.recorded(42, keyless), keyless),
        "Without a file identity, unchanged attributes do not vouch");
    var whole = new FileSnapshot(100, FileTime.from(Instant.ofEpochSecond(1_700_000_000L)), "(dev=1,ino=2)", created);
    assertEquals(LazyParsedLog.IndexTrust.CHECKSUM, LazyParsedLog.trust(LazyParsedLog.recorded(42, whole), whole),
        "A whole-second time could hide a same-second rewrite");
    var another = new FileSnapshot(100, modified, "(dev=1,ino=3)", created);
    assertEquals(LazyParsedLog.IndexTrust.PROOF, LazyParsedLog.trust(recorded, another), "Another file needs the full proof");
    var recreated = new FileSnapshot(100, modified, "(dev=1,ino=2)", FileTime.from(Instant.ofEpochSecond(1_699_000_005L)));
    assertEquals(LazyParsedLog.IndexTrust.PROOF, LazyParsedLog.trust(recorded, recreated),
        "A reported creation time that changed belongs to another file");
    // Java before 22 reports the modification time as the creation time on Linux; it moves with a touch.
    var linux = new FileSnapshot(100, modified, "(dev=1,ino=2)", modified);
    var linuxTouched = new FileSnapshot(100, moved.modified(), "(dev=1,ino=2)", moved.modified());
    assertEquals(LazyParsedLog.IndexTrust.CHECKSUM,
        LazyParsedLog.trust(LazyParsedLog.recorded(42, linux), linuxTouched),
        "A creation time equal to the modification time is unreported and not compared");
  }

  @Test void anOrdinaryFilesKeyIgnoresItsModificationTimeButNotItsIdentity() {
    var path = Path.of("logs", "reuse.wpilog");
    var created = FileTime.from(Instant.ofEpochSecond(1_699_000_000L, 7));
    var file = new FileSnapshot(100, FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 500)), "(dev=1,ino=2)", created);
    var touched = new FileSnapshot(100, FileTime.from(Instant.ofEpochSecond(1_700_000_009L, 500)), "(dev=1,ino=2)", created);
    String key = LazyParsedLog.indexIdentity(path, file, null);
    assertTrue(key.matches("file:[0-9a-f]{64}"), key);
    assertEquals(key, LazyParsedLog.indexIdentity(path, touched, null), "A touched file finds its index");
    assertNotEquals(key, LazyParsedLog.indexIdentity(path,
        new FileSnapshot(100, file.modified(), "(dev=1,ino=3)", created), null), "Another file identity");
    assertNotEquals(key, LazyParsedLog.indexIdentity(Path.of("logs", "other.wpilog"), file, null), "Another path");
    assertNotEquals(key, LazyParsedLog.indexIdentity(path,
        new FileSnapshot(101, file.modified(), "(dev=1,ino=2)", created), null), "Another size");
    assertEquals("sha256:" + "a".repeat(64), LazyParsedLog.indexIdentity(path, file, "a".repeat(64)),
        "A store member is keyed by its manifested hash");
  }

  private void indexAndUnload() throws Exception {
    try (var use = manager.acquireReadOnly(source.toString())) {
      assertEquals(List.of(10.0, 20.0), values(use.log(), "/Value"));
    }
    assertTrue(manager.unloadLog(source.toString()), "The first mapping must leave the loaded cache");
    phases.clear();
  }

  /** Reopens the source and checks the named entry's two values; returns the scans this took. */
  private long reopen(String entry) throws Exception {
    return reopen(entry, List.of(10.0, 20.0));
  }

  private long reopen(String entry, List<Object> expected) throws Exception {
    long scans = LogScan.passCount();
    try (var use = manager.acquireReadOnly(source.toString())) {
      assertEquals(expected, values(use.log(), entry), "Entries: " + use.log().entries().keySet());
    }
    return LogScan.passCount() - scans;
  }

  private static boolean vouches(Path file) throws IOException {
    var now = FileSnapshot.of(file);
    return now.fileKey() != null && now.modified().toInstant().getNano() != 0;
  }

  private static List<Object> values(LogData log, String name) {
    var values = log.values().get(name);
    return values == null ? List.of() : values.stream().map(TimestampedValue::value).toList();
  }

  private static void write(Path path, boolean extra) throws IOException {
    try (var writer = new WpilogWriter(path, "scan index reuse")) {
      int value = writer.start("/Value", "double", "", 0);
      int tail = writer.start("/Tail", "double", "", 0);
      writer.append(value, 1_000_000, WpilogWriter.encodeDouble(10));
      writer.append(value, 2_000_000, WpilogWriter.encodeDouble(20));
      writer.append(tail, 3_000_000, WpilogWriter.encodeDouble(99));
      if (extra) writer.append(value, 4_000_000, WpilogWriter.encodeDouble(30));
    }
  }

  /** Renames the /Value declaration to /Other in place: same size, same file, new content. */
  private static void rewriteDeclaration(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    byte[] from = "/Value".getBytes(StandardCharsets.UTF_8);
    byte[] to = "/Other".getBytes(StandardCharsets.UTF_8);
    int found = -1;
    for (int i = 0; i <= bytes.length - from.length; i++) {
      if (Arrays.equals(from, Arrays.copyOfRange(bytes, i, i + from.length))) {
        assertEquals(-1, found, "The declaration must occur once"); found = i;
      }
    }
    assertTrue(found > 12, "Change an interior declaration, outside the header");
    System.arraycopy(to, 0, bytes, found, to.length);
    Files.write(file, bytes);
  }
}
