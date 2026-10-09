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
import java.util.ArrayList;
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
import org.triplehelix.wpilogmcp.capture.WpilogOutput;
import org.triplehelix.wpilogmcp.log.struct.StructDecodeException;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.log.subsystems.EntryDecoder;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;

/**
 * An index built by the single capture writer, never by rescanning its growing file. A record
 * becomes visible only after the complete write, its per-entry volatile length, and the global
 * publication boundary. Readers take each entry's length on first use, bounded by that boundary,
 * so a call's values, schemas, and time range describe one fixed prefix. Data reads take no locks.
 *
 * <p>The hot window holds wire values. Older records use offsets into read-only mappings beside
 * the write channel. Mappings have atomic reader references: replacing one never unmaps bytes a
 * decoder is using. Eviction retires the mapping after in-flight calls; resumption reuses the
 * writer's index and opens a new mapping, without a scan.
 */
public final class LiveLog implements LogData, AutoCloseable {
  private static final int CHUNK = 1024;
  private static class Record {
    final long sequence, timestampUs;
    final int offset, end;
    volatile Object hot;
    Record(long sequence, long timestampUs, WpilogOutput.Written written, Object hot) {
      this.sequence = sequence; this.timestampUs = timestampUs;
      this.offset = (int) written.offset(); this.end = (int) (written.offset() + written.size());
      this.hot = hot;
    }
    long offset() { return offset; }
    long end() { return end; }
  }
  private static final class WideRecord extends Record {
    final long wideOffset, wideEnd;
    WideRecord(long sequence, long timestampUs, WpilogOutput.Written written, Object hot) {
      super(sequence, timestampUs, written, hot); wideOffset = written.offset(); wideEnd = wideOffset + written.size();
    }
    @Override long offset() { return wideOffset; }
    @Override long end() { return wideEnd; }
  }
  private static final class Series {
    final EntryInfo info;
    volatile Record[][] chunks = new Record[1][];
    volatile int length;
    Series(EntryInfo info) { this.info = info; }
    void append(Record record) {
      int index = length, chunk = index / CHUNK;
      if (chunk == chunks.length) chunks = java.util.Arrays.copyOf(chunks, chunks.length * 2);
      if (chunks[chunk] == null) chunks[chunk] = new Record[CHUNK];
      chunks[chunk][index % CHUNK] = record;
      length = index + 1;
    }
    Record at(int index) { return chunks[index / CHUNK][index % CHUNK]; }
    int bound(long sequence) {
      int low = 0, high = length;
      while (low < high) {
        int mid = (low + high) >>> 1;
        if (at(mid).sequence <= sequence) low = mid + 1; else high = mid;
      }
      return low;
    }
  }
  private record State(Map<String, Series> entries, Map<String, EntryInfo> infos, long sequence, double min, double max,
      int jumps, long firstJump, boolean open) {}
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
  private final long hotWindowUs;
  private final java.util.function.Supplier<Double> robotNowUs;
  private volatile State state = new State(Map.of(), Map.of(), 0, 0, 0, 0, -1, true);
  private final PriorityQueue<Record> hot = new PriorityQueue<>(Comparator.comparingLong(r -> r.timestampUs));
  private final AtomicReference<Mapping> mapping = new AtomicReference<>();
  private final AtomicLong mappedReads = new AtomicLong();
  private final AtomicLong mappings = new AtomicLong();
  private final AtomicInteger users = new AtomicInteger();
  private final AtomicBoolean retired = new AtomicBoolean();
  private final Object lifetime = new Object();
  private long coldBefore = Long.MIN_VALUE;

  public LiveLog(Path path, long hotWindowUs) { this(path, hotWindowUs, () -> null); }

  public LiveLog(Path path, long hotWindowUs, java.util.function.Supplier<Double> robotNowUs) {
    this.robotNowUs = robotNowUs;
    if (hotWindowUs < 0) throw new IllegalArgumentException("Negative hot window");
    this.path = path.toAbsolutePath().normalize(); this.hotWindowUs = hotWindowUs;
  }

  /** Writer thread only. Repeated names follow LogScan's first-declaration/type rule. */
  public void announce(EntryInfo info) {
    var before = state;
    if (before.entries().containsKey(info.name())) return;
    var entries = new LinkedHashMap<>(before.entries()); entries.put(info.name(), new Series(info));
    var infos = new LinkedHashMap<>(before.infos()); infos.put(info.name(), info);
    state = new State(Collections.unmodifiableMap(entries), Collections.unmodifiableMap(infos), before.sequence(), before.min(), before.max(), before.jumps(), before.firstJump(), true);
  }

  /** Publish metadata with the index boundary so an already acquired view keeps its facts. */
  public void metadata(EntryInfo info) {
    var before = state;
    var original = before.infos().get(info.name());
    if (original == null || !original.type().equals(info.type())) return;
    var infos = new LinkedHashMap<>(before.infos());
    infos.put(info.name(), new EntryInfo(original.id(), original.name(), original.type(), info.metadata()));
    state = new State(before.entries(), Collections.unmodifiableMap(infos), before.sequence(),
        before.min(), before.max(), before.jumps(), before.firstJump(), before.open());
  }

