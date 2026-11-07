/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.store;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.triplehelix.wpilogmcp.store.StoreCatalog.StoredFile;
import org.triplehelix.wpilogmcp.sync.Combination;
import org.triplehelix.wpilogmcp.sync.ConfidenceLevel;
import org.triplehelix.wpilogmcp.sync.SyncMethod;
import org.triplehelix.wpilogmcp.sync.SyncResult;

/**
 * Which files of a session share a clock, by the offsets the store recorded when it matched them.
 * A matched file names what it was measured against: a file by its hash, or a capture recording,
 * since a capture still recording has no hash. Following those names leads each file to a
 * reference, and the files of one capture recording share the robot's clock. Two files that lead
 * to the same reference are aligned by exact arithmetic on their recorded measurements, never by a
 * new correlation, so a copy that lacks the reference (a laptop's mirror holds no captures) aligns
 * them as the store that measured them does.
 *
 * <p>A match recorded before matches named their recording names neither a file nor a recording.
 * It is taken to mean the session's recording only when the session shows exactly one: peer sync
 * can put recordings of different boots in one session, and a calendar cannot choose between them.
 */
public final class RecordedClocks {
  private RecordedClocks() {}

  private static final MathContext PRECISION = new MathContext(40, RoundingMode.HALF_EVEN);
  private static final BigDecimal NANOS = BigDecimal.ONE.scaleByPowerOfTen(9);

  /** The store whose captures a store's recordings name: a mirror's origin, otherwise the store itself. */
  public static String origin(StoreCatalog.Snapshot store) {
    return store.header().mirror() && store.header().origin() != null ? store.header().origin().storeId() : store.header().id();
  }

  /** One recorded measurement: {@code file}'s offset against {@code against}, as the store holds it. */
  public record Step(StoredFile file, String against, SyncResult measured) {}

  /**
   * How {@code from}'s time maps onto the anchor's, or why the recorded facts cannot say.
   *
   * @param source the measurements from {@code from} to where the two meet, applied forward
   * @param anchor the measurements from the anchor to where the two meet, applied in reverse
   * @param reference where they meet: a file, or a capture recording
   * @param refusal why the facts cannot align the two; null when they do
   */
  public record Alignment(StoredFile from, List<Step> source, List<Step> anchor, String reference, String refusal) {
    public Alignment {
      source = List.copyOf(source); anchor = List.copyOf(anchor);
    }
    public boolean aligned() { return refusal == null; }

    /** The steps as one mapping: a single forward measurement as recorded, otherwise their exact composition. */
    public SyncResult single() {
      if (!aligned()) return SyncResult.failed(refusal);
      if (anchor.isEmpty() && source.size() == 1) return source.get(0).measured();
      if (anchor.isEmpty() && source.isEmpty()) {
        return new SyncResult(0, 1.0, ConfidenceLevel.HIGH, List.of(), SyncMethod.COMBINED,
            "Both files are on one recording's clock", 0, 0, new Combination(reference, List.of()));
      }
      return combine(this);
    }

    /**
     * The mapping as a session read applies it: the forward measurements in order, as recorded, or
     * their composition when the anchor's own measurement has to be reversed.
     */
    public List<SyncResult> steps() {
      if (!aligned()) throw new IllegalStateException(refusal);
      return anchor.isEmpty() ? source.stream().map(Step::measured).toList() : List.of(single());
    }
  }

  /**
   * A REV log's alignment onto a WPILOG of its session: empty when their recorded offsets share no
   * reference, so the REV log is not that WPILOG's companion; a failed result with the reason when
   * they would share one but the evidence is ambiguous or invalid, so the REV log stays readable on
   * its own clock.
   */
  public static Optional<SyncResult> revAlignment(StoredFile rev, StoredFile anchor, List<StoredFile> files, String origin) {
    if (!"revlog".equals(rev.file().kind()) || !"wpilog".equals(anchor.file().kind())) return Optional.empty();
    try {
      return between(rev, anchor, files, origin).map(Alignment::single);
    } catch (IOException e) {
      return Optional.of(SyncResult.failed(e.getMessage()));
    }
  }

