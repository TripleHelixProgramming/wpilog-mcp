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

  // ==================== Concurrency Tests ====================

  @Test
  @DisplayName("concurrent session creation produces unique sessions")
  void concurrentSessionCreation() throws InterruptedException {
    int threadCount = 20;
    var sessions = java.util.Collections.synchronizedList(new java.util.ArrayList<McpSession>());
    var errors = new java.util.concurrent.atomic.AtomicInteger(0);
    var barrier = new java.util.concurrent.CyclicBarrier(threadCount);

    var threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      threads[i] = new Thread(() -> {
        try {
          barrier.await(); // All threads start simultaneously
          sessions.add(manager.createSession());
        } catch (Exception e) {
          errors.incrementAndGet();
        }
      });
    }
    for (var t : threads) t.start();
    for (var t : threads) t.join();

    assertEquals(0, errors.get(), "No errors during concurrent creation");
    assertEquals(threadCount, manager.size(), "All sessions should be created");

    // Verify all IDs are unique
    var ids = sessions.stream().map(McpSession::getId).distinct().count();
    assertEquals(threadCount, ids, "All session IDs should be unique");
  }

  @Test
  @DisplayName("concurrent get and touch does not corrupt sessions")
  void concurrentGetAndTouch() throws InterruptedException {
    // Create sessions first
    var sessionIds = new java.util.ArrayList<String>();
    for (int i = 0; i < 10; i++) {
      sessionIds.add(manager.createSession().getId());
    }

    int threadCount = 20;
    var errors = new java.util.concurrent.atomic.AtomicInteger(0);
    var threads = new Thread[threadCount];

    for (int i = 0; i < threadCount; i++) {
      final int idx = i;
      threads[i] = new Thread(() -> {
        try {
          for (int j = 0; j < 100; j++) {
            String id = sessionIds.get((idx + j) % sessionIds.size());
            var session = manager.getSession(id);
            assertNotNull(session, "Session should exist: " + id);
          }
        } catch (Exception e) {
          errors.incrementAndGet();
        }
      });
    }
    for (var t : threads) t.start();
    for (var t : threads) t.join();

    assertEquals(0, errors.get(), "Concurrent get/touch should not throw");
    assertEquals(10, manager.size(), "All sessions should still exist");
  }

  @Test
  @DisplayName("concurrent cleanup and access does not corrupt state")
  void concurrentCleanupAndAccess() throws InterruptedException {
    // Create sessions
    for (int i = 0; i < 20; i++) {
      manager.createSession();
    }

    int threadCount = 10;
    var errors = new java.util.concurrent.atomic.AtomicInteger(0);
    var threads = new Thread[threadCount];

    // Half the threads create sessions, half clean up
    for (int i = 0; i < threadCount; i++) {
      final boolean isCleanup = i % 2 == 0;
      threads[i] = new Thread(() -> {
        try {
          for (int j = 0; j < 50; j++) {
            if (isCleanup) {
              manager.cleanupExpired(Duration.ofHours(1)); // Won't expire recent sessions
            } else {
              manager.createSession();
            }
          }
        } catch (Exception e) {
          errors.incrementAndGet();
        }
      });
    }
    for (var t : threads) t.start();
    for (var t : threads) t.join();

    assertEquals(0, errors.get(), "Concurrent create + cleanup should not throw");
    assertTrue(manager.size() > 0, "Some sessions should remain");
  }
}
