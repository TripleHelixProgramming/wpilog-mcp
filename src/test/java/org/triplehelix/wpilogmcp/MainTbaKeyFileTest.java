/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The TBA API key read from a file (-tba-key-file), as the VS Code extension passes it. */
@DisplayName("Main -tba-key-file")
class MainTbaKeyFileTest {

  @TempDir
  Path tempDir;

  @Nested
  @DisplayName("readKeyFile")
  class ReadKeyFile {

    @Test
    @DisplayName("reads the key, trimmed of surrounding whitespace and a final newline")
    void reads() throws Exception {
      var file = Files.writeString(tempDir.resolve("key"), "  abc123\n");
      assertEquals(Optional.of("abc123"), Main.readKeyFile(file.toString()));
    }

    @Test
    @DisplayName("a missing, blank, or directory path gives no key")
    void noKey() throws Exception {
      assertEquals(Optional.empty(), Main.readKeyFile(tempDir.resolve("missing").toString()));
      var blank = Files.writeString(tempDir.resolve("blank"), " \n");
      assertEquals(Optional.empty(), Main.readKeyFile(blank.toString()));
      assertEquals(Optional.empty(), Main.readKeyFile(tempDir.toString()));
      assertEquals(Optional.empty(), Main.readKeyFile("\0bad"));
    }
  }

  @Nested
  @DisplayName("in a fresh JVM")
  class FreshJvm {

    @Test
    @DisplayName("the key in the file enables TBA, and the key itself is never logged")
    void enablesTba() throws Exception {
      var key = Files.writeString(tempDir.resolve("tba-api-key"), "secret-key-1234\n");
      var output = MainProcess.run(tempDir, List.of("-logdir", tempDir.toString(),
          "-tba-key-file", key.toString(), "-diskcachedisable"), Map.of());
      assertTrue(output.contains("TBA enrichment enabled"), output);
      assertFalse(output.contains("secret-key-1234"), output);
    }

    @Test
    @DisplayName("a missing file is warned about and TBA stays off")
    void missingFile() throws Exception {
      var missing = tempDir.resolve("no-key");
      var output = MainProcess.run(tempDir, List.of("-logdir", tempDir.toString(),
          "-tba-key-file", missing.toString(), "-diskcachedisable"), Map.of());
      assertTrue(output.contains("TBA API key file " + missing), output);
      assertTrue(output.contains("TBA enrichment disabled"), output);
    }

    @Test
    @DisplayName("with a missing file, TBA_API_KEY still applies")
    void environmentFallback() throws Exception {
      var output = MainProcess.run(tempDir, List.of("-logdir", tempDir.toString(),
          "-tba-key-file", tempDir.resolve("no-key").toString(), "-diskcachedisable"),
          Map.of("TBA_API_KEY", "from-environment"));
      assertTrue(output.contains("TBA enrichment enabled"), output);
    }
  }
}
