/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.util.concurrent.atomic.AtomicLong;

/** One access owner charges retained permutations and concurrent sorting workspaces together. */
public final class SortBudget {
  private final long limit;
  private final AtomicLong held = new AtomicLong();

  public SortBudget(long limit) {
    if (limit < 0) throw new IllegalArgumentException("Sort budget must be nonnegative");
    this.limit = limit;
  }

  void reserve(long bytes) throws ReadException {
    long current;
    do {
      current = held.get();
      if (bytes > limit - current) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT,
          "Time ordering needs " + bytes + " bytes; the local sort budget is " + limit
              + " bytes (" + current + " held). Use file order or a larger budget.");
    } while (!held.compareAndSet(current, current + bytes));
  }
  void release(long bytes) { held.addAndGet(-bytes); }
}
