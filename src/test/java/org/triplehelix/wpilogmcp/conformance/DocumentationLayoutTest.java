/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Shared-server capabilities are documented without promoting an untested separate product. */
class DocumentationLayoutTest {
  @Test void mainGuidesDescribeCapabilitiesWithoutPromotingASeparateProduct() throws Exception {
    var promotion = Pattern.compile("(?i)pit[- ]server");
    for (String file : List.of("README.md", "doc/OPERATIONS.md", "doc/STANDALONE.md",
        "doc/TOOLS.md", "vscode-extension/README.md")) {
      String text = Files.readString(Path.of(file)).replaceAll("\\]\\([^)]*\\)", "]");
      assertFalse(promotion.matcher(text).find(), file + " still promotes experimental setup");
    }
  }

  @Test void experimentalGuidesAreTogetherAndStateTheirTestingLimit() throws Exception {
    var root = Path.of("doc/pit-server");
    for (String file : List.of("README.md", "OPERATIONS.md", "STANDALONE.md", "TOOLS.md",
        "ARCHITECTURE.md", "DEVELOPMENT.md", "EXTENSION.md", "PIT_SERVER_PLAN.md")) {
      var path = root.resolve(file);
      assertTrue(Files.isRegularFile(path), "Missing relocated guide: " + path);
      assertTrue(Files.readString(path).contains("has not had real-world testing"), path.toString());
    }
    assertFalse(Files.exists(Path.of("doc/PIT_SERVER_PLAN.md")), "The plan belongs with its guides");
    assertTrue(Files.isRegularFile(root.resolve("metrics/compose.yaml")));
  }
}
