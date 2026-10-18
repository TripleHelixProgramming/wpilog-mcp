/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import java.util.Set;
import org.triplehelix.wpilogmcp.capture.*;
import org.triplehelix.wpilogmcp.log.*;
import org.triplehelix.wpilogmcp.mcp.*;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.*;
import org.triplehelix.wpilogmcp.store.*;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;
import org.triplehelix.wpilogmcp.tools.VisualizationTools;
import org.triplehelix.wpilogmcp.visualization.ServerIdentity;

/** Foreground real HTTP owner with an active synthetic recording and a finalized rollover.
 * Its stdin lifetime belongs solely to the isolated editor runner, never an installed service. */
public final class OverviewServerFixture {
  private OverviewServerFixture() {}
  // Replace a few synthetic names only in the panel fixture; the paged-overview fixture keeps
  // its all-integer inventory. The union still has exactly 20,000 names.
  private static String pickerName(int n) {
    return switch (n) {
      case 7 -> "/.schema/struct:PickerTranslation";
      case 8 -> "/.schema/struct:PickerPose";
      case 10 -> "/.schema/struct:PickerConflict";
      default -> RecordingFixture.name(n);
    };
  }
  private static String pickerType(int n) {
    return switch (n) {
      case 5, 11 -> "double[]";
      case 6 -> "struct:PickerPose";
      case 7, 8, 10 -> "structschema";
      case 9 -> "struct:PickerConflict";
      case 12 -> "struct:MissingPickerSchema";
      default -> "int";
    };
  }
  private static Object pickerValue(int n, int sample) {
    return switch (n) {
      case 5 -> java.util.List.of(2.0 + sample * 6, 6.0 + sample * 6, 10.0 + sample * 6);
      case 6 -> WpilogWriter.encodeDoubleArray(new double[] {2 + sample * 6, 3 + sample * 6, 4 + sample * 6});
      case 7 -> WpilogWriter.encodeString("double x;double y;");
      case 8 -> WpilogWriter.encodeString("PickerTranslation translation;double speed;");
      case 10 -> WpilogWriter.encodeString(sample == 0 ? "double x;" : "int64 x;");
      case 9, 12 -> WpilogWriter.encodeDouble(7);
      case 11 -> java.util.List.of();
      default -> (long) n + Math.max(0, sample - 1);
    };
  }
  public static void main(String[] args) throws Exception {
    var fixture = new RecordingFixture(Path.of(args[0]), 0); int names = 20_000;
    boolean fields = args.length >= 3;
    var logs = LogManager.getInstance(); logs.addAllowedDirectory(fixture.root);
    var file = fixture.directory.resolve("capture.wpilog"); Files.delete(file);
    var index = new CaptureIndex((address, start) -> file, logs, 64L << 20);
    var writer = new CaptureWriter(Clock.fixed(Instant.parse(RecordingFixture.WHEN), ZoneOffset.UTC), new ManualScheduler(), CapturePolicy.ALL, index);
    var registry = new ToolRegistry(); registry.serverIdentity(ServerIdentity.open(fixture.root.resolveSibling("fixture-state")));
    VisualizationTools.registerAll(registry);
    var server = new HttpTransport(registry, Integer.parseInt(args[1]));
    try {
      writer.connected(RobotAddress.uri("127.0.0.1", 5810, "overview-fixture"), "networktables.first.wpi.edu"); writer.timeSync(1_000_000, 0);
      for (int n = 0; n < names; n++) {
        if (n >= names / 2 && n % 2 != 0) continue;
        String type = fields ? pickerType(n) : "int";
        var entry = new Announce(fields ? pickerName(n) : RecordingFixture.name(n), n + 1, type, null, new JsonObject());
        writer.announce(entry); writer.value(entry, new ValueFrame(n + 1, 1_000_000,
            org.triplehelix.wpilogmcp.nt4.NtType.fromNt4(type).code(), fields ? pickerValue(n, 0) : (long) n), 0);
      }
      String metadata;
      try (var use = logs.acquire(file.toString())) { metadata = use.log().entries().get("NT:" + RecordingFixture.name(0)).metadata(); }
      fixture.rewrite(1, out -> {
        for (int n = 0; n < names; n++) {
          if (n >= names / 2 && n % 2 == 0) continue;
          String type = fields ? pickerType(n) : "int";
          var nt = org.triplehelix.wpilogmcp.nt4.NtType.fromNt4(type);
          int id = out.start("NT:" + (fields ? pickerName(n) : RecordingFixture.name(n)), nt.wpilog(), metadata, 0);
          out.append(id, 1_000_000, WpilogOutput.payload(nt.code(), fields ? pickerValue(n, 1) : (long) n));
          out.append(id, 2_000_000, WpilogOutput.payload(nt.code(), fields ? pickerValue(n, 2) : (long) n + 1));
        }
      });
      var manifest = fixture.directory.resolve("session.json");
      var session = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
      session.getAsJsonArray("files").remove(0);
      var open = StoreJson.JSON.toJsonTree(new OpenCapture("capture.wpilog",
          new Provenance("captured", null, "capture.wpilog", RecordingFixture.WHEN, false), Files.size(file), 1, 2)).getAsJsonObject();
      try (var use = logs.acquire(file.toString())) { open.getAsJsonObject("provenance").addProperty("writer_identity", ((LiveLog.View) use.log()).captureIdentity()); }
      session.add("open_capture", open); Files.writeString(manifest, session.toString()); logs.catalogPublished();
      server.setStoreDirectories(Set.of(fixture.root)); server.start();
      try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
        String line;
        while ((line = input.readLine()) != null) {
          if (args.length < 3) continue;
          var command = JsonParser.parseString(line).getAsJsonObject();
          var answer = new JsonObject(); answer.add("id", command.get("id"));
          if (command.get("op").getAsString().equals("append")) {
            long value = command.get("value").getAsLong();
            var entry = new Announce(RecordingFixture.name(0), 1, "int", null, new JsonObject());
            long timestamp = command.has("at_us") ? command.get("at_us").getAsLong() : 1_000_000;
            writer.value(entry, new ValueFrame(1, timestamp, 2, value), 0);
            answer.addProperty("value", value);
          } else if (command.get("op").getAsString().equals("chartAppend")) {
            var entry = new Announce(RecordingFixture.name(5), 6, "double[]", null, new JsonObject());
            writer.value(entry, new ValueFrame(6, 1_500_000, org.triplehelix.wpilogmcp.nt4.NtType.fromNt4("double[]").code(),
                java.util.List.of(20.0, 24.0, 28.0)), 0);
          } else if (command.get("op").getAsString().equals("historyAdvance") || command.get("op").getAsString().equals("historyLate")) {
            boolean advance = command.get("op").getAsString().equals("historyAdvance");
            if (advance) writer.timeSync(120_000_000, 0);
            var entry = new Announce(RecordingFixture.name(5), 6, "double[]", null, new JsonObject());
            writer.value(entry, new ValueFrame(6, advance ? 120_000_000 : 1_250_000,
                org.triplehelix.wpilogmcp.nt4.NtType.fromNt4("double[]").code(),
                advance ? java.util.List.of(30.0, 34.0, 38.0) : java.util.List.of(26.0, 30.0, 34.0)), 0);
          } else if (command.get("op").getAsString().equals("finalize")) {
            writer.close(); var before = StoreJson.JSON.fromJson(Files.readString(manifest), Session.class);
            var members = new java.util.ArrayList<>(before.files());
            String hash = LogStore.contentHash(file);
            members.add(new LogFile("capture.wpilog", hash, Files.size(file), "wpilog", before.openCapture().provenance(),
                true, 0, 0, RecordingFixture.WHEN, RecordingFixture.WHEN, "pit_clock", false, null));
            Files.writeString(manifest, StoreJson.JSON.toJson(new Session(before.id(), before.startedAt(), before.endedAt(), before.startBasis(),
                before.event(), before.matchType(), before.matchNumber(), before.teamNumber(), members)));
            logs.catalogPublished(); answer.addProperty("hash", hash);
          } else throw new IllegalArgumentException("Unknown synthetic command");
          Files.writeString(Path.of(args[2]), answer.toString());
        }
      }
    } finally { server.stop(); writer.close(); logs.release(fixture.root); }
  }
}
