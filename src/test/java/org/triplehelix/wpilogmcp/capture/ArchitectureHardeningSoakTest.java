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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.triplehelix.wpilogmcp.config.CaptureConfig;
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

  /** The robot's wall clock, by AdvantageKit's name, so panels are placed by measured UTC. */
  private static final String CLOCK = "/AdvantageKit/SystemStats/EpochTimeMicros";
  /** The robot's wall clock at {@code START_US}: 2026-10-22T00:00:00Z. */
  private static final long EPOCH_AT_START_US = 1_792_627_200_000_000L;
  private static final int PANELS = 12;
  private static final long WINDOW_US = TimeUnit.MINUTES.toMicros(5);
  private static final int MAX_POINTS = 1000;
  private static final long REFRESH_BOUND_NS = TimeUnit.MILLISECONDS.toNanos(250);
  private static final long REFRESH_ALLOCATION_BOUND = 50L << 20;
  private static final long HEAP_GROWTH_BOUND = 64L << 20;
  /** Values sent but not yet recorded while the prefill runs ahead of real time: ten seconds' worth. */
  private static final long PREFILL_IN_FLIGHT = 50_000;
  /**
   * How much faster than real time the prefill streams. Run as fast as the recorder takes values,
   * about thirty times the soak's rate, the listener stalled for seconds and once lost the robot,
   * which a recording at the soak's rate never meets. That was when the writer wrote each record
   * as it came; the prefill's own listener delays are reported, not bounded.
   */
  private static final int PREFILL_SPEED = 10;

  /** One refresh of the twelve panels: its wall time, and what the server's HTTP threads allocated. */
  private record Refresh(long elapsedNs, long allocatedBytes) {}

  /**
   * The gate live panels on a recording must pass before the recorded dashboard ships (stage 1 of
   * the Grafana plan, with the bounds the maintainer set on 2026-10-21). The capture first holds
   * two hours at the soak's rate, streamed ten times faster than real time; then the robot streams in real
   * time for ten minutes while twelve panels ask every five seconds for the last five minutes, by
   * measured UTC, at 1,000 points, as Grafana does. The listener waits at most a second; each
   * refresh finishes within 250 ms and its requests allocate under 50 MB, at two hours as at two
   * minutes; and the heap left after a full collection grows by less than 64 MB over the run.
   * Before the recording kept its index in columns, a refresh decoded every record of each
   * panel's entry, so its cost grew with the session.
   */
  @Test @Order(3) void liveDashboardKeepsItsBoundsOnATwoHourRecording() throws Exception {
    int seconds = Integer.getInteger("hardening.gate.seconds", 600);
    double hours = Double.parseDouble(System.getProperty("hardening.gate.prefill.hours", "2"));
    String label = System.getProperty("hardening.soak.label", "full");
    assertTrue(label.matches("[A-Za-z0-9_-]+"));
    var reportDirectory = Files.createDirectories(Path.of("build/reports/architecture-hardening"));
    var work = Files.createTempDirectory(reportDirectory, "dashboard-gate-" + label + "-").toRealPath();
    var manager = LogManager.getInstance();
    var savedAllowed = manager.getAllowedDirectories();
    manager.addAllowedDirectory(work);
    var report = new JsonObject();
    report.addProperty("label", label);
    report.addProperty("synthetic", true);
    report.addProperty("topics", TOPICS);
    report.addProperty("clock_topic", CLOCK);
    report.addProperty("rate_hz_per_topic", RATE_HZ);
    report.addProperty("prefill_hours", hours);
    report.addProperty("prefill_speed", PREFILL_SPEED);
    report.addProperty("dashboard_sec", seconds);
    report.addProperty("panels", PANELS);
    report.addProperty("window_sec", WINDOW_US / 1_000_000);
    report.addProperty("max_points", MAX_POINTS);
    report.addProperty("allocation_measurement", "Bytes allocated by the HTTP server's request and streaming threads while the twelve requests ran.");
    var robot = new AtomicLong(START_US);
    var sent = new AtomicLong();
    var loop = ClientScheduler.daemon("dashboard-gate-listener");
    var dashboard = Executors.newSingleThreadScheduledExecutor(runnable -> {
      var thread = new Thread(runnable, "dashboard-gate-panels");
      thread.setDaemon(true);
      return thread;
    });
    Nt4Gateway gateway = null;
    CaptureService capture = null;
    HttpTransport transport = null;
    var monitor = new Monitor();
    var prefillMonitor = new Monitor();
    boolean passed = false;
    try {
      gateway = new Nt4Gateway(new InetSocketAddress("127.0.0.1", 0), robot::get, List.of(Nt4Client.V40));
      gateway.start().get(10, TimeUnit.SECONDS);
      for (int topic = 0; topic < TOPICS; topic++) gateway.announce(name(topic), "int", new JsonObject()).get(10, TimeUnit.SECONDS);
      gateway.announce(CLOCK, "int", new JsonObject()).get(10, TimeUnit.SECONDS);
      // The pit server's capture as servers.yaml configures it, with the default ten-minute hot window.
      capture = new CaptureService(new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", gateway.port(), "dashboard-gate")),
          work.resolve("store"), .001, CapturePolicy.ALL, TimeUnit.SECONDS.toMicros(600)), manager, Clock.systemUTC(), loop);
      capture.start();
      var live = capture.live();
      HarnessHttp.await("synthetic NT4 connection and clock", 15, () -> live.connected() && live.timeEstimate().isPresent());
      HarnessHttp.await("all synthetic topic declarations", 15, () -> live.topics().size() == TOPICS + 1);
      var tools = new ToolRegistry();
      WpilogTools.registerAll(tools);
      transport = new HttpTransport(tools, 0);
      transport.useConfiguredCapture(capture);
      transport.start();
      var panels = new Panels(transport.getPort());
      long heapBefore = heapAfterFullCollection();

      long prefillTicks = Math.round(hours * 3600 * RATE_HZ);
      long twoMinutes = 2 * 60 * RATE_HZ;
      var early = new ArrayList<Refresh>();
      Refresh warmUp = null;
      prefillMonitor.start(loop);
      long prefillStart = System.nanoTime();
      for (long tick = 0; tick < prefillTicks; tick++) {
        long due = prefillStart + TimeUnit.MICROSECONDS.toNanos(tick * PERIOD_US / PREFILL_SPEED);
        while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
        sent.addAndGet(publish(gateway, robot, tick));
        while (sent.get() - live.receivedValues() > PREFILL_IN_FLIGHT) {
          // A recording that lost its robot loses what was sent meanwhile; say why at once.
          assertTrue(live.connected(), () -> "The recorder disconnected at tick " + live.receivedValues() / (TOPICS + 1)
              + ": " + live.disconnectReason());
          LockSupport.parkNanos(200_000);
        }
        if (tick + 1 == twoMinutes) {
          HarnessHttp.awaitProgress("values at two minutes", 60, live::receivedValues, () -> live.receivedValues() == sent.get());
          // The first request after start loads and compiles the read path; it is reported, not bounded.
          warmUp = panels.refresh(tick);
          for (int i = 0; i < 5; i++) early.add(panels.refresh(tick));
        }
      }
      HarnessHttp.awaitProgress("prefilled values", 120, live::receivedValues, () -> live.receivedValues() == sent.get());
      prefillMonitor.close();
      report.addProperty("prefill_values", sent.get());
      report.addProperty("prefill_wall_sec", (System.nanoTime() - prefillStart) / 1e9);
      report.addProperty("prefill_listener_loop_max_delay_ms", prefillMonitor.longestNs.get() / 1e6);
      report.addProperty("prefill_listener_delays_over_one_second", prefillMonitor.stalls.get());

      // Real time from here: the robot streams while the dashboard refreshes every five seconds.
      monitor.start(loop);
      var refreshes = Collections.synchronizedList(new ArrayList<Refresh>());
      var panelFailure = new AtomicReference<Throwable>();
      var lastTick = new AtomicLong(prefillTicks - 1);
      dashboard.scheduleAtFixedRate(() -> {
        try {
          refreshes.add(panels.refresh(lastTick.get()));
        } catch (Throwable problem) {
          panelFailure.compareAndSet(null, problem);
        }
      }, 0, 5, TimeUnit.SECONDS);
      long streamStart = System.nanoTime();
      long ticks = (long) seconds * RATE_HZ;
      for (long i = 0; i < ticks; i++) {
        long due = streamStart + TimeUnit.MICROSECONDS.toNanos(i * PERIOD_US);
        while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime());
        long tick = prefillTicks + i;
        sent.addAndGet(publish(gateway, robot, tick));
        lastTick.set(tick);
      }
      dashboard.shutdown();
      assertTrue(dashboard.awaitTermination(60, TimeUnit.SECONDS), "The last refresh must finish");
      HarnessHttp.awaitProgress("streamed values", 60, live::receivedValues, () -> live.receivedValues() == sent.get());
      monitor.close();
      long heapAfter = heapAfterFullCollection();

      List<Refresh> run;
      synchronized (refreshes) { run = List.copyOf(refreshes); }
      report.add("warm_up_refresh", json(warmUp == null ? List.of() : List.of(warmUp)));
      report.add("refreshes_at_two_minutes", json(early));
      report.add("refreshes_at_two_hours", json(run));
      report.addProperty("listener_loop_max_delay_ms", monitor.longestNs.get() / 1e6);
      report.addProperty("heap_after_full_collection_before_bytes", heapBefore);
      report.addProperty("heap_after_full_collection_after_bytes", heapAfter);
      report.addProperty("heap_growth_bytes", heapAfter - heapBefore);
      report.addProperty("sent_values", sent.get());
      report.addProperty("recorded_values", live.receivedValues());
      report.addProperty("panel_failure", panelFailure.get() == null ? null : panelFailure.get().toString());
      var all = new ArrayList<>(early);
      all.addAll(run);
      long slowest = all.stream().mapToLong(Refresh::elapsedNs).max().orElse(0);
      long largest = all.stream().mapToLong(Refresh::allocatedBytes).max().orElse(0);
      assertAll(
          () -> assertNull(panelFailure.get(), "Every panel must answer with its window's buckets"),
          () -> assertTrue(run.size() >= seconds / 5, "Refreshes run: " + run.size()),
          () -> assertTrue(monitor.longestNs.get() <= TimeUnit.MICROSECONDS.toNanos(LoopWatchdog.STALL_US),
              "Listener loop exceeded one second: " + monitor.longestNs.get() / 1e6 + " ms"),
          () -> assertTrue(slowest <= REFRESH_BOUND_NS, "A refresh took " + slowest / 1e6 + " ms"),
          () -> assertTrue(largest < REFRESH_ALLOCATION_BOUND, "A refresh allocated " + largest / 1e6 + " MB"),
          () -> assertTrue(heapAfter - heapBefore < HEAP_GROWTH_BOUND,
              "Heap after a full collection grew by " + (heapAfter - heapBefore) / 1e6 + " MB"),
          () -> assertEquals(sent.get(), live.receivedValues(), "Every value sent must be recorded"));
      passed = true;
    } finally {
      monitor.close();
      prefillMonitor.close();
      dashboard.shutdownNow();
      if (transport != null) transport.stop();
      if (capture != null) capture.close();
      if (gateway != null) gateway.close();
      loop.close();
      loop.awaitTermination(TimeUnit.SECONDS.toMicros(30));
      manager.release(work);
      manager.clearAllowedDirectories();
      savedAllowed.forEach(manager::addAllowedDirectory);
      Files.writeString(reportDirectory.resolve("dashboard-gate-" + label + ".json"),
          new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report) + System.lineSeparator());
      System.out.println("Live dashboard gate: " + report);
      // Two hours of capture take about a gigabyte: a pass deletes them, a failure keeps them to look at.
      if (passed) {
        try (var paths = Files.walk(work)) {
          for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
      }
    }
  }

  /** One tick: every topic's value and the robot's wall clock, at the tick's robot time; the values sent. */
  private static int publish(Nt4Gateway gateway, AtomicLong robot, long tick) throws Exception {
    long timestamp = START_US + tick * PERIOD_US;
    robot.set(timestamp);
    var batch = new CompletableFuture<?>[TOPICS + 1];
    for (int topic = 0; topic < TOPICS; topic++) batch[topic] = gateway.value(name(topic), timestamp, 2, value(topic, tick));
    batch[TOPICS] = gateway.value(CLOCK, timestamp, 2, EPOCH_AT_START_US + tick * PERIOD_US);
    CompletableFuture.allOf(batch).get(10, TimeUnit.SECONDS);
    return TOPICS + 1;
  }

  /** Twelve panels, as Grafana's Infinity asks for them: one entry each, by measured UTC, in buckets. */
  private static final class Panels {
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final int port;

    Panels(int port) {
      this.port = port;
    }

    /** All twelve at once for the five minutes up to {@code lastTick}, with each answer checked. */
    Refresh refresh(long lastTick) throws Exception {
      long end = EPOCH_AT_START_US + lastTick * PERIOD_US;
      // A window reaching back before the recording began starts at its first value.
      long firstTick = Math.max(0, lastTick - WINDOW_US / PERIOD_US);
      var before = requestThreadAllocation();
      long started = System.nanoTime();
      var answers = new ArrayList<CompletableFuture<HttpResponse<String>>>();
      for (int panel = 0; panel < PANELS; panel++) {
        var query = "?capture=current&format=csv&max_points=" + MAX_POINTS
            + "&names=" + URLEncoder.encode("NT:" + name(panel), StandardCharsets.UTF_8)
            + "&start_utc=" + URLEncoder.encode(instant(end - WINDOW_US), StandardCharsets.UTF_8)
            + "&end_utc=" + URLEncoder.encode(instant(end), StandardCharsets.UTF_8);
        var uri = URI.create("http://127.0.0.1:" + port + HttpTransport.DATA_PATH + query);
        answers.add(http.sendAsync(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()));
      }
      var responses = new ArrayList<HttpResponse<String>>();
      for (var answer : answers) responses.add(answer.get(60, TimeUnit.SECONDS));
      long elapsed = System.nanoTime() - started;
      long allocated = allocatedSince(before);
      for (int panel = 0; panel < PANELS; panel++) check(responses.get(panel), panel, firstTick, lastTick);
      return new Refresh(elapsed, allocated);
    }

    /**
     * The window's buckets: the first holds the sample at the window's start, exactly, and the
     * last reaches the latest value sent, less any still on its way to the recording.
     */
    private static void check(HttpResponse<String> response, int topic, long firstTick, long lastTick) {
      assertEquals(200, response.statusCode(), response.body());
      var rows = response.body().lines().filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
      assertEquals("timestamp_sec,timestamp_utc,count,min,max,mean,first,last", rows.get(0));
      assertTrue(rows.size() > 1 && rows.size() <= MAX_POINTS + 1, "Buckets: " + (rows.size() - 1));
      var first = rows.get(1).split(",", -1);
      var last = rows.get(rows.size() - 1).split(",", -1);
      assertEquals((double) value(topic, firstTick), Double.parseDouble(first[3]), "The first bucket's minimum");
      double latest = Double.parseDouble(last[4]);
      assertTrue(latest <= value(topic, lastTick) && latest >= value(topic, lastTick - RATE_HZ),
          "The last bucket's maximum, " + latest + ", against the value sent at tick " + lastTick);
    }
  }

  private static String instant(long epochUs) {
    return Instant.ofEpochSecond(Math.floorDiv(epochUs, 1_000_000L), Math.floorMod(epochUs, 1_000_000L) * 1000).toString();
  }

  /**
   * Bytes each of the HTTP server's threads has allocated so far: those that take requests and
   * those that stream a data response, where an answer's work is done.
   */
  private static Map<Long, Long> requestThreadAllocation() {
    var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    var allocated = new HashMap<Long, Long>();
    for (var thread : Thread.getAllStackTraces().keySet()) {
      if (!thread.getName().startsWith("http-")) continue;
      long bytes = threads.getThreadAllocatedBytes(thread.getId());
      if (bytes >= 0) allocated.put(thread.getId(), bytes);
    }
    return allocated;
  }

  private static long allocatedSince(Map<Long, Long> before) {
    long total = 0;
    for (var thread : requestThreadAllocation().entrySet()) total += thread.getValue() - before.getOrDefault(thread.getKey(), 0L);
    return total;
  }

  private static long heapAfterFullCollection() {
    for (int i = 0; i < 3; i++) System.gc();
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  private static com.google.gson.JsonObject json(List<Refresh> refreshes) {
    var summary = new JsonObject();
    var times = refreshes.stream().mapToDouble(r -> r.elapsedNs() / 1e6).sorted().toArray();
    var bytes = refreshes.stream().mapToDouble(r -> r.allocatedBytes() / 1e6).sorted().toArray();
    summary.addProperty("count", refreshes.size());
    if (times.length > 0) {
      summary.addProperty("median_ms", times[times.length / 2]);
      summary.addProperty("p95_ms", times[(int) Math.min(times.length - 1, Math.ceil(.95 * times.length) - 1)]);
      summary.addProperty("max_ms", times[times.length - 1]);
      summary.addProperty("median_allocated_mb", bytes[bytes.length / 2]);
      summary.addProperty("max_allocated_mb", bytes[bytes.length - 1]);
    }
    return summary;
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
    /** Probes the loop ran a second or more late, the watchdog's stall. */
    final AtomicLong stalls = new AtomicLong();
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
            long delay = System.nanoTime() - now;
            longestNs.accumulateAndGet(delay, Math::max);
            if (delay >= TimeUnit.MICROSECONDS.toNanos(LoopWatchdog.STALL_US)) stalls.incrementAndGet();
            pendingAtNs.compareAndSet(now, 0);
          });
        }
      }, 0, 100, TimeUnit.MILLISECONDS);
    }
    @Override public void close() { observer.shutdownNow(); }
  }
}
