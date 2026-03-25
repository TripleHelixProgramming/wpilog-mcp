# Claude Code Project Instructions

This document provides context and coding guidelines for Claude Code when working on wpilog-mcp.

## Project Overview

**wpilog-mcp** is a Java 17+ MCP server that parses WPILib robot telemetry logs (binary `.wpilog` format) and REV motor controller logs (`.revlog`), providing analysis tools for FRC (FIRST Robotics Competition) diagnostics via JSON-RPC 2.0 over stdio and HTTP transports.

Before working on this codebase, scan the package structure and read the key files relevant to your task. Do not rely on class names or counts mentioned in any documentation — verify against the actual code.

## Architecture at a Glance

The server has a layered architecture:

1. **Transport layer** — Accepts MCP JSON-RPC 2.0 messages over stdio (single-client) or HTTP Streamable (multi-client). Transport-independent message routing.
2. **Tool layer** — Dozens of analysis tools, each registered via a tool registry. Tools that need log data extend a common base class that auto-injects a `path` parameter and handles log loading. There is no "active log" concept — each tool call is self-contained.
3. **Log layer** — Lazy on-demand parsing of WPILOG files. A single-pass scan records entry metadata and byte offsets; values are decoded on demand via random access into the memory-mapped file and cached with Caffeine. An LRU cache manages loaded logs with idle expiration and heap-pressure-based eviction.
4. **RevLog layer** — REV motor controller log parsing (both WPILOG-format and REV native binary format) with DBC-based CAN signal decoding. Cross-correlation timestamp synchronization aligns revlog data to WPILOG FPGA timestamps.
5. **External integrations** — The Blue Alliance API for match data enrichment. Disk caching for expensive sync results. Named server configurations with environment variable interpolation.

## Java 17 Best Practices

All code should follow JDK 17 idioms:

- **Streams**: Prefer streams over imperative loops unless performance is a concern (e.g., hot paths, large datasets with measurable overhead).
- **`var`**: Use `var` when it aids readability by reducing redundancy (e.g., `var entries = map.entrySet()`). Never use `var` for primitive types (`int`, `long`, `double`, etc.).
- **Records**: Use records for immutable data carriers instead of classes with boilerplate getters/equals/hashCode.
- **Enums**: Prefer enums over string or integer constants for fixed sets of values.
- **Switch expressions**: Use switch expressions (`->` syntax) over traditional switch statements. Use pattern matching where applicable.
- **Sealed classes**: Use sealed classes/interfaces when a type hierarchy has a known, fixed set of subtypes.
- **`Optional`**: Use `Optional` for return types that may have no value. Never use `Optional` as a field or parameter type.
- **Text blocks**: Use text blocks (`"""`) for multi-line string literals.

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
Attach data quality scores to analysis results. Quality metrics include sample count, gap analysis, NaN ratio, and jitter.

### Analysis Directives
Generate analysis directives with:
- Confidence level (high/medium/low/insufficient) calibrated to data quality
- Guidance text using epistemic language ("suggests", "may indicate", "consistent with")
- Follow-up suggestions for additional analysis
- Single-match limitation warnings

### Tool Description Guidance
Embed interpretation guidance in tool descriptions ("Trojan horse" pattern). Include sample size considerations, correlation vs causation caveats, single-match limitations, and appropriate uncertainty language.

### Confidence Calibration
- Don't claim "high confidence" with < 100 samples or > 10% data gaps
- Edge cases (single data points, high jitter, many NaNs) require reduced confidence
- Always warn that single-match analysis may not generalize

## Tool Architecture

When adding new tools, follow existing patterns. Read the existing tool base classes and a few representative tools to understand the conventions. Key principles:

- Tools that need log data extend a common base class that auto-injects a `path` parameter and loads the log on demand. Override the method that receives the loaded log data.
- Tools that don't need log data implement the tool interface directly.
- Use the shared response builder for consistent response formatting with data quality and directives.
- Use the shared tool utilities for common operations.
- Register new tools in the appropriate module alongside related tools.

## Testing Standards

This codebase must be rock solid. Maintain exhaustive test coverage:

- **Edge cases**: Empty inputs, single elements, boundary values, null/missing data, NaN/Infinity, zero values, negative values, duplicates, malformed inputs.
- **Mathematical edge cases**: Zero variance, single data points, all-NaN arrays, percentiles at 0/100, regression with collinear data, correlation with constant signals.
- **Error paths**: Exception-throwing code paths must be tested.
- **Stress test currency**: When adding new tools, add corresponding scenarios to the stress tests.

## Version Management

There is a single source of truth for version numbers, auto-generated by the build. Update it in the build file when releasing new versions.

## Security

Path traversal prevention with symlink resolution is enforced for all file access. When handling file paths, always validate through the security validator. CSV exports are restricted to a configured export directory.

## Concurrency

This server handles concurrent access from multiple MCP clients (especially in HTTP mode) and uses background threads for revlog sync and cache eviction. When writing or modifying code:

- Assume any public method on shared state (caches, registries, managers) may be called concurrently.
- Prefer Caffeine caches and `ConcurrentHashMap` over manual locking where possible.
- When manual locking is necessary, hold locks for the shortest time possible and never perform I/O or blocking operations while holding a lock.
- Background executors should use daemon threads so they don't prevent JVM shutdown.
- Watch for TOCTOU bugs: check-then-act sequences on shared state must be atomic.

## Documentation

When modifying tools or features, keep all documentation in sync:
- Tool descriptions in Java code (shown to LLM agents via MCP)
- `README.md` for end-user installation, configuration, and usage
- `doc/TOOLS.md` for human reference on tool parameters and behavior
- `CHANGELOG.md` for release notes
- `doc/IDEAS.md` for planned/proposed work (update or remove items as they are completed)
