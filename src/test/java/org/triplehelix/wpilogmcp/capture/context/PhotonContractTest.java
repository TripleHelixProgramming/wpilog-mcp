/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A backend shape change must reach the published stand-down state, not just a parser exception. */
class PhotonContractTest {
  @ParameterizedTest
  @CsvSource({"version,settings.general.version", "database,missing photon.sqlite",
      "unknown,unexpectedSetting", "mixed,unexpectedSetting"})
  void changedBackendContractStandsDownWithoutDeliveringOrRetrying(String fault, String reason) throws Exception {
    try (var backend = new PhotonFixture()) {
      switch (fault) {
        case "version" -> backend.document.getAsJsonObject("settings").getAsJsonObject("general").addProperty("version", "v0.0.0");
        case "database" -> {
          var out = new ByteArrayOutputStream();
          try (var zip = new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry("settings.txt")); zip.write(1); zip.closeEntry(); }
          backend.export = out.toByteArray();
        }
        case "unknown" -> { backend.document = new com.google.gson.JsonObject(); backend.document.addProperty("unexpectedSetting", 1); }
        case "mixed" -> backend.document.addProperty("unexpectedSetting", 1);
        default -> throw new AssertionError(fault);
      }
      var reference = new AtomicReference<PhotonVisionProvider>();
      var outcome = new CompletableFuture<ProviderStatus>();
      var delivered = new java.util.concurrent.atomic.AtomicInteger();
      try (var provider = new PhotonVisionProvider(backend.address(), () -> 1_000_000.0,
          (session, timestamp, cameras, metadata) -> { delivered.incrementAndGet(); return CompletableFuture.completedFuture(null); },
          () -> {
            var state = reference.get().status();
            if (state.state().equals("stand_down") || state.state().equals("following")) outcome.complete(state);
          })) {
        reference.set(provider); var session = new Object(); provider.session(session, true);
        var state = outcome.get(30, TimeUnit.SECONDS);
        assertEquals("stand_down", state.state(), fault + " must be refused before following");
        assertTrue(state.reason().contains(reason), state.toString());
        assertEquals(0, delivered.get());
        int requests = backend.exports.get(); provider.session(session, true);
        assertEquals(requests, backend.exports.get(), "A failed backend stands down for the session");
      }
    }
  }
}
