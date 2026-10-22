/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import edu.wpi.first.util.datalog.DataLogAccess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.log.struct.StructDecodeException;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.log.subsystems.EntryDecoder;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.read.LegacyMaterializer;
import org.triplehelix.wpilogmcp.read.RecordSeries;
import org.triplehelix.wpilogmcp.read.SampleCursors;

/**
 * An index built by the single capture writer, never by rescanning its growing file. A record
 * becomes visible only after the complete write, its per-entry volatile length, and the global
 * publication boundary. Readers take each entry's length on first use, bounded by that boundary,
 * so a call's values, schemas, and time range describe one fixed prefix. Data reads take no locks.
 *
 * <p>The index keeps each entry's records as columns outside the heap ({@link OpenColumns}): every
 * record's facts, and a number's value, so the heap no longer grows with the session. A number is
 * read from its column when its record's payload has its type's width, as every record the writer
 * encodes has; any other record keeps its wire value while it is hot, and older ones are read from
 * read-only mappings beside the write channel, so a malformed record is still reported where it
 * is. Mappings have atomic reader references: replacing one never unmaps bytes a decoder is using.
 * Eviction retires the mapping after in-flight calls; resumption reuses the writer's index and
 * opens a new mapping, without a scan. The columns' scratch file is deleted once the writer has
 * moved on and the last reader has finished.
 */
public final class LiveLog implements LogData, AutoCloseable {
  private static final int HOT_CHUNK = 1024;

  /**
   * The wire values of an entry's records that are read from the WPILOG, while they are hot. A
   * chunk none of whose values is still hot is dropped, so the heap holds only the hot window.
   */
  private static final class HotValues {
    private volatile AtomicReferenceArray<AtomicReferenceArray<Object>> chunks = new AtomicReferenceArray<>(4);
    private int[] held = new int[4];

    /** Writer thread only. */
    void put(int index, Object value) {
      int c = index / HOT_CHUNK;
      var outer = chunks;
      if (c >= outer.length()) {
        var grown = new AtomicReferenceArray<AtomicReferenceArray<Object>>(Math.max(c + 1, outer.length() * 2));
        for (int i = 0; i < outer.length(); i++) grown.set(i, outer.get(i));
        chunks = outer = grown;
        held = java.util.Arrays.copyOf(held, grown.length());
      }
      var chunk = outer.get(c);
      if (chunk == null) {
        chunk = new AtomicReferenceArray<>(HOT_CHUNK);
        outer.set(c, chunk);
      }
      chunk.set(index % HOT_CHUNK, value);
      held[c]++;
    }

    Object get(int index) {
      int c = index / HOT_CHUNK;
      var outer = chunks;
      if (c >= outer.length()) return null;
      var chunk = outer.get(c);
      return chunk == null ? null : chunk.get(index % HOT_CHUNK);
    }

    /** Writer thread only, once the mapping covers the record: its value is read from there. */
    void clear(int index) {
      int c = index / HOT_CHUNK;
      var outer = chunks;
      var chunk = outer.get(c);
      chunk.set(index % HOT_CHUNK, null);
      if (--held[c] == 0) outer.set(c, null);
    }
  }

  /**
   * One clock entry's valid readings, shared by every view of this recording and extended by
   * whichever reads first past them, so no view reads the clock entry from its start again.
   */
  private static final class Clock {
    private final WallClock.ValidReadings readings = new WallClock.ValidReadings();
    private org.triplehelix.wpilogmcp.read.UtcTimeMapping last;
    private int lastLength = -1;

    /**
     * The mapping of the entry's first {@code length} values, in log order; null when a jump
     * after them began a new run and only a fresh read can give theirs.
     */
    org.triplehelix.wpilogmcp.read.UtcTimeMapping mapping(String entry, String clock, List<TimestampedValue> values, int length) {
      int start;
      synchronized (this) {
        if (length == lastLength) return last;
        start = readings.values();
      }
      // Read outside the lock: a column's blocks may come from the scratch file.
      var added = new TimestampedValue[Math.max(0, length - start)];
      for (int i = 0; i < added.length; i++) added[i] = values.get(start + i);
      WallClock.Prefix prefix;
      synchronized (this) {
        for (int i = readings.values() - start; i < added.length; i++) readings.add(added[i]);
        prefix = readings.prefixOf(length).orElse(null);
      }
      if (prefix == null) return null;
      var mapping = org.triplehelix.wpilogmcp.read.UtcTimeMapping.of(entry, clock, prefix);
      synchronized (this) {
        if (length > lastLength) {
          last = mapping;
          lastLength = length;
        }
      }
      return mapping;
    }
  }
  private final java.util.concurrent.ConcurrentHashMap<String, Clock> clocks = new java.util.concurrent.ConcurrentHashMap<>();

