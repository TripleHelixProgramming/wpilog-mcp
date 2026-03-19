package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for WPILib Rotation3d struct (quaternion).
 *
 * <p>Layout (32 bytes):
 * <ul>
 *   <li>double qw (8 bytes) - Quaternion w component
 *   <li>double qx (8 bytes) - Quaternion x component
 *   <li>double qy (8 bytes) - Quaternion y component
 *   <li>double qz (8 bytes) - Quaternion z component
 * </ul>
 *
 * @since 0.4.0
 */
public class Rotation3dDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 32; // 4 doubles
  private final BinaryReader reader;

  public Rotation3dDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "Rotation3d";
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
        "qw", reader.readDouble(data, offset),
        "qx", reader.readDouble(data, offset + 8),
        "qy", reader.readDouble(data, offset + 16),
        "qz", reader.readDouble(data, offset + 24));
  }
}
