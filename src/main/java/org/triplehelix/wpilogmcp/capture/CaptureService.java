/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;

/** Owns the recorder for a configured HTTP server; waiting for a robot never blocks startup. */
public final class CaptureService implements AutoCloseable {
  private final Nt4Client client;
  private final org.triplehelix.wpilogmcp.store.CaptureStore placement;

  public CaptureService(CaptureConfig config, LogManager manager) throws IOException {
    this(config, manager, Clock.systemUTC(), ClientScheduler.daemon());
  }

  public CaptureService(CaptureConfig config, LogManager manager, Clock clock, ClientScheduler loop)
      throws IOException {
    this(config, manager, clock, loop, WpilogOutput::new);
  }

  /** Injectable output for filesystem-failure checks over a real loopback NT4 connection. */
  public CaptureService(CaptureConfig config, LogManager manager, Clock clock, ClientScheduler loop,
      CaptureWriter.OutputFactory outputs) throws IOException {
    placement = manager.stores().store(config.store()).captures(clock);
    var writer = new CaptureWriter(clock, loop, config.policy(), new CaptureIndex(placement, manager, config.hotWindowUs()), config.maxFileBytes(), outputs);
    client = new Nt4Client(config.addresses(), Nt4Client.captureSubscription(config.periodSeconds()), writer,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), loop);
  }

  public void start() { client.start(); }

  @Override public void close() {
    try {
      client.closeAsync().get();
      // The NT4 event loop is already stopped. Only shutdown waits behind store imports/hashing.
      placement.completion().get();
    }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    catch (java.util.concurrent.ExecutionException e) {
      org.slf4j.LoggerFactory.getLogger(CaptureService.class).error("Capture shutdown did not finish", e);
    }
  }
}
