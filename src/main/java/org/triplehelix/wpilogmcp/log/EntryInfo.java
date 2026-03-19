package org.triplehelix.wpilogmcp.log;

/**
 * Metadata about a log entry.
 *
 * @param id The entry ID from the log file
 * @param name The entry name (e.g., "/robot/pose")
 * @param type The entry type (e.g., "double", "struct:Pose2d")
 * @param metadata Additional metadata from the log file
 * @since 0.4.0
 */
public record EntryInfo(int id, String name, String type, String metadata) {}
