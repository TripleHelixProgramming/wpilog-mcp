/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.triplehelix.wpilogmcp.config.ClientLeases;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.visualization.ServerIdentity;

/** A raw sender has no automatic HTTP retries to conceal a reset that lost a refusal. */
class HttpRefusalTest {
  @TempDir Path temp;

  @ParameterizedTest(name = "{1} chunked={5}")
  @CsvSource({
      "POST, /read/v1/follows, 65536, 413, RESOURCE_LIMIT, false",
      "POST, /read/v1/follows/unused/next, 65536, 413, RESOURCE_LIMIT, false",
      "POST, /read/v1/snapshots, 65536, 413, RESOURCE_LIMIT, false",
      "POST, /mcp, 4194304, 413, exceeds, false",
      "POST, /directories, 65536, 400, too large, false",
      "POST, /store/mirror/configure, 65536, 400, exceeds, false",
      "POST, /store/sync, 65536, 400, exceeds, false",
      "POST, /store/import, 1048576, 400, at most 1 MiB, false",
      "POST, /store/assign, 1048576, 400, at most 1 MiB, false",
      "POST, /visualizations/v1/source, 1048576, 400, exceeds, false",
      "PUT, /store/local-ingestion, 65536, 413, RESOURCE_LIMIT, false",
      "POST, /pit-mcp, 4194304, 413, exceeds, false",
      "POST, /read/v1/follows, 65536, 413, RESOURCE_LIMIT, true",
      "POST, /mcp, 4194304, 413, exceeds, true"
  })
  void everyOversizedBodyWithinTheDrainBoundGetsItsRefusal(String method, String route,
      int limit, int status, String reason, boolean chunked) throws Exception {
    temp = temp.toRealPath();
    var logs = LogManager.getInstance();
    var original = logs.getConfiguredDirectories();
    logs.addAllowedDirectory(temp);
    var registry = new ToolRegistry(); registry.serverIdentity(ServerIdentity.open(temp.resolve("identity")));
    var server = new HttpTransport(registry, 0, "127.0.0.1", null, null, "http");
    var forwarded = new AtomicInteger();
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/mcp", exchange -> { forwarded.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
    upstream.start();
    try {
      server.configureLocalIngestion(temp.resolve("policy.json")); server.start();
      var initialized = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
          "http://127.0.0.1:" + server.getPort() + "/mcp"))
          .POST(HttpRequest.BodyPublishers.ofString(HttpAdmissionTest.INITIALIZE)).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, initialized.statusCode(), initialized.body());
      String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
      if (route.equals("/pit-mcp")) {
        String target = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/mcp";
        ClientLeases.getInstance().registerPit(session, target, "Basic synthetic");
        route += "?url=" + URLEncoder.encode(target, StandardCharsets.UTF_8);
      }
      // The surplus fits the shared 4 MiB discard budget but exceeds the JDK's close drain.
      byte[] body = new byte[limit + (3 << 20) + 1]; Arrays.fill(body, (byte) ' ');
      var failures = new ArrayList<String>();
      for (int attempt = 0; attempt < 20; attempt++) {
        try {
          var reply = post(server.getPort(), method, route, session, body, chunked);
          assertEquals(status, reply.status(), reply.body());
          assertTrue(reply.body().contains(reason), reply.body());
          if (route.startsWith("/read/")) assertTrue(reply.body().contains("\"retryable\":false"), reply.body());
        } catch (IOException | AssertionError failure) { failures.add(attempt + ": " + failure); }
      }
      System.out.println("Refusal replies " + method + " " + route.split("\\?")[0]
          + " chunked=" + chunked + ": " + (20 - failures.size()) + "/20");
      assertEquals(0, failures.size(), String.join("\n", failures));
      assertEquals(0, forwarded.get(), "An oversized proxy request must never be forwarded");
    } finally {
      server.stop(); upstream.stop(0);
      logs.clearAllowedDirectories(); original.forEach(logs::addAllowedDirectory);
    }
  }

  private record Reply(int status, String body) {}
  private static Reply post(int port, String method, String route, String session, byte[] body,
      boolean chunked) throws IOException {
    try (var socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10000);
      var output = socket.getOutputStream();
      output.write((method + " " + route + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
          + "Content-Type: application/json\r\nMcp-Session-Id: " + session + "\r\n"
          + (chunked ? "Transfer-Encoding: chunked" : "Content-Length: " + body.length) + "\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      for (int offset = 0; offset < body.length; offset += 8192) {
        int size = Math.min(8192, body.length - offset);
        if (chunked) output.write((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body, offset, size);
        if (chunked) output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
      }
      if (chunked) output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      output.flush();
      var input = new BufferedInputStream(socket.getInputStream());
      String status = line(input);
      if (!status.startsWith("HTTP/1.1 ")) throw new IOException("Missing HTTP reply: " + status);
      int length = -1;
      for (String header; !(header = line(input)).isEmpty();) {
        if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(header.substring(15).trim());
      }
      if (length < 0 || length > 65536) throw new IOException("Invalid refusal length: " + length);
      byte[] bytes = input.readNBytes(length);
      if (bytes.length != length) throw new IOException("Truncated refusal: " + bytes.length + "/" + length);
      return new Reply(Integer.parseInt(status.split(" ")[1]), new String(bytes, StandardCharsets.UTF_8));
    }
  }
  private static String line(BufferedInputStream input) throws IOException {
    var value = new StringBuilder();
    for (int c; (c = input.read()) != '\n';) {
      if (c == -1) throw new IOException("EOF before HTTP headers");
      if (c != '\r') value.append((char) c);
      if (value.length() > 8192) throw new IOException("Unbounded HTTP header");
    }
    return value.toString();
  }
}
