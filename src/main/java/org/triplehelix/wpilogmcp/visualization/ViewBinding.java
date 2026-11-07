/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import com.google.gson.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.triplehelix.wpilogmcp.read.*;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.read.server.OrdinalFollow;

/** Resolves one immutable working revision on the very prefix used for bootstrap. A later
 * revision never rewrites an opened query. The caller must replace its subscription and reject
 * late pages using the returned revision; this owner does not join the metadata queue over I/O. */
public final class ViewBinding {
  public static final int PAGE_BYTES = 1 << 20, MAX_SELECTIONS = 32;
  @FunctionalInterface interface Views { JsonObject get(UUID id) throws IOException; }
  private final Views views;
  private final RecordingBindings bindings;
  ViewBinding(VisualizationService views, RecordingBindings bindings) { this(views::get, bindings); }
  ViewBinding(Views views, RecordingBindings bindings) { this.views = views; this.bindings = bindings; }

  public Prepared prepare(UUID id, long revision, BooleanSupplier alive) throws IOException {
    var view = current(id, revision); var source = view.getAsJsonObject("source_ref");
    if (!VisualizationContracts.kind(source).equals("session")) throw new IOException("SOURCE_UNAVAILABLE: view binding requires an anchored recording; use the recorded preview for a file");
    var config = view.getAsJsonObject("configuration"); var bound = bindings.admit(source, config);
    try {
      var prefix = bound.admission().prefix(); var summary = RecordingInventory.summary(prefix);
      var range = config.getAsJsonObject("time").getAsJsonObject(prefix.finalized() ? "recorded" : "live");
      var clock = summary.getAsJsonObject("clock"); var windows = new ArrayList<ReadQuery.Window>();
      boolean waiting = false;
      switch (range.get("mode").getAsString()) {
        case "full" -> { }
        case "fixed" -> windows.add(new ReadQuery.Window(micros(range.get("start_sec").getAsDouble()), micros(range.get("end_sec").getAsDouble()), true));
        case "follow" -> {
          if (clock.get("right_edge_sec").isJsonNull()) waiting = true;
          else {
            double end = clock.get("right_edge_sec").getAsDouble();
            windows.add(new ReadQuery.Window(micros(end - range.get("window_sec").getAsDouble()), micros(end), true));
          }
        }
        default -> throw new IllegalArgumentException("Unsupported view time mode");
      }
      var selected = new LinkedHashMap<String, ReadQuery>(); var signature = new JsonObject();
      for (var item : bound.result().getAsJsonArray("bindings")) {
        var b = item.getAsJsonObject(); if (!b.get("state").getAsString().equals("resolved")) continue;
        var query = new ReadQuery(b.get("entry").getAsString(), windows, ReadQuery.Order.FILE, 4096, PAGE_BYTES,
            ReadWire.nullableText(b, "field_path"), null);
        var pair = new JsonArray(); pair.add(query.entry()); pair.add(query.field()); String key = pair.toString();
        selected.putIfAbsent(key, query); signature.add(b.get("requirement_id").getAsString(), signature(b));
      }
      if (selected.size() > MAX_SELECTIONS) throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "A recording view follow admits at most 32 distinct entry/field selections; split the layout");
      var out = new JsonObject(); out.addProperty("api_version", 1); out.addProperty("view_id", id.toString()); out.addProperty("revision", revision);
      out.add("source_ref", source.deepCopy()); out.add("compatibility", bound.result()); out.add("clock", clock);
      out.add("time_policy", range.deepCopy()); out.addProperty("frontier_scope", "queries"); out.addProperty("max_page_bytes", PAGE_BYTES);
      out.addProperty("follow_ready", !waiting && !selected.isEmpty());
      out.addProperty("reason", waiting ? "Waiting for recorded clock evidence before choosing the historical window"
          : selected.isEmpty() ? "No requirements are resolved; edit the layout or check again when declarations arrive" : "Resolved selections are pinned; unresolved requirements remain placeholders");
      var queries = new JsonArray(); selected.values().forEach(q -> queries.add(ReadWire.query(q))); out.add("queries", queries);
      // A detached answer and a follow creation response both fit the panel client's allowance.
      try { ReadWire.bytes(out, PAGE_BYTES - (32 << 10)); }
      catch (ReadWire.WireLimit | ReadException limit) {
        throw new ReadException(ReadProblem.Code.RESOURCE_LIMIT, "View binding response exceeds its 1 MiB allowance; split the layout or select smaller metadata");
      }
      current(id, revision); checkAlive(alive); bound.admission().check();
      return new Prepared(id, revision, source.deepCopy(), config.deepCopy(), out, List.copyOf(selected.values()), signature, bound, alive);
    } catch (IOException | RuntimeException | Error failure) { bound.close(); throw failure; }
  }
  private JsonObject current(UUID id, long expected) throws IOException {
    var value = views.get(id);
    if (value.get("revision").getAsLong() != expected) throw new IOException("REVISION_CONFLICT: working view changed; read its current revision before binding");
    return value;
  }
  private static long micros(double seconds) {
    if (!Double.isFinite(seconds) || Math.abs(seconds) > 9_007_199_254.0) throw new IllegalArgumentException("View time exceeds the exact microsecond range");
    return Math.round(seconds * 1e6);
  }
  private static JsonObject signature(JsonObject binding) {
    var out = new JsonObject();
    for (var key : List.of("entry", "field_path", "declared_type", "unit", "evidence_identity")) out.add(key, binding.get(key));
    return out;
  }
  private static void checkAlive(BooleanSupplier alive) throws IOException {
    if (!alive.getAsBoolean()) throw new java.nio.file.AccessDeniedException("Visualization session expired; reconnect before binding or following");
  }

  /** Close on every path, including refused admission or a failed response. After construction
   * the follow engine owns the initial prefix; closing this preparation cannot close its peers. */
  public final class Prepared implements AutoCloseable {
    private final UUID id; private final long revision;
    private final JsonObject source, configuration, result, signatures;
    private final List<ReadQuery> queries; private final BooleanSupplier alive;
    private RecordingBindings.Bound initial;
    private boolean opened;
    private Prepared(UUID id, long revision, JsonObject source, JsonObject configuration, JsonObject result,
        List<ReadQuery> queries, JsonObject signatures, RecordingBindings.Bound initial, BooleanSupplier alive) {
      this.id = id; this.revision = revision; this.source = source; this.configuration = configuration; this.result = result;
      this.queries = queries; this.signatures = signatures; this.initial = initial; this.alive = alive;
    }
    public JsonObject describe() { return result.deepCopy(); }
    public void checkCurrent() throws IOException { current(id, revision); checkAlive(alive); }
    public OrdinalFollow open(String epoch, OrdinalFollow.Budget budget) throws IOException {
      if (opened || initial == null) throw new IllegalStateException("View binding was already consumed or closed");
      checkCurrent();
      if (!result.get("follow_ready").getAsBoolean()) throw new IOException((queries.isEmpty() ? "NO_RESOLVED_BINDINGS: " : "CLOCK_UNAVAILABLE: ") + result.get("reason").getAsString());
      var continuation = initial.admission().continuation();
      if (continuation == null) throw new IOException("SOURCE_UNAVAILABLE: recording has no continuation owner");
      opened = true;
      // Only initially selected requirements participate in this immutable lease. A newly
      // usable placeholder needs a new binding/job; it cannot expand a peer's opened query.
      var pinned = VisualizationContracts.blank(); var required = new JsonArray();
      for (var item : configuration.getAsJsonArray("requirements")) if (signatures.has(item.getAsJsonObject().get("id").getAsString())) required.add(item.deepCopy());
      pinned.add("requirements", required);
      var followSource = new Continuation(bindings, source, pinned, signatures, initial, continuation, alive);
      initial = null; OrdinalFollow engine;
      try { engine = new OrdinalFollow(epoch, followSource, queries, budget, 4096, PAGE_BYTES, OrdinalFollow.FrontierScope.QUERIES); }
      finally { followSource.closeUnused(); }
      try { checkCurrent(); return engine; }
      catch (IOException | RuntimeException | Error failure) { engine.close(); throw failure; }
    }
    @Override public void close() throws IOException { if (initial != null) { var old = initial; initial = null; old.close(); } }
  }
  /** A lease retains only the selected intent and compact signatures, not the detached binding
   * answer, original panels, working-state service, or expired preparation. */
  private static final class Continuation implements OrdinalFollow.Source {
    private final RecordingBindings bindings;
    private final JsonObject source, pinned, signatures;
    private final OrdinalFollow.Source continuation;
    private final BooleanSupplier alive;
    private RecordingBindings.Bound first;
    Continuation(RecordingBindings bindings, JsonObject source, JsonObject pinned, JsonObject signatures,
        RecordingBindings.Bound first, OrdinalFollow.Source continuation, BooleanSupplier alive) {
      this.bindings = bindings; this.source = source; this.pinned = pinned; this.signatures = signatures;
      this.first = first; this.continuation = continuation; this.alive = alive;
    }
    @Override public OrdinalFollow.Prefix open() throws IOException {
      checkAlive(alive);
      if (first != null) {
        first.admission().check(); var prefix = first.admission().prefix(); first = null; return prefix;
      }
      var next = bindings.admit(source, pinned, () -> {
        var prefix = continuation.open();
        return new RecordedSources.Admission(prefix, continuation::authorize, continuation);
      });
      boolean transferred = false;
      try {
        for (var item : next.result().getAsJsonArray("bindings")) {
          var binding = item.getAsJsonObject();
          if (!binding.get("state").getAsString().equals("resolved")
              || !signature(binding).equals(signatures.get(binding.get("requirement_id").getAsString())))
            throw new IOException("BINDING_CHANGED: selected declaration, schema or resolver evidence changed; rebind the same view explicitly");
        }
        transferred = true; return next.admission().prefix();
      } finally { if (!transferred) next.close(); }
    }
    @Override public void authorize() throws IOException { checkAlive(alive); continuation.authorize(); }
    @Override public JsonObject clock(OrdinalFollow.Prefix prefix) throws IOException {
      return RecordingInventory.summary(prefix).getAsJsonObject("clock");
    }
    @Override public boolean awaitChange(Duration wait, BooleanSupplier cancelled) throws IOException {
      return continuation.awaitChange(wait, () -> cancelled.getAsBoolean() || !alive.getAsBoolean());
    }
    void closeUnused() throws IOException { if (first != null) { var retired = first; first = null; retired.close(); } }
  }

}
