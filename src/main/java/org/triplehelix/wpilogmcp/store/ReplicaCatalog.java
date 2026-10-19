/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Format-2 identities and evidence are separate from the old transfer-verified bit. In particular,
 * migration cannot invent an authoritative file identity for an old mirror from its path or hash.
 * The enclosing header is replaced atomically, so its epoch, revision and records agree.
 */
public record ReplicaCatalog(int schemaVersion, String epoch, long revision, List<Entry> files,
    List<String> includedSessions, List<Exclusion> exclusions) {
  public static final int MAX_FILES = 4096;
  public static final int MAX_EXCLUSIONS = 4096;
  public static final int MAX_CLEANUP = 32;
  public ReplicaCatalog {
    if (schemaVersion != 1 || revision < 1) throw new IllegalArgumentException("Invalid replica catalog version");
    uuid(epoch); files = List.copyOf(files); includedSessions = List.copyOf(includedSessions);
    exclusions = List.copyOf(exclusions);
    if (files.size() > MAX_FILES || includedSessions.size() > MAX_FILES || exclusions.size() > MAX_EXCLUSIONS)
      throw new IllegalArgumentException("Replica metadata limit reached; existing records are preserved");
    if (files.stream().map(Entry::key).distinct().count() != files.size()
        || files.stream().map(Entry::path).distinct().count() != files.size())
      throw new IllegalArgumentException("Duplicate replica key or path");
    var identities = files.stream().map(Entry::identity).filter(Objects::nonNull).toList();
    if (identities.stream().distinct().count() != identities.size()) throw new IllegalArgumentException("Duplicate replica identity/version");
  }

  public record Identity(String masterStoreId, String fileId, String fileVersion) {
    public Identity { uuid(masterStoreId); uuid(fileId); uuid(fileVersion); }
  }

  /** A master statement, not proof that the recipient obtained it from the master. */
  public record MasterRecord(Identity identity, String catalogEpoch, long catalogRevision,
      String sha256, long sizeBytes, String finalizationBasis) {
    public MasterRecord {
      Objects.requireNonNull(identity); uuid(catalogEpoch); hash(sha256);
      if (catalogRevision < 1 || sizeBytes < 0 || !Set.of("closed_capture", "completed_import").contains(finalizationBasis))
        throw new IllegalArgumentException("Invalid master finalization record");
    }
  }

  /** The receipt retains the direct acquisition channel separately from a peer's claim. */
  public record Confirmation(MasterRecord record, String obtainedFrom, String obtainedAt) {
    public Confirmation {
      Objects.requireNonNull(record);
      if (obtainedFrom == null || obtainedFrom.isBlank()) throw new IllegalArgumentException("Missing confirmation channel");
      java.time.Instant.parse(obtainedAt);
    }
  }

  public record Entry(String key, Identity identity, String path, String sha256, long sizeBytes,
      String kind, String sessionId, String completionBasis, String verificationBasis,
      boolean transferVerified, Confirmation confirmation) {
    public Entry {
      uuid(key); hash(sha256);
      if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":"))
        throw new IllegalArgumentException("Invalid replica path");
      for (var part : path.split("/", -1)) StoreFiles.component(part);
      if (sizeBytes < 0 || !Set.of("wpilog", "revlog").contains(kind)
          || !Set.of("closed_capture", "completed_import").contains(completionBasis)
          || !Set.of("master_local", "legacy_unconfirmed", "provisional", "verified").contains(verificationBasis))
        throw new IllegalArgumentException("Invalid replica facts");
      boolean confirmed = verificationBasis.equals("verified") || verificationBasis.equals("master_local");
      if (confirmed && (!transferVerified || identity == null || confirmation == null
          || !identity.equals(confirmation.record().identity()) || !sha256.equals(confirmation.record().sha256())
          || sizeBytes != confirmation.record().sizeBytes() || !completionBasis.equals(confirmation.record().finalizationBasis())))
        throw new IllegalArgumentException("Verification needs a matching master record and checked bytes");
      if (verificationBasis.equals("legacy_unconfirmed") && (identity != null || confirmation != null))
        throw new IllegalArgumentException("Legacy transfer facts do not establish master identity");
    }
  }

  public record Exclusion(String key, Identity identity, String sha256) {
    public Exclusion { uuid(key); hash(sha256); }
  }
  public record CleanupPreview(String storeId, String catalogEpoch, long catalogRevision,
      List<Entry> files, long payloadBytes, List<Exclusion> acquisitionExclusions,
      String writerState) {}

  /** A preview is exact and revision-bound; manual/unmanaged paths cannot enter this selection. */
  public CleanupPreview preview(String storeId, List<String> keys) {
    if (keys.isEmpty() || keys.size() > MAX_CLEANUP || keys.stream().distinct().count() != keys.size())
      throw new IllegalArgumentException("Select 1 to 32 distinct replica keys");
    var chosen = keys.stream().map(key -> files.stream().filter(f -> f.key().equals(key)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown replica key"))).toList();
    long bytes = 0; for (var file : chosen) bytes = Math.addExact(bytes, file.sizeBytes());
    return new CleanupPreview(storeId, epoch, revision, chosen, bytes, chosen.stream()
        .map(f -> new Exclusion(f.key(), f.identity(), f.sha256())).toList(), "writer_not_ready");
  }

  /** Persistent exclusion admission fails before a future payload delete could lose its guard. */
  public ReplicaCatalog excluding(CleanupPreview preview) {
    if (!epoch.equals(preview.catalogEpoch()) || revision != preview.catalogRevision()
        || !preview.files().stream().allMatch(files::contains)) throw new IllegalArgumentException("STALE_PREVIEW: catalog changed");
    var next = new java.util.ArrayList<>(exclusions);
    for (var file : preview.files()) {
      var exclusion = new Exclusion(file.key(), file.identity(), file.sha256());
      if (!next.contains(exclusion)) next.add(exclusion);
    }
    return new ReplicaCatalog(schemaVersion, epoch, Math.addExact(revision, 1), files, includedSessions, next);
  }

  private static void uuid(String value) {
    if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid replica UUID");
  }
  private static void hash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid replica SHA-256");
  }
}
