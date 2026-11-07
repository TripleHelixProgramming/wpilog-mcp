/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CaptureIndex;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.tools.SignalResolver;

class ContextRecordTest {
  @TempDir Path directory;
  @Test void providerEntriesRedeclareOnRolloverAndMatchBothReadersIncludingMetadataChanges() throws Exception {
    var loop = new ManualScheduler(); var manager = LogManager.getInstance(); var saved = manager.getAllowedDirectories(); manager.addAllowedDirectory(directory);
    var index = new CaptureIndex(new CaptureWriter.Observer() {
      public Path create(String address, Instant start) { return directory.resolve("capture.wpilog"); }
    }, manager, 0);
    String name = "/Daemon/roboRIO/cpu_busy_fraction"; var metadata = new JsonObject();
    metadata.addProperty("source", "ssh"); metadata.addProperty("sampled", true); metadata.addProperty("period_sec", 2);
    var expected = new ArrayList<Double>(); long bytes = 0;
    try (var writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL, index, 1024)) {
      assertEquals(0, writer.recordContext(name, "double", .5, 10, metadata));
      writer.connected(RobotAddress.uri("127.0.0.1", 5810, "context"), "networktables.first.wpi.edu");
      writer.timeSync(1_000_000, 0); writer.announce(new Announce("/anchor", 1, "int", null, new JsonObject()));
      for (int n = 0; n < 140; n++) {
        if (n == 20) metadata.addProperty("period_sec", 4);
        double value = n / 10.0; expected.add(value);
        bytes += writer.recordContext(name, "double", value, 1_000_000L + n * 1_000L, metadata);
      }
      try (var live = manager.acquire(writer.session().path().toString())) {
        assertEquals(metadata.toString(), live.log().entries().get(name).metadata());
      }
      writer.disconnected(); assertTrue(writer.session().files().size() >= 3);
      long foundBytes = 0; var found = new ArrayList<Double>(); int n = 0;
      for (var file : writer.session().files()) {
        Path path = directory.resolve(file.name()); assertTrue(Files.size(path) <= 1024);
        var independent = IndependentLog.read(path, java.util.Set.of(name));
        var series = independent.series.get(name); assertNotNull(series);
        for (int k = 0; k < series.n; k++) {
          assertEquals(1 + n * .001, series.times[k], .0000001); found.add(series.values[k]); n++;
        }
        var ids = new java.util.HashSet<Long>();
        try (var records = new IndependentLog.Records(path)) {
          for (int pos = records.first;;) {
            var record = records.at(pos); if (record == null) break; pos = record.end();
            if (records.control(record) == 0 && records.start(record).name().equals(name)) ids.add(records.start(record).id());
            if (ids.contains(record.id())) foundBytes += record.end() - record.offset();
          }
        }
        var reader = new edu.wpi.first.util.datalog.DataLogReader(ByteBuffer.wrap(Files.readAllBytes(path)));
        assertTrue(reader.isValid()); var values = new ArrayList<Double>();
        for (var record : reader) if (!record.isControl() && ids.contains((long) record.getEntry())) values.add(record.getDouble());
        assertEquals(List.of(java.util.Arrays.stream(java.util.Arrays.copyOf(series.values, series.n)).boxed().toArray(Double[]::new)), values);
        manager.unloadLog(path.toString());
        try (var fresh = manager.acquire(path.toString())) {
          assertEquals(series.n, fresh.log().sampleCount(name));
          assertTrue(fresh.log().entries().get(name).metadata().contains("sampled"));
        }
      }
      assertEquals(expected, found); assertEquals(bytes, foundBytes, "Costs include exactly provider data records and headers");
    } finally { manager.release(directory); manager.clearAllowedDirectories(); saved.forEach(manager::addAllowedDirectory); }
  }

  @Test void allFourTailRolesHaveAnExactConventionAndUnknownNamesRemainOrdinaryText() throws Exception {
    var file = directory.resolve("tails.wpilog");
    try (var output = new org.triplehelix.wpilogmcp.fixtures.WpilogWriter(file, "synthetic tail roles")) {
      for (String role : List.of("program_console", "kernel", "syslog", "journal", "custom")) {
        output.append(output.start("/Daemon/Tail/host/" + role, "string", "", 0), 1_000_000, "synthetic line".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      output.start("/other/program_console", "string", "", 0);
      output.start("/Daemon/Tail/other/program_console", "double", "", 0);
    }
    try (var log = new org.triplehelix.wpilogmcp.log.LazyParsedLog(file.toString(),
        new edu.wpi.first.util.datalog.DataLogReader(ByteBuffer.wrap(Files.readAllBytes(file))), 1 << 20)) {
      assertEquals(Map.of("program_console", List.of("/Daemon/Tail/host/program_console"), "kernel", List.of("/Daemon/Tail/host/kernel"),
          "syslog", List.of("/Daemon/Tail/host/syslog"), "journal", List.of("/Daemon/Tail/host/journal")), SignalResolver.followedText(log));
    }
  }
}
