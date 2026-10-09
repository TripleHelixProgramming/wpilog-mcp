/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

/** Read receipts directly from the capture's manifest, without walking a store or joining its queue. */
public final class SystemLogFiles {
  private SystemLogFiles() {}
  public record Receipt(Path path, SystemLogState.File file) {}
  public record Snapshot(Path manifest, Session session, List<Receipt> files, List<Path> clocks, String collectingServer) {}

  static Path resolve(StoreFiles io, Path root, Path directory, Session session, SystemLogState.File file) throws IOException {
    validate(file);
    Path path;
    if (file.location() == SystemLogState.Location.SESSION) {
      path = io.resolve(directory, file.path());
      // A consolidated fragment keeps its directory and old manifest under merged/<id>/.
      // Permit only that repeated structure followed by robot/system, never other controls.
      var relative = directory.relativize(path); int first = 0;
      while (relative.getNameCount() > first + 2 && relative.getName(first).toString().equals("merged")) first += 2;
      if (relative.getNameCount() <= first + 2 || !relative.getName(first).toString().equals("robot")
          || !relative.getName(first + 1).toString().equals("system")) throw new IOException("System file is outside session robot/system: " + file.path());
    } else {
      path = io.resolve(root, file.path());
      String serial = session.deviceIdentity() == null ? null : session.deviceIdentity().serialNumber();
      if (serial == null) {
        var robot = robotDirectory(root, directory);
        if (robot != null) serial = io.read(robot.resolve("robot.json"), StoreManifest.Robot.class).serialNumber();
      }
      if (serial == null) throw new IOException("Shared system file has no robot identity");
      path = shared(io, root, root.resolve("robots").resolve(StoreFiles.component(serial)), file);
    }
    return io.check(path);
  }
  static void validate(SystemLogState.File file) throws IOException {
    if (file == null || file.location() == null || file.provenance() == null || file.sizeBytes() < 0
        || file.source() == null || file.format() == null
        || !List.of("kernel", "syslog", "program", "jvm_crash").contains(file.source())
        || !List.of("text", "gzip_text", "dmesg", "journal").contains(file.format())
        || file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}")) throw new IOException("Invalid system file receipt");
  }
  static Path shared(StoreFiles io, Path root, Path robot, SystemLogState.File file) throws IOException {
    validate(file); var path = io.resolve(root, file.path()); var system = robot.resolve("system");
    if (file.location() != SystemLogState.Location.STORE || !path.startsWith(system)
        || path.equals(SystemLogIndex.path(robot)) || path.startsWith(system.resolve(".pull"))) {
      throw new IOException("Shared system file is outside this robot's published system directory: " + file.path());
    }
    return path;
  }
  static String remotePath(String sessionPath, SystemLogState.File file) {
    return file.location() == SystemLogState.Location.STORE ? file.path() : sessionPath + "/" + file.path();
  }

  static Path robotDirectory(Path root, Path directory) {
    var relative = root.relativize(directory);
    return relative.getNameCount() >= 2 && relative.getName(0).toString().equals("robots") ? root.resolve(relative.subpath(0, 2)) : null;
  }

  static Optional<Snapshot> read(Path capture, SecurityValidator security) throws IOException {
    try { return readChecked(capture, security); }
    catch (RuntimeException e) { throw new IOException("Invalid session manifest for " + capture + ": " + e.getMessage(), e); }
  }
  private static Optional<Snapshot> readChecked(Path capture, SecurityValidator security) throws IOException {
    var root = StoreCatalog.containing(capture);
    if (root.isEmpty()) return Optional.empty();
    var io = new StoreFiles(root.get(), security);
    var directory = capture.toAbsolutePath().normalize().getParent();
    while (directory != null && directory.startsWith(root.get()) && !Files.exists(directory.resolve("session.json"))) directory = directory.getParent();
    if (directory == null || !directory.startsWith(root.get())) return Optional.empty();
    var manifest = io.check(directory.resolve("session.json"));
    var session = io.read(manifest, Session.class);
    var clocks = new java.util.ArrayList<Path>();
    for (var file : session.files()) if (file.kind().equals("wpilog")) clocks.add(io.resolve(directory, file.path()));
    if (session.openCapture() != null) clocks.add(io.resolve(directory, session.openCapture().path()));
    if (clocks.stream().noneMatch(capture::equals)) throw new IOException("The requested log is not a member of " + manifest);
    var receipts = new java.util.ArrayList<Receipt>();
    try {
      for (var file : session.systemLogs().files()) receipts.add(new Receipt(resolve(io, root.get(), directory, session, file), file));
    } catch (IOException e) { throw new IOException("Invalid system file receipt in " + manifest + ": " + e.getMessage(), e); }
    var robot = robotDirectory(root.get(), directory);
    if (robot != null) for (var entry : SystemLogIndex.read(io, robot).files()) if (SystemLogIndex.applies(entry, session)) {
      var path = resolve(io, root.get(), directory, session, entry.file());
      receipts.removeIf(r -> r.path().equals(path));
      receipts.add(new Receipt(path, entry.file()));
    }
    var header = io.read(root.get().resolve("store.json"), StoreManifest.Header.class);
    String collector = header.origin() == null ? null : header.origin().url();
    if (collector == null) for (var receipt : receipts) {
      var copies = receipt.file().provenance().copiedFrom();
      if (!copies.isEmpty()) { collector = copies.get(copies.size() - 1).url(); break; }
    }
    if (collector == null) collector = "store " + header.id();
    return Optional.of(new Snapshot(manifest, session, List.copyOf(receipts), List.copyOf(clocks), collector));
  }
}
