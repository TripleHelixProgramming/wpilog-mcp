package org.triplehelix.wpilogmcp.log;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.LogManager.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogManager.ParsedLog;
import org.triplehelix.wpilogmcp.log.LogManager.TimestampedValue;

/**
 * Tests for LogManager functionality including struct decoding and cache management.
 */
class LogManagerTest {

  private LogManager logManager;

  @BeforeEach
  void setUp() {
    logManager = LogManager.getInstance();
    logManager.unloadAllLogs();
    logManager.resetConfiguration();
  }

  @Nested
  @DisplayName("Singleton Pattern")
  class SingletonPattern {

    @Test
    @DisplayName("returns same instance")
    void returnsSameInstance() {
      LogManager instance1 = LogManager.getInstance();
      LogManager instance2 = LogManager.getInstance();
      assertSame(instance1, instance2);
    }
  }

  @Nested
  @DisplayName("Log Loading")
  class LogLoading {

    @Test
    @DisplayName("throws IOException for non-existent file")
    void throwsForNonExistentFile() {
      assertThrows(IOException.class, () -> logManager.loadLog("/nonexistent/file.wpilog"));
    }

    @Test
    @DisplayName("throws IOException for invalid file")
    void throwsForInvalidFile(@TempDir Path tempDir) throws IOException {
      Path invalidFile = tempDir.resolve("invalid.wpilog");
      Files.write(invalidFile, "not a valid wpilog".getBytes());

      assertThrows(IOException.class, () -> logManager.loadLog(invalidFile.toString()));
    }
  }

  @Nested
  @DisplayName("Active Log Management")
  class ActiveLogManagement {

    @Test
    @DisplayName("returns null when no log loaded")
    void returnsNullWhenNoLogLoaded() {
      logManager.unloadAllLogs();
      assertNull(logManager.getActiveLog());
    }

    @Test
    @DisplayName("setActiveLog returns false for unknown path")
    void setActiveLogReturnsFalseForUnknownPath() {
      assertFalse(logManager.setActiveLog("/some/unknown/path.wpilog"));
    }
  }

  @Nested
  @DisplayName("LRU Cache Behavior")
  class LruCacheBehavior {

    @Test
    @DisplayName("evicts least recently used logs when capacity reached")
    void evictLeastRecentlyUsed() {
      // Set max to 5 for this test (default is now 20)
      logManager.setMaxLoadedLogs(5);

      // Create dummy logs using test accessor
      for (int i = 1; i <= 5; i++) {
        String path = "/log" + i;
        ParsedLog dummyLog = new ParsedLog(path, Map.of(), Map.of(), 0, 10);
        logManager.testPutLog(path, dummyLog);
      }
      logManager.testSetActiveLogPath("/log5");

      assertEquals(5, logManager.getLoadedLogCount());

      // Should not evict anything at size 5
      logManager.testEvictIfNeeded();
      assertEquals(5, logManager.getLoadedLogCount());

      // Add one more to go over capacity
      logManager.testPutLog("/log6", new ParsedLog("/log6", Map.of(), Map.of(), 0, 10));

      // Now it should evict the oldest one (/log1)
      logManager.testEvictIfNeeded();

      assertEquals(5, logManager.getLoadedLogCount());
      assertFalse(logManager.testContainsLog("/log1"));
      assertTrue(logManager.testContainsLog("/log2"));
      assertTrue(logManager.testContainsLog("/log6"));
    }
  }

  @Nested
  @DisplayName("Struct Decoding")
  class StructDecoding {

    @Test
    @DisplayName("readDouble decodes little-endian double")
    void readDoubleDecodesLittleEndian() {
      double expected = 3.14159265359;
      ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putDouble(expected);
      byte[] data = buffer.array();

      double actual = logManager.testReadDouble(data, 0);
      assertEquals(expected, actual, 1e-10);
    }

