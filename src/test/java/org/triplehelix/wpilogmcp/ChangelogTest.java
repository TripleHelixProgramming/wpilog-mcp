/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** A duplicated release heading can silently put unreleased work in an immutable release. */
class ChangelogTest {
  @Test void eachReleaseHasOneSectionAndUnreleasedComesFirst() throws Exception {
    var headings = Pattern.compile("(?m)^## \\[([^]\\r\\n]+)](?: - \\d{4}-\\d{2}-\\d{2})?\\s*$")
        .matcher(Files.readString(Path.of("CHANGELOG.md"))).results().map(m -> m.group(1)).toList();
    assertFalse(headings.isEmpty(), "The changelog must contain release sections");
    assertEquals("Unreleased", headings.get(0));
    var versions = new HashSet<String>();
    for (String version : headings) assertTrue(versions.add(version), "Duplicate changelog release: " + version);
  }
}
