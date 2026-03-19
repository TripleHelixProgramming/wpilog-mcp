package org.triplehelix.wpilogmcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Utility for programmatically building Mock ParsedLog objects for unit testing.
 */
public class MockLogBuilder {
  private String path = "/mock/test.wpilog";
  private final Map<String, EntryInfo> entries = new HashMap<>();
  private final Map<String, List<TimestampedValue>> values = new HashMap<>();
  private double minTimestamp = Double.MAX_VALUE;
  private double maxTimestamp = Double.MIN_VALUE;
  private int nextId = 1;

  public MockLogBuilder setPath(String path) {
    this.path = path;
    return this;
  }

  public MockLogBuilder addEntry(String name, String type, List<TimestampedValue> data) {
    entries.put(name, new EntryInfo(nextId++, name, type, ""));
    values.put(name, new ArrayList<>(data));

    for (var tv : data) {
      minTimestamp = Math.min(minTimestamp, tv.timestamp());
      maxTimestamp = Math.max(maxTimestamp, tv.timestamp());
    }
    return this;
  }

  public MockLogBuilder addNumericEntry(String name, double[] timestamps, double[] data) {
    var tvs = new ArrayList<TimestampedValue>();
    for (int i = 0; i < timestamps.length; i++) {
      tvs.add(new TimestampedValue(timestamps[i], data[i]));
    }
    return addEntry(name, "double", tvs);
  }

  public ParsedLog build() {
    if (entries.isEmpty()) {
      return new ParsedLog(path, new HashMap<>(), new HashMap<>(), 0.0, 0.0);
    }
    return new ParsedLog(path, entries, values, minTimestamp, maxTimestamp);
  }

  /** Creates a standard mock log with DriverStation state for testing timelines and cycles. */
  public static ParsedLog createStandardMatchLog() {
    var builder = new MockLogBuilder();

    // 0-15s Auto, 15-135s Teleop
    var enabledValues = new ArrayList<TimestampedValue>();
    enabledValues.add(new TimestampedValue(0.0, true));
    enabledValues.add(new TimestampedValue(135.0, false));

    var autoValues = new ArrayList<TimestampedValue>();
    autoValues.add(new TimestampedValue(0.0, true));
    autoValues.add(new TimestampedValue(15.0, false));

    return builder
        .addEntry("/DriverStation/Enabled", "boolean", enabledValues)
        .addEntry("/DriverStation/Autonomous", "boolean", autoValues)
        .build();
  }
}
