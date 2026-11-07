/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.catalog.StoreReadCatalog;
import org.triplehelix.wpilogmcp.read.internal.ReadWire;
import org.triplehelix.wpilogmcp.read.rev.RecordedRevRead;

/** One eager recorded-REV read, streamed in bounded frames without recomputing alignment. */
final class RevReadEndpoint {
  private RevReadEndpoint() {}
  static void describe(HttpExchange exchange, StoreReadCatalog catalog, JsonObject request) throws IOException {
    try (var read = source(catalog, request)) {
      var descriptor = header(read, "rev_description");
      var signals = new JsonObject();
      read.signals().forEach((key, signal) -> {
        var metadata = new JsonObject(); metadata.addProperty("name", signal.name());
        metadata.addProperty("device_key", signal.deviceKey()); metadata.addProperty("unit", signal.unit());
        metadata.addProperty("sample_count", Integer.toString(signal.values().size())); signals.add(key, metadata);
      });
      descriptor.add("signals", signals);
      byte[] bytes = ReadWire.bytes(descriptor, ReadWire.MAX_FRAME_BYTES);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
      catch (IOException disconnected) { /* No second HTTP response after the headers. */ }
      finally { exchange.close(); }
    }
  }
  static void handle(HttpExchange exchange, StoreReadCatalog catalog, JsonObject request) throws IOException {
    String signal = ReadWire.text(request, "signal"), requestedClock = ReadWire.text(request, "clock");
    var clock = switch (requestedClock) {
      case "raw" -> RecordedRevRead.Clock.RAW;
      case "fpga" -> RecordedRevRead.Clock.FPGA;
      default -> throw new IllegalArgumentException("REV clock must be raw or fpga");
    };
    try (var read = source(catalog, request)) {
      int count = read.rawValues(signal).size();
      read.samples(signal, 0, 0, clock); // Refuse an unavailable clock before publishing HTTP headers.
      var header = header(read, "rev");
      header.addProperty("signal", signal); header.addProperty("clock", requestedClock);
      header.addProperty("unit", read.signals().get(signal).unit()); header.addProperty("sample_count", Integer.toString(count));
      byte[] first = ReadWire.bytes(header, ReadWire.MAX_FRAME_BYTES);
      exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
      exchange.sendResponseHeaders(200, 0);
      try (var output = exchange.getResponseBody()) {
        output.write(first); output.write('\n');
        try {
          for (int offset = 0; offset < count;) {
            var page = read.samples(signal, offset, ReadWire.MAX_REV_SAMPLES, clock);
            var batch = new JsonObject(); batch.addProperty("kind", "rev_samples");
            var samples = new JsonArray();
            for (var value : page) {
              var sample = new JsonObject(); sample.add("timestamp_sec", ReadWire.value(value.timestamp()));
              sample.add("value", ReadWire.value(value.value())); samples.add(sample);
            }
            batch.add("samples", samples); write(output, batch); offset += page.size();
          }
          write(output, ReadWire.end(null));
        } catch (IOException | IllegalArgumentException failure) {
          var explained = failure instanceof ReadException readFailure ? readFailure
              : new ReadException(failure instanceof ReadWire.WireLimit ? ReadProblem.Code.RESOURCE_LIMIT
                  : ReadProblem.Code.MALFORMED_RECORD, failure.getMessage());
          write(output, ReadWire.end(explained));
        }
      } catch (IOException disconnected) {
        // Once headers are committed the completion record is the only success proof. A disconnected
        // client cannot receive it; do not let the outer handler attempt a second HTTP response.
      } finally { exchange.close(); }
    }
  }
  private static RecordedRevRead source(StoreReadCatalog catalog, JsonObject request) throws IOException {
    return catalog.openRev(Path.of(ReadWire.text(request, "companion_path")), Path.of(ReadWire.text(request, "anchor_path")),
        request.has("signal") ? java.util.Set.of(ReadWire.text(request, "signal")) : null);
  }
  private static JsonObject header(RecordedRevRead read, String kind) {
    var header = new JsonObject(); header.addProperty("kind", kind); header.addProperty("api_version", ReadWire.VERSION);
    header.addProperty("path", read.path().toString());
    header.addProperty("state", read.state() == RecordedRevRead.State.RECORDED ? "recorded" : "unsynchronized");
    header.addProperty("reason", read.reason()); header.add("alignment", ReadWire.JSON.toJsonTree(read.state() == RecordedRevRead.State.RECORDED ? read.alignment() : null));
    return header;
  }
  private static void write(OutputStream output, JsonObject frame) throws IOException {
    output.write(ReadWire.bytes(frame, ReadWire.MAX_FRAME_BYTES)); output.write('\n');
  }
}
