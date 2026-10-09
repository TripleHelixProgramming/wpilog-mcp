/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.util.List;
import org.triplehelix.wpilogmcp.capture.pull.KernelLines;

/** Text companions have their own clocks and shared, cross-boot files are not telemetry logs. */
public record SystemLogState(List<File> files, KernelLines.Cursor kernelCursor, String journalCursor,
    String journalBootId, java.util.Map<String, String> reasons) {
  public static final SystemLogState EMPTY = new SystemLogState(List.of(), null, null, null, java.util.Map.of());
  public SystemLogState {
    files = files == null ? List.of() : List.copyOf(files);
    reasons = reasons == null ? java.util.Map.of() : java.util.Map.copyOf(reasons);
  }
  public enum Location { SESSION, STORE }
  /** SESSION paths survive session renames; STORE is only for shared robot/system files. */
  public record File(Location location, String path, String source, String format, String sha256,
      long sizeBytes, StoreManifest.Provenance provenance, String note) {}
  public SystemLogState withFiles(List<File> value) { return new SystemLogState(value, kernelCursor, journalCursor, journalBootId, reasons); }
}
