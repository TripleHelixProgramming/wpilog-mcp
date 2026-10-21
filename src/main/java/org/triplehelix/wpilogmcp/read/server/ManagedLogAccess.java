/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.read.LogAccess;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.SortBudget;
import org.triplehelix.wpilogmcp.read.SharedTimeOrders;

/** Server admission shares the manager's leases, catalog aliases and writer-owned live prefix.
 * It never reopens a cached file. The local reader remains usable without these server services. */
public final class ManagedLogAccess implements LogAccess {
  private final LogManager manager;
  private final SharedTimeOrders orders = new SharedTimeOrders(new SortBudget(64L << 20));
  private final Set<ManagedReadSnapshot> snapshots = new HashSet<>();
  private boolean closed;

  public ManagedLogAccess(LogManager manager) { this.manager = java.util.Objects.requireNonNull(manager); }

  @Override public ManagedReadSnapshot open(Path path) throws IOException { return acquire(path.toString(), false, null); }

  /** Legacy entry points keep their original path spelling and manager refusal messages. */
  public ManagedReadSnapshot acquire(String path) throws IOException { return acquire(path, true, null); }
  public ManagedReadSnapshot acquire(String path, String scopeId) throws IOException { return acquire(path, true, scopeId); }

  private ManagedReadSnapshot acquire(String path, boolean callerOwnsUse, String scopeId) throws IOException {
    synchronized (this) {
      if (closed) throw new ReadException(ReadProblem.Code.CLOSED, "Managed log access is closed");
    }
    var requested = Path.of(path);
    SourceScopes.Scope scope = null;
    if (scopeId != null) {
      scope = manager.sourceScopes().select(scopeId);
      requested = requested.isAbsolute() ? requested : scope.root().resolve(requested);
      manager.sourceScopes().recheckPath(requested, scope);
      path = requested.toString();
    } else if (!requested.isAbsolute()) {
      var choices = manager.sourceScopes().snapshot();
      if (choices.size() > 1) throw new SourceScopes.Failure("SOURCE_SELECTION_REQUIRED", "A relative file path requires an explicit source scope", choices);
    }
    var use = callerOwnsUse ? manager.acquire(path) : manager.acquireReadOnly(path);
    ManagedReadSnapshot snapshot;
    try {
      if (scope != null) manager.sourceScopes().recheckPath(Path.of(use.log().path()), scope);
      snapshot = new ManagedReadSnapshot(this, use, orders, callerOwnsUse, scope, use.replica());
    }
    catch (Throwable failure) { use.close(); throw failure; }
    synchronized (this) {
      if (!closed) { snapshots.add(snapshot); return snapshot; }
    }
    snapshot.close();
    throw new ReadException(ReadProblem.Code.CLOSED, "Managed log access closed while opening " + path);
  }

  void recheckScope(SourceScopes.Scope scope, Path path) throws IOException {
    if (scope != null) manager.sourceScopes().recheckPath(path, scope);
  }

  synchronized void released(ManagedReadSnapshot snapshot) { snapshots.remove(snapshot); }

  @Override public void close() throws IOException {
    List<ManagedReadSnapshot> retiring;
    synchronized (this) {
      if (closed) return;
      closed = true; retiring = List.copyOf(snapshots); snapshots.clear();
    }
    for (var snapshot : retiring) snapshot.retireFromOwner();
  }
}
