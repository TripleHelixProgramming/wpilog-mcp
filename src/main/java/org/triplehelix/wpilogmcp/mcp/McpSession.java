package org.triplehelix.wpilogmcp.mcp;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-client MCP session state.
 *
 * <p>Each connected client gets its own session, which tracks the client's active log path
 * independently of other clients. The parsed log cache itself is shared; only the "which log is
 * active" pointer is per-session.
 */
public class McpSession {
  private final String id;
  private final Instant createdAt;
  private volatile Instant lastAccessedAt;
  private volatile String activeLogPath;

  public McpSession() {
    this.id = UUID.randomUUID().toString();
    this.createdAt = Instant.now();
    this.lastAccessedAt = this.createdAt;
  }

  public String getId() {
    return id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastAccessedAt() {
    return lastAccessedAt;
  }

  public void touch() {
    this.lastAccessedAt = Instant.now();
  }

  public String getActiveLogPath() {
    return activeLogPath;
  }

  public void setActiveLogPath(String path) {
    this.activeLogPath = path;
    touch();
  }
}
