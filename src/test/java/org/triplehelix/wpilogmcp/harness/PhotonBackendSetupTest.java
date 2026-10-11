/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.harness;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

class PhotonBackendSetupTest {
  @Test void networkModeUsesTheReleasesNumericEnumWithoutManagingTheHost() {
    var config = JsonParser.parseString(PhotonBackend.networkConfiguration()).getAsJsonObject();
    assertTrue(config.getAsJsonPrimitive("connectionType").isNumber(), "NetworkMode's JSON values are 0 and 1, not DHCP and Static");
    assertEquals(0, config.get("connectionType").getAsInt());
    assertEquals("127.0.0.1", config.get("ntServerAddress").getAsString());
    assertFalse(config.get("runNTServer").getAsBoolean());
    assertFalse(config.get("shouldManage").getAsBoolean());
  }

  @Test void settingsRestartMustFinishBeforeTheAuditSocketConnects() {
    String initial = "Listening on http://localhost:5800/\n";
    String stopping = initial + "Web server going down for restart\n";
    assertFalse(PhotonBackend.restarted(initial, initial.length()), "The original listener is not the restarted one");
    assertFalse(PhotonBackend.restarted(stopping, initial.length()), "The asynchronous restart has not bound yet");
    assertTrue(PhotonBackend.restarted(stopping + initial, initial.length()));
    assertFalse(PhotonBackend.restarted(stopping + initial, (stopping + initial).length()), "Ignore an earlier restart");
  }
  @TempDir Path temporary;

  @Test void settingsSavedBeforeAnAbruptCloseStillReachTheRestartBarrier() throws Exception {
    var output = temporary.resolve("backend.log");
    Files.writeString(output, "Listening on http://localhost:5800/\n");
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/settings/general", exchange -> {
      exchange.getRequestBody().readAllBytes();
      Files.writeString(output, "Successfully saved general settings\n"
          + "Web server going down for restart\nListening on http://localhost:5800/\n",
          StandardOpenOption.APPEND);
      exchange.close(); // The pinned backend can restart before its response headers arrive.
    });
    server.start();
    try {
      PhotonBackend.configureNetwork(HttpClient.newHttpClient(),
          URI.create("http://127.0.0.1:" + server.getAddress().getPort()), output, () -> true, 5);
    } finally { server.stop(0); }
  }

  @Test void restartWithoutAFreshSaveIsNotSuccess() {
    String saved = "Successfully saved general settings\n";
    String restarted = "Web server going down for restart\nListening on http://localhost:5800/\n";
    assertFalse(PhotonBackend.settingsApplied(restarted, 0), "Restart alone does not prove a save");
    assertFalse(PhotonBackend.settingsApplied(saved + restarted, saved.length()), "An earlier save is not this request");
    assertFalse(PhotonBackend.settingsApplied(saved, 0), "The save alone is not a restarted listener");
    assertTrue(PhotonBackend.settingsApplied(saved + restarted, 0));
  }

  @Test void unconfirmedSaveReportsTheBackendTail() throws Exception {
    var output = temporary.resolve("backend.log");
    Files.writeString(output, "previous startup\n");
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/settings/general", exchange -> {
      exchange.getRequestBody().readAllBytes();
      Files.writeString(output, "synthetic save refusal\n", StandardOpenOption.APPEND);
      exchange.close();
    });
    server.start();
    try {
      var failure = assertThrows(AssertionError.class, () -> PhotonBackend.configureNetwork(
          HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
          output, () -> true, 0)); // No idle wait: missing evidence cannot become true.
      assertTrue(failure.getMessage().contains("backend.log tail"));
      assertTrue(failure.getMessage().contains("synthetic save refusal"));
      assertEquals(1, failure.getSuppressed().length, "Retain the failed delivery for diagnosis");
    } finally { server.stop(0); }
  }

}
