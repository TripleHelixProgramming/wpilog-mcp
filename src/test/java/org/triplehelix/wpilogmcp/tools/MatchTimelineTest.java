/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.util.datalog.DataLogReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.subsystems.StructDecoderRegistry;
import org.triplehelix.wpilogmcp.tools.MatchTimeline.EndReason;
import org.triplehelix.wpilogmcp.tools.MatchTimeline.Mode;
import org.triplehelix.wpilogmcp.tools.MatchTimeline.State;

@DisplayName("MatchTimeline")
class MatchTimelineTest {

  @TempDir static Path dir;
  static final Map<String, LazyParsedLog> fixtures = new HashMap<>();

  @BeforeAll
  static void loadFixtures() throws Exception {
    for (var f : FixtureLogs.generateAll(dir)) {
      fixtures.put(f.id(), new LazyParsedLog(f.path().toString(),
          new DataLogReader(f.path().toString()), new StructDecoderRegistry(), 100_000_000));
    }
  }

  @AfterAll
  static void close() {
    fixtures.values().forEach(LazyParsedLog::close);
  }

  static MatchTimeline fixture(String id) {
    return MatchTimeline.of(fixtures.get(id));
  }

  static List<TimestampedValue> bools(double[] t, boolean[] v) {
    var out = new ArrayList<TimestampedValue>();
    for (int i = 0; i < t.length; i++) out.add(new TimestampedValue(t[i], v[i]));
    return out;
  }

  static LogData log(String path, Object... entries) {
    var b = new MockLogBuilder().setPath(path);
    for (int i = 0; i < entries.length; i += 3) {
      var name = (String) entries[i];
      var t = (double[]) entries[i + 1];
      var v = entries[i + 2];
      if (v instanceof boolean[] bv) {
        b.addBooleanEntry(name, t, bv);
      } else if (v instanceof long[] lv) {
        var tvs = new ArrayList<TimestampedValue>();
        for (int k = 0; k < t.length; k++) tvs.add(new TimestampedValue(t[k], lv[k]));
        b.addEntry(name, "int64", tvs);
      } else if (v instanceof double[] dv) {
        b.addNumericEntry(name, t, dv);
      } else if (v instanceof String[] sv) {
        var tvs = new ArrayList<TimestampedValue>();
        for (int k = 0; k < t.length; k++) tvs.add(new TimestampedValue(t[k], sv[k]));
        b.addEntry(name, "string", tvs);
      }
    }
    return b.build();
  }

  @Nested
  @DisplayName("fixture logs")
  class Fixtures {

    @Test
    @DisplayName("practice session: four enabled segments, the last running to the log end")
    void practiceSegments() {
      var tl = fixture("akit_practice");
      var enabled = tl.enabledSegments();
      assertEquals(4, enabled.size());
      double[][] expected = {{40.2, 70.0}, {80.0, 110.0}, {120.0, 130.0}, {150.0, 240.0}};
      for (int i = 0; i < 4; i++) {
        assertEquals(expected[i][0], enabled.get(i).start(), 1e-9, "start " + i);
        assertEquals(expected[i][1], enabled.get(i).end(), 1e-9, "end " + i);
        assertEquals(Mode.TELEOP, enabled.get(i).mode(), "Autonomous held false");
      }
      assertEquals(EndReason.DISABLED, enabled.get(2).endReason());
      assertEquals(EndReason.LOG_END, enabled.get(3).endReason());
      assertFalse(tl.autonomousEverTrue());
      assertEquals(1, tl.autonomousSampleCount());
      assertTrue(tl.matches().isEmpty(), "a practice session is not a match");
      // segments tile the log with no gaps
      var segs = tl.segments();
      assertEquals(8.36, segs.get(0).start(), 1e-9);
      for (int i = 1; i < segs.size(); i++) {
        assertEquals(segs.get(i - 1).end(), segs.get(i).start(), 1e-12);
      }
      assertEquals(new MatchTimeline.Season(2026, "log_clock:/SystemStats/EpochTimeMicros"),
          tl.season());
    }

    @Test
    @DisplayName("FMS match: autonomous, teleop, and endgame from the 2026 timing")
    void fmsMatch() {
      var tl = fixture("akit_match");
      assertEquals(1, tl.matches().size());
      var m = tl.matches().get(0);
      assertEquals("fms_attached", m.basis());
      assertTrue(m.complete());
      assertEquals(20.0, m.auto().start(), 1e-9);
      assertEquals(40.0, m.auto().end(), 1e-9);
      assertEquals(43.0, m.teleop().start(), 1e-9);
      assertEquals(183.0, m.teleop().end(), 1e-9);
      assertEquals(153.0, m.endgameStart(), 1e-9); // 2026 endgame: last 30 s
      assertEquals(Mode.AUTO, tl.modeAt(30.0));
      assertEquals(Mode.TELEOP, tl.modeAt(100.0));
      assertEquals(State.DISABLED, tl.stateAt(41.0));
      assertTrue(tl.fmsAttachedAt(20.0));
    }

