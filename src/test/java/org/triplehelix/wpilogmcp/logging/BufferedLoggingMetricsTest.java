/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.config.MetricsConfig;
import org.triplehelix.wpilogmcp.mcp.MetricsEndpoint;

/** Process isolation makes a real installed buffer's nonzero lifetime counter safe to inspect. */
class BufferedLoggingMetricsTest {
  @TempDir Path temporary;

  @Test void installedProductionBufferPublishesItsExactNonzeroDropCounter() throws Exception {
    var stdout = temporary.resolve("stdout");
    var stderr = temporary.resolve("stderr");
    var cache = Path.of(System.getenv().getOrDefault("WPILOG_DISK_CACHE_DIR",
        "build/test-disk-cache")).toAbsolutePath().resolve("buffered-metrics");
    var builder = new ProcessBuilder(
        ProcessHandle.current().info().command().orElseThrow(),
        "-Duser.home=" + temporary,
        "-cp", System.getProperty("java.class.path"), Child.class.getName())
        .directory(temporary.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
    builder.environment().keySet().removeIf(name -> name.startsWith("WPILOG_")
        || name.equals("TBA_API_KEY"));
    builder.environment().put("WPILOG_DISK_CACHE_DIR", cache.toString());
    builder.environment().put("XDG_DATA_HOME", temporary.resolve("xdg").toString());
    builder.environment().put("LOCALAPPDATA", temporary.resolve("local-app-data").toString());
    var child = builder.start();
    child.getOutputStream().close();
    try {
      assertTrue(child.waitFor(10, TimeUnit.SECONDS),
          "The isolated buffer metrics probe did not finish");
      assertEquals(0, child.exitValue(), "Probe stderr: " + Files.readString(stderr));
      var result = JsonParser.parseString(Files.readString(stdout)).getAsJsonObject();
      assertEquals(1_048_576, result.get("retained_bytes").getAsInt(),
          "Two held bytes plus 1,048,574 queued bytes fill the production byte budget");
      assertEquals(3, result.get("dropped_lines").getAsLong());
      assertEquals(9, result.get("dropped_bytes").getAsLong());
      String metrics = result.get("metrics").getAsString();
      assertEquals(List.of("wpilog_log_dropped_lines_total 3"), metrics.lines()
          .filter(line -> line.startsWith("wpilog_log_dropped_lines_total ")).toList(),
          "The metric must expose the actual installed owner's nonzero lifetime count");
      assertTrue(metrics.contains("# TYPE wpilog_log_dropped_lines_total counter\n"));
    } finally {
      child.destroyForcibly();
      child.waitFor(5, TimeUnit.SECONDS);
    }
  }

  /** No server or socket is opened: render reads only the supplied immutable metric snapshots. */
  public static final class Child {
    public static void main(String[] args) throws Exception {
      var original = System.err;
      var entered = new CountDownLatch(1);
      var released = new CountDownLatch(1);
      System.setErr(new PrintStream(new OutputStream() {
        @Override public void write(int value) throws IOException {
          write(new byte[] {(byte) value}, 0, 1);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
          entered.countDown();
          try { released.await(); }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Held metrics output interrupted", e);
          }
          original.write(bytes, offset, length);
        }
      }, true, StandardCharsets.UTF_8));
      var output = BufferedLogOutput.install();
      try {
        System.err.print("h\n");
        if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("The sink did not hold two bytes");
        // Independent arithmetic: 2 + (1,048,573 payload bytes + LF) = 1,048,576.
        System.err.print("q".repeat(1_048_573) + "\n");
        System.err.print("\n"); // Even one more byte must be refused at the production bound.
        System.err.print("abc\n");
        System.err.write("é\r\n".getBytes(StandardCharsets.UTF_8));
        var result = new JsonObject();
        result.addProperty("retained_bytes", output.retainedBytes());
        result.addProperty("dropped_lines", BufferedLogOutput.droppedLines());
        result.addProperty("dropped_bytes", output.lostBytes());
        result.addProperty("metrics", MetricsEndpoint.render(MetricsConfig.DEFAULT, null,
            MetricsEndpoint.Components.NONE, new MetricsEndpoint.Jvm(List.of(), List.of())));
        System.out.println(result);
      } finally {
        released.countDown();
        if (!output.finish(2, TimeUnit.SECONDS)) throw new AssertionError("Released output did not drain");
      }
    }
  }
}
