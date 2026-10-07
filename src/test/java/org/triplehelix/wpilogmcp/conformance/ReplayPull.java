/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.triplehelix.wpilogmcp.capture.pull.SftpTransport;
import org.triplehelix.wpilogmcp.config.PullConfig;
import org.triplehelix.wpilogmcp.harness.FakeRoboRio;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.sync.FileTransfer;

/** Real SSH/SFTP, production transfer verification and store matching; time alone is injected. */
final class ReplayPull implements AutoCloseable {
  final FakeRoboRio rio;
  final SftpTransport remote;
  final org.triplehelix.wpilogmcp.capture.context.DeviceIdentity device;

  ReplayPull(ReplaySource source, Path root) throws Exception {
    rio = new FakeRoboRio(root, source.serial(), "replay");
    var defaults = PullConfig.DISABLED;
    var config = new PullConfig(true, defaults.directories(), 0, 1_000_000,
        new PullConfig.Ssh("lvuser", "", null, false, rio.port()));
    remote = SftpTransport.connect("127.0.0.1", config, null); device = remote.identity();
    Files.copy(source.path, rio.logs().resolve(source.path.getFileName()));
  }

  Map<String, Object> verify(ReplaySource source, ReplayCapture capture, Clock wall, long shiftUs) throws Exception {
    return verify(source, capture, wall, shiftUs, capture.files.get(0));
  }

  Map<String, Object> verify(ReplaySource source, ReplayCapture capture, Clock wall, long shiftUs, Path expectedCapture) throws Exception {
    try { return verifyComplete(source, capture, wall, shiftUs, expectedCapture); }
    finally { capture.manager.release(source.path); }
  }

