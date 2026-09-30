/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.ToolRegistry;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;

/**
 * Pose primitives (review section 5.6): the difference of two pose streams, and the change in a
 * pose beyond what odometry predicts (vision corrections, wheel slip, resets).
 *
 * @since 0.9.0
 */
public final class PoseTools {

  private PoseTools() {}

  public static void registerAll(ToolRegistry registry) {
    registry.registerTool(new ComparePosesTool());
    registry.registerTool(new PoseCorrectionsTool());
  }

  // ==================== pose streams ====================

  /** A planar pose at a log time: position in meters, heading in radians. */
  record PoseSample(double t, double x, double y, double heading) {}

  /** A pose stream in time order, and how many records could not be read as a pose. */
  record PoseStream(String entry, List<PoseSample> samples, double[] times, int unreadable) {

    /**
     * The pose at {@code t}: linear between the samples around it (the heading along the
     * shortest arc), or the sample in force ({@code hold}); empty before the first sample, after
     * the last (linear), or across a gap longer than {@code maxGapSec}.
     */
    Optional<PoseSample> at(double t, boolean hold, double maxGapSec) {
      int i = lastAtOrBefore(t);
      if (i < 0) return Optional.empty();
      var a = samples.get(i);
      if (a.t() == t) return Optional.of(a);
      if (hold) return t - a.t() <= maxGapSec ? Optional.of(a) : Optional.empty();
      if (i + 1 >= samples.size()) return Optional.empty();
      var b = samples.get(i + 1);
      if (b.t() - a.t() > maxGapSec) return Optional.empty();
      double f = (t - a.t()) / (b.t() - a.t());
      return Optional.of(new PoseSample(t, a.x() + f * (b.x() - a.x()),
          a.y() + f * (b.y() - a.y()), a.heading() + f * wrap(b.heading() - a.heading())));
    }

