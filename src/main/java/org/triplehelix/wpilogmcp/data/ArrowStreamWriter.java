/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.data;

import com.google.flatbuffers.FlatBufferBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.arrow.flatbuf.Bool;
import org.apache.arrow.flatbuf.Buffer;
import org.apache.arrow.flatbuf.Endianness;
import org.apache.arrow.flatbuf.Field;
import org.apache.arrow.flatbuf.FieldNode;
import org.apache.arrow.flatbuf.FloatingPoint;
import org.apache.arrow.flatbuf.Int;
import org.apache.arrow.flatbuf.KeyValue;
import org.apache.arrow.flatbuf.Message;
import org.apache.arrow.flatbuf.MessageHeader;
import org.apache.arrow.flatbuf.MetadataVersion;
import org.apache.arrow.flatbuf.Precision;
import org.apache.arrow.flatbuf.RecordBatch;
import org.apache.arrow.flatbuf.Schema;
import org.apache.arrow.flatbuf.Struct_;
import org.apache.arrow.flatbuf.TimeUnit;
import org.apache.arrow.flatbuf.Timestamp;
import org.apache.arrow.flatbuf.Type;
import org.apache.arrow.flatbuf.Utf8;

/**
 * Writes the Apache Arrow IPC streaming format: a schema message, record batches, and the
 * end-of-stream marker, each message encapsulated as the format requires (a continuation marker,
 * the metadata's length, the Flatbuffers metadata padded to eight bytes, then the body, whose
 * buffers are each padded to eight bytes). The metadata is built with Arrow's own Flatbuffers
 * classes (arrow-format) and the bodies by {@link ColumnBuilder}, so the server writes Arrow
 * without arrow-vector or arrow-memory, whose off-heap allocator would put memory outside the
 * heap the cache and the heap limit govern (EXPLORER_PLAN.md §6).
 *
 * <p>The stream is written as it is produced: a batch goes out when the caller says, so no entry
 * is held in memory whole. A reader is given the schema's custom metadata once and each batch's
 * custom metadata with the batch, which is how the data endpoint tags a batch with its entry.
 */
public final class ArrowStreamWriter {
  private static final int CONTINUATION = 0xFFFFFFFF;
  private static final int ALIGNMENT = 8;

  private final OutputStream out;
  private final List<ArrowType.Field> fields;
  private boolean schemaWritten;
  private boolean finished;
  private long bytesWritten;

  /**
   * @param out Where the stream goes; not closed by this writer
   * @param fields The schema's fields, in order
   */
  public ArrowStreamWriter(OutputStream out, List<ArrowType.Field> fields) {
    this.out = out;
    this.fields = List.copyOf(fields);
  }

  /** How many bytes have been written so far. */
  public long bytesWritten() {
    return bytesWritten;
  }

  /** Writes the schema message, with its custom metadata; once, before any batch. */
  public void writeSchema(Map<String, String> metadata) throws IOException {
    if (schemaWritten) throw new IllegalStateException("the schema was written already");
    var b = new FlatBufferBuilder(1024);
    int[] fieldOffsets = new int[fields.size()];
    for (int i = 0; i < fields.size(); i++) fieldOffsets[i] = field(b, fields.get(i));
    int fieldsVector = Schema.createFieldsVector(b, fieldOffsets);
    int schemaMetadata = metadata(b, metadata, true);
    int schema = Schema.createSchema(b, Endianness.Little, fieldsVector, schemaMetadata, 0);
    int message = Message.createMessage(b, MetadataVersion.V5, MessageHeader.Schema, schema, 0, 0);
    b.finish(message);
    writeMessage(b, List.of());
    schemaWritten = true;
  }

  /**
   * Writes one record batch from the columns, one per schema field, each holding the same number
   * of values, then resets them for the next batch. {@code metadata} is the batch's custom
   * metadata (may be empty). A batch of no rows is written as such: the endpoint's final empty
   * batch says a file changed.
   */
  public void writeBatch(List<ColumnBuilder> columns, Map<String, String> metadata)
      throws IOException {
    if (!schemaWritten) throw new IllegalStateException("write the schema first");
    if (finished) throw new IllegalStateException("the stream is finished");
    if (columns.size() != fields.size()) {
      throw new IllegalArgumentException(columns.size() + " columns for " + fields.size()
          + " fields");
    }
    int rows = columns.isEmpty() ? 0 : columns.get(0).length();
    for (var column : columns) {
      if (column.length() != rows) {
        throw new IllegalArgumentException("columns of different lengths: " + column.length()
            + " and " + rows);
      }
    }
    var nodes = new ArrayList<ColumnBuilder.Node>();
    var buffers = new ArrayList<ColumnBuilder.Bytes>();
    for (var column : columns) column.collect(nodes, buffers);

    var b = new FlatBufferBuilder(1024);
    int batchMetadata = metadata(b, metadata, false);
    // Struct vectors are built in reverse
    RecordBatch.startNodesVector(b, nodes.size());
    for (int i = nodes.size() - 1; i >= 0; i--) {
      FieldNode.createFieldNode(b, nodes.get(i).length(), nodes.get(i).nullCount());
    }
    int nodesVector = b.endVector();
    long[] offsets = new long[buffers.size()];
    long bodyLength = 0;
    for (int i = 0; i < buffers.size(); i++) {
      offsets[i] = bodyLength;
      bodyLength += padded(buffers.get(i).length());
    }
    RecordBatch.startBuffersVector(b, buffers.size());
    for (int i = buffers.size() - 1; i >= 0; i--) {
      Buffer.createBuffer(b, offsets[i], buffers.get(i).length());
    }
    int buffersVector = b.endVector();
    int batch = RecordBatch.createRecordBatch(b, rows, nodesVector, buffersVector, 0, 0);
    int message = Message.createMessage(b, MetadataVersion.V5, MessageHeader.RecordBatch, batch,
        bodyLength, batchMetadata);
    b.finish(message);
    writeMessage(b, buffers);
    for (var column : columns) column.reset();
  }

