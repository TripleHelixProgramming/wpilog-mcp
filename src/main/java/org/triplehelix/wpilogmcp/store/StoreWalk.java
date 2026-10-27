/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Every entry under a folder, depth first, without following links, for a walk that takes no
 * lock while the store's writers keep working. An entry can vanish once it is listed: every
 * manifest write creates a temporary file and renames it over the manifest. {@link Files#walk}
 * then throws, so publication recovery failed on a busy store, and its caller could not tell a
 * renamed temporary file from a moved folder. Here a vanished entry comes back without
 * attributes and its caller decides what the absence means; any other failure ends the walk.
 * The probe runs between listing an entry and reading it, where such a rename lands.
 */
final class StoreWalk implements Closeable {
  record Entry(Path path, BasicFileAttributes attributes) {
    boolean vanished() { return attributes == null; }
    boolean directory() { return attributes != null && attributes.isDirectory(); }
  }
  @FunctionalInterface interface Probe { void listed(Path entry) throws IOException; }

  private final ArrayDeque<DirectoryStream<Path>> listings = new ArrayDeque<>();
  private final ArrayDeque<Iterator<Path>> remaining = new ArrayDeque<>();
  private final Probe probe;

  StoreWalk(Path root, Probe probe) throws IOException {
    this.probe = probe;
    open(root);
  }

  /** The next entry, a folder before what it holds, or null once the walk is complete. */
  Entry next() throws IOException {
    while (!remaining.isEmpty()) {
      Path entry;
      try {
        if (!remaining.peek().hasNext()) { remaining.pop(); listings.pop().close(); continue; }
        entry = remaining.peek().next();
      } catch (DirectoryIteratorException failed) { throw failed.getCause(); }
      probe.listed(entry);
      try {
        var attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isDirectory()) open(entry);
        return new Entry(entry, attributes);
      } catch (NoSuchFileException vanished) {
        return new Entry(entry, null);
      }
    }
    return null;
  }

  private void open(Path directory) throws IOException {
    var listing = Files.newDirectoryStream(directory);
    listings.push(listing);
    remaining.push(listing.iterator());
  }

  @Override public void close() throws IOException {
    IOException failure = null;
    while (!listings.isEmpty()) {
      try { listings.pop().close(); } catch (IOException e) { if (failure == null) failure = e; }
    }
    remaining.clear();
    if (failure != null) throw failure;
  }
}
