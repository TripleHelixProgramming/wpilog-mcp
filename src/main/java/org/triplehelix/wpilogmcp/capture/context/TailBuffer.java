/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounds both pre-session history and a slow consumer. No I/O happens under this short lock. */
public final class TailBuffer {
  public static final int MAX_LINES = 1000, LINES_PER_SECOND = 200, MAX_LINE_BYTES = 65_536;
  public record Line(String text, long receivedUs, boolean buffered) {
    public long timestampUs(double offsetUs) { return Math.max(0, Math.round(receivedUs + offsetUs)); }
  }
  private final ArrayDeque<Line> lines = new ArrayDeque<>();
  private long second = Long.MIN_VALUE, dropped, burstDrops, overflowDrops, lastReceipt, lastDrop;
  private int accepted;
  private boolean buffered;

  public synchronized void line(String text, long receivedUs, boolean beforeSession) {
    advance(receivedUs);
    lastReceipt = receivedUs;
    if (accepted == LINES_PER_SECOND) { dropped++; burstDrops++; lastDrop = receivedUs; return; }
    accepted++;
    add(new Line(text, receivedUs, beforeSession));
  }
  /** A single oversized line cannot allocate without bound or disappear without accounting. */
  public synchronized void oversized(long receivedUs) { advance(receivedUs); dropped++; burstDrops++; lastDrop = receivedUs; lastReceipt = receivedUs; }
  private void advance(long now) {
    // A burst crosses bucket boundaries. Emit once after a full second without another drop.
    if (burstDrops > 0 && now - lastDrop >= 1_000_000) {
      add(new Line("[wpilog-mcp dropped " + burstDrops + " tail lines: rate or line-size limit]", lastDrop, buffered));
      burstDrops = 0;
    }
    long bucket = Math.floorDiv(now, 1_000_000);
    if (bucket != second) { second = bucket; accepted = 0; }
  }
  private void add(Line line) {
    buffered |= line.buffered();
    if (lines.size() == MAX_LINES) { lines.removeFirst(); dropped++; overflowDrops++; }
    lines.addLast(line);
  }
  public synchronized List<Line> drain(long nowUs, int limit) {
    advance(nowUs);
    var result = new ArrayList<Line>();
    if (overflowDrops > 0 && limit > 0) {
      result.add(new Line("[wpilog-mcp dropped " + overflowDrops + " tail lines: buffer limit]", lastReceipt, true));
      overflowDrops = 0;
    }
    while (!lines.isEmpty() && result.size() < limit) result.add(lines.removeFirst());
    return List.copyOf(result);
  }
  public synchronized int size() { return lines.size(); }
  public synchronized long dropped() { return dropped; }
  public synchronized double linesPerSecond(long nowUs) { return Math.floorDiv(nowUs, 1_000_000) == second ? accepted : 0; }
  public synchronized boolean buffered() { return buffered; }
}
