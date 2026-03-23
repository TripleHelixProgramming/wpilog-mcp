package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionManagerTest {
  private SessionManager manager;

  @BeforeEach
  void setUp() {
    manager = new SessionManager();
  }

  @Test
  @DisplayName("createSession returns a session with a unique ID")
  void createSession() {
    var s1 = manager.createSession();
    var s2 = manager.createSession();

    assertNotNull(s1.getId());
    assertNotNull(s2.getId());
    assertNotEquals(s1.getId(), s2.getId());
    assertEquals(2, manager.size());
  }

  @Test
  @DisplayName("getSession returns the session by ID")
  void getSession() {
    var session = manager.createSession();
    var retrieved = manager.getSession(session.getId());

    assertNotNull(retrieved);
    assertSame(session, retrieved);
  }

  @Test
  @DisplayName("getSession returns null for unknown ID")
  void getSessionUnknown() {
    assertNull(manager.getSession("nonexistent"));
  }

  @Test
  @DisplayName("getSession returns null for null ID")
  void getSessionNull() {
    assertNull(manager.getSession(null));
  }

  @Test
  @DisplayName("removeSession removes and returns the session")
  void removeSession() {
    var session = manager.createSession();
    var removed = manager.removeSession(session.getId());

    assertSame(session, removed);
    assertEquals(0, manager.size());
    assertNull(manager.getSession(session.getId()));
  }

  @Test
  @DisplayName("removeSession returns null for unknown ID")
  void removeSessionUnknown() {
    assertNull(manager.removeSession("nonexistent"));
  }

  @Test
  @DisplayName("cleanupExpired removes sessions older than max idle")
  void cleanupExpired() throws InterruptedException {
    manager.createSession();
    manager.createSession();
    assertEquals(2, manager.size());

    // Sessions were just created, so zero-duration cleanup should remove them
    // (their last access is in the past relative to "now + 0")
    Thread.sleep(10); // Ensure some time has passed
    int cleaned = manager.cleanupExpired(Duration.ZERO);
    assertEquals(2, cleaned);
    assertEquals(0, manager.size());
  }

  @Test
  @DisplayName("cleanupExpired keeps recent sessions")
  void cleanupExpiredKeepsRecent() {
    manager.createSession();
    int cleaned = manager.cleanupExpired(Duration.ofHours(1));
    assertEquals(0, cleaned);
    assertEquals(1, manager.size());
  }
}