  /** One entry: its columns, and the hot values of its records that are read from the WPILOG. */
  private static final class Entry {
    final OpenColumns.Series columns;
    final HotValues hot = new HotValues();
    /** The payload size of a number its column holds, or -1 when it holds none. */
    final int width;
    /** The first record whose value is not its column's; written by the writer. */
    volatile int firstOther = Integer.MAX_VALUE;
    /**
     * Every such record, in order, below {@code otherCount}. The list doubles as it fills, since a
     * client that sends integers on a double topic makes every record of a session one of them.
     */
    private volatile int[] others = new int[0];
    private volatile int otherCount;
    /** How many times the writer copied the list to grow it, which a test counts. */
    int otherGrowths;

    Entry(OpenColumns.Series columns) {
      this.columns = columns;
      width = columns.kind == null ? -1 : switch (columns.kind) {
        case F64, I64 -> 8;
        case F32 -> 4;
        case BOOL -> 1;
        case ENUM -> -1;
      };
    }

    EntryInfo info() { return columns.info; }

    /** Writer thread only, before the record is published: its value is read from the WPILOG. */
    void other(int index) {
      var list = others;
      int count = otherCount;
      if (count == list.length) {
        list = java.util.Arrays.copyOf(list, Math.max(8, 2 * list.length));
        otherGrowths++;
        others = list;
      }
      list[count] = index;
      // Published after the index is in the list a reader then reads.
      otherCount = count + 1;
      if (firstOther == Integer.MAX_VALUE) firstOther = index;
    }

    /** Whether a record's value is its column's: a number the writer recorded in its type's width. */
    boolean held(int index) {
      if (width < 0) return false;
      if (index < firstOther) return true;
      // The count first: a list read after it holds every index the count covers.
      int count = otherCount;
      return java.util.Arrays.binarySearch(others, 0, count, index) < 0;
    }
  }

  /** A record read from the WPILOG, while its wire value is hot; {@code end} is where it ends. */
  private record Hot(Entry entry, int index, long timestampUs, long end) {}

  private record State(Map<String, Entry> entries, Map<String, EntryInfo> infos, long sequence, double min, double max,
      int jumps, long firstJump, boolean open, long revision) {}
  public record Publication(String writerId, long revision) {}
  private static final class Mapping {
    final ScopedLogReader reader;
    final long size;
    final AtomicInteger references = new AtomicInteger(1);
    Mapping(Path path) throws IOException {
      reader = new ScopedLogReader(path); size = DataLogAccess.size(reader.reader());
    }
    boolean retain() {
      int refs;
      do { refs = references.get(); if (refs == 0) return false; }
      while (!references.compareAndSet(refs, refs + 1));
      return true;
    }
    void release() {
      if (references.decrementAndGet() == 0) {
        try { reader.close(); } catch (IOException e) { throw new UncheckedIOException(e); }
      }
    }
  }

  private volatile Path path;
  private final String captureIdentity;
  private final long hotWindowUs;
  private final java.util.function.Supplier<Double> robotNowUs;
  private volatile State state = new State(Map.of(), Map.of(), 0, 0, 0, 0, -1, true, 0);
  private final PriorityQueue<Hot> hot = new PriorityQueue<>(Comparator.comparingLong(Hot::timestampUs));
  private final OpenColumns columns;
  private volatile boolean discarded;
  private final AtomicBoolean columnsClosed = new AtomicBoolean();
  private final AtomicReference<Mapping> mapping = new AtomicReference<>();
  private final AtomicLong mappedReads = new AtomicLong();
  private final AtomicLong mappings = new AtomicLong();
  private final AtomicInteger users = new AtomicInteger();
  private final AtomicBoolean retired = new AtomicBoolean();
  private final Object lifetime = new Object();
  private long coldBefore = Long.MIN_VALUE;

