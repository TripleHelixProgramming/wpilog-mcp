/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.triplehelix.wpilogmcp.capture.CaptureIndex;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.log.LiveLog;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** Test-only stdin control beside the real capture/index/HTTP owners. No product test route,
 * robot connection, user store or downloaded recording is involved. The Node driver computes
 * its own expected ordinals and values instead of asking another server tool for the answer. */
public final class FollowBrokerFixture {
  private static final String WHEN = "2026-01-01T00:00:00Z";
  private static final String STORE = "11111111-1111-4111-8111-111111111111";
  private static final String SESSION = "22222222-2222-4222-8222-222222222222";
  private final LogManager logs = LogManager.getInstance();
  private final Path root, file, manifest;
  private final Announce x = new Announce("/x", 1, "int", null, new JsonObject());
  private final Announce y = new Announce("/y", 2, "double", null, new JsonObject());
  private final boolean direct;
  private CaptureWriter writer;
  private HttpTransport transport;

  private FollowBrokerFixture(Path root, boolean emptyAnalysis, boolean direct) throws Exception {
    this.direct = direct;
    this.root = Files.createDirectories(root).toRealPath(); logs.addAllowedDirectory(this.root);
    if (direct) org.triplehelix.wpilogmcp.log.LogDirectory.getInstance().setLogDirectory(this.root.toString());
    var robot = Files.createDirectories(this.root.resolve("robots/fixture"));
    var directory = Files.createDirectories(robot.resolve("sessions/2026-01-01/session"));
    file = directory.resolve("capture.wpilog"); manifest = directory.resolve("session.json");
    Files.writeString(this.root.resolve("store.json"), StoreJson.JSON.toJson(new Header(1, WHEN, STORE, List.of())));
    Files.writeString(robot.resolve("robot.json"), StoreJson.JSON.toJson(new Robot("fixture", "fixture", null, null, "logged")));
    var index = emptyAnalysis ? new CaptureIndex((address, start) -> file, logs, 10_000_000, () -> 3_000_000.0)
        : new CaptureIndex((address, start) -> file, logs, 10_000_000);
    writer = new CaptureWriter(Clock.fixed(Instant.parse(WHEN), ZoneOffset.UTC), new ManualScheduler(), CapturePolicy.ALL, index);
    writer.connected(RobotAddress.uri("127.0.0.1", 5810, "broker-fixture"), "networktables.first.wpi.edu");
    writer.timeSync(1_000_000, 0); writer.announce(x); writer.announce(y);
    if (!emptyAnalysis) {
      append(100, 0);
      writer.value(y, new ValueFrame(2, 100, 1, -0.0d), 0);
    }
    var open = new OpenCapture("capture.wpilog", new Provenance("captured", null, "capture.wpilog", WHEN, false), Files.size(file), 1, 2);
    var session = StoreJson.JSON.toJsonTree(new Session(SESSION, WHEN, WHEN, "pit_clock", null, null, null, null, List.of(), open)).getAsJsonObject();
    try (var use = logs.acquire(file.toString())) {
      session.getAsJsonObject("open_capture").getAsJsonObject("provenance").addProperty("writer_identity", ((LiveLog.View) use.log()).captureIdentity());
    }
    Files.writeString(manifest, session.toString()); start(0);
  }
  private void start(int port) throws Exception {
    var registry = new ToolRegistry(); org.triplehelix.wpilogmcp.tools.VisualizationTools.registerAll(registry);
    if (direct) { registry.serverIdentity(org.triplehelix.wpilogmcp.visualization.ServerIdentity.open(root.resolveSibling("state"))); org.triplehelix.wpilogmcp.tools.CoreTools.registerAll(registry); }
    transport = new HttpTransport(registry, port); transport.setStoreDirectories(Set.of(root)); transport.start();
  }
  private void append(long timestamp, long n) { writer.value(x, new ValueFrame(1, timestamp, 2, 9_007_199_254_740_993L + n), 0); }
  private JsonObject command(JsonObject request) throws Exception {
    var result = new JsonObject();
    switch (request.get("op").getAsString()) {
      case "analysis-append" -> writer.value(x, new ValueFrame(1, 2_500_000, 2, 41L), 0);
      case "append" -> {
        int first = request.get("first").getAsInt(), count = request.get("count").getAsInt();
        for (int n = first; n < first + count; n++) append(n % 2 == 0 ? 100 : 50, n);
        result.addProperty("count", count);
      }
      case "arrays" -> {
        var arrays = new Announce("/arrays", 5, "double[]", null, new JsonObject()); writer.announce(arrays);
        for (int n = 0; n < 4096; n++) {
          var values = new ArrayList<Double>(19);
          for (int column = 0; column < 19; column++) values.add(n * 32 + column + 0.25);
          writer.value(arrays, new ValueFrame(5, n, 17, values), 0);
        }
      }
      case "topics" -> {
        int count = request.get("count").getAsInt();
        if (count < 2 || count > 10000) throw new IllegalArgumentException("Synthetic topic count outside fixture bound");
        var rollover = file.resolveSibling("rollover.wpilog");
        try (var use = logs.acquire(file.toString()); var out = new WpilogWriter(rollover, "")) {
          int xId = out.start("NT:/x", "int64", direct ? use.log().entries().get("NT:/x").metadata() : "", 0); out.append(xId, -10, WpilogWriter.encodeInt64(-7));
          out.start("NT:/y", "double", direct ? use.log().entries().get("NT:/y").metadata() : "", 0);
          for (int n = 0; n < count - 2; n++) {
            String name = "/inputs/synthetic/subsystem/signal-" + String.format(java.util.Locale.ROOT, "%05d", n);
            var properties = new JsonObject(); properties.addProperty("unit", "synthetic");
            writer.announce(new Announce(name, n + 20, "double", null, properties));
            out.start("NT:" + name, "double", "{\"unit\":\"synthetic\"}", 0);
          }
        }
        String hash = LogStore.contentHash(rollover); var session = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        var member = new LogFile("rollover.wpilog", hash, Files.size(rollover), "wpilog",
            new Provenance("captured", null, "rollover.wpilog", WHEN, false), true, 0, 0, WHEN, WHEN, "pit_clock", false, null);
        session.getAsJsonArray("files").add(StoreJson.JSON.toJsonTree(member)); Files.writeString(manifest, session.toString()); logs.catalogPublished();
        result.addProperty("rollover_hash", hash); result.addProperty("topics_per_member", count);
      }
      case "metadata" -> { writer.announce(new Announce("/late", 3, "int", null, new JsonObject())); }
      case "gap" -> {
        var raw = new Announce("/raw", 4, "raw", null, new JsonObject()); writer.announce(raw);
        writer.value(raw, new ValueFrame(4, 25, 5, new byte[80_000]), 0);
      }
      case "restart" -> { int port = transport.getPort(); transport.stop(); start(port); }
      case "finalize" -> {
        writer.close(); writer = null;
        var before = StoreJson.JSON.fromJson(Files.readString(manifest), Session.class);
        String hash = LogStore.contentHash(file);
        var closed = new LogFile("capture.wpilog", hash, Files.size(file), "wpilog", before.openCapture().provenance(),
            true, 0, 0, WHEN, WHEN, "pit_clock", false, null);
        var members = new ArrayList<>(before.files()); members.add(closed);
        Files.writeString(manifest, StoreJson.JSON.toJson(new Session(SESSION, WHEN, WHEN, "pit_clock", null, null, null, null, members)));
        logs.catalogPublished(); result.addProperty("hash", hash);
      }
      default -> throw new IllegalArgumentException("Unknown fixture command");
    }
    result.addProperty("op", request.get("op").getAsString()); return result;
  }
  private static void reply(JsonObject value) { System.out.println("BROKER " + value); System.out.flush(); }
  public static void main(String[] args) throws Exception {
    var fixture = new FollowBrokerFixture(Path.of(args[0]), args.length > 1 && args[1].equals("empty-analysis"), args.length > 1 && args[1].equals("direct"));
    try {
      var ready = new JsonObject(); ready.addProperty("port", fixture.transport.getPort()); ready.addProperty("file", fixture.file.toString());
      ready.add("configuration", org.triplehelix.wpilogmcp.visualization.VisualizationContracts.blank()); reply(ready);
      try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = input.readLine()) != null) reply(fixture.command(JsonParser.parseString(line).getAsJsonObject()));
      }
    } finally {
      if (fixture.writer != null) fixture.writer.close(); fixture.transport.stop(); fixture.logs.release(fixture.root);
    }
  }
}
