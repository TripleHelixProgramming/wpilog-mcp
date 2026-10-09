/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.capture.context.DeviceIdentity;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.nt4.client.ClientScheduler;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.sync.FileTransfer;

/** All SSH and file work runs on its own daemon, never on the NT4 event loop. */
public final class PullCoordinator implements AutoCloseable {
  @FunctionalInterface public interface Connector {
    RobotRemote connect(String address, PullConfig config, String pinnedFingerprint) throws IOException;
  }
  public record Identity(long connection, DeviceIdentity device) {}
  @FunctionalInterface public interface Factory {
    PullCoordinator create(PullConfig config, PullGate gate, LogStore store, Clock wall, Consumer<Identity> identity);
  }
  private final PullConfig config;
  private final PullGate gate;
  private final LogStore store;
  private final Clock wall;
  private final ClientScheduler worker;
  private final Connector connector;
  private final Consumer<Identity> identity;
  private final AtomicBoolean stopped = new AtomicBoolean(), started = new AtomicBoolean();
  private final AtomicBoolean busy = new AtomicBoolean();
  private final java.util.concurrent.CompletableFuture<Void> closing = new java.util.concurrent.CompletableFuture<>();
  private final AtomicReference<RobotRemote> remote = new AtomicReference<>();
  private FileTransfer transfer;
  private volatile SystemPullPass system;
  private boolean systemPhase;
  private long connection = -1;
  private FileTransfer.Status lastStatus;
  /** Cumulative copied payload bytes and successful verifications for this server process. */
  public record Progress(long bytes, long files, int pendingFiles, int waitingFiles) {}
  private volatile java.util.Map<String, Progress> progress = java.util.Map.of();
  private volatile String serial;
  public java.util.Map<String, Progress> progress() {
    var snapshot = progress;
    boolean open = gate.open(); String current = serial;
    var result = new java.util.TreeMap<String, Progress>();
    snapshot.forEach((robot, p) -> result.put(robot,
        new Progress(p.bytes(), p.files(), p.pendingFiles(), open && robot.equals(current) ? 0 : p.pendingFiles())));
    return java.util.Map.copyOf(result);
  }

  public PullCoordinator(PullConfig config, PullGate gate, LogStore store, Clock wall, Consumer<Identity> identity) {
    this(config, gate, store, wall, identity, ClientScheduler.daemon("robot-pull"), SftpTransport::connect);
  }
  public PullCoordinator(PullConfig config, PullGate gate, LogStore store, Clock wall, Consumer<Identity> identity,
      ClientScheduler worker, Connector connector) {
    this.config = config; this.gate = gate; this.store = store; this.wall = wall; this.identity = identity;
    this.worker = worker; this.connector = connector;
  }
  public void start() { if (!stopped.get() && started.compareAndSet(false, true)) worker.execute(this::tick); }

  private void tick() {
    if (stopped.get()) return;
    long delay = 250_000;
    try {
      var result = step();
      delay = result.waitUs() > 0 ? Math.min(result.waitUs(), 250_000) : result.status() == FileTransfer.Status.IDLE ? 3_000_000 : 250_000;
      if (result.status() == FileTransfer.Status.COPIED) delay = Math.max(1, result.waitUs());
    } catch (Exception e) {
      closeRemote(); transfer = null;
      LoggerFactory.getLogger(PullCoordinator.class).warn("Robot pull will retry: {}", e.getMessage()); delay = 3_000_000;
    } finally {
      if (!stopped.get()) worker.schedule(this::tick, delay);
    }
  }

