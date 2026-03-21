@echo off
setlocal enabledelayedexpansion

:: ============================================================================
:: wpilog-mcp Launcher (Windows)
::
:: Automatically locates the WPILib 2026 JDK and starts the MCP server.
::
:: Environment variables:
::   WPILOG_MAX_HEAP  - Max JVM heap size (default: 4g). Examples: 2g, 8g, 512m
::   JAVA_HOME        - Override JDK location (WPILib 2026 JDK auto-detected)
:: ============================================================================

set SCRIPT_DIR=%~dp0

:: --- JVM Configuration ---

if not defined WPILOG_MAX_HEAP set WPILOG_MAX_HEAP=4g
set MAX_HEAP=%WPILOG_MAX_HEAP%

:: --- JAR Discovery ---

set JAR_PATH=
for %%f in ("%SCRIPT_DIR%build\libs\wpilog-mcp-*-all.jar") do (
    set JAR_PATH=%%f
)

if not defined JAR_PATH (
    echo Error: Server JAR not found in %SCRIPT_DIR%build\libs\ >&2
    echo Please run '.\gradlew.bat buildMcp' first. >&2
    exit /b 1
)

:: --- JDK Discovery Logic ---

:: 1. Check if JAVA_HOME is already set to a WPILib 2026 JDK
if defined JAVA_HOME (
    echo %JAVA_HOME% | findstr /i "wpilib\2026\jdk" >nul
    if !errorlevel! equ 0 (
        set JAVA_EXEC="%JAVA_HOME%\bin\java.exe"
    )
)

:: 2. Check standard installation path for Windows
if not defined JAVA_EXEC (
    set WPILIB_JDK_PATH=C:\Users\Public\wpilib\2026\jdk\bin\java.exe
    if exist "!WPILIB_JDK_PATH!" (
        set JAVA_EXEC="!WPILIB_JDK_PATH!"
    )
)

:: 3. Fallback to system java
if not defined JAVA_EXEC (
    set JAVA_EXEC=java
)

:: --- Execution ---

%JAVA_EXEC% -Xmx%MAX_HEAP% -jar "%JAR_PATH%" %*
