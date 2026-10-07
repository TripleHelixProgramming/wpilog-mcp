/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.capture.CaptureStats;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.capture.pull.PullCoordinator;
import org.triplehelix.wpilogmcp.config.MetricsConfig;
import org.triplehelix.wpilogmcp.fixtures.PrometheusText;
import org.triplehelix.wpilogmcp.fixtures.PrometheusText.Key;
import org.triplehelix.wpilogmcp.nt4.TimeSync;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client.LatestValue;
import org.triplehelix.wpilogmcp.store.CaptureStore;

class MetricsProjectionTest {
  static final String SPECIAL = "/numeric/quote\" back\\slash\n café";
  static LatestValue latest(String type, Object value) { return new LatestValue(value, 1_000_000, 0, type); }
  static Map<String, LatestValue> topics(int t) {
    var values = new LinkedHashMap<String, LatestValue>();
    values.put(SPECIAL, latest("double", Math.sin(t)));
    values.put("/numeric/count", latest("int", (long) t));
    values.put("/numeric/flag", latest("boolean", t % 2 == 0));
    values.put("/numeric/float", latest("float", t / 10f));
    values.put("/numeric/array", latest("double[]", java.util.stream.IntStream.range(0, 20).mapToObj(i -> t + 2.0 * i).toList()));
    values.put("/numeric/bools", latest("boolean[]", List.of(false, true)));
    values.put("/numeric/nan", latest("double", Double.NaN));
    values.put("/numeric/inf", latest("float[]", List.of(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)));
    values.put("/text", latest("string", "synthetic")); values.put("/json", latest("json", "17"));
    values.put("/strings", latest("string[]", List.of("1", "2")));
    values.put("/raw", latest("raw", new byte[] {3}));
    values.put("/.schema/struct:Part", latest("structschema", "double x;bool active".getBytes(StandardCharsets.UTF_8)));
    values.put("/.schema/struct:Whole", latest("structschema", "Part part;enum {idle=0,run=1} int32 mode;double samples[20];char label[4];double _derived".getBytes(StandardCharsets.UTF_8)));
    byte[] bytes = ByteBuffer.allocate(185).order(ByteOrder.LITTLE_ENDIAN).putDouble(3 * t).put((byte) 1).putInt(1).array();
    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); buffer.position(13);
    for (int i = 0; i < 20; i++) buffer.putDouble(t - i);
    buffer.put(new byte[] {'t','e','s','t'}).putDouble(7 * t);
    values.put("/numeric/struct", latest("struct:Whole", bytes));
    byte[] array = ByteBuffer.allocate(18 * 3).order(ByteOrder.LITTLE_ENDIAN).array();
    var parts = ByteBuffer.wrap(array).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < 6; i++) parts.putDouble(t + i).put((byte) (i % 2));
    values.put("/numeric/structs", latest("struct:Part[]", array));
    values.put("/missing", latest("struct:Pose2d", new byte[24])); // No canonical-schema guessing.
    values.put("/bad", latest("struct:Part", new byte[2]));
    values.put("/.schema/struct:Incomplete", latest("structschema", "Rotation2d angle".getBytes(StandardCharsets.UTF_8)));
    values.put("/missing-nested", latest("struct:Incomplete", new byte[8]));
    return values;
  }
  static LiveCapture.Metrics snapshot(Map<String, LatestValue> topics) {
    var status = new CaptureStore.Status(Path.of("capture.wpilog"), Instant.EPOCH, null, "127.0.0.1", true, null, null,
        new CaptureStats(8, 20, 300, Map.of(), List.of(), Map.of()), null, null, null);
    return new LiveCapture.Metrics(true, "127.0.0.1", status, topics, new TimeSync.Sample(0, 2400, -120_000),
        1_750_000.0, Map.of("SYNTHETIC", new PullCoordinator.Progress(4096, 2, 3, 3)));
  }
  static MetricsEndpoint.Jvm jvm() {
    return new MetricsEndpoint.Jvm(List.of(new MetricsEndpoint.Memory("heap", 100, 200, 500),
        new MetricsEndpoint.Memory("nonheap", 20, 40, -1)), List.of(new MetricsEndpoint.Collections("collector", 2, 1250),
        new MetricsEndpoint.Collections("unknown", -1, -1)));
  }
  @Test void everyLineHasIndependentKnownValuesWithBoundedArraysAndRecordedStructFields() {
    for (int t : new int[] {0, 2, 5}) for (int limit : new int[] {0, 3, 16}) {
      var view = PrometheusText.parse(MetricsEndpoint.render(new MetricsConfig(List.of(), limit), snapshot(topics(t)),
          new MetricsEndpoint.Components(4, Map.of("synthetic", new MetricsEndpoint.ProviderCost(.002, 31))), jvm()));
      var expected = new LinkedHashMap<Key, Double>();
      put(expected, SPECIAL, null, null, Math.sin(t)); put(expected, "/numeric/count", null, null, t);
      put(expected, "/numeric/flag", null, null, t % 2 == 0 ? 1 : 0); put(expected, "/numeric/float", null, null, (double) (t / 10f));
      put(expected, "/numeric/nan", null, null, Double.NaN);
      for (int i = 0; i < limit; i++) put(expected, "/numeric/array", i, null, t + 2 * i);
      for (int i = 0; i < Math.min(2, limit); i++) {
        put(expected, "/numeric/bools", i, null, i);
        put(expected, "/numeric/inf", i, null, i == 0 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
      }
      put(expected, "/numeric/struct", null, "part.x", 3 * t); put(expected, "/numeric/struct", null, "part.active", 1);
      put(expected, "/numeric/struct", null, "mode", 1); put(expected, "/numeric/struct", null, "_derived", 7 * t);
      for (int i = 0; i < limit; i++) put(expected, "/numeric/struct", null, "samples[" + i + "]", t - i);
      for (int i = 0; i < Math.min(6, limit); i++) {
        put(expected, "/numeric/structs", i, "x", t + i); put(expected, "/numeric/structs", i, "active", i % 2);
      }
      var numeric = new LinkedHashMap<Key, Double>(); view.samples().forEach((key, value) -> { if (key.name().equals("nt_value")) numeric.put(key, value); });
      assertEquals(expected, numeric);
      var topics = expected.keySet().stream().map(key -> key.labels().get("topic")).collect(java.util.stream.Collectors.toSet());
      assertEquals(topics.size(), view.samples().keySet().stream().filter(k -> k.name().equals("nt_age_seconds")).count());
      topics.forEach(topic -> assertEquals(.75, view.value("nt_age_seconds", Map.of("topic", topic))));
      assertEquals(-.12, view.value("wpilog_nt_time_offset_seconds", Map.of()));
      assertEquals(.0024, view.value("wpilog_nt_round_trip_seconds", Map.of()));
      assertEquals(1, view.value("wpilog_nt_connected", Map.of()));
      assertEquals(1, view.value("wpilog_nt_address_info", Map.of("address", "127.0.0.1")));
      assertEquals(1, view.value("wpilog_capture_open", Map.of()));
      var session = Map.of("session_started_at", Instant.EPOCH.toString());
      assertEquals(8, view.value("wpilog_capture_topics", session));
      assertEquals(20, view.value("wpilog_capture_records_total", session));
      assertEquals(300, view.value("wpilog_capture_bytes_total", session));
      assertEquals(4096, view.value("wpilog_pull_bytes_total", Map.of("robot", "SYNTHETIC")));
      assertEquals(2, view.value("wpilog_pull_files_total", Map.of("robot", "SYNTHETIC")));
      assertEquals(3, view.value("wpilog_pull_files_waiting", Map.of("robot", "SYNTHETIC")));
      assertEquals(4, view.value("wpilog_gateway_clients", Map.of()));
      assertEquals(.002, view.value("wpilog_provider_sample_duration_seconds", Map.of("provider", "synthetic")));
      assertEquals(31, view.value("wpilog_provider_sample_bytes", Map.of("provider", "synthetic")));
      for (String area : List.of("heap", "nonheap")) {
        assertEquals(area.equals("heap") ? 100 : 20, view.value("wpilog_jvm_memory_used_bytes", Map.of("area", area)));
        assertEquals(area.equals("heap") ? 200 : 40, view.value("wpilog_jvm_memory_committed_bytes", Map.of("area", area)));
      }
      assertEquals(500, view.value("wpilog_jvm_memory_max_bytes", Map.of("area", "heap")));
      assertEquals(2, view.value("wpilog_jvm_gc_collections_total", Map.of("collector", "collector")));
      assertEquals(1.25, view.value("wpilog_jvm_gc_duration_seconds_total", Map.of("collector", "collector")));
      assertEquals(expected.size() + topics.size() + 21, view.samples().size(), "Every emitted sample has an independent assertion");
      view.types().forEach((name, type) -> assertEquals(name.endsWith("_total") ? "counter" : "gauge", type));
    }
  }
  static void put(Map<Key, Double> expected, String topic, Integer index, String field, double value) {
    var labels = new java.util.TreeMap<String, String>(); labels.put("topic", topic);
    if (index != null) labels.put("index", index.toString()); if (field != null) labels.put("field", field);
    expected.put(new Key("nt_value", labels), value);
  }
  @Test void filteringKeepsSchemasAndMissingTimeSyncOmitsOnlyAges() {
    var s = snapshot(topics(2));
    var unknown = new LiveCapture.Metrics(false, s.address(), s.current(), s.latest(), null, null, Map.of());
    var parsed = PrometheusText.parse(MetricsEndpoint.render(new MetricsConfig(List.of("/numeric/struct"), 2), unknown,
        MetricsEndpoint.Components.NONE, jvm()));
    assertFalse(parsed.samples().keySet().stream().anyMatch(k -> k.name().equals("nt_age_seconds") || k.name().equals("wpilog_nt_time_offset_seconds")));
    assertEquals(6, parsed.samples().keySet().stream().filter(k -> k.name().equals("nt_value") && k.labels().get("topic").equals("/numeric/struct")).count());
    assertFalse(parsed.samples().keySet().stream().anyMatch(k -> k.name().equals("nt_value") && !k.labels().get("topic").startsWith("/numeric/struct")));
    var future = new LiveCapture.Metrics(true, s.address(), null, Map.of("/x", latest("int", 4)), s.time(), 0.0, Map.of());
    assertEquals(-1, PrometheusText.parse(MetricsEndpoint.render(MetricsConfig.DEFAULT, future, MetricsEndpoint.Components.NONE, jvm()))
        .value("nt_age_seconds", Map.of("topic", "/x")));
  }
}
