/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.log.LogManager;

class TransferVerificationTest {
  @TempDir Path dir;
  @Test void everyFixtureCrossesTheFakeTransportByteForByteAndUsesTheRealReader() throws Exception {
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(dir);
    try {
      var fixtures = org.triplehelix.wpilogmcp.fixtures.FixtureLogs.generateAll(dir.resolve("fixtures"));
      for (var fixture : fixtures) {
        byte[] expected = Files.readAllBytes(fixture.path());
        var copy = dir.resolve("copy-" + fixture.id() + ".wpilog");
        var f = new FileTransferTest.Fake() {
          @Override public void verify(String name) throws IOException {
            verifies++; manager.release(copy); Files.write(copy, local.get(name)); TransferVerification.verify(copy, manager);
          }
        };
        f.put(fixture.path().getFileName().toString(), expected, 123); f.finish(f.engine());
        var entry = f.saved.files().get(0);
        assertArrayEquals(expected, f.local.get(entry.localName()), fixture.id());
        assertEquals(!fixture.id().equals("truncated"), entry.verified(), fixture.id());
        assertEquals(fixture.id().equals("truncated") ? 2 : 1, f.verifies, fixture.id());
      }
    } finally { manager.release(dir); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
  @Test void aRecoverableTruncatedFileIsNotACompletedTransfer() throws Exception {
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(dir);
    try {
      var path = dir.resolve("complete.wpilog");
      try (var w = new WpilogWriter(path, "synthetic transfer verification")) {
        int entry = w.start("/x", "int64", "", 0); w.append(entry, 1_000_000, WpilogWriter.encodeInt64(7));
      }
      TransferVerification.verify(path, manager);
      byte[] full = Files.readAllBytes(path); var cut = dir.resolve("cut.wpilog"); Files.write(cut, Arrays.copyOf(full, full.length - 1));
      try (var use = manager.acquire(cut.toString())) { assertTrue(use.log().truncated()); }
      assertTrue(assertThrows(IOException.class, () -> TransferVerification.verify(cut, manager)).getMessage().contains("EOF"));
      var malformed = dir.resolve("malformed.wpilog"); Files.writeString(malformed, "synthetic invalid header");
      assertThrows(IOException.class, () -> TransferVerification.verify(malformed, manager));
    } finally { manager.release(dir); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
  @Test void nativeRevVerificationRequiresCompleteHeadersAndFrames() throws Exception {
    var manager = LogManager.getInstance(); var allowed = manager.getAllowedDirectories(); manager.addAllowedDirectory(dir);
    try {
      // Native REV: one firmware record, entry 1, ten-byte firmware frame, CAN id 5.
      byte[] valid = {0, 1, 10, 5, 0, 0, 0, 1, 0, 0, 0, 0, 0};
      var path = dir.resolve("native.revlog"); Files.write(path, valid); TransferVerification.verify(path, manager);
      int index = 0;
      for (byte[] tail : List.of(new byte[]{0}, new byte[]{0, 2, 16, 1}, new byte[]{0, 2, 1, 0}, new byte[]{(byte) 0xf0, 1, 0})) {
        var corrupt = dir.resolve("native-" + index++ + ".revlog");
        var joined = Arrays.copyOf(valid, valid.length + tail.length); System.arraycopy(tail, 0, joined, valid.length, tail.length);
        Files.write(corrupt, joined);
        assertTrue(assertThrows(IOException.class, () -> TransferVerification.verify(corrupt, manager)).getMessage().contains("EOF"));
      }
    } finally { manager.release(dir); manager.clearAllowedDirectories(); allowed.forEach(manager::addAllowedDirectory); }
  }
}