  /** A small immutable token cannot retain the index after a follower releases its prefix.
   * Followers allocate and compare it off the writer loop; publication only advances the
   * revision in the state replacement the writer already performs. */
  public Publication publication() { return new Publication(captureIdentity, state.revision()); }

  public LiveLog(Path path, long hotWindowUs) { this(path, hotWindowUs, () -> null); }

  public LiveLog(Path path, long hotWindowUs, java.util.function.Supplier<Double> robotNowUs) {
    this(path, hotWindowUs, robotNowUs, java.util.UUID.randomUUID().toString());
  }
  /** CaptureWriter supplies the same incarnation to this index and its durable provenance. */
  public LiveLog(Path path, long hotWindowUs, java.util.function.Supplier<Double> robotNowUs, String captureIdentity) {
    this(path, hotWindowUs, robotNowUs, captureIdentity, OpenColumns.Placement.HEAP);
  }
  /** As above, with the columns' blocks written where {@code placement} says. */
  public LiveLog(Path path, long hotWindowUs, java.util.function.Supplier<Double> robotNowUs, String captureIdentity,
      OpenColumns.Placement placement) {
    this.captureIdentity = java.util.Objects.requireNonNull(captureIdentity);
    this.robotNowUs = robotNowUs;
    if (hotWindowUs < 0) throw new IllegalArgumentException("Negative hot window");
    this.path = path.toAbsolutePath().normalize(); this.hotWindowUs = hotWindowUs;
    this.columns = new OpenColumns(placement, captureIdentity);
  }

  /** Why the columns' blocks stay in the heap, when they do. */
  public Optional<String> columnsInHeap() {
    return Optional.ofNullable(columns.heapReason());
  }

  /** Writer thread only. Repeated names follow LogScan's first-declaration/type rule. */
  public void announce(EntryInfo info) {
    var before = state;
    if (before.entries().containsKey(info.name())) return;
    var entries = new LinkedHashMap<>(before.entries()); entries.put(info.name(), new Entry(columns.new Series(info)));
    var infos = new LinkedHashMap<>(before.infos()); infos.put(info.name(), info);
    state = new State(Collections.unmodifiableMap(entries), Collections.unmodifiableMap(infos), before.sequence(), before.min(), before.max(), before.jumps(), before.firstJump(), true, before.revision() + 1);
  }

  /** Publish metadata with the index boundary so an already acquired view keeps its facts. */
  public void metadata(EntryInfo info) {
    var before = state;
    var original = before.infos().get(info.name());
    if (original == null || !original.type().equals(info.type())) return;
    var infos = new LinkedHashMap<>(before.infos());
    infos.put(info.name(), new EntryInfo(original.id(), original.name(), original.type(), info.metadata()));
    state = new State(before.entries(), Collections.unmodifiableMap(infos), before.sequence(),
        before.min(), before.max(), before.jumps(), before.firstJump(), before.open(), before.revision() + 1);
  }

  /** Called only after WpilogOutput has written the entire record. */
  public void append(EntryInfo info, ValueFrame frame, WpilogOutput.Written written) throws IOException {
    var before = state; var entry = before.entries().get(info.name());
    if (entry == null || !entry.info().type().equals(info.type())) return;
    double time = frame.timestampUs() / 1_000_000.0;
    if (before.sequence() > 0 && time > before.max() + LogScan.MAX_FORWARD_JUMP_SEC) {
      state = new State(before.entries(), before.infos(), before.sequence(), before.min(), before.max(), before.jumps() + 1,
          before.firstJump() < 0 ? written.offset() : before.firstJump(), true, before.revision() + 1);
      return;
    }
    long sequence = before.sequence() + 1;
    var kind = entry.columns.kind;
    // A number keeps its value in the column only when the record holds it in its type's width,
    // as every record the capture encodes does; any other record is read from the WPILOG.
    boolean held = entry.width >= 0 && written.payloadSize() == entry.width && OpenColumns.holds(kind, frame.value());
    int index = entry.columns.length();
    if (!held) {
      if (entry.width >= 0) entry.other(index);
      entry.hot.put(index, frame.value());
      hot.add(new Hot(entry, index, frame.timestampUs(), written.offset() + written.size()));
    }
    entry.columns.append(sequence, frame.timestampUs(), written.offset(), written.payloadSize(),
        held ? OpenColumns.bitsOf(kind, frame.value()) : 0);
    double min = sequence == 1 ? time : Math.min(before.min(), time);
    double max = sequence == 1 ? time : Math.max(before.max(), time);
    // Readers can only see complete record slots after this volatile publication.
    state = new State(before.entries(), before.infos(), sequence, min, max, before.jumps(), before.firstJump(), true, before.revision() + 1);
    advance(Math.round(max * 1_000_000));
  }

