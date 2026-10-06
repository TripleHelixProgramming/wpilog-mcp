/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.RobotCandidates.*;

class RobotCandidatesTest {
  @TempDir Path temp;
  @Test void eachKindNeedsOneSerialAndConflictingHintsNeverPromoteAChoice() {
    var target = new Fingerprint(9999, "entries", "inventory");
    var first = new Known("SYNTHETIC-A", target);
    var single = RobotCandidates.match(target, List.of(first, first));
    assertEquals(List.of(new Candidate("SYNTHETIC-A", List.of(new Evidence("logged_team_number", "9999"),
        new Evidence("entry_set", "entries"), new Evidence("rev_can_inventory", "inventory")))), single);
    assertTrue(RobotCandidates.match(target, List.of(first, new Known("SYNTHETIC-B", target))).isEmpty());
    assertTrue(RobotCandidates.match(target, List.of(new Known("SYNTHETIC-A", new Fingerprint(9999, "other", null)),
        new Known("SYNTHETIC-B", new Fingerprint(8888, "entries", null)))).isEmpty());
    assertTrue(RobotCandidates.match(new Fingerprint(null, null, null), List.of(first)).isEmpty());
    assertTrue(RobotCandidates.match(new Fingerprint(1, "none", "none"), List.of(first)).isEmpty());
    assertTrue(RobotCandidates.match(target, List.of()).isEmpty());
  }
  @Test void fingerprintsUseLoggedTeamExactEntryNamesAndTypesAndCanInventory() throws Exception {
    var paths = List.of(temp.resolve("a.wpilog"), temp.resolve("b.wpilog"), temp.resolve("c.wpilog"));
    for (int i = 0; i < paths.size(); i++) try (var w = new WpilogWriter(paths.get(i), "synthetic fingerprint")) {
      for (String name : i == 1 ? List.of("/b", "/a") : List.of("/a", "/b")) {
        int id = w.start(name, i == 2 ? "double" : "int64", "", 0);
        w.append(id, 1_000_000, i == 2 ? WpilogWriter.encodeDouble(i) : WpilogWriter.encodeInt64(i));
      }
      if (i == 0) {
        int team = w.start("/SystemStats/TeamNumber", "int64", "", 0); w.append(team, 1_000_000, WpilogWriter.encodeInt64(9999));
      }
    }
    assertEquals(9999, RobotCandidates.read(paths.get(0), "wpilog").loggedTeamNumber());
    assertNull(RobotCandidates.read(paths.get(1), "wpilog").loggedTeamNumber());
    assertNotEquals(RobotCandidates.read(paths.get(1), "wpilog").entrySet(), RobotCandidates.read(paths.get(2), "wpilog").entrySet());
    var reverse = temp.resolve("reversed.wpilog");
    try (var w = new WpilogWriter(reverse, "synthetic reordered fingerprint")) {
      w.start("/a", "int64", "", 0); w.start("/b", "int64", "", 0);
    }
    assertEquals(RobotCandidates.read(reverse, "wpilog").entrySet(), RobotCandidates.read(paths.get(1), "wpilog").entrySet());
    String first = null;
    for (int can : List.of(5, 6, 5)) {
      var path = temp.resolve("can-" + can + ".revlog");
      try (var w = new WpilogWriter(path, "synthetic CAN fingerprint")) {
        int id = w.start("CAN/" + can, "raw", "", 0);
        w.append(id, 1_000_000, new byte[8]);
        w.finish(id, 1_000_000);
      }
      String inventory = RobotCandidates.read(path, "revlog").canInventory(); assertNotNull(inventory);
      if (first == null) first = inventory;
      else if (can == 6) assertNotEquals(first, inventory);
      else assertEquals(first, inventory);
    }
  }
}
