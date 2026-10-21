/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import static org.triplehelix.wpilogmcp.columns.ColumnFileFormat.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.IntToLongFunction;
import java.util.function.IntUnaryOperator;
import java.util.zip.CRC32C;
import org.triplehelix.wpilogmcp.columns.ColumnFileFormat.Kind;
import org.triplehelix.wpilogmcp.log.DecodeProblem;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.FieldPath;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.RecordSeries;
import org.triplehelix.wpilogmcp.read.SampleCursors;

/**
 * Writes a log's column file from the log's own decode, so a column holds exactly the values the
 * tools read today: the presentation value of each numeric entry, and of each numeric leaf of a
 * struct entry at the path the field path grammar spells for it ({@code .translation.x},
 * {@code [2].angle.value}, derived angles included).
 *
 * <p>A lazily read log is decoded one entry at a time, record by record, through the same decode
 * and the same stable sort into time order as the tools' reads, but not through the log's cache
 * of decoded values: a build that filled that cache with every entry ran a 4 GB heap out of
 * memory on a 2 GiB match log whose struct arrays decode to far more objects than the log has
 * bytes. Only the columns of the entry being built are held, in blocks, so a build's memory is
 * bounded by one entry's columns.
 *
 * <p>Some values no column holds exactly: a numeric leaf of a type no kind stores, an element at
 * index 64 or beyond of an array inside a struct, a field name the grammar cannot address, and a
 * leaf whose kind changes between records (a uint64 field decodes as an integer below 2^63 and
 * as a double above). Each leaves its entry incomplete: the entry's other columns are kept, and a
 * read that pools elements with {@code [*]}, which needs every element, takes the log's own
 * path. Some entries are not held at all, and every read of them takes the log's path: a struct
 * array with a record of more than 64 elements, which the scan's record sizes show before
 * anything is decoded; an entry with more than 2^24 samples across its columns; and an entry
 * whose values are not on the log's own clock.
 */
public final class ColumnFileWriter {
  private ColumnFileWriter() {}

  /** No section: a column that shares no record timestamps, or an entry without them. */
  static final long NONE = -1;
  /** Samples one entry's columns may hold: 200 MB of the build's heap at most. */
  static final int MAX_ENTRY_SAMPLES = 1 << 24;
  private static final int BLOCK = 1 << 12;

  /** What one build wrote. */
  public record Written(int entries, int columns, long samples, long bytes) {}

  /**
   * Writes the column file of {@code log} to {@code target}, which must not exist. The file is
   * deleted again if the build fails; the caller publishes it, under the name that makes it
   * visible, only once this returns.
   *
   * @param source the identity of the log's content, which a reader must name to open the file
   */
  public static Written write(LogData log, String source, Path target) throws IOException {
    return write(log, log instanceof LazyParsedLog lazy ? entry -> lazy.records(entry, false) : null, source, target);
  }

  /**
   * As {@link #write(LogData, String, Path)}, decoding each entry from {@code records}; with no
   * records, from the log's decoded values, for a log that holds them all already.
   */
  static Written write(LogData log, Function<String, RecordSeries> records, String source, Path target)
      throws IOException {
    return write(log, records, source, target, MAX_ENTRY_SAMPLES);
  }

