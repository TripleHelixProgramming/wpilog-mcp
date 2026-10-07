/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
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
 *
 * <p>A log is read in two passes, so that a 2 GB log needs neither a 2 GB array nor every value
 * in memory: {@code read(file, Set.of())} counts each entry's records, and a second call names
 * the few entries whose times and values to keep, and those whose raw payloads to keep (struct
 * arrays and their schemas, which this reader does not decode itself).
 */
final class IndependentLog {

  /** Complete records for replay, retaining byte offsets rather than another copy of the log. */
  static final class Records implements AutoCloseable {
    record Start(long id, String name, String type, String metadata) {}
    record Record(int offset, int end, long id, long timestampUs, int payloadOffset, int payloadSize) {}
    private final ByteBuffer data;
    final int first;
    String stopped;

    Records(Path file) throws IOException {
      data = bytes(file);
      if (data.limit() < 12 || unsigned(data, 0, 6) != 0x474F4C495057L || data.get(7) != 1) {
        close(); throw new NotALog(file, Files.size(file));
      }
      long beginning = 12 + unsigned(data, 8, 4);
      if (beginning > data.limit()) { close(); throw new IOException("Invalid WPILOG extra header: " + file); }
      first = (int) beginning;
    }

    Record at(int offset) {
      if (offset >= data.limit()) return null;
      int bits = data.get(offset) & 255;
      int ids = (bits & 3) + 1, sizes = ((bits >> 2) & 3) + 1, times = ((bits >> 4) & 7) + 1;
      long payload = (long) offset + 1 + ids + sizes + times;
      if (payload > data.limit()) { stopped = "incomplete_header"; return null; }
      long size = unsigned(data, offset + 1L + ids, sizes);
      if (payload + size > data.limit()) { stopped = "incomplete_payload"; return null; }
      return new Record(offset, (int) (payload + size), unsigned(data, offset + 1L, ids),
          unsigned(data, offset + 1L + ids + sizes, times), (int) payload, (int) size);
    }

    int control(Record record) { return record.id() == 0 && record.payloadSize() > 0 ? data.get(record.payloadOffset()) & 255 : -1; }
    long controlId(Record record) { return record.payloadSize() >= 5 ? unsigned(data, record.payloadOffset() + 1L, 4) : -1; }
    Start start(Record record) {
      var cursor = new long[] {record.payloadOffset() + 5L};
      String name = string(data, cursor, record.end()), type = string(data, cursor, record.end());
      String metadata = string(data, cursor, record.end());
      return name == null || type == null || metadata == null ? null : new Start(controlId(record), name, type, metadata);
    }
    String metadata(Record record) { return string(data, new long[] {record.payloadOffset() + 5L}, record.end()); }
    byte[] payload(Record record) {
      byte[] bytes = new byte[record.payloadSize()]; data.get(record.payloadOffset(), bytes); return bytes;
    }
    boolean samePayload(Record record, Records other, Record expected) {
      return record.payloadSize() == expected.payloadSize()
          && data.slice(record.payloadOffset(), record.payloadSize())
              .equals(other.data.slice(expected.payloadOffset(), expected.payloadSize()));
    }
    String extraHeader() {
      byte[] bytes = new byte[first - 12]; data.get(12, bytes); return new String(bytes, StandardCharsets.UTF_8);
    }
    @Override public void close() {
      if (!data.isDirect()) return;
      // The independent reader owns this mapping. Releasing it here lets Windows delete a
      // completed replay's scratch capture without waiting for an unrelated GC cycle.
      try {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        ((sun.misc.Unsafe) field.get(null)).invokeCleaner(data);
      } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
  }

  /** The file does not start with the WPILOG header. */
  static final class NotALog extends IOException {
    NotALog(Path file, long bytes) {
      super("not a WPILOG (" + bytes + " bytes): " + file);
    }
  }

  /** The file is longer than one buffer can address. */
  static final class TooLarge extends IOException {
    TooLarge(Path file, long bytes) {
      super("over " + Integer.MAX_VALUE + " bytes (" + bytes + "): " + file);
    }
  }

  /** A timestamp this far past everything before it is not a robot's clock. */
  static final double DAY_SEC = 86_400.0;
  /** How many records before a point of damage may belong to the damage. */
  static final int NEAR_DAMAGE = 16;
  /** A file up to this size is read into memory; a larger one is mapped. */
  static final long IN_MEMORY_BYTES = 64L << 20;

  /** One entry name's records. */
  static final class Series {
    final String name;
    final String type;
    /** The payload size of the type's value, or 0 when the type is not a numeric scalar. */
    final int width;
    /** Whether the times and values of its numeric records are kept. */
    final boolean kept;
    /** Whether the times and payloads of all its records are kept, whatever the type. */
    final boolean keptRaw;
    final java.util.List<Double> payloadTimes = new java.util.ArrayList<>();
    final java.util.List<byte[]> payloads = new java.util.ArrayList<>();
    /** Data records of the entry. */
    int records;
    /** Of those, the ones that decode as a number: a numeric type and a payload of its size. */
    int numeric;
    /** Of those, the finite ones. */
    int finite;
    double[] times = new double[0];
    double[] values = new double[0];
    /** How many times and values are kept: {@link #numeric} when kept, otherwise 0. */
    int n;

    Series(String name, String type, boolean kept, boolean keptRaw) {
      this.name = name;
      this.type = type;
      this.kept = kept;
      this.keptRaw = keptRaw;
      this.width = switch (type) {
        case "double", "int64" -> 8;
        case "float" -> 4;
        case "boolean" -> 1;
        default -> 0;
      };
    }

