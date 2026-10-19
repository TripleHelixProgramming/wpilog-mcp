/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import org.triplehelix.wpilogmcp.log.LogFileException;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.store.StoreDoor;

/** Serving locations are choices, not search fallbacks. An origin can have several local copies;
 * neither matching content nor an offline neighbor makes one of those copies the default.
 * Directory IDs identify configured canonical roots, without writing into those directories.
 * They are locators, never grants; every actual read still uses the manager's security owner. */
public final class SourceScopes {
  public static final int MAX_SCOPES = 1024;
  public static final int MAX_PAGE = 64;
  private static final int MAX_PAGE_BYTES = 64 << 10;
  public record Scope(String id, String kind, Path root, boolean available, String reason) {
    public JsonObject describe() {
      var out = new JsonObject(); out.addProperty("scope_id", id); out.addProperty("kind", kind);
      out.addProperty("path", root.toString()); out.addProperty("available", available);
      if (reason != null) out.addProperty("reason", reason);
      return out;
    }
  }
  public static final class Failure extends LogFileException {
    private final String code;
    private final List<Scope> choices;
    private final String hint;
    public Failure(String code, String message, List<Scope> choices) {
      this(code, message, choices, null);
    }
    /** A legacy adapter needs a remedy its own argument schema accepts. */
    public Failure(String code, String message, List<Scope> choices, String hint) {
      super(code + ": " + message); this.code = code; this.choices = List.copyOf(choices); this.hint = hint;
    }
    public JsonObject result() {
      var out = page(choices, 0, MAX_PAGE); out.addProperty("status", "error"); out.addProperty("success", false);
      out.addProperty("code", code); out.addProperty("error", getMessage());
      out.addProperty("hint", hint != null ? hint : code.equals("SOURCE_SELECTION_REQUIRED")
          ? "Do not choose a source automatically: ask the user, then pass its scope_id. Use list_source_scopes for all choices."
          : "Check the selected scope with list_source_scopes; do not switch to another copy automatically.");
      return out;
    }
  }
  private final Supplier<Set<Path>> roots;
  private final StoreDoor door;
  private volatile Map<Path, Scope> remembered = Map.of();

  public SourceScopes(Supplier<Set<Path>> roots) {
    this.roots = roots;
    // Store discovery deliberately does not follow directory links. Resolve configured root
    // links here, exactly as directory scope identity and the security owner do.
    door = new StoreDoor(() -> roots.get().stream().map(SourceScopes::canonical)
        .collect(java.util.stream.Collectors.toSet()));
  }

  /** Full root walks, exposed to distinguish admission discovery from bounded access checks. */
  public long discoveryWalks() { return door.discoveryWalks(); }

  public List<Scope> snapshot() throws IOException {
    var configured = roots.get().stream().map(SourceScopes::canonical).distinct().sorted().toList();
    if (configured.size() > MAX_SCOPES) throw new Failure("RESOURCE_LIMIT", "Too many configured source scopes", List.of());
    // A newly created store changes the choice set immediately, including underneath a
    // configured directory. StoreDoor's short discovery cache is unsuitable for selection.
    door.refresh();
    var inventory = door.inventory(); var prior = remembered;
    var scopes = new LinkedHashMap<Path, Scope>();
    for (var store : inventory.stores()) scopes.put(store.root(), new Scope(store.description().id(),
        store.description().mirror() ? "mirror" : "store", store.root(), true, null));
    for (var problem : inventory.unreadable()) {
      var root = canonical(Path.of(problem.path())); var old = prior.get(root);
      String kind = old != null ? old.kind() : StoreCatalog.isStore(root) ? "store" : "directory";
      scopes.put(root, new Scope(old == null ? directoryId(root) : old.id(), kind, root, false,
          "Source discovery or store metadata is unavailable"));
    }
    for (var root : configured) if (!scopes.containsKey(root)) {
      var old = prior.get(root); boolean available = Files.isDirectory(root) && Files.isReadable(root);
      // A temporarily removed store cannot reappear as an ordinary directory with a new identity.
      if (old != null && !old.kind().equals("directory")) {
        scopes.put(root, new Scope(old.id(), old.kind(), root, false, "Selected store is unavailable"));
      } else scopes.put(root, new Scope(directoryId(root), "directory", root, available,
          available ? null : "Configured directory is unavailable"));
    }
    if (scopes.size() > MAX_SCOPES) throw new Failure("RESOURCE_LIMIT", "Too many discovered source scopes", List.of());
    remembered = Map.copyOf(scopes);
    return scopes.values().stream().sorted(Comparator.comparing(Scope::id).thenComparing(s -> s.root().toString())).toList();
  }

  /** Resolve only the selected scope, counting unavailable choices before any file filtering. */
  public Scope select(String id) throws IOException { return select(snapshot(), id); }
  private static Scope select(List<Scope> scopes, String id) throws IOException {
    if (id == null && scopes.size() > 1) throw new Failure("SOURCE_SELECTION_REQUIRED", "Select one configured source", scopes);
    var candidates = id == null ? scopes : scopes.stream().filter(s -> s.id().equals(id)).toList();
    if (candidates.size() != 1) throw new Failure("SOURCE_UNAVAILABLE",
        candidates.isEmpty() ? "Selected source is not configured" : "Several roots claim the selected scope identity", scopes);
    var selected = candidates.get(0);
    if (!selected.available()) throw new Failure("SOURCE_UNAVAILABLE", selected.reason(), scopes);
    return selected;
  }

