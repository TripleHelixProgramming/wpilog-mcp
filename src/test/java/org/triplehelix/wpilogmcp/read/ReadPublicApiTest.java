/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.triplehelix.wpilogmcp.read.http.RemoteLogAccess;
class ReadPublicApiTest {
  @Test void publicCatalogAndDescriptorDoNotExposeJsonImplementationClasses() throws Exception {
    for (var method : java.util.List.of(RemoteLogAccess.class.getMethod("catalog"),
        RemoteLogAccess.RemoteSnapshot.class.getMethod("descriptor"))) {
      assertFalse(method.getReturnType().getName().contains("gson"), method + " exposes a private JSON dependency");
      assertTrue(method.getReturnType().isRecord(), "The public model is a typed immutable record: " + method);
    }
  }
}
