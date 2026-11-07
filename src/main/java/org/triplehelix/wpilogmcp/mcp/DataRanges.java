/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonArray;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog;
import org.triplehelix.wpilogmcp.store.StoreCatalog;

/**
 * The files a robot range reads, chosen from a store's catalog by the robot's identity, the
 * source asked for and the catalog's calendar times. Those times only nominate a file, since they
 * come from the clock each record names, such as the pit's for a capture: the data endpoint then
 * places every file by its own measured clock. A roboRIO log is any WPILOG the store pulled,
 * imported or was given; a recording is one the pit captured. Nothing here reads a value.
 */
final class DataRanges {
  private DataRanges() {}

  /** The window the store already uses to pair a pulled log with a session, on either side. */
  static final long NOMINATION_MARGIN_US = TimeUnit.HOURS.toMicros(2);
  /** The most files one request reads, the session read's limit. */
  static final int MAX_FILES = 64;

  enum Role {
    ROBOT_LOG("robot_log"), CAPTURE("capture"), REV("rev");
    final String wire;
    Role(String wire) { this.wire = wire; }
  }

  /**
   * A nominated file: its role, its session, whether it is the capture still recording, its catalog
   * record, and for a capture its recording, which the files of one recording share; a session id
   * cannot prove a shared robot clock after consolidation.
   */
  record Candidate(Path path, Role role, String storeId, String robotId, String sessionId, boolean open,
      Long startedUs, StoreCatalog.StoredFile stored, org.triplehelix.wpilogmcp.store.CaptureRecording recording) {}

  /**
   * The stores a request reads: the one it names, or the only one. Several stores and no choice
   * is refused, since there is no default store; the refusal lists them.
   */
  static List<StoreCatalog.Snapshot> stores(StoreReadCatalog catalog, String store)
      throws DataEndpoint.Refusal, java.io.IOException {
    if (catalog == null) {
      throw new DataEndpoint.Refusal(404, "No store is configured on this server",
          "A robot range reads a store's catalog; configure a store in servers.yaml");
    }
    var all = catalog.snapshot().stores();
    if (store != null) {
      var chosen = all.stream().filter(s -> s.header() != null && store.equals(s.header().id())).toList();
      if (chosen.isEmpty()) throw refusal(404, "No configured store has the id " + store, all);
      return chosen;
    }
    if (all.size() > 1) throw refusal(400, "Several stores are configured; choose one with store=<id>", all);
    return all;
  }

  /**
   * The manifests the chosen stores' catalog could not read, with why: their files are in no
   * answer, and an answer that left them out silently would read as if they did not exist.
   */
  static JsonArray problems(StoreReadCatalog catalog, List<StoreCatalog.Snapshot> stores) throws java.io.IOException {
    var out = new JsonArray();
    if (catalog == null) return out;
    for (var problem : catalog.snapshot().problems()) {
      if (stores.stream().noneMatch(s -> problem.manifest().startsWith(s.root()))) continue;
      var json = new com.google.gson.JsonObject();
      json.addProperty("manifest", problem.manifest().toString());
      json.addProperty("reason", problem.reason());
      out.add(json);
    }
    return out;
  }

  private static DataEndpoint.Refusal refusal(int status, String error, List<StoreCatalog.Snapshot> all) {
    var refusal = new DataEndpoint.Refusal(status, error, "GET /data/robots?store=<id> lists a store's robots");
    var ids = new JsonArray();
    all.forEach(s -> { if (s.header() != null) ids.add(s.header().id()); });
    refusal.body.add("stores", ids);
    return refusal;
  }

