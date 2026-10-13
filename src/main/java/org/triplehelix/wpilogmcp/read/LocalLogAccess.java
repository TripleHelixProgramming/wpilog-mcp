/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** Local ownership deliberately does not touch LogManager or StoreRegistry: constructing a reader
 * must not initialize a server, adopt strays, create directories or use someone's home cache. */
final class LocalLogAccess implements LogAccess {
  private final ReadOptions options;
  private final SecurityValidator security = new SecurityValidator();
  private final Set<LocalReadSnapshot> snapshots = new HashSet<>();
  private final SharedTimeOrders orders;
  private boolean closed;

  LocalLogAccess(ReadOptions options) throws IOException {
    this.options = options;
    this.orders = new SharedTimeOrders(new SortBudget(options.sortMemoryBytes()));
    for (var root : options.roots()) {
      var real = root.toRealPath();
      if (!Files.isDirectory(real)) throw new IOException("Read root is not a directory: " + root);
      security.addAllowedDirectory(real);
    }
  }

  @Override public ReadSnapshot open(Path path) throws IOException {
    return open(path, null, false);
  }
  LocalReadSnapshot open(Path path, LocalReadSnapshot previous, boolean following) throws IOException {
    synchronized (this) { requireOpen(); }
    try { security.validate(path); }
    catch (IOException e) { throw new ReadException(ReadProblem.Code.ACCESS_DENIED, e.getMessage(), e); }
    var real = path.toRealPath();
    security.validate(real);
    var snapshot = LocalReadSnapshot.open(this, real, options, previous, following);
    synchronized (this) {
      if (!closed) { snapshots.add(snapshot); return snapshot; }
    }
    snapshot.close();
    throw new ReadException(ReadProblem.Code.CLOSED, "Log access closed while opening " + path);
  }

  private void requireOpen() throws ReadException {
    if (closed) throw new ReadException(ReadProblem.Code.CLOSED, "Log access is closed");
  }

  synchronized void released(LocalReadSnapshot snapshot) { snapshots.remove(snapshot); }

  SharedTimeOrders orders() { return orders; }

  @Override public void close() throws IOException {
    List<LocalReadSnapshot> retiring;
    synchronized (this) {
      if (closed) return;
      closed = true; retiring = List.copyOf(snapshots); snapshots.clear();
    }
    IOException failure = null;
    for (var snapshot : retiring) {
      try { snapshot.close(); }
      catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
    }
    if (failure != null) throw failure;
  }
}
