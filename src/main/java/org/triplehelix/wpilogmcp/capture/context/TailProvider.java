/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.config.ProviderConfig;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.ssh.SshConnection;

/** Each stream has its own reader. Neither a quiet tail nor an SSH call occupies the capture loop. */
public final class TailProvider implements AutoCloseable {
  public record State(String state, String reason, Double roundTripMs) {}
  private final ProviderConfig.Tail config;
  private final Supplier<SshConnection> connection;
  private final LongSupplier clock, period;
  private final BooleanSupplier sessionOpen;
  private final ClientScheduler worker;
  private final TailBuffer buffer = new TailBuffer();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Lines lines = new Lines();
  private volatile State state = new State("offline", "NT4 is disconnected", null);
  private volatile Object session;
  private volatile Runnable activity = () -> {};
  public void onActivity(Runnable action) { activity = action; }
  private Object blockedSession;
  private boolean blocked;
  private String blockedReason;
  private SshConnection current;
  private volatile SshConnection.Command command;
  private boolean polling;
  private long nextPoll, offset = -1;
  private String inode;

  public TailProvider(ProviderConfig.Tail config, Supplier<SshConnection> connection, LongSupplier clock,
      LongSupplier period, BooleanSupplier sessionOpen, ClientScheduler worker) {
    this.config = config; this.connection = connection; this.clock = clock; this.period = period;
    this.sessionOpen = sessionOpen; this.worker = worker;
  }
  public void start() { worker.execute(this::tick); }
  public void session(Object value) { session = value; }
  public TailBuffer buffer() { return buffer; }
  public State state() { return state; }
  private void state(String value, String reason, Double rtt) {
    var before = state; state = new State(value, reason, rtt);
    if (!before.state().equals(value) || !java.util.Objects.equals(before.reason(), reason)) {
      activity.run();
      LoggerFactory.getLogger(TailProvider.class).info("Tail {} {}{}", config.path(), value, reason == null ? "" : ": " + reason);
    }
  }
  private void block(String reason) { blocked = true; blockedSession = session; blockedReason = reason; state("stand_down", reason, state.roundTripMs()); }
  private void tick() {
    if (closed.get()) return;
    try {
      // A miss before the first announcement belongs to that first session too.
      if (blocked && blockedSession == null && session != null) blockedSession = session;
      if (blocked && blockedSession != null && session != null && session != blockedSession) blocked = false;
      var ssh = connection.get();
      if (ssh != null && !ssh.connected()) ssh = null;
      if (ssh == null) { stopCommand(); current = null; state("offline", "SSH or NT4 is disconnected", null); return; }
      if (blocked) { state("stand_down", blockedReason, state.roundTripMs()); return; }
      if (current != ssh) { stopCommand(); current = ssh; polling = false; offset = -1; inode = null; lines.reset(); }
      if (polling) { if (clock.getAsLong() >= nextPoll) poll(ssh); return; }
      if (command != null) return;
      long sent = clock.getAsLong();
      command = ssh.follow(TailCommand.open(config.path(), config.role()));
      var failure = new java.util.concurrent.atomic.AtomicReference<String>();
      // A missing/unsupported command cannot leave a worker waiting indefinitely for its handshake.
      var channel = command;
      state("connecting", null, null);
      var reader = new Thread(() -> read(channel, sent, failure), "ssh-tail-" + config.role()); reader.setDaemon(true); reader.start();
      worker.schedule(() -> { if (command == channel && state.state().equals("connecting")) { failure.set("Tail handshake exceeded five seconds"); channel.close(); } }, 5_000_000);
    } catch (Exception e) {
      stopCommand();
      if (current != null && current.connected()) block("Cannot follow " + config.path() + ": " + e.getMessage());
      else state("waiting", "SSH disconnected", null);
    } finally { if (!closed.get()) worker.schedule(this::tick, 250_000); }
  }
  private void read(SshConnection.Command channel, long sent, java.util.concurrent.atomic.AtomicReference<String> failure) {
    try (var input = new BufferedInputStream(channel.output())) {
      var header = new ByteArrayOutputStream(); int next;
      while ((next = input.read()) != -1 && next != '\n') {
        if (header.size() == 256) throw new IOException("Invalid tail handshake"); header.write(next);
      }
      if (closed.get() || command != channel) return;
      String mode = header.toString(StandardCharsets.UTF_8);
      double rtt = (clock.getAsLong() - sent) / 1000.0;
      if (mode.equals(TailCommand.MISSING)) { failure.set("File is missing or unreadable: " + config.path()); return; }
      if (mode.equals(TailCommand.POLL)) {
        worker.execute(() -> { if (!closed.get() && command == channel) { stopCommand(); polling = true; state("polling", null, rtt); } }); return;
      }
      if (!mode.equals(TailCommand.FOLLOW)) throw new IOException("Source did not acknowledge the follow command: " + config.path());
      state("following", null, rtt);
      while (!closed.get() && command == channel && (next = input.read()) != -1) {
        if (command == channel) lines.accept(next);
      }
    } catch (IOException e) { failure.compareAndSet(null, e.getMessage()); }
    finally {
      if (!closed.get()) worker.execute(() -> {
        if (command == channel) {
          stopCommand();
          if (current != null && current.connected()) block(failure.get() == null ? "Follow command ended; source unavailable for this session" : failure.get());
          else state("offline", "SSH disconnected", null);
        }
      });
    }
  }
  private void poll(SshConnection ssh) throws IOException {
    long sent = clock.getAsLong();
    String stat = ssh.exec(TailCommand.stat(config.path()), 5000, 256).orElseThrow(() -> new IOException("SSH exec unavailable"));
    var parts = stat.strip().split("\\s+");
    if (parts.length != 2 || !parts[0].matches("[0-9]+") || !parts[1].matches("[0-9]+")) throw new IOException("File is missing or unreadable: " + config.path());
    long size = Long.parseLong(parts[1]);
    if (offset < 0) offset = size;
    else if (!parts[0].equals(inode) || size < offset) { offset = 0; lines.reset(); }
    inode = parts[0];
    if (size > offset) {
      byte[] bytes = ssh.execBytes(TailCommand.read(config.path(), offset), 5000, 65_536).orElseThrow(() -> new IOException("SSH exec unavailable"));
      for (byte value : bytes) lines.accept(value & 255);
      offset += bytes.length;
    }
    nextPoll = clock.getAsLong() + period.getAsLong(); state("polling", null, (clock.getAsLong() - sent) / 1000.0);
  }
  private void stopCommand() { var old = command; command = null; if (old != null) old.close(); }
  @Override public void close() { if (closed.compareAndSet(false, true)) { stopCommand(); worker.close(); } }

  private final class Lines {
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private boolean oversized;
    synchronized void accept(int value) {
      if (value == '\n') {
        if (oversized) buffer.oversized(clock.getAsLong());
        else buffer.line(line.toString(StandardCharsets.UTF_8).replaceFirst("\\r$", ""), clock.getAsLong(), !sessionOpen.getAsBoolean());
        line.reset(); oversized = false; activity.run();
      } else if (!oversized && line.size() < TailBuffer.MAX_LINE_BYTES) line.write(value);
      else { oversized = true; line.reset(); }
    }
    synchronized void reset() {
      if (line.size() > 0 || oversized) buffer.oversized(clock.getAsLong());
      line.reset(); oversized = false;
    }
  }
}