  /**
   * The robot's WPILOGs whose catalog times overlap the window widened by
   * {@link #NOMINATION_MARGIN_US}, in calendar order, and its REV logs when a REV signal is asked
   * for. A file that states no time cannot be left out on that evidence, so it is nominated too. A
   * robot the store does not know is refused, and so is a range that nominates more than
   * {@link #MAX_FILES} files, with the true count.
   */
  static List<Candidate> candidates(List<StoreCatalog.Snapshot> stores, String robot, boolean robotLogs,
      boolean captures, boolean revLogs, long startUtc, long endUtc) throws DataEndpoint.Refusal {
    var found = new ArrayList<Candidate>();
    boolean known = false;
    for (var store : stores) {
      for (var directory : store.robots()) known |= matches(directory.robot(), robot);
      var open = new HashSet<Path>();
      store.openCaptures().forEach(f -> open.add(f.path()));
      for (var file : store.allFiles()) {
        if (!matches(file.robot(), robot)) continue;
        Role role;
        if ("revlog".equals(file.file().kind())) {
          if (!revLogs) continue;
          role = Role.REV;
        } else if ("wpilog".equals(file.file().kind())) {
          var provenance = file.file().provenance();
          role = provenance != null && provenance.captured() ? Role.CAPTURE : Role.ROBOT_LOG;
          if (role == Role.CAPTURE ? !captures : !robotLogs) continue;
        } else {
          continue;
        }
        boolean recording = open.contains(file.path());
        Long start = epochMicros(file.file().startedAt());
        Long end = recording ? null : epochMicros(file.file().endedAt());
        if (start != null && start > endUtc + NOMINATION_MARGIN_US) continue;
        if (end != null && end < startUtc - NOMINATION_MARGIN_US) continue;
        var captured = role != Role.CAPTURE || store.header() == null ? null : org.triplehelix.wpilogmcp.store.CaptureRecording
            .of(file.file().provenance(), org.triplehelix.wpilogmcp.store.RecordedClocks.origin(store)).orElse(null);
        found.add(new Candidate(file.path(), role, store.header() == null ? null : store.header().id(),
            file.robot().id(), file.session() == null ? null : file.session().id(), recording, start, file, captured));
      }
    }
    if (!known) {
      throw new DataEndpoint.Refusal(404, "The store knows no robot " + robot + ", by id or serial number",
          "GET /data/robots lists the store's robots");
    }
    if (found.size() > MAX_FILES) {
      var refusal = new DataEndpoint.Refusal(413, "The range nominates " + found.size() + " files, over the limit of "
          + MAX_FILES, "Narrow the time range");
      refusal.body.addProperty("files", found.size());
      refusal.body.addProperty("max_files", MAX_FILES);
      throw refusal;
    }
    found.sort(Comparator.comparing((Candidate c) -> c.startedUs() == null ? Long.MAX_VALUE : c.startedUs())
        .thenComparing(c -> c.path().toString()));
    return found;
  }

  /** A REV log's anchor: the WPILOG at {@code file} in the list given, and the alignment onto its clock; or why none places it. */
  record Anchor(int file, org.triplehelix.wpilogmcp.sync.SyncResult alignment, String refusal) {}

  /**
   * The WPILOG that places a REV log: of the files given, the first of its session with a recorded
   * alignment, a roboRIO log before a recording, as the default source prefers the roboRIO's own
   * log. Only recorded alignments count; nothing is correlated during a request.
   */
  static Anchor anchor(Candidate rev, List<Candidate> wpilogs, List<StoreCatalog.Snapshot> stores) {
    var store = stores.stream().filter(s -> rev.stored().path().startsWith(s.root())).findFirst();
    if (store.isEmpty()) return new Anchor(-1, null, "Not in a store the range reads");
    var files = store.get().allFiles();
    String origin = org.triplehelix.wpilogmcp.store.RecordedClocks.origin(store.get());
    String failed = null;
    for (var role : List.of(Role.ROBOT_LOG, Role.CAPTURE)) {
      for (int i = 0; i < wpilogs.size(); i++) {
        var wpilog = wpilogs.get(i);
        if (wpilog.role() != role) continue;
        var alignment = org.triplehelix.wpilogmcp.store.RecordedClocks.revAlignment(rev.stored(), wpilog.stored(), files, origin);
        if (alignment.isEmpty()) continue;
        if (alignment.get().isSuccessful()) return new Anchor(i, alignment.get(), null);
        if (failed == null) failed = alignment.get().explanation();
      }
    }
    return new Anchor(-1, null, failed != null ? "Its recorded alignment cannot place it: " + failed
        : "No WPILOG the range placed has a recorded alignment with it");
  }

  static boolean matches(org.triplehelix.wpilogmcp.store.StoreManifest.Robot record, String robot) {
    return record != null && (robot.equals(record.id()) || robot.equals(record.serialNumber()));
  }

  /** An ISO 8601 instant as epoch microseconds, or null for no time or one that does not parse. */
  static Long epochMicros(String instant) {
    if (instant == null || instant.isBlank()) return null;
    try {
      var parsed = Instant.parse(instant);
      return Math.addExact(Math.multiplyExact(parsed.getEpochSecond(), 1_000_000L), parsed.getNano() / 1_000);
    } catch (DateTimeParseException | ArithmeticException unreadable) {
      return null;
    }
  }
}
