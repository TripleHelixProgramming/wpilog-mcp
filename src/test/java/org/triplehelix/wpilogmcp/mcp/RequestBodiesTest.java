/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RequestBodiesTest {
  @Test void acceptedBodiesKeepTheirBytesAndOversizedBodiesKeepOnlyTheDetectionPrefix() throws Exception {
    for (int size : new int[] {0, 64, 65, 65536}) {
      byte[] bytes = new byte[size]; Arrays.fill(bytes, (byte) 'x');
      var input = new ByteArrayInputStream(bytes);
      assertArrayEquals(Arrays.copyOf(bytes, Math.min(size, 65)), RequestBodies.read(input, 64));
      assertEquals(0, input.available(), "The whole bounded excess must be consumed before refusing");
    }
  }
  @Test void anUnendingBodyCannotMakeTheDiscardExceedFourMiB() throws Exception {
    class Endless extends InputStream {
      long consumed;
      @Override public int read() { consumed++; return 'x'; }
      @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        consumed += length;
        if (consumed > 65 + 4 * 1024 * 1024L) throw new IOException("Read beyond the discard budget");
        return length;
      }
    }
    var input = new Endless();
    var failure = assertThrows(IOException.class, () -> RequestBodies.read(input, 64));
    assertEquals("Request refusal discard limit reached", failure.getMessage());
    assertEquals(65 + 4 * 1024 * 1024L, input.consumed);
  }
  @Test void aStalledDiscardExpiresAndDoesNotInterruptTheNextRequest() throws Exception {
    var input = new InputStream() {
      int remaining = 65;
      @Override public int read() throws IOException { throw new IOException("Use bulk reads"); }
      @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining > 0) { int count = Math.min(remaining, length); remaining -= count; return count; }
        try { new CountDownLatch(1).await(3, TimeUnit.SECONDS); }
        catch (InterruptedException expired) { Thread.currentThread().interrupt(); throw new InterruptedIOException("discard interrupted"); }
        throw new IOException("Synthetic sender guard expired");
      }
    };
    long started = System.nanoTime();
    assertThrows(InterruptedIOException.class, () -> RequestBodies.read(input, 64, Duration.ofMillis(100)));
    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "The discard has its own deadline");
    assertFalse(Thread.currentThread().isInterrupted(), "A reused worker must not inherit this drain's interrupt");
    assertArrayEquals(new byte[] {1, 2}, RequestBodies.read(new ByteArrayInputStream(new byte[] {1, 2}), 64));
  }
  @Test void aCompletedDiscardCancelsItsDeadline() throws Exception {
    RequestBodies.read(new ByteArrayInputStream(new byte[128]), 64, Duration.ofMillis(50));
    Thread.sleep(100);
    assertFalse(Thread.currentThread().isInterrupted());
  }
  @Test void aReadReturningAfterItsDeadlineCannotReportSuccessfulDrain() {
    var input = new InputStream() {
      int remaining = 65;
      @Override public int read() throws IOException { throw new IOException("Use bulk reads"); }
      @Override public int read(byte[] bytes, int offset, int length) {
        if (length == 0) return 0;
        if (remaining > 0) { int count = Math.min(remaining, length); remaining -= count; return count; }
        try { new CountDownLatch(1).await(3, TimeUnit.SECONDS); }
        catch (InterruptedException expired) { /* Simulate a read that completes at the deadline. */ }
        return -1;
      }
    };
    assertThrows(InterruptedIOException.class, () -> RequestBodies.read(input, 64, Duration.ofMillis(50)));
  }
  @Test void aRealSocketStalledDuringDiscardReleasesTheWorker() throws Exception {
    var worker = Executors.newSingleThreadExecutor(r -> { var thread = new Thread(r, "refusal-fixture"); thread.setDaemon(true); return thread; });
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var finished = new CountDownLatch(1);
    server.setExecutor(worker);
    server.createContext("/", exchange -> {
      try { RequestBodies.read(exchange.getRequestBody(), 64, Duration.ofMillis(100)); }
      catch (IOException expected) { /* The unfinished sender is disconnected on deadline. */ }
      finally { exchange.close(); finished.countDown(); }
    });
    server.start();
    try (var socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1000000\r\n\r\n" + "x".repeat(65)).getBytes(StandardCharsets.US_ASCII));
      assertTrue(finished.await(2, TimeUnit.SECONDS), "The real socket read must obey the discard deadline");
      assertEquals(-1, socket.getInputStream().read());
      assertFalse(worker.submit(() -> Thread.currentThread().isInterrupted()).get(2, TimeUnit.SECONDS));
    } finally { server.stop(0); worker.shutdownNow(); }
  }
  @Test void stoppingAtTheDiscardBoundCannotStrandResponseClose() throws Exception {
    var worker = Executors.newSingleThreadExecutor(r -> { var thread = new Thread(r, "refusal-bound-fixture"); thread.setDaemon(true); return thread; });
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var finished = new CountDownLatch(1);
    server.setExecutor(worker);
    server.createContext("/", exchange -> {
      // Mirror the read route's resource close as well as its response close. Either can
      // invoke the JDK's drain unless the failed read has already disconnected the exchange.
      try (var input = exchange.getRequestBody()) {
        RequestBodies.read(exchange, 64);
        exchange.sendResponseHeaders(413, 1);
        try (var output = exchange.getResponseBody()) { output.write('x'); }
      } catch (IOException expected) { /* Reaching the drain bound permits a disconnect. */ }
      finally { exchange.close(); finished.countDown(); }
    });
    server.start();
    try (var socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
      socket.getOutputStream().write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 16000000\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().write(new byte[65 + 4 * 1024 * 1024]);
      assertTrue(finished.await(2, TimeUnit.SECONDS), "Do not let response close start the JDK's additional blocking drain");
      assertFalse(worker.submit(() -> Thread.currentThread().isInterrupted()).get(2, TimeUnit.SECONDS));
    } finally { server.stop(0); worker.shutdownNow(); }
  }
}
