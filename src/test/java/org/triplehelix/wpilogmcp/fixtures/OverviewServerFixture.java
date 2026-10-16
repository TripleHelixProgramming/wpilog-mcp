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
  public static void main(String[] args) throws Exception {
    var fixture = new RecordingFixture(Path.of(args[0]), 0); int names = 20_000;
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
        var entry = new Announce(RecordingFixture.name(n), n + 1, "int", null, new JsonObject());
        writer.announce(entry); writer.value(entry, new ValueFrame(n + 1, 1_000_000, 2, (long) n), 0);
      }
      String metadata;
      try (var use = logs.acquire(file.toString())) { metadata = use.log().entries().get("NT:" + RecordingFixture.name(0)).metadata(); }
      fixture.rewrite(1, out -> {
        for (int n = 0; n < names; n++) {
          if (n >= names / 2 && n % 2 == 0) continue;
          int id = out.start("NT:" + RecordingFixture.name(n), "int64", metadata, 0);
          out.append(id, 1_000_000, WpilogWriter.encodeInt64(n));
          out.append(id, 2_000_000, WpilogWriter.encodeInt64(n + 1));
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
      while (System.in.read() != -1) { /* runner owns lifetime; no product test route */ }
    } finally { server.stop(); writer.close(); logs.release(fixture.root); }
  }
}
