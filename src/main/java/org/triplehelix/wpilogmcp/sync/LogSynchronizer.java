/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.sync;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.revlog.ParsedRevLog;

/**
 * Synchronizes revlog timestamps with wpilog FPGA time.
 *
 * <p>The synchronization uses a two-phase approach:
 * <ol>
 *   <li><b>Coarse alignment:</b> Uses systemTime entries from wpilog and filename
 *       timestamp from revlog to estimate initial offset</li>
 *   <li><b>Fine alignment:</b> Uses cross-correlation of matching signals to
 *       refine the offset with millisecond accuracy</li>
 * </ol>
 *
 * @since 0.5.0
 */
public class LogSynchronizer {
  private static final Logger logger = LoggerFactory.getLogger(LogSynchronizer.class);

  /** Default search window: ±60 seconds from coarse estimate (in samples at default rate). */
  static final int DEFAULT_SEARCH_WINDOW_SAMPLES = 6000;

  /** Default sample rate for resampling signals (Hz). */
  static final double DEFAULT_SAMPLE_RATE_HZ = 100.0;

  /**
   * Default maximum samples to resample.
   * 60,000 samples at 100Hz = 10 minutes of data, sufficient for most FRC matches.
   */
  static final int DEFAULT_MAX_RESAMPLE_SAMPLES = 60000;

  /**
   * Default minimum standard deviation for a signal to be considered non-flat.
   * Motor duty cycle at rest may have small floating-point noise (~1e-6),
   * so we use 1e-6 to reject truly flat signals while preserving low-variance ones.
   */
  static final double DEFAULT_FLAT_SIGNAL_THRESHOLD = 1e-6;

  /** Default minimum correlation to consider a pair useful. */
  static final double DEFAULT_MIN_USEFUL_CORRELATION = 0.5;

  /** Default minimum correlation for high-quality match. */
  static final double DEFAULT_HIGH_CORRELATION_THRESHOLD = 0.7;

  /**
   * The least overlapping data a correlation may rest on: a few seconds of a mechanism's output
   * correlates with some stretch of another log at almost any lag (a 3.7 s REV log from the
   * next boot "synchronized" at +110 s in a real log).
   */
  static final double MIN_OVERLAP_SEC = 10.0;

  /** Pairs cross-correlated at full resolution, after ranking every candidate coarsely. */
  static final int MAX_REFINED_PAIRS = 5;

  /** The coarse ranking's sample rate, as a fraction of the full rate. */
  private static final int COARSE_DECIMATION = 10;

  private final SignalMatcher signalMatcher;
  private final int searchWindowSamples;
  private final double sampleRateHz;
  private final int maxResampleSamples;
  private final double flatSignalThreshold;
  private final double minUsefulCorrelation;
  private final double highCorrelationThreshold;
  private static final java.time.format.DateTimeFormatter FILENAME_TIMESTAMP_FORMAT =
      java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
  private final ZoneId filenameTimezone;

  /**
   * Creates a new LogSynchronizer with default parameters.
   */
  public LogSynchronizer() {
    this(DEFAULT_SEARCH_WINDOW_SAMPLES, DEFAULT_SAMPLE_RATE_HZ,
         DEFAULT_MAX_RESAMPLE_SAMPLES, DEFAULT_FLAT_SIGNAL_THRESHOLD,
         DEFAULT_MIN_USEFUL_CORRELATION, DEFAULT_HIGH_CORRELATION_THRESHOLD);
  }

  /**
   * Creates a new LogSynchronizer with configurable parameters.
   *
   * @param searchWindowSamples Search window for cross-correlation (±samples from coarse estimate)
   * @param sampleRateHz Sample rate for resampling signals
   * @param maxResampleSamples Maximum number of samples when resampling
   * @param flatSignalThreshold Minimum std dev for a signal to be non-flat
   * @param minUsefulCorrelation Minimum correlation to consider a pair useful
   * @param highCorrelationThreshold Minimum correlation for high-quality match
   */
  public LogSynchronizer(int searchWindowSamples, double sampleRateHz,
      int maxResampleSamples, double flatSignalThreshold,
      double minUsefulCorrelation, double highCorrelationThreshold) {
    this(searchWindowSamples, sampleRateHz, maxResampleSamples,
         flatSignalThreshold, minUsefulCorrelation, highCorrelationThreshold, null);
  }

  /**
   * Creates a new LogSynchronizer with configurable parameters and timezone.
   *
   * @param searchWindowSamples Search window for cross-correlation (±samples from coarse estimate)
   * @param sampleRateHz Sample rate for resampling signals
   * @param maxResampleSamples Maximum number of samples when resampling
   * @param flatSignalThreshold Minimum std dev for a signal to be non-flat
   * @param minUsefulCorrelation Minimum correlation to consider a pair useful
   * @param highCorrelationThreshold Minimum correlation for high-quality match
   * @param filenameTimezone Timezone for interpreting revlog filename timestamps, or null to
   *     use the zone the wpilog shows ({@link
   *     org.triplehelix.wpilogmcp.log.WallClock#revlogFilenameZone}): REVLib in robot code
   *     names its files by the roboRIO's clock, which also named the wpilog. Set it for REV
   *     logs named by another clock (the REV Hardware Client uses the PC's local time).
   */
  public LogSynchronizer(int searchWindowSamples, double sampleRateHz,
      int maxResampleSamples, double flatSignalThreshold,
      double minUsefulCorrelation, double highCorrelationThreshold,
      ZoneId filenameTimezone) {
    this.signalMatcher = new SignalMatcher();
    this.searchWindowSamples = searchWindowSamples;
    this.sampleRateHz = sampleRateHz;
    this.maxResampleSamples = maxResampleSamples;
    this.flatSignalThreshold = flatSignalThreshold;
    this.minUsefulCorrelation = minUsefulCorrelation;
    this.highCorrelationThreshold = highCorrelationThreshold;
    this.filenameTimezone = filenameTimezone;
  }

