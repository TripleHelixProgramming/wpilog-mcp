/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogScan;
import org.triplehelix.wpilogmcp.log.MappedLogBytes;
import org.triplehelix.wpilogmcp.log.RecordOffsets;

/**
 * Optional scan indexes retain declarations and exact offset/order facts, never telemetry.
 * Bounded streaming input and structural validation precede publication; corruption is a cache
 * miss, not a reason a valid source log becomes unreadable. The cache owner supplies fresh
 * source-content proof and owns atomic publication of the temporary file written here.
 *
 * <p>The fixed big-endian format is magic, format/recipe versions, source identity/size/SHA,
 * the content's CRC32C and the file's attributes when it was proved, scan facts,
 * declaration-ordered entries with narrow/wide positions, then CRC32 and exact EOF.
 * CRC detects corruption, not deliberate forgery by a local actor who can replace cache files.
 */
public class ScanIndexSerializer {
  public static final int FORMAT_VERSION = 2;
  public static final int RECIPE_VERSION = 1;
  public static final int MAX_CACHE_FILE_BYTES = 64 * 1024 * 1024;
  public static final long MAX_DECODED_BYTES = 64L * 1024 * 1024;
  public static final int MAX_ENTRIES = 200_000;
  public static final int MAX_STRING_BYTES = 1024 * 1024;
  private static final byte[] MAGIC = {'W','P','I','S','C','A','N',0};
  private static final long BASE_BYTES = 512;
  private static final long ENTRY_BYTES = 512;

  /** Identity nominates a key; sha256 is the caller's current whole-content proof. */
  public record Source(String identity, long sizeBytes, String sha256) {}

  /**
   * What an index was proved under: the content's CRC32C, the cheap check a later open falls
   * back to, and the file's modification time, identity and creation time then. A later open
   * that finds the same attributes, where the file system can vouch for them, reuses the index
   * without reading the file.
   */
  public record Recorded(long crc32c, Instant modified, String fileKey, Instant created) {}

  /** An index as stored, before its caller decides how far the recorded attributes vouch. */
  public record Held(Source source, Recorded recorded, LogScan scan) {}

  public record Header(int formatVersion, int recipeVersion, Source source, Recorded recorded, long encodedBytes) {}
  private record Decoded(Header header, LogScan scan) {}

  /** A failed optional cache input always leaves the ordinary scan available. */
  public LogScan read(Path path, Source expected) {
    if (expected == null) return null;
    try {
      return decode(path, expected::equals, true).scan();
    } catch (IOException | RuntimeException invalid) {
      return null;
    }
  }

  /** The index stored for a file's identity and size, whatever content it was proved for. */
  public Held hold(Path path, String identity, long sizeBytes) {
    try {
      var decoded = decode(path,
          source -> source.identity().equals(identity) && source.sizeBytes() == sizeBytes, true);
      return new Held(decoded.header().source(), decoded.header().recorded(), decoded.scan());
    } catch (IOException | RuntimeException invalid) {
      return null;
    }
  }

  /** Cleanup verifies the whole representation without allocating its offset arrays. */
  public Header inspect(Path path) {
    try {
      return decode(path, source -> true, false).header();
    } catch (IOException | RuntimeException invalid) {
      return null;
    }
  }

