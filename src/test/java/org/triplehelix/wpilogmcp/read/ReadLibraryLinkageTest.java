/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Test;

/** A happy-path smoke cannot reach every type in a reader's error or manifest path. */
class ReadLibraryLinkageTest {
  @Test void everyReaderImplementationDependencyIsPackaged() {
    var out = new StringWriter();
    var tool = ToolProvider.findFirst("jdeps").orElseThrow();
    int status = tool.run(new PrintWriter(out), new PrintWriter(out), "--multi-release", "17",
        "--ignore-missing-deps", "-verbose:class", "-filter:none", System.getProperty("read.testJar"));
    assertEquals(0, status, out.toString());
    var missing = out.toString().lines().filter(line -> line.stripLeading().startsWith("org.triplehelix.")
        && line.contains("not found")).toList();
    assertEquals(java.util.List.of(), missing, "The reader must not borrow omitted server classes");
  }
}