    private int lastAtOrBefore(double t) {
      int lo = 0;
      int hi = times.length;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (times[mid] <= t) lo = mid + 1;
        else hi = mid;
      }
      return lo - 1;
    }
  }

  /** The heading of a decoded Pose2d (rotation.value) or Pose3d (yaw), in radians. */
  static Double heading(Object pose) {
    return StructFields.number(pose, "rotation.value", "rotation._derived.yaw");
  }

  /** Reads a struct:Pose2d or struct:Pose3d entry (Pose3d projected on the floor). */
  static PoseStream poses(LogData log, String entry) {
    var values = log.values().get(entry);
    var samples = new ArrayList<PoseSample>();
    int unreadable = 0;
    double last = Double.NEGATIVE_INFINITY;
    for (var tv : values == null ? List.<TimestampedValue>of() : values) {
      Double x = StructFields.poseX(tv.value());
      Double y = StructFields.poseY(tv.value());
      Double h = heading(tv.value());
      if (x == null || y == null || h == null || !Double.isFinite(tv.timestamp())
          || tv.timestamp() < last) {
        unreadable++;
        continue;
      }
      if (tv.timestamp() == last) {
        samples.set(samples.size() - 1, new PoseSample(tv.timestamp(), x, y, h));
        continue;
      }
      samples.add(new PoseSample(tv.timestamp(), x, y, h));
      last = tv.timestamp();
    }
    return new PoseStream(entry, samples,
        samples.stream().mapToDouble(PoseSample::t).toArray(), unreadable);
  }

  static boolean isPose(String type) {
    return type.equals("struct:Pose2d") || type.equals("struct:Pose3d");
  }

  /** Checks that a named entry exists and holds poses. */
  static void requirePose(LogData log, String name, String param) {
    var info = log.entries().get(name);
    if (info == null) {
      throw new IllegalArgumentException(param + " " + name + " is not in this log. Use "
          + "search_entries (type 'Pose2d') to find it.");
    }
    if (!isPose(info.type())) {
      throw new IllegalArgumentException(param + " " + name + " is " + info.type()
          + "; it must be struct:Pose2d or struct:Pose3d.");
    }
  }

  /** An angle in radians wrapped to [-pi, pi). */
  static double wrap(double radians) {
    return NumericSignal.wrapToHalfTurn(radians, 2 * Math.PI);
  }

  /** Whether two times fall in the same window of the scope (any time, for 'all'). */
  static boolean sameWindow(TimeScope scope, double a, double b) {
    if (scope.isAll()) return true;
    return scope.windows().stream().anyMatch(w -> w.contains(a) && w.contains(b));
  }

  /** Distribution of values: count, mean, median, p95, max (and optional extras). */
  static JsonObject distribution(double[] values, boolean withP99) {
    var o = new JsonObject();
    o.addProperty("count", values.length);
    if (values.length == 0) return o;
    var sorted = values.clone();
    Arrays.sort(sorted);
    o.addProperty("mean", Arrays.stream(values).average().orElse(0));
    o.addProperty("median", percentile(sorted, 0.5));
    o.addProperty("p95", percentile(sorted, 0.95));
    if (withP99) o.addProperty("p99", percentile(sorted, 0.99));
    o.addProperty("max", sorted[sorted.length - 1]);
    return o;
  }

  /** Signed-component statistics: mean, std_dev (Bessel), p5, p95. */
  static JsonObject components(double[] values) {
    var o = new JsonObject();
    o.addProperty("count", values.length);
    if (values.length == 0) return o;
    var sorted = values.clone();
    Arrays.sort(sorted);
    double mean = Arrays.stream(values).average().orElse(0);
    double ss = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum();
    o.addProperty("mean", mean);
    o.addProperty("std_dev", values.length > 1 ? Math.sqrt(ss / (values.length - 1)) : 0.0);
    o.addProperty("p5", percentile(sorted, 0.05));
    o.addProperty("p95", percentile(sorted, 0.95));
    return o;
  }

  // ==================== compare_poses ====================

  static class ComparePosesTool extends LogRequiringTool {
    static final int TOP = 5;

    @Override
    public String name() { return "compare_poses"; }

    @Override
    public String description() {
      return "The difference between two pose streams (struct:Pose2d or Pose3d, the latter "
          + "projected on the floor), sampled at the records of pose_entry with "
          + "reference_entry interpolated there (linear, heading along the shortest arc; "
          + "'previous' for a reference logged when it changes; no value across a gap longer "
          + "than max_gap_sec). pose minus reference: distance_m (count, mean, median, p95, "
          + "max, rmse), heading_difference_rad (signed mean; median, p95, max of its size), "
          + "and components: frame 'field' gives dx_m and dy_m in field coordinates; frame "
          + "'reference' gives along_m (positive: pose ahead of the reference along its "
          + "heading) and cross_m (positive: pose to its left), as a path-following error "
          + "is usually read. largest lists the times of the largest distances. Uses: path "
          + "following (setpoint as reference), two pose estimators, a pose against a "
          + "camera's estimate. For each camera observation at its own timestamp, use "
          + "analyze_vision. pose_entry defaults to the robot pose (a conventional name, else "
          + "the only Pose2d); several others are listed to confirm, not guessed."
          + StatisticsTools.SCOPE_HELP + GUIDANCE_UNIVERSAL;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("reference_entry", "string", "The pose to measure against "
              + "(struct:Pose2d or Pose3d), e.g. a path setpoint", true)
          .addProperty("pose_entry", "string", "The pose measured (struct:Pose2d or Pose3d); "
              + "default: the robot pose", false)
          .addProperty("frame", "string", "'field' (default: dx_m, dy_m) or 'reference' "
              + "(along_m, cross_m in the reference's heading)", false)
          .addProperty("interpolation", "string", "'linear' (default) or 'previous' for the "
              + "reference", false)
          .addNumberProperty("max_gap_sec", "Longest reference gap to interpolate across "
              + "(default 0.25)", false, 0.25)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(),
              false)
          .build();
    }

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var referenceName = getOptString(arguments, "reference_entry", null);
      if (referenceName == null) {
        throw new IllegalArgumentException("reference_entry is required: the pose to measure "
            + "against (e.g. a path setpoint)");
      }
      requirePose(log, referenceName, "reference_entry");
      var poseRole = SignalResolver.robotPose(log, getOptString(arguments, "pose_entry", null));
      if (poseRole.chosen().isEmpty()) {
        return ResponseBuilder.noMatch(SignalResolver.unresolvedReason(poseRole, "pose_entry"))
            .addData("robot_pose", poseRole.toJson(10)).build();
      }
      var frame = getOptString(arguments, "frame", "field").toLowerCase(Locale.ROOT);
      if (!List.of("field", "reference").contains(frame)) {
        throw new IllegalArgumentException("frame must be 'field' or 'reference'");
      }
      var interpolation = getOptString(arguments, "interpolation", "linear")
          .toLowerCase(Locale.ROOT);
      if (!List.of("linear", "previous").contains(interpolation)) {
        throw new IllegalArgumentException("interpolation must be 'linear' or 'previous'");
      }
      double maxGap = getOptDouble(arguments, "max_gap_sec", 0.25);
      if (!(maxGap > 0)) throw new IllegalArgumentException("max_gap_sec must be positive");
      var scope = TimeScope.fromArguments(log, null, arguments);

      var pose = poses(log, poseRole.chosen().get());
      var reference = poses(log, referenceName);
      boolean hold = interpolation.equals("previous");

      var distance = new ArrayList<Double>();
      var headingDiff = new ArrayList<Double>();
      var first = new ArrayList<Double>();
      var second = new ArrayList<Double>();
      var largest = new ArrayList<double[]>();
      int unaligned = 0;
      for (var p : pose.samples()) {
        if (!scope.contains(p.t())) continue;
        var r = reference.at(p.t(), hold, maxGap);
        if (r.isEmpty()) {
          unaligned++;
          continue;
        }
        double dx = p.x() - r.get().x();
        double dy = p.y() - r.get().y();
        double d = Math.hypot(dx, dy);
        distance.add(d);
        headingDiff.add(wrap(p.heading() - r.get().heading()));
        if (frame.equals("field")) {
          first.add(dx);
          second.add(dy);
        } else {
          double c = Math.cos(r.get().heading());
          double s = Math.sin(r.get().heading());
          first.add(dx * c + dy * s);
          second.add(-dx * s + dy * c);
        }
        largest.add(new double[] {p.t(), d});
      }

      var builder = success()
          .addProperty("pose_entry", pose.entry())
          .addProperty("reference_entry", reference.entry())
          .addProperty("frame", frame)
          .addProperty("interpolation", interpolation)
          .addProperty("count", distance.size())
          .addProperty("unaligned", unaligned)
          .addInput("pose", pose.entry())
          .addInput("reference", reference.entry())
          .addInputScope(scope);
      if (!poseRole.chosen().get().equals(getOptString(arguments, "pose_entry", null))) {
        builder.addData("robot_pose", poseRole.toJson(10));
      }
      if (distance.isEmpty()) {
        return builder.status(ResultContract.Status.NO_MATCH)
            .addProperty("reason", "No record of " + pose.entry() + " in scope had a "
                + "reference value (" + unaligned + " without one: before or after "
                + reference.entry() + "'s samples, or across a gap longer than max_gap_sec).")
            .build();
      }
      double[] d = distance.stream().mapToDouble(Double::doubleValue).toArray();
      var distanceJson = distribution(d, false);
      distanceJson.addProperty("rmse", Math.sqrt(Arrays.stream(d).map(v -> v * v).average()
          .orElse(0)));
      builder.addData("distance_m", distanceJson);
      double[] h = headingDiff.stream().mapToDouble(Double::doubleValue).toArray();
      var headingJson = distribution(Arrays.stream(h).map(Math::abs).toArray(), false);
      headingJson.remove("mean");
      headingJson.addProperty("mean_signed", Arrays.stream(h).average().orElse(0));
      builder.addData("heading_difference_rad", headingJson);
      var names = frame.equals("field") ? List.of("dx_m", "dy_m") : List.of("along_m", "cross_m");
      builder.addData(names.get(0), components(first.stream().mapToDouble(Double::doubleValue)
          .toArray()));
      builder.addData(names.get(1), components(second.stream().mapToDouble(Double::doubleValue)
          .toArray()));
      largest.sort((a, b) -> Double.compare(b[1], a[1]));
      var top = new JsonArray();
      largest.stream().limit(TOP).forEach(e -> {
        var o = new JsonObject();
        o.addProperty("timestamp_sec", e[0]);
        o.addProperty("distance_m", e[1]);
        top.add(o);
      });
      builder.addLimitedList("largest", top, largest.size(), TOP);
      if (pose.unreadable() + reference.unreadable() > 0) {
        builder.addWarning((pose.unreadable() + reference.unreadable()) + " record(s) could not "
            + "be read as a pose (no translation or heading, or out of time order) and were "
            + "skipped.");
      }
      var quality = DataQuality.fromSegments(scope.split(log.values().get(pose.entry())));
      return builder.addDataQuality(quality)
          .addDirectives(AnalysisDirectives.fromQuality(quality).addSingleMatchCaveat())
          .build();
    }
  }

  // ==================== pose_corrections ====================

  static class PoseCorrectionsTool extends LogRequiringTool {
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;

    @Override
    public String name() { return "pose_corrections"; }

    @Override
    public String description() {
      return "How much a pose changed beyond what odometry predicts: for each pair of "
          + "consecutive pose_entry records (in scope, at most max_interval_sec apart), its "
          + "change minus the predicted change, where the prediction is the change of "
          + "odometry_pose_entry (a pose from wheel odometry alone, rotated into the pose's "
          + "frame), or else chassis_speeds_entry integrated over the interval (robot-relative "
          + "by default, rotated by the pose's heading; speeds_frame 'field' for field-relative "
          + "speeds; odometry.frame_check gives the median residual read either way, and a "
          + "warning says when the other frame fits better). Returns residual_translation_m "
          + "(count, mean, median, p95, p99, max) and residual_heading_rad (sizes), and the "
          + "corrections: intervals whose residual is at "
          + "least threshold_m (or heading_threshold_rad), in time order with dx_m, dy_m, "
          + "translation_m, heading_rad, and speed_mps, and correction_count, "
          + "total_translation_m, and correction_interval_sec (n, min, median, p95, max: the "
          + "cadence of corrections, e.g. vision updates). A correction within 0.5 s of an "
          + "enable has near_enable_sec (odometry is often reset there). A residual is not "
          + "by itself a vision correction: wheel slip, collisions, a pose reset, and timing "
          + "differences between the entries also make one; compare with the vision "
          + "entries (analyze_vision) before attributing it. pose_entry defaults to the robot "
          + "pose and chassis_speeds_entry to the measured chassis speeds (conventional "
          + "names or the only candidate; others are listed to confirm, not guessed)."
          + StatisticsTools.SCOPE_HELP + GUIDANCE_UNIVERSAL + GUIDANCE_MATCH_ANALYSIS;
    }

    @Override
    protected JsonObject toolSchema() {
      return new SchemaBuilder()
          .addProperty("pose_entry", "string", "The pose (struct:Pose2d or Pose3d), e.g. a "
              + "pose estimator's output; default: the robot pose", false)
          .addProperty("odometry_pose_entry", "string", "A pose from wheel odometry alone; "
              + "when given, its change is the prediction", false)
          .addProperty("chassis_speeds_entry", "string", "struct:ChassisSpeeds to integrate "
              + "when no odometry pose is given; default: the measured chassis speeds", false)
          .addProperty("speeds_frame", "string", "'robot' (default, as kinematics produce "
              + "them) or 'field'", false)
          .addNumberProperty("threshold_m", "Residual translation that counts as a correction "
              + "(default 0.05)", false, 0.05)
          .addNumberProperty("heading_threshold_rad", "Residual heading that counts as a "
              + "correction (default: none)", false, null)
          .addNumberProperty("max_interval_sec", "Longest interval between pose records to "
              + "compare (default 0.1)", false, 0.1)
          .addNumberProperty("start_time", "Start timestamp (s)", false, null)
          .addNumberProperty("end_time", "End timestamp (s)", false, null)
          .addProperty("scope", "string", TimeScope.SCOPE_DESCRIPTION, false)
          .addArrayProperty("windows", TimeScope.WINDOWS_DESCRIPTION, TimeScope.windowItemSchema(),
              false)
          .addIntegerProperty("limit", "Maximum corrections listed (max 500)", false,
              DEFAULT_LIMIT)
          .build();
    }

    /** A chassis speed sample: robot- or field-relative, m/s and rad/s. */
    record Speeds(double t, double vx, double vy, double omega) {}

    /** The odometry prediction for one interval: displacement and heading change. */
    record Prediction(double dx, double dy, double dHeading, double speed) {}

    @Override
    protected JsonElement executeWithLog(LogData log, JsonObject arguments) throws Exception {
      var poseRole = SignalResolver.robotPose(log, getOptString(arguments, "pose_entry", null));
      if (poseRole.chosen().isEmpty()) {
        return ResponseBuilder.noMatch(SignalResolver.unresolvedReason(poseRole, "pose_entry"))
            .addData("robot_pose", poseRole.toJson(10)).build();
      }
      var odometryName = getOptString(arguments, "odometry_pose_entry", null);
      SignalResolver.Resolution speedsRole = null;
      if (odometryName != null) {
        requirePose(log, odometryName, "odometry_pose_entry");
      } else {
        var explicitSpeeds = getOptString(arguments, "chassis_speeds_entry", null);
        speedsRole = explicitSpeeds != null
            ? SignalResolver.explicit(log, SignalResolver.Role.CHASSIS_SPEEDS_MEASURED,
                explicitSpeeds, "chassis_speeds_entry",
                t -> t.equals("struct:ChassisSpeeds"), "struct:ChassisSpeeds")
            : SignalResolver.resolve(log, SignalResolver.Role.CHASSIS_SPEEDS_MEASURED);
        if (speedsRole.chosen().isEmpty()) {
          return ResponseBuilder.noMatch("No odometry to predict the pose's change: "
                  + SignalResolver.unresolvedReason(speedsRole, "chassis_speeds_entry")
                  + " Or pass odometry_pose_entry (a pose from wheel odometry alone).")
              .addData("chassis_speeds", speedsRole.toJson(10)).build();
        }
      }
      var speedsFrame = getOptString(arguments, "speeds_frame", "robot").toLowerCase(Locale.ROOT);
      if (!List.of("robot", "field").contains(speedsFrame)) {
        throw new IllegalArgumentException("speeds_frame must be 'robot' or 'field'");
      }
      double threshold = getOptDouble(arguments, "threshold_m", 0.05);
      Double headingThreshold = getOptDouble(arguments, "heading_threshold_rad");
      double maxInterval = getOptDouble(arguments, "max_interval_sec", 0.1);
      if (!(threshold > 0) || !(maxInterval > 0)
          || (headingThreshold != null && !(headingThreshold > 0))) {
        throw new IllegalArgumentException("threshold_m, heading_threshold_rad, and "
            + "max_interval_sec must be positive");
      }
      int limit = Math.min(MAX_LIMIT, Math.max(1, getOptInt(arguments, "limit", DEFAULT_LIMIT)));
      var scope = TimeScope.fromArguments(log, null, arguments);

      var pose = poses(log, poseRole.chosen().get());
      PoseStream odometry = odometryName != null ? poses(log, odometryName) : null;
      List<Speeds> speeds = speedsRole != null ? speeds(log, speedsRole.chosen().get()) : null;
      double[] speedTimes = speeds != null
          ? speeds.stream().mapToDouble(Speeds::t).toArray() : null;
      var enables = MatchTimeline.of(log).enabledSegments().stream()
          .map(MatchTimeline.Segment::start).toList();

      var translation = new ArrayList<Double>();
      var otherFrame = new ArrayList<Double>();
      var heading = new ArrayList<Double>();
      var corrections = new ArrayList<JsonObject>();
      var correctionTimes = new ArrayList<Double>();
      double total = 0;
      int tooLong = 0;
      int uncovered = 0;
      var samples = pose.samples();
      for (int i = 1; i < samples.size(); i++) {
        var a = samples.get(i - 1);
        var b = samples.get(i);
        if (!scope.contains(a.t()) || !scope.contains(b.t()) || !sameWindow(scope, a.t(), b.t())) {
          continue;
        }
        if (b.t() - a.t() > maxInterval) {
          tooLong++;
          continue;
        }
        var predicted = odometry != null ? fromOdometry(odometry, a, b, maxInterval)
            : fromSpeeds(speeds, speedTimes, a, b, speedsFrame.equals("field"));
        if (predicted.isEmpty()) {
          uncovered++;
          continue;
        }
        var p = predicted.get();
        if (odometry == null) {
          // The same interval read in the other frame, to check the frame against the data
          fromSpeeds(speeds, speedTimes, a, b, !speedsFrame.equals("field")).ifPresent(q ->
              otherFrame.add(Math.hypot((b.x() - a.x()) - q.dx(), (b.y() - a.y()) - q.dy())));
        }
        double ex = (b.x() - a.x()) - p.dx();
        double ey = (b.y() - a.y()) - p.dy();
        double e = Math.hypot(ex, ey);
        double eh = wrap(wrap(b.heading() - a.heading()) - p.dHeading());
        translation.add(e);
        heading.add(Math.abs(eh));
        boolean isCorrection = e >= threshold
            || (headingThreshold != null && Math.abs(eh) >= headingThreshold);
        if (isCorrection) {
          total += e;
          correctionTimes.add(b.t());
          var o = new JsonObject();
          o.addProperty("timestamp_sec", b.t());
          o.addProperty("interval_sec", b.t() - a.t());
          o.addProperty("dx_m", ex);
          o.addProperty("dy_m", ey);
          o.addProperty("translation_m", e);
          o.addProperty("heading_rad", eh);
          o.addProperty("speed_mps", p.speed());
          double t = b.t();
          enables.stream().filter(s -> Math.abs(t - s) <= FrcDomainTools.AnalyzeVisionTool
                  .NEAR_ENABLE_SEC)
              .findFirst().ifPresent(s -> o.addProperty("near_enable_sec", t - s));
          corrections.add(o);
        }
      }

      var builder = success()
          .addProperty("pose_entry", pose.entry())
          .addInput("pose", pose.entry())
          .addInputScope(scope);
      var odometryJson = new JsonObject();
      if (odometry != null) {
        odometryJson.addProperty("source", "odometry_pose");
        odometryJson.addProperty("entry", odometry.entry());
        builder.addInput("odometry_pose", odometry.entry());
      } else {
        odometryJson.addProperty("source", "chassis_speeds");
        odometryJson.addProperty("entry", speedsRole.chosen().get());
        odometryJson.addProperty("speeds_frame", speedsFrame);
        odometryJson.addProperty("basis", speedsRole.basis());
        builder.addInput("chassis_speeds", speedsRole.chosen().get());
      }
      builder.addData("odometry", odometryJson);
      if (!poseRole.chosen().get().equals(getOptString(arguments, "pose_entry", null))) {
        builder.addData("robot_pose", poseRole.toJson(10));
      }
      var intervals = new JsonObject();
      intervals.addProperty("analyzed", translation.size());
      intervals.addProperty("longer_than_max", tooLong);
      intervals.addProperty("without_odometry", uncovered);
      intervals.addProperty("unreadable_pose_records", pose.unreadable());
      builder.addData("intervals", intervals);
      if (translation.isEmpty()) {
        return builder.status(ResultContract.Status.NO_MATCH)
            .addProperty("reason", "No pair of consecutive " + pose.entry() + " records in "
                + "scope could be compared (" + tooLong + " intervals longer than "
                + "max_interval_sec, " + uncovered + " without odometry covering them).")
            .build();
      }
      double[] residuals = translation.stream().mapToDouble(Double::doubleValue).toArray();
      builder.addData("residual_translation_m", distribution(residuals, true));
      if (!otherFrame.isEmpty()) {
        double chosenMedian = median(residuals);
        double otherMedian = median(otherFrame.stream().mapToDouble(Double::doubleValue)
            .toArray());
        var other = speedsFrame.equals("field") ? "robot" : "field";
        var check = new JsonObject();
        check.addProperty(speedsFrame + "_median_m", chosenMedian);
        check.addProperty(other + "_median_m", otherMedian);
        odometryJson.add("frame_check", check);
        if (otherMedian < chosenMedian / 2 && chosenMedian > FRAME_CHECK_MIN_M) {
          builder.addWarning("The chassis speeds fit the pose better as " + other
              + "-relative: the median residual is " + String.format("%.4f", otherMedian)
              + " m read that way, " + String.format("%.4f", chosenMedian) + " m as "
              + speedsFrame + "-relative. If " + odometryJson.get("entry").getAsString()
              + " is " + other + "-relative, pass speeds_frame '" + other + "'; these "
              + "corrections are not reliable as they stand.");
        }
      }
      builder.addData("residual_heading_rad", distribution(heading.stream()
          .mapToDouble(Double::doubleValue).toArray(), true));
      builder.addProperty("threshold_m", threshold);
      if (headingThreshold != null) builder.addProperty("heading_threshold_rad", headingThreshold);
      builder.addProperty("correction_count", corrections.size());
      builder.addProperty("total_translation_m", total);
      var list = new JsonArray();
      corrections.stream().limit(limit).forEach(list::add);
      builder.addLimitedList("corrections", list, corrections.size(), limit);
      if (correctionTimes.size() > 1) {
        var gaps = new double[correctionTimes.size() - 1];
        for (int k = 1; k < correctionTimes.size(); k++) {
          gaps[k - 1] = correctionTimes.get(k) - correctionTimes.get(k - 1);
        }
        Arrays.sort(gaps);
        var cadence = new JsonObject();
        cadence.addProperty("n", gaps.length);
        cadence.addProperty("min", gaps[0]);
        cadence.addProperty("median", percentile(gaps, 0.5));
        cadence.addProperty("p95", percentile(gaps, 0.95));
        cadence.addProperty("max", gaps[gaps.length - 1]);
        builder.addData("correction_interval_sec", cadence);
      }
      var quality = DataQuality.fromSegments(scope.split(log.values().get(pose.entry())));
      return builder.addDataQuality(quality)
          .addDirectives(AnalysisDirectives.fromQuality(quality).addSingleMatchCaveat()
              .addFollowup("Use analyze_vision to see whether corrections coincide with vision "
                  + "observations, and find_condition to limit the scope to driving"))
          .build();
    }

    /** A median residual below this fits either frame (the robot turned little). */
    static final double FRAME_CHECK_MIN_M = 0.005;

    static double median(double[] values) {
      var sorted = values.clone();
      Arrays.sort(sorted);
      return percentile(sorted, 0.5);
    }

    /** Reads a struct:ChassisSpeeds entry (vx, vy, omega), in time order. */
    static List<Speeds> speeds(LogData log, String entry) {
      var values = log.values().get(entry);
      var out = new ArrayList<Speeds>();
      double last = Double.NEGATIVE_INFINITY;
      for (var tv : values == null ? List.<TimestampedValue>of() : values) {
        Double vx = StructFields.number(tv.value(), "vx");
        Double vy = StructFields.number(tv.value(), "vy");
        Double w = StructFields.number(tv.value(), "omega");
        if (vx == null || vy == null || w == null || !(tv.timestamp() > last)) continue;
        out.add(new Speeds(tv.timestamp(), vx, vy, w));
        last = tv.timestamp();
      }
      return out;
    }

    /** The odometry pose's change over the interval, rotated into the pose's frame. */
    static Optional<Prediction> fromOdometry(PoseStream odometry, PoseSample a, PoseSample b,
        double maxGap) {
      var o0 = odometry.at(a.t(), false, maxGap);
      var o1 = odometry.at(b.t(), false, maxGap);
      if (o0.isEmpty() || o1.isEmpty()) return Optional.empty();
      double dx = o1.get().x() - o0.get().x();
      double dy = o1.get().y() - o0.get().y();
      double phi = wrap(a.heading() - o0.get().heading());
      double c = Math.cos(phi);
      double s = Math.sin(phi);
      double dt = b.t() - a.t();
      return Optional.of(new Prediction(dx * c - dy * s, dx * s + dy * c,
          wrap(o1.get().heading() - o0.get().heading()), Math.hypot(dx, dy) / dt));
    }

    /**
     * Chassis speeds integrated over the interval (trapezoidal, linear between speed samples),
     * rotated by the heading (the pose's heading at the start plus the integrated turn) when
     * robot-relative. Empty unless speed samples bracket the interval.
     */
    static Optional<Prediction> fromSpeeds(List<Speeds> speeds, double[] times, PoseSample a,
        PoseSample b, boolean fieldRelative) {
      if (times.length < 2 || times[0] > a.t() || times[times.length - 1] < b.t()) {
        return Optional.empty();
      }
      var knots = new ArrayList<Double>();
      knots.add(a.t());
      int first = Arrays.binarySearch(times, a.t());
      int k = first >= 0 ? first + 1 : -first - 1;
      for (; k < times.length && times[k] < b.t(); k++) knots.add(times[k]);
      knots.add(b.t());
      double x = 0;
      double y = 0;
      double theta = a.heading();
      double turned = 0;
      double speedSum = 0;
      for (int i = 1; i < knots.size(); i++) {
        double t0 = knots.get(i - 1);
        double t1 = knots.get(i);
        double dt = t1 - t0;
        if (dt <= 0) continue;
        var s0 = speedAt(speeds, times, t0);
        var s1 = speedAt(speeds, times, t1);
        double vx = (s0.vx() + s1.vx()) / 2;
        double vy = (s0.vy() + s1.vy()) / 2;
        double w = (s0.omega() + s1.omega()) / 2;
        double mid = theta + w * dt / 2;
        if (fieldRelative) {
          x += vx * dt;
          y += vy * dt;
        } else {
          x += (vx * Math.cos(mid) - vy * Math.sin(mid)) * dt;
          y += (vx * Math.sin(mid) + vy * Math.cos(mid)) * dt;
        }
        theta += w * dt;
        turned += w * dt;
        speedSum += Math.hypot(vx, vy) * dt;
      }
      return Optional.of(new Prediction(x, y, turned, speedSum / (b.t() - a.t())));
    }

    /** Speeds at t, linear between the samples around it (t within their span). */
    static Speeds speedAt(List<Speeds> speeds, double[] times, double t) {
      int i = Arrays.binarySearch(times, t);
      if (i >= 0) return speeds.get(i);
      int hi = -i - 1;
      var s0 = speeds.get(hi - 1);
      var s1 = speeds.get(hi);
      double f = (t - s0.t()) / (s1.t() - s0.t());
      return new Speeds(t, s0.vx() + f * (s1.vx() - s0.vx()), s0.vy() + f * (s1.vy() - s0.vy()),
          s0.omega() + f * (s1.omega() - s0.omega()));
    }
  }
}
