/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator;

/** Opt-in native multicast proof uses a unique service type and a synthetic empty master.
 * It cannot discover or synchronize the team's production replica advertisements. */
class MdnsPeersTest {
  @TempDir Path root;
  @Test @EnabledIfEnvironmentVariable(named = "WPILOG_MDNS_PROOF", matches = "1")
  void nativeProviderDiscoversHandshakesAndRemovesAnIsolatedSyntheticService() throws Exception {
    root = root.toRealPath(); var security = new SecurityValidator(); security.addAllowedDirectory(root);
    String master = UUID.randomUUID().toString();
    new StoreFiles(root, security).write(root.resolve("store.json"), new StoreManifest.Header(2, "2026-10-19T00:00:00Z", master,
        List.of(), Map.of(), false, List.of(), null, new ReplicaCatalog(1, UUID.randomUUID().toString(), 1, List.of(), List.of(), List.of())));
    var door = new StoreDoor(() -> Set.of(root));
    var address = Collections.list(NetworkInterface.getNetworkInterfaces()).stream().filter(network -> {
      try { return network.isUp() && !network.isLoopback() && network.supportsMulticast(); } catch (java.net.SocketException e) { return false; }
    }).flatMap(network -> Collections.list(network.getInetAddresses()).stream())
        .filter(ip -> ip instanceof Inet4Address && ip.isSiteLocalAddress()).findFirst().orElseThrow(() -> new java.io.IOException("No private multicast interface for native proof"));
    var http = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(address, 0), 0);
    try (var protocol = new ReplicaProtocol(door); var discovery = new PeerDiscovery(); var senderHints = new PeerDiscovery()) {
      http.createContext("/replicas/v1/handshake", exchange -> {
        byte[] bytes = StoreJson.JSON.toJson(protocol.handshake()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length); try (var output = exchange.getResponseBody()) { output.write(bytes); }
      });
      http.createContext("/replicas/v1/close", exchange -> {
        protocol.closeSession(exchange.getRequestHeaders().getFirst("Replica-Session")); exchange.getRequestBody().readAllBytes();
        byte[] bytes = "{\"status\":\"ok\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
      });
      http.start();
      String type = "_wpr-" + UUID.randomUUID().toString().substring(0, 8) + "._tcp.local.";
      try (var receiver = new MdnsPeers(discovery, new StoreDoor(Set::of), address, 9, false, type);
           var sender = new MdnsPeers(senderHints, door, address, http.getAddress().getPort(), true, type)) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (discovery.endpoints(master).isEmpty() && System.nanoTime() < end) Thread.sleep(50);
        assertEquals(List.of("http://" + address.getHostAddress() + ":" + http.getAddress().getPort() + "?store=" + master), discovery.endpoints(master),
            sender.status() + " / " + receiver.status());
        sender.close(); sender.close();
        end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!discovery.endpoints(master).isEmpty() && System.nanoTime() < end) Thread.sleep(50);
        assertTrue(discovery.endpoints(master).isEmpty(), "goodbye or provider removal revokes the supplier");
      }
    } finally { http.stop(0); }
  }
}
