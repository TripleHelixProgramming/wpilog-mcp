/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

class LogFollowerTest {
  @TempDir Path temp;
  @Test void partialAppendResumesAndAReadingCursorKeepsItsFixedPrefix() throws Exception {
    var file = temp.resolve("growing.wpilog");
    try (var writer = new WpilogWriter(file, "")) {
      int id = writer.start("x", "int64", "", 0);
      writer.append(id, 20, WpilogWriter.encodeInt64(1)); writer.append(id, 20, WpilogWriter.encodeInt64(2));
    }
    long before = Files.size(file);
    byte[] record = {0,1,8,10,77,0,0,0,0,0,0,0};
    try (var access = LogAccess.local(List.of(temp)); var follow = access.follow(file)) {
      var initial = follow.refresh(); assertEquals(LogFollower.State.INITIAL, initial.state());
      var held = initial.readNew("x");
      Files.write(file, Arrays.copyOf(record, 6), StandardOpenOption.APPEND);
      var partial = follow.refresh();
      assertEquals(LogFollower.State.APPENDED, partial.state()); assertEquals(before, partial.scannedFrom());
      assertEquals(before, partial.resumePoint()); assertTrue(partial.snapshot().truncated()); assertFalse(partial.snapshot().damaged());
      assertEquals(List.of(1L,2L), held.next().samples().stream().map(Sample::value).toList()); held.close();
      try (var noNew = partial.readNew("x")) { assertTrue(noNew.next().samples().isEmpty()); }
      Files.write(file, Arrays.copyOfRange(record, 6, 12), StandardOpenOption.APPEND);
      var finished = follow.refresh(); assertEquals(before, finished.scannedFrom());
      assertFalse(finished.snapshot().truncated());
      try (var values = finished.readNew("x")) {
        var batch = values.next(); assertEquals(1, batch.recordsVisited());
        assertEquals(77L, batch.samples().get(0).value()); assertEquals(10, batch.samples().get(0).timestampUs());
      }
      assertEquals(LogFollower.State.UNCHANGED, follow.refresh().state());
      try (var unchanged = follow.refresh().readNew("x")) { assertTrue(unchanged.next().samples().isEmpty()); }
    }
  }
}
