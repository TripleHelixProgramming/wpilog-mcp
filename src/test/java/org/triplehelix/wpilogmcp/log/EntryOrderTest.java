/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.util.datalog.DataLogReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.subsystems.LogParser;

/**
 * Entries iterate in declaration order in both parsers (review issue B9), and an entry name started
 * twice is one entry holding all its records.
 */
@DisplayName("Entry order and restarted entries")
class EntryOrderTest {

  /** Names chosen so hash order differs from declaration order. */
  static final List<String> NAMES = List.of("/Z/Last", "/A/First", "/M/Middle", "/B/Second",
      "/Y/Penultimate", "/C/Third", "/X/Tenth", "/D/Fourth");

  static Path write(Path dir) throws Exception {
    var path = dir.resolve("order.wpilog");
    try (var w = new WpilogWriter(path, "")) {
      int t = 1_000_000;
      var ids = new ArrayList<Integer>();
      for (var name : NAMES) ids.add(w.start(name, "double", "", t));
      for (int i = 0; i < ids.size(); i++) {
        w.append(ids.get(i), t + i, WpilogWriter.encodeDouble(i));
      }
      // "/A/First" is finished and started again under a new id; both records belong to it
      w.finish(ids.get(1), 2_000_000);
      int again = w.start("/A/First", "double", "", 3_000_000);
      w.append(again, 3_000_001, WpilogWriter.encodeDouble(99));
      // "/B/Second" restarted with a different type: the second declaration's records are ignored
      int conflicting = w.start("/B/Second", "string", "", 4_000_000);
      w.append(conflicting, 4_000_001, WpilogWriter.encodeString("not a double"));
    }
    return path;
  }

  @Test
  @DisplayName("LazyParsedLog: declaration order, restarted entry merged, type conflict ignored")
  void lazy(@TempDir Path dir) throws Exception {
    var path = write(dir);
    try (var log = new LazyParsedLog(path.toString(), new DataLogReader(path.toString()),
        10_000_000)) {
      assertEquals(NAMES, new ArrayList<>(log.entries().keySet()));
      var first = log.values().get("/A/First");
      assertEquals(2, first.size());
      assertEquals(1.0, first.get(0).value());
      assertEquals(99.0, first.get(1).value());
      assertEquals(2, log.sampleCount("/A/First"));
      assertEquals(1, log.values().get("/B/Second").size());
      assertEquals("double", log.entries().get("/B/Second").type());
    }
  }

  @Test
  @DisplayName("LogParser (eager): the same order and merging")
  void eager(@TempDir Path dir) throws Exception {
    var path = write(dir);
    var log = new LogParser().parse(path);
    assertEquals(NAMES, new ArrayList<>(log.entries().keySet()));
    assertEquals(NAMES, new ArrayList<>(log.values().keySet()));
    assertEquals(2, log.values().get("/A/First").size());
    assertEquals(1, log.values().get("/B/Second").size());
  }
}