  /** As above, with at most {@code maxEntrySamples} samples across one entry's columns. */
  static Written write(LogData log, Function<String, RecordSeries> records, String source, Path target,
      int maxEntrySamples) throws IOException {
    if (utf8(source).length > MAX_STRING_BYTES) throw new IllegalArgumentException("Source identity is too long");
    var directory = new Directory();
    directory.string(source);
    int countAt = directory.size();
    directory.u32(0);
    int entries = 0, columns = 0;
    long samples = 0;
    // Created before the cleanup below applies, so a file already there is never deleted.
    var channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    boolean written = false;
    try (var out = new Output(channel)) {
      out.header();
      for (var info : new TreeMap<>(log.entries()).values()) {
        if (entries == MAX_ENTRIES) break;
        if (!covered(info.type()) || !fits(info)) continue;
        var entry = records != null ? stream(log, info, records.apply(info.name()), maxEntrySamples)
            : decoded(log, info, maxEntrySamples);
        if (entry == null || entry.columns.isEmpty() || columns + entry.columns.size() > MAX_COLUMNS
            || entry.problem != null && utf8(entry.problem.message()).length > MAX_STRING_BYTES) continue;
        long mark = out.position();
        int directoryMark = directory.size();
        writeEntry(out, directory, entry);
        if (directory.size() > MAX_DIRECTORY_BYTES) {
          out.rewind(mark);
          directory.rewind(directoryMark);
          continue;
        }
        entries++;
        columns += entry.columns.size();
        for (var column : entry.columns) samples += column.size;
      }
      directory.patchU32(countAt, entries);
      out.align();
      long directoryOffset = out.position();
      int crc = out.directory(directory);
      out.footer(directoryOffset, directory.size(), crc);
      written = true;
      return new Written(entries, columns, samples, out.position());
    } finally {
      if (!written) Files.deleteIfExists(target);
    }
  }

  /** Numeric entries and struct entries; numeric arrays are not held yet. */
  static boolean covered(String type) {
    return switch (type) {
      case "double", "float", "int64", "boolean" -> true;
      default -> StructSchemas.structName(type) != null;
    };
  }

  private static boolean fits(EntryInfo info) {
    return utf8(info.name()).length <= MAX_STRING_BYTES && utf8(info.type()).length <= MAX_STRING_BYTES;
  }

  /**
   * One entry decoded record by record in file order, as the tools' decode reads it, then put in
   * time order by the same stable sort; null for an entry the file does not hold.
   */
  private static Collected stream(LogData log, EntryInfo info, RecordSeries series, int maxSamples) throws IOException {
    var struct = StructSchemas.structName(info.type());
    if (struct != null && StructSchemas.isArrayType(info.type())) {
      var schema = log.structSchemas().info(struct).filter(s -> s.valid() && s.size() > 0);
      if (schema.isPresent()) {
        long widest = (long) schema.get().size() * MAX_INDEX;
        for (int i = 0; i < series.size(); i++) if (series.payloadBytes(i) > widest) return null;
      }
    }
    var walk = new Walk(series.size(), maxSamples);
    var sink = new SampleCursors.Sink() {
      int failed;
      String first;

      @Override
      public void sample(long time, long offset, int ordinal, Object value) {
        walk.record(time, value);
      }

      @Override
      public void problem(ReadProblem problem) {
        if (failed++ == 0) first = problem.message();
      }
    };
    try {
      SampleCursors.consume(series, info.name(), sink);
    } catch (Walk.TooMany tooMany) {
      return null;
    }
    walk.sortByTime();
    var problem = sink.failed == 0 ? null : new DecodeProblem(sink.first, sink.failed, series.size());
    return collected(log, info, walk, problem);
  }

  /** One entry from the log's decoded values, which are in time order already. */
  private static Collected decoded(LogData log, EntryInfo info, int maxSamples) {
    var values = log.values().get(info.name());
    if (values == null) values = List.of();
    var walk = new Walk(values.size(), maxSamples);
    try {
      for (var value : values) {
        long time = value.timestampUs();
        if (Double.doubleToRawLongBits(time / 1_000_000.0) != Double.doubleToRawLongBits(value.timestamp())) return null;
        if (walk.decoded > 0 && time < walk.times[walk.decoded - 1]) return null;
        walk.record(time, value.value());
      }
    } catch (Walk.TooMany tooMany) {
      return null;
    }
    return collected(log, info, walk, log.decodeProblem(info.name()).orElse(null));
  }

  private static Collected collected(LogData log, EntryInfo info, Walk walk, DecodeProblem problem) {
    var columns = walk.columns.stream().filter(node -> !node.dropped).toList();
    if (StructSchemas.structName(info.type()) == null
        && (columns.size() != 1 || columns.get(0).size != walk.decoded)) return null;
    return new Collected(info.name(), info.type(), log.sampleCount(info.name()), walk.decoded, problem,
        walk.complete, walk.times, columns);
  }

