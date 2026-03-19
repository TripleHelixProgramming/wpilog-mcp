package org.triplehelix.wpilogmcp.log.subsystems;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.log.subsystems.decoders.StructDecoder;

/**
 * Tests for {@link StructDecoderRegistry}.
 */
class StructDecoderRegistryTest {

  private StructDecoderRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new StructDecoderRegistry();
  }

  @Test
  void testRegistryInitializedWith16BuiltInDecoders() {
    assertEquals(16, registry.getRegisteredDecoderCount(), "Should have 16 built-in decoders");
  }

  @Test
  void testAllBuiltInDecodersRegistered() {
    // Geometry
    assertTrue(registry.hasDecoder("Pose2d"));
    assertTrue(registry.hasDecoder("Pose3d"));
    assertTrue(registry.hasDecoder("Translation2d"));
    assertTrue(registry.hasDecoder("Translation3d"));
    assertTrue(registry.hasDecoder("Rotation2d"));
    assertTrue(registry.hasDecoder("Rotation3d"));

    // Transforms
    assertTrue(registry.hasDecoder("Transform2d"));
    assertTrue(registry.hasDecoder("Transform3d"));
    assertTrue(registry.hasDecoder("Twist2d"));
    assertTrue(registry.hasDecoder("Twist3d"));

    // Kinematics
    assertTrue(registry.hasDecoder("ChassisSpeeds"));
    assertTrue(registry.hasDecoder("SwerveModuleState"));
    assertTrue(registry.hasDecoder("SwerveModulePosition"));

    // Vision/Autonomous
    assertTrue(registry.hasDecoder("TargetObservation"));
    assertTrue(registry.hasDecoder("PoseObservation"));
    assertTrue(registry.hasDecoder("SwerveSample"));
  }

  @Test
  void testDecodePose2d() {
    // Create Pose2d: x=1.0, y=2.0, rotation=0.5 radians
    byte[] data = new byte[24];
    writeLittleEndianDouble(data, 0, 1.0);
    writeLittleEndianDouble(data, 8, 2.0);
    writeLittleEndianDouble(data, 16, 0.5);

    Object result = registry.decodeStruct("struct:Pose2d", data);

    assertTrue(result instanceof Map);
    @SuppressWarnings("unchecked")
    Map<String, Object> pose = (Map<String, Object>) result;

    assertEquals(1.0, (Double) pose.get("x"), 0.001);
    assertEquals(2.0, (Double) pose.get("y"), 0.001);
    assertEquals(0.5, (Double) pose.get("rotation_rad"), 0.001);
    assertEquals(Math.toDegrees(0.5), (Double) pose.get("rotation_deg"), 0.001);
  }

  @Test
  void testDecodePose2dArray() {
    // Create array of 2 Pose2d structs
    byte[] data = new byte[48]; // 2 * 24 bytes
    writeLittleEndianDouble(data, 0, 1.0);
    writeLittleEndianDouble(data, 8, 2.0);
    writeLittleEndianDouble(data, 16, 0.5);

    writeLittleEndianDouble(data, 24, 3.0);
    writeLittleEndianDouble(data, 32, 4.0);
    writeLittleEndianDouble(data, 40, 1.0);

    Object result = registry.decodeStruct("structarray:Pose2d[]", data);

    assertTrue(result instanceof List);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> poses = (List<Map<String, Object>>) result;

    assertEquals(2, poses.size());
    assertEquals(1.0, (Double) poses.get(0).get("x"), 0.001);
    assertEquals(3.0, (Double) poses.get(1).get("x"), 0.001);
  }

  @Test
  void testDecodeChassisSpeeds() {
    // Create ChassisSpeeds: vx=1.5, vy=2.5, omega=0.3
    byte[] data = new byte[24];
    writeLittleEndianDouble(data, 0, 1.5);
    writeLittleEndianDouble(data, 8, 2.5);
    writeLittleEndianDouble(data, 16, 0.3);

    Object result = registry.decodeStruct("struct:ChassisSpeeds", data);

    assertTrue(result instanceof Map);
    @SuppressWarnings("unchecked")
    Map<String, Object> speeds = (Map<String, Object>) result;

    assertEquals(1.5, (Double) speeds.get("vx_mps"), 0.001);
    assertEquals(2.5, (Double) speeds.get("vy_mps"), 0.001);
    assertEquals(0.3, (Double) speeds.get("omega_radps"), 0.001);
  }

  @Test
  void testDecodeUnknownType() {
    byte[] data = new byte[]{1, 2, 3, 4};
    Object result = registry.decodeStruct("struct:UnknownType", data);

    assertTrue(result instanceof String);
    assertTrue(((String) result).contains("01020304")); // hex dump
  }

  @Test
  void testRegisterCustomDecoder() {
    StructDecoder customDecoder = new StructDecoder() {
      @Override
      public String getTypeName() {
        return "CustomType";
      }

      @Override
      public int getStructSize() {
        return 8;
      }

      @Override
      public Map<String, Object> decode(byte[] data, int offset) {
        return Map.of("custom", "decoded");
      }
    };

    assertFalse(registry.hasDecoder("CustomType"));
    registry.register(customDecoder);
    assertTrue(registry.hasDecoder("CustomType"));
    assertEquals(17, registry.getRegisteredDecoderCount());

    byte[] data = new byte[8];
    Object result = registry.decodeStruct("struct:CustomType", data);
    assertTrue(result instanceof Map);
    @SuppressWarnings("unchecked")
    Map<String, Object> decoded = (Map<String, Object>) result;
    assertEquals("decoded", decoded.get("custom"));
  }

  @Test
  void testRegisterDuplicateDecoderThrows() {
    StructDecoder duplicate = new StructDecoder() {
      @Override
      public String getTypeName() {
        return "Pose2d"; // Already registered
      }

      @Override
      public int getStructSize() {
        return 24;
      }

      @Override
      public Map<String, Object> decode(byte[] data, int offset) {
        return Map.of();
      }
    };

    assertThrows(IllegalArgumentException.class, () -> registry.register(duplicate));
  }

  @Test
  void testExtractStructTypeName() {
    // Test various type string formats
    assertEquals("01020304", BinaryReader.bytesToHex(new byte[]{1, 2, 3, 4}));
  }

  // Helper to write little-endian double
  private void writeLittleEndianDouble(byte[] data, int offset, double value) {
    long bits = Double.doubleToLongBits(value);
    for (int i = 0; i < 8; i++) {
      data[offset + i] = (byte) ((bits >> (i * 8)) & 0xFF);
    }
  }
}
