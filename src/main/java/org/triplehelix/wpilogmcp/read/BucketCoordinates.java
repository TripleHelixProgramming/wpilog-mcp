/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.math.BigInteger;

/** Integer coordinates keep one microsecond on the same side of a bucket boundary in every
 * reducer. The overflow fallback handles the complete signed-long timestamp span. */
public final class BucketCoordinates {
  private BucketCoordinates() {}

  public static int index(long startUs, long endUs, int points, long timeUs) {
    if (points < 1 || endUs < startUs) throw new IllegalArgumentException("Invalid bucket window or count");
    if (endUs == startUs || timeUs <= startUs) return 0;
    if (timeUs >= endUs) return points - 1;
    try {
      return (int) (Math.multiplyExact(Math.subtractExact(timeUs, startUs), points)
          / Math.subtractExact(endUs, startUs));
    } catch (ArithmeticException overflow) {
      return BigInteger.valueOf(timeUs).subtract(BigInteger.valueOf(startUs)).multiply(BigInteger.valueOf(points))
          .divide(BigInteger.valueOf(endUs).subtract(BigInteger.valueOf(startUs))).intValueExact();
    }
  }

  /** Round starts upward: floor((time-start)*points/span) then assigns that integer to its bucket. */
  public static long boundary(long startUs, long endUs, int points, int index) {
    if (points < 1 || endUs < startUs || index < 0 || index > points)
      throw new IllegalArgumentException("Invalid bucket boundary");
    var span = BigInteger.valueOf(endUs).subtract(BigInteger.valueOf(startUs));
    return BigInteger.valueOf(startUs).add(span.multiply(BigInteger.valueOf(index))
        .add(BigInteger.valueOf(points - 1)).divide(BigInteger.valueOf(points))).longValueExact();
  }
}
