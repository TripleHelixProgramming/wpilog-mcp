/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;

/** Compile and run as an external consumer: the server/test classpath cannot hide dependencies. */
class ReadLibraryTest {
  @TempDir Path temp;

  @Test void artifactContainsNoServerBootstrapOrNativeRuntime() throws Exception {
    var path = Path.of(System.getProperty("read.testJar", "build/missing-reader-library.jar"));
    assertTrue(Files.isRegularFile(path), "build must produce the standalone read library");
    try (var jar = new JarFile(path.toFile())) {
      assertNull(jar.getManifest().getMainAttributes().getValue("Main-Class"));
      for (String forbidden : List.of("Main", "log/LogManager", "mcp/HttpTransport",
          "nt4/client/Nt4Client", "capture/CaptureService", "ssh/SharedSsh")) {
        assertNull(jar.getEntry("org/triplehelix/wpilogmcp/" + forbidden + ".class"), forbidden);
      }
      assertTrue(jar.stream().noneMatch(e -> e.getName().endsWith(".dll")
          || e.getName().endsWith(".dylib") || e.getName().endsWith(".so")));
      assertNotNull(jar.getEntry("org/triplehelix/wpilogmcp/read/LogAccess.class"));
      assertNotNull(jar.getEntry("org/triplehelix/wpilogmcp/read/catalog/StoreReadCatalog.class"));
      assertNotNull(jar.getEntry("org/triplehelix/wpilogmcp/read/http/RemoteLogAccess.class"));
    }
  }

