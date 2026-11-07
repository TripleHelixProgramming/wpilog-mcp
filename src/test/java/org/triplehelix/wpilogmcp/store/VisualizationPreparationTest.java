/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

class VisualizationPreparationTest {
  @TempDir Path root;

  @Test void visualizationMetadataIsNotAnUnmanagedLogButAdjacentFilesStillAre() throws Exception {
    root = root.toRealPath();
    var security = new SecurityValidator(); security.addAllowedDirectory(root);
    var io = new StoreFiles(root, security);
    io.write(root.resolve("store.json"), new StoreManifest.Header(StoreManifest.FORMAT_VERSION,
        Instant.now().toString(), UUID.randomUUID().toString(), List.of()));
    var definitions = Files.createDirectories(root.resolve("visualizations/templates"));
    Files.writeString(definitions.resolve("fixture.json"), "{}");
    var stray = Files.writeString(root.resolve("unmanaged.txt"), "synthetic stray");
    assertEquals(List.of(stray), StoreCatalog.read(root, security).unmanaged());
  }
}
