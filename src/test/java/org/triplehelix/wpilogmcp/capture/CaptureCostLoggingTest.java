/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

/** A fresh JVM exercises SimpleLogger's actual level filtering, which is fixed at first use. */
class CaptureCostLoggingTest {
  @TempDir Path directory;

  @Test void aThousandTopicsProduceOneInfoSummaryPerReport() throws Exception {
    var lines = run("info");
    var info = lines.stream().filter(line -> line.contains(" INFO ")).toList();
    assertEquals(2, info.size(), "Two five-minute reports must yield two INFO lines, not 2,000");
    assertTrue(info.stream().allMatch(line -> line.contains("Capture cost:")), info.toString());
    assertTrue(lines.stream().noneMatch(line -> line.contains("Capture topic ")),
        "Per-topic details belong at DEBUG");
  }

  @Test void debugRetainsEveryTopicWithoutPromotingTheDetails() throws Exception {
    var lines = run("debug");
    var details = lines.stream().filter(line -> line.contains("Capture topic ")).toList();
    assertEquals(2_000, details.size(), "Both reports retain all 1,000 topic details at DEBUG");
    assertTrue(details.stream().allMatch(line -> line.contains(" DEBUG ")),
        "Per-topic details must not flood the ordinary INFO journal");
    assertEquals(2, details.stream().filter(line -> line.contains("Capture topic /topic-1000:")).count());
  }

  @Test void summaryReportsIndependentCumulativeTotalsAndFiveCostliestTopics() throws Exception {
    var lines = run("info");
    var summaries = lines.stream().filter(line -> line.contains("Capture cost:")).toList();
    assertEquals(2, summaries.size(), "Every five-minute report needs an aggregate");
    // Payloads are 1 through 1,000 ASCII bytes: 500,500 bytes. The WPILOG headers have
    // four bytes for entries/payloads 1..255 and six for 256..1,000: another 5,490 bytes.
    String expected = "topics=1000, records=1000, bytes=505990, dropped=500, thinned=333";
    String top = "top_by_bytes=[/topic-1000=1006, /topic-0999=1005, /topic-0998=1004, "
        + "/topic-0997=1003, /topic-0996=1002]";
    for (var summary : summaries) {
      assertTrue(summary.contains(expected), summary);
      assertTrue(summary.contains(top), summary);
    }
  }

  private List<String> run(String level) throws Exception {
    var output = directory.resolve(level + ".log");
    var process = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=" + level,
        "-cp", System.getProperty("java.class.path"), ReportProcess.class.getName(), directory.toString())
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try { assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Capture report process timed out"); }
    finally { process.destroyForcibly(); }
    assertEquals(0, process.exitValue(), Files.readString(output));
    return Files.readAllLines(output);
  }

  public static class ReportProcess {
    public static void main(String[] args) throws Exception {
      var loop = new ManualScheduler();
      var flushes = new AtomicInteger();
      var policy = new CapturePolicy(List.of(), Map.of("/", 1_000_000L));
      try (var writer = new CaptureWriter(Clock.systemUTC(), loop, policy, new CaptureWriter.Observer() {
        @Override public Path create(String address, java.time.Instant start) {
          return Path.of(args[0]).resolve("capture.wpilog");
        }
        @Override public void flushed(CaptureWriter.Session session) { flushes.incrementAndGet(); }
      })) {
        writer.connected(URI.create("ws://127.0.0.1/nt/test"), "networktables.first.wpi.edu");
        writer.timeSync(1, 0);
        for (int i = 1; i <= 1_000; i++) {
          var topic = new Announce("/topic-%04d".formatted(i), i, "string", null, new JsonObject());
          writer.announce(topic);
          var value = new ValueFrame(i, 1, 4, "x".repeat(i));
          writer.value(topic, value, 299_000_000);
          if (i % 3 == 0) writer.value(topic, value, 299_000_000); // Same timestamp is thinned.
          if (i % 2 == 0) writer.invalidValue(topic, 2);
        }
        for (int report = 0; report < 2; report++) {
          int previousFlushes = flushes.get();
          loop.advance(CaptureWriter.REPORT_PERIOD_US);
          loop.until(() -> flushes.get() > previousFlushes);
        }
      }
    }
  }
}
