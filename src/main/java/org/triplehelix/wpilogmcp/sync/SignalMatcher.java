package org.triplehelix.wpilogmcp.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.EntryInfo;
import org.triplehelix.wpilogmcp.log.ParsedLog;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;
import org.triplehelix.wpilogmcp.revlog.RevLogDevice;
import org.triplehelix.wpilogmcp.revlog.RevLogSignal;

/**
 * Identifies matching signal pairs between wpilog and revlog for synchronization.
 *
 * <p>Signal matching uses multiple strategies:
 * <ol>
 *   <li>Name-based matching: looks for common keywords in entry names</li>
 *   <li>Signal type matching: matches revlog signal types to wpilog patterns</li>
 *   <li>User-provided hints: CAN ID to entry name mappings</li>
 * </ol>
 *
 * @since 0.5.0
 */
public class SignalMatcher {
  private static final Logger logger = LoggerFactory.getLogger(SignalMatcher.class);

  /**
   * Known signal type mappings from revlog signals to wpilog entry patterns.
   * Keys are revlog signal names (lowercase), values are patterns to look for in wpilog entries.
   */
  private static final Map<String, List<String>> SIGNAL_PATTERNS = Map.of(
      "appliedoutput", List.of(
          "output", "dutycycle", "duty_cycle", "appliedvolts",
          "motoroutput", "percentoutput", "appliedoutput", "voltage"
      ),
      "velocity", List.of(
          "velocity", "speed", "rpm", "angularvelocity", "encodervelocity",
          "wheelspeed", "motorvelocity"
      ),
      "position", List.of(
          "position", "angle", "rotation", "encoderposition", "distance",
          "motorposition", "wheelposition"
      ),
      "busvoltage", List.of(
          "batteryvoltage", "busvoltage", "voltage", "batteryvolts",
          "supplyvoltage", "inputvoltage"
      ),
      "outputcurrent", List.of(
          "current", "amps", "motorcurrent", "stator", "supplycurrent",
          "outputcurrent", "statorcurrent"
      ),
      "temperature", List.of(
          "temperature", "temp", "motortemp", "controllertemp"
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
      ParsedLog wpilog,
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
      ParsedLog wpilog,
      RevLogSignal revSignal,
      RevLogDevice device,
      String deviceHint) {

    List<MatchCandidate> matches = new ArrayList<>();
    String signalType = revSignal.name().toLowerCase();

    // Get patterns for this signal type
    List<String> patterns = SIGNAL_PATTERNS.getOrDefault(
        signalType, List.of(signalType));

    for (Map.Entry<String, EntryInfo> entry : wpilog.entries().entrySet()) {
      String entryName = entry.getKey();
      EntryInfo info = entry.getValue();

      // Skip non-numeric entries
      if (!isNumericEntry(info)) {
        continue;
      }

      String lowerName = entryName.toLowerCase();

      // Calculate match score
      double score = calculateMatchScore(lowerName, patterns, deviceHint);

      if (score > 0) {
        matches.add(new MatchCandidate(entryName, score));
      }
    }

    // Sort by score descending and limit to top 3 per signal
    matches.sort(Comparator.comparingDouble(MatchCandidate::score).reversed());
    if (matches.size() > 3) {
      matches = matches.subList(0, 3);
    }

    return matches;
  }

  /**
   * Calculates a match score for an entry name against signal patterns.
   */
  private double calculateMatchScore(String entryName, List<String> patterns, String deviceHint) {
    double score = 0.0;

    // Check pattern matches
    for (String pattern : patterns) {
      if (entryName.contains(pattern)) {
        score += 0.5;
        break; // Only count one pattern match
      }
    }

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
   *   <li>appliedOutput - usually has most variance</li>
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
        "temperature", 5
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
