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
  public static void verify(Path path, LogManager manager) throws IOException {
    manager.stores().validate(path);
    if (Files.size(path) > Integer.MAX_VALUE) throw new IOException("Transferred log exceeds the current 2 GB reader limit");
    byte[] header;
    try (var input = Files.newInputStream(path)) { header = input.readNBytes(6); }
    if (new String(header, StandardCharsets.US_ASCII).equals("WPILOG")) {
      try (var use = manager.acquire(path.toString())) {
        if (use.log().truncated() || use.log().damaged()) throw new IOException("WPILOG scan did not reach a clean EOF");
      }
    } else {
      var log = new RevLogParser(new DbcLoader().load(null)).parseComplete(path);
      if (log.devices().isEmpty()) throw new IOException("No readable REV devices at EOF");
    }
  }
}
