/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/** A file's content key cannot distinguish synchronization computed before exact time order. */
class SyncMicrosecondCacheVersionTest {
  @TempDir Path temp;

  @Test void versionTwelveSynchronizationCannotSurviveExactMicrosecondOrdering() throws Exception {
    var serializer = new SyncCacheSerializer();
    var revlog = new ParsedRevLog(temp.resolve("REV_20260101_000000.revlog").toString(),
        "20260101_000000", Map.of(), Map.of(), 0, 1, 0);
    var entry = new SyncCacheSerializer.CachedSyncEntry(revlog,
        SyncResult.fromSystemTimeOnly(9_007_199_254_740_992L, "Old double-order clock result"),
        "synthetic-wpilog-content", "synthetic-revlog-content", 0);
    var file = temp.resolve("version-twelve.msgpack");
    serializer.write(entry, file);
    var bytes = Files.readAllBytes(file);
    // Versions 12 and 13 are one-byte MessagePack positive fixints. This is a valid old
    // envelope, not a checksum-corruption test; the body format has not changed.
    assertTrue((bytes[0] & 0xff) < 128, "The fixture needs a one-byte version field");
    bytes[0] = 12;
    var checksum = new CRC32();
    checksum.update(bytes, 0, bytes.length - Integer.BYTES);
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(bytes.length - Integer.BYTES, (int) checksum.getValue());
    Files.write(file, bytes);
    assertEquals(12, serializer.readFormatVersion(file));
    assertNull(serializer.read(file),
        "Version 12 cached double-order synchronization must be recomputed from exact microseconds");
  }
}
