/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.store.StoreCatalog.StoredFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.LogFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.Matching;
import org.triplehelix.wpilogmcp.store.StoreManifest.Session;

class StoreCompanionTest {
  private static final Session SESSION = new Session("id", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "log",
      null, null, null, null, List.of());
  private static StoredFile file(String session, String kind, String hash, Matching match) {
    var directory = Path.of("store", session);
    return new StoredFile(directory.resolve(kind + "-" + hash + ".log"), directory.resolve("session.json"), null, SESSION,
        new LogFile(kind + "-" + hash + ".log", hash, 42, kind, null, true, 0, 1, null, null, null, false, match));
  }
  private static Matching match(String hash) { return new Matching("by_correlation", hash, 0, 1, 0, 0, "data_alone"); }
  private static boolean companion(StoredFile candidate, StoredFile anchor) {
    var files = List.of(candidate, anchor);
    return RecordedClocks.revAlignment(candidate, anchor, files, "store").isPresent();
  }

  @Test void onlyARevFileWhoseRecordedOffsetLeadsToThisAnchorInThisSessionIsACompanion() {
    var anchor = file("one", "wpilog", "capture-hash", null);
    assertAll(
        () -> assertTrue(companion(file("one", "revlog", "rev", match("capture-hash")), anchor)),
        () -> assertFalse(companion(file("one", "wpilog", "pull", match("capture-hash")), anchor)),
        () -> assertFalse(companion(file("two", "revlog", "rev", match("capture-hash")), anchor)),
        () -> assertFalse(companion(file("one", "revlog", "rev", match("other-hash")), anchor)),
        // An offset that names no reference, in a session that names no recording, leads nowhere.
        () -> assertFalse(companion(file("one", "revlog", "rev", match(null)), anchor)),
        () -> assertFalse(companion(file("one", "revlog", "rev", null), anchor)),
        () -> assertFalse(companion(file("one", "revlog", "rev", match(null)), file("one", "wpilog", null, null))));
  }
}
