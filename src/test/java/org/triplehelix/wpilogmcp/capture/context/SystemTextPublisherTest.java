/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.store.SystemPullStore;

class SystemTextPublisherTest {
  @TempDir Path temp;
  @Test void completeLinesPreserveUnicodeAndBlankLinesButWaitForAnIncompleteSuffix() throws Exception {
    var lines = new SystemTextPublisher.TextLines(new StringReader("alpha \u03b2\r\n\npartial"));
    assertEquals("alpha \u03b2", lines.next()); assertEquals("", lines.next()); assertNull(lines.next());
  }
  @Test void aLineCannotExceedItsBound() {
    var lines = new SystemTextPublisher.TextLines(new StringReader("x".repeat(65537) + "\n"));
    assertTrue(assertThrows(IOException.class, lines::next).getMessage().contains("64 KiB"));
  }
  @Test void aPublicationCannotExceedItsLineCount() throws Exception {
    var lines = new SystemTextPublisher.TextLines(new StringReader("\n".repeat(65537)));
    for (int i = 0; i < 65536; i++) assertEquals("", lines.next());
    assertTrue(assertThrows(IOException.class, lines::next).getMessage().contains("65,536"));
  }
  @Test void aPublicationCannotExceedItsCharacterBudget() throws Exception {
    var lines = new SystemTextPublisher.TextLines(new StringReader(("x".repeat(65536) + "\n").repeat(257)));
    for (int i = 0; i < 256; i++) assertEquals(65536, lines.next().length());
    assertTrue(assertThrows(IOException.class, lines::next).getMessage().contains("16 MiB"));
  }
  @Test void snapshotGrowthReusesOnlyAnIdenticalPrefixAndRetriesTheUnterminatedLine() throws Exception {
    var old = temp.resolve("old.txt"); var current = temp.resolve("current.txt");
    Files.writeString(old, "first \u03b2\npartial"); Files.writeString(current, "first \u03b2\npartial complete\nnew\n");
    var text = new SystemPullStore.Text("s", temp.resolve("capture.wpilog"), current, "syslog", "text", "/var/log/messages", 0, Files.size(current), old, Files.size(old));
    assertEquals("first \u03b2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, SystemTextPublisher.verifiedPrefix(text));
    Files.writeString(old, "wrong \u03b2\npartial"); assertEquals(0, SystemTextPublisher.verifiedPrefix(text));
    Files.writeString(old, "short"); assertEquals(0, SystemTextPublisher.verifiedPrefix(text));
  }
  @Test void prefixProofHonorsMoveReservationsOnBothFilesAndReleasesItsReaders() throws Exception {
    var old = temp.resolve("old.txt"); var current = temp.resolve("current.txt");
    Files.writeString(old, "first\n"); Files.writeString(current, "first\nsecond\n");
    var text = new SystemPullStore.Text("s", temp.resolve("capture.wpilog"), current, "syslog", "text", "/var/log/messages", 0, Files.size(current), old, Files.size(old));
    for (var path : java.util.List.of(old, current)) {
      try (var moving = org.triplehelix.wpilogmcp.log.LogFileAccess.reserveMove(java.util.List.of(path))) {
        assertThrows(IOException.class, () -> SystemTextPublisher.verifiedPrefix(text));
      }
    }
    assertEquals(6, SystemTextPublisher.verifiedPrefix(text));
    try (var moving = org.triplehelix.wpilogmcp.log.LogFileAccess.move(java.util.List.of(old, current))) { }
  }
}
