@echo off
setlocal

:: ============================================================================
:: wpilog-mcp Launcher (Windows)
::
:: Automatically locates the WPILib 2026 JDK and starts the MCP server.
:: ============================================================================

set SCRIPT_DIR=%~dp0
set JAR_PATH=%SCRIPT_DIR%build\libs\wpilog-mcp-0.1.0-all.jar

:: --- JDK Discovery Logic ---

:: 1. Check if JAVA_HOME is already set to a WPILib 2026 JDK
if defined JAVA_HOME (
    echo %JAVA_HOME% | findstr /i "wpilib\2026\jdk" >nul
    if %errorlevel% equ 0 (
        set JAVA_EXEC="%JAVA_HOME%\bin\java.exe"
    )
)

:: 2. Check standard installation path for Windows
if not defined JAVA_EXEC (
    set WPILIB_JDK_PATH=C:\Users\Public\wpilib\2026\jdk\bin\java.exe
    if exist "%WPILIB_JDK_PATH%" (
        set JAVA_EXEC="%WPILIB_JDK_PATH%"
    )
)

:: 3. Fallback to system java
if not defined JAVA_EXEC (
    set JAVA_EXEC=java
)

:: --- Execution ---

if not exist "%JAR_PATH%" (
    echo Error: Server JAR not found at %JAR_PATH% >&2
    echo Please run '.\gradlew.bat buildMcp' first. >&2
    exit /b 1
)

%JAVA_EXEC% -jar "%JAR_PATH%" %*
