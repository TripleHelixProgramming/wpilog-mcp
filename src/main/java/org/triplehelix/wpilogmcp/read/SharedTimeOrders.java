/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.util.HashMap;
import java.util.Map;

/** Permutations belong to a file version (or a writer's fixed prefix), not to a client's handle.
 * A lease lasts through its snapshot's final cursor. Only the last lease releases the budget;
 * no mappings or callbacks are retained after that, and sorting never runs under this monitor. */
public final class SharedTimeOrders {
  private final SortBudget budget;
  private final Map<Object, Entry> entries = new HashMap<>();
  private static final class Entry {
    final TimeOrderCache cache;
    int users;
    Entry(SortBudget budget) { cache = new TimeOrderCache(budget); }
  }
  public SharedTimeOrders(SortBudget budget) { this.budget = budget; }
  public synchronized Lease acquire(Object version) {
    var entry = entries.computeIfAbsent(version, ignored -> new Entry(budget));
    entry.users++;
    return new Lease(version, entry);
  }
  public final class Lease implements AutoCloseable {
    private final Object version;
    private final Entry entry;
    private boolean closed;
    private Lease(Object version, Entry entry) { this.version = version; this.entry = entry; }
    public TimeOrderCache cache() { return entry.cache; }
    @Override public void close() {
      boolean last;
      synchronized (SharedTimeOrders.this) {
        if (closed) return;
        closed = true;
        last = --entry.users == 0;
        if (last) {
          entries.remove(version, entry);
          // Clearing arrays and returning atomic budget charges performs no IO or callbacks.
          // Do it before another acquisition can reserve a new permutation for this version.
          entry.cache.close();
        }
      }
    }
  }
}
