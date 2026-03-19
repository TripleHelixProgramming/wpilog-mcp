package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.triplehelix.wpilogmcp.log.LogManager.TimestampedValue;
import org.triplehelix.wpilogmcp.mcp.McpServer;
import org.triplehelix.wpilogmcp.mcp.McpServer.SchemaBuilder;
import org.triplehelix.wpilogmcp.mcp.McpServer.Tool;

import static org.triplehelix.wpilogmcp.tools.ToolUtils.*;

/**
 * Robot-specific analysis tools for WPILOG data.
 */
public final class RobotAnalysisTools {

  private RobotAnalysisTools() {}

  public static void registerAll(McpServer server) {
    server.registerTool(new GetMatchPhasesTool());
    server.registerTool(new AnalyzeSwerveTool());
    server.registerTool(new PowerAnalysisTool());
    server.registerTool(new CanHealthTool());
    server.registerTool(new CompareMatchesTool());
    server.registerTool(new GetCodeMetadataTool());
    server.registerTool(new MoiRegressionTool());
  }

  static class GetMatchPhasesTool implements Tool {
    @Override
    public String name() { return "get_match_phases"; }

    @Override
    public String description() {
      return "Auto-detect match phases (autonomous 0-15s, teleop 15-135s, endgame 120-135s) from DriverStation data or log timestamps.";
    }

    @Override
    public JsonObject inputSchema() { return new SchemaBuilder().build(); }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var matchStart = log.entries().keySet().stream()
          .filter(name -> name.contains("DriverStation") && name.contains("Enabled"))
          .map(name -> log.values().get(name))
          .filter(Objects::nonNull)
          .flatMap(List::stream)
          .filter(tv -> tv.value() instanceof Boolean enabled && enabled)
          .mapToDouble(TimestampedValue::timestamp)
          .min();

      double start = matchStart.orElse(log.minTimestamp());
      double end = log.maxTimestamp();

      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("log_duration", log.duration());

      var phases = new JsonObject();
      phases.add("autonomous", createPhase(start, Math.min(start + 15, end), "Auto (0-15s)"));
      if (log.duration() > 15) {
        phases.add("teleop", createPhase(start + 15, Math.min(start + 135, end), "Teleop (15-135s)"));
      }
      if (log.duration() > 120) {
        phases.add("endgame", createPhase(start + 120, Math.min(start + 135, end), "Endgame (120-135s)"));
      }
      result.add("phases", phases);
      return result;
    }

