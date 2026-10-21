/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;

/** Only complete recovery with proven local receipts may defer publication without stopping capture. */
public final class PublicationPendingException extends IOException {
  public PublicationPendingException(String message) { super(message); }
}
