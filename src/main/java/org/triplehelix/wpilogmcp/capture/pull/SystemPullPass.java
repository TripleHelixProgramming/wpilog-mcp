/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.pull;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.triplehelix.wpilogmcp.config.SystemPullConfig;
import org.triplehelix.wpilogmcp.ssh.SshConnection;
import org.triplehelix.wpilogmcp.store.SystemPullStore;
import org.triplehelix.wpilogmcp.sync.FileTransfer;
import org.triplehelix.wpilogmcp.sync.RemoteFiles;

/** A second phase of the ordinary pull pass, on its worker, gate and byte budget. Commands
 * stream to disk one block at a time; no unbounded exec reply waits in memory. A separate
 * deadline can close a blocked command even when the pull worker itself cannot run. */
public final class SystemPullPass implements AutoCloseable {
  private static final String HEADER = "WPILOG_SYSTEM_BEGIN ", END = "WPILOG_SYSTEM_END ";
  private final RobotRemote remote;
  private final SystemRemote system;
  private final SystemPullStore local;
  private final SystemPullConfig config;
  private final long rate;
  private final LongSupplier clock;
  private final BooleanSupplier gate;
  private final java.util.concurrent.ScheduledThreadPoolExecutor deadline;
  private FileTransfer files;
  private String transferSession;
  private final java.util.Map<RemoteFiles.File, Optional<String>> hashes = new java.util.HashMap<>();
  private int phase;
  private long nextRead;
  private String staging;
  private long held;
  private volatile SshConnection.Command command;
  private volatile boolean closed;

