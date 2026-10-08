/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture.context;

/** Generated OS counters: two seconds, 120 busy ticks of 200, and 50 program ticks at 100 Hz. */
public final class ProcFixture {
  private ProcFixture() {}
  public static String sample(int n) {
    String program = "42 (robot (java)) S 1 2 3 4 5 6 7 8 9 10 " + (10 + 40 * n) + " " + (5 + 10 * n)
        + " 0 0 20 0 7 0 500 99999 3";
    return """
        WPILOG_STATS_1:ticks
        100
        WPILOG_STATS_1:pages
        8192
        WPILOG_STATS_1:load
        1.5 0.5 0.25 3/12 42
        WPILOG_STATS_1:stat
        cpu %d %d %d %d 0 0 0 0 %d %d
        intr 99
        WPILOG_STATS_1:memory
        MemAvailable: 500 kB
        MemFree: 100 kB
        WPILOG_STATS_1:uptime
        %d.0 70.0
        WPILOG_STATS_1:network
        Inter-| Receive | Transmit
        face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed
        eth0: %d 0 0 0 0 0 0 0 %d 0 0 0 0 0 0 0
        WPILOG_STATS_1:disk/home/lvuser
        Filesystem 1024-blocks Used Available Capacity Mounted on
        /dev/synthetic 100 89 11 89%% /home/lvuser
        WPILOG_STATS_1:program
        %s
        """.formatted(100 + 80 * n, 20 + 10 * n, 30 + 30 * n, 850 + 80 * n,
            5 + 4 * n, 2 + n, 100 + 2 * n, 1000 + 400 * n, 2000 + 600 * n, program);
  }
}
