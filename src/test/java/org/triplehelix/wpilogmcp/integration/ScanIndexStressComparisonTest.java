/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.ChildJvms;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.CoreTools;
import org.triplehelix.wpilogmcp.tools.StatisticsTools;

/** The real-log comparison has an ordinary synthetic caller with independently derived answers. */
class ScanIndexStressComparisonTest {
  @ParameterizedTest @ValueSource(booleans = {false, true})
  void persistedHitRetainsIndependentSamplesAndStatistics(boolean adjacentMicros) throws Exception {
    var parent = Files.createDirectories(Path.of("build", "test-disk-cache", "scan-comparison").toAbsolutePath());
    var work = Files.createTempDirectory(parent, "probe-");
    var cache = Files.createDirectories(work.resolve("cache"));
    var stdout = work.resolve("stdout");
    var stderr = work.resolve("stderr");
    var builder = new ProcessBuilder(ChildJvms.JAVA, ChildJvms.HEADLESS,
        "-Duser.home=" + work.resolve("home"), "-cp", System.getProperty("java.class.path"),
        Child.class.getName(), work.toString(), Boolean.toString(adjacentMicros))
        .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
    builder.environment().keySet().removeIf(name -> name.startsWith("WPILOG_") || name.equals("TBA_API_KEY"));
    builder.environment().put("WPILOG_DISK_CACHE_DIR", cache.toString());
    builder.environment().put("XDG_DATA_HOME", work.resolve("xdg").toString());
    builder.environment().put("LOCALAPPDATA", work.resolve("local-app-data").toString());
    var child = builder.start();
    child.getOutputStream().close();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Synthetic scan comparison must finish");
      assertEquals(0, child.exitValue(), () -> {
        try { return Files.readString(stderr); }
        catch (Exception error) { return error.toString(); }
      });
      var result = JsonParser.parseString(Files.readString(stdout)).getAsJsonObject();
      var answers = result.getAsJsonObject("answers");
      var values = answers.getAsJsonObject("exact_coordinates /Value").getAsJsonArray("samples");
      long[] times = adjacentMicros ? new long[]{8_589_934_592_000_001L, 8_589_934_592_000_002L}
          : new long[]{0, 1_000_000, 1_000_000, 2_000_000, 3_000_000};
      double[] expected = adjacentMicros ? new double[]{1, 2} : new double[]{2, 4, 5, 6, 8};
      assertEquals(expected.length, values.size());
      for (int i = 0; i < expected.length; i++) {
        var sample = values.get(i).getAsJsonObject();
        assertEquals(Long.toString(times[i]), sample.get("timestamp_us").getAsString());
        assertEquals(expected[i], sample.get("value").getAsDouble());
      }
      var statistics = answers.getAsJsonObject("get_statistics /Value");
      assertEquals("ok", statistics.get("status").getAsString());
      assertEquals(expected.length, statistics.get("count").getAsInt());
      // [2,4,5,6,8] has mean/median 5, sum of squared deviations 20 and n-1=4.
      // [1,2] has mean/median 1.5, sum of squared deviations .5 and n-1=1.
      var statisticsExpected = adjacentMicros
          ? new double[]{1, 2, 1.5, 1.5, Math.sqrt(0.5), 1.25, 1.75, 0.5, 1.05, 1.95}
          : new double[]{2, 8, 5, 5, Math.sqrt(5), 4, 6, 2, 2.4, 7.6};
      var fields = List.of("min", "max", "mean", "median", "std_dev", "q1", "q3", "iqr", "p5", "p95");
      for (int i = 0; i < fields.size(); i++)
        assertEquals(statisticsExpected[i], statistics.get(fields.get(i)).getAsDouble(), 1e-12, fields.get(i));
      var read = answers.getAsJsonObject("read_entry /Value");
      assertEquals("ok", read.get("status").getAsString());
      var rows = read.getAsJsonArray("samples");
      assertEquals(expected.length, rows.size());
      for (int i = 0; i < expected.length; i++) {
        assertEquals(expected[i], rows.get(i).getAsJsonObject().get("value").getAsDouble());
        assertEquals(times[i] / 1_000_000.0, rows.get(i).getAsJsonObject().get("timestamp_sec").getAsDouble());
      }
      var declarations = answers.getAsJsonObject("scan_metadata").getAsJsonArray("entries");
      assertEquals(2, declarations.size());
      assertEquals("/Value", declarations.get(0).getAsJsonObject().get("name").getAsString());
      assertEquals("{\"unit\":\"volts\"}", declarations.get(0).getAsJsonObject().get("metadata").getAsString());
      assertEquals(expected.length, declarations.get(0).getAsJsonObject().get("sample_count").getAsInt());
      assertEquals("/Label", declarations.get(1).getAsJsonObject().get("name").getAsString());
      assertEquals(1, declarations.get(1).getAsJsonObject().get("sample_count").getAsInt());
      var health = result.getAsJsonObject("health").getAsJsonObject("scan_index_disk_cache");
      assertEquals(1, health.get("entry_count").getAsInt());
      assertEquals(1, result.get("actual_index_files").getAsInt());
      assertTrue(result.get("actual_index_bytes").getAsLong() > 0);
      assertEquals(result.get("actual_index_bytes").getAsLong(), health.get("size_bytes").getAsLong());
      assertEquals(1, health.get("misses").getAsLong(), "Only the first enabled open misses");
      assertEquals(1, health.get("hits").getAsLong(), "The second enabled open must hit the persisted index");
      assertEquals(2, health.get("format_version").getAsInt());
      assertEquals(1, health.get("recipe_version").getAsInt());
      assertTrue(health.get("enabled").getAsBoolean());
      assertTrue(Files.isSameFile(cache, Path.of(health.get("directory").getAsString())));
    } finally {
      child.destroyForcibly();
      child.waitFor(5, TimeUnit.SECONDS);
    }
  }

  /** Calls the same helper as stressTest without its configuration loader or real-log search. */
  public static final class Child {
    public static void main(String[] arguments) throws Exception {
      var work = Path.of(arguments[0]);
      boolean adjacent = Boolean.parseBoolean(arguments[1]);
      var path = work.resolve("synthetic.wpilog");
      try (var writer = new WpilogWriter(path, "scan-index stress parity")) {
        int value = writer.start("/Value", "double", "{}", 0);
        int label = writer.start("/Label", "string", "{}", 0);
        long base = adjacent ? 8_589_934_592_000_000L : 0;
        long[] times = adjacent ? new long[]{base + 2, base + 1}
            : new long[]{0, 2_000_000, 1_000_000, 1_000_000, 3_000_000};
        double[] values = adjacent ? new double[]{2, 1} : new double[]{2, 6, 4, 5, 8};
        for (int i = 0; i < times.length; i++) writer.append(value, times[i], WpilogWriter.encodeDouble(values[i]));
        writer.append(label, adjacent ? base + 1 : 1_000_000, WpilogWriter.encodeString("ready"));
        writer.setMetadata(value, "{\"unit\":\"volts\"}", adjacent ? base + 2 : 3_000_000);
      }
      var manager = LogManager.getInstance();
      try {
        manager.addAllowedDirectory(work);
        var tools = new ArrayList<ToolRegistry.Tool>();
        var registry = new ToolRegistry() {
          @Override public void registerTool(Tool tool) { tools.add(tool); }
        };
        CoreTools.registerAll(registry);
        StatisticsTools.registerAll(registry);
        var answers = StressTest.compareScanIndexWithFreshScan(manager, path.toString(), tools);
        var result = new JsonObject();
        var rows = new JsonObject();
        answers.forEach(rows::add);
        result.add("answers", rows);
        var health = tools.stream().filter(tool -> tool.name().equals("health_check")).findFirst().orElseThrow();
        result.add("health", health.execute(new JsonObject()));
        var cache = manager.getCacheDirectory().getPath();
        try (var files = Files.list(cache)) {
          var indexes = files.filter(file -> file.getFileName().toString().endsWith("-scan.idx")).toList();
          long bytes = 0;
          for (var file : indexes) bytes += Files.size(file);
          result.addProperty("actual_index_files", indexes.size());
          result.addProperty("actual_index_bytes", bytes);
        }
        System.out.println(result);
      } finally { manager.shutdown(); }
    }
  }
}
