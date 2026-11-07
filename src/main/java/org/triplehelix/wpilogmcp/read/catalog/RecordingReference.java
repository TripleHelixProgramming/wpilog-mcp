/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.catalog;

import java.time.Instant;
import java.util.List;

/** Durable capture identity contains no serving URL or path. Known final hashes can recover
 * the exact retained members if an older writer has discarded a session's merge aliases. */
public record RecordingReference(String originStoreId, String sessionId, String originalName,
    String recordingStartedAt, List<String> finalizedHashes) {
  public RecordingReference {
    if (originStoreId == null || originStoreId.isBlank() || sessionId == null || sessionId.isBlank()
        || originalName == null || originalName.isBlank() || originalName.length() > 512
        || originalName.equals(".") || originalName.equals("..") || originalName.contains("/") || originalName.contains("\\"))
      throw new IllegalArgumentException("A recording reference needs store/session ids and one original filename");
    Instant.parse(recordingStartedAt);
    finalizedHashes = finalizedHashes == null ? List.of() : List.copyOf(finalizedHashes);
    if (finalizedHashes.size() > 256 || finalizedHashes.stream().anyMatch(h -> !h.matches("[0-9a-f]{64}")))
      throw new IllegalArgumentException("Finalized members require at most 256 SHA-256 hashes");
  }
}