  /**
   * Synchronizes a revlog with a wpilog file.
   *
   * @param wpilog The parsed wpilog
   * @param revlog The parsed revlog
   * @return The synchronization result
   */
  public SyncResult synchronize(LogData wpilog, ParsedRevLog revlog) {
    return synchronize(wpilog, revlog, Map.of());
  }

  /**
   * Synchronizes a revlog with a wpilog file using optional CAN ID hints.
   *
   * @param wpilog The parsed wpilog
   * @param revlog The parsed revlog
   * @param canIdHints Optional mapping of CAN IDs to wpilog entry name hints
   * @return The synchronization result
   */
  public SyncResult synchronize(
      LogData wpilog,
      ParsedRevLog revlog,
      Map<Integer, String> canIdHints) {

    logger.info("Starting synchronization of {} with {}",
        revlog.path(), wpilog.path());

    // Phase 1: Coarse alignment from systemTime
    OptionalLong coarseOffsetOpt = estimateCoarseOffset(wpilog, revlog);

    // Phase 2: Find candidate signal pairs
    List<SignalPair> candidates = signalMatcher.findPairs(wpilog, revlog, canIdHints);
    candidates = signalMatcher.prioritizePairs(candidates);

    if (candidates.isEmpty() && coarseOffsetOpt.isEmpty()) {
      logger.warn("No signal pairs and no coarse offset - sync failed");
      return SyncResult.failed(
          "No matching signals found and no system time data available for alignment.");
    }

    if (candidates.isEmpty()) {
      long coarseOffset = coarseOffsetOpt.getAsLong();
      logger.warn("No signal pairs found for cross-correlation, using coarse offset only");
      return SyncResult.fromSystemTimeOnly(coarseOffset,
          "No matching signals found for fine alignment. Using the wall-clock estimate only: "
              + "the REV log's filename time against the wpilog's wall clock, off by as much as the roboRIO's clock was when the file was named (seconds or more).");
    }

    long coarseOffset;
    if (coarseOffsetOpt.isPresent()) {
      coarseOffset = coarseOffsetOpt.getAsLong();
      logger.debug("Coarse offset estimate: {}ms", coarseOffset / 1000.0);
    } else {
      coarseOffset = 0L;
      logger.warn("No coarse offset estimate — centering cross-correlation search at 0. "
          + "Sync may fail if true offset exceeds ±{}s search window.",
          searchWindowSamples / sampleRateHz);
    }

    // Phase 3: Names only nominate pairs; the data choose them. Rank every candidate by a
    // coarse correlation (1/10 the sample rate, the same search range), then cross-correlate
    // the best few at full resolution.
    List<SignalPair> ranked = rankByCoarseCorrelation(candidates, coarseOffset);
    List<SignalPairResult> results = new ArrayList<>();
    for (SignalPair pair : ranked) {
      SignalPairResult result = crossCorrelate(pair, coarseOffset);
      results.add(result);

      logger.debug("Pair {} <-> {}: correlation={}, offset={}ms",
          pair.wpilogEntry(), pair.revlogSignal(),
          String.format("%.3f", result.correlation()),
          result.estimatedOffsetMillis());

      if (results.size() >= MAX_REFINED_PAIRS) {
        break;
      }
    }

    // Phase 4: Compute consensus offset and confidence
    SyncResult baseResult = computeConsensus(results, coarseOffset, coarseOffsetOpt.isPresent());

    // Phase 5: Estimate clock drift for long recordings (>15 minutes)
    if (baseResult.isSuccessful() && baseResult.method() == SyncMethod.CROSS_CORRELATION) {
      double revDuration = revlog.maxTimestamp() - revlog.minTimestamp();
      if (revDuration > 15 * 60) { // 15 minutes
        return estimateDrift(baseResult, candidates, coarseOffset, revlog);
      }
    }

    return baseResult;
  }

