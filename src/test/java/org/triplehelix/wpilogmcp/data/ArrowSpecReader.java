/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.flatbuf.Field;
import org.apache.arrow.flatbuf.FloatingPoint;
import org.apache.arrow.flatbuf.Int;
import org.apache.arrow.flatbuf.Message;
import org.apache.arrow.flatbuf.MessageHeader;
import org.apache.arrow.flatbuf.Precision;
import org.apache.arrow.flatbuf.RecordBatch;
import org.apache.arrow.flatbuf.Schema;
import org.apache.arrow.flatbuf.TimeUnit;
import org.apache.arrow.flatbuf.Timestamp;
import org.apache.arrow.flatbuf.Type;

/**
 * A reader of the Arrow IPC streaming format written from the specification, sharing no code
 * with the server's writer: it parses the encapsulated messages, the schema's fields, and each
 * batch's nodes and buffers with Arrow's own Flatbuffers classes, then decodes every column from
 * the body by the layout the specification gives for its type (the validity bitmap, the offsets,
 * the data, the children). What it reads is compared to what the writer was given, and, in CI,
 * pyarrow reads the same streams (see ci/check_arrow.py), so a writer that only its own reader
 * understands cannot pass.
 */
public final class ArrowSpecReader {

  /** A field as the schema declares it. */
  public record FieldSpec(String name, String type, boolean nullable, List<FieldSpec> children) {}

  /** A batch: its custom metadata, its row count, and its columns as boxed values (null for null). */
  public record Batch(Map<String, String> metadata, long rows, List<List<Object>> columns) {}

  /** A whole stream. */
  public record Stream(List<FieldSpec> fields, Map<String, String> metadata, List<Batch> batches,
      boolean endMarker) {}

  private ArrowSpecReader() {}

  public static Stream read(byte[] bytes) {
    var in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    List<FieldSpec> fields = null;
    Map<String, String> schemaMetadata = null;
    var batches = new ArrayList<Batch>();
    boolean end = false;
    while (in.remaining() >= 8) {
      if (in.position() % 8 != 0) throw new IllegalStateException("message not 8-byte aligned at " + in.position());
      int continuation = in.getInt();
      if (continuation != 0xFFFFFFFF) throw new IllegalStateException("no continuation marker at " + (in.position() - 4));
      int metadataLength = in.getInt();
      if (metadataLength == 0) {
        end = true;
        break;
      }
      if (metadataLength % 8 != 0) throw new IllegalStateException("metadata length " + metadataLength + " not padded");
      var metadata = in.slice().order(ByteOrder.LITTLE_ENDIAN);
      metadata.limit(metadataLength);
      in.position(in.position() + metadataLength);
      var message = Message.getRootAsMessage(metadata);
      int bodyLength = (int) message.bodyLength();
      var body = in.slice().order(ByteOrder.LITTLE_ENDIAN);
      body.limit(bodyLength);
      in.position(in.position() + bodyLength);
      switch (message.headerType()) {
        case MessageHeader.Schema -> {
          var schema = (Schema) message.header(new Schema());
          fields = new ArrayList<>();
          for (int i = 0; i < schema.fieldsLength(); i++) fields.add(field(schema.fields(i)));
          schemaMetadata = new LinkedHashMap<>();
          for (int i = 0; i < schema.customMetadataLength(); i++) {
            schemaMetadata.put(schema.customMetadata(i).key(), schema.customMetadata(i).value());
          }
        }
        case MessageHeader.RecordBatch -> {
          if (fields == null) throw new IllegalStateException("a batch before the schema");
          var batch = (RecordBatch) message.header(new RecordBatch());
          var batchMetadata = new LinkedHashMap<String, String>();
          for (int i = 0; i < message.customMetadataLength(); i++) {
            batchMetadata.put(message.customMetadata(i).key(), message.customMetadata(i).value());
          }
          var cursor = new int[] {0, 0}; // next node, next buffer
          var columns = new ArrayList<List<Object>>();
          for (var f : fields) columns.add(decode(f, batch, body, cursor));
          if (cursor[0] != batch.nodesLength()) throw new IllegalStateException("nodes left over");
          if (cursor[1] != batch.buffersLength()) throw new IllegalStateException("buffers left over");
          batches.add(new Batch(batchMetadata, batch.length(), columns));
        }
        default -> throw new IllegalStateException("unexpected message type " + message.headerType());
      }
    }
    if (fields == null) throw new IllegalStateException("no schema");
    return new Stream(fields, schemaMetadata, batches, end);
  }

  private static FieldSpec field(Field f) {
    String type = switch (f.typeType()) {
      case Type.FloatingPoint -> {
        var fp = (FloatingPoint) f.type(new FloatingPoint());
        yield fp.precision() == Precision.DOUBLE ? "float64" : "float" + fp.precision();
      }
      case Type.Int -> {
        var i = (Int) f.type(new Int());
        yield (i.isSigned() ? "int" : "uint") + i.bitWidth();
      }
      case Type.Bool -> "bool";
      case Type.Utf8 -> "utf8";
      case Type.Binary -> "binary";
      case Type.Timestamp -> {
        var t = (Timestamp) f.type(new Timestamp());
        yield "timestamp[" + (t.unit() == TimeUnit.MICROSECOND ? "us" : "unit" + t.unit()) + "]"
            + (t.timezone() == null ? "" : "," + t.timezone());
      }
      case Type.Struct_ -> "struct";
      case Type.List -> "list";
      default -> "type" + f.typeType();
    };
    var children = new ArrayList<FieldSpec>();
    for (int i = 0; i < f.childrenLength(); i++) children.add(field(f.children(i)));
    return new FieldSpec(f.name(), type, f.nullable(), children);
  }

