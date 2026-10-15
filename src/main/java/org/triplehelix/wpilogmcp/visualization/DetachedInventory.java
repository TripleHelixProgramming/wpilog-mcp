/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.read.server.*;

/** Compact immutable facts only. Native strings can be compared while building, but metadata
 * is retained solely as a digest and every prefix is closed before this object is published. */
final class DetachedInventory {
  record Declaration(String member, int id, String type, String metadata, long count) {}
  record Row(String id, String name, String preview, int nameLength, List<Declaration> declarations,
      List<Integer> typeLengths, boolean unavailable, boolean conflict, Long count, int wireBytes) {
    JsonObject json() {
      var out = new JsonObject(); out.addProperty("row_id", id); out.addProperty("unavailable", unavailable);
      out.addProperty("conflict", conflict); out.addProperty("sample_count", count == null ? null : Long.toString(count));
      var members = new JsonArray();
      for (var d : declarations) {
        var item = new JsonObject(); item.addProperty("member_id", d.member());
        item.addProperty("type", d.type()); item.addProperty("sample_count", Long.toString(d.count())); members.add(item);
      }
      out.add("members", members);
      if (!unavailable) { out.addProperty("name", name); out.addProperty("type", conflict ? null : declarations.get(0).type()); }
      else {
        out.addProperty("display_preview", preview); out.addProperty("name_length_utf16", nameLength);
        var lengths = new JsonArray(); typeLengths.forEach(lengths::add); out.add("type_lengths_utf16", lengths);
        out.addProperty("reason", "Declaration text exceeds the inventory row budget; display_preview is not a binding name");
      }
      return out;
    }
  }
  final JsonObject source, summary;
  final List<Row> rows;
  final Map<String, Row> byId;
  final Map<String, RecordingMemberIdentity> identities;
  final CatalogRecordingSource.Access access;
  final long bytes;
  final int unavailable, conflicting;
  private DetachedInventory(JsonObject source, JsonObject summary, List<Row> rows, Map<String, RecordingMemberIdentity> identities,
      CatalogRecordingSource.Access access, long bytes) {
    this.source = source; this.summary = summary; this.rows = List.copyOf(rows); this.identities = Map.copyOf(identities);
    this.access = access; this.bytes = bytes;
    var index = new HashMap<String, Row>(); rows.forEach(row -> index.put(row.id(), row)); byId = Map.copyOf(index);
    unavailable = (int) rows.stream().filter(Row::unavailable).count(); conflicting = (int) rows.stream().filter(Row::conflict).count();
  }
  static DetachedInventory build(JsonObject source, RecordedSources.Admission admission, long maxBytes, BooleanSupplier cancelled) throws IOException {
    var prefix = admission.prefix(); var budget = new Allocation(maxBytes);
    budget.charge(8192); budget.text(source.toString());
    var summary = RecordingInventory.summary(prefix); budget.charge(8L * InventoryWire.size(summary));
    var entries = new TreeMap<String, ArrayList<Declaration>>();
    int occurrences = 0;
    for (var member : prefix.members()) {
      budget.text(member.id());
      for (var entry : member.snapshot().entries().values()) {
        check(cancelled);
        if (++occurrences > RecordingInventories.MAX_OCCURRENCES) throw InventoryFailure.limit("Inventory exceeds 200000 member-entry occurrences", false);
        var values = entries.get(entry.name());
        if (values == null) { budget.charge(512); values = new ArrayList<>(); entries.put(entry.name(), values); }
        // Native name/type references live only until the row has been checked and detached.
        // Never retain raw metadata, even when it is much larger than the wire budget.
        budget.charge(256); values.add(new Declaration(member.id(), entry.id(), entry.type(), digest(entry.metadata()), member.snapshot().sampleCount(entry.name())));
      }
    }
    var rows = new ArrayList<Row>(); var identities = new TreeMap<String, RecordingMemberIdentity>();
    prefix.members().forEach(member -> identities.put(member.id(), RecordingMemberIdentity.of(member)));
    for (var entry : entries.entrySet()) {
      check(cancelled); var declarations = entry.getValue(); declarations.sort(Comparator.comparing(Declaration::member));
      boolean conflict = declarations.stream().anyMatch(d -> !d.type().equals(declarations.get(0).type()) || !d.metadata().equals(declarations.get(0).metadata()));
      long count = 0;
      if (!conflict) try { for (var d : declarations) count = Math.addExact(count, d.count()); }
      catch (ArithmeticException overflow) { throw InventoryFailure.limit("Inventory sample count exceeds the exact signed 64-bit range", false); }
      String id = UUID.randomUUID().toString();
      var row = new Row(id, entry.getKey(), null, entry.getKey().length(), List.copyOf(declarations), List.of(), false, conflict, conflict ? null : count, 0);
      int size;
      try { size = InventoryWire.size(row.json());
        if (size > InventoryWire.BYTES - 4096) throw InventoryFailure.limit("Row leaves no space for the page envelope", false);
      }
      catch (InventoryFailure huge) {
        var lengths = declarations.stream().map(d -> d.type().length()).toList();
        var compact = declarations.stream().map(d -> new Declaration(d.member(), d.id(), null, d.metadata(), d.count())).toList();
        row = new Row(id, null, preview(entry.getKey()), entry.getKey().length(), compact, lengths, true, conflict, conflict ? null : count, 0);
        size = InventoryWire.size(row.json());
      }
      budget.text(row.name()); budget.text(row.preview()); for (var d : row.declarations()) budget.text(d.type());
      rows.add(new Row(row.id(), row.name(), row.preview(), row.nameLength(), row.declarations(), row.typeLengths(), row.unavailable(), row.conflict(), row.count(), size - InventoryWire.ENVELOPE_BYTES));
    }
    admission.access().check(); check(cancelled);
    return new DetachedInventory(source.deepCopy(), summary, rows, identities, admission.access(), budget.used);
  }
  JsonObject details(RecordedSources.Admission admission, List<String> ids) throws IOException {
    var prefix = admission.prefix();
    if (!summary.get("recording_id").getAsString().equals(prefix.recordingId()) || prefix.members().size() != identities.size())
      throw InventoryFailure.changed("Recording identity or member set changed");
    for (var member : prefix.members()) {
      var old = identities.get(member.id());
      if (old == null) throw InventoryFailure.changed("Recording member set changed");
      String reason = old.changed(member, summary.get("finalized").getAsBoolean(), prefix.finalized());
      if (reason != null) throw InventoryFailure.changed(reason);
    }
    var selected = new TreeSet<String>(); var rows = new JsonArray(); boolean grew = false;
    for (String id : ids) {
      var row = byId.get(id);
      if (row == null) throw new IllegalArgumentException("Unknown inventory row_id");
      if (row.unavailable()) throw InventoryFailure.limit("Selected row text is unavailable; choose another entry", false);
      selected.add(row.name()); rows.add(row.json());
      var declarations = new HashMap<String, Declaration>(); row.declarations().forEach(d -> declarations.put(d.member(), d));
      for (var member : prefix.members()) {
        EntryInfo next = member.snapshot().entries().get(row.name()); var old = declarations.get(member.id());
        if (old == null && next == null) continue;
        if (old == null || next == null || old.id() != next.id() || !old.type().equals(next.type()) || !old.metadata().equals(digest(next.metadata()))
            || member.snapshot().sampleCount(row.name()) < old.count()) throw InventoryFailure.changed("Selected declaration or retained history changed");
        grew |= member.snapshot().sampleCount(row.name()) > old.count();
      }
    }
    var out = RecordingInventory.summary(prefix); out.addProperty("detail_revision", UUID.randomUUID().toString());
    out.addProperty("count_basis", "fresh_admission"); out.addProperty("counts_grew_since_inventory", grew);
    out.addProperty("selected_rows_basis", "inventory_creation"); out.add("selected_rows", rows); var descriptors = new JsonArray(); var problems = new JsonArray();
    var schemaTokens = new HashMap<String, String>();
    int bytes = InventoryWire.size(out);
    for (var member : prefix.members()) {
      JsonObject descriptor;
      try { descriptor = ReadWire.descriptorForEntries("", member.snapshot(), selected, InventoryWire.BYTES - bytes); }
      catch (ReadWire.WireLimit limit) { throw InventoryFailure.limit("Selected details exceed 1 MiB; select fewer entries", false); }
      var item = new JsonObject(); item.addProperty("member_id", member.id()); item.add("snapshot", descriptor); descriptors.add(item);
      for (String name : selected) {
        var entry = member.snapshot().entries().get(name); if (entry == null) continue;
        String type = StructSchemas.structName(entry.type());
        if (type != null && member.snapshot().schemas().info(type).isEmpty()) problem(problems, member.id(), name, "MISSING_SCHEMA", "Selected struct has no recorded schema");
      }
      for (var element : descriptor.getAsJsonArray("schemas")) {
        var schema = element.getAsJsonObject(); String name = schema.get("name").getAsString();
        if (schema.has("error") && !schema.get("error").isJsonNull()) problem(problems, member.id(), name, "INVALID_SCHEMA", schema.get("error").getAsString());
        String token = digest(schema.get("schema").isJsonNull() ? null : schema.get("schema").getAsString());
        String prior = schemaTokens.putIfAbsent(name, token);
        if (prior != null && !prior.equals(token)) problem(problems, member.id(), name, "SCHEMA_CONFLICT", "Members record different schemas; no compatible binding is inferred");
      }
      out.add("descriptors", descriptors); out.add("schema_problems", problems); bytes = InventoryWire.size(out);
    }
    admission.access().check(); return out;
  }
  private static void problem(JsonArray problems, String member, String name, String code, String reason) {
    var problem = new JsonObject(); problem.addProperty("member_id", member); problem.addProperty("name", name);
    problem.addProperty("code", code); problem.addProperty("reason", reason); problems.add(problem);
  }
  private static String preview(String text) { return text.substring(0, text.offsetByCodePoints(0, Math.min(128, text.codePointCount(0, text.length())))); }
  static String digest(String text) {
    try {
      var hash = MessageDigest.getInstance("SHA-256"); hash.update((byte) (text == null ? 0 : 1));
      // Hash exact UTF-16 code units, with a fixed scratch buffer; do not allocate UTF-8 for
      // multi-megabyte metadata or collapse distinct malformed surrogate sequences.
      if (text != null) {
        var bytes = new byte[8192]; int used = 0;
        for (int i = 0; i < text.length(); i++) {
          char c = text.charAt(i); bytes[used++] = (byte) (c >>> 8); bytes[used++] = (byte) c;
          if (used == bytes.length) { hash.update(bytes); used = 0; }
        }
        hash.update(bytes, 0, used);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }
  static void check(BooleanSupplier cancelled) throws IOException {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Inventory construction cancelled");
  }
  private static final class Allocation {
    final long maximum; long used;
    Allocation(long maximum) { this.maximum = maximum; }
    void charge(long bytes) throws InventoryFailure {
      if (bytes < 0 || bytes > maximum - used) throw InventoryFailure.limit("Detached inventory exceeds its 32 MiB accounted metadata limit", false);
      used += bytes;
    }
    void text(String value) throws InventoryFailure { if (value != null) charge(64L + 2L * value.length()); }
  }
}
