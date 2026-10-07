/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package com.jcraft.jsch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The JSch boundary, with channel time advanced by the test instead of a socket or sleep. */
public final class SlowSsh extends JSch {
  public final byte[] hostKey = {1, 2, 3};
  public boolean authenticated, disconnected, commandClosed, endOnClose;
  public int connectTimeout, channelTimeout;
  public long elapsedMs;
  public Runnable onRead = () -> {};
  public String reply = "0".repeat(64) + "  -\n";
  public Session session;
  @Override public void addIdentity(String path) { /* No real credential is loaded. */ }
  @Override public Session getSession(String user, String host, int port) throws JSchException {
    session = new Session(this, user, host, port) {
      @Override public void connect(int timeout) throws JSchException {
        connectTimeout = timeout;
        if (getHostKeyRepository().check(host, hostKey) != HostKeyRepository.OK
            && "yes".equals(getConfig("StrictHostKeyChecking"))) throw new JSchException("changed host key");
        authenticated = true;
      }
      @Override public HostKey getHostKey() {
        try { return new HostKey(host, HostKey.ED25519, hostKey); }
        catch (JSchException e) { throw new AssertionError(e); }
      }
      @Override public void disconnect() { disconnected = true; }
      @Override public Channel openChannel(String kind) {
        if (kind.equals("sftp")) return new ChannelSftp() {
          @Override public void connect(int timeout) { channelTimeout = timeout; }
          @Override public void disconnect() {}
        };
        commandClosed = false;
        return new ChannelExec() {
          @Override public void connect(int timeout) { channelTimeout = timeout; }
          @Override public void disconnect() { commandClosed = true; }
          @Override public InputStream getInputStream() {
            return new java.io.FilterInputStream(new java.io.ByteArrayInputStream(SlowSsh.this.reply.getBytes(StandardCharsets.US_ASCII))) {
              boolean first = true;
              @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (first) {
                  first = false; onRead.run();
                  // JSch's idle socket limit disconnects after unanswered timeouts. A live
                  // OpenSSH peer answers keepalives even while a hash command has no stdout.
                  if (getTimeout() > 0 && getServerAliveInterval() == 0
                      && elapsedMs > (long) getTimeout() * (getServerAliveCountMax() + 1)) {
                    disconnected = true; throw new IOException("idle SSH socket timed out");
                  }
                }
                if (commandClosed) { if (endOnClose) return -1; throw new IOException("command channel closed"); }
                return super.read(bytes, offset, length);
              }
            };
          }
        };
      }
    };
    return session;
  }
}
