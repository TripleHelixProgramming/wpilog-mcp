/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.log.*;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.tools.*;

class WindowedConformanceTest {
  @TempDir Path temp;
  @Test void everyFixtureRecordAgreesWithTheIndependentReaderAcrossSmallWindows() throws Exception {
    for (var fixture : FixtureLogs.generateAll(FixtureLogs.defaultDirectory())) {
      var bytes = Files.readAllBytes(fixture.path()); var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      int originalExtra = header.getInt(8); int extra = 4094 - 12;
      var out = ByteBuffer.allocate(bytes.length - originalExtra + extra).order(ByteOrder.LITTLE_ENDIAN);
      out.put(bytes, 0, 8).putInt(extra).put(new byte[extra]).put(bytes, 12 + originalExtra, bytes.length - 12 - originalExtra);
      var file = temp.resolve(fixture.id() + ".wpilog"); Files.write(file, out.array());
      try (var expected = new IndependentLog.Records(fixture.path()); var actual = new ScopedLogReader(file, 4096)) {
        int from = expected.first; long at = 4094; int count = 0;
        for (var record = expected.at(from); record != null; record = expected.at(from)) {
          var value = actual.reader().getRecord(at);
          assertEquals(record.id(), Integer.toUnsignedLong(value.getEntry()), fixture.id());
          assertEquals(record.timestampUs(), value.getTimestamp(), fixture.id());
          assertArrayEquals(expected.payload(record), value.getRaw(), fixture.id());
          from = record.end(); at = actual.reader().recordEnd(at); count++;
        }
        assertEquals(expected.stopped == null, at == actual.reader().size(), fixture.id());
        if (!fixture.id().equals("empty")) assertTrue(count > 0);
        try (var log = new LazyParsedLog(file.toString(), actual.reader(), 1 << 20)) {
          assertEquals(fixture.id().equals("truncated"), log.truncated(), fixture.id());
        }
      }
    }
  }
  @Test void oneFixtureRunsTheSchemaDrivenToolSweepWithFourKiBWindows() throws Exception {
    var fixtures = FixtureLogs.generateAll(FixtureLogs.defaultDirectory());
    var fixture = fixtures.stream().filter(f -> f.id().equals("wpilib_dlm")).findFirst().orElse(fixtures.get(0));
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); var exportBefore = ExportTools.getExportDirectory();
    manager.addAllowedDirectory(fixture.path().getParent()); ExportTools.setExportDirectory(temp.toString());
    var registry = new ToolRegistry(); WpilogTools.registerAll(registry); int calls = 0;
    try (var window = MappedLogBytes.withWindowBytes(4096)) {
      manager.unloadLog(fixture.path().toString()); var log = manager.getOrLoad(fixture.path().toString());
      for (var name : registry.getToolNames()) {
        var tool = registry.getTool(name); if (!ToolArguments.takesPath(tool)) continue;
        for (var variant : ToolArguments.variants(tool, fixture, log, fixtures, temp)) {
          var result = tool.execute(variant.args()); Integer limit = variant.args().has("limit") ? variant.args().get("limit").getAsInt() : null;
          assertEquals(List.of(), ConformanceChecks.check(result, limit, true, name, variant.args()), name + "/" + variant.label()); calls++;
        }
      }
      assertTrue(calls > 80);
    } finally { manager.unloadAllLogs(); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); ExportTools.setExportDirectory(exportBefore.toString()); }
  }
}
