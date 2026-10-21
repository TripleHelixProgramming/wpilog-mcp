/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Rechecking directory facts is cheaper than reopening every unchanged listing. Store headers
 * are still checked directly on every traversal, so a new store in an existing directory owns
 * its subtree immediately. This cache is read-only and performs no I/O under a shared lock.
 * Two seconds conservatively covers common coarse local mtimes; a one-minute forced refresh
 * bounds delayed remote attributes. Those policies are not a universal filesystem guarantee. */
final class DirectoryListings {
  static final long RECENT_MILLIS = 2_000, MAX_AGE_MILLIS = 60_000;
  private record Stamp(FileTime modified, Object key, FileTime created, long size) {
    static Stamp of(BasicFileAttributes attrs) { return new Stamp(attrs.lastModifiedTime(), attrs.fileKey(), attrs.creationTime(), attrs.size()); }
  }
  private record Listing(Stamp stamp, List<Path> children, long listedAt, int weight) {}
  @FunctionalInterface interface Opener {
    java.nio.file.DirectoryStream<Path> open(Path directory) throws IOException;
  }
  private final Clock clock;
  private final Opener opener;
  private final Consumer<Path> read;
  private final com.github.benmanes.caffeine.cache.Cache<Path,Listing> cached =
      com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumWeight(32 << 20)
          .weigher((Path key, Listing value) -> value.weight())
          .expireAfterAccess(java.time.Duration.ofMinutes(10)).build();

  DirectoryListings(Clock clock, Consumer<Path> read) { this(clock, read, Files::newDirectoryStream); }
  /** A real iterator-failure seam checks closure and partial-listing rejection without an OS race. */
  DirectoryListings(Clock clock, Consumer<Path> read, Opener opener) {
    this.clock = clock; this.read = read; this.opener = opener;
  }

  List<Path> discover(Path root) throws IOException {
    if (!Files.isDirectory(root)) return List.of();
    var pending = new ArrayDeque<Path>(); pending.add(root.toRealPath());
    var stores = new ArrayList<Path>();
    long pendingBytes = 128 + pending.getFirst().toString().length() * 2L;
    while (!pending.isEmpty()) {
      var directory = pending.removeFirst();
      pendingBytes -= 128 + directory.toString().length() * 2L;
      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
      // Do not infer a new store only from its parent's timestamp: that parent may be old.
      if (StoreCatalog.isStore(directory)) { stores.add(directory); continue; }
      var children = children(directory);
      // A wide directory must not make a new unbounded cache/traversal allocation. Falling
      // back to the established streaming walk changes cost, never the selected sources.
      if (children == null) return StoreCatalog.discover(root, read);
      for (var child : children) {
        pendingBytes += 128 + child.toString().length() * 2L;
        if (pendingBytes > 32 << 20) return StoreCatalog.discover(root, read);
        pending.addLast(child);
      }
    }
    return stores.stream().sorted().toList();
  }

  private List<Path> children(Path directory) throws IOException {
    if (!Files.isReadable(directory)) throw new java.nio.file.AccessDeniedException(directory.toString());
    for (int attempt = 0; attempt < 3; attempt++) {
      var before = Stamp.of(Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
      long now = clock.millis(); var known = cached.getIfPresent(directory);
      long age = known == null ? -1 : now - known.listedAt();
      // Clock rollback or future mtimes force a fresh listing as well.
      boolean recent = before.modified().toMillis() >= now - RECENT_MILLIS;
      if (known != null && known.stamp().equals(before) && age >= 0 && age < MAX_AGE_MILLIS && !recent)
        return known.children();
      var children = new ArrayList<Path>(); long weight = 256 + directory.toString().length() * 2L;
      try (var listing = opener.open(directory)) {
        read.accept(directory);
        for (var entry : listing) if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
          weight += 128 + entry.toString().length() * 2L;
          if (weight > 32 << 20) return null;
          children.add(entry);
        }
      } catch (java.nio.file.DirectoryIteratorException failure) {
        // DirectoryStream wraps iteration I/O; walkFileTree previously unwrapped it before
        // StoreDoor isolated this root. Retain suppressed close failures when restoring that boundary.
        var cause = failure.getCause();
        for (var suppressed : failure.getSuppressed()) if (suppressed != cause) cause.addSuppressed(suppressed);
        throw cause;
      }
      var after = Stamp.of(Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
      if (!before.equals(after)) continue;
      children.sort(null); var result = List.copyOf(children);
      if (weight <= 32 << 20) cached.put(directory, new Listing(after, result, now, (int) weight));
      return result;
    }
    throw new IOException("Directory changed during three listing attempts; retry discovery");
  }
}
