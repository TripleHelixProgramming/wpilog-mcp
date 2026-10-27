/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.fixtures;

/**
 * The start of a command for a JVM that a test starts: this JVM's java, headless. Chart images draw
 * through AWT, and on a Mac a JVM that is not headless gets a Dock icon that takes focus, so every JVM
 * a test starts is headless, as the test JVMs themselves are (build.gradle).
 */
public final class ChildJvms {
  private ChildJvms() {}

  /** This JVM's java executable. */
  public static final String JAVA = ProcessHandle.current().info().command().orElse("java");
  /** The option that keeps a JVM from opening a window. */
  public static final String HEADLESS = "-Djava.awt.headless=true";
}
