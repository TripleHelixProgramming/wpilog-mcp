/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.wpi.first.util.datalog.DataLogAccess;
import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;
import org.triplehelix.wpilogmcp.log.struct.StructDecodeException;
import org.triplehelix.wpilogmcp.log.struct.StructMap;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.log.subsystems.EntryDecoder;

/**
 * Lazily-loaded wpilog data backed by a memory-mapped file and Caffeine cache.
 *
 * <p>Built from a single scan of the file. The scan records entry metadata and byte offsets
 * for each data record (4 bytes per ordinary record; 8 when a wide address requires it). Values are decoded on demand via
 * random access to the windowed byte source when tools access specific entries.
 *
 * <p>Decoded values are cached in a Caffeine weight-based LRU cache. If an entry is evicted
 * under memory pressure, re-decoding uses the stored byte offsets for direct access — no
 * full file re-scan needed.
 *
 * <p>Struct values are decoded by the log's own schemas ({@code /.schema/struct:*} entries, read
 * once after the scan). Records that cannot be decoded are counted per entry and reported through
 * {@link #decodeProblem(String)}.
 *
 * @since 0.8.0
 */
public class LazyParsedLog implements LogData, AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(LazyParsedLog.class);

  private final String path;
  private final Map<String, EntryInfo> entries;
  private final double minTimestamp;
  private final double maxTimestamp;
  private final boolean truncated;
  private final boolean damaged;
  private final String truncationMessage;

  // Long addresses, retaining int-backed storage for ordinary files.
  private final Map<String, RecordOffsets> recordOffsets;

  private final LogReader reader;
  private final LogScan scan;
  LogScan scan() { return scan; }
  private final StructSchemas structSchemas;
  private final Map<String, DecodeProblem> decodeProblems = new ConcurrentHashMap<>();
  /** Entries decoded at least once, whose decode problems (if any) are therefore known. */
  private final Set<String> decodedOnce = ConcurrentHashMap.newKeySet();
  private final Cache<String, List<TimestampedValue>> valueCache;
  private final LazyValuesMap valuesView;
  private volatile boolean closed = false;
  private ScopedLogReader ownedReader;
  private int holders;
  private boolean unmapping;
  private volatile boolean unmapped;

  /**
   * The manager owns this mapping until eviction and the last in-flight use have both ended.
   * Construction failures close it too: a failed scan must not keep a Windows file immovable.
   */
  static LazyParsedLog open(Path path, long maxCacheWeightBytes) throws IOException {
    return open(path, maxCacheWeightBytes, null);
  }

  static LazyParsedLog open(Path path, long maxCacheWeightBytes, LogScan previous) throws IOException {
    var reader = new ScopedLogReader(path);
    try {
      var log = new LazyParsedLog(path.toString(), reader.reader(), maxCacheWeightBytes, previous);
      log.ownedReader = reader;
      return log;
    } catch (Throwable e) {
      try {
        reader.close();
      } catch (IOException close) {
        e.addSuppressed(close);
      }
      throw e;
    }
  }

  /** Retaining and retiring are one decision, so an evicted mapping cannot acquire a new user. */
  synchronized boolean retain() {
    if (closed && ownedReader != null) return false;
    holders++;
    return true;
  }

  void releaseUse() {
    boolean release;
    synchronized (this) {
      if (--holders < 0) throw new IllegalStateException("Log use released twice");
      release = claimUnmap();
    }
    if (release) unmap();
  }

  private boolean claimUnmap() {
    if (!closed || holders != 0 || ownedReader == null || unmapping) return false;
    unmapping = true;
    return true;
  }

  private void unmap() {
    unmapped = true;
    try {
      ownedReader.close();
    } catch (IOException e) {
      // Its file-access claim stays held on failure, so an import reports it instead of moving.
      logger.warn("Could not release log mapping for {}: {}", path, e.getMessage());
    }
  }

  /**
   * Creates a LazyParsedLog by scanning the file once.
   *
   * <p>The scan builds entry metadata and records byte offsets for each data record.
   * No values are decoded during construction. The ByteBuffer from the DataLogReader's
   * memory-mapped file is used for subsequent random-access decoding.
   *
   * @param path The file path
   * @param reader The DataLogReader (memory-mapped)
   * @param maxCacheWeightBytes Maximum total weight of cached decoded values in bytes
   * @throws IOException if the reader is invalid
   */
  public LazyParsedLog(String path, DataLogReader reader, long maxCacheWeightBytes)
      throws IOException {
    this(path, LogReader.of(reader), maxCacheWeightBytes);
  }

  public LazyParsedLog(String path, LogReader reader, long maxCacheWeightBytes) throws IOException {
    this(path, reader, maxCacheWeightBytes, null);
  }

  private LazyParsedLog(String path, LogReader reader, long maxCacheWeightBytes, LogScan previous) throws IOException {
    if (!reader.isValid()) {
      throw LogFileException.invalid(Path.of(path));
    }

    this.path = path;
    this.reader = reader;

    // One pass over the records (shared with LogParser): entries in declaration order, each
    // data record's byte offset for random-access decoding later, and where the file stops being
    // a valid log (a damaged tail is not read; see LogScan)
    logger.debug("Scanning log: {}", path);
    long startTime = System.nanoTime();
    this.scan = previous == null ? LogScan.of(reader, Path.of(path)) : LogScan.resume(previous, reader, Path.of(path));
    var entriesByName = scan.entries();
    var offsetLists = scan.offsets();
    int totalDataRecords = scan.dataRecords();

    this.entries = entriesByName;
    this.minTimestamp = scan.minTimestamp();
    this.maxTimestamp = scan.maxTimestamp();
    this.truncated = scan.truncated();
    this.damaged = scan.damaged();
    this.truncationMessage = scan.truncationMessage();

    // Compact the arrays, retaining four-byte storage for ordinary files
    this.recordOffsets = new HashMap<>();
    for (var entry : offsetLists.entrySet()) {
      recordOffsets.put(entry.getKey(), entry.getValue().compact());
    }

    // Spot-check: validate a few random offsets to ensure entry IDs match.
    // Pick up to 3 records (first, middle, last) from a non-empty entry.
    for (var offsetEntry : recordOffsets.entrySet()) {
      var offsets = offsetEntry.getValue();
      if (offsets.size() == 0) continue;
      var expectedInfo = entriesByName.get(offsetEntry.getKey());
      if (expectedInfo == null) continue;

      int[] sampleIndices = offsets.size() == 1
          ? new int[]{0}
          : offsets.size() == 2
              ? new int[]{0, offsets.size() - 1}
              : new int[]{0, offsets.size() / 2, offsets.size() - 1};
      for (int idx : sampleIndices) {
        try {
          var record = DataLogAccess.getRecord(reader, offsets.get(idx));
          if (record.getEntry() != expectedInfo.id()) {
            logger.warn("Offset validation mismatch for '{}': expected entry ID {} but found {} at offset {}",
                offsetEntry.getKey(), expectedInfo.id(), record.getEntry(), offsets.get(idx));
          }
        } catch (Exception e) {
          logger.warn("Offset validation failed for '{}' at offset {}: {}",
              offsetEntry.getKey(), offsets.get(idx), e.getMessage());
        }
      }
      break; // Only spot-check one entry to keep startup fast
    }

    // Struct schemas: the first record of each /.schema/struct: entry
    this.structSchemas = StructSchemas.fromLog(entries, name -> {
      var offsets = recordOffsets.get(name);
      if (offsets == null || offsets.size() == 0) return null;
      try {
        return DataLogAccess.getRecord(reader, offsets.get(0)).getString();
      } catch (RuntimeException e) {
        logger.warn("Unreadable struct schema entry '{}': {}", name, e.getMessage());
        return null;
      }
    });

    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
    long offsetMemoryKb = recordOffsets.values().stream().mapToLong(RecordOffsets::storageBytes).sum() / 1024;
    logger.info("Scanned {}: {} entries, {} records, {} KB offsets in {}ms",
        Path.of(path).getFileName(), entries.size(),
        totalDataRecords, offsetMemoryKb, elapsedMs);

    // Configure Caffeine cache
    this.valueCache = Caffeine.newBuilder()
        .maximumWeight(maxCacheWeightBytes)
        .weigher((String key, List<TimestampedValue> values) -> estimateMemoryBytes(key, values))
        .evictionListener((key, value, cause) -> {
          if (cause.wasEvicted()) {
            logger.trace("Evicted entry values: {} ({})", key, cause);
          }
        })
        .build();

    this.valuesView = new LazyValuesMap();
  }

  @Override
  public String path() {
    return path;
  }

  @Override
  public Map<String, EntryInfo> entries() {
    return entries;
  }

  @Override
  public double minTimestamp() {
    return minTimestamp;
  }

  @Override
  public double maxTimestamp() {
    return maxTimestamp;
  }

  @Override
  public boolean truncated() {
    return truncated;
  }

  @Override
  public boolean damaged() {
    return damaged;
  }

  @Override
  public int sampleCount(String entryName) {
    var offsets = recordOffsets.get(entryName);
    return offsets != null ? offsets.size() : 0;
  }

  @Override
  public String truncationMessage() {
    return truncationMessage;
  }

  @Override
  public Map<String, List<TimestampedValue>> values() {
    return valuesView;
  }

  @Override
  public StructSchemas structSchemas() {
    return structSchemas;
  }

  @Override
  public Optional<DecodeProblem> decodeProblem(String entryName) {
    if (!entries.containsKey(entryName)) return Optional.empty();
    // Known once decoded (even if the values were evicted since): never decode twice to ask
    if (!decodedOnce.contains(entryName)) valuesView.get(entryName);
    return Optional.ofNullable(decodeProblems.get(entryName));
  }

  /** The number of entries whose decoded values are cached (for tests). */
  long cachedEntryCount() {
    valueCache.cleanUp();
    return valueCache.estimatedSize();
  }

  /**
   * Eviction stops caching immediately but leaves the mapping alive for its current holders.
   * The last holder releases it deterministically. Readers supplied by a caller belong to that
   * caller instead, so closing this view only drops its decoded values.
   */
  @Override
  public void close() {
    boolean release;
    synchronized (this) {
      closed = true;
      release = claimUnmap();
    }
    valueCache.invalidateAll();
    if (release) unmap();
    logger.debug("Closed LazyParsedLog: {}", path);
  }

  /**
   * Decodes all records for a specific entry using random access via byte offsets.
   * No file re-scan — reads only the records for the requested entry.
   */
  private List<TimestampedValue> decodeEntry(String entryName) {
    if (unmapped) throw new IllegalStateException("Log mapping has been released: " + path);
    var info = entries.get(entryName);
    if (info == null) return null;

    var offsets = recordOffsets.get(entryName);
    if (offsets == null || offsets.size() == 0) {
      decodedOnce.add(entryName);
      return List.of();
    }

    var type = info.type();
    var values = new ArrayList<TimestampedValue>(offsets.size());

    long startTime = System.nanoTime();
    int failed = 0;
    String firstFailure = null;

    for (int index = 0; index < offsets.size(); index++) {
      long offset = offsets.get(index);
      DataLogRecord record = null;
      try {
        record = DataLogAccess.getRecord(reader, offset);
        double timestamp = record.getTimestamp() / 1_000_000.0;
        var value = EntryDecoder.decodeValue(record, type, structSchemas);
        values.add(new TimestampedValue(timestamp, value));
      } catch (StructDecodeException e) {
        failed++;
        if (firstFailure == null) firstFailure = e.getMessage();
      } catch (Exception e) {
        failed++;
        if (firstFailure == null) firstFailure = EntryDecoder.malformedMessage(record, type, e);
        logger.trace("Malformed record at offset {} for {}: {}", offset, entryName, e.getMessage());
      }
    }
    if (failed > 0) {
      decodeProblems.put(entryName, new DecodeProblem(firstFailure, failed, offsets.size()));
      logger.debug("{}: {} of {} records not decoded: {}", entryName, failed, offsets.size(),
          firstFailure);
    }
    decodedOnce.add(entryName);

    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
    if (elapsedMs > 10) {
      logger.debug("Decoded {}: {} values in {}ms (random access)", entryName, values.size(), elapsedMs);
    }

    return Collections.unmodifiableList(values);
  }

  /**
   * Estimates the memory usage of a cached entry in bytes (for Caffeine weigher): the average
   * size of up to three sampled values (first, middle, last), nested structs included.
   */
  private int estimateMemoryBytes(String key, List<TimestampedValue> values) {
    if (values == null || values.isEmpty()) return 64;
    long sum = 0;
    int[] indices = sampleIndices(values.size());
    for (int idx : indices) sum += estimateValueBytes(values.get(idx).value(), 0);
    long perValue = 32 + sum / indices.length;
    long total = (long) values.size() * perValue + 200;
    return (int) Math.min(total, Integer.MAX_VALUE);
  }

  /**
   * Approximate heap size of one decoded value, following nested maps and lists. Slightly
   * generous: decoded structs are {@link StructMap}s
   * (a shared key array and one values array) and immutable lists; enum labels are shared.
   */
  static long estimateValueBytes(Object value, int depth) {
    if (value == null) return 8;
    if (value instanceof Number || value instanceof Boolean) return 24;
    if (value instanceof String s) return 40 + s.length() * 2L;
    if (value instanceof EnumValue) return 32;
    if (value instanceof byte[] b) return 16 + b.length;
    if (value instanceof double[] d) return 16 + d.length * 8L;
    if (value instanceof long[] l) return 16 + l.length * 8L;
    if (value instanceof float[] f) return 16 + f.length * 4L;
    if (value instanceof boolean[] b) return 16 + b.length;
    if (value instanceof String[] a) {
      long size = 16 + a.length * 8L;
      for (var str : a) size += str == null ? 0 : 40 + str.length() * 2L;
      return size;
    }
    if (depth > 8) return 64;
    if (value instanceof StructMap m) {
      long size = 48 + 8L * m.size(); // the map and its values array
      for (var v : m.values()) size += estimateValueBytes(v, depth + 1);
      return size;
    }
    if (value instanceof Map<?, ?> m) {
      long size = 160; // a hash map, its table, and a wrapper
      for (var v : m.values()) size += 40 + estimateValueBytes(v, depth + 1);
      return size;
    }
    if (value instanceof List<?> list) {
      if (list.isEmpty()) return 16;
      // struct arrays hold records of one type: size the first, scale by the count
      return 40 + list.size() * (8L + estimateValueBytes(list.get(0), depth + 1));
    }
    return 64;
  }

  /** Returns up to 3 sample indices (first, middle, last) for the given size. */
  private static int[] sampleIndices(int size) {
    if (size <= 1) return new int[]{0};
    if (size == 2) return new int[]{0, 1};
    return new int[]{0, size / 2, size - 1};
  }

  /**
   * Map view that lazily decodes entry values via the Caffeine cache.
   */
  private class LazyValuesMap extends AbstractMap<String, List<TimestampedValue>> {

    @Override
    public List<TimestampedValue> get(Object key) {
      if (!(key instanceof String name)) return null;
      if (!entries.containsKey(name)) return null;
      // Closed (evicted while a call still holds it): decode without refilling the cache
      if (closed) return decodeEntry(name);
      return valueCache.get(name, LazyParsedLog.this::decodeEntry);
    }

    @Override
    public boolean containsKey(Object key) {
      return key instanceof String name && entries.containsKey(name);
    }

    @Override
    public Set<String> keySet() {
      return entries.keySet();
    }

    @Override
    public int size() {
      return entries.size();
    }

    /**
     * Every entry, in declaration order; each one's values are decoded when taken
     * ({@link Entry#getValue()}), so iterating to filter by name or type decodes nothing.
     */
    @Override
    public Set<Entry<String, List<TimestampedValue>>> entrySet() {
      return new AbstractSet<>() {
        @Override
        public Iterator<Entry<String, List<TimestampedValue>>> iterator() {
          var names = entries.keySet().iterator();
          return new Iterator<>() {
            @Override
            public boolean hasNext() {
              return names.hasNext();
            }

            @Override
            public Entry<String, List<TimestampedValue>> next() {
              return new LazyEntry(names.next());
            }
          };
        }

        @Override
        public int size() {
          return entries.size();
        }
      };
    }
  }

  /** A map entry whose values are decoded when taken. */
  private final class LazyEntry implements Map.Entry<String, List<TimestampedValue>> {
    private final String name;

    LazyEntry(String name) {
      this.name = name;
    }

    @Override
    public String getKey() {
      return name;
    }

    @Override
    public List<TimestampedValue> getValue() {
      return valuesView.get(name);
    }

    @Override
    public List<TimestampedValue> setValue(List<TimestampedValue> value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Map.Entry<?, ?> e && name.equals(e.getKey())
          && Objects.equals(getValue(), e.getValue());
    }

    @Override
    public int hashCode() {
      return name.hashCode() ^ Objects.hashCode(getValue());
    }
  }
}
