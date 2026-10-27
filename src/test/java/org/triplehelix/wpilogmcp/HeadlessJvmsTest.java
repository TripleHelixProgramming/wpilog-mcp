/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** No JVM a test runs or starts opens a window: on a Mac one that is not headless takes focus. */
class HeadlessJvmsTest {
  @Test void theTestJvmIsHeadless() {
    assertEquals("true", System.getProperty("java.awt.headless"), "every test task sets it (build.gradle)");
    assertTrue(java.awt.GraphicsEnvironment.isHeadless());
  }

  @Test void theServerIsHeadlessUnlessItsCommandLineSaysOtherwise() {
    String saved = System.getProperty("java.awt.headless");
    try {
      System.clearProperty("java.awt.headless");
      Main.headlessByDefault();
      assertEquals("true", System.getProperty("java.awt.headless"));
      System.setProperty("java.awt.headless", "false");
      Main.headlessByDefault();
      assertEquals("false", System.getProperty("java.awt.headless"), "a value set on the command line wins");
    } finally {
      if (saved == null) System.clearProperty("java.awt.headless"); else System.setProperty("java.awt.headless", saved);
    }
  }

  @Test void testsStartJvmsOnlyThroughTheHeadlessFixture() throws Exception {
    String direct = "ProcessHandle.current().info()" + ".command()";
    var found = new ArrayList<String>();
    try (var files = Files.walk(Path.of("src", "test", "java"))) {
      for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        if (!file.getFileName().toString().equals("ChildJvms.java") && Files.readString(file).contains(direct)) found.add(file.toString());
      }
    }
    assertEquals(List.of(), found, "a test that starts a JVM takes its java and the headless option from ChildJvms");
  }
}