  /** Values and time sync move the boundary; only the flush tick retires hot objects. */
  public void advance(long serverTimeUs) {
    coldBefore = Math.max(coldBefore, serverTimeUs - hotWindowUs);
  }

  /** One mapping can cover the entire expired batch, even for a zero-length hot window. */
  public void expire() throws IOException {
    while (!hot.isEmpty() && hot.peek().timestampUs() <= coldBefore) {
      var cold = hot.peek(); ensureMapped(cold.end());
      cold.entry().hot.clear(cold.index()); hot.remove();
    }
  }

  private void ensureMapped(long end) throws IOException {
    var current = mapping.get();
    if (current != null && current.size >= end) return;
    var next = new Mapping(path); mappings.incrementAndGet();
    if (next.size < end) { next.release(); throw new IOException("Capture record not completely written"); }
    var old = mapping.getAndSet(next); if (old != null) old.release();
  }

  public void finish() {
    synchronized (lifetime) {
      var s = state; state = new State(s.entries(), s.infos(), s.sequence(), s.min(), s.max(), s.jumps(), s.firstJump(), false, s.revision() + 1);
    }
  }
  public void resume() throws IOException {
    // Publish a fresh mapping even if an old cache-removal notification is arriving meanwhile.
    var next = new Mapping(path); mappings.incrementAndGet(); Mapping old;
    synchronized (lifetime) {
      old = mapping.getAndSet(next);
      var s = state; state = new State(s.entries(), s.infos(), s.sequence(), s.min(), s.max(), s.jumps(), s.firstJump(), true, s.revision() + 1);
      retired.set(false);
    }
    if (old != null) old.release();
  }
  public void relocate(Path destination) throws IOException {
    path = destination.toAbsolutePath().normalize();
    if (mapping.get() != null) {
      var next = new Mapping(path); mappings.incrementAndGet(); var old = mapping.getAndSet(next); if (old != null) old.release();
    }
  }

  /** A call retains the index/mapping lifetime, independently of cache eviction. */
  View retainView() throws IOException {
    if (retired.get()) return null;
    users.incrementAndGet();
    if (retired.get()) { releaseUse(); return null; }
    LogFileAccess.Lease claim = null;
    try {
      claim = LogFileAccess.read(path);
      return new View(state, path.toString(), claim);
    } catch (IOException | RuntimeException e) {
      if (claim != null) claim.close();
      releaseUse(); throw e;
    }
  }
  private void releaseUse() {
    if (users.decrementAndGet() == 0) retireMapping();
  }
  private void retireMapping() {
    Mapping old = null;
    boolean closeColumns;
    synchronized (lifetime) {
      boolean idle = retired.get() && users.get() == 0;
      if (idle) old = mapping.getAndSet(null);
      closeColumns = idle && discarded;
    }
    if (old != null) old.release(); // Never unmap or perform I/O while holding a lifetime lock.
    if (closeColumns && columnsClosed.compareAndSet(false, true)) columns.close();
  }

  /**
   * The writer has moved on to another index, so this one is never resumed: its columns' scratch
   * file is deleted once it is retired and its last reader has finished.
   */
  public void discard() {
    discarded = true;
    retireMapping();
  }
  @Override public void close() {
    synchronized (lifetime) {
      if (state.open()) return; // A late eviction from a previous connection cannot retire its successor.
      retired.set(true);
    }
    retireMapping();
  }

