/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.store.StoreJson;

/** Real child JVMs, a temporary daemon record, and a cross-process store lock. */
class MainImportTest {
  @TempDir Path temp;
  record Ran(int code, String output) {}

  private ProcessBuilder process(List<String> args, Path output) throws Exception {
    var command = new ArrayList<String>();
    command.add(ProcessHandle.current().info().command().orElse("java"));
    command.add("-Duser.home=" + temp.toRealPath());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(Main.class.getName());
    command.addAll(args);
    var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
    for (var name : List.of("WPILOG_DIR", "TBA_API_KEY", "WPILOG_HTTP_BIND", "WPILOG_HTTP_PATH", "WPILOG_DEBUG")) {
      builder.environment().remove(name);
    }
    return builder;
  }

  private Ran run(Path config, String... args) throws Exception {
    var command = new ArrayList<>(List.of("import", "--config", config.toString()));
    command.addAll(List.of(args));
    var output = Files.createTempFile(temp, "command", ".txt");
    var child = process(command, output).start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Import command hung: " + Files.readString(output));
      return new Ran(child.exitValue(), Files.readString(output));
    } finally { child.destroyForcibly(); }
  }

  private Path config(String name, int port, Path... dirs) throws Exception {
    var config = Files.createTempFile(temp, "servers", ".yaml");
    Files.writeString(config, "servers:\n  " + name + ":\n    transport: http\n    port: " + port
        + "\n    logdir: " + StoreJson.JSON.toJson(List.of(dirs)) + "\n");
    return config;
  }

  private JsonObject result(Ran ran) {
    assertEquals(0, ran.code(), ran.output());
    return ran.output().lines().filter(line -> line.startsWith("result "))
        .map(line -> JsonParser.parseString(line.substring(7)).getAsJsonObject())
        .reduce((a, b) -> b).orElseThrow(() -> new AssertionError(ran.output()));
  }

  private Path destination(JsonObject result) {
    var file = result.getAsJsonArray("files").get(0).getAsJsonObject();
    assertEquals("imported", file.get("status").getAsString(), result.toString());
    return Path.of(file.get("path").getAsString());
  }

  @Test void offlineDefaultCreatesStoreAndUsesAnExistingConfiguredStoreThenAnOverride() throws Exception {
    temp = temp.toRealPath();
    var first = Files.createDirectory(temp.resolve("first"));
    var second = Files.createDirectory(temp.resolve("second"));
    var config = config("default", 2363, first, second);
    var one = ImportFixture.write(second.resolve("one.wpilog"), 1);
    var firstImport = run(config, "--robot", "practice", one.toString());
    assertTrue(firstImport.output().contains("No running daemon"), firstImport.output());
    assertTrue(firstImport.output().contains("store.lock"));
    assertTrue(destination(result(firstImport)).startsWith(first));
    assertTrue(Files.exists(one), "Default import copies");
    assertTrue(Files.isDirectory(first.resolve("inbox")));
    var reordered = config("default", 2363, second, first);
    var two = ImportFixture.write(second.resolve("two.wpilog"), 2);
    var moved = run(reordered, "--move", "--robot", "practice", two.toString());
    assertTrue(destination(result(moved)).startsWith(first), "Prefer an existing store over the first plain directory");
    assertFalse(Files.exists(two));
    var override = second.resolve("chosen");
    var three = ImportFixture.write(second.resolve("three.wpilog"), 3);
    var selected = run(reordered, "--store", override.toString(), "--robot", "other", three.toString());
    assertTrue(destination(result(selected)).startsWith(override));
    var refused = run(reordered, "--store", temp.resolve("outside").toString(), three.toString());
    assertEquals(1, refused.code());
    assertTrue(refused.output().contains("outside configured log directories"), refused.output());
    assertFalse(Files.exists(temp.resolve("outside")));
  }

  @Test void offlineOutsideTransferMovesThroughInboxWithAReceipt() throws Exception {
    temp = temp.toRealPath();
    var root = Files.createDirectory(temp.resolve("store"));
    var outside = ImportFixture.write(temp.resolve("usb").resolve("outside.wpilog"), 4);
    byte[] bytes = Files.readAllBytes(outside);
    var imported = run(config("default", 2363, root), "--move", "--robot", "practice", outside.getParent().toString());
    assertTrue(imported.output().contains("Moved to inbox:"), imported.output());
    assertArrayEquals(bytes, Files.readAllBytes(destination(result(imported))));
    assertFalse(Files.exists(outside));
    var receipt = Files.readAllLines(root.resolve("inbox").resolve("imported.log"));
    assertEquals(1, receipt.size());
    assertEquals("imported", JsonParser.parseString(receipt.get(0)).getAsJsonObject().get("status").getAsString());
  }

  @Test void heldFileChannelLockRefusesTheOfflineProcessAndReleasesForNextImport() throws Exception {
    temp = temp.toRealPath();
    var root = Files.createDirectory(temp.resolve("store"));
    var input = ImportFixture.write(root.resolve("one.wpilog"), 5);
    var config = config("default", 2363, root);
    try (var channel = FileChannel.open(root.resolve("store.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
         var lock = channel.lock()) {
      var refused = run(config, "--move", "--robot", "practice", input.toString());
      assertEquals(1, refused.code(), refused.output());
      assertTrue(refused.output().contains("Store lock is held"), refused.output());
      assertTrue(Files.exists(input));
      assertFalse(Files.exists(root.resolve("store.json")));
    }
    destination(result(run(config, "--robot", "practice", input.toString())));
  }

  @Test void runningNamedDaemonReceivesJobAndOutsideCopiesOrMovesUseItsInbox() throws Exception {
    temp = temp.toRealPath();
    int port;
    try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    var root = Files.createDirectory(temp.resolve("store"));
    var config = config("pit", port, root);
    var output = temp.resolve("daemon.txt");
    var child = process(List.of("--internal-daemon", "pit", "--config", config.toString()), output).start();
    var client = HttpClient.newHttpClient();
    var base = URI.create("http://127.0.0.1:" + port);
    try {
      long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      boolean ready = false;
      while (child.isAlive() && System.nanoTime() < deadline) {
        try {
          var response = client.send(HttpRequest.newBuilder(base.resolve("/health"))
              .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString());
          ready = response.statusCode() == 200;
          if (ready) break;
        } catch (java.io.IOException ignored) { }
        Thread.sleep(30);
      }
      assertTrue(ready, Files.readString(output));
      var runDir = Files.createDirectories(temp.resolve(".wpilog-mcp").resolve("run"));
      Files.writeString(runDir.resolve("pit.pid"), child.pid() + "\n" + port + "\n");
      var inside = ImportFixture.write(root.resolve("inside.wpilog"), 6);
      var imported = run(config, "--server", "pit", "--robot", "practice", inside.toString());
      assertTrue(imported.output().contains("Import through daemon on port " + port), imported.output());
      assertTrue(Files.exists(destination(result(imported))));
      var job = imported.output().lines().filter(line -> line.startsWith("job "))
          .map(line -> JsonParser.parseString(line.substring(4)).getAsJsonObject()).findFirst().orElseThrow();
      var polled = client.send(HttpRequest.newBuilder(base.resolve(job.get("url").getAsString())).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals("done", JsonParser.parseString(polled.body()).getAsJsonObject().get("state").getAsString());
      assertTrue(Files.readString(output).contains("Import job " + job.get("job_id").getAsString()));
      for (boolean move : List.of(false, true)) {
        var outside = ImportFixture.write(temp.resolve("usb").resolve("outside-" + move + ".wpilog"), move ? 8 : 7);
        byte[] bytes = Files.readAllBytes(outside);
        var args = new ArrayList<>(List.of("--server", "pit", "--robot", "inbox_robot", outside.toString()));
        if (move) args.add("--move");
        var staged = run(config, args.toArray(String[]::new));
        assertEquals(0, staged.code(), staged.output());
        assertTrue(staged.output().contains(move ? "Moved to inbox:" : "Copied to inbox:"), staged.output());
        assertFalse(staged.output().contains("--robot applies to direct imports only"));
        assertEquals(!move, Files.exists(outside));
        var receiptPath = root.resolve("inbox").resolve("imported.log");
        int count = move ? 2 : 1;
        deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while ((!Files.exists(receiptPath) || Files.readAllLines(receiptPath).size() < count)
            && System.nanoTime() < deadline) Thread.sleep(30);
        var receipt = JsonParser.parseString(Files.readAllLines(receiptPath).get(count - 1)).getAsJsonObject();
        assertEquals("imported", receipt.get("status").getAsString());
        assertTrue(Path.of(receipt.get("path").getAsString()).startsWith(root.resolve("robots").resolve("inbox_robot")));
        assertArrayEquals(bytes, Files.readAllBytes(Path.of(receipt.get("path").getAsString())));
        assertFalse(Files.exists(Path.of(receipt.get("original_path").getAsString())));
      }
    } finally {
      child.destroy();
      if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly();
    }
  }

  @Test void aUsbDirectoryGroupsTwoRobotsAndTwoBootsAndRecognizesDuplicates() throws Exception {
    temp = temp.toRealPath();
    var root = Files.createDirectory(temp.resolve("store"));
    var usb = Files.createDirectory(temp.resolve("usb"));
    var originals = new java.util.LinkedHashMap<Path, byte[]>();
    for (int robot = 0; robot < 2; robot++) for (int boot = 0; boot < 2; boot++) for (int log = 0; log < 2; log++) {
      var file = ImportFixture.write(usb.resolve(robot + "-" + boot + "-" + log + ".wpilog"),
          1 + robot * 4 + boot * 2 + log, "SYNTHETIC-USB-" + robot,
          1_767_225_600_000_000L + boot * 3_600_000_000L);
      originals.put(file, Files.readAllBytes(file));
    }
    Files.copy(originals.keySet().iterator().next(), usb.resolve("duplicate.wpilog"));
    var configuration = config("default", 2363, root);
    var first = result(run(configuration, usb.toString()));
    assertEquals(8, first.getAsJsonArray("files").asList().stream()
        .filter(f -> f.getAsJsonObject().get("status").getAsString().equals("imported")).count(), first.toString());
    var security = new org.triplehelix.wpilogmcp.log.subsystems.SecurityValidator();
    security.addAllowedDirectory(root);
    var catalog = org.triplehelix.wpilogmcp.store.StoreCatalog.readManaged(root, security);
    assertEquals(8, catalog.files().size()); assertEquals(4, catalog.sessions().size());
    assertEquals(2, catalog.files().stream().map(f -> f.robot().serialNumber()).distinct().count());
    for (var session : catalog.sessions()) assertEquals(2, session.session().files().size());
    for (var original : originals.entrySet()) assertArrayEquals(original.getValue(), Files.readAllBytes(original.getKey()));
    var again = result(run(configuration, usb.toString()));
    assertTrue(again.getAsJsonArray("files").asList().stream().allMatch(f ->
        f.getAsJsonObject().get("status").getAsString().equals("present")), again.toString());
    assertEquals(8, org.triplehelix.wpilogmcp.store.StoreCatalog.readManaged(root, security).files().size());
  }

  @Test void usageAndMissingConfigurationHaveExitCodes() throws Exception {
    assertAll(List.of(new String[] {"import"}, new String[] {"import", "--move"},
        new String[] {"import", "--robot"}, new String[] {"import", "--store"},
        new String[] {"import", "--server"}, new String[] {"import", "--bogus", "x"})
        .stream().map(args -> (org.junit.jupiter.api.function.Executable) () -> assertEquals(2, Main.runImport(args))));
    var missing = run(temp.resolve("missing.yaml"), temp.resolve("file.wpilog").toString());
    assertEquals(1, missing.code());
  }
}
