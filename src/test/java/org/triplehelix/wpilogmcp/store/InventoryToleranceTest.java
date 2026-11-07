/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.triplehelix.wpilogmcp.fixtures.SystemSessionFixture.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.capture.pull.FakeRobot;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** Reader tolerance must not weaken the inventory used by existing store writers. */
class InventoryToleranceTest {
  @TempDir Path temp;

  @ParameterizedTest @ValueSource(strings = {"robot.json", "system/index.json", "pull.json", "import.json"})
  void invalidControlManifestsStillRefuseStatusPublicationAndSystemPull(String control) throws Exception {
    var root = temp.toRealPath().resolve("store");
    create(root, "session", true, 42);
    var security = new SecurityValidator(); security.addAllowedDirectory(temp);
    try (var registry = new StoreRegistry(security)) {
      var store = registry.store(root);
      // A second robot exercises inventory tolerance, not the puller's own index reread.
      var other = root.resolve("robots/other"); Files.createDirectories(other);
      Files.writeString(other.resolve("robot.json"), StoreJson.JSON.toJson(
          new StoreManifest.Robot("other", "other", null, null, "logged")));
      var invalid = control.equals("import.json") ? root.resolve("unassigned/bad/import.json") : other.resolve(control);
      Files.createDirectories(invalid.getParent()); Files.writeString(invalid, "{broken");
      try (var observer = store.observe(new LogStore.Observer() {
        public void manifest(Path path, Object value) {}
        public void inventory(StoreCatalog.Snapshot snapshot) { fail("Invalid control manifest was skipped: " + control); }
      })) {
        var failure = assertThrows(ExecutionException.class, () -> store.refreshStatus().get(10, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());
      }
      var pull = store.systemPulls(FakeRobot.device(SERIAL, "SHA256:fixture"), WALL);
      assertThrows(IOException.class, pull::beginPass, "A writer must not proceed without " + control);
    }
  }
}
