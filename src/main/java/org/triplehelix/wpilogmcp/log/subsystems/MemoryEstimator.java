package org.triplehelix.wpilogmcp.log.subsystems;

import java.util.Collection;
import org.triplehelix.wpilogmcp.log.ParsedLog;

/**
 * Estimates memory usage of parsed WPILOG files.
 *
 * <p>This class provides heuristics for estimating the memory footprint of cached logs, which is
 * used for memory-based eviction strategies. The estimates include:
 *
 * <ul>
 *   <li>TimestampedValue object overhead
 *   <li>Entry metadata (name strings, EntryInfo objects)
 *   <li>Type-specific value sizes (primitives, strings, structs)
 *   <li>Collection overhead (ArrayList, HashMap)
 * </ul>
 *
 * <p>Memory estimates are approximate (typically within 20% of actual) and optimized for
 * performance over precision.
 *
 * @since 0.4.0
 */
public class MemoryEstimator {

  /**
   * Estimates the total memory usage of a collection of parsed logs in bytes.
   *
   * @param logs The collection of parsed logs
   * @return Estimated memory usage in bytes
   */
  public long estimateTotalMemory(Collection<ParsedLog> logs) {
    return logs.stream().mapToLong(this::estimateLogMemory).sum();
  }

  /**
   * Estimates the memory usage of a single log in bytes.
   *
   * <p>Uses type information and actual value sampling for better accuracy. Includes:
   *
   * <ul>
   *   <li>All TimestampedValue objects
   *   <li>Entry metadata (EntryInfo, entry name strings)
   *   <li>Collection overhead
   * </ul>
   *
   * @param log The parsed log
   * @return Estimated memory usage in bytes
   */
  public long estimateLogMemory(ParsedLog log) {
    long totalMemory = 0;

    for (var entry : log.entries().entrySet()) {
      var entryName = entry.getKey();
      var entryInfo = entry.getValue();
      var values = log.values().get(entryName);

      if (values == null || values.isEmpty()) continue;

      // Estimate per-value memory based on type and actual values
      long perValueMemory = estimateValueMemory(entryInfo.type(), values.get(0).value());
      totalMemory += values.size() * perValueMemory;

      // Add entry metadata overhead: name string, EntryInfo object, List object, etc.
      totalMemory += 200 + (entryName.length() * 2); // Java strings are 2 bytes per char
    }

    return totalMemory;
  }

  /**
   * Estimates memory usage of a single value based on its type and content.
   *
   * <p>Returns the estimated bytes including:
   *
   * <ul>
   *   <li>Base overhead (32 bytes) - TimestampedValue object + timestamp + references
   *   <li>Value size based on type (primitives, strings, structs, arrays)
   * </ul>
   *
   * @param type The WPILib type string (e.g., "double", "string", "struct:Pose2d")
   * @param sampleValue A sample value to estimate size (e.g., for string length)
   * @return Estimated memory usage in bytes
   */
  public long estimateValueMemory(String type, Object sampleValue) {
    // Base overhead for TimestampedValue object: timestamp (8) + object reference (8) + object
    // header (16)
    long baseOverhead = 32;

    // Estimate value size based on type
    long valueSize =
        switch (type) {
          case "double", "int64" -> 8;
          case "float" -> 4;
          case "boolean" -> 1;
          case "string" -> {
            if (sampleValue instanceof String s) {
              // String overhead (object header + length field) + character data
              yield 40 + (s.length() * 2);
            }
            yield 100; // Default for unknown string size
          }
          case "byte[]", "raw" -> {
            if (sampleValue instanceof byte[] b) {
              yield 40 + b.length;
            }
            yield 100; // Default for unknown byte array size
          }
          default -> {
            // For structs and other complex types
            if (sampleValue instanceof java.util.Map) {
              @SuppressWarnings("unchecked")
              var map = (java.util.Map<String, Object>) sampleValue;
              // Map overhead + approximate size of entries
              yield 200 + (map.size() * 50);
            }
            yield 40; // Fallback for unknown types
          }
        };

    return baseOverhead + valueSize;
  }
}
