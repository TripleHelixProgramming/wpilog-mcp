#!/bin/sh
# wpilog-mcp installer for macOS/Linux
# Usage: curl -fsSL https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.sh | sh
set -e

REPO="TripleHelixProgramming/wpilog-mcp"
INSTALL_DIR="$HOME/.wpilog-mcp"

echo ""
echo "========================================"
echo "  wpilog-mcp Installer"
echo "========================================"
echo ""

# --- Find Java ---

find_java() {
    # 1. WPILib JDK, newest four-digit year; globbing preserves spaces in the home folder.
    wpilib_java=""
    for year in "$HOME"/wpilib/[0-9][0-9][0-9][0-9]; do
        if [ -x "$year/jdk/bin/java" ]; then
            wpilib_java="$year/jdk/bin/java"
        fi
    done
    if [ -n "$wpilib_java" ]; then
        echo "Found WPILib JDK: $wpilib_java" >&2
        echo "$wpilib_java"
        return 0
    fi

    # 2. JAVA_HOME
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "Found JAVA_HOME: $JAVA_HOME/bin/java" >&2
        echo "$JAVA_HOME/bin/java"
        return 0
    fi

    # 3. System PATH
    if command -v java >/dev/null 2>&1; then
        echo "Found java on PATH: $(command -v java)" >&2
        command -v java
        return 0
    fi

    echo "ERROR: Java 17+ is required but not found." >&2
    echo "Install the WPILib toolkit: https://docs.wpilib.org/en/stable/docs/zero-to-robot/step-2/wpilib-setup.html" >&2
    exit 1
}

JAVA_EXEC=$(find_java)

# Verify Java version
JAVA_VERSION=$("$JAVA_EXEC" -version 2>&1 | head -1)
echo "Java version: $JAVA_VERSION"
echo ""

# --- Download latest release ---

echo "Fetching latest release from GitHub..."
RELEASE_JSON=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" -H "User-Agent: wpilog-mcp-installer")
VERSION=$(echo "$RELEASE_JSON" | grep '"tag_name"' | sed 's/.*"v\([^"]*\)".*/\1/')
JAR_URL=$(echo "$RELEASE_JSON" | grep '"browser_download_url".*-all\.jar"' | sed 's/.*"\(https[^"]*\)".*/\1/')

if [ -z "$JAR_URL" ]; then
    echo "ERROR: No JAR asset found in latest release" >&2
    exit 1
fi

# The version names files below, so it must look like one
case "$VERSION" in
    ""|*[!0-9A-Za-z.-]*)
        echo "ERROR: The latest release's tag is not a version: $VERSION" >&2
        exit 1
        ;;
esac

echo "Latest version: $VERSION"
echo ""

# The JAR owns the install layout and prints the PATH hint. Never download over an installed JAR.
TEMP_JAR=$(mktemp "${TMPDIR:-/tmp}/wpilog-mcp.XXXXXX")
trap 'rm -f "$TEMP_JAR"' EXIT HUP INT TERM
curl -fsSL -o "$TEMP_JAR" "$JAR_URL"
"$JAVA_EXEC" -jar "$TEMP_JAR" install --install-dir "$INSTALL_DIR"