  private record Collected(String name, String type, int records, int decoded, DecodeProblem problem,
      boolean complete, long[] recordTimes, List<Node> columns) {}

  /** The numeric leaves of an entry's records, gathered by path in the order they first appear. */
  private static final class Walk {
    /** Thrown once an entry's columns would hold more samples than a build may. */
    static final class TooMany extends RuntimeException {
      TooMany() {
        super(null, null, false, false);
      }
    }

    final int maxSamples;
    long[] times;
    int decoded;
    long samples;
    final Node root = new Node("", false, 0);
    final List<Node> columns = new ArrayList<>();
    boolean complete = true;

    Walk(int records, int maxSamples) {
      times = new long[records];
      this.maxSamples = maxSamples;
    }

    void record(long time, Object value) {
      if (decoded == times.length) times = Arrays.copyOf(times, Math.max(16, decoded * 2));
      times[decoded] = time;
      visit(root, value, decoded);
      decoded++;
    }

    /**
     * Puts the records in time order, equal times in file order, as the tools' decode sorts
     * them; each column's samples follow their records.
     */
    void sortByTime() {
      boolean ordered = true;
      for (int i = 1; i < decoded && ordered; i++) ordered = times[i] >= times[i - 1];
      if (ordered) return;
      var order = new Integer[decoded];
      for (int i = 0; i < decoded; i++) order[i] = i;
      var fileTimes = times;
      Arrays.sort(order, Comparator.comparingLong(i -> fileTimes[i]));
      var rank = new int[decoded];
      var sorted = new long[decoded];
      for (int k = 0; k < decoded; k++) {
        rank[order[k]] = k;
        sorted[k] = fileTimes[order[k]];
      }
      times = sorted;
      for (var column : columns) if (!column.dropped) column.reorder(rank);
    }

    private void visit(Node node, Object value, int ordinal) {
      if (value == null) return;
      if (value instanceof Map<?, ?> fields) {
        if (node.depth >= MAX_DEPTH) { complete = false; return; }
        for (var field : fields.entrySet()) {
          var child = field.getKey() instanceof String name && addressable(name) ? node.field(name) : null;
          if (child != null) visit(child, field.getValue(), ordinal);
          else complete = false;
        }
      } else if (value instanceof List<?> elements) {
        if (node.depth >= MAX_DEPTH) { complete = false; return; }
        if (elements.size() > MAX_INDEX) complete = false;
        for (int i = 0; i < Math.min(elements.size(), MAX_INDEX); i++) {
          var child = node.element(i);
          if (child != null) visit(child, elements.get(i), ordinal);
          else complete = false;
        }
      } else {
        leaf(node, value, ordinal);
      }
    }

    private void leaf(Node node, Object value, int ordinal) {
      var kind = kindOf(value);
      if (kind == null) {
        // A number of another type, or an array whose elements a path could still address.
        if (FieldPath.toNumber(value) != null || value.getClass().isArray() && !(value instanceof byte[])) complete = false;
        return;
      }
      if (node.dropped) return;
      if (node.kind == null) {
        if (columns.size() == MAX_ENTRY_COLUMNS) { node.drop(); complete = false; return; }
        node.kind = kind;
        columns.add(node);
      } else if (node.kind != kind) {
        samples -= node.size;
        node.drop();
        complete = false;
        return;
      }
      if (++samples > maxSamples) throw new TooMany();
      node.add(ordinal, bitsOf(value));
    }

    /** A name the grammar reads back as one field step. */
    private static boolean addressable(String name) {
      return !name.isEmpty() && name.indexOf('.') < 0 && name.indexOf('[') < 0;
    }
  }

  /**
   * One path in an entry's records, and its column once a numeric leaf is seen there. A column
   * keeps its records' positions only from the first record that lacks the leaf; until then a
   * sample's position is its index.
   */
  private static final class Node {
    final String path;
    final boolean indexed;
    final int depth;
    Map<String, Node> fields;
    List<Node> elements;
    Kind kind;
    boolean dropped;
    int size;
    LongBlocks bits = new LongBlocks();
    IntBlocks ordinals;