  /**
   * Estimates linear clock drift by computing offsets on the first and second halves
   * of the recording separately. If the offsets differ, there is clock skew.
   *
   * @param baseResult The initial sync result (without drift)
   * @param candidates The signal pairs to use
   * @param coarseOffset The coarse offset estimate
   * @param revlog The parsed revlog (for time range)
   * @return A SyncResult with drift rate populated, or the original if drift cannot be estimated
   */
  private SyncResult estimateDrift(
      SyncResult baseResult,
      List<SignalPair> candidates,
      long coarseOffset,
      ParsedRevLog revlog) {

    double midTime = (revlog.minTimestamp() + revlog.maxTimestamp()) / 2.0;

    // Try to get offset from a pair that spans the full recording
    // by cross-correlating just the first half and just the second half
    for (SignalPair pair : candidates) {
      if (!pair.hasSufficientSamples()) continue;

      List<TimestampedValue> revValues = pair.revlogValues();
      double revStart = revValues.get(0).timestamp();
      double revEnd = revValues.get(revValues.size() - 1).timestamp();

      if (revEnd - revStart < 10 * 60) continue; // Need at least 10 min span

      // Split revlog values at midpoint
      List<TimestampedValue> firstHalf = revValues.stream()
          .filter(tv -> tv.timestamp() < midTime)
          .toList();
      List<TimestampedValue> secondHalf = revValues.stream()
          .filter(tv -> tv.timestamp() >= midTime)
          .toList();

      if (firstHalf.size() < 100 || secondHalf.size() < 100) continue;

      // Similarly split wpilog values (using the base offset to find corresponding times)
      double offsetSec = baseResult.offsetSeconds();
      double wpiMidTime = midTime + offsetSec;

      List<TimestampedValue> wpiValues = pair.wpilogValues();
      List<TimestampedValue> wpiFirstHalf = wpiValues.stream()
          .filter(tv -> tv.timestamp() < wpiMidTime)
          .toList();
      List<TimestampedValue> wpiSecondHalf = wpiValues.stream()
          .filter(tv -> tv.timestamp() >= wpiMidTime)
          .toList();

      if (wpiFirstHalf.size() < 100 || wpiSecondHalf.size() < 100) continue;

      // Cross-correlate each half, using the base result's offset as center
      // (more accurate than the coarse offset, which may be zero/estimated)
      long refinedOffset = baseResult.offsetMicros();
      SignalPair firstPair = new SignalPair(
          pair.wpilogEntry(), pair.revlogSignal(),
          wpiFirstHalf, firstHalf, pair.signalType(), pair.matchScore());
      SignalPair secondPair = new SignalPair(
          pair.wpilogEntry(), pair.revlogSignal(),
          wpiSecondHalf, secondHalf, pair.signalType(), pair.matchScore());

      SignalPairResult firstResult = crossCorrelate(firstPair, refinedOffset);
      SignalPairResult secondResult = crossCorrelate(secondPair, refinedOffset);

      if (!firstResult.isUsableCorrelation() || !secondResult.isUsableCorrelation()) continue;

      // Compute drift rate — time delta between median timestamps of each half.
      // Using actual medians instead of geometric midpoint handles non-uniform
      // sample distribution (e.g., robot disabled gaps).
      double firstHalfMedianTime = medianTimestamp(firstHalf);
      double secondHalfMedianTime = medianTimestamp(secondHalf);
      double timeDelta = secondHalfMedianTime - firstHalfMedianTime;

      if (timeDelta < 60) continue; // Too close together

      long offsetDelta = secondResult.estimatedOffsetMicros() - firstResult.estimatedOffsetMicros();
      double driftNanosPerSec = (offsetDelta * 1000.0) / timeDelta;

      // Only report drift if it's significant (>1 ns/s = ~0.1ms per 100s)
      if (Math.abs(driftNanosPerSec) < 1.0) {
        logger.debug("Clock drift negligible ({} ns/s), ignoring", driftNanosPerSec);
        return baseResult;
      }

      double refTime = (revStart + revEnd) / 2.0;
      String driftInfo = String.format(" Clock drift detected: %.1f ns/s (%.1f ms/hr).",
          driftNanosPerSec, driftNanosPerSec * 3.6);

      logger.info("Estimated clock drift: {} ns/s between revlog and wpilog",
          String.format("%.1f", driftNanosPerSec));

      return new SyncResult(
          baseResult.offsetMicros(),
          baseResult.confidence(),
          baseResult.confidenceLevel(),
          baseResult.signalPairs(),
          baseResult.method(),
          baseResult.explanation() + driftInfo,
          driftNanosPerSec,
          refTime);
    }

    // Could not estimate drift
    return baseResult;
  }

  /**
   * Estimates coarse offset from systemTime entries and filename timestamp.
   *
   * <p>The offset is calculated as: FPGA_time - revlog_time
   * Adding this offset to revlog timestamps converts them to FPGA time.
   *
   * @return The estimated offset in microseconds, or empty if estimation failed
   */
  private OptionalLong estimateCoarseOffset(LogData wpilog, ParsedRevLog revlog) {
    // Try to find systemTime entries in wpilog
    List<SystemTimeEntry> systemTimes = extractSystemTimeEntries(wpilog);

    // Parse revlog filename timestamp
    LocalDateTime revlogStartTime = parseFilenameTimestamp(revlog.filenameTimestamp());

    if (systemTimes.isEmpty() || revlogStartTime == null) {
      logger.warn("Cannot estimate coarse offset: missing systemTime or filename timestamp");
      return OptionalLong.empty();
    }

    // Convert revlog start time to epoch microseconds. The wall-clock entries are epoch
    // microseconds (zone-independent); the filename is in the zone of the clock that named it,
    // which the wpilog's own filename shows unless a zone was configured.
    ZoneId zone = filenameTimezone != null ? filenameTimezone
        : org.triplehelix.wpilogmcp.log.WallClock.revlogFilenameZone(wpilog).offset();
    long revlogStartEpochMicros = revlogStartTime
        .atZone(zone)
        .toEpochSecond() * 1_000_000L;

    // Interpolate FPGA time at revlog start
    long fpgaAtRevlogStart = interpolateFpgaTime(systemTimes, revlogStartEpochMicros);

    // The revlog's first timestamp is relative to its start
    long revlogFirstTimestamp = (long) (revlog.minTimestamp() * 1_000_000);

    // Offset = FPGA time at revlog start - revlog's relative timestamp base
    return OptionalLong.of(fpgaAtRevlogStart - revlogFirstTimestamp);
  }