  public SystemPullPass(RobotRemote remote, SystemPullStore local, SystemPullConfig config,
      long rate, LongSupplier clock, BooleanSupplier gate) {
    this.remote = remote; this.system = remote instanceof SystemRemote s ? s : null;
    this.local = local; this.config = config; this.rate = rate; this.clock = clock; this.gate = gate;
    deadline = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> { var t = new Thread(task, "system-pull-deadline"); t.setDaemon(true); return t; });
    deadline.setRemoveOnCancelPolicy(true);
  }
  public FileTransfer.Result step() throws IOException {
    if (closed || !gate.getAsBoolean()) {
      pause(); return result(FileTransfer.Status.PAUSED, 0, null);
    }
    if (clock.getAsLong() < nextRead) return result(FileTransfer.Status.WAITING, 0, null);
    if (phase == 0) {
      if (!local.beginPass()) return result(FileTransfer.Status.IDLE, 0, "Waiting for an open capture session");
      if (system == null) return result(FileTransfer.Status.REFUSED, 0, "SSH system-log transport unavailable");
      if (files == null || !local.sessionId().equals(transferSession)) {
        transferSession = local.sessionId();
        var held = local.manifest();
        // A new session needs its own receipts even for an unchanged NI file. Verify the
        // held prefix again, without fetching it again; completed snapshots remain immutable.
        var associate = new org.triplehelix.wpilogmcp.sync.PullManifest(held.formatVersion(), held.serialNumber(),
            held.files().stream().map(e -> new org.triplehelix.wpilogmcp.sync.PullManifest.Entry(e.remoteName(), e.size(), e.mtimeMillis(),
                e.bytesCopied(), false, e.localName(), e.retries(), e.failure())).toList(), held.history());
        files = new FileTransfer(new RemoteFiles() {
        public List<File> list() throws IOException {
          var listing = system.systemFiles(config).stream().sorted(java.util.Comparator.comparing(f -> f.file().name())).toList();
          var sources = new java.util.HashMap<String, String>(); var selectedHashes = new java.util.HashSet<String>();
          var retained = new ArrayList<File>();
          hashes.keySet().retainAll(listing.stream().map(SystemRemote.SourceFile::file).collect(java.util.stream.Collectors.toSet()));
          for (var item : listing) {
            var file = item.file(); sources.put(file.name(), item.source());
            // A complete hash proves a rotation even if both names are still in the listing.
            var hash = Optional.<String>empty();
            if (item.source().equals("syslog")) {
              hash = hashes.get(file);
              if (hash == null) { hash = remote.prefixHash(file.name(), file.size()); hashes.put(file, hash); }
            }
            if (hash.isPresent()) {
              if (local.reuse(file.name(), item.source(), hash.get(), file.size()) || !selectedHashes.add(hash.get())) continue;
            }
            retained.add(file);
          }
          local.sources(sources); return retained;
        }
        public byte[] read(String name, long offset, int count) throws IOException { return remote.read(name, offset, count); }
        public Optional<String> prefixHash(String name, long length) throws IOException { return remote.prefixHash(name, length); }
        }, local, associate, rate, clock, gate);
      }
      phase = 1;
    }
    if (phase == 1) {
      var step = files.step();
      if (step.status() != FileTransfer.Status.IDLE) return step;
      phase = 2;
    }
    var state = local.state();
    if (phase == 2 && (config.kernel() == SystemPullConfig.Kernel.OFF || state.reasons().containsKey("kernel"))) phase++;
    if (phase == 3 && (!config.journal() || state.reasons().containsKey("journal"))) phase++;
    if (phase == 4) { phase = 0; return result(FileTransfer.Status.IDLE, 0, null); }
    String source = phase == 2 ? "kernel" : "journal";
    if (command == null) {
      if (staging != null) { local.discard(staging); staging = null; }
      command = system.systemCommand(command(source, state.journalCursor(), state.journalBootId()));
      staging = local.create(source); held = 0;
    }
    var active = command; var expired = new java.util.concurrent.atomic.AtomicBoolean();
    var timeout = deadline.schedule(() -> { expired.set(true); active.close(); }, 30, java.util.concurrent.TimeUnit.SECONDS);
    byte[] block;
    try {
      block = active.output().readNBytes(FileTransfer.BLOCK_BYTES);
      if (expired.get()) throw new IOException("System-log command did not supply a block within 30 seconds");
    } finally { timeout.cancel(false); }
    if (closed) return result(FileTransfer.Status.PAUSED, 0, null);
    if (block.length > 0) {
      local.append(staging, held, block); held += block.length;
      nextRead = clock.getAsLong() + (long) Math.ceil(block.length * 1_000_000.0 / rate);
      return result(FileTransfer.Status.COPIED, block.length, null);
    }
    active.close(); command = null;
    boolean recorded;
    try { recorded = finish(source); }
    finally { local.discard(staging); staging = null; }
    phase++;
    return result(recorded ? FileTransfer.Status.VERIFIED : FileTransfer.Status.WAITING, 0, null);
  }
  /** Wrapper status is parsed separately from log text; errors never masquerade as a log line. */
  public static String command(String source, String cursor, String boot) {
    String action = source.equals("kernel") ? "dmesg" : "journalctl -q --no-pager -o short-unix --show-cursor"
        + (cursor == null ? " -b " + (boot == null ? "\"$boot\"" : SshConnection.quote(boot)) : " --after-cursor " + SshConnection.quote(cursor));
    return "IFS=' ' read -r uptime rest < /proc/uptime; IFS= read -r boot < /proc/sys/kernel/random/boot_id; "
        + "printf '" + HEADER + "%s %s\\n' \"$uptime\" \"$boot\"; " + action
        + "; result=$?; printf '\\n" + END + "%s\\n' \"$result\"";
  }
  private boolean finish(String source) throws IOException {
    var path = local.snapshotPath(staging);
    String header, footer = null, cursor = null, previous = null; long lineCount = 1; boolean journalData = false;
    // First validate the complete reply before publishing any of its lines or advancing a cursor.
    try (var reader = Files.newBufferedReader(path)) {
      header = reader.readLine(); String line;
      while ((line = reader.readLine()) != null) {
        lineCount++; previous = footer; footer = line;
        if (line.startsWith("-- cursor: ")) cursor = line.substring(11).strip();
        else if (!line.startsWith(END) && !line.isBlank()) journalData = true;
      }
    }
    if (header == null || !header.startsWith(HEADER) || footer == null || !footer.startsWith(END)) throw new IOException("Incomplete system-log command reply; cursor unchanged, retry after reconnect");
    if (!"WPILOG_SYSTEM_END 0".equals(footer)) {
      local.standDown(source, (source.equals("journal") ? "journalctl unavailable or failed; no syslog-file fallback" : "dmesg unavailable or failed (including permission denial)")
          + "; exit " + footer.substring(END.length())); return false;
    }
    String[] fields = header.substring(HEADER.length()).split(" ", 2);
    double uptime;
    try { uptime = Double.parseDouble(fields[0]); }
    catch (RuntimeException e) { throw new IOException("Invalid kernel uptime in system-log reply", e); }
    if (fields.length != 2 || fields[1].isBlank() || !Double.isFinite(uptime) || uptime < 0) throw new IOException("Missing boot evidence in system-log reply");
    if (source.equals("journal") && cursor == null) {
      if (!journalData) return true; // A quiet journal need not emit a new cursor; retain the old one.
      local.standDown(source, "journalctl returned data without a cursor; reply cannot be committed safely"); return false;
    }
    try (var lines = Files.lines(path)) {
      // Exclude the wrapper and its one separator newline, retaining blank lines within the record.
      var records = lines.skip(1).limit(Math.max(0, lineCount - 2 - ("".equals(previous) ? 1 : 0)))
          .filter(l -> !source.equals("journal") || !l.startsWith("-- cursor: "));
      if (source.equals("kernel")) {
        // Linux's ring is bounded; protect a misconfigured exec endpoint without silently truncating it.
        if (Files.size(path) > 16 * 1024 * 1024) { local.standDown(source, "dmesg snapshot exceeds the 16 MiB ring-buffer safety bound"); return false; }
        local.kernel(records.toList(), uptime);
      } else local.journal(records::iterator, cursor, fields[1]);
    }
    return true;
  }
  private FileTransfer.Result result(FileTransfer.Status status, long bytes, String detail) {
    return new FileTransfer.Result(status, Math.max(0, nextRead - clock.getAsLong()), phase == 2 ? "dmesg" : phase == 3 ? "journalctl" : null, bytes, detail);
  }
  /** JSch writes exec output from the shared network reader. A paused pipe can block every
   * other channel, so release it and retry this snapshot from its durable cursor on resume. */
  public void pause() {
    var active = command; command = null;
    if (active != null) active.close();
  }
  @Override public void close() {
    closed = true; deadline.shutdownNow(); pause();
    if (staging != null) local.discardLater(staging);
  }
}
