/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log.struct;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A decoded struct value: an immutable map from field name to value, in schema order.
 *
 * <p>Compact on purpose — a log holds millions of these. The field names are one array shared
 * by every value of the struct type; each value holds only its own values array (about a
 * quarter of a {@code LinkedHashMap}'s footprint). Lookup is a scan of the (short) field list.
 *
 * @since 0.9.0
 */
public final class StructMap extends AbstractMap<String, Object> {

  private final String[] keys;
  private final Object[] values;

  /**
   * @param keys Field names, shared, never modified
   * @param values Field values, owned by this map
   */
  StructMap(String[] keys, Object[] values) {
    this.keys = keys;
    this.values = values;
  }

  @Override
  public int size() {
    return keys.length;
  }

  @Override
  public Object get(Object key) {
    int i = indexOf(key);
    return i >= 0 ? values[i] : null;
  }

  @Override
  public boolean containsKey(Object key) {
    return indexOf(key) >= 0;
  }

  private int indexOf(Object key) {
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] == key || keys[i].equals(key)) return i;
    }
    return -1;
  }

  @Override
  public Set<Entry<String, Object>> entrySet() {
    return new AbstractSet<>() {
      @Override
      public int size() {
        return keys.length;
      }

      @Override
      public Iterator<Entry<String, Object>> iterator() {
        return new Iterator<>() {
          private int next;

          @Override
          public boolean hasNext() {
            return next < keys.length;
          }

          @Override
          public Entry<String, Object> next() {
            if (next >= keys.length) throw new NoSuchElementException();
            var entry = new SimpleImmutableEntry<>(keys[next], values[next]);
            next++;
            return entry;
          }
        };
      }
    };
  }
}
