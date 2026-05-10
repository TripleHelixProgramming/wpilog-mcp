/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

/**
 * A value with an associated timestamp from a log entry.
 *
 * @param timestamp The timestamp in seconds
 * @param value The value (can be primitive, String, or decoded struct Map)
 * @since 0.4.0
 */
public record TimestampedValue(double timestamp, Object value) {}