  /**
   * How {@code source} maps onto {@code anchor}, two files of one session: empty when their
   * recorded offsets lead to different references; an alignment, or the reason there is none.
   *
   * @param files the session's files, open captures included (others are ignored)
   * @param origin the store whose captures the recordings name ({@link #origin})
   * @throws IOException when the recorded evidence that would align them is invalid
   */
  public static Optional<Alignment> between(StoredFile source, StoredFile anchor, List<StoredFile> files,
      String origin) throws IOException {
    if (source.session() == null || anchor.session() == null || !source.manifestPath().equals(anchor.manifestPath())) {
      return Optional.empty();
    }
    var session = new Context(anchor, files, origin);
    if (source.path().equals(anchor.path())) {
      return Optional.of(new Alignment(source, List.of(), List.of(), session.describe(anchor), null));
    }
    var from = session.chain(source); var to = session.chain(anchor);
    if (from.refusal() != null || to.refusal() != null) {
      // An unnamed reference was some capture recording of this session: it may be any file on one.
      var other = from.refusal() != null ? to : from;
      boolean related = other.refusal() != null || other.clock().recording() != null;
      return related ? Optional.of(new Alignment(source, from.steps(), to.steps(), null,
          from.refusal() != null ? from.refusal() : to.refusal())) : Optional.empty();
    }
    if (!from.clock().same(to.clock())) return Optional.empty();
    var forward = new ArrayList<>(from.steps()); var reverse = new ArrayList<>(to.steps());
    String reference = null;
    // A measurement both chains share cancels: two files measured against X meet at X.
    while (!forward.isEmpty() && !reverse.isEmpty()
        && forward.get(forward.size() - 1).file().path().equals(reverse.get(reverse.size() - 1).file().path())) {
      var shared = forward.remove(forward.size() - 1); reverse.remove(reverse.size() - 1);
      reference = session.describe(shared.file());
    }
    if (reverse.isEmpty() && reference == null) reference = session.describe(anchor);
    if (forward.isEmpty() && reference == null) reference = session.describe(source);
    if (reference == null) reference = session.describe(from.clock(), to.clock());
    for (var step : forward) check(step);
    for (var step : reverse) check(step);
    return Optional.of(new Alignment(source, forward, reverse, reference, null));
  }

  private static void check(Step step) throws IOException {
    var measured = step.measured();
    if (!measured.isSuccessful()) throw new IOException(measured.explanation());
    if (measured.driftRateNanosPerSec() <= -1_000_000_000.) {
      throw new IOException("Recorded clock is not increasing in " + step.file().manifestPath() + " for " + step.file().file().path());
    }
  }

  /** A reference clock: a file by hash, a capture recording, or both. */
  private record Clock(String sha256, CaptureRecording recording) {
    boolean same(Clock other) {
      return sha256 != null && sha256.equals(other.sha256) || recording != null && recording.equals(other.recording);
    }
  }
  private record Chain(List<Step> steps, Clock clock, String refusal) {}

  /** One session's files and the facts that name its recordings. */
  private static final class Context {
    private final Path manifest;
    private final StoreManifest.Session session;
    private final List<StoredFile> files;
    private final String origin;

    Context(StoredFile anchor, List<StoredFile> all, String origin) {
      this.manifest = anchor.manifestPath(); this.session = anchor.session(); this.origin = origin;
      this.files = all.stream().filter(f -> f.manifestPath().equals(manifest) && f.session() != null).toList();
    }

    CaptureRecording recording(StoredFile file) {
      return file.file().provenance() == null ? null : CaptureRecording.of(file.file().provenance(), origin).orElse(null);
    }
    StoredFile byHash(String hash) {
      return files.stream().filter(f -> hash.equals(f.file().sha256())).findFirst().orElse(null);
    }
    StoreManifest.ClockRecord record(String hash) {
      return session.clocks().stream().filter(c -> hash.equals(c.captureSha256())).findFirst().orElse(null);
    }

