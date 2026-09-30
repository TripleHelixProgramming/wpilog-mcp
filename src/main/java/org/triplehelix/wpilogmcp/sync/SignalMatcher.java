/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogDevice;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;

/**
 * Identifies matching signal pairs between wpilog and revlog for synchronization.
 *
 * <p>Names only nominate candidates; the synchronizer's correlation decides which pairs are
 * used. A candidate is a numeric wpilog entry whose leaf name (the part after the last '/')
 * contains a keyword of the revlog signal's kind: matching the whole path would nominate every
 * AdvantageKit output ({@code /RealOutputs/...} contains "output"). User-provided hints (CAN ID
 * to entry name) and motor-like path words rank candidates, but do not nominate them.
 *
 * @since 0.5.0
 */
public class SignalMatcher {
  private static final Logger logger = LoggerFactory.getLogger(SignalMatcher.class);

  /**
   * The REV signals used to synchronize, and the leaf-name words of the wpilog entries that may
   * record the same quantity. Only signals that change quickly carry timing: a position is a
   * running total (it correlates with any trend at almost any lag) and a temperature changes
   * over minutes, so neither nominates pairs; nor does any other REV signal.
   */
  private static final Map<String, List<String>> SIGNAL_PATTERNS = Map.of(
      "appliedoutput", List.of(
          "output", "dutycycle", "duty_cycle", "appliedvolts", "appliedvoltage",
          "motorvoltage", "motoroutput", "percentoutput", "appliedoutput"
      ),
      "velocity", List.of(
          "velocity", "speed", "rpm", "angularvelocity", "encodervelocity",
          "wheelspeed", "motorvelocity"
      ),
      "busvoltage", List.of(
          "batteryvoltage", "busvoltage", "voltage", "batteryvolts",
          "supplyvoltage", "inputvoltage"
      ),
      "outputcurrent", List.of(
          "current", "amps", "motorcurrent", "stator", "supplycurrent",
          "outputcurrent", "statorcurrent"
      )
  );

  /**
   * Numeric types that can be correlated.
   */
  private static final Set<String> NUMERIC_TYPES = Set.of(
      "double", "float", "int64", "int32", "int16", "int8"
  );

  /**
   * Finds candidate signal pairs for cross-correlation.
   *
   * @param wpilog The parsed wpilog
   * @param revlog The parsed revlog
   * @param canIdHints Optional mapping of CAN IDs to wpilog entry name hints
   * @return List of signal pairs sorted by match quality (best first)
   */
  public List<SignalPair> findPairs(
      LogData wpilog,
      ParsedRevLog revlog,
      Map<Integer, String> canIdHints) {

    List<SignalPair> pairs = new ArrayList<>();

    for (RevLogDevice device : revlog.devices().values()) {
      String deviceKey = device.deviceKey();
      String hint = canIdHints != null ? canIdHints.get(device.canId()) : null;

      // Get all signals for this device
      for (RevLogSignal revSignal : revlog.signals().values()) {
        if (!revSignal.deviceKey().equals(deviceKey)) {
          continue;
        }

        // Find matching wpilog entries
        List<MatchCandidate> candidates = findMatchingEntries(
            wpilog, revSignal, device, hint);

        for (MatchCandidate candidate : candidates) {
          List<TimestampedValue> wpiValues = wpilog.values().get(candidate.entryName);
          List<TimestampedValue> revValues = revSignal.values();

          if (wpiValues != null && !wpiValues.isEmpty() && !revValues.isEmpty()) {
            SignalPair pair = new SignalPair(
                candidate.entryName,
                revSignal.fullKey(),
                wpiValues,
                revValues,
                revSignal.name(),
                candidate.score
            );

            if (pair.hasSufficientSamples()) {
              pairs.add(pair);
              logger.debug("Found signal pair: {} <-> {} (score: {}, samples: {}/{})",
                  candidate.entryName, revSignal.fullKey(), candidate.score,
                  wpiValues.size(), revValues.size());
            }
          }
        }
      }
    }

    // Sort by match score (best first) then by sample count
    // Note: Use negation for descending order since reversed() doesn't chain correctly
    pairs.sort(Comparator
        .comparingDouble((SignalPair p) -> -p.matchScore())
        .thenComparingInt((SignalPair p) -> -p.minSampleCount()));

    logger.info("Found {} candidate signal pairs for synchronization", pairs.size());
    return pairs;
  }