    @Test
    @DisplayName("decodePose2d extracts x, y, and rotation")
    void decodePose2dExtractsFields() {
      double x = 1.5, y = 2.5, rotation = Math.PI / 4;
      byte[] data = createPose2dBytes(x, y, rotation);

      Map<String, Object> result = logManager.testDecodePose2d(data, 0);

      assertEquals(x, (double) result.get("x"), 1e-10);
      assertEquals(y, (double) result.get("y"), 1e-10);
      assertEquals(rotation, (double) result.get("rotation_rad"), 1e-10);
    }

    private byte[] createPose2dBytes(double x, double y, double rotation) {
      ByteBuffer buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putDouble(x).putDouble(y).putDouble(rotation);
      return buffer.array();
    }

    @Test
    @DisplayName("readFloat decodes little-endian float")
    void readFloatDecodesLittleEndian() {
      float expected = 3.14159f;
      ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putFloat(expected);
      byte[] data = buffer.array();

      float actual = logManager.testReadFloat(data, 0);
      assertEquals(expected, actual, 1e-6f);
    }

    @Test
    @DisplayName("readFloat handles negative values")
    void readFloatHandlesNegative() {
      float expected = -123.456f;
      ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putFloat(expected);
      byte[] data = buffer.array();

      float actual = logManager.testReadFloat(data, 0);
      assertEquals(expected, actual, 1e-6f);
    }

    @Test
    @DisplayName("readInt32 decodes little-endian int")
    void readInt32DecodesLittleEndian() {
      int expected = 123456789;
      ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putInt(expected);
      byte[] data = buffer.array();

      int actual = logManager.testReadInt32(data, 0);
      assertEquals(expected, actual);
    }

    @Test
    @DisplayName("readInt32 handles negative values")
    void readInt32HandlesNegative() {
      int expected = -1;
      ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putInt(expected);
      byte[] data = buffer.array();

      int actual = logManager.testReadInt32(data, 0);
      assertEquals(expected, actual);
    }

    @Test
    @DisplayName("readInt32 handles max int value")
    void readInt32HandlesMaxValue() {
      int expected = Integer.MAX_VALUE;
      ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putInt(expected);
      byte[] data = buffer.array();

      int actual = logManager.testReadInt32(data, 0);
      assertEquals(expected, actual);
    }

    @Test
    @DisplayName("readInt32 handles min int value")
    void readInt32HandlesMinValue() {
      int expected = Integer.MIN_VALUE;
      ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putInt(expected);
      byte[] data = buffer.array();

      int actual = logManager.testReadInt32(data, 0);
      assertEquals(expected, actual);
    }
  }

  @Nested
  @DisplayName("TargetObservation Decoding")
  class TargetObservationDecoding {

    @Test
    @DisplayName("decodes all fields correctly")
    void decodesAllFields() {
      double yaw = -0.524;       // ~-30 degrees
      double pitch = 0.175;     // ~10 degrees
      double skew = 0.0;
      double area = 0.05;
      float confidence = 0.95f;
      int objectID = 42;

      byte[] data = createTargetObservationBytes(yaw, pitch, skew, area, confidence, objectID);
      Map<String, Object> result = logManager.testDecodeTargetObservation(data, 0);

      assertEquals(yaw, (double) result.get("yaw_rad"), 1e-10);
      assertEquals(Math.toDegrees(yaw), (double) result.get("yaw_deg"), 1e-10);
      assertEquals(pitch, (double) result.get("pitch_rad"), 1e-10);
      assertEquals(Math.toDegrees(pitch), (double) result.get("pitch_deg"), 1e-10);
      assertEquals(skew, (double) result.get("skew_rad"), 1e-10);
      assertEquals(Math.toDegrees(skew), (double) result.get("skew_deg"), 1e-10);
      assertEquals(area, (double) result.get("area"), 1e-10);
      assertEquals(confidence, ((Number) result.get("confidence")).floatValue(), 1e-6f);
      assertEquals(objectID, ((Number) result.get("objectID")).intValue());
    }

