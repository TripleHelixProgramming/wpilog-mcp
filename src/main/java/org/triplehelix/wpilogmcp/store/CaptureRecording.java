/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.util.Optional;
import org.triplehelix.wpilogmcp.store.StoreManifest.Provenance;

/** A recording is the original capture start on its first collecting store. Session ids and
 * calendar overlap group files, but cannot prove a shared robot clock after consolidation.
 * This is the common membership proof for session reads and durable live references. */
public record CaptureRecording(String collectingStoreId, String startedAt) {
  public static Optional<CaptureRecording> of(Provenance provenance, String servingOrigin) {
    if (provenance == null || !provenance.captured() || provenance.importedAt() == null
        || provenance.importedAt().isBlank()) return Optional.empty();
    String collector = provenance.copiedFrom().isEmpty() ? servingOrigin : provenance.copiedFrom().get(0).storeId();
    if (collector == null || collector.isBlank()) return Optional.empty();
    return Optional.of(new CaptureRecording(collector, provenance.importedAt()));
  }
  public static boolean same(Provenance left, Provenance right, String servingOrigin) {
    var first = of(left, servingOrigin);
    return first.isPresent() && first.equals(of(right, servingOrigin));
  }
}
