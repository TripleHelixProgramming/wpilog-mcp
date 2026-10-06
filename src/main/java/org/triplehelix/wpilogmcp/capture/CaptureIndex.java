/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LiveLog;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.nt4.ValueFrame;
import org.triplehelix.wpilogmcp.store.CaptureStore;

/** Connects completed writes to tools before publishing their store manifest. No scan is involved. */
public final class CaptureIndex implements CaptureWriter.Observer {
  private final CaptureWriter.Observer placement;
  private final LogManager manager;
  private final long hotWindowUs;
  private LiveLog live;
  public CaptureIndex(CaptureWriter.Observer placement, LogManager manager, long hotWindowUs) {
    this.placement = placement; this.manager = manager; this.hotWindowUs = hotWindowUs;
    if (placement instanceof CaptureStore store) store.onMove((from, to) -> {
      try { if (live != null && Path.of(live.path()).equals(from)) manager.relocateCapture(live, from, to); } catch (IOException e) { throw new UncheckedIOException(e); }
    });
  }
  @Override public Path create(String address, Instant start) throws IOException { return placement.create(address, start); }
  @Override public Path create(String address, Instant start, CaptureWriter.Session previous) throws IOException {
    return placement.create(address, start, previous);
  }
  @Override public Path create(String address, Instant start, CaptureWriter.Session previous,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity) throws IOException {
    return placement.create(address, start, previous, identity);
  }
  @Override public Path identified(CaptureWriter.Session session,
      org.triplehelix.wpilogmcp.capture.context.DeviceIdentity identity) throws IOException {
    return placement.identified(session, identity);
  }
  @Override public void identity(CaptureWriter.Session session) throws IOException { placement.identity(session); }
  @Override public void opened(CaptureWriter.Session session, boolean resumed) throws IOException {
    if (!resumed) live = new LiveLog(session.path(), hotWindowUs);
    manager.beginCapture(live);
    placement.opened(session, resumed);
  }
  @Override public void entry(CaptureWriter.Session session, EntryInfo entry) throws IOException {
    live.announce(entry); placement.entry(session, entry);
  }
  @Override public void value(CaptureWriter.Session session, EntryInfo entry, ValueFrame frame, WpilogOutput.Written written)
      throws IOException {
    live.append(entry, frame, written); placement.value(session, entry, frame, written);
  }
  @Override public void flushed(CaptureWriter.Session session) throws IOException {
    live.expire(); placement.flushed(session);
  }
  @Override public void timeSync(CaptureWriter.Session session, long serverTimeUs) throws IOException {
    live.advance(serverTimeUs); placement.timeSync(session, serverTimeUs);
  }
  @Override public void fileClosed(CaptureWriter.Session session) throws IOException {
    manager.finishCapture(live); placement.fileClosed(session);
  }
  @Override public void closed(CaptureWriter.Session session) throws IOException { placement.closed(session); }
  @Override public void cost(String topic, TopicCost.Snapshot cost) { placement.cost(topic, cost); }
  public LiveLog live() { return live; }
}
