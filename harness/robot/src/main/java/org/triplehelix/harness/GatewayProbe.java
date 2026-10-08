/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.harness;

import edu.wpi.first.networktables.GenericSubscriber;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.PubSubOption;
import edu.wpi.first.util.datalog.DataLogWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** An independent ntcore client records the gateway's wire timestamps, not this JVM's local ones.
 * The server-side harness verifier computes its answers from the timeline, never from this log. */
final class GatewayProbe implements AutoCloseable {
  private static final List<String> SIGNALS = List.of("Sine", "Counter", "Toggle", "String", "Raw", "Pose", "Modules");
  private final NetworkTableInstance nt = NetworkTableInstance.create();
  private final Map<String, GenericSubscriber> subscriptions = new LinkedHashMap<>();
  private final Map<String, Integer> entries = new LinkedHashMap<>();
  private final java.util.concurrent.ScheduledExecutorService reader = Executors.newSingleThreadScheduledExecutor(r -> {
    var t = new Thread(r, "gateway-ntcore-probe"); t.setDaemon(true); return t;
  });
  private final DataLogWriter log;
  private final Path control;
  private final java.util.concurrent.ScheduledFuture<?> task;
  private boolean ready;

  GatewayProbe(int port, Path control, Path output) throws Exception {
    this.control = control;
    Files.createDirectories(output.getParent()); log = new DataLogWriter(output.toString(), "synthetic gateway observations");
    for (String name : SIGNALS) subscribe("/Harness/" + name);
    for (String schema : List.of("Pose2d", "Translation2d", "Rotation2d", "SwerveModuleState")) subscribe("/.schema/struct:" + schema);
    nt.setServer("127.0.0.1", port); nt.startClient4("harness-gateway-probe");
    task = reader.scheduleAtFixedRate(() -> {
      try { drain(); }
      catch (Throwable error) { error.printStackTrace(); Runtime.getRuntime().halt(1); }
    }, 0, 10, TimeUnit.MILLISECONDS);
  }
  private void subscribe(String name) {
    subscriptions.put(name, nt.getTopic(name).genericSubscribe("", PubSubOption.sendAll(true),
        PubSubOption.keepDuplicates(true), PubSubOption.periodic(0.01), PubSubOption.pollStorage(8192)));
  }
  private void drain() throws Exception {
    for (var entry : subscriptions.entrySet()) {
      var topic = entry.getValue().getTopic();
      for (var value : entry.getValue().readQueue()) {
        if (!value.isValid()) continue;
        String type = topic.getTypeString();
        long timestamp = value.getServerTime();
        int id = entries.computeIfAbsent(entry.getKey(), name -> log.start("NT:" + name,
            type.equals("int") ? "int64" : type, topic.getProperties(), timestamp));
        switch (value.getType()) {
          case kBoolean -> log.appendBoolean(id, value.getBoolean(), timestamp);
          case kInteger -> log.appendInteger(id, value.getInteger(), timestamp);
          case kDouble -> log.appendDouble(id, value.getDouble(), timestamp);
          case kString -> log.appendString(id, value.getString(), timestamp);
          case kRaw -> log.appendRaw(id, value.getRaw(), timestamp);
          default -> throw new IllegalStateException("Unexpected scripted topic type " + type);
        }
      }
    }
    log.flush();
    if (!ready && nt.isConnected() && SIGNALS.stream().allMatch(name -> subscriptions.get("/Harness/" + name).getTopic().exists())) {
      Files.writeString(control.resolve("gateway-ready"), "ready\n"); ready = true;
    }
  }
  @Override public void close() throws Exception {
    task.cancel(false);
    reader.submit(() -> {
      try { drain(); log.close(); }
      catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
    }).get(10, TimeUnit.SECONDS);
    subscriptions.values().forEach(GenericSubscriber::close); nt.close(); reader.shutdown();
  }
}
