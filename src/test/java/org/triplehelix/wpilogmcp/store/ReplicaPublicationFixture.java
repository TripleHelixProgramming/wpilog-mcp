/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;

/** A real bounded catalog can be filled with synthetic metadata without copying thousands of logs. */
public final class ReplicaPublicationFixture {
  private ReplicaPublicationFixture() {}

  /** The caller imports and migrates one seed so every fabricated row has valid payload facts. */
  public static StoreManifest.Header fillCatalog(LogStore store, LogManager manager, int count)
      throws Exception {
    var header = store.replicaHeader();
    var seed = header.replicas().files().get(0);
    var entries = new ArrayList<ReplicaCatalog.Entry>();
    entries.add(seed);
    for (int i = 1; i < count; i++) {
      var identity = new ReplicaCatalog.Identity(header.id(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
      entries.add(new ReplicaCatalog.Entry(UUID.randomUUID().toString(), identity,
          "synthetic-" + i + ".wpilog", seed.sha256(), seed.sizeBytes(), "wpilog", null,
          "completed_import", "master_local", true,
          new ReplicaCatalog.Confirmation(new ReplicaCatalog.MasterRecord(identity,
              header.replicas().epoch(), 2, seed.sha256(), seed.sizeBytes(), "completed_import"),
              "local_master_catalog", "2026-10-20T12:00:00Z"), null, seed.placement()));
    }
    var filled = header.withReplicas(new ReplicaCatalog(1, header.replicas().epoch(), 2,
        entries, List.of(), List.of()));
    new StoreFiles(store.root(), manager.testGetSecurityValidator())
        .writeDurable(store.root().resolve("store.json"), filled);
    return filled;
  }

  /** The manual writer feeds synthetic events directly and never creates an NT4 client. */
  public static Path completedCapture(LogStore store, Clock clock, long value) throws Exception {
    var placement = store.captures(clock);
    var loop = new ManualScheduler();
    Path captured;
    try (var writer = new CaptureWriter(clock, loop, CapturePolicy.ALL, placement)) {
      writer.connected(RobotAddress.uri("127.0.0.1", 1, "synthetic-writer"), "networktables.first.wpi.edu");
      writer.timeSync(10_000_000, 0);
      var topic = new ControlMessage.Announce("/Value", 1, "int", null, new JsonObject());
      writer.announce(topic);
      loop.until(writer::ready);
      writer.value(topic, new ValueFrame(1, 10_000_001, 2, value), 0);
      captured = writer.session().path();
    }
    placement.completion().get(10, TimeUnit.SECONDS);
    store.awaitImports();
    return captured;
  }

  /** Runs {@code action} as each later publication recovery pass selects its store, before its first turn. */
  public static void onPublicationStart(LogStore store, PassStart action) {
    store.publicationStartProbe(identity -> action.run());
  }
  @FunctionalInterface public interface PassStart { void run() throws java.io.IOException; }
}
