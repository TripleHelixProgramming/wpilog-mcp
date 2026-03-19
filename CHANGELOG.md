# Changelog

All notable changes to wpilog-mcp will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

#### Architecture: LogManager Subsystem Extraction
- **Refactored LogManager** from 1438-line monolith into facade pattern with 6 specialized subsystems:
  - `LogCache`: LRU cache with thread-safe operations and eviction logic
  - `LogParser`: WPILOG file parsing delegating to struct decoder registry
  - `StructDecoderRegistry`: Extensible registry pattern replacing 500-line switch statement with Map-based decoder lookup
  - `SecurityValidator`: Path validation logic with traversal attack prevention
  - `MemoryEstimator`: Memory usage estimation for cache eviction decisions
  - `BinaryReader`: Binary reading utilities for struct decoding
- **Created 16 struct decoder classes** implementing `StructDecoder` interface for WPILib types:
  - Geometry: `Pose2dDecoder`, `Pose3dDecoder`, `Translation2dDecoder`, `Translation3dDecoder`, `Rotation2dDecoder`, `Rotation3dDecoder`, `Transform2dDecoder`, `Transform3dDecoder`, `Twist2dDecoder`, `Twist3dDecoder`
  - Kinematics: `ChassisSpeedsDecoder`, `SwerveModuleStateDecoder`, `SwerveModulePositionDecoder`, `DifferentialDriveWheelSpeedsDecoder`, `MecanumDriveWheelSpeedsDecoder`
  - Vision: `TargetObservationDecoder`, `PoseObservationDecoder`
  - Autonomous: `SwerveSampleDecoder`
- **Benefits**: Improved maintainability, extensibility for custom struct types, clearer separation of concerns, easier testing
- **Backward Compatibility**: All public APIs preserved - zero breaking changes

### Fixed
- **`list_loaded_logs`**: Now properly iterates cache and returns LoadedLogInfo records with memory estimates and active status
- **Tool Documentation**: Updated 6 tool descriptions to explicitly document expected "no data found" messages:
  - `analyze_swerve`: Documents "no swerve modules detected" message
  - `power_analysis`: Documents "no battery data found" message
  - `can_health`: Documents "no CAN data found" message
  - `get_code_metadata`: Documents "no code metadata found" message
  - `analyze_auto`: Documents "no auto period detected" message
  - `analyze_can_bus`: Documents "no CAN bus data found" message

### Added
- **Memory Monitoring**: New `getMemoryStats()` method in LogManager providing comprehensive heap statistics:
  - Estimated memory usage from cache (MB)
  - Actual JVM heap usage (used, max, free, utilization percentage)
  - Estimation accuracy ratio comparing heuristics to actual usage
  - Available via `health_check` tool for real-time monitoring
- **LogCache Iteration**: Added `getAllEntries()` method to LogCache for thread-safe cache enumeration
- **`health_check` tool enhancement**: Now includes estimation accuracy metric showing how well memory heuristics match actual heap usage

## [0.3.0] - 2026-03-19

### Added

#### New Tools (4)
- **`list_struct_types`**: Lists all supported WPILib struct types organized by category (geometry, kinematics, vision, autonomous)
- **`health_check`**: System health monitoring with JVM memory usage, cache memory estimate, loaded logs count, and TBA availability
- **`analyze_loop_timing`**: Real-time performance analysis detecting loop overruns > 20ms with jitter analysis and health assessment
- **`analyze_can_bus`**: CAN bus health monitoring with utilization analysis, TX/RX error tracking, and actionable recommendations

#### Enhanced Tools (4)
- **`profile_mechanism`**: Added stall detection (velocity < 0.01, current > threshold), settling time calculation, and overshoot percentage
- **`analyze_vision`**: Added pose jump detection to identify unreliable vision estimates that can cause odometry drift
- **`analyze_auto`**: Added path following RMSE calculation with max error tracking and typical value guidelines
- **`analyze_cycles`**: Added dead time analysis to identify idle periods between cycles with percentage of teleop

