/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages MCP client sessions.
 *
 * <p>Thread-safe. Sessions are created on {@code initialize} and removed on {@code DELETE} or
 * expiry.
 */
public class SessionManager {
  private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

  private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

  private final Consumer<String> onRemoval;

  public SessionManager() {
    this(id -> {});
  }

  public SessionManager(Consumer<String> onRemoval) {
    this.onRemoval = onRemoval;
  }

  /** Lease mutation and removal share the map's per-session lock; neither may perform I/O. */
  public boolean update(String id, Consumer<McpSession> action) {
    if (id == null) return false;
    return sessions.computeIfPresent(id, (key, session) -> {
      session.touch();
      action.accept(session);
      return session;
    }) != null;
  }

  public McpSession createSession() {
    var session = new McpSession();
    sessions.put(session.getId(), session);
    logger.info("Created session: {}", session.getId());
    return session;
  }

  public McpSession getSession(String id) {
    if (id == null) return null;
    return sessions.computeIfPresent(id, (key, session) -> {
      session.touch();
      return session;
    });
  }

  public McpSession removeSession(String id) {
    var removed = new McpSession[1];
    sessions.computeIfPresent(id, (key, session) -> {
      removed[0] = session;
      onRemoval.accept(key);
      return null;
    });
    if (removed[0] != null) {
      logger.info("Removed session: {}", id);
    }
    return removed[0];
  }

  public int cleanupExpired(Duration maxIdle) {
    return cleanupExpired(maxIdle, Instant.now());
  }

  int cleanupExpired(Duration maxIdle, Instant now) {
    var cutoff = now.minus(maxIdle);
    var count = new AtomicInteger();
    sessions.keySet().forEach(id -> sessions.computeIfPresent(id, (key, session) -> {
      if (session.getLastAccessedAt().isBefore(cutoff)) {
        onRemoval.accept(key);
        count.incrementAndGet();
        return null;
      }
      return session;
    }));
    if (count.get() > 0) {
      logger.info("Cleaned up {} expired session(s)", count.get());
    }
    return count.get();
  }

  public void clear() {
    sessions.keySet().forEach(this::removeSession);
  }

  public int size() {
    return sessions.size();
  }
}
