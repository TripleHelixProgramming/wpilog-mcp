/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.read.UtcTimeMapping;

/**
 * {@code capture=current} over the session recording now: its open file and the closed files a
 * window reaches. A rollover and a same-boot reconnect each close a file and continue the session
 * in the next, and a session continues only while the robot's clock does, so its files share that
 * clock: a time in the log's seconds means the same in each. In UTC a time is placed by the clock
 * of the file that was recording then, each closed file's up to its last record and the next
 * file's after it. A reconnect's file starts with the values the robot re-sends at their original
 * times; they are placed where the closed file first recorded them, so the two copies agree.
 */
final class DataRecording {
  private DataRecording() {}

  /**
   * The closed files a window may reach, oldest first. For a UTC window their times on the pit's
   * clock only nominate them, within the margin a robot range allows, as each is then placed by
   * its own clock; for a window in the log's seconds their span on the robot's clock decides,
   * though a file the manifest still names open is judged by its start alone, since its span was
   * still growing when published; without a window every one is read. At most
   * {@link DataRanges#MAX_FILES} files answer with the open one; more are refused with the count.
   */
  static List<LiveCapture.SessionFile> nominate(List<LiveCapture.SessionFile> files, Double startTime,
      Double endTime, Long startUtc, Long endUtc) throws DataEndpoint.Refusal {
    var found = new ArrayList<LiveCapture.SessionFile>();
    boolean utc = startUtc != null || endUtc != null;
    for (var file : files) {
      if (utc) {
        Long started = DataRanges.epochMicros(file.startedAt()), ended = DataRanges.epochMicros(file.endedAt());
        if (endUtc != null && started != null && started > endUtc + DataRanges.NOMINATION_MARGIN_US) continue;
        if (startUtc != null && ended != null && ended < startUtc - DataRanges.NOMINATION_MARGIN_US) continue;
      } else {
        if (endTime != null && file.minUs() / 1e6 > endTime) continue;
        if (startTime != null && file.endedAt() != null && file.maxUs() / 1e6 < startTime) continue;
      }
      found.add(file);
    }
    if (found.size() + 1 > DataRanges.MAX_FILES) {
      var refusal = new DataEndpoint.Refusal(413, "The window reaches " + (found.size() + 1) + " files of the recording, "
          + "over the limit of " + DataRanges.MAX_FILES, "Narrow the window");
      refusal.body.addProperty("files", found.size() + 1);
      refusal.body.addProperty("max_files", DataRanges.MAX_FILES);
      throw refusal;
    }
    return found;
  }

  /**
   * The recording's clock. File k's stretch of the robot's clock runs from just after file k-1's
   * last record to its own, and the open file's runs on; a time is placed by the clock of the file
   * whose stretch holds it, and a file whose clock places nothing leaves its stretch unplaced, with
   * its reason.
   */
  static final class Clock implements DataEndpoint.Placement {
    private final List<UtcTimeMapping> clocks;
    private final long[] ends;

    /** One clock per file, oldest first, and each file's last record; the last file's stretch runs on. */
    Clock(List<UtcTimeMapping> clocks, long[] ends) {
      this.clocks = List.copyOf(clocks);
      this.ends = ends.clone();
      this.ends[this.ends.length - 1] = Long.MAX_VALUE;
    }

    int stretch(long logMicros) {
      int low = 0, high = ends.length - 1;
      while (low < high) {
        int middle = (low + high) >>> 1;
        if (ends[middle] < logMicros) low = middle + 1; else high = middle;
      }
      return low;
    }

    UtcTimeMapping clock(int file) { return clocks.get(file); }
    int files() { return clocks.size(); }

    /** Whether some file's clock places some time: otherwise no window in UTC holds anything. */
    boolean places() {
      return clocks.stream().anyMatch(c -> c.unavailableReason().isEmpty());
    }

    @Override public UtcTimeMapping.Mapping at(long logMicros) {
      return clocks.get(stretch(logMicros)).at(logMicros);
    }

    private long from(int file) { return file == 0 ? Long.MIN_VALUE : ends[file - 1] + 1; }

    /**
     * The robot times a UTC window holds, one interval for each stretch whose clock places some of
     * it, in order; a file's clock runs on past its last reading, but it answers only in its stretch.
     */
    List<long[]> window(Long startUtc, Long endUtc) {
      var out = new ArrayList<long[]>();
      for (int k = 0; k < clocks.size(); k++) {
        var clock = clocks.get(k);
        if (clock.unavailableReason().isPresent()) continue;
        var window = DataEndpoint.logWindow(clock, startUtc, endUtc);
        if (window == null) continue;
        long low = Math.max(window[0], from(k)), high = Math.min(window[1], ends[k]);
        if (low <= high) out.add(new long[] {low, high});
      }
      return out;
    }

    /** A stretch of robot time that no clock places, and why. */
    record Unplaced(long fromUs, long toUs, String reason) {}

    /** What the clock leaves unplaced: a stretch whose file has no clock, and a stretch's start before its clock's first reading. */
    List<Unplaced> unplaced() {
      var out = new ArrayList<Unplaced>();
      for (int k = 0; k < clocks.size(); k++) {
        var clock = clocks.get(k);
        long low = from(k), high = ends[k];
        if (low > high) continue;
        var reason = clock.unavailableReason();
        if (reason.isPresent()) out.add(new Unplaced(low, high, reason.get()));
        else if (clock.firstLogMicros() > low)
          out.add(new Unplaced(low, Math.min(clock.firstLogMicros() - 1, high), "Before the first valid wall-clock reading"));
      }
      return out;
    }

    /** The distinct reasons, in order, of the stretches given. */
    static String reasons(List<Unplaced> stretches) {
      var reasons = new LinkedHashSet<String>();
      stretches.forEach(s -> reasons.add(s.reason()));
      return reasons.isEmpty() ? null : String.join("; ", reasons);
    }
  }

  /** Time-ordered lists merged in time order; on equal times an earlier list's value comes first. */
  static List<TimestampedValue> merge(List<List<TimestampedValue>> lists) {
    var out = new ArrayList<TimestampedValue>(lists.stream().mapToInt(List::size).sum());
    lists.forEach(out::addAll);
    // A stable sort of runs already in order: the reconnect's repeated values interleave.
    out.sort(Comparator.comparingLong(TimestampedValue::timestampUs));
    return out;
  }
}
