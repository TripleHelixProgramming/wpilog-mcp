/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.triplehelix.wpilogmcp.fixtures.ChildJvms;

/**
 * A fresh JVM catches a logger that cached the original stream before Main replaced stderr.
 * Blocking the underlying stream also proves protocol output retains its independent owner.
 */
class MainBufferedLoggingTest {
  @TempDir Path temporary;

  @ParameterizedTest(name = "{0}, cached logger stream = {1}")
  @CsvSource({"legacy,false", "start,false", "run,false", "--internal-daemon,false", "run,true",
      "bare-stop,false", "bare-connect,false"})
  void blockedOutputCannotPreventServerStartupOrHoldTheProcessAfterEof(
      String mode, boolean cacheOutputStream) throws Exception {
    var result = run("blocked", mode, cacheOutputStream, List.of());
    assertTrue(result.exited(), "Server startup logging blocked before the MCP initialize reply "
        + "or stderr kept the process alive after EOF; stdout: " + result.out());
    assertEquals(0, result.code(), result.err());
    assertEquals("buffered", result.marker(),
        "Main must replace stderr before its first startup log, including named configuration loading");
    assertProtocolReply(result.out());
    assertEquals("", result.err(), "The underlying stderr was held for the child's entire lifetime");
  }

  @Test void normalEofDrainsTheShutdownLogAndKeepsProtocolStdoutSeparate() throws Exception {
    var result = run("slow-shutdown", "run", true, List.of("-debug"));
    assertTrue(result.exited(), "An ordinary EOF must finish bounded log draining");
    assertEquals(0, result.code(), result.err());
    assertProtocolReply(result.out());
    int loaded = result.err().indexOf("Loaded configuration 'synthetic'");
    int stopped = result.err().indexOf("MCP server stopped");
    int shutdown = result.err().indexOf("Stdio shutdown: shutting down LogManager");
    assertTrue(loaded >= 0 && stopped > loaded && shutdown > stopped,
        "Startup, EOF and shutdown logs must be drained in order before exit:\n" + result.err());
  }

  @Test void shortLivedCommandsKeepTheirErrorsDirectAndComplete() throws Exception {
    for (String command : List.of("service-unit", "robot-facts")) {
      var result = run("direct", command, false, List.of());
      assertTrue(result.exited(), command + " did not exit");
      assertEquals(1, result.code(), command + " must preserve its error exit code");
      assertEquals("", result.out(), command + " errors belong on stderr");
      assertEquals("direct", result.marker(),
          "Short-lived commands retain direct stderr so their final error cannot be lost at exit");
      var lines = result.err().lines().toList();
      assertEquals(1, lines.size(), command + " emits one complete error line: " + result.err());
      if (command.equals("service-unit")) {
        assertEquals("Cannot print service units: Usage: wpilog-mcp service-unit <name> [--config <file>]",
            lines.get(0));
      } else {
        // Existing option redaction consumes the usage placeholder and its closing bracket.
        assertEquals("Cannot collect robot facts: Choose a host or --server. "
            + "wpilog-mcp robot-facts <host> [--user lvuser] [--port 22] [--key [redacted] [--out <file>]"
            + " | robot-facts --server <name> [--config <file>] [--out <file>]", lines.get(0));
      }
    }
  }

  private static void assertProtocolReply(String output) {
    var lines = output.lines().filter(line -> !line.isBlank()).toList();
    assertEquals(1, lines.size(), "stdout must contain exactly one JSON-RPC reply: " + output);
    var reply = JsonParser.parseString(lines.get(0)).getAsJsonObject();
    assertEquals("2.0", reply.get("jsonrpc").getAsString());
    assertEquals(17, reply.get("id").getAsInt());
    assertTrue(reply.has("result"), "The initialize request must finish while stderr is blocked");
    assertFalse(reply.has("error"));
    assertTrue(reply.getAsJsonObject("result").has("serverInfo"));
  }

  private record Result(boolean exited, int code, String out, String err, String marker) {}

