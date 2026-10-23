/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * The CSV rows the data endpoint writes, character for character, against text worked out by
 * hand: numbers as {@code String.valueOf} spells them, an empty cell for a bucket with no finite
 * sample and for a time before the clock's first reading, and the first and last samples as
 * logged, NaN and infinities included. Grafana reads these rows, so a change to how a row is
 * written must not change one character of it.
 */
class DataEndpointCsvTextTest {
  /** 2023-11-14T22:13:20Z, the wall clock at log time zero. */
  private static final long W = 1_700_000_000_000_000L;
  @TempDir Path directory;
  private final LogManager manager = LogManager.getInstance();
  private java.util.Set<Path> previous;
  private HttpServer server;
  private Path log;

  @BeforeEach void start() throws Exception {
    directory = directory.toRealPath();
    previous = manager.getAllowedDirectories();
    manager.addAllowedDirectory(directory);
    log = directory.resolve("cells.wpilog");
    try (var writer = new WpilogWriter(log, "")) {
      int value = writer.start("/value", "double", "", 0);
      int clock = writer.start("systemTime", "int64", "", 0);
      double[] values = {1.5, Double.NaN, Double.POSITIVE_INFINITY, -2.25, 3.0};
      for (int i = 0; i < values.length; i++) {
        long time = 1_000_000L + 100_000L * i;
        writer.append(value, time, WpilogWriter.encodeDouble(values[i]));
        // The clock is first read between the second and third samples.
        if (i == 1 || i == 3) writer.append(clock, time + 50_000, WpilogWriter.encodeInt64(W + time + 50_000));
      }
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(HttpTransport.DATA_PATH, new DataEndpoint(manager, () -> null)::handle);
    server.start();
  }

  @AfterEach void stop() {
    server.stop(0);
    manager.unloadLog(log.toString());
    manager.clearAllowedDirectories();
    previous.forEach(manager::addAllowedDirectory);
  }

  /** The rows after the metadata lines. */
  private String rows(String query) throws Exception {
    var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + HttpTransport.DATA_PATH + "?path="
        + URLEncoder.encode(log.toString(), StandardCharsets.UTF_8) + "&names=/value&format=csv" + query);
    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return response.body().lines().filter(line -> !line.startsWith("#") && !line.isEmpty()).collect(Collectors.joining("\n"));
  }

  @Test void bucketsKeepEmptyCellsForWhatTheyCannotSay() throws Exception {
    // Two buckets from 1.0 s to 1.4 s: 1.5 and NaN; then infinity, -2.25 and 3.0, whose finite mean is 0.375.
    assertEquals("""
        timestamp_sec,timestamp_utc,count,min,max,mean,first,last
        1.0,,2,1.5,1.5,1.5,1.5,NaN
        1.2,2023-11-14T22:13:21.200000Z,3,-2.25,3.0,0.375,Infinity,3.0""", rows("&max_points=2&utc=true"));
    assertEquals("""
        timestamp_sec,count,min,max,mean,first,last
        1.0,2,1.5,1.5,1.5,1.5,NaN
        1.2,3,-2.25,3.0,0.375,Infinity,3.0""", rows("&max_points=2"));
  }

  @Test void samplesInBucketColumnsKeepTheirOwnValues() throws Exception {
    // Fewer samples than buckets asked for: one row each, extrapolated past the last reading at 1.35 s.
    assertEquals("""
        timestamp_sec,timestamp_utc,count,min,max,mean,first,last
        1.0,,1,1.5,1.5,1.5,1.5,1.5
        1.1,,1,,,,NaN,NaN
        1.2,2023-11-14T22:13:21.200000Z,1,,,,Infinity,Infinity
        1.3,2023-11-14T22:13:21.300000Z,1,-2.25,-2.25,-2.25,-2.25,-2.25
        1.4,2023-11-14T22:13:21.400000Z,1,3.0,3.0,3.0,3.0,3.0""", rows("&max_points=100&utc=true"));
  }
}
