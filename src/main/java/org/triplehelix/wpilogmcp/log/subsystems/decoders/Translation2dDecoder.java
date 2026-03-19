package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.Map;
import org.triplehelix.wpilogmcp.log.subsystems.BinaryReader;

/**
 * Decoder for WPILib Translation2d struct (x, y).
 *
 * <p>Layout (16 bytes):
 * <ul>
 *   <li>double x (8 bytes)
 *   <li>double y (8 bytes)
 * </ul>
 *
 * @since 0.4.0
 */
public class Translation2dDecoder implements StructDecoder {
  private static final int STRUCT_SIZE = 16; // 2 doubles
  private final BinaryReader reader;

  public Translation2dDecoder(BinaryReader reader) {
    this.reader = reader;
  }

  @Override
  public String getTypeName() {
    return "Translation2d";
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
        "y", reader.readDouble(data, offset + 8));
  }
}
