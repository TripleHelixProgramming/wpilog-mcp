/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import edu.wpi.first.util.datalog.DataLogAccess;
import edu.wpi.first.util.datalog.DataLogReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One pass over a WPILOG file's records: the entries in declaration order, each entry's data-record
 * byte offsets, and the time range, stopping where the file stops being a valid log. Shared by
 * {@link LazyParsedLog} and {@code LogParser}, so both read a damaged file the same way.
 *
 * <p>A log cut off mid-write (power loss, a robot program killed) can end in bytes that parse as
 * records but are not: records for entry ids the log never declared, and timestamps years or
 * thousands of years away. One such timestamp would stretch the log's time range, and with it
 * every timeline, duration, and time-based match built on it. So:
 * <ul>
 *   <li>A data record for an entry id that was never declared ends the scan: the rest of the file
 *       is damaged. So does a record that cannot be read at all (the file ends inside it).
 *   <li>The last few records before that point whose timestamps run backward more than
 *       {@value #MAX_BACKWARD_NEAR_DAMAGE_SEC} s past the log so far belong to the damage and are
 *       dropped. (Elsewhere a backward timestamp can be real: NetworkTables logging records a
 *       value with the time it last changed.)
 *   <li>A record whose timestamp jumps more than {@value #MAX_FORWARD_JUMP_SEC} s past the log so
 *       far is ignored wherever it is: a robot's clock cannot do that within one log.
 * </ul>
 * The result says what was ignored, with byte offsets, in {@link #truncationMessage()}.
 *
 * @param entries Entries by name, in declaration order
 * @param offsets Each entry's data-record byte offsets, in file order
 * @param minTimestamp Earliest timestamp of an accepted record (seconds), 0 if none
 * @param maxTimestamp Latest timestamp of an accepted record (seconds), 0 if none
 * @param dataRecords The number of accepted data records
 * @param truncated Whether part of the file could not be read as a valid log
 * @param truncationMessage What was not read, and why, or null
 * @since 0.9.0
 */
public record LogScan(Map<String, EntryInfo> entries, Map<String, List<Integer>> offsets,
    double minTimestamp, double maxTimestamp, int dataRecords, boolean truncated,
    String truncationMessage) {

  private static final Logger logger = LoggerFactory.getLogger(LogScan.class);

  /** A forward jump in time larger than this (a day) cannot happen within one log. */
  static final double MAX_FORWARD_JUMP_SEC = 86_400.0;

  /** Near damage, a record whose time runs backward more than this is part of the damage. */
  static final double MAX_BACKWARD_NEAR_DAMAGE_SEC = 60.0;

  /** How many records before the damage are examined. */
  static final int ROLLBACK_LIMIT = 16;

  /** An accepted data record, with the time range before it (to undo it). */
  private record Accepted(String name, double timestamp, double minBefore, double maxBefore) {}

  /**
   * Scans a log.
   *
   * @param reader An open reader over the file
   * @param path The file (for the header length)
   * @return The scan
   * @throws IOException if the header cannot be read
   */
  public static LogScan of(DataLogReader reader, Path path) throws IOException {
    var entriesById = new HashMap<Integer, EntryInfo>();
    var ignoredIds = new HashSet<Integer>(); // declared, deliberately not indexed
    var entriesByName = new LinkedHashMap<String, EntryInfo>();
    var offsets = new HashMap<String, List<Integer>>();
    double minTs = Double.MAX_VALUE;
    double maxTs = Double.NEGATIVE_INFINITY;
    int dataRecords = 0;
    int jumps = 0;
    int firstJump = -1;
    String damage = null;
    var recent = new ArrayDeque<Accepted>();

    // Walk records by their own bounds (DataLogAccess.recordEnd), not WPILib's iterator, whose
    // hasNext() skips a final record shorter than 16 bytes
    int pos = DataLogAccess.firstRecordOffset(path);
    int size = DataLogAccess.size(reader);
    try {
      while (pos < size) {
        int next = DataLogAccess.recordEnd(reader, pos);
        if (next < 0) {
          damage = "the file ends inside a record at byte " + pos;
          break;
        }
        var record = DataLogAccess.getRecord(reader, pos);
        if (record.isStart()) {
          var start = record.getStartData();
          if (start.name == null || start.name.isEmpty()) {
            ignoredIds.add(start.entry);
          } else {
            var info = new EntryInfo(start.entry, start.name, start.type, start.metadata);
            var existing = entriesByName.get(start.name);
            if (existing == null) {
              entriesByName.put(start.name, info);
              entriesById.put(start.entry, info);
              offsets.put(start.name, new ArrayList<>());
            } else if (existing.type().equals(start.type)) {
              // The same name started again (after a Finish, or by another writer): one entry,
              // keeping the first declaration and all records
              entriesById.put(start.entry, existing);
            } else {
              logger.warn("Entry '{}' restarted with type '{}' (was '{}'); ignoring its records",
                  start.name, start.type, existing.type());
              entriesById.remove(start.entry);
              ignoredIds.add(start.entry);
            }
          }
        } else if (!record.isControl()) {
          int id = record.getEntry();
          var info = entriesById.get(id);
          if (info == null) {
            if (!ignoredIds.contains(id)) {
              damage = "a data record at byte " + pos + " refers to entry id " + id
                  + ", which the log never declared";
              break;
            }
          } else {
            double timestamp = record.getTimestamp() / 1_000_000.0;
            if (dataRecords > 0 && timestamp > maxTs + MAX_FORWARD_JUMP_SEC) {
              jumps++;
              if (firstJump < 0) firstJump = pos;
            } else {
              recent.addLast(new Accepted(info.name(), timestamp, minTs, maxTs));
              if (recent.size() > ROLLBACK_LIMIT) recent.removeFirst();
              minTs = Math.min(minTs, timestamp);
              maxTs = Math.max(maxTs, timestamp);
              offsets.get(info.name()).add(pos);
              dataRecords++;
            }
          }
        }
        pos = next;
      }
    } catch (java.util.NoSuchElementException | java.nio.BufferUnderflowException
             | IndexOutOfBoundsException | IllegalArgumentException e) {
      // WPILib's reader throws these on a record cut off mid-write
      damage = "the record at byte " + pos + " cannot be read ("
          + e.getClass().getSimpleName() + ")";
    }

    int rolledBack = 0;
    if (damage != null) {
      while (!recent.isEmpty()) {
        var last = recent.peekLast();
        if (last.maxBefore() == Double.NEGATIVE_INFINITY
            || last.timestamp() >= last.maxBefore() - MAX_BACKWARD_NEAR_DAMAGE_SEC) {
          break;
        }
        recent.removeLast();
        var list = offsets.get(last.name());
        list.remove(list.size() - 1);
        minTs = last.minBefore();
        maxTs = last.maxBefore();
        dataRecords--;
        rolledBack++;
      }
    }

    double min = minTs == Double.MAX_VALUE ? 0 : minTs;
    double max = maxTs == Double.NEGATIVE_INFINITY ? 0 : maxTs;
    String message = null;
    if (damage != null || jumps > 0) {
      var parts = new ArrayList<String>();
      if (damage != null) {
        parts.add("Log file is truncated or damaged: " + damage + "; the rest of the file was "
            + "not read" + (rolledBack > 0 ? ", and the " + rolledBack + " record"
                + (rolledBack == 1 ? "" : "s") + " just before it, whose timestamps ran backward, "
                + (rolledBack == 1 ? "was" : "were") + " dropped" : "") + ".");
      }
      if (jumps > 0) {
        parts.add(jumps + " record" + (jumps == 1 ? " whose timestamp jumps" : "s whose "
            + "timestamps jump") + " more than a day past the rest of the log " + (jumps == 1
                ? "was" : "were") + " ignored (the first at byte " + firstJump + ").");
      }
      parts.add(String.format("Data from %.2f to %.2f s was recovered.", min, max));
      message = String.join(" ", parts);
      logger.warn("Log file '{}': {}", path.getFileName(), message);
    }

    return new LogScan(Collections.unmodifiableMap(entriesByName), offsets, min, max,
        dataRecords, message != null, message);
  }
}
