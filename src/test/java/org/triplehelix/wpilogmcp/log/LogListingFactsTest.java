/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogDirectory.LogFileInfo;

/**
 * What the listing says about a log (its time, event, match, and team) comes from the file names
 * the two logging frameworks write and from the entries that carry those facts by convention.
 * Nothing is taken from a name that only resembles one.
 *
 * <p>Before: WPILib's own names ({@code FRC_20260102_030405_XXYY_Q7.wpilog}) were not parsed at
 * all, an AdvantageKit name without an event had no time, a name with an event and no match was
 * called a practice match, and any entry whose name contained "eventname", "matchtype",
 * "matchnumber", "stationnumber", or "teamnumber" was read as that fact, whatever it was and
 * whatever its type. The listing's event, match, and team select The Blue Alliance data for the
 * log, so a wrong one attaches another match's result to it.
 */
class LogListingFactsTest {

  @TempDir Path dir;
  private LogDirectory logDirectory;
  private List<Path> savedDirectories;
  private Integer savedTeam;

  @BeforeEach
  void setUp() {
    logDirectory = LogDirectory.getInstance();
    savedDirectories = logDirectory.getLogDirectories();
    savedTeam = logDirectory.getDefaultTeamNumber();
    logDirectory.setLogDirectory(dir.toString());
    logDirectory.setDefaultTeamNumber(null);
    logDirectory.clearCache();
  }

  @AfterEach
  void restore() {
    logDirectory.setLogDirectories(savedDirectories.stream().map(Path::toString).toList());
    logDirectory.setDefaultTeamNumber(savedTeam);
    logDirectory.clearCache();
  }

  private LogFileInfo listed(String filename) throws IOException {
    logDirectory.clearCache();
    return logDirectory.listAvailableLogs().stream()
        .filter(l -> l.filename().equals(filename)).findFirst().orElseThrow();
  }

  /** An empty file: only its name speaks. */
  private LogFileInfo named(String filename) throws IOException {
    Files.createFile(dir.resolve(filename));
    return listed(filename);
  }

