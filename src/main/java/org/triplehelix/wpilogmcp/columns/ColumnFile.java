/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.columns;

import static org.triplehelix.wpilogmcp.columns.ColumnFileFormat.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32C;
import org.triplehelix.wpilogmcp.columns.ColumnFileFormat.Kind;
import org.triplehelix.wpilogmcp.log.DecodeProblem;
import org.triplehelix.wpilogmcp.log.LogBytes;
import org.triplehelix.wpilogmcp.log.MappedLogBytes;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.FieldPath;
import org.triplehelix.wpilogmcp.read.NumericSamples;
import org.triplehelix.wpilogmcp.read.RecordSeries;
import org.triplehelix.wpilogmcp.read.SampleCursors;
import org.triplehelix.wpilogmcp.read.SecondsWindow;

/**
 * A log's column file, mapped as the log itself is, so its columns live in the operating
 * system's file cache and no sample becomes an object on the heap.
 *
 * <p>A selection holds a path only when the file holds it exactly, and is empty otherwise, so
 * that the caller reads the log instead: a path with no column, a pooled path ({@code [*]}) on an
 * incomplete entry, and every path once a section has failed its checksum. A selection checks
 * every section it will read before it is returned, so a read takes all its values from the file
 * or all from the log, never some of each.
 *
 * <p>One owner closes the file, after every read of it has finished, as with a mapped log: a
 * mapping released while another thread reads it would crash the process.
 */
public final class ColumnFile implements AutoCloseable {
  private static final int CHUNK = 8192;

  private final MappedLogBytes bytes;
  private final String source;
  private final Map<String, Entry> entries;
  private volatile String damage;

  private ColumnFile(MappedLogBytes bytes, String source, Map<String, Entry> entries) {
    this.bytes = bytes;
    this.source = source;
    this.entries = entries;
  }

  /**
   * Opens a column file and checks its header, footer, directory and identity.
   *
   * @param expectedSource the identity of the log the caller is reading; a file built from any
   *     other content is refused
   * @throws IOException for a file that is not a complete column file of this version and recipe
   *     for that source
   */
  public static ColumnFile open(Path path, String expectedSource) throws IOException {
    var bytes = new MappedLogBytes(path, MappedLogBytes.windowBytes());
    try {
      var directory = new Parser(bytes).parse(expectedSource);
      return new ColumnFile(bytes, expectedSource, directory);
    } catch (IOException | RuntimeException failure) {
      try {
        bytes.close();
      } catch (IOException release) {
        failure.addSuppressed(release);
      }
      if (failure instanceof IOException io) throw io;
      throw new IOException("Malformed column file: " + failure, failure);
    }
  }

  public String source() {
    return source;
  }

  /** The entry, when the file holds it. */
  public Optional<Entry> entry(String name) {
    return Optional.ofNullable(entries.get(name));
  }

  /** Why the file's columns are no longer read, when a section failed its checksum. */
  public Optional<String> damage() {
    return Optional.ofNullable(damage);
  }

  /**
   * The samples of {@code path} in {@code entry}, in the order the tools read them, when the file
   * holds them exactly; empty otherwise, and the caller reads the log.
   */
  public Optional<Selection> select(String entry, FieldPath path) {
    if (damage != null) return Optional.empty();
    var held = entries.get(entry);
    if (held == null) return Optional.empty();
    List<Column> columns;
    if (!path.hasWildcard()) {
      var column = held.columns.get(path.toString());
      if (column == null) return Optional.empty();
      columns = List.of(column);
    } else {
      if (!held.complete) return Optional.empty();
      columns = pooled(held, path);
      if (columns.isEmpty()) return Optional.empty();
    }
    try {
      for (var column : columns) {
        verify(column.times, held.decoded);
        verify(column.values, held.decoded);
        if (column.ordinals != null) verify(column.ordinals, held.decoded);
      }
    } catch (IOException failed) {
      damage = failed.getMessage();
      return Optional.empty();
    }
    return Optional.of(new Selection(this, held, columns, path.hasWildcard()));
  }