    Node(String path, boolean indexed, int depth) {
      this.path = path;
      this.indexed = indexed;
      this.depth = depth;
    }

    /** The node of a field, or null when its path would be longer than a column's may be. */
    Node field(String name) {
      if (path.length() + 1 + name.length() > MAX_PATH_CHARS) return null;
      if (fields == null) fields = new HashMap<>();
      return fields.computeIfAbsent(name, n -> new Node(path + "." + n, indexed, depth + 1));
    }

    Node element(int index) {
      if (path.length() + 12 > MAX_PATH_CHARS) return null;
      if (elements == null) elements = new ArrayList<>();
      while (elements.size() <= index) elements.add(new Node(path + "[" + elements.size() + "]", true, depth + 1));
      return elements.get(index);
    }

    void add(int ordinal, long value) {
      if (ordinals == null && ordinal != size) {
        ordinals = new IntBlocks();
        for (int i = 0; i < size; i++) ordinals.add(i);
      }
      if (ordinals != null) ordinals.add(ordinal);
      bits.add(value);
      size++;
    }

    int ordinal(int i) {
      return ordinals == null ? i : ordinals.get(i);
    }

    /** Moves each sample to its record's place in time order. */
    void reorder(int[] rank) {
      var keys = new long[size];
      for (int i = 0; i < size; i++) keys[i] = (long) rank[ordinal(i)] << 32 | i;
      Arrays.sort(keys);
      var oldBits = bits;
      bits = new LongBlocks();
      ordinals = null;
      int count = size;
      size = 0;
      for (int i = 0; i < count; i++) add((int) (keys[i] >>> 32), oldBits.get((int) keys[i]));
    }

    void drop() {
      dropped = true;
      bits = new LongBlocks();
      ordinals = null;
      size = 0;
    }
  }

  /** Values appended in blocks, so a column grows without copying what it holds. */
  private static final class LongBlocks {
    private final List<long[]> blocks = new ArrayList<>();
    private int size;

    void add(long value) {
      if (size % BLOCK == 0) blocks.add(new long[BLOCK]);
      blocks.get(size / BLOCK)[size++ % BLOCK] = value;
    }

    long get(int i) {
      return blocks.get(i / BLOCK)[i % BLOCK];
    }
  }

  private static final class IntBlocks {
    private final List<int[]> blocks = new ArrayList<>();
    private int size;

    void add(int value) {
      if (size % BLOCK == 0) blocks.add(new int[BLOCK]);
      blocks.get(size / BLOCK)[size++ % BLOCK] = value;
    }

    int get(int i) {
      return blocks.get(i / BLOCK)[i % BLOCK];
    }
  }