  private static long utc(int y, int mo, int d, int h, int mi, int s) {
    return LocalDateTime.of(y, mo, d, h, mi, s).toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  private static void assertFacts(LogFileInfo info, String event, String matchType,
      Integer matchNumber) {
    assertEquals(event, info.eventName(), info.filename() + " event");
    assertEquals(matchType, info.matchType(), info.filename() + " match type");
    assertEquals(matchNumber, info.matchNumber(), info.filename() + " match number");
  }

  @Nested
  @DisplayName("WPILib DataLogManager file names")
  class DataLogManagerNames {

    @Test
    @DisplayName("FRC_<date>_<time>: the time, in UTC, and nothing else")
    void timeOnly() throws IOException {
      var info = named("FRC_20260102_030405.wpilog");
      assertEquals(utc(2026, 1, 2, 3, 4, 5), info.logCreationTime());
      assertFacts(info, null, null, null);
      assertEquals("FRC_20260102_030405", info.friendlyName());
    }

    @Test
    @DisplayName("FRC_<date>_<time>_<EVENT>_<P|Q|E><n>: the event and the match")
    void eventAndMatch() throws IOException {
      var q = named("FRC_20260102_030405_XXYY_Q7.wpilog");
      assertEquals(utc(2026, 1, 2, 3, 4, 5), q.logCreationTime());
      assertFacts(q, "XXYY", "Qualification", 7);
      assertEquals("XXYY Qualification 7", q.friendlyName());
      assertFacts(named("FRC_20260102_040506_XXYY_P3.wpilog"), "XXYY", "Practice", 3);
      assertFacts(named("FRC_20260102_050607_XXYY_E12.wpilog"), "XXYY", "Elimination", 12);
    }

    @Test
    @DisplayName("FRC_TBD_<id>: the clock was not set, so no time and nothing else")
    void clockNotSet() throws IOException {
      var info = named("FRC_TBD_0123456789abcdef.wpilog");
      assertNull(info.logCreationTime());
      assertFacts(info, null, null, null);
    }

    @Test
    @DisplayName("a renamed or copied file keeps its time; its other parts are not read")
    void renamed() throws IOException {
      var copy = named("FRC_20260102_030405.2.wpilog");
      assertEquals(utc(2026, 1, 2, 3, 4, 5), copy.logCreationTime());
      assertFacts(copy, null, null, null);
      var renamed = named("FRC_20260102_040506_XXYY_Q7-after-the-fix.wpilog");
      assertEquals(utc(2026, 1, 2, 4, 5, 6), renamed.logCreationTime());
      assertFacts(renamed, null, null, null);
    }

    @Test
    @DisplayName("digits that are not a date give no time")
    void notADate() throws IOException {
      assertNull(named("FRC_20261302_030405.wpilog").logCreationTime());
      assertNull(named("FRC_20260102_250405.wpilog").logCreationTime());
    }
  }

  @Nested
  @DisplayName("AdvantageKit file names")
  class AdvantageKitNames {

    @Test
    @DisplayName("<prefix>_<date>_<time>: the time and nothing else")
    void timeOnly() throws IOException {
      var info = named("akit_26-01-02_03-04-05.wpilog");
      assertEquals(utc(2026, 1, 2, 3, 4, 5), info.logCreationTime());
      assertFacts(info, null, null, null);
    }

    @Test
    @DisplayName("an event and no match is an event and no match, not a practice match")
    void eventOnly() throws IOException {
      // The Driver Station reports an event name off the field too, with match type None. A
      // practice match is named ..._xxyy_p3.
      var info = named("akit_26-01-02_03-04-05_xxyy.wpilog");
      assertFacts(info, "XXYY", null, null);
      assertEquals("XXYY", info.friendlyName());
      assertFacts(named("akit_26-01-02_04-05-06_week0.wpilog"), "WEEK0", null, null);
    }

    @Test
    @DisplayName("an event and a match")
    void eventAndMatch() throws IOException {
      assertFacts(named("akit_26-01-02_03-04-05_xxyy_q7.wpilog"), "XXYY", "Qualification", 7);
      assertFacts(named("akit_26-01-02_04-05-06_xxyy_p3.wpilog"), "XXYY", "Practice", 3);
      assertFacts(named("akit_26-01-02_05-06-07_xxyy_e12.wpilog"), "XXYY", "Elimination", 12);
      // The Blue Alliance's codes, which the listing has always accepted
      assertFacts(named("frc_26-01-02_06-07-08_xxyy_qm42.wpilog"), "XXYY", "Qualification", 42);
      assertFacts(named("frc_26-01-02_07-08-09_xxyy_sf2.wpilog"), "XXYY", "Semifinal", 2);
      assertFacts(named("frc_26-01-02_08-09-10_xxyy_f1.wpilog"), "XXYY", "Final", 1);
    }

    @Test
    @DisplayName("a match and no event is a match, not an event named after it")
    void matchOnly() throws IOException {
      // It used to be the event "Q7" with a practice match
      var info = named("akit_26-01-02_03-04-05_q7.wpilog");
      assertFacts(info, null, "Qualification", 7);
      assertEquals(utc(2026, 1, 2, 3, 4, 5), info.logCreationTime());
    }

    @Test
    @DisplayName("_sim marks a replay or simulation output; it is not an event")
    void sim() throws IOException {
      // It used to be the event "SIM"
      var bare = named("akit_26-01-02_03-04-05_sim.wpilog");
      assertFacts(bare, null, null, null);
      assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5).atZone(ZoneId.systemDefault())
          .toInstant().toEpochMilli(), bare.logCreationTime());
      assertFacts(named("akit_26-01-02_04-05-06_xxyy_q7_sim.wpilog"), "XXYY",
          "Qualification (sim)", 7);
      var event = named("akit_26-01-02_05-06-07_xxyy_sim.wpilog");
      assertFacts(event, "XXYY", null, null);
      assertEquals("XXYY (sim)", event.friendlyName());
    }

    @Test
    @DisplayName("parts that are not an event and a match code are not read as either")
    void otherParts() throws IOException {
      // The time is still the time; the rest is someone's own naming
      var words = named("akit_26-01-02_03-04-05_xxyy_practice_match_3.wpilog");
      assertEquals(utc(2026, 1, 2, 3, 4, 5), words.logCreationTime());
      assertFacts(words, null, null, null);
      // It used to be match type "zz"
      assertFacts(named("akit_26-01-02_04-05-06_xxyy_zz9.wpilog"), null, null, null);
    }

