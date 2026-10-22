/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.data.ArrowSpecReader;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * A window chosen by UTC holds exactly the rows a whole read places inside it by its own
 * {@code timestamp_utc}, on every fixture with a clock: the ends on a sample's instant, a
 * microsecond inside it, a microsecond outside it, and open. A sample has one time however it
 * is asked for, so an end off by one fails here.
 */
class DataEndpointUtcWindowDifferentialTest {
  private static final LogManager manager = LogManager.getInstance();
  private static Set<Path> previous;
  private static HttpTransport transport;
  private static final HttpClient client = HttpClient.newHttpClient();
  private static List<FixtureLogs.Fixture> fixtures;
  /** Fixtures of its own: generating into the shared directory would rewrite files other classes hold. */
  @org.junit.jupiter.api.io.TempDir static Path directory;

  private record Row(long logUs, Long utcUs, Object value) {}

  @BeforeAll static void start() throws Exception {
    directory = directory.toRealPath();
    fixtures = FixtureLogs.generateAll(directory);
    previous = manager.getAllowedDirectories();
    manager.addAllowedDirectory(directory);
    transport = new HttpTransport(new ToolRegistry(), 0);
    transport.start();
  }

  @AfterAll static void stop() {
    if (transport != null) transport.stop();
    manager.clearAllowedDirectories();
    previous.forEach(manager::addAllowedDirectory);
  }

  private static String iso(long epochUs) {
    return Instant.ofEpochSecond(Math.floorDiv(epochUs, 1_000_000L), Math.floorMod(epochUs, 1_000_000L) * 1000).toString();
  }

  private static List<Row> read(Path path, String entry, String window) throws Exception {
    var uri = URI.create("http://127.0.0.1:" + transport.getPort() + HttpTransport.DATA_PATH + "?path="
        + URLEncoder.encode(path.toString(), StandardCharsets.UTF_8) + "&names=" + URLEncoder.encode(entry, StandardCharsets.UTF_8)
        + "&utc=true" + window);
    var response = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, response.statusCode(), () -> new String(response.body(), StandardCharsets.UTF_8));
    var stream = ArrowSpecReader.read(response.body());
    var rows = new ArrayList<Row>();
    for (var batch : stream.batches()) {
      if (!entry.equals(batch.metadata().get("entry"))) continue;
      var times = batch.columns().get(0);
      var utc = batch.columns().get(1);
      var values = batch.columns().get(2);
      for (int i = 0; i < times.size(); i++) {
        rows.add(new Row(((Number) times.get(i)).longValue(), utc.get(i) == null ? null : ((Number) utc.get(i)).longValue(), values.get(i)));
      }
    }
    return rows;
  }

  @Test
  void aUtcWindowHoldsTheRowsAWholeReadPlacesInIt() throws Exception {
    var random = new Random(23);
    int windows = 0, placedFixtures = 0;
    for (var fixture : fixtures) {
      var path = fixture.path();
      List<String> entries;
      try (var use = manager.acquire(path.toString())) {
        var log = use.log();
        if (log.utcMapping().unavailableReason().isPresent()) continue;
        entries = log.entries().values().stream()
            .filter(info -> Set.of("double", "float", "int64", "boolean").contains(info.type()))
            .filter(info -> log.sampleCount(info.name()) >= 3)
            .map(info -> info.name()).sorted().limit(6).toList();
      }
      placedFixtures++;
      for (var entry : entries) {
        var whole = read(path, entry, "");
        var placed = whole.stream().filter(row -> row.utcUs() != null).toList();
        if (placed.size() < 2) continue;
        for (int trial = 0; trial < 4; trial++) {
          int a = random.nextInt(placed.size()), b = random.nextInt(placed.size());
          long start = placed.get(Math.min(a, b)).utcUs(), end = placed.get(Math.max(a, b)).utcUs();
          for (long[] window : new long[][] {{start, end}, {start + 1, end - 1}, {start - 1, end + 1},
              {start, Long.MAX_VALUE}, {Long.MIN_VALUE, end}}) {
            long low = window[0], high = window[1];
            var query = new StringBuilder();
            if (low != Long.MIN_VALUE) query.append("&start_utc=").append(URLEncoder.encode(iso(low), StandardCharsets.UTF_8));
            if (high != Long.MAX_VALUE) query.append("&end_utc=").append(URLEncoder.encode(iso(high), StandardCharsets.UTF_8));
            if (high < low) continue;
            var expected = placed.stream().filter(row -> row.utcUs() >= low && row.utcUs() <= high).toList();
            assertEquals(expected, read(path, entry, query.toString()),
                fixture.id() + " " + entry + " from " + iso(low) + " to " + iso(high));
            windows++;
          }
        }
      }
    }
    assertTrue(placedFixtures >= 2, "fixtures with a clock: " + placedFixtures);
    assertTrue(windows >= 100, "windows compared: " + windows);
  }
}
