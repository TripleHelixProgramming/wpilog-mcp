/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;

/** Size against MCP's escaped text content, leaving room for its envelope, source reference,
 * request id and result-contract fields. HTTP uses the identical, smaller data object. */
final class InventoryWire {
  static final int BYTES = 1 << 20, REQUEST_BYTES = RecordingInventories.MAX_REQUEST_BYTES;
  static final int ENVELOPE_BYTES = 2 * REQUEST_BYTES + 8192;
  private static final Gson JSON = new GsonBuilder().serializeNulls().create();
  static int size(JsonObject value) throws InventoryFailure {
    try {
      byte[] raw = ReadWire.bytes(value, BYTES - ENVELOPE_BYTES);
      var escaped = new JsonPrimitive(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
      return ReadWire.bytes(escaped, BYTES - ENVELOPE_BYTES).length + ENVELOPE_BYTES;
    } catch (java.io.IOException | ReadWire.WireLimit failure) {
      throw InventoryFailure.limit("Inventory reply exceeds 1 MiB including its transport envelope; select fewer entries", false);
    }
  }
  static void continuation(JsonObject request) throws InventoryFailure {
    try { ReadWire.bytes(request, REQUEST_BYTES - 2048); }
    catch (java.io.IOException | ReadWire.WireLimit failure) { throw InventoryFailure.limit("Continuation leaves no room for the MCP request envelope", false); }
  }
  static void request(JsonObject request) throws InventoryFailure {
    try { ReadWire.bytes(request, REQUEST_BYTES); }
    catch (java.io.IOException | ReadWire.WireLimit failure) { throw InventoryFailure.limit("Inventory request exceeds 64 KiB", false); }
  }
  static String text(JsonObject request, String name) {
    var value = request.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(name + " must be a string");
    return value.getAsString();
  }
  static String json(JsonElement value) { return JSON.toJson(value); }
}