    @Test
    @DisplayName("WPILib DataLogManager: DS: booleans, FMS from the control word, season 2025")
    void wpilibMatch() {
      var tl = fixture("wpilib_dlm");
      assertEquals("DS:enabled", tl.sources().enabled());
      assertEquals("DS:autonomous", tl.sources().autonomous());
      assertEquals("NT:/FMSInfo/FMSControlData", tl.sources().controlWord());
      assertEquals(2025, tl.season().year());
      assertEquals("log_clock:systemTime", tl.season().basis());
      assertEquals(1, tl.matches().size());
      var m = tl.matches().get(0);
      assertEquals("fms_attached", m.basis());
      assertEquals(10.0, m.auto().start(), 1e-9);
      assertEquals(28.0, m.teleop().start(), 1e-9);
      assertEquals(143.0, m.endgameStart(), 1e-9); // 2025 endgame: last 20 s
      assertTrue(m.complete());
    }

    @Test
    @DisplayName("both DS naming conventions: AdvantageKit entries win and events are not doubled")
    void dualDs() {
      var tl = fixture("dual_ds");
      assertEquals("/DriverStation/Enabled", tl.sources().enabled());
      assertTrue(tl.sources().ignored().contains("DS:enabled"));
      assertTrue(tl.sources().ignored().contains("DS:autonomous"));
      long enables = tl.events(null, null).stream().filter(e -> e.type().equals("ENABLED")).count();
      assertEquals(2, enables);
    }

    @Test
    @DisplayName("no DriverStation entries: state unknown throughout")
    void noDs() {
      var tl = fixture("no_ds");
      assertFalse(tl.hasEnabledData());
      assertEquals(1, tl.segments().size());
      assertEquals(State.UNKNOWN, tl.segments().get(0).state());
      assertTrue(tl.events(null, null).isEmpty());
      assertTrue(tl.matches().isEmpty());
    }

    @Test
    @DisplayName("empty log: no segments")
    void empty() {
      var tl = fixture("empty");
      assertTrue(tl.enabledSegments().isEmpty());
      assertTrue(tl.matches().isEmpty());
    }
  }

  @Nested
  @DisplayName("segments")
  class Segments {

    @Test
    @DisplayName("a disable logged at the last timestamp ends the final segment with 'disabled'")
    void disableAtLogEnd() {
      var tl = MatchTimeline.of(log("/t/a.wpilog",
          "/DriverStation/Enabled", new double[] {0, 1, 5}, new boolean[] {false, true, false}));
      var segs = tl.segments();
      assertEquals(2, segs.size());
      assertEquals(State.ENABLED, segs.get(1).state());
      assertEquals(EndReason.DISABLED, segs.get(1).endReason());
      var types = tl.events(null, null).stream().map(MatchTimeline.Event::type).toList();
      assertEquals(List.of("DISABLED", "ENABLED", "DISABLED"), types);
      assertTrue(tl.events(null, null).get(0).initial());
    }

    @Test
    @DisplayName("state is unknown before the first DriverStation sample")
    void unknownBeforeData() {
      var tl = MatchTimeline.of(log("/t/b.wpilog",
          "/Other", new double[] {0, 10}, new double[] {1, 2},
          "/DriverStation/Enabled", new double[] {2, 4}, new boolean[] {false, true}));
      var segs = tl.segments();
      assertEquals(State.UNKNOWN, segs.get(0).state());
      assertEquals(EndReason.DS_DATA, segs.get(0).endReason());
      assertEquals(2.0, segs.get(0).end(), 1e-9);
      assertEquals(State.UNKNOWN, tl.stateAt(1.0));
      assertEquals(State.ENABLED, segs.get(segs.size() - 1).state());
      assertEquals(EndReason.LOG_END, segs.get(segs.size() - 1).endReason());
    }

    @Test
    @DisplayName("a mode flag logged just after the enable is not a separate segment")
    void modeSettles() {
      var tl = MatchTimeline.of(log("/t/c.wpilog",
          "/DriverStation/Enabled", new double[] {0, 5, 25}, new boolean[] {false, true, false},
          "/DriverStation/Autonomous", new double[] {0, 5.02, 25.02},
          new boolean[] {false, true, false},
          "/Other", new double[] {30}, new double[] {1}));
      var enabled = tl.enabledSegments();
      assertEquals(1, enabled.size());
      assertEquals(Mode.AUTO, enabled.get(0).mode());
      assertEquals(5.0, enabled.get(0).start(), 1e-9);
      assertEquals(25.0, enabled.get(0).end(), 1e-9);
    }

    @Test
    @DisplayName("a mode change while enabled splits the segment with 'mode_change'")
    void modeChangeWhileEnabled() {
      var tl = MatchTimeline.of(log("/t/d.wpilog",
          "/DriverStation/Enabled", new double[] {0, 160}, new boolean[] {true, false},
          "/DriverStation/Autonomous", new double[] {0, 20}, new boolean[] {true, false},
          "/Other", new double[] {170}, new double[] {1}));
      var enabled = tl.enabledSegments();
      assertEquals(2, enabled.size());
      assertEquals(EndReason.MODE_CHANGE, enabled.get(0).endReason());
      assertEquals(Mode.TELEOP, enabled.get(1).mode());
      assertEquals(20.0, enabled.get(1).start(), 1e-9);
      assertEquals(1, tl.matches().size(), "auto then teleop with no gap is a match run");
      assertEquals("mode_sequence", tl.matches().get(0).basis());
    }