  /** Call-scoped, immutable boundary; its per-entry decoding cache never mixes two prefixes. */
  public final class View implements LogData, AutoCloseable {
    LiveLog source() { return LiveLog.this; }
    private final State seen;
    private final double scopeEnd;
    private final double robotTimeEstimateSec;
    private final String seenPath;
    private final Map<String, EntryInfo> entries;
    private final ConcurrentHashMap<String, Integer> lengths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<TimestampedValue>> decoded = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DecodeProblem> problems = new ConcurrentHashMap<>();
    private final StructSchemas schemas;
    private final LogFileAccess.Lease claim;
    private final AtomicBoolean closed = new AtomicBoolean();
    private record ReadIdentity(LiveLog owner, long sequence) {}
    /** Equal only for views of this writer's same immutable publication prefix. */
    public Object readIdentity() { return new ReadIdentity(LiveLog.this, seen.sequence()); }
    /** An index replacement cannot impersonate a retained writer merely by reusing its path. */
    public String captureIdentity() { return captureIdentity; }

    /** A fixed call observes the shared NT4 estimate once. Absence is distinct from the last
     * sample: callers must not describe a sample timestamp as evidence of time synchronization. */
    public java.util.OptionalDouble robotTimeEstimateSec() {
      return Double.isFinite(robotTimeEstimateSec) ? java.util.OptionalDouble.of(robotTimeEstimateSec) : java.util.OptionalDouble.empty();
    }

    private View(State seen, String seenPath, LogFileAccess.Lease claim) {
      this.seen = seen; this.seenPath = seenPath; this.claim = claim;
      Double now = seen.open() ? robotNowUs.get() : null;
      robotTimeEstimateSec = now != null && Double.isFinite(now) ? now / 1_000_000.0 : Double.NaN;
      scopeEnd = now == null ? seen.max() : Math.max(seen.max(), now / 1_000_000.0);
      entries = seen.infos();
      schemas = StructSchemas.fromLog(entries, name -> {
        var entry = seen.entries().get(name);
        int length = sampleCount(name);
        return length == 0 ? null : value(entry, entry.columns.reader(length), 0, StructSchemas.fallbackOnly(), false);
      });
    }
    @Override public String path() { return seenPath; }
    @Override public Map<String, EntryInfo> entries() { return entries; }
    @Override public double minTimestamp() { return seen.min(); }
    @Override public double maxTimestamp() { return seen.max(); }
    @Override public double timeScopeEnd() { return scopeEnd; }
    @Override public boolean truncated() { return seen.jumps() > 0; }
    @Override public boolean damaged() { return truncated(); }
    @Override public String truncationMessage() {
      return truncated() ? LogScan.jumpMessage(seen.jumps(), seen.firstJump()) + " "
          + String.format("Data from %.2f to %.2f s was recovered.", seen.min(), seen.max()) : null;
    }
    @Override public Optional<SessionTimeRange> sessionTimeRange() {
      return seen.open() ? Optional.of(new SessionTimeRange(seen.min(), seen.max())) : Optional.empty();
    }
    @Override public StructSchemas structSchemas() { return schemas; }
    @Override public int sampleCount(String name) {
      var entry = seen.entries().get(name);
      return entry == null ? 0 : lengths.computeIfAbsent(name, key -> entry.columns.bound(seen.sequence()));
    }

    /**
     * A number's samples from its column, in time order, when every record of this prefix keeps
     * its value there; empty otherwise, and the caller decodes the records, which sorts them.
     */
    @Override public Optional<NumericColumn> numericColumn(String name, org.triplehelix.wpilogmcp.read.FieldPath path) {
      var entry = seen.entries().get(name);
      if (entry == null || !path.isRoot() || entry.width < 0) return Optional.empty();
      int length = sampleCount(name);
      if (entry.firstOther < length || !entry.columns.monotonic(length)) return Optional.empty();
      var kind = entry.columns.kind;
      var reader = entry.columns.reader(length);
      var samples = new org.triplehelix.wpilogmcp.read.NumericSamples() {
        @Override public int size() { return length; }
        @Override public long timeUs(int index) { return reader.timeUs(index); }
        /** As {@code TimestampedValue.atMicros} computes a decoded value's seconds. */
        @Override public double seconds(int index) { return reader.timeUs(index) / 1_000_000.0; }
        @Override public double number(int index) { return kind.number(reader.bits(index)); }
        /** An integer's own bits, as {@code Long.equals} compares the decoded values. */
        @Override public long valueBits(int index) {
          return kind == org.triplehelix.wpilogmcp.columns.ColumnFileFormat.Kind.I64 ? reader.bits(index)
              : Double.doubleToLongBits(number(index));
        }
      };
      final class Values extends java.util.AbstractList<TimestampedValue> implements java.util.RandomAccess {
        @Override public TimestampedValue get(int index) {
          return TimestampedValue.atMicros(reader.timeUs(index), kind.value(reader.bits(index)));
        }
        @Override public int size() { return length; }
      }
      return Optional.of(new NumericColumn(samples, length, 0, new Values()));
    }
    /** A number whose column holds every record of this prefix decoded each as its type; anything else is decoded to find out. */
    @Override public Optional<DecodeProblem> decodeProblem(String name) {
      var entry = seen.entries().get(name);
      if (entry != null && entry.width >= 0 && entry.firstOther >= sampleCount(name)) return Optional.empty();
      values().get(name);
      return Optional.ofNullable(problems.get(name));
    }

