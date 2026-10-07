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
  public record RobotDirectory(Path path, Robot robot) {}
  public record Snapshot(Path root, Header header, List<RobotDirectory> robots,
      List<StoredFile> files, List<Path> unmanaged, List<Move> moved, List<StoredFile> openCaptures) {
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
    try {
      return readValidated(directory, security);
    } catch (RuntimeException e) {
      throw new IOException("Invalid store manifest at " + directory + ": " + e.getMessage(), e);
    }
  }

  private static Snapshot readValidated(Path directory, SecurityValidator security) throws IOException {
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
            var session = io.read(manifest, Session.class);
            validate(session, manifest);
            managed.add(manifest);
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
    try (var walk = Files.walk(root)) {
      for (var path : walk.filter(Files::isRegularFile).sorted().toList()) {
        if (path.startsWith(root.resolve("inbox")) || path.equals(root.resolve("store.lock"))) continue;
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
        List.copyOf(unmanaged), List.copyOf(moved), List.copyOf(openCaptures));
  }

  private static List<Path> children(Path path) throws IOException {
    try (var entries = Files.list(path)) {
      return entries.sorted().toList();
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
