package org.triplehelix.wpilogmcp.tools;

import org.triplehelix.wpilogmcp.mcp.McpServer;

/**
 * MCP tool implementations for WPILOG analysis.
 *
 * <p>This class serves as the entry point for registering all WPILOG analysis tools with the MCP
 * server. Tools are organized into logical modules:
 *
 * <h2>Core Tools ({@link CoreTools})</h2>
 *
 * <ul>
 *   <li>{@code list_available_logs} - Browse logs in the configured directory
 *   <li>{@code load_log} - Load a WPILOG file for analysis
 *   <li>{@code list_entries} - List all entries in the active log
 *   <li>{@code get_entry_info} - Get detailed info about a specific entry
 *   <li>{@code read_entry} - Read values from an entry with pagination
 *   <li>{@code list_loaded_logs} - List all loaded logs and cache status
 *   <li>{@code set_active_log} - Switch between loaded logs
 *   <li>{@code unload_log} - Unload a specific log
 *   <li>{@code unload_all_logs} - Clear the log cache
 * </ul>
 *
 * <h2>Query Tools ({@link QueryTools})</h2>
 *
 * <ul>
 *   <li>{@code search_entries} - Search entries by type, name, or sample count
 *   <li>{@code get_types} - List all data types in the log
 *   <li>{@code find_condition} - Find when values cross thresholds
 *   <li>{@code search_strings} - Search string entries for patterns
 * </ul>
 *
 * <h2>Statistics Tools ({@link StatisticsTools})</h2>
 *
 * <ul>
 *   <li>{@code get_statistics} - Compute statistics on numeric entries
 *   <li>{@code compare_entries} - Compare two entries (e.g., RealOutputs vs ReplayOutputs)
 *   <li>{@code detect_anomalies} - Find outliers using IQR method
 *   <li>{@code find_peaks} - Find local maxima/minima
 *   <li>{@code rate_of_change} - Compute derivatives
 *   <li>{@code time_correlate} - Compute correlation between entries
 * </ul>
 *
 * <h2>Robot Analysis Tools ({@link RobotAnalysisTools})</h2>
 *
 * <ul>
 *   <li>{@code get_match_phases} - Get auto/teleop/endgame time ranges
 *   <li>{@code analyze_swerve} - Analyze swerve drive performance
 *   <li>{@code power_analysis} - Analyze battery and current data
 *   <li>{@code can_health} - Check for CAN bus errors
 *   <li>{@code compare_matches} - Compare entries across multiple logs
 *   <li>{@code get_code_metadata} - Extract Git info and build metadata
 *   <li>{@code moi_regression} - OLS regression for moment of inertia and damping from velocity/current logs
 * </ul>
 *
 * <h2>FRC Domain-Specific Analysis Tools ({@link FrcDomainTools})</h2>
 *
 * <ul>
 *   <li>{@code get_ds_timeline} - DriverStation event timeline
 *   <li>{@code analyze_vision} - Vision system reliability analysis
 *   <li>{@code profile_mechanism} - Mechanism health and tuning
 *   <li>{@code analyze_auto} - Autonomous routine profiling
 *   <li>{@code analyze_cycles} - Game piece cycle time analysis
 *   <li>{@code analyze_replay_drift} - AdvantageKit replay validation
 * </ul>
 *
 * <h2>Export Tools ({@link ExportTools})</h2>
 *
 * <ul>
 *   <li>{@code export_csv} - Export entry data to CSV
 *   <li>{@code generate_report} - Generate comprehensive match report
 * </ul>
 *
 * <h2>TBA Tools ({@link TbaTools})</h2>
 *
 * <ul>
 *   <li>{@code get_tba_status} - Get The Blue Alliance API integration status
 * </ul>
 *
 * <h2>RevLog Tools ({@link RevLogTools})</h2>
 *
 * <ul>
 *   <li>{@code list_revlog_signals} - List available REV signals with sync status
 *   <li>{@code get_revlog_data} - Query REV signal data with FPGA timestamps
 *   <li>{@code sync_status} - Get synchronization confidence and details
 * </ul>
 *
 * @see McpServer.Tool
 * @see CoreTools
 * @see QueryTools
 * @see StatisticsTools
 * @see RobotAnalysisTools
 * @see FrcDomainTools
 * @see ExportTools
 * @see TbaTools
 * @see RevLogTools
 */
public final class WpilogTools {

  private WpilogTools() {}

  /**
   * Registers all WPILOG tools with the MCP server.
   *
   * <p>This method should be called once during server startup. It delegates to each tool module's
   * registration method.
   *
   * @param server The MCP server to register tools with
   */
  public static void registerAll(McpServer server) {
    CoreTools.registerAll(server);
    QueryTools.registerAll(server);
    StatisticsTools.registerAll(server);
    RobotAnalysisTools.registerAll(server);
    FrcDomainTools.registerAll(server);
    ExportTools.registerAll(server);
    TbaTools.registerAll(server);
    RevLogTools.registerAll(server);
  }
}