  /**
   * The facts of an entry's records, by each record's position among the entry's decoded records,
   * which a cursor's ordinal gives: where the record lies in the log, its payload's size, and its
   * index among the entry's records in file order, as native reads report them. Empty when the
   * file holds none for the entry, or when one of their sections fails its check, which damages
   * the file as a column's would. A view belongs to one reading thread.
   */
  public Optional<RecordFacts> facts(String entry) {
    if (damage != null) return Optional.empty();
    var held = entries.get(entry);
    if (held == null || held.facts == null) return Optional.empty();
    try {
      verify(held.facts.offsets(), held);
      if (held.facts.sizes() != null) verify(held.facts.sizes(), held);
      if (held.facts.ordinals() != null) verify(held.facts.ordinals(), held);
    } catch (IOException failed) {
      damage = failed.getMessage();
      return Optional.empty();
    }
    return Optional.of(new RecordFacts(bytes, held.facts));
  }

  /**
   * The records of one numeric leaf as a native read reports them, in time order: each record's
   * time and facts, and the leaf's value as the decode gives a scalar entry's (a Double, Float,
   * Long or Boolean). Empty unless the file holds the leaf from every record of the entry, every
   * record decoded, and the file keeps the entry's facts, so the caller reads the log instead. An
   * enum field is never held this way, since its label is not kept. A series belongs to one
   * reading thread.
   */
  public Optional<HeldRecords> records(String entry, FieldPath path) {
    if (path.hasWildcard()) return Optional.empty();
    var selection = select(entry, path).orElse(null);
    if (selection == null) return Optional.empty();
    var held = selection.entry;
    var column = selection.columns.get(0);
    if (held.problem != null || held.decoded != held.records || column.size != held.decoded
        || column.kind == Kind.ENUM) return Optional.empty();
    return facts(entry).map(facts -> new HeldRecords(new ColumnSamples(bytes, column, 0, column.size), facts));
  }

  @Override
  public void close() throws IOException {
    bytes.close();
  }

  /**
   * The columns a pooled path matches, in the order {@code FieldPath.resolveAll} visits their
   * elements within a record: by the indexes at the pattern's {@code [*]} steps.
   */
  private static List<Column> pooled(Entry entry, FieldPath pattern) {
    return StructLeaves.pooled(entry.columns.values(), column -> column.path, pattern);
  }

  /** Checks a section's checksum and order once; a section that fails damages the file. */
  private void verify(Section section, int decoded) throws IOException {
    if (section.verified) return;
    var crc = new CRC32C();
    long previous = Long.MIN_VALUE;
    for (int from = 0; from < section.count; from += CHUNK) {
      int count = Math.min(CHUNK, section.count - from);
      var view = bytes.view(section.offset + (long) from * section.width, count * section.width);
      crc.update(view.duplicate());
      for (int i = 0; i < count; i++) {
        long value = section.width == 8 ? view.getLong(8 * i) : view.getInt(4 * i);
        boolean ordered = switch (section.role) {
          case TIMES -> value >= previous;
          case ORDINALS -> value > previous && value < decoded && value >= 0;
          case BOOLS -> value == 0 || value == 1;
          case FLOATS -> value >>> 32 == 0;
          case VALUES -> true;
        };
        if (!ordered) throw new IOException("Column section at " + section.offset + " holds a value out of place");
        previous = value;
      }
    }
    if ((int) crc.getValue() != section.crc) throw new IOException("Column section at " + section.offset + " fails its checksum");
    section.verified = true;
  }