    @Test
    @DisplayName("the control word alone gives enabled state, mode, and FMS attachment")
    void controlWordOnly() {
      var tl = MatchTimeline.of(log("/t/2026_e.wpilog",
          "NT:/FMSInfo/FMSControlData", new double[] {0, 10, 30, 33, 173},
          new long[] {48, 51, 48, 49, 48}));
      assertEquals(State.ENABLED, tl.stateAt(15));
      assertEquals(Mode.AUTO, tl.modeAt(15));
      assertEquals(Mode.TELEOP, tl.modeAt(100));
      assertTrue(tl.fmsAttachedAt(15));
      assertEquals(1, tl.matches().size());
      assertEquals("fms_attached", tl.matches().get(0).basis());
    }

    @Test
    @DisplayName("an FMS teleop period with the robot disabled through auto is still a match")
    void fmsTeleopOnly() {
      var tl = MatchTimeline.of(log("/t/2026_f.wpilog",
          "/DriverStation/FMSAttached", new double[] {0}, new boolean[] {true},
          "/DriverStation/Enabled", new double[] {0, 23, 163}, new boolean[] {false, true, false},
          "/DriverStation/Autonomous", new double[] {0}, new boolean[] {false},
          "/Other", new double[] {170}, new double[] {1}));
      assertEquals(1, tl.matches().size());
      assertNull(tl.matches().get(0).auto());
      assertTrue(tl.matches().get(0).complete());
    }

    @Test
    @DisplayName("without FMS, a 2 s auto test followed by teleop is not a match")
    void shortAutoIsNotAMatch() {
      var tl = MatchTimeline.of(log("/t/2026_g.wpilog",
          "/DriverStation/Enabled", new double[] {0, 5, 7, 8, 60},
          new boolean[] {false, true, false, true, false},
          "/DriverStation/Autonomous", new double[] {0, 4.98, 7.02},
          new boolean[] {false, true, false}));
      assertEquals(2, tl.enabledSegments().size());
      assertTrue(tl.matches().isEmpty());
    }

    @Test
    @DisplayName("windowed events include only transitions inside the window")
    void windowedEvents() {
      var tl = MatchTimeline.of(log("/t/h.wpilog",
          "/DriverStation/Enabled", new double[] {0, 111, 126, 128, 263},
          new boolean[] {false, true, false, true, false},
          "/DriverStation/Autonomous", new double[] {0, 27, 126},
          new boolean[] {false, true, false}));
      var all = tl.events(null, null).stream()
          .filter(e -> e.category().equals("match_phase")).toList();
      assertEquals(2, all.size());
      assertEquals("AUTO_START", all.get(0).type());
      assertEquals(111.0, all.get(0).timestamp(), 1e-9);
      assertEquals("TELEOP_START", all.get(1).type());
      assertEquals(128.0, all.get(1).timestamp(), 1e-9);
      var windowed = tl.events(115.0, null);
      assertTrue(windowed.stream().noneMatch(e -> e.type().equals("AUTO_START")));
      assertTrue(windowed.stream().anyMatch(e -> e.type().equals("TELEOP_START")));
    }
  }

  @Nested
  @DisplayName("season")
  class Seasons {

    @Test
    @DisplayName("the build date is used when the log has no wall clock")
    void buildDate() {
      var season = MatchTimeline.seasonOf(log("/logs/2019/x.wpilog",
          "/RealMetadata/BuildDate", new double[] {0}, new String[] {"2024-02-10 10:00:00 EST"}));
      assertEquals(2024, season.year());
      assertTrue(season.basis().startsWith("build_date"));
    }

    @Test
    @DisplayName("a year in a directory name is ignored; the file name counts")
    void fileNameOnly() {
      assertEquals(2025, MatchTimeline.seasonOf(log("/logs/2019/FRC_2025_x.wpilog",
          "/Other", new double[] {0}, new double[] {1})).year());
      var fallback = MatchTimeline.seasonOf(log("/logs/2019/match.wpilog",
          "/Other", new double[] {0}, new double[] {1}));
      assertEquals("current_year", fallback.basis());
    }

    @Test
    @DisplayName("an invalid wall clock (1970) is skipped")
    void invalidClock() {
      var tvs = new ArrayList<TimestampedValue>();
      tvs.add(new TimestampedValue(0.0, 0L));
      var log = new MockLogBuilder().setPath("/t/2025_i.wpilog")
          .addEntry("/SystemStats/EpochTimeMicros", "int64", tvs).build();
      assertEquals(new MatchTimeline.Season(2025, "file_name"), MatchTimeline.seasonOf(log));
    }
  }
}
