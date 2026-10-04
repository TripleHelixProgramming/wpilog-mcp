/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * A file as it looked at one moment: its size, modification time, and identity (the inode on
 * Unix; Windows reports none). A loaded log keeps the snapshot taken before it was read, and
 * every later call compares it with the file now, so a log copied off the robot again once it has
 * grown is reloaded rather than answered from the first copy, and a result read while the file
 * was being replaced is discarded rather than trusted.
 *
 * <p>The three fields catch the three ways a file changes under a loaded log. A file renamed
 * into place (rsync's default) has a new identity; one overwritten in place ({@code cp} keeps the
 * inode) has a new size or time; one still being written grows between two looks. A change the
 * attributes do not show (the same bytes rewritten within the file system's time resolution)
 * goes unnoticed, which is why the mapped reads themselves are guarded too (an
 * {@link InternalError} from a read of a file truncated under its mapping is turned into an
 * explained error).
 *
 * @param size The file's size in bytes
 * @param modified Its last modification time
 * @param fileKey Its identity, or null where the file system reports none
 * @since 0.9.1
 */
public record FileSnapshot(long size, FileTime modified, Object fileKey) {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  /**
   * Reads the file's attributes now.
   *
   * @param path The file
   * @return Its snapshot, or null when it does not exist
   * @throws IOException if the attributes cannot be read for another reason
   */
  public static FileSnapshot of(Path path) throws IOException {
    try {
      var attrs = Files.readAttributes(path, BasicFileAttributes.class);
      return new FileSnapshot(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey());
    } catch (NoSuchFileException e) {
      return null;
    }
  }

  /**
   * Whether the file is, as far as its attributes show, the one this snapshot was taken of. The
   * identity is compared only when both sides have one.
   *
   * @param now The file's snapshot now, or null when it no longer exists
   */
  public boolean sameAs(FileSnapshot now) {
    if (now == null) return false;
    if (size != now.size || !modified.equals(now.modified)) return false;
    return fileKey == null || now.fileKey == null || fileKey.equals(now.fileKey);
  }

  /**
   * What changed between this snapshot and the file now, for a message: which of the three
   * attributes differ, with the old and new values.
   *
   * @param now The file's snapshot now, or null when it no longer exists
   */
  public String describeChange(FileSnapshot now) {
    if (now == null) return "the file no longer exists";
    var parts = new java.util.ArrayList<String>();
    if (fileKey != null && now.fileKey != null && !fileKey.equals(now.fileKey)) {
      parts.add("it was replaced by another file");
    }
    if (size != now.size) {
      parts.add("its size went from " + bytes(size) + " to " + bytes(now.size));
    }
    if (!modified.equals(now.modified)) {
      parts.add("it was modified at " + TIME.format(now.modified.toInstant())
          + ", after " + TIME.format(modified.toInstant()));
    }
    return parts.isEmpty() ? "its attributes changed" : String.join(", ", parts);
  }

  /** A byte count for a message: exact below a megabyte, else in MB to one decimal. */
  static String bytes(long n) {
    return n < 1024 * 1024 ? n + " bytes" : String.format("%.1f MB", n / (1024.0 * 1024.0));
  }
}
