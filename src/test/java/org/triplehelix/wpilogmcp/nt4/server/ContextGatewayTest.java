/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4.server;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureService;
import org.triplehelix.wpilogmcp.capture.CaptureWriter;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;

/** A provider's already-recorded timestamp must survive both network forwarding and remote capture. */
class ContextGatewayTest {
  @TempDir Path temp;
  private record Sample(String name, String type, Object value, long time, JsonObject metadata) {}
  private record Observed(Announce topic, ValueFrame value) {}
  private static JsonObject metadata(String source) {
    var result = new JsonObject(); result.addProperty("source", source);
    result.addProperty("timestamp_basis", "receipt mapped through NT4 server time"); return result;
  }
  private CaptureConfig config(int robot, int gateway, String folder) {
    return new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", robot, folder)), temp.resolve(folder),
        .001, CapturePolicy.ALL, 600_000_000, 1 << 20, PullConfig.DISABLED, gateway);
  }
  private static CaptureWriter writer(CaptureService capture) throws Exception {
    var field = CaptureService.class.getDeclaredField("writer"); field.setAccessible(true); return (CaptureWriter) field.get(capture);
  }
  private static void record(CaptureService capture, ClientScheduler loop, Sample sample) throws Exception {
    var writer = writer(capture); var complete = new CompletableFuture<Integer>();
    loop.execute(() -> { try { complete.complete(writer.recordContext(sample.name(), sample.type(), sample.value(), sample.time(), sample.metadata())); }
      catch (Throwable error) { complete.completeExceptionally(error); } });
    assertTrue(complete.get(5, TimeUnit.SECONDS) > 0, "The fixture must first write the provider sample");
  }
  private static Nt4Client subscriber(int port, String name, LinkedBlockingQueue<Observed> values) {
    return new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", port, name)), .001, new Nt4Client.Listener() {
      @Override public void value(Announce topic, ValueFrame value, long received) {
        if (topic.name().startsWith("/Daemon/")) values.add(new Observed(topic, value));
      }
    });
  }
  private static Observed next(LinkedBlockingQueue<Observed> values) throws Exception {
    var value = values.poll(3, TimeUnit.SECONDS); assertNotNull(value, "Recorded provider sample must reach the gateway subscriber"); return value;
  }

  /** Hold only the pull worker's handoff; network callbacks continue on the real ordered loop. */
  private static final class HeldLoop implements ClientScheduler {
    final ClientScheduler delegate = ClientScheduler.daemon();
    final java.util.concurrent.atomic.AtomicLong time = new java.util.concurrent.atomic.AtomicLong();
    final LinkedBlockingQueue<Runnable> held = new LinkedBlockingQueue<>();
    volatile Thread pull;
    public long nowUs() { return time.get(); }
    public void execute(Runnable action) { if (Thread.currentThread() == pull) held.add(action); else delegate.execute(action); }
    public void schedule(Runnable action, long delayUs) { delegate.schedule(action, delayUs); }
    public void close() { delegate.close(); }
    public void awaitTermination(long timeoutUs) throws InterruptedException { delegate.awaitTermination(timeoutUs); }
    void release() throws Exception {
      var task = held.poll(3, TimeUnit.SECONDS); assertNotNull(task, "Pull must hand off bounded delivery"); delegate.execute(task);
    }
  }

  @Test void unmappedReceiptTimePrecedesDeliveryWaitAndCloseRejectsALateHandoff() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    try (var robot = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 20_000_000)) {
      robot.start().get(5, TimeUnit.SECONDS); robot.announce("/trigger", "int", new JsonObject()).join();
      int port = CaptureGatewayTest.port(); var loop = new HeldLoop(); var config = config(robot.port(), port, "receipt");
      try (var pit = new CaptureService(config, manager, Clock.systemUTC(), loop)) {
        var device = org.triplehelix.wpilogmcp.capture.pull.FakeRobot.device("synthetic-receipt", "SHA256:synthetic");
        writer(pit).identity(device); pit.start().get(5, TimeUnit.SECONDS);
        HarnessHttp.await("identified capture", 5, () -> pit.live().current() != null && pit.live().current().open());
        var values = new LinkedBlockingQueue<Observed>();
        try (var downstream = subscriber(port, "receipt-reader", values)) {
          downstream.start(); next(values); // Retained identity.
          var local = manager.stores().store(config.store()).systemPulls(device, Clock.systemUTC()); assertTrue(local.beginPass());
          var first = worker.submit(() -> { loop.pull = Thread.currentThread(); local.kernel(List.of("unknown time"), 30); return null; });
          HarnessHttp.await("first text handoff", 5, () -> !loop.held.isEmpty());
          loop.time.set(1_000_000); loop.release(); first.get(3, TimeUnit.SECONDS);
          var line = next(values); assertEquals("/Daemon/System/kernel/unmapped", line.topic().name());
          assertEquals(20_000_000, line.value().timestampUs(), "One second of capture-loop delay must not become receipt time");
          var second = worker.submit(() -> { local.kernel(List.of("unknown time", "after close"), 31); return null; });
          HarnessHttp.await("second text handoff", 5, () -> !loop.held.isEmpty());
          var field = CaptureService.class.getDeclaredField("textPublisher"); field.setAccessible(true); ((AutoCloseable) field.get(pit)).close();
          loop.release(); second.get(3, TimeUnit.SECONDS);
          assertNull(values.poll(100, TimeUnit.MILLISECONDS), "A handoff queued before close cannot publish afterwards");
        }
      }
    } finally { worker.shutdownNow(); manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }

  @Test void providerValuesKeepTheirTypesTimestampsMetadataAndLatestValueOverTheGateway() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    try (var robot = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000)) {
      robot.start().get(5, TimeUnit.SECONDS); robot.announce("/trigger", "int", new JsonObject()).join();
      int port = CaptureGatewayTest.port(); var loop = ClientScheduler.daemon();
      try (var pit = new CaptureService(config(robot.port(), port, "pit"), manager, Clock.systemUTC(), loop)) {
        pit.start().get(5, TimeUnit.SECONDS);
        HarnessHttp.await("pit recording", 5, () -> pit.live().current() != null && pit.live().current().open());
        var values = new LinkedBlockingQueue<Observed>();
        try (var downstream = subscriber(port, "live", values)) {
          downstream.start(); HarnessHttp.await("downstream subscription", 5, () -> downstream.topics().containsKey("/trigger"));
          var samples = List.of(new Sample("/Daemon/JVM/heap/used_bytes", "int64", 2048L, 9_000_123, metadata("jmx")),
              new Sample("/Daemon/roboRIO/uptime_sec", "double", 50.25, 9_100_456, metadata("ssh")),
              new Sample("/Daemon/Tail/robot/program_console", "string", "synthetic line", 9_200_789, metadata("tail")),
              new Sample("/Daemon/PhotonVision/front/Settings", "json", "{\"camera\":\"front\"}", 9_300_012, metadata("photonvision")));
          for (var sample : samples) {
            record(pit, loop, sample); var seen = next(values);
            assertEquals(sample.name(), seen.topic().name()); assertEquals(sample.type().equals("int64") ? "int" : sample.type(), seen.topic().type());
            assertEquals(sample.time(), seen.value().timestampUs()); assertEquals(sample.value(), seen.value().value());
            assertEquals(sample.metadata(), seen.topic().properties().getAsJsonObject("wpilog_context").getAsJsonObject("metadata"));
          }
          var changed = metadata("jmx"); changed.addProperty("period_sec", 2.0);
          record(pit, loop, new Sample(samples.get(0).name(), "int64", 4096L, 9_400_345, changed));
          var seen = next(values); assertEquals(changed, seen.topic().properties().getAsJsonObject("wpilog_context").getAsJsonObject("metadata"));
          assertEquals(9_400_345, seen.value().timestampUs()); assertEquals(4096L, seen.value().value());
          var retained = new LinkedBlockingQueue<Observed>();
          try (var later = subscriber(port, "late", retained)) {
            later.start();
            var held = new java.util.HashMap<String, Observed>();
            for (int i = 0; i < samples.size(); i++) { var value = next(retained); held.put(value.topic().name(), value); }
            assertEquals(4096L, held.get(samples.get(0).name()).value().value());
            assertEquals(9_400_345, held.get(samples.get(0).name()).value().timestampUs());
          }
          robot.unannounce("/trigger").join(); robot.dropClients().join();
          HarnessHttp.await("provider declarations cleared on disconnect", 5, () -> downstream.topics().keySet().stream().noneMatch(n -> n.startsWith("/Daemon/")));
        }
      }
    } finally { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }

  @Test void explicitContextPropertiesKeepTheProviderEntryAndMetadataInRemoteRecordings() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    Path file;
    var metadata = metadata("jmx"); metadata.addProperty("sampled", true);
    var envelope = new JsonObject(); envelope.addProperty("version", 1); envelope.add("metadata", metadata);
    var properties = new JsonObject(); properties.add("wpilog_context", envelope);
    try (var source = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 10_000_000)) {
      source.start().get(5, TimeUnit.SECONDS); source.announce("/Daemon/JVM/heap/used_bytes", "int", properties).join();
      source.announce("/Daemon/ordinary", "int", new JsonObject()).join();
      try (var remote = new CaptureService(config(source.port(), 0, "remote"), manager)) {
        remote.start().get(5, TimeUnit.SECONDS);
        HarnessHttp.await("remote recording", 5, () -> remote.live().current() != null && remote.live().current().open());
        source.value("/Daemon/JVM/heap/used_bytes", 9_123_456, 2, 2048L).join();
        source.value("/Daemon/ordinary", 9_123_457, 2, 7L).join();
        HarnessHttp.await("remote samples", 5, () -> remote.live().current().statistics().records() >= 2);
        file = remote.live().sessions().get(0).directory().resolve("capture.wpilog");
      }
      try (var use = manager.acquire(file.toString())) {
        var log = use.log(); var entry = log.entries().get("/Daemon/JVM/heap/used_bytes");
        assertNotNull(entry, "An explicit gateway context keeps its canonical provider entry name");
        assertEquals("int64", entry.type());
        var captured = JsonParser.parseString(entry.metadata()).getAsJsonObject();
        assertEquals("jmx", captured.get("source").getAsString()); assertTrue(captured.get("sampled").getAsBoolean());
        assertEquals(properties, captured.getAsJsonObject("nt4_properties"));
        var values = log.values().get(entry.name()); assertEquals(1, values.size());
        assertEquals(9.123456, values.get(0).timestamp(), 1e-12); assertEquals(2048L, values.get(0).value());
        assertTrue(log.entries().containsKey("NT:/Daemon/ordinary"), "A name alone never claims provider provenance");
      }
    } finally { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }

  @Test void committedKernelLinesUseTheServersClockMappingAndOnlyNewLinesArePublished() throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    try (var robot = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), () -> 20_000_000)) {
      robot.start().get(5, TimeUnit.SECONDS); robot.announce("/trigger", "int", new JsonObject()).join();
      int port = CaptureGatewayTest.port(); var loop = ClientScheduler.daemon(); var config = config(robot.port(), port, "kernel");
      try (var pit = new CaptureService(config, manager, Clock.systemUTC(), loop)) {
        var device = org.triplehelix.wpilogmcp.capture.pull.FakeRobot.device("synthetic-context", "SHA256:synthetic");
        writer(pit).identity(device); pit.start().get(5, TimeUnit.SECONDS);
        HarnessHttp.await("identified capture", 5, () -> pit.live().current() != null && pit.live().current().open());
        record(pit, loop, new Sample("/Daemon/roboRIO/uptime_sec", "double", 50.0, 10_000_000, metadata("ssh")));
        record(pit, loop, new Sample("/Daemon/roboRIO/uptime_sec", "double", 60.0, 20_000_000, metadata("ssh")));
        var values = new LinkedBlockingQueue<Observed>();
        try (var downstream = subscriber(port, "kernel-reader", values)) {
          downstream.start(); next(values); next(values); // Retained identity and uptime samples.
          var local = manager.stores().store(config.store()).systemPulls(device, Clock.systemUTC()); assertTrue(local.beginPass());
          local.kernel(List.of("[55.0] synthetic warning", "no written timestamp"), 61);
          var mapped = next(values); assertEquals("/Daemon/System/kernel", mapped.topic().name());
          assertEquals(15_000_000, mapped.value().timestampUs());
          var body = JsonParser.parseString((String) mapped.value().value()).getAsJsonObject();
          assertEquals("[55.0] synthetic warning", body.get("text").getAsString());
          assertEquals("[55.0]", body.get("original_timestamp").getAsString());
          assertEquals("uptime_pairing", body.get("timestamp_basis").getAsString()); assertEquals(15.0, body.get("timestamp_sec").getAsDouble());
          var unmapped = next(values); assertEquals("/Daemon/System/kernel/unmapped", unmapped.topic().name());
          var unknown = JsonParser.parseString((String) unmapped.value().value()).getAsJsonObject();
          assertTrue(unknown.get("timestamp_sec").isJsonNull()); assertTrue(unknown.get("timestamp_reason").getAsString().contains("No unambiguous"));
          local.kernel(List.of("[55.0] synthetic warning", "no written timestamp"), 62);
          local.kernel(List.of("[55.0] synthetic warning", "no written timestamp", "[56.0] next synthetic"), 63);
          var added = next(values); assertEquals(16_000_000, added.value().timestampUs());
          assertTrue(values.isEmpty(), "A repeated durable cursor must not replay old kernel lines");
          long epoch = java.time.Instant.parse("2026-03-07T14:22:33Z").getEpochSecond();
          robot.announce("systemTime", "int", new JsonObject()).join();
          robot.value("systemTime", 10_000_000, 2, epoch * 1_000_000).join();
          HarnessHttp.await("wall clock sample", 5, () -> pit.live().latest().containsKey("systemTime"));
          local.journal(List.of((epoch + 5) + ".250 host synthetic journal"), "cursor-1", "boot");
          var journal = next(values); assertEquals("/Daemon/System/syslog", journal.topic().name());
          assertEquals(15_250_000, journal.value().timestampUs());
          assertEquals("system_time", JsonParser.parseString((String) journal.value().value()).getAsJsonObject().get("timestamp_basis").getAsString());
          local.sources(java.util.Map.of("/var/log/messages", "syslog"));
          String staged = local.create("messages");
          byte[] before = "2026-03-07T14:22:38Z first file line\n2026-03-07T14:22:39Z partial".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          local.append(staged, 0, before);
          var receipt = new org.triplehelix.wpilogmcp.sync.PullManifest.Entry("/var/log/messages", before.length, 0, before.length, true, staged, 0, null);
          local.verified(receipt); assertEquals(15_000_000, next(values).value().timestampUs());
          local.verified(receipt); assertTrue(values.isEmpty(), "An identical file receipt is not new text");
          byte[] suffix = " line\n".getBytes(java.nio.charset.StandardCharsets.UTF_8); local.append(staged, before.length, suffix);
          local.verified(new org.triplehelix.wpilogmcp.sync.PullManifest.Entry(receipt.remoteName(), before.length + suffix.length, 1, before.length + suffix.length, true, staged, 0, null));
          var completed = next(values); assertEquals(16_000_000, completed.value().timestampUs());
          assertTrue(((String) completed.value().value()).contains("partial line")); assertTrue(values.isEmpty());
          String rotated = local.create("messages");
          byte[] fresh = "2026-03-07T14:22:38Z x\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          local.append(rotated, 0, fresh);
          local.verified(new org.triplehelix.wpilogmcp.sync.PullManifest.Entry(receipt.remoteName(), fresh.length, 2, fresh.length, true, rotated, 0, null));
          assertEquals(15_000_000, next(values).value().timestampUs());
          byte[] growth = "2026-03-07T14:22:39Z y\n".getBytes(java.nio.charset.StandardCharsets.UTF_8); local.append(rotated, fresh.length, growth);
          local.verified(new org.triplehelix.wpilogmcp.sync.PullManifest.Entry(receipt.remoteName(), fresh.length + growth.length, 3, fresh.length + growth.length, true, rotated, 0, null));
          assertEquals(16_000_000, next(values).value().timestampUs(), "Only the appended line is published after rotation");
          assertTrue(values.isEmpty());
        }
      }
    } finally { manager.release(temp); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
}
