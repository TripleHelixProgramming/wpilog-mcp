/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.wpi.first.util.datalog.DataLogReader;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import edu.wpi.first.util.datalog.DataLogAccess;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;
import org.triplehelix.wpilogmcp.log.struct.StructDecodeException;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.log.subsystems.EntryDecoder;

/**
 * Lazily-loaded wpilog data backed by a memory-mapped file and Caffeine cache.
 *
 * <p>Built from a single scan of the file. The scan records entry metadata and byte offsets
 * for each data record (4 bytes per record — compact). Values are decoded on demand via
 * random access to the memory-mapped ByteBuffer when tools access specific entries.
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
  private final String truncationMessage;

  // Per-entry byte offsets into the memory-mapped file (compact: 4 bytes per record)
  private final Map<String, int[]> recordOffsets;

  private final DataLogReader reader;
  private final StructSchemas structSchemas;
  private final Map<String, DecodeProblem> decodeProblems = new ConcurrentHashMap<>();
  /** Entries decoded at least once, whose decode problems (if any) are therefore known. */
  private final Set<String> decodedOnce = ConcurrentHashMap.newKeySet();
  private final Cache<String, List<TimestampedValue>> valueCache;
  private final LazyValuesMap valuesView;
  private volatile boolean closed = false;

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
    if (!reader.isValid()) {
      throw new IOException("Invalid WPILOG file: " + path);
    }

    // DataLogReader uses a memory-mapped ByteBuffer (int-indexed), so files > 2GB
    // would overflow. Check proactively to give a clear error.
    long fileSize = java.nio.file.Files.size(java.nio.file.Path.of(path));
    if (fileSize > Integer.MAX_VALUE) {
      throw new IOException("WPILOG file exceeds 2 GB limit for memory-mapped access: " + path
          + " (" + (fileSize / (1024 * 1024)) + " MB)");
    }

    this.path = path;
    this.reader = reader;

    // One pass over the records (shared with LogParser): entries in declaration order, each
    // data record's byte offset for random-access decoding later, and where the file stops being
    // a valid log (a damaged tail is not read; see LogScan)
    logger.debug("Scanning log: {}", path);
    long startTime = System.nanoTime();
    var scan = LogScan.of(reader, java.nio.file.Path.of(path));
    var entriesByName = scan.entries();
    var offsetLists = scan.offsets();
    int totalDataRecords = scan.dataRecords();

    this.entries = entriesByName;
    this.minTimestamp = scan.minTimestamp();
    this.maxTimestamp = scan.maxTimestamp();
    this.truncated = scan.truncated();
    this.truncationMessage = scan.truncationMessage();

    // Compact offset lists to int[] arrays
    this.recordOffsets = new HashMap<>();
    for (var entry : offsetLists.entrySet()) {
      var list = entry.getValue();
      int[] arr = new int[list.size()];
      for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
      recordOffsets.put(entry.getKey(), arr);
    }

    // Spot-check: validate a few random offsets to ensure entry IDs match.
    // Pick up to 3 records (first, middle, last) from a non-empty entry.
    for (var offsetEntry : recordOffsets.entrySet()) {
      int[] offsets = offsetEntry.getValue();
      if (offsets.length == 0) continue;
      var expectedInfo = entriesByName.get(offsetEntry.getKey());
      if (expectedInfo == null) continue;

      int[] sampleIndices = offsets.length == 1
          ? new int[]{0}
          : offsets.length == 2
              ? new int[]{0, offsets.length - 1}
              : new int[]{0, offsets.length / 2, offsets.length - 1};
      for (int idx : sampleIndices) {
        try {
          var record = DataLogAccess.getRecord(reader, offsets[idx]);
          if (record.getEntry() != expectedInfo.id()) {
            logger.warn("Offset validation mismatch for '{}': expected entry ID {} but found {} at offset {}",
                offsetEntry.getKey(), expectedInfo.id(), record.getEntry(), offsets[idx]);
          }
        } catch (Exception e) {
          logger.warn("Offset validation failed for '{}' at offset {}: {}",
              offsetEntry.getKey(), offsets[idx], e.getMessage());
        }
      }
      break; // Only spot-check one entry to keep startup fast
    }

    // Struct schemas: the first record of each /.schema/struct: entry
    this.structSchemas = StructSchemas.fromLog(entries, name -> {
      int[] offsets = recordOffsets.get(name);
      if (offsets == null || offsets.length == 0) return null;
      try {
        return DataLogAccess.getRecord(reader, offsets[0]).getString();
      } catch (RuntimeException e) {
        logger.warn("Unreadable struct schema entry '{}': {}", name, e.getMessage());
        return null;
      }
    });

    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
    long offsetMemoryKb = (long) totalDataRecords * 4 / 1024;
    logger.info("Scanned {}: {} entries, {} records, {} KB offsets in {}ms",
        java.nio.file.Path.of(path).getFileName(), entries.size(),
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

  @Override public String path() { return path; }
  @Override public Map<String, EntryInfo> entries() { return entries; }
  @Override public double minTimestamp() { return minTimestamp; }
  @Override public double maxTimestamp() { return maxTimestamp; }
  @Override public boolean truncated() { return truncated; }

  @Override
  public int sampleCount(String entryName) {
    int[] offsets = recordOffsets.get(entryName);
    return offsets != null ? offsets.length : 0;
  }
  @Override public String truncationMessage() { return truncationMessage; }

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
   * Releases the cached values. A closed log still reads correctly: a tool call that obtained it
   * before it was evicted (heap pressure, idle expiry, replacement) decodes what it asks for
   * again, without caching, instead of silently getting empty values. The memory-mapped file is
   * released by the garbage collector once nothing references this log.
   */
  @Override
  public void close() {
    closed = true;
    valueCache.invalidateAll();
    logger.debug("Closed LazyParsedLog: {}", path);
  }

  /**
   * Decodes all records for a specific entry using random access via byte offsets.
   * No file re-scan — reads only the records for the requested entry.
   */
  private List<TimestampedValue> decodeEntry(String entryName) {
    var info = entries.get(entryName);
    if (info == null) return null;

    int[] offsets = recordOffsets.get(entryName);
    if (offsets == null || offsets.length == 0) {
      decodedOnce.add(entryName);
      return List.of();
    }

    var type = info.type();
    var values = new ArrayList<TimestampedValue>(offsets.length);

    long startTime = System.nanoTime();
    int failed = 0;
    String firstFailure = null;

    for (int offset : offsets) {
      try {
        var record = DataLogAccess.getRecord(reader, offset);
        double timestamp = record.getTimestamp() / 1_000_000.0;
        var value = EntryDecoder.decodeValue(record, type, structSchemas);
        values.add(new TimestampedValue(timestamp, value));
      } catch (StructDecodeException e) {
        failed++;
        if (firstFailure == null) firstFailure = e.getMessage();
      } catch (Exception e) {
        failed++;
        if (firstFailure == null) firstFailure = "malformed record (" + e.getMessage() + ")";
        logger.trace("Malformed record at offset {} for {}: {}", offset, entryName, e.getMessage());
      }
    }
    if (failed > 0) {
      decodeProblems.put(entryName, new DecodeProblem(firstFailure, failed, offsets.length));
      logger.debug("{}: {} of {} records not decoded: {}", entryName, failed, offsets.length,
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
   * generous: decoded structs are {@link org.triplehelix.wpilogmcp.log.struct.StructMap}s
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
    if (value instanceof org.triplehelix.wpilogmcp.log.struct.StructMap m) {
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
      return new java.util.AbstractSet<>() {
        @Override
        public java.util.Iterator<Entry<String, List<TimestampedValue>>> iterator() {
          var names = entries.keySet().iterator();
          return new java.util.Iterator<>() {
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
          && java.util.Objects.equals(getValue(), e.getValue());
    }

    @Override
    public int hashCode() {
      return name.hashCode() ^ java.util.Objects.hashCode(getValue());
    }
  }
}
