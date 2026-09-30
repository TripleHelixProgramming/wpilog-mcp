/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log.struct;

/**
 * An integer struct field declared with an {@code enum {...}} clause in its schema: the stored
 * number and, when the schema names it, its label. Serialized as
 * {@code {"value": 2, "label": "PHOTONVISION"}}; field paths read {@code value} as the number.
 *
 * @param value The stored integer
 * @param label The schema's name for it, or null when the value is not one the schema lists
 * @since 0.9.0
 */
public record EnumValue(long value, String label) {}