  /**
   * Finds wpilog entries that might match a revlog signal.
   */
  private List<MatchCandidate> findMatchingEntries(
      LogData wpilog,
      RevLogSignal revSignal,
      RevLogDevice device,
      String deviceHint) {

    List<MatchCandidate> matches = new ArrayList<>();
    String signalType = revSignal.name().toLowerCase(java.util.Locale.ROOT);

    // Get patterns for this signal type
    List<String> patterns = SIGNAL_PATTERNS.get(signalType);
    if (patterns == null) {
      return matches;
    }

    for (Map.Entry<String, EntryInfo> entry : wpilog.entries().entrySet()) {
      String entryName = entry.getKey();
      EntryInfo info = entry.getValue();

      // Skip non-numeric entries
      if (!isNumericEntry(info)) {
        continue;
      }

      String lowerName = entryName.toLowerCase(java.util.Locale.ROOT);
      String leaf = lowerName.substring(lowerName.lastIndexOf('/') + 1);
      if (patterns.stream().noneMatch(leaf::contains)) {
        continue;
      }
      matches.add(new MatchCandidate(entryName,
          calculateMatchScore(lowerName, patterns, deviceHint)));
    }

    // Best-named first; the synchronizer ranks all of them by correlation
    matches.sort(Comparator.comparingDouble(MatchCandidate::score).reversed());
    return matches;
  }

  /**
   * Ranks a candidate (its leaf name already matched a pattern): the match, a device hint in the
   * path, and a motor-like path word.
   */
  private double calculateMatchScore(String entryName, List<String> patterns, String deviceHint) {
    double score = 0.5;

    // Boost score if device hint matches
    if (deviceHint != null && !deviceHint.isBlank()) {
      if (entryName.contains(deviceHint.toLowerCase())) {
        score += 0.4;
      }
    }

    // Boost score for common motor-related path segments
    if (entryName.contains("drive") || entryName.contains("motor") ||
        entryName.contains("wheel") || entryName.contains("arm") ||
        entryName.contains("shooter") || entryName.contains("intake")) {
      score += 0.1;
    }

    return score;
  }

  /**
   * Checks if an entry contains numeric data.
   */
  private boolean isNumericEntry(EntryInfo info) {
    String type = info.type().toLowerCase();
    return NUMERIC_TYPES.contains(type) || type.startsWith("double") || type.startsWith("float");
  }

  /**
   * Internal class to hold match candidates with scores.
   */
  private record MatchCandidate(String entryName, double score) {}

  /**
   * Prioritizes signal pairs by expected correlation quality.
   *
   * <p>Priority order:
   * <ol>
   *   <li>AppliedOutput - usually has most variance</li>
   *   <li>velocity - good dynamic signal</li>
   *   <li>outputCurrent - good for load changes</li>
   *   <li>position - may have less variance</li>
   *   <li>Others</li>
   * </ol>
   *
   * @param pairs The signal pairs to prioritize
   * @return Prioritized list (best candidates first)
   */
  public List<SignalPair> prioritizePairs(List<SignalPair> pairs) {
    Map<String, Integer> priority = Map.of(
        "appliedoutput", 1,
        "velocity", 2,
        "outputcurrent", 3,
        "position", 4,
        "motortemperature", 5
    );

    return pairs.stream()
        .sorted(Comparator
            .comparingInt((SignalPair p) ->
                priority.getOrDefault(p.signalType().toLowerCase(), 10))
            .thenComparingDouble((SignalPair p) -> -p.matchScore())
            .thenComparingInt((SignalPair p) -> -p.minSampleCount()))
        .toList();
  }
}