  /** Test seam for a complete scheduling step; only the coordinator's one worker calls it in use. */
  public FileTransfer.Result step() throws Exception {
    if (!busy.compareAndSet(false, true)) throw new IllegalStateException("A pull step is already running");
    try { return performStep(); } finally { busy.set(false); }
  }
  private FileTransfer.Result performStep() throws Exception {
    if (connection != gate.connection()) { closeRemote(); transfer = null; connection = gate.connection(); }
    if (stopped.get() || !gate.open()) {
      if (transfer != null) transfer.step(); // Drop the old prefix proof when the gate closes.
      if (system != null) system.pause();
      return report(new FileTransfer.Result(FileTransfer.Status.PAUSED, 0, null, 0, null));
    }
    if (transfer == null) {
      long episode = gate.connection(); String address = gate.address();
      if (address == null) return report(new FileTransfer.Result(FileTransfer.Status.PAUSED, 0, null, 0, null));
      String pin = store.hostKey(address).get();
      if (stopped.get() || !gate.open() || episode != gate.connection()) return report(new FileTransfer.Result(FileTransfer.Status.PAUSED, 0, null, 0, null));
      var contact = connector.connect(address, config, pin);
      remote.set(contact);
      if (stopped.get() || !gate.open() || episode != gate.connection()) { closeRemote(); return report(new FileTransfer.Result(FileTransfer.Status.PAUSED, 0, null, 0, null)); }
      var device = contact.identity();
      if (stopped.get() || !gate.open() || episode != gate.connection()) { closeRemote(); return report(new FileTransfer.Result(FileTransfer.Status.PAUSED, 0, null, 0, null)); }
      store.identify(device, wall).get();
      serial = device.serialNumber();
      identity.accept(new Identity(episode, device));
      var local = store.pulls(device, wall);
      transfer = new FileTransfer(contact, local, local.manifest(), config.rateBytes(), worker::nowUs,
          () -> !stopped.get() && gate.open() && episode == gate.connection());
      if (config.system().enabled()) system = new SystemPullPass(contact, store.systemPulls(device, wall), config.system(),
          config.rateBytes(), worker::nowUs, () -> !stopped.get() && gate.open() && episode == gate.connection());
      connection = episode;
    }
    var result = systemPhase ? system.step() : transfer.step();
    if (result.status() == FileTransfer.Status.IDLE && system != null) {
      systemPhase = !systemPhase;
      if (systemPhase) return report(new FileTransfer.Result(FileTransfer.Status.WAITING, 0, null, 0, null));
    }
    return report(result);
  }
  private FileTransfer.Result report(FileTransfer.Result result) {
    var status = result.status();
    if (serial != null) {
      var before = progress.getOrDefault(serial, new Progress(0, 0, 0, 0));
      var next = new java.util.HashMap<>(progress);
      next.put(serial, new Progress(before.bytes() + result.bytes(),
          before.files() + (status == FileTransfer.Status.VERIFIED ? 1 : 0),
          transfer == null ? before.pendingFiles() : transfer.pendingFiles(), 0));
      progress = java.util.Map.copyOf(next);
    }
    if (status == FileTransfer.Status.VERIFIED || status == FileTransfer.Status.RETRIED || status == FileTransfer.Status.REFUSED
        || status != lastStatus && (status == FileTransfer.Status.PAUSED || status == FileTransfer.Status.COPIED)) {
      LoggerFactory.getLogger(PullCoordinator.class).info("Robot pull {}: {} {}", status, result.remoteName(), result.detail() == null ? "" : result.detail());
    }
    if (status != FileTransfer.Status.WAITING) lastStatus = status;
    return result;
  }
  private void closeRemote() {
    var secondPass = system; system = null; systemPhase = false;
    if (secondPass != null) secondPass.close();
    var contact = remote.getAndSet(null);
    if (contact != null) try { contact.close(); } catch (IOException e) { LoggerFactory.getLogger(PullCoordinator.class).debug("SSH close failed: {}", e.getMessage()); }
  }
  /** Transport shutdown must share the capture's deadline, not block before it begins. */
  public java.util.concurrent.CompletableFuture<Void> closeAsync() {
    if (stopped.compareAndSet(false, true)) {
      worker.close();
      var closer = new Thread(() -> {
        try { closeRemote(); closing.complete(null); }
        catch (RuntimeException e) { closing.completeExceptionally(e); }
      }, "robot-pull-close");
      closer.setDaemon(true); closer.start();
    }
    return closing;
  }
  @Override public void close() { closeAsync(); }
}
