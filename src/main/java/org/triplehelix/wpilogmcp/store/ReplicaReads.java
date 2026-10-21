/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** Immutable admission facts are captured once per read. A later master confirmation or
 * replacement must not retroactively relabel answers computed from an earlier generation. */
public final class ReplicaReads {
  private ReplicaReads() {}
  public record Facts(String servingScopeId, String serverEpoch, ReplicaCatalog.Identity identity,
      String contentSha256, long sizeBytes, String generation, String verificationBasis,
      String authorityState, ReplicaCatalog.Confirmation confirmation, ReplicaCatalog.Placement placement) {}
  private record Admission(StoreManifest.Header header, ReplicaCatalog.Entry file) {}

  /** Another process's open capture is still growing even when this manager has no LiveLog.
   * Only the selected path's ancestors are inspected; unrelated sessions cannot delay a read. */
  public static boolean openCapture(Path path, SecurityValidator security) throws IOException {
    var owner = StoreCatalog.containing(path);
    if (owner.isEmpty()) return false;
    var root = owner.get().toRealPath();
    var io = new StoreFiles(root, security);
    var real = path.toRealPath();
    io.check(real);
    for (var directory = real.getParent(); directory != null && directory.startsWith(root);
        directory = directory.getParent()) {
      var manifest = directory.resolve("session.json");
      if (!Files.isRegularFile(manifest)) continue;
      var session = io.read(manifest, StoreManifest.Session.class);
      if (session.openCapture() == null) continue;
      var capture = io.resolve(directory, session.openCapture().path());
      if (Files.exists(capture) && capture.toRealPath().equals(real)) return true;
    }
    return false;
  }

  private static Admission admission(Path path, SecurityValidator security) throws IOException {
    var owner = StoreCatalog.containing(path); if (owner.isEmpty()) return null;
    var root = owner.get(); var io = new StoreFiles(root, security);
    var loaded = ReplicaCatalogFiles.read(io, root.resolve("store.json")); var header = loaded.header();
    if (header.replicas() == null) return null;
    var real = path.toRealPath(); io.check(real);
    var found = loaded.entries().get(StoreFiles.relative(root, real));
    if (found == null && header.mirror()) throw new IOException("SOURCE_UNAVAILABLE: this mirror generation is no longer published");
    return found == null ? null : new Admission(header, found);
  }
  static ReplicaCatalog.Entry publishedMirror(Path path, SecurityValidator security) throws IOException {
    var admitted = admission(path, security);
    return admitted == null || !admitted.header().mirror() ? null : admitted.file();
  }
  public static ReplicaCatalog.Entry entry(Path path, SecurityValidator security) throws IOException {
    var admitted = admission(path, security); return admitted == null ? null : admitted.file();
  }
  public static Facts facts(Path path, SecurityValidator security, String epoch) throws IOException {
    var admitted = admission(path, security); if (admitted == null) return null;
    var file = admitted.file(); var header = admitted.header();
    String state = file.confirmation() != null && (!file.sha256().equals(file.confirmation().record().sha256())
        || file.sizeBytes() != file.confirmation().record().sizeBytes()) ? "authoritative_mismatch" : file.verificationBasis();
    return new Facts(header.id(), epoch, file.identity(), file.sha256(), file.sizeBytes(), file.path(), file.verificationBasis(), state,
        file.confirmation(), file.placement());
  }
  public static Path resolve(Path root, SecurityValidator security, ReplicaCatalog.Identity identity) throws IOException {
    var io = new StoreFiles(root, security); var header = io.read(root.resolve("store.json"), StoreManifest.Header.class);
    if (header.replicas() == null) throw new IOException("SOURCE_UNAVAILABLE: selected store has no versioned catalog");
    var file = header.replicas().files().stream().filter(f -> identity.equals(f.identity())).findFirst()
        .orElseThrow(() -> new IOException("SOURCE_UNAVAILABLE: selected file version is not held in this scope"));
    return io.resolve(root, file.path());
  }
}