    @Test
    @DisplayName("handles negative confidence and objectID")
    void handlesNegativeValues() {
      double yaw = 0.0;
      double pitch = 0.0;
      double skew = 0.0;
      double area = 0.0;
      float confidence = -1.0f;  // No valid detection
      int objectID = -1;         // No valid object

      byte[] data = createTargetObservationBytes(yaw, pitch, skew, area, confidence, objectID);
      Map<String, Object> result = logManager.testDecodeTargetObservation(data, 0);

      assertEquals(confidence, ((Number) result.get("confidence")).floatValue(), 1e-6f);
      assertEquals(objectID, ((Number) result.get("objectID")).intValue());
    }

    @Test
    @DisplayName("returns error for insufficient data")
    void returnsErrorForInsufficientData() {
      byte[] data = new byte[39]; // One byte short of 40
      Map<String, Object> result = logManager.testDecodeTargetObservation(data, 0);

      assertTrue(result.containsKey("error"));
      assertTrue(result.get("error").toString().contains("insufficient data"));
    }

    @Test
    @DisplayName("decodes with non-zero offset")
    void decodesWithOffset() {
      double yaw = 1.0;
      double pitch = 2.0;
      double skew = 3.0;
      double area = 4.0;
      float confidence = 0.5f;
      int objectID = 100;

      byte[] innerData = createTargetObservationBytes(yaw, pitch, skew, area, confidence, objectID);
      byte[] data = new byte[10 + innerData.length];
      System.arraycopy(innerData, 0, data, 10, innerData.length);

      Map<String, Object> result = logManager.testDecodeTargetObservation(data, 10);

      assertEquals(yaw, (double) result.get("yaw_rad"), 1e-10);
      assertEquals(objectID, ((Number) result.get("objectID")).intValue());
    }

    @Test
    @DisplayName("decodes array of TargetObservations")
    void decodesArray() {
      byte[] obs1 = createTargetObservationBytes(0.1, 0.2, 0.0, 0.01, 0.9f, 1);
      byte[] obs2 = createTargetObservationBytes(0.3, 0.4, 0.0, 0.02, 0.8f, 2);
      byte[] obs3 = createTargetObservationBytes(0.5, 0.6, 0.0, 0.03, 0.7f, 3);

      byte[] data = new byte[obs1.length + obs2.length + obs3.length];
      System.arraycopy(obs1, 0, data, 0, obs1.length);
      System.arraycopy(obs2, 0, data, obs1.length, obs2.length);
      System.arraycopy(obs3, 0, data, obs1.length + obs2.length, obs3.length);

      List<Map<String, Object>> result = logManager.testDecodeTargetObservationArray(data);

      assertEquals(3, result.size());
      assertEquals(0.1, (double) result.get(0).get("yaw_rad"), 1e-10);
      assertEquals(0.3, (double) result.get(1).get("yaw_rad"), 1e-10);
      assertEquals(0.5, (double) result.get(2).get("yaw_rad"), 1e-10);
      assertEquals(1, ((Number) result.get(0).get("objectID")).intValue());
      assertEquals(2, ((Number) result.get(1).get("objectID")).intValue());
      assertEquals(3, ((Number) result.get(2).get("objectID")).intValue());
    }

    @Test
    @DisplayName("empty array returns empty list")
    void emptyArrayReturnsEmptyList() {
      byte[] data = new byte[0];
      List<Map<String, Object>> result = logManager.testDecodeTargetObservationArray(data);
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("partial data at end is ignored in array")
    void partialDataIgnoredInArray() {
      byte[] obs1 = createTargetObservationBytes(0.1, 0.2, 0.0, 0.01, 0.9f, 1);
      byte[] data = new byte[obs1.length + 20]; // 20 extra bytes (not enough for another)
      System.arraycopy(obs1, 0, data, 0, obs1.length);

      List<Map<String, Object>> result = logManager.testDecodeTargetObservationArray(data);

      assertEquals(1, result.size());
    }

    private byte[] createTargetObservationBytes(double yaw, double pitch, double skew,
                                                 double area, float confidence, int objectID) {
      ByteBuffer buffer = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putDouble(yaw);
      buffer.putDouble(pitch);
      buffer.putDouble(skew);
      buffer.putDouble(area);
      buffer.putFloat(confidence);
      buffer.putInt(objectID);
      return buffer.array();
    }
  }

