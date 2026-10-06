/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Unannounce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;

class ReplayIntegrationTest {
  @TempDir Path directory;
  private record Expected(String name, String type, long timestamp, byte[] payload) {}
  private record Received(Announce topic, ValueFrame frame) {}

  @TestFactory Stream<DynamicTest> everyFixtureReplayedOverLoopback() throws Exception {
    return FixtureLogs.generateAll(FixtureLogs.defaultDirectory()).stream()
        .map(f -> DynamicTest.dynamicTest(f.id(), () -> replay(f.path())));
  }

  private void replay(Path path) throws Exception {
    var expected = new ArrayList<Expected>();
    var declared = new LinkedHashMap<String, String>();
    var entries = new HashMap<Integer, String[]>();
    // This oracle reads the WPILOG's raw payloads, independently of the replay/type conversion.
    var records = new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(path))).iterator();
    while (records.hasNext()) {
      edu.wpi.first.util.datalog.DataLogRecord record;
      try { record = records.next(); }
      catch (IllegalArgumentException e) {
        assertTrue(path.getFileName().toString().contains("truncated"), "Unexpected fixture damage");
        break;
      }
      if (record.isStart()) {
        var s = record.getStartData(); entries.put(s.entry, new String[] {s.name, s.type}); declared.put(s.name, s.type);
      } else if (record.isFinish()) entries.remove(record.getFinishEntry());
      else if (!record.isControl()) {
        var entry = entries.get(record.getEntry());
        if (entry != null) expected.add(new Expected(entry[0], entry[1], record.getTimestamp(), record.getRaw()));
      }
    }
    expected.sort(Comparator.comparingLong(Expected::timestamp));
    var ready = new CompletableFuture<Void>();
    var done = new CompletableFuture<Void>();
    var received = new ArrayList<Received>();
    var announces = new ArrayList<Announce>();
    var unannounces = new ArrayList<String>();
    var threads = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
    var listener = new Nt4Client.Listener() {
      @Override public void announce(Announce a) {
        threads.add(Thread.currentThread().getId());
        if (a.name().equals("/test/ready")) ready.complete(null); else announces.add(a);
      }
      @Override public void value(Announce a, ValueFrame v, long receivedAt) {
        threads.add(Thread.currentThread().getId());
        assertTrue(announces.stream().anyMatch(topic -> topic.id() == a.id()) || a.name().equals("/test/ready"));
        if (!a.name().equals("/test/ready")) received.add(new Received(a, v));
      }
      @Override public void unannounce(Unannounce a) {
        threads.add(Thread.currentThread().getId());
        if (a.name().equals("/test/ready")) done.complete(null); else unannounces.add(a.name());
      }
    };
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000)) {
      gateway.start().get(5, TimeUnit.SECONDS);
      var replayer = new FixtureReplayer(path);
      replayer.announce(gateway);
      gateway.announce("/test/ready", "int", new JsonObject()).join();
      try (var client = new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", gateway.port(), "fixture")), 0.01, listener)) {
        client.start(); ready.get(10, TimeUnit.SECONDS);
        replayer.replay(gateway, 0, ignored -> fail("Fast replay must not wait"));
        for (var name : declared.keySet()) gateway.unannounce(name).join();
        gateway.unannounce("/test/ready").join(); done.get(30, TimeUnit.SECONDS);
        assertEquals(List.copyOf(declared.keySet()), announces.stream().map(Announce::name).toList());
        assertEquals(List.copyOf(declared.keySet()), unannounces);
        assertEquals(expected.size(), received.size(), "Every value exactly once");
        for (int i = 0; i < expected.size(); i++) {
          var e = expected.get(i); var r = received.get(i);
          assertEquals(e.name(), r.topic().name(), "value order at " + i);
          assertEquals(e.timestamp(), r.frame().timestampUs(), "timestamp at " + i);
          assertEquals(switch (e.type()) { case "int64" -> "int"; case "int64[]" -> "int[]"; default -> e.type(); }, r.topic().type());
          assertArrayEquals(e.payload(), payload(e.type(), r.frame().value()), "payload at " + i + " " + e.name());
        }
        assertEquals(1, threads.size(), "All callbacks share one listener thread");
        assertTrue(client.latestValues().isEmpty()); assertTrue(client.topics().isEmpty());
        assertSchemas(received);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static byte[] payload(String type, Object v) {
    return switch (type) {
      case "boolean" -> WpilogWriter.encodeBoolean((Boolean) v);
      case "double" -> WpilogWriter.encodeDouble((Double) v);
      case "float" -> WpilogWriter.encodeFloat((Float) v);
      case "int64" -> WpilogWriter.encodeInt64((Long) v);
      case "string", "json" -> WpilogWriter.encodeString((String) v);
      case "boolean[]" -> {
        var list = (List<Boolean>) v; var array = new boolean[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = list.get(i);
        yield WpilogWriter.encodeBooleanArray(array);
      }
      case "int64[]" -> WpilogWriter.encodeInt64Array(((List<Long>) v).stream().mapToLong(Long::longValue).toArray());
      case "double[]" -> WpilogWriter.encodeDoubleArray(((List<Double>) v).stream().mapToDouble(Double::doubleValue).toArray());
      case "float[]" -> {
        var list = (List<Float>) v; var array = new float[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = list.get(i);
        yield WpilogWriter.encodeFloatArray(array);
      }
      case "string[]" -> WpilogWriter.encodeStringArray(((List<String>) v).toArray(String[]::new));
      default -> (byte[]) v;
    };
  }

  private static void assertSchemas(List<Received> received) {
    var schemas = new LinkedHashMap<String, String>();
    var names = new LinkedHashMap<String, String>();
    received.stream().filter(r -> r.topic().type().equals("structschema")).forEach(r -> {
      String name = r.topic().name().substring(r.topic().name().indexOf("struct:") + 7);
      schemas.putIfAbsent(name, new String((byte[]) r.frame().value(), java.nio.charset.StandardCharsets.UTF_8));
      names.putIfAbsent(name, r.topic().name());
    });
    var decoder = StructSchemas.of(schemas, names);
    for (var r : received) {
      String type = r.topic().type();
      if (type.startsWith("struct:") && schemas.containsKey(type.substring(7).replace("[]", ""))) {
        if (r.topic().name().equals("/RealOutputs/Arm/Partial") && ((byte[]) r.frame().value()).length == 29) {
          // This fixture deliberately cuts three ArmState records short. Preserve the damage too.
          assertThrows(org.triplehelix.wpilogmcp.log.struct.StructDecodeException.class,
              () -> decoder.decode(type, (byte[]) r.frame().value()));
        } else {
          var decoded = decoder.decode(type, (byte[]) r.frame().value());
          assertNotNull(decoded);
          if (type.equals("struct:Pair")) {
            assertEquals(7L, ((Map<?, ?>) decoded).get("count"));
            assertEquals(2.5, ((Map<?, ?>) decoded).get("speed"));
          }
        }
      }
    }
  }

  @Test void paceIsMeasuredFromFirstTimestampAndStructSchemaMakesKnownValueDecodable() throws Exception {
    var path = directory.resolve("pace.wpilog");
    try (var writer = new WpilogWriter(path, "synthetic NT4 test")) {
      int schema = writer.start("/.schema/struct:Pair", "structschema", "{}", 0);
      writer.append(schema, 100, WpilogWriter.encodeString("int32 count;double speed;"));
      int pair = writer.start("/pair", "struct:Pair", "{}", 0);
      byte[] raw = ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(7).putDouble(2.5).array();
      writer.append(pair, 300, raw); writer.append(pair, 500, raw);
    }
    replay(path);
    var deadlines = new ArrayList<Long>();
    try (var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 0)) {
      gateway.start().get(5, TimeUnit.SECONDS);
      var replayer = new FixtureReplayer(path); replayer.announce(gateway);
      replayer.replay(gateway, 1, deadlines::add); assertEquals(List.of(0L, 200L, 400L), deadlines);
      deadlines.clear(); replayer.replay(gateway, 2, deadlines::add); assertEquals(List.of(0L, 100L, 200L), deadlines);
    }
    var decoded = (Map<?, ?>) StructSchemas.of(Map.of("Pair", "int32 count;double speed;"), Map.of())
        .decode("struct:Pair", ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(7).putDouble(2.5).array());
    assertEquals(7L, decoded.get("count")); assertEquals(2.5, decoded.get("speed"));
  }

  @Test void everyPrimitiveArrayAndOpaqueTypeReplaysIncludingLargeFragmentedValues() throws Exception {
    var path = directory.resolve("all-types.wpilog");
    var payloads = new LinkedHashMap<String, byte[]>();
    payloads.put("boolean", WpilogWriter.encodeBoolean(true));
    payloads.put("int64", WpilogWriter.encodeInt64(Long.MIN_VALUE));
    payloads.put("float", WpilogWriter.encodeFloat(-0.0f));
    payloads.put("double", WpilogWriter.encodeDouble(Double.POSITIVE_INFINITY));
    payloads.put("string", WpilogWriter.encodeString("µs → robot"));
    payloads.put("json", WpilogWriter.encodeString("{\"x\":1}"));
    payloads.put("boolean[]", WpilogWriter.encodeBooleanArray(new boolean[] {false, true}));
    payloads.put("int64[]", WpilogWriter.encodeInt64Array(new long[] {Long.MIN_VALUE, Long.MAX_VALUE}));
    payloads.put("float[]", WpilogWriter.encodeFloatArray(new float[] {Float.NaN, Float.NEGATIVE_INFINITY}));
    payloads.put("double[]", WpilogWriter.encodeDoubleArray(new double[] {-0.0, 0.25}));
    payloads.put("string[]", WpilogWriter.encodeStringArray(new String[] {"", "é"}));
    var large = new byte[4097];
    for (int i = 0; i < large.length; i++) large[i] = (byte) i;
    for (var type : List.of("raw", "rpc", "msgpack", "protobuf:Test", "vendor")) payloads.put(type, large);
    try (var writer = new WpilogWriter(path, "synthetic NT4 type table")) {
      long timestamp = 1234;
      for (var entry : payloads.entrySet()) {
        int id = writer.start("/test/" + entry.getKey(), entry.getKey(), "{}", 0);
        writer.append(id, timestamp++, entry.getValue());
      }
    }
    replay(path);
  }
}