  /**
   * Declines an unsupported optional representation before creating a file. The supplied path
   * must be a uniquely owned temporary: CREATE_NEW prevents overwriting another writer's work.
   * The caller serializes a stable, unpublished scan and handles failure/atomic final rename.
   */
  public boolean write(Path path, Source source, Recorded recorded, LogScan scan) throws IOException {
    long encoded;
    try {
      encoded = preflight(source, recorded, scan);
    } catch (IOException | RuntimeException unsupported) {
      return false;
    }
    var crc = new CRC32();
    try (var raw = new BufferedOutputStream(Files.newOutputStream(path,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
      var bounded = new BoundedOutput(raw, MAX_CACHE_FILE_BYTES - 4L);
      var out = new DataOutputStream(new CheckedOutputStream(bounded, crc));
      out.write(MAGIC); out.writeInt(FORMAT_VERSION); out.writeInt(RECIPE_VERSION);
      writeString(out, source.identity()); out.writeLong(source.sizeBytes());
      out.write(HexFormat.of().parseHex(source.sha256()));
      out.writeInt((int) recorded.crc32c());
      out.writeLong(recorded.modified().getEpochSecond()); out.writeInt(recorded.modified().getNano());
      writeString(out, recorded.fileKey());
      out.writeByte(recorded.created() == null ? 0 : 1);
      if (recorded.created() != null) {
        out.writeLong(recorded.created().getEpochSecond()); out.writeInt(recorded.created().getNano());
      }
      out.writeDouble(scan.minTimestamp()); out.writeDouble(scan.maxTimestamp());
      out.writeInt(scan.dataRecords());
      out.writeByte((scan.truncated() ? 1 : 0) | (scan.damaged() ? 2 : 0));
      writeString(out, scan.truncationMessage());
      out.writeLong(scan.resumePoint()); out.writeLong(scan.scannedFrom());
      out.writeInt(scan.entries().size());
      for (var item : scan.entries().entrySet()) {
        var info = item.getValue(); var offsets = scan.offsets().get(item.getKey());
        out.writeInt(info.id()); writeString(out, info.name());
        writeString(out, info.type()); writeString(out, info.metadata());
        out.writeInt(offsets.size()); out.writeByte(offsets.wide() ? 1 : 0);
        var order = offsets.orderState();
        out.writeInt(order.firstUnordered()); out.writeLong(order.lastTimestampUs());
        out.writeByte(order.lastTimestampKnown() ? 1 : 0);
        for (int i = 0; i < offsets.size(); i++) {
          if (offsets.wide()) out.writeLong(offsets.get(i));
          else out.writeInt(Math.toIntExact(offsets.get(i)));
        }
      }
      out.flush();
      if (bounded.written != encoded - 4) throw new IOException("Scan changed during cache encoding");
      new DataOutputStream(raw).writeInt((int) crc.getValue());
    }
    return true;
  }

  private Decoded decode(Path path, java.util.function.Predicate<Source> accepted, boolean materialize)
      throws IOException {
    long size = Files.size(path);
    if (size < 20 || size > MAX_CACHE_FILE_BYTES) throw invalid("encoded size");
    try (var raw = new BufferedInputStream(openInput(path))) {
      var limited = new BoundedInput(raw, size);
      var crc = new CRC32();
      var in = new DataInputStream(new CheckedInputStream(limited, crc));
      byte[] magic = new byte[MAGIC.length]; in.readFully(magic);
      if (!Arrays.equals(magic, MAGIC)) throw invalid("magic");
      int format = in.readInt(), recipe = in.readInt();
      if (format != FORMAT_VERSION || recipe != RECIPE_VERSION) throw invalid("version");
      var budget = new Budget();
      String identity = readString(in, limited, budget, false);
      long sourceSize = in.readLong();
      byte[] sha = new byte[32]; in.readFully(sha);
      var source = new Source(identity, sourceSize, HexFormat.of().formatHex(sha));
      validateSource(source);
      if (!accepted.test(source)) throw invalid("source proof");
      long checksum32c = Integer.toUnsignedLong(in.readInt());
      var modified = fileTime(in.readLong(), in.readInt());
      String fileKey = readString(in, limited, budget, true);
      int createdTag = in.readUnsignedByte();
      if (createdTag > 1) throw invalid("creation tag");
      var recorded = new Recorded(checksum32c, modified, fileKey,
          createdTag == 1 ? fileTime(in.readLong(), in.readInt()) : null);
      double min = in.readDouble(), max = in.readDouble();
      int records = in.readInt(), flags = in.readUnsignedByte();
      if (flags > 3) throw invalid("flags");
      boolean truncated = (flags & 1) != 0, damaged = (flags & 2) != 0;
      String message = readString(in, limited, budget, true);
      long resume = in.readLong(), scanned = in.readLong();
      validateScanFacts(sourceSize, min, max, records, truncated, damaged, message, resume, scanned);
      int count = in.readInt();
      if (count < 0 || count > MAX_ENTRIES || count > limited.bodyRemaining() / 35)
        throw invalid("entry count");
      var entries = materialize ? new LinkedHashMap<String, EntryInfo>() : null;
      var positions = materialize ? new LinkedHashMap<String, RecordOffsets>() : null;
      var names = new HashSet<String>();
      long total = 0;
      for (int i = 0; i < count; i++) {
        budget.charge(ENTRY_BYTES);
        int id = in.readInt();
        String name = readString(in, limited, budget, false);
        String type = readString(in, limited, budget, false);
        String metadata = readString(in, limited, budget, false);
        if (name.isEmpty() || !names.add(name)) throw invalid("duplicate or empty entry name");
        int offsets = in.readInt();
        boolean wide = booleanByte(in);
        var order = new RecordOffsets.OrderState(in.readInt(), in.readLong(), booleanByte(in));
        RecordOffsets.validateOrderState(offsets, order);
        int width = wide ? 8 : 4;
        if (offsets > limited.bodyRemaining() / width) throw invalid("offset count");
        budget.charge((long) offsets * width);
        total += offsets;
        if (total > records) throw invalid("aggregate offset count");
        RecordOffsets restored = null;
        if (materialize) {
          restored = RecordOffsets.readPersisted(offsets, wide, order, sourceSize,
              () -> wide ? in.readLong() : in.readInt());
        } else {
          long preceding = -1;
          for (int j = 0; j < offsets; j++) {
            long offset = wide ? in.readLong() : in.readInt();
            validatePosition(offset, preceding, sourceSize, wide); preceding = offset;
          }
        }
        if (materialize) {
          entries.put(name, new EntryInfo(id, name, type, metadata));
          positions.put(name, restored);
        }
      }
      if (total != records || limited.bodyRemaining() != 0) throw invalid("record count or trailing payload");
      int checksum = new DataInputStream(limited).readInt();
      if ((int) crc.getValue() != checksum || limited.remaining != 0 || raw.read() != -1)
        throw invalid("checksum or changed length");
      var header = new Header(format, recipe, source, recorded, size);
      var scan = materialize ? new LogScan(Collections.unmodifiableMap(entries),
          Collections.unmodifiableMap(positions), min, max, records, truncated, damaged, message,
          resume, scanned, null) : null;
      return new Decoded(header, scan);
    }
  }

  /** Boundary seam: oversized inputs are refused before opening any content stream. */
  InputStream openInput(Path path) throws IOException { return Files.newInputStream(path); }

  private static boolean booleanByte(DataInputStream in) throws IOException {
    int value = in.readUnsignedByte();
    if (value > 1) throw invalid("boolean tag");
    return value == 1;
  }

  private static long preflight(Source source, Recorded recorded, LogScan scan) throws IOException {
    validateSource(source);
    if (recorded == null || recorded.modified() == null || recorded.crc32c() < 0 || recorded.crc32c() > 0xFFFF_FFFFL)
      throw invalid("recorded proof");
    validateScanFacts(source.sizeBytes(), scan.minTimestamp(), scan.maxTimestamp(), scan.dataRecords(),
        scan.truncated(), scan.damaged(), scan.truncationMessage(), scan.resumePoint(), scan.scannedFrom());
    if (scan.entries().size() > MAX_ENTRIES || !scan.entries().keySet().equals(scan.offsets().keySet()))
      throw invalid("entry maps");
    var budget = new Budget();
    long bytes = 16 + stringSize(source.identity(), budget, false) + 8 + 32
        + 4 + 12 + stringSize(recorded.fileKey(), budget, true) + 1 + (recorded.created() == null ? 0 : 12)
        + 16 + 4 + 1 + stringSize(scan.truncationMessage(), budget, true) + 16 + 4 + 4;
    long total = 0;
    for (var item : scan.entries().entrySet()) {
      budget.charge(ENTRY_BYTES);
      var info = item.getValue(); var offsets = scan.offsets().get(item.getKey());
      if (!item.getKey().equals(info.name()) || info.name().isEmpty()) throw invalid("entry name");
      bytes += 4 + stringSize(info.name(), budget, false) + stringSize(info.type(), budget, false)
          + stringSize(info.metadata(), budget, false) + 4 + 1 + 4 + 8 + 1;
      RecordOffsets.validateOrderState(offsets.size(), offsets.orderState());
      long positions = (long) offsets.size() * (offsets.wide() ? 8 : 4);
      budget.charge(positions); bytes += positions;
      if (bytes > MAX_CACHE_FILE_BYTES) throw invalid("encoded size");
      total += offsets.size();
      if (total > scan.dataRecords()) throw invalid("aggregate offset count");
      long preceding = -1;
      for (int i = 0; i < offsets.size(); i++) {
        long position = offsets.get(i);
        validatePosition(position, preceding, source.sizeBytes(), offsets.wide()); preceding = position;
      }
    }
    if (total != scan.dataRecords() || bytes > MAX_CACHE_FILE_BYTES) throw invalid("record count or encoded size");
    return bytes;
  }

  private static void validateSource(Source source) throws IOException {
    if (source == null || source.identity() == null || source.sha256() == null
        || !source.identity().matches("(?:sha256|file):[0-9a-f]{64}")
        || !source.sha256().matches("[0-9a-f]{64}")
        || source.identity().startsWith("sha256:") && !source.identity().substring(7).equals(source.sha256())
        || source.sizeBytes() < 12 || source.sizeBytes() > MappedLogBytes.MAX_FILE_BYTES)
      throw invalid("source identity");
  }

  private static Instant fileTime(long seconds, int nanos) throws IOException {
    if (nanos < 0 || nanos > 999_999_999) throw invalid("file time");
    try {
      return Instant.ofEpochSecond(seconds, nanos);
    } catch (java.time.DateTimeException outOfRange) {
      throw invalid("file time");
    }
  }

  private static void validateScanFacts(long size, double min, double max, int records,
      boolean truncated, boolean damaged, String message, long resume, long scanned) throws IOException {
    if (!Double.isFinite(min) || !Double.isFinite(max) || min > max || records < 0
        || records == 0 && (min != 0 || max != 0) || records > size / 4
        || truncated != (message != null) || damaged && !truncated
        || resume < 12 || resume > size || scanned < 12 || scanned > resume)
      throw invalid("scan facts");
  }

  private static void validatePosition(long position, long preceding, long size, boolean wide)
      throws IOException {
    if (position < 12 || position > size - 4 || position <= preceding
        || !wide && position > Integer.MAX_VALUE) throw invalid("record position");
  }

  private static long stringSize(String value, Budget budget, boolean nullable) throws IOException {
    if (value == null) {
      if (!nullable) throw invalid("null string");
      return 4;
    }
    int bytes = utf8Size(value);
    budget.charge(32L + 2L * bytes);
    return 4L + bytes;
  }

  private static int utf8Size(String value) throws IOException {
    if (value.length() > MAX_STRING_BYTES) throw invalid("string length");
    int bytes = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x80) bytes++;
      else if (c < 0x800) bytes += 2;
      else if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid("string encoding");
        bytes += 4;
      } else if (Character.isLowSurrogate(c)) throw invalid("string encoding");
      else bytes += 3;
      if (bytes > MAX_STRING_BYTES) throw invalid("string length");
    }
    return bytes;
  }

  private static void writeString(DataOutputStream out, String value) throws IOException {
    if (value == null) { out.writeInt(-1); return; }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length); out.write(bytes);
  }

  private static String readString(DataInputStream in, BoundedInput limited, Budget budget,
      boolean nullable) throws IOException {
    int count = in.readInt();
    if (nullable && count == -1) return null;
    if (count < 0 || count > MAX_STRING_BYTES || count > limited.bodyRemaining())
      throw invalid("string length");
    budget.charge(32L + 2L * count);
    byte[] bytes = new byte[count]; in.readFully(bytes);
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
  }

  /** Conservative graph accounting, distinct from measured JVM heap or source-log limits. */
  private static final class Budget {
    long used = BASE_BYTES;
    void charge(long bytes) throws IOException {
      if (bytes < 0 || bytes > MAX_DECODED_BYTES - used) throw invalid("decoded budget");
      used += bytes;
    }
  }

  private static final class BoundedInput extends InputStream {
    final InputStream source;
    long remaining;
    BoundedInput(InputStream source, long remaining) { this.source = source; this.remaining = remaining; }
    long bodyRemaining() { return Math.max(0, remaining - 4); }
    @Override public int read() throws IOException {
      if (remaining == 0) return -1;
      int value = source.read(); if (value >= 0) remaining--; return value;
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
      if (length == 0) return 0;
      if (remaining == 0) return -1;
      int count = source.read(bytes, offset, (int) Math.min(length, remaining));
      if (count > 0) remaining -= count;
      return count;
    }
  }

  private static final class BoundedOutput extends java.io.OutputStream {
    final java.io.OutputStream target;
    final long maximum;
    long written;
    BoundedOutput(java.io.OutputStream target, long maximum) { this.target = target; this.maximum = maximum; }
    @Override public void write(int value) throws IOException {
      if (written == maximum) throw invalid("encoded size");
      target.write(value); written++;
    }
    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
      if (length > maximum - written) throw invalid("encoded size");
      target.write(bytes, offset, length); written += length;
    }
    @Override public void flush() throws IOException { target.flush(); }
  }

  private static IOException invalid(String field) { return new IOException("Invalid scan cache " + field); }
}
