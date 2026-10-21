/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.triplehelix.wpilogmcp.capture.LiveCapture;
import org.triplehelix.wpilogmcp.config.MetricsConfig;
import org.triplehelix.wpilogmcp.log.struct.StructDecodeException;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.FieldPath;
import org.triplehelix.wpilogmcp.store.DiskSpaceBudgets;
import org.triplehelix.wpilogmcp.store.StoreRegistry;

/** Prometheus 0.0.4 from published facts: no recorder, queue, filesystem, or scrape history.
 * Each scrape is a sampled view; only capture records every publication between scrapes. */
public final class MetricsEndpoint {
  public record Memory(String area, long used, long committed, long max) {}
  public record Collections(String collector, long count, long millis) {}
  public record Jvm(List<Memory> memory, List<Collections> collections) {
    public static Jvm sample() {
      var bean = ManagementFactory.getMemoryMXBean();
      var heap = bean.getHeapMemoryUsage(); var other = bean.getNonHeapMemoryUsage();
      return new Jvm(List.of(new Memory("heap", heap.getUsed(), heap.getCommitted(), heap.getMax()),
          new Memory("nonheap", other.getUsed(), other.getCommitted(), other.getMax())),
          ManagementFactory.getGarbageCollectorMXBeans().stream()
              .map(gc -> new Collections(gc.getName(), gc.getCollectionCount(), gc.getCollectionTime())).toList());
    }
  }
  /** Later gateway/provider owners supply their snapshots here; no such component is started
   * by this endpoint. Unknown provider costs have no samples, not invented zero durations. */
  public record ProviderCost(double seconds, long bytes) {}
  public record Components(int gatewayClients, Map<String, ProviderCost> providers) {
    public static final Components NONE = new Components(0, Map.of());
    public Components { providers = Map.copyOf(providers); }
  }
  private final MetricsConfig config;
  private final LiveCapture live;
  private final Supplier<Components> components;
  private final Supplier<List<DiskSpaceBudgets.Snapshot>> diskSpace;
  public MetricsEndpoint(MetricsConfig config, LiveCapture live) { this(config, live, () -> Components.NONE); }
  public MetricsEndpoint(MetricsConfig config, LiveCapture live, Supplier<Components> components) {
    this(config, live, components,
        () -> org.triplehelix.wpilogmcp.log.LogManager.getInstance().stores().diskSnapshots());
  }
  MetricsEndpoint(MetricsConfig config, LiveCapture live, Supplier<Components> components,
      Supplier<List<DiskSpaceBudgets.Snapshot>> diskSpace) {
    this.config = config == null ? MetricsConfig.DEFAULT : config; this.live = live; this.components = components;
    this.diskSpace = diskSpace;
  }
  void handle(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestURI().getPath().equals("/metrics")) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
    if (!exchange.getRequestMethod().equals("GET")) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
    var bytes = render(config, live == null ? null : live.metrics(), components.get(), Jvm.sample(),
        org.triplehelix.wpilogmcp.log.LogManager.getInstance().stores().queueMetrics(),
        diskSpace.get(),
        org.triplehelix.wpilogmcp.log.LogManager.getInstance().stores().catalogCapacities(),
        org.triplehelix.wpilogmcp.log.LogManager.getInstance().stores().publicationCounts()).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var out = exchange.getResponseBody()) { out.write(bytes); }
  }

  public static String render(MetricsConfig config, LiveCapture.Metrics live, Components components, Jvm jvm) {
    return render(config, live, components, jvm, new StoreRegistry.QueueMetrics(0, 0));
  }

  /** Queue owners publish observations independently of their work; a scrape never joins a turn. */
  static String render(MetricsConfig config, LiveCapture.Metrics live, Components components, Jvm jvm,
      StoreRegistry.QueueMetrics queues) {
    return render(config, live, components, jvm, queues, List.of());
  }

  /** A failed space reading is unknown, never a fabricated zero-byte gauge. */
  static String render(MetricsConfig config, LiveCapture.Metrics live, Components components, Jvm jvm,
      StoreRegistry.QueueMetrics queues, List<DiskSpaceBudgets.Snapshot> filesystems) {
    return render(config, live, components, jvm, queues, filesystems, List.of());
  }

  /** Catalog counts are publication facts; encoded metadata limits are not measured heap usage. */
  static String render(MetricsConfig config, LiveCapture.Metrics live, Components components, Jvm jvm,
      StoreRegistry.QueueMetrics queues, List<DiskSpaceBudgets.Snapshot> filesystems,
      List<StoreRegistry.CatalogCapacity> catalogs) {
    return render(config, live, components, jvm, queues, filesystems, catalogs, List.of());
  }

  /** Only complete owner publications have a count; a scrape never starts recovery to fill one. */
  static String render(MetricsConfig config, LiveCapture.Metrics live, Components components, Jvm jvm,
      StoreRegistry.QueueMetrics queues, List<DiskSpaceBudgets.Snapshot> filesystems,
      List<StoreRegistry.CatalogCapacity> catalogs, List<StoreRegistry.PendingCount> pending) {
    var out = new Exposition();
    out.counter("wpilog_log_dropped_lines_total",
        "Complete server log lines dropped by the bounded stderr buffer in this process.",
        Map.of(), org.triplehelix.wpilogmcp.logging.BufferedLogOutput.droppedLines());
    // Copied roots can retain one identity. Its label cannot distinguish those owners, and
    // neither summing nor choosing one would describe the selected store's pending count.
    var occurrences = pending.stream().collect(java.util.stream.Collectors.groupingBy(
        StoreRegistry.PendingCount::storeId, java.util.stream.Collectors.counting()));
    for (var published : pending) if (occurrences.get(published.storeId()) == 1)
      out.gauge("wpilog_replica_pending_publications",
          "Pending capture publications after a complete recovery inventory.",
          Map.of("store_id", published.storeId()), published.totalPendingCount());
    for (var published : catalogs) {
      var labels = Map.of("store_id", published.storeId()); var capacity = published.catalogCapacity();
      out.gauge("wpilog_replica_catalog_records", "Replica records in the last published catalog.", labels, capacity.records());
      out.gauge("wpilog_replica_catalog_records_limit", "Maximum records in one replica catalog.", labels, capacity.recordsLimit());
      out.gauge("wpilog_replica_catalog_exclusions", "Exclusions in the last published replica catalog.", labels, capacity.exclusions());
      out.gauge("wpilog_replica_catalog_exclusions_limit", "Maximum exclusions in one replica catalog.", labels, capacity.exclusionsLimit());
      out.gauge("wpilog_replica_catalog_near_limit", "Whether either catalog count is at least 80 percent of its limit.", labels, capacity.nearLimit() ? 1 : 0);
    }
    out.counter("wpilog_store_queue_over_budget_turns_total",
        "Store turns exceeding the one-second budget, counted once per turn.", Map.of(), queues.overBudgetTurns());
    out.gauge("wpilog_store_queue_longest_turn_seconds",
        "Longest store turn observed outside its owner thread in this process.", Map.of(), queues.longestTurnUs() / 1_000_000.0);
    for (var filesystem : filesystems) {
      var labels = Map.of("filesystem_id", filesystem.filesystemId());
      out.gauge("wpilog_store_filesystem_space_known",
          "Whether the last filesystem space reading succeeded.", labels,
          filesystem.state() == DiskSpaceBudgets.State.AVAILABLE ? 1 : 0);
      if (filesystem.freeBytes() != null) out.gauge("wpilog_store_filesystem_free_bytes",
          "Last measured usable filesystem bytes; other processes can change this reading.", labels, filesystem.freeBytes());
      out.gauge("wpilog_store_filesystem_reserved_bytes",
          "Bytes reserved by copying writers until their I/O completes.", labels, filesystem.reservedBytes());
      out.gauge("wpilog_store_filesystem_capture_reserve_bytes",
          "Standing filesystem reserve protected for capture.", labels, filesystem.captureReserveBytes());
    }
    out.gauge("wpilog_nt_connected", "Whether the NT4 connection is up.", Map.of(), live != null && live.connected() ? 1 : 0);
    if (live != null) {
      if (!live.address().isEmpty()) out.gauge("wpilog_nt_address_info", "Last connected robot address.", Map.of("address", live.address()), 1);
      if (live.time() != null) {
        out.gauge("wpilog_nt_time_offset_seconds", "Robot clock minus the capture monotonic clock.", Map.of(), live.time().offsetUs() / 1_000_000.0);
        out.gauge("wpilog_nt_round_trip_seconds", "Round trip of the selected time estimate.", Map.of(), live.time().roundTripUs() / 1_000_000.0);
      }
      var status = live.current();
      out.gauge("wpilog_capture_open", "Whether a capture session is open.", Map.of(), status != null && status.open() ? 1 : 0);
      if (status != null && status.statistics() != null) {
        var stats = status.statistics();
        var labels = Map.of("session_started_at", status.startedAt().toString());
        out.gauge("wpilog_capture_topics", "Recorded entries in this session, including finished entries.", labels, stats.topicCount());
        out.counter("wpilog_capture_records_total", "Recorded NT4 value records in this session, excluding schema seeds and context.", labels, stats.records());
        out.counter("wpilog_capture_bytes_total", "Recorded NT4 value bytes including record headers, across rollover files.", labels, stats.bytes());
      }
      live.pull().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
        var labels = Map.of("robot", entry.getKey()); var progress = entry.getValue();
        out.counter("wpilog_pull_bytes_total", "Copied payload bytes in this process, including retransfers.", labels, progress.bytes());
        out.counter("wpilog_pull_files_total", "Successful file verifications in this process, including growing file updates.", labels, progress.files());
        out.gauge("wpilog_pull_files_waiting", "Known unfinished files held by the disabled and connected gate.", labels, progress.waitingFiles());
      });
      for (var provider : live.providers()) {
        var labels = Map.of("provider", provider.name());
        out.gauge("wpilog_provider_state", "Published provider state (1 for the named state).",
            Map.of("provider", provider.name(), "state", provider.state()), 1);
        if (provider.lastRoundTripMs() != null) out.gauge("wpilog_provider_sample_duration_seconds", "Last completed provider sample cost.", labels, provider.lastRoundTripMs() / 1000.0);
        out.gauge("wpilog_provider_sample_bytes", "Last completed provider sample payload size.", labels, provider.sampleBytes());
        out.gauge("wpilog_provider_period_seconds", "Current provider sampling or follow interval.", labels, provider.periodSec());
        if (provider.robotCpuSec() != null) out.gauge("wpilog_provider_robot_cpu_seconds", "Robot processor time between samples; not CPU attributed to this provider.", labels, provider.robotCpuSec());
        out.gauge("wpilog_provider_lines_per_second", "Accepted lines in the current one-second bucket.", labels, provider.linesPerSec());
        out.counter("wpilog_provider_dropped_lines_total", "Follower lines dropped by bounded rate, line size or buffer.", labels, provider.droppedLines());
        out.counter("wpilog_provider_dropped_before_sync_total", "Stats samples dropped before an NT4 time estimate.", labels, provider.droppedBeforeSync());
      }
      topics(out, config, live);
    } else out.gauge("wpilog_capture_open", "Whether a capture session is open.", Map.of(), 0);
    out.gauge("wpilog_gateway_clients", "Connected gateway clients; zero without a wired gateway.", Map.of(), components.gatewayClients());
    components.providers().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
      if (live != null && live.providers().stream().anyMatch(p -> p.name().equals(entry.getKey()))) return;
      var labels = Map.of("provider", entry.getKey());
      out.gauge("wpilog_provider_sample_duration_seconds", "Last completed provider sample cost.", labels, entry.getValue().seconds());
      out.gauge("wpilog_provider_sample_bytes", "Last completed provider sample payload size.", labels, entry.getValue().bytes());
    });
    for (var memory : jvm.memory()) {
      var labels = Map.of("area", memory.area());
      out.gauge("wpilog_jvm_memory_used_bytes", "Current JVM memory use.", labels, memory.used());
      out.gauge("wpilog_jvm_memory_committed_bytes", "Memory committed to the JVM.", labels, memory.committed());
      if (memory.max() >= 0) out.gauge("wpilog_jvm_memory_max_bytes", "JVM memory limit when defined.", labels, memory.max());
    }
    for (var gc : jvm.collections()) {
      var labels = Map.of("collector", gc.collector());
      if (gc.count() >= 0) out.counter("wpilog_jvm_gc_collections_total", "JVM garbage collections.", labels, gc.count());
      if (gc.millis() >= 0) out.counter("wpilog_jvm_gc_duration_seconds_total", "Cumulative JVM garbage collection time.", labels, gc.millis() / 1000.0);
    }
    return out.render();
  }

  private static void topics(Exposition out, MetricsConfig config, LiveCapture.Metrics live) {
    var texts = new TreeMap<String, String>(); var entries = new TreeMap<String, String>();
    live.latest().forEach((name, latest) -> {
      var struct = StructSchemas.schemaEntryStruct(name, latest.type());
      if (struct != null && latest.value() instanceof byte[] bytes) {
        texts.put(struct, new String(bytes, StandardCharsets.UTF_8)); entries.put(struct, name);
      }
    });
    var schemas = StructSchemas.recordedOnly(texts, entries);
    live.latest().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
      String topic = entry.getKey(); if (!config.includes(topic)) return;
      var latest = entry.getValue(); Object value = latest.value();
      var labels = Map.of("topic", topic); boolean emitted = false;
      String struct = StructSchemas.structName(latest.type());
      if (struct != null && value instanceof byte[] bytes) {
        try {
          var decoded = schemas.decode(latest.type(), bytes);
          if (StructSchemas.isArrayType(latest.type()) && decoded instanceof List<?> list) {
            for (int i = 0; i < Math.min(list.size(), config.maxArrayLength()); i++) {
              emitted |= struct(out, schemas, struct, list.get(i), Map.of("topic", topic, "index", Integer.toString(i)), config.maxArrayLength());
            }
          } else emitted = struct(out, schemas, struct, decoded, labels, config.maxArrayLength());
        } catch (StructDecodeException invalid) {
          // A missing or incompatible schema must not invent a value or break the scrape.
        }
      } else if (List.of("boolean[]", "int[]", "float[]", "double[]").contains(latest.type()) && value instanceof List<?> list) {
        for (int i = 0; i < Math.min(list.size(), config.maxArrayLength()); i++) {
          emitted |= number(out, Map.of("topic", topic, "index", Integer.toString(i)), list.get(i));
        }
      } else if (List.of("boolean", "int", "float", "double").contains(latest.type())) emitted = number(out, labels, value);
      if (emitted && live.robotNowUs() != null) out.gauge("nt_age_seconds", "Robot-clock age of the last publication; negative for a future timestamp.",
          labels, (live.robotNowUs() - latest.serverTimestampUs()) / 1_000_000.0);
    });
  }
  private static boolean struct(Exposition out, StructSchemas schemas, String name, Object value, Map<String, String> labels, int limit) {
    var leaves = FieldPath.numericLeaves(schemas, name, value, limit);
    for (var leaf : leaves) {
      var fields = new TreeMap<>(labels); fields.put("field", leaf.field()); number(out, fields, leaf.value());
    }
    return !leaves.isEmpty();
  }
  private static boolean number(Exposition out, Map<String, String> labels, Object value) {
    Number n = value instanceof Boolean b ? b ? 1 : 0 : value instanceof Number number ? number : null;
    if (n == null) return false;
    out.gauge("nt_value", "Latest numeric NT4 or provider value; a sampled view, not the capture record.", labels, n);
    return true;
  }

  /** One request owns this builder. Families stay grouped, and every label is escaped. */
  private static final class Exposition {
    private record Family(String type, String help, List<String> samples) {}
    private final Map<String, Family> families = new TreeMap<>();
    void gauge(String name, String help, Map<String, String> labels, Number value) { add(name, "gauge", help, labels, value); }
    void counter(String name, String help, Map<String, String> labels, Number value) { add(name, "counter", help, labels, value); }
    private void add(String name, String type, String help, Map<String, String> labels, Number value) {
      var family = families.computeIfAbsent(name, ignored -> new Family(type, help, new ArrayList<>()));
      var sample = new StringBuilder(name);
      if (!labels.isEmpty()) {
        sample.append('{'); boolean first = true;
        for (var label : new TreeMap<>(labels).entrySet()) {
          if (!first) sample.append(','); first = false;
          sample.append(label.getKey()).append("=\"").append(escape(label.getValue())).append('"');
        }
        sample.append('}');
      }
      double d = value.doubleValue();
      String number = Double.isNaN(d) ? "NaN" : d == Double.POSITIVE_INFINITY ? "+Inf"
          : d == Double.NEGATIVE_INFINITY ? "-Inf" : value instanceof Float ? Double.toString(d) : value.toString();
      family.samples().add(sample.append(' ').append(number).append('\n').toString());
    }
    String render() {
      var text = new StringBuilder();
      families.forEach((name, family) -> {
        text.append("# HELP ").append(name).append(' ').append(escape(family.help())).append('\n');
        text.append("# TYPE ").append(name).append(' ').append(family.type()).append('\n');
        family.samples().forEach(text::append);
      });
      return text.toString();
    }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\""); }
  }
}
