/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import edu.wpi.first.util.struct.DynamicStruct;
import edu.wpi.first.util.struct.StructDescriptorDatabase;
import edu.wpi.first.util.struct.StructFieldType;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.log.LazyParsedLog;
import org.triplehelix.wpilogmcp.log.ScopedLogReader;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;
import org.triplehelix.wpilogmcp.read.LogAccess;
import org.triplehelix.wpilogmcp.read.ReadOptions;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.ReadSnapshot;
import org.triplehelix.wpilogmcp.read.Sample;
import org.triplehelix.wpilogmcp.read.SampleBatch;

/** The public reader must agree with independently parsed record bytes, not merely another
 * caller of the same decoder. WPILib's dynamic decoder supplies the independent struct oracle;
 * the legacy comparison additionally preserves enrichment and whole-entry failure counts. */
class LocalReadDifferentialTest {
  @TempDir Path temp;

  @Test void everyFixtureAndWindowAgreesWithIndependentRecordsAcrossFourKiBMappingBoundaries()
      throws Exception {
    var fixtures = FixtureLogs.generateAll(FixtureLogs.defaultDirectory());
    var options = new ReadOptions(List.of(temp), 64L << 20, 64 << 20, 4096);
    long samples = 0;
    int entries = 0;
    int problems = 0;
    try (var access = LogAccess.local(options)) {
      for (var fixture : fixtures) {
        var path = padded(fixture.path(), fixture.id());
        try (var raw = new IndependentLog.Records(path);
             var snapshot = access.open(path);
             var legacyReader = new ScopedLogReader(path, 4096);
             var legacy = new LazyParsedLog(path.toString(), legacyReader.reader(), 1 << 20)) {
          var source = inspect(raw);
          assertEquals(source.entries().keySet(), snapshot.entries().keySet(), fixture.id());
          assertEquals(legacy.truncated(), snapshot.truncated(), fixture.id());
          assertEquals(legacy.damaged(), snapshot.damaged(), fixture.id());
          for (var schema : source.schemas().entrySet()) {
            var actual = snapshot.schemas().info(schema.getKey()).orElseThrow();
            assertEquals(schema.getValue(), actual.schema(), fixture.id() + "/" + schema.getKey());
            assertEquals(source.structs().find(schema.getKey()).isValid(), actual.valid());
          }
          for (var item : source.entries().entrySet()) {
            String name = item.getKey();
            var expectedEntry = item.getValue();
            var actualEntry = snapshot.entries().get(name);
            String where = fixture.id() + "/" + name;
            assertEquals(expectedEntry.type(), actualEntry.type(), where);
            assertEquals(expectedEntry.metadata(), actualEntry.metadata(), where);
            var records = source.records().get(name);
            assertEquals(records.size(), snapshot.sampleCount(name), where);
            var all = check(snapshot, ReadQuery.all(name), records, raw, source.structs(), expectedEntry.type(), where);
            var old = legacy.values().get(name);
            assertEquals(old.size(), all.samples().size(), where);
            for (int i = 0; i < old.size(); i++) {
              assertEquals(old.get(i).timestamp(), all.samples().get(i).timestampUs() / 1_000_000.0, where);
              sameValue(old.get(i).value(), all.samples().get(i).value(), where);
            }
            assertEquals(legacy.decodeProblem(name).map(problem -> problem.failedRecords()).orElse(0),
                all.problems().size(), where);
            samples += all.samples().size();
            problems += all.problems().size();
            entries++;
            if (!records.isEmpty()) {
              // Choose boundaries from the oracle's records, so inclusivity really excludes a
              // record, rather than asking both readers to agree on an empty decimal interval.
              var ordered = records.stream().sorted(Comparator.comparingLong(r -> r.record().timestampUs())).toList();
              long start = ordered.get(ordered.size() / 3).record().timestampUs();
              long end = ordered.get(2 * ordered.size() / 3).record().timestampUs();
              for (boolean inclusive : List.of(false, true)) {
                var selected = records.stream().filter(r -> r.record().timestampUs() >= start
                    && (r.record().timestampUs() < end || inclusive && r.record().timestampUs() == end)).toList();
                check(snapshot, ReadQuery.window(name, start, end, inclusive), selected, raw,
                    source.structs(), expectedEntry.type(), where + (inclusive ? "/closed" : "/half-open"));
              }
            }
          }
        }
      }
    }
    assertEquals(24, fixtures.size(), "Review the corpus when a fixture is added; none may silently disappear");
    assertTrue(problems >= 4, "The custom struct fixture must include short and schema-less records");
    System.out.printf("[local-read-differential] fixtures=%d entries=%d samples=%d decode_problems=%d%n",
        fixtures.size(), entries, samples, problems);
  }