#### Core Improvements
- **Execution Time Tracking**: All tool responses now include `_execution_time_ms` field for performance monitoring
- **Intelligent Error Handling**:
  - Error classification with specific codes: `invalid_parameter` (IllegalArgumentException), `io_error` (IOException), `memory_error` (OutOfMemoryError)
  - "Did You Mean?" suggestions for misspelled tool names using Levenshtein distance algorithm
- **Logging**: Added structured logging for tool execution (success/failure) and error events
- **toLowerCase() Optimization**: Cached toLowerCase() results in hot loops for better performance

### Fixed
- **IQR Calculation**: Implemented proper linear percentile interpolation for accurate outlier detection (previously used simple array indexing)
- **Memory Estimation**: Improved accuracy by properly calculating struct array sizes and handling all data types
- **Incomplete Tool Implementations**:
  - `profile_mechanism`: Now fully implements stall detection, settling time, and overshoot calculations
  - `analyze_vision`: Now includes pose jump detection with configurable thresholds
  - `analyze_auto`: Now calculates path following RMSE between desired and actual poses
  - `analyze_cycles`: Now tracks dead time (idle periods) with configurable idle state

### Changed
- **Test Suite**: Added 22 new comprehensive unit tests covering all enhanced functionality
  - StatisticsTools: Percentile interpolation and IQR accuracy tests
  - FrcDomainTools: Stall detection, settling time, overshoot, pose jumps, path following, cycle analysis, loop timing, CAN bus tests
  - CoreTools: New tool tests for `list_struct_types` and `health_check`
  - McpServer: Error classification and "Did You Mean?" suggestion tests
- **Documentation**: Comprehensive updates to README.md and TOOLS.md with detailed usage examples, health assessment criteria, and troubleshooting guides
- **Tool Count**: Increased from 35 to 39 tools

### Performance
- Average tool execution time: 489ms (stress test with real robot logs)
- Concurrent operations: 1000 ops/sec throughput verified
- Cache eviction: LRU policy working correctly with configurable limits

## [0.2.1] - 2026-03-15

### Added
- Struct decoders for vision types (`TargetObservation`, `PoseObservation`)
- Struct decoders for autonomous types (`SwerveSample`)
- Expanded struct type support in LogManager

### Changed
- Enhanced README with additional usage examples
- Updated documentation for struct type decoding

### Fixed
- Team 2363 Triple Helix website link in README

## [0.2.0] - 2026-03-10

### Added
- **`moi_regression` tool**: Mechanism moment of inertia estimation from voltage/velocity/acceleration data
- Support for mechanism characterization and control system identification

### Changed
- Improved mechanism analysis capabilities

## [0.1.0] - 2026-03-01

### Added
- Initial release of wpilog-mcp MCP server
- 35 tools across 8 categories:
  - Core tools for log loading and entry reading
  - Multi-log management
  - Search and query tools
  - Statistical analysis (detect anomalies, find peaks, rate of change, correlation)
  - FRC-specific analysis (swerve, power, CAN health)
  - FRC domain tools (DS timeline, vision, mechanism profiling, auto, cycles, replay drift)
  - The Blue Alliance integration
  - Export tools (CSV, report generation)
- WPILib struct type decoding:
  - Geometry types (Pose2d/3d, Translation2d/3d, Rotation2d/3d, Transform2d/3d, Twist2d/3d)
  - Kinematics types (ChassisSpeeds, SwerveModuleState, SwerveModulePosition)
- LRU cache with configurable limits (by count or memory)
- TBA enrichment for match logs (scores, alliances, win/loss)
- Comprehensive test suite with unit and integration tests
- MCP protocol support via JSON-RPC over stdio

### Documentation
- Complete tool reference (TOOLS.md)
- Usage examples (EXAMPLE.md)
- Configuration guide for VS Code, Claude Code CLI, and Claude Desktop

[0.3.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/TripleHelixProgramming/wpilog-mcp/releases/tag/v0.1.0
