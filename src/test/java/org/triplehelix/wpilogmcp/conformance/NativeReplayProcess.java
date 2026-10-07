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
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

/** One native server process is one boot; acknowledgements count records, never a guessed delay. */
final class NativeReplayProcess implements AutoCloseable {
  final int topics;
  private final Path control;
  private final Process process;
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
    String java = ProcessHandle.current().info().command().orElseThrow();
    var command = List.of(java, "-Xmx512m", "-Djava.library.path=" + System.getProperty("harness.natives"),
        "-jar", System.getProperty("harness.robotJar"), "--replay", source.path.toAbsolutePath().toString(),
        control.toString(), Integer.toString(port), Long.toString(shiftUs), "0");
    var builder = new ProcessBuilder(command).directory(run.toFile()).redirectErrorStream(true).redirectOutput(run.resolve("robot.log").toFile());
    for (String key : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "HALSIM_EXTENSIONS", "TBA_API_KEY")) builder.environment().remove(key);
    builder.environment().put("LD_LIBRARY_PATH", System.getProperty("harness.natives"));
    builder.environment().put("DYLD_LIBRARY_PATH", System.getProperty("harness.natives"));
    process = builder.start();
    try {
      HarnessHttp.await("native replay ready", 30, () -> {
        assertTrue(process.isAlive(), source.path.toString()); return Files.exists(control.resolve("ready"));
      });
      topics = Integer.parseInt(Files.readString(control.resolve("ready")).strip());
    } catch (Exception | AssertionError failure) { close(); throw failure; }
  }

  void consume(ReplayCapture capture) throws Exception {
    long values = capture.received.get(), properties = capture.receivedProperties.get();
    Files.writeString(control.resolve("go"), "go");
    HarnessHttp.await("native replay consumed", 300, () -> {
      if (capture.loop instanceof ManualScheduler manual) manual.drain();
      assertTrue(process.isAlive(), source.path.toString());
      acknowledge("sent", "received", capture.received.get() - values);
      acknowledge("sent_properties", "received_properties", capture.receivedProperties.get() - properties);
      return Files.exists(control.resolve("done"));
    });
    assertTrue(Files.readString(control.resolve("watch_registrations")).strip().equals("1"),
        "One persistent watcher avoids registering across atomic handshake-file replacements");
    assertTrue(Long.parseLong(Files.readString(control.resolve("ds_updates")).strip()) == source.driverStationRecords(),
        "Replay must drive every recorded Driver Station update");
    assertTrue(Files.readString(control.resolve("ds_digest")).strip().equals(source.driverStationDigest(shiftUs)),
        "DriverStationSim state must follow the independently decoded source transitions");
  }

  private void acknowledge(String from, String to, long received) throws Exception {
    if (!Files.exists(control.resolve(from))) return;
    long sent = Long.parseLong(Files.readString(control.resolve(from)).strip());
    if (received < sent) return;
    var temporary = control.resolve(to + ".tmp"); Files.writeString(temporary, Long.toString(sent));
    Files.move(temporary, control.resolve(to), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  void finish() throws Exception {
    Files.writeString(control.resolve("stop"), "stop");
    assertTrue(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0, source.path.toString());
  }
  @Override public void close() throws Exception {
    if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
  }
}
