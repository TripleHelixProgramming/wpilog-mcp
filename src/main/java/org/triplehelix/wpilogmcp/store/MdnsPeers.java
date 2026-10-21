/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.jmdns.*;

/** JmDNS is the replaceable DNS-SD provider. TXT records contain identifiers, never URLs or
 * credentials. Only resolved local-link addresses can become bounded handshake candidates.
 * Provider protocol buffers and OS multicast state are separate from our accounted peer inventory. */
public final class MdnsPeers implements AutoCloseable {
  private final PeerDiscovery discovery;
  private final StoreDoor door;
  private final String type;
  private final InetAddress address;
  private final int port;
  private final boolean advertise;
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
    var t = new Thread(task, "replica-mdns"); t.setDaemon(true); return t;
  });
  private volatile JmDNS dns;
  private volatile boolean closed;
  private volatile String state = "starting", reason;
  private final Map<String, ServiceInfo> registered = new HashMap<>();
  private final Map<String, String[]> seen = new ConcurrentHashMap<>();
  private final Object seenLock = new Object();
  private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
  public record Status(String provider, String state, String reason, String serviceType) {}
  public MdnsPeers(PeerDiscovery discovery, StoreDoor door, InetAddress address, int port, boolean advertise) {
    this(discovery, door, address, port, advertise, PeerDiscovery.SERVICE_TYPE);
  }
  MdnsPeers(PeerDiscovery discovery, StoreDoor door, InetAddress address, int port, boolean advertise, String type) {
    this.discovery = discovery; this.door = door; this.address = address; this.port = port; this.advertise = advertise; this.type = type;
    worker.scheduleWithFixedDelay(this::refresh, 0, 30, TimeUnit.SECONDS);
  }
  public Status status() { return new Status("JmDNS 3.6.2", state, reason, type); }
  private void refresh() {
    if (closed) return;
    try {
      if (dns == null) {
        var fresh = JmDNS.create(address, "wpilog-" + UUID.randomUUID().toString().substring(0, 8));
        if (closed) { fresh.close(); return; }
        dns = fresh; fresh.addServiceListener(type, listener());
      }
      if (advertise) {
        var eligible = new LinkedHashMap<String, ServiceInfo>();
        for (var selected : door.stores()) {
          var header = new StoreFiles(selected.root(), selected.security()).read(selected.root().resolve("store.json"), StoreManifest.Header.class);
          if (header.replicas() == null) continue;
          String master = header.mirror() ? header.origin().storeId() : header.id();
          if (master == null) continue;
          if (eligible.size() >= 64) throw new IOException("At most 64 advertised replica stores per interface");
          eligible.put(header.id(), ServiceInfo.create(type, "wpilog-" + header.id(), port, 0, 0,
              Map.of("v", "1", "peer", header.id(), "master", master, "path", "/replicas/v1")));
        }
        for (var key : List.copyOf(registered.keySet())) if (!eligible.containsKey(key)) dns.unregisterService(registered.remove(key));
        for (var entry : eligible.entrySet()) if (!registered.containsKey(entry.getKey())) {
          dns.registerService(entry.getValue()); registered.put(entry.getKey(), entry.getValue());
        }
      }
      // Resolving/listing waits only on this provider's daemon, never the store or NT queue.
      for (var info : dns.list(type, 500)) accept(info);
      state = "ready"; reason = null;
    } catch (IOException | RuntimeException failure) { state = "unavailable"; reason = Objects.toString(failure.getMessage(), "mDNS unavailable"); }
  }
  private ServiceListener listener() {
    return new ServiceListener() {
      @Override public void serviceAdded(ServiceEvent event) { /* The bounded provider poll resolves incomplete announcements. */ }
      @Override public void serviceResolved(ServiceEvent event) { accept(event.getInfo()); }
      @Override public void serviceRemoved(ServiceEvent event) {
        var prior = seen.remove(event.getName()); if (prior != null) discovery.remove(prior[0], prior[1]);
      }
    };
  }
  private void accept(ServiceInfo info) {
    if (closed || info.getTextBytes().length > 4096 || !"1".equals(info.getPropertyString("v"))
        || !"/replicas/v1".equals(info.getPropertyString("path")) || info.getPort() < 1 || info.getPort() > 65535) return;
    String peer = info.getPropertyString("peer"), master = info.getPropertyString("master");
    if (peer == null || master == null) return;
    for (var host : info.getInetAddresses()) {
      if (host.isAnyLocalAddress() || host.isMulticastAddress() || !(host.isSiteLocalAddress() || host.isLinkLocalAddress() || host.isLoopbackAddress())) continue;
      try {
        String endpoint = new URI("http", null, host.getHostAddress(), info.getPort(), null, null, null).toASCIIString();
        synchronized (seenLock) {
          if (closed || seen.size() >= 64 && !seen.containsKey(info.getName())) return;
          seen.put(info.getName(), new String[] {peer, endpoint});
        }
        discovery.announce(peer, master, endpoint, info.getTextBytes().length, 90); return;
      } catch (URISyntaxException ignored) { /* Malformed provider addresses never become fetch URLs. */ }
    }
  }
  /** Start only on configured local interfaces. Manual endpoints need none of these sockets. */
  public static List<MdnsPeers> start(PeerDiscovery discovery, StoreDoor door, String bind, int port) throws IOException {
    var selected = InetAddress.getByName(bind); boolean announce = !selected.isLoopbackAddress();
    var addresses = new ArrayList<InetAddress>();
    if (!selected.isAnyLocalAddress() && !selected.isLoopbackAddress()) addresses.add(selected);
    else for (var network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      if (!network.isUp() || network.isLoopback() || !network.supportsMulticast()) continue;
      for (var address : Collections.list(network.getInetAddresses()))
        if (address instanceof Inet4Address && address.isSiteLocalAddress() && addresses.size() < 4) addresses.add(address);
    }
    return addresses.stream().map(address -> new MdnsPeers(discovery, door, address, port, announce)).toList();
  }
  @Override public void close() {
    if (!closing.compareAndSet(false, true)) return;
    closed = true; seen.values().forEach(value -> discovery.remove(value[0], value[1])); seen.clear();
    // close() can wait for DNS goodbye packets. It remains daemon-owned and cannot delay HTTP shutdown.
    worker.execute(() -> { var owned = dns; dns = null; if (owned != null) try { owned.close(); } catch (IOException ignored) {} });
    worker.shutdown();
  }
}