    private JsonObject createPhase(double s, double e, String desc) {
      var obj = new JsonObject();
      obj.addProperty("start", s);
      obj.addProperty("end", e);
      obj.addProperty("duration", Math.max(0, e - s));
      obj.addProperty("description", desc);
      return obj;
    }
  }

  static class AnalyzeSwerveTool implements Tool {
    @Override
    public String name() { return "analyze_swerve"; }

    @Override
    public String description() { return "Analyze swerve drive module performance. Looks for wheel slip, module synchronization issues, and speed discrepancies."; }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("module_prefix", "string", "Entry path prefix (e.g., '/Drive/Module')", false)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var prefix = arguments.has("module_prefix") ? arguments.get("module_prefix").getAsString() : null;

      var categorizedEntries = log.entries().entrySet().stream()
          .filter(e -> prefix == null || e.getKey().startsWith(prefix))
          .collect(Collectors.groupingBy(e -> {
            var type = e.getValue().type();
            if (type.contains("SwerveModuleState")) return "module_states";
            if (type.contains("SwerveModulePosition")) return "module_positions";
            if (type.contains("ChassisSpeeds")) return "chassis_speeds";
            return "other";
          }, Collectors.mapping(Map.Entry::getKey, Collectors.toList())));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("swerve_entries", GSON.toJsonTree(categorizedEntries));

      var states = categorizedEntries.get("module_states");
      var warnings = new ArrayList<String>();

      if (states != null) {
        var analysis = new JsonArray();
        for (var name : states) {
          var moduleResult = analyzeModule(name, log.values().get(name));
          if (moduleResult != null) {
            analysis.add(moduleResult);
          } else {
            warnings.add("Could not analyze module '" + name + "': no valid speed data found");
          }
        }
        result.add("module_analysis", analysis);
      }

      if (!warnings.isEmpty()) {
        result.add("warnings", GSON.toJsonTree(warnings));
      }

      return result;
    }

    private JsonObject analyzeModule(String name, List<TimestampedValue> values) {
      if (values == null || values.isEmpty()) return null;

      var speeds = values.stream()
          .flatMap(tv -> {
            if (tv.value() instanceof Map) {
              return java.util.stream.Stream.of((Map<?, ?>) tv.value());
            } else if (tv.value() instanceof List) {
              return ((List<?>) tv.value()).stream()
                  .filter(v -> v instanceof Map)
                  .map(v -> (Map<?, ?>) v);
            }
            return java.util.stream.Stream.empty();
          })
          .map(m -> m.get("speed_mps"))
          .filter(v -> v instanceof Number)
          .mapToDouble(v -> Math.abs(((Number) v).doubleValue()))
          .toArray();

      if (speeds.length == 0) return null;
      var stats = java.util.Arrays.stream(speeds).summaryStatistics();
      var obj = new JsonObject();
      obj.addProperty("entry", name);
      obj.addProperty("max_speed_mps", stats.getMax());
      obj.addProperty("avg_speed_mps", stats.getAverage());
      obj.addProperty("sample_count", stats.getCount());
      return obj;
    }
  }

  static class PowerAnalysisTool implements Tool {
    @Override
    public String name() { return "power_analysis"; }

    @Override
    public String description() { return "Analyze battery and current distribution data. Finds peak currents per channel and brownout risk."; }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("power_prefix", "string", "Entry path prefix (e.g., '/PDP')", false)
          .addNumberProperty("brownout_threshold", "Voltage threshold (default 7.0V)", false, 7.0)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var prefix = arguments.has("power_prefix") ? arguments.get("power_prefix").getAsString() : null;
      double threshold = arguments.has("brownout_threshold") ? arguments.get("brownout_threshold").getAsDouble() : 7.0;

      var voltageEntry = log.entries().keySet().stream()
          .filter(n -> (prefix == null || n.startsWith(prefix)) && (n.toLowerCase().contains("voltage")))
          .findFirst();

      var result = new JsonObject();
      result.addProperty("success", true);

      voltageEntry.ifPresent(name -> {
        var values = log.values().get(name);
        if (values != null && !values.isEmpty()) {
          var stats = values.stream()
              .filter(tv -> tv.value() instanceof Number)
              .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
              .summaryStatistics();
          
          var vObj = new JsonObject();
          vObj.addProperty("entry", name);
          vObj.addProperty("min_voltage", stats.getMin());
          vObj.addProperty("max_voltage", stats.getMax());
          vObj.addProperty("avg_voltage", stats.getAverage());
          
          long brownouts = values.stream()
              .filter(tv -> tv.value() instanceof Number && ((Number) tv.value()).doubleValue() < threshold)
              .count();
          vObj.addProperty("samples_below_threshold", brownouts);
          vObj.addProperty("brownout_risk", brownouts > 0 ? "HIGH" : (stats.getMin() < threshold + 1 ? "MODERATE" : "LOW"));
          result.add("voltage_analysis", vObj);
        }
      });

      return result;
    }
  }

  static class CanHealthTool implements Tool {
    @Override
    public String name() { return "can_health"; }

    @Override
    public String description() { return "Analyze CAN bus health by looking for timeout errors and communication issues."; }

    @Override
    public JsonObject inputSchema() { return new SchemaBuilder().build(); }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var errorCounts = log.entries().entrySet().stream()
          .filter(e -> "string".equals(e.getValue().type()))
          .collect(Collectors.toMap(Map.Entry::getKey, e -> {
            var values = log.values().get(e.getKey());
            return values == null ? 0L : values.stream()
                .filter(tv -> tv.value() instanceof String s && isCanError(s))
                .count();
          }))
          .entrySet().stream()
          .filter(e -> e.getValue() > 0)
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("error_counts_by_entry", GSON.toJsonTree(errorCounts));
      
      long total = errorCounts.values().stream().mapToLong(Long::longValue).sum();
      result.addProperty("total_can_errors", total);
      result.addProperty("health_assessment", total == 0 ? "GOOD" : (total < 50 ? "CONCERNING" : "POOR"));
      
      return result;
    }

    private boolean isCanError(String s) {
      var lower = s.toLowerCase();
      return lower.contains("can") && (lower.contains("timeout") || lower.contains("error") || lower.contains("fault"));
    }
  }

  static class CompareMatchesTool implements Tool {
    @Override
    public String name() { return "compare_matches"; }

    @Override
    public String description() { return "Compare statistics for an entry across multiple loaded log files."; }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder().addProperty("name", "string", "Entry name", true).build();
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var loadedPaths = getLogManager().getLoadedLogPaths();
      if (loadedPaths.size() < 2) return errorResult("Need at least 2 logs loaded to compare. Currently loaded: " + loadedPaths.size());

      var name = arguments.get("name").getAsString();
      var originalActive = getLogManager().getActiveLogPath();

      var comparisons = loadedPaths.stream()
          .map(path -> {
            getLogManager().setActiveLog(path);
            var log = getLogManager().getActiveLog();
            var stats = new JsonObject();
            stats.addProperty("log_filename", Path.of(path).getFileName().toString());
            
            var vals = log.values().get(name);
            if (vals != null) {
              var s = vals.stream()
                  .filter(tv -> tv.value() instanceof Number)
                  .mapToDouble(tv -> ((Number) tv.value()).doubleValue())
                  .summaryStatistics();
              if (s.getCount() > 0) {
                var sObj = new JsonObject();
                sObj.addProperty("min", s.getMin());
                sObj.addProperty("max", s.getMax());
                sObj.addProperty("mean", s.getAverage());
                stats.add("statistics", sObj);
              }
            }
            return stats;
          })
          .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);

      if (originalActive != null) getLogManager().setActiveLog(originalActive);

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("comparisons", comparisons);
      return result;
    }
  }

  /**
   * OLS regression tool for estimating moment of inertia (J) and viscous damping (B) from logged
   * motor current and mechanism velocity data.
   *
   * <p>Physics model: {@code G * motor_count * kt * I = J * α + B * ω}
   *
   * <p>Key inputs:
   * <ul>
   *   <li>velocity_entry — angular (rad/s) or linear (m/s) velocity from the log
   *   <li>current_entry — motor current in amps (always non-negative for TalonFX/Spark)
   *   <li>applied_volts_entry — optional, used to recover torque sign when current is unsigned
   * </ul>
   *
   * <p>The tool performs nearest-neighbour interpolation to align current to velocity timestamps,
   * applies optional moving-average smoothing, computes the numerical velocity derivative, then
   * solves the 2×2 normal equations analytically.
   */
  static class MoiRegressionTool implements Tool {

    @Override
    public String name() { return "moi_regression"; }

    @Override
    public String description() {
      return "Estimate moment of inertia J (kg·m²) and viscous damping B (Nm·s/rad) for a "
          + "DC-motor-driven mechanism using OLS regression on logged velocity and current. "
          + "Model: G * motor_count * kt * I = J * α + B * ω. "
          + "Supports angular (rad/s) or linear (m/s, via wheel_radius) velocity entries. "
          + "Provide applied_volts_entry when current is always non-negative (TalonFX/SparkMax) "
          + "so torque direction is recovered from voltage sign.";
    }

    @Override
    public JsonObject inputSchema() {
      return new SchemaBuilder()
          .addProperty("velocity_entry", "string",
              "Entry path for mechanism velocity (rad/s, or m/s if wheel_radius is given)", true)
          .addProperty("current_entry", "string",
              "Entry path for motor current (A)", true)
          .addNumberProperty("kt", "Motor torque constant per motor (Nm/A). "
              + "Kraken X60=0.01940, NEO Vortex=0.01706, NEO 550=0.0108", true, null)
          .addNumberProperty("gear_ratio", "Overall gear ratio from motor shaft to output shaft (G)",
              true, null)
          .addIntegerProperty("motor_count",
              "Number of motors driving the mechanism in parallel (default 1)", false, 1)
          .addNumberProperty("wheel_radius",
              "Wheel radius (m). Provide when velocity is logged as linear (m/s) to convert to angular",
              false, null)
          .addProperty("applied_volts_entry", "string",
              "Optional: entry for applied voltage. When current is always non-negative "
              + "(TalonFX/SparkMax), voltage sign is used to determine torque direction.", false)
          .addNumberProperty("start_time", "Analysis window start (seconds)", false, null)
          .addNumberProperty("end_time", "Analysis window end (seconds)", false, null)
          .addNumberProperty("alpha_threshold",
              "Min |α| (rad/s²) for a sample to be included in OLS. Filters near-steady-state "
              + "points. Default 1.0", false, 1.0)
          .addIntegerProperty("smooth_window",
              "Moving-average half-width (samples) applied to velocity before differentiating. "
              + "Default 2", false, 2)
          .build();
    }

    @Override
    public JsonElement execute(JsonObject args) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      // ── Required params ──────────────────────────────────────────────────────
      var velEntry  = getRequiredString(args, "velocity_entry");
      var currEntry = getRequiredString(args, "current_entry");
      double kt     = args.get("kt").getAsDouble();
      double G      = args.get("gear_ratio").getAsDouble();

      // ── Optional params ──────────────────────────────────────────────────────
      int    motorCount  = getOptInt(args,    "motor_count",     1);
      Double wheelRadius = getOptDouble(args, "wheel_radius");
      String voltsEntry  = getOptString(args, "applied_volts_entry", null);
      Double startTime   = getOptDouble(args, "start_time");
      Double endTime     = getOptDouble(args, "end_time");
      double alphaThr    = getOptDouble(args, "alpha_threshold", 1.0);
      int    smoothW     = getOptInt(args,    "smooth_window",   2);

      double torqueScale = G * motorCount * kt;

      // ── Fetch entries ─────────────────────────────────────────────────────────
      var velValues  = log.values().get(velEntry);
      if (velValues == null || velValues.isEmpty())
        return errorResult("Velocity entry not found or empty: " + velEntry);

      var currValues = log.values().get(currEntry);
      if (currValues == null || currValues.isEmpty())
        return errorResult("Current entry not found or empty: " + currEntry);

      List<TimestampedValue> voltsValues = null;
      if (voltsEntry != null) {
        voltsValues = log.values().get(voltsEntry);
        if (voltsValues == null || voltsValues.isEmpty())
          return errorResult("Applied volts entry not found or empty: " + voltsEntry);
      }

      // ── Filter to time window and build arrays ────────────────────────────────
      final double tStart = startTime != null ? startTime : Double.NEGATIVE_INFINITY;
      final double tEnd   = endTime   != null ? endTime   : Double.POSITIVE_INFINITY;

      var velFiltered = velValues.stream()
          .filter(tv -> tv.timestamp() >= tStart && tv.timestamp() <= tEnd
                        && tv.value() instanceof Number)
          .collect(Collectors.toList());

      if (velFiltered.size() < 10)
        return errorResult("Too few velocity samples in window: " + velFiltered.size() + " (need ≥10)");

      int n = velFiltered.size();
      double[] ts    = new double[n];
      double[] omega = new double[n];
      double radiusInv = (wheelRadius != null && wheelRadius > 0) ? 1.0 / wheelRadius : 1.0;
      for (int i = 0; i < n; i++) {
        ts[i]    = velFiltered.get(i).timestamp();
        omega[i] = ((Number) velFiltered.get(i).value()).doubleValue() * radiusInv;
      }

      // ── Smooth velocity and differentiate ─────────────────────────────────────
      double[] omegaS = movingAverage(omega, smoothW);
      double[] alpha  = gradient(omegaS, ts);

      // ── Interpolate current to velocity timestamps ────────────────────────────
      double[] curr = new double[n];
      for (int i = 0; i < n; i++) {
        Double v = getValueAtTimeLinear(currValues, ts[i]);
        curr[i] = v != null ? v : 0.0;
      }

      // ── Torque direction sign ─────────────────────────────────────────────────
      // If applied_volts_entry given: sign(volts); otherwise assume current is already signed.
      double[] tauSign = new double[n];
      if (voltsValues != null) {
        for (int i = 0; i < n; i++) {
          Double v = getValueAtTimeLinear(voltsValues, ts[i]);
          tauSign[i] = v != null ? Math.signum(v) : 0.0; // 0.0 → filtered out below
        }
      } else {
        java.util.Arrays.fill(tauSign, 1.0);
      }

      // ── OLS normal equations (2×2 system: J, B) ──────────────────────────────
      double sumA2 = 0, sumAW = 0, sumW2 = 0, sumTA = 0, sumTW = 0;
      int nUsed = 0, filtByThr = 0, filtBySign = 0;

      for (int i = 0; i < n; i++) {
        if (!Double.isFinite(alpha[i]) || !Double.isFinite(omegaS[i]) || !Double.isFinite(curr[i]))
          continue;
        if (Math.abs(alpha[i]) < alphaThr) { filtByThr++;  continue; }
        if (voltsValues != null && Math.abs(tauSign[i]) < 0.5) { filtBySign++; continue; }

        double tau = torqueScale * tauSign[i] * Math.abs(curr[i]);
        sumA2 += alpha[i] * alpha[i];
        sumAW += alpha[i] * omegaS[i];
        sumW2 += omegaS[i] * omegaS[i];
        sumTA += tau * alpha[i];
        sumTW += tau * omegaS[i];
        nUsed++;
      }

      if (nUsed < 5) {
        var err = errorResult("Insufficient samples after filtering: " + nUsed
            + ". Try lowering alpha_threshold or widening the time window.");
        err.addProperty("samples_total", n);
        err.addProperty("filtered_by_alpha_threshold", filtByThr);
        if (filtBySign > 0) err.addProperty("filtered_by_zero_volts", filtBySign);
        return err;
      }

      double det = sumA2 * sumW2 - sumAW * sumAW;
      if (Math.abs(det) < 1e-20)
        return errorResult("Singular OLS matrix: α and ω are nearly collinear. "
            + "Try a different time window or increase alpha_threshold.");

      double J = (sumTA * sumW2 - sumTW * sumAW) / det;
      double B = (sumTW * sumA2 - sumTA * sumAW) / det;

      // ── R² ───────────────────────────────────────────────────────────────────
      // Collect filtered y values, then do a second pass for ssTot/ssRes.
      double[] ys = new double[nUsed];
      int yi = 0;
      for (int i = 0; i < n; i++) {
        if (!Double.isFinite(alpha[i]) || !Double.isFinite(omegaS[i]) || !Double.isFinite(curr[i]))
          continue;
        if (Math.abs(alpha[i]) < alphaThr) continue;
        if (voltsValues != null && Math.abs(tauSign[i]) < 0.5) continue;
        ys[yi++] = torqueScale * tauSign[i] * Math.abs(curr[i]);
      }
      double mean = 0;
      for (double y : ys) mean += y;
      mean /= ys.length;

      double ssTot = 0, ssRes = 0;
      int ri = 0;
      for (int i = 0; i < n; i++) {
        if (!Double.isFinite(alpha[i]) || !Double.isFinite(omegaS[i]) || !Double.isFinite(curr[i]))
          continue;
        if (Math.abs(alpha[i]) < alphaThr) continue;
        if (voltsValues != null && Math.abs(tauSign[i]) < 0.5) continue;
        double y    = ys[ri++];
        double yHat = J * alpha[i] + B * omegaS[i];
        ssTot += (y - mean) * (y - mean);
        ssRes += (y - yHat) * (y - yHat);
      }
      double r2 = ssTot > 1e-20 ? 1.0 - ssRes / ssTot : Double.NaN;

      // ── Build result ──────────────────────────────────────────────────────────
      var result = new JsonObject();
      result.addProperty("success", true);
      result.addProperty("J_kg_m2", J);
      result.addProperty("B_Nm_s_per_rad", B);
      result.addProperty("r_squared", r2);
      result.addProperty("n_samples_used", nUsed);
      result.addProperty("n_samples_total", n);
      result.addProperty("filtered_by_alpha_threshold", filtByThr);
      if (filtBySign > 0) result.addProperty("filtered_by_zero_volts", filtBySign);

      var warnings = new JsonArray();
      if (J < 0)
        warnings.add("J is negative — physically invalid. If current is unsigned, add applied_volts_entry.");
      if (!Double.isNaN(r2) && r2 < 0.2)
        warnings.add(String.format("R²=%.3f is low. Narrow the window to a clean acceleration transient, "
            + "raise alpha_threshold, or increase smooth_window.", r2));
      if (nUsed < 20)
        warnings.add("Only " + nUsed + " samples used. Consider widening the window or lowering alpha_threshold.");
      if (warnings.size() > 0) result.add("warnings", warnings);

      var ctx = new JsonObject();
      ctx.addProperty("torque_scale_Nm_per_A", torqueScale);
      if (wheelRadius != null) ctx.addProperty("wheel_radius_m", wheelRadius);
      if (voltsEntry != null)  ctx.addProperty("applied_volts_used", true);
      if (startTime  != null)  ctx.addProperty("start_time", startTime);
      if (endTime    != null)  ctx.addProperty("end_time",   endTime);
      result.add("parameters_used", ctx);

      return result;
    }

    /** Symmetric moving average, half-width {@code w}. Edge points use a smaller window. */
    private double[] movingAverage(double[] v, int w) {
      int n = v.length;
      double[] out = new double[n];
      for (int i = 0; i < n; i++) {
        int lo = Math.max(0, i - w);
        int hi = Math.min(n - 1, i + w);
        double sum = 0;
        for (int j = lo; j <= hi; j++) sum += v[j];
        out[i] = sum / (hi - lo + 1);
      }
      return out;
    }

    /** Numerical gradient using central differences (numpy.gradient semantics). */
    private double[] gradient(double[] v, double[] t) {
      int n = v.length;
      double[] g = new double[n];
      if (n < 2) return g;
      g[0]     = (v[1]   - v[0])     / (t[1]   - t[0]);
      g[n - 1] = (v[n-1] - v[n-2])   / (t[n-1] - t[n-2]);
      for (int i = 1; i < n - 1; i++)
        g[i] = (v[i+1] - v[i-1]) / (t[i+1] - t[i-1]);
      return g;
    }
  }

  static class GetCodeMetadataTool implements Tool {
    @Override
    public String name() { return "get_code_metadata"; }

    @Override
    public String description() { return "Extract code metadata including Git SHA, branch, and build date."; }

    @Override
    public JsonObject inputSchema() { return new SchemaBuilder().build(); }

    @Override
    public JsonElement execute(JsonObject arguments) throws Exception {
      var log = getLogManager().getActiveLog();
      if (log == null) return errorResult("No log loaded");

      var keys = List.of("GitSHA", "GitBranch", "GitDirty", "BuildDate", "ProjectName", "Version");
      var found = log.entries().keySet().stream()
          .filter(name -> keys.stream().anyMatch(name::contains))
          .collect(Collectors.toMap(
              name -> keys.stream().filter(name::contains).findFirst().get(),
              name -> log.values().get(name).get(0).value(),
              (v1, v2) -> v1));

      var result = new JsonObject();
      result.addProperty("success", true);
      result.add("metadata", GSON.toJsonTree(found));
      return result;
    }
  }
}
