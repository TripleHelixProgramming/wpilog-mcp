package org.triplehelix.wpilogmcp.log.subsystems;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.triplehelix.wpilogmcp.log.subsystems.decoders.*;

/**
 * Registry for struct decoders with built-in WPILib types and support for custom decoders.
 *
 * <p>This class replaces the large switch statement pattern with a registry-based approach, making
 * the system extensible. Teams can register custom struct decoders for proprietary data types.
 *
 * <p>Thread-safe using ConcurrentHashMap.
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * // Register a custom decoder
 * var registry = new StructDecoderRegistry();
 * registry.register(new MyCustomDecoder(binaryReader));
 *
 * // Decode a struct
 * Object decoded = registry.decodeStruct("struct:MyCustomType", rawBytes);
 * }</pre>
 *
 * @since 0.4.0
 */
public class StructDecoderRegistry {
  private final Map<String, StructDecoder> decoders = new ConcurrentHashMap<>();
  private final BinaryReader binaryReader = new BinaryReader();

  /** Creates a new registry and registers all built-in WPILib struct decoders. */
  public StructDecoderRegistry() {
    registerBuiltinDecoders();
  }

  /**
   * Registers all built-in WPILib struct decoders.
   *
   * <p>This includes 16 struct types:
   *
   * <ul>
   *   <li>Geometry: Pose2d, Pose3d, Translation2d, Translation3d, Rotation2d, Rotation3d
   *   <li>Transforms: Transform2d, Transform3d, Twist2d, Twist3d
   *   <li>Kinematics: ChassisSpeeds, SwerveModuleState, SwerveModulePosition
   *   <li>Vision: TargetObservation, PoseObservation
   *   <li>Autonomous: SwerveSample (Choreo)
   * </ul>
   */
  private void registerBuiltinDecoders() {
    // Geometry decoders
    register(new Pose2dDecoder(binaryReader));
    register(new Pose3dDecoder(binaryReader));
    register(new Translation2dDecoder(binaryReader));
    register(new Translation3dDecoder(binaryReader));
    register(new Rotation2dDecoder(binaryReader));
    register(new Rotation3dDecoder(binaryReader));

    // Transform decoders
    register(new Transform2dDecoder(binaryReader));
    register(new Transform3dDecoder(binaryReader));
    register(new Twist2dDecoder(binaryReader));
    register(new Twist3dDecoder(binaryReader));

    // Kinematics decoders
    register(new ChassisSpeedsDecoder(binaryReader));
    register(new SwerveModuleStateDecoder(binaryReader));
    register(new SwerveModulePositionDecoder(binaryReader));

    // Vision decoders
    register(new TargetObservationDecoder(binaryReader));
    register(new PoseObservationDecoder(binaryReader));

    // Autonomous decoders
    register(new SwerveSampleDecoder(binaryReader));
  }

  /**
   * Registers a custom struct decoder.
   *
   * <p>This allows teams to add support for custom struct types not included in WPILib. The
   * decoder will be used automatically during log parsing when a struct of the matching type is
   * encountered.
   *
   * @param decoder The decoder to register (must be thread-safe)
   * @throws IllegalArgumentException if a decoder for this type is already registered (prevents
   *     accidental override of built-in decoders)
   */
  public void register(StructDecoder decoder) {
    String typeName = decoder.getTypeName();
    var existing = decoders.putIfAbsent(typeName, decoder);
    if (existing != null) {
      throw new IllegalArgumentException("Decoder already registered for type: " + typeName);
    }
  }

  /**
   * Decodes a struct value from raw bytes.
   *
   * <p>Handles both single structs ("struct:TypeName") and struct arrays
   * ("structarray:TypeName[]").
   *
   * @param type The full type string (e.g., "struct:Pose2d" or "structarray:Pose2d[]")
   * @param data The raw byte data
   * @return The decoded value: Map for single struct, List of Maps for array, or String for
   *     unknown types
   */
  public Object decodeStruct(String type, byte[] data) {
    boolean isArray = type.startsWith("structarray:") || type.endsWith("[]");
    String structType = extractStructTypeName(type);

    StructDecoder decoder = decoders.get(structType);
    if (decoder == null) {
      // Fallback for unknown types: hex dump for small data, size for large
      return data.length <= 100
          ? BinaryReader.bytesToHex(data)
          : "<" + structType + ": " + data.length + " bytes>";
    }

    return isArray ? decoder.decodeArray(data) : decoder.decode(data, 0);
  }

  /**
   * Extracts the base struct type name from a full type string.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>"struct:Pose2d" → "Pose2d"
   *   <li>"structarray:Pose2d[]" → "Pose2d"
   *   <li>"struct:ChassisSpeeds" → "ChassisSpeeds"
   * </ul>
   *
   * @param type The full type string
   * @return The base struct type name
   */
  private String extractStructTypeName(String type) {
    String structType = type;

    // Remove "structarray:" prefix if present
    if (structType.startsWith("structarray:")) {
      structType = structType.substring(12);
    }
    // Remove "struct:" prefix if present
    else if (structType.startsWith("struct:")) {
      structType = structType.substring(7);
    }

    // Remove "[]" suffix if present
    if (structType.endsWith("[]")) {
      structType = structType.substring(0, structType.length() - 2);
    }

    return structType.trim();
  }

  /**
   * Gets the number of registered struct decoders.
   *
   * @return The count of registered decoders (16 built-in + any custom)
   */
  public int getRegisteredDecoderCount() {
    return decoders.size();
  }

  /**
   * Checks if a decoder is registered for the given struct type.
   *
   * @param typeName The struct type name (e.g., "Pose2d")
   * @return true if a decoder is registered, false otherwise
   */
  public boolean hasDecoder(String typeName) {
    return decoders.containsKey(typeName);
  }

  /**
   * Gets the BinaryReader instance used by built-in decoders.
   *
   * <p>Custom decoders can use this same instance for consistency.
   *
   * @return The BinaryReader
   */
  public BinaryReader getBinaryReader() {
    return binaryReader;
  }
}
