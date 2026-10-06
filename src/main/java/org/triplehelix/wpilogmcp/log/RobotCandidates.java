/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;
import org.triplehelix.wpilogmcp.store.StoreCatalog;

/** Exact fingerprints nominate one robot at most. They never supply a serial for placement. */
public final class RobotCandidates {
  public record Fingerprint(Integer loggedTeamNumber, String entrySet, String canInventory) {}
  public record Known(String serialNumber, Fingerprint fingerprint) {}
  public record Evidence(String kind, String value) {}
  public record Candidate(String serialNumber, List<Evidence> evidence) {}
  private record Cached(FileSnapshot snapshot, Fingerprint fingerprint) {}
  private static final com.github.benmanes.caffeine.cache.Cache<Path, Cached> CACHE =
      Caffeine.newBuilder().maximumSize(4096).build();
  private RobotCandidates() {}

  /** Each evidence kind must itself match exactly one serial; conflicting unique hints disappear. */
  public static List<Candidate> match(Fingerprint target, List<Known> known) {
    var matches = new LinkedHashMap<String, List<Evidence>>();
    for (String kind : List.of("logged_team_number", "entry_set", "rev_can_inventory")) {
      String value = value(target, kind);
      if (value == null) continue;
      var serials = known.stream().filter(k -> value.equals(value(k.fingerprint(), kind)))
          .map(Known::serialNumber).distinct().toList();
      if (serials.size() == 1) matches.computeIfAbsent(serials.get(0), ignored -> new ArrayList<>()).add(new Evidence(kind, value));
    }
    if (matches.size() != 1) return List.of();
    var entry = matches.entrySet().iterator().next();
    return List.of(new Candidate(entry.getKey(), List.copyOf(entry.getValue())));
  }
  private static String value(Fingerprint f, String kind) {
    return switch (kind) {
      case "logged_team_number" -> f.loggedTeamNumber() == null ? null : f.loggedTeamNumber().toString();
      case "entry_set" -> f.entrySet(); default -> f.canInventory();
    };
  }

  public static List<Known> known(LogDirectory.DirectoryScan scan) {
    var paths = new LinkedHashMap<Path, Known>();
    for (var store : scan.stores()) for (var file : store.files()) {
      if (file.robot() == null || file.robot().serialNumber() == null) continue;
      // A file's own logged value has precedence over its connection's serial.
      String serial = scan.logs().stream().filter(l -> Path.of(l.path()).equals(file.path()) && l.robot() != null)
          .map(l -> l.robot().serialNumber()).findFirst().orElse(file.robot().serialNumber());
      add(paths, file.path(), serial, file.file().kind());
    }
    for (var log : scan.logs()) if (log.robot() != null && log.robot().serialNumber() != null) {
      var path = Path.of(log.path());
      if (!paths.containsKey(path) && (log.stored() == null || log.stored().session().openCapture() == null)) {
        add(paths, path, log.robot().serialNumber(), "wpilog");
      }
    }
    return List.copyOf(paths.values());
  }
  private static void add(Map<Path, Known> result, Path path, String serial, String kind) {
    try { result.put(path, new Known(serial, read(path, kind))); }
    catch (IOException | RuntimeException ignored) { /* Unreadable evidence cannot nominate a robot. */ }
  }
  public static List<Candidate> forFile(Path path, String kind, List<Known> known) {
    if (known.isEmpty()) return List.of();
    try { return match(read(path, kind), known); }
    catch (IOException | RuntimeException ignored) { return List.of(); }
  }
  static Fingerprint read(Path path, String kind) throws IOException {
    var before = FileSnapshot.of(path); if (before == null) throw new IOException("Missing fingerprint source");
    var cached = CACHE.getIfPresent(path);
    if (cached != null && before.sameAs(cached.snapshot())) return cached.fingerprint();
    Fingerprint result;
    if (kind.equals("revlog")) {
      var rev = new RevLogParser(new org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader().load(null)).parse(path);
      var inventory = new TreeSet<String>();
      rev.devices().values().forEach(d -> inventory.add(d.canId() + ":" + d.deviceType()));
      result = new Fingerprint(null, null, inventory.isEmpty() ? null : digest(inventory));
    } else {
      try (var reader = new ScopedLogReader(path); var log = new LazyParsedLog(path.toString(), reader.reader(), 4L << 20)) {
        var entries = new TreeSet<String>();
        log.entries().values().forEach(e -> entries.add(e.name().length() + ":" + e.name() + ":" + e.type()));
        result = new Fingerprint(LogMetadata.read(log).teamNumber(), entries.isEmpty() ? null : digest(entries), null);
      }
    }
    if (!before.sameAs(FileSnapshot.of(path))) throw new IOException("Fingerprint source changed");
    CACHE.put(path, new Cached(before, result)); return result;
  }
  private static String digest(java.util.SortedSet<String> values) {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      for (String value : values) hash.update((value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash.digest());
    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
}