  private Map<String, Object> verifyComplete(ReplaySource source, ReplayCapture capture, Clock wall,
      long shiftUs, Path expectedCapture) throws Exception {
    var companions = companions(source, capture);
    for (var bus : companions) {
      var path = Path.of(bus.revlog().path());
      Files.copy(path, rio.logs().resolve(path.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    var local = capture.store.pulls(device, wall); var now = new AtomicLong();
    int expectedFiles = remote.list().size();
    var transfer = new FileTransfer(remote, local, local.manifest(), 1_000_000, now::get, () -> true);
    long size = Files.size(source.path);
    for (var bus : companions) size += Files.size(Path.of(bus.revlog().path()));
    long bound = 2 * (size / FileTransfer.BLOCK_BYTES + 1) + 100L * (companions.size() + 1);
    boolean verified = false;
    for (long step = 0; step < bound; step++) {
      var result = transfer.step(); now.addAndGet(Math.max(1, result.waitUs()));
      if (transfer.manifest().files().size() == expectedFiles
          && transfer.manifest().files().stream().allMatch(e -> e.verified() || e.failure() != null)) {
        verified = transfer.manifest().files().stream().filter(e -> e.remoteName().endsWith("/" + source.path.getFileName()))
            .allMatch(org.triplehelix.wpilogmcp.sync.PullManifest.Entry::verified); break;
      }
    }
    var security = new org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator(); security.addAllowedDirectory(capture.directory);
    var catalog = StoreCatalog.read(capture.directory, security);
    var result = new LinkedHashMap<String, Object>(); result.put("verified", verified);
    result.put("revlogs", compareCompanions(companions, capture, catalog, shiftUs,
        capture.files.stream().filter(p -> p.getParent().equals(expectedCapture.getParent())).toList(), transfer.manifest()));
    if (!verified) {
      var entry = transfer.manifest().files().stream().filter(e -> e.remoteName().endsWith("/" + source.path.getFileName())).findFirst().orElseThrow();
      boolean originalRefused = false;
      try { org.triplehelix.wpilogmcp.sync.TransferVerification.verify(source.path, capture.manager); }
      catch (java.io.IOException expected) { originalRefused = true; }
      result.put("rejected_incomplete_source", source.reader.stopped != null && originalRefused && entry.failure() != null);
      result.put("retries", entry.retries()); result.put("bytes_held", entry.bytesCopied());
      return result;
    }
    var pulled = catalog.files().stream().filter(f -> f.file().provenance().kind().equals("pulled")
        && f.file().kind().equals("wpilog") && f.file().provenance().originalName().equals(source.path.getFileName().toString())).findFirst().orElseThrow();
    var capturePath = expectedCapture.toRealPath();
    var captured = catalog.files().stream().filter(f -> f.path().equals(capturePath)).findFirst().orElseThrow();
    boolean placed = pulled.manifestPath().equals(captured.manifestPath());
    result.put("placed", placed); result.put("expected_placement", Math.abs(shiftUs) <= 250_000);
    result.put("same_serial", pulled.robot().serialNumber().equals(captured.robot().serialNumber()));
    var match = pulled.file().matching();
    if (match != null) result.put("offset_us", match.offsetMicros());
    result.put("refusal_recorded", pulled.file().matchingReason() != null);
    // Query the copied file over HTTP as well as checking the manifest; refusal must not hide it.
    var args = new JsonObject(); args.addProperty("path", pulled.path().toString());
    var listing = capture.http.call("list_entries", args);
    result.put("retrievable", listing.has("entries") && listing.getAsJsonArray("entries").size() == source.entries.size());
    result.put("copy_equal", Files.mismatch(source.path, pulled.path()) == -1);
    var available = capture.http.call("list_available_logs", new JsonObject());
    boolean listedReason = available.getAsJsonArray("logs").asList().stream().map(e -> e.getAsJsonObject())
        .anyMatch(e -> e.get("path").getAsString().equals(pulled.path().toString()) && e.has("matching_reason")
            && e.get("matching_reason").getAsString().equals(pulled.file().matchingReason()));
    result.put("refusal_listed", listedReason);
    return result;
  }

  private static List<Map<String, Object>> compareCompanions(
      List<org.triplehelix.wpilogmcp.sync.SynchronizedLogs.SyncedRevLog> companions,
      ReplayCapture capture, StoreCatalog.Snapshot catalog, long shiftUs, List<Path> files,
      org.triplehelix.wpilogmcp.sync.PullManifest manifest) throws Exception {
    var revResults = new ArrayList<Map<String, Object>>();
    for (var bus : companions) {
      var original = Path.of(bus.revlog().path());
      var held = manifest.files().stream().filter(e -> e.remoteName().endsWith("/" + original.getFileName())).findFirst().orElseThrow();
      var stored = catalog.files().stream().filter(f -> f.file().provenance().kind().equals("pulled")
          && f.file().provenance().originalName().equals(original.getFileName().toString())).findFirst();
      Path copy = stored.map(StoreCatalog.StoredFile::path).orElseGet(() -> capture.directory.resolve(held.localName()));
      boolean refused = false;
      if (!held.verified()) {
        try { org.triplehelix.wpilogmcp.sync.TransferVerification.verify(original, capture.manager); }
        catch (java.io.IOException incomplete) { refused = held.failure() != null && held.retries() == 1; }
      }
      boolean visible = true;
      var parts = new ArrayList<Map<String, Object>>();
      var alignments = new ArrayList<org.triplehelix.wpilogmcp.sync.SyncResult>();
      for (var file : files) {
        waitForSync(capture, file);
        var actual = capture.manager.syncRevLog(file.toString(), copy.toString());
        alignments.add(actual);
        var query = new JsonObject(); query.addProperty("path", file.toString());
        var status = capture.http.call("sync_status", query);
        visible &= status.has("revlogs") && status.getAsJsonArray("revlogs").asList().stream()
            .map(v -> v.getAsJsonObject()).anyMatch(v -> v.get("path").getAsString().equals(copy.toString())
                && v.getAsJsonObject("sync").get("offset_microseconds").getAsLong() == actual.offsetMicros());
        parts.add(Map.of("method", actual.method(), "offset_us", actual.offsetMicros()));
      }
      // Rollover changes a file's input window. Compare the whole captured record set with
      // the whole source for the offset invariant; HTTP above separately checks each file.
      try (var joined = new ReplayJoinedLog(capture.manager, files)) {
        var actual = alignments.get(0);
        if (files.size() > 1) {
          var rev = new org.triplehelix.wpilogmcp.revlog.RevLogParser(new org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader().load(null))
              .parse(copy);
          actual = new org.triplehelix.wpilogmcp.sync.LogSynchronizer().synchronize(joined, rev);
        }
        var baseline = bus.syncResult();
        var row = new LinkedHashMap<String, Object>(); row.put("path", original.toString());
        row.put("numeric_records_compared", joined.entries().values().stream()
            .filter(e -> List.of("double", "float", "int64").contains(e.type())).mapToLong(e -> joined.sampleCount(e.name())).sum());
        row.put("source_method", baseline.method()); row.put("capture_method", actual.method());
        row.put("source_offset_us", baseline.offsetMicros()); row.put("capture_offset_us", actual.offsetMicros());
        row.put("expected_offset_us", baseline.offsetMicros() + shiftUs);
        row.put("copy_equal", Files.mismatch(original, copy) == -1);
        row.put("verified", held.verified()); row.put("rejected_incomplete_source", refused);
        row.put("verification_matches", held.verified() || refused); row.put("retries", held.retries());
        row.put("matches", baseline.isSuccessful() ? actual.isSuccessful()
            && Math.abs(actual.offsetMicros() - baseline.offsetMicros() - shiftUs) <= 20_000 : !actual.isSuccessful());
        row.put("http_visible", visible); row.put("file_results", parts);
        revResults.add(row);
      }
    }

    return revResults;
  }

  static boolean hasRevSiblings(ReplaySource source) throws java.io.IOException {
    try (var paths = Files.list(source.path.getParent())) {
      return paths.anyMatch(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".revlog"));
    }
  }

  private static List<org.triplehelix.wpilogmcp.sync.SynchronizedLogs.SyncedRevLog> companions(
      ReplaySource source, ReplayCapture capture) throws Exception {
    if (!hasRevSiblings(source)) return List.of();
    waitForSync(capture, source.path);
    var result = new ArrayList<org.triplehelix.wpilogmcp.sync.SynchronizedLogs.SyncedRevLog>();
    try (var paths = Files.list(source.path.getParent())) {
      for (var path : paths.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".revlog")).sorted().toList()) {
        // Include the negative cases normal filename nomination leaves out. A source that
        // cannot synchronize this bus must not gain evidence merely by passing through NT4.
        capture.manager.syncRevLog(source.path.toString(), path.toString());
        var logs = capture.manager.getSynchronizedLogs(source.path.toString());
        result.add(logs.revlogs().stream().filter(b -> Path.of(b.revlog().path()).equals(path.toAbsolutePath()))
            .findFirst().orElseThrow(() -> new AssertionError(source.path.toString())));
      }
    }
    return result;
  }
  private static void waitForSync(ReplayCapture capture, Path path) throws Exception {
    var args = new JsonObject(); args.addProperty("path", path.toString()); args.addProperty("timeout_ms", 5_000);
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
    while (true) {
      var wait = capture.http.call("wait_for_sync", args);
      if (wait.has("status") && wait.get("status").getAsString().equals("not_applicable")) return;
      if (!wait.has("completed")) throw new AssertionError(path.toString());
      if (wait.has("completed") && wait.get("completed").getAsBoolean()) break;
      if (System.nanoTime() >= deadline) throw new AssertionError(path.toString());
    }
  }
  @Override public void close() throws Exception { try { remote.close(); } finally { rio.close(); } }
}
