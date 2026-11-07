/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Shared-server capabilities are documented without promoting an experimental separate product. */
class DocumentationLayoutTest {
  /**
   * Every relative link in a committed guide reaches a file, and a link into a Markdown document
   * reaches one of its headings. Only the operations manuals and the decision index were checked
   * before, so two links to a renamed heading and a line-number anchor into a rendered guide broke
   * unnoticed. Review notes are records kept as written, and local ones are not committed, so
   * neither is read as a guide, and a guide names a local note without linking it.
   */
  @Test void everyRelativeLinkInTheGuidesReachesAFileAndHeading() throws Exception {
    var documents = new ArrayList<>(List.of(Path.of("README.md"), Path.of("CHANGELOG.md"),
        Path.of("vscode-extension/README.md")));
    try (var walk = Files.walk(Path.of("doc"))) {
      walk.filter(path -> path.getFileName().toString().endsWith(".md"))
          .filter(path -> !path.getFileName().toString().contains("REVIEW")
              && !path.getFileName().toString().endsWith(".local.md"))
          .sorted().forEach(documents::add);
    }
    var links = Pattern.compile("\\[[^\\]]*\\]\\(([^\\s)]+)\\)");
    var broken = new ArrayList<String>();
    int checked = 0;
    for (var document : documents) {
      String prose = Files.readString(document)
          .replaceAll("(?ms)^ *```[^\\r\\n]*\\R.*?^ *```[^\\r\\n]*(?:\\R|$)", "");
      var matcher = links.matcher(prose);
      while (matcher.find()) {
        String link = matcher.group(1);
        if (link.matches("[a-zA-Z][a-zA-Z0-9+.-]*:.*")) continue;
        checked++;
        String name = document.toString().replace('\\', '/') + " -> " + link;
        String[] parts = link.split("#", 2);
        Path target = parts[0].isEmpty() ? document
            : document.toAbsolutePath().getParent().resolve(parts[0]).normalize();
        if (!Files.exists(target)) {
          broken.add(name + " (no such file)");
        } else if (parts.length == 2 && target.getFileName().toString().endsWith(".md")
            && !DecisionIndexTest.headings(Files.readString(target)).contains(parts[1])) {
          broken.add(name + " (no such heading)");
        }
      }
    }
    assertTrue(checked > 200, "The link check must read the guides' links; it read " + checked);
    assertEquals(List.of(), broken, "Broken links");
  }

  @Test void visualizationVersionLinesMatchRemoteAdmission() throws Exception {
    String tool = "";
    int checked = 0;
    for (String line : Files.readAllLines(Path.of("doc/TOOLS.md"))) {
      if (line.startsWith("### `")) tool = line.substring(5, line.lastIndexOf('`'));
      if (!tool.startsWith("visualization_") || !line.startsWith("- `api_version`")) continue;
      checked++;
      var operation = org.triplehelix.wpilogmcp.visualization.VisualizationAccess.tool(tool);
      if (operation == org.triplehelix.wpilogmcp.visualization.VisualizationAccess.Operation.ADMINISTRATION) {
        assertTrue(line.contains("trusted local authority is required"), tool + ": " + line);
      } else {
        assertDoesNotThrow(() -> org.triplehelix.wpilogmcp.visualization.VisualizationAccess.transport(
            org.triplehelix.wpilogmcp.mcp.CallerContext.Authority.HTTP_REMOTE, 2, operation));
        assertTrue(line.contains("API 2 admits remote callers"), tool + ": " + line);
        assertFalse(line.contains("both require trusted local authority"), tool + ": " + line);
      }
    }
    assertTrue(checked > 0, "No visualization version claims checked");
  }

  @Test void replicaReferencesDescribeTheImplementedCheckpoint() throws Exception {
    String standalone = Files.readString(Path.of("doc/STANDALONE.md"));
    assertTrue(standalone.contains("`content` (`recorded`, anchored `session`, `unmanaged`, or `file_version`)"));
    assertFalse(standalone.contains("using them still await generation-aware admission"));
    String pit = Files.readString(Path.of("doc/pit-server/STANDALONE.md"));
    assertTrue(pit.contains("Format-2 mirrors do not yet carry system-log text"));
    assertFalse(pit.contains("a mirror carries the shared files that its sessions' spans"));
    assertTrue(Files.readString(Path.of("README.md")).contains("M5–M7 checkpoints"));
  }

  @Test void mainGuidesDescribeCapabilitiesWithoutPromotingASeparateProduct() throws Exception {
    // Direct remote source connections are a normal reader feature; NT4 acquisition setup
    // remains in the experimental guide and must not be promoted as a validated deployment.
    var promotion = Pattern.compile("(?i)nt4Gateway|gatewayPort|networktables\\.first\\.wpi\\.edu");
    for (String file : List.of("README.md", "doc/OPERATIONS.md", "doc/STANDALONE.md",
        "doc/TOOLS.md", "vscode-extension/README.md")) {
      String text = Files.readString(Path.of(file)).replaceAll("\\]\\([^)]*\\)", "]");
      assertFalse(promotion.matcher(text).find(), file + " still promotes experimental setup");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"README.md", "doc/STANDALONE.md"})
  void mainRecordingSummariesStateTheirCurrentTestingLimit(String file) throws Exception {
    assertTrue(Files.readString(Path.of(file)).contains("has limited controlled-shop evidence"), file);
  }

  @Test void experimentalGuidesAreTogetherAndStateTheirTestingLimit() throws Exception {
    var root = Path.of("doc/pit-server");
    for (String file : List.of("README.md", "OPERATIONS.md", "STANDALONE.md", "TOOLS.md",
        "ARCHITECTURE.md", "DEVELOPMENT.md", "EXTENSION.md", "PIT_SERVER_PLAN.md", "IDEAS.md", "EXPLORER_REMOTE_SESSIONS.md")) {
      var path = root.resolve(file);
      assertTrue(Files.isRegularFile(path), "Missing relocated guide: " + path);
      assertTrue(Files.readString(path).contains("has limited controlled-shop evidence"), path.toString());
    }
    assertFalse(Files.exists(Path.of("doc/PIT_SERVER_PLAN.md")), "The plan belongs with its guides");
    assertTrue(Files.isRegularFile(root.resolve("metrics/compose.yaml")));
  }
}
