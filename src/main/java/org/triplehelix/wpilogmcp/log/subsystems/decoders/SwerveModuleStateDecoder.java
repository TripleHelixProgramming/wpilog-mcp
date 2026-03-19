package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for WPILib SwerveModuleState struct (speed, angle).
 *
 * <p>Layout (16 bytes):
 * <ul>
 *   <li>double speed (8 bytes) - Module speed in m/s
 *   <li>double angle (8 bytes) - Module angle in radians
 * </ul>
 *
 * @since 0.4.0
 */
public class SwerveModuleStateDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 16; // 2 doubles
  private final BinaryReader reader;

  public SwerveModuleStateDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "SwerveModuleState";
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

    double speed = reader.readDouble(data, offset);
    double angle = reader.readDouble(data, offset + 8);

    return Map.of(
        "speed_mps", speed,
        "angle_rad", angle,
        "angle_deg", Math.toDegrees(angle));
  }
}
