/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.visualization;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.CallerContext.Authority;
import org.triplehelix.wpilogmcp.store.*;

class RecordedSourcesTest {
  @TempDir Path temp;
  @Test void repeatedSourceReadsReuseTheCatalogAndChangedRootsReplaceIt() throws Exception {
    var logs = LogManager.getInstance(); var original = logs.getConfiguredDirectories();
    temp = temp.toRealPath(); logs.clearAllowedDirectories(); logs.addAllowedDirectory(temp);
    try (var views = new VisualizationService(ServerIdentity.ephemeral())) {
      var door = new StoreDoor(logs::getAllowedDirectories);
      try (var sources = new RecordedSources(logs, views, door)) {
        var file = temp.resolve("fixture.wpilog"); try (var writer = new WpilogWriter(file, "")) { int id = writer.start("/value", "double", "", 0); writer.append(id, 0, WpilogWriter.encodeDouble(1)); }
        var current = RecordedSources.class.getDeclaredField("current"); current.setAccessible(true);
        sources.identify(Authority.STDIO_LOCAL, file); Object first = current.get(sources);
        sources.identify(Authority.STDIO_LOCAL, file); assertSame(first, current.get(sources), "stable roots reuse one catalog owner");
        logs.stores().store(temp.resolve("store")).importPaths(new LogStore.Request(List.of(), false, null), ignored -> {}).get(); door.refresh();
        sources.identify(Authority.STDIO_LOCAL, file); Object replaced = current.get(sources); assertNotSame(first, replaced);
        sources.identify(Authority.STDIO_LOCAL, file); assertSame(replaced, current.get(sources));
        logs.clearAllowedDirectories(); logs.addAllowedDirectory(Files.createDirectory(temp.resolve("other"))); assertThrows(Exception.class, () -> sources.identify(Authority.STDIO_LOCAL, file), "reuse cannot retain withdrawn grants");
      }
    } finally { logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory); }
  }
}
