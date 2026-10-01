/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcParser;

/** A cached sync is keyed by the DBC that decoded it (review 6, section 2.7). */
class SyncCacheKeyTest {

  @TempDir Path dir;

  static final String DBC_A = "VERSION \"1\"\n\nBO_ 1 M: 8 X\n SG_ S : 0|8@1+ (1,0) [0|255] \"\"\n";
  static final String DBC_B = DBC_A.replace("(1,0)", "(2,0)");

  @Test
  @DisplayName("parsers built from different DBC texts report different hashes")
  void parserHashFollowsTheDbc() {
    var parser = new DbcParser();
    var a = new RevLogParser(parser.parse(DBC_A)).dbcContentHash();
    var b = new RevLogParser(parser.parse(DBC_B)).dbcContentHash();
    assertEquals(16, a.length());
    assertNotEquals(a, b, "a changed scale factor is another DBC");
    assertEquals(a, new RevLogParser(parser.parse(DBC_A)).dbcContentHash());
  }

  @Test
  @DisplayName("the cache key changes with the DBC, so a replaced DBC re-decodes cached syncs")
  void keyIncludesTheDbcHash() throws Exception {
    var file = dir.resolve("REV_20260321_185000.revlog");
    Files.write(file, new byte[] {1, 2, 3});
    var info = new LogDirectory.RevLogFileInfo(file, "20260321_185000",
        LocalDateTime.of(2026, 3, 21, 18, 50, 0), null, 3);
    var withA = LogManager.revlogCacheKey(info, "aaaa");
    var withB = LogManager.revlogCacheKey(info, "bbbb");
    assertNotEquals(withA, withB);
    assertEquals(withA, LogManager.revlogCacheKey(info, "aaaa"));
    assertTrue(withA.endsWith("|dbc:aaaa"), withA);
    assertTrue(withA.contains("|REV_20260321_185000.revlog|"), "the file name is part of the key");
  }
}
