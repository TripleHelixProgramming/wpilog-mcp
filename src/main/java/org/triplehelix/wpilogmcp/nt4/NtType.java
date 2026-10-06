/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.nt4;

import java.util.List;
import java.util.Objects;

/**
 * An announcement's string owns the type. A binary code selects only the decoder: json is not
 * string, and structschema, struct arrays, protobufs and unknown vendor types stay opaque bytes.
 */
public record NtType(int code, String nt4, String wpilog) {
  public NtType {
    Objects.requireNonNull(nt4);
    Objects.requireNonNull(wpilog);
  }

  public static NtType fromNt4(String type) {
    Objects.requireNonNull(type);
    return new NtType(switch (type) {
      case "boolean" -> 0;
      case "double" -> 1;
      case "int" -> 2;
      case "float" -> 3;
      case "string", "json" -> 4;
      case "boolean[]" -> 16;
      case "double[]" -> 17;
      case "int[]" -> 18;
      case "float[]" -> 19;
      case "string[]" -> 20;
      default -> 5;
    }, type, switch (type) {
      case "int" -> "int64";
      case "int[]" -> "int64[]";
      default -> type;
    });
  }

  public static NtType fromWpilog(String type) {
    return fromNt4(switch (type) {
      case "int64" -> "int";
      case "int64[]" -> "int[]";
      default -> type;
    });
  }

  /** Validate the MessagePack family without coercing a wrong family into a plausible value. */
  static Object checkedValue(int code, Object value) {
    boolean valid = switch (code) {
      case 0 -> value instanceof Boolean;
      case 1 -> value instanceof Double;
      case 2 -> value instanceof Long || value instanceof Integer
          || value instanceof Short || value instanceof Byte;
      case 3 -> value instanceof Float;
      case 4 -> value instanceof String;
      case 5 -> value instanceof byte[];
      case 16, 17, 18, 19, 20 -> value instanceof List<?> list
          && list.stream().allMatch(v -> {
            try { checkedValue(code - 16, v); return true; }
            catch (IllegalArgumentException e) { return false; }
          });
      default -> false;
    };
    if (!valid) throw new IllegalArgumentException("Invalid NT4 value for type code " + code);
    if (value instanceof byte[] bytes) return bytes.clone();
    if (value instanceof List<?> list) return List.copyOf(list);
    if (code == 2) return ((Number) value).longValue();
    return value;
  }
}