  @Nested
  @DisplayName("PoseObservation Decoding")
  class PoseObservationDecoding {

    @Test
    @DisplayName("decodes all fields correctly")
    void decodesAllFields() {
      double timestamp = 79.120116;
      double poseX = 3.243, poseY = 5.986, poseZ = -0.018;
      double qw = -0.248, qx = -0.021, qy = 0.011, qz = 0.968;
      double ambiguity = 0.15;
      int tagCount = 1;
      double avgTagDist = 2.34;
      int type = 2; // PHOTONVISION

      byte[] data = createPoseObservationBytes(timestamp, poseX, poseY, poseZ,
          qw, qx, qy, qz, ambiguity, tagCount, avgTagDist, type);
      Map<String, Object> result = logManager.testDecodePoseObservation(data, 0);

      assertEquals(timestamp, (double) result.get("timestamp"), 1e-10);
      assertEquals(poseX, (double) result.get("pose_x"), 1e-10);
      assertEquals(poseY, (double) result.get("pose_y"), 1e-10);
      assertEquals(poseZ, (double) result.get("pose_z"), 1e-10);
      assertEquals(qw, (double) result.get("pose_qw"), 1e-10);
      assertEquals(qx, (double) result.get("pose_qx"), 1e-10);
      assertEquals(qy, (double) result.get("pose_qy"), 1e-10);
      assertEquals(qz, (double) result.get("pose_qz"), 1e-10);
      assertEquals(ambiguity, (double) result.get("ambiguity"), 1e-10);
      assertEquals(tagCount, ((Number) result.get("tagCount")).intValue());
      assertEquals(avgTagDist, (double) result.get("averageTagDistance"), 1e-10);
      assertEquals("PHOTONVISION", result.get("type"));
    }

    @Test
    @DisplayName("maps MEGATAG_1 enum correctly")
    void mapsMegatag1Enum() {
      byte[] data = createPoseObservationBytes(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0);
      Map<String, Object> result = logManager.testDecodePoseObservation(data, 0);
      assertEquals("MEGATAG_1", result.get("type"));
    }

    @Test
    @DisplayName("maps MEGATAG_2 enum correctly")
    void mapsMegatag2Enum() {
      byte[] data = createPoseObservationBytes(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1);
      Map<String, Object> result = logManager.testDecodePoseObservation(data, 0);
      assertEquals("MEGATAG_2", result.get("type"));
    }

    @Test
    @DisplayName("handles unknown enum values")
    void handlesUnknownEnumValues() {
      byte[] data = createPoseObservationBytes(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 99);
      Map<String, Object> result = logManager.testDecodePoseObservation(data, 0);
      assertEquals("UNKNOWN(99)", result.get("type"));
    }

    @Test
    @DisplayName("handles negative enum values")
    void handlesNegativeEnumValues() {
      byte[] data = createPoseObservationBytes(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, -1);
      Map<String, Object> result = logManager.testDecodePoseObservation(data, 0);
      assertTrue(result.get("type").toString().contains("UNKNOWN"));
    }

    @Test
    @DisplayName("returns error for insufficient data")
    void returnsErrorForInsufficientData() {
      byte[] data = new byte[87]; // One byte short of 88
      Map<String, Object> result = logManager.testDecodePoseObservation(data, 0);

      assertTrue(result.containsKey("error"));
      assertTrue(result.get("error").toString().contains("insufficient data"));
    }

    @Test
    @DisplayName("decodes with non-zero offset")
    void decodesWithOffset() {
      byte[] innerData = createPoseObservationBytes(
          100.5, 1.0, 2.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.5, 3, 5.0, 1);
      byte[] data = new byte[20 + innerData.length];
      System.arraycopy(innerData, 0, data, 20, innerData.length);

      Map<String, Object> result = logManager.testDecodePoseObservation(data, 20);

      assertEquals(100.5, (double) result.get("timestamp"), 1e-10);
      assertEquals(3, ((Number) result.get("tagCount")).intValue());
    }

