/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import java.io.IOException;
import java.io.Serializable;
import java.net.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.*;
import java.util.Map;
import javax.management.remote.*;
import javax.management.remote.rmi.RMIConnectorServer;

/** The test JVM's real MBeans, with registry and connector sharing one ephemeral loopback port. */
public final class JmxFixture implements AutoCloseable {
  private static final class Sockets implements RMIServerSocketFactory {
    int port;
    public ServerSocket createServerSocket(int requested) throws IOException {
      var socket = new ServerSocket(requested, 50, InetAddress.getByName("127.0.0.1")); port = socket.getLocalPort(); return socket;
    }
  }
  private record ClientSockets() implements RMIClientSocketFactory, Serializable {
    public Socket createSocket(String host, int port) throws IOException { return new Socket("127.0.0.1", port); }
  }
  private final Registry registry;
  private final JMXConnectorServer server;
  private final int port;
  public JmxFixture() throws Exception {
    var sockets = new Sockets(); var clients = new ClientSockets();
    registry = LocateRegistry.createRegistry(0, clients, sockets); port = sockets.port;
    var url = new JMXServiceURL("service:jmx:rmi://127.0.0.1:" + port + "/jndi/rmi://127.0.0.1:" + port + "/jmxrmi");
    server = JMXConnectorServerFactory.newJMXConnectorServer(url, Map.of(
        RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE, sockets,
        RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE, clients), java.lang.management.ManagementFactory.getPlatformMBeanServer());
    server.start();
  }
  public int port() { return port; }
  @Override public void close() throws Exception { server.stop(); UnicastRemoteObject.unexportObject(registry, true); }
}
