/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.store.StoreCatalog.StoredFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.LogFile;
import org.triplehelix.wpilogmcp.store.StoreManifest.Matching;

class StoreCompanionTest {
  private static StoredFile file(String session, String kind, String hash, Matching match) {
    var directory = Path.of("store", session);
    return new StoredFile(directory.resolve("log." + kind), directory.resolve("session.json"), null, null,
        new LogFile("log." + kind, hash, 42, kind, null, true, 0, 1, null, null, null, false, match));
  }
  private static Matching match(String hash) { return new Matching("by_correlation", hash, 0, 1, 0, 0, "data_alone"); }

  @Test void onlyARevFileMatchedToThisHashedAnchorInThisSessionIsACompanion() {
    var anchor = file("one", "wpilog", "capture-hash", null);
    assertAll(
        () -> assertTrue(StoreCatalog.isRevCompanion(file("one", "revlog", "rev", match("capture-hash")), anchor)),
        () -> assertFalse(StoreCatalog.isRevCompanion(file("one", "wpilog", "pull", match("capture-hash")), anchor)),
        () -> assertFalse(StoreCatalog.isRevCompanion(file("two", "revlog", "rev", match("capture-hash")), anchor)),
        () -> assertFalse(StoreCatalog.isRevCompanion(file("one", "revlog", "rev", match("other-hash")), anchor)),
        () -> assertFalse(StoreCatalog.isRevCompanion(file("one", "revlog", "rev", match(null)), anchor)),
        () -> assertFalse(StoreCatalog.isRevCompanion(file("one", "revlog", "rev", null), anchor)),
        () -> assertFalse(StoreCatalog.isRevCompanion(file("one", "revlog", "rev", match(null)), file("one", "wpilog", null, null))));
  }
}
