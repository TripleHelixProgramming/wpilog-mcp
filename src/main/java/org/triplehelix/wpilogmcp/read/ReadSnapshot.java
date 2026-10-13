/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;

/** One indexed file boundary or writer-owned fixed prefix. Closed-file reads across a change
 * are discarded with FILE_CHANGED; reopen to see growth. Closing retires admission, while existing cursors retain the mapping.
 * Schemas include their logged or assumed basis. Session snapshots retain source coordinates and recorded alignment. */
public interface ReadSnapshot extends AutoCloseable {
  Path path();
  /** Null when there is no single disk boundary (a writer-owned prefix or a session union).
   * Use live() to distinguish a writer-owned prefix from a composite snapshot. */
  FileSnapshot boundary();
  default boolean live() { return false; }
  default java.util.List<SourceFile> sources() { return java.util.List.of(); }
  default java.util.List<ExcludedSource> excludedSources() { return java.util.List.of(); }
  record SourceFile(String path, String sha256, org.triplehelix.wpilogmcp.store.StoreManifest.Provenance provenance,
      java.util.List<org.triplehelix.wpilogmcp.sync.SyncResult> clock) {
    public SourceFile { clock = java.util.List.copyOf(clock); }
  }
  record ExcludedSource(String path, String reason) {}
  Map<String, EntryInfo> entries();
  StructSchemas schemas();
  long sampleCount(String entry);
  /** Indexed address-field bytes, excluding decoded values and object overhead. */
  long indexBytes();
  boolean truncated();
  boolean damaged();
  String truncationMessage();
  SampleCursor read(ReadQuery query) throws IOException;
  default MultiSampleCursor readAll(java.util.List<ReadQuery> queries) throws IOException {
    return MultiSampleCursor.open(this, queries);
  }
  /** Full validation is an explicit all-record cursor, including out-of-window failures. */
  default SampleCursor validate(String entry) throws IOException {
    return read(new ReadQuery(entry, java.util.List.of(), ReadQuery.Order.FILE, 4096, 4 << 20));
  }
  @Override void close() throws IOException;
}
