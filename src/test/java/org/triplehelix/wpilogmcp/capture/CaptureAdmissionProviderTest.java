/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.context.ContextProviders;
import org.triplehelix.wpilogmcp.capture.context.TailCommand;
import org.triplehelix.wpilogmcp.config.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.*;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.ssh.*;

class CaptureAdmissionProviderTest {
  @TempDir Path directory;
  private static final class Steps implements ClientScheduler {
    record Timer(long at, Runnable action) {}
    final java.util.concurrent.LinkedBlockingDeque<Runnable> queue = new java.util.concurrent.LinkedBlockingDeque<>();
    final java.util.ArrayList<Timer> timers = new java.util.ArrayList<>(); long now;
    public long nowUs() { return now; }
    public void execute(Runnable task) { queue.add(task); }
    public void schedule(Runnable task, long delay) { timers.add(new Timer(now + delay, task)); }
    public void close() { timers.clear(); }
    void one() throws Exception { var task = queue.poll(5, TimeUnit.SECONDS); assertNotNull(task); task.run(); }
    void drain() { Runnable task; while ((task = queue.poll()) != null) task.run(); }
    void advance(long elapsed) { now += elapsed; var due = timers.stream().filter(t -> t.at() <= now).toList(); timers.removeAll(due); due.forEach(t -> t.action().run()); }
  }
  @Test void tailKeepsItsBufferedLineUntilAdmissionReplayFinishes() throws Exception {
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(directory);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var rio = new org.triplehelix.wpilogmcp.harness.FakeRoboRio(directory.resolve("remote"), "ADMISSION-TAIL", "");
        var robot = new Nt4Gateway(new java.net.InetSocketAddress("127.0.0.1", 0), () -> 1_000_000, List.of(Nt4Client.V40))) {
      robot.start().get(5, TimeUnit.SECONDS);
      var address = RobotAddress.uri("127.0.0.1", robot.port(), "provider-admission");
      try (var client = new Nt4Client(List.of(address), .02, new Nt4Client.Listener() {})) {
        client.start(); await(() -> client.timeEstimate().isPresent());
        var loop = new Steps(); var connections = new ManualScheduler(); var worker = new ManualScheduler();
        var wall = Clock.systemUTC(); var live = new LiveCapture(directory, loop); live.attach(client);
        var auth = new PullConfig.Ssh("lvuser", "", null, false, rio.port());
        var tail = new ProviderConfig.Tail(null, auth, "/console", "program_console");
        rio.script(TailCommand.open(tail.path(), tail.role()), out -> {
          out.write((TailCommand.FOLLOW + "\nretained during admission\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.flush(); release.await();
        });
        var config = new CaptureConfig(List.of(address), directory, .02, CapturePolicy.ALL, 0, 1 << 20,
            new PullConfig(false, List.of(), 0, 1_000_000, auth), 0,
            new ProviderConfig(true, ProviderConfig.DISABLED.stats(), List.of(tail)));
        var pending = new CompletableFuture<Path>(); var output = directory.resolve("capture.wpilog");
        try (var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, new CaptureWriter.Observer() {
          public Path create(String host, java.time.Instant start) { return output; }
          public CompletableFuture<Path> createAsync(String host, java.time.Instant start, CaptureWriter.Session previous,
              org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity) { return pending; }
        }, CaptureWriter.DEFAULT_MAX_FILE_BYTES, WpilogOutput::new, new CaptureWriter.AdmissionLimits(1 << 20, 1000, 30_000_000, 1, 1_000_000))) {
          var pool = new SharedSsh(host -> CompletableFuture.completedFuture(null), JschConnection::connect, host -> connections);
          var providers = new ContextProviders(config, writer, live, loop, manager.stores().store(directory), wall, pool, worker);
          try {
            providers.connected("127.0.0.1"); connections.drain(); providers.start();
            await(() -> { loop.drain(); return !live.providers().isEmpty() && live.providers().get(0).linesPerSec() > 0; });
            writer.connected(address, Nt4Client.V40); writer.timeSync(1_000_000, 0);
            var topic = new Announce("/anchor", 1, "int", null, new JsonObject()); writer.announce(topic);
            writer.value(topic, new ValueFrame(1, 1_000_001, 2, 1L), 1);
            pending.complete(output); loop.one(); assertNotNull(writer.session()); assertFalse(writer.ready());
            loop.advance(100_000); // A provider tick between replay slices must not consume its tail buffer.
            loop.drain(); assertTrue(writer.ready()); loop.advance(100_000); loop.drain();
            writer.disconnected();
            var strings = new java.util.ArrayList<String>(); var names = new java.util.HashMap<Integer, String>();
            for (var record : new edu.wpi.first.util.datalog.DataLogReader(java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(output)))) {
              if (record.isStart()) { var entry = record.getStartData(); names.put(entry.entry, entry.name); }
              if (!record.isControl() && names.getOrDefault(record.getEntry(), "").startsWith("/Daemon/Tail/")) strings.add(record.getString());
            }
            assertEquals(List.of("retained during admission"), strings);
          } finally { release.countDown(); var closed = providers.closeAsync(); connections.advance(250_000); assertTrue(closed.isDone()); }
        }
      }
    } finally { release.countDown(); manager.release(directory); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
  private static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
    assertTrue(condition.getAsBoolean());
  }
}
