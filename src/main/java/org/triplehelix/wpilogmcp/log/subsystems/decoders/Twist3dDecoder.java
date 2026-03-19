package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for WPILib Twist3d struct (dx, dy, dz, rx, ry, rz).
 *
 * <p>Layout (48 bytes):
 * <ul>
 *   <li>double dx (8 bytes) - Change in x
 *   <li>double dy (8 bytes) - Change in y
 *   <li>double dz (8 bytes) - Change in z
 *   <li>double rx (8 bytes) - Rotation around x axis
 *   <li>double ry (8 bytes) - Rotation around y axis
 *   <li>double rz (8 bytes) - Rotation around z axis
 * </ul>
 *
 * @since 0.4.0
 */
public class Twist3dDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 48; // 6 doubles
  private final BinaryReader reader;

  public Twist3dDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "Twist3d";
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
        "dz", reader.readDouble(data, offset + 16),
        "rx", reader.readDouble(data, offset + 24),
        "ry", reader.readDouble(data, offset + 32),
        "rz", reader.readDouble(data, offset + 40));
  }
}
