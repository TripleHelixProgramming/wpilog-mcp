/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;
import org.triplehelix.wpilogmcp.store.StoreManifest.LogFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.Move;
import org.triplehelix.wpilogmcp.store.StoreManifest.Robot;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

/** Read-only catalog. A directory name cannot adopt a stray into a robot's history. */
public final class StoreCatalog {
  private StoreCatalog() {
  }

  public static final Duration MOVE_NOTICE_LIFETIME = Duration.ofDays(7);

  public record StoredFile(Path path, Path manifestPath, Robot robot, Session session, LogFile file) {}
  /** A session-level match to an open, unhashed capture is not a hashed REV-file association. */
  public static boolean isRevCompanion(StoredFile candidate, StoredFile anchor) {
    var match = candidate.file().matching();
    return candidate.file().kind().equals("revlog") && candidate.manifestPath().equals(anchor.manifestPath())
        && match != null && match.wpilogSha256() != null && match.wpilogSha256().equals(anchor.file().sha256());
  }

  /** A damaged association stays visible and failed; it must never authorize a new correlation. */
  public static org.triplehelix.wpilogmcp.sync.SyncResult recordedAlignment(StoredFile companion) {
    var match = companion.file().matching();
    try {
      if (match == null || !Double.isFinite(match.confidence()) || match.confidence() < 0 || match.confidence() > 1
          || !Double.isFinite(match.driftRateNanosPerSec()) || !Double.isFinite(match.referenceTimeSec())) {
        throw new IllegalArgumentException("confidence must be in [0,1] and clock fields must be finite");
      }
      var recorded = match.synchronization();
      if (recorded == null) return new org.triplehelix.wpilogmcp.sync.SyncResult(match.offsetMicros(), match.confidence(),
          org.triplehelix.wpilogmcp.sync.ConfidenceLevel.fromScore(match.confidence()), List.of(),
          org.triplehelix.wpilogmcp.sync.SyncMethod.USER_PROVIDED, "Alignment recorded in the store manifest",
          match.driftRateNanosPerSec(), match.referenceTimeSec());
      if (recorded.method() == null || recorded.confidenceLevel() == null || recorded.signalPairs() == null
          || recorded.explanation() == null || recorded.offsetMicros() != match.offsetMicros()
          || recorded.confidence() != match.confidence() || recorded.driftRateNanosPerSec() != match.driftRateNanosPerSec()
          || recorded.referenceTimeSec() != match.referenceTimeSec()) {
        throw new IllegalArgumentException("synchronization fields are missing or disagree with the matching summary");
      }
      for (var pair : recorded.signalPairs()) {
        if (pair == null || pair.wpilogEntry() == null || pair.revlogSignal() == null || pair.samplesUsed() < 0
            || !Double.isFinite(pair.correlation()) || Math.abs(pair.correlation()) > 1) {
          throw new IllegalArgumentException("invalid signal-pair evidence");
        }
      }
      return recorded;
    } catch (RuntimeException e) {
      return org.triplehelix.wpilogmcp.sync.SyncResult.failed("Invalid recorded alignment in "
          + companion.manifestPath() + " for " + companion.file().path() + ": " + e.getMessage());
    }
  }
  public record RobotDirectory(Path path, Robot robot) {}
  public record SessionDirectory(Path path, Robot robot, Session session) {}
  public record Snapshot(Path root, Header header, List<RobotDirectory> robots,
      List<StoredFile> files, List<Path> unmanaged, List<Move> moved, List<StoredFile> openCaptures,
      List<SessionDirectory> sessions) {
    public Snapshot(Path root, Header header, List<RobotDirectory> robots,
        List<StoredFile> files, List<Path> unmanaged, List<Move> moved, List<StoredFile> openCaptures) {
      this(root, header, robots, files, unmanaged, moved, openCaptures, List.of());
    }
    public Snapshot(Path root, Header header, List<RobotDirectory> robots,
        List<StoredFile> files, List<Path> unmanaged, List<Move> moved) {
      this(root, header, robots, files, unmanaged, moved, List.of());
    }
    public List<StoredFile> allFiles() {
      return java.util.stream.Stream.concat(files.stream(), openCaptures.stream()).toList();
    }
  }