  /**
   * Extracts systemTime entries from wpilog.
   * systemTime entries map FPGA timestamps to wall clock time.
   */
  private List<SystemTimeEntry> extractSystemTimeEntries(LogData wpilog) {
    List<SystemTimeEntry> entries = new ArrayList<>();

    // WPILib's systemTime, else AdvantageKit's /SystemStats/EpochTimeMicros (exact names), from
    // after the clock was set (before, it reads 1970 or a default date)
    for (var reading : org.triplehelix.wpilogmcp.log.WallClock.validReadings(wpilog)) {
      entries.add(new SystemTimeEntry((long) (reading.logTime() * 1_000_000),
          reading.epochMicros()));
    }
    return entries;
  }

  /**
   * Parses a timestamp from revlog filename format: YYYYMMDD_HHMMSS
   */
  private LocalDateTime parseFilenameTimestamp(String timestamp) {
    if (timestamp == null || timestamp.length() < 15) {
      return null;
    }

    try {
      return LocalDateTime.parse(timestamp, FILENAME_TIMESTAMP_FORMAT);
    } catch (Exception e) {
      logger.debug("Failed to parse filename timestamp: {}", timestamp);
      return null;
    }
  }

  /**
   * Interpolates FPGA time at a given wall clock time using systemTime entries. Outside the
   * entries' span it extrapolates at one to one (both clocks count real time): a REV log named
   * before the wpilog's clock was first read starts before that reading, not at it.
   */
  static long interpolateFpgaTime(List<SystemTimeEntry> systemTimes, long wallClockMicros) {
    if (systemTimes.isEmpty()) {
      return 0;
    }

    // Find surrounding entries for interpolation
    SystemTimeEntry before = null;
    SystemTimeEntry after = null;

    for (SystemTimeEntry entry : systemTimes) {
      if (entry.wallClockMicros <= wallClockMicros) {
        before = entry;
      }
      if (entry.wallClockMicros >= wallClockMicros && after == null) {
        after = entry;
      }
    }

    if (before == null && after == null) {
      return systemTimes.get(0).fpgaMicros;
    }

    if (before == null) {
      return after.fpgaMicros - (after.wallClockMicros - wallClockMicros);
    }

    if (after == null) {
      return before.fpgaMicros + (wallClockMicros - before.wallClockMicros);
    }

    if (before == after) {
      return before.fpgaMicros;
    }

    // Linear interpolation
    double ratio = (double) (wallClockMicros - before.wallClockMicros) /
        (after.wallClockMicros - before.wallClockMicros);
    return before.fpgaMicros + (long) (ratio * (after.fpgaMicros - before.fpgaMicros));
  }

  /**
   * Performs cross-correlation between a signal pair.
   *
   * <p>The signals are resampled to uniform sample rate, then the lag between them
   * is searched in sample-space. The lag accounts for the different time domains
   * of wpilog (FPGA time) and revlog (CLOCK_MONOTONIC) by incorporating the
   * original start times and the coarse offset estimate.
   *
   * <p>A coarse search over integer sample lags finds the approximate alignment,
   * then parabolic interpolation refines to sub-sample accuracy.
   */
  private SignalPairResult crossCorrelate(SignalPair pair, long centerOffsetMicros) {
    return crossCorrelate(pair, centerOffsetMicros, sampleRateHz, searchWindowSamples,
        maxResampleSamples);
  }

  /**
   * Orders candidate pairs by their best correlation at 1/{@value #COARSE_DECIMATION} of the
   * sample rate over the same search range (the same data window, so the same pairs win),
   * highest first. Pairs that cannot be correlated sort last.
   */
  private List<SignalPair> rankByCoarseCorrelation(List<SignalPair> candidates,
      long centerOffsetMicros) {
    if (candidates.size() <= MAX_REFINED_PAIRS) return candidates;
    double rate = sampleRateHz / COARSE_DECIMATION;
    int window = Math.max(1, searchWindowSamples / COARSE_DECIMATION);
    int maxSamples = Math.max(10, maxResampleSamples / COARSE_DECIMATION);
    var scores = new java.util.IdentityHashMap<SignalPair, Double>();
    for (SignalPair pair : candidates) {
      scores.put(pair, crossCorrelate(pair, centerOffsetMicros, rate, window, maxSamples)
          .correlation());
    }
    return candidates.stream()
        .sorted(java.util.Comparator.comparingDouble((SignalPair p) -> -scores.get(p)))
        .toList();
  }

