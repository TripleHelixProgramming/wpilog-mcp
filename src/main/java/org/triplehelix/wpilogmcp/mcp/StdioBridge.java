/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays newline-delimited JSON-RPC between a client's standard streams and an MCP server's
 * Streamable HTTP endpoint: {@code wpilog-mcp connect}.
 *
 * <p>It exists so that one HTTP server can serve every client on a laptop, including the ones
 * whose configuration takes only a command to run (Claude Code's project file is the case it
 * was built for; Claude Desktop's is another). Each line read from standard input is posted to
 * the endpoint, with the session header once the server has given a session; a response body
 * is written to standard output as one line; a notification, which the server answers with
 * 202 and no body, writes nothing. The server's own messages, which the transport sends on its
 * {@code GET} stream, are written as they arrive, each event's data as one line. One session per
 * bridge: when standard input closes, the session is deleted, so that a server that exits when
 * idle may do so, and the bridge exits.
 *
 * <p>A request the server cannot answer (the connection refused, the session gone because the
 * server was restarted, an HTTP error with no JSON-RPC body) gets a JSON-RPC error response with
 * the request's id, so the client sees a failed call rather than a hang; and a connection lost or
 * a session gone ends the bridge with a non-zero exit, since the client's remedy is to run the
 * command again, which starts the server if it must.
 *
 * <p>Nothing is written to standard output but the server's messages: like {@link McpServer},
 * the bridge takes standard output for the protocol and sends the JVM's {@code System.out} to
 * standard error before anything can print there.
 */
public final class StdioBridge {
  private static final Logger logger = LoggerFactory.getLogger(StdioBridge.class);
  private static final String SESSION_HEADER = "Mcp-Session-Id";
  /** A request waits this long for a response; a tool call on a large log can take a while. */
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
  /** The JSON-RPC error code the bridge reports its own failures with. */
  static final int BRIDGE_ERROR = -32000;

  private final URI endpoint;
  private final BufferedReader in;
  private final PrintWriter out;
  private final HttpClient http;
  private final Gson gson = new GsonBuilder().serializeNulls().create();
  private volatile String sessionId;
  private final AtomicBoolean closing = new AtomicBoolean();
  private volatile InputStream eventStream;
  private Thread eventThread;

  /**
   * A bridge between the given streams and an endpoint.
   *
   * @param endpoint The server's MCP endpoint, such as {@code http://127.0.0.1:2363/mcp}
   * @param in Where the client's messages come from
   * @param out Where the server's messages go, one per line
   */
  public StdioBridge(URI endpoint, InputStream in, OutputStream out) {
    this.endpoint = endpoint;
    this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    this.out = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
    // HTTP/1.1 outright: the server speaks nothing else, and a request for HTTP/2 over plain
    // HTTP would carry an upgrade header on every request
    this.http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  /**
   * The MCP endpoint for a URL as a person gives it: the URL itself when it has a path, else
   * {@code /mcp} on it.
   */
  public static URI endpointFor(String url) {
    var uri = URI.create(url.strip());
    if (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath())) {
      return uri.resolve("/mcp");
    }
    return uri;
  }

  /**
   * Relays until standard input closes or the server is lost.
   *
   * @return The exit code: 0 when the input closed, 1 when the server could not be reached or
   *     the session was lost
   */
  public int run() {
    int exit = 0;
    try {
      String line;
      while ((line = in.readLine()) != null) {
        if (line.isBlank()) continue;
        if (!relay(line)) {
          exit = 1;
          break;
        }
      }
    } catch (IOException e) {
      logger.debug("Input closed: {}", e.toString());
    } finally {
      close();
    }
    return exit;
  }

  /**
   * Posts one line to the server and writes what comes back.
   *
   * @return false when the bridge should end: the server is unreachable or the session is gone
   */
  private boolean relay(String line) {
    JsonElement message;
    try {
      message = JsonParser.parseString(line);
    } catch (RuntimeException e) {
      // Not JSON: let the server say so, as it would on stdio
      write(JsonRpc.createErrorResponse(null, JsonRpc.PARSE_ERROR, "Parse error"));
      return true;
    }
    var id = message.isJsonObject() && message.getAsJsonObject().has("id")
        ? message.getAsJsonObject().get("id") : null;
    boolean initialize = message.isJsonObject()
        && "initialize".equals(McpMessageHandler.methodName(message.getAsJsonObject()));

    var request = HttpRequest.newBuilder(endpoint)
        .timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream");
    var session = sessionId;
    if (session != null && !initialize) {
      request.header(SESSION_HEADER, session);
    }
    request.POST(HttpRequest.BodyPublishers.ofString(line, StandardCharsets.UTF_8));

    HttpResponse<String> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      logger.error("The server at {} could not be reached: {}", endpoint, e.toString());
      if (id != null) {
        write(JsonRpc.createErrorResponse(id, BRIDGE_ERROR,
            "The wpilog-mcp server could not be reached: " + e.getMessage()));
      }
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }

    int status = response.statusCode();
    if (status == 202) {
      return true;
    }
    if (status == 200) {
      if (initialize) {
        // A client that initializes again gets a new session; the old one is ended, not leaked
        if (sessionId != null) endSession();
        response.headers().firstValue(SESSION_HEADER).ifPresent(this::startSession);
      }
      writeBody(response);
      return true;
    }
    // An error with a JSON-RPC body (a parse error, an invalid request) is the server's answer
    if (bodyIsJsonRpc(response.body())) {
      writeBody(response);
      return status != 404;
    }
    if (status == 404 && session != null) {
      logger.error("The server at {} no longer knows this session (was it restarted?); "
          + "run the command again", endpoint);
      if (id != null) {
        write(JsonRpc.createErrorResponse(id, BRIDGE_ERROR,
            "The wpilog-mcp server no longer knows this session; it was probably restarted. "
                + "Start the client's connection again."));
      }
      return false;
    }
    if (id != null) {
      write(JsonRpc.createErrorResponse(id, BRIDGE_ERROR,
          "The wpilog-mcp server answered HTTP " + status + ": " + response.body().strip()));
    } else {
      logger.warn("The server answered HTTP {} to a notification: {}", status,
          response.body().strip());
    }
    return true;
  }

  private static boolean bodyIsJsonRpc(String body) {
    try {
      var parsed = JsonParser.parseString(body);
      return parsed.isJsonObject() && parsed.getAsJsonObject().has("jsonrpc")
          || parsed.isJsonArray();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Writes a response body as one line: JSON re-serialized without line breaks. */
  private void writeBody(HttpResponse<String> response) {
    var contentType = response.headers().firstValue("Content-Type").orElse("");
    if (contentType.startsWith("text/event-stream")) {
      // A server may answer a POST with a stream of events; this one does not, but the format
      // is the same as the GET stream's
      for (var data : SseEvents.dataOf(response.body().lines())) {
        writeEvent(data);
      }
      return;
    }
    try {
      write(JsonParser.parseString(response.body()));
    } catch (RuntimeException e) {
      logger.warn("The server answered with something that is not JSON: {}", response.body());
    }
  }

  private void write(JsonElement message) {
    writeLine(gson.toJson(message));
  }

  /**
   * Writes an event's data as one line. The data may span several {@code data:} lines, which
   * the stream joins with newlines; a newline-delimited protocol needs the message on one line,
   * so it is parsed and written again as JSON. Data that is not JSON is not a message.
   */
  private void writeEvent(String data) {
    try {
      write(JsonParser.parseString(data));
    } catch (RuntimeException e) {
      logger.warn("The server sent an event that is not JSON: {}", data);
    }
  }

  private void writeLine(String line) {
    synchronized (out) {
      out.println(line);
      out.flush();
    }
  }

  /** Remembers the session the server gave and opens its stream of server messages. */
  private void startSession(String id) {
    sessionId = id;
    var request = HttpRequest.newBuilder(endpoint)
        .header("Accept", "text/event-stream")
        .header(SESSION_HEADER, id)
        .GET()
        .build();
    eventThread = new Thread(() -> readEvents(request), "mcp-bridge-events");
    eventThread.setDaemon(true);
    eventThread.start();
  }

  /** Reads the GET stream for the session and writes each event's data as a line. */
  private void readEvents(HttpRequest request) {
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        logger.debug("No event stream: the server answered {}", response.statusCode());
        return;
      }
      eventStream = response.body();
      try (var reader = new BufferedReader(
          new InputStreamReader(eventStream, StandardCharsets.UTF_8))) {
        var event = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isEmpty()) {
            if (event.length() > 0) {
              writeEvent(event.toString());
              event.setLength(0);
            }
          } else if (line.startsWith("data:")) {
            if (event.length() > 0) event.append('\n');
            event.append(line.substring(5).stripLeading());
          }
          // Comments (":ping") and other fields are not messages
        }
      }
    } catch (IOException e) {
      if (!closing.get()) {
        logger.debug("The event stream ended: {}", e.toString());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Ends the stream and the session, so that an idle server may exit. */
  private void close() {
    closing.set(true);
    endSession();
  }

  /** Closes the event stream, deletes the session, and waits for the stream's thread. */
  private void endSession() {
    var stream = eventStream;
    if (stream != null) {
      try {
        stream.close();
      } catch (IOException e) {
        logger.debug("Closing the event stream: {}", e.toString());
      }
    }
    var session = sessionId;
    if (session != null) {
      try {
        var delete = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(5))
            .header(SESSION_HEADER, session)
            .DELETE()
            .build();
        http.send(delete, HttpResponse.BodyHandlers.discarding());
      } catch (IOException e) {
        logger.debug("The session could not be deleted: {}", e.toString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (eventThread != null) {
      try {
        eventThread.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    sessionId = null;
    eventStream = null;
    eventThread = null;
  }

  /** The data of each event in a stream of server-sent-event lines. */
  static final class SseEvents {
    private SseEvents() {}

    static java.util.List<String> dataOf(java.util.stream.Stream<String> lines) {
      var events = new java.util.ArrayList<String>();
      var event = new StringBuilder();
      lines.forEach(line -> {
        if (line.isEmpty()) {
          if (event.length() > 0) {
            events.add(event.toString());
            event.setLength(0);
          }
        } else if (line.startsWith("data:")) {
          if (event.length() > 0) event.append('\n');
          event.append(line.substring(5).stripLeading());
        }
      });
      if (event.length() > 0) events.add(event.toString());
      return events;
    }
  }
}
