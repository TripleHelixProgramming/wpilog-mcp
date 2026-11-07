/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.columns.ColumnFileFormat;

/** Column files in the disk cache: their names, the startup sweep, and eviction with the rest. */
class ColumnDiskCacheTest {
  private static final String HASH = "ab".repeat(32);
  @TempDir Path root;

  private DiskCache cache() {
    var directory = new CacheDirectory();
    directory.setOverride(root.toString());
    return new DiskCache(directory, "test");
  }

  /** A file of {@code bytes} bytes whose header is a column file's of the given versions. */
  private Path columnFile(String name, int format, int recipe, int bytes) throws Exception {
    var content = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    content.putLong(ColumnFileFormat.MAGIC).putInt(format).putInt(recipe);
    return Files.write(root.resolve(name), content.array());
  }

  private Path sized(String name, int bytes, long modifiedMillis) throws Exception {
    var file = Files.write(root.resolve(name), new byte[bytes]);
    Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedMillis));
    return file;
  }

  @Test
  void aColumnFileIsNamedByItsContentAndVersions() {
    assertEquals("v" + ColumnFileFormat.FORMAT_VERSION + "-r" + ColumnFileFormat.RECIPE_VERSION + "-" + HASH
        + "-columns.col", DiskCache.columnFileName(HASH));
    assertThrows(IllegalArgumentException.class, () -> DiskCache.columnFileName("AB".repeat(32)));
    assertThrows(IllegalArgumentException.class, () -> DiskCache.columnFileName("ab"));
    var cache = cache();
    try {
      assertEquals(root.resolve(DiskCache.columnFileName(HASH)), cache.columnFilePath(HASH).orElseThrow());
      assertTrue(cache.columnFilePath("sha256:" + HASH).isEmpty(), "a malformed hash names no file");
      assertTrue(cache.columnFilePath(null).isEmpty());
      cache.setEnabled(false);
      assertTrue(cache.columnFilePath(HASH).isEmpty(), "a disabled cache keeps no column files");
    } finally {
      cache.shutdown();
    }
  }

  /**
   * A recording's open column file is named by its capture, swept at startup only when it is older
   * than this process, and counted toward the limit but never evicted while a capture writes it.
   */
  @Test
  void openColumnFilesAreSweptOnlyFromAnEarlierProcessAndNeverEvicted() throws Exception {
    var identity = "0123abcd-0000-4000-8000-00000000abcd";
    var cache = cache();
    try {
      assertEquals(root.resolve(identity + DiskCache.OPEN_COLUMN_SUFFIX), cache.openColumnPath(identity).orElseThrow());
      assertTrue(cache.openColumnPath("../escape").isEmpty(), "only a capture's identity names a file");
      assertTrue(cache.openColumnPath(null).isEmpty());
      long started = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
      var stale = sized("11111111-0000-4000-8000-000000000001" + DiskCache.OPEN_COLUMN_SUFFIX, 64, started - 60_000);
      var writing = sized("22222222-0000-4000-8000-000000000002" + DiskCache.OPEN_COLUMN_SUFFIX, 4096, System.currentTimeMillis());
      cache.cleanup();
      assertFalse(Files.exists(stale), "a file left by an earlier process is removed");
      assertTrue(Files.exists(writing), "a file this process or a running server writes is kept");
      // Over the limit, the open file stays and the oldest evictable file goes.
      var old = sized("old.msgpack", 4096, started - 120_000);
      cache.setMaxTotalSizeMb(0);
      cache.trim(file -> false);
      assertFalse(Files.exists(old), "an ordinary cache file is evicted");
      assertTrue(Files.exists(writing), "an open column file is never evicted");
      cache.setEnabled(false);
      assertTrue(cache.openColumnPath(identity).isEmpty(), "a disabled cache keeps no open column files");
    } finally {
      cache.shutdown();
    }
  }

  @Test
  void theStartupSweepKeepsCurrentColumnFilesAndRemovesTheRest() throws Exception {
    int format = ColumnFileFormat.FORMAT_VERSION, recipe = ColumnFileFormat.RECIPE_VERSION;
    var current = columnFile(DiskCache.columnFileName(HASH), format, recipe, 64);
    var otherRecipe = columnFile("v" + format + "-r" + (recipe + 1) + "-" + "cd".repeat(32) + "-columns.col", format, recipe + 1, 64);
    var damaged = columnFile(DiskCache.columnFileName("ef".repeat(32)), format + 1, recipe, 64);
    var truncated = Files.write(root.resolve(DiskCache.columnFileName("01".repeat(32))), new byte[] {1, 2, 3});
    var misnamed = columnFile("v" + format + "-r" + (recipe + 1) + "-" + "02".repeat(32) + "-columns.col", format, recipe, 64);
    var unrelated = Files.writeString(root.resolve("notes.txt"), "not a cache entry");
    var cache = cache();
    try {
      cache.cleanup();
      assertTrue(Files.exists(current), "a column file of this format and recipe is kept");
      assertFalse(Files.exists(otherRecipe), "another recipe's file is removed");
      assertFalse(Files.exists(damaged), "a file whose header names another format is removed");
      assertFalse(Files.exists(truncated), "a file too short for a header is removed");
      assertFalse(Files.exists(misnamed), "a file named for another recipe is removed, whatever its header says");
      assertEquals("not a cache entry", Files.readString(unrelated));
    } finally {
      cache.shutdown();
    }
  }

  /** Four files of 512 KiB under a 1 MiB limit: the two oldest go, except one a reader holds. */
  @Test
  void trimmingEvictsTheOldestOfEveryKindButKeepsFilesInUse() throws Exception {
    int half = 512 * 1024;
    var oldestColumns = sized("v1-r1-" + "aa".repeat(32) + "-columns.col", half, 1_000);
    var oldScan = sized("v2-r1-" + "bb".repeat(32) + "-scan.idx", half, 2_000);
    var sync = sized("cc".repeat(16) + "-sync.msgpack", half, 3_000);
    var newestColumns = sized("v1-r1-" + "dd".repeat(32) + "-columns.col", half, 4_000);
    var unrelated = sized("notes.txt", 4 * half, 0);
    var cache = cache();
    try {
      cache.setMaxTotalSizeMb(1);
      cache.trim(oldestColumns::equals);
      assertTrue(Files.exists(oldestColumns), "a file a reader holds is never evicted");
      assertFalse(Files.exists(oldScan), "the oldest file not in use goes first");
      assertFalse(Files.exists(sync), "then the next oldest, until the cache is under its limit");
      assertTrue(Files.exists(newestColumns));
      assertTrue(Files.exists(unrelated), "files the cache does not own are neither counted nor evicted");
    } finally {
      cache.shutdown();
    }
  }

  @Test
  void theStartupSweepCountsColumnFilesTowardTheLimit() throws Exception {
    int format = ColumnFileFormat.FORMAT_VERSION, recipe = ColumnFileFormat.RECIPE_VERSION;
    var older = columnFile(DiskCache.columnFileName("aa".repeat(32)), format, recipe, 768 * 1024);
    Files.setLastModifiedTime(older, FileTime.fromMillis(1_000));
    var newer = columnFile(DiskCache.columnFileName("bb".repeat(32)), format, recipe, 768 * 1024);
    Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000));
    var cache = cache();
    try {
      cache.setMaxTotalSizeMb(1);
      cache.cleanup();
      assertFalse(Files.exists(older), "the older column file is evicted to meet the limit");
      assertTrue(Files.exists(newer));
    } finally {
      cache.shutdown();
    }
  }
}
