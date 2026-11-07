/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.util.List;
import java.util.regex.Pattern;

/** Ring-buffer continuity is a recorded line and its clock, never just a line count. */
public final class KernelLines {
  private KernelLines() {}
  private static final Pattern STAMP = Pattern.compile("^\\[\\s*([0-9]+(?:\\.[0-9]+)?)\\].*");
  public record Cursor(double uptimeSec, String lastLine, Double lastSeconds) {}
  public record Delta(List<String> lines, Cursor cursor, boolean reboot, String note) {}
  public static Double seconds(String line) {
    var match = STAMP.matcher(line);
    if (!match.matches()) return null;
    double value = Double.parseDouble(match.group(1)); return Double.isFinite(value) ? value : null;
  }
  public static Delta following(List<String> lines, double uptime, Cursor previous) {
    if (!Double.isFinite(uptime) || uptime < 0) throw new IllegalArgumentException("Kernel uptime must be finite and nonnegative");
    boolean reboot = previous != null && uptime < previous.uptimeSec();
    int from = 0;
    String note = null;
    if (previous != null && !reboot && previous.lastLine() != null) {
      from = -1;
      for (int i = lines.size() - 1; i >= 0; i--) {
        if (lines.get(i).equals(previous.lastLine()) && java.util.Objects.equals(seconds(lines.get(i)), previous.lastSeconds())) {
          from = i + 1; break;
        }
      }
      if (from < 0) {
        note = "Previous kernel line is no longer in the ring buffer; earlier text may have been overwritten";
        from = lines.size();
        if (previous.lastSeconds() != null) for (int i = 0; i < lines.size(); i++) {
          var time = seconds(lines.get(i));
          if (time != null && time > previous.lastSeconds()) { from = i; break; }
        }
      }
    }
    var added = List.copyOf(lines.subList(from, lines.size()));
    var cursor = added.isEmpty() && previous != null && !reboot
        ? new Cursor(uptime, previous.lastLine(), previous.lastSeconds())
        : lines.isEmpty() ? new Cursor(uptime, null, null)
        : new Cursor(uptime, lines.get(lines.size() - 1), seconds(lines.get(lines.size() - 1)));
    return new Delta(added, cursor, reboot, note);
  }
}
