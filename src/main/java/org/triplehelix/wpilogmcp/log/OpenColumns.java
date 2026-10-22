/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.columns.ColumnFileFormat.Kind;

/**
 * An open capture's index outside the heap: each entry's records, in the order the writer wrote
 * them, as columns of their publication sequence, time, byte position and payload size, and of
 * the value for a number. The writer appends to an entry's tail, a few primitive arrays, and a
 * full tail of {@value #BLOCK} records becomes a block, packed as a finished column file packs its
 * positions, and written to a scratch file that is never forced to disk, so the operating system
 * keeps it in its cache and writes it out as memory requires. Readers read a block with one
 * positional read, so no mapping can be released under them.
 *
 * <p>When the file cannot take a block (no file was given, the recording's disk reserve is
 * short, or a write failed) the block stays in the heap, packed. A capture's heap then grows
 * by about 13 bytes a value, against the 52 an object for each record took, and
 * {@link #heapReason} says why.
 *
 * <p>One writer appends; any number of readers read the records below a length they have seen
 * published. A tail is never reused: a full tail is replaced by its block, and a reader still
 * holding the tail reads the same records from it.
 */
public final class OpenColumns implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(OpenColumns.class);
  static final int BLOCK = 512;
  private static final int SHIFT = 9;

  /** Where blocks go, and when the disk must be left to the recording. */
  public interface Placement {
    /** The scratch file for a capture's columns, or empty to keep every block in the heap. */
    Optional<Path> file(String captureIdentity);

    /** Whether the recording's disk reserve is short, which keeps new blocks in the heap. */
    boolean reserveShort();

    /** Every block in the heap, as a server without a disk cache keeps them. */
    Placement HEAP = new Placement() {
      @Override public Optional<Path> file(String captureIdentity) { return Optional.empty(); }
      @Override public boolean reserveShort() { return false; }
    };
  }

  private final Path path;
  private final BooleanSupplier reserveShort;
  private volatile FileChannel channel;
  private long fileEnd;
  /** Set once the file failed: every later block stays in the heap. */
  private boolean fileFailed;
  private volatile String heapReason;
  private volatile boolean closed;
  /** Packed blocks decoded by readers, which a test counts. */
  private final java.util.concurrent.atomic.AtomicLong blocksRead = new java.util.concurrent.atomic.AtomicLong();
  /** The writer's packing buffer: the largest block of the widest kind. */
  private final ByteBuffer packing = ByteBuffer.allocate(BLOCK * 48 + 64).order(ByteOrder.LITTLE_ENDIAN);

  OpenColumns(Placement placement, String captureIdentity) {
    Path file = null;
    try {
      file = placement.file(captureIdentity).orElse(null);
    } catch (RuntimeException unavailable) {
      logger.debug("No column file for capture {}: {}", captureIdentity, unavailable.toString());
    }
    path = file;
    reserveShort = placement::reserveShort;
    if (path == null) heapReason = "the disk cache is off or unavailable";
  }

  /** The scratch file, while this index may write or read it. */
  Optional<Path> file() {
    return path == null || channel == null ? Optional.empty() : Optional.of(path);
  }

  /** Why blocks are kept in the heap, or null while the file takes them. */
  String heapReason() {
    return heapReason;
  }

  /** How many packed blocks readers have decoded. */
  long blocksRead() {
    return blocksRead.get();
  }

  /** The kind of value an entry's column keeps, or null when it keeps only the records' facts. */
  static Kind kindOf(String type) {
    return switch (type) {
      case "double" -> Kind.F64;
      case "float" -> Kind.F32;
      case "int64" -> Kind.I64;
      case "boolean" -> Kind.BOOL;
      default -> null;
    };
  }

  /** Whether a recorded value is the kind's own type, as the capture's frames carry each type. */
  static boolean holds(Kind kind, Object value) {
    return switch (kind) {
      case F64 -> value instanceof Double;
      case F32 -> value instanceof Float;
      case I64 -> value instanceof Long;
      case BOOL -> value instanceof Boolean;
      case ENUM -> false;
    };
  }

  /** A number's bits as its column keeps them, from the value the writer recorded. */
  static long bitsOf(Kind kind, Object value) {
    return switch (kind) {
      case F64 -> Double.doubleToRawLongBits((Double) value);
      case F32 -> Float.floatToRawIntBits((Float) value) & 0xFFFF_FFFFL;
      case I64 -> ((Number) value).longValue();
      case BOOL -> (Boolean) value ? 1 : 0;
      case ENUM -> throw new IllegalArgumentException("An open capture keeps no enum column");
    };
  }

  /**
   * One block: the tail while it fills, then the packed block that replaces it. A leaf's block
   * gives its first sample's record as its first sequence, which a search by record reads.
   */
  private abstract static sealed class Block permits Tail, LeafTail, Packed {
    abstract long firstSequence();
    abstract long firstTimeUs();
  }

  /** A filling block's columns, written only by the writer below the published length. */
  private static final class Tail extends Block {
    long[] sequences, times, offsets, bits;
    int[] sizes;

    Tail(Kind kind, int capacity) {
      sequences = new long[capacity];
      times = new long[capacity];
      offsets = new long[capacity];
      sizes = new int[capacity];
      bits = kind == null ? null : new long[capacity];
    }

    /** Replaced arrays, never resized in place: a reader keeps the arrays it read. */
    void grow(int capacity) {
      sequences = java.util.Arrays.copyOf(sequences, capacity);
      times = java.util.Arrays.copyOf(times, capacity);
      offsets = java.util.Arrays.copyOf(offsets, capacity);
      sizes = java.util.Arrays.copyOf(sizes, capacity);
      if (bits != null) bits = java.util.Arrays.copyOf(bits, capacity);
    }

    @Override long firstSequence() { return sequences[0]; }
    @Override long firstTimeUs() { return times[0]; }
  }

  /** A filling block of a struct leaf's samples: each one's record and bits. */
  private static final class LeafTail extends Block {
    int[] records;
    long[] bits;

    LeafTail(int capacity) {
      records = new int[capacity];
      bits = new long[capacity];
    }

    /** Replaced arrays, never resized in place: a reader keeps the arrays it read. */
    void grow(int capacity) {
      records = java.util.Arrays.copyOf(records, capacity);
      bits = java.util.Arrays.copyOf(bits, capacity);
    }

    @Override long firstSequence() { return records[0]; }
    @Override long firstTimeUs() { return 0; }
  }

  /** A full block, packed: in the scratch file at {@code position}, or in the heap as {@code bytes}. */
  private static final class Packed extends Block {
    final long sequence, timeUs, position;
    final int length;
    final byte[] bytes;

    Packed(long sequence, long timeUs, long position, int length, byte[] bytes) {
      this.sequence = sequence;
      this.timeUs = timeUs;
      this.position = position;
      this.length = length;
      this.bytes = bytes;
    }

    @Override long firstSequence() { return sequence; }
    @Override long firstTimeUs() { return timeUs; }
  }

  /** One entry's columns. */
  final class Series {
    final EntryInfo info;
    final Kind kind;
    private volatile Block[] blocks = new Block[4];
    private volatile int length;
    private volatile int firstInversion = Integer.MAX_VALUE;
    private Tail tail;
    private int count;
    private long lastTimeUs;

    Series(EntryInfo info) {
      this.info = info;
      this.kind = kindOf(info.type());
    }

    /** Records published, the writer's own and every reader's upper bound. */
    int length() {
      return length;
    }

    /** Whether the first {@code length} records are in time order. */
    boolean monotonic(int length) {
      return firstInversion >= length;
    }

    /** Writer thread only: one record's facts, and its value's bits for a number. */
    void append(long sequence, long timeUs, long offset, int payloadBytes, long bits) throws IOException {
      int index = count;
      int slot = index & (BLOCK - 1);
      if (slot == 0) {
        tail = new Tail(kind, 8);
        var current = blocks;
        int block = index >>> SHIFT;
        if (block == current.length) blocks = current = java.util.Arrays.copyOf(current, current.length * 2);
        current[block] = tail;
      } else if (slot == tail.times.length) {
        tail.grow(Math.min(BLOCK, slot * 2));
      }
      if (index > 0 && firstInversion == Integer.MAX_VALUE && timeUs < lastTimeUs) firstInversion = index;
      lastTimeUs = timeUs;
      tail.sequences[slot] = sequence;
      tail.times[slot] = timeUs;
      tail.offsets[slot] = offset;
      tail.sizes[slot] = payloadBytes;
      if (tail.bits != null) tail.bits[slot] = bits;
      count = index + 1;
      // Readers see the record only after this volatile publication.
      length = count;
      if (slot == BLOCK - 1) seal(index >>> SHIFT);
    }

    /** A full tail becomes its packed block, in the file when the file takes it. */
    private void seal(int block) {
      var full = tail;
      packing.clear();
      pack(full, kind, packing);
      packing.flip();
      blocks[block] = place(full.sequences[0], full.times[0]);
      tail = null;
    }

    /** How many of the first records were published at or before {@code sequence}. */
    int bound(long sequence) {
      int published = length;
      if (published == 0) return 0;
      var current = blocks;
      int last = (published - 1) >>> SHIFT;
      if (current[0].firstSequence() > sequence) return 0;
      int low = 0, high = last;
      while (low < high) {
        int mid = (low + high + 1) >>> 1;
        if (current[mid].firstSequence() <= sequence) low = mid; else high = mid - 1;
      }
      var reader = new Reader(this, published);
      int lo = low << SHIFT, hi = Math.min(published, lo + BLOCK);
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (reader.sequence(mid) <= sequence) lo = mid + 1; else hi = mid;
      }
      return lo;
    }

    /** The first {@code limit} records, by position. */
    Reader reader(int limit) {
      return new Reader(this, limit);
    }

    /** The heap this entry's index holds: tails and packed blocks kept in the heap, and each block's reference. */
    long heapBytes() {
      return heapBytes(length);
    }

    /** As {@link #heapBytes()}, for the blocks that hold the first {@code limit} records. */
    long heapBytes(int limit) {
      long bytes = 0;
      var current = blocks;
      int blockCount = (Math.min(limit, length) + BLOCK - 1) >>> SHIFT;
      for (int b = 0; b < Math.min(blockCount, current.length); b++) {
        var block = current[b];
        if (block instanceof Tail filling) bytes += 36L * filling.times.length;
        else if (block instanceof Packed packed) bytes += 48 + (packed.bytes == null ? 0 : packed.bytes.length);
      }
      return bytes;
    }
  }

  /**
   * The packed block in {@link #packing}, written to the file when the file takes it and kept in
   * the heap otherwise. Writer thread only.
   */
  private Packed place(long firstSequence, long firstTimeUs) {
    int bytes = packing.remaining();
    if (fileWritable()) {
      try {
        var open = channel;
        if (open == null) channel = open = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.READ, StandardOpenOption.WRITE);
        long at = fileEnd;
        while (packing.hasRemaining()) open.write(packing, at + packing.position());
        fileEnd = at + bytes;
        heapReason = null;
        return new Packed(firstSequence, firstTimeUs, at, bytes, null);
      } catch (IOException | RuntimeException failed) {
        fileFailed = true;
        heapReason = "a block could not be written: " + failed;
        logger.warn("Open capture columns stay in the heap: {}", heapReason);
      }
    }
    var heap = new byte[bytes];
    packing.position(0);
    packing.get(heap);
    return new Packed(firstSequence, firstTimeUs, -1, bytes, heap);
  }

  /** Whether a block goes to the file: never once the file failed; not while the reserve is short. */
  private boolean fileWritable() {
    if (path == null || closed || fileFailed) return false;
    try {
      if (!reserveShort.getAsBoolean()) return true;
      heapReason = "the recording's disk reserve is short";
    } catch (RuntimeException unanswered) {
      heapReason = "the recording's disk reserve is unknown";
    }
    return false;
  }

  /**
   * One numeric leaf of a struct entry: for each record that held the leaf, the record's position
   * among its entry's records and the leaf's bits, in the order the writer walked them. Blocks are
   * packed and placed as an entry's are. Samples of one record never repeat, so a search by
   * record finds a prefix.
   */
  final class Leaf {
    final Kind kind;
    private volatile Block[] blocks = new Block[4];
    private volatile int length;
    private LeafTail tail;
    private int count;

    Leaf(Kind kind) {
      this.kind = kind;
    }

    /** Samples published. */
    int length() {
      return length;
    }

    /** Writer thread only, before the record is published: one sample of a record past the last. */
    void append(int record, long bits) {
      int index = count;
      int slot = index & (BLOCK - 1);
      if (slot == 0) {
        tail = new LeafTail(8);
        var current = blocks;
        int block = index >>> SHIFT;
        if (block == current.length) blocks = current = java.util.Arrays.copyOf(current, current.length * 2);
        current[block] = tail;
      } else if (slot == tail.records.length) {
        tail.grow(Math.min(BLOCK, slot * 2));
      }
      tail.records[slot] = record;
      tail.bits[slot] = bits;
      count = index + 1;
      length = count;
      if (slot == BLOCK - 1) {
        var full = tail;
        packing.clear();
        packLeaf(full, kind, packing);
        packing.flip();
        blocks[index >>> SHIFT] = place(full.records[0], 0);
        tail = null;
      }
    }

    /** How many of the samples belong to the first {@code records} records of their entry. */
    int bound(int records) {
      int published = length;
      if (published == 0) return 0;
      var current = blocks;
      if (current[0].firstSequence() >= records) return 0;
      int low = 0, high = (published - 1) >>> SHIFT;
      while (low < high) {
        int mid = (low + high + 1) >>> 1;
        if (current[mid].firstSequence() < records) low = mid; else high = mid - 1;
      }
      var reader = new LeafReader(this, published);
      int lo = low << SHIFT, hi = Math.min(published, lo + BLOCK);
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (reader.record(mid) < records) lo = mid + 1; else hi = mid;
      }
      return lo;
    }

    /** The first {@code limit} samples, by position. */
    LeafReader reader(int limit) {
      return new LeafReader(this, limit);
    }

    /** The heap the leaf holds: its filling tail, packed blocks kept in the heap, and a reference to each block. */
    long heapBytes() {
      long bytes = 0;
      var current = blocks;
      int blockCount = (length + BLOCK - 1) >>> SHIFT;
      for (int b = 0; b < Math.min(blockCount, current.length); b++) {
        var block = current[b];
        if (block instanceof LeafTail filling) bytes += 12L * filling.records.length;
        else if (block instanceof Packed packed) bytes += 48 + (packed.bytes == null ? 0 : packed.bytes.length);
      }
      return bytes;
    }
  }

  /** Samples 0 to {@code limit} of one leaf, by position, decoding a block once while it is read. */
  final class LeafReader {
    private final Leaf leaf;
    private final int limit;
    private int decodedBlock = -1;
    private int[] records;
    private long[] bits;
    private int[] ownRecords;
    private long[] ownBits;

    LeafReader(Leaf leaf, int limit) {
      this.leaf = leaf;
      this.limit = limit;
    }

    int size() { return limit; }
    int record(int index) { load(index); return records[index & (BLOCK - 1)]; }
    long bits(int index) { load(index); return bits[index & (BLOCK - 1)]; }

    private void load(int index) {
      java.util.Objects.checkIndex(index, limit);
      int block = index >>> SHIFT;
      if (block == decodedBlock) return;
      var held = leaf.blocks[block];
      if (held instanceof LeafTail filling) {
        records = filling.records;
        bits = filling.bits;
        // A tail still filling is read again next time, since its arrays may since have grown.
        decodedBlock = -1;
        return;
      }
      var packed = (Packed) held;
      blocksRead.incrementAndGet();
      var bytes = packed.bytes != null ? ByteBuffer.wrap(packed.bytes) : read(packed);
      bytes.order(ByteOrder.LITTLE_ENDIAN);
      if (ownRecords == null) {
        ownRecords = new int[BLOCK];
        ownBits = new long[BLOCK];
      }
      unpackLeaf(bytes, leaf.kind, ownRecords, ownBits);
      records = ownRecords;
      bits = ownBits;
      decodedBlock = block;
    }
  }

  /**
   * Records 0 to {@code limit} of one entry, by position, decoding a block once while it is read.
   * A packed block is decoded into this reader's own arrays; a filling tail is read in place, and
   * its arrays are never written here, since the writer owns them.
   */
  final class Reader {
    private final Series series;
    private final int limit;
    private int decodedBlock = -1;
    /** The arrays of the block being read: a tail's, or this reader's own. */
    private long[] sequences, times, offsets, bits;
    private int[] sizes;
    /** This reader's own decode arrays, allocated on the first packed block. */
    private long[] ownSequences, ownTimes, ownOffsets, ownBits;
    private int[] ownSizes;

    Reader(Series series, int limit) {
      this.series = series;
      this.limit = limit;
    }

    int size() { return limit; }
    long sequence(int index) { load(index); return sequences[index & (BLOCK - 1)]; }
    long timeUs(int index) { load(index); return times[index & (BLOCK - 1)]; }
    long offset(int index) { load(index); return offsets[index & (BLOCK - 1)]; }
    long payloadBytes(int index) { load(index); return sizes[index & (BLOCK - 1)]; }

    /** A number's bits as its column keeps them; the entry must keep a column. */
    long bits(int index) {
      load(index);
      if (bits == null) throw new IllegalStateException("Entry " + series.info.name() + " keeps no value column");
      return bits[index & (BLOCK - 1)];
    }

    private void load(int index) {
      java.util.Objects.checkIndex(index, limit);
      int block = index >>> SHIFT;
      if (block == decodedBlock) return;
      var held = series.blocks[block];
      if (held instanceof Tail filling) {
        sequences = filling.sequences;
        times = filling.times;
        offsets = filling.offsets;
        sizes = filling.sizes;
        bits = filling.bits;
        // A tail still filling is read again next time, since its arrays may since have grown.
        decodedBlock = -1;
        return;
      }
      var packed = (Packed) held;
      blocksRead.incrementAndGet();
      var bytes = packed.bytes != null ? ByteBuffer.wrap(packed.bytes) : read(packed);
      bytes.order(ByteOrder.LITTLE_ENDIAN);
      if (ownSequences == null) {
        ownSequences = new long[BLOCK];
        ownTimes = new long[BLOCK];
        ownOffsets = new long[BLOCK];
        ownSizes = new int[BLOCK];
        ownBits = series.kind == null ? null : new long[BLOCK];
      }
      unpack(bytes, series.kind, ownSequences, ownTimes, ownOffsets, ownSizes, ownBits);
      sequences = ownSequences;
      times = ownTimes;
      offsets = ownOffsets;
      sizes = ownSizes;
      bits = ownBits;
      decodedBlock = block;
    }
  }

  /** One packed block from the scratch file, with one positional read. */
  private ByteBuffer read(Packed packed) {
    var out = ByteBuffer.allocate(packed.length);
    try {
      var open = channel;
      if (open == null) throw new IOException("The column file is not open");
      while (out.hasRemaining()) {
        int n = open.read(out, packed.position + out.position());
        if (n < 0) throw new IOException("The column file ends inside a block");
      }
    } catch (IOException failed) {
      throw new java.io.UncheckedIOException("Open capture columns could not be read: " + failed.getMessage(), failed);
    }
    return out.flip();
  }

  /**
   * Packs a full tail: the first sequence, time and position whole; then each record's
   * difference from the one before, as zigzag varints; payload sizes once when they agree; and
   * values whole, in their kind's width.
   */
  static void pack(Tail tail, Kind kind, ByteBuffer out) {
    out.putLong(tail.sequences[0]).putLong(tail.times[0]).putLong(tail.offsets[0]);
    for (int i = 1; i < BLOCK; i++) varint(out, tail.sequences[i] - tail.sequences[i - 1]);
    for (int i = 1; i < BLOCK; i++) varint(out, zigzag(tail.times[i] - tail.times[i - 1]));
    for (int i = 1; i < BLOCK; i++) varint(out, zigzag(tail.offsets[i] - tail.offsets[i - 1]));
    boolean same = true;
    for (int i = 1; i < BLOCK && same; i++) same = tail.sizes[i] == tail.sizes[0];
    out.put((byte) (same ? 1 : 0));
    if (same) out.putInt(tail.sizes[0]);
    else for (int i = 0; i < BLOCK; i++) varint(out, tail.sizes[i]);
    if (kind == null) return;
    for (int i = 0; i < BLOCK; i++) {
      long value = tail.bits[i];
      switch (kind) {
        case F64, I64 -> out.putLong(value);
        case F32 -> out.putInt((int) value);
        case BOOL -> out.put((byte) value);
        case ENUM -> throw new IllegalStateException("An open capture keeps no enum column");
      }
    }
  }

  /**
   * Packs a full leaf tail: the first record whole, then each record's difference from the one
   * before as a varint, and the bits whole in the kind's width; an enum's number takes eight bytes.
   */
  static void packLeaf(LeafTail tail, Kind kind, ByteBuffer out) {
    out.putInt(tail.records[0]);
    for (int i = 1; i < BLOCK; i++) varint(out, tail.records[i] - tail.records[i - 1]);
    for (int i = 0; i < BLOCK; i++) {
      long value = tail.bits[i];
      switch (kind) {
        case F64, I64, ENUM -> out.putLong(value);
        case F32 -> out.putInt((int) value);
        case BOOL -> out.put((byte) value);
      }
    }
  }

  static void unpackLeaf(ByteBuffer in, Kind kind, int[] records, long[] bits) {
    records[0] = in.getInt();
    for (int i = 1; i < BLOCK; i++) records[i] = records[i - 1] + (int) readVarint(in);
    for (int i = 0; i < BLOCK; i++) {
      bits[i] = switch (kind) {
        case F64, I64, ENUM -> in.getLong();
        case F32 -> in.getInt() & 0xFFFF_FFFFL;
        case BOOL -> in.get();
      };
    }
  }

  static void unpack(ByteBuffer in, Kind kind, long[] sequences, long[] times, long[] offsets, int[] sizes, long[] bits) {
    sequences[0] = in.getLong();
    times[0] = in.getLong();
    offsets[0] = in.getLong();
    for (int i = 1; i < BLOCK; i++) sequences[i] = sequences[i - 1] + readVarint(in);
    for (int i = 1; i < BLOCK; i++) times[i] = times[i - 1] + unzigzag(readVarint(in));
    for (int i = 1; i < BLOCK; i++) offsets[i] = offsets[i - 1] + unzigzag(readVarint(in));
    if (in.get() == 1) java.util.Arrays.fill(sizes, in.getInt());
    else for (int i = 0; i < BLOCK; i++) sizes[i] = (int) readVarint(in);
    if (kind == null) return;
    for (int i = 0; i < BLOCK; i++) {
      bits[i] = switch (kind) {
        case F64, I64 -> in.getLong();
        case F32 -> in.getInt() & 0xFFFF_FFFFL;
        case BOOL -> in.get();
        case ENUM -> throw new IllegalStateException("An open capture keeps no enum column");
      };
    }
  }

  private static long zigzag(long value) {
    return (value << 1) ^ (value >> 63);
  }

  private static long unzigzag(long value) {
    return (value >>> 1) ^ -(value & 1);
  }

  private static void varint(ByteBuffer out, long value) {
    while ((value & ~0x7FL) != 0) {
      out.put((byte) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    out.put((byte) value);
  }

  private static long readVarint(ByteBuffer in) {
    long value = 0;
    int shift = 0;
    byte b;
    do {
      b = in.get();
      value |= (long) (b & 0x7F) << shift;
      shift += 7;
    } while (b < 0);
    return value;
  }

  /** Closes and deletes the scratch file; the caller has made sure no reader remains. */
  @Override
  public void close() {
    closed = true;
    var open = channel;
    channel = null;
    if (open != null) {
      try {
        open.close();
      } catch (IOException e) {
        logger.debug("Could not close open capture columns {}: {}", path, e.getMessage());
      }
    }
    if (path != null) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException e) {
        logger.debug("Could not delete open capture columns {}: {}", path, e.getMessage());
      }
    }
  }
}
