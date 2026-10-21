/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.triplehelix.wpilogmcp.log.FileSnapshot;
import org.triplehelix.wpilogmcp.store.StoreManifest.Header;

/** Immutable catalog parts precede the small atomic header. Inline format-2 stores remain
 * readable, but new publications cannot fill the header and block capture. Cache hits retain
 * parsed facts only: grants and every referenced filesystem boundary are checked on each use.
 * The cache's weight is an accounting estimate (four times encoded bytes), not measured heap. */
final class ReplicaCatalogFiles {
  /** Capacity admission failed before publication, so an import may discard its owned stage. */
  static final class CapacityRefused extends IOException {
    CapacityRefused(String message) { super(message); }
    CapacityRefused(String message, Throwable cause) { super(message, cause); }
  }

  static final int PART_BYTES = 1 << 20, MAX_BYTES = 32 << 20, MAX_PARTS = 128;
  static final String DIRECTORY = ".replicas/catalog";
  private static final com.github.benmanes.caffeine.cache.Cache<Path, Loaded> CACHE =
      com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumWeight(128 << 20)
          .weigher((Path path, Loaded value) -> value.weight()).expireAfterAccess(java.time.Duration.ofMinutes(10)).build();
  private ReplicaCatalogFiles() {}
  private record Reference(String index) {}
  private record Part(String hash, int bytes, int count) {}
  private record Index(int schemaVersion, String epoch, long revision, int total, List<Part> parts,
      List<String> includedSessions, List<ReplicaCatalog.Exclusion> exclusions) {}
  private record Stored(int formatVersion, String createdAt, String id, List<StoreManifest.Move> moves,
      Map<String, String> addresses, boolean mirror, List<String> peers, StoreManifest.MirrorOrigin origin,
      ReplicaCatalog replicas, Reference replicaCatalog) {
    Header header(ReplicaCatalog catalog) { return new Header(formatVersion, createdAt, id, moves, addresses, mirror, peers, origin, catalog); }
  }
  record Loaded(Header header, Map<String, ReplicaCatalog.Entry> entries, Map<Path, FileSnapshot> boundaries, int weight, Reference reference) {
    boolean current(StoreFiles io) throws IOException {
      for (var entry : boundaries.entrySet()) {
        io.check(entry.getKey());
        if (!entry.getValue().equals(FileSnapshot.of(entry.getKey()))) return false;
      }
      return true;
    }
  }
  private record Plan(Stored header, Map<String, byte[]> files, long bytes) {}
  static long metadataBytes(Header header) throws IOException { return plan(header).bytes(); }
  static void preflight(Header header) throws IOException { plan(header); }
  static void invalidate(Path header) { CACHE.invalidate(header.toAbsolutePath().normalize()); }

