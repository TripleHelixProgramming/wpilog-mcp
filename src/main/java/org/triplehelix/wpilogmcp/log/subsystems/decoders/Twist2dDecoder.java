package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for WPILib Twist2d struct (dx, dy, dtheta).
 *
 * <p>Layout (24 bytes):
 * <ul>
 *   <li>double dx (8 bytes) - Change in x
 *   <li>double dy (8 bytes) - Change in y
 *   <li>double dtheta (8 bytes) - Change in angle
 * </ul>
 *
 * @since 0.4.0
 */
public class Twist2dDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 24; // 3 doubles
  private final BinaryReader reader;

  public Twist2dDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "Twist2d";
  }

  @Override
  public int getStructSize() {
    return STRUCT_SIZE;
  }

  @Override
  public Map<String, Object> decode(byte[] data, int offset) {
    if (data.length < offset + STRUCT_SIZE) {
      return Map.of("error", "insufficient data");
    }

    return Map.of(
        "dx", reader.readDouble(data, offset),
        "dy", reader.readDouble(data, offset + 8),
        "dtheta", reader.readDouble(data, offset + 16));
  }
}
