/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.*;

/** Expected bytes are assembled from the format tables, never from the encoder under test. */
class ProtocolTest {
  @Test void timeEstimateReadsAPublishedWindowWithoutJoiningTheWriter() throws Exception {
    var sync = new TimeSync(100); sync.add(0, 10, 105); sync.add(90, 110, 300);
    var threads = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      synchronized (sync) {
        var answer = threads.submit(() -> sync.best(110).orElseThrow());
        assertEquals(new TimeSync.Sample(110, 20, 200), answer.get(1, java.util.concurrent.TimeUnit.SECONDS));
      }
      assertTrue(sync.best(210).isEmpty()); sync.clear(); assertTrue(sync.best(0).isEmpty());
    } finally { threads.shutdownNow(); }
  }
  static byte[] hex(String text) { return HexFormat.of().parseHex(text.replace(" ", "")); }
  private record Vector(String bytes, Object value) {}

  @TestFactory Stream<DynamicTest> scalarEncodings() {
    return Stream.of(
        new Vector("c0", null), new Vector("c2", false), new Vector("c3", true),
        new Vector("00", 0L), new Vector("7f", 127L), new Vector("cc80", 128L),
        new Vector("ccff", 255L), new Vector("cd0100", 256L), new Vector("cdffff", 65535L),
        new Vector("ce00010000", 65536L), new Vector("ceffffffff", 4294967295L),
        new Vector("cf0000000100000000", 4294967296L), new Vector("cf7fffffffffffffff", Long.MAX_VALUE),
        new Vector("cfffffffffffffffff", new BigInteger("18446744073709551615")),
        new Vector("ff", -1L), new Vector("e0", -32L), new Vector("d0df", -33L),
        new Vector("d080", -128L), new Vector("d1ff7f", -129L), new Vector("d18000", -32768L),
        new Vector("d2ffff7fff", -32769L), new Vector("d280000000", (long) Integer.MIN_VALUE),
        new Vector("d3ffffffff7fffffff", -2147483649L), new Vector("d38000000000000000", Long.MIN_VALUE),
        new Vector("ca3fc00000", 1.5f), new Vector("ca80000000", -0.0f),
        new Vector("ca7f800000", Float.POSITIVE_INFINITY), new Vector("ca7fc00000", Float.NaN),
        new Vector("cb3ff8000000000000", 1.5), new Vector("cb8000000000000000", -0.0),
        new Vector("cbfff0000000000000", Double.NEGATIVE_INFINITY), new Vector("cb7ff8000000000000", Double.NaN),
        new Vector("a0", ""), new Vector("a568c3a9c2b0", "hé°"),
        new Vector("c40300ff80", hex("00ff80")), new Vector("93c001a178", Arrays.asList(null, 1L, "x")),
        new Vector("81a176c0", Collections.singletonMap("v", null)))
        .map(v -> DynamicTest.dynamicTest(v.bytes(), () -> {
          assertArrayEquals(hex(v.bytes()), MessagePack.encode(v.value()));
          assertDeep(v.value(), MessagePack.decode(hex(v.bytes())));
        }));
  }

  @Test void decoderAcceptsAllIntegerWidthsEvenWhenNotMinimal() {
    for (var bytes : List.of("cc01", "cd0001", "ce00000001", "cf0000000000000001",
        "d001", "d10001", "d200000001", "d30000000000000001")) {
      assertEquals(1L, MessagePack.decode(hex(bytes)), bytes);
    }
    assertEquals(new BigInteger("9223372036854775808"), MessagePack.decode(hex("cf8000000000000000")));
  }

  @Test void lengthBoundariesUseSpecificationHeaders() {
    for (int n : new int[] {0, 15, 16, 31, 32, 255, 256, 65535, 65536}) {
      byte[] payload = new byte[n];
      Arrays.fill(payload, (byte) 'x');
      var string = "x".repeat(n);
      assertArrayEquals(join(lengthHeader(n, "string"), payload), MessagePack.encode(string));
      assertEquals(string, MessagePack.decode(join(lengthHeader(n, "string"), payload)));
      assertArrayEquals(join(lengthHeader(n, "binary"), payload), MessagePack.encode(payload));
      assertArrayEquals(payload, (byte[]) MessagePack.decode(join(lengthHeader(n, "binary"), payload)));
      byte[] zeros = new byte[n];
      assertArrayEquals(join(lengthHeader(n, "array"), zeros), MessagePack.encode(Collections.nCopies(n, 0L)));
      assertEquals(Collections.nCopies(n, 0L), MessagePack.decode(join(lengthHeader(n, "array"), zeros)));
    }
    for (int n : new int[] {0, 15, 16, 65535, 65536}) {
      var map = new LinkedHashMap<Object, Object>();
      var expected = new java.io.ByteArrayOutputStream();
      expected.writeBytes(lengthHeader(n, "map"));
      for (int i = 0; i < n; i++) {
        // Two UTF-8 bytes would alias; each key is a four-character ASCII hex string.
        var key = String.format(java.util.Locale.ROOT, "%04x", i);
        map.put(key, false);
        expected.write(0xa4);
        expected.writeBytes(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        expected.write(0xc2);
      }
      assertArrayEquals(expected.toByteArray(), MessagePack.encode(map));
      assertEquals(map, MessagePack.decode(expected.toByteArray()));
    }
    assertEquals(List.of(1L), MessagePack.decode(hex("dd0000000101")));
    assertEquals(Map.of("x", 1L), MessagePack.decode(hex("df00000001a17801")));
    assertEquals("x", MessagePack.decode(hex("db0000000178")));
    assertArrayEquals(hex("ff"), (byte[]) MessagePack.decode(hex("c600000001ff")));
  }

  // Header table transcribed independently: payload byte count / collection element count.
  private static byte[] lengthHeader(int n, String family) {
    if (family.equals("string") && n <= 31) return new byte[] {(byte) (0xa0 + n)};
    if (family.equals("array") && n <= 15) return new byte[] {(byte) (0x90 + n)};
    if (family.equals("map") && n <= 15) return new byte[] {(byte) (0x80 + n)};
    if (n <= 255 && (family.equals("string") || family.equals("binary"))) {
      return new byte[] {(byte) (family.equals("string") ? 0xd9 : 0xc4), (byte) n};
    }
    int code = switch (family) { case "string" -> 0xda; case "binary" -> 0xc5; case "array" -> 0xdc; default -> 0xde; };
    return n <= 65535 ? new byte[] {(byte) code, (byte) (n >> 8), (byte) n}
        : new byte[] {(byte) (code + 1), 0, 1, 0, 0};
  }

  @Test void malformedAndExcessiveObjectsAreRejectedBeforeAllocation() {
    for (var bytes : List.of("", "c1", "c7", "d40000", "cd01", "c4ff00", "dbffffffff",
        "ddffffffff", "dfffffffff", "a1ff", "81a178", "82a17801a17802", "ca0000")) {
      assertThrows(IllegalArgumentException.class, () -> MessagePack.decode(hex(bytes)), bytes);
    }
    assertThrows(IllegalArgumentException.class, () -> MessagePack.decode(hex("91".repeat(66) + "00")));
    assertThrows(IllegalArgumentException.class, () -> MessagePack.decode(hex("0000")));
    assertEquals("MessagePack: message too large", assertThrows(IllegalArgumentException.class,
        () -> MessagePack.decode(new byte[16 * 1024 * 1024 + 1])).getMessage());
    assertThrows(IllegalArgumentException.class, () -> MessagePack.encode(new byte[16 * 1024 * 1024 + 1]));
    // array32, 1,000,001 fixints: small on the wire but too many decoded objects.
    assertThrows(IllegalArgumentException.class, () -> MessagePack.decode(join(hex("dd000f4241"), new byte[1_000_001])));
    assertThrows(IllegalArgumentException.class, () -> MessagePack.encode(BigInteger.ONE.shiftLeft(64)));
    assertThrows(IllegalArgumentException.class, () -> MessagePack.encode(BigInteger.ONE.shiftLeft(63).negate().subtract(BigInteger.ONE)));
    assertThrows(IllegalArgumentException.class, () -> MessagePack.encode(new Object()));
    Object nested = 0L;
    for (int i = 0; i < 66; i++) nested = List.of(nested);
    Object tooDeep = nested;
    assertThrows(IllegalArgumentException.class, () -> MessagePack.encode(tooDeep));
  }

  @Test void binaryFramesMatchHandEncodedTypeTableAndPublishedExample() {
    var cases = List.of(new Vector("00c3", true), new Vector("01cb3ff8000000000000", 1.5),
        new Vector("02d0df", -33L), new Vector("03ca3fc00000", 1.5f), new Vector("04a178", "x"),
        new Vector("05c40200ff", hex("00ff")), new Vector("1092c2c3", List.of(false, true)),
        new Vector("1191cb3ff8000000000000", List.of(1.5)), new Vector("1291ff", List.of(-1L)),
        new Vector("1391ca3fc00000", List.of(1.5f)), new Vector("1491a178", List.of("x")));
    for (var v : cases) {
      int code = Integer.parseInt(v.bytes().substring(0, 2), 16);
      var expected = hex("94072a" + v.bytes());
      assertArrayEquals(expected, new ValueFrame(7, 42, code, v.value()).encode());
      var decoded = ValueFrame.decode(expected).get(0);
      assertEquals(7, decoded.topicId()); assertEquals(42, decoded.timestampUs()); assertEquals(code, decoded.typeCode());
      assertDeep(v.value(), decoded.value());
    }
    var example = ValueFrame.decode(hex("9432d207270e0001cb3fbf972474538ef3")).get(0);
    assertEquals(50, example.topicId()); assertEquals(120_000_000, example.timestampUs());
    assertEquals(0.1234, example.value());
    // Unsigned CE and signed D2 are equally valid 32-bit encodings of the published timestamp.
    assertArrayEquals(hex("9432ce07270e0001cb3fbf972474538ef3"), example.encode());
    var sync = new ValueFrame(-1, 0, 2, 500L);
    assertArrayEquals(hex("94ff0002cd01f4"), sync.encode());
    assertArrayEquals(hex("94ff0002cd01f494ff0002cd01f4"), ValueFrame.encode(List.of(sync, sync)));
    assertEquals(2, ValueFrame.decode(hex("94ff0002cd01f494ff0002cd01f4")).size());
  }

  @Test void invalidFrameFamiliesAndUnsupportedIds() {
    for (var bytes : List.of("93ff0002", "94000000c0", "94000001ca3f800000", "9400001291cb3ff0000000000000")) {
      assertThrows(IllegalArgumentException.class, () -> ValueFrame.decode(hex(bytes)));
    }
    assertEquals(List.of(), ValueFrame.decode(hex("94ce800000000002019400000601")));
    assertEquals(List.of(), ValueFrame.decode(hex("94cfffffffffffffffff000201940000cfffffffffffffffff01")));
    assertThrows(IllegalArgumentException.class, () -> new ValueFrame(-2, 0, 0, true));
    byte[] raw = {1};
    var frame = new ValueFrame(0, 0, 5, raw); raw[0] = 9;
    ((byte[]) frame.value())[0] = 8;
    assertArrayEquals(new byte[] {1}, (byte[]) frame.value());
  }

  @Test void typeStringsAreAuthoritativeInBothDirections() {
    String[][] table = {{"boolean", "boolean", "0"}, {"double", "double", "1"}, {"int", "int64", "2"},
        {"float", "float", "3"}, {"string", "string", "4"}, {"json", "json", "4"},
        {"boolean[]", "boolean[]", "16"}, {"double[]", "double[]", "17"}, {"int[]", "int64[]", "18"},
        {"float[]", "float[]", "19"}, {"string[]", "string[]", "20"}};
    for (var row : table) {
      var expected = new NtType(Integer.parseInt(row[2]), row[0], row[1]);
      assertEquals(expected, NtType.fromNt4(row[0])); assertEquals(expected, NtType.fromWpilog(row[1]));
    }
    for (var name : List.of("raw", "rpc", "msgpack", "protobuf", "protobuf:Test", "struct:Test", "struct:Test[]", "structschema", "vendor-type")) {
      var expected = new NtType(5, name, name);
      assertEquals(expected, NtType.fromNt4(name)); assertEquals(expected, NtType.fromWpilog(name));
    }
  }

  @Test void controlRecordsMatchHandWrittenJson() {
    var p = JsonParser.parseString("{\"x\":null,\"retained\":true}").getAsJsonObject();
    var options = JsonParser.parseString("{\"prefix\":true,\"all\":true,\"periodic\":0.01,\"custom\":3}").getAsJsonObject();
    var messages = List.of(new Publish("/a", 2, "json", p), new Unpublish(2), new SetProperties("/a", p),
        new Subscribe(List.of("", "/b"), 3, options), new Unsubscribe(3),
        new Announce("/a", 1, "json", 2, p), new Unannounce("/a", 1), new Properties("/a", true, p));
    var expected = """
        [{"method":"publish","params":{"name":"/a","pubuid":2,"type":"json","properties":{"x":null,"retained":true}}},
        {"method":"unpublish","params":{"pubuid":2}},
        {"method":"setproperties","params":{"name":"/a","update":{"x":null,"retained":true}}},
        {"method":"subscribe","params":{"topics":["","/b"],"subuid":3,"options":{"prefix":true,"all":true,"periodic":0.01,"custom":3}}},
        {"method":"unsubscribe","params":{"subuid":3}},
        {"method":"announce","params":{"name":"/a","id":1,"type":"json","pubuid":2,"properties":{"x":null,"retained":true}}},
        {"method":"unannounce","params":{"name":"/a","id":1}},
        {"method":"properties","params":{"name":"/a","ack":true,"update":{"x":null,"retained":true}}}]
        """;
    assertEquals(JsonParser.parseString(expected), JsonParser.parseString(ControlMessage.encode(messages)));
    assertEquals(messages, ControlMessage.decode(expected));
    assertEquals("[{\"method\":\"announce\",\"params\":{\"name\":\"/x\",\"id\":7,\"type\":\"double\",\"properties\":{}}}]",
        ControlMessage.encode(List.of(new Announce("/x", 7, "double", null, new JsonObject()))));
    assertFalse(new Properties("/x", null, p).json().getAsJsonObject("params").has("ack"));
    p.addProperty("changed", 1); options.addProperty("all", false);
    assertFalse(((Publish) messages.get(0)).properties().has("changed"));
    assertTrue(((Subscribe) messages.get(3)).all());
    ((Publish) messages.get(0)).properties().addProperty("changed", 2);
    assertFalse(((Publish) messages.get(0)).properties().has("changed"));
  }

  @Test void malformedControlsDoNotHideGoodNeighbors() {
    var input = """
        [null,1,{}, {"method":1,"params":{}}, {"method":"future","params":{}},
        {"method":"unpublish","params":[]}, {"method":"publish","params":{"name":[],"pubuid":0,"type":"raw","properties":{}}},
        {"method":"unpublish","params":{"pubuid":2147483648}},
        {"method":"unpublish","params":{"pubuid":1.5}},
        {"method":"unpublish","params":{"pubuid":"1"}},
        {"method":"subscribe","params":{"topics":[1],"subuid":0,"options":{}}},
        {"method":"subscribe","params":{"topics":[""],"subuid":0,"options":{"periodic":"0.01"}}},
        {"method":"properties","params":{"name":"/x","ack":1,"update":{}}},
        {"method":"unpublish","params":{"pubuid":5}}]
        """;
    assertEquals(List.of(new Unpublish(5)), ControlMessage.decode(input));
    assertEquals(List.of(), ControlMessage.decode("{}"));
    for (double period : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
      var options = new JsonObject(); options.addProperty("periodic", period);
      assertThrows(IllegalArgumentException.class, () -> new Subscribe(List.of(""), 0, options));
    }
    var topic = new Announce("/x", 0, "json", null, JsonParser.parseString("{\"x\":1,\"y\":2}").getAsJsonObject());
    var updated = topic.withUpdate(JsonParser.parseString("{\"x\":null,\"z\":3}").getAsJsonObject());
    assertEquals(JsonParser.parseString("{\"y\":2,\"z\":3}"), updated.properties());
  }

  @Test void timeOffsetWindowAndTiesHaveIndependentAnswers() {
    var sync = new TimeSync(1000);
    assertEquals(new TimeSync.Sample(120, 20, 1000), sync.add(100, 120, 1110));
    assertEquals(1000, sync.add(200, 260, 9000).offsetUs()); // Worse RTT must not win.
    assertEquals(2000, sync.add(300, 320, 2310).offsetUs()); // Same RTT, newer sample.
    assertEquals(3000, sync.add(400, 410, 3405).offsetUs());
    assertEquals(3000, sync.best(1409).orElseThrow().offsetUs());
    assertTrue(sync.best(1410).isEmpty());
    assertEquals(new TimeSync.Sample(11, 3, -8), TimeSync.measure(8, 11, 2));
    assertThrows(IllegalArgumentException.class, () -> TimeSync.measure(10, 9, 0));
    assertThrows(ArithmeticException.class, () -> TimeSync.measure(Long.MIN_VALUE, Long.MAX_VALUE, 0));
    assertThrows(IllegalArgumentException.class, () -> new TimeSync(0));
    sync.add(1, 3, 4); sync.clear(); assertTrue(sync.best(3).isEmpty());
  }

  static byte[] join(byte[] a, byte[] b) {
    var result = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, result, a.length, b.length); return result;
  }
  static void assertDeep(Object expected, Object actual) {
    if (expected instanceof byte[] b) assertArrayEquals(b, (byte[]) actual);
    else assertEquals(expected, actual);
  }
}
