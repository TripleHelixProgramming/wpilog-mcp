/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

class LocalReadBoundaryTest {
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void oversizedControlRecordIsRefusedDuringOpening(boolean metadataUpdate) throws Exception {
    var file = temp.resolve(metadataUpdate ? "large-metadata.wpilog" : "large-declaration.wpilog");
    int recordLimit = 64;
    try (var writer = new WpilogWriter(file, "")) {
      int id = writer.start(metadataUpdate ? "/x" : "/" + "x".repeat(256), "double", "", 0);
      if (metadataUpdate) writer.setMetadata(id, "m".repeat(256), 0);
      writer.append(id, 1, WpilogWriter.encodeDouble(2));
    }
    // Force the oversized control across map boundaries. It must be refused before a complete
    // record view copies it, even though no sample cursor has yet been opened.
    try (var access = LogAccess.local(new ReadOptions(List.of(temp), 1024, recordLimit, 32))) {
      var error = assertThrows(ReadException.class, () -> {
        try (var ignored = access.open(file)) { }
      });
      assertEquals(ReadProblem.Code.RESOURCE_LIMIT, error.code());
      assertTrue(error.getMessage().contains("control"), error.getMessage());
      assertTrue(error.getMessage().contains(Integer.toString(recordLimit)), error.getMessage());
    }
  }

  @Test void oversizedRecordedSchemaCannotSilentlyBecomeTheCanonicalFallback() throws Exception {
    var file = temp.resolve("recorded-schema.wpilog");
    String schemaName = "/.schema/struct:Pose2d";
    // It is legal to record a schema overriding a canonical name. These field names differ
    // deliberately: silently skipping the schema would decode the same 24 bytes as Pose2d.
    String schema = "double recorded_first; double recorded_second; double recorded_third;";
    byte[] encodedSchema = schema.getBytes(StandardCharsets.UTF_8);
    int recordLimit = 64;
    assertTrue(encodedSchema.length > recordLimit);
    try (var writer = new WpilogWriter(file, "")) {
      int schemaId = writer.start(schemaName, "structschema", "", 0);
      writer.append(schemaId, 0, encodedSchema);
      int poseId = writer.start("/pose", "struct:Pose2d", "", 0);
      writer.append(poseId, 1, new byte[24]);
    }

    try (var access = LogAccess.local(new ReadOptions(List.of(temp), 1024, recordLimit, 4096))) {
      var error = assertThrows(ReadException.class, () -> {
        // Close an erroneously accepted snapshot so the failing-first run cannot leak a map.
        try (var ignored = access.open(file)) { }
      });
      assertEquals(ReadProblem.Code.RESOURCE_LIMIT, error.code());
      assertTrue(error.getMessage().contains(schemaName), error.getMessage());
      assertTrue(error.getMessage().contains(Integer.toString(encodedSchema.length)), error.getMessage());
      assertTrue(error.getMessage().contains(Integer.toString(recordLimit)), error.getMessage());
    }
  }
}
