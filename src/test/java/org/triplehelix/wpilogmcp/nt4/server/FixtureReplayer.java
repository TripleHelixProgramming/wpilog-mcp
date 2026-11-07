/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import edu.wpi.first.util.datalog.DataLogAccess;
import edu.wpi.first.util.datalog.DataLogRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import org.triplehelix.wpilogmcp.nt4.NtType;

/**
 * Test-only robot: fixture entries become topics, raw structs/schemas stay binary, and stable
 * timestamp sorting interleaves entries. A supplied pacer waits until an elapsed microsecond
 * deadline, so 1x replay and fast replay share exactly the same event sequence.
 */
public final class FixtureReplayer {
  private record Topic(String name, String type) {}
  private record Sample(Topic topic, long timestampUs, Object value) {}
  private final Map<String, Topic> topics = new LinkedHashMap<>();
  private final List<Sample> samples = new ArrayList<>();

  public FixtureReplayer(Path file) throws IOException {
    var entries = new HashMap<Integer, Topic>();
    var reader = new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file)));
    long offset = DataLogAccess.firstRecordOffset(file);
    while (offset < DataLogAccess.size(reader)) {
      long next = DataLogAccess.recordEnd(reader, offset);
      if (next < 0) break; // Fixture with an incomplete final record.
      var record = DataLogAccess.getRecord(reader, offset);
      offset = next;
      if (record.isStart()) {
        var start = record.getStartData();
        var topic = new Topic(start.name, NtType.fromWpilog(start.type).nt4());
        entries.put(start.entry, topic); topics.put(topic.name(), topic);
      } else if (record.isFinish()) entries.remove(record.getFinishEntry());
      else if (!record.isControl()) {
        var topic = entries.get(record.getEntry());
        if (topic != null) samples.add(new Sample(topic, record.getTimestamp(), value(record, topic.type())));
      }
    }
    samples.sort(Comparator.comparingLong(Sample::timestampUs));
  }

  public void announce(Nt4Gateway gateway) {
    topics.values().forEach(t -> gateway.announce(t.name(), t.type(), new JsonObject()).join());
  }

  public void replay(Nt4Gateway gateway, double speed, LongConsumer awaitElapsedUs) {
    if (!Double.isFinite(speed) || speed < 0) throw new IllegalArgumentException("Replay speed");
    long start = samples.isEmpty() ? 0 : samples.get(0).timestampUs();
    CompletableFuture<Void> sent = CompletableFuture.completedFuture(null);
    int count = 0;
    for (var sample : samples) {
      if (speed > 0) awaitElapsedUs.accept((long) ((sample.timestampUs() - start) / speed));
      sent = gateway.value(sample.topic().name(), sample.timestampUs(),
          NtType.fromNt4(sample.topic().type()).code(), sample.value());
      if (++count % 256 == 0) sent.join(); // Bound the event-loop queue, including in fast replay.
    }
    sent.join();
  }

  /** Actual log pace without busy waiting; tests inject a recorder instead of sleeping. */
  public static LongConsumer realTimePacer() {
    long start = System.nanoTime();
    return deadline -> {
      long remaining;
      while ((remaining = deadline * 1000 - (System.nanoTime() - start)) > 0) {
        try { TimeUnit.NANOSECONDS.sleep(remaining); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
      }
    };
  }

  private static Object value(DataLogRecord record, String type) {
    return switch (type) {
      case "boolean" -> record.getBoolean();
      case "int" -> record.getInteger();
      case "double" -> record.getDouble();
      case "float" -> record.getFloat();
      case "string", "json" -> record.getString();
      case "boolean[]" -> {
        var values = new ArrayList<Boolean>(); for (boolean v : record.getBooleanArray()) values.add(v); yield values;
      }
      case "int[]" -> Arrays.stream(record.getIntegerArray()).boxed().toList();
      case "double[]" -> Arrays.stream(record.getDoubleArray()).boxed().toList();
      case "float[]" -> {
        var values = new ArrayList<Float>(); for (float v : record.getFloatArray()) values.add(v); yield values;
      }
      case "string[]" -> Arrays.asList(record.getStringArray());
      default -> record.getRaw();
    };
  }
}
