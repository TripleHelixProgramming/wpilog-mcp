/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The comparison a loaded log's file is held to. The three attributes each catch one way a file
 * changes under a loaded log, and the description names the ones that differ with their values,
 * so a discarded result says what happened. Nothing here maps a file, so every case runs on
 * every system, including the size change that Windows refuses on a mapped file.
 */
@DisplayName("file snapshots")
class FileSnapshotTest {

  private static final FileTime T1 = FileTime.from(Instant.parse("2026-03-21T16:29:56Z"));
  private static final FileTime T2 = FileTime.from(Instant.parse("2026-03-21T16:31:02Z"));

  @Test
  @DisplayName("the same size, time, and identity is the same file")
  void same() {
    var a = new FileSnapshot(1000, T1, "key");
    assertTrue(a.sameAs(new FileSnapshot(1000, T1, "key")));
    assertTrue(a.sameAs(new FileSnapshot(1000, T1, null)), "no identity to compare on one side");
    assertTrue(new FileSnapshot(1000, T1, null).sameAs(a));
  }

  @Test
  @DisplayName("a different size, time, or identity is a change, and the description names it")
  void changes() {
    var a = new FileSnapshot(1000, T1, "key");

    var grown = new FileSnapshot(2_500_000, T1, "key");
    assertFalse(a.sameAs(grown));
    assertEquals("its size went from 1000 bytes to 2.4 MB", a.describeChange(grown));

    var touched = new FileSnapshot(1000, T2, "key");
    assertFalse(a.sameAs(touched));
    assertEquals("it was modified at 2026-03-21T16:31:02Z, after 2026-03-21T16:29:56Z",
        a.describeChange(touched));

    var replaced = new FileSnapshot(1000, T1, "other");
    assertFalse(a.sameAs(replaced));
    assertEquals("it was replaced by another file", a.describeChange(replaced));

    var all = new FileSnapshot(1001, T2, "other");
    assertEquals("it was replaced by another file, its size went from 1000 bytes to 1001 bytes, "
        + "it was modified at 2026-03-21T16:31:02Z, after 2026-03-21T16:29:56Z",
        a.describeChange(all));
  }

  @Test
  @DisplayName("a file that no longer exists is a change of its own")
  void gone() {
    var a = new FileSnapshot(1000, T1, "key");
    assertFalse(a.sameAs(null));
    assertEquals("the file no longer exists", a.describeChange(null));
  }

  @Test
  @DisplayName("of() reads a file's attributes, and null for a missing file")
  void ofFile(@TempDir Path dir) throws Exception {
    var file = dir.resolve("a.bin");
    Files.write(file, new byte[123]);
    Files.setLastModifiedTime(file, T1);
    var snapshot = FileSnapshot.of(file);
    assertNotNull(snapshot);
    assertEquals(123, snapshot.size());
    assertEquals(T1, snapshot.modified());
    assertTrue(snapshot.sameAs(FileSnapshot.of(file)));
    assertNull(FileSnapshot.of(dir.resolve("missing.bin")));
  }
}