    @Test
    @DisplayName("decodes array of PoseObservations")
    void decodesArray() {
      byte[] obs1 = createPoseObservationBytes(1.0, 0, 0, 0, 1, 0, 0, 0, 0.1, 1, 1.0, 0);
      byte[] obs2 = createPoseObservationBytes(2.0, 1, 1, 1, 1, 0, 0, 0, 0.2, 2, 2.0, 1);
      byte[] obs3 = createPoseObservationBytes(3.0, 2, 2, 2, 1, 0, 0, 0, 0.3, 3, 3.0, 2);

      byte[] data = new byte[obs1.length + obs2.length + obs3.length];
      System.arraycopy(obs1, 0, data, 0, obs1.length);
      System.arraycopy(obs2, 0, data, obs1.length, obs2.length);
      System.arraycopy(obs3, 0, data, obs1.length + obs2.length, obs3.length);

      List<Map<String, Object>> result = logManager.testDecodePoseObservationArray(data);

      assertEquals(3, result.size());
      assertEquals(1.0, (double) result.get(0).get("timestamp"), 1e-10);
      assertEquals(2.0, (double) result.get(1).get("timestamp"), 1e-10);
      assertEquals(3.0, (double) result.get(2).get("timestamp"), 1e-10);
      assertEquals("MEGATAG_1", result.get(0).get("type"));
      assertEquals("MEGATAG_2", result.get(1).get("type"));
      assertEquals("PHOTONVISION", result.get(2).get("type"));
    }

    @Test
    @DisplayName("empty array returns empty list")
    void emptyArrayReturnsEmptyList() {
      byte[] data = new byte[0];
      List<Map<String, Object>> result = logManager.testDecodePoseObservationArray(data);
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("handles high tag counts")
    void handlesHighTagCounts() {
      byte[] data = createPoseObservationBytes(0, 0, 0, 0, 1, 0, 0, 0, 0, 100, 10.0, 2);
      Map<String, Object> result = logManager.testDecodePoseObservation(data, 0);
      assertEquals(100, ((Number) result.get("tagCount")).intValue());
    }

    @Test
    @DisplayName("preserves pose quaternion precision")
    void preservesQuaternionPrecision() {
      // Use actual quaternion values that form a unit quaternion
      double qw = 0.7071067811865476;
      double qx = 0.7071067811865476;
      double qy = 0.0;
      double qz = 0.0;

      byte[] data = createPoseObservationBytes(0, 0, 0, 0, qw, qx, qy, qz, 0, 0, 0, 0);
      Map<String, Object> result = logManager.testDecodePoseObservation(data, 0);

      assertEquals(qw, (double) result.get("pose_qw"), 1e-15);
      assertEquals(qx, (double) result.get("pose_qx"), 1e-15);
      assertEquals(qy, (double) result.get("pose_qy"), 1e-15);
      assertEquals(qz, (double) result.get("pose_qz"), 1e-15);
    }

    private byte[] createPoseObservationBytes(double timestamp, double poseX, double poseY, double poseZ,
                                               double qw, double qx, double qy, double qz,
                                               double ambiguity, int tagCount, double avgTagDist, int type) {
      ByteBuffer buffer = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putDouble(timestamp);
      buffer.putDouble(poseX);
      buffer.putDouble(poseY);
      buffer.putDouble(poseZ);
      buffer.putDouble(qw);
      buffer.putDouble(qx);
      buffer.putDouble(qy);
      buffer.putDouble(qz);
      buffer.putDouble(ambiguity);
      buffer.putInt(tagCount);
      buffer.putDouble(avgTagDist);
      buffer.putInt(type);
      return buffer.array();
    }
  }

  @Nested
  @DisplayName("SwerveSample Decoding")
  class SwerveSampleDecoding {

