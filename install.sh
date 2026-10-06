#!/bin/sh
# wpilog-mcp installer for macOS/Linux
# Usage: curl -fsSL https://raw.githubusercontent.com/TripleHelixProgramming/wpilog-mcp/main/install.sh | sh
set -e

REPO="TripleHelixProgramming/wpilog-mcp"
INSTALL_DIR="$HOME/.wpilog-mcp"
tag=""
pre_release=false
extension=""
interactive=auto
has_logdir=false
has_team=false
advanced=false

# Rotate forwarded arguments to the end; never split or eval a user's path.
remaining=$#
while [ "$remaining" -gt 0 ]; do
    flag=$1
    shift
    remaining=$((remaining - 1))
    case "$flag" in
        --tag|--install-dir|--logdir|--team)
            if [ "$remaining" -eq 0 ] || [ -z "$1" ]; then
                echo "ERROR: Missing value for $flag" >&2; exit 2
            fi
            value=$1; shift; remaining=$((remaining - 1))
            case "$flag" in
                --tag) tag=$value ;;
                --install-dir) INSTALL_DIR=$value; advanced=true ;;
                --logdir) set -- "$@" "$flag" "$value"; has_logdir=true; advanced=true ;;
                --team) set -- "$@" "$flag" "$value"; has_team=true; advanced=true ;;
            esac ;;
        --pre-release) pre_release=true ;;
        --with-extension) extension=yes; advanced=true ;;
        --without-extension) extension=no ;;
        --interactive) interactive=yes ;;
        --non-interactive) interactive=no ;;
        --refresh|--force) set -- "$@" "$flag"; advanced=true ;;
        *) echo "ERROR: Unknown installer argument: $flag" >&2; exit 2 ;;
    esac
done
if [ -n "$tag" ] && [ "$pre_release" = true ]; then
    echo "ERROR: Choose --tag or --pre-release, not both." >&2; exit 2
fi
case "$tag" in *[!0-9A-Za-z.-]*) echo "ERROR: Invalid release tag: $tag" >&2; exit 2 ;; esac

# Reading /dev/tty also works with curl | sh, whose stdin contains this script.
if [ "$interactive" = auto ]; then
    interactive=no
    if [ -t 1 ] && (exec 3</dev/tty) 2>/dev/null; then interactive=yes; fi
fi
if [ "$interactive" = yes ]; then
    if [ -t 1 ] && (exec 3</dev/tty) 2>/dev/null; then exec 3</dev/tty; else exec 3<&0; fi
    if [ -f "$INSTALL_DIR/servers.yaml" ] || [ -f "$INSTALL_DIR/servers.json" ]; then
        echo "Keeping your existing server settings, including during --refresh."
    else
        if [ "$has_logdir" = false ]; then
            printf 'Log directory [%s/riologs]: ' "$HOME"
            IFS= read -r answer <&3 || answer=""
            set -- "$@" --logdir "${answer:-$HOME/riologs}"
            while :; do
                printf 'Another log directory (Enter to finish): '
                IFS= read -r answer <&3 || answer=""
                [ -n "$answer" ] || break
                set -- "$@" --logdir "$answer"
            done
        fi
        if [ "$has_team" = false ]; then
            printf 'Team number (Enter to leave unset): '
            IFS= read -r answer <&3 || answer=""
            if [ -n "$answer" ]; then set -- "$@" --team "$answer"; fi
        fi
    fi
    if [ -z "$extension" ]; then
        printf 'Install the matching VS Code extension? [Y/n]: '
        IFS= read -r answer <&3 || answer=""
        case "$answer" in n|N|no|No) extension=no ;; *) extension=yes; advanced=true ;; esac
    fi
    exec 3<&-
fi

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

# A tag selects exactly one release; the first public release includes prereleases.
api="https://api.github.com/repos/$REPO/releases/latest"
if [ -n "$tag" ]; then api="https://api.github.com/repos/$REPO/releases/tags/$tag"; fi
if [ "$pre_release" = true ]; then api="https://api.github.com/repos/$REPO/releases?per_page=1"; fi
RELEASE_JSON=$(curl -fsSL "$api" -H "User-Agent: wpilog-mcp-installer")
VERSION=$(printf '%s\n' "$RELEASE_JSON" | grep '"tag_name"' | head -1 | sed 's/.*"v\([^" ]*\)".*/\1/')
JAR_URL=$(printf '%s\n' "$RELEASE_JSON" | grep '"browser_download_url".*-all\.jar"' | head -1 | sed 's/.*"\(https[^" ]*\)".*/\1/')
VSIX_URL=$(printf '%s\n' "$RELEASE_JSON" | grep '"browser_download_url".*\.vsix"' | head -1 | sed 's/.*"\(https[^" ]*\)".*/\1/')
case "$VERSION" in ""|*[!0-9A-Za-z.-]*) echo "ERROR: Release tag is not a version: $VERSION" >&2; exit 1 ;; esac
if [ -z "$JAR_URL" ]; then echo "ERROR: No JAR asset found in release $VERSION" >&2; exit 1; fi
if [ "$extension" = yes ] && [ -z "$VSIX_URL" ]; then
    echo "ERROR: No VSIX asset found in release $VERSION" >&2; exit 1
fi
echo "Installing release $VERSION"

# One scratch directory owns every download, including a completely fetched fallback script.
scratch=$(mktemp -d "${TMPDIR:-/tmp}/wpilog-install.XXXXXX")
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
TEMP_JAR="$scratch/server.jar"
curl -fsSL -o "$TEMP_JAR" "$JAR_URL"
if [ "$extension" = yes ]; then
    curl -fsSL -o "$scratch/extension.vsix" "$VSIX_URL"
    set -- "$@" --with-extension --vsix "$scratch/extension.vsix"
fi
if "$JAVA_EXEC" -jar "$TEMP_JAR" install --install-dir "$INSTALL_DIR" "$@" 2>"$scratch/error"; then
    cat "$scratch/error" >&2
    echo "Install path: release $VERSION install command."
else
    install_status=$?
    cat "$scratch/error" >&2
    # An actual install error must not trigger a second install or hide a failed refresh.
    if ! grep -q 'Unknown option' "$scratch/error"; then exit "$install_status"; fi
    if [ "$WPILOG_INSTALL_FALLBACK" = "1" ]; then
        echo "ERROR: Release installer fallback already attempted (exit $install_status)." >&2
        exit "$install_status"
    fi
    if [ "$advanced" = true ]; then
        echo "ERROR: Release $VERSION predates the install command and cannot honor these options. Choose a newer --tag." >&2
        exit "$install_status"
    fi
    INSTALLER_URL="https://raw.githubusercontent.com/$REPO/v$VERSION/install.sh"
    echo "Install path: release $VERSION predates the install command; using $INSTALLER_URL."
    curl -fsSL -o "$scratch/installer.sh" "$INSTALLER_URL"
    WPILOG_INSTALL_FALLBACK=1 sh < "$scratch/installer.sh"
fi
