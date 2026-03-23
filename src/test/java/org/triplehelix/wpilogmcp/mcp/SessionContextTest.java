package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionContextTest {

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  @DisplayName("current() returns null when no session is set")
  void currentReturnsNull() {
    assertNull(SessionContext.current());
  }

  @Test
  @DisplayName("set/current/clear lifecycle works")
  void setCurrentClear() {
    var session = new McpSession();
    SessionContext.set(session);
    assertSame(session, SessionContext.current());

    SessionContext.clear();
    assertNull(SessionContext.current());
  }

  @Test
  @DisplayName("sessions are thread-local (not shared between threads)")
  void threadLocal() throws InterruptedException {
    var session1 = new McpSession();
    SessionContext.set(session1);

    var holder = new McpSession[1];
    var thread = new Thread(() -> holder[0] = SessionContext.current());
    thread.start();
    thread.join();

    // Other thread should not see this thread's session
    assertNull(holder[0]);
    // This thread still sees its session
    assertSame(session1, SessionContext.current());
  }
}