  /** Make even a tiny fixture's first declaration straddle a mapping boundary. */
  private Path padded(Path source, String id) throws Exception {
    byte[] bytes = Files.readAllBytes(source);
    int originalExtra = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(8);
    int extra = 4094 - 12;
    var replacement = ByteBuffer.allocate(bytes.length - originalExtra + extra).order(ByteOrder.LITTLE_ENDIAN);
    replacement.put(bytes, 0, 8).putInt(extra).put(new byte[extra]);
    replacement.put(bytes, 12 + originalExtra, bytes.length - 12 - originalExtra);
    var path = temp.resolve(id + ".wpilog");
    Files.write(path, replacement.array());
    return path;
  }

  private record Raw(int ordinal, IndependentLog.Records.Record record) {}
  private record Source(Map<String, IndependentLog.Records.Start> entries,
      Map<String, List<Raw>> records, Map<String, String> schemas, StructDescriptorDatabase structs) {}
  private record Read(List<Sample> samples, List<ReadProblem> problems) {}

  private static Source inspect(IndependentLog.Records raw) throws Exception {
    var active = new HashMap<Long, IndependentLog.Records.Start>();
    var entries = new LinkedHashMap<String, IndependentLog.Records.Start>();
    var records = new LinkedHashMap<String, List<Raw>>();
    var schemas = new LinkedHashMap<String, String>();
    for (var record = raw.at(raw.first); record != null; record = raw.at(record.end())) {
      if (record.id() == 0) {
        switch (raw.control(record)) {
          case 0 -> {
            var entry = raw.start(record);
            assertNotNull(entry, "Generated fixture has a malformed declaration");
            active.put(entry.id(), entry);
            entries.put(entry.name(), entry);
            records.putIfAbsent(entry.name(), new ArrayList<>());
          }
          case 1 -> active.remove(raw.controlId(record));
          case 2 -> {
            var previous = active.get(raw.controlId(record));
            if (previous != null) {
              var changed = new IndependentLog.Records.Start(previous.id(), previous.name(), previous.type(), raw.metadata(record));
              active.put(changed.id(), changed);
              entries.put(changed.name(), changed);
            }
          }
          default -> fail("Unknown generated-fixture control record");
        }
      } else {
        var entry = active.get(record.id());
        assertNotNull(entry, "Generated data record has no active declaration");
        var values = records.get(entry.name());
        values.add(new Raw(values.size(), record));
        int schema = entry.name().lastIndexOf("/.schema/struct:");
        if (schema >= 0 && entry.type().equals("structschema")) {
          schemas.putIfAbsent(entry.name().substring(schema + "/.schema/struct:".length()),
              new String(raw.payload(record), StandardCharsets.UTF_8));
        }
      }
    }
    var structs = new StructDescriptorDatabase();
    for (var schema : schemas.entrySet()) structs.add(schema.getKey(), schema.getValue());
    return new Source(entries, records, schemas, structs);
  }

