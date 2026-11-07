/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.revlog.RevLogParser;
import org.triplehelix.wpilogmcp.revlog.dbc.DbcLoader;

/** Recovery can serve a partial log; a completed transfer must prove a clean scan through EOF. */
public final class TransferVerification {
  private TransferVerification() {}
  /** Owns the one strict decode until inspection and matching have finished with its bytes. */
  public static final class Content implements AutoCloseable {
    private final Path path;
    private final LogManager.LogUse use;
    private final RevLogParser parser;
    private final org.triplehelix.wpilogmcp.log.FileSnapshot snapshot;
    private org.triplehelix.wpilogmcp.revlog.ParsedRevLog rev;
    private Content(Path path, LogManager.LogUse use, RevLogParser parser,
        org.triplehelix.wpilogmcp.revlog.ParsedRevLog rev, org.triplehelix.wpilogmcp.log.FileSnapshot snapshot) {
      this.snapshot = snapshot;
      this.path = path; this.use = use; this.parser = parser; this.rev = rev;
    }
    public org.triplehelix.wpilogmcp.log.FileSnapshot snapshot() { return snapshot; }
    public org.triplehelix.wpilogmcp.log.LogData wpilog() { return use == null ? null : use.log(); }
    public org.triplehelix.wpilogmcp.revlog.ParsedRevLog revlog() throws IOException {
      if (rev == null) rev = parser.parseComplete(path);
      return rev;
    }
    @Override public void close() { if (use != null) use.close(); }
  }

  public static void verify(Path path, LogManager manager) throws IOException {
    try (var ignored = open(path, manager, new RevLogParser(new DbcLoader().load(null)))) { }
  }

  public static Content open(Path path, LogManager manager, RevLogParser parser) throws IOException {
    manager.stores().validate(path);
    var snapshot = org.triplehelix.wpilogmcp.log.FileSnapshot.of(path);
    if (Files.size(path) > org.triplehelix.wpilogmcp.log.MappedLogBytes.MAX_FILE_BYTES) throw org.triplehelix.wpilogmcp.log.LogFileException.tooLarge(path, Files.size(path));
    byte[] header;
    try (var input = Files.newInputStream(path)) { header = input.readNBytes(6); }
    if (new String(header, StandardCharsets.US_ASCII).equals("WPILOG")) {
      var use = manager.acquireReadOnly(path.toString());
      if (use.log().truncated() || use.log().damaged()) {
        use.close(); throw new IOException("WPILOG scan did not reach a clean EOF");
      }
      return new Content(path, use, parser, null, snapshot);
    }
    var log = parser.parseComplete(path);
    if (log.devices().isEmpty()) throw new IOException("No readable REV devices at EOF");
    return new Content(path, null, parser, log, snapshot);
  }
}
