/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;

/** Owns the recorder for a configured HTTP server; waiting for a robot never blocks startup. */
public final class CaptureService implements AutoCloseable {
  private final Nt4Client client;

  public CaptureService(CaptureConfig config, LogManager manager) throws IOException {
    this(config, manager, Clock.systemUTC(), ClientScheduler.daemon());
  }

  public CaptureService(CaptureConfig config, LogManager manager, Clock clock, ClientScheduler loop)
      throws IOException {
    var placement = manager.stores().store(config.store()).captures(clock);
    var writer = new CaptureWriter(clock, loop, config.policy(), placement);
    client = new Nt4Client(config.addresses(), Nt4Client.captureSubscription(config.periodSeconds()), writer,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), loop);
  }

  public void start() { client.start(); }

  @Override public void close() {
    try { client.closeAsync().get(15, TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      org.slf4j.LoggerFactory.getLogger(CaptureService.class).error("Capture shutdown did not finish", e);
    }
  }
}
