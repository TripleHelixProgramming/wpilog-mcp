/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.triplehelix.wpilogmcp.fixtures.FixtureLogs;
import org.triplehelix.wpilogmcp.fixtures.ImportFixture;
import org.triplehelix.wpilogmcp.log.LogManager;

/** The conformance sweep observes real ingestion receipts without a timer or a user's files. */
public record LocalIngestionFixture(LocalIngestion service, StoreRegistry registry, Path original)
    implements AutoCloseable {
  public static LocalIngestionFixture create(Path root, LogManager logs) throws Exception {
    root = FixtureLogs.freshDirectory(root).toRealPath();
    var source = Files.createDirectory(root.resolve("source"));
    var destination = Files.createDirectory(root.resolve("store"));
    var original = ImportFixture.write(source.resolve("synthetic.wpilog"), 1);
    var service = logs.stores().configureLocalIngestion(root.resolve("policy.json"));
    var fixture = new LocalIngestionFixture(service, logs.stores(), original);
    try {
      service.replace(new LocalIngestion.Policy(0, true, destination.toString(), null,
          List.of(new LocalIngestion.Source(source.toString(), "copy", true, false, "synthetic-ingestion"))));
      service.poll(0); service.currentWork().get(30, TimeUnit.SECONDS);
      service.poll(TimeUnit.SECONDS.toNanos(3)); service.currentWork().get(30, TimeUnit.SECONDS);
      return fixture;
    } catch (Exception | Error failure) {
      fixture.close(); throw failure;
    }
  }

  @Override public void close() {
    service.close(); registry.clearLocalIngestion(service);
  }
}
