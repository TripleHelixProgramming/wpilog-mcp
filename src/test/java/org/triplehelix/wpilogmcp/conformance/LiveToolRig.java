/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.triplehelix.wpilogmcp.capture.CapturePolicy;
import org.triplehelix.wpilogmcp.capture.CaptureService;
import org.triplehelix.wpilogmcp.config.CaptureConfig;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.nt4.client.ManualScheduler;
import org.triplehelix.wpilogmcp.nt4.client.Nt4Client;
import org.triplehelix.wpilogmcp.nt4.client.RobotAddress;
import org.triplehelix.wpilogmcp.nt4.server.Nt4Gateway;
import org.triplehelix.wpilogmcp.tools.LiveTools;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/** The production capture service and all HTTP tools; only robot and both clocks are synthetic. */
final class LiveToolRig implements AutoCloseable {
  final ManualScheduler loop = new ManualScheduler();
  final AtomicLong robot = new AtomicLong(10_000_000);
  final AtomicLong calendar = new AtomicLong(1_767_225_600_000L);
  final LogManager manager = LogManager.getInstance();
  final java.util.Set<Path> saved = manager.getAllowedDirectories();
  final List<Path> savedDirs = org.triplehelix.wpilogmcp.log.LogDirectory.getInstance().getLogDirectories();
  final Path root;
  final Nt4Gateway gateway;
  final CaptureService service;
  final HttpTransport transport;
  final HarnessHttp http;
  final ToolRegistry tools = new ToolRegistry();
  java.util.function.BiConsumer<String, JsonObject> observed = (name, result) -> {};
  LiveToolRig(Path root, CapturePolicy policy) throws Exception { this(root, policy, java.util.function.UnaryOperator.identity()); }
  LiveToolRig(Path root, CapturePolicy policy, java.util.function.UnaryOperator<CaptureConfig> configure) throws Exception {
    this.root = Files.createDirectories(root).toRealPath(); manager.addAllowedDirectory(this.root);
    org.triplehelix.wpilogmcp.log.LogDirectory.getInstance().setLogDirectory(this.root.toString());
    gateway = new Nt4Gateway(new java.net.InetSocketAddress("127.0.0.1", 0), robot::get, List.of(Nt4Client.V40));
    gateway.start().get(10, TimeUnit.SECONDS);
    var clock = new Clock() {
      public Instant instant() { return Instant.ofEpochMilli(calendar.get()); }
      public ZoneId getZone() { return java.time.ZoneOffset.UTC; }
      public Clock withZone(ZoneId zone) { return this; }
    };
    service = new CaptureService(configure.apply(new CaptureConfig(List.of(RobotAddress.uri("127.0.0.1", gateway.port(), "live-test")),
        this.root, .001, policy, 0)), manager, clock, loop);
    WpilogTools.registerAll(tools); LiveTools.registerAll(tools, service.live());
    transport = new HttpTransport(tools, 0); transport.setStoreDirectories(java.util.Set.of(this.root));
    transport.configureMetrics(null, service.live()); transport.start();
    http = new HarnessHttp(transport.getPort()); http.initialize();
    service.start().get(10, TimeUnit.SECONDS);
    pump(() -> service.live().connected());
  }
  void pump(BooleanSupplier ready) {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!ready.getAsBoolean() && System.nanoTime() < end) { loop.advance(0); java.util.concurrent.locks.LockSupport.parkNanos(100_000); }
    assertTrue(ready.getAsBoolean(), "Expected ordered callback"); loop.drain();
  }
  void announce(String name, String type) throws Exception {
    gateway.announce(name, type, new JsonObject()).get(); pump(() -> service.live().topics().containsKey(name));
  }
  void value(String name, long timestamp, int code, Object value) throws Exception {
    long before = service.live().receivedValues(); gateway.value(name, timestamp, code, value).get();
    pump(() -> service.live().receivedValues() > before);
  }
  void flush() {
    var before = service.live().current(); loop.advance(250_000);
    if (before != null) pump(() -> service.live().current() != before);
  }
  JsonObject call(String tool, String args) throws Exception {
    var result = CompletableFuture.supplyAsync(() -> {
      try { return http.call(tool, JsonParser.parseString(args).getAsJsonObject()); }
      catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
    });
    pump(result::isDone); var value = result.get(10, TimeUnit.SECONDS); observed.accept(tool, value); return value;
  }
  JsonObject session() throws Exception { return call("list_sessions", "{}").getAsJsonArray("sessions").get(0).getAsJsonObject(); }
  @Override public void close() throws Exception {
    service.live().endWaits("test ended");
    var closed = CompletableFuture.runAsync(service::close); pump(closed::isDone); closed.get(10, TimeUnit.SECONDS);
    transport.stop(); gateway.close(); manager.release(root);
    manager.clearAllowedDirectories(); saved.forEach(manager::addAllowedDirectory);
    org.triplehelix.wpilogmcp.log.LogDirectory.getInstance().setLogDirectories(savedDirs.stream().map(Path::toString).toList());
  }
}
