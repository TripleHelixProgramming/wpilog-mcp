/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.store.StoreCatalog;

/** Exact fingerprints nominate one robot at most. They never supply a serial for placement. */
public final class RobotCandidates {
  public record Fingerprint(Integer loggedTeamNumber, String entrySet, String canInventory) {}
  public record Known(String serialNumber, Fingerprint fingerprint) {}
  public record Evidence(String kind, String value) {}
  public record Candidate(String serialNumber, List<Evidence> evidence) {}
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

  /** Only persisted import facts participate; listing never opens a file for a fingerprint. */
  public static List<Known> known(LogDirectory.DirectoryScan scan) {
    var result = new ArrayList<Known>();
    for (var store : scan.stores()) for (var file : store.files()) {
      if (file.robot() == null || file.robot().serialNumber() == null || file.file().robotFingerprint() == null) continue;
      // A prefix's logged value still takes precedence over the connection's identity.
      String serial = scan.logs().stream().filter(l -> Path.of(l.path()).equals(file.path()) && l.robot() != null)
          .map(l -> l.robot().serialNumber()).findFirst().orElse(file.robot().serialNumber());
      result.add(new Known(serial, file.file().robotFingerprint()));
    }
    return List.copyOf(result);
  }
  public static List<Candidate> forFile(StoreCatalog.StoredFile file, List<Known> known) {
    return file == null || file.file().robotFingerprint() == null ? List.of() : match(file.file().robotFingerprint(), known);
  }
  /** Called during import's existing inspection, never by directory discovery. */
  public static Fingerprint inspect(LogData log, LogMetadata metadata) {
    var entries = new TreeSet<String>();
    log.entries().values().forEach(e -> entries.add(e.name().length() + ":" + e.name() + ":" + e.type()));
    return new Fingerprint(metadata.teamNumber(), entries.isEmpty() ? null : digest(entries), null);
  }
  public static Fingerprint inspect(ParsedRevLog rev) {
    var inventory = new TreeSet<String>();
    rev.devices().values().forEach(d -> inventory.add(d.canId() + ":" + d.deviceType()));
    return new Fingerprint(null, null, inventory.isEmpty() ? null : digest(inventory));
  }
  private static String digest(java.util.SortedSet<String> values) {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      for (String value : values) hash.update((value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash.digest());
    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }
}
