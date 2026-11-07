/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.capture.*;
import org.triplehelix.wpilogmcp.config.*;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ControlMessage.Announce;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;

/**
 * A provider's write runs on the capture loop, and the provider waits for its answer before it
 * writes again. A write that fails with an Error must answer too, or the provider stays busy for
 * the rest of the session, writing nothing and saying nothing.
 */
class ProviderWriteErrorTest {
  @TempDir Path temp;

  /** A metadata value that overflows the stack when written: an Error inside the capture loop's write. */
  private static final class Overflowing extends Number {
    @Override public int intValue() { return 0; }
    @Override public long longValue() { return 0; }
    @Override public float floatValue() { return 0; }
    @Override public double doubleValue() { return 0; }
    @Override public String toString() { throw new StackOverflowError("fixture"); }
  }

  private interface Body { void run(CaptureWriter writer, ContextProviders providers, LiveCapture live, ManualScheduler loop) throws Exception; }

  private void withProviders(List<URI> photonvision, Body body) throws Exception {
    var loop = new ManualScheduler(); var paths = new java.util.ArrayList<Path>(); var wall = Clock.systemUTC();
    int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
    var config = new CaptureConfig(List.of(URI.create("ws://127.0.0.1:5810/nt/test")), temp, .02, CapturePolicy.ALL,
        0, CaptureWriter.DEFAULT_MAX_FILE_BYTES, PullConfig.DISABLED, 0,
        new ProviderConfig(false, ProviderConfig.DISABLED.stats(), List.of(), photonvision, new ContextConfig.Jvm(port, 1_000_000)));
    var live = new LiveCapture(temp, loop);
    var manager = LogManager.getInstance(); var savedAllowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(temp);
    var directories = org.triplehelix.wpilogmcp.log.LogDirectory.getInstance();
    var saved = directories.getLogDirectories(); directories.setLogDirectory(temp.toString());
    try (var writer = new CaptureWriter(wall, loop, CapturePolicy.ALL, new CaptureWriter.Observer() {
      public Path create(String address, java.time.Instant start) { var p = temp.resolve(paths.size() + ".wpilog"); paths.add(p); return p; }
    }); var providers = new ContextProviders(config, writer, live, loop, manager.stores().store(temp), wall)) {
      writer.connected(URI.create("ws://127.0.0.1/nt/test"), "NT4"); writer.timeSync(10_000_000, 0);
      writer.announce(new Announce("/trigger", 1, "int", null, new JsonObject()));
      providers.connected("127.0.0.1"); providers.sessionChanged();
      body.run(writer, providers, live, loop);
    } finally { manager.clearAllowedDirectories(); savedAllowed.forEach(manager::addAllowedDirectory); directories.setLogDirectories(saved.stream().map(Path::toString).toList()); }
  }

  /** The production loop's executor keeps an escaped error in the task's own future; so does this one. */
  private static void drainKeepingErrors(ManualScheduler loop) {
    try { loop.drain(); } catch (StackOverflowError escaped) { assertEquals("fixture", escaped.getMessage()); }
  }

  private static void assertAnsweredWithTheError(CompletionStage<Void> write) {
    // Before, only an Exception answered: the Error left the write unfinished and its provider busy.
    var failure = assertThrows(ExecutionException.class, () -> write.toCompletableFuture().get(10, TimeUnit.SECONDS));
    assertInstanceOf(StackOverflowError.class, failure.getCause());
  }

  @Test void aJvmWriteThatFailsWithAnErrorAnswersItsProviderAndTheLoopGoesOn() throws Exception {
    withProviders(List.of(), (writer, providers, live, loop) -> {
      var metadata = new JsonObject(); metadata.addProperty("source", "jmx");
      var overflowing = metadata.deepCopy(); overflowing.add("fixture", new JsonPrimitive(new Overflowing()));
      var failed = providers.writeJvm(writer.session(), new JvmProvider.Sample(10_000_000, Map.of("heap/used_bytes", 1024L), null, null, overflowing));
      drainKeepingErrors(loop);
      assertAnsweredWithTheError(failed);
      var accepted = providers.writeJvm(writer.session(), new JvmProvider.Sample(11_000_000, Map.of("heap/used_bytes", 2048L), null, null, metadata));
      loop.drain(); accepted.toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertEquals(11_000_000, live.latest().get("/Daemon/JVM/heap/used_bytes").serverTimestampUs(), "the loop goes on writing");
    });
  }

  @Test void aPhotonVisionWriteThatFailsWithAnErrorAnswersItsProviderAndTheLoopGoesOn() throws Exception {
    int unused; try (var socket = new java.net.ServerSocket(0)) { unused = socket.getLocalPort(); }
    withProviders(List.of(URI.create("http://127.0.0.1:" + unused)), (writer, providers, live, loop) -> {
      // The provider's own sink, as the provider calls it after reading a settings export.
      var photons = ContextProviders.class.getDeclaredField("photons"); photons.setAccessible(true);
      var provider = ((List<?>) photons.get(providers)).get(0);
      var field = PhotonVisionProvider.class.getDeclaredField("sink"); field.setAccessible(true);
      var sink = (PhotonVisionProvider.Sink) field.get(provider);
      var metadata = new JsonObject(); metadata.addProperty("source", "photonvision");
      var overflowing = new JsonObject(); overflowing.add("exposure_raw", new JsonPrimitive(new Overflowing()));
      var failed = sink.write(writer.session(), 10_000_000, List.of(new PhotonSettings.Camera("front", overflowing)), metadata);
      drainKeepingErrors(loop);
      assertAnsweredWithTheError(failed);
      var settings = new JsonObject(); settings.addProperty("exposure_raw", 21);
      var accepted = sink.write(writer.session(), 11_000_000, List.of(new PhotonSettings.Camera("front", settings)), metadata);
      loop.drain(); accepted.toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertEquals(11_000_000, live.latest().get("/Daemon/PhotonVision/front/Settings").serverTimestampUs(), "the loop goes on writing");
    });
  }
}
