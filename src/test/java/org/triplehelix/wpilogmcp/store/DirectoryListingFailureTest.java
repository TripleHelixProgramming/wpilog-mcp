/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mid-iteration filesystem failures must retain the same source isolation as walkFileTree. */
class DirectoryListingFailureTest {
  @TempDir Path temp;

  @Test void iteratorFailureClosesTheStreamAndDoesNotPublishAPartialListing() throws Exception {
    temp = temp.toRealPath();
    var child = Files.createDirectory(temp.resolve("child"));
    writeHeader(child, UUID.randomUUID().toString());
    var failure = new IOException("synthetic directory enumeration failure");
    var closeFailure = new IOException("synthetic directory close failure");
    var opens = new AtomicInteger(); var closes = new AtomicInteger();
    var listings = new DirectoryListings(Clock.systemUTC(), ignored -> {}, directory -> {
      if (directory.equals(temp) && opens.getAndIncrement() == 0)
        return brokenAfterOneChild(child, failure, closeFailure, closes);
      return Files.newDirectoryStream(directory);
    });
    var actual = assertThrows(IOException.class, () -> listings.discover(temp),
        "An iterator's I/O failure must reach source admission as IOException");
    assertSame(failure, actual, "Unwrap the original I/O cause, not a generic discovery error");
    assertEquals(List.of(closeFailure), List.of(actual.getSuppressed()),
        "Unwrapping must retain the resource-close failure");
    assertEquals(1, closes.get(), "The failed stream must close exactly once");
    assertEquals(List.of(child), listings.discover(temp));
    assertEquals(2, opens.get(), "The next request must reopen, not reuse a partial listing");
  }

  @Test void failedIteratorRetainsUnavailableChoiceWithoutHidingHealthyStore() throws Exception {
    temp = temp.toRealPath();
    var healthy = Files.createDirectory(temp.resolve("healthy"));
    var broken = Files.createDirectory(temp.resolve("broken"));
    var child = Files.createDirectory(broken.resolve("child"));
    String id = UUID.randomUUID().toString(); writeHeader(healthy, id);
    var closes = new AtomicInteger();
    var door = new StoreDoor(() -> Set.of(healthy, broken), ignored -> {}, Clock.systemUTC(), directory ->
        directory.equals(broken)
            ? brokenAfterOneChild(child, new IOException("synthetic directory enumeration failure"), null, closes)
            : Files.newDirectoryStream(directory));
    var inventory = assertDoesNotThrow(door::inventory,
        "One iterator's I/O failure must not abort the healthy source inventory");
    assertEquals(List.of(id), inventory.stores().stream().map(s -> s.description().id()).toList());
    assertEquals(1, inventory.unreadable().size());
    assertEquals(broken, Path.of(inventory.unreadable().get(0).path()));
    assertEquals("synthetic directory enumeration failure", inventory.unreadable().get(0).reason());
    assertEquals(1, closes.get());
  }

  private static DirectoryStream<Path> brokenAfterOneChild(Path child, IOException failure,
      IOException closeFailure, AtomicInteger closes) {
    return new DirectoryStream<>() {
      @Override public Iterator<Path> iterator() {
        return new Iterator<>() {
          private boolean emitted;
          @Override public boolean hasNext() {
            if (emitted) throw new DirectoryIteratorException(failure);
            return true;
          }
          @Override public Path next() {
            if (emitted) throw new DirectoryIteratorException(failure);
            emitted = true; return child;
          }
        };
      }
      @Override public void close() throws IOException {
        closes.incrementAndGet();
        if (closeFailure != null) throw closeFailure;
      }
    };
  }

  private static void writeHeader(Path directory, String id) throws IOException {
    Files.writeString(directory.resolve("store.json"), StoreJson.JSON.toJson(
        new StoreManifest.Header(1, "2026-01-01T00:00:00Z", id, List.of())));
  }
}
