/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Small writing DSL over {@link WpilogWriter} for building fixture logs.
 *
 * <p>Timestamps are given in seconds and written as microseconds. Entries are declared on first
 * use, so declaration order (entry ids) follows the order in which a fixture first writes each
 * entry — the same way a robot program declares them. Struct schemas are registered the way
 * WPILib's {@code DataLog.addSchema(Struct)} does it — the outer type first, then its nested types,
 * each once — so fixtures carry the same out-of-order {@code /.schema/struct:*} entries a real
 * robot log does (for example {@code Pose2d} before {@code Translation2d}).
 */
public final class FixtureWriter implements AutoCloseable {

  /** Metadata AdvantageKit attaches to every entry it logs. */
  public static final String AKIT_METADATA = "{\"source\":\"AdvantageKit\"}";

  private final WpilogWriter log;
  private final String metadata;
  private final Map<String, Integer> ids = new HashMap<>();
  private final Map<String, String> types = new HashMap<>();
  private final Set<String> schemas = new HashSet<>();

  /**
   * Opens a fixture file for writing.
   *
   * @param path The file to create (parent directories are created)
   * @param metadata Metadata to attach to every entry (e.g. {@link #AKIT_METADATA}), or ""
   */
  public FixtureWriter(Path path, String metadata) throws IOException {
    if (path.getParent() != null) Files.createDirectories(path.getParent());
    Files.deleteIfExists(path);
    this.log = new WpilogWriter(path, "");
    this.metadata = metadata;
  }

  /** Converts seconds to WPILOG microseconds. */
  public static long micros(double seconds) {
    return Math.round(seconds * 1_000_000.0);
  }

  private int entry(String name, String type, double t) {
    var existing = ids.get(name);
    if (existing != null) {
      if (!types.get(name).equals(type)) {
        throw new IllegalArgumentException(
            "Entry " + name + " already declared as " + types.get(name) + ", not " + type);
      }
      return existing;
    }
    try {
      int id = log.start(name, type, metadata, micros(t));
      ids.put(name, id);
      types.put(name, type);
      return id;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private FixtureWriter write(String name, String type, double t, byte[] payload) {
    int id = entry(name, type, t);
    try {
      log.append(id, micros(t), payload);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return this;
  }

  /** Declares an entry without writing a value (a real log can hold entries with no data). */
  public FixtureWriter declare(String name, String type, double t) {
    entry(name, type, t);
    return this;
  }

  public FixtureWriter bool(String name, double t, boolean value) {
    return write(name, "boolean", t, WpilogWriter.encodeBoolean(value));
  }

  public FixtureWriter i64(String name, double t, long value) {
    return write(name, "int64", t, WpilogWriter.encodeInt64(value));
  }

  public FixtureWriter flt(String name, double t, float value) {
    return write(name, "float", t, WpilogWriter.encodeFloat(value));
  }

  public FixtureWriter dbl(String name, double t, double value) {
    return write(name, "double", t, WpilogWriter.encodeDouble(value));
  }

  public FixtureWriter str(String name, double t, String value) {
    return write(name, "string", t, WpilogWriter.encodeString(value));
  }

  public FixtureWriter json(String name, double t, String value) {
    return write(name, "json", t, WpilogWriter.encodeString(value));
  }

  public FixtureWriter strArr(String name, double t, String... values) {
    return write(name, "string[]", t, WpilogWriter.encodeStringArray(values));
  }

  public FixtureWriter dblArr(String name, double t, double... values) {
    return write(name, "double[]", t, WpilogWriter.encodeDoubleArray(values));
  }

  public FixtureWriter fltArr(String name, double t, float... values) {
    return write(name, "float[]", t, WpilogWriter.encodeFloatArray(values));
  }

  public FixtureWriter i64Arr(String name, double t, long... values) {
    return write(name, "int64[]", t, WpilogWriter.encodeInt64Array(values));
  }

  public FixtureWriter boolArr(String name, double t, boolean... values) {
    return write(name, "boolean[]", t, WpilogWriter.encodeBooleanArray(values));
  }

  /** Registers a WPILib struct type's schema and then its nested types, each once. */
  public FixtureWriter schema(WpiStructs.Type type, double t) {
    if (schemas.add(type.name())) {
      write("/.schema/struct:" + type.name(), "structschema", t,
          WpilogWriter.encodeString(type.schema()));
      for (var nested : type.nested()) schema(nested, t);
    }
    return this;
  }

  /** Writes one struct record, registering its schema first. */
  public FixtureWriter struct(String name, WpiStructs.Type type, double t, byte[] record) {
    if (record.length != type.size()) {
      throw new IllegalArgumentException(type.name() + " record must be " + type.size()
          + " bytes, got " + record.length);
    }
    schema(type, t);
    return write(name, "struct:" + type.name(), t, record);
  }

  /** Writes a struct array ({@code struct:Name[]}) from individual records. */
  public FixtureWriter structArr(String name, WpiStructs.Type type, double t, byte[]... records) {
    for (var record : records) {
      if (record.length != type.size()) {
        throw new IllegalArgumentException(type.name() + " record must be " + type.size()
            + " bytes, got " + record.length);
      }
    }
    schema(type, t);
    return write(name, "struct:" + type.name() + "[]", t, WpiStructs.concat(records));
  }

  /**
   * Registers a hand-written struct schema, as a team's own struct (or a vendor's) would appear:
   * entry {@code /.schema/struct:<typeName>}, type {@code structschema}.
   */
  public FixtureWriter schema(String typeName, String schema, double t) {
    if (schemas.add(typeName)) {
      write("/.schema/struct:" + typeName, "structschema", t, WpilogWriter.encodeString(schema));
    }
    return this;
  }

  /** Writes raw bytes under an explicit type string (for hand-packed custom structs). */
  public FixtureWriter raw(String name, String type, double t, byte[] data) {
    return write(name, type, t, data);
  }

  /** Starts a little-endian buffer for hand-packing custom struct records. */
  public static ByteBuffer le(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  @Override
  public void close() throws IOException {
    log.close();
  }
}
