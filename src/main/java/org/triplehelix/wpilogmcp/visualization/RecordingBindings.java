/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.triplehelix.wpilogmcp.log.struct.StructSchemas;
import org.triplehelix.wpilogmcp.read.*;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.tools.VisualizationBindings;

/** Compatibility is fresh server evidence, independent of the overview's loaded page. Each
 * member uses the existing resolver; combining members may reject a conflict, never choose a
 * preferred file. No view, follow lease, store mutation or retained sample cache is created. */
final class RecordingBindings {
  private final Semaphore slots = new Semaphore(2);
  @FunctionalInterface interface Admission { RecordedSources.Admission open(JsonObject source) throws IOException; }
  private final Admission admission;
  RecordingBindings(RecordedSources sources) { this(sources::admitRecording); }
  RecordingBindings(Admission admission) { this.admission = admission; }
  static JsonObject limits() {
    var out = new JsonObject(); out.addProperty("max_concurrent_checks", 2);
    out.addProperty("max_member_entry_occurrences", RecordingInventories.MAX_OCCURRENCES);
    out.addProperty("max_evidence_records", BindingEvidence.MAX_RECORDS); out.addProperty("max_evidence_payload_bytes", BindingEvidence.MAX_BYTES);
    out.addProperty("max_evidence_record_bytes", BindingEvidence.RECORD_BYTES); out.addProperty("max_role_candidates", 128);
    out.addProperty("max_result_bytes", 1 << 20); out.addProperty("cooperative_work_budget_ms", 10_000); return out;
  }
  record Bound(RecordedSources.Admission admission, JsonObject result) implements AutoCloseable {
    @Override public void close() throws IOException { admission.close(); }
  }
  JsonObject check(JsonObject source, JsonObject configuration) throws IOException {
    try (var bound = admit(source, configuration)) { return bound.result(); }
  }
  Bound admit(JsonObject source, JsonObject configuration) throws IOException {
    return admit(source, configuration, () -> admission.open(source));
  }
  @FunctionalInterface interface Open { RecordedSources.Admission open() throws IOException; }
  Bound admit(JsonObject source, JsonObject configuration, Open open) throws IOException {
    return admit(source, ignored -> configuration, open);
  }
  /** Exact data reads use the same declaration/schema agreement as view bindings, on one
   * admission. Accepted types come from that prefix; this never chooses a preferred member. */
  record Selection(String entry, String field) {}
  Bound admitExact(JsonObject source, String entry, String field) throws IOException {
    return admitExact(source, List.of(new Selection(entry, field)));
  }
  Bound admitExact(JsonObject source, List<Selection> selections) throws IOException {
    return admit(source, admitted -> {
      var configuration = VisualizationContracts.blank(); int index = 0;
      for (var selection : selections) {
        var types = new TreeSet<String>();
        for (var member : admitted.prefix().members()) {
          var info = member.snapshot().entries().get(selection.entry()); if (info != null) types.add(info.type());
        }
        if (types.isEmpty()) throw new IllegalArgumentException("Exact entry is missing from every source member: " + selection.entry());
        var requirement = new JsonObject();
        requirement.addProperty("id", "analysis" + index++); requirement.addProperty("selector_kind", "entry"); requirement.addProperty("entry", selection.entry());
        var accepted = new JsonArray(); types.forEach(accepted::add); requirement.add("accepted_entry_types", accepted);
        requirement.addProperty("field_path", selection.field()); requirement.addProperty("value_type", selection.field() == null ? "raw" : "number"); requirement.add("unit", JsonNull.INSTANCE);
        configuration.getAsJsonArray("requirements").add(requirement);
      }
      return configuration;
    }, () -> admission.open(source));
  }
  private Bound admit(JsonObject source, java.util.function.Function<RecordedSources.Admission, JsonObject> requested, Open open) throws IOException {
    if (!slots.tryAcquire()) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "Two recording compatibility checks are in progress; retry after one finishes", true);
    var budget = new BindingEvidence.Budget();
    RecordedSources.Admission admission = null; boolean transferred = false;
    try {
      admission = open.open();
      var configuration = requested.apply(admission);
      var prefix = admission.prefix(); int occurrences = 0;
      for (var member : prefix.members()) {
        budget.check(); occurrences = Math.addExact(occurrences, member.snapshot().entries().size());
        if (occurrences > RecordingInventories.MAX_OCCURRENCES) throw new BindingLimit("Recording compatibility exceeds 200000 member-entry occurrences");
      }
      var result = new JsonObject(); result.add("source_ref", source.deepCopy()); result.addProperty("recording_id", prefix.recordingId());
      result.addProperty("catalog_revision", Long.toString(prefix.catalogRevision())); result.addProperty("finalized", prefix.finalized());
      result.addProperty("binding_basis", "fresh_recording_prefix"); var bindings = new JsonArray(); result.add("bindings", bindings); int resolved = 0;
      for (var element : configuration.getAsJsonArray("requirements")) {
        budget.check(); var requirement = element.getAsJsonObject(); var one = VisualizationContracts.blank(); one.getAsJsonArray("requirements").add(requirement);
        var combined = new JsonObject(); combined.add("requirement_id", requirement.get("id"));
        combined.add("selector", requirement.get(requirement.get("selector_kind").getAsString())); combined.add("field_path", requirement.get("field_path"));
        combined.addProperty("state", "unresolved"); combined.add("entry", JsonNull.INSTANCE);
        var members = new JsonArray(); var problems = new JsonArray(); var warnings = new JsonArray();
        combined.add("members", members); combined.add("problems", problems); combined.add("warnings", warnings); bindings.add(combined);
        String selected = null, declaration = null; JsonObject chosen = null; var schemas = new TreeMap<String, String>();
        boolean role = requirement.get("selector_kind").getAsString().equals("role");
        for (var member : prefix.members()) {
          var snapshot = member.snapshot(); var log = new BindingEvidence(snapshot, budget);
          var bound = VisualizationBindings.check(log, one, true).getAsJsonArray("bindings").get(0).getAsJsonObject();
          bound.addProperty("member_id", member.id()); bound.addProperty("generation", Long.toString(member.generation()));
          bound.addProperty("writer_identity", member.writerIdentity()); bound.addProperty("content_sha256", member.contentSha256());
          String name = ReadWire.nullableText(bound, "entry");
          boolean absent = name == null && (role ? bound.has("candidates") && bound.getAsJsonArray("candidates").isEmpty() : !snapshot.entries().containsKey(requirement.get("entry").getAsString()));
          if (absent) bound.addProperty("state", "absent");
          bound.addProperty("sample_count", name == null ? null : Long.toString(snapshot.sampleCount(name))); members.add(bound);
          for (var warning : bound.getAsJsonArray("warnings")) warnings.add("Member " + member.id() + ": " + warning.getAsString());
          if (name != null) {
            var info = snapshot.entries().get(name);
            String token = info.type() + ":" + DetachedInventory.digest(info.metadata());
            if (selected != null && !selected.equals(name)) problem(problems, member.id(), "ROLE_CONFLICT", "Members resolve the role to different exact names; select an exact entry");
            if (selected != null && selected.equals(name) && !declaration.equals(token)) problem(problems, member.id(), "DECLARATION_CONFLICT", "Members declare different types or metadata for the selected name");
            if (selected == null) { selected = name; declaration = token; }
            checkSchemas(snapshot.schemas(), info.type(), schemas, problems, warnings, member.id(), budget);
          }
          if (!absent && !bound.get("state").getAsString().equals("resolved"))
            problem(problems, member.id(), name == null && role ? "UNRESOLVED_ROLE" : "INCOMPATIBLE_MEMBER", bound.get("reason").getAsString());
          if (chosen == null && bound.get("state").getAsString().equals("resolved")) chosen = bound;
          bounded(result);
        }
        if (chosen == null && problems.isEmpty()) problem(problems, null, role ? "UNRESOLVED_ROLE" : "MISSING_ENTRY", role ? "No member supplies a unique published role; select an exact entry" : "Exact entry is missing from every admitted member");
        if (chosen != null && problems.isEmpty()) {
          combined.addProperty("state", "resolved"); combined.addProperty("entry", selected);
          combined.add("declared_type", chosen.get("declared_type")); combined.add("unit", chosen.get("unit"));
          var proof = new JsonObject(); proof.addProperty("declaration", declaration);
          var definitions = new JsonObject(); schemas.forEach(definitions::addProperty); proof.add("schemas", definitions);
          combined.addProperty("evidence_identity", DetachedInventory.digest(proof.toString()));
          combined.addProperty("reason", "Present members agree; absent members remain explicit"); resolved++;
        } else combined.addProperty("reason", "Recording evidence is missing, incompatible or conflicting; inspect member reasons");
        combined.addProperty("basis", "Shared resolver checked every admitted member's complete native entry set");
      }
      result.addProperty("compatibility", resolved == bindings.size() ? "compatible" : resolved == 0 ? "incompatible" : "partial");
      result.addProperty("evidence_records_visited", budget.records); result.addProperty("evidence_payload_bytes", budget.bytes);
      budget.check(); admission.check(); bounded(result); var bound = new Bound(admission, result); transferred = true; return bound;
    } catch (UncheckedIOException failure) { throw failure.getCause(); }
    catch (BindingLimit | ReadWire.WireLimit failure) { throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, failure.getMessage()); }
    finally {
      try { if (!transferred && admission != null) admission.close(); }
      finally { slots.release(); }
    }
  }
  private static void checkSchemas(StructSchemas schemas, String type, Map<String, String> prior, JsonArray problems,
      JsonArray warnings, String member, BindingEvidence.Budget budget) {
    String root = StructSchemas.structName(type); if (root == null) return;
    var pending = new ArrayDeque<String>(); pending.add(root); var visited = new HashSet<String>();
    while (!pending.isEmpty()) {
      budget.check(); String name = pending.removeFirst(); if (!visited.add(name)) continue;
      var schema = schemas.info(name).orElse(null);
      if (schema == null || !schema.valid()) {
        problem(problems, member, "SCHEMA_UNAVAILABLE", "Selected struct " + name + " has no valid schema"); continue;
      }
      String digest = DetachedInventory.digest(schema.schema()); String before = prior.putIfAbsent(name, digest);
      if (before != null && !before.equals(digest)) problem(problems, member, "SCHEMA_CONFLICT", "Members supply different definitions of selected schema " + name);
      if (schema.source() != StructSchemas.Source.LOGGED) warnings.add("Member " + member + " uses " + schema.source().wire() + " schema for " + name);
      for (var field : schema.fields()) if (field.structType() != null) pending.add(field.structType());
    }
  }
  private static void bounded(JsonObject value) throws IOException {
    // Bound the compatibility value in both its ordinary and MCP-escaped encodings. The
    // general transport owns the outer JSON-RPC envelope and caller-supplied request id.
    try {
      byte[] raw = ReadWire.bytes(value, (1 << 20) - (144 << 10));
      ReadWire.bytes(new JsonPrimitive(new String(raw, java.nio.charset.StandardCharsets.UTF_8)), (1 << 20) - (144 << 10));
    } catch (ReadException | ReadWire.WireLimit limit) {
      throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT,
          "Recording compatibility result exceeds its 1 MiB allowance; select fewer requirements or entries with smaller metadata");
    }
  }
  private static void problem(JsonArray problems, String member, String code, String reason) {
    var item = new JsonObject(); item.addProperty("member_id", member); item.addProperty("code", code); item.addProperty("reason", reason); problems.add(item);
  }
}
