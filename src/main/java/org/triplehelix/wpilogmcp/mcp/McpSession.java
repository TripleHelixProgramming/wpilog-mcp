package org.triplehelix.wpilogmcp.mcp;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-client MCP session state.
 *
 * <p>Each connected client gets its own session. The parsed log cache is shared
 * and logs are referenced by explicit path parameters on each tool call.
 */
public class McpSession {
  private final String id;
  private final Instant createdAt;
  private volatile Instant lastAccessedAt;

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
}
