/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read.http;

import org.triplehelix.wpilogmcp.read.internal.ReadWire;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplehelix.wpilogmcp.fixtures.WpilogWriter;
import org.triplehelix.wpilogmcp.read.LogAccess;
import org.triplehelix.wpilogmcp.read.ReadException;
import org.triplehelix.wpilogmcp.read.ReadProblem;
import org.triplehelix.wpilogmcp.read.ReadQuery;

class RemoteLogAccessTest {
  @TempDir Path temp;

  private final class Backend implements AutoCloseable {
    final HttpServer server;
    final Path path;
    final AtomicInteger deletes = new AtomicInteger();
    final CountDownLatch errorHeaders = new CountDownLatch(1), finishError = new CountDownLatch(1);
    final java.util.concurrent.ExecutorService workers;
    volatile boolean stalledError, failedDelete, pauseOpen, pauseQuery;
    volatile java.util.function.UnaryOperator<String> mutateDescriptor = java.util.function.UnaryOperator.identity();
    final CountDownLatch admitted = new CountDownLatch(1), reply = new CountDownLatch(1);
    Backend(String end) throws Exception {
      path = temp.resolve("fixture.wpilog");
      try (var writer = new WpilogWriter(path, "")) {
        int id = writer.start("/x", "int64", "", 0); writer.append(id, 1, WpilogWriter.encodeInt64(2));
      }
      String descriptor;
      try (var access = LogAccess.local(List.of(temp)); var snapshot = access.open(path)) {
        descriptor = ReadWire.JSON.toJson(ReadWire.descriptor("test-snapshot", snapshot));
      }
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      workers = java.util.concurrent.Executors.newCachedThreadPool(r -> { var t = new Thread(r, "remote-read-test"); t.setDaemon(true); return t; });
      server.setExecutor(workers);
      server.createContext("/read/v1/snapshots", exchange -> {
        if (exchange.getRequestMethod().equals("DELETE")) {
          deletes.incrementAndGet(); exchange.sendResponseHeaders(failedDelete ? 503 : 204, -1); exchange.close(); return;
        }
        boolean query = exchange.getRequestURI().getPath().endsWith("/query");
        if ((query && pauseQuery) || (!query && pauseOpen)) {
          admitted.countDown();
          try { reply.await(5, TimeUnit.SECONDS); }
          catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }
        if (stalledError && query) {
          exchange.sendResponseHeaders(500, 0); exchange.getResponseBody().flush(); errorHeaders.countDown();
          try { finishError.await(5, TimeUnit.SECONDS); }
          catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
          finally { exchange.close(); }
          return;
        }
        String body = exchange.getRequestURI().getPath().endsWith("/query")
            ? "{\"kind\":\"batch\",\"samples\":[{\"timestamp_us\":\"1\",\"record_offset\":\"32\",\"ordinal\":0,\"value\":{\"type\":\"i64\",\"value\":\"2\"}}],"
                + "\"problems\":[],\"records_visited\":1,\"payload_bytes\":\"8\",\"complete\":true}\n" + end
            : mutateDescriptor.apply(descriptor);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", exchange.getRequestURI().getPath().endsWith("/query") ? "application/x-ndjson" : "application/json");
        exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/query") ? 200 : 201, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
      });
      server.start();
    }
    URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    @Override public void close() { reply.countDown(); finishError.countDown(); server.stop(0); workers.shutdownNow(); }
  }

