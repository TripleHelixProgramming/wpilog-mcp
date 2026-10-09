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
  public record Snapshot(Path manifest, Session session, List<Receipt> files, List<Path> clocks) {}

  static Path resolve(StoreFiles io, Path root, Path directory, Session session, SystemLogState.File file) throws IOException {
    if (file == null || file.location() == null || file.provenance() == null || file.sizeBytes() < 0
        || file.source() == null || file.format() == null
        || !List.of("kernel", "syslog", "program", "jvm_crash").contains(file.source())
        || !List.of("text", "gzip_text", "dmesg", "journal").contains(file.format())
        || file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}")) throw new IOException("Invalid system file receipt in " + directory.resolve("session.json"));
    Path path;
    if (file.location() == SystemLogState.Location.SESSION) {
      path = io.resolve(directory, file.path());
      if (!path.startsWith(directory.resolve("robot/system"))) throw new IOException("System file is outside session robot/system: " + file.path());
    } else {
      path = io.resolve(root, file.path());
      String serial = session.deviceIdentity() == null ? null : session.deviceIdentity().serialNumber();
      if (serial == null) {
        var relative = root.relativize(directory);
        if (relative.getNameCount() >= 2 && relative.getName(0).toString().equals("robots")) {
          serial = io.read(root.resolve(relative.subpath(0, 2)).resolve("robot.json"), StoreManifest.Robot.class).serialNumber();
        }
      }
      if (serial == null || !path.startsWith(root.resolve("robots").resolve(StoreFiles.component(serial)).resolve("system"))) {
        throw new IOException("Shared system file is outside this robot's system directory: " + file.path());
      }
    }
    return io.check(path);
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
    return Optional.of(new Snapshot(manifest, session, List.copyOf(receipts), List.copyOf(clocks)));
  }
}