  private SignalPairResult crossCorrelate(SignalPair pair, long centerOffsetMicros,
      double sampleRateHz, int searchWindowSamples, int maxResampleSamples) {
    List<TimestampedValue> wpiValues = pair.wpilogValues();
    List<TimestampedValue> revValues = pair.revlogValues();

    if (wpiValues.size() < 10 || revValues.size() < 10) {
      return SignalPairResult.failed(pair.wpilogEntry(), pair.revlogSignal());
    }

    // Track original start times (these are in different time domains)
    double wpiStartTime = wpiValues.get(0).timestamp();
    double revStartTime = revValues.get(0).timestamp();

    // Resample both signals to uniform rate (0-indexed arrays)
    double[] wpilogSamples = resample(wpiValues, sampleRateHz, maxResampleSamples);
    double[] revlogSamples = resample(revValues, sampleRateHz, maxResampleSamples);

    if (wpilogSamples.length < 10 || revlogSamples.length < 10) {
      return SignalPairResult.failed(pair.wpilogEntry(), pair.revlogSignal());
    }

    // Too little data to establish an offset, or flat signals
    int minOverlapSamples = (int) Math.ceil(MIN_OVERLAP_SEC * sampleRateHz);
    if (wpilogSamples.length < minOverlapSamples || revlogSamples.length < minOverlapSamples
        || isFlat(wpilogSamples) || isFlat(revlogSamples)) {
      return SignalPairResult.failed(pair.wpilogEntry(), pair.revlogSignal());
    }

    // Compute center lag in samples.
    // wpilogSamples[i] = value at FPGA time (wpiStartTime + i / sampleRate)
    // revlogSamples[j] = value at revlog time (revStartTime + j / sampleRate)
    // Same physical moment when: wpiStartTime + i/sr = revStartTime + j/sr + offset
    // So: i = j + (revStartTime + offset - wpiStartTime) * sr
    // lag = i - j = (revStartTime + offset - wpiStartTime) * sr
    double centerOffsetSec = centerOffsetMicros / 1_000_000.0;
    int centerLag = (int) Math.round(
        (revStartTime + centerOffsetSec - wpiStartTime) * sampleRateHz);

    // If the center lag is so far from the data that the entire search window
    // falls outside both arrays, correlation would be meaningless — skip.
    int maxArrayLen = Math.max(wpilogSamples.length, revlogSamples.length);
    if (Math.abs(centerLag) > maxArrayLen + searchWindowSamples) {
      logger.warn("Center lag {} samples is far beyond array bounds ({}) — "
          + "skipping cross-correlation for {} <-> {}",
          centerLag, maxArrayLen, pair.wpilogEntry(), pair.revlogSignal());
      return SignalPairResult.failed(pair.wpilogEntry(), pair.revlogSignal());
    }

    // Coarse search: every sample lag in ±searchWindow
    int bestLag = centerLag;
    double bestCorr = -2;

    for (int lag = centerLag - searchWindowSamples;
         lag <= centerLag + searchWindowSamples;
         lag++) {
      double corr = computeCorrelation(wpilogSamples, revlogSamples, lag, minOverlapSamples);
      if (corr > bestCorr) {
        bestCorr = corr;
        bestLag = lag;
      }
    }

    // Check if the best lag is at the search window boundary, which suggests
    // the true peak may lie outside the window — reduce confidence.
    String warning = null;
    boolean atBoundary = (bestLag == centerLag - searchWindowSamples)
        || (bestLag == centerLag + searchWindowSamples);
    if (atBoundary) {
      warning = "Correlation peak at search window boundary — true offset may be outside search range";
      bestCorr *= 0.7;
    }

    // Parabolic interpolation for sub-sample accuracy
    double refinedLag = bestLag;
    if (bestLag > centerLag - searchWindowSamples
        && bestLag < centerLag + searchWindowSamples) {
      double corrMinus = computeCorrelation(wpilogSamples, revlogSamples, bestLag - 1,
          minOverlapSamples);
      double corrPlus = computeCorrelation(wpilogSamples, revlogSamples, bestLag + 1,
          minOverlapSamples);
      double denom = 2 * (2 * bestCorr - corrMinus - corrPlus);
      if (Math.abs(denom) > 1e-10) {
        // Clamp refinement to ±1 sample to prevent wild jumps when
        // the correlation landscape is flat near the peak
        double delta = (corrMinus - corrPlus) / denom;
        refinedLag = bestLag + Math.max(-1.0, Math.min(1.0, delta));
      }
    }

    // Convert lag back to offset in microseconds
    // lag = (revStartTime + offset - wpiStartTime) * sampleRate
    // offset = lag / sampleRate + wpiStartTime - revStartTime
    double offsetSec = refinedLag / sampleRateHz + wpiStartTime - revStartTime;
    long offsetMicros = Math.round(offsetSec * 1_000_000);

    return new SignalPairResult(
        pair.wpilogEntry(),
        pair.revlogSignal(),
        offsetMicros,
        bestCorr,
        Math.min(wpilogSamples.length, revlogSamples.length),
        warning
    );
  }

