package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for WPILib Translation3d struct (x, y, z).
 *
 * <p>Layout (24 bytes):
 * <ul>
 *   <li>double x (8 bytes)
 *   <li>double y (8 bytes)
 *   <li>double z (8 bytes)
 * </ul>
 *
 * @since 0.4.0
 */
public class Translation3dDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 24; // 3 doubles
  private final BinaryReader reader;

  public Translation3dDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "Translation3d";
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
        "x", reader.readDouble(data, offset),
        "y", reader.readDouble(data, offset + 8),
        "z", reader.readDouble(data, offset + 16));
  }
}
