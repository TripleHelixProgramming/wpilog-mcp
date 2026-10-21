/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.PrometheusText;
import org.triplehelix.wpilogmcp.store.DiskSpaceBudgets;

class DiskSpaceEndpointTest {
  @TempDir Path directory;

  @Test void healthReportsPublishedPhysicalBytesWithoutReadingTheFilesystem() throws Exception {
    var reads = new AtomicInteger();
    try (var owner = new DiskSpaceBudgets(filesystem -> { reads.incrementAndGet(); return 1_000; })) {
      var store = owner.store(directory);
      store.configureCapture(new DiskSpaceBudgets.Policy(100, 20, 30));
      try (var held = store.reserveCopy(125)) {
        var server = new HttpTransport(new ToolRegistry(), 0);
        server.diskSpaceSnapshots(owner::snapshots);
        server.start();
        try {
          var response = get(server, "/health");
          assertEquals(200, response.statusCode());
          var body = JsonParser.parseString(response.body()).getAsJsonObject();
          assertEquals("ok", body.get("status").getAsString());
          assertTrue(body.has("store_filesystems"), "Health must expose the published filesystem budgets");
          var rows = body.getAsJsonArray("store_filesystems");
          assertEquals(1, rows.size());
          var row = rows.get(0).getAsJsonObject();
          assertEquals("filesystem_1", row.get("filesystem_id").getAsString());
          assertEquals("available", row.get("state").getAsString());
          assertEquals(1_000, row.get("free_bytes").getAsLong());
          assertEquals(125, row.get("reserved_bytes").getAsLong());
          assertEquals(100, row.get("capture_reserve_bytes").getAsLong());
          assertEquals(5, row.size(), "The public disk view contains no path or filesystem name");
          assertFalse(response.body().contains(directory.toString()));
          assertEquals(1, reads.get(), "A health request must not sample disk space");
        } finally { server.stop(); }
      }
    }
  }

  @Test void metricsUseBytesAndOpaqueFilesystemLabelsFromTheSamePublication() throws Exception {
    var reads = new AtomicInteger();
    try (var owner = new DiskSpaceBudgets(filesystem -> { reads.incrementAndGet(); return 1_000; })) {
      var store = owner.store(directory);
      store.configureCapture(new DiskSpaceBudgets.Policy(100, 20, 30));
      try (var held = store.reserveCopy(125)) {
        var server = new HttpTransport(new ToolRegistry(), 0);
        server.diskSpaceSnapshots(owner::snapshots);
        server.start();
        try {
          var response = get(server, "/metrics");
          assertEquals(200, response.statusCode());
          var view = PrometheusText.parse(response.body());
          var label = Map.of("filesystem_id", "filesystem_1");
          assertEquals(1_000, view.value("wpilog_store_filesystem_free_bytes", label));
          assertEquals(125, view.value("wpilog_store_filesystem_reserved_bytes", label));
          assertEquals(100, view.value("wpilog_store_filesystem_capture_reserve_bytes", label));
          assertEquals(1, view.value("wpilog_store_filesystem_space_known", label));
          assertEquals(4, view.samples().keySet().stream()
              .filter(key -> key.name().startsWith("wpilog_store_filesystem_")).count());
          assertFalse(response.body().contains(directory.toString()));
          assertEquals(1, reads.get(), "A scrape must not sample disk space");
        } finally { server.stop(); }
      }
    }
  }

  @Test void anUnknownReadingIsNullInHealthAndHasNoInventedFreeByteMetric() throws Exception {
    var reads = new AtomicInteger();
    try (var owner = new DiskSpaceBudgets(filesystem -> {
      reads.incrementAndGet();
      throw new IOException("private /sensitive/filesystem");
    })) {
      var store = owner.store(directory);
      store.configureCapture(new DiskSpaceBudgets.Policy(100, 20, 30));
      store.refresh();
      var server = new HttpTransport(new ToolRegistry(), 0);
      server.diskSpaceSnapshots(owner::snapshots);
      server.start();
      try {
        var response = get(server, "/health");
        var body = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("ok", body.get("status").getAsString());
        assertTrue(body.has("store_filesystems"), "Unknown disk measurements must remain visible");
        var row = body.getAsJsonArray("store_filesystems").get(0).getAsJsonObject();
        assertEquals("unknown", row.get("state").getAsString());
        assertTrue(row.get("free_bytes").isJsonNull());
        assertEquals(0, row.get("reserved_bytes").getAsLong());
        assertEquals(100, row.get("capture_reserve_bytes").getAsLong());
        var metrics = get(server, "/metrics");
        var view = PrometheusText.parse(metrics.body());
        var label = Map.of("filesystem_id", "filesystem_1");
        assertEquals(0, view.value("wpilog_store_filesystem_space_known", label));
        assertEquals(0, view.value("wpilog_store_filesystem_reserved_bytes", label));
        assertEquals(100, view.value("wpilog_store_filesystem_capture_reserve_bytes", label));
        assertFalse(view.samples().keySet().stream()
            .anyMatch(key -> key.name().equals("wpilog_store_filesystem_free_bytes")));
        assertFalse((response.body() + metrics.body()).contains("/sensitive"));
        assertEquals(1, reads.get(), "Neither public view repeats an unavailable filesystem read");
      } finally { server.stop(); }
    }
  }

  private static HttpResponse<String> get(HttpTransport server, String path) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + server.getPort() + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