    /** The measurements from {@code file} to the clock they lead to, or why that clock is unknown. */
    Chain chain(StoredFile file) throws IOException {
      var steps = new ArrayList<Step>(); var visited = new HashSet<Path>(); var at = file;
      while (true) {
        var match = at.file().matching();
        if (match == null) return new Chain(steps, new Clock(at.file().sha256(), recording(at)), null);
        if (!visited.add(at.path())) throw new IOException("Cycle in recorded alignment in " + manifest + " at " + at.file().path());
        var named = match.recording();
        if (named != null && (blank(named.collectingStoreId()) || blank(named.startedAt()))) {
          throw new IOException("Invalid recorded recording in " + manifest + " for " + at.file().path());
        }
        var measured = StoreCatalog.recordedAlignment(at);
        String hash = match.wpilogSha256();
        var next = hash == null ? null : byHash(hash);
        if (next != null) {
          var held = recording(next);
          if (named != null && held != null && !named.equals(held)) {
            throw new IOException("Recorded alignment of " + at.file().path() + " in " + manifest
                + " names a recording its reference file does not belong to");
          }
          steps.add(new Step(at, describe(next), measured)); at = next; continue;
        }
        Clock clock;
        if (hash != null) {
          var record = record(hash);
          clock = new Clock(hash, named != null ? named : record == null ? null : record.recording());
        } else if (named != null) clock = new Clock(null, named);
        else {
          var single = singleRecording();
          if (single.refusal() != null) {
            steps.add(new Step(at, "a capture still recording", measured));
            return new Chain(steps, null, at.file().path() + " was measured against a capture still recording, before matches "
                + "named their recording, and " + single.refusal() + ", so which robot clock it is on is unknown");
          }
          clock = new Clock(null, single.recording());
        }
        steps.add(new Step(at, describe(clock, clock), measured));
        return new Chain(steps, clock, null);
      }
    }

    private record Single(CaptureRecording recording, String refusal) {}
    /** The session's only recording, by every fact that names one, or why there is not exactly one. */
    Single singleRecording() {
      var known = new HashSet<CaptureRecording>(); int unknown = 0;
      for (var file : files) {
        if (file.file().provenance() != null && file.file().provenance().captured()) {
          var named = recording(file);
          if (named == null) unknown++; else known.add(named);
        }
        var match = file.file().matching();
        if (match != null && match.recording() != null) known.add(match.recording());
      }
      var listed = session.openCapture();
      // The open capture is among the files when the caller listed open captures; count it once.
      if (listed != null && files.stream().noneMatch(f -> f.file().path().equals(listed.path()))) {
        var open = CaptureRecording.of(listed.provenance(), origin);
        if (open.isEmpty()) unknown++; else known.add(open.get());
      }
      for (var record : session.clocks()) {
        if (record.recording() != null) known.add(record.recording());
        else if (record.captureSha256() == null || byHash(record.captureSha256()) == null) unknown++;
      }
      if (unknown == 0 && known.size() == 1) return new Single(known.iterator().next(), null);
      String held = known.isEmpty() ? "this session names no recording"
          : "this session holds " + known.size() + (known.size() == 1 ? " recording" : " recordings");
      return new Single(null, unknown == 0 ? held : held + " and " + unknown + (unknown == 1 ? " capture" : " captures")
          + " whose recording is unknown");
    }

    String describe(StoredFile file) { return file.file().path(); }
    /** Where two chains meet: the file they share, else the recording they share. */
    String describe(Clock left, Clock right) {
      if (left.sha256() != null && left.sha256().equals(right.sha256())) {
        var held = byHash(left.sha256());
        if (held != null) return describe(held);
        var record = record(left.sha256());
        // A manifest is input: a hash shorter than a real one is shown whole, not cut.
        String hash = left.sha256().length() > 12 ? left.sha256().substring(0, 12) : left.sha256();
        return record != null ? record.capture() + " (not held here; sha256 " + hash + ")"
            : "the file with sha256 " + hash + " (not held here)";
      }
      var recording = left.recording() != null ? left.recording() : right.recording();
      return "the capture recording started " + recording.startedAt() + " by store " + recording.collectingStoreId();
    }
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }

  /** Each measurement maps t to a*t + b, in seconds, exactly as {@link SyncResult} applies it. */
  private record Affine(BigDecimal a, BigDecimal b) {
    static Affine of(SyncResult step) {
      boolean drifts = step.driftRateNanosPerSec() != 0.0 && step.referenceTimeSec() != 0.0;
      var k = drifts ? new BigDecimal(step.driftRateNanosPerSec()).divide(NANOS) : BigDecimal.ZERO;
      var offset = BigDecimal.valueOf(step.offsetMicros()).scaleByPowerOfTen(-6);
      return new Affine(BigDecimal.ONE.add(k), drifts ? offset.subtract(new BigDecimal(step.referenceTimeSec()).multiply(k)) : offset);
    }
    Affine then(Affine next) { return new Affine(next.a.multiply(a), next.a.multiply(b).add(next.b)); }
    Affine thenInverse(Affine next) {
      return new Affine(a.divide(next.a, PRECISION), b.subtract(next.b).divide(next.a, PRECISION));
    }
  }

