/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log.struct;

import java.math.BigInteger;

/** Native reads retain the full unsigned field; compatibility tools keep {@link EnumValue}. */
public record UnsignedEnumValue(BigInteger value, String label) {
  public UnsignedEnumValue {
    if (value == null || value.signum() < 0 || value.bitLength() > 64)
      throw new IllegalArgumentException("Unsigned enum value must be in 0..18446744073709551615");
  }
}
