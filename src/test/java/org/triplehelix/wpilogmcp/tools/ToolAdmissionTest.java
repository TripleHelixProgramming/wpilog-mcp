/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.config.ClientLeases;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;

/** A synthetic log needs explicit admission even when another test has enabled lease policy. */
class ToolAdmissionTest {
  private static final class Fixture extends ToolTestBase {
    @Override protected void registerTools(ToolRegistry registry) { StatisticsTools.registerAll(registry); }
  }
  @Test void cachedMockPathsAreAdmittedOnlyForTheOwningTestUnderLeasePolicy() throws Exception {
    var manager = LogManager.getInstance(); var before = manager.getConfiguredDirectories();
    var leases = ClientLeases.getInstance(); var http = new HttpTransport(new ToolRegistry(), 0);
    http.start(); leases.replaceDirectories("tool-admission-test", List.of());
    var fixture = new Fixture(); fixture.setUpToolRegistry();
    try {
      var path = Path.of("synthetic-admission", "data.wpilog").toAbsolutePath();
      var log = new MockLogBuilder().setPath(path.toString()).addNumericEntry("/x", new double[]{1, 2}, new double[]{2, 4}).build();
      fixture.putLogInCache(log);
      var args = new com.google.gson.JsonObject(); args.addProperty("path", path.toString()); args.addProperty("name", "/x");
      var result = fixture.findTool("get_statistics").execute(args).getAsJsonObject();
      assertEquals("ok", result.get("status").getAsString(), result.toString());
      assertEquals(3., result.get("mean").getAsDouble());
    } finally {
      fixture.tearDownLogManager(); leases.remove("tool-admission-test"); http.stop();
    }
    assertFalse(manager.testIsLogLoaded(Path.of("synthetic-admission", "data.wpilog").toAbsolutePath().toString()),
        "The owning scope releases its pinned log");
    assertEquals(before, manager.getConfiguredDirectories(), "No synthetic root survives its owning test");
  }
}
