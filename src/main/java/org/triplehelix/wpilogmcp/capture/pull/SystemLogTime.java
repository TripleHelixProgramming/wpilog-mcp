/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

/** Parse only written clocks with a year and zone. A traditional syslog date supplies neither. */
public final class SystemLogTime {
  private SystemLogTime() {}
  private static final java.util.regex.Pattern UNIX = java.util.regex.Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s.*");
  private static final java.util.regex.Pattern ISO = java.util.regex.Pattern.compile("(?:^|.*?\\b)([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+(?:Z|[+-][0-9]{2}:[0-9]{2}))(?:\\s|$).*");
  private static final java.util.regex.Pattern SYSLOG = java.util.regex.Pattern.compile("^([A-Z][a-z]{2} +[0-9]{1,2} [0-9]{2}:[0-9]{2}:[0-9]{2})\\s.*");
  public static String written(String line, String format) {
    if (format.equals("dmesg")) { int end = line.indexOf(']'); return KernelLines.seconds(line) == null ? null : line.substring(0, end + 1); }
    var match = (format.equals("journal") ? UNIX : ISO).matcher(line);
    if (match.matches()) return match.group(1);
    var traditional = SYSLOG.matcher(line); return traditional.matches() ? traditional.group(1) : null;
  }
  public static Double epoch(String line, String format) {
    String text = written(line, format); if (text == null) return null;
    try {
      if (format.equals("journal")) { double value = Double.parseDouble(text); return Double.isFinite(value) ? value : null; }
      var time = java.time.OffsetDateTime.parse(text).toInstant(); return time.getEpochSecond() + time.getNano() / 1e9;
    } catch (IllegalArgumentException | java.time.DateTimeException e) { return null; }
  }
}
