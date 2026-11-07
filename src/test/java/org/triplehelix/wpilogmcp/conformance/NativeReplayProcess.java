/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;

/** One native server process is one boot; acknowledgements count records, never a guessed delay. */
final class NativeReplayProcess implements AutoCloseable {
  final int topics;
  private final Path control;
  private final Process process;
  private final java.util.concurrent.Semaphore notifications = new java.util.concurrent.Semaphore(0);
  private final Thread output;
  private final ReplaySource source;
  private final long shiftUs;

  static int freePort() throws Exception {
    try (var socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress("127.0.0.1", 0)); return socket.getLocalPort();
    }
  }

  NativeReplayProcess(ReplaySource source, long shiftUs, Path run, int port) throws Exception {
    this.source = source; this.shiftUs = shiftUs;
    control = Files.createDirectories(run.resolve("control")).toAbsolutePath();
    String javaCommand = ProcessHandle.current().info().command().orElseThrow();
    var command = List.of(javaCommand, "-Xmx512m", "-Djava.library.path=" + System.getProperty("harness.natives"),
        "-jar", System.getProperty("harness.robotJar"), "--replay", source.path.toAbsolutePath().toString(),
        control.toString(), Integer.toString(port), Long.toString(shiftUs), "0");
    var builder = new ProcessBuilder(command).directory(run.toFile()).redirectErrorStream(true);
    for (String key : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "HALSIM_EXTENSIONS", "TBA_API_KEY")) builder.environment().remove(key);
    builder.environment().put("LD_LIBRARY_PATH", System.getProperty("harness.natives"));
    builder.environment().put("DYLD_LIBRARY_PATH", System.getProperty("harness.natives"));
    process = builder.start();
    output = new Thread(() -> {
      try (var lines = process.inputReader(java.nio.charset.StandardCharsets.UTF_8);
           var log = Files.newBufferedWriter(run.resolve("robot.log"))) {
        for (String line; (line = lines.readLine()) != null;) {
          if (line.equals("WPILOG_REPLAY_CONTROL")) notifications.release();
          else { log.write(line); log.newLine(); log.flush(); }
        }
      } catch (java.io.IOException failure) { if (process.isAlive()) process.destroyForcibly(); }
      finally { notifications.release(); }
    }, "native-replay-control");
    output.setDaemon(true); output.start();
    process.onExit().thenRun(() -> notifications.release());
    try {
      awaitControl(() -> Files.exists(control.resolve("ready")));
      topics = Integer.parseInt(Files.readString(control.resolve("ready")).strip());
    } catch (Exception | AssertionError failure) { close(); throw failure; }
  }

  void consume(ReplayCapture capture) throws Exception {
    long values = capture.received.get(), properties = capture.receivedProperties.get();
    Files.writeString(control.resolve("go"), "go"); notifyRobot();
    long acknowledgedValues = -1, acknowledgedProperties = -1;
    while (!Files.exists(control.resolve("done"))) {
      final long previousValues = acknowledgedValues, previousProperties = acknowledgedProperties;
      awaitControl(() -> Files.exists(control.resolve("done"))
          || count("sent") > previousValues || count("sent_properties") > previousProperties);
      long sent = count("sent"), sentProperties = count("sent_properties");
      // A pipe wakes when ntcore publishes a barrier; the capture's receipt condition
      // wakes when its last value arrives. No filesystem-watch polling delay on either side.
      if (sent > acknowledgedValues) {
        capture.receivedThrough(values + sent); acknowledge("received", sent); acknowledgedValues = sent;
      }
      if (sentProperties > acknowledgedProperties) {
        capture.propertiesThrough(properties + sentProperties);
        acknowledge("received_properties", sentProperties); acknowledgedProperties = sentProperties;
      }
    }
    assertTrue(Files.readString(control.resolve("control_transport")).strip().equals("pipe"),
        "Blocking pipe notifications do not depend on the platform's file-watch poll interval");
    assertTrue(Long.parseLong(Files.readString(control.resolve("ds_updates")).strip()) == source.driverStationRecords(),
        "Replay must drive every recorded Driver Station update");
    assertTrue(Files.readString(control.resolve("ds_digest")).strip().equals(source.driverStationDigest(shiftUs)),
        "DriverStationSim state must follow the independently decoded source transitions");
  }

  private long count(String name) throws Exception {
    var path = control.resolve(name);
    return Files.exists(path) ? Long.parseLong(Files.readString(path).strip()) : -1;
  }

  private void awaitControl(HarnessHttp.Condition ready) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!ready.ready()) {
      assertTrue(process.isAlive(), source.path.toString());
      long left = deadline - System.nanoTime();
      assertTrue(left > 0, "Native replay control made no progress: " + source.path);
      notifications.tryAcquire(left, TimeUnit.NANOSECONDS);
    }
  }

  private void acknowledge(String to, long sent) throws Exception {
    var temporary = control.resolve(to + ".tmp"); Files.writeString(temporary, Long.toString(sent));
    Files.move(temporary, control.resolve(to), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    notifyRobot();
  }

  private void notifyRobot() throws java.io.IOException {
    process.getOutputStream().write('\n'); process.getOutputStream().flush();
  }

  void finish() throws Exception {
    Files.writeString(control.resolve("stop"), "stop"); notifyRobot();
    assertTrue(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0, source.path.toString());
  }
  boolean isAlive() { return process.isAlive(); }
  @Override public void close() throws Exception {
    try { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); } }
    finally { process.getOutputStream().close(); output.join(TimeUnit.SECONDS.toMillis(10)); }
  }
}
