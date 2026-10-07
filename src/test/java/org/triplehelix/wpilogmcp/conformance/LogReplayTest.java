/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;

class LogReplayTest {
  @TempDir Path directory;

  @org.junit.jupiter.api.Test void collidingLoggerNamesKeepBothStreams() throws Exception {
    var path = directory.resolve("collision.wpilog");
    try (var out = new WpilogWriter(path, "")) {
      int first = out.start("NT:/x", "double", "first", 1_000_000);
      int second = out.start("/x", "double", "second", 1_000_000);
      out.append(first, 1_000_000, WpilogWriter.encodeDouble(2));
      out.append(second, 1_000_000, WpilogWriter.encodeDouble(3));
    }
    try (var source = new ReplaySource(path)) {
      assertTrue(source.limitations.isEmpty(), "Distinct source entries must remain replayable");
      assertNotEquals(source.topic(source.entries.get("NT:/x")), source.topic(source.entries.get("/x")));
      var report = ReplayAudit.gateway(source, directory.resolve("captured"), 0, Clock.systemUTC());
      assertTrue(((java.util.Map<?, ?>) report.get("mismatches")).isEmpty(), report.toString());
    }
  }

  @org.junit.jupiter.api.TestFactory java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> replayEveryFixture() throws Exception {
    return org.triplehelix.wpilogmcp.fixtures.FixtureLogs.generateAll(directory.resolve("sources")).stream().map(f ->
        org.junit.jupiter.api.DynamicTest.dynamicTest(f.id(), () -> {
          try (var source = new ReplaySource(f.path())) {
            var result = ReplayAudit.gateway(source, directory.resolve(f.id()), 0, Clock.systemUTC());
            java.nio.file.Files.writeString(java.nio.file.Files.createDirectories(Path.of("build/reports/replay"))
                .resolve("gateway-fixture-" + f.id() + ".json"), new com.google.gson.Gson().toJson(result));
            assertTrue(((java.util.Map<?, ?>) result.get("mismatches")).isEmpty(), result.toString());
          }
        }));
  }

  @ParameterizedTest @EnumSource(ReplaySource.Kind.class)
  void conventionNamesTypesPropertiesAndSchemaOrderingArePreserved(ReplaySource.Kind kind) throws Exception {
    var file = directory.resolve("source.wpilog");
    String name = switch (kind) { case DATALOGMANAGER -> "NT:/x"; case ADVANTAGEKIT -> "/RealOutputs/x"; case OTHER -> "x"; };
    String topic = switch (kind) { case DATALOGMANAGER -> "/x"; case ADVANTAGEKIT -> "/AdvantageKit/RealOutputs/x"; case OTHER -> "x"; };
    String schema = kind == ReplaySource.Kind.DATALOGMANAGER ? "NT:/.schema/struct:Test" : "/.schema/struct:Test";
    try (var output = new WpilogWriter(file, kind == ReplaySource.Kind.ADVANTAGEKIT ? "AdvantageKit" : "")) {
      output.start("empty", "raw", "", 1_000_000);
      int id = output.start(name, "struct:Test", "{\"unit\":\"synthetic\"}", 1_000_000);
      output.append(id, 1_000_000, WpilogWriter.encodeDouble(3.5));
      int definition = output.start(schema, "structschema", "", 1_000_000);
      output.append(definition, 1_000_000, WpilogWriter.encodeString("double x;"));
      output.setMetadata(id, "{\"unit\":\"changed\"}", 1_020_000);
      output.append(id, 1_020_000, WpilogWriter.encodeDouble(4.5));
      output.finish(id, 1_040_000);
    }
    var time = new AtomicLong(1_125_000);
    try (var source = new ReplaySource(file);
         var gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), time::get)) {
      assertEquals(kind, source.kind);
      gateway.start().get(10, TimeUnit.SECONDS);
      var replay = new LogReplayer(source, gateway, time::set); replay.announce();
      var pace = new ArrayList<Long>();
      try (var capture = new ReplayCapture(RobotAddress.uri("127.0.0.1", gateway.port(), "replay"), file,
          directory.resolve("captured"), Clock.systemUTC())) {
        capture.ready(replay.topicCount()); replay.replay(125_000, 2, pace::add, capture::receivedThrough, capture::propertiesThrough); capture.stop();
        assertEquals(List.of(0L, 10_000L), pace);
        try (var recorded = new ReplaySource(capture.capture)) {
          var entry = recorded.entries.get("NT:" + topic); assertNotNull(entry); assertEquals("struct:Test", entry.type);
          var metadata = JsonParser.parseString(entry.metadata).getAsJsonObject();
          assertTrue(metadata.has("nt4_properties"), "Capture must retain announced properties");
          assertEquals("{\"unit\":\"synthetic\"}", metadata.getAsJsonObject("nt4_properties").get(LogReplayer.METADATA).getAsString());
          assertEquals(1, recorded.metadataChanges, "Property updates remain in the WPILOG control stream");
          assertEquals(2, entry.count);
          for (int i = 0; i < 2; i++) {
            var value = recorded.reader.at(entry.offset(i));
            assertEquals(1_125_000 + i * 20_000L, value.timestampUs());
            assertArrayEquals(WpilogWriter.encodeDouble(3.5 + i), recorded.reader.payload(value));
          }
          var rootSchema = recorded.entries.get("NT:/.schema/struct:Test"); assertNotNull(rootSchema);
          assertTrue(rootSchema.offset(0) < entry.offset(0), "Schema must precede the first struct value");
        }
        var info = capture.info(capture.capture, "NT:" + topic);
        assertEquals("ok", info.get("status").getAsString()); assertEquals("struct:Test", info.get("type").getAsString());
        var audit = new ReplayAudit().verify(source, capture, 125_000);
        assertTrue(((java.util.Map<?, ?>) audit.get("mismatches")).isEmpty(), audit.toString());
      }
    }
  }
}