  /**
   * Checks a packed section once: its checksum over exactly its bytes first, so damage reads as
   * damage, then its block index and every value decoded in order, each block's differences
   * ending exactly where the next block's begin, which a crafted section with a right checksum
   * must also pass.
   */
  private void verify(PackedSection section, Entry entry) throws IOException {
    if (section.verified) return;
    var crc = new CRC32C();
    for (long done = 0; done < section.length; done += 1 << 16) {
      crc.update(bytes.view(section.offset + done, (int) Math.min(1 << 16, section.length - done)));
    }
    if ((int) crc.getValue() != section.crc) throw new IOException("Packed section at " + section.offset + " fails its checksum");
    int blocks = Packed.blocks(section.count);
    long indexBytes = (long) Packed.INDEX_BYTES * blocks;
    long dataBytes = section.length - indexBytes;
    var firsts = new long[blocks];
    var starts = new long[blocks + 1];
    for (int from = 0; from < blocks; from += CHUNK) {
      int count = Math.min(CHUNK, blocks - from);
      var view = bytes.view(section.offset + (long) Packed.INDEX_BYTES * from, Packed.INDEX_BYTES * count);
      for (int b = 0; b < count; b++) {
        firsts[from + b] = view.getLong(Packed.INDEX_BYTES * b);
        starts[from + b] = view.getLong(Packed.INDEX_BYTES * b + 8);
      }
    }
    starts[blocks] = dataBytes;
    if (blocks > 0 && starts[0] != 0) throw new IOException("Packed section at " + section.offset + " starts out of place");
    boolean increasing = section.fact == Fact.OFFSETS && entry.facts.ordinals() == null;
    long previous = Long.MIN_VALUE;
    for (int b = 0; b < blocks; b++) {
      long length = starts[b + 1] - starts[b];
      if (length < 0 || length > (long) Packed.MAX_VARINT_BYTES * Packed.BLOCK)
        throw new IOException("Packed section at " + section.offset + " is laid out out of order");
      var data = bytes.view(section.offset + indexBytes + starts[b], (int) length);
      int first = b << Packed.SHIFT, end = (int) Math.min(section.count, (long) (b + 1) << Packed.SHIFT);
      long value = firsts[b];
      for (int i = first; i < end; i++) {
        if (i > first) value += Packed.unzigzag(Packed.getVarint(data));
        boolean fits = switch (section.fact) {
          case OFFSETS -> value >= 0 && (!increasing || value > previous);
          case SIZES -> value >= 0 && value <= Integer.MAX_VALUE;
          case ORDINALS -> value >= 0 && value < entry.records;
        };
        if (!fits) throw new IOException("Packed section at " + section.offset + " holds a value out of place");
        previous = value;
      }
      if (data.hasRemaining()) throw new IOException("Packed section at " + section.offset + " holds bytes past its values");
    }
    section.verified = true;
  }

  /** What a section's values must satisfy beyond its checksum. */
  private enum Role { TIMES, VALUES, BOOLS, FLOATS, ORDINALS }

  /** Which record fact a packed section holds. */
  private enum Fact { OFFSETS, SIZES, ORDINALS }

  private static final class PackedSection {
    final long offset;
    final int count;
    final long length;
    final int crc;
    final Fact fact;
    volatile boolean verified;

    PackedSection(long offset, int count, long length, int crc, Fact fact) {
      this.offset = offset;
      this.count = count;
      this.length = length;
      this.crc = crc;
      this.fact = fact;
    }
  }

  /** An entry's record facts as the directory locates them; one payload size when they agree. */
  private record Facts(PackedSection offsets, PackedSection sizes, long payload, PackedSection ordinals) {}

  /** An entry's record facts by record position; see {@link ColumnFile#facts}. */
  public static final class RecordFacts {
    private final PackedValues offsets;
    private final PackedValues sizes;
    private final long payload;
    private final PackedValues ordinals;

    private RecordFacts(LogBytes bytes, Facts facts) {
      offsets = new PackedValues(bytes, facts.offsets());
      sizes = facts.sizes() == null ? null : new PackedValues(bytes, facts.sizes());
      payload = facts.payload();
      ordinals = facts.ordinals() == null ? null : new PackedValues(bytes, facts.ordinals());
    }

    /** Where the record begins in the log, in bytes from the start of the file. */
    public long offset(int position) {
      return offsets.get(position);
    }

    public long payloadBytes(int position) {
      return sizes == null ? payload : sizes.get(position);
    }

    /** The record's index among the entry's records in file order. */
    public int ordinal(int position) {
      return ordinals == null ? position : (int) ordinals.get(position);
    }

    /**
     * Whether every record's index is its position, as it is unless a record moved into time
     * order or one before it failed to decode.
     */
    public boolean inFileOrder() {
      return ordinals == null;
    }
  }

  /**
   * A held leaf's records for a native read; see {@link ColumnFile#records}. Positions follow
   * time order, equal times in file order, as the log's records are sorted for a read in time
   * order, and each sample reports its record's own index.
   */
  public static final class HeldRecords implements RecordSeries {
    private final ColumnSamples column;
    private final RecordFacts facts;

    private HeldRecords(ColumnSamples column, RecordFacts facts) {
      this.column = column;
      this.facts = facts;
    }

    /** Whether each record's index among the entry's records is its position. */
    public boolean inFileOrder() {
      return facts.inFileOrder();
    }

    @Override public int size() { return column.size(); }
    @Override public long timestampUs(int index) { return column.timeUs(index); }
    @Override public long offset(int index) { return facts.offset(index); }
    @Override public long payloadBytes(int index) { return facts.payloadBytes(index); }
    /** The column keeps its records in time order. */
    @Override public boolean monotonic() { return true; }
    @Override public Object decode(int index) { return column.kind.value(column.values.get(column.from + index)); }

