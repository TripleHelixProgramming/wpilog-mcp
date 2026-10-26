/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.Path;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * Store folders a test opened through the process's registry. Listing a store folder opens it
 * there, and one store object owns a folder in a process, so a test that lists a folder works on it
 * through that registry and gives it back when it ends.
 */
public final class StoreFolders {
  private StoreFolders() {}

  /** The registry listings use: the process's own. */
  public static StoreRegistry registry() { return LogManager.getInstance().stores(); }

  /** Gives a folder back once its last turn has run; a folder never opened, or already gone, is ignored. */
  public static void release(Path folder) throws IOException {
    if (java.nio.file.Files.isDirectory(folder)) registry().release(folder);
  }
}