  @Test void anOverloadedServerRetainsItsRetryableStatusEvenWithAPlainTextBody() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/read/v1/catalog", exchange -> {
      byte[] body = "try later".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(503, body.length);
      try (var out = exchange.getResponseBody()) { out.write(body); }
    }); server.start();
    try (var remote = new RemoteLogAccess(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))) {
      var failure = assertThrows(ReadException.class, remote::catalog);
      assertEquals("BUSY", failure.code().name());
      assertTrue(failure.getMessage().contains("503"), failure.getMessage());
    } finally { server.stop(0); }
  }

  @Test void aCompleteBatchWithoutTheTerminalProofIsNeverReportedComplete() throws Exception {
    try (var backend = new Backend(""); var remote = new RemoteLogAccess(backend.origin());
         var snapshot = remote.open(backend.path); var cursor = snapshot.read(ReadQuery.all("/x"))) {
      var failure = assertThrows(IOException.class, cursor::next);
      assertTrue(failure.getMessage().contains("completion"));
    }
  }

  @Test void aChangedFileTerminalDiscardsTheLastBatchWithItsExplainedCode() throws Exception {
    try (var backend = new Backend("{\"kind\":\"end\",\"status\":\"error\",\"code\":\"FILE_CHANGED\",\"message\":\"Source changed; reopen it\"}\n");
         var remote = new RemoteLogAccess(backend.origin()); var snapshot = remote.open(backend.path);
         var cursor = snapshot.read(ReadQuery.all("/x"))) {
      var failure = assertThrows(ReadException.class, cursor::next);
      assertEquals(ReadProblem.Code.FILE_CHANGED, failure.code());
      assertEquals("Source changed; reopen it", failure.getMessage());
    }
  }

  @Test void originsCannotSmuggleCredentialsOrRedirectTheReadInterface() {
    assertThrows(IllegalArgumentException.class, () -> new RemoteLogAccess(URI.create("https://user:secret@example.com")));
    assertThrows(IllegalArgumentException.class, () -> new RemoteLogAccess(URI.create("https://example.com/another/path")));
    assertThrows(IllegalArgumentException.class, () -> new RemoteLogAccess(URI.create("file:///etc/passwd")));
  }

  @Test void stalledErrorBodiesHaveTheSameDeadlineAsSuccessfulResponses() throws Exception {
    try (var backend = new Backend(""); var remote = new RemoteLogAccess(backend.origin(), Duration.ofMillis(100));
         var snapshot = remote.open(backend.path)) {
      backend.stalledError = true;
      var failure = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try (var cursor = snapshot.read(ReadQuery.all("/x"))) { return null; }
        catch (IOException expected) { return expected; }
      });
      try {
        assertTrue(backend.errorHeaders.await(5, TimeUnit.SECONDS));
        assertNotNull(failure.get(2, TimeUnit.SECONDS), "Error headers must not disable the body deadline");
      } finally { backend.finishError.countDown(); }
    }
  }

  @Test void aFailedRemoteDeleteStillReleasesLocalOwnership() throws Exception {
    try (var backend = new Backend(""); var remote = new RemoteLogAccess(backend.origin())) {
      var snapshot = remote.open(backend.path); assertEquals(1, remote.retainedSnapshots());
      backend.failedDelete = true;
      assertThrows(IOException.class, snapshot::close);
      assertEquals(0, remote.retainedSnapshots(), "The peer's failure cannot retain the local snapshot or its deadline executor");
    }
  }
  @Test void closingAccessDuringOpenKeepsTheDeadlineOwnerUntilAdmissionFinishes() throws Exception {
    try (var backend = new Backend(""); var remote = new RemoteLogAccess(backend.origin())) {
      backend.pauseOpen = true;
      var opening = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try { return remote.open(backend.path); }
        catch (IOException expected) { throw new java.util.concurrent.CompletionException(expected); }
      });
      assertTrue(backend.admitted.await(5, TimeUnit.SECONDS));
      remote.close(); backend.reply.countDown();
      var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> opening.get(5, TimeUnit.SECONDS));
      assertInstanceOf(ReadException.class, failure.getCause());
      assertEquals(ReadProblem.Code.CLOSED, ((ReadException) failure.getCause()).code());
      assertEquals(1, backend.deletes.get(), "A remote lease created during close is still released");
      assertEquals(0, remote.retainedSnapshots());
    }
  }

  @Test void closingSnapshotDuringQueryDoesNotDeleteBeforeTheAdmissionReturns() throws Exception {
    try (var backend = new Backend(""); var remote = new RemoteLogAccess(backend.origin());
         var snapshot = remote.open(backend.path)) {
      backend.pauseQuery = true;
      var reading = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try { return snapshot.read(ReadQuery.all("/x")); }
        catch (IOException expected) { throw new java.util.concurrent.CompletionException(expected); }
      });
      try {
        assertTrue(backend.admitted.await(5, TimeUnit.SECONDS)); snapshot.close();
        assertEquals(0, backend.deletes.get(), "An admitted HTTP query retains its snapshot until its response arrives");
        backend.reply.countDown();
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> reading.get(5, TimeUnit.SECONDS));
        assertInstanceOf(ReadException.class, failure.getCause());
        assertEquals(ReadProblem.Code.CLOSED, ((ReadException) failure.getCause()).code());
        assertEquals(1, backend.deletes.get());
      } finally { backend.reply.countDown(); }
    }
  }

  @Test void malformedDescriptorFieldsCannotCoerceVersionOrEscapeCleanup() throws Exception {
    try (var backend = new Backend(""); var remote = new RemoteLogAccess(backend.origin())) {
      backend.mutateDescriptor = text -> text.replace("\"api_version\":1", "\"api_version\":4294967297");
      assertThrows(IOException.class, () -> remote.open(backend.path));
      backend.mutateDescriptor = text -> text.replace("\"id\":\"test-snapshot\"", "\"id\":{}");
      assertThrows(IOException.class, () -> remote.open(backend.path));
      assertEquals(0, remote.retainedSnapshots());
    }
  }

}
