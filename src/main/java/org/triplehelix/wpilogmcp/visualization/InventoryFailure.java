/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.JsonObject;
import java.io.IOException;

/** Inventory lifecycle is separate from a native reader's lease lifecycle. Both adapters
 * preserve these codes and remedies rather than classifying expiry as malformed input. */
public final class InventoryFailure extends IOException {
  public final int status;
  public final String code;
  public final boolean retryable;
  public InventoryFailure(int status, String code, String message, boolean retryable) {
    super(message); this.status = status; this.code = code; this.retryable = retryable;
  }
  public JsonObject json() {
    var out = new JsonObject(); out.addProperty("code", code); out.addProperty("error", getMessage());
    out.addProperty("retryable", retryable); if (retryable) out.addProperty("retry_after_ms", 1000); return out;
  }
  static InventoryFailure limit(String message, boolean retryable) { return new InventoryFailure(413, "RESOURCE_LIMIT", message, retryable); }
  static InventoryFailure expired() { return new InventoryFailure(410, "INVENTORY_EXPIRED", "Inventory expired; reopen from the anchored source_ref and restart the traversal", false); }
  static InventoryFailure changed(String reason) { return new InventoryFailure(409, "INVENTORY_CHANGED", reason + "; refresh the inventory and select the entry again", false); }
}