    @Override
    public void readRange(int start, int end, String entry, SampleCursors.Sink sink) {
      for (int index = start; index < end; index++) {
        sink.sample(timestampUs(index), offset(index), facts.ordinal(index), decode(index));
      }
    }

    /**
     * Every record of a held leaf decoded when the file was built, so a failure here is the
     * file's, not a record's, and the read stops rather than report the record as malformed.
     */
    @Override
    public String problemMessage(int index, Exception failure) {
      throw new IllegalStateException("The column file failed at record position " + index, failure);
    }
  }

  /** A verified packed section's values by index, decoded forward from the block that holds one. */
  private static final class PackedValues {
    private final LogBytes bytes;
    private final PackedSection section;
    private final int blocks;
    private final long indexBytes;
    private int block = -1;
    private int current;
    private long value;
    private ByteBuffer data;

    PackedValues(LogBytes bytes, PackedSection section) {
      this.bytes = bytes;
      this.section = section;
      blocks = Packed.blocks(section.count);
      indexBytes = (long) Packed.INDEX_BYTES * blocks;
    }

    long get(int i) {
      if (i < 0 || i >= section.count) throw new IndexOutOfBoundsException("Record position " + i);
      int b = i >>> Packed.SHIFT;
      if (b != block || i < current) start(b);
      while (current < i) {
        try {
          value += Packed.unzigzag(Packed.getVarint(data));
        } catch (IOException verified) {
          throw new IllegalStateException("A verified packed section no longer reads", verified);
        }
        current++;
      }
      return value;
    }

    private void start(int b) {
      var index = bytes.view(section.offset + (long) Packed.INDEX_BYTES * b, Packed.INDEX_BYTES);
      value = index.getLong(0);
      long from = index.getLong(8);
      long to = b + 1 < blocks
          ? bytes.view(section.offset + (long) Packed.INDEX_BYTES * (b + 1) + 8, 8).getLong(0)
          : section.length - indexBytes;
      data = bytes.view(section.offset + indexBytes + from, (int) (to - from));
      block = b;
      current = b << Packed.SHIFT;
    }
  }

  private static final class Section {
    final long offset;
    final int count;
    final int width;
    final int crc;
    final Role role;
    volatile boolean verified;

    Section(long offset, int count, int width, int crc, Role role) {
      this.offset = offset;
      this.count = count;
      this.width = width;
      this.crc = crc;
      this.role = role;
    }
  }

  private record Column(FieldPath path, Kind kind, int size, boolean full, Section times, Section values,
      Section ordinals) {}

  /** An entry the file holds: its record counts and decode problem as the log reports them. */
  public static final class Entry {
    private final String name;
    private final String type;
    private final int records;
    private final int decoded;
    private final DecodeProblem problem;
    private final boolean complete;
    private final Map<String, Column> columns;
    private final Facts facts;

    private Entry(String name, String type, int records, int decoded, DecodeProblem problem,
        boolean complete, Map<String, Column> columns, Facts facts) {
      this.name = name;
      this.type = type;
      this.records = records;
      this.decoded = decoded;
      this.problem = problem;
      this.complete = complete;
      this.columns = columns;
      this.facts = facts;
    }

    public String name() { return name; }
    public String type() { return type; }
    /** Records the log holds for the entry, decoded or not. */
    public int records() { return records; }
    /** Records that decoded, which the columns are positions into. */
    public int decoded() { return decoded; }
    public Optional<DecodeProblem> decodeProblem() { return Optional.ofNullable(problem); }
    /** Whether every numeric leaf of every decoded record has a column, so pooled reads are held. */
    public boolean complete() { return complete; }
    /** The paths of the entry's columns, as the field path grammar spells them. */
    public Set<String> paths() { return Collections.unmodifiableSet(columns.keySet()); }
  }

  /** The samples of one path, which may pool several columns. */
  public static final class Selection {
    private final ColumnFile file;
    private final Entry entry;
    private final List<Column> columns;
    private final boolean pooled;

    private Selection(ColumnFile file, Entry entry, List<Column> columns, boolean pooled) {
      this.file = file;
      this.entry = entry;
      this.columns = columns;
      this.pooled = pooled;
    }

    public Entry entry() { return entry; }

    /** Samples in the whole selection. */
    public int size() {
      return columns.stream().mapToInt(Column::size).sum();
    }

    /** The one kind of all its columns, or empty when they differ. */
    public Optional<Kind> kind() {
      var kind = columns.get(0).kind;
      return columns.stream().allMatch(c -> c.kind == kind) ? Optional.of(kind) : Optional.empty();
    }

