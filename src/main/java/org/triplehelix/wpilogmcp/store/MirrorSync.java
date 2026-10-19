/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.triplehelix.wpilogmcp.config.MirrorConfig;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/**
 * Preservation admission precedes the replacement transfer engine. A format-1 mirror must never
 * resume its old eviction policy, and a format-2 policy must never feed that old payload writer.
 * Until generation publication is integrated, both paths report their gate without remote I/O.
 */
public final class MirrorSync {
  public record Progress(String state, int remainingFiles, long remainingBytes, String path) {}
  public record Result(String state, String lastSync, long bytesCopied, List<String> evicted,
      List<String> retained, List<String> refusals, String reason) {}
  private final Path root;
  private final StoreFiles io;
  private final SecurityValidator security;

  MirrorSync(Path root, StoreFiles io, SecurityValidator security, LogManager manager,
      Clock clock, Consumer<Progress> progress) {
    this.root = root; this.io = io; this.security = security;
  }

  static Header initialize(Path root, StoreFiles io, SecurityValidator security, MirrorConfig config) throws IOException {
    var header = io.read(root.resolve("store.json"), Header.class);
    if (header.origin() != null) {
      if (!header.mirror() || !header.origin().url().equals(config.origin()))
        throw new IOException("mirror.origin differs from this folder's origin; choose another folder");
      return header;
    }
    var catalog = StoreCatalog.read(root, security);
    if (!catalog.robots().isEmpty() || !catalog.allFiles().isEmpty() || !catalog.unmanaged().isEmpty())
      throw new IOException("mirror.folder must be empty; an existing store is not converted into a mirror");
    header = header.withOrigin(new MirrorOrigin(null, config.origin(), null, List.of(), Map.of()));
    io.write(root.resolve("store.json"), header); return header;
  }

  Result run(MirrorConfig config, StoreSync.Source source) throws IOException {
    var header = initialize(root, io, security, config);
    boolean legacy = header.formatVersion() == 1;
    return new Result(legacy ? "migration_required" : "writer_not_ready", header.origin().lastSync(),
        0, List.of(), List.of(), List.of(), legacy
            ? "Mirror downloads are paused. Explicitly migrate to format 2; held files are preserved."
            : "Mirror downloads and cleanup remain paused until immutable generation publication is available; held files are preserved.");
  }

  /** Robot selection is AND (event OR date); absent date evidence never matches a date filter. */
  public static boolean acquires(MirrorConfig config, String robot, String event, String endedAt,
      String timeBasis, Instant now, boolean included) {
    if (included || config.scope().equals("all")) return true;
    if (!config.robots().isEmpty() && !config.robots().contains(robot)) return false;
    if (event != null && config.events().contains(event)) return true;
    if (endedAt == null || timeBasis == null || Set.of("unknown", "mtime", "file_mtime").contains(timeBasis)) return false;
    try { return !Instant.parse(endedAt).isBefore(now.minus(Duration.ofDays(config.days()))); }
    catch (java.time.DateTimeException invalid) { return false; }
  }

  /** A finalized rollover member is eligible even while the next member is still growing. */
  public static List<LogFile> finalized(Session session) {
    return session.files().stream().filter(file -> file.verified() && file.sha256() != null
        && file.sha256().matches("[0-9a-f]{64}") && file.sizeBytes() >= 0
        && (session.openCapture() == null || !session.openCapture().path().equals(file.path())))
        .toList();
  }
  static Path journalPath(Path root) { return StoreTransferInventory.mirrorJournal(root); }
  static Set<Path> managed(Path root, StoreFiles io) throws IOException { return StoreTransferInventory.mirrorFiles(root, io); }
}