  private static Read check(ReadSnapshot snapshot, ReadQuery query, List<Raw> records,
      IndependentLog.Records raw, StructDescriptorDatabase structs, String type, String where) throws Exception {
    var samples = new ArrayList<Sample>();
    var problems = new ArrayList<ReadProblem>();
    long visited = 0;
    long bytes = 0;
    try (var cursor = snapshot.read(query)) {
      SampleBatch batch;
      int batches = 0;
      do {
        batch = cursor.next();
        assertTrue(++batches <= records.size() + 1, where + ": a cursor made no bounded progress");
        assertTrue(batch.recordsVisited() <= query.batchRecords(), where);
        assertTrue(batch.payloadBytes() <= query.batchBytes() || batch.recordsVisited() == 1, where);
        samples.addAll(batch.samples());
        problems.addAll(batch.problems());
        visited += batch.recordsVisited();
        bytes += batch.payloadBytes();
      } while (!batch.complete());
    }
    assertEquals(records.size(), visited, where + ": visits include only the requested window");
    assertEquals(records.stream().mapToLong(r -> r.record().payloadSize()).sum(), bytes, where);
    var ordered = records.stream().sorted(Comparator.comparingLong((Raw r) -> r.record().timestampUs())
        .thenComparingInt(Raw::ordinal)).toList();
    int sampleIndex = 0;
    var badOffsets = new ArrayList<Long>();
    for (var expected : ordered) {
      byte[] payload = raw.payload(expected.record());
      if (!decodable(type, payload, structs)) {
        badOffsets.add((long) expected.record().offset());
        continue;
      }
      assertTrue(sampleIndex < samples.size(), where + ": missing independently decodable record");
      var actual = samples.get(sampleIndex++);
      assertEquals(expected.record().timestampUs(), actual.timestampUs(), where);
      assertEquals(expected.record().offset(), actual.recordOffset(), where);
      assertEquals(expected.ordinal(), actual.ordinal(), where);
      checkPayload(type, payload, structs, actual.value(), where);
    }
    assertEquals(sampleIndex, samples.size(), where + ": no extra or undecodable sample");
    assertEquals(badOffsets, problems.stream().map(ReadProblem::recordOffset).toList(), where);
    for (var problem : problems) {
      assertEquals(ReadProblem.Code.MALFORMED_RECORD, problem.code(), where);
      assertEquals(query.entry(), problem.entry(), where);
      assertNotNull(problem.message(), where);
      assertFalse(problem.message().isBlank(), where);
    }
    return new Read(samples, problems);
  }

  private static boolean decodable(String type, byte[] payload, StructDescriptorDatabase structs) {
    if (!type.startsWith("struct:") && !type.startsWith("structarray:")) return true;
    String name = type.substring(type.indexOf(':') + 1).replaceFirst("\\[\\]$", "");
    var descriptor = structs.find(name);
    if (descriptor == null || !descriptor.isValid() || descriptor.getSize() <= 0) return false;
    return type.endsWith("[]") || type.startsWith("structarray:")
        ? payload.length % descriptor.getSize() == 0 : payload.length == descriptor.getSize();
  }

  private static void checkPayload(String type, byte[] payload, StructDescriptorDatabase structs,
      Object actual, String where) {
    if (type.startsWith("struct:") || type.startsWith("structarray:")) {
      String name = type.substring(type.indexOf(':') + 1).replaceFirst("\\[\\]$", "");
      var descriptor = structs.find(name);
      int size = descriptor.getSize();
      boolean array = type.endsWith("[]") || type.startsWith("structarray:");
      if (array) assertEquals(payload.length / size, assertInstanceOf(List.class, actual, where).size(), where);
      int count = array ? payload.length / size : 1;
      for (int i = 0; i < count; i++) {
        var dynamic = DynamicStruct.wrap(descriptor,
            ByteBuffer.wrap(payload, i * size, size).slice().order(ByteOrder.LITTLE_ENDIAN));
        checkStruct(dynamic, array ? ((List<?>) actual).get(i) : actual, where);
      }
    } else {
      sameValue(primitive(type, payload), actual, where);
    }
  }