    @Test
    @DisplayName("decodes all scalar fields correctly")
    void decodesAllScalarFields() {
      double timestamp = 1.5;
      double x = 2.0, y = 3.0, heading = Math.PI / 4;
      double vx = 1.0, vy = 0.5, omega = 0.1;
      double ax = 0.2, ay = 0.3, alpha = 0.05;
      double[] forcesX = {10.0, 11.0, 12.0, 13.0};
      double[] forcesY = {20.0, 21.0, 22.0, 23.0};

      byte[] data = createSwerveSampleBytes(timestamp, x, y, heading, vx, vy, omega, ax, ay, alpha, forcesX, forcesY);
      Map<String, Object> result = logManager.testDecodeSwerveSample(data, 0);

      assertEquals(timestamp, (double) result.get("timestamp"), 1e-10);
      assertEquals(x, (double) result.get("x"), 1e-10);
      assertEquals(y, (double) result.get("y"), 1e-10);
      assertEquals(heading, (double) result.get("heading"), 1e-10);
      assertEquals(Math.toDegrees(heading), (double) result.get("heading_deg"), 1e-10);
      assertEquals(vx, (double) result.get("vx"), 1e-10);
      assertEquals(vy, (double) result.get("vy"), 1e-10);
      assertEquals(omega, (double) result.get("omega"), 1e-10);
      assertEquals(ax, (double) result.get("ax"), 1e-10);
      assertEquals(ay, (double) result.get("ay"), 1e-10);
      assertEquals(alpha, (double) result.get("alpha"), 1e-10);
    }

    @Test
    @DisplayName("decodes module forces arrays correctly")
    void decodesModuleForcesArrays() {
      double[] forcesX = {100.5, 200.5, 300.5, 400.5};
      double[] forcesY = {-100.5, -200.5, -300.5, -400.5};

      byte[] data = createSwerveSampleBytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, forcesX, forcesY);
      Map<String, Object> result = logManager.testDecodeSwerveSample(data, 0);

      double[] resultForcesX = (double[]) result.get("moduleForcesX");
      double[] resultForcesY = (double[]) result.get("moduleForcesY");

      assertNotNull(resultForcesX);
      assertNotNull(resultForcesY);
      assertEquals(4, resultForcesX.length);
      assertEquals(4, resultForcesY.length);

      for (int i = 0; i < 4; i++) {
        assertEquals(forcesX[i], resultForcesX[i], 1e-10);
        assertEquals(forcesY[i], resultForcesY[i], 1e-10);
      }
    }

    @Test
    @DisplayName("handles zero values")
    void handlesZeroValues() {
      double[] forcesX = {0, 0, 0, 0};
      double[] forcesY = {0, 0, 0, 0};

      byte[] data = createSwerveSampleBytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, forcesX, forcesY);
      Map<String, Object> result = logManager.testDecodeSwerveSample(data, 0);

      assertEquals(0.0, (double) result.get("timestamp"), 1e-10);
      assertEquals(0.0, (double) result.get("x"), 1e-10);
      assertEquals(0.0, (double) result.get("heading_deg"), 1e-10);
    }

    @Test
    @DisplayName("handles negative velocities and accelerations")
    void handlesNegativeValues() {
      double vx = -5.0, vy = -3.0, omega = -1.0;
      double ax = -2.0, ay = -1.0, alpha = -0.5;
      double[] forcesX = {0, 0, 0, 0};
      double[] forcesY = {0, 0, 0, 0};

      byte[] data = createSwerveSampleBytes(0, 0, 0, 0, vx, vy, omega, ax, ay, alpha, forcesX, forcesY);
      Map<String, Object> result = logManager.testDecodeSwerveSample(data, 0);

      assertEquals(vx, (double) result.get("vx"), 1e-10);
      assertEquals(vy, (double) result.get("vy"), 1e-10);
      assertEquals(omega, (double) result.get("omega"), 1e-10);
      assertEquals(ax, (double) result.get("ax"), 1e-10);
      assertEquals(ay, (double) result.get("ay"), 1e-10);
      assertEquals(alpha, (double) result.get("alpha"), 1e-10);
    }

