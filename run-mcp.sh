#!/bin/bash

# =============================================================================
# wpilog-mcp Launcher
#
# Automatically locates the WPILib 2026 JDK and starts the MCP server.
# =============================================================================

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
JAR_PATH="${SCRIPT_DIR}/build/libs/wpilog-mcp-0.1.0-all.jar"

# --- JDK Discovery Logic ---

# 1. Check if JAVA_HOME is already set to a WPILib 2026 JDK
if [[ -n "$JAVA_HOME" && "$JAVA_HOME" == *"wpilib/2026/jdk"* ]]; then
    JAVA_EXEC="${JAVA_HOME}/bin/java"
fi

# 2. Check standard installation paths for macOS/Linux
if [[ -z "$JAVA_EXEC" ]]; then
    WPILIB_JDK_PATH="${HOME}/wpilib/2026/jdk/bin/java"
    if [[ -f "$WPILIB_JDK_PATH" ]]; then
        JAVA_EXEC="$WPILIB_JDK_PATH"
    fi
fi

# 3. Fallback to system java
if [[ -z "$JAVA_EXEC" ]]; then
    JAVA_EXEC="java"
fi

# --- Execution ---

if [[ ! -f "$JAR_PATH" ]]; then
    echo "Error: Server JAR not found at ${JAR_PATH}" >&2
    echo "Please run './gradlew buildMcp' first." >&2
    exit 1
fi

exec "${JAVA_EXEC}" -jar "${JAR_PATH}" "$@"
