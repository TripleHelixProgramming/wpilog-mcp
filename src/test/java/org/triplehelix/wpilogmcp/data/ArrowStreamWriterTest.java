/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.data.ArrowType.Field;

/** The writer against a reader written from the specification ({@link ArrowSpecReader}). */
class ArrowStreamWriterTest {

  private static List<ColumnBuilder> builders(List<Field> fields) {
    var out = new ArrayList<ColumnBuilder>();
    for (var f : fields) out.add(ColumnBuilder.of(f.type()));
    return out;
  }

  @Test
  @DisplayName("every type round-trips with its nulls, over two batches, with the metadata and the end marker")
  void roundTrip() throws IOException {
    var pose = new ArrowType.Struct(List.of(
        new Field("x", new ArrowType.Float64(), false),
        new Field("name", new ArrowType.Utf8(), true)));
    var fields = List.of(
        new Field("timestamp", new ArrowType.TimestampMicros(), false),
        new Field("d", new ArrowType.Float64(), true),
        new Field("i", new ArrowType.Int64(), true),
        new Field("b", new ArrowType.Bool(), true),
        new Field("s", new ArrowType.Utf8(), true),
        new Field("raw", new ArrowType.Binary(), true),
        new Field("pose", pose, true),
        new Field("values", new ArrowType.ListOf(new Field("item", new ArrowType.Float64(), false)), true),
        new Field("poses", new ArrowType.ListOf(new Field("item", pose, false)), false));
    var out = new ByteArrayOutputStream();
    var writer = new ArrowStreamWriter(out, fields);
    writer.writeSchema(Map.of("version", "0.9.1", "inputs", "{\"log\":\"x\"}"));

    var columns = builders(fields);
    // Batch 1: eleven rows, so the bitmaps span two bytes
    int rows = 11;
    var expectedD = new ArrayList<Object>();
    var expectedI = new ArrayList<Object>();
    var expectedB = new ArrayList<Object>();
    var expectedS = new ArrayList<Object>();
    for (int r = 0; r < rows; r++) {
      columns.get(0).append(1_000_000L * r);
      Double d = r == 3 ? null : r == 5 ? Double.NaN : r * 1.5;
      columns.get(1).append(d);
      expectedD.add(d);
      Long i = r == 0 ? null : (long) -r;
      columns.get(2).append(i);
      expectedI.add(i);
      Boolean b = r == 9 ? null : r % 3 == 0;
      columns.get(3).append(b);
      expectedB.add(b);
      String s = r == 1 ? null : r == 2 ? "" : "row " + r + " é";
      columns.get(4).append(s);
      expectedS.add(s);
      columns.get(5).append(r == 4 ? null : new byte[] {(byte) r, (byte) 0xFF});
      var p = new LinkedHashMap<String, Object>();
      p.put("x", r * 0.25);
      p.put("name", r == 7 ? null : "p" + r);
      columns.get(6).append(r == 6 ? null : p);
      columns.get(7).append(r == 8 ? null : r == 2 ? List.of() : List.of(1.0 * r, 2.0 * r));
      var q = new LinkedHashMap<String, Object>();
      q.put("x", 100.0 + r);
      q.put("name", "q");
      columns.get(8).append(r % 2 == 0 ? List.of(q, q) : List.of());
    }
    writer.writeBatch(columns, Map.of("entry", "/A"));
    assertEquals(0, columns.get(0).length(), "the builders are reset after a batch");
    // Batch 2: one row, nothing null
    columns.get(0).append(7L);
    columns.get(1).append(2.0);
    columns.get(2).append(3L);
    columns.get(3).append(true);
    columns.get(4).append("x");
    columns.get(5).append(new byte[0]);
    columns.get(6).append(Map.of("x", 1.0, "name", "n"));
    columns.get(7).append(new double[] {9.0});
    columns.get(8).append(List.of());
    writer.writeBatch(columns, Map.of("entry", "/B"));
    writer.finish();
    byte[] bytes = out.toByteArray();
    assertEquals(bytes.length, writer.bytesWritten());
    // Left for the other readers: pyarrow (ci/check_arrow.py) opens it, and the webview's reader
    // (vscode-extension, arrowStream.test.ts) checks its nulls of every type against this test's data
    var samples = java.nio.file.Path.of("build", "arrow-samples");
    java.nio.file.Files.createDirectories(samples);
    java.nio.file.Files.write(samples.resolve("writer_roundtrip.arrow"), bytes);

    var stream = ArrowSpecReader.read(bytes);
    assertTrue(stream.endMarker());
    assertEquals(Map.of("version", "0.9.1", "inputs", "{\"log\":\"x\"}"), stream.metadata());
    assertEquals(List.of("timestamp", "d", "i", "b", "s", "raw", "pose", "values", "poses"),
        stream.fields().stream().map(ArrowSpecReader.FieldSpec::name).toList());
    assertEquals(List.of("timestamp[us]", "float64", "int64", "bool", "utf8", "binary", "struct", "list", "list"),
        stream.fields().stream().map(ArrowSpecReader.FieldSpec::type).toList());
    assertEquals(List.of("x", "name"), stream.fields().get(6).children().stream().map(ArrowSpecReader.FieldSpec::name).toList());
    assertEquals("float64", stream.fields().get(7).children().get(0).type());
    assertEquals("struct", stream.fields().get(8).children().get(0).type());
    assertTrue(stream.fields().get(1).nullable());
    assertTrue(!stream.fields().get(0).nullable());

    assertEquals(2, stream.batches().size());
    var first = stream.batches().get(0);
    assertEquals(Map.of("entry", "/A"), first.metadata());
    assertEquals(rows, first.rows());
    for (int r = 0; r < rows; r++) assertEquals(1_000_000L * r, first.columns().get(0).get(r));
    assertEquals(expectedD, first.columns().get(1));
    assertEquals(expectedI, first.columns().get(2));
    assertEquals(expectedB, first.columns().get(3));
    assertEquals(expectedS, first.columns().get(4));
    assertNull(first.columns().get(5).get(4));
    assertTrue(Arrays.equals(new byte[] {3, (byte) 0xFF}, (byte[]) first.columns().get(5).get(3)));
    assertNull(first.columns().get(6).get(6));
    // Row 7's struct has a null name: the struct is valid, the field inside it is not
    assertEquals(Map.of("x", 1.75), withoutNulls((Map<?, ?>) first.columns().get(6).get(7)));
    assertNull(((Map<?, ?>) first.columns().get(6).get(7)).get("name"));
    assertEquals(Map.of("x", 0.5, "name", "p2"), first.columns().get(6).get(2));
    assertNull(first.columns().get(7).get(8));
    assertEquals(List.of(), first.columns().get(7).get(2));
    assertEquals(List.of(3.0, 6.0), first.columns().get(7).get(3));
    assertEquals(List.of(), first.columns().get(8).get(1));
    assertEquals(List.of(Map.of("x", 104.0, "name", "q"), Map.of("x", 104.0, "name", "q")), first.columns().get(8).get(4));

    var second = stream.batches().get(1);
    assertEquals(Map.of("entry", "/B"), second.metadata());
    assertEquals(1, second.rows());
    assertEquals(List.of(7L), second.columns().get(0));
    assertEquals(List.of(2.0), second.columns().get(1));
    assertEquals(List.of(true), second.columns().get(3));
    assertEquals(List.of("x"), second.columns().get(4));
    assertEquals(List.of(Map.of("x", 1.0, "name", "n")), second.columns().get(6));
    assertEquals(List.of(List.of(9.0)), second.columns().get(7));
  }

