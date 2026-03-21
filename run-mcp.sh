#!/bin/bash

# =============================================================================
# wpilog-mcp Launcher
#
# Automatically locates the WPILib 2026 JDK and starts the MCP server.
#
# Environment variables:
#   WPILOG_MAX_HEAP  - Max JVM heap size (default: 4g). Examples: 2g, 8g, 512m
#   JAVA_HOME        - Override JDK location (WPILib 2026 JDK auto-detected)
# =============================================================================

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# --- JVM Configuration ---

MAX_HEAP="${WPILOG_MAX_HEAP:-4g}"

# --- JAR Discovery ---

# Find the fat JAR, preferring the most recently modified
JAR_PATH=""
for f in "${SCRIPT_DIR}"/build/libs/wpilog-mcp-*-all.jar; do
    if [[ -f "$f" ]]; then
        JAR_PATH="$f"
    fi
done

if [[ -z "$JAR_PATH" ]]; then
    echo "Error: Server JAR not found in ${SCRIPT_DIR}/build/libs/" >&2
    echo "Please run './gradlew buildMcp' first." >&2
    exit 1
fi

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

exec "${JAVA_EXEC}" -Xmx${MAX_HEAP} -jar "${JAR_PATH}" "$@"