  @Test void readerDependenciesArePrivateAndRedistributionIncludesWpilibNotice() throws Exception {
    try (var jar = new JarFile(System.getProperty("read.testJar"))) {
      for (String prefix : List.of("edu/wpi/", "com/google/gson/", "com/google/flatbuffers/", "org/apache/arrow/", "org/slf4j/", "com/github/benmanes/caffeine/")) {
        assertTrue(jar.stream().noneMatch(e -> e.getName().endsWith(".class") && e.getName().startsWith(prefix)), "Unrelocated dependency: " + prefix);
      }
      var notice = jar.getEntry("META-INF/LICENSE-WPILib"); assertNotNull(notice);
      try (var input = jar.getInputStream(notice)) {
        String text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("Redistribution")); assertTrue(text.contains("FIRST and other WPILib contributors"));
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
  void javaConsumerUsesOnlyTheReaderJarAndWritesNoHomeOrCache(int format) throws Exception {
    var jar = Path.of(System.getProperty("read.testJar", "build/missing-reader-library.jar")).toAbsolutePath();
    assertTrue(Files.isRegularFile(jar), "build must produce the standalone read library");
    var log = temp.resolve("input.wpilog");
    try (var writer = new WpilogWriter(log, "")) {
      int id = writer.start("count", "int64", "", 0);
      writer.append(id, 1_000_000, WpilogWriter.encodeInt64(2L));
      writer.append(id, 2_000_000, WpilogWriter.encodeInt64(4L));
      writer.append(id, 3_000_000, WpilogWriter.encodeInt64(6L));
      writer.finish(id, 3_000_000);
    }
    var store = storeFixture(format);
    var source = temp.resolve("Consumer.java");
    Files.writeString(source, """
        import java.nio.file.*;
        import java.util.*;
        import org.triplehelix.wpilogmcp.read.*;
        import org.triplehelix.wpilogmcp.read.catalog.*;
        import org.triplehelix.wpilogmcp.read.http.*;
        public class Consumer {
          static CatalogDescriptor catalog(RemoteLogAccess access) throws Exception { return access.catalog(); }
          static SnapshotDescriptor descriptor(RemoteLogAccess.RemoteSnapshot snapshot) { return snapshot.descriptor(); }
          public static void main(String[] args) throws Exception {
            Path file = Path.of(args[0]);
            try (var access = LogAccess.local(List.of(file.getParent()));
                 var snapshot = access.open(file);
                 var cursor = snapshot.read(ReadQuery.all("count"))) {
              long count = 0, sum = 0, time = 0;
              for (;;) {
                var batch = cursor.next();
                for (var sample : batch.samples()) {
                  count++; sum += (Long) sample.value(); time += sample.timestampUs();
                }
                if (batch.complete()) break;
              }
              System.out.println(count + ":" + sum + ":" + time);
            }
            try (var catalog = new StoreReadCatalog(List.of(file.getParent()))) {
              if (catalog.snapshot().problems().size() != 1) throw new AssertionError("missing store must be explained");
            }
            try (var catalog = new StoreReadCatalog(List.of(Path.of(args[1])))) {
              var inventory = catalog.snapshot();
              if (!inventory.problems().isEmpty()) throw new AssertionError(inventory.problems());
              var facts = inventory.stores().get(0);
              if (facts.header().formatVersion() != Integer.parseInt(args[2])) throw new AssertionError("store format changed");
              if (facts.header().formatVersion() == 2 && (facts.header().replicas().files().size() != 2
                  || facts.header().replicas().files().stream().anyMatch(f -> !f.verificationBasis().equals("legacy_unconfirmed"))))
                throw new AssertionError("legacy replica evidence changed");
              if (!facts.header().mirror() || facts.files().size() != 2 || inventory.sharedSystemLogs().size() != 1)
                throw new AssertionError("store facts or shared system receipts missing");
              Path wpilog = facts.files().stream().filter(f -> f.file().kind().equals("wpilog")).findFirst().orElseThrow().path();
              Path revlog = facts.files().stream().filter(f -> f.file().kind().equals("revlog")).findFirst().orElseThrow().path();
              var rev = catalog.openRev(revlog, wpilog);
              String signal = rev.signals().keySet().stream().filter(k -> k.endsWith("/AppliedOutput")).findFirst().orElseThrow();
              if (!rev.rawValues(signal).stream().map(v -> v.timestamp()).toList().equals(List.of(1.0, 3.0)))
                throw new AssertionError("raw REV clock changed");
              if (!rev.values(signal).stream().map(v -> v.timestamp()).toList().equals(List.of(1.25, 3.252)))
                throw new AssertionError("recorded offset/drift not preserved");
              System.out.println("catalog:2:rev:2:offset:250000");
            }
            // Reflection catches runtime type dependencies that javac did not need to resolve.
            for (Class<?> api : List.of(LogAccess.class, ReadSnapshot.class, StoreReadCatalog.class,
                org.triplehelix.wpilogmcp.read.rev.RecordedRevRead.class, RemoteLogAccess.class)) {
              api.getDeclaredMethods();
            }
          }
        }
        """);
    int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
        "-classpath", jar.toString(), "-d", temp.toString(), source.toString(),
        Path.of("examples/read-api/ReadLog.java").toAbsolutePath().toString());
    assertEquals(0, compiled, "the documented library must compile without server/test dependencies");
    var home = temp.resolve("home"); Files.createDirectory(home);
    var cache = temp.resolve("cache");
    var output = temp.resolve("consumer.out");
    var javaExe = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    var builder = new ProcessBuilder(javaExe.toString(), "-Duser.home=" + home,
        "-cp", jar + java.io.File.pathSeparator + temp, "Consumer", log.toString(), store.toString(), Integer.toString(format));
    builder.environment().put("WPILOG_DISK_CACHE_DIR", cache.toString());
    var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "reader consumer must exit without daemon threads");
      assertEquals(0, process.exitValue(), Files.readString(output));
      assertTrue(Files.readString(output).contains("3:12:6000000"), Files.readString(output));
      assertTrue(Files.readString(output).contains("catalog:2:rev:2:offset:250000"), Files.readString(output));
      try (var files = Files.list(home)) { assertEquals(0, files.count(), "no home configuration or installation"); }
      assertFalse(Files.exists(cache), "no process-wide disk cache");
    } finally { process.destroyForcibly(); }
    var example = new ProcessBuilder(javaExe.toString(), "-Duser.home=" + home,
        "-cp", jar + java.io.File.pathSeparator + temp, "ReadLog", temp.toString(),
        log.toString(), "count").redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertTrue(example.waitFor(30, TimeUnit.SECONDS));
      assertEquals(0, example.exitValue(), Files.readString(output));
      assertTrue(Files.readString(output).contains("samples=3 decode_problems=0"), Files.readString(output));
    } finally { example.destroyForcibly(); }
  }

  private Path storeFixture(int format) throws Exception {
    var root = Files.createDirectories(temp.resolve("store"));
    var robot = Files.createDirectories(root.resolve("robots/fixture-serial"));
    var session = Files.createDirectories(robot.resolve("sessions/2026-01-01/session-id"));
    var wpilog = session.resolve("capture.wpilog");
    Files.copy(temp.resolve("input.wpilog"), wpilog);
    var revlog = session.resolve("bus.revlog");
    try (var writer = new WpilogWriter(revlog, "")) {
      int id = writer.start("CAN/3/Periodic Status 0", "raw", "", 0);
      writer.append(id, 1_000_000, org.triplehelix.wpilogmcp.fixtures.FixtureLogs.sparkStatus0(0.25, 12, 2, 30, false));
      writer.append(id, 3_000_000, org.triplehelix.wpilogmcp.fixtures.FixtureLogs.sparkStatus0(0.5, 12, 2, 30, false));
      writer.finish(id, 3_000_001);
    }
    String when = "2026-01-01T00:00:00Z";
    var provenance = new org.triplehelix.wpilogmcp.store.StoreManifest.Provenance("usb", "/fixture", "capture.wpilog", when, false);
    var anchor = receipt(wpilog, "wpilog", provenance, null);
    var companion = receipt(revlog, "revlog", provenance,
        new org.triplehelix.wpilogmcp.store.StoreManifest.Matching("by_correlation", anchor.sha256(), 250_000, 0.9, 1_000_000, 1, "data"));
    var header = new org.triplehelix.wpilogmcp.store.StoreManifest.Header(1, when, "mirror-id", List.of())
        .withOrigin(new org.triplehelix.wpilogmcp.store.StoreManifest.MirrorOrigin("origin-id", "http://fixture.invalid", when, List.of(), java.util.Map.of()));
    if (format == 2) {
      var entries = List.of(anchor, companion).stream().map(file -> new org.triplehelix.wpilogmcp.store.ReplicaCatalog.Entry(
          java.util.UUID.randomUUID().toString(), null, root.relativize(session.resolve(file.path())).toString().replace('\\', '/'),
          file.sha256(), file.sizeBytes(), file.kind(), "session-id", "completed_import", "legacy_unconfirmed", true, null)).toList();
      header = header.withReplicas(new org.triplehelix.wpilogmcp.store.ReplicaCatalog(1, java.util.UUID.randomUUID().toString(), 1,
          entries, List.of(), List.of()));
    }
    var identity = new org.triplehelix.wpilogmcp.store.StoreManifest.Robot("fixture-serial", "fixture-serial", "Fixture", "", "logged");
    var manifest = new org.triplehelix.wpilogmcp.store.StoreManifest.Session("session-id", when, when, "log", null, null, null, null,
        List.of(anchor, companion));
    var system = Files.createDirectories(robot.resolve("system"));
    var text = system.resolve("messages.txt"); Files.writeString(text, "synthetic system line\n");
    var shared = new org.triplehelix.wpilogmcp.store.SystemLogState.File(org.triplehelix.wpilogmcp.store.SystemLogState.Location.STORE,
        "robots/fixture-serial/system/messages.txt", "syslog", "text", hash(text), Files.size(text), provenance, null);
    var json = org.triplehelix.wpilogmcp.store.StoreJson.JSON;
    Files.writeString(root.resolve("store.json"), json.toJson(header));
    Files.writeString(robot.resolve("robot.json"), json.toJson(identity));
    Files.writeString(session.resolve("session.json"), json.toJson(manifest));
    Files.writeString(system.resolve("index.json"), json.toJson(new org.triplehelix.wpilogmcp.store.SystemLogIndex(List.of(
        new org.triplehelix.wpilogmcp.store.SystemLogIndex.Entry(shared, null)))));
    Files.writeString(robot.resolve("pull.json"), json.toJson(org.triplehelix.wpilogmcp.sync.PullManifest.empty("fixture-serial")));
    return root;
  }

  private static org.triplehelix.wpilogmcp.store.StoreManifest.LogFile receipt(Path path, String kind,
      org.triplehelix.wpilogmcp.store.StoreManifest.Provenance provenance,
      org.triplehelix.wpilogmcp.store.StoreManifest.Matching matching) throws Exception {
    return new org.triplehelix.wpilogmcp.store.StoreManifest.LogFile(path.getFileName().toString(), hash(path), Files.size(path), kind,
        provenance, true, 1, 3, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "log", false, matching);
  }
  private static String hash(Path path) throws Exception {
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

}
