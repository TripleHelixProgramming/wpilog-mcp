# Claude Code Project Instructions

This document provides context and coding guidelines for Claude Code when working on wpilog-mcp.

## Project Overview

**wpilog-mcp** is a Java 17+ MCP server that parses WPILib robot telemetry logs (binary `.wpilog` format) and REV motor controller logs (`.revlog`), providing 49 analysis tools for FRC (FIRST Robotics Competition) diagnostics via JSON-RPC 2.0 over stdio.

The codebase is ~17,000 lines of production Java and ~17,000 lines of tests (850+ test cases).

## Package Structure

- `cache/` — Persistent disk cache (MessagePack serialization, content fingerprinting, CRC-32 integrity, atomic writes)
- `game/` — Year-specific FRC game knowledge base (match timing, scoring, field geometry)
- `log/` — Log loading, binary parsing, LRU caching with memory estimation, struct decoding
- `mcp/` — MCP JSON-RPC 2.0 protocol implementation over stdio
- `revlog/` — REV Hardware Client `.revlog` parsing, DBC-based CAN signal decoding
- `sync/` — Cross-correlation timestamp synchronization between wpilog and revlog (async background processing)
- `tba/` — The Blue Alliance API integration with caching
- `tools/` — 49 MCP tools: statistics, FRC domain analysis, swerve, power, CAN, vision, cycle detection, battery health, MoI regression, loop timing, game info, export, plus LLM epistemological guardrails

## FRC Domain Knowledge

When working with FRC-specific code, keep these domain constants in mind:

- **Brownout thresholds**: 6.8V for roboRIO 1, 6.3V for roboRIO 2
- **Match phases**: Derived from DriverStation mode transitions (Enabled + Autonomous booleans). Handle FMS disabled gap between auto and teleop, practice mode without FMS, missing DS data.
- **Loop timing**: Auto-detect units (ms vs s) via median heuristic. Typical robot loop is 20ms (50Hz).
- **Swerve modules**: 4 modules (FL, FR, BL, BR) with drive velocity and steer angle per module.
- **CAN bus**: Distinguish disabled-state timeouts (normal) from enabled-state errors (problematic).

## Mathematical Rigor

This codebase performs numerical analysis on telemetry data. Maintain high standards for floating-point correctness:

- **Statistics**: Use Bessel's correction for sample standard deviation (n-1 denominator). Handle zero variance, single data points, all-NaN arrays.
- **Percentiles**: Correct interpolation at boundary conditions (0th, 100th percentile).
- **Derivatives**: Handle non-uniform timestamp spacing. Use appropriate smoothing windows.
- **Correlation**: Include sample sizes and p-values. Always caveat that correlation does not imply causation.
- **Edge cases**: Always handle empty datasets, single elements, NaN/Infinity propagation, division by zero, duplicate timestamps.

## LLM Epistemological Guardrails

This codebase implements strategies to prevent LLM overconfidence when interpreting telemetry data. When adding or modifying tools, follow these patterns:

### Primitive Tool Design
Return raw statistics (mean, std, percentiles, sample sizes, p-values) rather than pre-computed interpretations. Let the LLM synthesize across multiple tool calls rather than providing monolithic "health scores."

### Data Quality Metadata
Use `DataQuality.fromValues()` to compute quality scores and attach them to analysis results. Quality metrics include sample count, gap analysis, NaN ratio, and jitter.

### Analysis Directives
Use `AnalysisDirectives.fromQuality()` to generate `server_analysis_directives` blocks with:
- Confidence level (high/medium/low/insufficient) calibrated to data quality
- Guidance text using epistemic language ("suggests", "may indicate", "consistent with")
- Follow-up suggestions for additional analysis
- Single-match limitation warnings

### Tool Description Guidance
Embed interpretation guidance in tool descriptions ("Trojan horse" pattern). Include:
- Sample size considerations
- Correlation vs causation caveats
- Single-match limitations
- Appropriate uncertainty language

### Confidence Calibration
- Don't claim "high confidence" with < 100 samples or > 10% data gaps
- Edge cases (single data points, high jitter, many NaNs) require reduced confidence
- Always warn that single-match analysis may not generalize

## Tool Architecture

When adding new tools, follow existing patterns:

- **`ToolBase`**: Base class for simple tools. Override `execute()`.
- **`LogRequiringTool`**: For tools that need a loaded log. Override `executeWithLog()`.
- **`ResponseBuilder`**: Use for consistent response formatting with data quality and directives.
- **`ToolUtils`**: Shared utilities for common operations.

Tools are registered in their respective modules (`CoreTools`, `StatisticsTools`, `FrcDomainTools`, `RobotAnalysisTools`, `WpilogTools`, `RevLogTools`).

## Testing Standards

This codebase must be rock solid. Maintain exhaustive test coverage:

- **Edge cases**: Empty inputs, single elements, boundary values, null/missing data, NaN/Infinity, zero values, negative values, duplicates, malformed inputs.
- **Mathematical edge cases**: Zero variance, single data points, all-NaN arrays, percentiles at 0/100, regression with collinear data, correlation with constant signals.
- **Error paths**: Exception-throwing code paths must be tested.
- **Stress test currency**: When adding new tools, add corresponding scenarios to `StressTest.java`.

## Version Management

`Version.java` is the single source of truth for version numbers. Update it when releasing new versions. The version is used by `Main`, `DiskCache`, and `health_check`.

## Security

`SecurityValidator` prevents path traversal with symlink resolution. When handling file paths, always validate through this class.

## Documentation

When modifying tools, keep documentation in sync:
- Tool descriptions in Java code (shown to LLM agents via MCP)
- `TOOLS.md` for human reference
- `CHANGELOG.md` for release notes