  /**
   * Finds the best window of a signal for cross-correlation by selecting the region
   * with the highest variance. This handles the common FRC case where a log starts
   * with minutes of the robot disabled (flat signal) before the interesting data.
   *
   * @param values The timestamped values
   * @param maxDurationSec The maximum window duration in seconds
   * @return The values trimmed to the high-variance window, or original if short enough
   */
  private List<TimestampedValue> findHighVarianceWindow(
      List<TimestampedValue> values, double maxDurationSec) {
    if (values.isEmpty()) return values;

    double totalDuration = values.get(values.size() - 1).timestamp() - values.get(0).timestamp();
    if (totalDuration <= maxDurationSec) {
      return values; // Already fits
    }

    // Slide a window and find the one with highest variance
    double bestVariance = -1;
    int bestStartIdx = 0;

    // Step size: jump by ~10% of window each time
    int stepSize = Math.max(1, values.size() / 50);

    // Carry endIdx forward between iterations — since startIdx only increases,
    // the window end can only move forward, so we never need to scan backward.
    int carriedEndIdx = 0;

    for (int startIdx = 0; startIdx < values.size(); startIdx += stepSize) {
      double windowStart = values.get(startIdx).timestamp();
      double windowEnd = windowStart + maxDurationSec;

      // Advance end index from where we left off (or from startIdx if it jumped ahead)
      int endIdx = Math.max(carriedEndIdx, startIdx);
      while (endIdx < values.size() && values.get(endIdx).timestamp() <= windowEnd) {
        endIdx++;
      }
      carriedEndIdx = endIdx;
      if (endIdx - startIdx < 10) continue;

      // Compute variance for this window (sample every 10th point for speed)
      double sum = 0;
      int count = 0;
      for (int i = startIdx; i < endIdx; i += 10) {
        sum += extractDouble(values.get(i).value());
        count++;
      }
      if (count == 0) continue;
      double mean = sum / count;

      double sumSq = 0;
      for (int i = startIdx; i < endIdx; i += 10) {
        double diff = extractDouble(values.get(i).value()) - mean;
        sumSq += diff * diff;
      }
      double variance = count > 1 ? sumSq / (count - 1) : 0;

      if (variance > bestVariance) {
        bestVariance = variance;
        bestStartIdx = startIdx;
      }
    }

    // Extract the best window
    double windowStart = values.get(bestStartIdx).timestamp();
    double windowEnd = windowStart + maxDurationSec;
    int endIdx = bestStartIdx;
    while (endIdx < values.size() && values.get(endIdx).timestamp() <= windowEnd) {
      endIdx++;
    }

    return values.subList(bestStartIdx, endIdx);
  }

  /**
   * Resamples timestamped values to a uniform sample rate.
   * If the signal is longer than the max resample window, the highest-variance
   * region is selected to maximize correlation quality.
   */
  private double[] resample(List<TimestampedValue> values, double sampleRateHz,
      int maxResampleSamples) {
    if (values.isEmpty()) {
      return new double[0];
    }

    // If signal is too long, find the most active window
    double maxDuration = maxResampleSamples / sampleRateHz;
    List<TimestampedValue> window = findHighVarianceWindow(values, maxDuration);

    double startTime = window.get(0).timestamp();
    double endTime = window.get(window.size() - 1).timestamp();
    double duration = endTime - startTime;

    int numSamples = Math.max(1, (int) (duration * sampleRateHz));
    numSamples = Math.min(numSamples, maxResampleSamples);
    double[] samples = new double[numSamples];

    double samplePeriod = 1.0 / sampleRateHz;
    int valueIndex = 0;

    for (int i = 0; i < numSamples; i++) {
      double targetTime = startTime + i * samplePeriod;

      // Find surrounding values for interpolation
      while (valueIndex < window.size() - 1 &&
             window.get(valueIndex + 1).timestamp() < targetTime) {
        valueIndex++;
      }

      if (valueIndex >= window.size() - 1) {
        samples[i] = extractDouble(window.get(window.size() - 1).value());
      } else {
        TimestampedValue v0 = window.get(valueIndex);
        TimestampedValue v1 = window.get(valueIndex + 1);

        double t0 = v0.timestamp();
        double t1 = v1.timestamp();
        double val0 = extractDouble(v0.value());
        double val1 = extractDouble(v1.value());

        if (t1 == t0) {
          samples[i] = val0;
        } else {
          double ratio = (targetTime - t0) / (t1 - t0);
          samples[i] = val0 + ratio * (val1 - val0);
        }
      }
    }

    return samples;
  }

  /**
   * Checks if a signal is effectively flat (constant value).
   *
   * @param samples The resampled signal
   * @return true if the standard deviation is below the configured flat signal threshold
   */
  private boolean isFlat(double[] samples) {
    if (samples.length <= 1) return true;
    double sum = 0;
    for (double s : samples) sum += s;
    double mean = sum / samples.length;
    double sumSq = 0;
    for (double s : samples) sumSq += (s - mean) * (s - mean);
    // Sample standard deviation (Bessel's correction: n-1)
    return Math.sqrt(sumSq / (samples.length - 1)) < flatSignalThreshold;
  }

