/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.harness;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;
import org.triplehelix.wpilogmcp.capture.context.PhotonSettings;
import org.triplehelix.wpilogmcp.nt4.MessagePack;

/** The unmodified release process owns its fresh configuration; only generated pixels enter it. */
public final class PhotonBackend implements AutoCloseable {
  public static final String CAMERA = "WPI2026";
  public static final String PROVIDER = "photonvision/127.0.0.1:5800";
  public static final String SETTINGS = "/Daemon/PhotonVision/" + CAMERA + "/Settings";
  private final Path work;
  private final Process process;
  private final HttpClient http = HttpClient.newHttpClient();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final Set<String> keys = new java.util.concurrent.ConcurrentSkipListSet<>();
  private volatile JsonObject snapshot;
  private volatile boolean closing;
  private WebSocket socket;
  private final long started = System.nanoTime();

  public PhotonBackend(Path run, Path jar) throws Exception {
    var release = JsonParser.parseString(Files.readString(Path.of("harness/photonvision/release.json"))).getAsJsonObject();
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(jar)) {
      byte[] block = new byte[1024 * 1024]; for (int n; (n = input.read(block)) >= 0;) digest.update(block, 0, n);
    }
    assertEquals(release.get("sha256").getAsString(), java.util.HexFormat.of().formatHex(digest.digest()), "Pinned release JAR");
    // This release fixes its web/NT ports. Only this opt-in process test uses them; fail
    // clearly rather than contacting an existing coprocessor/backend on a developer machine.
    for (int port : new int[]{5800, 5810}) try (var check = new java.net.ServerSocket()) {
      check.bind(new java.net.InetSocketAddress("127.0.0.1", port));
    } catch (java.io.IOException e) { throw new java.io.IOException("PhotonVision harness needs unused loopback port " + port, e); }
    work = Files.createDirectory(run.resolve("photonvision"));
    var image = work.resolve("test-resources/testimages/2026/BlueOutpostFuelSpread.jpg");
    Files.createDirectories(image.getParent());
    // The pinned test-mode camera uses this filename. It is a new blank image, not WPI's image.
    var pixels = new java.awt.image.BufferedImage(640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var graphics = pixels.createGraphics(); graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 640, 480); graphics.dispose();
    assertTrue(javax.imageio.ImageIO.write(pixels, "jpg", image.toFile()));
    var home = Files.createDirectory(work.resolve("home"));
    process = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-Xmx512m", "-Djava.awt.headless=true",
        "-Duser.home=" + home, "-jar", jar.toString(), "--test-mode", "--disable-networking")
        .directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("backend.log").toFile()).start();
    try { start(); }
    catch (Throwable e) { close(); throw e; }
  }
  private void start() throws Exception {
    HarnessHttp.await("pinned PhotonVision export", 30, () -> {
      check(); var reply = get(PhotonSettings.EXPORT_PATH);
      if (reply.statusCode() != 200) return false;
      boolean database = false;
      try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(reply.body()))) {
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          if (entry.getName().equals("photon.sqlite")) {
            assertArrayEquals("SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII), zip.readNBytes(16));
            database = true;
          }
        }
      }
      assertTrue(database, "Actual export must hold photon.sqlite");
      Files.write(work.resolve("export.zip"), reply.body()); return true;
    });
    // The release's NT client uses the default port. Only this fresh backend is configured.
    int before = Files.readString(work.resolve("backend.log")).length();
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:5800/api/settings/general"))
        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(networkConfiguration())).build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    // The pinned route always restarts Javalin, even with host network management disabled.
    // Opening the audit first turns that expected setup restart into a spurious socket failure.
    HarnessHttp.await("PhotonVision settings restart", 30, () -> {
      check(); return restarted(Files.readString(work.resolve("backend.log")), before);
    });
    socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
        .buildAsync(URI.create("ws://127.0.0.1:5800/websocket_data"), new Audit()).get(10, TimeUnit.SECONDS);
    HarnessHttp.await("file-camera full binary settings", 30, () -> {
      check(); var value = snapshot;
      return value != null && value.getAsJsonArray("cameraSettings").size() == 1;
    });
    assertEquals("v2026.3.4", snapshot.getAsJsonObject("settings").getAsJsonObject("general").get("version").getAsString());
    assertEquals(CAMERA, snapshot.getAsJsonArray("cameraSettings").get(0).getAsJsonObject().get("nickname").getAsString());
    Files.writeString(work.resolve("startup-seconds.txt"), Double.toString((System.nanoTime() - started) / 1e9));
  }
  static String networkConfiguration() {
    return """
        {"ntServerAddress":"127.0.0.1","connectionType":0,"staticIp":"","hostname":"photon-harness",
         "runNTServer":false,"shouldManage":false,"shouldPublishProto":false,"networkManagerIface":"",
         "setStaticCommand":"","setDHCPcommand":""}
        """;
  }
  static boolean restarted(String output, int before) {
    int stop = output.indexOf("Web server going down for restart", before);
    return stop >= 0 && output.indexOf("Listening on http://localhost:5800/", stop) >= 0;
  }
  private HttpResponse<byte[]> get(String route) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:5800" + route)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
  }
  public void check() throws Exception {
    if (!process.isAlive()) fail("PhotonVision stopped: " + Files.readString(work.resolve("backend.log")));
    var error = failure.get(); if (error != null) throw new AssertionError("Actual PhotonVision UI contract", error);
  }
  private final class Audit implements WebSocket.Listener {
    final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer bytes, boolean last) {
      try {
        assertTrue(pending.size() + bytes.remaining() <= 4 * 1024 * 1024, "Bounded backend message");
        byte[] part = new byte[bytes.remaining()]; bytes.get(part); pending.writeBytes(part);
        if (last) {
          var value = new Gson().toJsonTree(MessagePack.decode(pending.toByteArray())).getAsJsonObject(); pending.reset();
          keys.addAll(value.keySet()); PhotonSettings.validateMessageKeys(value);
          if (value.has("settings")) {
            PhotonSettings.snapshot(value); snapshot = value;
            Files.writeString(work.resolve("snapshot.json"), value.toString());
          }
        }
      } catch (Throwable e) { failure.compareAndSet(null, e); }
      ws.request(1); return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
      failure.compareAndSet(null, new AssertionError("Expected binary MessagePack, received text")); ws.request(1); return null;
    }
    @Override public void onError(WebSocket ws, Throwable error) { if (!closing) failure.compareAndSet(null, error); }
    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
      if (!closing) failure.compareAndSet(null, new AssertionError("UI audit socket closed: " + code + " " + reason)); return null;
    }
  }
  public void verifyLive(HarnessHttp mcp, int httpPort, Path evidence) throws Exception {
    var current = new AtomicReference<JsonObject>();
    HarnessHttp.await("PhotonVision following and captured settings", 30, () -> {
      check(); var live = mcp.call("list_sessions", new JsonObject());
      Files.writeString(evidence.resolve("photon-provider.json"), live.toString());
      for (var row : live.getAsJsonArray("sessions")) {
        var session = row.getAsJsonObject(); if (!session.get("connected").getAsBoolean()) continue;
        for (var p : session.getAsJsonArray("providers")) {
          var provider = p.getAsJsonObject(); if (!PROVIDER.equals(provider.get("name").getAsString())) continue;
          assertNotEquals("stand_down", provider.get("state").getAsString(), provider.toString());
          if (provider.get("state").getAsString().equals("following") && provider.get("records").getAsLong() > 0) {
            current.set(session); return true;
          }
        }
      }
      return false;
    });
    var session = current.get();
    var path = Path.of(session.get("path").getAsString());
    HarnessHttp.await("PhotonVision has-target topic and camera context", 30, () -> {
      check(); var args = new JsonObject(); args.addProperty("path", path.toString());
      args.addProperty("vision_prefix", "NT:/photonvision/" + CAMERA + "/");
      var result = mcp.call("analyze_vision", args); Files.writeString(evidence.resolve("photon-vision.json"), result.toString());
      if (!result.has("camera_settings")) return false;
      var settings = result.getAsJsonObject("camera_settings").getAsJsonObject(CAMERA); if (settings == null) return false;
      assertEquals("captured", settings.get("status").getAsString(), result.toString());
      for (var sample : settings.getAsJsonArray("snapshots")) {
        var data = sample.getAsJsonObject().getAsJsonObject("settings");
        assertEquals("v2026.3.4", data.get("software_version").getAsString());
        assertEquals("AprilTag", data.getAsJsonObject("pipeline").get("type").getAsString());
        assertEquals(640, data.getAsJsonObject("pipeline").getAsJsonObject("resolution").get("width").getAsInt());
        assertEquals(480, data.getAsJsonObject("pipeline").getAsJsonObject("resolution").get("height").getAsInt());
        assertEquals(1, data.getAsJsonArray("calibrations").size());
        assertEquals(0, data.getAsJsonArray("calibrations").get(0).getAsJsonObject().get("snapshot_count").getAsInt(), "Test-mode calibration has no measured snapshots");
      }
      return true;
    });
    var metrics = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    assertTrue(metrics.contains("wpilog_provider_state{provider=\"" + PROVIDER + "\",state=\"following\"} 1\n"), metrics);
    HarnessHttp.await("PhotonVision state in manifest", 30, () -> {
      var manifest = JsonParser.parseString(Files.readString(path.getParent().resolve("session.json"))).getAsJsonObject();
      return manifest.has("capture_stats") && manifest.getAsJsonObject("capture_stats").getAsJsonArray("providers").asList().stream()
          .map(v -> v.getAsJsonObject()).anyMatch(p -> PROVIDER.equals(p.get("name").getAsString()) && "following".equals(p.get("state").getAsString()));
    });
  }
  @Override public void close() throws Exception {
    closing = true; if (socket != null) socket.abort();
    process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
    Files.writeString(work.resolve("message-keys.json"), new Gson().toJson(keys));
  }
}
