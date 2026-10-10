/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs.Fixture;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.mcp.HttpTransport;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.store.LogStore;
import org.triplehelix.wpilogmcp.store.StoreCatalog;
import org.triplehelix.wpilogmcp.tools.ExportTools;
import org.triplehelix.wpilogmcp.tools.WpilogTools;

/** Byte identity must also preserve all tool answers, including the REV companion association. */
class StoreSyncConformanceTest {
  @TempDir Path temp;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void everyFixtureToolAnswerSurvivesAnHttpStoreHop(boolean mirror) throws Exception {
    temp = temp.toRealPath(); var manager = LogManager.getInstance();
    var allowed = manager.getAllowedDirectories(); var oldExport = ExportTools.getExportDirectory();
    var export = Files.createDirectory(temp.resolve("export")); ExportTools.setExportDirectory(export.toString());
    manager.addAllowedDirectory(temp);
    var tools = new ArrayList<ToolRegistry.Tool>();
    WpilogTools.registerAll(new ToolRegistry() {
      @Override public void registerTool(Tool tool) { tools.add(tool); super.registerTool(tool); }
    });
    var http = new HttpTransport(new ToolRegistry(), 0);
    try {
      var fixtures = FixtureLogs.generateAll(temp.resolve("fixtures"));
      var a = manager.stores().store(temp.resolve("a")); var b = manager.stores().store(temp.resolve("b"));
      var paths = new ArrayList<>(fixtures.stream().map(Fixture::path).toList());
      try (var files = Files.list(temp.resolve("fixtures"))) {
        var companions = files.filter(p -> p.toString().endsWith(".revlog")).toList();
        // These must be admissible same-boot companions; the general corpus tests a 15.3 s lag.
        for (var rev : companions) org.triplehelix.wpilogmcp.fixtures.ImportFixture.sameClockRev(rev);
        paths.addAll(companions);
      }
      var imported = a.importPaths(new LogStore.Request(paths, false, "fixture"), p -> {}).get();
      assertTrue(imported.files().stream().noneMatch(f -> f.status().equals("refused")), imported.toString());
      var source = StoreCatalog.readManaged(a.root(), manager.testGetSecurityValidator());
      http.setStoreDirectories(Set.of(a.root())); http.start();
      String url = "http://127.0.0.1:" + http.getPort();
      if (mirror) {
        var result = b.mirror(new org.triplehelix.wpilogmcp.config.MirrorConfig(url, b.root(), 365_000,
            Long.MAX_VALUE, List.of(), List.of(), 30, 0), p -> {}).get();
        assertEquals("synchronized", result.state(), result.toString());
      } else {
        var result = b.sync(url, 0, p -> {}).get();
        assertEquals(List.of(), result.refusals(), result.toString()); assertEquals(List.of(), result.stopped(), result.toString());
      }
      var copy = StoreCatalog.readManaged(b.root(), manager.testGetSecurityValidator());
      assertEquals(source.files().size(), copy.files().size());
      var copiedPaths = new LinkedHashMap<String, Path>(); var aliases = new LinkedHashMap<String, String>();
      for (var file : source.files()) {
        var other = copy.files().stream().filter(f -> f.file().sha256().equals(file.file().sha256())).findFirst().orElseThrow();
        assertArrayEquals(Files.readAllBytes(file.path()), Files.readAllBytes(other.path()));
        copiedPaths.put(file.path().toString(), other.path());
        aliases.put(file.path().toString(), "<log:" + file.file().sha256() + ">");
        aliases.put(other.path().toString(), "<log:" + file.file().sha256() + ">");
      }
      var inStore = fixtures.stream().map(f -> new Fixture(f.id(), source.files().stream()
          .filter(s -> f.path().toString().equals(s.file().provenance().originalPath())).findFirst().orElseThrow().path(), f.description(), f.exercises())).toList();
      int calls = 0;
      for (var fixture : inStore) {
        String from = fixture.path().toString(), to = copiedPaths.get(from).toString();
        var log = manager.getOrLoad(from); manager.getOrLoad(to);
        manager.waitForRevLogSync(from, 30_000); manager.waitForRevLogSync(to, 30_000);
        for (var tool : tools) {
          if (!ToolArguments.takesPath(tool)) continue;
          for (var variant : ToolArguments.variants(tool, fixture, log, inStore, export)) {
            var args = variant.args();
            var changed = rewrite(args, Map.of(from, to)).getAsJsonObject();
            var first = tool.execute(args); var second = tool.execute(changed);
            assertEquals(rewrite(ConformanceChecks.normalize(first), aliases), rewrite(ConformanceChecks.normalize(second), aliases),
                fixture.id() + " / " + tool.name() + " / " + variant.label());
            calls++;
          }
        }
      }
      var report = Path.of("build/reports/conformance/" + (mirror ? "mirror" : "store-sync") + ".txt"); Files.createDirectories(report.getParent());
      Files.writeString(report, "fixtures=" + fixtures.size() + " files=" + source.files().size() + " tool_pairs=" + calls + "\n");
    } finally {
      http.stop(); manager.unloadAllLogs(); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory);
      ExportTools.setExportDirectory(oldExport.toString());
    }
  }

  /** Normalize only known paths, in keys and values, without mangling Windows JSON escapes. */
  private static JsonElement rewrite(JsonElement value, Map<String, String> aliases) {
    if (value == null || value.isJsonNull()) return value;
    if (value.isJsonObject()) {
      var result = new JsonObject();
      value.getAsJsonObject().entrySet().forEach(e -> result.add(rewrite(e.getKey(), aliases), rewrite(e.getValue(), aliases)));
      return result;
    }
    if (value.isJsonArray()) {
      var result = new com.google.gson.JsonArray(); value.getAsJsonArray().forEach(e -> result.add(rewrite(e, aliases))); return result;
    }
    return value.getAsJsonPrimitive().isString() ? new com.google.gson.JsonPrimitive(rewrite(value.getAsString(), aliases)) : value;
  }
  private static String rewrite(String text, Map<String, String> aliases) {
    for (var alias : aliases.entrySet()) text = text.replace(alias.getKey(), alias.getValue());
    return text;
  }
}
