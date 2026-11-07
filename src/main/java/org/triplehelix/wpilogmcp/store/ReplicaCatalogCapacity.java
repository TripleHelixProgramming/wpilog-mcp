/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

/** Warn before either count cap, without claiming these counts measure encoded bytes or heap.
 * Integer cross multiplication includes the first whole record at or above eighty percent. */
public record ReplicaCatalogCapacity(int records, int recordsLimit, int exclusions,
    int exclusionsLimit, boolean nearLimit) {
  public static ReplicaCatalogCapacity of(ReplicaCatalog catalog) {
    if (catalog == null) return null;
    int records = catalog.files().size(), exclusions = catalog.exclusions().size();
    return new ReplicaCatalogCapacity(records, ReplicaCatalog.MAX_FILES, exclusions,
        ReplicaCatalog.MAX_EXCLUSIONS, (long) records * 5 >= (long) ReplicaCatalog.MAX_FILES * 4
        || (long) exclusions * 5 >= (long) ReplicaCatalog.MAX_EXCLUSIONS * 4);
  }
}