    /**
     * The recording's clock readings up to this prefix: a clock whose column holds every record is
     * extended from where the recording's readings stopped; any other is read whole, as a loaded
     * log reads it.
     */
    @Override public org.triplehelix.wpilogmcp.read.UtcTimeMapping utcMapping() {
      return org.triplehelix.wpilogmcp.read.UtcTimeMapping.choose(this, (name, clock) -> {
        var column = numericColumn(name, org.triplehelix.wpilogmcp.read.FieldPath.ROOT);
        if (column.isPresent() && column.get().values() != null) {
          var mapping = clocks.computeIfAbsent(name, key -> new Clock())
              .mapping(name, clock, column.get().values(), sampleCount(name));
          if (mapping != null) return mapping;
        }
        return org.triplehelix.wpilogmcp.read.UtcTimeMapping.of(name, clock, values().get(name));
      });
    }
    @Override public Map<String, List<TimestampedValue>> values() {
      return new AbstractMap<>() {
        @Override public boolean containsKey(Object key) { return entries.containsKey(key); }
        @Override public Set<String> keySet() { return entries.keySet(); }
        @Override public int size() { return entries.size(); }
        @Override public List<TimestampedValue> get(Object key) {
          return key instanceof String name && entries.containsKey(name) ? decoded.computeIfAbsent(name, View.this::decode) : null;
        }
        @Override public Set<Entry<String, List<TimestampedValue>>> entrySet() {
          return new java.util.AbstractSet<>() {
            @Override public int size() { return entries.size(); }
            @Override public java.util.Iterator<Entry<String, List<TimestampedValue>>> iterator() {
              return entries.keySet().stream().<Entry<String, List<TimestampedValue>>>map(name -> new Entry<>() {
                @Override public String getKey() { return name; }
                @Override public List<TimestampedValue> getValue() { return View.this.values().get(name); }
                @Override public List<TimestampedValue> setValue(List<TimestampedValue> value) { throw new UnsupportedOperationException(); }
              }).iterator();
            }
          };
        }
      };
    }
    /**
     * The heap this view's prefix holds in the index, excluding hot value objects: each entry's
     * filling tail, its blocks kept in the heap, and a reference to each block.
     */
    public long indexBytes() {
      long bytes = 0;
      for (var entry : seen.entries().entrySet()) bytes += entry.getValue().columns.heapBytes(sampleCount(entry.getKey()));
      return bytes;
    }
    /** File-order access to this immutable publication prefix; the caller retains this view.
     * Exact source values preserve opaque bytes; legacy materialization keeps its presentation. */
    public RecordSeries records(String name, boolean sourceValues) {
      var held = seen.entries().get(name);
      if (held == null) throw new IllegalArgumentException("Entry not found: " + name);
      int length = sampleCount(name);
      var reader = held.columns.reader(length);
      return new RecordSeries() {
        @Override public int size() { return length; }
        @Override public long timestampUs(int index) { return reader.timeUs(index); }
        @Override public long offset(int index) { return reader.offset(index); }
        @Override public long payloadBytes(int index) { return reader.payloadBytes(index); }
        @Override public boolean monotonic() { return held.columns.monotonic(length); }
        @Override public Object decode(int index) {
          return value(held, reader, index, schemas, sourceValues);
        }
        @Override public void readRange(int start, int end, String entry, SampleCursors.Sink sink) {
          for (int index = start; index < end; index++) {
            long offset = reader.offset(index);
            Object decoded;
            try {
              decoded = value(held, reader, index, schemas, sourceValues);
            } catch (RuntimeException failure) {
              reportFailure(index, offset, entry, failure, sink);
              continue;
            }
            sink.sample(reader.timeUs(index), offset, index, decoded);
          }
        }
        @Override public String problemMessage(int index, Exception failure) {
          if (failure instanceof RecordedDecodeFailure) return failure.getMessage();
          // The live path has always counted only schema failures. An invalid hot value or
          // retired mapping remains an exception, rather than silently becoming missing data.
          if (failure instanceof StructDecodeException) return failure.getMessage();
          if (failure instanceof RuntimeException runtime) throw runtime;
          throw new IllegalStateException(failure);
        }
      };
    }
    private List<TimestampedValue> decode(String name) {
      var result = LegacyMaterializer.read(name, records(name, false));
      if (result.problem() != null) problems.put(name, result.problem());
      return result.values();
    }
    @Override public void close() {
      if (claim != null && closed.compareAndSet(false, true)) { try { claim.close(); } finally { releaseUse(); } }
    }
  }