  /**
   * Computes Pearson correlation coefficient for two signals at a given sample lag.
   *
   * <p>The lag represents: wpilog[lag + j] aligns with revlog[j].
   * Positive lag means wpilog is shifted right (starts later in sample space).
   *
   * <p>Uses the full Pearson formula on the overlapping window, computing
   * local mean and variance rather than assuming pre-normalized data.
   */
  private double computeCorrelation(double[] wpilog, double[] revlog, int lag,
      int minOverlapSamples) {
    // Find overlapping region
    int wpiStart = Math.max(0, lag);
    int revStart = Math.max(0, -lag);
    int overlapLength = Math.min(wpilog.length - wpiStart, revlog.length - revStart);
    int minLength = Math.min(wpilog.length, revlog.length);

    // Require at least 30% overlap for reliable correlation
    int minOverlap = Math.max(minOverlapSamples, (int) (minLength * 0.3));
    if (overlapLength < minOverlap) {
      return -1; // Not enough overlap
    }

    // Compute full Pearson correlation on the overlap window
    double meanX = 0, meanY = 0;
    for (int i = 0; i < overlapLength; i++) {
      meanX += wpilog[wpiStart + i];
      meanY += revlog[revStart + i];
    }
    meanX /= overlapLength;
    meanY /= overlapLength;

    double sumXY = 0, sumX2 = 0, sumY2 = 0;
    for (int i = 0; i < overlapLength; i++) {
      double dx = wpilog[wpiStart + i] - meanX;
      double dy = revlog[revStart + i] - meanY;
      sumXY += dx * dy;
      sumX2 += dx * dx;
      sumY2 += dy * dy;
    }

    double denom = Math.sqrt(sumX2 * sumY2);
    if (denom < 1e-10) return 0;
    return Math.max(-1.0, Math.min(1.0, sumXY / denom));
  }

  /**
   * Computes the median timestamp from a list of timestamped values.
   * Assumes the list is sorted by timestamp.
   */
  private static double medianTimestamp(List<TimestampedValue> values) {
    int n = values.size();
    if (n == 0) return 0.0;
    if (n % 2 == 1) {
      return values.get(n / 2).timestamp();
    }
    return (values.get(n / 2 - 1).timestamp() + values.get(n / 2).timestamp()) / 2.0;
  }

  /**
   * Extracts a double value from an Object.
   */
  private double extractDouble(Object value) {
    if (value instanceof Number num) {
      return num.doubleValue();
    }
    return 0.0;
  }

  /**
   * Computes consensus offset and confidence from signal pair results.
   */
  private SyncResult computeConsensus(List<SignalPairResult> results, long coarseOffset,
      boolean coarseEstimated) {
    // Filter to high-correlation pairs
    List<SignalPairResult> goodPairs = results.stream()
        .filter(r -> r.correlation() > highCorrelationThreshold)
        .toList();

    if (goodPairs.isEmpty()) {
      // Try with lower threshold
      goodPairs = results.stream()
          .filter(r -> r.correlation() > minUsefulCorrelation)
          .toList();

      if (goodPairs.isEmpty() && !coarseEstimated) {
        // No estimate at all: an offset of 0 would be a guess
        return new SyncResult(0L, 0.0, ConfidenceLevel.FAILED, results, SyncMethod.FAILED,
            "No signal pairs correlated, and the wpilog has no wall clock to estimate the "
                + "offset from. A REV log written by robot code on the same roboRIO is usually "
                + "on its FPGA clock (offset near 0): set_revlog_offset sets a known offset.");
      }
      if (goodPairs.isEmpty()) {
        return new SyncResult(
            coarseOffset,
            0.2,
            ConfidenceLevel.LOW,
            results,
            SyncMethod.SYSTEM_TIME_ONLY,
            "No signal pairs achieved strong correlation. Using the wall-clock estimate only: "
                + "the REV log's filename time against the wpilog's wall clock, off by as much as the roboRIO's clock was when the file was named (seconds or more).");
      }
    }

    // Pairs that disagree are not averaged: use the group of pairs that agree with each other
    // (within AGREEMENT_MICROS) with the most correlation behind it
    List<SignalPairResult> agreeing = largestAgreeingGroup(goodPairs);
    int setAside = goodPairs.size() - agreeing.size();
    goodPairs = agreeing;

    // Use median offset (robust to outliers)
    long medianOffset = computeMedianOffset(goodPairs);

    // Compute confidence; the level claims no more agreement than the pairs show
    double confidence = computeConfidence(goodPairs, medianOffset);
    double spreadMicros = computeOffsetStdDev(goodPairs, medianOffset);
    ConfidenceLevel level = capByAgreement(ConfidenceLevel.fromScore(confidence),
        goodPairs.size(), spreadMicros);
    if (level == ConfidenceLevel.MEDIUM) {
      confidence = Math.min(confidence, ConfidenceLevel.HIGH.getThreshold() - 0.01);
    } else if (level == ConfidenceLevel.LOW) {
      confidence = Math.min(confidence, ConfidenceLevel.MEDIUM.getThreshold() - 0.01);
    }

    String explanation = generateExplanation(goodPairs, medianOffset, level, spreadMicros);
    if (setAside > 0) {
      explanation += String.format(" %d other correlated pair(s) disagreed by more than %.0f ms "
          + "and were set aside (see signal_pairs).", setAside, AGREEMENT_MICROS / 1000.0);
    }

    return new SyncResult(
        medianOffset,
        confidence,
        level,
        results,
        SyncMethod.CROSS_CORRELATION,
        explanation
    );
  }

