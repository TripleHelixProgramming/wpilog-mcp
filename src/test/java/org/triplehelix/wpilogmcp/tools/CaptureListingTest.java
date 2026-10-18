/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.store.StoreJson;
import org.triplehelix.wpilogmcp.store.StoreManifest.*;

/** Session liveness is shared by rollover members; only the manifest names the current file. */
class CaptureListingTest extends ToolTestBase {
  @Override protected void registerTools(ToolRegistry registry) { CoreTools.registerAll(registry); }

  @Test void rolloverListingNamesTheOpenMemberRatherThanSortingItsFiles(@TempDir Path temp) throws Exception {
    var root = temp.toRealPath().resolve("store");
    var first = SystemSessionFixture.create(root, "rollover", false, 42);
    var manifest = first.resolveSibling("session.json");
    var session = StoreJson.JSON.fromJson(Files.readString(manifest), Session.class);
    var file = session.files().get(0);
    var second = Files.copy(first, first.resolveSibling("capture-2.wpilog"));
    var current = Files.copy(first, first.resolveSibling("capture-3.wpilog"));
    var closed = new LogFile(second.getFileName().toString(), file.sha256(), file.sizeBytes(), file.kind(),
        file.provenance(), true, 10, 20, session.startedAt(), session.endedAt(), "pit_clock", false, null);
    var open = new OpenCapture(current.getFileName().toString(), file.provenance(), Files.size(current), 10, 20);
    var rollover = new Session(session.id(), session.startedAt(), session.endedAt(), session.startBasis(),
        null, null, null, null, List.of(file, closed), open);
    SystemSessionFixture.write(manifest, rollover);
    var directory = LogDirectory.getInstance(); var previous = directory.getLogDirectories();
    var manager = org.triplehelix.wpilogmcp.log.LogManager.getInstance(); var allowed = manager.getAllowedDirectories();
    try {
      directory.setLogDirectory(root.toString()); manager.addAllowedDirectory(root);
      var result = findTool("list_available_logs").execute(new JsonObject()).getAsJsonObject();
      assertEquals("ok", result.get("status").getAsString(), result::toString);
      var logs = result.getAsJsonArray("logs"); assertEquals(3, logs.size(), result::toString);
      for (var entry : logs) {
        var facts = entry.getAsJsonObject().getAsJsonObject("session");
        assertTrue(facts.get("open").getAsBoolean());
        assertTrue(facts.has("open_capture"), "every captured member must name the current file");
        assertEquals(current.toString(), facts.get("open_capture").getAsString());
      }
      // Once finalized, the field must not advertise a stale writer.
      SystemSessionFixture.write(manifest, new Session(session.id(), session.startedAt(), session.endedAt(),
          session.startBasis(), null, null, null, null, List.of(file, closed)));
      directory.clearCache();
      for (var entry : findTool("list_available_logs").execute(new JsonObject()).getAsJsonObject().getAsJsonArray("logs")) {
        var facts = entry.getAsJsonObject().getAsJsonObject("session");
        if (facts == null) continue;
        assertFalse(facts.get("open").getAsBoolean()); assertFalse(facts.has("open_capture"));
      }
    } finally {
      directory.setLogDirectories(previous.stream().map(Path::toString).toList()); directory.clearCache();
      manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
    }
  }
}
