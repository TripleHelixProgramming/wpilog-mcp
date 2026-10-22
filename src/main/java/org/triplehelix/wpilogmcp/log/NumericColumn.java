/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.log;

import org.triplehelix.wpilogmcp.read.NumericSamples;

/**
 * An entry's numbers at one field path as its log's column file holds them: the samples in the
 * order the tools read them, with the counts the tools report beside them. The samples read
 * mapped memory that closes with the log, so they belong to the call that holds the log's use,
 * on one thread.
 *
 * @param samples the samples, in the order the tools read them
 * @param decoded the entry's records that decoded, which a read counts
 * @param recordsWithoutValue decoded records in which the path held no number
 * @param values for a scalar entry, the samples as the typed values its decode gives, made as
 *     they are read; null for a field, whose values are numbers
 */
public record NumericColumn(NumericSamples samples, int decoded, int recordsWithoutValue,
    java.util.List<TimestampedValue> values) {}
