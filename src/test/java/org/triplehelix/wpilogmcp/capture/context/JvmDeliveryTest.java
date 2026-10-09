/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.*;
import org.triplehelix.wpilogmcp.config.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

class JvmDeliveryTest {
  @TempDir Path temp;
  @Test void aReplyQueuedBehindTheSessionBoundaryNeverWritesIntoTheNextFile() throws Exception {
    var loop = new ManualScheduler(); var paths = new java.util.ArrayList<Path>(); var wall = Clock.systemUTC();
    int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
    var config = new CaptureConfig(List.of(URI.create("ws://127.0.0.1:5810/nt/test")), temp, .02, CapturePolicy.ALL,
        0, org.triplehelix.wpilogmcp.capture.CaptureWriter.DEFAULT_MAX_FILE_BYTES, PullConfig.DISABLED, 0,
        new ProviderConfig(false, ProviderConfig.DISABLED.stats(), List.of(), List.of(), new ContextConfig.Jvm(port, 1_000_000)));
    var live = new LiveCapture(temp, loop);
    var manager = LogManager.getInstance(); var savedAllowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    var directories = org.triplehelix.wpilogmcp.log.LogDirectory.getInstance();
    var saved = directories.getLogDirectories(); directories.setLogDirectory(temp.toString());
    try (var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, new CaptureWriter.Observer() {
      public Path create(String address, java.time.Instant start) { var p = temp.resolve(paths.size() + ".wpilog"); paths.add(p); return p; }
    }); var providers = new ContextProviders(config, writer, live, loop, LogManager.getInstance().stores().store(temp), wall)) {
      var topic = new Announce("/trigger", 1, "int", null, new JsonObject());
      writer.connected(URI.create("ws://127.0.0.1/nt/test"), "NT4"); writer.timeSync(10_000_000, 0); writer.announce(topic);
      providers.connected("127.0.0.1"); providers.sessionChanged();
      Object old = writer.session();
      var metadata = new JsonObject(); metadata.addProperty("source", "jmx");
      var sample = new JvmProvider.Sample(10_000_000, Map.of("heap/used_bytes", 1024L), null, null, metadata);
      var queued = providers.writeJvm(old, sample);
      // The network completed but its delivery is still queued; the robot reboot is processed first.
      writer.disconnected(); providers.disconnected();
      writer.connected(URI.create("ws://127.0.0.1/nt/test"), "NT4"); writer.timeSync(1_000_000, 1); writer.announce(topic);
      providers.connected("127.0.0.1"); providers.sessionChanged(); assertNotSame(old, writer.session());
      loop.drain(); queued.toCompletableFuture().get();
      assertFalse(live.latest().containsKey("/Daemon/JVM/heap/used_bytes"));
      var accepted = providers.writeJvm(writer.session(), new JvmProvider.Sample(1_000_000, sample.values(), null, null, metadata));
      loop.drain(); accepted.toCompletableFuture().get();
      assertEquals(1_000_000, live.latest().get("/Daemon/JVM/heap/used_bytes").serverTimestampUs());
    } finally { manager.clearAllowedDirectories(); savedAllowed.forEach(manager::addAllowedDirectory); directories.setLogDirectories(saved.stream().map(Path::toString).toList()); }
  }
}