  static Loaded read(StoreFiles io, Path path) throws IOException {
    path = path.toRealPath(); io.check(path);
    var cached = CACHE.getIfPresent(path);
    if (cached != null && cached.current(io)) return cached;
    // A publisher may retire an old part after we read the old header. Retry only when the
    // header changed; a missing/corrupt part of an unchanged publication must be refused.
    for (int attempt = 0; attempt < 3; attempt++) {
      var before = FileSnapshot.of(path);
      try {
        var boundaries = new LinkedHashMap<Path, FileSnapshot>();
        byte[] headerBytes = readBytes(io, path, StoreFiles.MAX_MANIFEST_BYTES, boundaries);
        StoreFiles.headerReads.incrementAndGet();
        var stored = StoreFiles.JSON.fromJson(new String(headerBytes, StandardCharsets.UTF_8), Stored.class);
        if (stored == null) throw new IOException("Empty store header");
        var catalog = stored.replicas(); long bytes = headerBytes.length;
        if (stored.replicaCatalog() != null) {
          if (catalog != null) throw new IOException("Ambiguous replica catalog publication");
          var root = io.root(); var ref = stored.replicaCatalog(); hash(ref.index());
          byte[] indexBytes = readPart(io, root, "index-" + ref.index(), StoreFiles.MAX_MANIFEST_BYTES, boundaries);
          if (!ref.index().equals(digest(indexBytes))) throw new IOException("Replica catalog index hash changed");
          var index = StoreFiles.JSON.fromJson(new String(indexBytes, StandardCharsets.UTF_8), Index.class);
          if (index == null || index.schemaVersion() != 1 || index.parts() == null || index.parts().size() > MAX_PARTS
              || index.total() < 0 || index.total() > ReplicaCatalog.MAX_FILES) throw new IOException("Invalid replica catalog index");
          bytes += indexBytes.length; var entries = new ArrayList<ReplicaCatalog.Entry>();
          for (var part : index.parts()) {
            hash(part.hash());
            if (part.bytes() < 2 || part.bytes() > PART_BYTES || part.count() < 1 || part.count() > 256
                || entries.size() + part.count() > index.total() || (bytes += part.bytes()) > MAX_BYTES)
              throw new IOException("RESOURCE_LIMIT: replica catalog part or aggregate bound exceeded");
            byte[] data = readPart(io, root, "part-" + part.hash(), part.bytes(), boundaries);
            if (data.length != part.bytes() || !part.hash().equals(digest(data))) throw new IOException("Replica catalog part hash changed");
            var rows = StoreFiles.JSON.fromJson(new String(data, StandardCharsets.UTF_8), ReplicaCatalog.Entry[].class);
            if (rows == null || rows.length != part.count()) throw new IOException("Replica catalog count changed");
            entries.addAll(List.of(rows));
          }
          if (entries.size() != index.total()) throw new IOException("Incomplete replica catalog");
          catalog = new ReplicaCatalog(1, index.epoch(), index.revision(), entries, index.includedSessions(), index.exclusions());
        }
        var header = stored.header(catalog); var entries = new HashMap<String, ReplicaCatalog.Entry>();
        if (catalog != null) for (var entry : catalog.files()) entries.put(entry.path(), entry);
        var loaded = new Loaded(header, Map.copyOf(entries), Map.copyOf(boundaries), (int) Math.min(Integer.MAX_VALUE, bytes * 4 + 4096), stored.replicaCatalog());
        if (loaded.current(io)) { CACHE.put(path, loaded); return loaded; }
      } catch (IOException | RuntimeException failure) {
        if (Objects.equals(before, FileSnapshot.of(path))) {
          if (failure instanceof IOException ioFailure) throw ioFailure;
          throw new IOException("Invalid manifest " + path + ": " + failure.getMessage(), failure);
        }
      }
    }
    throw new IOException("SOURCE_CHANGED: catalog changed during admission; retry");
  }
  private static byte[] readPart(StoreFiles io, Path root, String name, long bound, Map<Path, FileSnapshot> boundaries) throws IOException {
    return readBytes(io, io.resolve(root, DIRECTORY + "/" + name + ".json"), bound, boundaries);
  }
  private static byte[] readBytes(StoreFiles io, Path path, long limit, Map<Path, FileSnapshot> boundaries) throws IOException {
    io.check(path); var before = FileSnapshot.of(path);
    if (before == null || before.size() > limit) throw new IOException("Missing or oversized catalog metadata");
    byte[] bytes;
    try (var input = Files.newInputStream(path)) { bytes = input.readNBytes((int) limit + 1); }
    if (bytes.length > limit || !before.equals(FileSnapshot.of(path))) throw new IOException("SOURCE_CHANGED: catalog metadata changed");
    boundaries.put(path, before); return bytes;
  }
  static Object store(StoreFiles io, Header header) throws IOException {
    var currentPath = io.root().resolve("store.json");
    if (header.replicas() != null && Files.exists(currentPath)) {
      var current = read(io, currentPath);
      if (current.reference() != null && header.replicas().equals(current.header().replicas()))
        return new Stored(header.formatVersion(), header.createdAt(), header.id(), header.moves(), header.addresses(), header.mirror(),
            header.peers(), header.origin(), null, current.reference());
    }
    var plan = plan(header);
    if (header.replicas() == null) return header;
    Path directory = io.check(io.root().resolve(DIRECTORY)); Files.createDirectories(directory);
    // Before writing, discard only engine-named orphan parts, preserving the current reference.
    // After publication the same sweep removes the old generation. Readers retry on header change.
    Set<String> retained = references(io); retained.addAll(plan.files().keySet());
    prune(io, retained);
    for (var entry : plan.files().entrySet()) {
      var path = io.resolve(directory, entry.getKey());
      if (Files.exists(path)) {
        if (Files.size(path) != entry.getValue().length || !StoreFiles.hash(path).equals(digest(entry.getValue())))
          throw new IOException("CORRUPT_CATALOG: immutable catalog part changed");
      } else io.writeBytes(path, entry.getValue(), true);
    }
    return plan.header();
  }
  static void published(StoreFiles io) throws IOException { prune(io, references(io)); }
  private static Set<String> references(StoreFiles io) throws IOException {
    var result = new HashSet<String>(); Path path = io.root().resolve("store.json");
    if (!Files.exists(path)) return result;
    for (var header : List.of(path, io.root().resolve(".replicas/upgrade.json"))) {
      if (!Files.exists(header)) continue;
      var loaded = read(io, header);
      for (var member : loaded.boundaries().keySet()) if (member.getParent().equals(io.root().resolve(DIRECTORY))) result.add(member.getFileName().toString());
    }
    return result;
  }
  private static void prune(StoreFiles io, Set<String> retained) throws IOException {
    Path directory = io.check(io.root().resolve(DIRECTORY)); if (!Files.isDirectory(directory)) return;
    try (var files = Files.newDirectoryStream(directory)) {
      int inspected = 0;
      for (var path : files) {
        if (++inspected > 3 * MAX_PARTS + 6) throw new IOException("RESOURCE_LIMIT: too many catalog metadata files; inspect orphan publications");
        if (ownedName(path.getFileName().toString()) && !retained.contains(path.getFileName().toString())) Files.delete(io.check(path));
      }
    }
  }
  static boolean owned(Path root, Path path) {
    return path.getParent().equals(root.resolve(DIRECTORY)) && ownedName(path.getFileName().toString());
  }
  private static boolean ownedName(String name) { return name.matches("(?:part|index)-[0-9a-f]{64}\\.json"); }
  private static Plan plan(Header header) throws IOException {
    var catalog = header.replicas();
    if (catalog == null) return new Plan(null, Map.of(), encode(header).length);
    var files = new LinkedHashMap<String, byte[]>(); var parts = new ArrayList<Part>();
    var chunk = new ByteArrayOutputStream(); chunk.write('['); int rows = 0; long bytes = 0;
    for (var entry : catalog.files()) {
      byte[] row = encode(entry);
      if (row.length + 2 > PART_BYTES) throw new CapacityRefused("RESOURCE_LIMIT: replica row exceeds 1 MiB");
      if (rows == 256 || chunk.size() + row.length + 2 > PART_BYTES) {
        bytes += finish(files, parts, chunk, rows); chunk = new ByteArrayOutputStream(); chunk.write('['); rows = 0;
      }
      if (rows++ > 0) chunk.write(','); chunk.writeBytes(row);
      if (bytes + chunk.size() > MAX_BYTES) throw new CapacityRefused("RESOURCE_LIMIT: replica catalog exceeds 32 MiB");
    }
    if (rows > 0) bytes += finish(files, parts, chunk, rows);
    if (parts.size() > MAX_PARTS) throw new CapacityRefused("RESOURCE_LIMIT: replica catalog exceeds 128 parts");
    byte[] index = encode(new Index(1, catalog.epoch(), catalog.revision(), catalog.files().size(), parts, catalog.includedSessions(), catalog.exclusions()));
    if (index.length > StoreFiles.MAX_MANIFEST_BYTES) throw new CapacityRefused("RESOURCE_LIMIT: replica catalog index exceeds 4 MiB");
    String hash = digest(index); files.put("index-" + hash + ".json", index);
    var stored = new Stored(header.formatVersion(), header.createdAt(), header.id(), header.moves(), header.addresses(), header.mirror(),
        header.peers(), header.origin(), null, new Reference(hash));
    byte[] small = encode(stored);
    if (small.length > StoreFiles.MAX_MANIFEST_BYTES || (bytes += index.length + small.length) > MAX_BYTES)
      throw new CapacityRefused("RESOURCE_LIMIT: replica metadata exceeds its header or 32 MiB aggregate bound");
    return new Plan(stored, files, bytes);
  }
  private static int finish(Map<String, byte[]> files, List<Part> parts, ByteArrayOutputStream chunk, int rows) {
    chunk.write(']'); byte[] data = chunk.toByteArray(); String hash = digest(data);
    parts.add(new Part(hash, data.length, rows)); files.put("part-" + hash + ".json", data); return data.length;
  }
  private static byte[] encode(Object value) { return StoreFiles.JSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
  private static void hash(String hash) throws IOException {
    if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid replica catalog reference");
  }
  private static String digest(byte[] bytes) {
    try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }
}
