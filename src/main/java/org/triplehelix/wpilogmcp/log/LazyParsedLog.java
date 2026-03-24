package org.triplehelix.wpilogmcp.log;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.wpi.first.util.datalog.DataLogReader;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import edu.wpi.first.util.datalog.DataLogAccess;
import org.triplehelix.wpilogmcp.log.subsystems.EntryDecoder;
import org.triplehelix.wpilogmcp.log.subsystems.StructDecoderRegistry;

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
  private final StructDecoderRegistry decoderRegistry;
  private final Cache<String, List<TimestampedValue>> valueCache;
  private final LazyValuesMap valuesView;

  /**
   * Creates a LazyParsedLog by scanning the file once.
   *
   * <p>The scan builds entry metadata and records byte offsets for each data record.
   * No values are decoded during construction. The ByteBuffer from the DataLogReader's
   * memory-mapped file is used for subsequent random-access decoding.
   *
   * @param path The file path
   * @param reader The DataLogReader (memory-mapped)
   * @param decoderRegistry The struct decoder registry
   * @param maxCacheWeightBytes Maximum total weight of cached decoded values in bytes
   * @throws IOException if the reader is invalid
   */
  public LazyParsedLog(String path, DataLogReader reader,
      StructDecoderRegistry decoderRegistry, long maxCacheWeightBytes) throws IOException {
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
    this.decoderRegistry = decoderRegistry;

    // Single-pass scan using WPILib's iterator for correct Start record parsing,
    // while tracking byte offsets for data records via our WpilogRecordReader.
    // This gives us reliable entry metadata AND random-access offsets in one pass.
    var entriesById = new HashMap<Integer, EntryInfo>();
    var entriesByName = new HashMap<String, EntryInfo>();
    var offsetLists = new HashMap<String, List<Integer>>();

    double minTs = Double.MAX_VALUE;
    double maxTs = Double.NEGATIVE_INFINITY;
    boolean trunc = false;
    String truncMsg = null;

    logger.debug("Scanning log: {}", path);
    long startTime = System.nanoTime();
    int totalDataRecords = 0;

    // Walk byte offsets in parallel with the WPILib iterator, using DataLogAccess
    // to call the package-private getNextRecord() for offset tracking.
    // The extra header length determines where records start.
    int pos = 12 + reader.getExtraHeader().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

    try {
      for (var record : reader) {
        if (record.isStart()) {
          var startData = record.getStartData();
          if (startData.name != null && !startData.name.isEmpty()) {
            var info = new EntryInfo(startData.entry, startData.name, startData.type, startData.metadata);
            entriesById.put(startData.entry, info);
            entriesByName.put(startData.name, info);
            offsetLists.put(startData.name, new ArrayList<>());
          }
        } else if (!record.isFinish() && !record.isSetMetadata()) {
          // Data record — record its byte offset for random-access decode
          var info = entriesById.get(record.getEntry());
          if (info != null) {
            double timestamp = record.getTimestamp() / 1_000_000.0;
            minTs = Math.min(minTs, timestamp);
            maxTs = Math.max(maxTs, timestamp);
            offsetLists.get(info.name()).add(pos);
            totalDataRecords++;
          }
        }

        // Advance our byte offset tracker in lockstep with the iterator
        pos = DataLogAccess.getNextRecord(reader, pos);
      }
    } catch (IllegalArgumentException e) {
      String msg = e.getMessage();
      if (msg != null && (msg.contains("capacity") || msg.contains("truncat")
              || msg.contains("incomplete") || msg.contains("buffer"))) {
        trunc = true;
        truncMsg = "Log file is truncated (incomplete write). Data up to "
            + String.format("%.2f", maxTs) + " seconds was recovered.";
        logger.warn("Log file '{}' is truncated: {}", path, truncMsg);
      } else {
        throw new IOException("Error scanning log file: " + e.getMessage(), e);
      }
    }

    this.entries = Collections.unmodifiableMap(entriesByName);
    this.minTimestamp = minTs == Double.MAX_VALUE ? 0 : minTs;
    this.maxTimestamp = maxTs == Double.NEGATIVE_INFINITY ? 0 : maxTs;
    this.truncated = trunc;
    this.truncationMessage = truncMsg;

    // Compact offset lists to int[] arrays
    this.recordOffsets = new HashMap<>();
    for (var entry : offsetLists.entrySet()) {
      var list = entry.getValue();
      int[] arr = new int[list.size()];
      for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
      recordOffsets.put(entry.getKey(), arr);
    }

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
  @Override public String truncationMessage() { return truncationMessage; }

  @Override
  public Map<String, List<TimestampedValue>> values() {
    return valuesView;
  }

  @Override
  public void close() {
    valueCache.invalidateAll();
    recordOffsets.clear();
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
    if (offsets == null || offsets.length == 0) return List.of();

    var type = info.type();
    var values = new ArrayList<TimestampedValue>(offsets.length);

    long startTime = System.nanoTime();

    for (int offset : offsets) {
      try {
        var record = DataLogAccess.getRecord(reader, offset);
        double timestamp = record.getTimestamp() / 1_000_000.0;
        var value = EntryDecoder.decodeValue(record, type, decoderRegistry);
        values.add(new TimestampedValue(timestamp, value));
      } catch (Exception e) {
        logger.trace("Malformed record at offset {} for {}: {}", offset, entryName, e.getMessage());
      }
    }

    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
    if (elapsedMs > 10) {
      logger.debug("Decoded {}: {} values in {}ms (random access)", entryName, values.size(), elapsedMs);
    }

    return Collections.unmodifiableList(values);
  }

  /**
   * Estimates the memory usage of a cached entry in bytes (for Caffeine weigher).
   */
  private int estimateMemoryBytes(String key, List<TimestampedValue> values) {
    if (values == null || values.isEmpty()) return 64;

    long perValue = 32;
    var firstValue = values.get(0).value();
    if (firstValue instanceof Double || firstValue instanceof Long) {
      perValue += 24;
    } else if (firstValue instanceof String s) {
      perValue += 40 + s.length() * 2L;
    } else if (firstValue instanceof Map<?, ?> m) {
      perValue += 200 + m.size() * 50L;
    } else if (firstValue instanceof byte[] b) {
      perValue += 16 + b.length;
    } else if (firstValue instanceof double[] d) {
      perValue += 16 + d.length * 8L;
    } else {
      perValue += 40;
    }

    long total = (long) values.size() * perValue + 200;
    return (int) Math.min(total, Integer.MAX_VALUE);
  }

  /**
   * Map view that lazily decodes entry values via the Caffeine cache.
   */
  private class LazyValuesMap extends AbstractMap<String, List<TimestampedValue>> {

    @Override
    public List<TimestampedValue> get(Object key) {
      if (!(key instanceof String name)) return null;
      if (!entries.containsKey(name)) return null;
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

    @Override
    public Set<Entry<String, List<TimestampedValue>>> entrySet() {
      logger.debug("LazyValuesMap.entrySet() called — materializing all entries");
      var result = new java.util.LinkedHashSet<Entry<String, List<TimestampedValue>>>();
      for (var name : entries.keySet()) {
        var values = get(name);
        if (values != null) {
          result.add(new SimpleImmutableEntry<>(name, values));
        }
      }
      return result;
    }
  }
}