    /** Decoded records in which the path held no number, as the tools count them. */
    public int recordsWithoutValue() {
      if (!pooled) return entry.decoded - columns.get(0).size;
      if (columns.stream().anyMatch(Column::full)) return 0;
      int distinct = 0, last = -1;
      var cursor = cursor(all());
      while (cursor.next()) {
        if (cursor.ordinal() != last) distinct++;
        last = cursor.ordinal();
      }
      return entry.decoded - distinct;
    }

    public Range all() {
      var from = new int[columns.size()];
      var to = new int[columns.size()];
      for (int c = 0; c < columns.size(); c++) to[c] = columns.get(c).size;
      return new Range(from, to);
    }

    /**
     * The samples a window keeps, by the one seconds window {@code read_entry} and the data
     * endpoint take; the seconds are computed from the logged microseconds as the decoded value
     * carries them, so the window keeps the same samples as it does from the decoded log.
     */
    public Range window(Double startTime, Double endTime) {
      var window = new SecondsWindow(startTime, endTime);
      var from = new int[columns.size()];
      var to = new int[columns.size()];
      for (int c = 0; c < columns.size(); c++) {
        var column = new ColumnSamples(file.bytes, columns.get(c), 0, columns.get(c).size);
        from[c] = window.from(column);
        to[c] = window.to(column);
      }
      return new Range(from, to);
    }

    /**
     * A range's samples in read order as the numbers the tools read, for the overviews and
     * windows every reader shares. A pooled range is put in read order once, when the view is
     * made. A view belongs to one reading thread.
     */
    public NumericSamples samples(Range range) {
      if (range.from.length != columns.size()) throw new IllegalArgumentException("The range belongs to another selection");
      if (columns.size() == 1) return new ColumnSamples(file.bytes, columns.get(0), range.from[0], range.to[0]);
      int size = range.size();
      var column = new int[size];
      var position = new int[size];
      var cursor = cursor(range);
      for (int i = 0; cursor.next(); i++) {
        column[i] = cursor.current;
        position[i] = cursor.index;
      }
      var views = columns.stream().map(c -> new ColumnSamples(file.bytes, c, 0, c.size)).toList();
      return new NumericSamples() {
        @Override public int size() { return size; }
        @Override public long timeUs(int i) { return views.get(column[i]).timeUs(position[i]); }
        @Override public double seconds(int i) { return views.get(column[i]).seconds(position[i]); }
        @Override public double number(int i) { return views.get(column[i]).number(position[i]); }
      };
    }

    /**
     * A one-column range's samples as the values the decode gives a scalar entry, each made when
     * it is read, with its logged coordinate: a Double, Float, Long or Boolean.
     */
    public List<TimestampedValue> values(Range range) {
      if (columns.size() != 1 || columns.get(0).kind == Kind.ENUM)
        throw new IllegalStateException("Only one column of numbers has the values a scalar entry decodes to");
      var column = new ColumnSamples(file.bytes, columns.get(0), range.from[0], range.to[0]);
      final class View extends java.util.AbstractList<TimestampedValue> implements java.util.RandomAccess {
        @Override
        public TimestampedValue get(int index) {
          java.util.Objects.checkIndex(index, column.size());
          return TimestampedValue.atMicros(column.timeUs(index), column.kind.value(column.values.get(column.from + index)));
        }

        @Override
        public int size() {
          return column.size();
        }
      }
      return new View();
    }

    /** The first logged microsecond of a range's samples in read order, which is the earliest. */
    public long firstTimeUs(Range range) {
      long first = Long.MAX_VALUE;
      for (int c = 0; c < columns.size(); c++) {
        if (range.to[c] > range.from[c]) first = Math.min(first, new Longs(file.bytes, columns.get(c).times).get(range.from[c]));
      }
      if (first == Long.MAX_VALUE) throw new IllegalArgumentException("The range is empty");
      return first;
    }

    /** The last logged microsecond of a range's samples in read order, which is the latest. */
    public long lastTimeUs(Range range) {
      long last = Long.MIN_VALUE;
      boolean any = false;
      for (int c = 0; c < columns.size(); c++) {
        if (range.to[c] > range.from[c]) {
          last = Math.max(last, new Longs(file.bytes, columns.get(c).times).get(range.to[c] - 1));
          any = true;
        }
      }
      if (!any) throw new IllegalArgumentException("The range is empty");
      return last;
    }

