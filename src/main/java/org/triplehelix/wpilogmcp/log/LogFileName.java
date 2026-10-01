/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.triplehelix.wpilogmcp.log.LogDirectory.MatchType;

/**
 * What a log's file name says, for the names the two logging frameworks write:
 *
 * <ul>
 *   <li>WPILib's DataLogManager: {@code FRC_20260102_030405.wpilog} once the clock is set,
 *       {@code FRC_20260102_030405_XXYY_Q7.wpilog} once the field has given a match (P, Q, or E
 *       and the match number), {@code FRC_TBD_<id>.wpilog} before either. The time is UTC.
 *   <li>AdvantageKit: {@code akit_26-01-02_03-04-05[_event][_q7].wpilog}, with p, q, or e and
 *       the match number. A replay of a log is written beside it with {@code _sim} added.
 * </ul>
 *
 * <p>The event and match are read only from a name of exactly one of these shapes: a file
 * someone renamed says what they wrote, not what the robot recorded. An AdvantageKit name with
 * an event and no match is an event and no match: the Driver Station reports an event name off
 * the field too, with match type None, and a practice match is named {@code _p3}. The time is
 * read wherever a name carries one in either framework's format, renamed or not.
 *
 * @param time The date and time in the name, in the zone of the clock that wrote it, or null
 * @param event The event code, upper-cased, or null
 * @param matchType The match type, or null
 * @param matchNumber The match number, or null
 * @param simulation Whether the name ends in {@code _sim}
 * @since 0.9.0
 */
record LogFileName(LocalDateTime time, String event, MatchType matchType, Integer matchNumber,
    boolean simulation) {

  private static final Pattern DATALOGMANAGER = Pattern.compile(
      "^FRC_\\d{8}_\\d{6}_([A-Za-z0-9]*)_([PQE])(\\d{1,9})\\.wpilog$", Pattern.CASE_INSENSITIVE);

  /** Group 1: the parts after the time, each with its leading underscore. */
  private static final Pattern ADVANTAGEKIT = Pattern.compile(
      "^[a-z]+_\\d{2}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}((?:_[a-z0-9]+)*)\\.wpilog$",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern MATCH = Pattern.compile("^([a-z]+)(\\d{1,9})$");

  /**
   * The match codes a name may carry: the frameworks' p, q, and e, and The Blue Alliance's qm,
   * qf, sf, and f, which the listing has always accepted in names teams write themselves.
   */
  private static final Map<String, MatchType> MATCH_CODES = Map.of(
      "p", MatchType.PRACTICE, "q", MatchType.QUALIFICATION, "qm", MatchType.QUALIFICATION,
      "e", MatchType.ELIMINATION, "qf", MatchType.QUARTERFINAL, "sf", MatchType.SEMIFINAL,
      "f", MatchType.FINAL);

  static LogFileName parse(String filename) {
    var time = WallClock.filenameTime(filename).orElse(null);
    boolean simulation = filename.toLowerCase(Locale.ROOT).endsWith("_sim.wpilog");

    var dlm = DATALOGMANAGER.matcher(filename);
    if (dlm.matches() && time != null) {
      var event = dlm.group(1).isEmpty() ? null : dlm.group(1).toUpperCase(Locale.ROOT);
      return new LogFileName(time, event, MATCH_CODES.get(dlm.group(2).toLowerCase(Locale.ROOT)),
          Integer.valueOf(dlm.group(3)), false);
    }

    var akit = ADVANTAGEKIT.matcher(filename);
    if (akit.matches() && time != null) {
      var parts = new java.util.ArrayList<String>();
      for (var part : akit.group(1).toLowerCase(Locale.ROOT).split("_")) {
        if (!part.isEmpty()) parts.add(part);
      }
      if (simulation) parts.remove(parts.size() - 1);
      MatchType matchType = null;
      Integer matchNumber = null;
      if (!parts.isEmpty()) {
        var match = MATCH.matcher(parts.get(parts.size() - 1));
        if (match.matches() && MATCH_CODES.containsKey(match.group(1))) {
          matchType = MATCH_CODES.get(match.group(1));
          matchNumber = Integer.valueOf(match.group(2));
          parts.remove(parts.size() - 1);
        }
      }
      // What is left is the event, or nothing. More than one part is someone's own naming.
      if (parts.size() <= 1) {
        var event = parts.isEmpty() ? null : parts.get(0).toUpperCase(Locale.ROOT);
        return new LogFileName(time, event, matchType, matchNumber, simulation);
      }
    }
    return new LogFileName(time, null, null, null, simulation);
  }
}
