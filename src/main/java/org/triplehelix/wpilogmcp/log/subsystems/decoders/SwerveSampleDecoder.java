package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.LinkedHashMap;
import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for SwerveSample struct from Choreo library (path planning).
 *
 * <p>Layout (144 bytes):
 * <ul>
 *   <li>double timestamp (8 bytes)
 *   <li>double x (8 bytes)
 *   <li>double y (8 bytes)
 *   <li>double heading (8 bytes)
 *   <li>double vx (8 bytes)
 *   <li>double vy (8 bytes)
 *   <li>double omega (8 bytes)
 *   <li>double ax (8 bytes)
 *   <li>double ay (8 bytes)
 *   <li>double alpha (8 bytes)
 *   <li>double[4] moduleForcesX (32 bytes)
 *   <li>double[4] moduleForcesY (32 bytes)
 * </ul>
 *
 * @since 0.4.0
 */
public class SwerveSampleDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 144;
  private final BinaryReader reader;

  public SwerveSampleDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "SwerveSample";
  }

  @Override
  public int getStructSize() {
    return STRUCT_SIZE;
  }

  @Override
  public Map<String, Object> decode(byte[] data, int offset) {
    if (data.length < offset + STRUCT_SIZE) {
      return Map.of("error", "insufficient data for SwerveSample");
    }

    double timestamp = reader.readDouble(data, offset);
    double x = reader.readDouble(data, offset + 8);
    double y = reader.readDouble(data, offset + 16);
    double heading = reader.readDouble(data, offset + 24);
    double vx = reader.readDouble(data, offset + 32);
    double vy = reader.readDouble(data, offset + 40);
    double omega = reader.readDouble(data, offset + 48);
    double ax = reader.readDouble(data, offset + 56);
    double ay = reader.readDouble(data, offset + 64);
    double alpha = reader.readDouble(data, offset + 72);

    // Read module forces arrays (4 elements each)
    double[] moduleForcesX = new double[4];
    double[] moduleForcesY = new double[4];
    for (int i = 0; i < 4; i++) {
      moduleForcesX[i] = reader.readDouble(data, offset + 80 + i * 8);
      moduleForcesY[i] = reader.readDouble(data, offset + 112 + i * 8);
    }

    var result = new LinkedHashMap<String, Object>();
    result.put("timestamp", timestamp);
    result.put("x", x);
    result.put("y", y);
    result.put("heading", heading);
    result.put("heading_deg", Math.toDegrees(heading));
    result.put("vx", vx);
    result.put("vy", vy);
    result.put("omega", omega);
    result.put("ax", ax);
    result.put("ay", ay);
    result.put("alpha", alpha);
    result.put("moduleForcesX", moduleForcesX);
    result.put("moduleForcesY", moduleForcesY);
    return result;
  }
}