  /** An absolute file path is an explicit local selection. Relative names cannot select among
   * roots, and a scope-qualified path must stay in that exact store/directory after aliases. */
  public Scope forPath(Path path, String id) throws IOException {
    var scopes = snapshot(); var real = canonical(path);
    if (id == null && scopes.isEmpty()) return null; // Existing unrestricted standalone mode.
    if (id == null && !path.isAbsolute() && scopes.size() > 1)
      throw new Failure("SOURCE_SELECTION_REQUIRED", "Relative paths require an explicit source scope", scopes);
    var containing = scopes.stream().filter(s -> real.startsWith(s.root())).toList();
    // A manifested store owns its subtree even when its parent was also configured for browsing.
    var stores = containing.stream().filter(s -> !s.kind().equals("directory")).toList();
    var candidates = stores.isEmpty() ? containing : stores;
    var selected = id == null ? select(candidates, null) : select(scopes, id);
    if (candidates.stream().noneMatch(s -> s.id().equals(selected.id()) && s.root().equals(selected.root())))
      throw new Failure("SOURCE_UNAVAILABLE", "File does not belong to the selected serving scope", scopes);
    return selected;
  }

  /** Revalidate an established choice without rediscovering unrelated trees. A removed store
   * must retain its identity failure, even if its directory still exists. No lock covers I/O. */
  public void recheck(Scope selected) throws IOException {
    var configured = roots.get().stream().map(SourceScopes::canonical).toList();
    boolean admitted = selected.kind().equals("directory") ? configured.contains(selected.root())
        : configured.stream().anyMatch(root -> selected.root().startsWith(root)
            && StoreCatalog.containing(selected.root()).filter(parent -> parent.startsWith(root)).isEmpty());
    if (!selected.available() || !admitted || !Files.isDirectory(selected.root()) || !Files.isReadable(selected.root())
        || !canonical(selected.root()).equals(selected.root()))
      throw unavailable("Selected source is no longer configured or readable");
    if (selected.kind().equals("directory")) {
      if (StoreCatalog.isStore(selected.root())) throw unavailable("Selected directory has become a store; select its new scope");
    } else {
      try {
        var identity = door.describe(selected.root());
        if (!identity.id().equals(selected.id()) || identity.mirror() != selected.kind().equals("mirror"))
          throw unavailable("Selected store identity changed");
      } catch (IOException failure) { throw unavailable("Selected store is unavailable: " + failure.getMessage()); }
    }
  }

  /** The selected root and each ancestor of the actual file, including an alias target, still
   * own that file. This costs path depth rather than the size of any configured tree. */
  public void recheckPath(Path path, Scope selected) throws IOException {
    recheck(selected);
    var real = canonical(path);
    if (!real.startsWith(selected.root())) throw unavailable("File left the selected serving scope");
    var containing = StoreCatalog.containing(real).filter(root -> root.startsWith(selected.root()));
    if (selected.kind().equals("directory") ? containing.isPresent() : !containing.filter(selected.root()::equals).isPresent())
      throw unavailable("File does not belong to the selected serving scope");
  }

  private Failure unavailable(String message) {
    var choices = remembered.values().stream().sorted(Comparator.comparing(Scope::id).thenComparing(s -> s.root().toString())).toList();
    return new Failure("SOURCE_UNAVAILABLE", message, choices);
  }

  public JsonObject describe(int offset, int limit) throws IOException { return page(snapshot(), offset, limit); }
  public JsonObject describe(JsonObject request) throws IOException {
    for (var key : request.keySet()) if (!Set.of("api_version", "offset", "limit").contains(key))
      throw new IllegalArgumentException("Unknown source scope argument " + key);
    return describe(integer(request, "offset", 0), integer(request, "limit", MAX_PAGE));
  }
  private static int integer(JsonObject request, String key, int fallback) {
    if (!request.has(key)) return fallback;
    var value = request.get(key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException(key + " must be an integer");
    try { return value.getAsBigDecimal().intValueExact(); }
    catch (ArithmeticException invalid) { throw new IllegalArgumentException(key + " must be an integer in range"); }
  }
  private static JsonObject page(List<Scope> scopes, int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > MAX_PAGE) throw new IllegalArgumentException("Source scope offset must be nonnegative and limit must be 1..64");
    var out = new JsonObject(); var rows = new JsonArray(); int end = Math.min(scopes.size(), offset);
    int bytes = 1024;
    while (end < scopes.size() && rows.size() < limit) {
      var row = scopes.get(end).describe(); int cost = row.toString().getBytes(StandardCharsets.UTF_8).length + 1;
      if (bytes + cost > MAX_PAGE_BYTES) break;
      rows.add(row); bytes += cost; end++;
    }
    if (end < scopes.size() && rows.isEmpty()) throw new IllegalArgumentException("Source descriptor exceeds the encoded page budget");
    out.add("source_scopes", rows); out.addProperty("total", scopes.size()); out.addProperty("returned", rows.size());
    out.addProperty("offset", offset); out.addProperty("has_more", end < scopes.size());
    if (end < scopes.size()) out.addProperty("next_offset", end);
    var limits = new JsonObject(); var cap = new JsonObject(); cap.addProperty("total", scopes.size());
    cap.addProperty("returned", rows.size()); cap.addProperty("limit", limit); limits.add("source_scopes", cap); out.add("limits", limits);
    return out;
  }
  private static String directoryId(Path root) {
    return UUID.nameUUIDFromBytes(("wpilog-directory-scope\n" + root).getBytes(StandardCharsets.UTF_8)).toString();
  }
  private static Path canonical(Path path) {
    try { return path.toRealPath(); }
    catch (IOException absent) { return path.toAbsolutePath().normalize(); }
  }
}
