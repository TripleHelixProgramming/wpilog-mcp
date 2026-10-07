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
  static Fingerprint read(Path path, String kind) throws Exception {
    if (kind.equals("revlog")) return RobotCandidates.inspect(new org.triplehelix.wpilogmcp.revlog.RevLogParser(
        new org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader().load(null)).parse(path));
    try (var reader = new ScopedLogReader(path); var log = new LazyParsedLog(path.toString(), reader.reader(), 4L << 20)) {
      return RobotCandidates.inspect(log, LogMetadata.read(log));
    }
  }
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
  @Test void plainFilesSupplyNeitherKnownFingerprintsNorCandidateEvidence() throws Exception {
    var path = temp.resolve("plain.wpilog");
    try (var w = new WpilogWriter(path, "synthetic plain identity")) {
      w.append(w.start("/SystemStats/SerialNumber", "string", "", 0), 1, WpilogWriter.encodeString("SYNTHETIC-A"));
    }
    var robot = new org.triplehelix.wpilogmcp.store.StoreManifest.Robot("SYNTHETIC-A", "SYNTHETIC-A", null, null, "logged");
    var log = new LogDirectory.LogFileInfo(path.toString(), path.getFileName().toString(), null, null, null, null, 0, 0, null, null, robot);
    var scan = new LogDirectory.DirectoryScan(List.of(temp), List.of(log), List.of(), List.of());
    assertTrue(RobotCandidates.known(scan).isEmpty());
    assertTrue(RobotCandidates.forFile(null, List.of(new Known("SYNTHETIC-A", new Fingerprint(9999, "entries", null)))).isEmpty());
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
    assertEquals(9999, read(paths.get(0), "wpilog").loggedTeamNumber());
    assertNull(read(paths.get(1), "wpilog").loggedTeamNumber());
    assertNotEquals(read(paths.get(1), "wpilog").entrySet(), read(paths.get(2), "wpilog").entrySet());
    var reverse = temp.resolve("reversed.wpilog");
    try (var w = new WpilogWriter(reverse, "synthetic reordered fingerprint")) {
      w.start("/a", "int64", "", 0); w.start("/b", "int64", "", 0);
    }
    assertEquals(read(reverse, "wpilog").entrySet(), read(paths.get(1), "wpilog").entrySet());
    String first = null;
    for (int can : List.of(5, 6, 5)) {
      var path = temp.resolve("can-" + can + ".revlog");
      try (var w = new WpilogWriter(path, "synthetic CAN fingerprint")) {
        int id = w.start("CAN/" + can, "raw", "", 0);
        w.append(id, 1_000_000, new byte[8]);
        w.finish(id, 1_000_000);
      }
      String inventory = read(path, "revlog").canInventory(); assertNotNull(inventory);
      if (first == null) first = inventory;
      else if (can == 6) assertNotEquals(first, inventory);
      else assertEquals(first, inventory);
    }
  }
}
