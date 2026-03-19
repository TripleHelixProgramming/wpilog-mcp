package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for WPILib ChassisSpeeds struct (vx, vy, omega).
 *
 * <p>Layout (24 bytes):
 * <ul>
 *   <li>double vx (8 bytes) - Forward velocity in m/s
 *   <li>double vy (8 bytes) - Sideways velocity in m/s
 *   <li>double omega (8 bytes) - Angular velocity in rad/s
 * </ul>
 *
 * @since 0.4.0
 */
public class ChassisSpeedsDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 24; // 3 doubles
  private final BinaryReader reader;

  public ChassisSpeedsDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "ChassisSpeeds";
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
        "vx_mps", reader.readDouble(data, offset),
        "vy_mps", reader.readDouble(data, offset + 8),
        "omega_radps", reader.readDouble(data, offset + 16));
  }
}
