package org.triplehelix.wpilogmcp.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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

  public McpSession createSession() {
    var session = new McpSession();
    sessions.put(session.getId(), session);
    logger.info("Created session: {}", session.getId());
    return session;
  }

  public McpSession getSession(String id) {
    if (id == null) return null;
    var session = sessions.get(id);
    if (session != null) {
      session.touch();
    }
    return session;
  }

  public McpSession removeSession(String id) {
    var session = sessions.remove(id);
    if (session != null) {
      logger.info("Removed session: {}", id);
    }
    return session;
  }

  public int cleanupExpired(Duration maxIdle) {
    var cutoff = Instant.now().minus(maxIdle);
    // Use removeIf for atomic per-entry removal on ConcurrentHashMap, avoiding
    // a TOCTOU race where a session could be touched between filter and remove.
    var count = new int[]{0};
    sessions.entrySet().removeIf(e -> {
      if (e.getValue().getLastAccessedAt().isBefore(cutoff)) {
        count[0]++;
        return true;
      }
      return false;
    });

    if (count[0] > 0) {
      logger.info("Cleaned up {} expired session(s)", count[0]);
    }
    return count[0];
  }

  public int size() {
    return sessions.size();
  }
}