    public Cursor cursor(Range range) {
      if (range.from.length != columns.size()) throw new IllegalArgumentException("The range belongs to another selection");
      return new Cursor(file.bytes, columns, range);
    }


  }

  /** One column's samples from {@code from} up to {@code to}, as numbers, by position. */
  private static final class ColumnSamples implements NumericSamples {
    private final Kind kind;
    private final int from;
    private final int size;
    private final Longs times;
    private final Longs values;

    ColumnSamples(LogBytes bytes, Column column, int from, int to) {
      this.kind = column.kind;
      this.from = from;
      this.size = to - from;
      this.times = new Longs(bytes, column.times);
      this.values = new Longs(bytes, column.values);
    }

    @Override public int size() { return size; }
    @Override public long timeUs(int index) { return times.get(from + index); }
    /** As {@code TimestampedValue.atMicros} computes a decoded value's seconds. */
    @Override public double seconds(int index) { return timeUs(index) / 1_000_000.0; }
    @Override public double number(int index) { return kind.number(values.get(from + index)); }
    /** An integer's own bits, as {@code Long.equals} compares the decoded values. */
    @Override public long valueBits(int index) {
      return kind == Kind.I64 ? values.get(from + index) : Double.doubleToLongBits(number(index));
    }
  }

  /** Index ranges into each column of a selection. */
  public static final class Range {
    private final int[] from;
    private final int[] to;

    private Range(int[] from, int[] to) {
      this.from = from;
      this.to = to;
    }

    public int size() {
      int size = 0;
      for (int c = 0; c < from.length; c++) size += to[c] - from[c];
      return size;
    }

    public boolean isEmpty() {
      return size() == 0;
    }
  }

  /**
   * A selection's samples in the order the tools read them: by record, and within a record by
   * element, as {@code FieldPath.resolveAll} visits a record's elements.
   */
  public static final class Cursor {
    private final List<Column> columns;
    private final Longs[] times;
    private final Longs[] values;
    private final Ints[] ordinals;
    private final int[] position;
    private final int[] end;
    private int current = -1;
    private int index;

    private Cursor(LogBytes bytes, List<Column> columns, Range range) {
      this.columns = columns;
      int count = columns.size();
      times = new Longs[count];
      values = new Longs[count];
      ordinals = new Ints[count];
      position = range.from.clone();
      end = range.to.clone();
      for (int c = 0; c < count; c++) {
        var column = columns.get(c);
        times[c] = new Longs(bytes, column.times);
        values[c] = new Longs(bytes, column.values);
        if (column.ordinals != null) ordinals[c] = new Ints(bytes, column.ordinals);
      }
    }

    public boolean next() {
      int best = -1;
      for (int c = 0; c < position.length; c++) {
        if (position[c] < end[c] && (best < 0 || ordinal(c, position[c]) < ordinal(best, position[best]))) best = c;
      }
      if (best < 0) return false;
      current = best;
      index = position[best]++;
      return true;
    }

    public long timeUs() {
      return times[current].get(index);
    }

    /** The decoded value's seconds, as {@code TimestampedValue.atMicros} computes them. */
    public double seconds() {
      return timeUs() / 1_000_000.0;
    }

    public long bits() {
      return values[current].get(index);
    }

    public Kind kind() {
      return columns.get(current).kind;
    }

    /** The value as the tools read it; see {@link Kind#number}. */
    public double number() {
      return kind().number(bits());
    }

    /** The position of the sample's record among the entry's decoded records. */
    public int ordinal() {
      return ordinal(current, index);
    }

    private int ordinal(int column, int at) {
      if (columns.get(column).full) return at;
      if (ordinals[column] == null) throw new IllegalStateException("A column outside arrays keeps no record positions");
      return ordinals[column].get(at);
    }
  }

  /** A section's eight-byte values, read through one mapped view of up to 8,192 at a time. */
  private static final class Longs {
    private final LogBytes bytes;
    private final long offset;
    private final int count;
    private ByteBuffer view;
    private int base;
    private int limit;

    Longs(LogBytes bytes, Section section) {
      this.bytes = bytes;
      this.offset = section.offset;
      this.count = section.count;
    }

    long get(int i) {
      if (view == null || i < base || i >= limit) {
        base = i - i % CHUNK;
        limit = Math.min(count, base + CHUNK);
        view = bytes.view(offset + 8L * base, 8 * (limit - base));
      }
      return view.getLong(8 * (i - base));
    }
  }

