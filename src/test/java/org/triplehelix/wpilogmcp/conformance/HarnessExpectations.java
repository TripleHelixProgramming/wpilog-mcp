/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;

/** An independent timeline oracle: no robot implementation or captured value supplies an answer. */
final class HarnessExpectations {
  static final List<String> TOPICS = List.of("Sine", "Counter", "Toggle", "String", "Raw", "Pose", "Modules");
  private HarnessExpectations() {}
  private static JsonObject json(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }

  static void verify(JsonObject timeline, Path run, Path store, HarnessHttp http, JsonObject listing,
      List<FakeRoboRio.Read> reads) throws Exception {
    String serial = timeline.get("serial_number").getAsString(); var root = store.resolve("robots").resolve(serial);
    var robot = json(root.resolve("robot.json"));
    assertEquals("device", robot.get("basis").getAsString()); assertEquals(serial, robot.get("serial_number").getAsString());
    assertEquals(timeline.get("comments"), robot.get("comments"));
    var captures = listing.getAsJsonArray("logs").asList().stream().map(e -> e.getAsJsonObject())
        .filter(e -> e.get("path").getAsString().endsWith("capture.wpilog")).toList();
    assertEquals(timeline.getAsJsonArray("boots").size(), captures.size(), "One capture session per scripted boot");
    var ids = new java.util.HashSet<String>();
    for (int bootIndex = 0; bootIndex < timeline.getAsJsonArray("boots").size(); bootIndex++) {
      var boot = timeline.getAsJsonArray("boots").get(bootIndex).getAsJsonObject(); var match = boot.getAsJsonObject("match");
      JsonObject session = null; Path capture = null;
      for (var row : captures) {
        var path = Path.of(row.get("path").getAsString()); assertTrue(path.startsWith(root));
        var manifest = json(path.getParent().resolve("session.json"));
        if (match.get("number").equals(manifest.get("match_number"))) { session = manifest; capture = path; }
      }
      assertNotNull(session, "Missing session for scripted match " + match); assertTrue(ids.add(session.get("id").getAsString()));
      assertEquals(match.get("event"), session.get("event"));
      assertEquals(match.get("type").getAsString(), session.get("match_type").getAsString());
      assertEquals(timeline.get("team_number"), session.get("team_number"));
      assertTrue(session.get("open_capture").isJsonNull());
      var identity = session.getAsJsonObject("device_identity");
      assertEquals(serial, identity.get("serial_number").getAsString());
      assertEquals(timeline.get("comments"), identity.get("comments")); assertTrue(identity.get("host_key_fingerprint").getAsString().startsWith("SHA256:"));
      var identityArgs = new JsonObject(); identityArgs.addProperty("path", capture.toString());
      identityArgs.addProperty("name", "/Daemon/Robot/Identity");
      var context = http.call("read_entry", identityArgs);
      assertEquals("ok", context.get("status").getAsString(), context.toString());
      assertFalse(context.getAsJsonArray("samples").isEmpty());
      for (var sample : context.getAsJsonArray("samples")) {
        var value = sample.getAsJsonObject().get("value");
        var device = value.isJsonObject() ? value.getAsJsonObject() : JsonParser.parseString(value.getAsString()).getAsJsonObject();
        assertEquals("device", device.get("basis").getAsString());
        assertEquals(serial, device.get("serial_number").getAsString());
        assertEquals(timeline.get("comments"), device.get("comments"));
        assertEquals(identity.get("host_key_fingerprint"), device.get("host_key_fingerprint"));
      }
      var pulled = session.getAsJsonArray("files").asList().stream().map(e -> e.getAsJsonObject())
          .filter(e -> e.getAsJsonObject("provenance").get("kind").getAsString().equals("pulled")).toList();
      assertEquals(1, pulled.size(), "A verified robot log must match its capture session: " + session);
      var file = pulled.get(0); assertTrue(file.get("verified").getAsBoolean());
      assertEquals("by_correlation", file.getAsJsonObject("matching").get("method").getAsString());
      assertTrue(Math.abs(file.getAsJsonObject("matching").get("offset_micros").getAsLong()) <= timeline.get("period_us").getAsLong());
      String suffix = "_" + match.get("event").getAsString() + "_" + match.get("type").getAsString().charAt(0) + match.get("number").getAsInt() + ".wpilog";
      assertTrue(file.getAsJsonObject("provenance").get("original_name").getAsString().endsWith(suffix));
      var pulledPath = capture.getParent().resolve(file.get("path").getAsString());
      assertTrue(pulledPath.startsWith(capture.getParent().resolve("robot")));
      assertEquals(Files.size(pulledPath), file.get("size_bytes").getAsLong());
      assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pulledPath))), file.get("sha256").getAsString());
      verifyValues(timeline, boot, bootIndex, capture, http, run);
      verifyValues(timeline, boot, bootIndex, pulledPath, http, run);
      verifyGate(boot, run.resolve("boot-" + bootIndex).resolve("ticks.csv"), reads);
    }
    var progress = json(root.resolve("pull.json"));
    assertEquals(timeline.getAsJsonArray("boots").size(), progress.getAsJsonArray("files").size());
    for (var value : progress.getAsJsonArray("files")) {
      var file = value.getAsJsonObject(); assertTrue(file.get("verified").getAsBoolean());
      assertEquals(file.get("size"), file.get("bytes_copied"));
    }
    var events = timeline.getAsJsonArray("boots").asList().stream()
        .map(b -> "_" + b.getAsJsonObject().getAsJsonObject("match").get("event").getAsString() + "_").toList();
    assertTrue(reads.stream().anyMatch(r -> r.path().endsWith(".wpilog") && events.stream().noneMatch(r.path()::contains)), "Pull began before DataLogManager's match rename");
    for (String event : events) assertTrue(reads.stream().anyMatch(r -> r.path().contains(event)), "Pull followed DataLogManager's match rename: " + event);
  }

  private static void verifyValues(JsonObject timeline, JsonObject boot, int bootIndex, Path file,
      HarnessHttp http, Path run) throws Exception {
    var names = TOPICS.stream().map(t -> "NT:/Harness/" + t).collect(java.util.stream.Collectors.toSet());
    var independent = IndependentLog.read(file, Set.of(), names);
    assertNull(independent.stopped, "The differential scan must reach the end: " + file);
    long start = timeline.get("sample_start_us").getAsLong(), end = timeline.get("sample_end_us").getAsLong(), period = timeline.get("period_us").getAsLong();
    int count = Math.toIntExact((end - start) / period);
    for (String schema : List.of("Pose2d", "Translation2d", "Rotation2d", "SwerveModuleState")) {
      assertTrue(independent.series.containsKey("NT:/.schema/struct:" + schema), "Schema topic " + schema);
    }
    var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String topic : TOPICS) {
      checks.add(() -> {
        String entry = "NT:/Harness/" + topic;
        var series = independent.series.get(entry); assertNotNull(series, entry + " in " + file);
        assertEquals(switch (topic) {
          case "Sine" -> "double"; case "Counter" -> "int64"; case "Toggle" -> "boolean";
          case "String" -> "string"; case "Raw" -> "raw";
          case "Pose" -> "struct:Pose2d"; case "Modules" -> "struct:SwerveModuleState[]";
          default -> throw new AssertionError(topic);
        }, series.type, entry + " type");
        assertEquals(count, series.records, entry + " records in " + file);
        for (int i = 0; i < count; i++) {
          long time = start + i * period;
          assertEquals(time / 1_000_000.0, series.payloadTimes.get(i), 1e-12, entry + " timestamp " + i);
          assertArrayEquals(payload(timeline, boot, bootIndex, topic, i, time), series.payloads.get(i), entry + " value " + i);
        }
        var args = new JsonObject(); args.addProperty("path", file.toString()); args.addProperty("name", entry); args.addProperty("limit", count);
        var result = http.call("read_entry", args);
        Files.writeString(run.resolve("boot-" + bootIndex + "-" + (file.getFileName().toString().equals("capture.wpilog") ? "capture-" : "pulled-") + topic + ".json"), result.toString());
        assertEquals("ok", result.get("status").getAsString(), result.toString()); assertEquals(count, result.get("total_in_range").getAsInt());
        assertEquals(count, result.getAsJsonArray("samples").size(), "HTTP returns every scripted sample");
        for (int i = 0; i < result.getAsJsonArray("samples").size(); i++) {
          var sample = result.getAsJsonArray("samples").get(i).getAsJsonObject(); double seconds = (start + i * period) / 1_000_000.0;
          assertEquals(seconds, sample.get("timestamp_sec").getAsDouble(), 1e-12);
          var value = sample.get("value");
          switch (topic) {
            case "Sine" -> assertEquals(Math.sin(seconds * (2 * Math.PI) * boot.get("sine_hz").getAsDouble()), value.getAsDouble(), 1e-12);
            case "Counter" -> assertEquals(counter(boot, i), value.getAsLong());
            case "Toggle" -> assertEquals((i / 5) % 2 == 0, value.getAsBoolean());
            case "String" -> assertEquals("boot-" + bootIndex + ":tick-" + i, value.getAsString());
            case "Raw" -> { for (int n = 0; n < timeline.get("raw_bytes").getAsInt(); n++) assertEquals((byte) (i + n + bootIndex), value.getAsJsonArray().get(n).getAsByte()); }
            case "Pose" -> {
              assertEquals(seconds, value.getAsJsonObject().getAsJsonObject("translation").get("x").getAsDouble(), 1e-12);
              assertEquals(-seconds / 2, value.getAsJsonObject().getAsJsonObject("translation").get("y").getAsDouble(), 1e-12);
              assertEquals(seconds / 8, value.getAsJsonObject().getAsJsonObject("rotation").get("value").getAsDouble(), 1e-12);
            }
            case "Modules" -> {
              assertEquals(2, value.getAsJsonArray().size());
              assertEquals(seconds, value.getAsJsonArray().get(0).getAsJsonObject().get("speed").getAsDouble(), 1e-12);
              assertEquals(-seconds, value.getAsJsonArray().get(1).getAsJsonObject().get("speed").getAsDouble(), 1e-12);
              assertEquals(seconds / 9, value.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("angle").get("value").getAsDouble(), 1e-12);
              assertEquals(-seconds / 10, value.getAsJsonArray().get(1).getAsJsonObject().getAsJsonObject("angle").get("value").getAsDouble(), 1e-12);
            }
            default -> throw new AssertionError(topic);
          }
        }
      });
    }
    assertAll(file.toString(), checks);
  }

  static byte[] payload(JsonObject timeline, JsonObject boot, int bootIndex, String topic, int i, long timeUs) {
    double t = timeUs / 1_000_000.0;
    return switch (topic) {
      case "Sine" -> doubles(Math.sin(2 * Math.PI * boot.get("sine_hz").getAsDouble() * t));
      case "Counter" -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(counter(boot, i)).array();
      case "Toggle" -> new byte[] {(byte) ((i / 5) % 2 == 0 ? 1 : 0)};
      case "String" -> ("boot-" + bootIndex + ":tick-" + i).getBytes(StandardCharsets.UTF_8);
      case "Raw" -> { byte[] raw = new byte[timeline.get("raw_bytes").getAsInt()]; for (int n = 0; n < raw.length; n++) raw[n] = (byte) (i + n + bootIndex); yield raw; }
      case "Pose" -> doubles(t, -t / 2, t / 8);
      case "Modules" -> doubles(t, t / 9, -t, -t / 10);
      default -> throw new AssertionError(topic);
    };
  }
  private static long counter(JsonObject boot, int tick) {
    int reset = boot.getAsJsonArray("counter_resets").asList().stream().mapToInt(v -> v.getAsInt()).filter(v -> v <= tick).max().orElseThrow();
    return tick - reset;
  }
  private static byte[] doubles(double... values) {
    var bytes = ByteBuffer.allocate(8 * values.length).order(ByteOrder.LITTLE_ENDIAN); for (double value : values) bytes.putDouble(value); return bytes.array();
  }
  private record Tick(long robotUs, long wallUs) {}
  private static void verifyGate(JsonObject boot, Path ticksFile, List<FakeRoboRio.Read> reads) throws Exception {
    var ticks = Files.readAllLines(ticksFile).stream().map(line -> line.split(",")).map(v -> new Tick(Long.parseLong(v[0]), Long.parseLong(v[1]))).toList();
    var phases = boot.getAsJsonArray("phases"); long enabledBytes = 0, disabledBytes = 0;
    for (var read : reads) {
      if (!read.path().endsWith(".wpilog") || read.wallTimeUs() < ticks.get(0).wallUs() || read.wallTimeUs() > ticks.get(ticks.size() - 1).wallUs()) continue;
      int low = 0, high = ticks.size();
      while (low + 1 < high) {
        int middle = (low + high) >>> 1;
        if (ticks.get(middle).wallUs() <= read.wallTimeUs()) low = middle; else high = middle;
      }
      var tick = ticks.get(low);
      String state = "disabled";
      for (var value : phases) { var phase = value.getAsJsonObject(); if (phase.get("at_us").getAsLong() <= tick.robotUs()) state = phase.get("state").getAsString(); }
      if (state.equals("disabled")) disabledBytes += read.bytes(); else enabledBytes += read.bytes();
    }
    assertTrue(disabledBytes > 0, "Pull gate must actually open during disabled");
    assertTrue(enabledBytes <= 65536, "At most the block in progress may finish on enable; read " + enabledBytes + " bytes");
  }
}
