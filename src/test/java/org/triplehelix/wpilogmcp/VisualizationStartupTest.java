/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.ChildJvms;
import org.triplehelix.wpilogmcp.harness.HarnessHttp;

/** Optional visualization recovery must not stop an otherwise usable named server. */
class VisualizationStartupTest {
  @TempDir Path temp;
  @ParameterizedTest @ValueSource(strings = {"unwritable", "symlink", "blocked"})
  void unavailableVisualizationStateStillServesHealthAndTools(String failure) throws Exception {
    var home = Files.createDirectories(temp.resolve("home")).toRealPath();
    var parent = home.resolve(".wpilog-mcp/state/visualizations");
    if (failure.equals("symlink")) {
      try { Files.createSymbolicLink(home.resolve(".wpilog-mcp"), Files.createDirectories(temp.resolve("linked")).toRealPath()); }
      catch (UnsupportedOperationException | java.io.IOException e) { assumeTrue(false, "Symlinks unavailable: " + e); }
    } else if (failure.equals("blocked")) {
      Files.createDirectories(parent.getParent()); Files.writeString(parent, "synthetic blocking file");
    } else {
      Files.createDirectories(parent);
      assumeTrue(Files.getFileStore(parent).supportsFileAttributeView("posix"));
      Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-xr-xr-x"));
    }
    try {
      if (failure.equals("unwritable")) assumeFalse(Files.isWritable(parent), "Account bypasses POSIX permissions");
      var config = Files.writeString(temp.resolve("servers.yaml"), "diskcachedisable: true\nservers:\n  fixture:\n    transport: http\n    port: 0\n");
      var err = temp.resolve("stderr.log");
      var builder = new ProcessBuilder(List.of(ChildJvms.JAVA, ChildJvms.HEADLESS,
          "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"), Main.class.getName(),
          "run", "fixture", "--config", config.toString())).directory(temp.toFile())
          .redirectOutput(temp.resolve("stdout.log").toFile()).redirectError(err.toFile());
      for (String key : List.of("WPILOG_DIR", "WPILOG_DEBUG", "WPILOG_HTTP_BIND", "WPILOG_HTTP_PATH", "TBA_API_KEY")) builder.environment().remove(key);
      var child = builder.start();
      try {
        var port = new AtomicInteger();
        var listening = java.util.regex.Pattern.compile("MCP HTTP server listening on http://127\\.0\\.0\\.1:(\\d+)/mcp");
        HarnessHttp.await("named server despite unavailable visualization state", 10, () -> {
          var messages = Files.readString(err); assertTrue(child.isAlive(), messages);
          var match = listening.matcher(messages); if (!match.find()) return false;
          port.set(Integer.parseInt(match.group(1))); return true;
        });
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
            .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var health = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("ephemeral", health.get("working_state").getAsString());
        assertTrue(health.get("working_state_reason").getAsString().contains("unavailable"));
        var client = new HarnessHttp(port.get()); client.initialize();
        var guide = client.call("get_server_guide", new JsonObject());
        assertEquals(health.get("server_instance_id"), guide.get("server_instance_id"));
        assertEquals(health.get("working_state_reason"), guide.get("working_state_reason"));
        assertTrue(Files.readString(err).contains("WARN"), "Fallback must be visible in the server log");
      } finally {
        child.destroy(); if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
      }
    } finally {
      if (failure.equals("unwritable") && Files.exists(parent)) Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
    }
  }
}