  public static boolean isStore(Path directory) {
    return Files.exists(directory.resolve("store.json"));
  }

  /** Find nested stores too, but never walk a store's own session tree looking for another. */
  public static List<Path> discover(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) return List.of();
    var roots = new ArrayList<Path>();
    Files.walkFileTree(directory.toRealPath(), new java.nio.file.SimpleFileVisitor<>() {
      @Override
      public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
          java.nio.file.attribute.BasicFileAttributes attrs) {
        if (!isStore(dir)) return java.nio.file.FileVisitResult.CONTINUE;
        roots.add(dir);
        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
      }
    });
    return roots.stream().sorted().toList();
  }

  /** Store membership follows the file's directory ancestry, not its extension or filename. */
  public static Optional<Path> containing(Path file) {
    var parent = file.toAbsolutePath().normalize().getParent();
    while (parent != null) {
      if (isStore(parent)) return Optional.of(parent);
      parent = parent.getParent();
    }
    return Optional.empty();
  }

  public static Snapshot read(Path directory, SecurityValidator security) throws IOException {
    return read(directory, security, true);
  }

  /** Network listings need manifest membership, not an inventory of every stray in a season. */
  public static Snapshot readManaged(Path directory, SecurityValidator security) throws IOException {
    return read(directory, security, false);
  }

  private static Snapshot read(Path directory, SecurityValidator security, boolean strays) throws IOException {
    return read(directory, security, strays, null);
  }

  /** A damaged historical session cannot stop a new capture. Only status publication skips
   * it; imports and the HTTP door continue to require a complete, valid catalog. */
  static Snapshot readInventory(Path directory, SecurityValidator security,
      java.util.function.BiConsumer<Path, String> skipped) throws IOException {
    return read(directory, security, false, skipped);
  }

  @FunctionalInterface interface SessionReader { Session read(StoreFiles io, Path path) throws IOException; }
  static Snapshot readInventory(Path directory, SecurityValidator security,
      java.util.function.BiConsumer<Path, String> skipped, SessionReader reader) throws IOException {
    return read(directory, security, false, skipped, reader);
  }
  private static Snapshot read(Path directory, SecurityValidator security, boolean strays,
      java.util.function.BiConsumer<Path, String> skipped) throws IOException {
    return read(directory, security, strays, skipped, (io, path) -> io.read(path, Session.class));
  }
  private static Snapshot read(Path directory, SecurityValidator security, boolean strays,
      java.util.function.BiConsumer<Path, String> skipped, SessionReader reader) throws IOException {
    try {
      return readValidated(directory, security, strays, skipped, reader);
    } catch (RuntimeException e) {
      throw new IOException("Invalid store manifest at " + directory + ": " + e.getMessage(), e);
    }
  }

  private static Snapshot readValidated(Path directory, SecurityValidator security, boolean strays,
      java.util.function.BiConsumer<Path, String> skipped, SessionReader reader) throws IOException {
    var root = directory.toRealPath();
    var io = new StoreFiles(root, security);
    var headerPath = root.resolve("store.json");
    var header = io.read(headerPath, Header.class);
    if (header.formatVersion() != StoreManifest.FORMAT_VERSION) {
      throw new IOException("Store format version " + header.formatVersion() + " at " + root
          + (header.formatVersion() > StoreManifest.FORMAT_VERSION
              ? " is newer than this server supports; upgrade the server."
              : " requires an explicit migration; it will not be changed automatically."));
    }
    if (header.id() == null || header.createdAt() == null || header.moves() == null) {
      throw new IOException("Incomplete store manifest: " + headerPath);
    }
    var managed = new HashSet<Path>();
    managed.add(headerPath);
    var robots = new ArrayList<RobotDirectory>();
    var files = new ArrayList<StoredFile>();
    var openCaptures = new ArrayList<StoredFile>();
    var sessionDirectories = new ArrayList<SessionDirectory>();
    var robotsRoot = io.check(root.resolve("robots"));
    if (Files.isDirectory(robotsRoot)) {
      for (var robotDir : children(robotsRoot)) {
        io.check(robotDir);
        var robotPath = robotDir.resolve("robot.json");
        if (!Files.isDirectory(robotDir) || !Files.isRegularFile(robotPath)) continue;
        var robot = io.read(robotPath, Robot.class);
        if (robot.id() == null || !robot.id().equals(robotDir.getFileName().toString())
            || !List.of("logged", "device", "stated", "address").contains(robot.basis())) {
          throw new IOException("Invalid robot manifest: " + robotPath);
        }
        managed.add(robotPath);
        robots.add(new RobotDirectory(robotDir, robot));
        var systemIndex = io.check(SystemLogIndex.path(robotDir));
        if (Files.exists(systemIndex)) {
          managed.add(systemIndex);
          for (var entry : SystemLogIndex.read(io, robotDir).files()) managed.add(SystemLogFiles.shared(io, root, robotDir, entry.file()));
        }
        var pullPath = io.check(robotDir.resolve("pull.json"));
        if (Files.exists(pullPath)) {
          var pull = io.read(pullPath, org.triplehelix.wpilogmcp.sync.PullManifest.class);
          if (!java.util.Objects.equals(robot.serialNumber(), pull.serialNumber())) throw new IOException("Pull manifest serial differs from robot: " + pullPath);
          managed.add(pullPath);
          for (var entry : java.util.stream.Stream.concat(pull.files().stream(), pull.history().stream()).toList()) {
            var held = io.resolve(root, entry.localName());
            // A verified file is also in session.json; only staging needs this membership.
            if (held.startsWith(robotDir.resolve("pulled"))) managed.add(held);
          }
        }
        var sessions = io.check(robotDir.resolve("sessions"));
        if (!Files.isDirectory(sessions)) continue;
        for (var day : children(sessions)) {
          io.check(day);
          if (!Files.isDirectory(day)) continue;
          for (var sessionDir : children(day)) {
            io.check(sessionDir);
            var manifest = sessionDir.resolve("session.json");
            if (!Files.isDirectory(sessionDir) || !Files.isRegularFile(manifest)) continue;
            try {
              var inventory = sessionInventory(io, root, sessionDir, robot, reader);
              for (var path : inventory.managed()) {
                if (managed.contains(path)) throw new IOException("File listed twice: " + path);
              }
              managed.addAll(inventory.managed()); managed.addAll(inventory.shared()); files.addAll(inventory.files());
              openCaptures.addAll(inventory.openCaptures()); sessionDirectories.add(inventory.session());
            } catch (IOException | RuntimeException invalid) {
              if (skipped == null) throw invalid;
              skipped.accept(manifest, invalid.getMessage());
            }
          }
        }
      }
    }
    var unassigned = io.check(root.resolve("unassigned"));
    if (Files.isDirectory(unassigned)) {
      for (var bucket : children(unassigned)) {
        io.check(bucket);
        var manifest = bucket.resolve("import.json");
        if (!Files.isDirectory(bucket) || !Files.isRegularFile(manifest)) continue;
        var file = io.read(manifest, LogFile.class);
        var path = io.resolve(bucket, file.path());
        validate(file, path);
        managed.add(manifest);
        if (!managed.add(path)) throw new IOException("File listed twice: " + path);
        files.add(new StoredFile(path, manifest, null, null, file));
      }
    }
    var unmanaged = new ArrayList<Path>();
    if (strays) managed.addAll(StoreSync.managed(root, io));
    if (strays && header.mirror()) managed.addAll(MirrorSync.managed(root, io));
    if (strays) try (var walk = Files.walk(root)) {
      for (var path : walk.filter(Files::isRegularFile).sorted().toList()) {
        if (!header.mirror() && path.startsWith(root.resolve("inbox")) || path.equals(root.resolve("store.lock"))) continue;
        io.check(path);
        if (!managed.contains(path)) unmanaged.add(path);
      }
    }
    var moved = new ArrayList<Move>();
    for (var move : header.moves()) {
      var target = io.resolve(root, move.movedTo());
      if (Instant.parse(move.movedAt()).plus(MOVE_NOTICE_LIFETIME).isAfter(Instant.now())) {
        moved.add(new Move(move.originalPath(), target.toString(), move.movedAt()));
      }
    }
    return new Snapshot(root, header, List.copyOf(robots), List.copyOf(files),
        List.copyOf(unmanaged), List.copyOf(moved), List.copyOf(openCaptures), List.copyOf(sessionDirectories));
  }

  /** Resolve one catalog member without walking the catalog for every transfer block. */
  public record Payload(Path path, long size) {}
  public static Payload payload(Path directory, String relative, SecurityValidator security) throws IOException {
    var root = directory.toRealPath(); var io = new StoreFiles(root, security);
    var path = io.resolve(root, relative);
    var parts = root.relativize(path);
    Path parent; Session session = null; LogFile file = null;
    if (parts.getNameCount() >= 4 && parts.getName(0).toString().equals("robots") && parts.getName(2).toString().equals("system")) {
      var robot = root.resolve(parts.subpath(0, 2));
      var identity = io.read(robot.resolve("robot.json"), Robot.class);
      if (!parts.getName(1).toString().equals(identity.id())) throw new IOException("Invalid robot manifest");
      for (var entry : SystemLogIndex.read(io, robot).files()) if (SystemLogFiles.shared(io, root, robot, entry.file()).equals(path)) return textPayload(path, entry.file());
      // Compatibility for stores written before the robot-wide index existed.
      for (var owner : readManaged(root, security).sessions()) if (owner.robot().id().equals(identity.id())) {
        for (var text : owner.session().systemLogs().files()) if (SystemLogFiles.resolve(io, root, owner.path(), owner.session(), text).equals(path)) return textPayload(path, text);
      }
      throw new IOException("File is not in the store catalog");
    }
    if (parts.getNameCount() >= 6 && parts.getName(0).toString().equals("robots")
        && parts.getName(2).toString().equals("sessions")) {
      var robotDir = root.resolve(parts.subpath(0, 2));
      var robot = io.read(robotDir.resolve("robot.json"), Robot.class);
      if (!parts.getName(1).toString().equals(robot.id())) throw new IOException("Invalid robot manifest");
      parent = root.resolve(parts.subpath(0, 5));
      session = io.read(parent.resolve("session.json"), Session.class);
      validate(session, parent);
      for (var candidate : session.files()) if (io.resolve(parent, candidate.path()).equals(path)) file = candidate;
      for (var text : session.systemLogs().files()) if (SystemLogFiles.resolve(io, root, parent, session, text).equals(path)) return textPayload(path, text);
    } else if (parts.getNameCount() >= 3 && parts.getName(0).toString().equals("unassigned")) {
      parent = root.resolve(parts.subpath(0, 2));
      var candidate = io.read(parent.resolve("import.json"), LogFile.class);
      if (io.resolve(parent, candidate.path()).equals(path)) file = candidate;
    } else throw new IOException("Not a store log path");
    // Imported logs may themselves be named robot.json, inside their payload subdirectory.
    // The owning control manifest is never a payload, even under a bad manifest.
    if (path.equals(parent.resolve("session.json")) || path.equals(parent.resolve("import.json"))) {
      throw new IOException("Not a store log path");
    }
    if (file != null) { validate(file, path); return new Payload(path, file.sizeBytes()); }
    if (session != null && session.openCapture() != null
        && io.resolve(parent, session.openCapture().path()).equals(path)
        && "captured".equals(session.openCapture().provenance().kind()) && Files.isRegularFile(path)) return new Payload(path, Files.size(path));
    throw new IOException("File is not in the store catalog");
  }

  private static Payload textPayload(Path path, SystemLogState.File file) throws IOException {
    if (!Files.isRegularFile(path) || Files.size(path) < file.sizeBytes()) throw new IOException("System file has not been copied locally: " + path);
    return new Payload(path, file.sizeBytes());
  }

  private record SessionInventory(SessionDirectory session, List<StoredFile> files,
      List<StoredFile> openCaptures, java.util.Set<Path> managed, java.util.Set<Path> shared) {}
  private static SessionInventory sessionInventory(StoreFiles io, Path root, Path sessionDir, Robot robot, SessionReader reader) throws IOException {
    var manifest = sessionDir.resolve("session.json");
    var managed = new HashSet<Path>();
    var shared = new HashSet<Path>();
    var files = new ArrayList<StoredFile>();
    var openCaptures = new ArrayList<StoredFile>();
    var session = reader.read(io, manifest);
    validate(session, manifest);
    managed.add(manifest);
    mergedMetadata(io, sessionDir, managed);
    if (session.openCapture() != null) {
      var capture = session.openCapture();
      var path = io.resolve(sessionDir, capture.path());
      managed.add(io.check(org.triplehelix.wpilogmcp.capture.CaptureLease.lockPath(path)));
      if (!Files.isRegularFile(path) || capture.provenance() == null
          || !"captured".equals(capture.provenance().kind()) || capture.sizeBytes() < 0
          || !Double.isFinite(capture.minTimestampSec()) || !Double.isFinite(capture.maxTimestampSec())
          || capture.minTimestampSec() > capture.maxTimestampSec() || !managed.add(path)) {
        throw new IOException("Invalid open capture: " + path);
      }
      var file = new LogFile(capture.path(), null, Files.size(path), "wpilog", capture.provenance(),
          false, capture.minTimestampSec(), capture.maxTimestampSec(), session.startedAt(), null,
          session.startBasis(), false, null);
      openCaptures.add(new StoredFile(path, manifest, robot, session, file));
    }
    for (var file : session.files()) {
      var path = io.resolve(sessionDir, file.path());
      if (file.provenance() != null && "captured".equals(file.provenance().kind())) {
        managed.add(io.check(org.triplehelix.wpilogmcp.capture.CaptureLease.lockPath(path)));
      }
      validate(file, path);
      if (!managed.add(path)) throw new IOException("File listed twice: " + path);
      files.add(new StoredFile(path, manifest, robot, session, file));
    }
    for (var text : session.systemLogs().files()) {
      var path = SystemLogFiles.resolve(io, root, sessionDir, session, text);
      // Shared receipts written before the robot index remain valid in more than one session.
      if (text.location() == SystemLogState.Location.SESSION) managed.add(path);
      else shared.add(path);
    }
    return new SessionInventory(new SessionDirectory(sessionDir, robot, session), files, openCaptures, managed, shared);
  }

  private static List<Path> children(Path path) throws IOException {
    try (var entries = Files.list(path)) {
      return entries.sorted().toList();
    }
  }

  /** Consolidation retains old manifests beside moved payloads; they are history, not sessions. */
  private static void mergedMetadata(StoreFiles io, Path session, java.util.Set<Path> managed) throws IOException {
    var merged = io.check(session.resolve("merged"));
    if (!Files.isDirectory(merged)) return;
    for (var directory : children(merged)) {
      var manifest = io.check(directory.resolve("session.json"));
      if (!Files.isRegularFile(manifest)) continue;
      validate(io.read(manifest, Session.class), manifest);
      managed.add(manifest); mergedMetadata(io, directory, managed);
    }
  }

  private static void validate(Session session, Path path) throws IOException {
    try {
      if (session.id() == null || session.files() == null || session.startBasis() == null
          || Instant.parse(session.startedAt()).isAfter(Instant.parse(session.endedAt()))) {
        throw new IllegalArgumentException("Missing or inverted session facts");
      }
    } catch (RuntimeException e) {
      throw new IOException("Invalid session manifest: " + path, e);
    }
  }

  private static void validate(LogFile file, Path path) throws IOException {
    if (file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}") || file.sizeBytes() < 0
        || !List.of("wpilog", "revlog").contains(file.kind()) || file.provenance() == null
        || !file.verified() || !Files.isRegularFile(path) || Files.size(path) != file.sizeBytes()
        || !Double.isFinite(file.minTimestampSec()) || !Double.isFinite(file.maxTimestampSec())
        || file.minTimestampSec() > file.maxTimestampSec()) {
      throw new IOException("Invalid or missing manifested log: " + path);
    }
  }
}