    @Test
    @DisplayName("returns error for insufficient data")
    void returnsErrorForInsufficientData() {
      byte[] data = new byte[143]; // One byte short of 144
      Map<String, Object> result = logManager.testDecodeSwerveSample(data, 0);

      assertTrue(result.containsKey("error"));
      assertTrue(result.get("error").toString().contains("insufficient data"));
    }

    @Test
    @DisplayName("decodes with non-zero offset")
    void decodesWithOffset() {
      double[] forcesX = {1, 2, 3, 4};
      double[] forcesY = {5, 6, 7, 8};
      byte[] innerData = createSwerveSampleBytes(99.9, 1.0, 2.0, 3.0, 0, 0, 0, 0, 0, 0, forcesX, forcesY);
      byte[] data = new byte[50 + innerData.length];
      System.arraycopy(innerData, 0, data, 50, innerData.length);

      Map<String, Object> result = logManager.testDecodeSwerveSample(data, 50);

      assertEquals(99.9, (double) result.get("timestamp"), 1e-10);
      assertEquals(1.0, (double) result.get("x"), 1e-10);
    }

    @Test
    @DisplayName("decodes array of SwerveSamples")
    void decodesArray() {
      double[] forcesX = {0, 0, 0, 0};
      double[] forcesY = {0, 0, 0, 0};

      byte[] sample1 = createSwerveSampleBytes(0.0, 0, 0, 0, 1, 0, 0, 0, 0, 0, forcesX, forcesY);
      byte[] sample2 = createSwerveSampleBytes(0.02, 0.1, 0.05, 0.01, 2, 0, 0, 0, 0, 0, forcesX, forcesY);
      byte[] sample3 = createSwerveSampleBytes(0.04, 0.2, 0.10, 0.02, 3, 0, 0, 0, 0, 0, forcesX, forcesY);

      byte[] data = new byte[sample1.length + sample2.length + sample3.length];
      System.arraycopy(sample1, 0, data, 0, sample1.length);
      System.arraycopy(sample2, 0, data, sample1.length, sample2.length);
      System.arraycopy(sample3, 0, data, sample1.length + sample2.length, sample3.length);

      List<Map<String, Object>> result = logManager.testDecodeSwerveSampleArray(data);

      assertEquals(3, result.size());
      assertEquals(0.0, (double) result.get(0).get("timestamp"), 1e-10);
      assertEquals(0.02, (double) result.get(1).get("timestamp"), 1e-10);
      assertEquals(0.04, (double) result.get(2).get("timestamp"), 1e-10);
      assertEquals(1.0, (double) result.get(0).get("vx"), 1e-10);
      assertEquals(2.0, (double) result.get(1).get("vx"), 1e-10);
      assertEquals(3.0, (double) result.get(2).get("vx"), 1e-10);
    }

