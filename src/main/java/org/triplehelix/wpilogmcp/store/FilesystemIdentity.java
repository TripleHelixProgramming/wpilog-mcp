/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Which filesystem a path is on, the same however the path is spelled. A {@link FileStore} is not:
 * on Windows, Java reaches a path longer than 247 characters through a {@code \\?\} prefix, and that
 * path's store names its volume {@code \\?\C:}, unequal to the same volume reached through a shorter
 * path. A stage sits about fifty characters below its destination, so one disk looked like two:
 * placements were refused as crossing filesystems, and the disk budget counted the disk twice.
 * Windows' volume serial number does not depend on the spelling; elsewhere the store's own
 * identity already does not.
 */
record FilesystemIdentity(Object key) {
  static FilesystemIdentity of(FileStore store) throws IOException {
    try {
      return new FilesystemIdentity(List.of(store.getAttribute("volume:vsn"), store.type()));
    } catch (UnsupportedOperationException notWindows) {
      return new FilesystemIdentity(store);
    }
  }

  static FilesystemIdentity of(Path path) throws IOException { return of(Files.getFileStore(path)); }

  static boolean same(Path first, Path second) throws IOException { return of(first).equals(of(second)); }
}