  /** Called only after WpilogOutput has written the entire record. */
  public void append(EntryInfo info, ValueFrame frame, WpilogOutput.Written written) throws IOException {
    var before = state; var series = before.entries().get(info.name());
    if (series == null || !series.info.type().equals(info.type())) return;
    double time = frame.timestampUs() / 1_000_000.0;
    if (before.sequence() > 0 && time > before.max() + LogScan.MAX_FORWARD_JUMP_SEC) {
      state = new State(before.entries(), before.infos(), before.sequence(), before.min(), before.max(), before.jumps() + 1,
          before.firstJump() < 0 ? written.offset() : before.firstJump(), true);
      return;
    }
    long sequence = before.sequence() + 1;
    var record = written.offset() + written.size() <= Integer.MAX_VALUE
        ? new Record(sequence, frame.timestampUs(), written, frame.value())
        : new WideRecord(sequence, frame.timestampUs(), written, frame.value());
    series.append(record); hot.add(record);
    double min = sequence == 1 ? time : Math.min(before.min(), time);
    double max = sequence == 1 ? time : Math.max(before.max(), time);
    // Readers can only see complete record slots after this volatile publication.
    state = new State(before.entries(), before.infos(), sequence, min, max, before.jumps(), before.firstJump(), true);
    advance(Math.round(max * 1_000_000));
  }

  /** Values and time sync move the boundary; only the flush tick retires hot objects. */
  public void advance(long serverTimeUs) {
    coldBefore = Math.max(coldBefore, serverTimeUs - hotWindowUs);
  }

  /** One mapping can cover the entire expired batch, even for a zero-length hot window. */
  public void expire() throws IOException {
    while (!hot.isEmpty() && hot.peek().timestampUs <= coldBefore) {
      var cold = hot.peek(); ensureMapped(cold.end());
      cold.hot = null; hot.remove();
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
      var s = state; state = new State(s.entries(), s.infos(), s.sequence(), s.min(), s.max(), s.jumps(), s.firstJump(), false);
    }
  }
  public void resume() throws IOException {
    // Publish a fresh mapping even if an old cache-removal notification is arriving meanwhile.
    var next = new Mapping(path); mappings.incrementAndGet(); Mapping old;
    synchronized (lifetime) {
      old = mapping.getAndSet(next);
      var s = state; state = new State(s.entries(), s.infos(), s.sequence(), s.min(), s.max(), s.jumps(), s.firstJump(), true);
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
    synchronized (lifetime) {
      if (retired.get() && users.get() == 0) old = mapping.getAndSet(null);
    }
    if (old != null) old.release(); // Never unmap or perform I/O while holding a lifetime lock.
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
    private final String seenPath;
    private final Map<String, EntryInfo> entries;
    private final ConcurrentHashMap<String, Integer> lengths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<TimestampedValue>> decoded = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DecodeProblem> problems = new ConcurrentHashMap<>();
    private final StructSchemas schemas;
    private final LogFileAccess.Lease claim;
    private final AtomicBoolean closed = new AtomicBoolean();
    private View(State seen, String seenPath, LogFileAccess.Lease claim) {
      this.seen = seen; this.seenPath = seenPath; this.claim = claim;
      Double now = seen.open() ? robotNowUs.get() : null;
      scopeEnd = now == null ? seen.max() : Math.max(seen.max(), now / 1_000_000.0);
      entries = seen.infos();
      schemas = StructSchemas.fromLog(entries, name -> {
        var series = seen.entries().get(name);
        return sampleCount(name) == 0 ? null : value(series.at(0), series.info.type(), StructSchemas.fallbackOnly());
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
      var series = seen.entries().get(name);
      return series == null ? 0 : lengths.computeIfAbsent(name, key -> series.bound(seen.sequence()));
    }
    @Override public Optional<DecodeProblem> decodeProblem(String name) { values().get(name); return Optional.ofNullable(problems.get(name)); }
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
    private List<TimestampedValue> decode(String name) {
      var series = seen.entries().get(name); int length = sampleCount(name);
      var result = new ArrayList<TimestampedValue>(length); int failed = 0; String reason = null;
      for (int i = 0; i < length; i++) {
        var record = series.at(i);
        try { result.add(new TimestampedValue(record.timestampUs / 1_000_000.0, value(record, series.info.type(), schemas))); }
        catch (StructDecodeException e) { failed++; if (reason == null) reason = e.getMessage(); }
      }
      if (failed > 0) problems.put(name, new DecodeProblem(reason, failed, length));
      return Collections.unmodifiableList(result);
    }
    @Override public void close() {
      if (claim != null && closed.compareAndSet(false, true)) { try { claim.close(); } finally { releaseUse(); } }
    }
  }

  private Object value(Record record, String type, StructSchemas schemas) {
    var cached = record.hot;
    if (cached != null) return EntryDecoder.decodeNetworkValue(cached, type, schemas);
    while (true) {
      var held = mapping.get();
      if (held == null) throw new IllegalStateException("Live log mapping is retired: " + path);
      if (!held.retain()) continue;
      try { mappedReads.incrementAndGet(); return EntryDecoder.decodeValue(DataLogAccess.getRecord(held.reader.reader(), record.offset()), type, schemas); }
      finally { held.release(); }
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
    var seen = state; var series = seen.entries().get(name);
    return series == null ? 0 : series.bound(seen.sequence());
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
  long mappingCount() { return mappings.get(); }
  public long hotRecordCount() { return hot.size(); } // Writer-thread diagnostic for deterministic tests.
}
