/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** A decision locator must keep pointing to its evidence after a guide or heading is renamed. */
class DecisionIndexTest {
  @Test void everyDecisionHasADateStatusAndAResolvableHeading() throws Exception {
    var document = Path.of("doc/DECISIONS.md");
    assertTrue(Files.isRegularFile(document), "Missing decision index: doc/DECISIONS.md");
    var links = Pattern.compile("\\[[^\\]]+\\]\\(([^\\s)]+)\\)");
    int rows = 0;
    for (String line : Files.readAllLines(document)) {
      if (!line.startsWith("|") || line.startsWith("| Date |") || line.startsWith("|---")) continue;
      rows++;
      String[] columns = line.split("\\|", -1);
      assertEquals(6, columns.length, "One dated decision per row: " + line);
      assertTrue(columns[1].strip().matches("\\d{4}-\\d{2}-\\d{2}(?: \\(indexed\\))?"),
          "Date must identify the decision or explicitly identify its indexing date: " + line);
      String date = columns[1].strip().split(" ", 2)[0];
      assertDoesNotThrow(() -> LocalDate.parse(date), "Invalid decision date: " + line);
      assertTrue(Set.of("standing", "superseded", "rejected").contains(columns[2].strip()),
          "Unknown decision status: " + line);
      var matcher = links.matcher(columns[4]);
      assertTrue(matcher.find(), "Decision needs its recorded heading: " + line);
    }
    assertTrue(rows > 0, "Decision index must not be empty");
    int checked = 0;
    var matcher = links.matcher(Files.readString(document));
    while (matcher.find()) {
      String link = matcher.group(1);
      assertFalse(link.matches("[a-zA-Z][a-zA-Z0-9+.-]*:.*"), "Use a repository decision record: " + link);
      String[] parts = link.split("#", -1);
      assertEquals(2, parts.length, "Decision link needs exactly one heading: " + link);
      assertFalse(parts[1].isBlank(), "Decision link needs a heading: " + link);
      Path target = document.getParent().resolve(parts[0]).normalize();
      assertTrue(Files.isRegularFile(target), "Missing decision file: " + link);
      assertTrue(headings(Files.readString(target)).contains(parts[1]), "Missing decision heading: " + link);
      checked++;
    }
    assertTrue(checked >= rows, "Every indexed decision must have a checked heading");
  }

  @Test void headingAnchorsHandleFencesFormattingUnicodeAndCollisionSuffixes() {
    assertEquals(Set.of("a-heading", "a-heading-1", "a-heading-2", "a-heading-1-1",
        "café-μs", "setext-heading"), headings("""
        # A **heading**
        ## A heading-1
        # A heading
        ### A heading-1 ###
        ```text
        # Fenced text
        ```
        ~~~
        # More fenced text
        ~~~
        # Café `μs`
        Setext heading
        --------------
        """));
  }

  /**
   * Repository headings use GitHub anchors: punctuation is removed, spaces become hyphens,
   * and each collision takes the first unused numeric suffix, including naturally suffixed
   * headings. Code fences are prose examples, not link targets.
   */
  static Set<String> headings(String markdown) {
    var result = new HashSet<String>();
    char fence = 0;
    int fenceLength = 0;
    String previous = null;
    for (String line : markdown.split("\\R")) {
      var marker = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$").matcher(line);
      if (marker.matches()) {
        String run = marker.group(1);
        if (fence == 0) { fence = run.charAt(0); fenceLength = run.length(); }
        else if (run.charAt(0) == fence && run.length() >= fenceLength && marker.group(2).isBlank())
          fence = 0;
        previous = null;
        continue;
      }
      if (fence != 0) continue;
      var atx = Pattern.compile("^ {0,3}#{1,6}\\s+(.+?)\\s*$").matcher(line);
      String title = atx.matches() ? atx.group(1).replaceFirst("\\s+#+\\s*$", "")
          : line.matches("^ {0,3}(?:=+|-+)\\s*$") && previous != null ? previous : null;
      if (title != null) {
        String base = title.replaceAll("<[^>]+>", "")
            .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")
            .strip().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_ -]", "").replace(' ', '-');
        String candidate = base;
        for (int suffix = 1; result.contains(candidate); suffix++) candidate = base + "-" + suffix;
        result.add(candidate);
      }
      previous = line.isBlank() || title != null ? null : line.strip();
    }
    return result;
  }
}