  private static final class Ints {
    private final LogBytes bytes;
    private final long offset;
    private final int count;
    private ByteBuffer view;
    private int base;
    private int limit;

    Ints(LogBytes bytes, Section section) {
      this.bytes = bytes;
      this.offset = section.offset;
      this.count = section.count;
    }

    int get(int i) {
      if (view == null || i < base || i >= limit) {
        base = i - i % CHUNK;
        limit = Math.min(count, base + CHUNK);
        view = bytes.view(offset + 4L * base, 4 * (limit - base));
      }
      return view.getInt(4 * (i - base));
    }
  }

  /**
   * Reads the header, footer and directory, checking every count and offset against the file
   * and the format's limits before it allocates anything sized by them.
   */
  private static final class Parser {
    private final MappedLogBytes bytes;
    private ByteBuffer in;
    private long sectionsEnd;

    Parser(MappedLogBytes bytes) {
      this.bytes = bytes;
    }

    Map<String, Entry> parse(String expectedSource) throws IOException {
      long size = bytes.size();
      if (size < HEADER_BYTES + FOOTER_BYTES) throw new IOException("Not a column file: " + size + " bytes");
      var header = bytes.view(0, HEADER_BYTES);
      if (header.getLong(0) != MAGIC) throw new IOException("Not a column file");
      int format = header.getInt(8), recipe = header.getInt(12);
      if (format != FORMAT_VERSION) throw new IOException("Column file format " + format + ", not " + FORMAT_VERSION);
      if (recipe != RECIPE_VERSION) throw new IOException("Column file recipe " + recipe + ", not " + RECIPE_VERSION);
      var footer = bytes.view(size - FOOTER_BYTES, FOOTER_BYTES);
      if (footer.getInt(20) != FOOTER_MAGIC) throw new IOException("Column file is incomplete");
      long directoryOffset = footer.getLong(0), directoryLength = footer.getLong(8);
      if (directoryLength < 0 || directoryLength > MAX_DIRECTORY_BYTES || directoryOffset < HEADER_BYTES
          || directoryOffset % 8 != 0 || directoryOffset != size - FOOTER_BYTES - directoryLength)
        throw new IOException("Column file directory is out of place");
      sectionsEnd = directoryOffset;
      in = bytes.view(directoryOffset, (int) directoryLength);
      var crc = new CRC32C();
      crc.update(header.duplicate());
      crc.update(in.duplicate());
      if ((int) crc.getValue() != footer.getInt(16)) throw new IOException("Column file directory fails its checksum");

      if (!string().equals(expectedSource)) throw new IOException("Column file belongs to other content");
      int count = count(MAX_ENTRIES);
      var entries = new HashMap<String, Entry>();
      int columns = 0;
      for (int e = 0; e < count; e++) {
        var entry = entry();
        columns += entry.columns.size();
        if (columns > MAX_COLUMNS) throw new IOException("Column file holds too many columns");
        if (entries.put(entry.name, entry) != null) throw new IOException("Column file repeats entry " + entry.name);
      }
      if (in.hasRemaining()) throw new IOException("Column file directory has trailing bytes");
      return Map.copyOf(entries);
    }

