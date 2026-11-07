/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Baseline requests and inputs must survive a candidate run unchanged. */
class ReadBehaviorCorpusTest {
  @TempDir Path temporary;

  @Test void anExistingCorpusIsNeverRegenerated() throws Exception {
    var root = Files.createDirectory(temporary.resolve("frozen"));
    var sentinel = root.resolve("manifest.json");
    Files.writeString(sentinel, "do not regenerate expected behavior");
    assertThrows(FileAlreadyExistsException.class, () -> ReadBehaviorCorpus.create(root));
    assertEquals("do not regenerate expected behavior", Files.readString(sentinel));
  }

  @Test void freezesInputsRequestsAndOutOfWindowDecodeDiagnostics() throws Exception {
    var root = temporary.resolve("corpus");
    ReadBehaviorCorpus.create(root);
    assertTrue(Files.isRegularFile(root.resolve("manifest.json")), "a corpus needs its frozen request manifest");
    var manifest = JsonParser.parseString(Files.readString(root.resolve("manifest.json"))).getAsJsonObject();
    assertEquals(1, manifest.get("format_version").getAsInt());
    assertTrue(manifest.getAsJsonArray("inputs").size() >= 25);
    for (var row : manifest.getAsJsonArray("inputs")) {
      var input = row.getAsJsonObject();
      var path = root.resolve(input.get("path").getAsString());
      assertTrue(path.normalize().startsWith(root));
      assertEquals(Files.size(path), input.get("size_bytes").getAsLong());
      assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))),
          input.get("sha256").getAsString());
      assertEquals(Files.getLastModifiedTime(path).toMillis(), input.get("modified_ms").getAsLong());
    }
    var ids = new HashSet<String>();
    var tools = new HashSet<String>();
    int windows = 0;
    for (var row : manifest.getAsJsonArray("cases")) {
      var c = row.getAsJsonObject();
      assertTrue(ids.add(c.get("id").getAsString()), "case identifiers are unique");
      if (c.has("tool")) tools.add(c.get("tool").getAsString());
      if (c.get("id").getAsString().endsWith("/window")) {
        var params = new java.util.HashMap<String, String>();
        c.getAsJsonArray("query").forEach(p -> params.put(p.getAsJsonArray().get(0).getAsString(), p.getAsJsonArray().get(1).getAsString()));
        var source = IndependentLog.read(Path.of(params.get("path")), java.util.Set.of(), java.util.Set.of(params.get("names")));
        double start = Double.parseDouble(params.get("start_time"));
        double end = Double.parseDouble(params.get("end_time"));
        assertTrue(source.series.get(params.get("names")).payloadTimes.stream().anyMatch(t -> t >= start && t <= end),
            "window must exercise recorded data: " + c.get("id"));
        windows++;
      }
    }
    assertTrue(tools.containsAll(java.util.Set.of("read_entry", "get_statistics", "resolve_signals",
        "render_chart", "export_csv", "get_revlog_data", "search_system_logs")));
    for (String kind : java.util.List.of("mcp", "arrow", "csv")) {
      assertTrue(ids.contains("decode-outside-window/" + kind), "narrow legacy reads retain whole-entry diagnostics: " + kind);
    }
    assertTrue(ids.contains("http/origin-refused"));
    assertTrue(ids.contains("http/path-refused"));
    assertFalse(tools.contains("set_revlog_offset"), "read parity must not silently mutate the initial alignment");
    assertTrue(windows > 100, "nonempty windows cover the convention corpus");
  }
}
