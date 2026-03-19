package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.LinkedHashMap;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for PoseObservation struct (vision pose estimate).
 *
 * <p>Layout (88 bytes):
 * <ul>
 *   <li>double timestamp (8 bytes)
 *   <li>Pose3d pose (56 bytes) - Translation3d(24) + Rotation3d/Quaternion(32)
 *   <li>double ambiguity (8 bytes)
 *   <li>int32 tagCount (4 bytes)
 *   <li>double averageTagDistance (8 bytes)
 *   <li>int32 type (4 bytes) - PoseObservationType enum
 * </ul>
 *
 * @since 0.4.0
 */
public class PoseObservationDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 88;
  private static final String[] POSE_OBSERVATION_TYPES = {"MEGATAG_1", "MEGATAG_2", "PHOTONVISION"};
  private final BinaryReader reader;

  public PoseObservationDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "PoseObservation";
  }

  @Override
  public int getStructSize() {
    return STRUCT_SIZE;
  }

  @Override
  public Map<String, Object> decode(byte[] data, int offset) {
    if (data.length < offset + STRUCT_SIZE) {
      return Map.of("error", "insufficient data for PoseObservation");
    }

    double timestamp = reader.readDouble(data, offset);

    // Decode embedded Pose3d (56 bytes): Translation3d (x, y, z) + Quaternion (w, x, y, z)
    double poseX = reader.readDouble(data, offset + 8);
    double poseY = reader.readDouble(data, offset + 16);
    double poseZ = reader.readDouble(data, offset + 24);
    double qw = reader.readDouble(data, offset + 32);
    double qx = reader.readDouble(data, offset + 40);
    double qy = reader.readDouble(data, offset + 48);
    double qz = reader.readDouble(data, offset + 56);

    double ambiguity = reader.readDouble(data, offset + 64);
    int tagCount = reader.readInt32(data, offset + 72);
    double averageTagDistance = reader.readDouble(data, offset + 76);
    int typeOrdinal = reader.readInt32(data, offset + 84);

    String typeName =
        (typeOrdinal >= 0 && typeOrdinal < POSE_OBSERVATION_TYPES.length)
            ? POSE_OBSERVATION_TYPES[typeOrdinal]
            : "UNKNOWN(" + typeOrdinal + ")";

    // Return flat structure with pose fields prefixed
    var result = new LinkedHashMap<String, Object>();
    result.put("timestamp", timestamp);
    result.put("pose_x", poseX);
    result.put("pose_y", poseY);
    result.put("pose_z", poseZ);
    result.put("pose_qw", qw);
    result.put("pose_qx", qx);
    result.put("pose_qy", qy);
    result.put("pose_qz", qz);
    result.put("ambiguity", ambiguity);
    result.put("tagCount", tagCount);
    result.put("averageTagDistance", averageTagDistance);
    result.put("type", typeName);
    return result;
  }
}
