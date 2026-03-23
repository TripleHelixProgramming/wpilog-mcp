package org.triplehelix.wpilogmcp.mcp;

/**
 * Thread-local holder for the current MCP session.
 *
 * <p>Set by the transport layer before tool execution and cleared in a finally block. Tools access
 * the session indirectly via {@link org.triplehelix.wpilogmcp.log.LogManager}, which checks this
 * context to resolve the per-session active log.
 */
public final class SessionContext {
  private static final ThreadLocal<McpSession> CURRENT = new ThreadLocal<>();

  private SessionContext() {}

  public static McpSession current() {
    return CURRENT.get();
  }

  public static void set(McpSession session) {
    CURRENT.set(session);
  }

  public static void clear() {
    CURRENT.remove();
  }
}
