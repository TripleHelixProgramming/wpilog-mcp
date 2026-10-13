/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.data.ArrowSpecReader;
import org.triplehelix.wpilogmcp.data.ArrowStreamWriter;
import org.triplehelix.wpilogmcp.data.ArrowType;
import org.triplehelix.wpilogmcp.data.ColumnBuilder;
import org.triplehelix.wpilogmcp.data.ReadArrowTypes;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.struct.EnumValue;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.log.struct.UnsignedEnumValue;
import org.triplehelix.wpilogmcp.log.subsystems.EntryDecoder;

class NativeUnsignedReadTest {
  @TempDir Path temp;
  private static final List<BigInteger> UNSIGNED = List.of(BigInteger.ZERO, BigInteger.ONE,
      new BigInteger("9007199254740993"), new BigInteger("9223372036854775807"),
      new BigInteger("9223372036854775808"), new BigInteger("9223372036854775809"),
      new BigInteger("18446744073709551615"));

  @Test void sourceCursorKeepsAllUnsignedBitsWhileLegacyDecodingStaysUnchanged() throws Exception {
    var path = temp.resolve("unsigned.wpilog");
    try (var writer = new WpilogWriter(path, "")) {
      int schema = writer.start("/.schema/struct:Unsigned", "structschema", "", 0);
      writer.append(schema, 0, WpilogWriter.encodeString("uint64 value;"));
      int entry = writer.start("/counter", "struct:Unsigned", "", 0);
      for (int i = 0; i < UNSIGNED.size(); i++) writer.append(entry, i, bytes(UNSIGNED.get(i)));
    }
    try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(path);
         var cursor = snapshot.read(ReadQuery.all("/counter"))) {
      assertEquals(UNSIGNED, cursor.next().samples().stream()
          .map(s -> ((Map<?, ?>) s.value()).get("value")).toList());
      for (var value : UNSIGNED) {
        Object old = ((Map<?, ?>) snapshot.schemas().decode("struct:Unsigned", bytes(value))).get("value");
        if (value.bitLength() < 64) assertEquals(value.longValueExact(), old);
        else assertEquals(value.doubleValue(), old, "legacy tools retain their existing uint64 conversion");
      }
    }
  }

  @Test void liveSourceModeKeepsUnsignedArraysNestedFieldsAndEnumValues() {
    var schemas = StructSchemas.recordedOnly(Map.of("Counter", "uint64 counts[2]; enum {ZERO=0, ONE=1} uint64 mode;",
        "Outer", "Counter counter; uint64 flags:3;"), Map.of());
    byte[] raw = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        .putLong(Long.MIN_VALUE + 1).putLong(-1L).putLong(-1L).putLong(7).array();
    var nativeValue = (Map<?, ?>) EntryDecoder.decodeNetworkRecordedValue(raw, "struct:Outer", schemas);
    var nativeCounter = (Map<?, ?>) nativeValue.get("counter");
    assertEquals(List.of(new BigInteger("9223372036854775809"), new BigInteger("18446744073709551615")), nativeCounter.get("counts"));
    assertEquals(new UnsignedEnumValue(new BigInteger("18446744073709551615"), null), nativeCounter.get("mode"));
    assertEquals(BigInteger.valueOf(7), nativeValue.get("flags"));
    var old = (Map<?, ?>) EntryDecoder.decodeNetworkValue(raw, "struct:Outer", schemas);
    var oldCounter = (Map<?, ?>) old.get("counter");
    assertEquals(new EnumValue(-1L, null), oldCounter.get("mode"));
    assertEquals(List.of(9223372036854775808.0, 18446744073709551616.0), oldCounter.get("counts"));
    assertEquals(7L, old.get("flags"));
  }

  @Test void arrowUsesOneUnsignedTypeForLowAndHighValuesIncludingEnums() throws Exception {
    var schemas = StructSchemas.recordedOnly(Map.of("Unsigned", "uint64 value; enum {ZERO=0, ONE=1} uint64 mode;"), Map.of());
    var type = ReadArrowTypes.recordedType("struct:Unsigned", schemas, 1024);
    var out = new ByteArrayOutputStream();
    var writer = new ArrowStreamWriter(out, List.of(new ArrowType.Field("value", type, false)));
    writer.writeSchema(Map.of());
    var column = ColumnBuilder.of(type);
    for (var value : UNSIGNED) {
      byte[] raw = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
          .putLong(value.longValue()).putLong(value.longValue()).array();
      column.append(EntryDecoder.decodeNetworkRecordedValue(raw, "struct:Unsigned", schemas));
    }
    writer.writeBatch(List.of(column), Map.of()); writer.finish();
    var read = ArrowSpecReader.read(out.toByteArray());
    assertEquals("uint64", read.fields().get(0).children().get(0).type());
    assertEquals("uint64", read.fields().get(0).children().get(1).children().get(0).type());
    var values = new ArrayList<Object>();
    for (var value : read.batches().get(0).columns().get(0)) {
      var fields = (Map<?, ?>) value;
      values.add(fields.get("value"));
      assertEquals(fields.get("value"), ((Map<?, ?>) fields.get("mode")).get("value"));
    }
    assertEquals(UNSIGNED, values);
    var samples = java.nio.file.Path.of("build", "arrow-samples", "native");
    java.nio.file.Files.createDirectories(samples);
    java.nio.file.Files.write(samples.resolve("native_unsigned.arrow"), out.toByteArray());
  }

  @Test void unsignedEnumsRejectValuesOutsideTheirDeclaredWidth() {
    assertThrows(IllegalArgumentException.class, () -> new UnsignedEnumValue(BigInteger.valueOf(-1), null));
    assertThrows(IllegalArgumentException.class, () -> new UnsignedEnumValue(BigInteger.ONE.shiftLeft(64), null));
  }

  private static byte[] bytes(BigInteger value) {
    return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.longValue()).array();
  }
}