    private Entry entry() throws IOException {
      var name = string();
      var type = string();
      int flags = u8();
      if ((flags & ~3) != 0) throw new IOException("Unknown entry flags " + flags);
      int records = count(Integer.MAX_VALUE), decoded = count(Integer.MAX_VALUE);
      DecodeProblem problem = null;
      if ((flags & 2) != 0) problem = new DecodeProblem(string(), count(Integer.MAX_VALUE), count(Integer.MAX_VALUE));
      long timesOffset = u64();
      int timesCrc = i32();
      var recordTimes = timesOffset == ColumnFileWriter.NONE ? null : section(timesOffset, decoded, 8, timesCrc, Role.TIMES);
      int layoutOfFacts = u8();
      if ((layoutOfFacts & ~7) != 0 || layoutOfFacts != 0 && (layoutOfFacts & 1) == 0)
        throw new IOException("Unknown record facts layout " + layoutOfFacts);
      Facts facts = null;
      if (layoutOfFacts != 0) {
        var offsets = packed(decoded, Fact.OFFSETS);
        PackedSection sizes = null;
        long payload = -1;
        if ((layoutOfFacts & 2) != 0) sizes = packed(decoded, Fact.SIZES);
        else if ((payload = u64()) < 0 || payload > Integer.MAX_VALUE)
          throw new IOException("Entry " + name + " has a record payload size out of place");
        var ordinals = (layoutOfFacts & 4) != 0 ? packed(decoded, Fact.ORDINALS) : null;
        facts = new Facts(offsets, sizes, payload, ordinals);
      }
      int count = count(MAX_ENTRY_COLUMNS);
      if (count == 0) throw new IOException("Entry " + name + " holds no columns");
      boolean scalar = StructSchemas.structName(type) == null;
      if (scalar && !ColumnFileWriter.covered(type)) throw new IOException("Entry " + name + " has type " + type);
      var columns = new LinkedHashMap<String, Column>();
      for (int c = 0; c < count; c++) {
        var text = string();
        var kind = Kind.of(u8());
        int size = count(decoded);
        int layout = u8();
        if ((layout & ~3) != 0) throw new IOException("Unknown column layout " + layout);
        boolean own = (layout & 1) != 0, positions = (layout & 2) != 0;
        var path = path(text);
        boolean indexed = path.steps().stream().anyMatch(step -> step instanceof FieldPath.Index);
        if (size == 0 || own != size < decoded || positions != (own && indexed) || scalar != path.isRoot())
          throw new IOException("Column " + name + text + " is laid out inconsistently");
        var times = own ? section(u64(), size, 8, i32(), Role.TIMES) : recordTimes;
        if (times == null) throw new IOException("Column " + name + text + " shares timestamps the entry lacks");
        var values = section(u64(), size, 8, i32(), switch (kind) {
          case BOOL -> Role.BOOLS;
          case F32 -> Role.FLOATS;
          default -> Role.VALUES;
        });
        var ordinals = positions ? section(u64(), size, 4, i32(), Role.ORDINALS) : null;
        if (columns.put(text, new Column(path, kind, size, !own, times, values, ordinals)) != null)
          throw new IOException("Entry " + name + " repeats column " + text);
      }
      if (scalar && columns.size() != 1) throw new IOException("Entry " + name + " is a number with several columns");
      return new Entry(name, type, records, decoded, problem, (flags & 1) != 0, columns, facts);
    }

    /** A packed section's place, which must lie inside the file and fit the values it can hold. */
    private PackedSection packed(int count, Fact fact) throws IOException {
      long offset = u64(), length = u64();
      int crc = i32();
      long index = (long) Packed.INDEX_BYTES * Packed.blocks(count);
      if (offset < HEADER_BYTES || offset % 8 != 0 || offset > sectionsEnd || length < index
          || length > sectionsEnd - offset || length - index > (long) Packed.MAX_VARINT_BYTES * count)
        throw new IOException("Packed section lies outside the file");
      return new PackedSection(offset, count, length, crc, fact);
    }

    /** A column's path, which must read back as itself, select one leaf, and stay in limits. */
    private static FieldPath path(String text) throws IOException {
      FieldPath path;
      try {
        path = FieldPath.parse(text);
      } catch (IllegalArgumentException malformed) {
        throw new IOException("Column path " + text + " is malformed");
      }
      if (text.length() > MAX_PATH_CHARS || path.hasWildcard() || !path.toString().equals(text)
          || path.steps().stream().anyMatch(s -> s instanceof FieldPath.Index i && i.index() >= MAX_INDEX))
        throw new IOException("Column path " + text + " is not one a column file holds");
      return path;
    }

    private Section section(long offset, int count, int width, int crc, Role role) throws IOException {
      long length = (long) count * width;
      if (offset < HEADER_BYTES || offset % 8 != 0 || offset > sectionsEnd || length > sectionsEnd - offset)
        throw new IOException("Column section lies outside the file");
      return new Section(offset, count, width, crc, role);
    }

    private int count(int limit) throws IOException {
      int count = i32();
      if (count < 0 || count > limit) throw new IOException("Column file count " + Integer.toUnsignedString(count) + " exceeds " + limit);
      return count;
    }

    private int u8() throws IOException {
      need(1);
      return in.get() & 0xFF;
    }

    private int i32() throws IOException {
      need(4);
      return in.getInt();
    }

    private long u64() throws IOException {
      need(8);
      return in.getLong();
    }

    private String string() throws IOException {
      int length = count(MAX_STRING_BYTES);
      need(length);
      var text = in.slice(in.position(), length);
      in.position(in.position() + length);
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(text).toString();
    }

    private void need(int count) throws IOException {
      if (in.remaining() < count) throw new IOException("Column file directory ends early");
    }
  }
}
