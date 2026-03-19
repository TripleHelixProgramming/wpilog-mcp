package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for TargetObservation struct (vision target detection).
 *
 * <p>Layout (40 bytes):
 * <ul>
 *   <li>Rotation2d yaw (8 bytes) - double radians
 *   <li>Rotation2d pitch (8 bytes) - double radians
 *   <li>Rotation2d skew (8 bytes) - double radians
 *   <li>double area (8 bytes)
 *   <li>float confidence (4 bytes)
 *   <li>int32 objectID (4 bytes)
 * </ul>
 *
 * @since 0.4.0
 */
public class TargetObservationDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 40;
  private final BinaryReader reader;

  public TargetObservationDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "TargetObservation";
  }

  @Override
  public int getStructSize() {
    return STRUCT_SIZE;
  }

  @Override
  public Map<String, Object> decode(byte[] data, int offset) {
    if (data.length < offset + STRUCT_SIZE) {
      return Map.of("error", "insufficient data for TargetObservation");
    }

    double yawRad = reader.readDouble(data, offset);
    double pitchRad = reader.readDouble(data, offset + 8);
    double skewRad = reader.readDouble(data, offset + 16);
    double area = reader.readDouble(data, offset + 24);
    float confidence = reader.readFloat(data, offset + 32);
    int objectID = reader.readInt32(data, offset + 36);

    return Map.of(
        "yaw_rad", yawRad,
        "yaw_deg", Math.toDegrees(yawRad),
        "pitch_rad", pitchRad,
        "pitch_deg", Math.toDegrees(pitchRad),
        "skew_rad", skewRad,
        "skew_deg", Math.toDegrees(skewRad),
        "area", area,
        "confidence", confidence,
        "objectID", objectID);
  }
}