  /** Cold native reads report bad payload bytes at their ordinal. Wrapping only the decoder
   * preserves failures in mapping lifetime and invalid internal hot values as real exceptions. */
  private static final class RecordedDecodeFailure extends RuntimeException {
    RecordedDecodeFailure(String message, RuntimeException cause) { super(message, cause); }
  }
  /**
   * A record's value: a number from its column, as the decode gives it; any other record from its
   * hot wire value, or from the WPILOG once the mapping covers it.
   */
  private Object value(Entry entry, OpenColumns.Reader reader, int index, StructSchemas schemas, boolean sourceValues) {
    if (entry.held(index)) return entry.columns.kind.value(reader.bits(index));
    var type = entry.info().type();
    var cached = entry.hot.get(index);
    if (cached != null) return sourceValues ? EntryDecoder.decodeNetworkRecordedValue(cached, type, schemas)
        : EntryDecoder.decodeNetworkValue(cached, type, schemas);
    long offset = reader.offset(index);
    while (true) {
      var held = mapping.get();
      if (held == null) throw new IllegalStateException("Live log mapping is retired: " + path);
      if (!held.retain()) continue;
      try {
        mappedReads.incrementAndGet();
        var data = DataLogAccess.getRecord(held.reader.reader(), offset);
        if (!sourceValues) return EntryDecoder.decodeValue(data, type, schemas);
        try { return EntryDecoder.decodeRecordedValue(data, type, schemas); }
        catch (RuntimeException failure) {
          throw new RecordedDecodeFailure(EntryDecoder.malformedMessage(data, type, failure), failure);
        }
      } finally { held.release(); }
    }
  }
  private View current() { return new View(state, path.toString(), null); }
  @Override public String path() { return path.toString(); }
  @Override public Map<String, EntryInfo> entries() {
    return state.infos();
  }
  @Override public Map<String, List<TimestampedValue>> values() { return current().values(); }
  @Override public double minTimestamp() { return state.min(); }
  @Override public double maxTimestamp() { return state.max(); }
  @Override public int sampleCount(String name) {
    var seen = state; var entry = seen.entries().get(name);
    return entry == null ? 0 : entry.columns.bound(seen.sequence());
  }
  @Override public boolean truncated() { return state.jumps() > 0; }
  @Override public boolean damaged() { return truncated(); }
  @Override public String truncationMessage() {
    var seen = state;
    return seen.jumps() == 0 ? null : LogScan.jumpMessage(seen.jumps(), seen.firstJump()) + " "
        + String.format("Data from %.2f to %.2f s was recovered.", seen.min(), seen.max());
  }
  @Override public StructSchemas structSchemas() { return current().structSchemas(); }
  long mappedReadCount() { return mappedReads.get(); }
  /** Column blocks readers have decoded, which tests count to show a read costs what its window holds. */
  public long columnBlocksRead() { return columns.blocksRead(); }
  int otherListGrowths(String name) {
    var entry = state.entries().get(name);
    return entry == null ? 0 : entry.otherGrowths;
  }
  long mappingCount() { return mappings.get(); }
  public long hotRecordCount() { return hot.size(); } // Writer-thread diagnostic for deterministic tests.
}