    private void add(double time, double value) {
      if (n == times.length) {
        int capacity = Math.max(64, n * 2);
        times = Arrays.copyOf(times, capacity);
        values = Arrays.copyOf(values, capacity);
      }
      times[n] = time;
      values[n++] = value;
    }

    /** The finite kept values, sorted. */
    double[] finiteSorted() {
      var out = new double[n];
      int k = 0;
      for (int i = 0; i < n; i++) if (Double.isFinite(values[i])) out[k++] = values[i];
      out = Arrays.copyOf(out, k);
      Arrays.sort(out);
      return out;
    }
  }

  final Map<String, Series> series = new LinkedHashMap<>();
  double minTime = Double.POSITIVE_INFINITY;
  double maxTime = Double.NEGATIVE_INFINITY;
  long dataRecords;
  /** Why reading stopped before the end of the file, or null when the whole file was read. */
  String stopped;
  /** Data records set aside because their time jumps more than a day past the log so far. */
  int dayJumps;
  /** The entries of the last data records read, oldest first (at most {@link #NEAR_DAMAGE}). */
  final ArrayDeque<String> lastRecords = new ArrayDeque<>();

  /** How many of the last records before the damage belong to the entry. */
  int nearDamage(String name) {
    if (stopped == null) return 0;
    int k = 0;
    for (var last : lastRecords) if (last.equals(name)) k++;
    return k;
  }

  private static long unsigned(ByteBuffer b, long offset, int count) {
    long v = 0;
    for (int i = 0; i < count; i++) v |= (long) (b.get((int) (offset + i)) & 0xFF) << (8 * i);
    return v;
  }

  /** A length-prefixed string of a control record, or null when it runs past the payload. */
  private static String string(ByteBuffer b, long[] cursor, long end) {
    if (cursor[0] + 4 > end) return null;
    long count = unsigned(b, cursor[0], 4);
    cursor[0] += 4;
    if (cursor[0] + count > end) return null;
    var raw = new byte[(int) count];
    b.get((int) cursor[0], raw);
    cursor[0] += count;
    return new String(raw, StandardCharsets.UTF_8);
  }

  private static ByteBuffer bytes(Path file) throws IOException {
    long size = Files.size(file);
    if (size > Integer.MAX_VALUE) throw new TooLarge(file, size);
    if (size <= IN_MEMORY_BYTES) return ByteBuffer.wrap(Files.readAllBytes(file));
    try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
      return channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
    }
  }

  /**
   * Reads a log, counting every entry's records and keeping the times and values of the numeric
   * entries named in {@code keep}.
   */
  static IndependentLog read(Path file, Set<String> keep) throws IOException {
    return read(file, keep, Set.of());
  }

  /** As {@link #read(Path, Set)}, also keeping the raw payloads of the entries in {@code keepRaw}. */
  static IndependentLog read(Path file, Set<String> keep, Set<String> keepRaw)
      throws IOException {
    var b = bytes(file);
    long length = b.limit();
    if (length < 12 || unsigned(b, 0, 6) != 0x474F4C495057L) { // "WPILOG", little-endian
      throw new NotALog(file, length);
    }
    var log = new IndependentLog();
    var active = new HashMap<Long, Series>();
    long pos = 12 + unsigned(b, 8, 4);
    if (pos > length) {
      log.stopped = "the extra header runs past the end of the file";
      return log;
    }
    while (pos < length) {
      int bits = b.get((int) pos) & 0xFF;
      int idLength = (bits & 0x3) + 1;
      int sizeLength = ((bits >> 2) & 0x3) + 1;
      int timeLength = ((bits >> 4) & 0x7) + 1;
      long data = pos + 1 + idLength + sizeLength + timeLength;
      if (data > length) {
        log.stopped = "the file ends inside a record header at byte " + pos;
        break;
      }
      long id = unsigned(b, pos + 1, idLength);
      long size = unsigned(b, pos + 1 + idLength, sizeLength);
      long micros = unsigned(b, pos + 1 + idLength + sizeLength, timeLength);
      long end = data + size;
      if (end > length) {
        log.stopped = "the file ends inside a record at byte " + pos;
        break;
      }
      if (id == 0) {
        long kind = size > 0 ? unsigned(b, data, 1) : -1;
        if (kind == 0 && size >= 5) {
          long entry = unsigned(b, data + 1, 4);
          var cursor = new long[] {data + 5};
          var name = string(b, cursor, end);
          var type = string(b, cursor, end);
          if (name == null || type == null) {
            log.stopped = "a Start record at byte " + pos + " is malformed";
            break;
          }
          var existing = log.series.get(name);
          if (existing == null) {
            existing = new Series(name, type, keep.contains(name), keepRaw.contains(name));
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
          if (s.keptRaw) {
            var raw = new byte[(int) size];
            b.get((int) data, raw);
            s.payloadTimes.add(time);
            s.payloads.add(raw);
          }
          log.minTime = Math.min(log.minTime, time);
          log.maxTime = Math.max(log.maxTime, time);
          if (s.width > 0 && size == s.width) {
            double value = switch (s.type) {
              case "double" -> Double.longBitsToDouble(unsigned(b, data, 8));
              case "float" -> Float.intBitsToFloat((int) unsigned(b, data, 4));
              case "int64" -> (double) unsigned(b, data, 8);
              default -> unsigned(b, data, 1) != 0 ? 1.0 : 0.0;
            };
            s.numeric++;
            if (Double.isFinite(value)) s.finite++;
            if (s.kept) s.add(time, value);
          }
        }
      }
      pos = end;
    }
    return log;
  }
}
