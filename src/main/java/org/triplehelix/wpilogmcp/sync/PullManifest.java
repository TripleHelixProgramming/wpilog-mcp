/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import java.util.List;

/** Per-serial transfer progress. Retired generations remain files, never append targets. */
public record PullManifest(int formatVersion, String serialNumber, List<Entry> files, List<Entry> history) {
  public static final int FORMAT_VERSION = 1;
  public PullManifest {
    if (formatVersion != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported pull manifest version " + formatVersion);
    files = List.copyOf(files); history = List.copyOf(history);
    if (files.stream().map(Entry::remoteName).distinct().count() != files.size()) throw new IllegalArgumentException("Duplicate remote names");
  }
  public static PullManifest empty(String serial) { return new PullManifest(FORMAT_VERSION, serial, List.of(), List.of()); }
  public record Entry(String remoteName, long size, long mtimeMillis, long bytesCopied,
      boolean verified, String localName, int retries, String failure) {
    public Entry {
      if (remoteName == null || remoteName.isBlank() || localName == null || localName.isBlank()
          || size < 0 || bytesCopied < 0 || bytesCopied > size || retries < 0 || retries > 1) {
        throw new IllegalArgumentException("Invalid pull manifest entry");
      }
      if (verified && (bytesCopied != size || failure != null)) throw new IllegalArgumentException("Incomplete verified pull");
    }
    Entry progress(RemoteFiles.File remote, long bytes, boolean checked, String local, int retry, String error) {
      return new Entry(remote.name(), remote.size(), remote.mtimeMillis(), bytes, checked, local, retry, error);
    }
  }
}
