/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

/** A bounded evidence refusal must escape compatibility's ordinary unresolved-field catch. */
public final class BindingLimit extends RuntimeException {
  public BindingLimit(String message) { super(message); }
}
