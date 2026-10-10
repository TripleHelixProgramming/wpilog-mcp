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
 *       is damaged. An incomplete final record stops at its first byte for a later resume;
 *       that alone is truncation, not damage.
 *   <li>The last few records before that point whose timestamps run more than
 *       {@value #MAX_BACKWARD_NEAR_DAMAGE_SEC} s backward or ahead of the log so far belong to
 *       the damage and are dropped. (Elsewhere a backward timestamp can be real: NetworkTables
 *       logging records a value with the time it last changed.)
 *   <li>A record whose timestamp jumps more than {@value #MAX_FORWARD_JUMP_SEC} s past the log so
 *       far is ignored wherever it is: a robot's clock cannot do that within one log.
 *   <li>A negative timestamp is data, not damage. Logs written by WPILib's DataLogManager on real
 *       robots routinely hold records tens of seconds before zero (typically one per
 *       NetworkTables entry), and dropping them empties those entries.
 *   <li>A header whose extra-header length runs past the end of the file is damage too.
 * </ul>
 * The result says what was ignored, with byte offsets, in {@link #truncationMessage()}.
 *
 * @param entries Entries by name, in declaration order
 * @param offsets Each entry's data-record byte offsets, in file order
 * @param minTimestamp Earliest timestamp of an accepted record (seconds), 0 if none
 * @param maxTimestamp Latest timestamp of an accepted record (seconds), 0 if none
 * @param dataRecords The number of accepted data records
 * @param truncated Whether part of the file could not be read as a valid log
 * @param damaged Whether more was lost than a final record cut off mid-write: garbage or
 *     unreadable records, or records set aside for their timestamps
 * @param truncationMessage What was not read, and why, or null
 * @param resumePoint First byte not yet indexed, including an incomplete final record
 * @param scannedFrom First byte visited by this scan, after a copied index when resumed
 * @param continuation Opaque declaration/anchor state, or null when damage requires a fresh scan
 * @since 0.9.0
 */
public record LogScan(Map<String, EntryInfo> entries, Map<String, RecordOffsets> offsets,
    double minTimestamp, double maxTimestamp, int dataRecords, boolean truncated,
    boolean damaged, String truncationMessage, long resumePoint, long scannedFrom, Continuation continuation) {

  private static final Logger logger = LoggerFactory.getLogger(LogScan.class);

  /** A forward jump in time larger than this (a day) cannot happen within one log. */
  static final double MAX_FORWARD_JUMP_SEC = 86_400.0;

  /**
   * Near damage, a record whose time runs backward or ahead more than this is part of the
   * damage.
   */
  static final double MAX_BACKWARD_NEAR_DAMAGE_SEC = 60.0;

  /** How many records before the damage are examined. */
  static final int ROLLBACK_LIMIT = 16;

  /** An accepted data record, with the time range before it (to undo it). */
  private record Accepted(String name, double timestamp, double minBefore, double maxBefore) {}

  /** State needed by an append, including redeclared IDs and the damage rollback boundary. */
  public static final class Continuation {
    private final Map<Integer, EntryInfo> ids;
    private final java.util.Set<Integer> ignored;
    private final List<Accepted> recent;
    private final long fileSize, headerEnd, lastRecord;
    private final byte[] header, lastRecordHash;
    private Continuation(Map<Integer, EntryInfo> ids, java.util.Set<Integer> ignored,
        List<Accepted> recent, long fileSize, long headerEnd, byte[] header, long lastRecord, byte[] lastRecordHash) {
      this.ids = ids; this.ignored = ignored; this.recent = recent; this.fileSize = fileSize;
      this.headerEnd = headerEnd; this.header = header; this.lastRecord = lastRecord; this.lastRecordHash = lastRecordHash;
    }
  }

  /** Only complete anchors are compared. Interior rewrites that preserve both anchors are not detected. */
  boolean acceptsAppend(LogReader reader) {
    var c = continuation;
    if (damaged || c == null || c.header == null || reader.size() <= c.fileSize) return false;
    return java.util.Arrays.equals(c.header, reader.fingerprint(0, c.headerEnd))
        && (c.lastRecord < 0 || java.util.Arrays.equals(c.lastRecordHash,
            reader.fingerprint(c.lastRecord, resumePoint)));
  }

  /** Remap outside this class, then copy the old index and read only records at its resume point. */
  public static LogScan resume(LogScan previous, LogReader reader, Path path) throws IOException {
    return previous.acceptsAppend(reader) ? scan(previous, reader, path) : of(reader, path);
  }

  /**
   * Scans a log.
   *
   * @param reader An open reader over the file
   * @param path The file (for the header length)
   * @return The scan
   * @throws IOException if the header cannot be read
   */
  public static LogScan of(DataLogReader reader, Path path) throws IOException {
    return of(LogReader.of(reader), path);
  }

  public static LogScan of(LogReader reader, Path path) throws IOException {
    return scan(null, reader, path);
  }

  private static LogScan scan(LogScan previous, LogReader reader, Path path) throws IOException {
    var entriesById = new HashMap<Integer, EntryInfo>();
    var ignoredIds = new HashSet<Integer>(); // declared, deliberately not indexed
    var entriesByName = new LinkedHashMap<String, EntryInfo>();
    var offsets = new HashMap<String, RecordOffsets>();
    double minTs = Double.MAX_VALUE;
    double maxTs = Double.NEGATIVE_INFINITY;
    int dataRecords = 0;
    int jumps = 0;
    long firstJump = -1;
    String damage = null;
    // The ordinary end of a robot's log: power went off inside the last record
    boolean cutInsideRecord = false;
    var recent = new ArrayDeque<Accepted>();

    // Walk records by their own bounds (DataLogAccess.recordEnd), not WPILib's iterator, whose
    // hasNext() skips a final record shorter than 16 bytes
    long headerEnd = previous == null ? DataLogAccess.firstRecordOffset(path) : previous.continuation.headerEnd;
    long pos = headerEnd;
    long lastRecord = -1;
    if (previous != null) {
      entriesById.putAll(previous.continuation.ids); ignoredIds.addAll(previous.continuation.ignored);
      entriesByName.putAll(previous.entries);
      previous.offsets.forEach((name, values) -> offsets.put(name, values.copy()));
      dataRecords = previous.dataRecords;
      if (dataRecords > 0) { minTs = previous.minTimestamp; maxTs = previous.maxTimestamp; }
      recent.addAll(previous.continuation.recent);
      pos = previous.resumePoint; lastRecord = previous.continuation.lastRecord;
    }
    long scannedFrom = pos;
    long size = DataLogAccess.size(reader);
    if (pos < 12 || pos > size) {
      damage = "the header's extra-header length runs past the end of the file";
      pos = size; // nothing to read
    }
    try {
      while (pos < size) {
        long next = DataLogAccess.recordEnd(reader, pos);
        if (next < 0) {
          damage = "the file ends inside a record at byte " + pos;
          cutInsideRecord = true;
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
              offsets.put(start.name, new RecordOffsets());
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
        } else if (record.isSetMetadata()) {
          var update = record.getSetMetadataData();
          var existing = entriesById.get(update.entry);
          if (existing != null) {
            var info = new EntryInfo(existing.id(), existing.name(), existing.type(), update.metadata);
            entriesByName.put(existing.name(), info);
            entriesById.replaceAll((id, entry) -> entry.name().equals(existing.name()) ? info : entry);
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
              // A clock cannot jump a day within one log. (A negative timestamp is not such a
              // sign: DataLogManager logs carry them on healthy robots.)
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
        lastRecord = pos; pos = next;
      }
    } catch (RuntimeException e) {
      // WPILib's reader throws NoSuchElement, BufferUnderflow, IndexOutOfBounds, and
      // IllegalArgument exceptions on a record cut off mid-write, and NegativeArraySize on a
      // Start record whose string length is garbage; any of them means the file stops being a
      // log here
      damage = "the record at byte " + pos + " cannot be read ("
          + e.getClass().getSimpleName() + ")";
    }

    int rolledBack = 0;
    if (damage != null && !cutInsideRecord) {
      // The last records before the damage are examined newest first; one whose time runs more
      // than MAX_BACKWARD_NEAR_DAMAGE_SEC backward or ahead of the log before it is dropped, and
      // the examination continues past it, so a garbage record does not shield an earlier one
      while (!recent.isEmpty()) {
        var last = recent.peekLast();
        boolean fits = last.maxBefore() == Double.NEGATIVE_INFINITY
            || (last.timestamp() >= last.maxBefore() - MAX_BACKWARD_NEAR_DAMAGE_SEC
                && last.timestamp() <= last.maxBefore() + MAX_BACKWARD_NEAR_DAMAGE_SEC);
        if (fits) break;
        recent.removeLast();
        offsets.get(last.name()).removeLast();
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
                + (rolledBack == 1 ? "" : "s") + " just before it, whose timestamps ran backward "
                + "or jumped ahead, " + (rolledBack == 1 ? "was" : "were") + " dropped" : "")
            + ".");
      }
      if (jumps > 0) {
        parts.add(jumpMessage(jumps, firstJump));
      }
      parts.add(String.format("Data from %.2f to %.2f s was recovered.", min, max));
      message = String.join(" ", parts);
      logger.warn("Log file '{}': {}", path.getFileName(), message);
    }

    boolean damaged = (damage != null && !cutInsideRecord) || rolledBack > 0 || jumps > 0;
    var continuation = damaged ? null : new Continuation(Map.copyOf(entriesById), java.util.Set.copyOf(ignoredIds),
        List.copyOf(recent), size, headerEnd, reader.fingerprint(0, headerEnd), lastRecord,
        lastRecord < 0 ? null : reader.fingerprint(lastRecord, pos));
    return new LogScan(Collections.unmodifiableMap(entriesByName), offsets, min, max,
        dataRecords, message != null, damaged, message, pos, scannedFrom, continuation);
  }

  static String jumpMessage(int jumps, long firstJump) {
    return jumps + " record" + (jumps == 1 ? " whose timestamp jumps" : "s whose timestamps jump")
        + " more than a day past the rest of the log " + (jumps == 1 ? "was" : "were")
        + " ignored (the first at byte " + firstJump + ").";
  }
}
