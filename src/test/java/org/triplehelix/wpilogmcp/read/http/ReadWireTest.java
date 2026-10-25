/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.http;

import org.triplehelix.wpilogmcp.read.internal.ReadWire;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;
import org.triplehelix.wpilogmcp.read.ReadProblem;

class ReadWireTest {
  @Test void longAndFloatingBitsNeverPassThroughAnApproximateJsonNumber() {
    var integer = ReadWire.value(Long.MAX_VALUE).getAsJsonObject();
    assertEquals("i64", integer.get("type").getAsString());
    assertTrue(integer.get("value").getAsJsonPrimitive().isString());
    assertEquals("9223372036854775807", integer.get("value").getAsString());
    assertEquals(Long.MAX_VALUE, ReadWire.value(integer));
    for (long bits : new long[] {0x8000000000000000L, 0x7ff8000000000042L, 0x7ff0000000000000L}) {
      var value = ReadWire.value(Double.longBitsToDouble(bits));
      assertEquals(bits, Double.doubleToRawLongBits((Double) ReadWire.value(value)));
    }
    int bits = 0x7fc00042;
    assertEquals(bits, Float.floatToRawIntBits((Float) ReadWire.value(ReadWire.value(Float.intBitsToFloat(bits)))));
  }

  @Test void nestedStructArraysOpaqueBytesAndEnumsRetainTheirTypes() {
    var value = Map.of("array", new long[] {Long.MIN_VALUE, Long.MAX_VALUE},
        "opaque", new byte[] {0, (byte) 255}, "enum", new EnumValue(3, "three"),
        "nested", List.of(Map.of("x", 1.5)));
    var decoded = (Map<?, ?>) ReadWire.value(ReadWire.value(value));
    assertArrayEquals(new long[] {Long.MIN_VALUE, Long.MAX_VALUE}, (long[]) decoded.get("array"));
    assertArrayEquals(new byte[] {0, (byte) 255}, (byte[]) decoded.get("opaque"));
    assertEquals(new EnumValue(3, "three"), decoded.get("enum"));
    assertEquals(List.of(Map.of("x", 1.5)), decoded.get("nested"));
  }

  @Test void unknownTagsAndWrongShapesAreRefusedInsteadOfCoerced() {
    assertThrows(IllegalArgumentException.class, () -> ReadWire.value(JsonParser.parseString("{\"type\":\"future\",\"value\":1}")));
    assertThrows(IllegalArgumentException.class, () -> ReadWire.value(JsonParser.parseString("{\"type\":\"i64\",\"value\":9007199254740993}")));
    assertThrows(IllegalArgumentException.class, () -> ReadWire.value(JsonParser.parseString("{\"type\":\"i64\",\"value\":\"9223372036854775808\"}")));
    Object nested = 1L;
    for (int i = 0; i < 70; i++) nested = List.of(nested);
    Object tooDeep = nested;
    assertThrows(IllegalArgumentException.class, () -> ReadWire.value(tooDeep));
  }

  @Test void wireExpansionIsBudgetedBeforeAllocatingTagsForArrayElements() {
    assertThrows(IllegalArgumentException.class, () -> ReadWire.value(new double[1024], 256));
    assertThrows(IllegalArgumentException.class, () -> ReadWire.value(List.of(new long[20], new long[20]), 1024));
  }

  @Test void theWireAllocationBudgetCoversTheWholeBatchNotEachSampleSeparately() {
    var batch = new org.triplehelix.wpilogmcp.read.SampleBatch(List.of(
        new org.triplehelix.wpilogmcp.read.Sample(1, 32, 0, new long[10]),
        new org.triplehelix.wpilogmcp.read.Sample(2, 128, 1, new long[10])), List.of(), 2, 160, true);
    assertThrows(IllegalArgumentException.class, () -> ReadWire.batch(batch, 1024));
  }

  @Test void splittingKeepsDiagnosticsFilteredWorkAndEncodedByteCounts() {
    var values = new double[64];
    var samples = new java.util.ArrayList<org.triplehelix.wpilogmcp.read.Sample>();
    for (int i = 0; i < 4094; i++)
      samples.add(new org.triplehelix.wpilogmcp.read.Sample(i, 64L + i * 520, i, values, 512));
    var problem = new org.triplehelix.wpilogmcp.read.ReadProblem(
        org.triplehelix.wpilogmcp.read.ReadProblem.Code.MALFORMED_RECORD, "/values", 3_000_000, "Partial double");
    // 4,094 decoded records, a three-byte malformed payload and one filtered eight-byte record.
    long payload = 4094L * 512 + 3 + 8;
    var source = new org.triplehelix.wpilogmcp.read.SampleBatch(samples, List.of(problem), 4096, payload, true);
    var parts = new java.util.ArrayList<org.triplehelix.wpilogmcp.read.SampleBatch>();
    ReadWire.wireBatches(source).forEachRemaining(part -> parts.add(ReadWire.batch(ReadWire.batch(part))));
    assertTrue(parts.size() > 1, "A source batch is split to fit the wire without changing the query");
    assertEquals(4096, parts.stream().mapToInt(p -> p.recordsVisited()).sum());
    assertEquals(payload, parts.stream().mapToLong(p -> p.payloadBytes()).sum());
    assertEquals(List.of(problem), parts.stream().flatMap(p -> p.problems().stream()).toList());
    var decoded = parts.stream().flatMap(p -> p.samples().stream()).toList();
    assertEquals(samples.size(), decoded.size());
    for (int i = 0; i < decoded.size(); i++) {
      assertEquals(i, decoded.get(i).ordinal());
      assertEquals(512, decoded.get(i).payloadBytes());
      assertArrayEquals(values, (double[]) decoded.get(i).value());
    }
    assertTrue(parts.get(parts.size() - 1).complete());
    assertTrue(parts.subList(0, parts.size() - 1).stream().noneMatch(p -> p.complete()));
  }