  private static ByteBuffer buffer(RecordBatch batch, ByteBuffer body, int index) {
    var b = batch.buffers(index);
    if (b.offset() % 8 != 0) throw new IllegalStateException("buffer " + index + " at offset " + b.offset() + " is not aligned");
    var slice = body.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    slice.position((int) b.offset());
    slice.limit((int) (b.offset() + b.length()));
    return slice.slice().order(ByteOrder.LITTLE_ENDIAN);
  }

  private static boolean[] validity(ByteBuffer bitmap, long length, long nullCount) {
    var valid = new boolean[(int) length];
    if (nullCount == 0) {
      if (bitmap.remaining() != 0 && bitmap.remaining() < (length + 7) / 8) {
        throw new IllegalStateException("validity bitmap too short");
      }
      java.util.Arrays.fill(valid, true);
      return valid;
    }
    if (bitmap.remaining() < (length + 7) / 8) throw new IllegalStateException("validity bitmap too short");
    int nulls = 0;
    for (int i = 0; i < length; i++) {
      valid[i] = (bitmap.get(i >>> 3) & (1 << (i & 7))) != 0;
      if (!valid[i]) nulls++;
    }
    if (nulls != nullCount) throw new IllegalStateException("null count " + nullCount + " but " + nulls + " bits clear");
    return valid;
  }

  private static List<Object> decode(FieldSpec f, RecordBatch batch, ByteBuffer body, int[] cursor) {
    var node = batch.nodes(cursor[0]++);
    int n = (int) node.length();
    var valid = validity(buffer(batch, body, cursor[1]++), n, node.nullCount());
    var out = new ArrayList<Object>(n);
    switch (f.type()) {
      case "float64" -> {
        var data = buffer(batch, body, cursor[1]++);
        if (data.remaining() < 8L * n) throw new IllegalStateException("float64 data too short");
        for (int i = 0; i < n; i++) out.add(valid[i] ? data.getDouble(8 * i) : null);
      }
      case "int64", "timestamp[us]" -> {
        var data = buffer(batch, body, cursor[1]++);
        if (data.remaining() < 8L * n) throw new IllegalStateException("int64 data too short");
        for (int i = 0; i < n; i++) out.add(valid[i] ? data.getLong(8 * i) : null);
      }
      case "bool" -> {
        var data = buffer(batch, body, cursor[1]++);
        if (data.remaining() < (n + 7) / 8) throw new IllegalStateException("bool data too short");
        for (int i = 0; i < n; i++) {
          out.add(valid[i] ? (data.get(i >>> 3) & (1 << (i & 7))) != 0 : null);
        }
      }
      case "utf8", "binary" -> {
        var offsets = buffer(batch, body, cursor[1]++);
        var data = buffer(batch, body, cursor[1]++);
        if (offsets.remaining() < 4L * (n + 1)) throw new IllegalStateException("offsets too short");
        for (int i = 0; i < n; i++) {
          int start = offsets.getInt(4 * i);
          int end = offsets.getInt(4 * (i + 1));
          if (end < start || end > data.remaining()) throw new IllegalStateException("bad offsets");
          var bytes = new byte[end - start];
          data.duplicate().position(start).get(bytes);
          out.add(!valid[i] ? null : f.type().equals("utf8") ? new String(bytes, StandardCharsets.UTF_8) : bytes);
        }
      }
      case "struct" -> {
        var children = new ArrayList<List<Object>>();
        for (var child : f.children()) children.add(decode(child, batch, body, cursor));
        for (int i = 0; i < n; i++) {
          if (!valid[i]) {
            out.add(null);
            continue;
          }
          var map = new LinkedHashMap<String, Object>();
          for (int c = 0; c < children.size(); c++) {
            if (children.get(c).size() != n) throw new IllegalStateException("child length differs");
            map.put(f.children().get(c).name(), children.get(c).get(i));
          }
          out.add(map);
        }
      }
      case "list" -> {
        var offsets = buffer(batch, body, cursor[1]++);
        if (offsets.remaining() < 4L * (n + 1)) throw new IllegalStateException("list offsets too short");
        var child = decode(f.children().get(0), batch, body, cursor);
        for (int i = 0; i < n; i++) {
          int start = offsets.getInt(4 * i);
          int end = offsets.getInt(4 * (i + 1));
          if (end < start || end > child.size()) throw new IllegalStateException("bad list offsets");
          out.add(valid[i] ? new ArrayList<>(child.subList(start, end)) : null);
        }
      }
      default -> throw new IllegalStateException("cannot decode " + f.type());
    }
    return out;
  }
}
