/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.read;

import java.util.List;

/** Bounded work, including malformed records and file-order records filtered out by windows.
 * Bytes count selected encoded payloads, not heap estimates.
 * One record larger than the batch byte target is returned alone, subject to maxRecordBytes.
 * Empty samples need not mean EOF: inspect complete and problems. */
public record SampleBatch(List<Sample> samples, List<ReadProblem> problems, int recordsVisited,
    long payloadBytes, boolean complete) {
  public SampleBatch { samples = List.copyOf(samples); problems = List.copyOf(problems); }
}