  private static Object primitive(String type, byte[] payload) {
    var bytes = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    return switch (type) {
      case "boolean" -> bytes.get() != 0;
      case "int64" -> bytes.getLong();
      case "float" -> bytes.getFloat();
      case "double" -> bytes.getDouble();
      case "string", "json", "structschema" -> new String(payload, StandardCharsets.UTF_8);
      case "boolean[]" -> { var a = new boolean[payload.length]; for (int i = 0; i < a.length; i++) a[i] = bytes.get() != 0; yield a; }
      case "int64[]" -> { var a = new long[payload.length / 8]; for (int i = 0; i < a.length; i++) a[i] = bytes.getLong(); yield a; }
      case "float[]" -> { var a = new float[payload.length / 4]; for (int i = 0; i < a.length; i++) a[i] = bytes.getFloat(); yield a; }
      case "double[]" -> { var a = new double[payload.length / 8]; for (int i = 0; i < a.length; i++) a[i] = bytes.getDouble(); yield a; }
      case "string[]" -> {
        var a = new String[bytes.getInt()];
        for (int i = 0; i < a.length; i++) {
          byte[] encoded = new byte[bytes.getInt()]; bytes.get(encoded);
          a[i] = new String(encoded, StandardCharsets.UTF_8);
        }
        yield a;
      }
      case "raw" -> payload;
      default -> throw new AssertionError("Independent fixture decoder must cover new type: " + type);
    };
  }

  private static void checkStruct(DynamicStruct expected, Object value, String where) {
    var actual = assertInstanceOf(Map.class, value, where);
    for (var field : expected.getDescriptor().getFields()) {
      assertTrue(actual.containsKey(field.getName()), where + ": missing field " + field.getName());
      Object actualField = actual.get(field.getName());
      if (field.getType() == StructFieldType.kChar) {
        assertEquals(expected.getStringField(field), actualField, where);
        continue;
      }
      if (field.getArraySize() > 1) assertEquals(field.getArraySize(), assertInstanceOf(List.class, actualField, where).size(), where);
      for (int i = 0; i < field.getArraySize(); i++) {
        Object element = field.getArraySize() > 1 ? ((List<?>) actualField).get(i) : actualField;
        if (field.getType() == StructFieldType.kStruct) checkStruct(expected.getStructField(field, i), element, where);
        else if (field.getType() == StructFieldType.kBool) assertEquals(expected.getBoolField(field, i), element, where);
        else if (field.getType() == StructFieldType.kFloat) assertEquals(expected.getFloatField(field, i), element, where);
        else if (field.getType() == StructFieldType.kDouble) assertEquals(expected.getDoubleField(field, i), element, where);
        else if (field.hasEnum()) {
          long number = expected.getIntField(field, i);
          var enumeration = assertInstanceOf(EnumValue.class, element, where);
          assertEquals(number, enumeration.value(), where);
          assertEquals(field.getEnumValues().entrySet().stream().filter(e -> e.getValue() == number)
              .map(Map.Entry::getKey).findFirst().orElse(null), enumeration.label(), where);
        } else assertEquals(expected.getIntField(field, i), element, where);
      }
    }
  }

  private static void sameValue(Object expected, Object actual, String where) {
    if (expected != null && expected.getClass().isArray()) {
      assertNotNull(actual, where);
      assertEquals(expected.getClass(), actual.getClass(), where);
      assertEquals(Array.getLength(expected), Array.getLength(actual), where);
      for (int i = 0; i < Array.getLength(expected); i++) sameValue(Array.get(expected, i), Array.get(actual, i), where);
    } else if (expected instanceof List<?> list) {
      var other = assertInstanceOf(List.class, actual, where);
      assertEquals(list.size(), other.size(), where);
      for (int i = 0; i < list.size(); i++) sameValue(list.get(i), other.get(i), where);
    } else if (expected instanceof Map<?, ?> map) {
      var other = assertInstanceOf(Map.class, actual, where);
      assertEquals(map.keySet(), other.keySet(), where);
      map.forEach((key, item) -> sameValue(item, other.get(key), where));
    } else assertEquals(expected, actual, where);
  }
}
