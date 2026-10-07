/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.global.KeepAliveHandler;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/**
 * A synthetic device behind real SSH framing. Its virtual root is a temporary directory; the
 * robot writes directly into logs(). Exec recognizes one read-only grammar and never invokes a
 * shell. This checks our transport, not the account permissions or binaries of an NI image.
 */
public final class FakeRoboRio implements AutoCloseable {
  private static final Pattern HASH = Pattern.compile("head -c ([0-9]+) -- ('(?:[^']|'\"'\"')*') \\| sha256sum");
  public record Read(long wallTimeUs, String path, long offset, int bytes) {}
  public record Hash(String path, long length) {}
  @FunctionalInterface public interface BeforeHash { void run(Hash request) throws Exception; }
  private final SshServer server = SshServer.setUpDefaultServer();
  private final Path root;
  private final List<Read> reads = new CopyOnWriteArrayList<>();
  public final AtomicInteger authentications = new AtomicInteger();
  public final AtomicInteger keepalives = new AtomicInteger();
  public final AtomicInteger commands = new AtomicInteger();
  public final AtomicInteger refusedCommands = new AtomicInteger();
  public volatile BeforeHash beforeHash = request -> {};
  public volatile Consumer<Integer> onKeepalive = count -> {};

  public FakeRoboRio(Path root, String serial, String comments) throws Exception {
    this.root = Files.createDirectories(root).toRealPath();
    Files.createDirectories(logs());
    Files.createDirectories(this.root.resolve("proc/42"));
    Files.writeString(this.root.resolve("proc/42/environ"), "PATH=/usr/bin\0serialnum=" + serial + "\0");
    Files.createDirectories(this.root.resolve("etc"));
    Files.writeString(this.root.resolve("etc/machine-info"), "PRETTY_HOSTNAME=\""
        + comments.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
    server.setHost("127.0.0.1"); server.setPort(0);
    var key = SecurityUtils.getKeyPairGenerator("EdDSA").generateKeyPair();
    server.setKeyPairProvider(KeyPairProvider.wrap(key));
    server.setUserAuthFactories(List.of(org.apache.sshd.server.auth.password.UserAuthPasswordFactory.INSTANCE));
    server.setPasswordAuthenticator((user, password, session) -> {
      authentications.incrementAndGet(); return user.equals("lvuser") && password.isEmpty();
    });
    server.setFileSystemFactory(new VirtualFileSystemFactory(this.root));
    var sftp = new SftpSubsystemFactory.Builder().build();
    sftp.addSftpEventListener(new SftpEventListener() {
      @Override public void read(ServerSession session, String handle, FileHandle file,
          long offset, byte[] data, int dataOffset, int dataLen, int readLen, Throwable thrown) {
        if (readLen > 0) reads.add(new Read(System.currentTimeMillis() * 1000,
            file.getFile().toString().replace('\\', '/'), offset, readLen));
      }
    });
    server.setSubsystemFactories(List.of(sftp));
    server.setCommandFactory((channel, command) -> new HashCommand(command));
    var handlers = new java.util.ArrayList<>(server.getGlobalRequestHandlers());
    handlers.add(0, (connection, request, reply, buffer) -> {
      if (request.startsWith("keepalive@")) onKeepalive.accept(keepalives.incrementAndGet());
      return KeepAliveHandler.INSTANCE.process(connection, request, reply, buffer);
    });
    server.setGlobalRequestHandlers(handlers);
    server.start();
  }

  public int port() { return server.getPort(); }
  public Path logs() { return root.resolve("home/lvuser/logs"); }
  public List<Read> reads() { return List.copyOf(reads); }

  /** Parse the exact shell quoting emitted by the puller, including embedded apostrophes. */
  public static Hash parseHash(String command) throws IOException {
    var match = HASH.matcher(command);
    if (!match.matches()) throw new IOException("Only the puller's prefix hash command is allowed");
    try {
      String quoted = match.group(2);
      String path = quoted.substring(1, quoted.length() - 1).replace("'\"'\"'", "'");
      if (!path.startsWith("/") || path.indexOf('\0') >= 0) throw new IOException("Expected an absolute path");
      return new Hash(path, Long.parseLong(match.group(1)));
    } catch (NumberFormatException e) { throw new IOException("Invalid hash length", e); }
  }

  private String hash(Hash request) throws Exception {
    var path = root.resolve(request.path().substring(1)).normalize();
    if (!path.startsWith(root) || !path.toRealPath().startsWith(root)) throw new IOException("Outside synthetic root");
    var digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      long left = request.length(); byte[] bytes = new byte[65536];
      while (left > 0) {
        int count = input.read(bytes, 0, (int) Math.min(left, bytes.length));
        if (count < 0) throw new IOException("Prefix exceeds file length");
        digest.update(bytes, 0, count); left -= count;
      }
    }
    return HexFormat.of().formatHex(digest.digest()) + "  -\n";
  }

  private final class HashCommand implements Command {
    private final String text;
    private OutputStream out, err;
    private ExitCallback exit;
    private Thread worker;
    HashCommand(String text) { this.text = text; }
    @Override public void setInputStream(InputStream in) {}
    @Override public void setOutputStream(OutputStream out) { this.out = out; }
    @Override public void setErrorStream(OutputStream err) { this.err = err; }
    @Override public void setExitCallback(ExitCallback exit) { this.exit = exit; }
    @Override public void start(ChannelSession channel, Environment environment) {
      worker = new Thread(() -> {
        int status = 0;
        try {
          var request = parseHash(text); commands.incrementAndGet(); beforeHash.run(request);
          out.write(hash(request).getBytes(StandardCharsets.US_ASCII)); out.flush();
        } catch (Exception e) {
          status = 1; refusedCommands.incrementAndGet();
          try { err.write((e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8)); err.flush(); }
          catch (IOException ignored) { /* The client's deadline may already have closed it. */ }
        } finally { exit.onExit(status); }
      }, "synthetic-rio-hash");
      worker.setDaemon(true); worker.start();
    }
    @Override public void destroy(ChannelSession channel) { if (worker != null) worker.interrupt(); }
  }

  @Override public void close() throws IOException { server.stop(true); }
}
