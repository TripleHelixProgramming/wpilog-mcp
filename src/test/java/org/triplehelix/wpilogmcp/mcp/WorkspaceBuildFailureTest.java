/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.visualization.ServerIdentity;

/**
 * The visualization workspace is built once, by its first caller, and every other caller waits on
 * that build. A build that fails, with an Error as much as an exception, must answer them all and
 * leave the next caller free to build again.
 */
class WorkspaceBuildFailureTest {
  @Test void anErrorBuildingTheWorkspaceReachesItsCallerAndTheNextCallBuildsAgain() throws Exception {
    var registry = new ToolRegistry(); registry.serverIdentity(ServerIdentity.ephemeral());
    var real = registry.workspaceBuild; var builds = new AtomicInteger();
    registry.workspaceBuild = identity -> {
      if (builds.incrementAndGet() == 1) throw new StackOverflowError("fixture");
      return real.build(identity);
    };
    try {
      assertEquals("fixture", assertThrows(StackOverflowError.class, registry::visualizations).getMessage());
      // Before, the failed build stayed registered and unfinished, and this call waited on it for good.
      var workspace = assertTimeoutPreemptively(Duration.ofSeconds(10), registry::visualizations);
      assertNotNull(workspace);
      assertEquals(2, builds.get());
      assertSame(workspace, registry.visualizations(), "the second build is the one kept");
    } finally { registry.closeVisualizations(); }
  }

  @Test void aCallerWaitingOnABuildThatFailsWithAnErrorIsAnswered() throws Exception {
    var registry = new ToolRegistry(); registry.serverIdentity(ServerIdentity.ephemeral());
    var real = registry.workspaceBuild; var builds = new AtomicInteger();
    var building = new CountDownLatch(1); var release = new CountDownLatch(1);
    registry.workspaceBuild = identity -> {
      if (builds.incrementAndGet() > 1) return real.build(identity);
      building.countDown();
      try { release.await(); } catch (InterruptedException e) { throw new IOException(e); }
      throw new StackOverflowError();
    };
    var builderFailure = new AtomicReference<Throwable>(); var waiterResult = new AtomicReference<Object>();
    var builder = new Thread(() -> {
      try { registry.visualizations(); } catch (Throwable e) { builderFailure.set(e); }
    }, "workspace-builder");
    var waiter = new Thread(() -> {
      try { waiterResult.set(registry.visualizations()); } catch (Throwable e) { waiterResult.set(e); }
    }, "workspace-waiter");
    builder.setDaemon(true); waiter.setDaemon(true);
    try {
      builder.start(); assertTrue(building.await(10, TimeUnit.SECONDS));
      waiter.start();
      // The waiter parks on the builder's unfinished build rather than starting one of its own.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (waiter.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(5);
      assertEquals(Thread.State.WAITING, waiter.getState());
      release.countDown();
      builder.join(10_000); waiter.join(10_000);
      // Before, only an Exception answered: the waiter stayed parked on the failed build for good.
      assertFalse(waiter.isAlive(), "the waiting caller is answered");
      assertInstanceOf(StackOverflowError.class, builderFailure.get(), "the builder still sees its own error");
      var answer = assertInstanceOf(IOException.class, waiterResult.get());
      assertEquals("Workspace unavailable: StackOverflowError", answer.getMessage());
      assertInstanceOf(StackOverflowError.class, answer.getCause());
      assertNotNull(registry.visualizations());
      assertEquals(2, builds.get());
    } finally {
      release.countDown(); waiter.interrupt(); registry.closeVisualizations();
    }
  }
}
