package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for WPILib Pose3d struct (translation + quaternion rotation).
 *
 * <p>Layout (56 bytes):
 * <ul>
 *   <li>double x (8 bytes)
 *   <li>double y (8 bytes)
 *   <li>double z (8 bytes)
 *   <li>double qw (8 bytes) - Quaternion w component
 *   <li>double qx (8 bytes) - Quaternion x component
 *   <li>double qy (8 bytes) - Quaternion y component
 *   <li>double qz (8 bytes) - Quaternion z component
 * </ul>
 *
 * @since 0.4.0
 */
public class Pose3dDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 56; // 7 doubles
  private final BinaryReader reader;

  public Pose3dDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "Pose3d";
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

    double x = reader.readDouble(data, offset);
    double y = reader.readDouble(data, offset + 8);
    double z = reader.readDouble(data, offset + 16);
    double qw = reader.readDouble(data, offset + 24);
    double qx = reader.readDouble(data, offset + 32);
    double qy = reader.readDouble(data, offset + 40);
    double qz = reader.readDouble(data, offset + 48);

    return Map.of(
        "x", x,
        "y", y,
        "z", z,
        "qw", qw,
        "qx", qx,
        "qy", qy,
        "qz", qz);
  }
}
