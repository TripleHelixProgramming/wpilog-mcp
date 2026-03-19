package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for WPILib Rotation2d struct (angle in radians).
 *
 * <p>Layout (8 bytes):
 * <ul>
 *   <li>double angle (8 bytes) - Rotation in radians
 * </ul>
 *
 * @since 0.4.0
 */
public class Rotation2dDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 8; // 1 double
  private final BinaryReader reader;

  public Rotation2dDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "Rotation2d";
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

    double rad = reader.readDouble(data, offset);
    return Map.of("radians", rad, "degrees", Math.toDegrees(rad));
  }
}