    @Test
    @DisplayName("empty array returns empty list")
    void emptyArrayReturnsEmptyList() {
      byte[] data = new byte[0];
      List<Map<String, Object>> result = logManager.testDecodeSwerveSampleArray(data);
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("partial data at end is ignored in array")
    void partialDataIgnoredInArray() {
      double[] forcesX = {0, 0, 0, 0};
      double[] forcesY = {0, 0, 0, 0};
      byte[] sample1 = createSwerveSampleBytes(1.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, forcesX, forcesY);
      byte[] data = new byte[sample1.length + 100]; // 100 extra bytes (not enough for another)
      System.arraycopy(sample1, 0, data, 0, sample1.length);

      List<Map<String, Object>> result = logManager.testDecodeSwerveSampleArray(data);

      assertEquals(1, result.size());
    }

    @Test
    @DisplayName("heading in degrees calculated correctly for full rotation")
    void headingDegreesCalculatedCorrectly() {
      double[] forcesX = {0, 0, 0, 0};
      double[] forcesY = {0, 0, 0, 0};

      // Test various angles
      double[] headings = {0, Math.PI / 2, Math.PI, -Math.PI / 2, 2 * Math.PI};
      double[] expectedDegrees = {0, 90, 180, -90, 360};

      for (int i = 0; i < headings.length; i++) {
        byte[] data = createSwerveSampleBytes(0, 0, 0, headings[i], 0, 0, 0, 0, 0, 0, forcesX, forcesY);
        Map<String, Object> result = logManager.testDecodeSwerveSample(data, 0);
        assertEquals(expectedDegrees[i], (double) result.get("heading_deg"), 1e-10,
            "Failed for heading " + headings[i]);
      }
    }

    @Test
    @DisplayName("preserves double precision for all fields")
    void preservesDoublePrecision() {
      double preciseValue = 1.23456789012345678;
      double[] forcesX = {preciseValue, preciseValue, preciseValue, preciseValue};
      double[] forcesY = {preciseValue, preciseValue, preciseValue, preciseValue};

      byte[] data = createSwerveSampleBytes(preciseValue, preciseValue, preciseValue, preciseValue,
          preciseValue, preciseValue, preciseValue, preciseValue, preciseValue, preciseValue, forcesX, forcesY);
      Map<String, Object> result = logManager.testDecodeSwerveSample(data, 0);

      // IEEE 754 double has ~15-17 significant digits
      assertEquals(preciseValue, (double) result.get("timestamp"), 1e-15);
      assertEquals(preciseValue, (double) result.get("x"), 1e-15);
      double[] resultForcesX = (double[]) result.get("moduleForcesX");
      assertEquals(preciseValue, resultForcesX[0], 1e-15);
    }

    private byte[] createSwerveSampleBytes(double timestamp, double x, double y, double heading,
                                            double vx, double vy, double omega,
                                            double ax, double ay, double alpha,
                                            double[] moduleForcesX, double[] moduleForcesY) {
      ByteBuffer buffer = ByteBuffer.allocate(144).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putDouble(timestamp);
      buffer.putDouble(x);
      buffer.putDouble(y);
      buffer.putDouble(heading);
      buffer.putDouble(vx);
      buffer.putDouble(vy);
      buffer.putDouble(omega);
      buffer.putDouble(ax);
      buffer.putDouble(ay);
      buffer.putDouble(alpha);
      for (int i = 0; i < 4; i++) {
        buffer.putDouble(moduleForcesX[i]);
      }
      for (int i = 0; i < 4; i++) {
        buffer.putDouble(moduleForcesY[i]);
      }
      return buffer.array();
    }
  }

  @Nested
  @DisplayName("Record Types")
  class RecordTypes {

    @Test
    @DisplayName("EntryInfo stores all fields")
    void entryInfoStoresAllFields() {
      EntryInfo info = new EntryInfo(1, "/Robot/Pose", "struct:Pose2d", "some metadata");

      assertEquals(1, info.id());
      assertEquals("/Robot/Pose", info.name());
      assertEquals("struct:Pose2d", info.type());
      assertEquals("some metadata", info.metadata());
    }

    @Test
    @DisplayName("TimestampedValue stores timestamp and value")
    void timestampedValueStoresFields() {
      Map<String, Object> value = Map.of("x", 1.0, "y", 2.0);
      TimestampedValue tv = new TimestampedValue(123.456, value);

      assertEquals(123.456, tv.timestamp());
      assertEquals(value, tv.value());
    }

    @Test
    @DisplayName("ParsedLog computes entry count")
    void parsedLogComputesEntryCount() {
      Map<String, EntryInfo> entries =
          Map.of(
              "entry1", new EntryInfo(1, "entry1", "double", ""),
              "entry2", new EntryInfo(2, "entry2", "string", ""));

      ParsedLog log = new ParsedLog("/test.wpilog", entries, Map.of(), 0.0, 10.0);

      assertEquals(2, log.entryCount());
    }

    @Test
    @DisplayName("ParsedLog computes duration")
    void parsedLogComputesDuration() {
      ParsedLog log = new ParsedLog("/test.wpilog", Map.of(), Map.of(), 5.0, 15.0);

      assertEquals(10.0, log.duration());
    }
  }
}
