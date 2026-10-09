/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;

/**
 * General reasoning guidance for client LLM agents: how to apply the scientific method to
 * telemetry and avoid confabulation.
 *
 * <p>The guidance is delivered through two channels, because no single MCP channel reaches every
 * client:
 *
 * <ul>
 *   <li>{@link #SERVER_INSTRUCTIONS} is returned as the {@code instructions} field of the MCP
 *       {@code initialize} response. Claude Code, VS Code Copilot, and Gemini CLI place it in the
 *       model's system prompt; Claude Desktop currently drops it. Claude Code truncates it at
 *       {@link #INSTRUCTIONS_BYTE_LIMIT}, so the highest-value rules come first and the whole
 *       text is kept ASCII (a truncation mid-multibyte-character has bitten other servers).
 *   <li>{@link #analysisPrinciples()} is returned by {@code get_server_guide} as
 *       {@code analysis_principles}. Tool results reach the model in every client, so this is the
 *       long-form version: the method, calibration rules, a catalogue of confabulation traps with
 *       the tool call that avoids each, and report formats.
 * </ul>
 *
 * <p>Wording principles (learned the hard way): rules must be concrete and checkable ("never
 * quote a number not in a tool result"), scoped so they do not fire on simple lookups, and must
 * not turn the server's {@code confidence_level} into a blanket ceiling. That field is driven by
 * sample counts, holds, and timing, and reads "low" on sparse change-only signals whose events are
 * perfectly clear, so it bounds statistics, not directly observed events; a pit crew needs "the log shows a 149 A stall at
 * 87.2 s" stated plainly. Every claim the text makes about a tool (parameter names, what a tool
 * can and cannot read, what an event type means) must match the implementation; the test suite
 * checks tool names, but semantics are checked by review.
 *
 * @since 0.9.0
 */
public final class AnalysisGuidance {

  private AnalysisGuidance() {}

  /**
   * Maximum size, in JSON-escaped UTF-8 bytes, that {@link #SERVER_INSTRUCTIONS} may occupy.
   * Claude Code truncates MCP server instructions (and tool descriptions) at 2 KB.
   */
  public static final int INSTRUCTIONS_BYTE_LIMIT = 2048;

  /**
   * Server-level instructions returned on {@code initialize}. Keep under
   * {@link #INSTRUCTIONS_BYTE_LIMIT}; put the most important rules first; ASCII only.
   */
  public static final String FRESH_DATA_INSTRUCTIONS = "list_sessions, get_latest_values and wait_for_change answer for the present; log tools answer for the recorded past.\n"
      + "The current capture file is the one list_available_logs marks open.";
  public static String forLocation(String location) {
    return location + "\n" + FRESH_DATA_INSTRUCTIONS + "\n" + SERVER_INSTRUCTIONS;
  }
  public static final String SERVER_INSTRUCTIONS = """
      wpilog-mcp: Answer the question asked first, then evidence.
      1. Never name an entry or number without tool evidence. no_match means not found; absent data is not absent problems. Say what was searched; ask for naming. A read_entry page is not the whole log.
      2. An entry's name does not prove what it measures. Check robot source code for mechanism, units, measured or commanded; else state the assumption.
      3. Tools compute statistics, rates, durations and correlations, never mental math. Struct/array fields: Entry.field, Entry[i]; otherwise export_csv. Call get_match_phases before time reasoning.
      4. Verify premises (get_ds_timeline, find_condition). BROWNOUT_START/END are voltage crossings; only RIO_BROWNOUT_START means logged output cutoff.
      5. Three tiers: logged events are facts; statistics are inferences bounded by confidence_level and data_quality.reasons; causes outside telemetry are hypotheses to check physically. Quality bounds statistics, not events.
      6. For why questions, answer yes/no/cannot tell, then test a rival even when the user names a cause: phase/state, logging/timing artifact, simultaneous load.
      7. Scope statistics by phase/state (scope): whole-log numbers mix in disabled time and boot. Cite entry, window, n, statistic; one log is one sample. Generalize across matches (compare_matches).
      8. An early end is "log ends", not "match ended". REV timing depends on sync_status. Scores only from TBA (get_tba_match_data).
      9. End diagnoses with ranked findings, confidence and a next check. get_server_guide has the full method.""";

