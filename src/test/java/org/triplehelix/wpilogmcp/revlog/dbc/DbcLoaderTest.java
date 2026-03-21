package org.triplehelix.wpilogmcp.revlog.dbc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for DbcLoader.
 */
class DbcLoaderTest {

  private DbcLoader loader;

  @TempDir
  Path tempDir;

  @BeforeEach
  void setUp() {
    loader = new DbcLoader();
  }

  @Test
  void testLoadFromString() {
    String content = """
        VERSION "1.0"
        BO_ 0x100 Test: 8 Node
         SG_ Signal : 0|8@1+ (1,0) [0|255] ""
        """;

    DbcDatabase db = loader.loadFromString(content);

    assertEquals("1.0", db.version());
    assertEquals(1, db.messageCount());
  }

  @Test
  void testLoadFromFile(@TempDir Path tempDir) throws IOException {
    String content = """
        VERSION "file_test"
        BO_ 0x200 FileTest: 8 Node
         SG_ FileSignal : 0|16@1+ (1,0) [0|65535] ""
        """;

    Path dbcFile = tempDir.resolve("test.dbc");
    Files.writeString(dbcFile, content);

    DbcDatabase db = loader.loadFromFile(dbcFile);

    assertEquals("file_test", db.version());
    assertTrue(db.getMessage(0x200).isPresent());
  }

  @Test
  void testLoadWithCommandLineOverride(@TempDir Path tempDir) throws IOException {
    String content = """
        VERSION "override"
        BO_ 0x300 Override: 8 Node
         SG_ OverrideSignal : 0|8@1+ (1,0) [0|255] ""
        """;

    Path overrideFile = tempDir.resolve("override.dbc");
    Files.writeString(overrideFile, content);

    DbcDatabase db = loader.load(overrideFile.toString());

    assertEquals("override", db.version());
    assertTrue(db.getMessage(0x300).isPresent());
  }

  @Test
  void testLoadFallsBackToEmbedded() throws IOException {
    // Pass a non-existent path, should fall back to embedded
    DbcDatabase db = loader.load("/nonexistent/path/to/file.dbc");

    // The embedded resource should have been loaded
    // Check that it has some content (the embedded rev_spark.dbc)
    assertNotNull(db);
    assertTrue(db.messageCount() > 0);
  }

  @Test
  void testLoadNullOverrideFallsBackToEmbedded() throws IOException {
    DbcDatabase db = loader.load(null);

    assertNotNull(db);
    assertTrue(db.messageCount() > 0);
  }

  @Test
  void testLoadEmbedded() throws IOException {
    DbcDatabase db = loader.loadEmbedded();

    // Verify embedded DBC has expected content
    assertNotNull(db);
    assertTrue(db.messageCount() > 0);

    // Check for expected REV SPARK messages
    // Periodic_Status_0 base ID is 0x02051800
    var status0 = db.getMessage(0x02051800);
    assertTrue(status0.isPresent(), "Expected Periodic_Status_0 message");
    assertEquals("Periodic_Status_0", status0.get().name());

    // Check for expected signals
    var appliedOutput = status0.get().getSignal("AppliedOutput");
    assertNotNull(appliedOutput, "Expected AppliedOutput signal");
    assertEquals(0.0001, appliedOutput.scale(), 0.00001);
    assertTrue(appliedOutput.signed());
  }

  @Test
  void testGetConfigDir() {
    Path configDir = DbcLoader.getConfigDir();
    assertNotNull(configDir);
    assertTrue(configDir.isAbsolute());

    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac")) {
      assertTrue(configDir.toString().contains("Library/Application Support"));
    } else if (os.contains("win")) {
      assertTrue(configDir.toString().contains("wpilog-mcp"));
    } else {
      assertTrue(configDir.toString().contains(".config"));
    }
  }

  @Test
  void testGetSourceDescriptionWithOverride(@TempDir Path tempDir) throws IOException {
    Path overrideFile = tempDir.resolve("override.dbc");
    Files.writeString(overrideFile, "VERSION \"\"");

    String desc = loader.getSourceDescription(overrideFile.toString());
    assertTrue(desc.startsWith("Command-line:"));
    assertTrue(desc.contains(overrideFile.toString()));
  }

  @Test
  void testGetSourceDescriptionNoOverride() {
    String desc = loader.getSourceDescription(null);
    // Should fall through to embedded
    assertTrue(desc.contains("Embedded") || desc.contains("Config") || desc.contains("Environment"),
        "Expected description to indicate source: " + desc);
  }

  @Test
  void testGetSourceDescriptionNonexistentOverride() {
    String desc = loader.getSourceDescription("/nonexistent/path.dbc");
    // Should fall through since file doesn't exist
    assertFalse(desc.contains("Command-line"));
  }
}
