/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** A refresh is explicit retirement of an install, with a recoverable backup, never an update side effect. */
final class InstallRefresh {
  private InstallRefresh() {
  }

  private record ConfigCopy(String name, Path sourceWithinRoot) {
  }

  record Prepared(Path backup, String previousVersion) {
  }

  static Prepared prepare(Path root, Path source, String version, boolean force, boolean windows)
      throws IOException {
    if (Files.isSymbolicLink(root)) {
      throw new IOException("Refresh requires a real install directory: " + root);
    }
    var realRoot = root.toRealPath();
    if (source.toRealPath().startsWith(realRoot)) {
      throw new IOException("Run --refresh from a downloaded or built JAR outside the install directory; "
          + "Windows cannot rename the running JAR's directory");
    }
    try (var files = Files.list(root)) {
      if (files.findAny().isEmpty()) {
        return null;
      }
    }
    var security = new SecurityValidator();
    security.addAllowedDirectory(realRoot);
    var run = root.resolve("run");
    security.validate(run);
    Files.createDirectories(run);
    var installLock = root.resolve("install.lock");
    var startLock = run.resolve(".install-refresh.guard");
    var copies = new ArrayList<ConfigCopy>();
    String before;
    InstallGuard installing = null;
    InstallGuard starting = null;
    try {
      try (var install = InstallGuard.acquire(root, installLock, true);
           var start = InstallGuard.acquire(root, startLock, true)) {
        installing = install;
        starting = start;
        var current = root.resolve("bin").resolve(windows ? "wpilog-mcp.bat" : "wpilog-mcp");
        before = Files.isRegularFile(current) ? InstallCommand.launcherVersion(Files.readString(current)) : null;
        if (!force && before != null && InstallCommand.compareVersions(version, before) < 0) {
          throw new IOException("Refresh would downgrade " + before + " to " + version + "; use --force to choose it explicitly");
        }
        for (String name : List.of("servers.yaml", "servers.json")) {
          var file = root.resolve(name);
          security.validate(file);
          if (Files.isRegularFile(file)) {
            copies.add(new ConfigCopy(name, realRoot.relativize(file.toRealPath())));
          }
        }
        List<Path> records;
        try (var files = Files.list(run)) {
          records = files.filter(path -> path.getFileName().toString().endsWith(".pid")).toList();
        }
        var manager = new DaemonManager(run);
        for (var record : records) {
          security.validate(record);
          var lines = Files.readAllLines(record);
          if (lines.size() > 2) {
            throw new IOException("A daemon is starting or stopping; finish that operation before --refresh: " + record);
          }
          String name = record.getFileName().toString().replaceFirst("\\.pid$", "");
          if (!manager.stopDaemon(name) || Files.exists(record)) {
            throw new IOException("Could not stop daemon before refresh: " + name);
          }
        }
        // Starts cannot claim a PID while this guard is held. Once marked, both kinds of
        // caller refuse even through an old open handle while the directory is being renamed.
        install.markForRefresh();
        start.markForRefresh();
      }
      var stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
      var backup = root.resolveSibling(root.getFileName() + ".backup-" + stamp + "-" + UUID.randomUUID());
      Files.move(root, backup, StandardCopyOption.ATOMIC_MOVE);
      try {
        Files.createDirectory(root);
        try (var guard = InstallGuard.acquire(root, root.resolve("install.lock"))) {
          for (var copy : copies) {
            Files.copy(backup.resolve(copy.sourceWithinRoot()), root.resolve(copy.name()), StandardCopyOption.COPY_ATTRIBUTES);
          }
        }
      } catch (IOException e) {
        throw new IOException("Previous install kept at " + backup + "; restoring settings failed: " + e.getMessage(), e);
      }
      return new Prepared(backup, before);
    } catch (IOException e) {
      // When rename failed the old layout remains usable. On success these paths either no
      // longer exist or belong to the fresh install, whose unmarked locks must stay untouched.
      if (installing != null) {
        installing.clearMarker(installLock);
      }
      if (starting != null) {
        starting.clearMarker(startLock);
      }
      throw e;
    }
  }
}
