/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.util.List;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

/** Detached public description, independent of the JSON library used by a transport. A null
 * boundary means a fixed writer-owned prefix only when live is true; store is null for loose files. */
public record SnapshotDescriptor(int apiVersion, String id, String path, boolean live, Boundary boundary,
    Map<String, EntryInfo> entries, List<StructSchemas.StructInfo> schemas, Map<String, Long> sampleCounts,
    long indexBytes, boolean truncated, boolean damaged, String truncationMessage,
    double idleTimeoutSec, CatalogDescriptor.Membership store, List<ReadSnapshot.SourceFile> sources, List<ReadSnapshot.ExcludedSource> excludedSources) {
  public SnapshotDescriptor {
    entries = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(entries));
    sources = sources == null ? List.of() : List.copyOf(sources);
    excludedSources = excludedSources == null ? List.of() : List.copyOf(excludedSources);
    schemas = List.copyOf(schemas); sampleCounts = Map.copyOf(sampleCounts);
  }
  public record Boundary(long sizeBytes, String modified, String created, String identity) {}
}
