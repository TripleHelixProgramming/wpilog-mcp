package org.triplehelix.wpilogmcp.log.subsystems.decoders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Interface for decoding WPILib struct types from raw byte arrays.
 *
 * <p>Implementations should be stateless and thread-safe. Each decoder handles a specific struct
 * type (e.g., Pose2d, ChassisSpeeds) and can decode both single instances and arrays.
 *
 * <p>Decoders are registered with {@link
 * org.triplehelix.wpilogmcp.log.subsystems.StructDecoderRegistry} and used during log parsing to
 * convert binary struct data into Map representations.
 *
 * <h2>Example Implementation:</h2>
 *
 * <pre>{@code
 * public class Pose2dDecoder implements StructDecoder {
 *   private final BinaryReader reader;
 *
 *   public Pose2dDecoder(BinaryReader reader) {
 *     this.reader = reader;
 *   }
 *
 *   public String getTypeName() { return "Pose2d"; }
 *   public int getStructSize() { return 24; } // 3 doubles
 *
 *   public Map<String, Object> decode(byte[] data, int offset) {
 *     double x = reader.readDouble(data, offset);
 *     double y = reader.readDouble(data, offset + 8);
 *     double rotation = reader.readDouble(data, offset + 16);
 *     return Map.of("x", x, "y", y, "rotation_rad", rotation);
 *   }
 * }
 * }</pre>
 *
 * @since 0.4.0
 */
public interface StructDecoder {

  /**
   * Gets the struct type name this decoder handles.
   *
   * <p>This should be the base type name without the "struct:" prefix. For example, "Pose2d" for
   * the WPILib Pose2d struct type.
   *
   * @return The struct type name (e.g., "Pose2d", "ChassisSpeeds")
   */
  String getTypeName();

  /**
   * Gets the size in bytes of a single struct instance.
   *
   * <p>This is used to validate data length and for array decoding.
   *
   * @return The struct size in bytes
   */
  int getStructSize();

  /**
   * Decodes a single struct from a byte array at the given offset.
   *
   * <p>The implementation should:
   *
   * <ul>
   *   <li>Read struct fields using the provided offset
   *   <li>Return a Map with field names as keys
   *   <li>Handle insufficient data gracefully (return error Map)
   *   <li>Not modify the input data array
   * </ul>
   *
   * @param data The byte array containing struct data
   * @param offset The offset to start reading from (must be >= 0)
   * @return A Map representation of the struct fields, or an error Map if decoding fails
   */
  Map<String, Object> decode(byte[] data, int offset);

  /**
   * Decodes an array of structs from a byte array.
   *
   * <p>The default implementation calls {@link #decode(byte[], int)} in a loop, advancing by
   * {@link #getStructSize()} bytes each iteration. Implementations can override this if a more
   * efficient approach is available.
   *
   * @param data The byte array containing struct array data
   * @return A List of Map representations, one per struct instance
   */
  default List<Map<String, Object>> decodeArray(byte[] data) {
    var result = new ArrayList<Map<String, Object>>();
    int structSize = getStructSize();
    for (int offset = 0; offset + structSize <= data.length; offset += structSize) {
      result.add(decode(data, offset));
    }
    return result;
  }
}