    @Test
    @DisplayName("a name with no time: nothing is read from it")
    void noTime() throws IOException {
      var info = named("akit_0123456789abcdef.wpilog");
      assertNull(info.logCreationTime());
      assertFacts(info, null, null, null);
      assertEquals("akit_0123456789abcdef", info.friendlyName());
    }
  }

  @Nested
  @DisplayName("Event, match, and team from the log's entries")
  class ConventionalEntries {

    private static final long SEC = 1_000_000L;

    @Test
    @DisplayName("AdvantageKit's DriverStation and SystemStats tables")
    void advantageKit() throws IOException {
      try (var w = new WpilogWriter(dir.resolve("a.wpilog"), "")) {
        int event = w.start("/DriverStation/EventName", "string", "", 0);
        int type = w.start("/DriverStation/MatchType", "int64", "", 0);
        int number = w.start("/DriverStation/MatchNumber", "int64", "", 0);
        int team = w.start("/SystemStats/TeamNumber", "int64", "", 0);
        w.append(event, SEC, WpilogWriter.encodeString("XXYY"));
        w.append(type, SEC, WpilogWriter.encodeInt64(2));
        w.append(number, SEC, WpilogWriter.encodeInt64(7));
        w.append(team, SEC, WpilogWriter.encodeInt64(9999));
      }
      var info = listed("a.wpilog");
      assertFacts(info, "XXYY", "Qualification", 7);
      assertEquals(9999, info.teamNumber());
    }

    @Test
    @DisplayName("NetworkTables' FMSInfo table, as DataLogManager records it")
    void networkTables() throws IOException {
      try (var w = new WpilogWriter(dir.resolve("n.wpilog"), "")) {
        int event = w.start("NT:/FMSInfo/EventName", "string", "", 0);
        int type = w.start("NT:/FMSInfo/MatchType", "int64", "", 0);
        int number = w.start("NT:/FMSInfo/MatchNumber", "int64", "", 0);
        int station = w.start("NT:/FMSInfo/StationNumber", "int64", "", 0);
        w.append(event, SEC, WpilogWriter.encodeString("XXYY"));
        w.append(type, SEC, WpilogWriter.encodeInt64(3));
        w.append(number, SEC, WpilogWriter.encodeInt64(4));
        w.append(station, SEC, WpilogWriter.encodeInt64(2));
      }
      var info = listed("n.wpilog");
      assertFacts(info, "XXYY", "Elimination", 4);
      assertNull(info.teamNumber(), "a station number is not a team number");
    }

    @Test
    @DisplayName("values not set (empty, 0, None) leave the fact unset, before or after a set one")
    void unsetValues() throws IOException {
      try (var w = new WpilogWriter(dir.resolve("u.wpilog"), "")) {
        int event = w.start("/DriverStation/EventName", "string", "", 0);
        int type = w.start("/DriverStation/MatchType", "int64", "", 0);
        int number = w.start("/DriverStation/MatchNumber", "int64", "", 0);
        int team = w.start("/SystemStats/TeamNumber", "int64", "", 0);
        for (long t = 1; t <= 3; t++) {
          boolean set = t == 2;
          w.append(event, t * SEC, WpilogWriter.encodeString(set ? "XXYY" : ""));
          w.append(type, t * SEC, WpilogWriter.encodeInt64(set ? 1 : 0));
          w.append(number, t * SEC, WpilogWriter.encodeInt64(set ? 3 : 0));
          w.append(team, t * SEC, WpilogWriter.encodeInt64(set ? 9999 : 0));
        }
      }
      var info = listed("u.wpilog");
      assertFacts(info, "XXYY", "Practice", 3);
      assertEquals(9999, info.teamNumber());

      try (var w = new WpilogWriter(dir.resolve("z.wpilog"), "")) {
        int type = w.start("/DriverStation/MatchType", "int64", "", 0);
        int team = w.start("/SystemStats/TeamNumber", "int64", "", 0);
        w.append(type, SEC, WpilogWriter.encodeInt64(6)); // not a match type
        w.append(team, SEC, WpilogWriter.encodeInt64(0));
      }
      logDirectory.setDefaultTeamNumber(8888);
      var unset = listed("z.wpilog");
      assertFacts(unset, null, null, null);
      assertEquals(8888, unset.teamNumber(), "team 0 is no team: the configured default applies");
    }

    @Test
    @DisplayName("a team's own entries with similar names are not read")
    void similarNames() throws IOException {
      try (var w = new WpilogWriter(dir.resolve("s.wpilog"), "")) {
        int state = w.start("/RealOutputs/GameState/MatchType", "string", "", 0);
        int event = w.start("/Scoreboard/EventName", "string", "", 0);
        int number = w.start("/Scoreboard/MatchNumber", "int64", "", 0);
        int shown = w.start("/Dashboard/TeamNumberShown", "int64", "", 0);
        int station = w.start("/Operator/StationNumber", "int64", "", 0);
        w.append(state, SEC, WpilogWriter.encodeString("Qualification"));
        w.append(event, SEC, WpilogWriter.encodeString("ZZ"));
        w.append(number, SEC, WpilogWriter.encodeInt64(12));
        w.append(shown, SEC, WpilogWriter.encodeInt64(4321));
        w.append(station, SEC, WpilogWriter.encodeInt64(25));
      }
      var info = listed("s.wpilog");
      assertFacts(info, null, null, null);
      assertNull(info.teamNumber());
      assertEquals("s", info.friendlyName());
    }

    @Test
    @DisplayName("a conventional name with another type is not read, and does not end the reading")
    void otherTypes() throws IOException {
      try (var w = new WpilogWriter(dir.resolve("t.wpilog"), "")) {
        // A float record is 4 bytes: reading it as an integer used to throw and end the reading
        // of everything after it
        int odd = w.start("/Sim/DriverStation/MatchNumber", "float", "", 0);
        int text = w.start("/Old/DriverStation/MatchType", "string", "", 0);
        int event = w.start("/DriverStation/EventName", "string", "", 0);
        int type = w.start("/DriverStation/MatchType", "int64", "", 0);
        int number = w.start("/DriverStation/MatchNumber", "int64", "", 0);
        w.append(odd, SEC, WpilogWriter.encodeFloat(5f));
        w.append(text, SEC, WpilogWriter.encodeString("Final"));
        w.append(event, 2 * SEC, WpilogWriter.encodeString("XXYY"));
        w.append(type, 2 * SEC, WpilogWriter.encodeInt64(2));
        w.append(number, 2 * SEC, WpilogWriter.encodeInt64(7));
      }
      assertFacts(listed("t.wpilog"), "XXYY", "Qualification", 7);
    }

    @Test
    @DisplayName("a match number while the match type is None is not a match number")
    void numberWithoutType() throws IOException {
      // With no match the Driver Station's match number can hold anything: real logs start
      // with a five-digit number and match type None. It used to be listed as the match.
      try (var w = new WpilogWriter(dir.resolve("g.wpilog"), "")) {
        int type = w.start("/DriverStation/MatchType", "int64", "", 0);
        int number = w.start("/DriverStation/MatchNumber", "int64", "", 0);
        w.append(type, SEC, WpilogWriter.encodeInt64(0));
        w.append(number, SEC, WpilogWriter.encodeInt64(54321));
        w.append(number, 2 * SEC, WpilogWriter.encodeInt64(0));
      }
      assertFacts(listed("g.wpilog"), null, null, null);

      // The number that stands when the type is set is the match's
      try (var w = new WpilogWriter(dir.resolve("h.wpilog"), "")) {
        int type = w.start("/DriverStation/MatchType", "int64", "", 0);
        int number = w.start("/DriverStation/MatchNumber", "int64", "", 0);
        w.append(type, SEC, WpilogWriter.encodeInt64(0));
        w.append(number, SEC, WpilogWriter.encodeInt64(54321));
        w.append(number, 2 * SEC, WpilogWriter.encodeInt64(10));
        w.append(type, 2 * SEC, WpilogWriter.encodeInt64(2));
        w.append(type, 3 * SEC, WpilogWriter.encodeInt64(0));
        w.append(number, 3 * SEC, WpilogWriter.encodeInt64(0));
      }
      assertFacts(listed("h.wpilog"), null, "Qualification", 10);
    }

    @Test
    @DisplayName("the match type and number come together from one source")
    void matchFromOneSource() throws IOException {
      // The records give a type and no number yet: the name's match is taken whole, not its
      // number alone under the records' type
      var partial = "akit_26-01-02_03-04-05_xxyy_q7.wpilog";
      try (var w = new WpilogWriter(dir.resolve(partial), "")) {
        int type = w.start("/DriverStation/MatchType", "int64", "", 0);
        w.append(type, SEC, WpilogWriter.encodeInt64(1));
      }
      assertFacts(listed(partial), "XXYY", "Qualification", 7);

      // The records give both: they stand
      var whole = "akit_26-01-02_04-05-06_xxyy_q7.wpilog";
      try (var w = new WpilogWriter(dir.resolve(whole), "")) {
        int type = w.start("/DriverStation/MatchType", "int64", "", 0);
        int number = w.start("/DriverStation/MatchNumber", "int64", "", 0);
        w.append(number, SEC, WpilogWriter.encodeInt64(4));
        w.append(type, SEC, WpilogWriter.encodeInt64(3));
      }
      assertFacts(listed(whole), "XXYY", "Elimination", 4);
    }

    @Test
    @DisplayName("the log's entries come before its file name; the name fills what they leave")
    void entriesBeforeName() throws IOException {
      var name = "akit_26-01-02_03-04-05_xxyy_q7.wpilog";
      try (var w = new WpilogWriter(dir.resolve(name), "")) {
        int event = w.start("/DriverStation/EventName", "string", "", 0);
        int type = w.start("/DriverStation/MatchType", "int64", "", 0);
        w.append(event, SEC, WpilogWriter.encodeString("")); // not set yet at boot
        w.append(type, SEC, WpilogWriter.encodeInt64(0));
      }
      var info = listed(name);
      assertFacts(info, "XXYY", "Qualification", 7);
      assertEquals(utc(2026, 1, 2, 3, 4, 5), info.logCreationTime());
    }
  }
}