  /** Writes the end-of-stream marker; nothing may follow. */
  public void finish() throws IOException {
    if (finished) return;
    if (!schemaWritten) throw new IllegalStateException("write the schema first");
    writeInt(CONTINUATION);
    writeInt(0);
    bytesWritten += 8;
    out.flush();
    finished = true;
  }

  // ---- the metadata ----

  private int field(FlatBufferBuilder b, ArrowType.Field field) {
    int name = b.createString(field.name());
    int[] childOffsets;
    byte typeType;
    int type;
    var t = field.type();
    if (t instanceof ArrowType.Float64) {
      typeType = Type.FloatingPoint;
      type = FloatingPoint.createFloatingPoint(b, Precision.DOUBLE);
      childOffsets = new int[0];
    } else if (t instanceof ArrowType.Int64) {
      typeType = Type.Int;
      type = Int.createInt(b, 64, true);
      childOffsets = new int[0];
    } else if (t instanceof ArrowType.Bool) {
      typeType = Type.Bool;
      Bool.startBool(b);
      type = Bool.endBool(b);
      childOffsets = new int[0];
    } else if (t instanceof ArrowType.Utf8) {
      typeType = Type.Utf8;
      Utf8.startUtf8(b);
      type = Utf8.endUtf8(b);
      childOffsets = new int[0];
    } else if (t instanceof ArrowType.Binary) {
      typeType = Type.Binary;
      org.apache.arrow.flatbuf.Binary.startBinary(b);
      type = org.apache.arrow.flatbuf.Binary.endBinary(b);
      childOffsets = new int[0];
    } else if (t instanceof ArrowType.TimestampMicros) {
      typeType = Type.Timestamp;
      type = Timestamp.createTimestamp(b, TimeUnit.MICROSECOND, 0);
      childOffsets = new int[0];
    } else if (t instanceof ArrowType.Struct s) {
      childOffsets = new int[s.fields().size()];
      for (int i = 0; i < childOffsets.length; i++) childOffsets[i] = field(b, s.fields().get(i));
      typeType = Type.Struct_;
      Struct_.startStruct_(b);
      type = Struct_.endStruct_(b);
    } else if (t instanceof ArrowType.ListOf l) {
      childOffsets = new int[] {field(b, l.child())};
      typeType = Type.List;
      org.apache.arrow.flatbuf.List.startList(b);
      type = org.apache.arrow.flatbuf.List.endList(b);
    } else {
      throw new IllegalArgumentException("no Arrow type for " + t);
    }
    int children = Field.createChildrenVector(b, childOffsets);
    return Field.createField(b, name, field.nullable(), typeType, type, 0, children, 0);
  }

  private static int metadata(FlatBufferBuilder b, Map<String, String> metadata, boolean schema) {
    if (metadata == null || metadata.isEmpty()) return 0;
    int[] pairs = new int[metadata.size()];
    int i = 0;
    for (var e : metadata.entrySet()) {
      int key = b.createString(e.getKey());
      int value = b.createString(e.getValue());
      pairs[i++] = KeyValue.createKeyValue(b, key, value);
    }
    return schema ? Schema.createCustomMetadataVector(b, pairs)
        : Message.createCustomMetadataVector(b, pairs);
  }

  // ---- the encapsulation ----

  private void writeMessage(FlatBufferBuilder b, List<ColumnBuilder.Bytes> body)
      throws IOException {
    byte[] metadata = b.sizedByteArray();
    // The prefix (continuation and length, 8 bytes) plus the metadata is padded to the alignment
    int paddedMetadata = (int) padded(metadata.length);
    writeInt(CONTINUATION);
    writeInt(paddedMetadata);
    out.write(metadata);
    pad(paddedMetadata - metadata.length);
    bytesWritten += 8 + paddedMetadata;
    for (var buffer : body) {
      out.write(buffer.array(), 0, buffer.length());
      pad((int) (padded(buffer.length()) - buffer.length()));
      bytesWritten += padded(buffer.length());
    }
  }

  private static long padded(long length) {
    return (length + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT;
  }

  private void pad(int bytes) throws IOException {
    for (int i = 0; i < bytes; i++) out.write(0);
  }

  private void writeInt(int v) throws IOException {
    out.write(v & 0xFF);
    out.write((v >>> 8) & 0xFF);
    out.write((v >>> 16) & 0xFF);
    out.write((v >>> 24) & 0xFF);
  }
}