  /** Offsets within this of each other agree. */
  static final long AGREEMENT_MICROS = 50_000L;

  /**
   * The pairs whose offsets agree (within {@link #AGREEMENT_MICROS} of one pair's offset) with
   * the largest total correlation. A pair can correlate well at a wrong offset (two unrelated
   * signals that rise and fall together, or a mis-paired mechanism), so contradictory offsets
   * are not averaged.
   */
  static List<SignalPairResult> largestAgreeingGroup(List<SignalPairResult> pairs) {
    List<SignalPairResult> best = pairs;
    double bestWeight = -1;
    for (SignalPairResult anchor : pairs) {
      var group = pairs.stream()
          .filter(p -> Math.abs(p.estimatedOffsetMicros() - anchor.estimatedOffsetMicros())
              <= AGREEMENT_MICROS)
          .toList();
      double weight = group.stream().mapToDouble(SignalPairResult::correlation).sum();
      if (weight > bestWeight) {
        bestWeight = weight;
        best = group;
      }
    }
    return best;
  }

  /**
   * Computes median offset from signal pair results.
   */
  private long computeMedianOffset(List<SignalPairResult> pairs) {
    List<Long> offsets = pairs.stream()
        .map(SignalPairResult::estimatedOffsetMicros)
        .sorted()
        .toList();

    int mid = offsets.size() / 2;
    if (offsets.size() % 2 == 0) {
      // Overflow-safe median of two longs
      return offsets.get(mid - 1) + (offsets.get(mid) - offsets.get(mid - 1)) / 2;
    }
    return offsets.get(mid);
  }

  /**
   * Computes confidence score based on:
   * 1. Number of agreeing pairs
   * 2. Correlation strength
   * 3. Agreement between pairs (std dev of offsets)
   */
  private double computeConfidence(List<SignalPairResult> pairs, long medianOffset) {
    if (pairs.isEmpty()) {
      return 0.0;
    }

    // Factor 1: Average correlation (0-0.4)
    double avgCorr = pairs.stream()
        .mapToDouble(SignalPairResult::correlation)
        .average()
        .orElse(0);
    double corrScore = avgCorr * 0.4;

    // Factor 2: Number of good pairs (0-0.3)
    double pairScore = Math.min(pairs.size() / 3.0, 1.0) * 0.3;

    // Factor 3: Agreement between pairs (0-0.3)
    double stdDev = computeOffsetStdDev(pairs, medianOffset);
    // 5ms std dev = full score, 50ms = no score
    double agreementScore = Math.max(0, 1 - stdDev / 50_000) * 0.3;

    return Math.min(1.0, corrScore + pairScore + agreementScore);
  }

  /**
   * Computes standard deviation of offsets from median.
   */
  private double computeOffsetStdDev(List<SignalPairResult> pairs, long medianOffset) {
    if (pairs.size() < 2) {
      return 0;
    }

    double sumSq = 0;
    for (SignalPairResult pair : pairs) {
      double diff = pair.estimatedOffsetMicros() - medianOffset;
      sumSq += diff * diff;
    }

    return Math.sqrt(sumSq / (pairs.size() - 1));
  }

  /**
   * The most a level may claim given how well the pairs agree: each level's description names
   * an agreement ("within 5ms" for high, "minor disagreement" for medium), so one pair, or
   * pairs whose offsets spread more than that, cannot be rated above it.
   */
  static ConfidenceLevel capByAgreement(ConfidenceLevel level, int pairCount,
      double spreadMicros) {
    ConfidenceLevel cap = spreadMicros > 50_000 ? ConfidenceLevel.LOW
        : spreadMicros > 5_000 || pairCount < 2 ? ConfidenceLevel.MEDIUM
        : ConfidenceLevel.HIGH;
    return level.ordinal() < cap.ordinal() ? cap : level;
  }

  /**
   * Generates a human-readable explanation of the sync result.
   */
  private String generateExplanation(List<SignalPairResult> pairs, long offset,
      ConfidenceLevel level, double spreadMicros) {
    StringBuilder sb = new StringBuilder();

    sb.append(String.format("Synchronized using %d signal pair(s). ", pairs.size()));
    sb.append(String.format("Offset: %.1fms. ", offset / 1000.0));
    if (pairs.size() > 1) {
      long lo = pairs.stream().mapToLong(SignalPairResult::estimatedOffsetMicros).min().orElse(0);
      long hi = pairs.stream().mapToLong(SignalPairResult::estimatedOffsetMicros).max().orElse(0);
      sb.append(String.format("The pairs' offsets range from %.1f to %.1f ms (standard "
          + "deviation %.1f ms). ", lo / 1000.0, hi / 1000.0, spreadMicros / 1000.0));
    }

    sb.append(switch (level) {
      case HIGH -> "High confidence - multiple signals agree closely.";
      case MEDIUM -> pairs.size() < 2
          ? "Medium confidence - a single signal pair, with nothing to check it against."
          : "Medium confidence - reasonable signal agreement.";
      default -> "Low confidence - limited signal correlation or agreement.";
    });

    return sb.toString();
  }

  /**
   * Internal record for systemTime entry data.
   */
  record SystemTimeEntry(long fpgaMicros, long wallClockMicros) {}
}