  private Result run(String sink, String mode, boolean cached, List<String> extra) throws Exception {
    var folder = Files.createTempDirectory(temporary, "child-");
    var cache = Path.of(System.getenv().getOrDefault("WPILOG_DISK_CACHE_DIR",
        "build/test-disk-cache")).toAbsolutePath().resolve("main-buffered-" + folder.getFileName());
    var config = folder.resolve("servers.yaml");
    Files.writeString(config, "diskcachedisable: true\ndiskcachedir: "
        + new com.google.gson.JsonPrimitive(cache.toString())
        + "\nservers:\n  synthetic:\n    transport: stdio\n");
    var stdout = folder.resolve("stdout");
    var stderr = folder.resolve("stderr");
    var marker = folder.resolve("stream-owner");
    var command = new ArrayList<>(List.of(
        ChildJvms.JAVA, ChildJvms.HEADLESS,
        "-Duser.home=" + folder,
        "-Dorg.slf4j.simpleLogger.cacheOutputStream=" + cached,
        "-cp", System.getProperty("java.class.path"), Child.class.getName(),
        sink, marker.toString()));
    if (mode.equals("legacy")) {
      command.addAll(List.of("-diskcachedisable", "-diskcachedir", cache.toString()));
    } else if (mode.equals("bare-stop") || mode.equals("bare-connect")) {
      command.add(mode.substring("bare-".length()));
    } else if (mode.equals("service-unit") || mode.equals("robot-facts")) {
      command.add(mode);
    } else {
      command.addAll(List.of(mode, "synthetic", "--config", config.toString()));
    }
    command.addAll(extra);
    var builder = new ProcessBuilder(command).directory(folder.toFile())
        .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
    builder.environment().keySet().removeIf(name -> name.startsWith("WPILOG_")
        || name.equals("TBA_API_KEY") || name.equals("INVOCATION_ID"));
    builder.environment().put("WPILOG_DISK_CACHE_DIR", cache.toString());
    builder.environment().put("XDG_DATA_HOME", folder.resolve("xdg").toString());
    builder.environment().put("LOCALAPPDATA", folder.resolve("local-app-data").toString());
    var child = builder.start();
    try {
      child.getOutputStream().write(("{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"initialize\","
          + "\"params\":{}}\n").getBytes(StandardCharsets.UTF_8));
      child.getOutputStream().close();
      boolean exited = child.waitFor(10, TimeUnit.SECONDS);
      if (!exited) {
        child.destroyForcibly();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS), "The isolated child could not be stopped");
      }
      return new Result(exited, child.exitValue(), Files.readString(stdout), Files.readString(stderr),
          Files.exists(marker) ? Files.readString(marker) : "");
    } finally {
      child.destroyForcibly();
      child.waitFor(5, TimeUnit.SECONDS);
    }
  }

  /**
   * This main deliberately creates no logger before replacing stderr. It never opens a socket;
   * every server mode is configured as stdio, and invalid short-lived commands stop in parsing.
   */
  public static final class Child {
    public static void main(String[] args) throws Exception {
      String mode = args[0];
      Path marker = Path.of(args[1]);
      var original = System.err;
      var sink = new PrintStream(new OutputStream() {
        private final CountDownLatch blocked = new CountDownLatch(1);
        @Override public void write(int value) throws IOException {
          write(new byte[] {(byte) value}, 0, 1);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
          if (mode.equals("blocked")) {
            boolean interrupted = false;
            for (;;) {
              try { blocked.await(); break; }
              catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
          } else if (mode.equals("slow-shutdown") && new String(bytes, offset, length,
              StandardCharsets.UTF_8).contains("Stdio shutdown: shutting down LogManager")) {
            try { Thread.sleep(300); }
            catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException("Shutdown sink interrupted", e);
            }
          }
          original.write(bytes, offset, length);
        }
      }, true, StandardCharsets.UTF_8);
      System.setErr(sink);
      if (mode.equals("direct")) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> writeMarker(marker, sink), "test-stream-owner"));
      }
      Main.main(Arrays.copyOfRange(args, 2, args.length));
      if (!mode.equals("direct")) writeMarker(marker, sink);
    }

    private static void writeMarker(Path marker, PrintStream original) {
      try { Files.writeString(marker, System.err == original ? "direct" : "buffered"); }
      catch (IOException e) { throw new IllegalStateException(e); }
    }
  }
}
