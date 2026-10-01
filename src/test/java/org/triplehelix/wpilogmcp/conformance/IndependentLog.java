/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A WPILOG read straight from its bytes, by the published format, for differential tests. It
 * shares no code with the server and does not use WPILib's reader, so an agreement between the
 * two is evidence and a disagreement is a finding.
 *
 * <p>Format: a 12-byte header ("WPILOG", a 16-bit version, the 32-bit length of an extra header
 * string), the extra header, then records. A record starts with a bit field giving the byte
 * widths of its entry id (1-4), payload size (1-4), and timestamp (1-8), all little-endian.
 * Entry id 0 is a control record: Start (0) declares an entry's id, name, type, and metadata;
 * Finish (1) retires an id; SetMetadata (2) changes metadata. Every other record is data.
 */
final class IndependentLog {

  static final Set<String> NUMERIC = Set.of("double", "float", "int64", "boolean");

  /** One entry name's records: all are counted; numeric scalars also keep time and value. */
  static final class Series {
    final String name;
    final String type;
    int records;
    int undecodable;
    double[] times = new double[64];
    double[] values = new double[64];
    int n;

    Series(String name, String type) {
      this.name = name;
      this.type = type;
    }

    void add(double time, double value) {
      if (n == times.length) {
        times = Arrays.copyOf(times, n * 2);
        values = Arrays.copyOf(values, n * 2);
      }
      times[n] = time;
      values[n++] = value;
    }

    /** The finite values, sorted. */
    double[] finiteSorted() {
      var out = new double[n];
      int k = 0;
      for (int i = 0; i < n; i++) if (Double.isFinite(values[i])) out[k++] = values[i];
      out = Arrays.copyOf(out, k);
      Arrays.sort(out);
      return out;
    }
  }

  /** A timestamp this far past everything before it is not a robot's clock: the record is set aside. */
  static final double DAY_SEC = 86_400.0;
  /** How many records before a point of damage may belong to the damage. */
  static final int NEAR_DAMAGE = 16;

  final Map<String, Series> series = new LinkedHashMap<>();
  double minTime = Double.POSITIVE_INFINITY;
  double maxTime = Double.NEGATIVE_INFINITY;
  long dataRecords;
  /** Why reading stopped before the end of the file, or null when the whole file was read. */
  String stopped;
  /** Data records set aside because their time jumps more than a day past the log so far. */
  int dayJumps;
  /** The entries of the last data records read, oldest first (at most {@link #NEAR_DAMAGE}). */
  final java.util.ArrayDeque<String> lastRecords = new java.util.ArrayDeque<>();

  /** How many of the last records before the damage belong to the entry. */
  int nearDamage(String name) {
    if (stopped == null) return 0;
    int k = 0;
    for (var n : lastRecords) if (n.equals(name)) k++;
    return k;
  }

  private static long unsigned(byte[] b, int offset, int length) {
    long v = 0;
    for (int i = 0; i < length; i++) v |= (long) (b[offset + i] & 0xFF) << (8 * i);
    return v;
  }

  /** A length-prefixed string of a control record, or null when it runs past the payload. */
  private static String string(byte[] b, int[] cursor, int end) {
    if (cursor[0] + 4 > end) return null;
    long length = unsigned(b, cursor[0], 4);
    cursor[0] += 4;
    if (length < 0 || cursor[0] + length > end) return null;
    var s = new String(b, cursor[0], (int) length, StandardCharsets.UTF_8);
    cursor[0] += (int) length;
    return s;
  }

  static IndependentLog read(Path file) throws IOException {
    var b = Files.readAllBytes(file);
    if (b.length < 12 || !new String(b, 0, 6, StandardCharsets.US_ASCII).equals("WPILOG")) {
      throw new IOException("not a WPILOG: " + file);
    }
    var log = new IndependentLog();
    var active = new HashMap<Long, Series>();
    long first = 12 + unsigned(b, 8, 4);
    if (first > b.length) {
      log.stopped = "the extra header runs past the end of the file";
      return log;
    }
    int pos = (int) first;
    while (pos < b.length) {
      int bits = b[pos] & 0xFF;
      int idLength = (bits & 0x3) + 1;
      int sizeLength = ((bits >> 2) & 0x3) + 1;
      int timeLength = ((bits >> 4) & 0x7) + 1;
      int header = 1 + idLength + sizeLength + timeLength;
      if (pos + header > b.length) {
        log.stopped = "the file ends inside a record header at byte " + pos;
        break;
      }
      long id = unsigned(b, pos + 1, idLength);
      long size = unsigned(b, pos + 1 + idLength, sizeLength);
      long micros = unsigned(b, pos + 1 + idLength + sizeLength, timeLength);
      int data = pos + header;
      if (data + size > b.length) {
        log.stopped = "the file ends inside a record at byte " + pos;
        break;
      }
      int end = data + (int) size;
      if (id == 0) {
        int kind = size > 0 ? b[data] & 0xFF : -1;
        if (kind == 0 && size >= 5) {
          long entry = unsigned(b, data + 1, 4);
          var cursor = new int[] {data + 5};
          var name = string(b, cursor, end);
          var type = string(b, cursor, end);
          if (name == null || type == null) {
            log.stopped = "a Start record at byte " + pos + " is malformed";
            break;
          }
          var existing = log.series.get(name);
          if (existing == null) {
            existing = new Series(name, type);
            log.series.put(name, existing);
            active.put(entry, existing);
          } else {
            // The same name declared again with another type: its records are not this entry's
            active.put(entry, existing.type.equals(type) ? existing : null);
          }
        } else if (kind == 1 && size >= 5) {
          active.remove(unsigned(b, data + 1, 4));
        }
      } else {
        if (!active.containsKey(id)) {
          log.stopped = "a data record at byte " + pos + " is for an entry id never declared";
          break;
        }
        var s = active.get(id);
        if (s != null) {
          double time = micros / 1e6;
          if (log.dataRecords > 0 && time > log.maxTime + DAY_SEC) {
            log.dayJumps++;
            pos = end;
            continue;
          }
          log.lastRecords.addLast(s.name);
          if (log.lastRecords.size() > NEAR_DAMAGE) log.lastRecords.removeFirst();
          s.records++;
          log.dataRecords++;
          log.minTime = Math.min(log.minTime, time);
          log.maxTime = Math.max(log.maxTime, time);
          switch (s.type) {
            case "double" -> {
              if (size == 8) s.add(time, Double.longBitsToDouble(unsigned(b, data, 8)));
              else s.undecodable++;
            }
            case "float" -> {
              if (size == 4) s.add(time, Float.intBitsToFloat((int) unsigned(b, data, 4)));
              else s.undecodable++;
            }
            case "int64" -> {
              if (size == 8) s.add(time, (double) unsigned(b, data, 8));
              else s.undecodable++;
            }
            case "boolean" -> {
              if (size == 1) s.add(time, b[data] != 0 ? 1.0 : 0.0);
              else s.undecodable++;
            }
            default -> { }
          }
        }
      }
      pos = end;
    }
    return log;
  }
}