  /**
   * An entry's sections, then its directory record. A column with a value in every record shares
   * the entry's record timestamps; any other column has its own, and an array element's column
   * also keeps each sample's record position, which a pooled read merges by.
   */
  private static void writeEntry(Output out, Directory directory, Collected entry) throws IOException {
    boolean shared = entry.columns.stream().anyMatch(column -> column.size == entry.decoded);
    long timesOffset = NONE;
    int timesCrc = 0;
    if (shared) {
      timesOffset = out.position();
      timesCrc = out.longs(i -> entry.recordTimes[i], entry.decoded);
    }
    directory.string(entry.name);
    directory.string(entry.type);
    directory.u8((entry.complete ? 1 : 0) | (entry.problem != null ? 2 : 0));
    directory.u32(entry.records);
    directory.u32(entry.decoded);
    if (entry.problem != null) {
      directory.string(entry.problem.message());
      directory.u32(entry.problem.failedRecords());
      directory.u32(entry.problem.totalRecords());
    }
    directory.u64(timesOffset);
    directory.u32(timesCrc);
    directory.u32(entry.columns.size());
    for (var column : entry.columns) {
      boolean own = column.size < entry.decoded, positions = own && column.indexed;
      directory.string(column.path);
      directory.u8(column.kind.code);
      directory.u32(column.size);
      directory.u8((own ? 1 : 0) | (positions ? 2 : 0));
      if (own) {
        directory.u64(out.position());
        directory.u32(out.longs(i -> entry.recordTimes[column.ordinal(i)], column.size));
      }
      directory.u64(out.position());
      directory.u32(out.longs(column.bits::get, column.size));
      if (positions) {
        directory.u64(out.position());
        directory.u32(out.ints(column::ordinal, column.size));
      }
    }
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** The file, written through one buffer; each section's checksum covers exactly its bytes. */
  private static final class Output implements AutoCloseable {
    private final FileChannel channel;
    private final ByteBuffer buffer = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
    private final CRC32C crc = new CRC32C();
    private final byte[] header = new byte[HEADER_BYTES];
    private long written;

    Output(FileChannel channel) {
      this.channel = channel;
    }

    long position() {
      return written + buffer.position();
    }

    void header() {
      var bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
      bytes.putLong(MAGIC).putInt(FORMAT_VERSION).putInt(RECIPE_VERSION).putLong(0).putLong(0);
      buffer.put(header);
    }

    int longs(IntToLongFunction values, int count) throws IOException {
      begin();
      for (int i = 0; i < count; i++) {
        if (buffer.remaining() < 8) drain(true);
        buffer.putLong(values.applyAsLong(i));
      }
      return end();
    }

    int ints(IntUnaryOperator values, int count) throws IOException {
      begin();
      for (int i = 0; i < count; i++) {
        if (buffer.remaining() < 4) drain(true);
        buffer.putInt(values.applyAsInt(i));
      }
      int checksum = end();
      align();
      return checksum;
    }

    /** Zeros up to the next eight-byte boundary, outside any checksum. */
    void align() throws IOException {
      if (buffer.remaining() < 8) drain(false);
      while (position() % 8 != 0) buffer.put((byte) 0);
    }

    /** Writes the directory and returns the checksum of the header and directory together. */
    int directory(Directory directory) throws IOException {
      drain(false);
      var bytes = directory.bytes();
      crc.reset();
      crc.update(header);
      crc.update(bytes.duplicate());
      while (bytes.hasRemaining()) written += channel.write(bytes);
      return (int) crc.getValue();
    }

    void footer(long directoryOffset, int directoryLength, int checksum) throws IOException {
      buffer.putLong(directoryOffset).putLong(directoryLength).putInt(checksum).putInt(FOOTER_MAGIC);
      drain(false);
    }

    /** Discards everything after {@code position}, which an earlier {@link #position} returned. */
    void rewind(long position) throws IOException {
      drain(false);
      channel.truncate(position);
      channel.position(position);
      written = position;
    }

    private void begin() throws IOException {
      drain(false);
      crc.reset();
    }

    private int end() throws IOException {
      drain(true);
      return (int) crc.getValue();
    }

    private void drain(boolean checksum) throws IOException {
      buffer.flip();
      if (checksum) crc.update(buffer.array(), 0, buffer.limit());
      while (buffer.hasRemaining()) written += channel.write(buffer);
      buffer.clear();
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }

  /** The directory, built in memory and written after the sections it describes. */
  private static final class Directory {
    private ByteBuffer bytes = ByteBuffer.allocate(1 << 12).order(ByteOrder.LITTLE_ENDIAN);

    int size() {
      return bytes.position();
    }

    void u8(int value) {
      room(1).put((byte) value);
    }

    void u32(int value) {
      room(4).putInt(value);
    }

    void u64(long value) {
      room(8).putLong(value);
    }

    void string(String text) {
      var encoded = utf8(text);
      u32(encoded.length);
      room(encoded.length).put(encoded);
    }

    void patchU32(int at, int value) {
      bytes.putInt(at, value);
    }

    void rewind(int size) {
      bytes.position(size);
    }

    ByteBuffer bytes() {
      return bytes.duplicate().flip();
    }

    /** Grows past the limit by at most one entry's record, which the writer then rewinds. */
    private ByteBuffer room(int count) {
      if (bytes.remaining() < count) {
        int capacity = Math.toIntExact(Math.max((long) bytes.position() + count, 2L * bytes.capacity()));
        bytes = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN).put(bytes.flip());
      }
      return bytes;
    }
  }
}