  private static SyncResult combine(Alignment alignment) {
    try {
      return combined(alignment);
    } catch (ArithmeticException e) {
      return SyncResult.failed("The offsets recorded against " + alignment.reference() + " overflow when combined");
    }
  }
  private static SyncResult combined(Alignment alignment) {
    var map = new Affine(BigDecimal.ONE, BigDecimal.ZERO);
    var measurements = new ArrayList<Combination.Measurement>();
    double confidence = 1.0; var level = ConfidenceLevel.HIGH;
    for (var step : alignment.source()) {
      map = map.then(Affine.of(step.measured())); measurements.add(measurement(step, false));
    }
    for (int i = alignment.anchor().size() - 1; i >= 0; i--) {
      var step = alignment.anchor().get(i);
      map = map.thenInverse(Affine.of(step.measured())); measurements.add(measurement(step, true));
    }
    for (var m : measurements) {
      confidence = Math.min(confidence, m.confidence());
      if (m.confidenceLevel().ordinal() > level.ordinal()) level = m.confidenceLevel();
    }
    double drift = map.a().subtract(BigDecimal.ONE).multiply(NANOS).doubleValue();
    if (!Double.isFinite(drift) || drift <= -1_000_000_000.) throw new ArithmeticException("drift");
    double reference = drift == 0.0 ? 0.0 : reference(alignment);
    var at = new BigDecimal(reference);
    long offset = map.a().multiply(at).add(map.b()).subtract(at).scaleByPowerOfTen(6)
        .setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    var combination = new Combination(alignment.reference(), measurements);
    return new SyncResult(offset, confidence, level, List.of(), SyncMethod.COMBINED,
        explanation(combination), drift, reference, combination);
  }

  /** Where the combined offset is stated: the source's own measured reference, else the middle of its span. */
  private static double reference(Alignment alignment) {
    if (!alignment.source().isEmpty()) {
      var first = alignment.source().get(0).measured();
      if (first.driftRateNanosPerSec() != 0.0 && first.referenceTimeSec() != 0.0) return first.referenceTimeSec();
    }
    double middle = (alignment.from().file().minTimestampSec() + alignment.from().file().maxTimestampSec()) / 2;
    return Double.isFinite(middle) && middle != 0.0 ? middle : 1.0;
  }

  private static Combination.Measurement measurement(Step step, boolean reversed) {
    var m = step.measured();
    return new Combination.Measurement(step.file().file().path(), step.against(), reversed, m.method(), m.confidence(),
        m.confidenceLevel(), m.offsetMicros(), m.driftRateNanosPerSec(), m.referenceTimeSec());
  }

  private static String explanation(Combination combination) {
    var parts = new ArrayList<String>();
    for (var m : combination.measurements()) {
      parts.add(String.format(Locale.ROOT, "%s measured against %s%s (%s, %s confidence, offset %+.3f ms%s)", m.file(), m.against(),
          m.reversed() ? ", applied in reverse" : "", m.method().getDescription().toLowerCase(Locale.ROOT),
          m.confidenceLevel().getLabel(), m.offsetMicros() / 1000.0,
          m.driftRateNanosPerSec() == 0.0 ? "" : String.format(Locale.ROOT, ", drift %.1f ppm", m.driftRateNanosPerSec() / 1000.0)));
    }
    double bound = combination.maxErrorMs();
    return "Combined from offsets recorded against " + combination.reference() + ": " + String.join("; ", parts)
        + ". No correlation between these two files was run. The alignment is no more confident than its least "
        + "confident measurement, and " + (Double.isFinite(bound) ? "its error is at most about "
        + Combination.milliseconds(bound) + " ms, the sum of the measurements' bounds."
        : "its accuracy is unknown, since one measurement's accuracy cannot be judged.");
  }
}
