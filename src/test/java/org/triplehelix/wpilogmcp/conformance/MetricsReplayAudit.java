/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import edu.wpi.first.util.struct.DynamicStruct;
import edu.wpi.first.util.struct.StructDescriptorDatabase;
import edu.wpi.first.util.struct.StructFieldType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.fixtures.PrometheusText;
import org.triplehelix.wpilogmcp.fixtures.PrometheusText.Key;

/** Source records come from the differential reader. WPILib's DynamicStruct is the numeric
 * oracle, independent of the server's compiled struct decoder and field paths. Never print a
 * source value on failure: real-directory callers report only the input path. */
final class MetricsReplayAudit {
  record Value(String type, Object value, long time) {}
  static int check(LiveToolRig rig, Map<String, Value> latest) throws Exception {
    var schemas = new StructDescriptorDatabase();
    latest.forEach((topic, value) -> {
      int at = topic.lastIndexOf(".schema/struct:");
      if (value.type().equals("structschema") && at >= 0 && (at == 0 || topic.charAt(at - 1) == '/')) {
        try { schemas.add(topic.substring(at + ".schema/struct:".length()), new String((byte[]) value.value(), StandardCharsets.UTF_8)); }
        catch (edu.wpi.first.util.struct.BadSchemaException invalid) { /* Invalid schemas carry no metrics. */ }
      }
    });
    var expected = new HashMap<Key, Double>();
    latest.forEach((topic, value) -> {
      String type = value.type();
      if (List.of("double", "float", "int", "boolean").contains(type)) put(expected, topic, null, null, number(value.value()));
      else if (List.of("double[]", "float[]", "int[]", "boolean[]").contains(type)) {
        var values = (List<?>) value.value();
        for (int i = 0; i < Math.min(16, values.size()); i++) put(expected, topic, i, null, number(values.get(i)));
      } else if (type.startsWith("struct:") || type.startsWith("structarray:")) {
        String name = type.substring(type.indexOf(':') + 1).replaceFirst("\\[\\]$", "");
        var descriptor = schemas.find(name); byte[] bytes = (byte[]) value.value();
        if (descriptor == null || !descriptor.isValid() || descriptor.getSize() <= 0) return;
        int size = descriptor.getSize(); boolean array = type.endsWith("[]") || type.startsWith("structarray:");
        if (array ? bytes.length % size != 0 : bytes.length != size) return;
        int count = array ? Math.min(16, bytes.length / size) : 1;
        for (int i = 0; i < count; i++) {
          var record = DynamicStruct.wrap(descriptor, ByteBuffer.wrap(bytes, i * size, size).slice());
          fields(expected, topic, array ? i : null, "", record);
        }
      }
    });
    var scrape = LiveMetricsTest.scrape(rig);
    var observed = new HashMap<Key, Double>();
    scrape.samples().forEach((key, value) -> { if (key.name().equals("nt_value")) observed.put(key, value); });
    assertTrue(expected.equals(observed), "Replay metrics differ from source records");
    var exposed = expected.keySet().stream().map(key -> key.labels().get("topic")).collect(java.util.stream.Collectors.toSet());
    Double now = rig.service.live().robotNowUs();
    for (var topic : exposed) {
      var key = new Key("nt_age_seconds", Map.of("topic", topic));
      if (now == null) assertFalse(scrape.samples().containsKey(key));
      else assertTrue(Double.compare((now - latest.get(topic).time()) / 1e6, scrape.value(key.name(), key.labels())) == 0, "Replay metric age differs");
    }
    var status = rig.service.live().current();
    if (status != null) {
      var labels = Map.of("session_started_at", status.startedAt().toString());
      assertEquals(status.statistics().records(), scrape.value("wpilog_capture_records_total", labels));
      assertEquals(status.statistics().bytes(), scrape.value("wpilog_capture_bytes_total", labels));
    }
    return observed.size();
  }
  private static double number(Object value) { return value instanceof Boolean b ? b ? 1 : 0 : ((Number) value).doubleValue(); }
  private static void fields(Map<Key, Double> result, String topic, Integer index, String prefix, DynamicStruct struct) {
    for (var field : struct.getDescriptor().getFields()) {
      if (field.getType() == StructFieldType.kChar) continue;
      for (int i = 0; i < Math.min(16, field.getArraySize()); i++) {
        String path = prefix + field.getName() + (field.getArraySize() > 1 ? "[" + i + "]" : "");
        if (field.getType() == StructFieldType.kStruct) fields(result, topic, index, path + ".", struct.getStructField(field, i));
        else {
          double value = switch (field.getType()) {
            case kBool -> struct.getBoolField(field, i) ? 1 : 0;
            case kFloat -> struct.getFloatField(field, i);
            case kDouble -> struct.getDoubleField(field, i);
            default -> struct.getIntField(field, i);
          };
          put(result, topic, index, path, value);
        }
      }
    }
  }
  private static void put(Map<Key, Double> result, String topic, Integer index, String field, double value) {
    var labels = new HashMap<String, String>(); labels.put("topic", topic);
    if (index != null) labels.put("index", index.toString()); if (field != null) labels.put("field", field);
    result.put(new Key("nt_value", labels), value);
  }
}
