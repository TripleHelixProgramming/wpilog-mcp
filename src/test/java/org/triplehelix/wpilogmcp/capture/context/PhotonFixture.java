/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.triplehelix.wpilogmcp.nt4.MessagePack;

/** Synthetic pinned shape on one HTTP/WebSocket port, with a minimal independent RFC 6455 peer. */
public final class PhotonFixture implements AutoCloseable {
  private final ServerSocket listener = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
  private final List<Socket> sockets = new CopyOnWriteArrayList<>(), websockets = new CopyOnWriteArrayList<>();
  public final AtomicInteger exports = new AtomicInteger(), connections = new AtomicInteger(), pings = new AtomicInteger();
  public volatile JsonObject document = document(10);
  public volatile int exportStatus = 200;
  public volatile boolean answerPing = true;
  public volatile boolean holdRefreshUntilOldSocketCloses;
  private final java.util.concurrent.CountDownLatch firstSocketClosed = new java.util.concurrent.CountDownLatch(1);
  public volatile byte[] export = archive();
  public PhotonFixture() throws Exception {
    daemon(() -> { while (!listener.isClosed()) try {
      var socket = listener.accept(); sockets.add(socket); daemon(() -> serve(socket));
    } catch (Exception e) { if (!listener.isClosed()) throw new AssertionError(e); } });
  }
  public URI address() { return URI.create("http://127.0.0.1:" + listener.getLocalPort()); }
  public static JsonObject document(int exposure) {
    var root = JsonParser.parseString("""
        {"settings":{"general":{"version":"v2026.3.4","hardwareModel":"synthetic coprocessor",
          "hardwarePlatform":"synthetic Linux","wpilibArch":"linuxarm64","gpuAcceleration":"synthetic",
          "mrCalWorking":true},"atfl":{"tags":[],"field":{"length":16,"width":8}}},"cameraSettings":[]}
        """).getAsJsonObject();
    for (int i = 0; i < 2; i++) {
      var camera = JsonParser.parseString("""
          {"nickname":"front","uniqueName":"synthetic-0","currentPipelineIndex":0,
           "currentPipelineSettings":{"pipelineType":5,"pipelineNickname":"tags","cameraVideoModeIndex":0,
             "cameraExposureRaw":10,"cameraAutoExposure":false,"cameraGain":4,"solvePNPEnabled":true,"doMultiTarget":true},
           "videoFormatList":{"0":{"width":640,"height":480,"fps":30,"pixelFormat":"Gray"}},
           "calibrations":[{"resolution":{"width":640,"height":480},"cameraIntrinsics":{"rows":3,"cols":3,"type":6,"data":[1,0,2,0,1,2,0,0,1]},
             "distCoeffs":{"rows":1,"cols":5,"type":6,"data":[0,0,0,0,0]},"lensmodel":"LENSMODEL_OPENCV","numSnapshots":2,"meanErrors":[0.25,0.5]}],
           "isConnected":true,"hasConnected":true,"mismatch":false,"deactivated":false}
          """).getAsJsonObject();
      camera.addProperty("nickname", i == 0 ? "front" : "rear"); camera.addProperty("uniqueName", "synthetic-" + i);
      camera.getAsJsonObject("currentPipelineSettings").addProperty("cameraExposureRaw", exposure + i);
      root.getAsJsonArray("cameraSettings").add(camera);
    }
    return root;
  }
  public static byte[] archive() throws Exception {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("photon.sqlite")); zip.write("SQLite format 3\0synthetic".getBytes(StandardCharsets.US_ASCII)); zip.closeEntry();
    }
    return out.toByteArray();
  }
  public void change(int exposure) throws Exception {
    document = document(exposure);
    // v2026.3.4 selective notifications omit the camera ID: the provider must ask for full state.
    var update = new JsonObject(); var delta = new JsonObject(); delta.addProperty("cameraExposureRaw", exposure);
    update.add("mutatePipelineSettings", delta); push(update);
  }
  public void push(JsonObject value) throws Exception {
    byte[] data = MessagePack.encode(new Gson().fromJson(value, Object.class));
    for (var socket : websockets) if (!socket.isClosed()) frame(socket, 2, data);
  }
  private void serve(Socket socket) {
    try (socket) {
      var in = new DataInputStream(socket.getInputStream()); var headers = new ByteArrayOutputStream();
      int suffix = 0;
      while (suffix != 0x0d0a0d0a) {
        int b = in.read(); if (b < 0) return; headers.write(b); suffix = suffix << 8 | b;
        if (headers.size() > 8192) throw new java.io.IOException("Excessive fixture headers");
      }
      String request = headers.toString(StandardCharsets.US_ASCII); var out = socket.getOutputStream();
      if (request.startsWith("GET /api/settings/photonvision_config.zip ")) {
        exports.incrementAndGet(); byte[] body = export;
        out.write(("HTTP/1.1 " + exportStatus + " Fixture\r\nContent-Type: application/zip\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body); out.flush(); return;
      }
      if (!request.startsWith("GET /websocket_data ")) throw new java.io.IOException("Unexpected route");
      // PhotonVision broadcasts a full configuration to all sockets when a user connects.
      // Holding this handshake pins the client-side boundary: no old receiver may survive
      // until the replacement begins delivering that broadcast.
      if (holdRefreshUntilOldSocketCloses && connections.get() > 0
          && !firstSocketClosed.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
        throw new java.io.IOException("Old settings socket was not closed before refresh");
      }
      String key = request.lines().filter(s -> s.toLowerCase().startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":", 2)[1].trim();
      String accept = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
          .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
      out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
      websockets.add(socket); connections.incrementAndGet();
      frame(socket, 2, MessagePack.encode(new Gson().fromJson(document, Object.class)));
      while (true) {
        int opcode = in.readUnsignedByte() & 15; int flags = in.readUnsignedByte(); int n = flags & 127;
        if (n == 126) n = in.readUnsignedShort(); else if (n == 127) n = Math.toIntExact(in.readLong());
        if (n > 65536) throw new java.io.IOException("Unexpected fixture client payload");
        byte[] mask = (flags & 128) != 0 ? in.readNBytes(4) : null; byte[] data = in.readNBytes(n);
        if (data.length != n) return;
        if (mask != null) for (int i = 0; i < n; i++) data[i] ^= mask[i % 4];
        if (opcode == 8) return;
        if (opcode == 9) { if (answerPing) frame(socket, 10, data); pings.incrementAndGet(); }
        else if (opcode != 10) throw new AssertionError("Provider sent a data/write message");
      }
    } catch (java.io.IOException ignored) { /* Peer closed/replaced or fixture vanished. */ }
    catch (Exception e) { throw new AssertionError(e); }
    finally { if (websockets.remove(socket)) firstSocketClosed.countDown(); }
  }
  private static void frame(Socket socket, int opcode, byte[] data) throws Exception {
    synchronized (socket) {
      var out = new DataOutputStream(socket.getOutputStream()); out.writeByte(128 | opcode);
      if (data.length < 126) out.writeByte(data.length); else { out.writeByte(126); out.writeShort(data.length); }
      out.write(data); out.flush();
    }
  }
  private static void daemon(Runnable action) { var thread = new Thread(action, "photon-fixture"); thread.setDaemon(true); thread.start(); }
  @Override public void close() throws Exception { listener.close(); for (var socket : sockets) socket.close(); }
}