  /**
   * Long-form principles returned by {@code get_server_guide}. Authored as JSON for readability;
   * parsed strictly once (so a stray comma fails the build rather than shipping a {@code null})
   * and deep-copied on each call so callers may modify the result freely.
   */
  private static final String PRINCIPLES_JSON = """
      {
        "purpose": "How to reason about this server's results without confabulating. Read once per session; apply the method to causal questions and the traps everywhere.",
        "answer_first": "Lead with the answer to the question actually asked, in one or two sentences. Evidence, alternatives, and confidence follow. For lookups they are optional.",
        "system_logs": "The pulled file is the exact record (search_system_logs); the tailed entry is the timely one (search_strings), timestamped at receipt. They may contain the same text twice.",
        "method": {
          "applies_to": "Causal or diagnostic questions ('why', 'what caused', 'is X the problem'). A lookup ('what is the loop time', 'what happened at 87s') needs discovery and a direct answer, nothing more.",
          "steps": [
            "Observe: learn what is actually logged (list_entries, search_entries) and where the phases are (get_match_phases). When the robot project's source code is at hand (the workspace is often that project), find where each entry you will rely on is logged: that code, not the entry's name, says which mechanism it belongs to, its units, and whether it is measured or commanded. Confirm the event in the question actually occurred (get_ds_timeline, find_condition).",
            "Hypothesize: the candidate cause plus at least one rival. Always include 'normal for this phase or state' and 'logging or timing artifact'; for power questions add 'another load at the same instant'.",
            "Predict before testing: 'if H1, entry E exceeds X during phase P within T of the event; if H2, it does not'. A threshold chosen after seeing the data is not a test; if you change it, say so and why.",
            "Test: one tool call per prediction, scoped with start_time/end_time where the tool accepts them (get_statistics, rate_of_change, time_correlate, read_entry).",
            "Evaluate: read data_quality and confidence_level before believing a statistic; a discrete event needs no statistic.",
            "Conclude in tiered language: fact (observed event), inference (statistic, bounded by confidence_level), hypothesis (cause outside the telemetry, needs physical inspection).",
            "Next: name the single measurement or inspection that would most reduce the remaining uncertainty."
          ],
          "pit_mode": "With a match coming up, answer with the fewest tool calls that test the leading cause and one rival, then offer the deeper analysis instead of running it. Run the full loop for post-event analysis or when the answer would change a hardware decision."
        },
        "calibration": {
          "server_confidence_level": "Derived from quality_score, which starts at 1.0 and subtracts, each with a line in data_quality.reasons: up to 0.3 for time in intervals over 5x the median (20% of the span is the full penalty; for a change_only series these are holds, not missing data, but statistics weigh samples, not time), up to 0.2 for timing jitter (median absolute deviation, periodic series), up to 0.2 for non-finite values, and 0.3 or 0.15 for fewer than 100 or 500 finite samples. data_quality.sampling says whether the series is periodic, change_only (logged when it changes, as AdvantageKit does), or event. Good full-match data reads high; low usually means few samples or long holds, which bounds a statistic but not an observed event.",

          "what_it_bounds": "Claims built on a mean, trend, percentile, correlation, or score. It does not bound claims about discrete events.",
          "discrete_events": "A logged brownout flag, a voltage threshold crossing, a DS disable, a joystick disconnect, an enabled-state CAN error, a current peak above stall: these are observations. State them plainly with the timestamp, even from one match. The cause of the event is a separate claim with its own confidence.",
          "cause_confidence": {
            "high": "the mechanism is visible in the same time window (for example 149 A on the elevator's current entry at the voltage minimum) and no rival survived testing",
            "medium": "consistent with the data, but rivals are untested or the root cause lies outside telemetry",
            "low": "one weak signal, n below 15, or revlog alignment coarser than the interval in question"
          },
          "never_high_when": [
            "n < 100 samples",
            "gaps cover more than 10% of the window",
            "single match and the claim is a pattern or trend",
            "the claim depends on revlog timing with low sync confidence"
          ],
          "do_not_hedge": "Do not attach caveats to facts. 'The log shows a 149 A stall at 87.2 s' needs no qualifier. Reserve hedging for the cause and for generalizations."
        },
        "user_proposed_cause": "Treat it as the first hypothesis. Answer it in the first sentence (yes, no, or cannot tell from this log) with the entry and window that decides it. Do not confirm it from one consistent statistic; do not dismiss it without a tool result. Then name the strongest rival and what the data says about it. Example: a compressor draws a steady 10-20 A and rarely causes a brownout alone; check its channel's peak_current_A in power_analysis channel_analysis, its current around the voltage minimum (read_entry with start_time/end_time on a scalar current entry or on the /PowerDistribution/ChannelCurrent array), and what else was drawing then. A steady load shows near-zero correlation with voltage even when it contributes.",
        "traps": [
          {"trap": "Explaining an event that did not happen", "fix": "Check first with get_ds_timeline, find_condition, or search_strings. get_ds_timeline names the voltage entry it scanned as brownout_voltage_entry and warns when the log has none; in that case the absence of BROWNOUT events is not evidence, so use power_analysis or find_condition on a voltage entry instead. For console errors, get_ds_timeline gives exact counts (text_event_counts), a distinct-message summary (text_event_summary: each group's pattern with one example text), and the message of each ALERT_RAISED event, not the full list of messages; search_strings with level=error lists them all. If the user's event is absent (no crossing below threshold, no error, no disable), say so and ask what they observed; do not explain a hypothetical."},
          {"trap": "Naming an entry or quoting a value that no tool returned", "fix": "Every entry name must come from list_entries or search_entries; every number from a tool result. If it is not logged, say 'not logged'."},
          {"trap": "Taking an entry's name as proof of what it measures", "fix": "A name is a label a programmer chose: currentHeight is the present height, not an electrical current; PhotonVision's targetYaw is a camera reading, not a setpoint; and real logs hold two target module-state arrays beside the measured one, which no name tells apart. Content misleads too: a planned trajectory is a struct array of timestamps and poses, like a camera's observations, and a gyro's struct has yaw and pitch, like a camera's target. Before attributing an entry to a mechanism, comparing a setpoint with a measurement, or reading a number as amps or meters, read where the robot code logs it: search the robot project for the entry's key (the string given to AdvantageKit's Logger.recordOutput, a field of an inputs class, a NetworkTables topic, an Epilogue-logged member). That code gives the mechanism, the units, the sign convention, and whether the value is measured or commanded. With the code, state the mapping as a fact and cite the file. Without it, state the mapping as an assumption taken from the name, and say what would confirm it (get_entry_info for the type and metadata, read_entry for the values, or the team)."},
          {"trap": "Treating a page or a summary as the whole message log", "fix": "search_strings is paged: total_matches is the full count and has_more says whether another page exists, so fetch the next offset before saying how many errors there were or that a message never appeared. get_ds_timeline's text_event_summary groups messages after replacing numbers with #; its variants field says how many distinct raw texts a group covers (device 5 and device 7 are one group with variants 2), so drill into a group with search_strings (regex) before treating it as one issue. Long values are truncated (value_truncated); raise max_value_chars to see the whole text."},
          {"trap": "Treating one read_entry page or window as the whole signal", "fix": "Use get_statistics, find_condition, or find_peaks for whole-window claims. Use read_entry only to inspect a window already located by another tool, and state its bounds."},
          {"trap": "Whole-log statistics", "fix": "Whole-log numbers mix in disabled time (0 A, steady voltage) and boot transients (CAN errors, loop overruns before the DS connects). Pass scope (enabled, disabled, auto, teleop, segment:<i> from get_match_phases) to get_statistics, detect_anomalies, find_peaks, rate_of_change, time_correlate, compare_entries, compare_matches, find_condition, align_entries, compare_poses, pose_corrections, render_chart, and search_system_logs, which also take windows, any list of intervals such as find_condition's; each window is analyzed on its own (no rate or peak spans the gap between two). analyze_swerve, analyze_loop_timing, power_analysis, and predict_battery_health take scope but not windows."},
          {"trap": "Reading no_match or not_applicable as 'no problem found'", "fix": "status no_match means the tool did not find the entries it analyzes, and not_applicable that it does not apply to this log (for example analyze_auto on a practice log where Autonomous was never true). reason, looked_for, and hint say what was searched and how to point the tool at the data. Report that the data is absent or the question does not apply, never that the robot is fine. partial means some sections were skipped; skipped says which and why."},
          {"trap": "Silent choice among near-duplicate entries", "fix": "Logs contain mirrors (NetworkTables copies, /RealOutputs vs /ReplayOutputs, per-module vs aggregate swerve). State which entry you analyzed and why (get_entry_info: type, sample_count, metadata, time_range_sec). If the answer would differ by entry, run both."},
          {"trap": "Assuming a user-quoted time is log time", "fix": "Match clock, seconds into a phase, and log time differ, and tool timestamps are the log's own FPGA clock (the first sample is usually not at 0). Map with get_match_phases and confirm with the user if what you find does not match their description."},
          {"trap": "Analyzing the wrong log", "fix": "Identify the log with list_available_logs (TBA match metadata when configured, otherwise filename and timestamp) and state the filename. If no log clearly matches the requested match, ask."},
          {"trap": "Presenting a voltage threshold crossing as a roboRIO brownout", "fix": "get_ds_timeline BROWNOUT_START (basis voltage_threshold) is the first sample below brownout_threshold on brownout_voltage_entry, and BROWNOUT_END the first sample 0.2 V above it; power_analysis voltage_analysis reports samples_below_threshold (a sample count, not a crossing count) on voltage_analysis.entry. Check that entry is a battery entry, not a rail or applied voltage. Thresholds: the log's own BrownoutVoltage entry when it has one (brownout_threshold_basis reads logged), else 6.8 V (roboRIO 1) stated as an assumption, since a roboRIO 2 defaults to 6.3 V; pass brownout_threshold only to override. None of this is the roboRIO's own brownout flag: only RIO_BROWNOUT_START/END events (basis rio_flag), taken from a logged boolean flag such as AdvantageKit /SystemStats/BrownedOut, mean the roboRIO actually cut outputs, and rio_brownout_flag_logged says whether such a flag exists in the log. When it is false, report the threshold data and say the roboRIO brownout state is not determinable from this log."},
          {"trap": "Guessing which device is on a PDH channel", "fix": "/PowerDistribution/ChannelCurrent is a double[] entry: numeric tools read one channel as /PowerDistribution/ChannelCurrent[3] (get_statistics also pools every channel with [*]; compare_matches reads one channel the same way). power_analysis lists amperage entries (names like ...Current, ...CurrentAmps, ...Amps, or WPILib PowerDistribution[<CAN id>]/Chan<N>) sorted by peak_current_A (largest magnitude, signed, with its time), with min/max and average; it expands the array per index into entries named like /PowerDistribution/ChannelCurrent[3]. Only the top 30 are listed by default: current_entries_analyzed gives the total, and channel_limit raises the cut, so a low-draw channel missing from the list is not missing from the log. For a time window pass start_time/end_time to get_statistics on the channel. Scalar entries such as /PowerDistribution/TotalCurrent, /SystemStats/BatteryCurrent, Chan<N>, or a per-mechanism current the team logs work with every tool. The wiring map is not in the log unless entries are named: ask which channel feeds the mechanism; never assume channel 0 is the drivetrain."},
          {"trap": "Treating a CAN error burst at the brownout timestamp as the cause", "fix": "Devices reset when the rail drops; errors within about a second after a brownout are a consequence. Look for enabled-state errors that precede the voltage dip. Errors while disabled are normal."},
          {"trap": "Reading correlation as cause", "fix": "A mechanism that fires only while scoring starts when voltage is still high, so its current correlates positively with voltage: that is timing, not causal impact. A constant load (a flywheel at 12-20 A) correlates near zero because a flat signal cannot co-vary. Compare voltage statistics with the mechanism on versus off (find_condition on a mechanism current, then get_statistics with windows set to its intervals) instead of relying on r."},
          {"trap": "Significant but negligible", "fix": "With thousands of samples almost everything is significant. Report r with effective_sample_size (time_correlate's p-value already discounts autocorrelated samples; a 50 Hz signal is far fewer independent samples than records); only an effect large enough to matter for the mechanism is a finding."},
          {"trap": "IQR outliers on a bimodal signal", "fix": "Motor current and velocity are 0 when idle and high when running; detect_anomalies flags every active period unless its scope or windows exclude idle time. Pass windows from find_condition (the mechanism running), or scope enabled; pick thresholds for find_condition from the mechanism's normal operating range."},
          {"trap": "Wraparound spikes", "fix": "Headings and steer angles jump between -180 and 180 (or 0 and 360). Rotation fields of structs (/RealOutputs/Drive/Pose.rotation.value, .rotation._derived.degrees) are known angles: get_statistics, rate_of_change, find_peaks, detect_anomalies, and time_correlate unwrap them (get_statistics reports angle.wraps and circular statistics) and compare_entries takes the shortest difference. A heading logged as a plain double is not known to be an angle: check its range with get_statistics first; a jump of about 360 in one sample is a wrap, not motion. analyze_swerve handles module angles."},
          {"trap": "Concluding that struct data cannot be analyzed", "fix": "Numeric tools read struct fields and array elements by path: append it to the entry name (/RealOutputs/Drive/Pose.translation.x, /Vision/Camera0/PoseObservations[0].tagCount) or pass it as field. get_entry_info lists an entry's numeric_leaf_paths; a numeric tool given a struct entry lists them in its error. list_struct_types says whether a struct decodes by the log's own schema or an assumed layout (source assumed), which may not match the team's struct."},
          {"trap": "Reading across gaps", "fix": "A window with no samples of a periodic signal is unknown, not zero and not steady: when data_quality reports gaps overlapping the window of interest, say the claim is unsupported there. For a change_only signal (data_quality.sampling), a long interval is a hold (the value did not change), but statistics weigh samples, not time."},
          {"trap": "Cross-source timing from an unsynced revlog", "fix": "Call sync_status (or wait_for_sync) before claiming a revlog event preceded or followed a wpilog event. If the reported accuracy is coarser than the interval (a 200 ms dip with plus or minus 2 s sync), say the ordering cannot be determined; offer set_revlog_offset if the user knows a shared event. Stall current, temperature, and fault flags from the revlog remain valid without precise alignment."},
          {"trap": "Inferring the match result or score from telemetry", "fix": "Use get_tba_match_data or the tba field of list_available_logs; the log shows only the robot's own state."},
          {"trap": "Reading the end of a truncated log as the end of the match", "fix": "A match log shows the year's auto period (20 s in 2026, 15 s before), a short gap, and teleop (140 s in 2026, 135 s before) ending in a disable; get_game_info has the year's timing. If get_match_phases shows a phase that is short with no disable, or the log ends mid-phase, report 'log ends at Xs' and give per-phase statistics only for complete phases. analyze_cycles marks incomplete cycles; exclude them and say how many."},
          {"trap": "Reporting heuristic scores as measurements", "fix": "Health scores, brownout risk levels, and battery health estimates are heuristics. Report the underlying numbers they were computed from alongside them."},
          {"trap": "Implausible values taken at face value", "fix": "Battery above 14 V, a negative duration, a phase longer than get_game_info allows, a loop time over 1 s: suspect the query, the units (analyze_loop_timing auto-detects ms vs s), or the log before the robot."}
        ],
        "cross_match": [
          "One log is one sample. compare_matches shows that two matches differ, not why.",
          "compare_matches compares one signal across two logs with percentiles, and resolves scope (enabled, teleop, segment:<i>) in each log's own timeline, so pass scope 'enabled' rather than comparing whole logs: a whole-log max is often the multi-second boot loop, which it flags. For more than two logs, run get_statistics with the same name and scope on each and tabulate the results.",
          "Keep only FMS-connected match logs unless asked; exclude practice, pit, and replay (_sim) logs.",
          "Check get_code_metadata on each log; a different git SHA between matches is a confounder for any behavior change. Battery, alliance partners, and field position also change.",
          "Run the same tool with the same parameters and the same phase windows on every log before comparing.",
          "Report per-match values with n per match, then the pattern. 'Happened in 4 of 6 matches' is a pattern; 'happened once' is an event."
        ],
        "naming": {
          "advantagekit": "/SystemStats/BatteryVoltage, /SystemStats/BrownedOut, /PowerDistribution/ChannelCurrent (array), /PowerDistribution/TotalCurrent, /DriverStation/Enabled, /RealOutputs/<Subsystem>/..., /AdvantageKit/...",
          "wpilib_datalog": "DS:enabled, DS:autonomous, DS:test, DS:estop, DS:joystick0/...; NetworkTables entries prefixed NT:/ (for example NT:/SmartDashboard/...); battery and PDH data only if the team logged them (typically NT:/SmartDashboard/PowerDistribution[<CAN id>]/Voltage, TotalCurrent, and per-channel Chan<N>; the id is 1 for a REV PDH, 0 for a CTRE PDP). Phase, DS, and CAN tools recognize both /DriverStation/... and DS:... names; power_analysis and get_ds_timeline both use the PowerDistribution voltage when no BatteryVoltage entry exists.",
          "when_a_tool_finds_nothing": "If analyze_swerve, profile_mechanism, or analyze_cycles reports no matching entries, list the names you searched, run search_entries with the subsystem word (swerve, module, drive, elevator), and find the team's naming in the robot code or ask the user for it. Do not reconstruct the analysis from raw entries by hand.",
          "the_server_does_not_guess": "Tools pick an entry for a role (battery voltage, loop time, robot pose, vision pose, auto chooser, path poses, total current, swerve module states, has-target flags) only when it follows a well-known convention (AdvantageKit, WPILib, CTRE, PathPlanner, YAGSL, Limelight, PhotonVision names), is the only entry of its type, or is passed explicitly. profile_mechanism uses only entries passed explicitly: no convention says which entry is a mechanism's setpoint or measurement, or what its units are. Entries that match only by name are listed as candidates (resolve_signals: match heuristic, needs_confirmation; a tool's candidates, skipped reason, or no_match hint) and are not used. Establish which candidate is which from the robot's source code where the entry is logged, which is the only place that says what it holds and in which units; failing that from its values (get_entry_info, read_entry, get_statistics) or by asking the user. Then pass it with the parameter the reason names (voltage_entry, entry, pose_entry, chooser_entry, ...). Never assume the first candidate."
        },
        "units": "Battery voltage in V (12.0-13.2 V at rest is healthy). Currents in A; ChannelCurrent is an array indexed by channel, TotalCurrent is a scalar. analyze_loop_timing auto-detects ms vs s; a 20 ms nominal loop reported as 0.02 is seconds. Tool timestamps are the log's own clock in seconds (FPGA time, which starts at roboRIO boot, so the first sample is usually not at 0); take the real range from get_entry_info time_range_sec or get_match_phases, and do not rebase or convert by hand.",
        "report_format": {
          "pit": "Two to four sentences: what happened (event, time, entry, value); the most likely why, with confidence; the one thing to check before the next match. Caveats only if they change the action.",
          "deep_dive": "For each finding, ranked by evidence strength: Finding; Evidence (log, entry, phase or window, n, statistic); Alternatives considered and the observation that ruled them out; Confidence (high, medium, low) and why; Not determinable from telemetry (what needs physical inspection, code review, or another match); Next measurement.",
          "choose": "Use pit when the user mentions an upcoming match, time pressure, or asks a single yes/no question; otherwise deep_dive.",
          "example": "Finding: 4 of the 6 worst voltage events involved a 149 A stall on the intake or climber. Evidence: battery voltage crossings below 6.8 V from find_condition, per-channel peaks from power_analysis channel_analysis, intake current from read_entry in a 2 s window around each crossing, teleop only, n = 6 events. Alternatives: battery age (ruled out: voltage recovers fully between events); wiring (not testable from logs). Confidence: high that the stalls occur, the events are unambiguous; medium on cause, telemetry cannot distinguish mechanical binding from a control-loop issue. Not determinable from telemetry: the root cause of the stall; needs physical inspection. Next: get_statistics with the teleop window on the same entries in the other logs from this event."
        }
      }""";

  private static final JsonObject PRINCIPLES = parseStrict(PRINCIPLES_JSON);

  /**
   * Returns the long-form analysis principles as a fresh JSON object.
   *
   * @return A deep copy; callers may modify it freely
   */
  public static JsonObject analysisPrinciples() {
    return PRINCIPLES.deepCopy();
  }

  private static JsonObject parseStrict(String json) {
    var reader = new JsonReader(new StringReader(json));
    reader.setStrictness(Strictness.STRICT);
    return JsonParser.parseReader(reader).getAsJsonObject();
  }
}