  @Test void queryCountsAndFlagsMustHaveExactShapesInsteadOfCoercingThem() {
    for (String body : List.of("{\"entry\":\"x\",\"batch_records\":4294967297}",
        "{\"entry\":\"x\",\"batch_records\":1.5}",
        "{\"entry\":\"x\",\"windows\":{}}",
        "{\"entry\":\"x\",\"windows\":[{\"start_us\":\"0\",\"end_us\":\"1\",\"end_inclusive\":\"true\"}]}"))
      assertThrows(IllegalArgumentException.class, () -> ReadWire.query(JsonParser.parseString(body).getAsJsonObject()), body);
  }

  @Test void invalidUtf8AndDuplicateFieldsAreRejectedAtTheWireBoundary() {
    assertThrows(java.io.IOException.class, () -> ReadWire.parse(new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0x80, '"', '}'}));
    assertThrows(java.io.IOException.class, () -> ReadWire.parse("{\"path\":\"a\",\"path\":\"b\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
  @Test void aRefusalKeepsTheServersRetryableFlagAndRefusesOneThatIsNotABoolean() {
    String refusal = "{\"status\":\"error\",\"code\":\"FILE_CHANGED\",\"message\":\"Store changed; retry the read\"";
    var retryable = ReadWire.failure(JsonParser.parseString(refusal + ",\"retryable\":true}").getAsJsonObject());
    assertEquals(ReadProblem.Code.FILE_CHANGED, retryable.code());
    assertEquals("Store changed; retry the read", retryable.getMessage());
    assertTrue(retryable.retryable());
    assertFalse(ReadWire.failure(JsonParser.parseString(refusal + ",\"retryable\":false}").getAsJsonObject()).retryable());
    // An older server sends no flag, which means the refusal is permanent.
    assertFalse(ReadWire.failure(JsonParser.parseString(refusal + "}").getAsJsonObject()).retryable());
    for (String flag : List.of("\"true\"", "1", "null", "{}", "[]")) {
      var invalid = ReadWire.failure(JsonParser.parseString(refusal + ",\"retryable\":" + flag + "}").getAsJsonObject());
      assertEquals(ReadProblem.Code.MALFORMED_RECORD, invalid.code(), flag);
      assertFalse(invalid.retryable(), flag);
    }
    var changed = ReadWire.responseFailure(409, (refusal + ",\"retryable\":true}").getBytes(StandardCharsets.UTF_8));
    assertEquals(ReadProblem.Code.FILE_CHANGED, changed.code());
    assertTrue(changed.retryable());
    for (int status : List.of(503, 429)) {
      var busy = ReadWire.responseFailure(status, "try later".getBytes(StandardCharsets.UTF_8));
      assertEquals(ReadProblem.Code.BUSY, busy.code());
      assertTrue(busy.retryable(), "HTTP " + status);
    }
  }

  @Test void unsigned64ValuesStayExactAndRejectValuesOutsideTheirRecordedRange() {
    var maximum = new java.math.BigInteger("18446744073709551615");
    var wire = ReadWire.value(maximum).getAsJsonObject();
    assertEquals("u64", wire.get("type").getAsString());
    assertEquals(maximum.toString(), wire.get("value").getAsString());
    assertEquals(maximum, ReadWire.value(wire));
    assertEquals(java.math.BigInteger.ZERO, ReadWire.value(ReadWire.value(java.math.BigInteger.ZERO)));
    var enumValue = new org.triplehelix.wpilogmcp.log.struct.UnsignedEnumValue(maximum, "maximum");
    var enumWire = ReadWire.value(enumValue).getAsJsonObject();
    assertEquals("enum_u64", enumWire.get("type").getAsString());
    assertEquals(enumValue, ReadWire.value(enumWire));
    assertThrows(IllegalArgumentException.class, () -> ReadWire.value(java.math.BigInteger.ONE.negate()));
    assertThrows(IllegalArgumentException.class, () -> ReadWire.value(maximum.add(java.math.BigInteger.ONE)));
    assertThrows(IllegalArgumentException.class, () -> ReadWire.value(JsonParser.parseString("{\"type\":\"u64\",\"value\":\"-1\"}")));
  }

}
