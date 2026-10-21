/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import edu.wpi.first.util.datalog.DataLogReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Properties;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Unannounce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.LoopWatchdog;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.read.ReadQuery;
import org.triplehelix.wpilogmcp.read.http.RemoteLogAccess;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreRegistry;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/**
 * A sustained real-loopback workload measures capture separately from its competing readers.
 * The slow inspection callback is the admission test's seam: baseline failures are measured,
 * never fabricated by shortening admission or making the writer fail.
 */
@Tag("architecture-hardening-soak")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArchitectureHardeningSoakTest {
  private static final int TOPICS = 100;
  private static final int RATE_HZ = 50;
  private static final int READ_SAMPLES = 50_000;
  private static final long START_US = 10_000_000;
  private static final long PERIOD_US = 1_000_000 / RATE_HZ;
  private static final long FORCE_BOUND_NS = TimeUnit.SECONDS.toNanos(2);

  @Test @Order(1) void measureUnstalledManyFileImport() throws Exception {
    String label = System.getProperty("hardening.soak.label", "full");
    assertTrue(label.matches("[A-Za-z0-9_-]+"));
    var directory = Files.createDirectories(Path.of("build/reports/architecture-hardening"));
    var work = Files.createTempDirectory(directory, "import-timing-" + label + "-").toRealPath();
    var manager = LogManager.getInstance();
    var savedAllowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(work);
    var security = new org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator();
    security.addAllowedDirectory(work);
    var report = new JsonObject();
    var firstCallbackNs = new AtomicLong();
    long start = 0;
    long complete = 0;
    try (var stores = new StoreRegistry(security)) {
      var paths = new ArrayList<Path>();
      long bytes = 0;
      for (int i = 0; i < 500; i++) {
        var path = ImportFixture.write(work.resolve("sources").resolve("source-" + i + ".wpilog"), i);
        paths.add(path);
        bytes += Files.size(path);
      }
      var store = stores.store(work.resolve("store"));
      start = System.nanoTime();
      var result = store.importPaths(new LogStore.Request(paths, false, null),
          progress -> firstCallbackNs.compareAndSet(0, System.nanoTime())).get(5, TimeUnit.MINUTES);
      complete = System.nanoTime();
      assertEquals(paths.size(), result.files().size(), "One outcome for each distinct synthetic file");
      assertTrue(result.files().stream().noneMatch(file -> "refused".equals(file.status())),
          () -> result.files().stream().filter(file -> "refused".equals(file.status())).toList().toString());
      report.addProperty("synthetic", true);
      report.addProperty("file_count", paths.size());
      report.addProperty("input_bytes", bytes);
      report.addProperty("outcome_count", result.files().size());
      report.addProperty("injected_delay_ms", 0);
      report.addProperty("caller_elapsed_ms", (complete - start) / 1e6);
      report.addProperty("callback_to_completion_ms", (complete - firstCallbackNs.get()) / 1e6);
      report.addProperty("before_first_callback_ms", (firstCallbackNs.get() - start) / 1e6);
      report.addProperty("store_queue_turn_max_ms", stores.queueMetrics().longestTurnUs() / 1e3);
      report.addProperty("store_queue_over_budget_turns", stores.queueMetrics().overBudgetTurns());
      report.addProperty("measurement_basis",
          "An otherwise idle store. Caller elapsed includes queue/lock admission and completion; "
          + "first progress callback to future completion excludes prior admission and setup. "
          + "Neither interval is claimed as a queue-turn duration; store_queue_turn_max_ms "
          + "comes from the separate outside turn observer.");
      Files.writeString(directory.resolve("import-timing-" + label + ".json"),
          new GsonBuilder().setPrettyPrinting().create().toJson(report) + System.lineSeparator());
      System.out.println("Architecture hardening import timing: " + report);
    } finally {
      manager.release(work);
      manager.clearAllowedDirectories();
      savedAllowed.forEach(manager::addAllowedDirectory);
    }
  }

  @Test @Order(2) void captureKeepsEveryGeneratedValueDuringImportAndHttpReads() throws Exception {
    int seconds = Integer.getInteger("hardening.soak.seconds", 600);
    assertTrue(seconds >= 120, "A shortened baseline must still run for at least two minutes");
    String label = System.getProperty("hardening.soak.label", "full");
    assertTrue(label.matches("[A-Za-z0-9_-]+"));
    var reportDirectory = Files.createDirectories(Path.of("build/reports/architecture-hardening"));
    var work = Files.createTempDirectory(reportDirectory, "soak-" + label + "-").toRealPath();
    var manager = LogManager.getInstance();
    var savedAllowed = manager.getAllowedDirectories();
    var savedDirectories = LogDirectory.getInstance().getLogDirectories();
    manager.addAllowedDirectory(work);
    LogDirectory.getInstance().setLogDirectory(work.toString());
    var report = new JsonObject();
    report.addProperty("label", label);
    report.addProperty("synthetic", true);
    report.addProperty("duration_sec", seconds);
    report.addProperty("topics", TOPICS);
    report.addProperty("rate_hz_per_topic", RATE_HZ);
    report.addProperty("import_file_count", 128);
    report.addProperty("injected_import_inspection_delay_ms", 35_000);
    report.addProperty("work_directory", work.toString());
    report.addProperty("store_queue_turn_measurement",
        "Maximum independently observed store turn, including an in-progress turn; one-second budget.");
    var received = new AtomicLong();
    var sent = new AtomicLong();
    var robot = new AtomicLong(START_US);
    var force = new ForceMeasurements();
    var monitor = new Monitor();
    var stopReads = new AtomicBoolean();
    var readFailure = new AtomicReference<Throwable>();
    var mcpCalls = new AtomicLong();
    var exactCalls = new AtomicLong();
    var exactSamples = new AtomicLong();
    var inspected = new CountDownLatch(1);
    var releaseImport = new CountDownLatch(1);
    var heldOnce = new AtomicBoolean();
    var inspectionDelayNs = new AtomicLong();
    var importWallNs = new AtomicLong();
    var importFailure = new AtomicReference<Throwable>();
    var loop = ClientScheduler.daemon("hardening-soak-listener");
    var readers = Executors.newFixedThreadPool(2, runnable -> {
      var thread = new Thread(runnable, "hardening-soak-reader");
      thread.setDaemon(true);
      return thread;
    });
    HttpTransport transport = null;
    Nt4Client client = null;
    CaptureWriter writer = null;
    Nt4Gateway gateway = null;
    CompletableFuture<LogStore.Result> importing = null;
    long started = System.nanoTime();
    long recorded = 0;
    long checked = 0;
    String admissionFailure = null;
    try {
      var fixture = readFixture(work.resolve("reads.wpilog"));
      var imports = new ArrayList<Path>();
      for (int i = 0; i < 128; i++) {
        imports.add(ImportFixture.write(work.resolve("imports").resolve("source-" + i + ".wpilog"), i));
      }
      var store = manager.stores().store(work.resolve("store"));
      var placement = store.captures(Clock.systemUTC());
      // Initialize/recover before starting the slow import, as normal server startup does.
      placement.recoverAsync().get(30, TimeUnit.SECONDS);
      writer = new CaptureWriter(Clock.systemUTC(), loop, CapturePolicy.ALL,
          new CaptureIndex(placement, manager, 0), CaptureWriter.DEFAULT_MAX_FILE_BYTES, force::open,
          CaptureWriter.ADMISSION_LIMITS);
      var recorder = writer;
      gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), robot::get, List.of(Nt4Client.V40));
      gateway.start().get(10, TimeUnit.SECONDS);
      var listener = new Nt4Client.Listener() {
        @Override public void connected(URI address, String protocol) { recorder.connected(address, protocol); }
        @Override public void disconnected() { recorder.disconnected(); }
        @Override public void announce(Announce topic) { recorder.announce(topic); }
        @Override public void unannounce(Unannounce topic) { recorder.unannounce(topic); }
        @Override public void properties(Properties update) { recorder.properties(update); }
        @Override public void timeSync(long time, long receipt) { recorder.timeSync(time, receipt); }
        @Override public void invalidValue(Announce topic, int type) { recorder.invalidValue(topic, type); }
        @Override public void value(Announce topic, ValueFrame value, long receipt) {
          received.incrementAndGet();
          recorder.value(topic, value, receipt);
        }
      };
      client = new Nt4Client(List.of(RobotAddress.uri("127.0.0.1", gateway.port(), "hardening-soak")),
          Nt4Client.captureSubscription(.001), listener, HttpClient.newHttpClient(), loop);
      client.start();
      var connectedClient = client;
      HarnessHttp.await("synthetic NT4 connection and clock", 15,
          () -> connectedClient.isConnected() && connectedClient.timeEstimate().isPresent());
      var tools = new ToolRegistry();
      WpilogTools.registerAll(tools);
      transport = new HttpTransport(tools, 0);
      transport.setStoreDirectories(java.util.Set.of(work));
      transport.start();
      int port = transport.getPort();
      readers.submit(() -> mcpReads(port, fixture, stopReads, readFailure, mcpCalls));
      readers.submit(() -> exactReads(port, fixture, stopReads, readFailure, exactCalls, exactSamples));
      monitor.start(loop);
      long importStart = System.nanoTime();
      importing = store.importPaths(new LogStore.Request(imports, false, null), progress -> {
        if (progress.phase().equals("inspecting") && heldOnce.compareAndSet(false, true)) {
          long before = System.nanoTime();
          inspected.countDown();
          try { releaseImport.await(35, TimeUnit.SECONDS); }
          catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
          inspectionDelayNs.set(System.nanoTime() - before);
        }
      });
      importing.whenComplete((result, failure) -> {
        importWallNs.set(System.nanoTime() - importStart);
        importFailure.set(failure);
      });
      assertTrue(inspected.await(15, TimeUnit.SECONDS), "Import must be inspecting before capture admission");
      for (int topic = 0; topic < TOPICS; topic++) {
        gateway.announce(name(topic), "int", new JsonObject()).get(10, TimeUnit.SECONDS);
      }
      HarnessHttp.await("all synthetic topic declarations", 15, () -> connectedClient.topics().size() == TOPICS);
      long streamStart = System.nanoTime();
      int ticks = Math.multiplyExact(seconds, RATE_HZ);
      for (int tick = 0; tick < ticks; tick++) {
        long due = streamStart + TimeUnit.MICROSECONDS.toNanos(tick * PERIOD_US);
        while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
        long timestamp = START_US + tick * PERIOD_US;
        robot.set(timestamp);
        var batch = new ArrayList<CompletableFuture<Void>>();
        for (int topic = 0; topic < TOPICS; topic++) {
          batch.add(gateway.value(name(topic), timestamp, 2, value(topic, tick)));
        }
        CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        sent.addAndGet(TOPICS);
      }
      report.addProperty("stream_elapsed_sec", (System.nanoTime() - streamStart) / 1e9);
      HarnessHttp.awaitProgress("last NT4 values", 15, received::get, () -> received.get() == sent.get());
      releaseImport.countDown();
      var imported = importing.get(60, TimeUnit.SECONDS);
      assertEquals(imports.size(), imported.files().size(), "Every synthetic import has an outcome");
      assertTrue(imported.files().stream().noneMatch(file -> "refused".equals(file.status())),
          () -> "Concurrent capture must not prevent import publication: " + imported.files());
      report.addProperty("import_outcome_count", imported.files().size());
      client.closeAsync().get(30, TimeUnit.SECONDS);
      client = null;
      admissionFailure = recorder.admissionFailure();
      placement.completion().get(30, TimeUnit.SECONDS);
      stopReads.set(true);
      readers.shutdown();
      assertTrue(readers.awaitTermination(30, TimeUnit.SECONDS), "HTTP readers must finish");
      monitor.close();
      // WPILib's independent reader checks both values and timestamps, not the server's count.
      var counts = new long[TOPICS];
      try (var paths = Files.walk(store.root())) {
        for (var path : paths.filter(p -> p.toString().endsWith(".wpilog")).toList()) {
          var ids = new HashMap<Integer, Integer>();
          for (var record : new DataLogReader(ByteBuffer.wrap(Files.readAllBytes(path)))) {
            if (record.isStart()) {
              var entry = record.getStartData();
              String prefix = "NT:/architecture-soak/topic-";
              if (entry.name.startsWith(prefix)) ids.put(entry.entry, Integer.parseInt(entry.name.substring(prefix.length())));
            } else if (!record.isControl() && ids.containsKey(record.getEntry())) {
              int topic = ids.get(record.getEntry());
              long tick = counts[topic]++;
              assertEquals(START_US + tick * PERIOD_US, record.getTimestamp(), "Exact timestamp for " + name(topic));
              assertEquals(value(topic, tick), record.getInteger(), "Exact value for " + name(topic));
              checked++;
            }
          }
        }
      }
      recorded = java.util.Arrays.stream(counts).sum();
      var topicCounts = new com.google.gson.JsonArray();
      for (long count : counts) topicCounts.add(count);
      report.add("recorded_values_per_topic", topicCounts);
      report.addProperty("admission_failure", admissionFailure);
      long actualRecorded = recorded;
      String actualFailure = admissionFailure;
      assertAll(
          () -> assertNull(actualFailure, "Capture admission must survive a competing import"),
          () -> assertEquals((long) ticks * TOPICS, sent.get(), "Generator count"),
          () -> assertEquals(sent.get(), actualRecorded, "Every generated value must be recorded"),
          () -> assertTrue(monitor.longestNs.get() <= TimeUnit.MICROSECONDS.toNanos(LoopWatchdog.STALL_US),
              "Listener loop exceeded one second: " + monitor.longestNs.get() / 1e6 + " ms"),
          () -> assertTrue(manager.stores().queueMetrics().longestTurnUs() <= 1_000_000,
              "Store owner exceeded one second: " + manager.stores().queueMetrics().longestTurnUs() + " us"),
          () -> assertEquals(0, manager.stores().queueMetrics().overBudgetTurns(),
              "No store turn may exceed the independently observed budget"),
          () -> assertTrue(force.count.get() > 0, "Recording must force periodically"),
          () -> assertTrue(force.longestGapNs.get() <= FORCE_BOUND_NS,
              "More than two seconds between completed forces: " + force.longestGapNs.get() / 1e6 + " ms"),
          () -> assertTrue(force.longestNs.get() <= FORCE_BOUND_NS,
              "A force took more than two seconds: " + force.longestNs.get() / 1e6 + " ms"),
          () -> assertTrue(monitor.peakHeapBytes.get() < Runtime.getRuntime().maxMemory(), "Heap must remain below its maximum"),
          () -> assertNull(readFailure.get(), "Concurrent HTTP reads failed"),
          () -> assertNull(importFailure.get(), "Concurrent import failed"),
          () -> assertTrue(mcpCalls.get() > 0 && exactCalls.get() > 0, "Both HTTP read paths must do work"));
    } finally {
      stopReads.set(true);
      releaseImport.countDown();
      monitor.close();
      readers.shutdownNow();
      if (client != null) client.closeAsync().get(30, TimeUnit.SECONDS);
      if (writer != null) writer.close();
      if (gateway != null) gateway.close();
      loop.close();
      loop.awaitTermination(TimeUnit.SECONDS.toMicros(30));
      if (transport != null) transport.stop();
      manager.release(work);
      manager.clearAllowedDirectories();
      savedAllowed.forEach(manager::addAllowedDirectory);
      LogDirectory.getInstance().setLogDirectories(savedDirectories.stream().map(Path::toString).toList());
      report.addProperty("wall_time_sec", (System.nanoTime() - started) / 1e9);
      report.addProperty("sent_values", sent.get());
      report.addProperty("received_values", received.get());
      report.addProperty("recorded_values", recorded);
      report.addProperty("independently_checked_values", checked);
      report.addProperty("listener_loop_max_delay_ms", monitor.longestNs.get() / 1e6);
      report.addProperty("store_queue_turn_max_ms", manager.stores().queueMetrics().longestTurnUs() / 1e3);
      report.addProperty("store_queue_over_budget_turns", manager.stores().queueMetrics().overBudgetTurns());
      report.addProperty("heap_peak_sampled_bytes", monitor.peakHeapBytes.get());
      report.addProperty("heap_max_bytes", Runtime.getRuntime().maxMemory());
      report.addProperty("force_count", force.count.get());
      report.addProperty("force_max_duration_ms", force.longestNs.get() / 1e6);
      report.addProperty("force_max_completion_gap_ms", force.longestGapNs.get() / 1e6);
      report.addProperty("force_bound_ms", FORCE_BOUND_NS / 1e6);
      report.addProperty("import_wall_ms", importWallNs.get() / 1e6);
      report.addProperty("import_inspection_callback_ms", inspectionDelayNs.get() / 1e6);
      report.addProperty("mcp_calls", mcpCalls.get());
      report.addProperty("read_v1_calls", exactCalls.get());
      report.addProperty("read_v1_samples_checked", exactSamples.get());
      report.addProperty("read_failure", readFailure.get() == null ? null : readFailure.get().toString());
      report.addProperty("import_failure", importFailure.get() == null ? null : importFailure.get().toString());
      Files.writeString(reportDirectory.resolve("soak-" + label + ".json"),
          new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report) + System.lineSeparator());
      System.out.println("Architecture hardening soak: " + report);
    }
  }

  private static String name(int topic) { return "/architecture-soak/topic-" + topic; }
  private static long value(int topic, long tick) { return topic * 1_000_000L + tick; }

  private static Path readFixture(Path path) throws IOException {
    try (var writer = new WpilogWriter(path, "independent arithmetic workload")) {
      int entry = writer.start("/analysis", "double", "", 0);
      for (int i = 0; i < READ_SAMPLES; i++) writer.append(entry, i * PERIOD_US, WpilogWriter.encodeDouble(i));
    }
    return path;
  }

  private static void mcpReads(int port, Path fixture, AtomicBoolean stop,
      AtomicReference<Throwable> failure, AtomicLong completed) {
    try {
      var http = new HarnessHttp(port);
      http.initialize();
      var args = new JsonObject();
      args.addProperty("path", fixture.toString());
      args.addProperty("name", "/analysis");
      while (!stop.get()) {
        var result = http.call("get_statistics", args);
        assertEquals("ok", result.get("status").getAsString(), result.toString());
        assertEquals((READ_SAMPLES - 1) / 2.0, result.get("mean").getAsDouble(), 0);
        completed.incrementAndGet();
      }
    } catch (Throwable problem) { failure.compareAndSet(null, problem); }
  }

  private static void exactReads(int port, Path fixture, AtomicBoolean stop,
      AtomicReference<Throwable> failure, AtomicLong completed, AtomicLong samples) {
    try (var remote = new RemoteLogAccess(URI.create("http://127.0.0.1:" + port))) {
      while (!stop.get()) {
        long count = 0;
        try (var snapshot = remote.open(fixture); var cursor = snapshot.read(ReadQuery.all("/analysis"))) {
          while (true) {
            var batch = cursor.next();
            assertTrue(batch.problems().isEmpty());
            for (var sample : batch.samples()) {
              assertEquals(count * PERIOD_US, sample.timestampUs());
              assertEquals((double) count, ((Number) sample.value()).doubleValue(), 0);
              count++;
            }
            if (batch.complete()) break;
          }
        }
        assertEquals(READ_SAMPLES, count);
        samples.addAndGet(count);
        completed.incrementAndGet();
      }
    } catch (Throwable problem) { failure.compareAndSet(null, problem); }
  }

  private static final class ForceMeasurements {
    final AtomicLong count = new AtomicLong(), longestNs = new AtomicLong();
    final AtomicLong previousNs = new AtomicLong(), longestGapNs = new AtomicLong();
    WpilogOutput open(Path path, int nextEntry, boolean resume) throws IOException {
      return new WpilogOutput(path, nextEntry, resume) {
        @Override public void flush() throws IOException {
          long started = System.nanoTime();
          super.flush();
          long finished = System.nanoTime();
          longestNs.accumulateAndGet(finished - started, Math::max);
          long previous = previousNs.getAndSet(finished);
          if (previous != 0) longestGapNs.accumulateAndGet(finished - previous, Math::max);
          count.incrementAndGet();
        }
      };
    }
  }

  /** The observer measures queued heartbeat delay from outside, including a heartbeat still stuck. */
  private static final class Monitor implements AutoCloseable {
    final AtomicLong longestNs = new AtomicLong(), peakHeapBytes = new AtomicLong();
    final AtomicLong pendingAtNs = new AtomicLong();
    final java.util.concurrent.ScheduledExecutorService observer =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
          var thread = new Thread(runnable, "hardening-soak-watchdog");
          thread.setDaemon(true);
          return thread;
        });
    void start(ClientScheduler loop) {
      observer.scheduleAtFixedRate(() -> {
        long now = System.nanoTime();
        peakHeapBytes.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
        long previous = pendingAtNs.get();
        if (previous != 0) longestNs.accumulateAndGet(now - previous, Math::max);
        else if (pendingAtNs.compareAndSet(0, now)) {
          loop.execute(() -> {
            longestNs.accumulateAndGet(System.nanoTime() - now, Math::max);
            pendingAtNs.compareAndSet(now, 0);
          });
        }
      }, 0, 100, TimeUnit.MILLISECONDS);
    }
    @Override public void close() { observer.shutdownNow(); }
  }
}