  private static Map<String, Object> withoutNulls(Map<?, ?> map) {
    var out = new LinkedHashMap<String, Object>();
    map.forEach((k, v) -> {
      if (v != null) out.put((String) k, v);
    });
    return out;
  }

  @Test
  @DisplayName("a column with no null has no validity bitmap, and every buffer and message is aligned")
  void layout() throws IOException {
    var fields = List.of(new Field("v", new ArrowType.Float64(), true));
    var out = new ByteArrayOutputStream();
    var writer = new ArrowStreamWriter(out, fields);
    writer.writeSchema(Map.of());
    var columns = builders(fields);
    for (int i = 0; i < 3; i++) columns.get(0).append(1.0 * i);
    writer.writeBatch(columns, Map.of());
    writer.finish();
    // The reader checks the alignment of every message and buffer, and that an absent bitmap
    // comes with a null count of zero
    var stream = ArrowSpecReader.read(out.toByteArray());
    assertEquals(List.of(0.0, 1.0, 2.0), stream.batches().get(0).columns().get(0));
    assertEquals(0, out.size() % 8);
    // Three doubles are 24 bytes: a body of 24 (no bitmap) plus the padded headers
    var bytes = out.toByteArray();
    assertEquals(0xFFFFFFFF, java.nio.ByteBuffer.wrap(bytes, bytes.length - 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
    assertEquals(0, java.nio.ByteBuffer.wrap(bytes, bytes.length - 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
  }

  @Test
  @DisplayName("an empty batch carries its metadata: how a stream says the file changed")
  void emptyBatch() throws IOException {
    var fields = List.of(new Field("v", new ArrowType.Int64(), false));
    var out = new ByteArrayOutputStream();
    var writer = new ArrowStreamWriter(out, fields);
    writer.writeSchema(Map.of());
    writer.writeBatch(builders(fields), Map.of("file_changed", "size 10 to 20"));
    writer.finish();
    var stream = ArrowSpecReader.read(out.toByteArray());
    assertEquals(1, stream.batches().size());
    assertEquals(0, stream.batches().get(0).rows());
    assertEquals("size 10 to 20", stream.batches().get(0).metadata().get("file_changed"));
  }

  @Test
  @DisplayName("a batch before the schema, the wrong number of columns, and ragged columns are refused")
  void misuse() throws IOException {
    var fields = List.of(new Field("a", new ArrowType.Int64(), false), new Field("b", new ArrowType.Int64(), false));
    var out = new ByteArrayOutputStream();
    var writer = new ArrowStreamWriter(out, fields);
    var columns = builders(fields);
    assertThrows(IllegalStateException.class, () -> writer.writeBatch(columns, Map.of()));
    writer.writeSchema(Map.of());
    assertThrows(IllegalStateException.class, () -> writer.writeSchema(Map.of()));
    assertThrows(IllegalArgumentException.class, () -> writer.writeBatch(columns.subList(0, 1), Map.of()));
    columns.get(0).append(1L);
    assertThrows(IllegalArgumentException.class, () -> writer.writeBatch(columns, Map.of()));
    writer.finish();
    assertThrows(IllegalStateException.class, () -> writer.writeBatch(columns, Map.of()));
  }
}
