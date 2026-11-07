/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.IOException;
import java.security.*;
import java.util.*;
import org.erdtman.jcs.JsonCanonicalizer;

/** Portable definitions have one strict validator and an RFC 8785 digest. Store receipts and
 * source locators never enter this envelope. JCS supplies ECMAScript binary64 formatting. */
public final class TemplateDefinition {
  public static final String FORMAT = "wpilog-visualization-template";
  private TemplateDefinition() {}
  public static JsonObject validate(JsonObject input) {
    var value = VisualizationContracts.parse(input.toString());
    if (!value.keySet().equals(Set.of("schema_version", "template_id", "revision", "name", "description", "configuration"))) fail("unknown or missing template fields");
    if (!value.get("schema_version").equals(new JsonPrimitive(1))) fail("unsupported schema_version");
    UUID.fromString(text(value, "template_id", 36, false));
    revision(value, "revision", false); text(value, "name", 256, false); text(value, "description", 4096, true);
    VisualizationContracts.configuration(value.getAsJsonObject("configuration"));
    return value;
  }
  public static String text(JsonObject value, String key, int limit, boolean empty) {
    var field = value.get(key);
    if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) fail(key + " must be a string");
    String s = field.getAsString(); if ((!empty && s.isBlank()) || s.length() > limit) fail(key + " exceeds its text bounds");
    return s;
  }
  public static long revision(JsonObject value, String key, boolean zero) {
    var field = value.get(key);
    if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber()) fail(key + " must be an integer");
    final long exact;
    try { exact = field.getAsBigDecimal().longValueExact(); }
    catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException("INVALID_TEMPLATE: " + key + " must be an exact safe integer", invalid); }
    if (exact < (zero ? 0 : 1) || exact > 9_007_199_254_740_991L) fail(key + " must be a safe integer");
    return exact;
  }
  public static String canonical(JsonObject object) throws IOException {
    return new JsonCanonicalizer(VisualizationContracts.parse(object.toString()).toString()).getEncodedString();
  }
  public static String digest(JsonObject object) throws IOException {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new JsonCanonicalizer(VisualizationContracts.parse(object.toString()).toString()).getEncodedUTF8())); }
    catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }
  public static JsonObject export(JsonObject template) throws IOException {
    var out = new JsonObject(); out.addProperty("format", FORMAT); out.addProperty("schema_version", 1); out.add("template", validate(template));
    out.addProperty("content_sha256", digest(out)); return out;
  }
  /** The portable text is nested inside HTTP/MCP JSON; admission must count that extra escaping. */
  public static JsonObject exportReply(JsonObject template) throws IOException {
    var envelope = export(template); var out = new JsonObject(); out.addProperty("envelope_json", envelope.toString());
    for (var key : List.of("format", "schema_version", "content_sha256")) out.add(key, envelope.get(key)); return out;
  }
  public static JsonObject imported(String raw) throws IOException {
    var envelope = VisualizationContracts.parse(raw);
    if (!envelope.keySet().equals(Set.of("format", "schema_version", "template", "content_sha256"))) fail("unknown or missing envelope fields");
    if (!FORMAT.equals(text(envelope, "format", 64, false)) || !envelope.get("schema_version").equals(new JsonPrimitive(1))) fail("unsupported template envelope");
    var template = validate(envelope.getAsJsonObject("template"));
    String expected = text(envelope, "content_sha256", 64, false); envelope.remove("content_sha256");
    if (!expected.equals(digest(envelope))) fail("content_sha256 mismatch");
    return template;
  }
  public static JsonObject reference(String store, JsonObject template) {
    var ref = new JsonObject(); ref.addProperty("origin", store == null ? "bundled" : "store");
    if (store != null) ref.addProperty("store_id", store);
    ref.add("template_id", template.get("template_id")); ref.add("revision", template.get("revision")); return ref;
  }
  /** Pinned provenance is validated on admission and recovery, even when no template is loaded. */
  public static JsonElement validateReference(JsonElement input) {
    if (input == null || input.isJsonNull()) return JsonNull.INSTANCE;
    if (!input.isJsonObject()) fail("template_ref must be an object or null");
    var ref = input.getAsJsonObject(); String origin = text(ref, "origin", 16, false);
    var fields = switch (origin) {
      case "bundled" -> Set.of("origin", "template_id", "revision");
      case "store" -> Set.of("origin", "store_id", "template_id", "revision");
      case "library" -> Set.of("origin", "server_instance_id", "library_id", "template_id", "revision");
      default -> throw new IllegalArgumentException("INVALID_TEMPLATE: unknown reference origin");
    };
    if (!ref.keySet().equals(fields)) fail("unknown or missing template reference fields");
    UUID.fromString(text(ref, "template_id", 36, false)); revision(ref, "revision", false);
    if (origin.equals("store")) UUID.fromString(text(ref, "store_id", 36, false));
    if (origin.equals("library")) { LibraryReferences.uuid(ref, "server_instance_id"); LibraryReferences.uuid(ref, "library_id"); }
    return ref.deepCopy();
  }
  public static JsonObject catalog(String store, JsonObject template) {
    var out = new JsonObject(); out.add("template_ref", reference(store, template)); out.add("template", template.deepCopy()); return out;
  }
  private static void fail(String reason) { throw new IllegalArgumentException("INVALID_TEMPLATE: " + reason); }
}
