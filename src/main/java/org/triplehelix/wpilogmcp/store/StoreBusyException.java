/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.util.Objects;

/**
 * A store condition that passes on its own: another process holds {@code store.lock}, the
 * store's queue stayed occupied past a turn's deadline, or a record changed while it was read.
 * Trying again later can succeed, so a caller may wait and retry; corrupt or unsafe recovery is
 * never this type. Capture startup once stopped on these until the server restarted, because a
 * busy store and a corrupt one failed alike.
 */
public class StoreBusyException extends IOException {
  /** What kept the store busy, in the fixed vocabulary that public status may carry. */
  public enum Condition { LOCK_HELD, QUEUE_BUSY, RECORD_CHANGING }

  private final Condition condition;

  public StoreBusyException(Condition condition, String message) {
    this(condition, message, null);
  }

  public StoreBusyException(Condition condition, String message, Throwable cause) {
    super(message, cause);
    this.condition = Objects.requireNonNull(condition);
  }

  public Condition condition() { return condition; }

  /** The busy condition inside a future's wrappers, or null when the failure is anything else. */
  public static StoreBusyException in(Throwable failure) {
    var cause = failure;
    while ((cause instanceof java.util.concurrent.CompletionException
        || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null)
      cause = cause.getCause();
    return cause instanceof StoreBusyException busy ? busy : null;
  }
}
